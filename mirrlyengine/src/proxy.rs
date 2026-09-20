use crate::cfproxy::*;
use crate::config::*;
use crate::crypto::*;
use crate::ws::*;
use crate::{ldebug, linfo, lwarn};
use byteorder::{ByteOrder, LittleEndian};
use rand::RngCore;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::atomic::{AtomicI32, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Mutex;
use tokio_util::sync::CancellationToken;

// ---------------------------------------------------------------------------
// Target resolution
// ---------------------------------------------------------------------------

pub fn resolve_configured_target(dc: i32, is_media: bool) -> Option<String> {
    let map = DC_OPT.read();
    if is_media {
        if let Some(t) = map.get(&(-dc)) {
            if !t.is_empty() {
                return Some(t.clone());
            }
        }
        Some(get_dc_target_ip(dc, true).to_string())
    } else {
        if let Some(t) = map.get(&dc) {
            if !t.is_empty() {
                return Some(t.clone());
            }
        }
        Some(get_dc_target_ip(dc, false).to_string())
    }
}

pub fn resolve_fallback_target(dc: i32, is_media: bool) -> String {
    get_dc_target_ip(dc, is_media).to_string()
}

pub fn ws_domains(dc: i32, _is_media: bool) -> Vec<String> {
    let mut effective_dc = dc;
    if let Some(o) = DC_OVERRIDES.get(&dc) {
        effective_dc = *o;
    }
    vec![
        format!("kws{}.web.telegram.org", effective_dc),
        format!("kws{}-1.web.telegram.org", effective_dc),
    ]
}

pub fn media_tag(is_media: bool) -> &'static str {
    if is_media {
        "m"
    } else {
        ""
    }
}

pub fn is_media_int(b: bool) -> i32 {
    if b {
        1
    } else {
        0
    }
}

// ---------------------------------------------------------------------------
// WsPool
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub struct DcSlot {
    pub dc: i32,
    pub is_media: i32,
}

pub struct PoolEntry {
    pub ws: Arc<RawWebSocket>,
    pub domain: String,
    pub created: i64,
}

pub struct SlotState {
    pub queue: Mutex<std::collections::VecDeque<PoolEntry>>,
    pub refilling: AtomicI32,
}

#[derive(Debug, Clone, Copy)]
pub struct SlotDemand {
    pub last_requested: Instant,
    pub request_count: u64,
    pub useful_rx_count: u64,
}

pub struct WsPool {
    slots: Mutex<HashMap<DcSlot, Arc<SlotState>>>,
    demand: Arc<parking_lot::RwLock<HashMap<DcSlot, SlotDemand>>>,
    cancel_token: CancellationToken,
    cancel_refill: Arc<parking_lot::RwLock<CancellationToken>>,
    generation: AtomicU64,
}

impl WsPool {
    pub fn new(cancel_token: CancellationToken) -> WsPool {
        let cancel_refill = Arc::new(parking_lot::RwLock::new(cancel_token.child_token()));
        WsPool {
            slots: Mutex::new(HashMap::new()),
            demand: Arc::new(parking_lot::RwLock::new(HashMap::new())),
            cancel_token,
            cancel_refill,
            generation: AtomicU64::new(0),
        }
    }

    pub fn record_demand(&self, dc: i32, is_media: bool) {
        let slot = DcSlot {
            dc,
            is_media: is_media_int(is_media),
        };
        let mut map = self.demand.write();
        let entry = map.entry(slot).or_insert(SlotDemand {
            last_requested: Instant::now(),
            request_count: 0,
            useful_rx_count: 0,
        });
        entry.last_requested = Instant::now();
        entry.request_count += 1;
        ldebug!(
            "WsPool: demand recorded for DC{}{} (count={})",
            dc,
            media_tag(is_media),
            entry.request_count
        );
    }

    pub fn record_useful_rx(&self, dc: i32, is_media: bool) {
        let slot = DcSlot {
            dc,
            is_media: is_media_int(is_media),
        };
        let mut map = self.demand.write();
        if let Some(entry) = map.get_mut(&slot) {
            entry.useful_rx_count += 1;
        }
    }

    pub fn is_slot_demanded(&self, slot: DcSlot) -> bool {
        let map = self.demand.read();
        if let Some(entry) = map.get(&slot) {
            entry.last_requested.elapsed() < Duration::from_secs(600)
        } else {
            false
        }
    }

    pub fn target_size(&self, slot: DcSlot) -> usize {
        let is_mobile = MOBILE_NETWORK.load(Ordering::Relaxed);
        if is_mobile {
            // Cellular profile: strictly demand-driven.
            // If slot has not been demanded by actual client traffic, target is 0.
            // If demanded, exactly 1 standby socket to keep resource footprint minimal.
            if self.is_slot_demanded(slot) {
                1
            } else {
                0
            }
        } else {
            // Wi-Fi profile:
            let requested = MTPROTO_STANDBY_PER_ACTIVE_SLOT_REQUESTED.load(Ordering::Relaxed);
            let configured = effective_mtproto_standby(requested, false);
            if self.is_slot_demanded(slot) {
                let map = self.demand.read();
                let useful = map.get(&slot).map(|d| d.useful_rx_count).unwrap_or(0);
                if useful >= 2 {
                    configured
                } else {
                    1
                }
            } else if slot.dc == 2 && slot.is_media == 0 {
                1
            } else {
                0
            }
        }
    }

    pub async fn get_slot(&self, slot: DcSlot) -> Arc<SlotState> {
        let mut map = self.slots.lock().await;
        map.entry(slot)
            .or_insert_with(|| {
                Arc::new(SlotState {
                    queue: Mutex::new(std::collections::VecDeque::with_capacity(16)),
                    refilling: AtomicI32::new(0),
                })
            })
            .clone()
    }

    pub async fn get(
        self: &Arc<Self>,
        dc: i32,
        is_media: bool,
    ) -> Option<(Arc<RawWebSocket>, String)> {
        let slot = DcSlot {
            dc,
            is_media: is_media_int(is_media),
        };
        let state = self.get_slot(slot).await;
        let now = now_unix();

        let mut res: Option<(Arc<RawWebSocket>, String)> = None;
        {
            let mut q = state.queue.lock().await;
            while let Some(entry) = q.pop_front() {
                if is_pool_entry_usable(&entry, now) {
                    res = Some((entry.ws, entry.domain));
                    STATS.pool_hits.fetch_add(1, Ordering::Relaxed);
                    break;
                } else {
                    let e_ws = entry.ws;
                    tokio::spawn(async move {
                        e_ws.close().await;
                    });
                }
            }
            if res.is_none() {
                STATS.pool_misses.fetch_add(1, Ordering::Relaxed);
            }
        }

        if state
            .refilling
            .compare_exchange(0, 1, Ordering::SeqCst, Ordering::SeqCst)
            .is_ok()
        {
            let pool = self.clone();
            let st = state.clone();
            let gen = self.generation.load(Ordering::SeqCst);
            let cancel = self.cancel_refill.read().clone();
            tokio::spawn(async move {
                pool.refill(slot, st, gen, cancel).await;
            });
        }

        res
    }

    pub async fn refill(
        self: Arc<Self>,
        slot: DcSlot,
        state: Arc<SlotState>,
        gen: u64,
        cancel: CancellationToken,
    ) {
        let cur_len = state.queue.lock().await.len();
        let target_size = self.target_size(slot);
        let needed = target_size.saturating_sub(cur_len);
        if needed == 0 || self.generation.load(Ordering::SeqCst) != gen || cancel.is_cancelled() {
            state.refilling.store(0, Ordering::SeqCst);
            return;
        }

        let dc = slot.dc;
        let effective_dc = DC_OVERRIDES.get(&dc).copied().unwrap_or(dc);

        let (enabled, domains) = {
            let cfg = CFPROXY.read();
            (CFPROXY_ENABLED.load(Ordering::Relaxed), cfg.domains.clone())
        };
        if !enabled || domains.is_empty() {
            state.refilling.store(0, Ordering::SeqCst);
            return;
        }

        let ordered = crate::balancer::BALANCER
            .read()
            .get_domains_for_dc(effective_dc, slot.is_media != 0);
        let mut candidates = Vec::new();
        for d in ordered {
            if cfproxy_429_cooldown_remaining(&d) == Duration::ZERO {
                candidates.push(d);
            }
        }
        if candidates.is_empty() {
            state.refilling.store(0, Ordering::SeqCst);
            return;
        }

        let mut handles = Vec::new();
        for i in 0..needed {
            let domain_base = candidates[i % candidates.len()].clone();
            let target_domain = format!("kws{}.{}", effective_dc, domain_base);
            let path = "/apiws".to_string();
            let cancel_h = cancel.clone();

            handles.push(tokio::spawn(async move {
                tokio::select! {
                    _ = cancel_h.cancelled() => None,
                    res = cf_connect_domain_with_category(
                        &target_domain,
                        &path,
                        None,
                        3.5,
                        crate::budget::FlowCategory::Background,
                        Some(&cancel_h),
                    ) => {
                        let (ws_opt, _ip, err_opt) = res;
                        if let Some(ws) = ws_opt {
                            Some((ws, target_domain))
                        } else {
                            if let Some(e) = err_opt {
                                if crate::ws::is_cooldown_error(&e) {
                                    mark_cfproxy_429_cooldown(&target_domain, &e);
                                }
                            }
                            None
                        }
                    }
                }
            }));
        }

        for h in handles {
            if let Ok(Some((ws, dom))) = h.await {
                if self.generation.load(Ordering::SeqCst) != gen || cancel.is_cancelled() {
                    tokio::spawn(async move {
                        let _ = ws.close().await;
                    });
                    continue;
                }

                let now = now_unix();
                let ws_arc = Arc::new(ws);
                let mut q = state.queue.lock().await;
                if self.generation.load(Ordering::SeqCst) == gen && !cancel.is_cancelled() && q.len() < 8 {
                    q.push_back(PoolEntry {
                        ws: ws_arc,
                        domain: dom,
                        created: now,
                    });
                } else {
                    drop(q);
                    tokio::spawn(async move {
                        let _ = ws_arc.close().await;
                    });
                }
            }
        }

        state.refilling.store(0, Ordering::SeqCst);
    }

    pub fn start_housekeeper(self: &Arc<Self>) {
        let pool = Arc::downgrade(self);
        let cancel = self.cancel_token.clone();
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(WS_POOL_HOUSEKEEP_INTERVAL);
            interval.tick().await;
            loop {
                tokio::select! {
                    _ = cancel.cancelled() => return,
                    _ = interval.tick() => {
                        if let Some(pool_strong) = pool.upgrade() {
                            pool_strong.clean_idle_sockets().await;
                        } else {
                            return;
                        }
                    }
                }
            }
        });
    }

    async fn clean_idle_sockets(&self) {
        let now = now_unix();
        let max_age = WS_POOL_REUSE_MAX_AGE as i64;

        let slot_states: Vec<Arc<SlotState>> = {
            let map = self.slots.lock().await;
            map.values().cloned().collect()
        };

        for state in slot_states {
            {
                let mut q = state.queue.lock().await;
                let mut active = std::collections::VecDeque::with_capacity(q.len());
                while let Some(entry) = q.pop_front() {
                    if entry.ws.is_closed() || (now - entry.created) > max_age {
                        let ws = entry.ws.clone();
                        tokio::spawn(async move {
                            let _ = ws.close().await;
                        });
                    } else {
                        active.push_back(entry);
                    }
                }
                *q = active;
            }
        }
    }

    pub async fn warmup(self: &Arc<Self>, _dc_opt_map: &HashMap<i32, String>) {
        if MOBILE_NETWORK.load(Ordering::Relaxed) {
            crate::linfo!("WsPool: Cellular profile: cold start warmup skipped (demand-driven standby active)");
            return;
        }

        let gen = self.generation.load(Ordering::SeqCst);
        let cancel = self.cancel_refill.read().clone();

        // On Wi-Fi: warm up at most 1 standby for DC2
        let slot = DcSlot { dc: 2, is_media: 0 };
        let state = self.get_slot(slot).await;
        if state
            .refilling
            .compare_exchange(0, 1, Ordering::SeqCst, Ordering::SeqCst)
            .is_ok()
        {
            let pool = self.clone();
            tokio::spawn(async move {
                pool.refill(slot, state, gen, cancel).await;
            });
        }
    }

    /// Predictive non-destructive prewarm on screen/power event.
    /// Never alters generation of existing flows, never drops active bridges,
    /// and only adds standby if there is free background dial budget.
    pub async fn prewarm(self: &Arc<Self>, _dc_opt_map: &HashMap<i32, String>) {
        if !crate::budget::DIAL_BUDGET
            .has_free_budget_for(crate::budget::FlowCategory::Background)
            .await
        {
            crate::linfo!("WsPool::prewarm: skipped (dial budget busy or queued user flow)");
            return;
        }

        let is_mobile = MOBILE_NETWORK.load(Ordering::Relaxed);

        let candidate_slots: Vec<DcSlot> = if is_mobile {
            let map = self.demand.read();
            map.iter()
                .filter(|(_, d)| {
                    d.last_requested.elapsed() < Duration::from_secs(600) && d.useful_rx_count > 0
                })
                .map(|(slot, _)| *slot)
                .collect()
        } else {
            let mut slots = Vec::new();
            {
                let map = self.demand.read();
                for (slot, d) in map.iter() {
                    if d.last_requested.elapsed() < Duration::from_secs(600) {
                        slots.push(*slot);
                    }
                }
            }
            if slots.is_empty() {
                slots.push(DcSlot { dc: 2, is_media: 0 });
            }
            slots
        };

        for slot in candidate_slots {
            let target = self.target_size(slot);
            if target == 0 {
                continue;
            }
            let state = self.get_slot(slot).await;
            let current_count = state.queue.lock().await.len();
            if current_count >= target {
                continue;
            }

            let gen = self.generation.load(Ordering::SeqCst);
            let cancel = self.cancel_refill.read().clone();

            if state
                .refilling
                .compare_exchange(0, 1, Ordering::SeqCst, Ordering::SeqCst)
                .is_ok()
            {
                let pool = self.clone();
                tokio::spawn(async move {
                    pool.refill(slot, state, gen, cancel).await;
                });
            }
        }
    }

    pub async fn reset(&self) {
        // 1. Атомарно увеличиваем номер эпохи
        self.generation.fetch_add(1, Ordering::SeqCst);

        // 2. Отменяем старый токен refill и создаем новый дочерний
        let old_token = {
            let mut lock = self.cancel_refill.write();
            let old = lock.clone();
            *lock = self.cancel_token.child_token();
            old
        };
        old_token.cancel();

        // 3. Синхронно очищаем очереди всех слотов и сбрасываем флаги refilling
        let map = self.slots.lock().await;
        for s in map.values() {
            s.refilling.store(0, Ordering::SeqCst);
            let mut q = s.queue.lock().await;
            for e in q.drain(..) {
                let ws = e.ws;
                tokio::spawn(async move {
                    let _ = ws.close().await;
                });
            }
        }
    }

    pub async fn reset_and_warmup(self: &Arc<Self>, dc_opt_map: &HashMap<i32, String>) {
        self.reset().await;
        self.warmup(dc_opt_map).await;
    }

    pub async fn close_all(&self) {
        self.reset().await;
    }
}

fn is_pool_entry_usable(e: &PoolEntry, now: i64) -> bool {
    if e.ws.is_closed() {
        return false;
    }
    if now - e.created > WS_POOL_REUSE_MAX_AGE as i64 {
        return false;
    }
    true
}

// ---------------------------------------------------------------------------
// HTTP transport detection
// ---------------------------------------------------------------------------

pub fn is_http_transport(data: &[u8]) -> bool {
    if data.len() < 4 {
        return false;
    }
    &data[..4] == b"POST"
        || &data[..3] == b"GET"
        || &data[..4] == b"HEAD"
        || (data.len() >= 7 && &data[..7] == b"OPTIONS")
}

// ---------------------------------------------------------------------------
// Bridge WS
// ---------------------------------------------------------------------------

pub async fn bridge_ws(
    conn: TcpStream,
    ws: Arc<RawWebSocket>,
    _label: String,
    dc: i32,
    dst: String,
    _port: u16,
    is_media: bool,
    mut splitter: MsgSplitter,
    mut clt_dec: TrackedStream,
    mut clt_enc: TrackedStream,
    mut tg_enc: TrackedStream,
    mut tg_dec: TrackedStream,
    is_faketls: bool,
    initial_clt_data: Vec<u8>,
    cancel_token: CancellationToken,
    pool: Arc<WsPool>,
) {
    let cancel = cancel_token.child_token();
    let _bridge_guard = cancel.clone().drop_guard();
    let activity = crate::bridge::BridgeActivity::with_ws(ws.clone());

    let (mut conn_read, mut conn_write) = conn.into_split();

    // One native heartbeat for this exact WebSocket connection.
    let ws_ping = ws.clone();
    let cancel_ping = cancel.clone();
    let heartbeat = async move {
        if ws_ping.run_heartbeat(cancel_ping.clone()).await.is_err() {
            cancel_ping.cancel();
        }
    };

    // up: client -> ws
    let ws_up = ws.clone();
    let cancel_up = cancel.clone();
    let activity_up = activity.clone();
    let up = async move {
        let mut clean_eof = false;
        // 1. If there were extra bytes in the initial TLS application record, process them first
        if !initial_clt_data.is_empty() {
            let n = initial_clt_data.len();
            STATS.bytes_up.fetch_add(n as i64, Ordering::Relaxed);

            let frames = splitter.process(&initial_clt_data, &mut clt_dec, &mut tg_enc);
            if !frames.is_empty() {
                if !matches!(crate::bridge::bounded_io(ws_up.send_batch(&frames), &cancel_up, BRIDGE_WRITE_TIMEOUT).await, Ok(Ok(()))) {
                    cancel_up.cancel();
                    return;
                }
            }
        }

        let mut buf = vec![0u8; WS_BRIDGE_CHUNK_SIZE];
        loop {
            let chunk_data: Vec<u8> = if is_faketls {
                let read_res = activity_up.wait(crate::faketls::read_tls_app_data(&mut conn_read), &cancel_up).await;
                match read_res {
                    Ok(Ok(d)) if !d.is_empty() => d,
                    Ok(Ok(_)) => { clean_eof = true; break; },
                    _ => break,
                }
            } else {
                let read_res = activity_up.wait(conn_read.read(&mut buf), &cancel_up).await;
                let n = match read_res {
                    Ok(Ok(0)) => { clean_eof = true; break; },
                    Ok(Err(_)) | Err(_) => break,
                    Ok(Ok(n)) => n,
                };
                buf[..n].to_vec()
            };

            activity_up.touch();
            let n = chunk_data.len();
            STATS.bytes_up.fetch_add(n as i64, Ordering::Relaxed);

            let frames = splitter.process(&chunk_data, &mut clt_dec, &mut tg_enc);
            if !frames.is_empty() {
                if !matches!(crate::bridge::bounded_io(ws_up.send_batch(&frames), &cancel_up, BRIDGE_WRITE_TIMEOUT).await, Ok(Ok(()))) {
                    break;
                }
            }
        }

        if !clean_eof { cancel_up.cancel(); return; }
        let tail = splitter.flush(&mut tg_enc);
        if !tail.is_empty() {
            if !matches!(crate::bridge::bounded_io(ws_up.send_batch(&tail), &cancel_up, BRIDGE_WRITE_TIMEOUT).await, Ok(Ok(()))) {
                cancel_up.cancel(); return;
            }
        }

        activity_up.upload_eof();
    };

    // down: ws -> client
    let ws_down = ws.clone();
    let cancel_down = cancel.clone();

    let pool_down = pool.clone();
    let down_dst = dst.clone();
    let down = async move {
        let mut first_rx = true;
        let mut bytes_before_stall: u64 = 0;
        let base_domain = crate::balancer::normalize_domain(&down_dst);
        let cur_gen = crate::network_profile::current_generation();
        loop {
            let recv_res = activity.wait(ws_down.recv(), &cancel_down).await;
            let mut data = match recv_res {
                Ok(Ok(d)) => d,
                _ => break,
            };
            activity.touch();
            let n = data.len();
            bytes_before_stall = bytes_before_stall.saturating_add(n as u64);
            STATS.bytes_down.fetch_add(n as i64, Ordering::Relaxed);
            if first_rx && n > 0 {
                first_rx = false;
                pool_down.record_useful_rx(dc, is_media);
                crate::balancer::BALANCER.write().record_useful_success(
                    cur_gen,
                    dc,
                    is_media,
                    &base_domain,
                );
            }

            tg_dec.xor(&mut data);
            clt_enc.xor(&mut data);

            if is_faketls {
                if !matches!(crate::bridge::bounded_io(
                    crate::faketls::write_tls_app_data(&mut conn_write, &data),
                    &cancel_down, BRIDGE_WRITE_TIMEOUT).await, Ok(Ok(())))
                {
                    break;
                }
            } else {
                if crate::socks5::bounded_write(&mut conn_write, &data, &cancel_down, BRIDGE_WRITE_TIMEOUT).await.is_err() {
                    break;
                }
            }
        }
        if first_rx {
            crate::balancer::BALANCER.write().record_failure(
                cur_gen,
                dc,
                is_media,
                &base_domain,
            );
            crate::node_independence::NODE_INDEPENDENCE_TRACKER
                .write()
                .record_node_stall(&base_domain, 0);
        } else if !cancel_down.is_cancelled() && bytes_before_stall < 65536 {
            crate::node_independence::NODE_INDEPENDENCE_TRACKER
                .write()
                .record_node_stall(&base_domain, bytes_before_stall);
        } else {
            crate::node_independence::NODE_INDEPENDENCE_TRACKER
                .write()
                .record_node_bytes_transferred(&base_domain, bytes_before_stall);
        }
        cancel_down.cancel();
    };

    let transfer = async {
        tokio::join!(up, down);
        cancel.cancel();
    };
    tokio::join!(transfer, heartbeat);

    ws.close().await;
}

// ---------------------------------------------------------------------------
// Cfproxy fallback
// ---------------------------------------------------------------------------

pub fn get_dc_target_ip(dc: i32, is_media: bool) -> &'static str {
    if is_media {
        match dc {
            1 => "149.154.175.51",
            2 => "149.154.167.52",
            3 => "149.154.175.101",
            4 => "149.154.167.92",
            5 => "91.108.56.165",
            203 => "91.105.192.100",
            _ => "149.154.167.52",
        }
    } else {
        match dc {
            1 => "149.154.175.50",
            2 => "149.154.167.51",
            3 => "149.154.175.100",
            4 => "149.154.167.91",
            5 => "91.108.56.130",
            203 => "91.105.192.100",
            _ => "149.154.167.51",
        }
    }
}

pub fn get_dc_target_ipv6(dc: i32, is_media: bool) -> &'static str {
    if is_media {
        match dc {
            1 => "2001:b28:f23d:f001::b",
            2 => "2001:67c:4e8:f002::b",
            3 => "2001:b28:f23d:f003::b",
            4 => "2001:67c:4e8:f004::b",
            5 => "2001:b28:f23f:f005::b",
            203 => "2001:67c:4e8:f002::b",
            _ => "2001:67c:4e8:f002::b",
        }
    } else {
        match dc {
            1 => "2001:b28:f23d:f001::a",
            2 => "2001:67c:4e8:f002::a",
            3 => "2001:b28:f23d:f003::a",
            4 => "2001:67c:4e8:f004::a",
            5 => "2001:b28:f23f:f005::a",
            203 => "2001:67c:4e8:f002::a",
            _ => "2001:67c:4e8:f002::a",
        }
    }
}

pub fn get_dc_target_ip_for_current_network(dc: i32, is_media: bool) -> &'static str {
    if crate::recovery::is_ipv6_only_network() {
        get_dc_target_ipv6(dc, is_media)
    } else {
        get_dc_target_ip(dc, is_media)
    }
}

async fn cfproxy_acquire_ws(
    pool: &Arc<WsPool>,
    dc: i32,
    is_media: bool,
    cancel_token: &CancellationToken,
) -> Option<(Arc<RawWebSocket>, String)> {
    pool.record_demand(dc, is_media);

    // 1. Попытка мгновенного захвата сокета из предварительно прогретого пула (0 ms)
    if let Some((ws, domain)) = pool.get(dc, is_media).await {
        linfo!(
            " DC{}{} acquired from WsPool socket pool (0 ms): {}",
            dc,
            media_tag(is_media),
            domain
        );
        return Some((ws, domain));
    }

    // 2. Если в пуле сокетов не оказалось (Miss), запускаем параллельную Anycast CDN гонку
    let race_res = cfproxy_acquire_ws_race(dc, is_media, cancel_token).await;

    // 3. Фоново восполняем пул для этого слота, чтобы следующий сокет был взят мгновенно
    let slot = DcSlot {
        dc,
        is_media: is_media_int(is_media),
    };
    let state = pool.get_slot(slot).await;
    if state
        .refilling
        .compare_exchange(0, 1, Ordering::SeqCst, Ordering::SeqCst)
        .is_ok()
    {
        let p = pool.clone();
        let gen = pool.generation.load(Ordering::SeqCst);
        let cancel = pool.cancel_refill.read().clone();
        tokio::spawn(async move {
            p.refill(slot, state, gen, cancel).await;
        });
    }

    race_res
}

async fn cfproxy_acquire_ws_race(
    dc: i32,
    is_media: bool,
    cancel_token: &CancellationToken,
) -> Option<(Arc<RawWebSocket>, String)> {
    let expected = crate::generation_guard::snapshot();
    let (enabled, domains) = {
        let cfg = CFPROXY.read();
        (CFPROXY_ENABLED.load(Ordering::Relaxed), cfg.domains.clone())
    };
    if !enabled {
        return None;
    }

    let effective_dc = DC_OVERRIDES.get(&dc).copied().unwrap_or(dc);

    // MTProto routes exclusively via Flowseal Anycast CDNs (kws{effective_dc}.{domain}/apiws).
    // NOTE: Custom user workers and developer workers are deliberately disabled for MTProto
    // because MTProto Obfs2 framing over Cloudflare Workers (cloudflare:sockets) breaks the
    // Telegram web gateway connection and prevents Anycast CDN balancer from functioning.
    // Cloudflare Workers are reserved exclusively for the SOCKS5 proxy protocol stack.
    let mut candidate_targets: Vec<(String, String)> = Vec::new();

    if !domains.is_empty() {
        let ordered = crate::balancer::BALANCER
            .read()
            .get_domains_for_dc(effective_dc, is_media);
        let diverse_ordered = crate::node_independence::NODE_INDEPENDENCE_TRACKER
            .read()
            .select_diverse_race_candidates(&ordered, 4);
        for d in diverse_ordered {
            let remaining = cfproxy_429_cooldown_remaining(&d);
            if remaining == Duration::ZERO {
                let domain = format!("kws{}.{}", effective_dc, d);
                candidate_targets.push((domain, "/apiws".to_string()));
            }
        }
    }

    if candidate_targets.is_empty() {
        lwarn!(
            " CF fallback DC{}{}: all Anycast CDN domains unreachable",
            dc,
            media_tag(is_media)
        );
        return None;
    }

    let m_tag = media_tag(is_media);
    ldebug!(
        "MTProto Happy Eyeballs Race: {} targets for DC{}{}",
        candidate_targets.len(),
        dc,
        m_tag
    );

    let (tx, mut rx) =
        tokio::sync::mpsc::channel::<(RawWebSocket, String)>(candidate_targets.len());
    let is_mobile = MOBILE_NETWORK.load(Ordering::Relaxed);
    let stagger_step = if is_mobile {
        Duration::from_millis(400)
    } else {
        Duration::from_millis(25)
    };

    let child_cancel = cancel_token.child_token();
    let mut handles = Vec::with_capacity(candidate_targets.len());

    for (i, (dom, path)) in candidate_targets.into_iter().enumerate() {
        let tx = tx.clone();
        let cancel = child_cancel.clone();
        let delay = stagger_step * (i as u32);

        handles.push(tokio::spawn(async move {
            tokio::select! {
                biased;
                _ = cancel.cancelled() => {}
                res = async {
                    if delay > Duration::ZERO {
                        tokio::time::sleep(delay).await;
                    }
                    if cancel.is_cancelled() {
                        return None;
                    }
                    let (ws, resolved_ip, err) = cf_connect_domain_with_category(
                        &dom,
                        &path,
                        None,
                        3.5,
                        crate::budget::FlowCategory::UserFlow,
                        Some(&cancel),
                    ).await;

                    if let Some(w) = ws {
                        if cancel.is_cancelled() {
                            ldebug!("MTProto race runner-up late 101 gracefully closing for {}", dom);
                            let _ = w.close().await;
                            return None;
                        }
                        if !resolved_ip.is_empty() {
                            ldebug!("MTProto race ok {} via {}", dom, resolved_ip);
                        } else {
                            ldebug!("MTProto race ok {}", dom);
                        }
                        Some((w, dom))
                    } else {
                        if let Some(ref e) = err {
                            let base = crate::balancer::normalize_domain(&dom);
                            let resolved_ip_opt = resolved_ip.parse::<std::net::IpAddr>().ok();
                            crate::node_independence::NODE_INDEPENDENCE_TRACKER
                                .write()
                                .record_node_failure(&base, resolved_ip_opt, None);
                            crate::balancer::BALANCER.write().record_failure(
                                crate::network_profile::current_generation(),
                                effective_dc,
                                is_media,
                                &base,
                            );
                            if crate::ws::is_cooldown_error(e) {
                                let _ = crate::generation_guard::apply_if_current(expected, || {
                                    mark_cfproxy_429_cooldown(&dom, e);
                                });
                            }
                            if !resolved_ip.is_empty() {
                                log_cf_conn_error(
                                    &format!("MTProto race fail {} via {}: {}", dom, resolved_ip, e.compact()),
                                    e,
                                );
                            } else {
                                log_cf_conn_error(
                                    &format!("MTProto race fail {}: {}", dom, e.compact()),
                                    e,
                                );
                            }
                        }
                        None
                    }
                } => {
                    if let Some((w, dom)) = res {
                        if cancel.is_cancelled() {
                            let _ = w.close().await;
                        } else {
                            let _ = tx.send((w, dom)).await;
                        }
                    }
                }
            }
        }));
    }

    drop(tx);

    let mut winning_res: Option<(Arc<RawWebSocket>, String)> = None;

    tokio::select! {
        _ = cancel_token.cancelled() => {
            ldebug!("MTProto connection race cancelled by parent");
            child_cancel.cancel();
        }
        msg = rx.recv() => {
            child_cancel.cancel();
            if let Some((ws, winner_domain)) = msg {
                if !crate::generation_guard::is_current(expected) {
                    let _ = ws.close().await;
                } else {
                    let accepted = crate::generation_guard::apply_if_current(expected, || {
                        clear_cfproxy_429_cooldown(&winner_domain);
                        let base_domain = crate::balancer::normalize_domain(&winner_domain);
                        if let Some(ip) = ws.peer_ip() {
                            let colo = ws.colo().to_string();
                            crate::node_independence::NODE_INDEPENDENCE_TRACKER
                                .write()
                                .record_node_handshake_success(&base_domain, ip, &colo);
                        }
                        crate::balancer::BALANCER.write().update_domain_for_dc(
                            effective_dc,
                            is_media,
                            &base_domain,
                        );
                    }).is_some();
                    if accepted {
                        linfo!("MTProto endpoint selected: {}", winner_domain);
                        winning_res = Some((Arc::new(ws), winner_domain));
                    } else {
                        let _ = ws.close().await;
                    }
                }
            }
        }
    }

    // A winner (or caller cancellation) makes all remaining attempts obsolete.
    // Abort and await each handle within a bounded timeout so the number of active racer tasks
    // becomes 0 and all TCP/TLS/DNS resources are freed.
    for handle in &handles {
        handle.abort();
    }
    for handle in handles {
        let _ = tokio::time::timeout(Duration::from_millis(500), handle).await;
    }

    // Drain and gracefully close any runner-up connections that arrived in rx
    while let Ok((extra_ws, extra_dom)) = rx.try_recv() {
        ldebug!("MTProto closing runner-up connection to {}", extra_dom);
        let _ = extra_ws.close().await;
    }

    if !crate::generation_guard::is_current(expected) {
        if let Some((ws, _)) = winning_res {
            let _ = ws.close().await;
        }
        return None;
    }
    winning_res
}

// ---------------------------------------------------------------------------
// do_fallback — строго Cloudflare CDN fallback (Direct TCP Fallback удален)
// ---------------------------------------------------------------------------

pub async fn do_fallback(
    pool: &Arc<WsPool>,
    conn: TcpStream,
    relay_init: &[u8],
    label: String,
    dc: i32,
    is_media: bool,
    splitter: MsgSplitter,
    clt_dec: &TrackedStream,
    clt_enc: &TrackedStream,
    tg_enc: &TrackedStream,
    tg_dec: &TrackedStream,
    is_faketls: bool,
    initial_clt_data: Vec<u8>,
    cancel_token: CancellationToken,
) -> bool {
    let clt_dec = clt_dec.clone_state();
    let clt_enc = clt_enc.clone_state();
    let tg_enc = tg_enc.clone_state();
    let tg_dec = tg_dec.clone_state();

    let use_cf = CFPROXY_ENABLED.load(Ordering::Relaxed);

    if use_cf {
        if let Some((ws, chosen_domain)) =
            cfproxy_acquire_ws(pool, dc, is_media, &cancel_token).await
        {
            STATS.connections_cfproxy.fetch_add(1, Ordering::Relaxed);
            linfo!(
                " DC{}{} connected via CDN: {}",
                dc,
                media_tag(is_media),
                chosen_domain
            );

            if ws.send(relay_init).await.is_err() {
                let _ = ws.close().await;
                return false;
            }

            bridge_ws(
                conn,
                ws,
                label,
                dc,
                chosen_domain,
                443,
                is_media,
                splitter,
                clt_dec,
                clt_enc,
                tg_enc,
                tg_dec,
                is_faketls,
                initial_clt_data,
                cancel_token,
                pool.clone(),
            )
            .await;
            return true;
        }
    }

    // Direct TCP Fallback категорически запрещен (заблокирован ТСПУ в РФ): сессия прерывается
    false
}

// ---------------------------------------------------------------------------
// Client connection handler
// ---------------------------------------------------------------------------

pub async fn handle_client(
    pool: Arc<WsPool>,
    mut conn: TcpStream,
    cancel_token: CancellationToken,
) {
    STATS.connections_total.fetch_add(1, Ordering::Relaxed);
    STATS.connections_active.fetch_add(1, Ordering::Relaxed);
    struct ActiveGuard;
    impl Drop for ActiveGuard {
        fn drop(&mut self) {
            if STATS.connections_active.load(Ordering::Relaxed) > 0 {
                STATS.connections_active.fetch_sub(1, Ordering::Relaxed);
            }
        }
    }
    let _guard = ActiveGuard;

    let peer = conn
        .peer_addr()
        .map(|a| a.to_string())
        .unwrap_or_else(|_| "unknown".to_string());
    let label = peer;

    set_sock_opts(&conn);

    let current_secret = PROXY_SECRET.read().clone();
    let secret_bytes = hex::decode(&current_secret).unwrap_or_default();

    // 1. Read initial 5 bytes to detect FakeTLS vs plain MTProto
    let mut initial_5 = [0u8; 5];
    let init_res = tokio::select! {
        _ = cancel_token.cancelled() => return,
        res = tokio::time::timeout(Duration::from_secs(10), conn.read_exact(&mut initial_5)) => res,
    };
    match init_res {
        Ok(Ok(_)) => {}
        _ => return,
    }

    let mut handshake = [0u8; 64];
    let mut initial_clt_data: Vec<u8> = Vec::new();
    let mut is_faketls = false;

    if crate::faketls::is_tls_handshake(&initial_5) {
        is_faketls = true;
        ldebug!(
            "{}: FakeTLS handshake detected (0x16 0x03 0x01/0x03)",
            label
        );
        if let Err(e) = tokio::time::timeout(
            Duration::from_secs(10),
            crate::faketls::handle_fake_tls_handshake(&mut conn, &initial_5),
        ).await.unwrap_or_else(|_| Err(std::io::Error::new(std::io::ErrorKind::TimedOut, "FakeTLS handshake timeout"))) {
            ldebug!("{}: FakeTLS handshake failed: {}", label, e);
            STATS.connections_bad.fetch_add(1, Ordering::Relaxed);
            return;
        }

        // Read client's first TLS ApplicationData record containing the 64-byte MTProto handshake
        let mut app_buf = Vec::new();
        while app_buf.len() < 64 {
            let record = match tokio::time::timeout(
                Duration::from_secs(10),
                crate::faketls::read_tls_app_data(&mut conn),
            )
            .await
            {
                Ok(Ok(d)) if !d.is_empty() => d,
                _ => {
                    STATS.connections_bad.fetch_add(1, Ordering::Relaxed);
                    return;
                }
            };
            app_buf.extend_from_slice(&record);
        }

        handshake.copy_from_slice(&app_buf[..64]);
        if app_buf.len() > 64 {
            initial_clt_data = app_buf[64..].to_vec();
        }
    } else {
        handshake[..5].copy_from_slice(&initial_5);
        let rem_res = tokio::select! {
            _ = cancel_token.cancelled() => return,
            res = tokio::time::timeout(Duration::from_secs(10), conn.read_exact(&mut handshake[5..64])) => res,
        };
        match rem_res {
            Ok(Ok(_)) => {}
            _ => return,
        }
    }

    if is_http_transport(&handshake) {
        STATS
            .connections_http_reject
            .fetch_add(1, Ordering::Relaxed);
        let _ = conn
            .write_all(b"HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n")
            .await;
        return;
    }

    let clt_dec_prekey = &handshake[8..40];
    let clt_dec_iv = &handshake[40..56];
    let mut hash_dec = Sha256::new();
    hash_dec.update(clt_dec_prekey);
    hash_dec.update(&secret_bytes);
    let mut clt_decryptor = new_aes_ctr(&hash_dec.finalize(), clt_dec_iv);

    let mut decrypted = handshake;
    clt_decryptor.xor(&mut decrypted);

    let proto_tag = &decrypted[56..60];
    let proto = LittleEndian::read_u32(proto_tag);
    if !valid_proto(proto) {
        STATS.connections_bad.fetch_add(1, Ordering::Relaxed);
        return;
    }

    let dc_raw = LittleEndian::read_u16(&decrypted[60..62]) as i16;
    let mut dc = dc_raw as i32;
    if dc < 0 {
        dc = -dc;
    }
    let is_media = dc_raw < 0;
    let _m_tag = media_tag(is_media);
    let effective_dc = DC_OVERRIDES.get(&dc).copied().unwrap_or(dc);

    pool.record_demand(dc, is_media);

    let mut clt_enc_prekey_and_iv = [0u8; 48];
    for i in 0..48 {
        clt_enc_prekey_and_iv[i] = handshake[8 + 47 - i];
    }
    let mut hash_enc = Sha256::new();
    hash_enc.update(&clt_enc_prekey_and_iv[..32]);
    hash_enc.update(&secret_bytes);
    let clt_encryptor = new_aes_ctr(&hash_enc.finalize(), &clt_enc_prekey_and_iv[32..]);

    let mut relay_init = [0u8; 64];
    loop {
        rand::thread_rng().fill_bytes(&mut relay_init);
        if relay_init[0] == 0xEF {
            continue;
        }
        let s = &relay_init[..4];
        if s == b"HEAD"
            || s == b"POST"
            || s == b"GET "
            || s == &[0xee, 0xee, 0xee, 0xee]
            || s == &[0xdd, 0xdd, 0xdd, 0xdd]
        {
            continue;
        }
        if relay_init[0] == 0x16
            && relay_init[1] == 0x03
            && relay_init[2] == 0x01
            && relay_init[3] == 0x02
        {
            continue;
        }
        if relay_init[4] == 0 && relay_init[5] == 0 && relay_init[6] == 0 && relay_init[7] == 0 {
            continue;
        }
        break;
    }

    let mut tg_dec_prekey_and_iv = [0u8; 48];
    for i in 0..48 {
        tg_dec_prekey_and_iv[i] = relay_init[8 + 47 - i];
    }

    let mut tg_encryptor = new_aes_ctr(&relay_init[8..40], &relay_init[40..56]);
    let tg_decryptor = new_aes_ctr(&tg_dec_prekey_and_iv[..32], &tg_dec_prekey_and_iv[32..]);

    let mut dc_bytes = [0u8; 2];
    let dc_idx = effective_dc;
    LittleEndian::write_u16(&mut dc_bytes, dc_idx as u16);

    let mut tail_plain = [0u8; 8];
    tail_plain[0..4].copy_from_slice(proto_tag);
    tail_plain[4..6].copy_from_slice(&dc_bytes);
    rand::thread_rng().fill_bytes(&mut tail_plain[6..8]);

    let mut encrypted_full = relay_init;
    tg_encryptor.xor(&mut encrypted_full);

    let mut keystream_tail = [0u8; 8];
    for i in 0..8 {
        keystream_tail[i] = encrypted_full[56 + i] ^ relay_init[56 + i];
        relay_init[56 + i] = tail_plain[i] ^ keystream_tail[i];
    }

    let splitter = MsgSplitter::new(proto);

    // MTProto маршрутизируется исключительно через распределенные узлы Flowseal Anycast CDN
    // с предварительно прогретым пулом сокетов WsPool (0 ms захват).
    // Прямой Fallback на IP Telegram (149.154.175.50) категорически исключен из-за блокировки ТСПУ в РФ.
    do_fallback(
        &pool,
        conn,
        &relay_init,
        label,
        dc,
        is_media,
        splitter,
        &clt_decryptor,
        &clt_encryptor,
        &tg_encryptor,
        &tg_decryptor,
        is_faketls,
        initial_clt_data,
        cancel_token,
    )
    .await;
}

#[allow(dead_code)]

pub async fn connect_direct_ws(
    target: &str,
    domains: &[String],
    timeout: f64,
) -> (Option<RawWebSocket>, bool, bool) {
    if domains.is_empty() {
        return (None, false, false);
    }
    let mut ws_failed_redirect = false;
    let mut all_redirects = true;

    for dom in domains {
        match ws_connect(target, dom, "/apiws", timeout).await {
            Ok(ws) => return (Some(ws), ws_failed_redirect, false),
            Err(e) => {
                STATS.ws_errors.fetch_add(1, Ordering::Relaxed);
                if let Some(h) = e.handshake() {
                    if h.is_redirect() {
                        ws_failed_redirect = true;
                    } else {
                        all_redirects = false;
                    }
                } else {
                    all_redirects = false;
                }
            }
        }
    }
    (None, ws_failed_redirect, all_redirects)
}

// ---------------------------------------------------------------------------
// Server
// ---------------------------------------------------------------------------

pub async fn run_proxy(
    pool: Arc<WsPool>,
    host: String,
    port: u16,
    dc_opt_map: HashMap<i32, String>,
    cancel_root: CancellationToken,
    cancel_sessions: Arc<parking_lot::RwLock<CancellationToken>>,
    listener: TcpListener,
) -> std::io::Result<()> {
    {
        let mut m = DC_OPT.write();
        *m = dc_opt_map.clone();
    }

    start_cfproxy_refresh();

    pool.start_housekeeper();
    {
        let p = pool.clone();
        let map = dc_opt_map.clone();
        tokio::spawn(async move {
            p.warmup(&map).await;
        });
    }

    linfo!("━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    linfo!("  TG WS Proxy started");
    linfo!("  Address: {}:{}", host, port);

    let cancel_stats = cancel_root.clone();
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(Duration::from_secs(60));
        interval.tick().await;
        loop {
            tokio::select! {
                _ = cancel_stats.cancelled() => return,
                _ = interval.tick() => {
                    linfo!(" {}", STATS.summary_ru());
                }
            }
        }
    });

    let mut sessions = tokio::task::JoinSet::new();
    loop {
        tokio::select! {
            _ = cancel_root.cancelled() => {
                break;
            }
            _ = sessions.join_next(), if !sessions.is_empty() => {}
            accept = listener.accept() => {
                match accept {
                    Ok((conn, _)) => {
                        let p = pool.clone();
                        let cancel = cancel_sessions.read().child_token();
                        sessions.spawn(async move {
                            handle_client(p, conn, cancel).await;
                        });
                    }
                    Err(_) => {
                        continue;
                    }
                }
            }
        }
    }

    drop(listener);
    cancel_root.cancel();
    if tokio::time::timeout(Duration::from_secs(2), async {
        while sessions.join_next().await.is_some() {}
    })
    .await
    .is_err()
    {
        lwarn!("MTProto shutdown: aborting sessions that did not drain in time");
        sessions.abort_all();
        while sessions.join_next().await.is_some() {}
    }
    pool.close_all().await;
    Ok(())
}

pub fn parse_cidr_pool(cidrs_str: &str) -> HashMap<i32, String> {
    let mut result = HashMap::new();
    if cidrs_str.trim().is_empty() {
        return result;
    }
    for pair in cidrs_str.split(',') {
        let parts: Vec<&str> = pair.split(':').collect();
        if parts.len() == 2 {
            let dc_raw = parts[0].trim();
            let ip_raw = parts[1].trim();
            if let Ok(dc) = dc_raw.parse::<i32>() {
                if !ip_raw.is_empty() {
                    if let Ok(ip) = ip_raw.parse::<std::net::IpAddr>() {
                        result.insert(dc, ip.to_string());
                    }
                }
            }
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_dc_target_ip_media_and_chat() {
        // Chat IPs
        assert_eq!(get_dc_target_ip(1, false), "149.154.175.50");
        assert_eq!(get_dc_target_ip(2, false), "149.154.167.51");
        assert_eq!(get_dc_target_ip(3, false), "149.154.175.100");
        assert_eq!(get_dc_target_ip(4, false), "149.154.167.91");
        assert_eq!(get_dc_target_ip(5, false), "91.108.56.130");
        assert_eq!(get_dc_target_ip(203, false), "91.105.192.100");

        // Media IPs
        assert_eq!(get_dc_target_ip(1, true), "149.154.175.51");
        assert_eq!(get_dc_target_ip(2, true), "149.154.167.52");
        assert_eq!(get_dc_target_ip(3, true), "149.154.175.101");
        assert_eq!(get_dc_target_ip(4, true), "149.154.167.92");
        assert_eq!(get_dc_target_ip(5, true), "91.108.56.165");
        assert_eq!(get_dc_target_ip(203, true), "91.105.192.100");
    }

    #[test]
    fn test_dc_target_ipv6_and_current_network() {
        assert_eq!(get_dc_target_ipv6(1, false), "2001:b28:f23d:f001::a");
        assert_eq!(get_dc_target_ipv6(2, false), "2001:67c:4e8:f002::a");
        assert_eq!(get_dc_target_ipv6(1, true), "2001:b28:f23d:f001::b");
        assert_eq!(get_dc_target_ipv6(2, true), "2001:67c:4e8:f002::b");

        crate::recovery::set_ipv6_only_network(false);
        assert_eq!(get_dc_target_ip_for_current_network(2, false), "149.154.167.51");

        crate::recovery::set_ipv6_only_network(true);
        assert_eq!(get_dc_target_ip_for_current_network(2, false), "2001:67c:4e8:f002::a");
        crate::recovery::set_ipv6_only_network(false);
    }

    #[test]
    fn test_ws_domains_media_prefix() {
        let dc2_chat = ws_domains(2, false);
        assert_eq!(dc2_chat[0], "kws2.web.telegram.org");
        assert_eq!(dc2_chat[1], "kws2-1.web.telegram.org");

        let dc2_media = ws_domains(2, true);
        assert_eq!(dc2_media[0], "kws2.web.telegram.org");
        assert_eq!(dc2_media[1], "kws2-1.web.telegram.org");

        let dc4_media = ws_domains(4, true);
        assert_eq!(dc4_media[0], "kws4.web.telegram.org");
        assert_eq!(dc4_media[1], "kws4-1.web.telegram.org");
    }

    #[test]
    fn test_parse_cidr_pool() {
        let map = parse_cidr_pool("2:149.154.167.51,4:149.154.167.91");
        assert_eq!(map.get(&2), Some(&"149.154.167.51".to_string()));
        assert_eq!(map.get(&4), Some(&"149.154.167.91".to_string()));
        assert_eq!(map.get(&1), None);
    }

    #[tokio::test]
    async fn test_ws_pool_demand_driven_sizing() {
        let cancel = CancellationToken::new();
        let pool = Arc::new(WsPool::new(cancel));

        let slot_dc2_chat = DcSlot { dc: 2, is_media: 0 };
        let slot_dc4_chat = DcSlot { dc: 4, is_media: 0 };
        let slot_dc2_media = DcSlot { dc: 2, is_media: 1 };

        // 1. Mobile profile: no demand -> target size 0
        MOBILE_NETWORK.store(true, Ordering::Relaxed);
        assert_eq!(pool.target_size(slot_dc2_chat), 0);
        assert_eq!(pool.target_size(slot_dc4_chat), 0);

        // 2. Client demands DC2 chat -> target size becomes 1 (standby)
        pool.record_demand(2, false);
        assert_eq!(pool.target_size(slot_dc2_chat), 1);
        // DC4 and DC2 media were not demanded -> still 0
        assert_eq!(pool.target_size(slot_dc4_chat), 0);
        assert_eq!(pool.target_size(slot_dc2_media), 0);

        // 3. Client demands DC2 media -> target size becomes 1
        pool.record_demand(2, true);
        assert_eq!(pool.target_size(slot_dc2_media), 1);

        // 4. Wi-Fi profile: expands with useful RX
        MOBILE_NETWORK.store(false, Ordering::Relaxed);
        MTPROTO_STANDBY_PER_ACTIVE_SLOT_REQUESTED.store(4, Ordering::Relaxed);
        assert_eq!(pool.target_size(slot_dc2_chat), 1); // Only 1 until sustained useful RX

        pool.record_useful_rx(2, false);
        pool.record_useful_rx(2, false);
        assert_eq!(pool.target_size(slot_dc2_chat), 4); // Expands to 4 after sustained useful traffic

        MOBILE_NETWORK.store(false, Ordering::Relaxed);
    }

    #[tokio::test]
    async fn test_mtproto_race_child_cancellation_structure() {
        let parent_cancel = CancellationToken::new();
        let child_cancel = parent_cancel.child_token();

        let active_tasks = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let mut handles = Vec::new();

        for i in 0..4 {
            let cancel = child_cancel.clone();
            let active = active_tasks.clone();
            handles.push(tokio::spawn(async move {
                active.fetch_add(1, Ordering::SeqCst);
                tokio::select! {
                    biased;
                    _ = cancel.cancelled() => {
                        active.fetch_sub(1, Ordering::SeqCst);
                    }
                    _ = tokio::time::sleep(Duration::from_millis(500 * (i + 1))) => {
                        active.fetch_sub(1, Ordering::SeqCst);
                    }
                }
            }));
        }

        // Initially all 4 tasks are active
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert_eq!(active_tasks.load(Ordering::SeqCst), 4);

        // Cancel child token (as happens when a winner is chosen)
        child_cancel.cancel();

        for handle in handles {
            let _ = tokio::time::timeout(Duration::from_millis(200), handle).await;
        }

        // Active racer tasks must become 0 within bounded time
        assert_eq!(active_tasks.load(Ordering::SeqCst), 0);
    }
}
