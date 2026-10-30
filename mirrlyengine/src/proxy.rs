use crate::cfproxy::*;
use crate::config::*;
use crate::crypto::*;
use crate::ws::*;

use byteorder::{ByteOrder, LittleEndian};
use rand::RngCore;
use sha2::{Digest, Sha256};
use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio_util::sync::CancellationToken;

// ---------------------------------------------------------------------------
// Target resolution
// ---------------------------------------------------------------------------


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
    pub queue: parking_lot::Mutex<VecDeque<PoolEntry>>,
    pub in_flight: AtomicUsize,
}

impl SlotState {
    pub fn new() -> Self {
        Self {
            queue: parking_lot::Mutex::new(VecDeque::with_capacity(16)),
            in_flight: AtomicUsize::new(0),
        }
    }

    /// Instant non-blocking pop (< 1 us) under synchronized spinlock.
    /// Discarded or stale sockets are moved to background tokio::spawn for non-blocking close.
    pub fn try_pop_fresh_matching(&self, max_age: Duration, expected_domain: Option<&str>) -> Option<(Arc<RawWebSocket>, String)> {
        let now = now_unix();
        let max_age_secs = max_age.as_secs() as i64;
        let mut q = self.queue.lock();
        while let Some(entry) = q.pop_front() {
            let domain_match = match expected_domain {
                Some(d) if !d.is_empty() => entry.domain == d,
                _ => true,
            };
            let age = now - entry.created;
            if !entry.ws.is_closed() && age >= 0 && age < max_age_secs && domain_match {
                return Some((entry.ws, entry.domain));
            } else {
                let ws = entry.ws;
                tokio::spawn(async move {
                    let _ = ws.close().await;
                });
            }
        }
        None
    }

    pub fn try_pop_fresh(&self, max_age: Duration) -> Option<(Arc<RawWebSocket>, String)> {
        self.try_pop_fresh_matching(max_age, None)
    }

    pub fn push_if_below(&self, entry: PoolEntry, target: usize) -> Result<(), PoolEntry> {
        let mut q = self.queue.lock();
        if q.len() < target {
            q.push_back(entry);
            Ok(())
        } else {
            Err(entry)
        }
    }

    pub fn ready_count(&self) -> usize {
        self.queue.lock().len()
    }

    pub fn in_flight_count(&self) -> usize {
        self.in_flight.load(Ordering::Relaxed)
    }

    pub fn dec_in_flight(&self, count: usize) {
        let _ = self.in_flight.fetch_update(Ordering::Relaxed, Ordering::Relaxed, |val| {
            Some(val.saturating_sub(count))
        });
    }

    pub fn drain_all(&self) -> Vec<PoolEntry> {
        let mut q = self.queue.lock();
        self.in_flight.store(0, Ordering::Relaxed);
        q.drain(..).collect()
    }
}

#[derive(Debug, Clone, Copy)]
pub struct SlotDemand {
    pub last_requested: Instant,
    pub request_count: u64,
    pub useful_rx_count: u64,
}

pub struct WsPool {
    slots: parking_lot::RwLock<HashMap<DcSlot, Arc<SlotState>>>,
    demand: Arc<parking_lot::RwLock<HashMap<DcSlot, SlotDemand>>>,
    cancel_token: CancellationToken,
    cancel_refill: Arc<parking_lot::RwLock<CancellationToken>>,
    generation: AtomicU64,
}

impl WsPool {
    pub fn new(cancel_token: CancellationToken) -> WsPool {
        let cancel_refill = Arc::new(parking_lot::RwLock::new(cancel_token.child_token()));
        WsPool {
            slots: parking_lot::RwLock::new(HashMap::new()),
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
        let is_dc2_chat = slot.dc == 2 && slot.is_media == 0;
        let is_dc2_media = slot.dc == 2 && slot.is_media == 1;
        if is_mobile {
            // Cellular profile:
            // DC2 chat has baseline target 1 for 0 ms pre-warm before first Telegram request.
            // DC2 media also gets 1 standby socket to eliminate cold dials when sending photos/voice/stickers.
            if self.is_slot_demanded(slot) || is_dc2_chat || is_dc2_media {
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
                    configured.min(2).max(1)
                }
            } else if is_dc2_chat || is_dc2_media {
                // Baseline pre-warm for DC2 chat and media on Wi-Fi: 1–2 ready sockets (up to configured)
                configured.min(2).max(1)
            } else {
                0
            }
        }
    }

    #[inline]
    pub fn get_slot_sync(&self, slot: DcSlot) -> Arc<SlotState> {
        {
            let r = self.slots.read();
            if let Some(state) = r.get(&slot) {
                return state.clone();
            }
        }
        let mut w = self.slots.write();
        w.entry(slot)
            .or_insert_with(|| Arc::new(SlotState::new()))
            .clone()
    }

    pub async fn get_slot(&self, slot: DcSlot) -> Arc<SlotState> {
        self.get_slot_sync(slot)
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
        let expected_domain = {
            let cfg = CFPROXY.read();
            let ud = cfg.user_domain.trim();
            if !ud.is_empty() {
                Some(ud.to_string())
            } else {
                None
            }
        };
        let state = self.get_slot_sync(slot);
        let max_age = if MOBILE_NETWORK.load(Ordering::Relaxed) {
            Duration::from_secs(22)
        } else {
            Duration::from_secs(45)
        };
        let res = state.try_pop_fresh_matching(max_age, expected_domain.as_deref());
        if res.is_some() {
            STATS.pool_hits.fetch_add(1, Ordering::Relaxed);
        } else {
            STATS.pool_misses.fetch_add(1, Ordering::Relaxed);
        }

        self.trigger_refill(slot, &state);

        res
    }

    pub fn trigger_refill(self: &Arc<Self>, slot: DcSlot, state: &Arc<SlotState>) {
        let target = self.target_size(slot);
        if target == 0 {
            return;
        }

        let (enabled, domains, user_domain) = {
            let cfg = CFPROXY.read();
            (
                CFPROXY_ENABLED.load(Ordering::Relaxed),
                cfg.domains.clone(),
                cfg.user_domain.trim().to_string(),
            )
        };
        if !enabled || (user_domain.is_empty() && domains.is_empty()) {
            return;
        }

        if !user_domain.is_empty() && cfproxy_429_cooldown_remaining_scoped(&user_domain, ProtoScope::Mtproto) != Duration::ZERO {
            return;
        }

        let needed = {
            let current = state.ready_count();
            let inflight = state.in_flight.load(Ordering::Relaxed);
            if current + inflight >= target {
                0
            } else {
                let n = target - (current + inflight);
                state.in_flight.fetch_add(n, Ordering::Relaxed);
                n
            }
        };

        if needed == 0 {
            return;
        }

        let pool = self.clone();
        let state = state.clone();
        let gen = self.generation.load(Ordering::SeqCst);
        let cancel = self.cancel_refill.read().clone();

        tokio::spawn(async move {
            pool.refill_needed(slot, state, needed, gen, cancel).await;
        });
    }

    async fn refill_needed(
        self: Arc<Self>,
        slot: DcSlot,
        state: Arc<SlotState>,
        needed: usize,
        gen: u64,
        cancel: CancellationToken,
    ) {
        struct SlotInFlightGuard {
            state: Arc<SlotState>,
            active: bool,
        }
        impl Drop for SlotInFlightGuard {
            fn drop(&mut self) {
                if self.active {
                    self.state.dec_in_flight(1);
                }
            }
        }

        if self.generation.load(Ordering::SeqCst) != gen || cancel.is_cancelled() {
            state.dec_in_flight(needed);
            return;
        }

        let dc = slot.dc;
        let effective_dc = DC_OVERRIDES.get(&dc).copied().unwrap_or(dc);

        let (enabled, domains, user_domain) = {
            let cfg = CFPROXY.read();
            (
                CFPROXY_ENABLED.load(Ordering::Relaxed),
                cfg.domains.clone(),
                cfg.user_domain.trim().to_string(),
            )
        };
        if !enabled || (user_domain.is_empty() && domains.is_empty()) {
            state.dec_in_flight(needed);
            return;
        }

        let is_mobile = MOBILE_NETWORK.load(Ordering::Relaxed);

        if !user_domain.is_empty() {
            if cfproxy_429_cooldown_remaining_scoped(&user_domain, ProtoScope::Mtproto) != Duration::ZERO {
                state.dec_in_flight(needed);
                return;
            }

            let target_dst = canonical_dc_ipv4(effective_dc);
            let timeout = if is_mobile {
                crate::config::adaptive_ws_connect_timeout().clamp(3.2, 4.2)
            } else {
                crate::config::adaptive_ws_connect_timeout().clamp(3.5, 5.0)
            };

            for _ in 0..needed {
                let sid = format!("{:016x}", rand::random::<u64>());
                let path = format!(
                    "/apiws?dst={}&dc={}&media={}&sid={}",
                    target_dst,
                    effective_dc,
                    slot.is_media,
                    sid
                );
                let target_domain = user_domain.clone();
                let cancel_h = cancel.clone();
                let state_h = state.clone();
                let pool_h = self.clone();

                tokio::spawn(async move {
                    let _guard = SlotInFlightGuard {
                        state: state_h.clone(),
                        active: true,
                    };

                    let res = tokio::select! {
                        _ = cancel_h.cancelled() => None,
                        r = cf_connect_domain_with_category(
                            &target_domain,
                            &path,
                            None,
                            timeout,
                            crate::budget::FlowCategory::Background,
                            Some(&cancel_h),
                        ) => {
                            let (ws_opt, _ip, err_opt) = r;
                            if let Some(ws) = ws_opt {
                                clear_cfproxy_429_cooldown_scoped(&target_domain, ProtoScope::Mtproto);
                                Some((ws, target_domain))
                            } else {
                                if let Some(e) = err_opt {
                                    if crate::ws::is_cooldown_error(&e) || is_http_status_error(&e, 429) {
                                        mark_cfproxy_429_cooldown_scoped(&target_domain, ProtoScope::Mtproto, &e);
                                    }
                                }
                                None
                            }
                        }
                    };

                    if let Some((ws, dom)) = res {
                        if pool_h.generation.load(Ordering::SeqCst) != gen || cancel_h.is_cancelled() {
                            tokio::spawn(async move {
                                let _ = ws.close().await;
                            });
                            return;
                        }

                        let ws_arc = Arc::new(ws);
                        let entry = PoolEntry {
                            ws: ws_arc.clone(),
                            domain: dom,
                            created: now_unix(),
                        };

                        if pool_h.generation.load(Ordering::SeqCst) == gen && !cancel_h.is_cancelled() {
                            if let Err(unpushed) = state_h.push_if_below(entry, 8) {
                                tokio::spawn(async move {
                                    let _ = unpushed.ws.close().await;
                                });
                            }
                        } else {
                            tokio::spawn(async move {
                                let _ = ws_arc.close().await;
                            });
                        }
                    }
                });
            }
        } else {
            let ordered = crate::balancer::BALANCER
                .read()
                .get_domains_for_dc(effective_dc, slot.is_media != 0);
            let mut candidates = Vec::new();
            for d in ordered {
                if cfproxy_429_cooldown_remaining_scoped(&d, ProtoScope::Mtproto) == Duration::ZERO {
                    candidates.push(d);
                }
            }
            if candidates.is_empty() {
                state.dec_in_flight(needed);
                return;
            }

            for i in 0..needed {
                let domain_base = candidates[i % candidates.len()].clone();
                let target_domain = format!("kws{}.{}", effective_dc, domain_base);
                let path = "/apiws".to_string();
                let cancel_h = cancel.clone();
                let state_h = state.clone();
                let pool_h = self.clone();

                tokio::spawn(async move {
                    let _guard = SlotInFlightGuard {
                        state: state_h.clone(),
                        active: true,
                    };

                    let res = tokio::select! {
                        _ = cancel_h.cancelled() => None,
                        r = cf_connect_domain_with_category(
                            &target_domain,
                            &path,
                            None,
                            3.5,
                            crate::budget::FlowCategory::Background,
                            Some(&cancel_h),
                        ) => {
                            let (ws_opt, _ip, err_opt) = r;
                            if let Some(ws) = ws_opt {
                                Some((ws, target_domain))
                            } else {
                                if let Some(e) = err_opt {
                                    if crate::ws::is_cooldown_error(&e) {
                                        mark_cfproxy_429_cooldown_scoped(&target_domain, ProtoScope::Mtproto, &e);
                                    }
                                }
                                None
                            }
                        }
                    };

                    if let Some((ws, dom)) = res {
                        if pool_h.generation.load(Ordering::SeqCst) != gen || cancel_h.is_cancelled() {
                            tokio::spawn(async move {
                                let _ = ws.close().await;
                            });
                            return;
                        }

                        let ws_arc = Arc::new(ws);
                        let entry = PoolEntry {
                            ws: ws_arc.clone(),
                            domain: dom,
                            created: now_unix(),
                        };

                        if pool_h.generation.load(Ordering::SeqCst) == gen && !cancel_h.is_cancelled() {
                            if let Err(unpushed) = state_h.push_if_below(entry, 8) {
                                tokio::spawn(async move {
                                    let _ = unpushed.ws.close().await;
                                });
                            }
                        } else {
                            tokio::spawn(async move {
                                let _ = ws_arc.close().await;
                            });
                        }
                    }
                });
            }
        }
    }

    pub async fn refill(
        self: Arc<Self>,
        slot: DcSlot,
        state: Arc<SlotState>,
        gen: u64,
        cancel: CancellationToken,
    ) {
        let target = self.target_size(slot);
        let needed = {
            let current = state.ready_count();
            let inflight = state.in_flight.load(Ordering::Relaxed);
            if current + inflight >= target {
                0
            } else {
                let n = target - (current + inflight);
                state.in_flight.fetch_add(n, Ordering::Relaxed);
                n
            }
        };
        if needed == 0 {
            return;
        }
        self.refill_needed(slot, state, needed, gen, cancel).await;
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

        let slot_states: Vec<Arc<SlotState>> = {
            let map = self.slots.read();
            map.values().cloned().collect()
        };

        for state in slot_states {
            let mut to_close = Vec::new();
            {
                let mut q = state.queue.lock();
                let mut active = VecDeque::with_capacity(q.len());
                while let Some(entry) = q.pop_front() {
                    if !is_pool_entry_usable(&entry, now) {
                        to_close.push(entry.ws);
                    } else {
                        active.push_back(entry);
                    }
                }
                *q = active;
            }
            for ws in to_close {
                tokio::spawn(async move {
                    let _ = ws.close().await;
                });
            }
        }
    }

    pub async fn warmup(self: &Arc<Self>, dc_opt_map: &HashMap<i32, String>) {
        self.prewarm(dc_opt_map).await;
    }

    /// Predictive non-destructive prewarm on startup and screen/power event.
    /// Never alters generation of existing flows, never drops active bridges,
    /// and pre-warms 1–2 standby sockets for DC2 / demanded slots if dial budget allows.
    pub async fn prewarm(self: &Arc<Self>, _dc_opt_map: &HashMap<i32, String>) {
        let (enabled, has_user_worker, domains_empty, user_domain) = {
            let cfg = CFPROXY.read();
            (
                CFPROXY_ENABLED.load(Ordering::Relaxed),
                !cfg.user_domain.trim().is_empty(),
                cfg.domains.is_empty(),
                cfg.user_domain.trim().to_string(),
            )
        };
        if !enabled || (!has_user_worker && domains_empty) {
            return;
        }
        if has_user_worker && cfproxy_429_cooldown_remaining_scoped(&user_domain, ProtoScope::Mtproto) != Duration::ZERO {
            crate::ldebug!("WsPool::prewarm: skipped (user worker is in cooldown)");
            return;
        }
        if !crate::budget::DIAL_BUDGET
            .has_free_budget_for(crate::budget::FlowCategory::Background)
            .await
        {
            crate::linfo!("WsPool::prewarm: skipped (dial budget busy or queued user flow)");
            return;
        }

        let mut candidate_slots: Vec<DcSlot> = {
            let map = self.demand.read();
            map.iter()
                .filter(|(_, d)| {
                    d.last_requested.elapsed() < Duration::from_secs(600)
                })
                .map(|(slot, _)| *slot)
                .collect()
        };

        // Aggressive pre-warm: DC2 chat is the default MTProto gateway for Telegram ping / first connect,
        // and DC2 media ensures instant photo/voice uploads without cold dials.
        let default_dc2_chat = DcSlot { dc: 2, is_media: 0 };
        let default_dc2_media = DcSlot { dc: 2, is_media: 1 };
        if !candidate_slots.contains(&default_dc2_chat) {
            candidate_slots.push(default_dc2_chat);
        }
        if !candidate_slots.contains(&default_dc2_media) {
            candidate_slots.push(default_dc2_media);
        }

        for slot in candidate_slots {
            let state = self.get_slot_sync(slot);
            self.trigger_refill(slot, &state);
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

        // 3. Синхронно очищаем очереди всех слотов и сбрасываем счетчик in_flight
        let slot_states: Vec<Arc<SlotState>> = {
            let map = self.slots.read();
            map.values().cloned().collect()
        };
        for s in slot_states {
            let entries = s.drain_all();
            for e in entries {
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
    is_worker: bool,
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

            if is_worker {
                let mut chunk = initial_clt_data;
                clt_dec.xor(&mut chunk);
                tg_enc.xor(&mut chunk);
                if !matches!(crate::bridge::bounded_io(ws_up.send_batch(&[chunk]), &cancel_up, BRIDGE_WRITE_TIMEOUT).await, Ok(Ok(()))) {
                    cancel_up.cancel();
                    return;
                }
            } else {
                let frames = splitter.process(&initial_clt_data, &mut clt_dec, &mut tg_enc);
                if !frames.is_empty() {
                    if !matches!(crate::bridge::bounded_io(ws_up.send_batch(&frames), &cancel_up, BRIDGE_WRITE_TIMEOUT).await, Ok(Ok(()))) {
                        cancel_up.cancel();
                        return;
                    }
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

            if is_worker {
                // WsFraming::Tunnel: Cloudflare Worker is a streaming TCP tunnel, not an MTProto packet parser.
                // Forwarding raw chunks directly (bounded to WS_BRIDGE_CHUNK_SIZE = 64 KiB) avoids
                // accumulating packets > 1 MiB which violates Cloudflare Worker's 1 MiB message limit!
                let mut chunk = chunk_data;
                clt_dec.xor(&mut chunk);
                tg_enc.xor(&mut chunk);
                if !matches!(crate::bridge::bounded_io(ws_up.send_batch(&[chunk]), &cancel_up, BRIDGE_WRITE_TIMEOUT).await, Ok(Ok(()))) {
                    break;
                }
            } else {
                let frames = splitter.process(&chunk_data, &mut clt_dec, &mut tg_enc);
                if !frames.is_empty() {
                    if !matches!(crate::bridge::bounded_io(ws_up.send_batch(&frames), &cancel_up, BRIDGE_WRITE_TIMEOUT).await, Ok(Ok(()))) {
                        break;
                    }
                }
            }
        }

        if !clean_eof { cancel_up.cancel(); return; }
        if !is_worker {
            let tail = splitter.flush(&mut tg_enc);
            if !tail.is_empty() {
                if !matches!(crate::bridge::bounded_io(ws_up.send_batch(&tail), &cancel_up, BRIDGE_WRITE_TIMEOUT).await, Ok(Ok(()))) {
                    cancel_up.cancel(); return;
                }
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


async fn cfproxy_acquire_ws(
    pool: &Arc<WsPool>,
    dc: i32,
    is_media: bool,
    cancel_token: &CancellationToken,
) -> Option<(Arc<RawWebSocket>, String, bool)> {
    pool.record_demand(dc, is_media);

    let (use_cf, user_domain) = {
        let cfg = CFPROXY.read();
        (
            CFPROXY_ENABLED.load(Ordering::Relaxed),
            cfg.user_domain.trim().to_string(),
        )
    };
    if !use_cf {
        return None;
    }

    let effective_dc = DC_OVERRIDES.get(&dc).copied().unwrap_or(dc);

    // Tier 1: User's Cloudflare Worker (WSS /apiws with Flowseal Media DC4 Fix)
    if !user_domain.is_empty() {
        let remaining = cfproxy_429_cooldown_remaining_scoped(&user_domain, ProtoScope::Mtproto);
        if remaining == Duration::ZERO {
            // Tier 1a: Instant capture from pre-warmed WsPool socket pool (0 ms)
            if let Some((ws, domain)) = pool.get(dc, is_media).await {
                linfo!(
                    " DC{}{} acquired from WsPool Worker socket pool (0 ms): {}",
                    dc,
                    media_tag(is_media),
                    domain
                );
                return Some((ws, domain, true));
            }

            let target_dst = canonical_dc_ipv4(effective_dc);
            let session_id = format!("{:016x}", rand::random::<u64>());
            let path = format!(
                "/apiws?dst={}&dc={}&media={}&sid={}",
                target_dst,
                effective_dc,
                if is_media { "1" } else { "0" },
                session_id
            );
            ldebug!(
                "MTProto Worker attempt {} -> DC{}{} (dst={})",
                user_domain, effective_dc, media_tag(is_media), target_dst
            );
            let is_mobile = MOBILE_NETWORK.load(Ordering::Relaxed);
            let worker_timeout = if is_mobile {
                crate::config::adaptive_ws_connect_timeout().clamp(3.2, 4.2)
            } else {
                crate::config::adaptive_ws_connect_timeout().clamp(3.5, 5.0)
            };
            let dial_res = tokio::select! {
                _ = cancel_token.cancelled() => return None,
                res = cf_connect_domain_with_category(
                    &user_domain,
                    &path,
                    None,
                    worker_timeout,
                    crate::budget::FlowCategory::UserFlow,
                    Some(cancel_token),
                ) => res,
            };
            let (ws_opt, resolved_ip, err_opt) = dial_res;
            if let Some(ws) = ws_opt {
                if !resolved_ip.is_empty() {
                    ldebug!("MTProto Worker ok {} via {}", user_domain, resolved_ip);
                } else {
                    ldebug!("MTProto Worker ok {}", user_domain);
                }
                clear_cfproxy_429_cooldown_scoped(&user_domain, ProtoScope::Mtproto);
                return Some((Arc::new(ws), user_domain, true));
            }
            if let Some(e) = err_opt {
                if is_http_status_error(&e, 429) {
                    mark_cfproxy_429_cooldown_scoped(&user_domain, ProtoScope::Mtproto, &e);
                }
                lwarn!(
                    "MTProto Worker fail {}: {}, Flowseal Anycast SDN is disabled in worker mode",
                    user_domain, e.compact()
                );
            }
        } else {
            ldebug!(
                "MTProto Worker {} is cooling down ({:.0}s)",
                user_domain, remaining.as_secs_f64()
            );
        }
        // In Worker mode, Flowseal SDN is strictly NOT used as requested
        return None;
    }

    // Tier 2a: Instant capture from pre-warmed WsPool socket pool (0 ms)
    if let Some((ws, domain)) = pool.get(dc, is_media).await {
        linfo!(
            " DC{}{} acquired from WsPool socket pool (0 ms): {}",
            dc,
            media_tag(is_media),
            domain
        );
        return Some((ws, domain, false));
    }

    // Tier 2b: Parallel Happy Eyeballs Anycast CDN race
    let race_res = cfproxy_acquire_ws_race(dc, is_media, cancel_token).await;
    if race_res.is_some() {
        // Replenish slot in background for next request
        let slot = DcSlot {
            dc,
            is_media: is_media_int(is_media),
        };
        let state = pool.get_slot_sync(slot);
        pool.trigger_refill(slot, &state);
        return race_res.map(|(ws, d)| (ws, d, false));
    }

    // Direct web.telegram.org fallback is omitted: it is blocked by TSPU in Russia and causes 3.5s stalls.
    None
}

pub(crate) async fn cfproxy_acquire_ws_race(
    dc: i32,
    is_media: bool,
    cancel_token: &CancellationToken,
) -> Option<(Arc<RawWebSocket>, String)> {
    cfproxy_acquire_raw_ws_race(dc, is_media, ProtoScope::Mtproto, cancel_token)
        .await
        .map(|(ws, dom)| (Arc::new(ws), dom))
}

pub(crate) async fn cfproxy_acquire_raw_ws_race(
    dc: i32,
    is_media: bool,
    scope: ProtoScope,
    cancel_token: &CancellationToken,
) -> Option<(RawWebSocket, String)> {
    let expected = crate::generation_guard::snapshot();
    let (enabled, mut domains) = {
        let cfg = CFPROXY.read();
        (CFPROXY_ENABLED.load(Ordering::Relaxed), cfg.domains.clone())
    };
    if !enabled {
        return None;
    }
    if domains.is_empty() {
        domains = crate::cfproxy::default_cfproxy_domains();
    }

    let effective_dc = DC_OVERRIDES.get(&dc).copied().unwrap_or(dc);

    // Parallel Anycast CDN targets (kws{effective_dc}.{domain}/apiws)
    let mut candidate_targets: Vec<(String, String)> = Vec::new();

    if !domains.is_empty() {
        let ordered = crate::balancer::BALANCER
            .read()
            .get_domains_for_dc(effective_dc, is_media);
        let candidate_list = if !ordered.is_empty() {
            ordered
        } else {
            domains.clone()
        };
        let diverse_ordered = crate::node_independence::NODE_INDEPENDENCE_TRACKER
            .read()
            .select_diverse_race_candidates(&candidate_list, 4);
        for d in &diverse_ordered {
            let remaining = cfproxy_429_cooldown_remaining_scoped(d, scope);
            if remaining == Duration::ZERO {
                let domain = format!("kws{}.{}", effective_dc, d);
                candidate_targets.push((domain, "/apiws".to_string()));
            }
        }
        for d in &candidate_list {
            if !diverse_ordered.contains(d) && cfproxy_429_cooldown_remaining_scoped(d, scope) == Duration::ZERO {
                let domain = format!("kws{}.{}", effective_dc, d);
                candidate_targets.push((domain, "/apiws".to_string()));
                if candidate_targets.len() >= 6 {
                    break;
                }
            }
        }
    }

    if candidate_targets.is_empty() {
        lwarn!(
            "[{}] CF fallback DC{}{}: all Anycast CDN domains unreachable",
            scope,
            dc,
            media_tag(is_media)
        );
        return None;
    }

    let m_tag = media_tag(is_media);
    ldebug!(
        "[{}] Happy Eyeballs Race: {} targets for DC{}{}",
        scope,
        candidate_targets.len(),
        dc,
        m_tag
    );

    let (tx, mut rx) =
        tokio::sync::mpsc::channel::<(RawWebSocket, String)>(candidate_targets.len());
    let is_mobile = MOBILE_NETWORK.load(Ordering::Relaxed);
    let stagger_step = if is_mobile {
        Duration::from_millis(100)
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
                    let candidate_timeout = crate::config::adaptive_ws_connect_timeout().clamp(5.0, 8.0);
                    let (ws, resolved_ip, err) = cf_connect_domain_with_category(
                        &dom,
                        &path,
                        None,
                        candidate_timeout,
                        crate::budget::FlowCategory::UserFlow,
                        Some(&cancel),
                    ).await;

                    if let Some(w) = ws {
                        if cancel.is_cancelled() {
                            ldebug!("[{}] race runner-up late 101 gracefully closing for {}", scope, dom);
                            let _ = w.close().await;
                            return None;
                        }
                        if !resolved_ip.is_empty() {
                            ldebug!("[{}] race ok {} via {}", scope, dom, resolved_ip);
                        } else {
                            ldebug!("[{}] race ok {}", scope, dom);
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
                                    mark_cfproxy_429_cooldown_scoped(&dom, scope, e);
                                });
                            }
                            if !resolved_ip.is_empty() {
                                log_cf_conn_error(
                                    &format!("[{}] race fail {} via {}: {}", scope, dom, resolved_ip, e.compact()),
                                    e,
                                );
                            } else {
                                log_cf_conn_error(
                                    &format!("[{}] race fail {}: {}", scope, dom, e.compact()),
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

    let mut winning_res: Option<(RawWebSocket, String)> = None;

    tokio::select! {
        _ = cancel_token.cancelled() => {
            ldebug!("[{}] connection race cancelled by parent", scope);
            child_cancel.cancel();
        }
        msg = rx.recv() => {
            child_cancel.cancel();
            if let Some((ws, winner_domain)) = msg {
                if !crate::generation_guard::is_current(expected) {
                    let _ = ws.close().await;
                } else {
                    let accepted = crate::generation_guard::apply_if_current(expected, || {
                        clear_cfproxy_429_cooldown_scoped(&winner_domain, scope);
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
                        linfo!("[{}] endpoint selected: {}", scope, winner_domain);
                        winning_res = Some((ws, winner_domain));
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
        ldebug!("[{}] closing runner-up connection to {}", scope, extra_dom);
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
// dispatch_mtproto_uplink — основной диспетчер MTProto сессий в WSS туннель
// ---------------------------------------------------------------------------

/// Основной диспетчер MTProto соединений в распределенный Anycast CDN / Cloudflare Worker WSS туннель.
///
/// Прямой TCP fallback на публичные IP дата-центров Telegram категорически заблокирован ТСПУ
/// на территории РФ, поэтому маршрутизация пакетов выполняется исключительно через защищенный
/// WebSocket (WSS) транспорт с пулом предварительно прогретых соединений (WsPool).
/// В случае недоступности WSS аплинка сессия прерывается для предотвращения раскрытия прямого TCP трафика.
pub async fn dispatch_mtproto_uplink(
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
        if let Some((ws, chosen_domain, is_worker)) =
            cfproxy_acquire_ws(pool, dc, is_media, &cancel_token).await
        {
            STATS.connections_cfproxy.fetch_add(1, Ordering::Relaxed);
            linfo!(
                " DC{}{} connected via {}: {}",
                dc,
                media_tag(is_media),
                if is_worker { "Worker WSS" } else { "CDN" },
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
                is_worker,
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

    set_client_sock_opts(&conn);

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
    let dc_idx = if is_media { -effective_dc } else { effective_dc };
    LittleEndian::write_u16(&mut dc_bytes, (dc_idx as i16) as u16);

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
    dispatch_mtproto_uplink(
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


#[cfg(test)]
mod tests {
    use super::*;


    #[test]
    fn test_mtproto_media_dc_negation() {
        let dc = 2;
        let is_media = true;
        let effective_dc = DC_OVERRIDES.get(&dc).copied().unwrap_or(dc);
        let dc_idx = if is_media { -effective_dc } else { effective_dc };
        let mut dc_bytes = [0u8; 2];
        LittleEndian::write_u16(&mut dc_bytes, (dc_idx as i16) as u16);
        let decoded = LittleEndian::read_u16(&dc_bytes) as i16;
        assert_eq!(decoded, -2);
        assert!(decoded < 0);

        let is_chat = false;
        let dc_idx_chat = if is_chat { -effective_dc } else { effective_dc };
        LittleEndian::write_u16(&mut dc_bytes, (dc_idx_chat as i16) as u16);
        let decoded_chat = LittleEndian::read_u16(&dc_bytes) as i16;
        assert_eq!(decoded_chat, 2);
        assert!(decoded_chat > 0);
    }


    #[tokio::test]
    async fn test_ws_pool_demand_driven_sizing() {
        let cancel = CancellationToken::new();
        let pool = Arc::new(WsPool::new(cancel));

        let slot_dc2_chat = DcSlot { dc: 2, is_media: 0 };
        let slot_dc4_chat = DcSlot { dc: 4, is_media: 0 };
        let slot_dc2_media = DcSlot { dc: 2, is_media: 1 };
        let slot_dc4_media = DcSlot { dc: 4, is_media: 1 };

        // 1. Mobile profile: DC2 chat and media have baseline target 1 for 0 ms pre-warm, DC4 slots are 0 without demand
        MOBILE_NETWORK.store(true, Ordering::Relaxed);
        assert_eq!(pool.target_size(slot_dc2_chat), 1);
        assert_eq!(pool.target_size(slot_dc4_chat), 0);
        assert_eq!(pool.target_size(slot_dc2_media), 1);
        assert_eq!(pool.target_size(slot_dc4_media), 0);

        // 2. Client demands DC4 chat -> target size becomes 1 (standby)
        pool.record_demand(4, false);
        assert_eq!(pool.target_size(slot_dc4_chat), 1);

        // 3. Client demands DC2 media -> target size becomes 1
        pool.record_demand(2, true);
        assert_eq!(pool.target_size(slot_dc2_media), 1);

        // 4. Wi-Fi profile: baseline 2 for DC2 chat (configured.min(2).max(1)), expands with useful RX
        MOBILE_NETWORK.store(false, Ordering::Relaxed);
        MTPROTO_STANDBY_PER_ACTIVE_SLOT_REQUESTED.store(4, Ordering::Relaxed);
        assert_eq!(pool.target_size(slot_dc2_chat), 2); // Baseline 2 on Wi-Fi until sustained useful RX

        pool.record_demand(2, false);
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

    #[tokio::test]
    async fn test_is_pool_entry_usable_cellular_and_wifi() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let connect_fut = tokio::net::TcpStream::connect(addr);
        let accept_fut = listener.accept();
        let (client, server_res) = tokio::join!(connect_fut, accept_fut);
        let client = client.unwrap();
        let (server, _) = server_res.unwrap();
        drop(server);

        let raw_ws = Arc::new(RawWebSocket::from_plain_stream_for_heartbeat_test(client));
        let now = 1000i64;

        let entry_fresh = PoolEntry {
            ws: raw_ws.clone(),
            domain: "test.worker.dev".to_string(),
            created: now - 10,
        };

        let entry_cellular_stale = PoolEntry {
            ws: raw_ws.clone(),
            domain: "test.worker.dev".to_string(),
            created: now - 23,
        };

        let entry_wifi_stale = PoolEntry {
            ws: raw_ws.clone(),
            domain: "test.worker.dev".to_string(),
            created: now - 46,
        };

        // 1. Mobile profile (TTL = 22s)
        MOBILE_NETWORK.store(true, Ordering::Relaxed);
        assert!(is_pool_entry_usable(&entry_fresh, now));
        assert!(!is_pool_entry_usable(&entry_cellular_stale, now));
        assert!(!is_pool_entry_usable(&entry_wifi_stale, now));

        // 2. Wi-Fi profile (TTL = 45s)
        MOBILE_NETWORK.store(false, Ordering::Relaxed);
        assert!(is_pool_entry_usable(&entry_fresh, now));
        assert!(is_pool_entry_usable(&entry_cellular_stale, now));
        assert!(!is_pool_entry_usable(&entry_wifi_stale, now));

        // 3. Closed socket is never usable
        raw_ws.closed.store(true, Ordering::Relaxed);
        assert!(!is_pool_entry_usable(&entry_fresh, now));

        MOBILE_NETWORK.store(false, Ordering::Relaxed);
    }

    #[tokio::test]
    async fn test_ws_pool_nonblocking_sharded_slots_and_in_flight() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let connect_fut = tokio::net::TcpStream::connect(addr);
        let accept_fut = listener.accept();
        let (client, server_res) = tokio::join!(connect_fut, accept_fut);
        let client = client.unwrap();
        let (server, _) = server_res.unwrap();
        drop(server);

        let raw_ws = Arc::new(RawWebSocket::from_plain_stream_for_heartbeat_test(client));
        let state = SlotState::new();
        assert_eq!(state.ready_count(), 0);
        assert_eq!(state.in_flight_count(), 0);

        // 1. in_flight increment and saturating decrement
        state.in_flight.fetch_add(2, Ordering::Relaxed);
        assert_eq!(state.in_flight_count(), 2);
        state.dec_in_flight(1);
        assert_eq!(state.in_flight_count(), 1);
        state.dec_in_flight(5); // underflow guard saturating_sub
        assert_eq!(state.in_flight_count(), 0);

        // 2. push_if_below and instant non-blocking try_pop_fresh (< 1 us)
        let entry = PoolEntry {
            ws: raw_ws.clone(),
            domain: "my-worker.example.com".to_string(),
            created: now_unix(),
        };
        assert!(state.push_if_below(entry, 2).is_ok());
        assert_eq!(state.ready_count(), 1);

        let start = Instant::now();
        let popped = state.try_pop_fresh(Duration::from_secs(30));
        let elapsed = start.elapsed();
        assert!(popped.is_some());
        assert!(elapsed < Duration::from_millis(50));
        assert_eq!(state.ready_count(), 0);

        // 3. Sharded WsPool slot retrieval under concurrent read locks
        let cancel = CancellationToken::new();
        let pool = Arc::new(WsPool::new(cancel));
        let slot1 = DcSlot { dc: 2, is_media: 0 };
        let slot2 = DcSlot { dc: 4, is_media: 1 };
        let s1 = pool.get_slot_sync(slot1);
        let s2 = pool.get_slot_sync(slot2);
        assert!(!Arc::ptr_eq(&s1, &s2));
        assert!(Arc::ptr_eq(&s1, &pool.get_slot_sync(slot1)));

        // 4. Reset and drain
        s1.in_flight.store(3, Ordering::Relaxed);
        let drained = s1.drain_all();
        assert_eq!(drained.len(), 0);
        assert_eq!(s1.in_flight_count(), 0);
    }
}
