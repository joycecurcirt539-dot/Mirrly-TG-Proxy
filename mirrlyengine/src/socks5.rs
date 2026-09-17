use crate::cfproxy::*;
use crate::config::*;
use crate::ws::*;
use crate::{ldebug, linfo, lwarn};
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::{Duration, Instant};
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
    linfo!("  SOCKS5 Proxy started");
    linfo!("  Address: {}:{}", host, port);

    let mut sessions = tokio::task::JoinSet::new();
    loop {
        tokio::select! {
            _ = cancel_root.cancelled() => break,
            _ = sessions.join_next(), if !sessions.is_empty() => {}
            accept = listener.accept() => {
                match accept {
                    Ok((conn, _)) => {
                        let cancel = cancel_sessions.read().child_token();
                        sessions.spawn(async move {
                            handle_socks5_client(conn, cancel).await;
                        });
                    }
                    Err(_) => continue,
                }
            }
        }
    }

    if tokio::time::timeout(Duration::from_secs(2), async {
        while sessions.join_next().await.is_some() {}
    })
    .await
    .is_err()
    {
        lwarn!("SOCKS5 shutdown: aborting sessions that did not drain in time");
        sessions.abort_all();
        while sessions.join_next().await.is_some() {}
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

    set_sock_opts(&client);

    // 1. Handshake: Auth methods (RFC 1928 / RFC 1929)
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

    let (auth_required, expected_user, expected_pass) = {
        let auth = SOCKS5_AUTH.read();
        let has_user = !auth.username.is_empty();
        let has_pass = !auth.password.is_empty();
        (
            has_user || has_pass,
            auth.username.clone(),
            auth.password.clone(),
        )
    };

    if auth_required {
        // If client doesn't support Username/Password auth (0x02), reject with 0xFF (No acceptable methods)
        if !methods.contains(&0x02) {
            crate::lwarn!(
                "SOCKS5 client did not offer Username/Password auth (methods: {:?})",
                methods
            );
            let _ = client.write_all(&[0x05, 0xFF]).await;
            return;
        }

        // Tell client to proceed with Username/Password auth (0x02)
        if client.write_all(&[0x05, 0x02]).await.is_err() {
            return;
        }

        // Sub-negotiation RFC 1929:
        // +----+------+----------+------+----------+
        // |VER | ULEN |  UNAME   | PLEN |  PASSWD  |
        // +----+------+----------+------+----------+
        // | 1  |  1   | 1 to 255 |  1   | 1 to 255 |
        // +----+------+----------+------+----------+
        let mut auth_ver_ulen = [0u8; 2];
        let auth_sub_res = tokio::select! {
            _ = cancel.cancelled() => return,
            res = tokio::time::timeout(Duration::from_secs(5), client.read_exact(&mut auth_ver_ulen)) => res,
        };
        if auth_sub_res.is_err() || auth_sub_res.unwrap().is_err() {
            return;
        }
        if auth_ver_ulen[0] != 0x01 {
            let _ = client.write_all(&[0x01, 0xFF]).await;
            return;
        }
        let ulen = auth_ver_ulen[1] as usize;
        let mut uname_buf = vec![0u8; ulen];
        if client.read_exact(&mut uname_buf).await.is_err() {
            return;
        }

        let mut plen_buf = [0u8; 1];
        if client.read_exact(&mut plen_buf).await.is_err() {
            return;
        }
        let plen = plen_buf[0] as usize;
        let mut pass_buf = vec![0u8; plen];
        if client.read_exact(&mut pass_buf).await.is_err() {
            return;
        }

        let given_user = String::from_utf8_lossy(&uname_buf);
        let given_pass = String::from_utf8_lossy(&pass_buf);

        if given_user == expected_user && given_pass == expected_pass {
            // Authentication SUCCESS (0x00)
            if client.write_all(&[0x01, 0x00]).await.is_err() {
                return;
            }
        } else {
            crate::lwarn!("SOCKS5 auth rejected: username '{}' mismatch", given_user);
            let _ = client.write_all(&[0x01, 0xFF]).await;
            return;
        }
    } else {
        // No authentication required (open mode)
        if methods.contains(&0x00) {
            if client.write_all(&[0x05, 0x00]).await.is_err() {
                return;
            }
        } else if methods.contains(&0x02) {
            // If client specifically requested 0x02, gracefully accept
            if client.write_all(&[0x05, 0x02]).await.is_err() {
                return;
            }
            let mut auth_ver_ulen = [0u8; 2];
            if client.read_exact(&mut auth_ver_ulen).await.is_err() {
                return;
            }
            let ulen = auth_ver_ulen[1] as usize;
            let mut uname_buf = vec![0u8; ulen];
            let _ = client.read_exact(&mut uname_buf).await;
            let mut plen_buf = [0u8; 1];
            let _ = client.read_exact(&mut plen_buf).await;
            let plen = plen_buf[0] as usize;
            let mut pass_buf = vec![0u8; plen];
            let _ = client.read_exact(&mut pass_buf).await;
            if client.write_all(&[0x01, 0x00]).await.is_err() {
                return;
            }
        } else {
            let _ = client.write_all(&[0x05, 0xFF]).await;
            return;
        }
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
    if req_hdr[0] != 0x05 {
        return;
    }
    if req_hdr[1] == 0x03 {
        // CMD 0x03 = UDP ASSOCIATE (RFC 1928, used by Telegram VoIP audio/video calls)
        handle_socks5_udp_associate(client, req_hdr[3], cancel).await;
        return;
    }
    if req_hdr[1] != 0x01 {
        // CMD 0x01 = CONNECT
        let _ = client
            .write_all(&[0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
            .await;
        return;
    }

    let target_host = match req_hdr[3] {
        0x01 => {
            // IPv4
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
        0x03 => {
            // Domain
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
        0x04 => {
            // IPv6
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
            let _ = client
                .write_all(&[0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
                .await;
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
    let target_addr = if target_host.contains(':') && !target_host.starts_with('[') {
        format!("[{}]:{}", target_host, target_port)
    } else {
        format!("{}:{}", target_host, target_port)
    };

    ldebug!("SOCKS5 connect request to {}", target_addr);

    // 3. Connect via selected Uplink (Worker WSS, WARP MASQUE HTTP/3, or Hybrid)
    let uplink_opt = socks5_acquire_uplink(&target_addr, &cancel).await;

    let uplink = match uplink_opt {
        Some(u) => u,
        None => {
            lwarn!("SOCKS5: Uplink connect failed for target {}", target_addr);
            let _ = client
                .write_all(&[0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
                .await;
            crate::supervisor::ROUTE_SUPERVISOR.record_transport_readiness(false);
            crate::supervisor::ROUTE_SUPERVISOR.record_app_readiness(false, Some("Uplink connect failed"));
            return;
        }
    };

    crate::supervisor::ROUTE_SUPERVISOR.record_transport_readiness(true);

    let is_l3_bridge = matches!(uplink, SocksUplink::Masque(_) | SocksUplink::Awg(_));

    if !is_l3_bridge {
        // SOCKS5 success response for L7 and direct proxies (Worker WSS, VLESS, Opera).
        // Preserves B08 fix: emit REP=0x00 immediately so client can send client-first payload (TLS ClientHello)
        // without mutual deadlock. For L3 bridges (MASQUE/AWG), REP is deferred until smoltcp connects.
        if client
            .write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
            .await
            .is_err()
        {
            match uplink {
                SocksUplink::Ws(w) | SocksUplink::VlessWs(w) => {
                    w.close().await;
                }
                SocksUplink::Tcp(..) | SocksUplink::VlessTcp(..) => {}
                SocksUplink::Tls(..) => {}
                SocksUplink::Reality(..) => {}
                _ => {}
            }
            return;
        }

        match &uplink {
            SocksUplink::Ws(_) => {
                STATS.connections_cfproxy.fetch_add(1, Ordering::Relaxed);
                STATS.connections_ws.fetch_add(1, Ordering::Relaxed);
                crate::supervisor::ROUTE_SUPERVISOR.record_app_readiness(true, None);
            }
            SocksUplink::VlessWs(_) => {
                STATS.connections_ws.fetch_add(1, Ordering::Relaxed);
                STATS.connections_vless.fetch_add(1, Ordering::Relaxed);
                crate::supervisor::ROUTE_SUPERVISOR.record_app_readiness(true, None);
            }
            SocksUplink::Tcp(_, _, _) => {
                STATS.connections_opera.fetch_add(1, Ordering::Relaxed);
                crate::supervisor::ROUTE_SUPERVISOR.record_app_readiness(true, None);
            }
            SocksUplink::VlessTcp(_, _, _) | SocksUplink::Tls(_, _, _) | SocksUplink::Reality(_, _, _) => {
                STATS.connections_vless.fetch_add(1, Ordering::Relaxed);
                crate::supervisor::ROUTE_SUPERVISOR.record_app_readiness(true, None);
            }
            _ => {}
        }
    }

    // 4. Bi-directional bridge
    match uplink {
        SocksUplink::Ws(ws) => bridge_socks5_ws(client, ws, false, cancel).await,
        SocksUplink::VlessWs(ws) => bridge_socks5_ws(client, ws, true, cancel).await,
        SocksUplink::Masque(m) => bridge_socks5_masque(client, m, cancel).await,
        SocksUplink::Awg(awg) => bridge_socks5_awg(client, awg, cancel).await,
        SocksUplink::Tcp(tcp, init, v) => bridge_socks5_stream(client, tcp, init, v, false, cancel).await,
        SocksUplink::VlessTcp(tcp, init, v) => bridge_socks5_stream(client, tcp, init, v, true, cancel).await,
        SocksUplink::Tls(tls, init, v) => bridge_socks5_stream(client, tls, init, v, true, cancel).await,
        SocksUplink::Reality(r, init, v) => bridge_socks5_stream(client, r, init, v, true, cancel).await,
    }
}

pub enum SocksUplink {
    Ws(RawWebSocket),
    VlessWs(RawWebSocket),
    Masque(crate::masque::MasqueTunnel),
    Awg(crate::awg::AwgTunnel),
    Tcp(
        tokio::net::TcpStream,
        Vec<u8>,
        Option<crate::vision::VisionContext>,
    ),
    VlessTcp(
        tokio::net::TcpStream,
        Vec<u8>,
        Option<crate::vision::VisionContext>,
    ),
    Tls(
        tokio_rustls::client::TlsStream<tokio::net::TcpStream>,
        Vec<u8>,
        Option<crate::vision::VisionContext>,
    ),
    Reality(
        crate::reality::RealityStream,
        Vec<u8>,
        Option<crate::vision::VisionContext>,
    ),
}

impl From<crate::vless::VlessUplink> for SocksUplink {
    fn from(u: crate::vless::VlessUplink) -> Self {
        match u {
            crate::vless::VlessUplink::Ws(ws) => SocksUplink::VlessWs(ws),
            crate::vless::VlessUplink::Tcp(s, init, v) => SocksUplink::VlessTcp(s, init, v),
            crate::vless::VlessUplink::Tls(s, init, v) => SocksUplink::Tls(s, init, v),
            crate::vless::VlessUplink::Reality(s, init, v) => SocksUplink::Reality(s, init, v),
        }
    }
}

async fn socks5_acquire_uplink(
    target_addr: &str,
    cancel_token: &CancellationToken,
) -> Option<SocksUplink> {
    crate::supervisor::ROUTE_SUPERVISOR
        .acquire_socks5_uplink(target_addr, cancel_token)
        .await
}

#[derive(Debug)]
pub(crate) enum WorkerConnectError {
    Canceled,
    Cooldown429(String),
    ConnectionFailed(String),
    AckTimeoutOrInvalid(String),
}

async fn attempt_single_worker_connect(
    worker: String,
    path_v2: String,
    path_v1: String,
    cancel: CancellationToken,
    expected: crate::generation_guard::GenerationStamp,
) -> Result<(RawWebSocket, String), WorkerConnectError> {
    let start = std::time::Instant::now();
    if cancel.is_cancelled() {
        return Err(WorkerConnectError::Canceled);
    }

    // 1. First attempt /tcp-v2 with relay-ready ACK contract
    let (ws_v2, resolved_ip, err) = cf_connect_domain_with_category(
        &worker,
        &path_v2,
        None,
        3.5,
        crate::budget::FlowCategory::UserFlow,
        Some(&cancel),
    )
    .await;

    if let Some(w) = ws_v2 {
        if cancel.is_cancelled() {
            let _ = w.close().await;
            return Err(WorkerConnectError::Canceled);
        }
        // Wait for the versioned relay-ready control ACK from Worker: [0x56, 0x02, 0x00, 0x00]
        let ack_res = tokio::select! {
            _ = cancel.cancelled() => Err(WsError::Canceled),
            r = w.recv_with_timeout(Duration::from_millis(2500)) => r,
        };
        match ack_res {
            Ok(ack_data)
                if ack_data.len() >= 4
                    && ack_data[0] == 0x56
                    && ack_data[1] == 0x02
                    && ack_data[2] == 0x00 =>
            {
                let elapsed_ms = start.elapsed().as_millis() as u64;
                if !resolved_ip.is_empty() {
                    ldebug!(
                        "SOCKS5 worker ok {} via {} ({}ms, /tcp-v2 verified)",
                        worker,
                        resolved_ip,
                        elapsed_ms
                    );
                } else {
                    ldebug!(
                        "SOCKS5 worker ok {} ({}ms, /tcp-v2 verified)",
                        worker,
                        elapsed_ms
                    );
                }
                if crate::generation_guard::is_current(expected) {
                    STATS.socks5_v2_sessions.fetch_add(1, Ordering::Relaxed);
                    let _ = crate::generation_guard::apply_if_current(expected, || {
                        crate::recovery::mark_success(&worker);
                        clear_cfproxy_recovery_cooldown(&worker);
                    });
                    return Ok((w, worker));
                }
                let _ = w.close().await;
                return Err(WorkerConnectError::Canceled);
            }
            Ok(other) => {
                lwarn!(
                    "SOCKS5 worker {} sent invalid /tcp-v2 ACK (len={}), closing",
                    worker,
                    other.len()
                );
                let _ = w.close().await;
                let _ = crate::generation_guard::apply_if_current(expected, || {
                    mark_cfproxy_recovery_circuit_at_stage(
                        &worker,
                        Duration::from_secs(20),
                        "invalid_ack",
                        crate::recovery::EstablishmentStage::Ready,
                        expected.network,
                    );
                });
                return Err(WorkerConnectError::AckTimeoutOrInvalid(format!(
                    "invalid_ack_len_{}",
                    other.len()
                )));
            }
            Err(e) => {
                ldebug!(
                    "SOCKS5 worker {} /tcp-v2 ACK wait error/close: {:?}",
                    worker,
                    e
                );
                let _ = w.close().await;
                if matches!(e, WsError::Canceled) {
                    return Err(WorkerConnectError::Canceled);
                }
                let _ = crate::generation_guard::apply_if_current(expected, || {
                    mark_cfproxy_recovery_circuit_at_stage(
                        &worker,
                        Duration::from_secs(20),
                        "ack_timeout_or_error",
                        crate::recovery::EstablishmentStage::Ready,
                        expected.network,
                    );
                });
            }
        }
    } else if let Some(ref e) = err {
        if crate::ws::is_cooldown_error(e) {
            let _ = crate::generation_guard::apply_if_current(expected, || {
                mark_cfproxy_429_cooldown(&worker, e);
            });
            return Err(WorkerConnectError::Cooldown429(e.compact()));
        }
        let _ = crate::generation_guard::apply_if_current(expected, || {
            mark_cfproxy_recovery_circuit_at_stage(
                &worker,
                Duration::from_secs(25),
                &e.compact(),
                crate::recovery::EstablishmentStage::Wss,
                expected.network,
            );
        });
    }

    if cancel.is_cancelled() {
        return Err(WorkerConnectError::Canceled);
    }

    // 2. Compatibility fallback: if worker does not support /tcp-v2, fallback to legacy /tcp
    let (ws_v1, resolved_ip_v1, err_v1) = cf_connect_domain_with_category(
        &worker,
        &path_v1,
        None,
        3.5,
        crate::budget::FlowCategory::UserFlow,
        Some(&cancel),
    )
    .await;

    if let Some(w) = ws_v1 {
        if cancel.is_cancelled() {
            let _ = w.close().await;
            return Err(WorkerConnectError::Canceled);
        }
        let elapsed_ms = start.elapsed().as_millis() as u64;
        if !resolved_ip_v1.is_empty() {
            ldebug!(
                "SOCKS5 worker ok {} via {} ({}ms, legacy /tcp fallback)",
                worker,
                resolved_ip_v1,
                elapsed_ms
            );
        } else {
            ldebug!(
                "SOCKS5 worker ok {} ({}ms, legacy /tcp fallback)",
                worker,
                elapsed_ms
            );
        }
        if crate::generation_guard::is_current(expected) {
            STATS.socks5_v1_downgrades.fetch_add(1, Ordering::Relaxed);
            let _ = crate::generation_guard::apply_if_current(expected, || {
                crate::recovery::mark_success(&worker);
                clear_cfproxy_recovery_cooldown(&worker);
            });
            Ok((w, worker))
        } else {
            let _ = w.close().await;
            Err(WorkerConnectError::Canceled)
        }
    } else {
        if let Some(ref e) = err_v1 {
            if crate::ws::is_cooldown_error(e) {
                let _ = crate::generation_guard::apply_if_current(expected, || {
                    mark_cfproxy_429_cooldown(&worker, e);
                });
                return Err(WorkerConnectError::Cooldown429(e.compact()));
            }
            let _ = crate::generation_guard::apply_if_current(expected, || {
                mark_cfproxy_recovery_circuit_at_stage(
                    &worker,
                    Duration::from_secs(25),
                    &e.compact(),
                    crate::recovery::EstablishmentStage::Wss,
                    expected.network,
                );
            });
            let resolved_ip_opt = resolved_ip_v1.parse::<std::net::IpAddr>().ok();
            crate::node_independence::NODE_INDEPENDENCE_TRACKER
                .write()
                .record_node_failure(&worker, resolved_ip_opt, None);
            if !resolved_ip_v1.is_empty() {
                log_cf_conn_error(
                    &format!(
                        "SOCKS5 worker fail {} via {}: {}",
                        worker,
                        resolved_ip_v1,
                        e.compact()
                    ),
                    e,
                );
            } else {
                log_cf_conn_error(
                    &format!("SOCKS5 worker fail {}: {}", worker, e.compact()),
                    e,
                );
            }
            return Err(WorkerConnectError::ConnectionFailed(e.compact()));
        }
        Err(WorkerConnectError::ConnectionFailed(
            "unknown_error".to_string(),
        ))
    }
}

pub(crate) async fn socks5_acquire_cf_ws(
    target_addr: &str,
    cancel_token: &CancellationToken,
) -> Option<RawWebSocket> {
    let expected = crate::generation_guard::snapshot();
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

    let (target_host, target_port) = if let Some(pos) = target_addr.rfind(':') {
        let h = target_addr[..pos].trim_matches(['[', ']']);
        let p = &target_addr[pos + 1..];
        (h, p)
    } else {
        (target_addr.trim_matches(['[', ']']), "443")
    };
    let path_v2 = format!(
        "/tcp-v2?target={}&host={}&port={}&ip={}",
        target_addr, target_host, target_port, target_host
    );
    let path_v1 = format!(
        "/tcp?target={}&host={}&port={}&ip={}",
        target_addr, target_host, target_port, target_host
    );

    // 1. Identify proven worker: custom user_domain has top priority, then LAST_SOCKS5_WORKER
    let last_worker = LAST_SOCKS5_WORKER.read().clone();
    let proven_worker: Option<String> = if !user_domain.is_empty()
        && cfproxy_429_cooldown_remaining(&user_domain) == Duration::ZERO
        && try_acquire_cfproxy_half_open_trial(&user_domain, expected.network)
    {
        Some(user_domain.clone())
    } else if !last_worker.is_empty()
        && cfproxy_429_cooldown_remaining(&last_worker) == Duration::ZERO
        && try_acquire_cfproxy_half_open_trial(&last_worker, expected.network)
    {
        Some(last_worker.clone())
    } else {
        None
    };

    // Prepare candidate fallback pool (excluding proven worker and workers on 429 cooldown)
    let mut candidate_pool: Vec<String> = Vec::with_capacity(DEV_SOCKS5_WORKERS.len() + 2);
    if !user_domain.is_empty() && proven_worker.as_deref() != Some(&user_domain) {
        candidate_pool.push(user_domain.clone());
    }
    if !last_worker.is_empty()
        && proven_worker.as_deref() != Some(&last_worker)
        && !candidate_pool.iter().any(|c| c.eq_ignore_ascii_case(&last_worker))
    {
        candidate_pool.push(last_worker.clone());
    }
    for &w in DEV_SOCKS5_WORKERS {
        if proven_worker.as_deref() == Some(w)
            || candidate_pool.iter().any(|c| c.eq_ignore_ascii_case(w))
        {
            continue;
        }
        candidate_pool.push(w.to_string());
    }
    let fallback_pool: Vec<String> = candidate_pool
        .into_iter()
        .filter(|w| {
            cfproxy_429_cooldown_remaining(w) == Duration::ZERO
                && try_acquire_cfproxy_half_open_trial(w, expected.network)
        })
        .collect();

    // 2. STICKY FAST-PATH: If we have a proven worker, attempt a single fast connection first.
    // Hedging and fallback race are triggered only after an adaptive delay or upon a typed failure.
    let mut fallback_needed = false;
    let mut fast_handle: Option<
        tokio::task::JoinHandle<Result<(RawWebSocket, String), WorkerConnectError>>,
    > = None;
    let fast_cancel = cancel_token.child_token();

    if let Some(ref proven) = proven_worker {
        let is_mobile = MOBILE_NETWORK.load(Ordering::Relaxed);
        let smoothed_rtt = crate::network_profile::get_profile().transport_sli.smoothed_rtt_ms;
        let hedge_delay = if smoothed_rtt > 0 {
            Duration::from_millis(((smoothed_rtt as u64) * 3).clamp(800, 2500))
        } else if is_mobile {
            Duration::from_millis(1500)
        } else {
            Duration::from_millis(800)
        };

        let fp_worker = proven.clone();
        let fp_path_v2 = path_v2.clone();
        let fp_path_v1 = path_v1.clone();
        let fp_cancel = fast_cancel.clone();

        let mut handle = tokio::spawn(async move {
            attempt_single_worker_connect(
                fp_worker,
                fp_path_v2,
                fp_path_v1,
                fp_cancel,
                expected,
            )
            .await
        });

        tokio::select! {
            biased;
            _ = cancel_token.cancelled() => {
                release_cfproxy_half_open_trial(proven);
                fast_cancel.cancel();
                handle.abort();
                return None;
            }
            res = &mut handle => {
                match res {
                    Ok(Ok((ws, winner_domain))) => {
                        // Sticky fast-path SUCCESS: exactly 1 WSS attempt!
                        STATS.socks5_fastpath_hits.fetch_add(1, Ordering::Relaxed);
                        if crate::generation_guard::is_current(expected) {
                            let accepted = crate::generation_guard::apply_if_current(expected, || {
                                *LAST_SOCKS5_WORKER.write() = winner_domain.clone();
                                clear_cfproxy_429_cooldown(&winner_domain);
                                clear_cfproxy_recovery_cooldown(&winner_domain);
                                if let Some(ip) = ws.peer_ip() {
                                    let colo = ws.colo().to_string();
                                    crate::node_independence::NODE_INDEPENDENCE_TRACKER
                                        .write()
                                        .record_node_handshake_success(&winner_domain, ip, &colo);
                                }
                            }).is_some();
                            if accepted {
                                ldebug!("SOCKS5 fast-path to proven worker {} succeeded (1 WSS attempt)", winner_domain);
                                return Some(ws);
                            } else {
                                release_cfproxy_half_open_trial(proven);
                                let _ = ws.close().await;
                            }
                        } else {
                            release_cfproxy_half_open_trial(proven);
                            let _ = ws.close().await;
                        }
                    }
                    Ok(Err(err)) => {
                        release_cfproxy_half_open_trial(proven);
                        // Typed failure: connection error, 429 cooldown, or bad ACK
                        ldebug!(
                            "SOCKS5 fast-path to proven worker {} failed typed ({:?}), immediately initiating fallback race",
                            proven,
                            err
                        );
                        STATS.socks5_fallback_triggers.fetch_add(1, Ordering::Relaxed);
                        fallback_needed = true;
                    }
                    Err(_) => {
                        release_cfproxy_half_open_trial(proven);
                        STATS.socks5_fallback_triggers.fetch_add(1, Ordering::Relaxed);
                        fallback_needed = true;
                    }
                }
            }
            _ = tokio::time::sleep(hedge_delay) => {
                ldebug!(
                    "SOCKS5 fast-path hedge delay ({:?}) elapsed for {}, hedging with fallback race",
                    hedge_delay,
                    proven
                );
                STATS.socks5_fallback_triggers.fetch_add(1, Ordering::Relaxed);
                fallback_needed = true;
                fast_handle = Some(handle);
            }
        }
    } else {
        // Cold start: no proven worker, start initial race immediately
        fallback_needed = true;
    }

    if !fallback_needed || cancel_token.is_cancelled() {
        fast_cancel.cancel();
        if let Some(h) = fast_handle {
            h.abort();
        }
        return None;
    }

    if fallback_pool.is_empty() && fast_handle.is_none() {
        lwarn!("SOCKS5: no fallback workers available for race");
        return None;
    }

    // 3. BOUNDED FALLBACK RACE:
    // Select diverse candidates ensuring true route independence (MOB-027)
    let diverse_candidates = crate::node_independence::NODE_INDEPENDENCE_TRACKER
        .read()
        .select_diverse_race_candidates(&fallback_pool, 3);

    let race_cancel = cancel_token.child_token();
    let channel_cap = diverse_candidates.len() + if fast_handle.is_some() { 1 } else { 0 };
    let (tx, mut rx) = tokio::sync::mpsc::channel::<(RawWebSocket, String)>(channel_cap.max(1));
    let mut race_handles = Vec::with_capacity(channel_cap.max(1));

    // If fast-path is still running as a hedge, forward its outcome to the race channel
    if let Some(fh) = fast_handle {
        let tx_fp = tx.clone();
        let cancel_fp = race_cancel.clone();
        race_handles.push(tokio::spawn(async move {
            tokio::select! {
                _ = cancel_fp.cancelled() => {}
                res = fh => {
                    if let Ok(Ok((ws, winner))) = res {
                        if !cancel_fp.is_cancelled() {
                            let _ = tx_fp.send((ws, winner)).await;
                        } else {
                            let _ = ws.close().await;
                        }
                    }
                }
            }
        }));
    }

    let is_mobile = MOBILE_NETWORK.load(Ordering::Relaxed);
    let stagger_step = if is_mobile {
        Duration::from_millis(300)
    } else {
        Duration::from_millis(100)
    };

    for (i, worker) in diverse_candidates.clone().into_iter().enumerate() {
        let tx = tx.clone();
        let cancel = race_cancel.clone();
        let path_v2 = path_v2.clone();
        let path_v1 = path_v1.clone();
        let delay = stagger_step * (i as u32);

        race_handles.push(tokio::spawn(async move {
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
                    attempt_single_worker_connect(
                        worker,
                        path_v2,
                        path_v1,
                        cancel.clone(),
                        expected,
                    )
                    .await
                    .ok()
                } => {
                    if let Some((w, winner)) = res {
                        if cancel.is_cancelled() {
                            let _ = w.close().await;
                        } else {
                            let _ = tx.send((w, winner)).await;
                        }
                    }
                }
            }
        }));
    }

    drop(tx);

    let mut winning_ws: Option<RawWebSocket> = None;
    let mut winning_domain: Option<String> = None;

    tokio::select! {
        _ = cancel_token.cancelled() => {
            ldebug!("SOCKS5 fallback race cancelled by parent");
            race_cancel.cancel();
        }
        msg = rx.recv() => {
            race_cancel.cancel();
            if let Some((ws, winner_domain)) = msg {
                if !crate::generation_guard::is_current(expected) {
                    let _ = ws.close().await;
                } else {
                    let accepted = crate::generation_guard::apply_if_current(expected, || {
                        *LAST_SOCKS5_WORKER.write() = winner_domain.clone();
                        clear_cfproxy_429_cooldown(&winner_domain);
                        clear_cfproxy_recovery_cooldown(&winner_domain);
                        if let Some(ip) = ws.peer_ip() {
                            let colo = ws.colo().to_string();
                            crate::node_independence::NODE_INDEPENDENCE_TRACKER
                                .write()
                                .record_node_handshake_success(&winner_domain, ip, &colo);
                        }
                    }).is_some();
                    if accepted {
                        linfo!("SOCKS5 fallback worker selected: {}", winner_domain);
                        winning_domain = Some(winner_domain);
                        winning_ws = Some(ws);
                    } else {
                        let _ = ws.close().await;
                    }
                }
            }
        }
    }

    // Abort and await pending racer tasks within bounded time
    for handle in &race_handles {
        handle.abort();
    }
    for handle in race_handles {
        let _ = tokio::time::timeout(Duration::from_millis(500), handle).await;
    }

    // Drain and close runner-up connections
    while let Ok((extra_ws, extra_domain)) = rx.try_recv() {
        ldebug!("SOCKS5 closing runner-up connection to {}", extra_domain);
        let _ = extra_ws.close().await;
    }

    // Release half-open trial for candidates that did not win
    for w in &diverse_candidates {
        if winning_domain.as_deref() != Some(w.as_str()) {
            release_cfproxy_half_open_trial(w);
        }
    }
    if let Some(ref proven) = proven_worker {
        if winning_domain.as_deref() != Some(proven.as_str()) {
            release_cfproxy_half_open_trial(proven);
        }
    }

    if !crate::generation_guard::is_current(expected) {
        if let Some(ws) = winning_ws {
            let _ = ws.close().await;
        }
        return None;
    }
    winning_ws
}

/// Bounded async write that strictly honors the cancellation token and applies a write deadline.
/// Prevents tasks and tokio::join from hanging indefinitely when a slow or unresponsive reader
/// exhausts its TCP window buffer.
pub async fn bounded_write<W: tokio::io::AsyncWriteExt + Unpin>(
    writer: &mut W,
    data: &[u8],
    cancel: &CancellationToken,
    timeout_dur: Duration,
) -> Result<(), std::io::Error> {
    // Keep the committed offset outside the cancellable write future. Never retry a
    // cancelled write_all from byte zero; cancellation/timeout is terminal for this bridge.
    let deadline = tokio::time::Instant::now() + timeout_dur;
    let mut written = 0;
    while written < data.len() {
        let result = tokio::select! {
            biased;
            _ = cancel.cancelled() => Err(std::io::ErrorKind::Interrupted.into()),
            _ = tokio::time::sleep_until(deadline) => Err(std::io::ErrorKind::TimedOut.into()),
            result = writer.write(&data[written..]) => result,
        };
        match result {
            Ok(0) => return Err(std::io::ErrorKind::WriteZero.into()),
            Ok(n) => written += n,
            Err(error) => return Err(std::io::Error::new(error.kind(),
                format!("bridge write stopped after {written}/{} bytes: {error}", data.len()))),
        }
    }
    Ok(())
}
/// Bounded async shutdown that emits a FIN segment to half-close the writer direction.
pub async fn bounded_shutdown<W: tokio::io::AsyncWriteExt + Unpin>(
    writer: &mut W,
    cancel: &CancellationToken,
    timeout_dur: Duration,
) -> Result<(), std::io::Error> {
    tokio::select! {
        biased;
        _ = cancel.cancelled() => Err(std::io::Error::new(
            std::io::ErrorKind::Interrupted,
            "operation cancelled"
        )),
        res = tokio::time::timeout(timeout_dur, writer.shutdown()) => match res {
            Ok(shutdown_res) => shutdown_res,
            Err(_) => Err(std::io::Error::new(
                std::io::ErrorKind::TimedOut,
                "shutdown timeout"
            )),
        }
    }
}

pub async fn bridge_socks5_ws(
    client: TcpStream,
    ws: RawWebSocket,
    is_vless: bool,
    cancel_token: CancellationToken,
) {
    let ws = Arc::new(ws);
    let (mut c_read, mut c_write) = client.into_split();
    let bridge_cancel = cancel_token.child_token();
    let _bridge_guard = bridge_cancel.clone().drop_guard();
    let activity = crate::bridge::BridgeActivity::with_ws(ws.clone());

    // One native heartbeat for this exact WebSocket connection.
    let ws_ping = ws.clone();
    let cancel_ping = bridge_cancel.clone();
    let heartbeat = async move {
        if ws_ping.run_heartbeat(cancel_ping.clone()).await.is_err() {
            cancel_ping.cancel();
        }
    };

    // Up: Client -> WS
    let ws_up = ws.clone();
    let bridge_cancel_up = bridge_cancel.clone();
    let activity_up = activity.clone();
    let up = async move {
        let mut buf = vec![0u8; WS_BRIDGE_CHUNK_SIZE];
        loop {
            let res = tokio::select! {
                _ = bridge_cancel_up.cancelled() => break,
                r = tokio::time::timeout(Duration::from_secs(30), c_read.read(&mut buf)) => r,
            };
            match res {
                Ok(Ok(0)) => {
                    // Client EOF (Half-close: client finished sending upload data)
                    activity_up.upload_eof();
                    // WS has no directional FIN; allow bounded downstream drain.
                    break;
                }
                Ok(Err(e)) => {
                    ldebug!("Bridge WS client read error: {}", e);
                    bridge_cancel_up.cancel();
                    break;
                }
                Err(_) => {
                    // 30-second chunk timeout: check if connection was idle overall
                    let idle = ws_up.heartbeat_idle_for();
                    if idle >= crate::config::profile_aware_absolute_idle_timeout() {
                        ldebug!("Bridge WS overall idle timeout exceeded ({}s)", idle.as_secs());
                        bridge_cancel_up.cancel();
                        break;
                    }
                    // Downstream is actively transferring; continue upload read
                    continue;
                }
                Ok(Ok(n)) => {
                    activity_up.touch();
                    STATS.bytes_up.fetch_add(n as i64, Ordering::Relaxed);
                    let send_res = tokio::select! {
                        _ = bridge_cancel_up.cancelled() => Err(WsError::Other("cancelled".to_string())),
                        r = ws_up.send(&buf[..n]) => r,
                    };
                    if let Err(e) = send_res {
                        ldebug!("Bridge WS upstream send error: {:?}", e);
                        bridge_cancel_up.cancel();
                        break;
                    }
                }
            }
        }
    };

    // Down: WS -> Client
    let ws_down = ws.clone();
    let bridge_cancel_down = bridge_cancel.clone();
    let down = async move {
        let mut bytes_before_stall: u64 = 0;
        let mut vless_parser = if is_vless {
            Some(crate::vless::VlessResponseParser::new())
        } else {
            None
        };

        loop {
            // Keep a single receive future alive across idle checks: a partial WS
            // header/fragment must never be discarded and then parsed again mid-frame.
            let res = activity.wait(ws_down.recv(), &bridge_cancel_down).await
                .unwrap_or_else(|error| Err(WsError::Io(error)));
            match res {
                Ok(data) => {
                    activity.touch();
                    let to_write = if let Some(ref mut parser) = vless_parser {
                        if !parser.is_header_parsed() {
                            match parser.process_chunk(&data) {
                                Ok(Some(payload)) => payload,
                                Ok(None) => continue,
                                Err(e) => {
                                    lwarn!("VLESS WS downstream: invalid response header: {}", e);
                                    crate::supervisor::ROUTE_SUPERVISOR.record_app_readiness(false, Some("VLESS WS downstream: invalid response header"));
                                    bridge_cancel_down.cancel();
                                    break;
                                }
                            }
                        } else {
                            data
                        }
                    } else {
                        data
                    };

                    if to_write.is_empty() {
                        continue;
                    }

                    let n = to_write.len();
                    bytes_before_stall = bytes_before_stall.saturating_add(n as u64);
                    STATS.bytes_down.fetch_add(n as i64, Ordering::Relaxed);
                    if let Err(e) = bounded_write(&mut c_write, &to_write, &bridge_cancel_down, BRIDGE_WRITE_TIMEOUT).await {
                        ldebug!("Bridge WS client write error: {}", e);
                        bridge_cancel_down.cancel();
                        break;
                    }
                }
                Err(WsError::Timeout) => {
                    // 30-second chunk timeout: check if connection was idle overall
                    let idle = ws_down.heartbeat_idle_for();
                    if idle >= crate::config::profile_aware_absolute_idle_timeout() {
                        ldebug!("Bridge WS download idle timeout exceeded ({}s)", idle.as_secs());
                        bridge_cancel_down.cancel();
                        break;
                    }
                    // Upload is actively transferring; continue download read
                    continue;
                }
                Err(e) => {
                    ldebug!("Bridge WS downstream recv closed/error: {:?}", e);
                    let _ = bounded_shutdown(&mut c_write, &bridge_cancel_down, BRIDGE_WRITE_TIMEOUT).await;
                    bridge_cancel_down.cancel();
                    break;
                }
            }
        }
        let domain = ws_down.recovery_scope().to_string();
        if bytes_before_stall == 0 && !bridge_cancel_down.is_cancelled() {
            crate::node_independence::NODE_INDEPENDENCE_TRACKER
                .write()
                .record_node_stall(&domain, 0);
        } else if bytes_before_stall > 0 {
            crate::node_independence::NODE_INDEPENDENCE_TRACKER
                .write()
                .record_node_bytes_transferred(&domain, bytes_before_stall);
        }
        let _ = bounded_shutdown(&mut c_write, &bridge_cancel_down, Duration::from_secs(2)).await;
    };

    let transfer = async {
        tokio::join!(up, down);
        bridge_cancel.cancel();
    };
    tokio::join!(transfer, heartbeat);
    ws.close().await;
}

async fn bridge_socks5_masque(
    client: TcpStream,
    tunnel: crate::masque::MasqueTunnel,
    cancel_token: CancellationToken,
) {
    if let Err(e) = tunnel.run_smoltcp_bridge(client, cancel_token).await {
        crate::ldebug!("MASQUE smoltcp bridge terminated: {}", e);
    }
    tunnel.close().await;
}

async fn bridge_socks5_awg(
    client: TcpStream,
    tunnel: crate::awg::AwgTunnel,
    cancel_token: CancellationToken,
) {
    if let Err(e) = tunnel.run_smoltcp_bridge(client, cancel_token).await {
        crate::ldebug!("AWG smoltcp bridge terminated: {}", e);
    }
    tunnel.close().await;
}

pub async fn bridge_socks5_stream<S>(
    client: TcpStream,
    upstream: S,
    initial_downlink: Vec<u8>,
    vision: Option<crate::vision::VisionContext>,
    is_vless: bool,
    cancel_token: CancellationToken,
) where
    S: tokio::io::AsyncReadExt + tokio::io::AsyncWriteExt + Send + Unpin + 'static,
{
    let (mut cr, mut cw) = client.into_split();
    let (mut ur, mut uw) = tokio::io::split(upstream);
    let bridge_cancel = cancel_token.child_token();
    let _bridge_guard = bridge_cancel.clone().drop_guard();
    let last_activity = Arc::new(parking_lot::RwLock::new(std::time::Instant::now()));

    let (vision_framer, mut vision_unpadder) = match vision {
        Some(ctx) => (
            Some(crate::vision::VisionFramer::new(ctx.uuid)),
            Some(crate::vision::VisionUnpadder::new(ctx.uuid)),
        ),
        None => (None, None),
    };

    let mut vless_parser = if is_vless {
        Some(crate::vless::VlessResponseParser::new())
    } else {
        None
    };

    if !initial_downlink.is_empty() {
        let payload_after_vless = if let Some(ref mut parser) = vless_parser {
            match parser.process_chunk(&initial_downlink) {
                Ok(Some(p)) => p,
                Ok(None) => Vec::new(),
                Err(e) => {
                    lwarn!("VLESS initial downlink: invalid response header: {}", e);
                    crate::supervisor::ROUTE_SUPERVISOR.record_app_readiness(false, Some("VLESS initial downlink: invalid response header"));
                    return;
                }
            }
        } else {
            initial_downlink
        };

        if !payload_after_vless.is_empty() {
            let to_write = if let Some(ref mut unpadder) = vision_unpadder {
                unpadder.unpad(&payload_after_vless)
            } else {
                payload_after_vless
            };
            if !to_write.is_empty() {
                STATS
                    .bytes_down
                    .fetch_add(to_write.len() as i64, Ordering::Relaxed);
                if bounded_write(&mut cw, &to_write, &cancel_token, BRIDGE_WRITE_TIMEOUT).await.is_err() {
                    return;
                }
            }
        }
    }

    // Up: Client -> Upstream
    let la_up = last_activity.clone();
    let bridge_cancel_up = bridge_cancel.clone();
    let up = async move {
        let mut buf = vec![0u8; WS_BRIDGE_CHUNK_SIZE];
        let mut framer = vision_framer;
        loop {
            let res = tokio::select! {
                _ = bridge_cancel_up.cancelled() => break,
                r = tokio::time::timeout(Duration::from_secs(30), cr.read(&mut buf)) => r,
            };
            match res {
                Ok(Ok(0)) => {
                    // Client EOF (Half-close: client finished sending upload payload)
                    if let Some(ref mut f) = framer {
                        let tail = f.finish();
                        if !tail.is_empty() {
                            if bounded_write(&mut uw, &tail, &bridge_cancel_up, BRIDGE_WRITE_TIMEOUT).await.is_err() {
                                bridge_cancel_up.cancel();
                                break;
                            }
                        }
                    }
                    // Send FIN to upstream
                    if bounded_shutdown(&mut uw, &bridge_cancel_up, BRIDGE_WRITE_TIMEOUT).await.is_err() {
                        bridge_cancel_up.cancel();
                    }
                    // Exit upload half without cancelling bridge_cancel (allow downstream to drain)
                    break;
                }
                Ok(Err(e)) => {
                    ldebug!("Bridge stream client read error: {}", e);
                    bridge_cancel_up.cancel();
                    break;
                }
                Err(_) => {
                    // 30-second chunk timeout: check if connection was idle overall
                    let idle = la_up.read().elapsed();
                    if idle >= crate::config::profile_aware_absolute_idle_timeout() {
                        ldebug!("Bridge stream overall idle timeout exceeded ({}s)", idle.as_secs());
                        bridge_cancel_up.cancel();
                        break;
                    }
                    // Downstream is actively transferring; continue upload read
                    continue;
                }
                Ok(Ok(n)) => {
                    *la_up.write() = std::time::Instant::now();
                    STATS.bytes_up.fetch_add(n as i64, Ordering::Relaxed);
                    let payload = &buf[..n];
                    let write_res = if let Some(ref mut f) = framer {
                        if f.is_direct() {
                            bounded_write(&mut uw, payload, &bridge_cancel_up, BRIDGE_WRITE_TIMEOUT).await
                        } else {
                            let framed = f.frame(payload);
                            if !framed.is_empty() {
                                bounded_write(&mut uw, &framed, &bridge_cancel_up, BRIDGE_WRITE_TIMEOUT).await
                            } else {
                                Ok(())
                            }
                        }
                    } else {
                        bounded_write(&mut uw, payload, &bridge_cancel_up, BRIDGE_WRITE_TIMEOUT).await
                    };
                    if let Err(e) = write_res {
                        ldebug!("Bridge stream upstream write error: {}", e);
                        bridge_cancel_up.cancel();
                        break;
                    }
                }
            }
        }
    };

    // Down: Upstream -> Client
    let la_down = last_activity.clone();
    let bridge_cancel_down = bridge_cancel.clone();
    let down = async move {
        let mut buf = vec![0u8; WS_BRIDGE_CHUNK_SIZE];
        let mut unpadder = vision_unpadder;
        let mut parser = vless_parser;
        loop {
            let res = tokio::select! {
                _ = bridge_cancel_down.cancelled() => break,
                r = tokio::time::timeout(Duration::from_secs(30), ur.read(&mut buf)) => r,
            };
            match res {
                Ok(Ok(0)) => {
                    // Upstream EOF (Half-close: upstream finished sending data)
                    // Shutdown client write half (send FIN to client)
                    if bounded_shutdown(&mut cw, &bridge_cancel_down, BRIDGE_WRITE_TIMEOUT).await.is_err() {
                        bridge_cancel_down.cancel();
                    }
                    // Peer FIN closes only its send direction; keep accepting upload.
                    break;
                }
                Ok(Err(e)) => {
                    ldebug!("Bridge stream upstream read error: {}", e);
                    bridge_cancel_down.cancel();
                    break;
                }
                Err(_) => {
                    // 30-second chunk timeout: check if connection was idle overall
                    let idle = la_down.read().elapsed();
                    if idle >= crate::config::profile_aware_absolute_idle_timeout() {
                        ldebug!("Bridge stream overall idle timeout exceeded ({}s)", idle.as_secs());
                        bridge_cancel_down.cancel();
                        break;
                    }
                    // Upload is actively transferring; continue download read
                    continue;
                }
                Ok(Ok(n)) => {
                    *la_down.write() = std::time::Instant::now();
                    let chunk = &buf[..n];
                    let payload_after_vless = if let Some(ref mut p) = parser {
                        if !p.is_header_parsed() {
                            match p.process_chunk(chunk) {
                                Ok(Some(payload)) => payload,
                                Ok(None) => continue,
                                Err(e) => {
                                    lwarn!("VLESS stream downstream: invalid response header: {}", e);
                                    crate::supervisor::ROUTE_SUPERVISOR.record_app_readiness(false, Some("VLESS stream downstream: invalid response header"));
                                    bridge_cancel_down.cancel();
                                    break;
                                }
                            }
                        } else {
                            chunk.to_vec()
                        }
                    } else {
                        chunk.to_vec()
                    };

                    if payload_after_vless.is_empty() {
                        continue;
                    }

                    let write_res = if let Some(ref mut u) = unpadder {
                        if u.is_direct() {
                            STATS.bytes_down.fetch_add(payload_after_vless.len() as i64, Ordering::Relaxed);
                            bounded_write(&mut cw, &payload_after_vless, &bridge_cancel_down, BRIDGE_WRITE_TIMEOUT).await
                        } else {
                            let unpadded = u.unpad(&payload_after_vless);
                            if !unpadded.is_empty() {
                                STATS
                                    .bytes_down
                                    .fetch_add(unpadded.len() as i64, Ordering::Relaxed);
                                bounded_write(&mut cw, &unpadded, &bridge_cancel_down, BRIDGE_WRITE_TIMEOUT).await
                            } else {
                                Ok(())
                            }
                        }
                    } else {
                        STATS.bytes_down.fetch_add(payload_after_vless.len() as i64, Ordering::Relaxed);
                        bounded_write(&mut cw, &payload_after_vless, &bridge_cancel_down, BRIDGE_WRITE_TIMEOUT).await
                    };

                    if let Err(e) = write_res {
                        ldebug!("Bridge stream client write error: {}", e);
                        bridge_cancel_down.cancel();
                        break;
                    }
                }
            }
        }
        let _ = bounded_shutdown(&mut cw, &bridge_cancel_down, Duration::from_secs(2)).await;
    };

    let _ = tokio::join!(up, down);
    bridge_cancel.cancel();
}

/// Determines the bind socket address for the UDP relay and the BND.ADDR to report to the SOCKS5 client.
/// - If the TCP client connected over loopback (127.0.0.1 or ::1), the UDP relay strictly binds to loopback
///   to prevent external LAN exposure.
/// - If the TCP client connected over LAN or external interface, the relay binds to the corresponding interface
///   (or unspecified if listening globally) and reports the appropriate interface IP to the client.
pub fn select_udp_relay_bind_addr(
    tcp_local_addr: SocketAddr,
    tcp_peer_addr: SocketAddr,
) -> (SocketAddr, IpAddr) {
    let is_client_loopback = tcp_peer_addr.ip().is_loopback();
    if is_client_loopback {
        if tcp_peer_addr.is_ipv6() {
            (
                SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 0),
                IpAddr::V6(Ipv6Addr::LOCALHOST),
            )
        } else {
            (
                SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0),
                IpAddr::V4(Ipv4Addr::LOCALHOST),
            )
        }
    } else {
        let bind_ip = if tcp_local_addr.ip().is_unspecified() {
            if tcp_peer_addr.is_ipv6() {
                IpAddr::V6(Ipv6Addr::UNSPECIFIED)
            } else {
                IpAddr::V4(Ipv4Addr::UNSPECIFIED)
            }
        } else {
            tcp_local_addr.ip()
        };

        let bnd_ip = if !tcp_local_addr.ip().is_unspecified() {
            tcp_local_addr.ip()
        } else {
            tcp_peer_addr.ip()
        };

        (SocketAddr::new(bind_ip, 0), bnd_ip)
    }
}

/// Enforces strict sender pinning for a SOCKS5 UDP association (RFC 1928, threat mitigation for local/LAN relays).
/// - Restricts incoming datagrams to the authorized client IP (loopback or matching LAN peer).
/// - If the client specified a port in DST.PORT of the TCP request, pins to that exact endpoint.
/// - If port was 0, pins to the source endpoint of the FIRST datagram that passes RFC 1928 header validation.
/// - Drops datagrams from any other sender port or IP, preventing session hijacking or redirection of replies.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UdpAssociationPin {
    pub authorized_ip: IpAddr,
    pub is_loopback: bool,
    pub pinned_endpoint: Option<SocketAddr>,
}

impl UdpAssociationPin {
    pub fn new(
        tcp_peer_addr: SocketAddr,
        req_ip: Option<IpAddr>,
        req_port: u16,
    ) -> Self {
        let is_loopback = tcp_peer_addr.ip().is_loopback();
        let authorized_ip = tcp_peer_addr.ip();
        let pinned_endpoint = if req_port != 0 {
            if let Some(ip) = req_ip {
                if ip == authorized_ip {
                    Some(SocketAddr::new(ip, req_port))
                } else {
                    Some(SocketAddr::new(authorized_ip, req_port))
                }
            } else {
                Some(SocketAddr::new(authorized_ip, req_port))
            }
        } else {
            None
        };

        Self {
            authorized_ip,
            is_loopback,
            pinned_endpoint,
        }
    }

    pub fn is_ip_authorized(&self, ip: IpAddr) -> bool {
        ip == self.authorized_ip
    }

    pub fn validate_and_pin_sender(&mut self, src_addr: SocketAddr) -> Result<(), &'static str> {
        if !self.is_ip_authorized(src_addr.ip()) {
            return Err("Unauthorized IP");
        }
        if let Some(pinned) = self.pinned_endpoint {
            if src_addr != pinned {
                return Err("Unauthorized port (pinned to different endpoint)");
            }
        } else {
            self.pinned_endpoint = Some(src_addr);
        }
        Ok(())
    }
}

pub static ACTIVE_GLOBAL_UDP_SESSIONS: std::sync::atomic::AtomicUsize =
    std::sync::atomic::AtomicUsize::new(0);

pub fn max_global_udp_sessions() -> usize {
    if crate::config::MOBILE_NETWORK.load(Ordering::Relaxed) {
        1024
    } else {
        2048
    }
}

pub const MAX_TARGET_BYTE_BUDGET: usize = 128 * 1024; // 128 KB max buffered bytes per target
pub const ACTIVE_CONVERSATION_PROTECT_SCORE: f64 = 4000.0;
pub const UDP_CHANNEL_CAPACITY: usize = 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UdpSessionState {
    Connecting,
    Established,
}

#[derive(Clone)]
pub struct UdpSessionStats {
    pub state: Arc<parking_lot::RwLock<UdpSessionState>>,
    pub last_activity: Arc<parking_lot::RwLock<Instant>>,
    pub packets_sent: Arc<std::sync::atomic::AtomicU64>,
    pub packets_received: Arc<std::sync::atomic::AtomicU64>,
    pub buffered_bytes: Arc<std::sync::atomic::AtomicUsize>,
}

impl UdpSessionStats {
    pub fn new() -> Self {
        let now = Instant::now();
        Self {
            state: Arc::new(parking_lot::RwLock::new(UdpSessionState::Connecting)),
            last_activity: Arc::new(parking_lot::RwLock::new(now)),
            packets_sent: Arc::new(std::sync::atomic::AtomicU64::new(1)),
            packets_received: Arc::new(std::sync::atomic::AtomicU64::new(0)),
            buffered_bytes: Arc::new(std::sync::atomic::AtomicUsize::new(0)),
        }
    }

    pub fn mark_established(&self) {
        *self.state.write() = UdpSessionState::Established;
        *self.last_activity.write() = Instant::now();
    }

    pub fn on_packet_sent(&self, bytes: usize) {
        self.packets_sent.fetch_add(1, Ordering::Relaxed);
        let cur = self.buffered_bytes.load(Ordering::Relaxed);
        if cur >= bytes {
            self.buffered_bytes.fetch_sub(bytes, Ordering::Relaxed);
        } else {
            self.buffered_bytes.store(0, Ordering::Relaxed);
        }
        *self.last_activity.write() = Instant::now();
    }

    pub fn on_packet_received(&self) {
        self.packets_received.fetch_add(1, Ordering::Relaxed);
        *self.last_activity.write() = Instant::now();
    }
}

pub struct UdpSessionItem {
    pub target_addr: String,
    pub tx: tokio::sync::mpsc::Sender<Vec<u8>>,
    pub cancel_token: CancellationToken,
    pub stats: UdpSessionStats,
    pub created_at: Instant,
}

impl UdpSessionItem {
    pub fn eviction_score(&self, now: Instant) -> f64 {
        let state = *self.stats.state.read();
        let last_act = *self.stats.last_activity.read();
        let idle_secs = if now >= last_act {
            now.duration_since(last_act).as_secs_f64()
        } else {
            0.0
        };
        let sent = self.stats.packets_sent.load(Ordering::Relaxed);
        let recv = self.stats.packets_received.load(Ordering::Relaxed);

        if state == UdpSessionState::Connecting {
            let age_secs = if now >= self.created_at {
                now.duration_since(self.created_at).as_secs_f64()
            } else {
                0.0
            };
            if age_secs > 10.0 {
                // Stale connect attempt
                return -1000.0 - age_secs;
            } else {
                return -500.0 - age_secs;
            }
        }

        if idle_secs > 60.0 {
            // Idle session expired past TTL
            return -100.0 - idle_secs;
        }

        // Active conversation protection:
        // VoIP calls and active streams have bidirectional traffic and recent activity (<10s)
        let is_active_conversation = recv > 0 && sent >= 3 && idle_secs < 10.0;
        if is_active_conversation {
            5000.0 + (sent + recv * 2) as f64 - idle_secs * 100.0
        } else {
            10.0 + (sent + recv) as f64 - idle_secs * 5.0
        }
    }
}

pub enum SessionAcquireResult {
    Existing,
    New(tokio::sync::mpsc::Receiver<Vec<u8>>, CancellationToken, UdpSessionStats),
    Rejected(&'static str),
}

pub struct UdpSessionTable {
    pub sessions: HashMap<String, UdpSessionItem>,
    pub max_per_assoc: usize,
    pub max_connecting: usize,
    pub last_sweep: Instant,
}

impl UdpSessionTable {
    pub fn new() -> Self {
        let is_mobile = crate::config::MOBILE_NETWORK.load(Ordering::Relaxed);
        let max_per_assoc = if is_mobile { 128 } else { 256 };
        let max_connecting = if is_mobile { 16 } else { 32 };
        Self {
            sessions: HashMap::new(),
            max_per_assoc,
            max_connecting,
            last_sweep: Instant::now(),
        }
    }

    pub fn sweep_idle(&mut self, ttl: Duration) {
        let now = Instant::now();
        let mut to_remove = Vec::new();
        for (addr, item) in &self.sessions {
            if item.cancel_token.is_cancelled() || item.tx.is_closed() {
                to_remove.push(addr.clone());
                continue;
            }
            let state = *item.stats.state.read();
            if state == UdpSessionState::Connecting {
                if now.duration_since(item.created_at) > Duration::from_secs(10) {
                    to_remove.push(addr.clone());
                }
            } else {
                let last_act = *item.stats.last_activity.read();
                if now.duration_since(last_act) > ttl {
                    to_remove.push(addr.clone());
                }
            }
        }
        for addr in to_remove {
            if let Some(item) = self.sessions.remove(&addr) {
                item.cancel_token.cancel();
                ACTIVE_GLOBAL_UDP_SESSIONS.fetch_sub(1, Ordering::Relaxed);
                ldebug!("SOCKS5 UDP: swept idle session {}", addr);
            }
        }
    }

    pub fn get_or_create(
        &mut self,
        target_addr: &str,
        assoc_cancel: &CancellationToken,
        payload: Vec<u8>,
    ) -> SessionAcquireResult {
        let now = Instant::now();
        if now.duration_since(self.last_sweep) > Duration::from_secs(5) {
            self.sweep_idle(Duration::from_secs(60));
            self.last_sweep = now;
        }

        let payload_len = payload.len();
        if assoc_cancel.is_cancelled() || payload_len > MAX_TARGET_BYTE_BUDGET {
            return SessionAcquireResult::Rejected("Cancelled association or oversized datagram");
        }

        // 1. Check if session already exists
        if let Some(item) = self.sessions.get(target_addr) {
            if !item.tx.is_closed() && !item.cancel_token.is_cancelled() {
                let cur = item.stats.buffered_bytes.load(Ordering::Relaxed);
                if cur + payload_len > MAX_TARGET_BYTE_BUDGET {
                    return SessionAcquireResult::Rejected("Target byte budget exceeded");
                }
                item.stats.buffered_bytes.fetch_add(payload_len, Ordering::Relaxed);
                if item.tx.try_send(payload).is_err() {
                    item.stats.buffered_bytes.fetch_sub(payload_len, Ordering::Relaxed);
                    return SessionAcquireResult::Rejected("Target channel queue full");
                }
                return SessionAcquireResult::Existing;
            }
        }

        // Clean up closed or dead session with same target if present
        if let Some(old) = self.sessions.remove(target_addr) {
            old.cancel_token.cancel();
            ACTIVE_GLOBAL_UDP_SESSIONS.fetch_sub(1, Ordering::Relaxed);
        }

        // 2. Connecting session limit check
        let connecting_count = self
            .sessions
            .values()
            .filter(|s| *s.stats.state.read() == UdpSessionState::Connecting)
            .count();

        if connecting_count >= self.max_connecting {
            let stale_connecting = self
                .sessions
                .iter()
                .find(|(_, s)| {
                    *s.stats.state.read() == UdpSessionState::Connecting
                        && now.duration_since(s.created_at) > Duration::from_secs(10)
                })
                .map(|(k, _)| k.clone());

            if let Some(stale_key) = stale_connecting {
                if let Some(evicted) = self.sessions.remove(&stale_key) {
                    evicted.cancel_token.cancel();
                    ACTIVE_GLOBAL_UDP_SESSIONS.fetch_sub(1, Ordering::Relaxed);
                }
            } else {
                return SessionAcquireResult::Rejected("Concurrent connecting sessions limit reached");
            }
        }

        // 3. Table capacity & eviction (Per-association & Global)
        let global_count = ACTIVE_GLOBAL_UDP_SESSIONS.load(Ordering::Relaxed);
        let max_global = max_global_udp_sessions();
        if self.sessions.len() >= self.max_per_assoc || global_count >= max_global {
            let mut best_candidate: Option<(String, f64)> = None;
            for (addr, item) in &self.sessions {
                let score = item.eviction_score(now);
                match &best_candidate {
                    None => best_candidate = Some((addr.clone(), score)),
                    Some((_, lowest_score)) => {
                        if score < *lowest_score {
                            best_candidate = Some((addr.clone(), score));
                        }
                    }
                }
            }

            if let Some((evict_addr, score)) = best_candidate {
                if score >= ACTIVE_CONVERSATION_PROTECT_SCORE {
                    // All sessions are protected active conversations; reject new scan traffic!
                    return SessionAcquireResult::Rejected("Active conversations protected from eviction");
                }
                if let Some(evicted) = self.sessions.remove(&evict_addr) {
                    evicted.cancel_token.cancel();
                    ACTIVE_GLOBAL_UDP_SESSIONS.fetch_sub(1, Ordering::Relaxed);
                    ldebug!(
                        "SOCKS5 UDP: evicted session {} (score {:.1}) to admit {}",
                        evict_addr,
                        score,
                        target_addr
                    );
                }
            } else {
                return SessionAcquireResult::Rejected("UDP session table full");
            }
        }

        if ACTIVE_GLOBAL_UDP_SESSIONS.load(Ordering::Relaxed) >= max_global {
            return SessionAcquireResult::Rejected("Global UDP sessions limit reached");
        }

        // 4. Create new target session
        let (tx, rx) = tokio::sync::mpsc::channel::<Vec<u8>>(UDP_CHANNEL_CAPACITY);
        let cancel_child = assoc_cancel.child_token();
        let stats = UdpSessionStats::new();
        stats.buffered_bytes.store(payload_len, Ordering::Relaxed);

        if tx.try_send(payload).is_err() {
            return SessionAcquireResult::Rejected("Initial packet enqueue failed");
        }

        if ACTIVE_GLOBAL_UDP_SESSIONS.fetch_update(Ordering::Relaxed, Ordering::Relaxed,
            |count| (count < max_global).then_some(count + 1)).is_err() {
            return SessionAcquireResult::Rejected("Global UDP sessions limit reached");
        }
        self.sessions.insert(
            target_addr.to_string(),
            UdpSessionItem {
                target_addr: target_addr.to_string(),
                tx,
                cancel_token: cancel_child.clone(),
                stats: stats.clone(),
                created_at: now,
            },
        );

        SessionAcquireResult::New(rx, cancel_child, stats)
    }

    // A cancelled/evicted task must not remove a replacement for the same target.
    pub fn remove_if_current(&mut self, target_addr: &str, stats: &UdpSessionStats) {
        if self.sessions.get(target_addr).is_some_and(|item| Arc::ptr_eq(&item.stats.state, &stats.state)) {
            self.remove(target_addr);
        }
    }

    pub fn remove(&mut self, target_addr: &str) {
        if let Some(item) = self.sessions.remove(target_addr) {
            item.cancel_token.cancel();
            ACTIVE_GLOBAL_UDP_SESSIONS.fetch_sub(1, Ordering::Relaxed);
        }
    }

    pub fn clear(&mut self) {
        let count = self.sessions.len();
        for item in self.sessions.values() {
            item.cancel_token.cancel();
        }
        self.sessions.clear();
        ACTIVE_GLOBAL_UDP_SESSIONS.fetch_sub(count, Ordering::Relaxed);
    }
}

impl Drop for UdpSessionTable {
    fn drop(&mut self) { self.clear(); }
}

async fn handle_socks5_udp_associate(mut client: TcpStream, atyp: u8, cancel: CancellationToken) {
    let tcp_local_addr = match client.local_addr() {
        Ok(a) => a,
        Err(_) => return,
    };
    let tcp_peer_addr = match client.peer_addr() {
        Ok(a) => a,
        Err(_) => return,
    };

    // 1. Consume the client's destination address and port from the TCP request (RFC 1928)
    let request = tokio::select! {
        biased;
        _ = cancel.cancelled() => return,
        request = tokio::time::timeout(Duration::from_secs(5), async {
            let req_ip: Option<IpAddr> = match atyp {
                0x01 => {
                    let mut ip = [0u8; 4];
                    if client.read_exact(&mut ip).await.is_err() {
                        return None;
                    }
                    let v4 = Ipv4Addr::from(ip);
                    if v4.is_unspecified() {
                        None
                    } else {
                        Some(IpAddr::V4(v4))
                    }
                }
                0x03 => {
                    let mut len = [0u8; 1];
                    if client.read_exact(&mut len).await.is_err() {
                        return None;
                    }
                    let mut domain = vec![0u8; len[0] as usize];
                    if client.read_exact(&mut domain).await.is_err() {
                        return None;
                    }
                    let s = String::from_utf8_lossy(&domain);
                    s.parse::<IpAddr>().ok().filter(|ip| !ip.is_unspecified())
                }
                0x04 => {
                    let mut ip = [0u8; 16];
                    if client.read_exact(&mut ip).await.is_err() {
                        return None;
                    }
                    let v6 = Ipv6Addr::from(ip);
                    if v6.is_unspecified() {
                        None
                    } else {
                        Some(IpAddr::V6(v6))
                    }
                }
                _ => {
                    let _ = client
                        .write_all(&[0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
                        .await;
                    return None;
                }
            };
            let mut port_buf = [0u8; 2];
            if client.read_exact(&mut port_buf).await.is_err() {
                return None;
            }
            Some((req_ip, u16::from_be_bytes(port_buf)))

        }) => request,
    };
    let Ok(Some((req_ip, req_port))) = request else { return; };

    // 2. RFC 1928: Verify if active route/uplink mode supports UDP ASSOCIATE
    let route_pin = crate::supervisor::ROUTE_SUPERVISOR.pin_udp_route();
    if route_pin.is_none() {
        lwarn!("SOCKS5 UDP ASSOCIATE rejected: Active route does not support UDP");
        let _ = client
            .write_all(&[0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
            .await;
        return;
    }

    let route_pin = route_pin.expect("UDP route checked above");

    // 3. Bind local UDP socket for relaying client datagrams (differentiating loopback vs LAN)
    let (udp_bind_addr, bnd_ip) = select_udp_relay_bind_addr(tcp_local_addr, tcp_peer_addr);
    let udp_socket = match tokio::net::UdpSocket::bind(udp_bind_addr).await {
        Ok(s) => Arc::new(s),
        Err(e) => {
            lwarn!(
                "SOCKS5 UDP ASSOCIATE: Failed to bind UDP relay on {}: {:?}",
                udp_bind_addr,
                e
            );
            let _ = client
                .write_all(&[0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
                .await;
            return;
        }
    };

    let bound_addr = match udp_socket.local_addr() {
        Ok(a) => a,
        Err(_) => return,
    };
    let bound_port = bound_addr.port();
    let port_bytes = bound_port.to_be_bytes();

    if cancel.is_cancelled() || !crate::supervisor::ROUTE_SUPERVISOR.udp_pin_is_current(&route_pin) {
        return;
    }

    // 4. Send SOCKS5 success response with BND.ADDR and BND.PORT
    let mut reply = Vec::with_capacity(22);
    reply.push(0x05); // VER
    reply.push(0x00); // REP = Succeeded
    reply.push(0x00); // RSV
    match bnd_ip {
        IpAddr::V4(v4) => {
            reply.push(0x01); // ATYP = IPv4
            reply.extend_from_slice(&v4.octets());
        }
        IpAddr::V6(v6) => {
            reply.push(0x04); // ATYP = IPv6
            reply.extend_from_slice(&v6.octets());
        }
    }
    reply.extend_from_slice(&port_bytes);

    if client.write_all(&reply).await.is_err() {
        return;
    }

    linfo!(
        "SOCKS5 UDP ASSOCIATE established: bound {} for TCP peer {}",
        bound_addr,
        tcp_peer_addr
    );

    // 5. RFC 1928: UDP association terminates when the TCP connection closes
    let assoc_cancel = cancel.child_token();
    let _association_guard = assoc_cancel.clone().drop_guard();
    let mut tasks = tokio::task::JoinSet::new();
    let tcp_watcher_cancel = assoc_cancel.clone();
    tasks.spawn(async move {
        let mut dummy = [0u8; 128];
        loop {
            tokio::select! {
                _ = tcp_watcher_cancel.cancelled() => break,
                res = client.read(&mut dummy) => {
                    match res {
                        Ok(0) | Err(_) => {
                            tcp_watcher_cancel.cancel();
                            break;
                        }
                        Ok(_) => {}
                    }
                }
            }
        }
    });

    // 6. Manage UDP sessions per target address with client endpoint pinning & bounded session table
    let mut pin = UdpAssociationPin::new(tcp_peer_addr, req_ip, req_port);
    let client_udp_addr: Arc<parking_lot::RwLock<Option<SocketAddr>>> =
        Arc::new(parking_lot::RwLock::new(pin.pinned_endpoint));
    let session_table: Arc<tokio::sync::Mutex<UdpSessionTable>> =
        Arc::new(tokio::sync::Mutex::new(UdpSessionTable::new()));

    let mut buf = vec![0u8; 65536];
    let mut maintenance = tokio::time::interval(Duration::from_secs(1));
    loop {
        let res = tokio::select! {
            biased;
            _ = assoc_cancel.cancelled() => break,
            _ = maintenance.tick() => {
                if !crate::supervisor::ROUTE_SUPERVISOR.udp_pin_is_current(&route_pin) { break; }
                session_table.lock().await.sweep_idle(Duration::from_secs(60));
                continue;
            }
            _ = tasks.join_next(), if !tasks.is_empty() => continue,
            r = udp_socket.recv_from(&mut buf) => r,
        };

        let (n, src_addr) = match res {
            Ok(v) => v,
            Err(_) => break,
        };

        if !crate::supervisor::ROUTE_SUPERVISOR.udp_pin_is_current(&route_pin) { break; }

        // 1. IP validation: must originate from authorized client IP
        if !pin.is_ip_authorized(src_addr.ip()) {
            lwarn!(
                "SOCKS5 UDP: dropped datagram from unauthorized IP {} (expected client IP: {})",
                src_addr.ip(),
                tcp_peer_addr.ip()
            );
            continue;
        }

        // 2. Port validation: if already pinned, reject any sender with a different port
        if let Some(pinned) = pin.pinned_endpoint {
            if src_addr != pinned {
                lwarn!(
                    "SOCKS5 UDP: dropped datagram from unauthorized port {} (association is pinned to {})",
                    src_addr,
                    pinned
                );
                continue;
            }
        }

        // 3. RFC 1928: Validate SOCKS5 UDP header BEFORE modifying any association state
        let packet = match crate::vless::parse_socks5_udp_packet(&buf[..n]) {
            Ok(p) => p,
            Err(e) => {
                ldebug!("SOCKS5 UDP parse error from {}: {}", src_addr, e);
                continue;
            }
        };

        // 4. Pin endpoint on first valid datagram and update client_udp_addr
        if pin.pinned_endpoint.is_none() {
            linfo!(
                "SOCKS5 UDP: pinning association to client endpoint {} (TCP peer: {})",
                src_addr,
                tcp_peer_addr
            );
            let _ = pin.validate_and_pin_sender(src_addr);
            *client_udp_addr.write() = Some(src_addr);
        } else if client_udp_addr.read().is_none() {
            *client_udp_addr.write() = Some(src_addr);
        }

        // 5. Query or allocate target session with bounded budgets & LRU/TTL eviction
        let acquire_res = {
            let mut tbl = session_table.lock().await;
            if tasks.len() >= tbl.max_per_assoc * 2 + 1 { continue; }
            tbl.get_or_create(&packet.target_addr, &assoc_cancel, packet.payload)
        };

        match acquire_res {
            SessionAcquireResult::Existing => {
                continue;
            }
            SessionAcquireResult::Rejected(reason) => {
                ldebug!(
                    "SOCKS5 UDP: rejected datagram for {}: {}",
                    packet.target_addr,
                    reason
                );
                continue;
            }
            SessionAcquireResult::New(rx, cancel_child, stats) => {
                let target_addr = packet.target_addr.clone();
                let session_table_clone = session_table.clone();
                let udp_socket_clone = udp_socket.clone();
                let client_addr_clone = client_udp_addr.clone();
                let atyp = packet.atyp;
                let raw_addr = packet.raw_addr;
                let target_port = packet.target_port;

                let route_pin = route_pin.clone();
                tasks.spawn(async move {
                    let session_identity = stats.clone();
                    ldebug!("SOCKS5 UDP: Acquiring UDP uplink for {}", target_addr);
                    let uplink_opt = crate::supervisor::ROUTE_SUPERVISOR
                        .acquire_socks5_udp_uplink(&route_pin, &target_addr, &cancel_child)
                        .await;

                    let uplink = match uplink_opt {
                        Some(u) => u,
                        None => {
                            lwarn!(
                                "SOCKS5 UDP: Failed to acquire UDP uplink for {}",
                                target_addr
                            );
                            let mut tbl = session_table_clone.lock().await;
                            tbl.remove_if_current(&target_addr, &session_identity);
                            return;
                        }
                    };

                    stats.mark_established();
                    linfo!("SOCKS5 UDP: Uplink established for {}", target_addr);
                    match uplink {
                        crate::supervisor::SocksUdpUplink::Vless(vless_uplink) => {
                            bridge_vless_udp_target(
                                vless_uplink,
                                rx,
                                udp_socket_clone,
                                client_addr_clone,
                                atyp,
                                raw_addr,
                                target_port,
                                cancel_child,
                                stats,
                            )
                            .await;
                        }
                        crate::supervisor::SocksUdpUplink::Masque(masque_tunnel) => {
                            bridge_masque_udp_target(
                                masque_tunnel,
                                rx,
                                udp_socket_clone,
                                client_addr_clone,
                                atyp,
                                raw_addr,
                                target_port,
                                cancel_child,
                                stats,
                            )
                            .await;
                        }
                        crate::supervisor::SocksUdpUplink::Awg(awg_tunnel) => {
                            bridge_awg_udp_target(
                                awg_tunnel,
                                rx,
                                udp_socket_clone,
                                client_addr_clone,
                                atyp,
                                raw_addr,
                                target_port,
                                cancel_child,
                                stats,
                            )
                            .await;
                        }
                    }

                    let mut tbl = session_table_clone.lock().await;
                    tbl.remove_if_current(&target_addr, &session_identity);
                    ldebug!("SOCKS5 UDP: Uplink closed for {}", target_addr);
                });
            }
        }
    }

    assoc_cancel.cancel();
    {
        let mut tbl = session_table.lock().await;
        tbl.clear();
    }
    tasks.abort_all();
    while tasks.join_next().await.is_some() {}
    linfo!("SOCKS5 UDP ASSOCIATE closed on {}", bound_addr);
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UdpDatagramInfo<'a> {
    pub src_ip: IpAddr,
    pub src_port: u16,
    pub dst_port: u16,
    pub payload: &'a [u8],
}

pub fn build_ipv4_udp_packet(
    src_ip: Ipv4Addr,
    dst_ip: Ipv4Addr,
    src_port: u16,
    dst_port: u16,
    payload: &[u8],
) -> Vec<u8> {
    let udp_len = 8 + payload.len();
    let total_len = 20 + udp_len;
    let mut pkt = vec![0u8; total_len];

    // 1. IPv4 Header (20 bytes)
    pkt[0] = 0x45; // Version 4, IHL 5 (20 bytes)
    pkt[1] = 0x00; // DSCP / ECN
    pkt[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
    pkt[4..6].copy_from_slice(&[0x00, 0x00]); // Identification
    pkt[6..8].copy_from_slice(&[0x40, 0x00]); // Flags: DF (Don't Fragment)
    pkt[8] = 64; // TTL
    pkt[9] = 17; // Protocol: UDP (17)
    pkt[10..12].copy_from_slice(&[0x00, 0x00]); // Checksum placeholder
    pkt[12..16].copy_from_slice(&src_ip.octets());
    pkt[16..20].copy_from_slice(&dst_ip.octets());

    let ip_cksum = crate::masque::calc_internet_checksum(&pkt[0..20]);
    pkt[10..12].copy_from_slice(&ip_cksum.to_be_bytes());

    // 2. UDP Header (8 bytes)
    pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
    pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
    pkt[24..26].copy_from_slice(&(udp_len as u16).to_be_bytes());
    pkt[26..28].copy_from_slice(&[0x00, 0x00]); // Checksum placeholder

    // 3. Payload
    pkt[28..].copy_from_slice(payload);

    // 4. UDP Checksum with IPv4 Pseudo-Header
    let mut pseudo = Vec::with_capacity(12 + udp_len);
    pseudo.extend_from_slice(&src_ip.octets());
    pseudo.extend_from_slice(&dst_ip.octets());
    pseudo.push(0);
    pseudo.push(17);
    pseudo.extend_from_slice(&(udp_len as u16).to_be_bytes());
    pseudo.extend_from_slice(&pkt[20..]); // UDP header + payload

    let udp_cksum = crate::masque::calc_internet_checksum(&pseudo);
    let final_cksum = if udp_cksum == 0 { 0xffff } else { udp_cksum };
    pkt[26..28].copy_from_slice(&final_cksum.to_be_bytes());

    pkt
}

pub fn build_ipv6_udp_packet(
    src_ip: Ipv6Addr,
    dst_ip: Ipv6Addr,
    src_port: u16,
    dst_port: u16,
    payload: &[u8],
) -> Vec<u8> {
    let udp_len = 8 + payload.len();
    let total_len = 40 + udp_len;
    let mut pkt = vec![0u8; total_len];

    // 1. IPv6 Header (40 bytes)
    pkt[0..4].copy_from_slice(&[0x60, 0x00, 0x00, 0x00]); // Version 6, TC 0, Flow Label 0
    pkt[4..6].copy_from_slice(&(udp_len as u16).to_be_bytes()); // Payload Length
    pkt[6] = 17; // Next Header: UDP (17)
    pkt[7] = 64; // Hop Limit
    pkt[8..24].copy_from_slice(&src_ip.octets());
    pkt[24..40].copy_from_slice(&dst_ip.octets());

    // 2. UDP Header (8 bytes)
    pkt[40..42].copy_from_slice(&src_port.to_be_bytes());
    pkt[42..44].copy_from_slice(&dst_port.to_be_bytes());
    pkt[44..46].copy_from_slice(&(udp_len as u16).to_be_bytes());
    pkt[46..48].copy_from_slice(&[0x00, 0x00]); // Checksum placeholder

    // 3. Payload
    pkt[48..].copy_from_slice(payload);

    // 4. UDP Checksum with IPv6 Pseudo-Header
    let mut pseudo = Vec::with_capacity(40 + udp_len);
    pseudo.extend_from_slice(&src_ip.octets());
    pseudo.extend_from_slice(&dst_ip.octets());
    pseudo.extend_from_slice(&(udp_len as u32).to_be_bytes());
    pseudo.extend_from_slice(&[0, 0, 0, 17]);
    pseudo.extend_from_slice(&pkt[40..]); // UDP header + payload

    let udp_cksum = crate::masque::calc_internet_checksum(&pseudo);
    let final_cksum = if udp_cksum == 0 { 0xffff } else { udp_cksum };
    pkt[46..48].copy_from_slice(&final_cksum.to_be_bytes());

    pkt
}

pub fn parse_udp_from_ip_packet(ip_pkt: &[u8]) -> Option<UdpDatagramInfo<'_>> {
    if ip_pkt.is_empty() {
        return None;
    }
    let version = ip_pkt[0] >> 4;
    match version {
        4 => {
            if ip_pkt.len() < 20 {
                return None;
            }
            let ihl = (ip_pkt[0] & 0x0f) as usize * 4;
            if ip_pkt.len() < ihl + 8 {
                return None;
            }
            let proto = ip_pkt[9];
            if proto != 17 {
                return None;
            }
            let mut src_bytes = [0u8; 4];
            src_bytes.copy_from_slice(&ip_pkt[12..16]);
            let src_ip = IpAddr::V4(Ipv4Addr::from(src_bytes));
            let src_port = u16::from_be_bytes([ip_pkt[ihl], ip_pkt[ihl + 1]]);
            let dst_port = u16::from_be_bytes([ip_pkt[ihl + 2], ip_pkt[ihl + 3]]);
            let udp_len = u16::from_be_bytes([ip_pkt[ihl + 4], ip_pkt[ihl + 5]]) as usize;
            if udp_len < 8 || ip_pkt.len() < ihl + udp_len {
                return None;
            }
            let payload = &ip_pkt[ihl + 8..ihl + udp_len];
            Some(UdpDatagramInfo {
                src_ip,
                src_port,
                dst_port,
                payload,
            })
        }
        6 => {
            if ip_pkt.len() < 40 + 8 {
                return None;
            }
            let next_header = ip_pkt[6];
            if next_header != 17 {
                return None;
            }
            let mut src_bytes = [0u8; 16];
            src_bytes.copy_from_slice(&ip_pkt[8..24]);
            let src_ip = IpAddr::V6(Ipv6Addr::from(src_bytes));
            let src_port = u16::from_be_bytes([ip_pkt[40], ip_pkt[41]]);
            let dst_port = u16::from_be_bytes([ip_pkt[42], ip_pkt[43]]);
            let udp_len = u16::from_be_bytes([ip_pkt[44], ip_pkt[45]]) as usize;
            if udp_len < 8 || ip_pkt.len() < 40 + udp_len {
                return None;
            }
            let payload = &ip_pkt[48..40 + udp_len];
            Some(UdpDatagramInfo {
                src_ip,
                src_port,
                dst_port,
                payload,
            })
        }
        _ => None,
    }
}

async fn bridge_awg_udp_target(
    tunnel: crate::awg::AwgTunnel,
    mut rx: tokio::sync::mpsc::Receiver<Vec<u8>>,
    udp_socket: Arc<tokio::net::UdpSocket>,
    client_addr: Arc<parking_lot::RwLock<Option<SocketAddr>>>,
    atyp: u8,
    raw_addr: Vec<u8>,
    target_port: u16,
    cancel_token: CancellationToken,
    stats: UdpSessionStats,
) {
    let target_addr_str = tunnel.target_addr.clone();
    let (target_ip, resolved_target_port) =
        match crate::masque::parse_target_endpoint(&target_addr_str).await {
            Ok((ip, p)) => (ip, if p != 0 { p } else { target_port }),
            Err(e) => {
                lwarn!(
                    "AWG UDP: failed to resolve target endpoint '{}': {}",
                    target_addr_str,
                    e
                );
                return;
            }
        };

    let client_v4 = tunnel.peer.config.client_ipv4;
    let client_v6_opt = tunnel.peer.config.client_ipv6;

    if target_ip.is_ipv6() && client_v6_opt.is_none() {
        lwarn!(
            "AWG UDP: target '{}' is IPv6 but no client IPv6 configured; aborting",
            target_addr_str
        );
        return;
    }

    let local_port = tunnel.local_port;
    let peer = tunnel.peer.clone();
    let cancel_up = cancel_token.clone();
    let cancel_down = cancel_token.clone();

    let peer_up = peer.clone();
    let stats_up = stats.clone();
    let mut up_task = tokio::spawn(async move {
        loop {
            tokio::select! {
                _ = cancel_up.cancelled() => break,
                opt = rx.recv() => {
                    match opt {
                        Some(payload) => {
                            stats_up.on_packet_sent(payload.len());
                            let ip_pkt = match target_ip {
                                IpAddr::V4(v4) => {
                                    build_ipv4_udp_packet(client_v4, v4, local_port, resolved_target_port, &payload)
                                }
                                IpAddr::V6(v6) => {
                                    let c6 = match client_v6_opt {
                                        Some(c) => c,
                                        None => break,
                                    };
                                    build_ipv6_udp_packet(c6, v6, local_port, resolved_target_port, &payload)
                                }
                            };
                            STATS.bytes_up.fetch_add(payload.len() as i64, Ordering::Relaxed);
                            if let Err(e) = peer_up.send_ip_packet(&ip_pkt).await {
                                lwarn!("AWG UDP: send_ip_packet error: {}", e);
                                break;
                            }
                        }
                        None => break,
                    }
                }
            }
        }
    });

    let raw_addr_down = raw_addr.clone();
    let udp_socket_down = udp_socket.clone();
    let client_addr_down = client_addr.clone();
    let stats_down = stats.clone();
    let mut down_task = tokio::spawn(async move {
        let mut flow_rx = tunnel.flow_rx.lock().await;
        loop {
            let res = tokio::select! {
                _ = cancel_down.cancelled() => break,
                pkt_opt = tokio::time::timeout(Duration::from_secs(120), flow_rx.recv()) => pkt_opt,
            };
            match res {
                Ok(Some(ip_pkt)) => {
                    if let Some(info) = parse_udp_from_ip_packet(&ip_pkt) {
                        if info.dst_port == local_port {
                            stats_down.on_packet_received();
                            let s5_pkt = crate::vless::build_socks5_udp_packet(
                                atyp,
                                &raw_addr_down,
                                target_port,
                                info.payload,
                            );
                            let dest_opt = *client_addr_down.read();
                            if let Some(dest) = dest_opt {
                                STATS.bytes_down.fetch_add(info.payload.len() as i64, Ordering::Relaxed);
                                if udp_socket_down.send_to(&s5_pkt, dest).await.is_err() {
                                    break;
                                }
                            }
                        }
                    }
                }
                Ok(None) | Err(_) => break,
            }
        }
        tunnel.close().await;
    });

    tokio::select! {
        _ = cancel_token.cancelled() => {},
        _ = &mut up_task => {},
        _ = &mut down_task => {},
    }
    up_task.abort();
    down_task.abort();
    cancel_token.cancel();
}

async fn bridge_masque_udp_target(
    tunnel: crate::masque::MasqueTunnel,
    mut rx: tokio::sync::mpsc::Receiver<Vec<u8>>,
    udp_socket: Arc<tokio::net::UdpSocket>,
    client_addr: Arc<parking_lot::RwLock<Option<SocketAddr>>>,
    atyp: u8,
    raw_addr: Vec<u8>,
    target_port: u16,
    cancel_token: CancellationToken,
    stats: UdpSessionStats,
) {
    if tunnel.is_raw_l4 {
        lwarn!("MASQUE UDP: raw L4 connect mode does not support L3 UDP datagrams; aborting");
        return;
    }

    let target_addr_str = tunnel.target_addr.clone();
    let (target_ip, resolved_target_port) =
        match crate::masque::parse_target_endpoint(&target_addr_str).await {
            Ok((ip, p)) => (ip, if p != 0 { p } else { target_port }),
            Err(e) => {
                lwarn!(
                    "MASQUE UDP: failed to resolve target endpoint '{}': {}",
                    target_addr_str,
                    e
                );
                return;
            }
        };

    let (client_v4_opt, client_v6_opt) = {
        let cfg = crate::masque::WARP_CONFIG.read();
        let v4 = if !cfg.client_ipv4.trim().is_empty() {
            cfg.client_ipv4.trim().parse::<Ipv4Addr>().ok()
        } else {
            Some(Ipv4Addr::new(172, 16, 0, 2))
        };
        let v6 = if !cfg.client_ipv6.trim().is_empty() {
            cfg.client_ipv6.trim().parse::<Ipv6Addr>().ok()
        } else {
            None
        };
        (v4, v6)
    };

    let current_v4 = tunnel.effective_ipv4(client_v4_opt);
    let current_v6 = tunnel.effective_ipv6(client_v6_opt);

    if target_ip.is_ipv4() && current_v4.is_none() {
        lwarn!("MASQUE UDP: target is IPv4 but no client IPv4 available; aborting");
        return;
    }
    if target_ip.is_ipv6() && current_v6.is_none() {
        lwarn!("MASQUE UDP: target is IPv6 but no client IPv6 available; aborting");
        return;
    }

    let local_port = 40000 + (rand::random::<u16>() % 20000);
    let qid = tunnel.quarter_stream_id;
    let conn = tunnel.connection.clone();
    let cancel_up = cancel_token.clone();
    let cancel_down = cancel_token.clone();

    let conn_up = conn.clone();
    let stats_up = stats.clone();
    let mut up_task = tokio::spawn(async move {
        loop {
            tokio::select! {
                _ = cancel_up.cancelled() => break,
                opt = rx.recv() => {
                    match opt {
                        Some(payload) => {
                            stats_up.on_packet_sent(payload.len());
                            let ip_pkt = match target_ip {
                                IpAddr::V4(v4) => {
                                    build_ipv4_udp_packet(current_v4.unwrap(), v4, local_port, resolved_target_port, &payload)
                                }
                                IpAddr::V6(v6) => {
                                    build_ipv6_udp_packet(current_v6.unwrap(), v6, local_port, resolved_target_port, &payload)
                                }
                            };
                            let dgram = crate::masque::encode_h3_datagram(qid, 0, &ip_pkt);
                            STATS.bytes_up.fetch_add(payload.len() as i64, Ordering::Relaxed);
                            let send_res = tokio::time::timeout(
                                Duration::from_millis(100),
                                conn_up.send_datagram_wait(bytes::Bytes::from(dgram)),
                            )
                            .await;
                            match send_res {
                                Ok(Ok(())) => {}
                                Ok(Err(e)) => {
                                    ldebug!("MASQUE UDP send datagram error: {:?}", e);
                                    break;
                                }
                                Err(_) => {}
                            }
                        }
                        None => break,
                    }
                }
            }
        }
    });

    let raw_addr_down = raw_addr.clone();
    let udp_socket_down = udp_socket.clone();
    let client_addr_down = client_addr.clone();
    let stats_down = stats.clone();
    let mut down_task = tokio::spawn(async move {
        let mut dgram_rx = tunnel.dgram_rx.lock().await;
        loop {
            let res = tokio::select! {
                _ = cancel_down.cancelled() => break,
                pkt_opt = tokio::time::timeout(Duration::from_secs(120), dgram_rx.recv()) => pkt_opt,
            };
            match res {
                Ok(Some(ip_pkt_bytes)) => {
                    if let Some(info) = parse_udp_from_ip_packet(&ip_pkt_bytes) {
                        if info.dst_port == local_port {
                            stats_down.on_packet_received();
                            let s5_pkt = crate::vless::build_socks5_udp_packet(
                                atyp,
                                &raw_addr_down,
                                target_port,
                                info.payload,
                            );
                            let dest_opt = *client_addr_down.read();
                            if let Some(dest) = dest_opt {
                                STATS.bytes_down.fetch_add(info.payload.len() as i64, Ordering::Relaxed);
                                if udp_socket_down.send_to(&s5_pkt, dest).await.is_err() {
                                    break;
                                }
                            }
                        }
                    }
                }
                Ok(None) | Err(_) => break,
            }
        }
    });

    tokio::select! {
        _ = cancel_token.cancelled() => {},
        _ = &mut up_task => {},
        _ = &mut down_task => {},
    }
    up_task.abort();
    down_task.abort();
    cancel_token.cancel();
}

async fn bridge_vless_udp_target(
    uplink: crate::vless::VlessUplink,
    rx: tokio::sync::mpsc::Receiver<Vec<u8>>,
    udp_socket: Arc<tokio::net::UdpSocket>,
    client_addr: Arc<parking_lot::RwLock<Option<SocketAddr>>>,
    atyp: u8,
    raw_addr: Vec<u8>,
    target_port: u16,
    cancel_token: CancellationToken,
    stats: UdpSessionStats,
) {
    match uplink {
        crate::vless::VlessUplink::Ws(ws) => {
            let ws = Arc::new(ws);
            let ws_up = ws.clone();
            let cancel_up = cancel_token.clone();
            let stats_up = stats.clone();
            let mut rx = rx;
            let mut up = tokio::spawn(async move {
                loop {
                    tokio::select! {
                        _ = cancel_up.cancelled() => break,
                        opt = rx.recv() => {
                            match opt {
                                Some(payload) => {
                                    stats_up.on_packet_sent(payload.len());
                                    let packed = match crate::vless::pack_vless_udp_packet(&payload) {
                                        Ok(p) => p,
                                        Err(_) => continue,
                                    };
                                    STATS
                                        .bytes_up
                                        .fetch_add(payload.len() as i64, Ordering::Relaxed);
                                    let send_res = tokio::select! {
                                        _ = cancel_up.cancelled() => Err(crate::ws::WsError::Other("cancelled".to_string())),
                                        r = ws_up.send(&packed) => r,
                                    };
                                    if send_res.is_err() {
                                        break;
                                    }
                                }
                                None => break,
                            }
                        }
                    }
                }
            });

            let ws_down = ws.clone();
            let cancel_down = cancel_token.clone();
            let udp_socket_down = udp_socket.clone();
            let client_addr_down = client_addr.clone();
            let raw_addr_down = raw_addr.clone();
            let stats_down = stats.clone();
            let mut down = tokio::spawn(async move {
                let mut rx_buf = Vec::new();
                let mut vless_parser = crate::vless::VlessResponseParser::new();
                loop {
                    let res = tokio::select! {
                        _ = cancel_down.cancelled() => break,
                        r = ws_down.recv_with_timeout(BRIDGE_READ_TIMEOUT) => r,
                    };
                    match res {
                        Ok(data) => {
                            let payload = if !vless_parser.is_header_parsed() {
                                match vless_parser.process_chunk(&data) {
                                    Ok(Some(p)) => p,
                                    Ok(None) => continue,
                                    Err(e) => {
                                        lwarn!("VLESS WS UDP: response header error: {}", e);
                                        break;
                                    }
                                }
                            } else {
                                data
                            };

                            if payload.is_empty() {
                                continue;
                            }

                            rx_buf.extend_from_slice(&payload);
                            let packets = crate::vless::unpack_vless_udp_packets(&mut rx_buf);
                            for pkt in packets {
                                stats_down.on_packet_received();
                                STATS
                                    .bytes_down
                                    .fetch_add(pkt.len() as i64, Ordering::Relaxed);
                                let s5_pkt = crate::vless::build_socks5_udp_packet(
                                    atyp,
                                    &raw_addr_down,
                                    target_port,
                                    &pkt,
                                );
                                let dest_opt = *client_addr_down.read();
                                if let Some(dest) = dest_opt {
                                    let _ = udp_socket_down.send_to(&s5_pkt, dest).await;
                                }
                            }
                        }
                        Err(_) => break,
                    }
                }
            });

            tokio::select! {
                _ = cancel_token.cancelled() => {}
                _ = &mut up => {}
                _ = &mut down => {}
            }
            up.abort();
            down.abort();
            ws.close().await;
        }
        crate::vless::VlessUplink::Tcp(stream, initial_downlink, _) => {
            bridge_vless_udp_stream(
                stream,
                initial_downlink,
                rx,
                udp_socket,
                client_addr,
                atyp,
                raw_addr,
                target_port,
                cancel_token,
                stats,
            )
            .await;
        }
        crate::vless::VlessUplink::Tls(stream, initial_downlink, _) => {
            bridge_vless_udp_stream(
                stream,
                initial_downlink,
                rx,
                udp_socket,
                client_addr,
                atyp,
                raw_addr,
                target_port,
                cancel_token,
                stats,
            )
            .await;
        }
        crate::vless::VlessUplink::Reality(stream, initial_downlink, _) => {
            bridge_vless_udp_stream(
                stream,
                initial_downlink,
                rx,
                udp_socket,
                client_addr,
                atyp,
                raw_addr,
                target_port,
                cancel_token,
                stats,
            )
            .await;
        }
    }
}

async fn bridge_vless_udp_stream<S>(
    stream: S,
    initial_downlink: Vec<u8>,
    mut rx: tokio::sync::mpsc::Receiver<Vec<u8>>,
    udp_socket: Arc<tokio::net::UdpSocket>,
    client_addr: Arc<parking_lot::RwLock<Option<SocketAddr>>>,
    atyp: u8,
    raw_addr: Vec<u8>,
    target_port: u16,
    cancel_token: CancellationToken,
    stats: UdpSessionStats,
) where
    S: tokio::io::AsyncReadExt + tokio::io::AsyncWriteExt + Send + Unpin + 'static,
{
    let (mut ur, mut uw) = tokio::io::split(stream);
    let cancel_up = cancel_token.clone();
    let stats_up = stats.clone();
    let mut up = tokio::spawn(async move {
        loop {
            tokio::select! {
                _ = cancel_up.cancelled() => break,
                opt = rx.recv() => {
                    match opt {
                        Some(payload) => {
                            stats_up.on_packet_sent(payload.len());
                            let packed = match crate::vless::pack_vless_udp_packet(&payload) {
                                Ok(p) => p,
                                Err(_) => continue,
                            };
                            STATS
                                .bytes_up
                                .fetch_add(payload.len() as i64, Ordering::Relaxed);
                            if bounded_write(&mut uw, &packed, &cancel_up, BRIDGE_WRITE_TIMEOUT).await.is_err() {
                                break;
                            }
                        }
                        None => break,
                    }
                }
            }
        }
    });

    let cancel_down = cancel_token.clone();
    let stats_down = stats.clone();
    let mut down = tokio::spawn(async move {
        let mut vless_parser = crate::vless::VlessResponseParser::new();
        let mut rx_buf = Vec::new();

        if !initial_downlink.is_empty() {
            let initial_payload = match vless_parser.process_chunk(&initial_downlink) {
                Ok(Some(p)) => p,
                Ok(None) => Vec::new(),
                Err(e) => {
                    lwarn!("VLESS UDP stream: initial downlink error: {}", e);
                    return;
                }
            };
            if !initial_payload.is_empty() {
                rx_buf.extend_from_slice(&initial_payload);
                let initial_pkts = crate::vless::unpack_vless_udp_packets(&mut rx_buf);
                for pkt in initial_pkts {
                    stats_down.on_packet_received();
                    STATS
                        .bytes_down
                        .fetch_add(pkt.len() as i64, Ordering::Relaxed);
                    let s5_pkt = crate::vless::build_socks5_udp_packet(atyp, &raw_addr, target_port, &pkt);
                    let dest_opt = *client_addr.read();
                    if let Some(dest) = dest_opt {
                        let _ = udp_socket.send_to(&s5_pkt, dest).await;
                    }
                }
            }
        }

        let mut buf = vec![0u8; 65536];
        loop {
            let res = tokio::select! {
                _ = cancel_down.cancelled() => break,
                r = tokio::time::timeout(BRIDGE_READ_TIMEOUT, ur.read(&mut buf)) => r,
            };
            match res {
                Ok(Ok(0)) | Ok(Err(_)) | Err(_) => break,
                Ok(Ok(n)) => {
                    let chunk = &buf[..n];
                    let payload = if !vless_parser.is_header_parsed() {
                        match vless_parser.process_chunk(chunk) {
                            Ok(Some(p)) => p,
                            Ok(None) => continue,
                            Err(e) => {
                                lwarn!("VLESS UDP stream: response header error: {}", e);
                                break;
                            }
                        }
                    } else {
                        chunk.to_vec()
                    };

                    if payload.is_empty() {
                        continue;
                    }

                    rx_buf.extend_from_slice(&payload);
                    let packets = crate::vless::unpack_vless_udp_packets(&mut rx_buf);
                    for pkt in packets {
                        stats_down.on_packet_received();
                        STATS
                            .bytes_down
                            .fetch_add(pkt.len() as i64, Ordering::Relaxed);
                        let s5_pkt = crate::vless::build_socks5_udp_packet(
                            atyp,
                            &raw_addr,
                            target_port,
                            &pkt,
                        );
                        let dest_opt = *client_addr.read();
                        if let Some(dest) = dest_opt {
                            let _ = udp_socket.send_to(&s5_pkt, dest).await;
                        }
                    }
                }
            }
        }
    });

    tokio::select! {
        _ = cancel_token.cancelled() => {}
        _ = &mut up => {}
        _ = &mut down => {}
    }
    up.abort();
    down.abort();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_build_and_parse_ipv4_udp_packet() {
        let src_ip = Ipv4Addr::new(172, 16, 0, 2);
        let dst_ip = Ipv4Addr::new(1, 1, 1, 1);
        let src_port = 45000;
        let dst_port = 53;
        let payload = b"standard-dns-query";

        let pkt = build_ipv4_udp_packet(src_ip, dst_ip, src_port, dst_port, payload);

        // Verify IPv4 header
        assert_eq!(pkt[0] >> 4, 4); // IPv4
        assert_eq!(pkt[0] & 0x0f, 5); // IHL 5
        let total_len = u16::from_be_bytes([pkt[2], pkt[3]]) as usize;
        assert_eq!(total_len, 20 + 8 + payload.len());
        assert_eq!(pkt[9], 17); // Protocol UDP

        // IPv4 Header checksum verification: checksum over the 20 bytes must be 0
        let ip_cksum = crate::masque::calc_internet_checksum(&pkt[0..20]);
        assert_eq!(ip_cksum, 0);

        // UDP checksum should not be 0
        let udp_cksum = u16::from_be_bytes([pkt[26], pkt[27]]);
        assert_ne!(udp_cksum, 0);

        // Parse packet back
        let info = parse_udp_from_ip_packet(&pkt).expect("failed to parse IPv4 UDP packet");
        assert_eq!(info.src_ip, IpAddr::V4(src_ip));
        assert_eq!(info.src_port, src_port);
        assert_eq!(info.dst_port, dst_port);
        assert_eq!(info.payload, payload);
    }

    #[test]
    fn test_build_and_parse_ipv4_udp_packet_odd_payload() {
        let src_ip = Ipv4Addr::new(10, 0, 0, 1);
        let dst_ip = Ipv4Addr::new(8, 8, 8, 8);
        let src_port = 50123;
        let dst_port = 443;
        let payload = b"odd-bytes-payload-12345"; // 23 bytes (odd length)

        let pkt = build_ipv4_udp_packet(src_ip, dst_ip, src_port, dst_port, payload);
        let info = parse_udp_from_ip_packet(&pkt).expect("failed to parse odd-length packet");
        assert_eq!(info.src_ip, IpAddr::V4(src_ip));
        assert_eq!(info.src_port, src_port);
        assert_eq!(info.dst_port, dst_port);
        assert_eq!(info.payload, payload);
    }

    #[test]
    fn test_build_and_parse_ipv6_udp_packet() {
        let src_ip: Ipv6Addr = "2606:4700:110:8a49:f96e:ee7f:e311:806c".parse().unwrap();
        let dst_ip: Ipv6Addr = "2001:4860:4860::8888".parse().unwrap();
        let src_port = 55555;
        let dst_port = 53;
        let payload = b"ipv6-dns-query-payload";

        let pkt = build_ipv6_udp_packet(src_ip, dst_ip, src_port, dst_port, payload);

        // Verify IPv6 header
        assert_eq!(pkt[0] >> 4, 6); // IPv6
        let payload_len = u16::from_be_bytes([pkt[4], pkt[5]]) as usize;
        assert_eq!(payload_len, 8 + payload.len());
        assert_eq!(pkt[6], 17); // Next Header UDP

        // UDP checksum should not be 0
        let udp_cksum = u16::from_be_bytes([pkt[46], pkt[47]]);
        assert_ne!(udp_cksum, 0);

        // Parse packet back
        let info = parse_udp_from_ip_packet(&pkt).expect("failed to parse IPv6 UDP packet");
        assert_eq!(info.src_ip, IpAddr::V6(src_ip));
        assert_eq!(info.src_port, src_port);
        assert_eq!(info.dst_port, dst_port);
        assert_eq!(info.payload, payload);
    }

    #[test]
    fn test_parse_udp_from_ip_packet_invalid() {
        // Empty
        assert!(parse_udp_from_ip_packet(&[]).is_none());

        // Too short for IPv4
        assert!(parse_udp_from_ip_packet(&[0x45, 0, 0, 20]).is_none());

        // Not UDP (TCP proto = 6)
        let mut tcp_pkt = vec![0u8; 40];
        tcp_pkt[0] = 0x45;
        tcp_pkt[9] = 6;
        assert!(parse_udp_from_ip_packet(&tcp_pkt).is_none());

        // Truncated UDP header
        let mut short_udp = vec![0u8; 24]; // 20 bytes IP + only 4 bytes UDP
        short_udp[0] = 0x45;
        short_udp[9] = 17;
        assert!(parse_udp_from_ip_packet(&short_udp).is_none());
    }

    #[test]
    fn test_select_udp_relay_bind_addr_loopback_and_lan() {
        // 1. Loopback IPv4 client
        let local_v4: SocketAddr = "127.0.0.1:10808".parse().unwrap();
        let peer_v4: SocketAddr = "127.0.0.1:54321".parse().unwrap();
        let (bind_addr, bnd_ip) = select_udp_relay_bind_addr(local_v4, peer_v4);
        assert_eq!(bind_addr, "127.0.0.1:0".parse::<SocketAddr>().unwrap());
        assert_eq!(bnd_ip, IpAddr::V4(Ipv4Addr::LOCALHOST));

        // 2. Loopback IPv6 client
        let local_v6: SocketAddr = "[::1]:10808".parse().unwrap();
        let peer_v6: SocketAddr = "[::1]:54321".parse().unwrap();
        let (bind_addr6, bnd_ip6) = select_udp_relay_bind_addr(local_v6, peer_v6);
        assert_eq!(bind_addr6, "[::1]:0".parse::<SocketAddr>().unwrap());
        assert_eq!(bnd_ip6, IpAddr::V6(Ipv6Addr::LOCALHOST));

        // 3. LAN client on concrete interface
        let local_lan: SocketAddr = "192.168.1.10:10808".parse().unwrap();
        let peer_lan: SocketAddr = "192.168.1.55:49152".parse().unwrap();
        let (bind_lan, bnd_lan) = select_udp_relay_bind_addr(local_lan, peer_lan);
        assert_eq!(bind_lan, "192.168.1.10:0".parse::<SocketAddr>().unwrap());
        assert_eq!(bnd_lan, "192.168.1.10".parse::<IpAddr>().unwrap());

        // 4. Wildcard listener (0.0.0.0) with LAN client
        let local_any: SocketAddr = "0.0.0.0:10808".parse().unwrap();
        let (bind_any, bnd_any) = select_udp_relay_bind_addr(local_any, peer_lan);
        assert_eq!(bind_any, "0.0.0.0:0".parse::<SocketAddr>().unwrap());
        assert_eq!(bnd_any, "192.168.1.55".parse::<IpAddr>().unwrap());
    }

    #[test]
    fn test_udp_association_pin_initial_zero_port() {
        let tcp_peer: SocketAddr = "127.0.0.1:45000".parse().unwrap();
        let mut pin = UdpAssociationPin::new(tcp_peer, None, 0);
        assert_eq!(pin.pinned_endpoint, None);
        assert!(pin.is_loopback);

        // 1. Packet from unauthorized non-loopback IP is rejected
        let foreign_sender: SocketAddr = "192.168.1.99:5000".parse().unwrap();
        assert_eq!(pin.validate_and_pin_sender(foreign_sender), Err("Unauthorized IP"));
        assert_eq!(pin.pinned_endpoint, None); // Still unpinned

        // 2. First valid datagram from loopback pins the port
        let client_sender: SocketAddr = "127.0.0.1:51234".parse().unwrap();
        assert_eq!(pin.validate_and_pin_sender(client_sender), Ok(()));
        assert_eq!(pin.pinned_endpoint, Some(client_sender));

        // 3. Subsequent datagram from the same client endpoint is accepted
        assert_eq!(pin.validate_and_pin_sender(client_sender), Ok(()));

        // 4. Second local sender from a different port is rejected (mitigating local threat)
        let attacker_sender: SocketAddr = "127.0.0.1:59999".parse().unwrap();
        assert_eq!(
            pin.validate_and_pin_sender(attacker_sender),
            Err("Unauthorized port (pinned to different endpoint)")
        );

        // Pin remains strictly with the original client
        assert_eq!(pin.pinned_endpoint, Some(client_sender));
    }

    #[test]
    fn test_udp_association_pin_explicit_request_port() {
        let tcp_peer: SocketAddr = "127.0.0.1:45000".parse().unwrap();
        let req_ip = Some(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)));
        let mut pin = UdpAssociationPin::new(tcp_peer, req_ip, 33333);

        // Pinned immediately upon creation
        assert_eq!(
            pin.pinned_endpoint,
            Some("127.0.0.1:33333".parse::<SocketAddr>().unwrap())
        );

        // Datagram from expected port accepted
        assert_eq!(
            pin.validate_and_pin_sender("127.0.0.1:33333".parse().unwrap()),
            Ok(())
        );

        // Datagram from any other local port rejected immediately
        assert_eq!(
            pin.validate_and_pin_sender("127.0.0.1:33334".parse().unwrap()),
            Err("Unauthorized port (pinned to different endpoint)")
        );
    }

    #[test]
    fn test_udp_association_pin_lan_client() {
        let tcp_peer: SocketAddr = "192.168.1.77:50000".parse().unwrap();
        let mut pin = UdpAssociationPin::new(tcp_peer, None, 0);
        assert!(!pin.is_loopback);
        assert_eq!(pin.authorized_ip, "192.168.1.77".parse::<IpAddr>().unwrap());

        // Datagram from loopback rejected
        assert_eq!(
            pin.validate_and_pin_sender("127.0.0.1:1234".parse().unwrap()),
            Err("Unauthorized IP")
        );

        // Datagram from other LAN host rejected
        assert_eq!(
            pin.validate_and_pin_sender("192.168.1.78:5000".parse().unwrap()),
            Err("Unauthorized IP")
        );

        // First datagram from correct LAN client pinned
        let lan_sender: SocketAddr = "192.168.1.77:40001".parse().unwrap();
        assert_eq!(pin.validate_and_pin_sender(lan_sender), Ok(()));
        assert_eq!(pin.pinned_endpoint, Some(lan_sender));

        // Second port from same LAN client rejected
        let lan_sender2: SocketAddr = "192.168.1.77:40002".parse().unwrap();
        assert_eq!(
            pin.validate_and_pin_sender(lan_sender2),
            Err("Unauthorized port (pinned to different endpoint)")
        );
    }

    #[test]
    fn test_udp_session_table_capacity_and_eviction() {
        let assoc_cancel = CancellationToken::new();
        let mut table = UdpSessionTable::new(3, 2);

        // 1. Admit target 1 (connecting)
        let res1 = table.get_or_create("1.1.1.1:53", &assoc_cancel, vec![1, 2, 3]);
        let (mut _rx1, cancel1, stats1) = match res1 {
            SessionAcquireResult::New(rx, cancel, stats) => (rx, cancel, stats),
            _ => panic!("Expected New session for target 1"),
        };
        assert!(!cancel1.is_cancelled());

        // 2. Admit target 2 (connecting)
        let res2 = table.get_or_create("8.8.8.8:53", &assoc_cancel, vec![4, 5, 6]);
        let (mut _rx2, cancel2, _stats2) = match res2 {
            SessionAcquireResult::New(rx, cancel, stats) => (rx, cancel, stats),
            _ => panic!("Expected New session for target 2"),
        };
        assert!(!cancel2.is_cancelled());

        // 3. Third target while 2 are connecting and max_connecting = 2
        let res3_blocked = table.get_or_create("9.9.9.9:53", &assoc_cancel, vec![7, 8, 9]);
        match res3_blocked {
            SessionAcquireResult::Rejected(msg) => {
                assert_eq!(msg, "Concurrent connecting sessions limit reached");
            }
            _ => panic!("Expected Rejected due to max_connecting limit"),
        }

        // 4. Mark target 1 as established -> connecting count drops to 1
        stats1.mark_established();
        let res3 = table.get_or_create("9.9.9.9:53", &assoc_cancel, vec![7, 8, 9]);
        let (mut _rx3, cancel3, stats3) = match res3 {
            SessionAcquireResult::New(rx, cancel, stats) => (rx, cancel, stats),
            _ => panic!("Expected New session for target 3 once slot freed"),
        };
        stats3.mark_established();

        // 5. Table is now full (3/3: target 1 established, target 2 connecting, target 3 established)
        // Adding target 4 should evict target 2 (since target 2 is connecting and has lowest eviction score)
        let res4 = table.get_or_create("1.0.0.1:53", &assoc_cancel, vec![10, 11, 12]);
        let (mut _rx4, cancel4, _stats4) = match res4 {
            SessionAcquireResult::New(rx, cancel, stats) => (rx, cancel, stats),
            _ => panic!("Expected New session for target 4 after eviction"),
        };

        // Verify target 2 was evicted and cancelled
        assert!(cancel2.is_cancelled());
        assert!(!table.sessions.contains_key("8.8.8.8:53"));
        assert!(table.sessions.contains_key("1.1.1.1:53"));
        assert!(table.sessions.contains_key("9.9.9.9:53"));
        assert!(table.sessions.contains_key("1.0.0.1:53"));
        assert!(!cancel4.is_cancelled());

        // 6. Clear table
        table.clear();
        assert_eq!(table.sessions.len(), 0);
        assert!(cancel1.is_cancelled());
        assert!(cancel3.is_cancelled());
        assert!(cancel4.is_cancelled());
    }

    #[test]
    fn test_udp_session_table_protects_active_conversations() {
        let assoc_cancel = CancellationToken::new();
        let mut table = UdpSessionTable::new(2, 2);

        // 1. Establish session 1 as an active voice call (bidirectional, sent >= 3, recv > 0)
        let res1 = table.get_or_create("149.154.175.50:443", &assoc_cancel, vec![1; 50]);
        let (_rx1, cancel1, stats1) = match res1 {
            SessionAcquireResult::New(rx, cancel, stats) => (rx, cancel, stats),
            _ => panic!("Expected New session 1"),
        };
        stats1.mark_established();
        stats1.on_packet_sent(50);
        stats1.on_packet_sent(50);
        stats1.on_packet_sent(50);
        stats1.on_packet_received();
        assert!(stats1.is_active_conversation(Instant::now()));

        // 2. Establish session 2 as an active voice call
        let res2 = table.get_or_create("149.154.167.51:443", &assoc_cancel, vec![2; 50]);
        let (_rx2, cancel2, stats2) = match res2 {
            SessionAcquireResult::New(rx, cancel, stats) => (rx, cancel, stats),
            _ => panic!("Expected New session 2"),
        };
        stats2.mark_established();
        stats2.on_packet_sent(50);
        stats2.on_packet_sent(50);
        stats2.on_packet_sent(50);
        stats2.on_packet_received();
        assert!(stats2.is_active_conversation(Instant::now()));

        // Table is full (2/2) and BOTH sessions are active protected conversations
        // 3. Incoming scan probe should be rejected, PROTECTING the active conversations
        let scan_res = table.get_or_create("198.51.100.1:12345", &assoc_cancel, vec![9; 20]);
        match scan_res {
            SessionAcquireResult::Rejected(msg) => {
                assert_eq!(msg, "Active conversations protected from eviction");
            }
            _ => panic!("Expected scan probe to be rejected to protect active conversations"),
        }

        // Neither active call was cancelled or evicted
        assert!(!cancel1.is_cancelled());
        assert!(!cancel2.is_cancelled());
        assert!(table.sessions.contains_key("149.154.175.50:443"));
        assert!(table.sessions.contains_key("149.154.167.51:443"));

        table.clear();
    }

    #[test]
    fn test_udp_session_table_byte_budget_enforcement() {
        let assoc_cancel = CancellationToken::new();
        let mut table = UdpSessionTable::new(5, 5);

        // 1. Initial packet enqueued successfully
        let res = table.get_or_create("1.1.1.1:53", &assoc_cancel, vec![0u8; 1024]);
        let (mut rx, _cancel, stats) = match res {
            SessionAcquireResult::New(rx, cancel, stats) => (rx, cancel, stats),
            _ => panic!("Expected New session"),
        };
        assert_eq!(stats.buffered_bytes.load(Ordering::Relaxed), 1024);

        // 2. Existing session packet within budget
        let res_existing = table.get_or_create("1.1.1.1:53", &assoc_cancel, vec![0u8; 2048]);
        assert_eq!(res_existing, SessionAcquireResult::Existing);
        assert_eq!(stats.buffered_bytes.load(Ordering::Relaxed), 1024 + 2048);

        // 3. Artificially simulate buffered bytes exceeding MAX_TARGET_BYTE_BUDGET (128 KB)
        stats.buffered_bytes.store(MAX_TARGET_BYTE_BUDGET, Ordering::Relaxed);
        let res_overflow = table.get_or_create("1.1.1.1:53", &assoc_cancel, vec![0u8; 100]);
        match res_overflow {
            SessionAcquireResult::Rejected(msg) => {
                assert_eq!(msg, "Target buffered byte budget exceeded");
            }
            _ => panic!("Expected Rejected due to byte budget overflow"),
        }

        // 4. Consumer drains packets and decreases buffered bytes
        let _ = rx.try_recv();
        stats.on_packet_sent(65536);
        assert!(stats.buffered_bytes.load(Ordering::Relaxed) < MAX_TARGET_BYTE_BUDGET);

        // Now packet can be enqueued again
        let res_recovered = table.get_or_create("1.1.1.1:53", &assoc_cancel, vec![0u8; 100]);
        assert_eq!(res_recovered, SessionAcquireResult::Existing);

        table.clear();
    }

    #[tokio::test]
    async fn test_bounded_write_success_and_cancellation() {
        let (mut client_reader, mut server_writer) = tokio::io::duplex(1024);
        let cancel = CancellationToken::new();

        // 1. Successful bounded write
        let write_res = bounded_write(
            &mut server_writer,
            b"hello-bounded-write",
            &cancel,
            Duration::from_secs(2),
        )
        .await;
        assert!(write_res.is_ok());

        let mut read_buf = [0u8; 19];
        client_reader.read_exact(&mut read_buf).await.unwrap();
        assert_eq!(&read_buf, b"hello-bounded-write");

        // 2. Cancellation token immediately aborts bounded write
        cancel.cancel();
        let cancelled_res = bounded_write(
            &mut server_writer,
            b"should-not-be-written",
            &cancel,
            Duration::from_secs(2),
        )
        .await;
        assert!(cancelled_res.is_err());
        assert_eq!(
            cancelled_res.unwrap_err().kind(),
            std::io::ErrorKind::Interrupted
        );
    }

    #[tokio::test]
    async fn test_bounded_write_timeout_on_blocked_reader() {
        // Create small duplex buffer (16 bytes)
        let (_client_reader, mut server_writer) = tokio::io::duplex(16);
        let cancel = CancellationToken::new();

        // Fill buffer
        let _ = server_writer.write_all(&[1u8; 16]).await;

        // Next write without reader draining will block and hit timeout
        let timeout_res = bounded_write(
            &mut server_writer,
            &[2u8; 32],
            &cancel,
            Duration::from_millis(50),
        )
        .await;

        assert!(timeout_res.is_err());
        assert_eq!(
            timeout_res.unwrap_err().kind(),
            std::io::ErrorKind::TimedOut
        );
    }

    #[tokio::test]
    async fn test_bridge_stream_half_close_and_drain() {
        let (mut client_ep, proxy_client_ep) = tokio::io::duplex(4096);
        let (proxy_upstream_ep, mut upstream_ep) = tokio::io::duplex(4096);

        let (cr, cw) = tokio::io::split(proxy_client_ep);
        let (ur, uw) = tokio::io::split(proxy_upstream_ep);

        let cancel_token = CancellationToken::new();
        let bridge_cancel = cancel_token.child_token();
        let last_activity = Arc::new(parking_lot::RwLock::new(Instant::now()));

        // Spawn simulated bridge stream up and down
        let mut cr = cr;
        let mut uw = uw;
        let bridge_cancel_up = bridge_cancel.clone();
        let la_up = last_activity.clone();
        let up = tokio::spawn(async move {
            let mut buf = vec![0u8; 1024];
            loop {
                let res = tokio::select! {
                    _ = bridge_cancel_up.cancelled() => break,
                    r = tokio::time::timeout(Duration::from_secs(10), cr.read(&mut buf)) => r,
                };
                match res {
                    Ok(Ok(0)) => {
                        // Client EOF: half-close upload direction; send FIN to upstream
                        let _ = bounded_shutdown(&mut uw, &bridge_cancel_up, Duration::from_secs(2)).await;
                        break;
                    }
                    Ok(Ok(n)) => {
                        *la_up.write() = Instant::now();
                        if bounded_write(&mut uw, &buf[..n], &bridge_cancel_up, Duration::from_secs(2)).await.is_err() {
                            bridge_cancel_up.cancel();
                            break;
                        }
                    }
                    _ => {
                        bridge_cancel_up.cancel();
                        break;
                    }
                }
            }
        });

        let mut ur = ur;
        let mut cw = cw;
        let bridge_cancel_down = bridge_cancel.clone();
        let la_down = last_activity.clone();
        let down = tokio::spawn(async move {
            let mut buf = vec![0u8; 1024];
            loop {
                let res = tokio::select! {
                    _ = bridge_cancel_down.cancelled() => break,
                    r = tokio::time::timeout(Duration::from_secs(10), ur.read(&mut buf)) => r,
                };
                match res {
                    Ok(Ok(0)) => {
                        // Upstream EOF: send FIN to client
                        let _ = bounded_shutdown(&mut cw, &bridge_cancel_down, Duration::from_secs(2)).await;
                        bridge_cancel_down.cancel();
                        break;
                    }
                    Ok(Ok(n)) => {
                        *la_down.write() = Instant::now();
                        if bounded_write(&mut cw, &buf[..n], &bridge_cancel_down, Duration::from_secs(2)).await.is_err() {
                            bridge_cancel_down.cancel();
                            break;
                        }
                    }
                    _ => {
                        bridge_cancel_down.cancel();
                        break;
                    }
                }
            }
        });

        // 1. Client sends request to upstream
        client_ep.write_all(b"POST /data HTTP/1.1\r\n").await.unwrap();

        let mut req_buf = vec![0u8; 23];
        upstream_ep.read_exact(&mut req_buf).await.unwrap();
        assert_eq!(&req_buf, b"POST /data HTTP/1.1\r\n");

        // 2. Client half-closes write side (EOF)
        client_ep.shutdown().await.unwrap();

        // 3. Upstream reads EOF
        let mut eof_buf = [0u8; 10];
        let n = upstream_ep.read(&mut eof_buf).await.unwrap();
        assert_eq!(n, 0); // Upstream received EOF

        // 4. Upstream sends full response DOWN to client (verifying downstream drains despite upload half-closed)
        upstream_ep.write_all(b"HTTP/1.1 200 OK\r\n\r\nResponse-Body").await.unwrap();
        upstream_ep.shutdown().await.unwrap();

        // 5. Client successfully reads full response and reads EOF
        let mut resp_buf = vec![0u8; 32];
        client_ep.read_exact(&mut resp_buf).await.unwrap();
        assert_eq!(&resp_buf, b"HTTP/1.1 200 OK\r\n\r\nResponse-Body");

        let n2 = client_ep.read(&mut eof_buf).await.unwrap();
        assert_eq!(n2, 0);

        // 6. Tasks complete and join cleanly without hanging
        let res = tokio::time::timeout(Duration::from_secs(2), async {
            let _ = tokio::join!(up, down);
        })
        .await;
        assert!(res.is_ok(), "Tasks must join cleanly without hang on half-close");
    }
}
