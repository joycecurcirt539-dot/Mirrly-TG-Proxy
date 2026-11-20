// mirrlyengine/src/prober.rs
//
// Mirrly TG Proxy - High-Performance Native Prober Engine
// Implements TCP probing, Worker diagnostics, and transport health checks in Rust.
// Copyright (C) 2026 R1Xern (Mirrly Dev)

use serde::Serialize;
use std::net::SocketAddr;
use std::time::{Duration, Instant};

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
/// and Worker HTTP check.
/// Modes:
///   0 = MTProto Flowseal Anycast
///   1 = SOCKS5 Flowseal Anycast
///   2 = SOCKS5 Worker
///   3 = MTProto Worker
pub async fn probe_transport_health(
    mode: i32,
    domain_override: &str,
    timeout_ms: u64,
) -> TransportProbeResult {
    let to_ms = timeout_ms.clamp(400, 8000);
    let tls_on = crate::ws::is_tls_enabled();
    let default_stage = if tls_on { "WSS_TLS" } else { "WS_PLAIN_80" };

    if !(0..=3).contains(&mode) {
        return TransportProbeResult {
            alive: false,
            rtt_ms: -1,
            domain: domain_override.trim().to_string(),
            ip: String::new(),
            colo: String::new(),
            stage: default_stage.to_string(),
            failure: "UNSUPPORTED_MODE".to_string(),
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
