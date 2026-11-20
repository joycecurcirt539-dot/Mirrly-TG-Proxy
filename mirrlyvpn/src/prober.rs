// mirrlyvpn/src/prober.rs
//
// Mirrly TG Proxy - Native WARP & VPN Endpoint Prober Engine
// Implements fast, protected UDP WireGuard/AWG scanning, TCP fallback probing, and ICMP fallback.
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
    // 1. time=112 ms or time=112.2 ms
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

    #[cfg(unix)]
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

/// Determines the TCP fallback port for WARP endpoint probing when UDP WireGuard times out.
/// When `tls_enabled == false`, TCP fallback is completely disabled (strictly UDP WireGuard + ICMP only).
#[inline]
pub fn warp_tcp_fallback_port(port: u16, tls_enabled: bool) -> Option<u16> {
    if !tls_enabled {
        return None;
    }
    let is_pure_udp_vpn_port = port == 500 || port == 4500 || port == 1701 || port == 2408 || port == 0;
    Some(if is_pure_udp_vpn_port { 443 } else { port })
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

    if tokio_sock.connect(socket_addr).await.is_err() {
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
            // UDP did not respond.
            // When VPN_TLS_ENABLED is true, measure RTT via TCP fallback (port 443 for pure UDP ports).
            // When VPN_TLS_ENABLED is false, TCP fallback is disabled: probe strictly via UDP WireGuard + ICMP only.
            if let Some(tcp_port) = warp_tcp_fallback_port(port, crate::config::is_vpn_tls_enabled()) {
                let (tcp_alive, tcp_rtt, _) =
                    ping_tcp_host(&target_ip.to_string(), tcp_port, timeout_ms).await;
                if tcp_alive && tcp_rtt > 0 {
                    return WarpEndpointProbeResult {
                        endpoint: endpoint.to_string(),
                        ip: target_ip.to_string(),
                        port,
                        rtt_ms: tcp_rtt,
                        is_alive: true,
                        stage: "TCP_SYN_RESPONSIVE".to_string(),
                    };
                }
            }

            let icmp_target = if !ip_str.is_empty() {
                ip_str.as_str()
            } else {
                ""
            };
            let fallback_target = if !icmp_target.is_empty() {
                icmp_target.to_string()
            } else {
                target_ip.to_string()
            };
            if let Some(rtt) = probe_system_icmp_ping(&fallback_target, timeout_ms).await {
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

    results.sort_by(|a, b| match (a.is_alive, b.is_alive) {
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
    });

    results
}

pub async fn ping_tcp_host(host: &str, port: u16, timeout_ms: u64) -> (bool, i64, String) {
    let clean = host.trim().trim_matches(['[', ']']);
    let ips = if let Ok(ip) = clean.parse::<IpAddr>() {
        vec![ip]
    } else {
        crate::dns::resolve_dual_stack(clean).await.unwrap_or_default()
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

fn normalize_warp_probe_endpoint(domain_override: &str) -> String {
    let ep = domain_override.trim();
    if ep.is_empty() {
        return "162.159.192.1:2408".to_string();
    }
    if ep.starts_with('[') {
        if ep.contains("]:") {
            return ep.to_string();
        }
        return format!("{}:2408", ep);
    }
    if let Ok(ip) = ep.parse::<IpAddr>() {
        return match ip {
            IpAddr::V4(v4) => format!("{}:2408", v4),
            IpAddr::V6(v6) => format!("[{}]:2408", v6),
        };
    }
    if ep.contains(':') {
        ep.to_string()
    } else {
        format!("{}:2408", ep)
    }
}

pub async fn probe_vpn_transport_health(
    domain_override: &str,
    timeout_ms: u64,
) -> TransportProbeResult {
    let to_ms = timeout_ms.clamp(400, 8000);
    let target_ep = normalize_warp_probe_endpoint(domain_override);
    let res = probe_single_warp_endpoint(&target_ep, 0, to_ms).await;
    TransportProbeResult {
        alive: res.is_alive,
        rtt_ms: res.rtt_ms,
        domain: res.endpoint,
        ip: res.ip,
        colo: String::new(),
        stage: if res.is_alive {
            "AWG_UDP".to_string()
        } else {
            res.stage
        },
        failure: if res.is_alive {
            "NONE".to_string()
        } else {
            "WARP_HANDSHAKE_TIMEOUT".to_string()
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_warp_tcp_fallback_port_respects_tls_toggle() {
        assert_eq!(warp_tcp_fallback_port(2408, true), Some(443));
        assert_eq!(warp_tcp_fallback_port(500, true), Some(443));
        assert_eq!(warp_tcp_fallback_port(4500, true), Some(443));
        assert_eq!(warp_tcp_fallback_port(1701, true), Some(443));
        assert_eq!(warp_tcp_fallback_port(0, true), Some(443));
        assert_eq!(warp_tcp_fallback_port(8443, true), Some(8443));

        assert_eq!(warp_tcp_fallback_port(2408, false), None);
        assert_eq!(warp_tcp_fallback_port(500, false), None);
        assert_eq!(warp_tcp_fallback_port(4500, false), None);
        assert_eq!(warp_tcp_fallback_port(8443, false), None);
        assert_eq!(warp_tcp_fallback_port(443, false), None);
    }

    #[test]
    fn test_normalize_warp_probe_endpoint() {
        assert_eq!(normalize_warp_probe_endpoint(""), "162.159.192.1:2408");
        assert_eq!(normalize_warp_probe_endpoint("162.159.198.1"), "162.159.198.1:2408");
        assert_eq!(normalize_warp_probe_endpoint("162.159.198.1:8095"), "162.159.198.1:8095");
        assert_eq!(normalize_warp_probe_endpoint("2606:4700:d0::a29f:c001"), "[2606:4700:d0::a29f:c001]:2408");
        assert_eq!(normalize_warp_probe_endpoint("[2606:4700:d0::a29f:c001]:4500"), "[2606:4700:d0::a29f:c001]:4500");
    }
}
