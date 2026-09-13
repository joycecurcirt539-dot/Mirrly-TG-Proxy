use crate::cfproxy::*;
use crate::config::*;
use crate::ws::*;
use crate::{ldebug, linfo, lwarn};
use std::collections::HashMap;
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
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
    #[allow(unused_mut)]
    let mut ka = socket2::TcpKeepalive::new()
        .with_time(Duration::from_secs(30))
        .with_interval(Duration::from_secs(10));
    #[cfg(any(target_os = "android", unix))]
    {
        ka = ka.with_retries(3);
    }
    let _ = sock.set_tcp_keepalive(&ka);

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
            return;
        }
    };

    // SOCKS5 success response
    if client
        .write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
        .await
        .is_err()
    {
        match uplink {
            SocksUplink::Ws(w) | SocksUplink::VlessWs(w) => {
                w.close().await;
            }
            SocksUplink::Masque(m) => {
                m.close().await;
            }
            SocksUplink::Awg(a) => {
                a.close().await;
            }
            SocksUplink::Tcp(..) | SocksUplink::VlessTcp(..) => {}
            SocksUplink::Tls(..) => {}
            SocksUplink::Reality(..) => {}
        }
        return;
    }

    STATS.connections_cfproxy.fetch_add(1, Ordering::Relaxed);
    STATS.connections_ws.fetch_add(1, Ordering::Relaxed);

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
    let mode = crate::masque::get_uplink_mode();
    match mode {
        crate::masque::UPLINK_VLESS => {
            let (use_opera, opera_ep) = {
                let op = OPERA_VPN.read();
                (op.vless_enabled, op.endpoint.clone())
            };
            if use_opera && !opera_ep.is_empty() {
                if let Ok((host, port)) = crate::vless::parse_target_addr(target_addr) {
                    ldebug!(
                        "SOCKS5 VLESS standalone: connecting via Opera VPN {} -> {}:{}",
                        opera_ep,
                        host,
                        port
                    );
                    match crate::ws::connect_via_http_proxy(
                        &opera_ep,
                        &host,
                        port,
                        Duration::from_secs(5),
                    )
                    .await
                    {
                        Ok(stream) => {
                            linfo!(
                                "SOCKS5 VLESS connected via Opera VPN standalone {}",
                                opera_ep
                            );
                            return Some(SocksUplink::Tcp(stream, Vec::new(), None));
                        }
                        Err(e) => {
                            lwarn!("SOCKS5 VLESS: Opera VPN direct connect failed: {:?}", e);
                        }
                    }
                }
            }
            if let Some(uplink) =
                crate::vless::vless_acquire_uplink(target_addr, cancel_token).await
            {
                return Some(SocksUplink::from(uplink));
            }
            crate::linfo!("SOCKS5 VLESS fallback to Worker WSS for {}", target_addr);
            if let Some(ws) = socks5_acquire_cf_ws(target_addr, cancel_token).await {
                return Some(SocksUplink::Ws(ws));
            }
            None
        }
        crate::masque::UPLINK_MASQUE | crate::masque::UPLINK_WARP_CASCADE => {
            let (use_opera, opera_ep) = {
                let op = OPERA_VPN.read();
                (op.warp_enabled, op.endpoint.clone())
            };
            if use_opera && !opera_ep.is_empty() {
                if let Ok((host, port)) = crate::vless::parse_target_addr(target_addr) {
                    ldebug!(
                        "SOCKS5 WARP: connecting via Opera VPN {} -> {}:{}",
                        opera_ep,
                        host,
                        port
                    );
                    match crate::ws::connect_via_http_proxy(
                        &opera_ep,
                        &host,
                        port,
                        Duration::from_secs(5),
                    )
                    .await
                    {
                        Ok(stream) => {
                            linfo!("SOCKS5 WARP connected via Opera VPN {}", opera_ep);
                            crate::masque::set_active_cascade_stage(crate::masque::CASCADE_STAGE_MASQUE);
                            return Some(SocksUplink::Tcp(stream, Vec::new(), None));
                        }
                        Err(e) => {
                            lwarn!("SOCKS5 WARP: Opera VPN connect failed: {:?}", e);
                        }
                    }
                }
            }
            // Уровень 1: WARP MASQUE HTTP/3 (QUIC Anycast) — высокоскоростной прямой Anycast
            if let Some(tunnel) =
                crate::masque::masque_acquire_tunnel(target_addr, cancel_token).await
            {
                crate::masque::set_active_cascade_stage(crate::masque::CASCADE_STAGE_MASQUE);
                return Some(SocksUplink::Masque(tunnel));
            }
            // Уровень 2: WARP AmneziaWG — обфусцированный WireGuard Anycast против блокировок QUIC
            crate::lwarn!(
                "SOCKS5 Cascade: WARP MASQUE недоступен для {}, переключение на AmneziaWG",
                target_addr
            );
            if let Some(tunnel) = crate::awg::awg_acquire_tunnel(target_addr, cancel_token).await {
                crate::masque::set_active_cascade_stage(crate::masque::CASCADE_STAGE_AWG);
                return Some(SocksUplink::Awg(tunnel));
            }
            crate::lwarn!(
                "SOCKS5 Cascade: оба прямых Anycast-канала (MASQUE и AWG) недоступны для {}",
                target_addr
            );
            None
        }
        crate::masque::UPLINK_HYBRID => {
            if let Some(uplink) =
                crate::vless::vless_acquire_uplink(target_addr, cancel_token).await
            {
                return Some(SocksUplink::from(uplink));
            }
            let (use_opera, opera_ep) = {
                let op = OPERA_VPN.read();
                (op.warp_enabled, op.endpoint.clone())
            };
            if use_opera && !opera_ep.is_empty() {
                if let Ok((host, port)) = crate::vless::parse_target_addr(target_addr) {
                    match crate::ws::connect_via_http_proxy(
                        &opera_ep,
                        &host,
                        port,
                        Duration::from_secs(5),
                    )
                    .await
                    {
                        Ok(stream) => return Some(SocksUplink::Tcp(stream, Vec::new(), None)),
                        Err(_) => {}
                    }
                }
            }
            crate::linfo!("SOCKS5 Hybrid: fallback to WARP MASQUE for {}", target_addr);
            if let Some(tunnel) =
                crate::masque::masque_acquire_tunnel(target_addr, cancel_token).await
            {
                crate::masque::set_active_cascade_stage(crate::masque::CASCADE_STAGE_MASQUE);
                return Some(SocksUplink::Masque(tunnel));
            }
            if let Some(tunnel) = crate::awg::awg_acquire_tunnel(target_addr, cancel_token).await {
                crate::masque::set_active_cascade_stage(crate::masque::CASCADE_STAGE_AWG);
                return Some(SocksUplink::Awg(tunnel));
            }
            None
        }
        crate::masque::UPLINK_AWG => {
            let (use_opera, opera_ep) = {
                let op = OPERA_VPN.read();
                (op.warp_enabled, op.endpoint.clone())
            };
            if use_opera && !opera_ep.is_empty() {
                if let Ok((host, port)) = crate::vless::parse_target_addr(target_addr) {
                    ldebug!(
                        "SOCKS5 WARP (AWG): connecting via Opera VPN {} -> {}:{}",
                        opera_ep,
                        host,
                        port
                    );
                    match crate::ws::connect_via_http_proxy(
                        &opera_ep,
                        &host,
                        port,
                        Duration::from_secs(5),
                    )
                    .await
                    {
                        Ok(stream) => {
                            linfo!("SOCKS5 WARP connected via Opera VPN {}", opera_ep);
                            crate::masque::set_active_cascade_stage(crate::masque::CASCADE_STAGE_AWG);
                            return Some(SocksUplink::Tcp(stream, Vec::new(), None));
                        }
                        Err(e) => {
                            lwarn!("SOCKS5 WARP: Opera VPN connect failed: {:?}", e);
                        }
                    }
                }
            }
            // Уровень 1: WARP AmneziaWG — бронебойный обфусцированный WireGuard Anycast
            if let Some(tunnel) = crate::awg::awg_acquire_tunnel(target_addr, cancel_token).await {
                crate::masque::set_active_cascade_stage(crate::masque::CASCADE_STAGE_AWG);
                return Some(SocksUplink::Awg(tunnel));
            }
            crate::lwarn!(
                "SOCKS5: AWG tunnel failed for target {}, fallback to MASQUE Anycast",
                target_addr
            );
            // Уровень 2: WARP MASQUE — быстрый Anycast HTTP/3
            if let Some(tunnel) =
                crate::masque::masque_acquire_tunnel(target_addr, cancel_token).await
            {
                crate::masque::set_active_cascade_stage(crate::masque::CASCADE_STAGE_MASQUE);
                return Some(SocksUplink::Masque(tunnel));
            }
            crate::lwarn!(
                "SOCKS5: оба прямых Anycast-канала (AWG и MASQUE) недоступны для {}",
                target_addr
            );
            None
        }
        _ => {
            crate::masque::set_active_cascade_stage(crate::masque::CASCADE_STAGE_WORKER);
            socks5_acquire_cf_ws(target_addr, cancel_token)
                .await
                .map(SocksUplink::Ws)
        }
    }
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

    let (target_host, target_port) = if let Some(pos) = target_addr.rfind(':') {
        let h = target_addr[..pos].trim_matches(['[', ']']);
        let p = &target_addr[pos + 1..];
        (h, p)
    } else {
        (target_addr.trim_matches(['[', ']']), "443")
    };
    let path = format!(
        "/tcp?target={}&host={}&port={}&ip={}",
        target_addr, target_host, target_port, target_host
    );

    // 2. Candidate pool: User Worker (100% priority, 0ms stagger), then Developer Workers
    let last_worker = LAST_SOCKS5_WORKER.read().clone();
    let mut candidate_workers: Vec<String> = Vec::with_capacity(DEV_SOCKS5_WORKERS.len() + 2);

    // If user has specified a custom worker, it is ALWAYS candidate 0 (0ms stagger)
    if !user_domain.is_empty() {
        candidate_workers.push(user_domain.clone());
    }

    // If we have a previously successful worker that is not user_domain, prioritize next
    if !last_worker.is_empty()
        && !candidate_workers
            .iter()
            .any(|cw| cw.eq_ignore_ascii_case(&last_worker))
    {
        candidate_workers.push(last_worker.clone());
    }

    // Add developer fallback workers
    for &w in DEV_SOCKS5_WORKERS {
        if candidate_workers
            .iter()
            .any(|cw| cw.eq_ignore_ascii_case(w))
        {
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
        lwarn!("SOCKS5: all fallback workers unavailable (429 cooldown)");
        return None;
    }

    // Limit to top 4 candidates
    if active_workers.len() > 4 {
        active_workers.truncate(4);
    }

    ldebug!(
        "SOCKS5 Happy Eyeballs Race: {} workers for {}",
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
            let start = std::time::Instant::now();
            tokio::select! {
                _ = cancel.cancelled() => {}
                _ = tokio::time::sleep(delay) => {
                    let _permit = match sem.acquire().await {
                        Ok(p) => p,
                        Err(_) => return,
                    };
                    let (ws, resolved_ip, err) = cf_connect_domain(&worker, &path, 3.5).await;
                    if let Some(w) = ws {
                        let elapsed_ms = start.elapsed().as_millis() as u64;
                        if !resolved_ip.is_empty() {
                            ldebug!("SOCKS5 race ok {} via {} ({}ms)", worker, resolved_ip, elapsed_ms);
                        } else {
                            ldebug!("SOCKS5 race ok {} ({}ms)", worker, elapsed_ms);
                        }
                        let _ = tx.send((w, worker)).await;
                    } else if let Some(e) = err {
                        if crate::ws::is_cooldown_error(&e) {
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
                linfo!("SOCKS5 воркер выбран: {}", winner_domain);
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

    // Drain and close runner-up connections
    while let Ok((extra_ws, extra_domain)) = rx.try_recv() {
        ldebug!("SOCKS5 closing runner-up connection to {}", extra_domain);
        tokio::spawn(async move {
            let _ = extra_ws.close().await;
        });
    }

    winning_ws
}

async fn bridge_socks5_ws(
    client: TcpStream,
    ws: RawWebSocket,
    is_vless: bool,
    cancel_token: CancellationToken,
) {
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
        let mut vless_parser = if is_vless {
            Some(crate::vless::VlessResponseParser::new())
        } else {
            None
        };

        loop {
            let res = tokio::select! {
                _ = cancel_token_down.cancelled() => break,
                _ = cancel_down.notified() => break,
                r = ws_down.recv_with_timeout(BRIDGE_READ_TIMEOUT) => r,
            };
            match res {
                Ok(data) => {
                    let to_write = if let Some(ref mut parser) = vless_parser {
                        if !parser.is_header_parsed() {
                            match parser.process_chunk(&data) {
                                Ok(Some(payload)) => payload,
                                Ok(None) => continue,
                                Err(e) => {
                                    lwarn!("VLESS WS downstream: invalid response header: {}", e);
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
                    STATS.bytes_down.fetch_add(n as i64, Ordering::Relaxed);
                    *la_down.lock().await = std::time::Instant::now();
                    if c_write.write_all(&to_write).await.is_err() {
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

async fn bridge_socks5_stream<S>(
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
    let cancel = Arc::new(tokio::sync::Notify::new());

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
                if cw.write_all(&to_write).await.is_err() {
                    return;
                }
            }
        }
    }

    // Up: Client -> Upstream
    let cancel_up = cancel.clone();
    let cancel_token_up = cancel_token.clone();
    let up = tokio::spawn(async move {
        let mut buf = vec![0u8; WS_BRIDGE_CHUNK_SIZE];
        let mut framer = vision_framer;
        loop {
            let res = tokio::select! {
                _ = cancel_token_up.cancelled() => break,
                _ = cancel_up.notified() => break,
                r = tokio::time::timeout(BRIDGE_READ_TIMEOUT, cr.read(&mut buf)) => r,
            };
            match res {
                Ok(Ok(0)) => {
                    if let Some(ref mut f) = framer {
                        let tail = f.finish();
                        if !tail.is_empty() {
                            let _ = uw.write_all(&tail).await;
                        }
                    }
                    break;
                }
                Ok(Err(_)) | Err(_) => break,
                Ok(Ok(n)) => {
                    STATS.bytes_up.fetch_add(n as i64, Ordering::Relaxed);
                    let payload = &buf[..n];
                    if let Some(ref mut f) = framer {
                        if f.is_direct() {
                            if uw.write_all(payload).await.is_err() {
                                break;
                            }
                        } else {
                            let framed = f.frame(payload);
                            if !framed.is_empty() {
                                if uw.write_all(&framed).await.is_err() {
                                    break;
                                }
                            }
                        }
                    } else {
                        if uw.write_all(payload).await.is_err() {
                            break;
                        }
                    }
                }
            }
        }
        cancel_up.notify_waiters();
    });

    // Down: Upstream -> Client
    let cancel_down = cancel.clone();
    let cancel_token_down = cancel_token.clone();
    let down = tokio::spawn(async move {
        let mut buf = vec![0u8; WS_BRIDGE_CHUNK_SIZE];
        let mut unpadder = vision_unpadder;
        let mut parser = vless_parser;
        loop {
            let res = tokio::select! {
                _ = cancel_token_down.cancelled() => break,
                _ = cancel_down.notified() => break,
                r = tokio::time::timeout(BRIDGE_READ_TIMEOUT, ur.read(&mut buf)) => r,
            };
            match res {
                Ok(Ok(0)) | Ok(Err(_)) | Err(_) => break,
                Ok(Ok(n)) => {
                    let chunk = &buf[..n];
                    let payload_after_vless = if let Some(ref mut p) = parser {
                        if !p.is_header_parsed() {
                            match p.process_chunk(chunk) {
                                Ok(Some(payload)) => payload,
                                Ok(None) => continue,
                                Err(e) => {
                                    lwarn!("VLESS stream downstream: invalid response header: {}", e);
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

                    if let Some(ref mut u) = unpadder {
                        if u.is_direct() {
                            STATS.bytes_down.fetch_add(payload_after_vless.len() as i64, Ordering::Relaxed);
                            if cw.write_all(&payload_after_vless).await.is_err() {
                                break;
                            }
                        } else {
                            let unpadded = u.unpad(&payload_after_vless);
                            if !unpadded.is_empty() {
                                STATS
                                    .bytes_down
                                    .fetch_add(unpadded.len() as i64, Ordering::Relaxed);
                                if cw.write_all(&unpadded).await.is_err() {
                                    break;
                                }
                            }
                        }
                    } else {
                        STATS.bytes_down.fetch_add(payload_after_vless.len() as i64, Ordering::Relaxed);
                        if cw.write_all(&payload_after_vless).await.is_err() {
                            break;
                        }
                    }
                }
            }
        }
        cancel_down.notify_waiters();
    });

    let _ = tokio::join!(up, down);
    cancel.notify_waiters();
}

async fn handle_socks5_udp_associate(mut client: TcpStream, atyp: u8, cancel: CancellationToken) {
    // 1. Consume the client's destination address and port from the TCP request
    match atyp {
        0x01 => {
            let mut ip = [0u8; 4];
            if client.read_exact(&mut ip).await.is_err() {
                return;
            }
        }
        0x03 => {
            let mut len = [0u8; 1];
            if client.read_exact(&mut len).await.is_err() {
                return;
            }
            let mut domain = vec![0u8; len[0] as usize];
            if client.read_exact(&mut domain).await.is_err() {
                return;
            }
        }
        0x04 => {
            let mut ip = [0u8; 16];
            if client.read_exact(&mut ip).await.is_err() {
                return;
            }
        }
        _ => {
            let _ = client
                .write_all(&[0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
                .await;
            return;
        }
    }
    let mut port_buf = [0u8; 2];
    if client.read_exact(&mut port_buf).await.is_err() {
        return;
    }

    // 2. Bind local UDP socket for relaying client datagrams
    let udp_socket = match tokio::net::UdpSocket::bind("127.0.0.1:0").await {
        Ok(s) => Arc::new(s),
        Err(e) => {
            lwarn!(
                "SOCKS5 UDP ASSOCIATE: Failed to bind local UDP socket: {:?}",
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

    // 3. Send SOCKS5 success response: BND.ADDR = 127.0.0.1, BND.PORT = bound_port
    let reply = [
        0x05,
        0x00,
        0x00,
        0x01,
        127,
        0,
        0,
        1,
        port_bytes[0],
        port_bytes[1],
    ];
    if client.write_all(&reply).await.is_err() {
        return;
    }

    linfo!(
        "SOCKS5 UDP ASSOCIATE established on 127.0.0.1:{}",
        bound_port
    );

    // 4. RFC 1928: UDP association terminates when the TCP connection closes
    let assoc_cancel = cancel.child_token();
    let tcp_watcher_cancel = assoc_cancel.clone();
    tokio::spawn(async move {
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

    // 5. Manage UDP sessions per target address
    let client_udp_addr: Arc<parking_lot::RwLock<Option<SocketAddr>>> =
        Arc::new(parking_lot::RwLock::new(None));
    let sessions: Arc<tokio::sync::Mutex<HashMap<String, tokio::sync::mpsc::Sender<Vec<u8>>>>> =
        Arc::new(tokio::sync::Mutex::new(HashMap::new()));

    let mut buf = vec![0u8; 65536];
    loop {
        let res = tokio::select! {
            _ = assoc_cancel.cancelled() => break,
            r = udp_socket.recv_from(&mut buf) => r,
        };

        let (n, src_addr) = match res {
            Ok(v) => v,
            Err(_) => break,
        };

        *client_udp_addr.write() = Some(src_addr);

        let packet = match crate::vless::parse_socks5_udp_packet(&buf[..n]) {
            Ok(p) => p,
            Err(e) => {
                ldebug!("SOCKS5 UDP parse error from {}: {}", src_addr, e);
                continue;
            }
        };

        let mut sess_map = sessions.lock().await;
        if let Some(tx) = sess_map.get(&packet.target_addr) {
            if !tx.is_closed() {
                let _ = tx.try_send(packet.payload);
                continue;
            }
        }

        // New target session -> spawn worker
        let (tx, rx) = tokio::sync::mpsc::channel::<Vec<u8>>(256);
        let _ = tx.try_send(packet.payload);
        sess_map.insert(packet.target_addr.clone(), tx);
        drop(sess_map);

        let target_addr = packet.target_addr.clone();
        let sessions_clone = sessions.clone();
        let udp_socket_clone = udp_socket.clone();
        let client_addr_clone = client_udp_addr.clone();
        let cancel_child = assoc_cancel.child_token();
        let atyp = packet.atyp;
        let raw_addr = packet.raw_addr;
        let target_port = packet.target_port;

        tokio::spawn(async move {
            ldebug!("SOCKS5 UDP: Acquiring VLESS UDP uplink for {}", target_addr);
            let uplink_opt = crate::vless::vless_acquire_uplink_cmd(
                &target_addr,
                crate::vless::VLESS_CMD_UDP,
                &cancel_child,
            )
            .await;

            let uplink = match uplink_opt {
                Some(u) => u,
                None => {
                    lwarn!(
                        "SOCKS5 UDP: Failed to acquire VLESS UDP uplink for {}",
                        target_addr
                    );
                    let mut s = sessions_clone.lock().await;
                    s.remove(&target_addr);
                    return;
                }
            };

            linfo!("SOCKS5 UDP: Uplink established for {}", target_addr);
            bridge_vless_udp_target(
                uplink,
                rx,
                udp_socket_clone,
                client_addr_clone,
                atyp,
                raw_addr,
                target_port,
                cancel_child,
            )
            .await;

            let mut s = sessions_clone.lock().await;
            s.remove(&target_addr);
            ldebug!("SOCKS5 UDP: Uplink closed for {}", target_addr);
        });
    }

    assoc_cancel.cancel();
    linfo!("SOCKS5 UDP ASSOCIATE closed on 127.0.0.1:{}", bound_port);
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
) {
    match uplink {
        crate::vless::VlessUplink::Ws(ws) => {
            let ws = Arc::new(ws);
            let ws_up = ws.clone();
            let cancel_up = cancel_token.clone();
            let mut rx = rx;
            let mut up = tokio::spawn(async move {
                while let Some(payload) = rx.recv().await {
                    if cancel_up.is_cancelled() {
                        break;
                    }
                    let packed = match crate::vless::pack_vless_udp_packet(&payload) {
                        Ok(p) => p,
                        Err(_) => continue,
                    };
                    STATS
                        .bytes_up
                        .fetch_add(payload.len() as i64, Ordering::Relaxed);
                    if ws_up.send(&packed).await.is_err() {
                        break;
                    }
                }
            });

            let ws_down = ws.clone();
            let cancel_down = cancel_token.clone();
            let udp_socket_down = udp_socket.clone();
            let client_addr_down = client_addr.clone();
            let raw_addr_down = raw_addr.clone();
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
) where
    S: tokio::io::AsyncReadExt + tokio::io::AsyncWriteExt + Send + Unpin + 'static,
{
    let (mut ur, mut uw) = tokio::io::split(stream);
    let cancel_up = cancel_token.clone();
    let mut up = tokio::spawn(async move {
        while let Some(payload) = rx.recv().await {
            if cancel_up.is_cancelled() {
                break;
            }
            let packed = match crate::vless::pack_vless_udp_packet(&payload) {
                Ok(p) => p,
                Err(_) => continue,
            };
            STATS
                .bytes_up
                .fetch_add(payload.len() as i64, Ordering::Relaxed);
            if uw.write_all(&packed).await.is_err() {
                break;
            }
        }
    });

    let cancel_down = cancel_token.clone();
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
