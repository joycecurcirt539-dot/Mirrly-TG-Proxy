// mirrlyengine/src/timeline.rs
//
// Mirrly TG Proxy - Stage-Coded Connection Timeline & Diagnostic Telemetry Contract (MOB-001)
// Copyright (C) 2026 R1Xern (Mirrly Dev)
//
// Implements stage-coded observation of connection attempts:
// 1. Stage checkpoints: DNS -> TCP -> TLS -> WSS -> Relay ACK -> First TX -> Useful RX -> Stable -> Closed.
// 2. Clear distinction of stage-specific timeouts (DNS vs TCP vs TLS vs WSS vs Relay vs Useful RX).
// 3. Strict privacy & zero-leakage guarantee:
//    - NO SOCKS authentication credentials (username/password)
//    - NO MTProto proxy secrets or obfuscation keys
//    - NO Telegram application payload bytes
//    - Hostnames are sanitized (stripping query parameters, tokens, credentials)
//    - IP addresses are bucketed (/24 for IPv4, /48 for IPv6) for failure correlation
// 4. Primary SLI: time_to_useful_rx (elapsed time to first valid response byte from Telegram DC),
//    replacing superficial ping.
// 5. Detection of external ISP hard cutoff (~16 KB plateau on Cloudflare endpoints).

use crate::config::MOBILE_NETWORK;
use once_cell::sync::Lazy;
use parking_lot::{Mutex, RwLock};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, VecDeque};
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

pub static CURRENT_NETWORK_GENERATION: AtomicU64 = AtomicU64::new(1);

pub fn set_network_generation(gen: u64) {
    if gen > 0 {
        CURRENT_NETWORK_GENERATION.store(gen, Ordering::SeqCst);
    }
}

pub fn get_network_generation() -> u64 {
    CURRENT_NETWORK_GENERATION.load(Ordering::SeqCst)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ConnectionStage {
    Dns,
    Tcp,
    Tls,
    Wss,
    RelayAck,
    FirstTx,
    UsefulRx,
    Stable,
    Closed,
    Failed,
}

impl std::fmt::Display for ConnectionStage {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ConnectionStage::Dns => write!(f, "dns"),
            ConnectionStage::Tcp => write!(f, "tcp"),
            ConnectionStage::Tls => write!(f, "tls"),
            ConnectionStage::Wss => write!(f, "wss"),
            ConnectionStage::RelayAck => write!(f, "relay_ack"),
            ConnectionStage::FirstTx => write!(f, "first_tx"),
            ConnectionStage::UsefulRx => write!(f, "useful_rx"),
            ConnectionStage::Stable => write!(f, "stable"),
            ConnectionStage::Closed => write!(f, "closed"),
            ConnectionStage::Failed => write!(f, "failed"),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum StageTimeout {
    None,
    Dns,
    TcpConnect,
    TlsHandshake,
    WssHandshake,
    RelayAck,
    FirstRx,
    IdleRead,
    Write,
}

impl std::fmt::Display for StageTimeout {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            StageTimeout::None => write!(f, "none"),
            StageTimeout::Dns => write!(f, "dns_timeout"),
            StageTimeout::TcpConnect => write!(f, "tcp_connect_timeout"),
            StageTimeout::TlsHandshake => write!(f, "tls_handshake_timeout"),
            StageTimeout::WssHandshake => write!(f, "wss_handshake_timeout"),
            StageTimeout::RelayAck => write!(f, "relay_ack_timeout"),
            StageTimeout::FirstRx => write!(f, "first_rx_timeout"),
            StageTimeout::IdleRead => write!(f, "idle_read_timeout"),
            StageTimeout::Write => write!(f, "write_timeout"),
        }
    }
}

/// Sanitizes a hostname or URI to remove any credentials, secrets, tokens, or query strings.
pub fn sanitize_hostname(raw: &str) -> String {
    let mut s = raw.trim();
    if s.is_empty() {
        return "unknown".to_string();
    }
    // Strip scheme if present
    if let Some(pos) = s.find("://") {
        s = &s[pos + 3..];
    }
    // Strip userinfo (user:pass@)
    if let Some(pos) = s.rfind('@') {
        s = &s[pos + 1..];
    }
    // Strip path or query
    if let Some(pos) = s.find(|c| c == '/' || c == '?' || c == '#') {
        s = &s[..pos];
    }
    // Strip port if present
    if let Some(colon) = s.rfind(':') {
        if !s.starts_with('[') || s.ends_with(']') {
            let candidate_host = &s[..colon];
            if !candidate_host.contains(':') || (s.starts_with('[') && s[..colon].ends_with(']')) {
                s = candidate_host;
            }
        }
    }
    s = s.trim_matches(|c| c == '[' || c == ']');
    if s.is_empty() {
        "unknown".to_string()
    } else {
        s.to_lowercase()
    }
}

/// Buckets an IP address to preserve privacy while permitting diagnostic grouping.
/// IPv4 -> /24 (e.g. 104.21.45.0/24)
/// IPv6 -> /48 (e.g. 2606:4700:3033::/48)
pub fn bucket_ip(ip: IpAddr) -> String {
    match ip {
        IpAddr::V4(v4) => {
            let oct = v4.octets();
            format!("{}.{}.{}.0/24", oct[0], oct[1], oct[2])
        }
        IpAddr::V6(v6) => {
            let seg = v6.segments();
            format!("{:x}:{:x}:{:x}::/48", seg[0], seg[1], seg[2])
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConnectionAttemptTimeline {
    pub attempt_id: u64,
    pub network_generation: u64,
    pub is_mobile: bool,
    pub transport: String,
    pub hostname: String,
    pub target_port: u16,
    pub family: String,
    pub ip_bucket: String,
    pub dns_source: String,
    pub dns_ttl: u32,
    pub dns_duration_ms: u32,
    pub tcp_duration_ms: u32,
    pub tcp_status: String,
    pub tls_duration_ms: u32,
    pub tls_status: String,
    pub http_status: Option<u16>,
    pub wss_duration_ms: u32,
    pub wss_status: String,
    pub relay_ack_duration_ms: u32,
    pub relay_ack_status: String,
    pub first_tx_ms: Option<u32>,
    pub first_useful_rx_ms: Option<u32>,
    pub bytes_tx: u64,
    pub bytes_rx: u64,
    pub bytes_before_stall: u64,
    pub is_stall_cutoff: bool,
    pub close_code: Option<u16>,
    pub close_reason: String,
    pub current_stage: ConnectionStage,
    pub failure_stage: String,
    pub stage_timeout: String,
    pub failure_reason: String,
    pub start_timestamp_ms: u64,
    pub total_duration_ms: u64,
    pub completed: bool,
}

impl ConnectionAttemptTimeline {
    pub fn new(attempt_id: u64, transport: &str, raw_host: &str, target_port: u16) -> Self {
        let now_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);

        Self {
            attempt_id,
            network_generation: get_network_generation(),
            is_mobile: MOBILE_NETWORK.load(Ordering::Relaxed),
            transport: transport.to_string(),
            hostname: sanitize_hostname(raw_host),
            target_port,
            family: "unknown".to_string(),
            ip_bucket: "none".to_string(),
            dns_source: "none".to_string(),
            dns_ttl: 0,
            dns_duration_ms: 0,
            tcp_duration_ms: 0,
            tcp_status: "pending".to_string(),
            tls_duration_ms: 0,
            tls_status: "pending".to_string(),
            http_status: None,
            wss_duration_ms: 0,
            wss_status: "pending".to_string(),
            relay_ack_duration_ms: 0,
            relay_ack_status: "pending".to_string(),
            first_tx_ms: None,
            first_useful_rx_ms: None,
            bytes_tx: 0,
            bytes_rx: 0,
            bytes_before_stall: 0,
            is_stall_cutoff: false,
            close_code: None,
            close_reason: String::new(),
            current_stage: ConnectionStage::Dns,
            failure_stage: "none".to_string(),
            stage_timeout: StageTimeout::None.to_string(),
            failure_reason: String::new(),
            start_timestamp_ms: now_ms,
            total_duration_ms: 0,
            completed: false,
        }
    }
}

pub struct AttemptHandle {
    timeline: Arc<Mutex<ConnectionAttemptTimeline>>,
    start_instant: Instant,
}

impl AttemptHandle {
    pub fn id(&self) -> u64 {
        self.timeline.lock().attempt_id
    }

    pub fn snapshot(&self) -> ConnectionAttemptTimeline {
        self.timeline.lock().clone()
    }

    pub fn record_dns(&self, source: &str, ttl: u32, dur: Duration, ips: &[IpAddr]) {
        let mut g = self.timeline.lock();
        g.dns_source = source.to_string();
        g.dns_ttl = ttl;
        g.dns_duration_ms = dur.as_millis() as u32;
        if !ips.is_empty() {
            let first_ip = ips[0];
            g.family = match first_ip {
                IpAddr::V4(_) => "ipv4".to_string(),
                IpAddr::V6(_) => "ipv6".to_string(),
            };
            g.ip_bucket = bucket_ip(first_ip);
        }
        g.current_stage = ConnectionStage::Tcp;
    }

    pub fn record_tcp(&self, dur: Duration, status: &str, winner_addr: Option<SocketAddr>) {
        let mut g = self.timeline.lock();
        g.tcp_duration_ms = dur.as_millis() as u32;
        g.tcp_status = status.to_string();
        if let Some(sa) = winner_addr {
            g.family = match sa.ip() {
                IpAddr::V4(_) => "ipv4".to_string(),
                IpAddr::V6(_) => "ipv6".to_string(),
            };
            g.ip_bucket = bucket_ip(sa.ip());
            g.target_port = sa.port();
        }
        if status == "ok" {
            g.current_stage = ConnectionStage::Tls;
        }
    }

    pub fn record_tls(&self, dur: Duration, status: &str) {
        let mut g = self.timeline.lock();
        g.tls_duration_ms = dur.as_millis() as u32;
        g.tls_status = status.to_string();
        if status == "ok" {
            g.current_stage = ConnectionStage::Wss;
        }
    }

    pub fn record_wss(&self, http_status: u16, dur: Duration, status: &str) {
        let mut g = self.timeline.lock();
        g.http_status = Some(http_status);
        g.wss_duration_ms = dur.as_millis() as u32;
        g.wss_status = status.to_string();
        if status == "ok" && http_status == 101 {
            g.current_stage = ConnectionStage::RelayAck;
        }
    }

    pub fn record_relay_ack(&self, dur: Duration, status: &str) {
        let mut g = self.timeline.lock();
        g.relay_ack_duration_ms = dur.as_millis() as u32;
        g.relay_ack_status = status.to_string();
        if status == "ok" {
            g.current_stage = ConnectionStage::FirstTx;
        }
    }

    pub fn record_first_tx(&self) {
        let mut g = self.timeline.lock();
        if g.first_tx_ms.is_none() {
            let elapsed = self.start_instant.elapsed().as_millis() as u32;
            g.first_tx_ms = Some(elapsed);
            g.current_stage = ConnectionStage::UsefulRx;
        }
    }

    pub fn record_useful_rx(&self, initial_bytes: usize) {
        let mut g = self.timeline.lock();
        if g.first_useful_rx_ms.is_none() {
            let elapsed = self.start_instant.elapsed().as_millis() as u32;
            g.first_useful_rx_ms = Some(elapsed);
            g.current_stage = ConnectionStage::Stable;
            g.bytes_rx = g.bytes_rx.saturating_add(initial_bytes as u64);
            TIMELINE_TRACKER.notify_useful_rx(elapsed);
        }
    }

    pub fn record_data_transfer(&self, tx_delta: usize, rx_delta: usize) {
        let mut g = self.timeline.lock();
        g.bytes_tx = g.bytes_tx.saturating_add(tx_delta as u64);
        g.bytes_rx = g.bytes_rx.saturating_add(rx_delta as u64);
    }

    pub fn record_failure(&self, stage: ConnectionStage, timeout_type: StageTimeout, reason: &str) {
        let mut g = self.timeline.lock();
        if g.completed {
            return;
        }
        g.completed = true;
        g.current_stage = ConnectionStage::Failed;
        g.failure_stage = stage.to_string();
        g.stage_timeout = timeout_type.to_string();
        g.failure_reason = reason.to_string();
        g.total_duration_ms = self.start_instant.elapsed().as_millis() as u64;

        let total_bytes = g.bytes_tx + g.bytes_rx;
        g.bytes_before_stall = total_bytes;

        // Diagnostic evaluation for Russian ISP Cloudflare ~16 KB hard cutoff:
        // Connection establishes and transfers initial handshake/first chunk,
        // then stalls or drops near ~16 KB threshold (8 KB - 32 KB window).
        if total_bytes >= 8 * 1024 && total_bytes <= 32 * 1024 {
            g.is_stall_cutoff = true;
            TIMELINE_TRACKER.notify_cutoff();
        }

        TIMELINE_TRACKER.notify_failure(&g.failure_stage, &g.stage_timeout);
        TIMELINE_TRACKER.log_event(&g);
    }

    pub fn record_close(&self, code: Option<u16>, reason: &str) {
        let mut g = self.timeline.lock();
        if g.completed {
            return;
        }
        g.completed = true;
        g.current_stage = ConnectionStage::Closed;
        g.close_code = code;
        g.close_reason = reason.to_string();
        g.total_duration_ms = self.start_instant.elapsed().as_millis() as u64;
        g.bytes_before_stall = g.bytes_tx + g.bytes_rx;

        TIMELINE_TRACKER.log_event(&g);
    }
}

pub struct TimelineTracker {
    next_id: AtomicU64,
    history: RwLock<VecDeque<ConnectionAttemptTimeline>>,
    total_attempts: AtomicU64,
    successful_useful_rx_count: AtomicU64,
    cutoff_suspected_count: AtomicU64,
    useful_rx_samples: Mutex<VecDeque<u32>>,
    failures_by_stage: RwLock<HashMap<String, u64>>,
    failures_by_timeout: RwLock<HashMap<String, u64>>,
    last_useful_rx_timestamp_ms: AtomicU64,
}

impl TimelineTracker {
    pub fn new() -> Self {
        Self {
            next_id: AtomicU64::new(1),
            history: RwLock::new(VecDeque::with_capacity(64)),
            total_attempts: AtomicU64::new(0),
            successful_useful_rx_count: AtomicU64::new(0),
            cutoff_suspected_count: AtomicU64::new(0),
            useful_rx_samples: Mutex::new(VecDeque::with_capacity(128)),
            failures_by_stage: RwLock::new(HashMap::new()),
            failures_by_timeout: RwLock::new(HashMap::new()),
            last_useful_rx_timestamp_ms: AtomicU64::new(0),
        }
    }

    pub fn start_attempt(
        &self,
        transport: &str,
        raw_host: &str,
        target_port: u16,
    ) -> Arc<AttemptHandle> {
        let attempt_id = self.next_id.fetch_add(1, Ordering::SeqCst);
        self.total_attempts.fetch_add(1, Ordering::Relaxed);

        let timeline = ConnectionAttemptTimeline::new(attempt_id, transport, raw_host, target_port);
        let arc_timeline = Arc::new(Mutex::new(timeline));

        let handle = Arc::new(AttemptHandle {
            timeline: arc_timeline,
            start_instant: Instant::now(),
        });

        handle
    }

    fn log_event(&self, item: &ConnectionAttemptTimeline) {
        {
            let mut hist = self.history.write();
            if hist.len() >= 64 {
                hist.pop_front();
            }
            hist.push_back(item.clone());
        }

        crate::linfo!(
            "[TIMELINE] id={} gen={} net={} trans={} host={} bucket={} stage={:?} t_useful_rx={:?}ms tx={} rx={} cutoff={} err={}",
            item.attempt_id,
            item.network_generation,
            if item.is_mobile { "mobile" } else { "wifi" },
            item.transport,
            item.hostname,
            item.ip_bucket,
            item.current_stage,
            item.first_useful_rx_ms,
            item.bytes_tx,
            item.bytes_rx,
            item.is_stall_cutoff,
            if item.failure_reason.is_empty() { "none" } else { &item.failure_reason }
        );
    }

    fn notify_useful_rx(&self, elapsed_ms: u32) {
        self.successful_useful_rx_count.fetch_add(1, Ordering::Relaxed);
        let now_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        self.last_useful_rx_timestamp_ms.store(now_ms, Ordering::Relaxed);

        let mut samples = self.useful_rx_samples.lock();
        if samples.len() >= 128 {
            samples.pop_front();
        }
        samples.push_back(elapsed_ms);
    }

    fn notify_cutoff(&self) {
        self.cutoff_suspected_count.fetch_add(1, Ordering::Relaxed);
    }

    fn notify_failure(&self, stage: &str, timeout_type: &str) {
        {
            let mut f_stage = self.failures_by_stage.write();
            let counter = f_stage.entry(stage.to_string()).or_insert(0);
            *counter += 1;
        }
        if timeout_type != "none" {
            let mut f_timeout = self.failures_by_timeout.write();
            let counter = f_timeout.entry(timeout_type.to_string()).or_insert(0);
            *counter += 1;
        }
    }

    pub fn get_timeline_json(&self) -> String {
        let history = self.history.read().clone();
        let sli = self.get_sli_summary();

        #[derive(Serialize)]
        struct Output {
            sli: StageTimelineSliSummary,
            attempts: Vec<ConnectionAttemptTimeline>,
        }

        let out = Output {
            sli,
            attempts: history.into_iter().collect(),
        };

        serde_json::to_string(&out).unwrap_or_else(|_| "{}".to_string())
    }

    pub fn get_sli_summary(&self) -> StageTimelineSliSummary {
        let total = self.total_attempts.load(Ordering::Relaxed);
        let success = self.successful_useful_rx_count.load(Ordering::Relaxed);
        let cutoff = self.cutoff_suspected_count.load(Ordering::Relaxed);
        let last_useful = self.last_useful_rx_timestamp_ms.load(Ordering::Relaxed);
        let current_gen = get_network_generation();

        let samples: Vec<u32> = self.useful_rx_samples.lock().iter().copied().collect();
        let (p50, p95, min, max, avg) = if samples.is_empty() {
            (0, 0, 0, 0, 0)
        } else {
            let mut sorted = samples.clone();
            sorted.sort_unstable();
            let n = sorted.len();
            let p50_idx = n * 50 / 100;
            let p95_idx = (n * 95 / 100).min(n - 1);
            let sum: u64 = sorted.iter().map(|&v| v as u64).sum();
            (
                sorted[p50_idx],
                sorted[p95_idx],
                sorted[0],
                sorted[n - 1],
                (sum / n as u64) as u32,
            )
        };

        let f_stage = self.failures_by_stage.read().clone();
        let f_timeout = self.failures_by_timeout.read().clone();

        StageTimelineSliSummary {
            total_attempts: total,
            successful_useful_rx_count: success,
            time_to_useful_rx_p50_ms: p50,
            time_to_useful_rx_p95_ms: p95,
            time_to_useful_rx_min_ms: min,
            time_to_useful_rx_max_ms: max,
            time_to_useful_rx_avg_ms: avg,
            cutoff_suspected_count: cutoff,
            failures_by_stage: f_stage,
            failures_by_timeout: f_timeout,
            active_connections_count: crate::config::STATS.connections_active.load(Ordering::Relaxed) as u64,
            last_useful_rx_timestamp_ms: last_useful,
            current_network_generation: current_gen,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StageTimelineSliSummary {
    pub total_attempts: u64,
    pub successful_useful_rx_count: u64,
    pub time_to_useful_rx_p50_ms: u32,
    pub time_to_useful_rx_p95_ms: u32,
    pub time_to_useful_rx_min_ms: u32,
    pub time_to_useful_rx_max_ms: u32,
    pub time_to_useful_rx_avg_ms: u32,
    pub cutoff_suspected_count: u64,
    pub failures_by_stage: HashMap<String, u64>,
    pub failures_by_timeout: HashMap<String, u64>,
    pub active_connections_count: u64,
    pub last_useful_rx_timestamp_ms: u64,
    pub current_network_generation: u64,
}

pub static TIMELINE_TRACKER: Lazy<TimelineTracker> = Lazy::new(TimelineTracker::new);

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;

    #[test]
    fn test_sanitize_hostname_removes_auth_and_secrets() {
        let test_cases = &[
            ("admin:secret123@worker.domain.com", "worker.domain.com"),
            ("https://user:pass@example.com/api?secret=deadbeef#hash", "example.com"),
            ("[2606:4700:3033::6815:2d43]:443", "2606:4700:3033::6815:2d43"),
            ("kws2.cloudflare.com:8443", "kws2.cloudflare.com"),
        ];

        for (input, expected) in test_cases {
            assert_eq!(sanitize_hostname(input), *expected);
        }
    }

    #[test]
    fn test_bucket_ip_masks_last_bytes() {
        let ip4: IpAddr = "104.21.45.67".parse().unwrap();
        assert_eq!(bucket_ip(ip4), "104.21.45.0/24");

        let ip6: IpAddr = "2606:4700:3033::6815:2d43".parse().unwrap();
        assert_eq!(bucket_ip(ip6), "2606:4700:3033::/48");
    }

    #[test]
    fn test_stage_progression_and_useful_rx_sli() {
        let tracker = TimelineTracker::new();
        let handle = tracker.start_attempt("socks5_worker", "worker.domain.com", 443);

        handle.record_dns("doh", 300, Duration::from_millis(25), &[IpAddr::V4(Ipv4Addr::new(104, 21, 45, 1))]);
        handle.record_tcp(Duration::from_millis(40), "ok", Some("104.21.45.1:443".parse().unwrap()));
        handle.record_tls(Duration::from_millis(60), "ok");
        handle.record_wss(101, Duration::from_millis(30), "ok");
        handle.record_relay_ack(Duration::from_millis(15), "ok");
        handle.record_first_tx();
        handle.record_useful_rx(1024);

        let snap = handle.snapshot();
        assert_eq!(snap.current_stage, ConnectionStage::Stable);
        assert_eq!(snap.dns_source, "doh");
        assert_eq!(snap.ip_bucket, "104.21.45.0/24");
        assert!(snap.first_useful_rx_ms.is_some());
        assert!(snap.first_useful_rx_ms.unwrap() >= 0);

        let sli = tracker.get_sli_summary();
        assert_eq!(sli.total_attempts, 1);
        assert_eq!(sli.successful_useful_rx_count, 1);
    }

    #[test]
    fn test_stage_timeout_differentiation() {
        let tracker = TimelineTracker::new();

        // 1. TCP Timeout
        let h1 = tracker.start_attempt("socks5_worker", "worker1.com", 443);
        h1.record_dns("doh", 300, Duration::from_millis(20), &[]);
        h1.record_failure(ConnectionStage::Tcp, StageTimeout::TcpConnect, "SYN timeout after 2500ms");
        let s1 = h1.snapshot();
        assert_eq!(s1.failure_stage, "tcp");
        assert_eq!(s1.stage_timeout, "tcp_connect_timeout");

        // 2. TLS Timeout
        let h2 = tracker.start_attempt("socks5_worker", "worker2.com", 443);
        h2.record_dns("doh", 300, Duration::from_millis(20), &[]);
        h2.record_tcp(Duration::from_millis(35), "ok", None);
        h2.record_failure(ConnectionStage::Tls, StageTimeout::TlsHandshake, "ClientHello timeout");
        let s2 = h2.snapshot();
        assert_eq!(s2.failure_stage, "tls");
        assert_eq!(s2.stage_timeout, "tls_handshake_timeout");

        // 3. WSS Timeout
        let h3 = tracker.start_attempt("socks5_worker", "worker3.com", 443);
        h3.record_dns("doh", 300, Duration::from_millis(20), &[]);
        h3.record_tcp(Duration::from_millis(35), "ok", None);
        h3.record_tls(Duration::from_millis(45), "ok");
        h3.record_failure(ConnectionStage::Wss, StageTimeout::WssHandshake, "HTTP 101 timeout");
        let s3 = h3.snapshot();
        assert_eq!(s3.failure_stage, "wss");
        assert_eq!(s3.stage_timeout, "wss_handshake_timeout");

        let sli = tracker.get_sli_summary();
        assert_eq!(sli.failures_by_stage.get("tcp"), Some(&1));
        assert_eq!(sli.failures_by_stage.get("tls"), Some(&1));
        assert_eq!(sli.failures_by_stage.get("wss"), Some(&1));
        assert_eq!(sli.failures_by_timeout.get("tcp_connect_timeout"), Some(&1));
        assert_eq!(sli.failures_by_timeout.get("tls_handshake_timeout"), Some(&1));
        assert_eq!(sli.failures_by_timeout.get("wss_handshake_timeout"), Some(&1));
    }

    #[test]
    fn test_cutoff_detection_around_16kb() {
        let tracker = TimelineTracker::new();
        let handle = tracker.start_attempt("socks5_worker", "worker-cutoff.com", 443);

        handle.record_dns("doh", 300, Duration::from_millis(20), &[]);
        handle.record_tcp(Duration::from_millis(30), "ok", None);
        handle.record_tls(Duration::from_millis(40), "ok");
        handle.record_wss(101, Duration::from_millis(20), "ok");
        handle.record_first_tx();
        handle.record_useful_rx(2048);

        // Transferred 16384 bytes before sudden connection reset / stall
        handle.record_data_transfer(4096, 10240);
        handle.record_failure(ConnectionStage::Stable, StageTimeout::IdleRead, "Connection reset by peer at 16KB");

        let snap = handle.snapshot();
        assert!(snap.is_stall_cutoff);
        assert_eq!(snap.bytes_before_stall, 16384);

        let sli = tracker.get_sli_summary();
        assert_eq!(sli.cutoff_suspected_count, 1);
    }
}
