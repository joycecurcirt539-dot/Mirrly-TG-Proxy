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

pub fn create_quic_vn_probe() -> Vec<u8> {
    let mut pkt = Vec::with_capacity(64);
    pkt.push(0x80 | 0x40); // Long header, Fixed bit
    pkt.extend_from_slice(&[0x1a, 0x2a, 0x3a, 0x4a]); // Reserved unassigned version (forcing VN)
    pkt.push(0x08); // DCIL = 8
    pkt.extend_from_slice(&[0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08]); // DCID
    pkt.push(0x08); // SCIL = 8
    pkt.extend_from_slice(&[0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18]); // SCID
    while pkt.len() < 64 {
        pkt.push(0x00);
    }
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
            return WarpEndpointProbeResult {
                endpoint: endpoint.to_string(),
                ip: ip_str,
                port,
                rtt_ms: -1,
                is_alive: false,
                stage: "INVALID_IP".to_string(),
            };
        }
    };

    let socket_addr = SocketAddr::new(target_ip, port);
    let payload = if protocol == 1 {
        create_wireguard_initiation_probe(CLOUDFLARE_WARP_PEER_PUBKEY_B64)
    } else {
        create_quic_vn_probe()
    };

    let domain = if target_ip.is_ipv6() {
        socket2::Domain::IPV6
    } else {
        socket2::Domain::IPV4
    };

    let sock_res = socket2::Socket::new(domain, socket2::Type::DGRAM, Some(socket2::Protocol::UDP));
    let sock = match sock_res {
        Ok(s) => s,
        Err(_) => {
            return WarpEndpointProbeResult {
                endpoint: endpoint.to_string(),
                ip: ip_str,
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
            return WarpEndpointProbeResult {
                endpoint: endpoint.to_string(),
                ip: ip_str,
                port,
                rtt_ms: -1,
                is_alive: false,
                stage: "TOKIO_CONVERT_FAILED".to_string(),
            };
        }
    };

    if let Err(_) = tokio_sock.connect(socket_addr).await {
        return WarpEndpointProbeResult {
            endpoint: endpoint.to_string(),
            ip: ip_str,
            port,
            rtt_ms: -1,
            is_alive: false,
            stage: "CONNECT_FAILED".to_string(),
        };
    }

    let t0 = Instant::now();
    if let Err(_) = tokio_sock.send(&payload).await {
        return WarpEndpointProbeResult {
            endpoint: endpoint.to_string(),
            ip: ip_str,
            port,
            rtt_ms: -1,
            is_alive: false,
            stage: "SEND_FAILED".to_string(),
        };
    }

    let mut buf = [0u8; 1500];
    let recv_res = tokio::time::timeout(
        Duration::from_millis(timeout_ms),
        tokio_sock.recv(&mut buf),
    )
    .await;

    match recv_res {
        Ok(Ok(n)) => {
            let rtt = t0.elapsed().as_millis().max(1) as i64;
            let stage = if protocol == 1 {
                // WireGuard: Response type 2 (92 bytes) or Cookie Reply type 3 (64 bytes)
                if n >= 60 && (buf[0] == 2 || buf[0] == 3) {
                    "PROTOCOL_RECOGNIZED"
                } else {
                    "UDP_RESPONSIVE"
                }
            } else {
                // QUIC: Version Negotiation response has Long Header
                if n >= 16 {
                    "PROTOCOL_RECOGNIZED"
                } else {
                    "UDP_RESPONSIVE"
                }
            };
            WarpEndpointProbeResult {
                endpoint: endpoint.to_string(),
                ip: ip_str,
                port,
                rtt_ms: rtt,
                is_alive: true,
                stage: stage.to_string(),
            }
        }
        _ => WarpEndpointProbeResult {
            endpoint: endpoint.to_string(),
            ip: ip_str,
            port,
            rtt_ms: -1,
            is_alive: false,
            stage: "TIMEOUT".to_string(),
        },
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
                let a_score = if a.stage == "PROTOCOL_RECOGNIZED" { 2 } else { 1 };
                let b_score = if b.stage == "PROTOCOL_RECOGNIZED" { 2 } else { 1 };
                match b_score.cmp(&a_score) {
                    std::cmp::Ordering::Equal => a.rtt_ms.cmp(&b.rtt_ms),
                    other => other,
                }
            }
        }
    });

    results
}

pub async fn ping_tcp_host(host: &str, port: u16, timeout_ms: u64) -> (bool, i64, String) {
    let ips = match crate::dns::resolve_dual_stack(host, crate::dns::DnsScope::Bootstrap).await {
        Ok(ips) if !ips.is_empty() => ips,
        _ => return (false, -1, "DNS resolution failed".to_string()),
    };

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

    let url = format!("https://{}/", clean_domain);
    let t0 = Instant::now();
    let client = &*crate::dns::DOH_HTTP_CLIENT;
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
