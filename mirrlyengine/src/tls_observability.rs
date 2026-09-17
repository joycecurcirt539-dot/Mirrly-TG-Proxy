use once_cell::sync::Lazy;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Representation of the TLS handshake outcome.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum HandshakeKindSummary {
    Full,
    FullWithHelloRetryRequest,
    Resumed,
    Failed,
    Unknown,
}

impl From<Option<rustls::HandshakeKind>> for HandshakeKindSummary {
    fn from(kind: Option<rustls::HandshakeKind>) -> Self {
        match kind {
            Some(rustls::HandshakeKind::Full) => HandshakeKindSummary::Full,
            Some(rustls::HandshakeKind::FullWithHelloRetryRequest) => {
                HandshakeKindSummary::FullWithHelloRetryRequest
            }
            Some(rustls::HandshakeKind::Resumed) => HandshakeKindSummary::Resumed,
            None => HandshakeKindSummary::Unknown,
        }
    }
}

impl HandshakeKindSummary {
    pub fn as_str(&self) -> &'static str {
        match self {
            HandshakeKindSummary::Full => "full",
            HandshakeKindSummary::FullWithHelloRetryRequest => "full_hrr",
            HandshakeKindSummary::Resumed => "resumed",
            HandshakeKindSummary::Failed => "failed",
            HandshakeKindSummary::Unknown => "unknown",
        }
    }
}

/// Statistics tracked per (hostname, network_generation).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TlsHostStats {
    pub hostname: String,
    pub network_generation: u64,
    pub full_handshakes: u64,
    pub resumed_handshakes: u64,
    pub handshake_failures: u64,
    pub total_duration_ms: u64,
    pub min_duration_ms: u64,
    pub max_duration_ms: u64,
    pub avg_duration_ms: f64,
    pub resumption_ratio: f64,
    pub last_handshake_kind: Option<String>,
    pub last_duration_ms: u64,
    pub last_error: Option<String>,
}

impl TlsHostStats {
    pub fn new(hostname: String, network_generation: u64) -> Self {
        Self {
            hostname,
            network_generation,
            full_handshakes: 0,
            resumed_handshakes: 0,
            handshake_failures: 0,
            total_duration_ms: 0,
            min_duration_ms: 0,
            max_duration_ms: 0,
            avg_duration_ms: 0.0,
            resumption_ratio: 0.0,
            last_handshake_kind: None,
            last_duration_ms: 0,
            last_error: None,
        }
    }

    pub fn record_success(&mut self, kind: Option<rustls::HandshakeKind>, duration_ms: u64) {
        let kind_summary = HandshakeKindSummary::from(kind);
        match kind_summary {
            HandshakeKindSummary::Resumed => {
                self.resumed_handshakes += 1;
            }
            HandshakeKindSummary::Full | HandshakeKindSummary::FullWithHelloRetryRequest => {
                self.full_handshakes += 1;
            }
            HandshakeKindSummary::Unknown => {
                self.full_handshakes += 1;
            }
            HandshakeKindSummary::Failed => {}
        }

        self.total_duration_ms += duration_ms;
        if self.min_duration_ms == 0 || duration_ms < self.min_duration_ms {
            self.min_duration_ms = duration_ms;
        }
        if duration_ms > self.max_duration_ms {
            self.max_duration_ms = duration_ms;
        }

        let total_success = self.full_handshakes + self.resumed_handshakes;
        if total_success > 0 {
            self.avg_duration_ms = (self.total_duration_ms as f64) / (total_success as f64);
            self.resumption_ratio = (self.resumed_handshakes as f64) / (total_success as f64);
        }

        self.last_handshake_kind = Some(kind_summary.as_str().to_string());
        self.last_duration_ms = duration_ms;
        self.last_error = None;
    }

    pub fn record_failure(&mut self, duration_ms: u64, error_msg: &str) {
        self.handshake_failures += 1;
        self.last_handshake_kind = Some("failed".to_string());
        self.last_duration_ms = duration_ms;
        self.last_error = Some(error_msg.to_string());
    }
}

/// Global snapshot of TLS observability status.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TlsObservabilityStatus {
    pub current_network_generation: u64,
    pub total_full_handshakes: u64,
    pub total_resumed_handshakes: u64,
    pub total_handshake_failures: u64,
    pub global_resumption_ratio: f64,
    pub hosts: Vec<TlsHostStats>,
}

pub struct TlsObservabilityTracker {
    hosts: HashMap<(String, u64), TlsHostStats>,
}

impl TlsObservabilityTracker {
    pub fn new() -> Self {
        Self {
            hosts: HashMap::new(),
        }
    }

    pub fn record_success(
        &mut self,
        hostname: &str,
        network_generation: u64,
        kind: Option<rustls::HandshakeKind>,
        duration_ms: u64,
    ) {
        let key = (hostname.to_string(), network_generation);
        let entry = self
            .hosts
            .entry(key)
            .or_insert_with(|| TlsHostStats::new(hostname.to_string(), network_generation));
        entry.record_success(kind, duration_ms);
    }

    pub fn record_failure(
        &mut self,
        hostname: &str,
        network_generation: u64,
        duration_ms: u64,
        error_msg: &str,
    ) {
        let key = (hostname.to_string(), network_generation);
        let entry = self
            .hosts
            .entry(key)
            .or_insert_with(|| TlsHostStats::new(hostname.to_string(), network_generation));
        entry.record_failure(duration_ms, error_msg);
    }

    pub fn get_stats_for_host(
        &self,
        hostname: &str,
        network_generation: u64,
    ) -> Option<TlsHostStats> {
        let key = (hostname.to_string(), network_generation);
        self.hosts.get(&key).cloned()
    }

    pub fn get_status(&self) -> TlsObservabilityStatus {
        let current_gen = crate::network_profile::current_generation();
        let mut total_full = 0u64;
        let mut total_resumed = 0u64;
        let mut total_failures = 0u64;

        let mut hosts: Vec<TlsHostStats> = self.hosts.values().cloned().collect();
        hosts.sort_by(|a, b| {
            b.network_generation
                .cmp(&a.network_generation)
                .then_with(|| a.hostname.cmp(&b.hostname))
        });

        for h in &hosts {
            total_full += h.full_handshakes;
            total_resumed += h.resumed_handshakes;
            total_failures += h.handshake_failures;
        }

        let total_success = total_full + total_resumed;
        let global_resumption_ratio = if total_success > 0 {
            (total_resumed as f64) / (total_success as f64)
        } else {
            0.0
        };

        TlsObservabilityStatus {
            current_network_generation: current_gen,
            total_full_handshakes: total_full,
            total_resumed_handshakes: total_resumed,
            total_handshake_failures: total_failures,
            global_resumption_ratio,
            hosts,
        }
    }

    pub fn get_status_json(&self) -> String {
        serde_json::to_string(&self.get_status()).unwrap_or_else(|_| "{}".to_string())
    }

    pub fn reset(&mut self) {
        self.hosts.clear();
    }
}

pub static TLS_TRACKER: Lazy<RwLock<TlsObservabilityTracker>> =
    Lazy::new(|| RwLock::new(TlsObservabilityTracker::new()));

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_record_full_and_resumed_handshakes() {
        let mut tracker = TlsObservabilityTracker::new();
        let host = "worker1.example.workers.dev";
        let net_gen = 1;

        // 1st handshake: Full, 120ms
        tracker.record_success(host, net_gen, Some(rustls::HandshakeKind::Full), 120);
        let stats = tracker.get_stats_for_host(host, net_gen).unwrap();
        assert_eq!(stats.full_handshakes, 1);
        assert_eq!(stats.resumed_handshakes, 0);
        assert_eq!(stats.handshake_failures, 0);
        assert_eq!(stats.min_duration_ms, 120);
        assert_eq!(stats.max_duration_ms, 120);
        assert_eq!(stats.avg_duration_ms, 120.0);
        assert_eq!(stats.resumption_ratio, 0.0);
        assert_eq!(stats.last_handshake_kind.as_deref(), Some("full"));

        // 2nd handshake: Resumed, 25ms
        tracker.record_success(host, net_gen, Some(rustls::HandshakeKind::Resumed), 25);
        let stats = tracker.get_stats_for_host(host, net_gen).unwrap();
        assert_eq!(stats.full_handshakes, 1);
        assert_eq!(stats.resumed_handshakes, 1);
        assert_eq!(stats.handshake_failures, 0);
        assert_eq!(stats.min_duration_ms, 25);
        assert_eq!(stats.max_duration_ms, 120);
        assert_eq!(stats.avg_duration_ms, 72.5);
        assert_eq!(stats.resumption_ratio, 0.5);
        assert_eq!(stats.last_handshake_kind.as_deref(), Some("resumed"));

        // 3rd handshake: Resumed, 20ms
        tracker.record_success(host, net_gen, Some(rustls::HandshakeKind::Resumed), 20);
        let stats = tracker.get_stats_for_host(host, net_gen).unwrap();
        assert_eq!(stats.full_handshakes, 1);
        assert_eq!(stats.resumed_handshakes, 2);
        assert_eq!(stats.min_duration_ms, 20);
        assert_eq!(stats.max_duration_ms, 120);
        assert!((stats.resumption_ratio - 0.6666).abs() < 0.01);
    }

    #[test]
    fn test_record_handshake_failure() {
        let mut tracker = TlsObservabilityTracker::new();
        let host = "bad-cert.example.workers.dev";
        let net_gen = 1;

        tracker.record_failure(host, net_gen, 45, "invalid certificate: Expired");
        let stats = tracker.get_stats_for_host(host, net_gen).unwrap();
        assert_eq!(stats.full_handshakes, 0);
        assert_eq!(stats.resumed_handshakes, 0);
        assert_eq!(stats.handshake_failures, 1);
        assert_eq!(stats.last_handshake_kind.as_deref(), Some("failed"));
        assert_eq!(
            stats.last_error.as_deref(),
            Some("invalid certificate: Expired")
        );
    }

    #[test]
    fn test_network_generation_isolation() {
        let mut tracker = TlsObservabilityTracker::new();
        let host = "worker1.example.workers.dev";

        // Generation 1 (e.g. Wi-Fi)
        tracker.record_success(host, 1, Some(rustls::HandshakeKind::Full), 100);
        tracker.record_success(host, 1, Some(rustls::HandshakeKind::Resumed), 20);

        // Handover to Generation 2 (e.g. LTE): starts fresh, must do Full handshake again
        tracker.record_success(host, 2, Some(rustls::HandshakeKind::Full), 140);

        let gen1_stats = tracker.get_stats_for_host(host, 1).unwrap();
        assert_eq!(gen1_stats.full_handshakes, 1);
        assert_eq!(gen1_stats.resumed_handshakes, 1);

        let gen2_stats = tracker.get_stats_for_host(host, 2).unwrap();
        assert_eq!(gen2_stats.full_handshakes, 1);
        assert_eq!(gen2_stats.resumed_handshakes, 0);
    }

    #[test]
    fn test_status_serialization() {
        let mut tracker = TlsObservabilityTracker::new();
        tracker.record_success("nodeA.org", 1, Some(rustls::HandshakeKind::Full), 80);
        tracker.record_success("nodeA.org", 1, Some(rustls::HandshakeKind::Resumed), 15);
        tracker.record_failure("nodeB.org", 1, 30, "connection reset");

        let json = tracker.get_status_json();
        assert!(json.contains("nodeA.org"));
        assert!(json.contains("nodeB.org"));
        assert!(json.contains("\"total_full_handshakes\":1"));
        assert!(json.contains("\"total_resumed_handshakes\":1"));
        assert!(json.contains("\"total_handshake_failures\":1"));
    }
}
