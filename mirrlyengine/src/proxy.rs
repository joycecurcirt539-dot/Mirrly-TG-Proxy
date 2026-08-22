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
use std::time::Duration;
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
    }
    if let Some(t) = map.get(&dc) {
        if !t.is_empty() {
            return Some(t.clone());
        }
    }
    None
}

pub fn resolve_fallback_target(dc: i32, _is_media: bool) -> String {
    DC_DEFAULT_IPS
        .get(&dc)
        .map(|s| s.to_string())
        .unwrap_or_default()
}

pub fn ws_domains(dc: i32, is_media: bool) -> Vec<String> {
    let mut effective_dc = dc;
    if let Some(o) = DC_OVERRIDES.get(&dc) {
        effective_dc = *o;
    }
    if is_media {
        vec![
            format!("kws{}-1.web.telegram.org", effective_dc),
            format!("kws{}.web.telegram.org", effective_dc),
        ]
    } else {
        vec![
            format!("kws{}.web.telegram.org", effective_dc),
            format!("kws{}-1.web.telegram.org", effective_dc),
        ]
    }
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
    pub created: i64,
}

struct SlotState {
    queue: Mutex<std::collections::VecDeque<PoolEntry>>,
    refilling: AtomicI32,
}

pub struct WsPool {
    slots: Mutex<HashMap<DcSlot, Arc<SlotState>>>,
    cancel_token: CancellationToken,
    cancel_refill: Arc<parking_lot::RwLock<CancellationToken>>,
    generation: AtomicU64,
}

impl WsPool {
    pub fn new(cancel_token: CancellationToken) -> WsPool {
        let cancel_refill = Arc::new(parking_lot::RwLock::new(cancel_token.child_token()));
        WsPool {
            slots: Mutex::new(HashMap::new()),
            cancel_token,
            cancel_refill,
            generation: AtomicU64::new(0),
        }
    }

    async fn get_slot(&self, slot: DcSlot) -> Arc<SlotState> {
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
        target_ip: String,
        domains: Vec<String>,
    ) -> Option<Arc<RawWebSocket>> {
        let slot = DcSlot {
            dc,
            is_media: is_media_int(is_media),
        };
        let state = self.get_slot(slot).await;
        let now = now_unix();

        let mut ws: Option<Arc<RawWebSocket>> = None;
        {
            let mut q = state.queue.lock().await;
            while let Some(entry) = q.pop_front() {
                if is_pool_entry_usable(&entry, now) {
                    ws = Some(entry.ws);
                    STATS.pool_hits.fetch_add(1, Ordering::Relaxed);
                    break;
                } else {
                    let e_ws = entry.ws;
                    tokio::spawn(async move {
                        e_ws.close().await;
                    });
                }
            }
            if ws.is_none() {
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
                pool.refill(st, target_ip, domains, gen, cancel).await;
            });
        }

        ws
    }

    async fn refill(
        self: Arc<Self>,
        state: Arc<SlotState>,
        target_ip: String,
        domains: Vec<String>,
        gen: u64,
        cancel: CancellationToken,
    ) {
        let cur_len = state.queue.lock().await.len();
        let needed = POOL_SIZE.load(Ordering::Relaxed) as usize;
        let needed = needed.saturating_sub(cur_len);
        if needed == 0 || self.generation.load(Ordering::SeqCst) != gen || cancel.is_cancelled() {
            state.refilling.store(0, Ordering::SeqCst);
            return;
        }

        let mut handles = Vec::new();
        for _ in 0..needed {
            let target_ip = target_ip.clone();
            let domains = domains.clone();
            let cancel_handle = cancel.clone();
            handles.push(tokio::spawn(async move {
                tokio::select! {
                    _ = cancel_handle.cancelled() => None,
                    r = connect_one_ws(&target_ip, &domains) => r,
                }
            }));
        }

        for h in handles {
            if let Ok(Some(ws)) = h.await {
                // Если поколение пула изменилось во время коннекта или токен отменен,
                // отбрасываем и немедленно закрываем сокет от старого интерфейса
                if self.generation.load(Ordering::SeqCst) != gen || cancel.is_cancelled() {
                    tokio::spawn(async move {
                        ws.close().await;
                    });
                    continue;
                }

                let now = now_unix();
                let ws_arc = Arc::new(ws);
                let mut q = state.queue.lock().await;
                if q.len() < 16 {
                    q.push_back(PoolEntry { ws: ws_arc, created: now });
                } else {
                    drop(q);
                    tokio::spawn(async move {
                        ws_arc.close().await;
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
            let mut interval = tokio::time::interval(WS_POOL_PING_INTERVAL);
            interval.tick().await;
            loop {
                tokio::select! {
                    _ = cancel.cancelled() => return,
                    _ = interval.tick() => {
                        if let Some(pool_strong) = pool.upgrade() {
                            pool_strong.ping_and_clean_idle_sockets().await;
                        } else {
                            return;
                        }
                    }
                }
            }
        });
    }

    async fn ping_and_clean_idle_sockets(&self) {
        let now = now_unix();
        let max_age = WS_POOL_REUSE_MAX_AGE as i64;

        let slot_states: Vec<Arc<SlotState>> = {
            let map = self.slots.lock().await;
            map.values().cloned().collect()
        };

        for state in slot_states {
            let mut sockets_to_ping = Vec::new();
            {
                let mut q = state.queue.lock().await;
                let mut active = std::collections::VecDeque::with_capacity(q.len());
                while let Some(entry) = q.pop_front() {
                    if entry.ws.is_closed() || (now - entry.created) > max_age {
                        let ws = entry.ws.clone();
                        tokio::spawn(async move {
                            ws.close().await;
                        });
                    } else {
                        sockets_to_ping.push(entry.ws.clone());
                        active.push_back(entry);
                    }
                }
                *q = active;
            }

            for ws in sockets_to_ping {
                let ws_clone = ws.clone();
                tokio::spawn(async move {
                    if ws_clone.send_ping().await.is_err() {
                        ws_clone.close().await;
                    }
                });
            }
        }
    }

    pub async fn warmup(self: &Arc<Self>, dc_opt_map: &HashMap<i32, String>) {
        let gen = self.generation.load(Ordering::SeqCst);
        let cancel = self.cancel_refill.read().clone();
        for (dc, target_ip) in dc_opt_map {
            if target_ip.is_empty() {
                continue;
            }
            for is_media in [false, true] {
                let domains = ws_domains(*dc, is_media);
                let slot = DcSlot {
                    dc: *dc,
                    is_media: is_media_int(is_media),
                };
                let state = self.get_slot(slot).await;
                if state
                    .refilling
                    .compare_exchange(0, 1, Ordering::SeqCst, Ordering::SeqCst)
                    .is_ok()
                {
                    let pool = self.clone();
                    let st = state.clone();
                    let ip = target_ip.clone();
                    let doms = domains.clone();
                    let c = cancel.clone();
                    tokio::spawn(async move {
                        pool.refill(st, ip, doms, gen, c).await;
                    });
                }
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
                    ws.close().await;
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
    _dc: i32,
    _dst: String,
    _port: u16,
    _is_media: bool,
    mut splitter: Option<MsgSplitter>,
    mut clt_dec: TrackedStream,
    mut clt_enc: TrackedStream,
    mut tg_enc: TrackedStream,
    mut tg_dec: TrackedStream,
    is_faketls: bool,
    initial_clt_data: Vec<u8>,
    cancel_token: CancellationToken,
) {
    let last_activity = Arc::new(Mutex::new(std::time::Instant::now()));
    let cancel = Arc::new(tokio::sync::Notify::new());

    let (mut conn_read, mut conn_write) = conn.into_split();

    // ping keepalive
    let ws_ping = ws.clone();
    let la_ping = last_activity.clone();
    let cancel_ping = cancel.clone();
    let cancel_token_ping = cancel_token.clone();
    let ping_task = tokio::spawn(async move {
        let mut interval = tokio::time::interval(BRIDGE_PING_INTERVAL);
        interval.tick().await;
        loop {
            tokio::select! {
                _ = cancel_token_ping.cancelled() => return,
                _ = cancel_ping.notified() => return,
                _ = interval.tick() => {
                    let idle = la_ping.lock().await.elapsed();
                    if idle >= Duration::from_secs(10) {
                        if ws_ping.send_ping().await.is_err() {
                            cancel_ping.notify_waiters();
                            return;
                        }
                    }
                }
            }
        }
    });

    // up: client -> ws
    let ws_up = ws.clone();
    let la_up = last_activity.clone();
    let cancel_up = cancel.clone();
    let cancel_token_up = cancel_token.clone();
    let up_task = tokio::spawn(async move {
        // 1. If there were extra bytes in the initial TLS application record, process them first
        if !initial_clt_data.is_empty() {
            let mut init_chunk = initial_clt_data;
            let n = init_chunk.len();
            STATS.bytes_up.fetch_add(n as i64, Ordering::Relaxed);
            *la_up.lock().await = std::time::Instant::now();

            clt_dec.xor(&mut init_chunk);
            tg_enc.xor(&mut init_chunk);

            let send_err = {
                if let Some(sp) = splitter.as_mut() {
                    let parts = sp.split(&init_chunk);
                    if parts.len() > 1 {
                        ws_up.send_batch(&parts).await.is_err()
                    } else if parts.len() == 1 {
                        ws_up.send(&parts[0]).await.is_err()
                    } else {
                        false
                    }
                } else {
                    ws_up.send(&init_chunk).await.is_err()
                }
            };
            if send_err {
                cancel_up.notify_waiters();
                return;
            }
        }

        let mut buf = vec![0u8; WS_BRIDGE_CHUNK_SIZE];
        loop {
            let chunk_data: Vec<u8> = if is_faketls {
                let read_res = tokio::select! {
                    _ = cancel_token_up.cancelled() => break,
                    _ = cancel_up.notified() => break,
                    r = tokio::time::timeout(BRIDGE_READ_TIMEOUT, crate::faketls::read_tls_app_data(&mut conn_read)) => r,
                };
                match read_res {
                    Ok(Ok(d)) if !d.is_empty() => d,
                    _ => {
                        if let Some(sp) = splitter.as_mut() {
                            let tail = sp.flush();
                            if !tail.is_empty() {
                                let r = if tail.len() > 1 {
                                    ws_up.send_batch(&tail).await
                                } else {
                                    ws_up.send(&tail[0]).await
                                };
                                if r.is_err() {
                                    break;
                                }
                            }
                        }
                        break;
                    }
                }
            } else {
                let read_res = tokio::select! {
                    _ = cancel_token_up.cancelled() => break,
                    _ = cancel_up.notified() => break,
                    r = tokio::time::timeout(BRIDGE_READ_TIMEOUT, conn_read.read(&mut buf)) => r,
                };
                let n = match read_res {
                    Ok(Ok(0)) => {
                        if let Some(sp) = splitter.as_mut() {
                            let tail = sp.flush();
                            if !tail.is_empty() {
                                let r = if tail.len() > 1 {
                                    ws_up.send_batch(&tail).await
                                } else {
                                    ws_up.send(&tail[0]).await
                                };
                                if r.is_err() {
                                    break;
                                }
                            }
                        }
                        break;
                    }
                    Ok(Ok(n)) => n,
                    Ok(Err(_)) => break,
                    Err(_) => break,
                };
                buf[..n].to_vec()
            };

            let mut chunk = chunk_data;
            let n = chunk.len();
            STATS.bytes_up.fetch_add(n as i64, Ordering::Relaxed);
            *la_up.lock().await = std::time::Instant::now();

            clt_dec.xor(&mut chunk);
            tg_enc.xor(&mut chunk);

            let send_err = {
                if let Some(sp) = splitter.as_mut() {
                    let parts = sp.split(&chunk);
                    if parts.len() > 1 {
                        ws_up.send_batch(&parts).await.is_err()
                    } else if parts.len() == 1 {
                        ws_up.send(&parts[0]).await.is_err()
                    } else {
                        false
                    }
                } else {
                    ws_up.send(&chunk).await.is_err()
                }
            };
            if send_err {
                break;
            }
        }
        cancel_up.notify_waiters();
    });

    // down: ws -> client
    let ws_down = ws.clone();
    let la_down = last_activity.clone();
    let cancel_down = cancel.clone();
    let cancel_token_down = cancel_token.clone();
    let down_task = tokio::spawn(async move {
        loop {
            let recv_res = tokio::select! {
                _ = cancel_token_down.cancelled() => break,
                _ = cancel_down.notified() => break,
                r = ws_down.recv_with_timeout(BRIDGE_READ_TIMEOUT) => r,
            };
            let mut data = match recv_res {
                Ok(d) => d,
                Err(_) => break,
            };
            let n = data.len();
            STATS.bytes_down.fetch_add(n as i64, Ordering::Relaxed);
            *la_down.lock().await = std::time::Instant::now();

            tg_dec.xor(&mut data);
            clt_enc.xor(&mut data);

            if is_faketls {
                if crate::faketls::write_tls_app_data(&mut conn_write, &data).await.is_err() {
                    break;
                }
            } else {
                if conn_write.write_all(&data).await.is_err() {
                    break;
                }
            }
        }
        cancel_down.notify_waiters();
    });

    let _ = up_task.await;
    let _ = down_task.await;
    cancel.notify_waiters();
    ping_task.abort();

    ws.close().await;
}

// ---------------------------------------------------------------------------
// Cfproxy fallback
// ---------------------------------------------------------------------------

async fn try_cfproxy_base_domain(dc: i32, base_domain: &str) -> (Option<RawWebSocket>, String) {
    let base_domain = normalize_cf_domain(base_domain);
    if base_domain.is_empty() {
        return (None, String::new());
    }
    let remaining = cfproxy_429_cooldown_remaining(&base_domain);
    if remaining > Duration::ZERO {
        ldebug!(
            " CF skip {}: 429 cooldown {:.0}s",
            base_domain,
            remaining.as_secs_f64().ceil()
        );
        return (None, String::new());
    }
    let _permit = match acquire_cfproxy_attempt_slot().await {
        Some(p) => p,
        None => return (None, String::new()),
    };

    let domain = format!("kws{}.{}", dc, base_domain);
    ldebug!(" CF try {}", domain);

    let (ws, resolved_ip, err) = cf_connect_domain(&domain, "/apiws", 5.0).await;
    if let Some(e) = err {
        if is_http_status_error(&e, 429) {
            mark_cfproxy_429_cooldown(&base_domain, &e);
        }
        if !resolved_ip.is_empty() {
            log_cf_conn_error(
                &format!(" CF fail {} via {}: {}", domain, resolved_ip, e.compact()),
                &e,
            );
        } else {
            log_cf_conn_error(&format!(" CF fail {}: {}", domain, e.compact()), &e);
        }
        return (None, String::new());
    }

    clear_cfproxy_429_cooldown(&base_domain);
    if !resolved_ip.is_empty() {
        ldebug!(" CF ok {} via {}", domain, resolved_ip);
    } else {
        ldebug!(" CF ok {} via hostname", domain);
    }
    (ws, base_domain)
}

async fn cfproxy_acquire_ws(
    dc: i32,
    is_media: bool,
    cancel_token: &CancellationToken,
) -> Option<(RawWebSocket, String)> {
    let (enabled, user_domain, domains) = {
        let cfg = CFPROXY.read();
        (
            CFPROXY_ENABLED.load(Ordering::Relaxed),
            cfg.user_domain.clone(),
            cfg.domains.clone(),
        )
    };
    if !enabled {
        return None;
    }

    // 1. Priority 1: User-configured or active Cloudflare Worker (100% priority)
    if !user_domain.is_empty() {
        let target_ip = if is_media {
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
        };
        let path = format!("/tcp?target={}:443", target_ip);
        ldebug!(" CF Worker try {} via {}", target_ip, user_domain);
        let (ws, resolved_ip, err) = cf_connect_domain(&user_domain, &path, 5.0).await;
        if let Some(w) = ws {
            if !resolved_ip.is_empty() {
                ldebug!(" CF Worker ok {} via {}", user_domain, resolved_ip);
            } else {
                ldebug!(" CF Worker ok {}", user_domain);
            }
            return Some((w, user_domain));
        }
        if let Some(e) = err {
            log_cf_conn_error(&format!(" CF Worker fail {}: {}", user_domain, e.compact()), &e);
        }
    }

    // 2. Priority 2: Built-in Anycast CDN domains (Flowseal / Telegram CDN fallback)
    if domains.is_empty() {
        return None;
    }

    let ordered = crate::balancer::BALANCER.read().get_domains_for_dc(dc);
    if ordered.is_empty() {
        return None;
    }

    let m_tag = media_tag(is_media);
    ldebug!(" CF fallback DC{}{}: {} домен(ов)", dc, m_tag, ordered.len());

    let mut ws: Option<RawWebSocket> = None;
    let mut chosen_domain = String::new();

    if !ordered.is_empty() && !ordered[0].is_empty() {
        let (w, d) = try_cfproxy_base_domain(dc, &ordered[0]).await;
        ws = w;
        chosen_domain = d;
    }

    if ws.is_none() && ordered.len() > 1 {
        let remaining_domains: Vec<String> = ordered[1..].to_vec();
        let sem = Arc::new(tokio::sync::Semaphore::new(CFPROXY_FALLBACK_PARALLEL));
        let mut handles = Vec::new();
        for bd in remaining_domains {
            let sem = sem.clone();
            let cancel = cancel_token.clone();
            handles.push(tokio::spawn(async move {
                tokio::select! {
                    _ = cancel.cancelled() => None,
                    r = async {
                        let _p = sem.acquire().await.ok()?;
                        let (w, d) = try_cfproxy_base_domain(dc, &bd).await;
                        w.map(|ws| (ws, d))
                    } => r,
                }
            }));
        }
        for h in handles {
            if let Ok(Some((w, d))) = h.await {
                if ws.is_none() {
                    ws = Some(w);
                    chosen_domain = d;
                } else {
                    tokio::spawn(async move {
                        w.close().await;
                    });
                }
            }
        }
    }

    match ws {
        Some(w) => {
            if !chosen_domain.is_empty() {
                if crate::balancer::BALANCER.write().update_domain_for_dc(dc, &chosen_domain) {
                    linfo!(" CF домен для DC{} -> {}", dc, chosen_domain);
                }
            }
            Some((w, chosen_domain))
        }
        None => {
            lwarn!(" CF fallback DC{}{}: все CF домены недоступны", dc, m_tag);
            None
        }
    }
}

// ---------------------------------------------------------------------------
// do_fallback — строго Cloudflare CDN fallback (Direct TCP Fallback удален)
// ---------------------------------------------------------------------------

pub async fn do_fallback(
    conn: TcpStream,
    relay_init: &[u8],
    label: String,
    dc: i32,
    is_media: bool,
    splitter: Option<MsgSplitter>,
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
            cfproxy_acquire_ws(dc, is_media, &cancel_token).await
        {
            STATS.connections_cfproxy.fetch_add(1, Ordering::Relaxed);
            linfo!(" DC{}{} подключен через CF", dc, media_tag(is_media));

            if ws.send(relay_init).await.is_err() {
                ws.close().await;
                return false;
            }

            bridge_ws(
                conn,
                Arc::new(ws),
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
            )
            .await;
            return true;
        }
    }

    // Direct TCP Fallback полностью удален: сессия прерывается
    false
}

// ---------------------------------------------------------------------------
// Client handler
// ---------------------------------------------------------------------------

pub async fn handle_client(pool: Arc<WsPool>, mut conn: TcpStream, cancel_token: CancellationToken) {
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

    let _ = conn.set_nodelay(TCP_NODELAY.load(Ordering::Relaxed));
    let sock = socket2::SockRef::from(&conn);
    let ka = socket2::TcpKeepalive::new()
        .with_time(Duration::from_secs(30))
        .with_interval(Duration::from_secs(10))
        .with_retries(3);
    let _ = sock.set_tcp_keepalive(&ka);

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
        ldebug!("{}: FakeTLS handshake detected (0x16 0x03 0x01/0x03)", label);
        if let Err(e) = crate::faketls::handle_fake_tls_handshake(&mut conn, &initial_5).await {
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
        STATS.connections_http_reject.fetch_add(1, Ordering::Relaxed);
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
    let m_tag = media_tag(is_media);

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
    let dc_idx = if is_media { -dc } else { dc };
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

    let dc_key = (dc, is_media_int(is_media));
    let now = now_unix_f64();

    let splitter = MsgSplitter::new(&relay_init, proto);

    let target_opt = resolve_configured_target(dc, is_media);
    let dc_configured = target_opt.is_some();
    let target = target_opt.unwrap_or_default();

    let blacklisted = WS_BLACKLIST.read().get(&dc_key).copied().unwrap_or(false);

    if !dc_configured || blacklisted {
        do_fallback(
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
        return;
    }

    let fail_until = DC_FAIL_UNTIL.read().get(&dc_key).copied().unwrap_or(0.0);
    let ws_timeout = if now < fail_until {
        WS_FAIL_TIMEOUT
    } else {
        10.0
    };

    let domains = ws_domains(dc, is_media);
    let cancel_dial = cancel_token.clone();
    let dial_res = tokio::select! {
        _ = cancel_dial.cancelled() => return,
        res = async {
            if let Some(w) = pool.get(dc, is_media, target.clone(), domains.clone()).await {
                (Some(w), false, false, true)
            } else {
                let (w_opt, f_red, all_red) = connect_direct_ws(&target, &domains, ws_timeout).await;
                (w_opt.map(Arc::new), f_red, all_red, false)
            }
        } => res,
    };
    let (mut ws_opt, ws_failed_redirect, all_redirects, from_pool) = dial_res;

    if ws_opt.is_none() {
        lwarn!(" DC{}{}: все попытки WS провалены (DPI/Интернет)", dc, m_tag);
        if ws_failed_redirect && all_redirects {
            WS_BLACKLIST.write().insert(dc_key, true);
            lwarn!(" DC{}{} заблокирован (302)", dc, m_tag);
        } else {
            DC_FAIL_UNTIL.write().insert(dc_key, now + DC_FAIL_COOLDOWN);
        }
        let splitter_fb = MsgSplitter::new(&relay_init, proto);
        do_fallback(
            conn,
            &relay_init,
            label,
            dc,
            is_media,
            splitter_fb,
            &clt_decryptor,
            &clt_encryptor,
            &tg_encryptor,
            &tg_decryptor,
            is_faketls,
            initial_clt_data,
            cancel_token,
        )
        .await;
        return;
    }

    // direct init
    let mut ws = ws_opt.take().unwrap();
    let mut send_ok = ws.send(&relay_init).await.is_ok();
    if send_ok {
        ldebug!(" direct relayInit sent DC{}{}", dc, m_tag);
    } else {
        lwarn!(" direct relayInit write fail DC{}{}: closed", dc, m_tag);
        ws.close().await;

        if !from_pool {
            DC_FAIL_UNTIL.write().insert(dc_key, now + DC_FAIL_COOLDOWN);
        }

        lwarn!(" direct retry fresh ws DC{}{}", dc, m_tag);
        let (retry_ws, retry_failed_redirect, retry_all_redirects) =
            connect_direct_ws(&target, &domains, ws_timeout).await;
        match retry_ws {
            None => {
                if retry_failed_redirect && retry_all_redirects {
                    WS_BLACKLIST.write().insert(dc_key, true);
                    lwarn!(" DC{}{} заблокирован (302)", dc, m_tag);
                } else {
                    DC_FAIL_UNTIL.write().insert(dc_key, now + DC_FAIL_COOLDOWN);
                }
                lwarn!(" direct fallback DC{}{}", dc, m_tag);
                let splitter_fb = MsgSplitter::new(&relay_init, proto);
                do_fallback(
                    conn,
                    &relay_init,
                    label,
                    dc,
                    is_media,
                    splitter_fb,
                    &clt_decryptor,
                    &clt_encryptor,
                    &tg_encryptor,
                    &tg_decryptor,
                    is_faketls,
                    initial_clt_data,
                    cancel_token,
                )
                .await;
                return;
            }
            Some(rws) => {
                let rws = Arc::new(rws);
                if rws.send(&relay_init).await.is_err() {
                    lwarn!(" direct relayInit write fail DC{}{}: closed", dc, m_tag);
                    rws.close().await;
                    DC_FAIL_UNTIL.write().insert(dc_key, now + DC_FAIL_COOLDOWN);
                    lwarn!(" direct fallback DC{}{}", dc, m_tag);
                    let splitter_fb = MsgSplitter::new(&relay_init, proto);
                    do_fallback(
                        conn,
                        &relay_init,
                        label,
                        dc,
                        is_media,
                        splitter_fb,
                        &clt_decryptor,
                        &clt_encryptor,
                        &tg_encryptor,
                        &tg_decryptor,
                        is_faketls,
                        initial_clt_data,
                        cancel_token,
                    )
                    .await;
                    return;
                }
                ws = rws;
                send_ok = true;
            }
        }
    }
    let _ = send_ok;

    DC_FAIL_UNTIL.write().remove(&dc_key);
    let _ = &pool;
    STATS.connections_ws.fetch_add(1, Ordering::Relaxed);

    bridge_ws(
        conn,
        ws,
        label,
        dc,
        target,
        443,
        is_media,
        splitter,
        clt_decryptor,
        clt_encryptor,
        tg_encryptor,
        tg_decryptor,
        is_faketls,
        initial_clt_data,
        cancel_token,
    )
    .await;
}

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
    linfo!("  TG WS Proxy запущен");
    linfo!("  Адрес: {}:{}", host, port);

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

    loop {
        tokio::select! {
            _ = cancel_root.cancelled() => {
                break;
            }
            accept = listener.accept() => {
                match accept {
                    Ok((conn, _)) => {
                        let p = pool.clone();
                        let cancel = cancel_sessions.read().child_token();
                        tokio::spawn(async move {
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
    tokio::time::sleep(Duration::from_millis(100)).await;
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
