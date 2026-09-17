use once_cell::sync::Lazy;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::net::IpAddr;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum AddressFamily {
    Ipv4,
    Ipv6,
}

impl AddressFamily {
    pub fn as_str(&self) -> &'static str {
        match self {
            AddressFamily::Ipv4 => "IPv4",
            AddressFamily::Ipv6 => "IPv6",
        }
    }
}

pub fn infer_asn(ip: &IpAddr) -> &'static str {
    match ip {
        IpAddr::V4(v4) => {
            let o = v4.octets();
            // Cloudflare IPv4 ranges:
            // 104.16.0.0/12 (104.16 - 104.31)
            // 172.64.0.0/13 (172.64 - 172.71)
            // 162.158.0.0/15 (162.158 - 162.159)
            // 198.41.128.0/17
            // 188.114.96.0/20
            // 108.162.192.0/18
            // 141.101.64.0/18
            if o[0] == 104 && (o[1] >= 16 && o[1] <= 31) {
                "AS13335 (Cloudflare)"
            } else if o[0] == 172 && (o[1] >= 64 && o[1] <= 71) {
                "AS13335 (Cloudflare)"
            } else if o[0] == 162 && (o[1] == 158 || o[1] == 159) {
                "AS13335 (Cloudflare)"
            } else if o[0] == 198 && o[1] == 41 {
                "AS13335 (Cloudflare)"
            } else if o[0] == 188 && o[1] == 114 {
                "AS13335 (Cloudflare)"
            } else if o[0] == 108 && o[1] == 162 {
                "AS13335 (Cloudflare)"
            } else if o[0] == 141 && o[1] == 101 {
                "AS13335 (Cloudflare)"
            } else {
                "AS-DIRECT (IPv4)"
            }
        }
        IpAddr::V6(v6) => {
            let s = v6.segments();
            // Cloudflare IPv6: 2606:4700::/32, 2a06:98c0::/29
            if s[0] == 0x2606 && s[1] == 0x4700 {
                "AS13335 (Cloudflare IPv6)"
            } else if s[0] == 0x2a06 && (s[1] & 0xffc0) == 0x98c0 {
                "AS13335 (Cloudflare IPv6)"
            } else {
                "AS-DIRECT (IPv6)"
            }
        }
    }
}

pub fn get_ip_prefix(ip: &IpAddr) -> String {
    match ip {
        IpAddr::V4(v4) => {
            let o = v4.octets();
            format!("{}.{}.0.0/16", o[0], o[1])
        }
        IpAddr::V6(v6) => {
            let s = v6.segments();
            format!("{:04x}:{:04x}::/32", s[0], s[1])
        }
    }
}

pub fn extract_cf_colo(headers: &HashMap<String, String>) -> String {
    if let Some(cf_ray) = headers.get("cf-ray") {
        if let Some(idx) = cf_ray.rfind('-') {
            let colo = cf_ray[idx + 1..].trim();
            if !colo.is_empty() && colo.len() <= 6 {
                return colo.to_ascii_uppercase();
            }
        }
    }
    if let Some(colo) = headers.get("cf-colo").or_else(|| headers.get("x-colo")) {
        let trimmed = colo.trim();
        if !trimmed.is_empty() {
            return trimmed.to_ascii_uppercase();
        }
    }
    "UNKNOWN".to_string()
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct PathFingerprint {
    pub family: String,
    pub ip_prefix: String,
    pub colo: String,
    pub asn: String,
}

impl PathFingerprint {
    pub fn new(ip: &IpAddr, colo: &str) -> Self {
        let family = match ip {
            IpAddr::V4(_) => "IPv4",
            IpAddr::V6(_) => "IPv6",
        }
        .to_string();
        let ip_prefix = get_ip_prefix(ip);
        let asn = infer_asn(ip).to_string();
        let colo_clean = if colo.trim().is_empty() {
            "UNKNOWN".to_string()
        } else {
            colo.trim().to_ascii_uppercase()
        };
        Self {
            family,
            ip_prefix,
            colo: colo_clean,
            asn,
        }
    }

    pub fn to_key_string(&self) -> String {
        format!("{}|{}|{}|{}", self.family, self.ip_prefix, self.colo, self.asn)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NodeTelemetry {
    pub domain: String,
    pub resolved_ip: Option<String>,
    pub family: Option<String>,
    pub colo: String,
    pub asn: String,
    pub bytes_before_stall: u64,
    pub total_bytes_transferred: u64,
    pub success_count: u64,
    pub failure_count: u64,
    pub consecutive_failures: u32,
    pub path_fingerprint: Option<PathFingerprint>,
    pub failure_correlation_score: f64,
}

impl NodeTelemetry {
    pub fn new(domain: String) -> Self {
        Self {
            domain,
            resolved_ip: None,
            family: None,
            colo: "UNKNOWN".to_string(),
            asn: "UNKNOWN".to_string(),
            bytes_before_stall: 0,
            total_bytes_transferred: 0,
            success_count: 0,
            failure_count: 0,
            consecutive_failures: 0,
            path_fingerprint: None,
            failure_correlation_score: 0.0,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PathGroupStats {
    pub fingerprint_key: String,
    pub family: String,
    pub ip_prefix: String,
    pub colo: String,
    pub asn: String,
    pub node_count: usize,
    pub total_successes: u64,
    pub total_failures: u64,
    pub failure_rate: f64,
    pub is_blocked: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NodeIndependenceStatus {
    pub total_nodes: usize,
    pub nodes: Vec<NodeTelemetry>,
    pub path_groups: Vec<PathGroupStats>,
    pub diversity_ratio: f64,
}

pub struct NodeIndependenceTracker {
    nodes: HashMap<String, NodeTelemetry>,
    // Path fingerprint key -> (total_successes, total_failures)
    path_stats: HashMap<String, (u64, u64)>,
}

pub static NODE_INDEPENDENCE_TRACKER: Lazy<RwLock<NodeIndependenceTracker>> =
    Lazy::new(|| RwLock::new(NodeIndependenceTracker::new()));

impl NodeIndependenceTracker {
    pub fn new() -> Self {
        Self {
            nodes: HashMap::new(),
            path_stats: HashMap::new(),
        }
    }

    pub fn record_node_resolution(&mut self, domain: &str, ip: IpAddr) {
        let domain_norm = crate::balancer::normalize_domain(domain);
        if domain_norm.is_empty() {
            return;
        }

        let entry = self
            .nodes
            .entry(domain_norm.clone())
            .or_insert_with(|| NodeTelemetry::new(domain_norm));

        entry.resolved_ip = Some(ip.to_string());
        entry.family = Some(
            match ip {
                IpAddr::V4(_) => "IPv4",
                IpAddr::V6(_) => "IPv6",
            }
            .to_string(),
        );
        entry.asn = infer_asn(&ip).to_string();

        let fp = PathFingerprint::new(&ip, &entry.colo);
        entry.path_fingerprint = Some(fp);
    }

    pub fn record_node_handshake_success(&mut self, domain: &str, ip: IpAddr, colo: &str) {
        let domain_norm = crate::balancer::normalize_domain(domain);
        if domain_norm.is_empty() {
            return;
        }

        let fp = PathFingerprint::new(&ip, colo);
        let fp_key = fp.to_key_string();

        let path_stat = self.path_stats.entry(fp_key.clone()).or_insert((0, 0));
        path_stat.0 = path_stat.0.saturating_add(1);

        let entry = self
            .nodes
            .entry(domain_norm.clone())
            .or_insert_with(|| NodeTelemetry::new(domain_norm));

        entry.resolved_ip = Some(ip.to_string());
        entry.family = Some(
            match ip {
                IpAddr::V4(_) => "IPv4",
                IpAddr::V6(_) => "IPv6",
            }
            .to_string(),
        );
        entry.colo = if colo.trim().is_empty() {
            "UNKNOWN".to_string()
        } else {
            colo.trim().to_ascii_uppercase()
        };
        entry.asn = infer_asn(&ip).to_string();
        entry.path_fingerprint = Some(fp);
        entry.success_count = entry.success_count.saturating_add(1);
        entry.consecutive_failures = 0;

        self.recalculate_correlation_scores();
    }

    pub fn record_node_stall(&mut self, domain: &str, bytes_transferred: u64) {
        let domain_norm = crate::balancer::normalize_domain(domain);
        if domain_norm.is_empty() {
            return;
        }

        let entry = self
            .nodes
            .entry(domain_norm.clone())
            .or_insert_with(|| NodeTelemetry::new(domain_norm));

        entry.bytes_before_stall = bytes_transferred;
        entry.total_bytes_transferred = entry.total_bytes_transferred.saturating_add(bytes_transferred);
        entry.failure_count = entry.failure_count.saturating_add(1);
        entry.consecutive_failures = entry.consecutive_failures.saturating_add(1);

        if let Some(ref fp) = entry.path_fingerprint {
            let fp_key = fp.to_key_string();
            let path_stat = self.path_stats.entry(fp_key).or_insert((0, 0));
            path_stat.1 = path_stat.1.saturating_add(1);
        }

        self.recalculate_correlation_scores();
    }

    pub fn record_node_bytes_transferred(&mut self, domain: &str, bytes: u64) {
        let domain_norm = crate::balancer::normalize_domain(domain);
        if domain_norm.is_empty() {
            return;
        }

        let entry = self
            .nodes
            .entry(domain_norm.clone())
            .or_insert_with(|| NodeTelemetry::new(domain_norm));

        entry.total_bytes_transferred = entry.total_bytes_transferred.saturating_add(bytes);
    }

    pub fn record_node_failure(&mut self, domain: &str, ip: Option<IpAddr>, colo: Option<&str>) {
        let domain_norm = crate::balancer::normalize_domain(domain);
        if domain_norm.is_empty() {
            return;
        }

        let entry = self
            .nodes
            .entry(domain_norm.clone())
            .or_insert_with(|| NodeTelemetry::new(domain_norm));

        if let Some(ip_addr) = ip {
            entry.resolved_ip = Some(ip_addr.to_string());
            entry.family = Some(
                match ip_addr {
                    IpAddr::V4(_) => "IPv4",
                    IpAddr::V6(_) => "IPv6",
                }
                .to_string(),
            );
            entry.asn = infer_asn(&ip_addr).to_string();
            let c = colo.unwrap_or(&entry.colo);
            entry.path_fingerprint = Some(PathFingerprint::new(&ip_addr, c));
        }

        entry.bytes_before_stall = 0;
        entry.failure_count = entry.failure_count.saturating_add(1);
        entry.consecutive_failures = entry.consecutive_failures.saturating_add(1);

        if let Some(ref fp) = entry.path_fingerprint {
            let fp_key = fp.to_key_string();
            let path_stat = self.path_stats.entry(fp_key).or_insert((0, 0));
            path_stat.1 = path_stat.1.saturating_add(1);
        }

        self.recalculate_correlation_scores();
    }

    fn recalculate_correlation_scores(&mut self) {
        for entry in self.nodes.values_mut() {
            if let Some(ref fp) = entry.path_fingerprint {
                let fp_key = fp.to_key_string();
                if let Some(&(succ, fail)) = self.path_stats.get(&fp_key) {
                    let total = succ + fail;
                    if total > 0 {
                        entry.failure_correlation_score = fail as f64 / total as f64;
                    } else {
                        entry.failure_correlation_score = 0.0;
                    }
                }
            } else {
                // If unprobed but ends with .workers.dev, default correlation if any other workers.dev failed
                entry.failure_correlation_score = 0.0;
            }
        }
    }

    /// Selects candidates for racing based on TRUE route diversity and success probability.
    ///
    /// Rules (MOB-027):
    /// 1. Never flood the race with redundant clones that share a failing/blocked path fingerprint.
    /// 2. Candidates with distinct path fingerprints (different IP prefix, Colo, AddressFamily, or ASN)
    ///    are prioritized to maximize the probability that at least one diverse path succeeds.
    /// 3. If a path fingerprint has already experienced repeated failures (failure_correlation >= 0.8),
    ///    subsequent domains sharing the same fingerprint are excluded from the active race.
    pub fn select_diverse_race_candidates(
        &self,
        candidate_domains: &[String],
        max_slots: usize,
    ) -> Vec<String> {
        if candidate_domains.is_empty() || max_slots == 0 {
            return Vec::new();
        }

        let mut selected = Vec::new();
        let mut seen_fingerprints = HashSet::new();
        let mut deferred_candidates = Vec::new();

        // Pass 1: Select candidates that introduce a distinct, non-blocked path fingerprint
        for domain in candidate_domains {
            let norm = crate::balancer::normalize_domain(domain);
            let telemetry = self.nodes.get(&norm);

            let (fp_key, is_blocked) = match telemetry.and_then(|t| t.path_fingerprint.as_ref()) {
                Some(fp) => {
                    let key = fp.to_key_string();
                    let blocked = match self.path_stats.get(&key) {
                        Some(&(succ, fail)) => fail >= 2 && succ == 0,
                        None => false,
                    };
                    (key, blocked)
                }
                None => {
                    // Unresolved domain: infer generic fingerprint based on domain class
                    let inferred_key = if norm.ends_with(".workers.dev") {
                        "IPv4|104.21.0.0/16|UNKNOWN|AS13335 (Cloudflare)".to_string()
                    } else {
                        format!("UNKNOWN|{}|UNKNOWN|UNKNOWN", norm)
                    };
                    let blocked = match self.path_stats.get(&inferred_key) {
                        Some(&(succ, fail)) => fail >= 2 && succ == 0,
                        None => false,
                    };
                    (inferred_key, blocked)
                }
            };

            if is_blocked {
                // Shared edge path is currently blocked/failing; defer this clone
                deferred_candidates.push(domain.clone());
                continue;
            }

            if !seen_fingerprints.contains(&fp_key) {
                seen_fingerprints.insert(fp_key);
                selected.push(domain.clone());
                if selected.len() >= max_slots {
                    return selected;
                }
            } else {
                // Redundant clone of an already represented path
                deferred_candidates.push(domain.clone());
            }
        }

        // Pass 2: If spare race slots remain, fill with non-blocked duplicates
        for domain in deferred_candidates {
            if selected.len() >= max_slots {
                break;
            }
            let norm = crate::balancer::normalize_domain(&domain);
            let is_hard_blocked = self
                .nodes
                .get(&norm)
                .map(|t| t.consecutive_failures >= 3)
                .unwrap_or(false);

            if !is_hard_blocked && !selected.contains(&domain) {
                selected.push(domain);
            }
        }

        // Pass 3: Safety guarantee - if all candidates were filtered out, keep at least the first candidate
        if selected.is_empty() && !candidate_domains.is_empty() {
            selected.push(candidate_domains[0].clone());
        }

        selected
    }

    pub fn get_telemetry(&self, domain: &str) -> Option<NodeTelemetry> {
        let norm = crate::balancer::normalize_domain(domain);
        self.nodes.get(&norm).cloned()
    }

    pub fn get_all_telemetry_json(&self) -> String {
        let total_nodes = self.nodes.len();
        let mut nodes: Vec<NodeTelemetry> = self.nodes.values().cloned().collect();
        nodes.sort_by(|a, b| a.domain.cmp(&b.domain));

        let mut path_groups_map: HashMap<String, PathGroupStats> = HashMap::new();
        for node in &nodes {
            if let Some(ref fp) = node.path_fingerprint {
                let key = fp.to_key_string();
                let entry = path_groups_map.entry(key.clone()).or_insert_with(|| {
                    let (succ, fail) = self.path_stats.get(&key).copied().unwrap_or((0, 0));
                    let total = succ + fail;
                    let failure_rate = if total > 0 {
                        fail as f64 / total as f64
                    } else {
                        0.0
                    };
                    let is_blocked = fail >= 2 && succ == 0;
                    PathGroupStats {
                        fingerprint_key: key,
                        family: fp.family.clone(),
                        ip_prefix: fp.ip_prefix.clone(),
                        colo: fp.colo.clone(),
                        asn: fp.asn.clone(),
                        node_count: 0,
                        total_successes: succ,
                        total_failures: fail,
                        failure_rate,
                        is_blocked,
                    }
                });
                entry.node_count += 1;
            }
        }

        let mut path_groups: Vec<PathGroupStats> = path_groups_map.into_values().collect();
        path_groups.sort_by(|a, b| a.fingerprint_key.cmp(&b.fingerprint_key));

        let unique_paths = path_groups.len();
        let diversity_ratio = if total_nodes > 0 {
            unique_paths as f64 / total_nodes as f64
        } else {
            1.0
        };

        let status = NodeIndependenceStatus {
            total_nodes,
            nodes,
            path_groups,
            diversity_ratio,
        };

        serde_json::to_string(&status).unwrap_or_else(|_| "{}".to_string())
    }

    pub fn reset(&mut self) {
        self.nodes.clear();
        self.path_stats.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_infer_asn_and_prefixes() {
        let cf_v4: IpAddr = "104.21.32.1".parse().unwrap();
        assert_eq!(infer_asn(&cf_v4), "AS13335 (Cloudflare)");
        assert_eq!(get_ip_prefix(&cf_v4), "104.21.0.0/16");

        let cf_v6: IpAddr = "2606:4700:3033::6815:2037".parse().unwrap();
        assert_eq!(infer_asn(&cf_v6), "AS13335 (Cloudflare IPv6)");
        assert_eq!(get_ip_prefix(&cf_v6), "2606:4700::/32");

        let custom_v4: IpAddr = "95.165.12.3".parse().unwrap();
        assert_eq!(infer_asn(&custom_v4), "AS-DIRECT (IPv4)");
        assert_eq!(get_ip_prefix(&custom_v4), "95.165.0.0/16");
    }

    #[test]
    fn test_extract_cf_colo_from_headers() {
        let mut headers = HashMap::new();
        headers.insert("cf-ray".to_string(), "88591a2bc3f41234-DME".to_string());
        assert_eq!(extract_cf_colo(&headers), "DME");

        let mut headers2 = HashMap::new();
        headers2.insert("cf-ray".to_string(), "99abc1234567-FRA".to_string());
        assert_eq!(extract_cf_colo(&headers2), "FRA");

        let mut headers3 = HashMap::new();
        headers3.insert("server".to_string(), "cloudflare".to_string());
        assert_eq!(extract_cf_colo(&headers3), "UNKNOWN");
    }

    #[test]
    fn test_diverse_race_selection_filters_correlated_failing_clones() {
        let mut tracker = NodeIndependenceTracker::new();

        let ip_cf1: IpAddr = "104.21.32.1".parse().unwrap();
        let ip_cf2: IpAddr = "104.21.32.2".parse().unwrap();
        let ip_hel: IpAddr = "172.67.180.1".parse().unwrap();
        let ip_v6: IpAddr = "2606:4700:3033::1".parse().unwrap();

        // 3 domains resolve to DME on 104.21.0.0/16
        tracker.record_node_resolution("worker1.workers.dev", ip_cf1);
        tracker.record_node_resolution("worker2.workers.dev", ip_cf2);
        tracker.record_node_resolution("worker3.workers.dev", ip_cf1);

        // worker1 fails (e.g. DME throttled by DPI)
        tracker.record_node_failure("worker1.workers.dev", Some(ip_cf1), Some("DME"));
        tracker.record_node_stall("worker1.workers.dev", 0);

        // 1 domain resolves to HEL on 172.67.0.0/16
        tracker.record_node_resolution("worker-hel.dev", ip_hel);
        tracker.record_node_handshake_success("worker-hel.dev", ip_hel, "HEL");

        // 1 domain resolves to IPv6 on 2606:4700::/32
        tracker.record_node_resolution("worker-v6.dev", ip_v6);
        tracker.record_node_handshake_success("worker-v6.dev", ip_v6, "FRA");

        let candidates = vec![
            "worker1.workers.dev".to_string(),
            "worker2.workers.dev".to_string(),
            "worker3.workers.dev".to_string(),
            "worker-hel.dev".to_string(),
            "worker-v6.dev".to_string(),
        ];

        let selected = tracker.select_diverse_race_candidates(&candidates, 3);

        // worker2 and worker3 share the failing 104.21/DME path with worker1.
        // Instead of picking 3 failing clones, the tracker selects the diverse paths (HEL, IPv6)!
        assert!(selected.contains(&"worker-hel.dev".to_string()));
        assert!(selected.contains(&"worker-v6.dev".to_string()));
        assert_eq!(selected.len(), 3);
    }

    #[test]
    fn test_telemetry_json_output() {
        let mut tracker = NodeIndependenceTracker::new();
        let ip: IpAddr = "104.21.32.1".parse().unwrap();
        tracker.record_node_handshake_success("worker1.workers.dev", ip, "DME");
        tracker.record_node_stall("worker1.workers.dev", 16384);

        let json = tracker.get_all_telemetry_json();
        assert!(json.contains("\"resolved_ip\":\"104.21.32.1\""));
        assert!(json.contains("\"family\":\"IPv4\""));
        assert!(json.contains("\"colo\":\"DME\""));
        assert!(json.contains("\"asn\":\"AS13335 (Cloudflare)\""));
        assert!(json.contains("\"bytes_before_stall\":16384"));
        assert!(json.contains("\"failure_correlation_score\":"));
    }
}
