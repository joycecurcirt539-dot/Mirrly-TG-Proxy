use crate::cfproxy::*;
use crate::config::*;
use crate::ws::*;
use crate::{ldebug, linfo, lwarn};
use std::net::{Ipv4Addr, Ipv6Addr};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio_util::sync::CancellationToken;

pub async fn run_socks5_server(
    host: String,
    port: u16,
    cancel_root: CancellationToken,
    cancel_sessions: Arc<parking_lot::RwLock<CancellationToken>>,
    listener: TcpListener,
) -> std::io::Result<()> {
    linfo!("━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    linfo!("  SOCKS5 Proxy запущен");
    linfo!("  Адрес: {}:{}", host, port);

    loop {
        tokio::select! {
            _ = cancel_root.cancelled() => break,
            accept = listener.accept() => {
                match accept {
                    Ok((conn, _)) => {
                        let cancel = cancel_sessions.read().child_token();
                        tokio::spawn(async move {
                            handle_socks5_client(conn, cancel).await;
                        });
                    }
                    Err(_) => continue,
                }
            }
        }
    }

    Ok(())
}

async fn handle_socks5_client(mut client: TcpStream, cancel: CancellationToken) {
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

    let _ = client.set_nodelay(TCP_NODELAY.load(Ordering::Relaxed));
    let sock = socket2::SockRef::from(&client);
    let ka = socket2::TcpKeepalive::new()
        .with_time(Duration::from_secs(30))
        .with_interval(Duration::from_secs(10))
        .with_retries(3);
    let _ = sock.set_tcp_keepalive(&ka);

    // 1. Handshake: Auth methods
    let mut header = [0u8; 2];
    let auth_res = tokio::select! {
        _ = cancel.cancelled() => return,
        res = tokio::time::timeout(Duration::from_secs(5), client.read_exact(&mut header)) => res,
    };
    if auth_res.is_err() || auth_res.unwrap().is_err() {
        return;
    }
    if header[0] != 0x05 {
        return;
    }
    let num_methods = header[1] as usize;
    let mut methods = vec![0u8; num_methods];
    let read_m_res = tokio::select! {
        _ = cancel.cancelled() => return,
        res = client.read_exact(&mut methods) => res,
    };
    if read_m_res.is_err() {
        return;
    }
    if client.write_all(&[0x05, 0x00]).await.is_err() {
        return;
    }

    // 2. Request details
    let mut req_hdr = [0u8; 4];
    let req_res = tokio::select! {
        _ = cancel.cancelled() => return,
        res = client.read_exact(&mut req_hdr) => res,
    };
    if req_res.is_err() {
        return;
    }
    if req_hdr[0] != 0x05 || req_hdr[1] != 0x01 { // CMD 0x01 = CONNECT
        let _ = client.write_all(&[0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await;
        return;
    }

    let target_host = match req_hdr[3] {
        0x01 => { // IPv4
            let mut ip = [0u8; 4];
            let read_ip_res = tokio::select! {
                _ = cancel.cancelled() => return,
                res = client.read_exact(&mut ip) => res,
            };
            if read_ip_res.is_err() {
                return;
            }
            Ipv4Addr::from(ip).to_string()
        }
        0x03 => { // Domain
            let mut len = [0u8; 1];
            let read_len_res = tokio::select! {
                _ = cancel.cancelled() => return,
                res = client.read_exact(&mut len) => res,
            };
            if read_len_res.is_err() {
                return;
            }
            let mut domain = vec![0u8; len[0] as usize];
            let read_dom_res = tokio::select! {
                _ = cancel.cancelled() => return,
                res = client.read_exact(&mut domain) => res,
            };
            if read_dom_res.is_err() {
                return;
            }
            String::from_utf8_lossy(&domain).to_string()
        }
        0x04 => { // IPv6
            let mut ip = [0u8; 16];
            let read_ip6_res = tokio::select! {
                _ = cancel.cancelled() => return,
                res = client.read_exact(&mut ip) => res,
            };
            if read_ip6_res.is_err() {
                return;
            }
            Ipv6Addr::from(ip).to_string()
        }
        _ => {
            let _ = client.write_all(&[0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await;
            return;
        }
    };

    let mut port_buf = [0u8; 2];
    let read_port_res = tokio::select! {
        _ = cancel.cancelled() => return,
        res = client.read_exact(&mut port_buf) => res,
    };
    if read_port_res.is_err() {
        return;
    }
    let target_port = u16::from_be_bytes(port_buf);
    let target_addr = format!("{}:{}", target_host, target_port);

    ldebug!("SOCKS5 connect request to {}", target_addr);

    // 3. Connect via Cloudflare CDN
    let ws_opt = socks5_acquire_cf_ws(&target_addr, &cancel).await;

    let ws = match ws_opt {
        Some(w) => w,
        None => {
            lwarn!("SOCKS5: CF connect failed for target {}", target_addr);
            let _ = client.write_all(&[0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await;
            return;
        }
    };

    // SOCKS5 success response
    if client.write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await.is_err() {
        ws.close().await;
        return;
    }

    STATS.connections_cfproxy.fetch_add(1, Ordering::Relaxed);
    STATS.connections_ws.fetch_add(1, Ordering::Relaxed);

    // 4. Bi-directional bridge
    bridge_socks5_ws(client, ws, cancel).await;
}

async fn socks5_acquire_cf_ws(
    target_addr: &str,
    cancel_token: &CancellationToken,
) -> Option<RawWebSocket> {
    let (enabled, user_domain) = {
        let cfg = CFPROXY.read();
        (
            CFPROXY_ENABLED.load(Ordering::Relaxed),
            cfg.user_domain.clone(),
        )
    };

    if !enabled {
        return None;
    }

    let path = format!("/tcp?target={}", target_addr);

    // 1. Priority 1: User-configured custom Cloudflare Worker (100% priority)
    if !user_domain.is_empty() {
        let remaining = cfproxy_429_cooldown_remaining(&user_domain);
        if remaining > Duration::ZERO {
            ldebug!(
                "SOCKS5 custom worker {} skip: 429 cooldown {:.0}s",
                user_domain,
                remaining.as_secs_f64().ceil()
            );
        } else {
            ldebug!("SOCKS5 custom worker try {} -> {}", user_domain, target_addr);
            let dial_res = tokio::select! {
                _ = cancel_token.cancelled() => return None,
                res = cf_connect_domain(&user_domain, &path, 5.0) => res,
            };
            let (ws, resolved_ip, err) = dial_res;
            if let Some(w) = ws {
                if !resolved_ip.is_empty() {
                    ldebug!("SOCKS5 custom worker ok {} via {}", user_domain, resolved_ip);
                } else {
                    ldebug!("SOCKS5 custom worker ok {}", user_domain);
                }
                clear_cfproxy_429_cooldown(&user_domain);
                return Some(w);
            }
            if let Some(e) = err {
                if is_http_status_error(&e, 429) {
                    mark_cfproxy_429_cooldown(&user_domain, &e);
                }
                if !resolved_ip.is_empty() {
                    log_cf_conn_error(
                        &format!("SOCKS5 custom worker fail {} via {}: {}", user_domain, resolved_ip, e.compact()),
                        &e,
                    );
                } else {
                    log_cf_conn_error(
                        &format!("SOCKS5 custom worker fail {}: {}", user_domain, e.compact()),
                        &e,
                    );
                }
            }
        }
    }

    // 2. Priority 2: Staggered Concurrent Race (Happy Eyeballs / RFC 8305) across developer fallback workers
    let last_worker = LAST_SOCKS5_WORKER.read().clone();
    let mut candidate_workers: Vec<String> = Vec::with_capacity(DEV_SOCKS5_WORKERS.len());

    // If we have a previously successful worker, prioritize it first (0ms stagger fast-path)
    if !last_worker.is_empty()
        && !user_domain.eq_ignore_ascii_case(&last_worker)
        && DEV_SOCKS5_WORKERS.iter().any(|&w| w.eq_ignore_ascii_case(&last_worker))
    {
        candidate_workers.push(last_worker.clone());
    }

    for &w in DEV_SOCKS5_WORKERS {
        if !user_domain.is_empty() && w.eq_ignore_ascii_case(&user_domain) {
            continue;
        }
        if candidate_workers.iter().any(|cw| cw.eq_ignore_ascii_case(w)) {
            continue;
        }
        candidate_workers.push(w.to_string());
    }

    // Filter out workers on active 429 cooldown
    let mut active_workers: Vec<String> = Vec::with_capacity(candidate_workers.len());
    for w in candidate_workers {
        let remaining = cfproxy_429_cooldown_remaining(&w);
        if remaining > Duration::ZERO {
            ldebug!(
                "SOCKS5 skip {}: 429 cooldown {:.0}s",
                w,
                remaining.as_secs_f64().ceil()
            );
        } else {
            active_workers.push(w);
        }
    }

    if active_workers.is_empty() {
        lwarn!("SOCKS5: Все резервные воркеры недоступны (429 cooldown)");
        return None;
    }

    ldebug!(
        "SOCKS5 Happy Eyeballs Race: {} воркеров для {}",
        active_workers.len(),
        target_addr
    );

    let (tx, mut rx) = tokio::sync::mpsc::channel::<(RawWebSocket, String)>(active_workers.len());
    let mut handles = Vec::with_capacity(active_workers.len());
    let stagger_step = Duration::from_millis(100);
    let sem = Arc::new(tokio::sync::Semaphore::new(CFPROXY_FALLBACK_PARALLEL));

    for (i, worker) in active_workers.into_iter().enumerate() {
        let tx = tx.clone();
        let sem = sem.clone();
        let cancel = cancel_token.clone();
        let path = path.clone();
        let delay = stagger_step * (i as u32);

        handles.push(tokio::spawn(async move {
            tokio::select! {
                _ = cancel.cancelled() => {}
                _ = tokio::time::sleep(delay) => {
                    let _permit = match sem.acquire().await {
                        Ok(p) => p,
                        Err(_) => return,
                    };
                    let (ws, resolved_ip, err) = cf_connect_domain(&worker, &path, 3.5).await;
                    if let Some(w) = ws {
                        if !resolved_ip.is_empty() {
                            ldebug!("SOCKS5 race ok {} via {}", worker, resolved_ip);
                        } else {
                            ldebug!("SOCKS5 race ok {}", worker);
                        }
                        let _ = tx.send((w, worker)).await;
                    } else if let Some(e) = err {
                        if is_http_status_error(&e, 429) {
                            mark_cfproxy_429_cooldown(&worker, &e);
                        }
                        if !resolved_ip.is_empty() {
                            log_cf_conn_error(
                                &format!("SOCKS5 race fail {} via {}: {}", worker, resolved_ip, e.compact()),
                                &e,
                            );
                        } else {
                            log_cf_conn_error(
                                &format!("SOCKS5 race fail {}: {}", worker, e.compact()),
                                &e,
                            );
                        }
                    }
                }
            }
        }));
    }

    drop(tx);

    let mut winning_ws: Option<RawWebSocket> = None;

    tokio::select! {
        _ = cancel_token.cancelled() => {
            ldebug!("SOCKS5 connection race cancelled");
        }
        msg = rx.recv() => {
            if let Some((ws, winner_domain)) = msg {
                linfo!("⚡ SOCKS5 воркер выбран: {}", winner_domain);
                *LAST_SOCKS5_WORKER.write() = winner_domain.clone();
                clear_cfproxy_429_cooldown(&winner_domain);
                winning_ws = Some(ws);
            }
        }
    }

    // Abort pending racer tasks
    for h in handles {
        h.abort();
    }

    // Drain and close any runner-up connections that completed concurrently
    while let Ok((extra_ws, extra_domain)) = rx.try_recv() {
        ldebug!("SOCKS5 closing runner-up connection to {}", extra_domain);
        tokio::spawn(async move {
            let _ = extra_ws.close().await;
        });
    }

    winning_ws
}

async fn bridge_socks5_ws(client: TcpStream, ws: RawWebSocket, cancel_token: CancellationToken) {
    let ws = Arc::new(ws);
    let last_activity = Arc::new(tokio::sync::Mutex::new(std::time::Instant::now()));
    let (mut c_read, mut c_write) = client.into_split();
    let cancel = Arc::new(tokio::sync::Notify::new());

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

    // Up: Client -> WS
    let ws_up = ws.clone();
    let la_up = last_activity.clone();
    let cancel_up = cancel.clone();
    let cancel_token_up = cancel_token.clone();
    let up = tokio::spawn(async move {
        let mut buf = vec![0u8; WS_BRIDGE_CHUNK_SIZE];
        loop {
            let res = tokio::select! {
                _ = cancel_token_up.cancelled() => break,
                _ = cancel_up.notified() => break,
                r = tokio::time::timeout(BRIDGE_READ_TIMEOUT, c_read.read(&mut buf)) => r,
            };
            match res {
                Ok(Ok(0)) | Ok(Err(_)) | Err(_) => break,
                Ok(Ok(n)) => {
                    STATS.bytes_up.fetch_add(n as i64, Ordering::Relaxed);
                    *la_up.lock().await = std::time::Instant::now();
                    if ws_up.send(&buf[..n]).await.is_err() {
                        break;
                    }
                }
            }
        }
        cancel_up.notify_waiters();
    });

    // Down: WS -> Client
    let ws_down = ws.clone();
    let la_down = last_activity.clone();
    let cancel_down = cancel.clone();
    let cancel_token_down = cancel_token.clone();
    let down = tokio::spawn(async move {
        loop {
            let res = tokio::select! {
                _ = cancel_token_down.cancelled() => break,
                _ = cancel_down.notified() => break,
                r = ws_down.recv_with_timeout(BRIDGE_READ_TIMEOUT) => r,
            };
            match res {
                Ok(data) => {
                    let n = data.len();
                    STATS.bytes_down.fetch_add(n as i64, Ordering::Relaxed);
                    *la_down.lock().await = std::time::Instant::now();
                    if c_write.write_all(&data).await.is_err() {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
        cancel_down.notify_waiters();
    });

    let _ = tokio::join!(up, down);
    cancel.notify_waiters();
    ping_task.abort();
    ws.close().await;
}
