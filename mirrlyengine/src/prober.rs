// mirrlyengine/src/prober.rs
//
// Mirrly TG Proxy - High-Performance Native Prober Engine
// Implements fast, protected UDP Anycast scanning, TCP probing and Worker diagnostics in Rust.
// Copyright (C) 2026 R1Xern (Mirrly Dev)

use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use serde::Serialize;
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::Semaphore;

pub const CLOUDFLARE_WARP_PEER_PUBKEY_B64: &str = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=";

#[derive(Serialize, Clone, Debug)]
pub struct WarpEndpointProbeResult {
    pub endpoint: String,
    pub ip: String,
    pub port: u16,
    pub rtt_ms: i64,
    pub is_alive: bool,
    pub stage: String,
}

pub fn create_wireguard_initiation_probe(peer_pub_b64: &str) -> Vec<u8> {
    let peer_pub = B64
        .decode(peer_pub_b64)
        .unwrap_or_else(|_| B64.decode(CLOUDFLARE_WARP_PEER_PUBKEY_B64).unwrap());
    let mut pkt = vec![0u8; 148];
    // Message Type = 1 (Initiation)
    pkt[0] = 0x01;
    // sender index [4..8]
    let sender_idx: u32 = rand::random();
    pkt[4..8].copy_from_slice(&sender_idx.to_le_bytes());
    // unencrypted_ephemeral [8..40]
    rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut pkt[8..40]);
    // encrypted_static [40..88]
    rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut pkt[40..88]);
    // encrypted_timestamp [88..116]
    rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut pkt[88..116]);
    // MAC1 [116..132]
    let mut mac1_key_input = Vec::with_capacity(8 + 32);
    mac1_key_input.extend_from_slice(b"mac1----");
    mac1_key_input.extend_from_slice(&peer_pub);
    let mac1_key = crate::awg::blake2s_256(&mac1_key_input);
    let mac1 = crate::awg::blake2s(&mac1_key, &pkt[0..116], 16);
    pkt[116..132].copy_from_slice(&mac1);
    // MAC2 [132..148] = zeros
    pkt
}

fn parse_endpoint(ep: &str) -> Option<(String, u16)> {
    let trimmed = ep.trim();
    if trimmed.is_empty() {
        return None;
    }
    if trimmed.starts_with('[') {
        let close_bracket = trimmed.find(']')?;
        let ip = &trimmed[1..close_bracket];
        let port_part = &trimmed[close_bracket + 1..];
        let port = port_part.strip_prefix(':')?.parse().ok()?;
        Some((ip.to_string(), port))
    } else {
        let parts: Vec<&str> = trimmed.split(':').collect();
        if parts.len() == 2 {
            let ip = parts[0];
            let port = parts[1].parse().ok()?;
            Some((ip.to_string(), port))
        } else {
            None
        }
    }
}

pub fn parse_icmp_ping_rtt(output: &str) -> Option<i64> {
    // 1. time=112 ms или time=112.2 ms
    if let Some(idx) = output.find("time=") {
        let after = &output[idx + 5..];
        let end_idx = after.find(" ms").or_else(|| after.find(' ')).or_else(|| after.find('\n'))?;
        let num_str = after[..end_idx].trim();
        if let Ok(val) = num_str.parse::<f64>() {
            if val > 0.0 {
                return Some(val.round() as i64);
            }
        }
    }
    // 2. rtt min/avg/max/mdev = 112.202/112.202/112.202/0.000 ms
    if let Some(idx) = output.find("min/avg/max/mdev = ") {
        let after = &output[idx + 19..];
        let parts: Vec<&str> = after.split('/').collect();
        if parts.len() >= 2 {
            if let Ok(avg) = parts[1].trim().parse::<f64>() {
                if avg > 0.0 {
                    return Some(avg.round() as i64);
                }
            }
        }
    }
    None
}

pub async fn probe_system_icmp_ping(target: &str, timeout_ms: u64) -> Option<i64> {
    #[cfg(target_os = "android")]
    let ping_cmd = if target.contains(':') { "/system/bin/ping6" } else { "/system/bin/ping" };
    #[cfg(not(target_os = "android"))]
    let ping_cmd = "ping";

    let timeout_sec = ((timeout_ms + 999) / 1000).max(1);

    let mut cmd = tokio::process::Command::new(ping_cmd);
    #[cfg(target_os = "android")]
    cmd.args(&["-c", "1", "-W", &timeout_sec.to_string(), target]);

    #[cfg(windows)]
    cmd.args(&["-n", "1", "-w", &timeout_ms.to_string(), target]);

    #[cfg(all(unix, not(target_os = "android")))]
    cmd.args(&["-c", "1", "-W", &timeout_sec.to_string(), target]);

    let output = match tokio::time::timeout(Duration::from_millis(timeout_ms + 250), cmd.output()).await {
        Ok(Ok(out)) => String::from_utf8_lossy(&out.stdout).to_string(),
        _ => return None,
    };

    parse_icmp_ping_rtt(&output)
}

pub async fn probe_single_warp_endpoint(
    endpoint: &str,
    protocol: i32,
    timeout_ms: u64,
) -> WarpEndpointProbeResult {
    let (ip_str, port) = match parse_endpoint(endpoint) {
        Some(res) => res,
        None => {
            return WarpEndpointProbeResult {
                endpoint: endpoint.to_string(),
                ip: String::new(),
                port: 0,
                rtt_ms: -1,
                is_alive: false,
                stage: "INVALID_ENDPOINT".to_string(),
            };
        }
    };

    let target_ip: IpAddr = match ip_str.parse() {
        Ok(ip) => ip,
        Err(_) => {
            let resolved = match crate::dns::resolve_dual_stack(&ip_str).await {
                Ok(ips) if !ips.is_empty() => Some(ips[0]),
                _ => None,
            };
            match resolved {
                Some(ip) => ip,
                None => {
                    // Fallback через системный tokio lookup_host
                    match tokio::net::lookup_host(format!("{}:{}", ip_str, port)).await {
                        Ok(mut addrs) => match addrs.next() {
                            Some(addr) => addr.ip(),
                            None => {
                                if let Some(rtt) = probe_system_icmp_ping(&ip_str, timeout_ms).await {
                                    return WarpEndpointProbeResult {
                                        endpoint: endpoint.to_string(),
                                        ip: ip_str,
                                        port,
                                        rtt_ms: rtt,
                                        is_alive: true,
                                        stage: "ICMP_ECHO_RESPONSIVE".to_string(),
                                    };
                                }
                                return WarpEndpointProbeResult {
                                    endpoint: endpoint.to_string(),
                                    ip: ip_str,
                                    port,
                                    rtt_ms: -1,
                                    is_alive: false,
                                    stage: "DNS_RESOLUTION_FAILED".to_string(),
                                };
                            }
                        },
                        Err(_) => {
                            if let Some(rtt) = probe_system_icmp_ping(&ip_str, timeout_ms).await {
                                return WarpEndpointProbeResult {
                                    endpoint: endpoint.to_string(),
                                    ip: ip_str,
                                    port,
                                    rtt_ms: rtt,
                                    is_alive: true,
                                    stage: "ICMP_ECHO_RESPONSIVE".to_string(),
                                };
                            }
                            return WarpEndpointProbeResult {
                                endpoint: endpoint.to_string(),
                                ip: ip_str,
                                port,
                                rtt_ms: -1,
                                is_alive: false,
                                stage: "DNS_RESOLUTION_FAILED".to_string(),
                            };
                        }
                    }
                }
            }
        }
    };

    let socket_addr = SocketAddr::new(target_ip, port);
    let _ = protocol;
    let payload = create_wireguard_initiation_probe(CLOUDFLARE_WARP_PEER_PUBKEY_B64);

    let domain = if target_ip.is_ipv6() {
        socket2::Domain::IPV6
    } else {
        socket2::Domain::IPV4
    };

    let sock_res = socket2::Socket::new(domain, socket2::Type::DGRAM, Some(socket2::Protocol::UDP));
    let sock = match sock_res {
        Ok(s) => s,
        Err(_) => {
            if let Some(rtt) = probe_system_icmp_ping(&target_ip.to_string(), timeout_ms).await {
                return WarpEndpointProbeResult {
                    endpoint: endpoint.to_string(),
                    ip: target_ip.to_string(),
                    port,
                    rtt_ms: rtt,
                    is_alive: true,
                    stage: "ICMP_ECHO_RESPONSIVE".to_string(),
                };
            }
            return WarpEndpointProbeResult {
                endpoint: endpoint.to_string(),
                ip: target_ip.to_string(),
                port,
                rtt_ms: -1,
                is_alive: false,
                stage: "SOCKET_CREATE_FAILED".to_string(),
            };
        }
    };

    #[cfg(unix)]
    {
        use std::os::unix::io::AsRawFd;
        let raw_fd = sock.as_raw_fd();
        let _ = crate::protect_socket_fd(raw_fd);
    }

    let _ = sock.set_nonblocking(true);
    let std_sock: std::net::UdpSocket = sock.into();
    let tokio_sock = match tokio::net::UdpSocket::from_std(std_sock) {
        Ok(s) => s,
        Err(_) => {
            if let Some(rtt) = probe_system_icmp_ping(&target_ip.to_string(), timeout_ms).await {
                return WarpEndpointProbeResult {
                    endpoint: endpoint.to_string(),
                    ip: target_ip.to_string(),
                    port,
                    rtt_ms: rtt,
                    is_alive: true,
                    stage: "ICMP_ECHO_RESPONSIVE".to_string(),
                };
            }
            return WarpEndpointProbeResult {
                endpoint: endpoint.to_string(),
                ip: target_ip.to_string(),
                port,
                rtt_ms: -1,
                is_alive: false,
                stage: "TOKIO_CONVERT_FAILED".to_string(),
            };
        }
    };

    if let Err(_) = tokio_sock.connect(socket_addr).await {
        if let Some(rtt) = probe_system_icmp_ping(&target_ip.to_string(), timeout_ms).await {
            return WarpEndpointProbeResult {
                endpoint: endpoint.to_string(),
                ip: target_ip.to_string(),
                port,
                rtt_ms: rtt,
                is_alive: true,
                stage: "ICMP_ECHO_RESPONSIVE".to_string(),
            };
        }
        return WarpEndpointProbeResult {
            endpoint: endpoint.to_string(),
            ip: target_ip.to_string(),
            port,
            rtt_ms: -1,
            is_alive: false,
            stage: "CONNECT_FAILED".to_string(),
        };
    }

    let t0 = Instant::now();
    let _ = tokio_sock.send(&payload).await;

    let mut buf = [0u8; 1500];
    let udp_timeout = timeout_ms.min(250);
    let recv_res = tokio::time::timeout(
        Duration::from_millis(udp_timeout),
        tokio_sock.recv(&mut buf),
    )
    .await;

    match recv_res {
        Ok(Ok(n)) => {
            let rtt = t0.elapsed().as_millis().max(1) as i64;
            // WireGuard: Response type 2 (92 bytes) or Cookie Reply type 3 (64 bytes)
            let stage = if n >= 60 && (buf[0] == 2 || buf[0] == 3) {
                "PROTOCOL_RECOGNIZED"
            } else {
                "UDP_RESPONSIVE"
            };
            WarpEndpointProbeResult {
                endpoint: endpoint.to_string(),
                ip: target_ip.to_string(),
                port,
                rtt_ms: rtt,
                is_alive: true,
                stage: stage.to_string(),
            }
        }
        _ => {
            // UDP did not respond (expected for WireGuard when unauthenticated or dropped by DPI).
            // Measure actual network RTT via TCP connection to the same Anycast POP / node.
            let is_pure_udp_vpn_port = port == 500 || port == 4500 || port == 1701 || port == 2408 || port == 0;
            let tcp_port = if is_pure_udp_vpn_port { 443 } else { port };
            let (tcp_alive, tcp_rtt, _) = ping_tcp_host(&target_ip.to_string(), tcp_port, timeout_ms).await;
            if tcp_alive && tcp_rtt > 0 {
                WarpEndpointProbeResult {
                    endpoint: endpoint.to_string(),
                    ip: target_ip.to_string(),
                    port,
                    rtt_ms: tcp_rtt,
                    is_alive: true,
                    stage: "TCP_SYN_RESPONSIVE".to_string(),
                }
            } else {
                let icmp_target = if !ip_str.is_empty() { &ip_str } else { &target_ip.to_string() };
                if let Some(rtt) = probe_system_icmp_ping(icmp_target, timeout_ms).await {
                    WarpEndpointProbeResult {
                        endpoint: endpoint.to_string(),
                        ip: target_ip.to_string(),
                        port,
                        rtt_ms: rtt,
                        is_alive: true,
                        stage: "ICMP_ECHO_RESPONSIVE".to_string(),
                    }
                } else {
                    WarpEndpointProbeResult {
                        endpoint: endpoint.to_string(),
                        ip: target_ip.to_string(),
                        port,
                        rtt_ms: -1,
                        is_alive: false,
                        stage: "TIMEOUT".to_string(),
                    }
                }
            }
        }
    }
}

pub async fn probe_warp_endpoints(
    endpoints_csv: &str,
    protocol: i32,
    timeout_ms: u64,
) -> Vec<WarpEndpointProbeResult> {
    let endpoints: Vec<String> = endpoints_csv
        .split(',')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();

    let sem = Arc::new(Semaphore::new(20));
    let mut handles = Vec::new();

    for ep in endpoints {
        let sem_clone = sem.clone();
        handles.push(tokio::spawn(async move {
            let _permit = sem_clone.acquire().await.ok();
            probe_single_warp_endpoint(&ep, protocol, timeout_ms).await
        }));
    }

    let mut results = Vec::new();
    for handle in handles {
        if let Ok(res) = handle.await {
            results.push(res);
        }
    }

    results.sort_by(|a, b| {
        match (a.is_alive, b.is_alive) {
            (true, false) => std::cmp::Ordering::Less,
            (false, true) => std::cmp::Ordering::Greater,
            (false, false) => std::cmp::Ordering::Equal,
            (true, true) => {
                let a_score = match a.stage.as_str() {
                    "PROTOCOL_RECOGNIZED" => 3,
                    "UDP_RESPONSIVE" => 2,
                    _ => 1,
                };
                let b_score = match b.stage.as_str() {
                    "PROTOCOL_RECOGNIZED" => 3,
                    "UDP_RESPONSIVE" => 2,
                    _ => 1,
                };
                match b_score.cmp(&a_score) {
                    std::cmp::Ordering::Equal => a.rtt_ms.cmp(&b.rtt_ms),
                    other => other,
                }
            }
        }
    });

    results
}

fn is_cf_or_flowseal_host(host: &str) -> bool {
    if crate::config::is_cloudflare_domain(host) {
        return true;
    }
    let norm = crate::balancer::normalize_domain(host);
    crate::cfproxy::FLOWSEAL_OPEN_BASES
        .iter()
        .any(|&b| norm == format!("{}.co.uk", b))
}

pub async fn ping_tcp_host(host: &str, port: u16, timeout_ms: u64) -> (bool, i64, String) {
    let ips = if is_cf_or_flowseal_host(host) {
        crate::cfproxy::resolve_dual_stack_ips(host).await
    } else {
        crate::dns::resolve_dual_stack(host).await.unwrap_or_default()
    };
    if ips.is_empty() {
        return (false, -1, "Host resolution failed".to_string());
    }

    let mut last_err = "No address tried".to_string();
    for ip in ips {
        let domain = if ip.is_ipv6() {
            socket2::Domain::IPV6
        } else {
            socket2::Domain::IPV4
        };

        let sock = match socket2::Socket::new(domain, socket2::Type::STREAM, Some(socket2::Protocol::TCP)) {
            Ok(s) => s,
            Err(e) => {
                last_err = e.to_string();
                continue;
            }
        };

        let _ = sock.set_nonblocking(true);

        #[cfg(unix)]
        {
            use std::os::unix::io::AsRawFd;
            let raw_fd = sock.as_raw_fd();
            let _ = crate::protect_socket_fd(raw_fd);
        }

        let t0 = Instant::now();
        let target_addr = SocketAddr::new(ip, port);
        let _ = sock.connect(&target_addr.into());

        let std_stream: std::net::TcpStream = sock.into();
        let is_connected = match tokio::net::TcpStream::from_std(std_stream) {
            Ok(stream) => {
                match tokio::time::timeout(Duration::from_millis(timeout_ms), stream.writable()).await {
                    Ok(Ok(())) => match stream.take_error() {
                        Ok(None) => true,
                        Ok(Some(err)) => {
                            last_err = err.to_string();
                            false
                        }
                        Err(err) => {
                            last_err = err.to_string();
                            false
                        }
                    },
                    _ => {
                        last_err = "Connection timeout".to_string();
                        false
                    }
                }
            }
            Err(err) => {
                last_err = err.to_string();
                false
            }
        };

        if is_connected {
            let rtt = t0.elapsed().as_millis().max(1) as i64;
            return (true, rtt, ip.to_string());
        }
    }

    (false, -1, last_err)
}

static NO_TLS_HTTP_CLIENT: once_cell::sync::Lazy<reqwest::Client> = once_cell::sync::Lazy::new(|| {
    let _ = rustls::crypto::ring::default_provider().install_default();
    reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_millis(2500))
        .build()
        .unwrap_or_default()
});

static TLS_HTTP_CLIENT: once_cell::sync::Lazy<reqwest::Client> = once_cell::sync::Lazy::new(|| {
    let _ = rustls::crypto::ring::default_provider().install_default();
    reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_millis(2500))
        .build()
        .unwrap_or_default()
});

pub async fn ping_worker_endpoint(domain: &str, timeout_ms: u64) -> (String, Option<i64>) {
    let clean_domain = domain
        .trim()
        .trim_start_matches("https://")
        .trim_start_matches("http://")
        .trim_start_matches("wss://")
        .trim_end_matches('/');
    if clean_domain.is_empty() {
        return ("ERROR_UNREACHABLE".to_string(), None);
    }

    let use_tls = crate::ws::is_tls_enabled();
    let url = if use_tls {
        format!("https://{}/", clean_domain)
    } else {
        format!("http://{}/", clean_domain)
    };
    let t0 = Instant::now();
    let client = if use_tls {
        &*TLS_HTTP_CLIENT
    } else {
        &*NO_TLS_HTTP_CLIENT
    };
    match tokio::time::timeout(Duration::from_millis(timeout_ms), client.get(&url).send()).await {
        Ok(Ok(resp)) => {
            let status = resp.status().as_u16();
            let rtt = t0.elapsed().as_millis().max(1) as i64;
            if status == 200 || status == 101 {
                ("ONLINE".to_string(), Some(rtt))
            } else if status == 429 || status == 1015 || status == 1027 {
                ("RATE_LIMITED_429".to_string(), Some(rtt))
            } else {
                ("ERROR_UNREACHABLE".to_string(), None)
            }
        }
        _ => ("ERROR_UNREACHABLE".to_string(), None),
    }
}

#[derive(Serialize, Clone, Debug)]
pub struct TransportProbeResult {
    pub alive: bool,
    pub rtt_ms: i64,
    pub domain: String,
    pub ip: String,
    pub colo: String,
    pub stage: String,
    pub failure: String,
}

fn classify_ws_probe_failure(err: &crate::ws::WsError) -> &'static str {
    match err {
        crate::ws::WsError::Timeout => "CONNECT_TIMEOUT",
        crate::ws::WsError::Handshake(h) => {
            if h.status_code == 429 || h.status_code == 1015 || h.status_code == 1027 {
                "RATE_LIMITED_429"
            } else {
                "HOST_UNREACHABLE"
            }
        }
        crate::ws::WsError::Io(e) => match e.kind() {
            std::io::ErrorKind::TimedOut | std::io::ErrorKind::WouldBlock => "CONNECT_TIMEOUT",
            std::io::ErrorKind::ConnectionReset
            | std::io::ErrorKind::ConnectionAborted
            | std::io::ErrorKind::BrokenPipe => "DPI_BLOCKED",
            _ => "HOST_UNREACHABLE",
        },
        crate::ws::WsError::Canceled => "CONNECT_TIMEOUT",
        crate::ws::WsError::Other(msg) => {
            let low = msg.to_ascii_lowercase();
            if crate::ws::is_tls_enabled()
                && (low.contains("tls") || low.contains("cert") || low.contains("handshake"))
            {
                "TLS_HANDSHAKE_FAILED"
            } else if low.contains("dns") || low.contains("resolve") {
                "HOST_RESOLUTION_FAILED"
            } else if low.contains("reset") || low.contains("dpi") {
                "DPI_BLOCKED"
            } else {
                "HOST_UNREACHABLE"
            }
        }
    }
}

/// Unified transport health probe aligned with the active protocol, TLS toggle, Flowseal balancer,
/// Worker HTTP check, and WARP AWG UDP probes.
/// Modes:
///   0 = MTProto Flowseal Anycast
///   1 = SOCKS5 Flowseal Anycast
///   2 = SOCKS5 Worker
///   3 = MTProto Worker
///   4 = VPN WARP AWG
pub async fn probe_transport_health(
    mode: i32,
    domain_override: &str,
    timeout_ms: u64,
) -> TransportProbeResult {
    let to_ms = timeout_ms.clamp(400, 8000);
    let tls_on = crate::ws::is_tls_enabled();
    let default_stage = if tls_on { "WSS_TLS" } else { "WS_PLAIN_80" };

    // VPN WARP AWG probe
    if mode == 4 {
        let ep = domain_override.trim();
        let target_ep = if ep.is_empty() {
            "162.159.192.1:2408".to_string()
        } else if ep.contains(':') {
            ep.to_string()
        } else {
            format!("{}:2408", ep)
        };
        let res = probe_single_warp_endpoint(&target_ep, 0, to_ms).await;
        return TransportProbeResult {
            alive: res.is_alive,
            rtt_ms: res.rtt_ms,
            domain: res.endpoint,
            ip: res.ip,
            colo: String::new(),
            stage: if res.is_alive { "AWG_UDP".to_string() } else { res.stage },
            failure: if res.is_alive {
                "NONE".to_string()
            } else {
                "WARP_HANDSHAKE_TIMEOUT".to_string()
            },
        };
    }

    // Worker probe (SOCKS5 Worker / MTProto Worker)
    if mode == 2 || mode == 3 {
        let user_domain = {
            let trimmed = domain_override.trim();
            if !trimmed.is_empty() {
                crate::cfproxy::normalize_cf_domain(trimmed)
            } else {
                let d = crate::config::CFPROXY.read().user_domain.clone();
                crate::cfproxy::normalize_cf_domain(&d)
            }
        };
        if user_domain.is_empty() {
            return TransportProbeResult {
                alive: false,
                rtt_ms: -1,
                domain: String::new(),
                ip: String::new(),
                colo: String::new(),
                stage: default_stage.to_string(),
                failure: "HOST_RESOLUTION_FAILED".to_string(),
            };
        }
        let scope = if mode == 2 {
            crate::cfproxy::ProtoScope::Socks5
        } else {
            crate::cfproxy::ProtoScope::Mtproto
        };
        if crate::cfproxy::cfproxy_429_cooldown_remaining_scoped(&user_domain, scope) > Duration::ZERO {
            return TransportProbeResult {
                alive: false,
                rtt_ms: -1,
                domain: user_domain,
                ip: String::new(),
                colo: String::new(),
                stage: default_stage.to_string(),
                failure: "RATE_LIMITED_429".to_string(),
            };
        }
        let (status, rtt_opt) = ping_worker_endpoint(&user_domain, to_ms).await;
        if status == "ONLINE" {
            return TransportProbeResult {
                alive: true,
                rtt_ms: rtt_opt.unwrap_or(1),
                domain: user_domain,
                ip: String::new(),
                colo: String::new(),
                stage: if tls_on { "WORKER_WSS" } else { "WORKER_WS_80" }.to_string(),
                failure: "NONE".to_string(),
            };
        }
        if status == "RATE_LIMITED_429" {
            return TransportProbeResult {
                alive: false,
                rtt_ms: rtt_opt.unwrap_or(-1),
                domain: user_domain,
                ip: String::new(),
                colo: String::new(),
                stage: default_stage.to_string(),
                failure: "RATE_LIMITED_429".to_string(),
            };
        }

        // Fallback via Anycast-backed TCP+TLS/HTTP check if system DNS for ping_worker_endpoint failed
        let ips = crate::cfproxy::resolve_dual_stack_ips(&user_domain).await;
        if let Some(ip) = ips.first() {
            let port = crate::ws::default_ws_port();
            let addrs = vec![SocketAddr::new(*ip, port)];
            let path = if mode == 2 { "/tcp-v2" } else { "/apiws" };
            let t0 = Instant::now();
            match crate::ws::ws_connect_happy_eyeballs(&user_domain, path, &addrs, Duration::from_millis(to_ms)).await {
                Ok((ws, winner)) => {
                    let rtt = t0.elapsed().as_millis().max(1) as i64;
                    let colo = ws.colo().to_string();
                    tokio::spawn(async move {
                        let _ = ws.close().await;
                    });
                    return TransportProbeResult {
                        alive: true,
                        rtt_ms: rtt,
                        domain: user_domain,
                        ip: winner.ip().to_string(),
                        colo,
                        stage: if tls_on { "WORKER_WSS" } else { "WORKER_WS_80" }.to_string(),
                        failure: "NONE".to_string(),
                    };
                }
                Err(err) => {
                    return TransportProbeResult {
                        alive: false,
                        rtt_ms: -1,
                        domain: user_domain,
                        ip: ip.to_string(),
                        colo: String::new(),
                        stage: default_stage.to_string(),
                        failure: classify_ws_probe_failure(&err).to_string(),
                    };
                }
            }
        }

        return TransportProbeResult {
            alive: false,
            rtt_ms: -1,
            domain: user_domain,
            ip: String::new(),
            colo: String::new(),
            stage: default_stage.to_string(),
            failure: "CONNECT_TIMEOUT".to_string(),
        };
    }

    // Flowseal Anycast CDN (mode 0 = MTProto, mode 1 = SOCKS5):
    // Try active balancer domain + top fallback Flowseal bases over actual WS/WSS handshake.
    let mut candidates: Vec<String> = Vec::new();
    let trimmed_ov = crate::balancer::normalize_domain(domain_override);
    if !trimmed_ov.is_empty() {
        candidates.push(trimmed_ov);
    }
    if let Some(fastest) = crate::balancer::BALANCER.read().get_fastest_domain_for_dc(2, false) {
        if !candidates.contains(&fastest) {
            candidates.push(fastest);
        }
    }
    {
        let cfg_domains = crate::config::CFPROXY.read().domains.clone();
        for raw in cfg_domains.into_iter().take(6) {
            let dec = crate::cfproxy::decode_cf_domain(&raw);
            let norm = crate::balancer::normalize_domain(&dec);
            if !norm.is_empty() && !candidates.contains(&norm) {
                candidates.push(norm);
            }
            if candidates.len() >= 3 {
                break;
            }
        }
    }
    for &open_base in crate::cfproxy::FLOWSEAL_OPEN_BASES.iter().take(3) {
        let dom = format!("{}.co.uk", open_base);
        if !candidates.contains(&dom) {
            candidates.push(dom);
        }
        if candidates.len() >= 3 {
            break;
        }
    }

    let port = crate::ws::default_ws_port();
    let per_domain_timeout = Duration::from_millis((to_ms / 2).clamp(600, 1800));
    let mut last_failure = "HOST_UNREACHABLE";
    let mut last_domain = candidates.first().cloned().unwrap_or_default();

    for base in candidates.into_iter().take(3) {
        let target_host = format!("kws2.{}", base);
        last_domain = base.clone();
        let candidate_ips = crate::cfproxy::resolve_dual_stack_ips(&target_host).await;
        if candidate_ips.is_empty() {
            last_failure = "HOST_RESOLUTION_FAILED";
            continue;
        }
        let addrs: Vec<SocketAddr> = candidate_ips
            .into_iter()
            .take(4)
            .map(|ip| SocketAddr::new(ip, port))
            .collect();
        let t0 = Instant::now();
        match crate::ws::ws_connect_happy_eyeballs(&target_host, "/apiws", &addrs, per_domain_timeout).await {
            Ok((ws, winner)) => {
                let rtt = t0.elapsed().as_millis().max(1) as i64;
                let colo = ws.colo().to_string();
                tokio::spawn(async move {
                    let _ = ws.close().await;
                });
                return TransportProbeResult {
                    alive: true,
                    rtt_ms: rtt,
                    domain: base,
                    ip: winner.ip().to_string(),
                    colo,
                    stage: if tls_on { "FLOWSEAL_WSS" } else { "FLOWSEAL_WS_80" }.to_string(),
                    failure: "NONE".to_string(),
                };
            }
            Err(err) => {
                last_failure = classify_ws_probe_failure(&err);
            }
        }
    }

    TransportProbeResult {
        alive: false,
        rtt_ms: -1,
        domain: last_domain,
        ip: String::new(),
        colo: String::new(),
        stage: default_stage.to_string(),
        failure: last_failure.to_string(),
    }
}
