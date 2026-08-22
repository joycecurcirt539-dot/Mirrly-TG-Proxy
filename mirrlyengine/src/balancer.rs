use once_cell::sync::Lazy;
use parking_lot::RwLock;
use rand::seq::SliceRandom;
use std::collections::HashMap;

pub const STABILITY_HYSTERESIS_MS: u64 = 60;

pub struct Balancer {
    domains: Vec<String>,
    // Per-DC ranked domains: dc_id -> Vec<(domain, latency_ms)> sorted by lowest latency ms
    dc_rankings: HashMap<i32, Vec<(String, u64)>>,
    // Per-DC active domain: dc_id -> domain
    dc_to_domain: HashMap<i32, String>,
}

pub static BALANCER: Lazy<RwLock<Balancer>> = Lazy::new(|| RwLock::new(Balancer::new()));

impl Balancer {
    pub fn new() -> Self {
        Self {
            domains: Vec::new(),
            dc_rankings: HashMap::new(),
            dc_to_domain: HashMap::new(),
        }
    }

    pub fn update_domains_list(&mut self, domains_list: &[String]) {
        let mut current_sorted = self.domains.clone();
        current_sorted.sort();
        let mut new_sorted = domains_list.to_vec();
        new_sorted.sort();

        if current_sorted == new_sorted {
            return;
        }

        self.domains = domains_list.to_vec();
        if self.dc_rankings.is_empty() {
            let mut rng = rand::thread_rng();
            self.dc_to_domain.clear();
            for dc_id in [1, 2, 3, 4, 5, 203] {
                if let Some(domain) = self.domains.choose(&mut rng) {
                    self.dc_to_domain.insert(dc_id, domain.clone());
                }
            }
        }
    }

    /// Update ranking specifically for `dc_id`
    pub fn update_ranked_domains_for_dc(&mut self, dc_id: i32, mut ranked: Vec<(String, u64)>) {
        if ranked.is_empty() {
            return;
        }
        // Sort by lowest latency ms
        ranked.sort_by_key(|(_, latency)| *latency);
        let ranked_map: HashMap<&str, u64> =
            ranked.iter().map(|(d, l)| (d.as_str(), *l)).collect();
        let (best_domain, best_latency) = &ranked[0];

        // Stability-First Selection for THIS specific DC:
        // On mobile LTE networks, RTT fluctuates naturally by 20-50ms. Switching active endpoints
        // on minor jitter causes connection resets and TCP re-handshakes.
        // If the current active domain for this DC is still alive, healthy and within STABILITY_HYSTERESIS_MS (60ms),
        // we PRESERVE it for 99.9% connection stability.
        let should_switch = match self.dc_to_domain.get(&dc_id) {
            Some(current_d) if !current_d.is_empty() => {
                match ranked_map.get(current_d.as_str()) {
                    Some(&current_lat) => {
                        // Only switch if the new winner is significantly faster (> 60ms)
                        current_lat > best_latency + STABILITY_HYSTERESIS_MS
                    }
                    None => {
                        // Current domain failed/timed out in probe, switch to best healthy domain
                        true
                    }
                }
            }
            _ => true, // No current domain set, adopt best immediately
        };

        if should_switch {
            self.dc_to_domain.insert(dc_id, best_domain.clone());
        }

        self.dc_rankings.insert(dc_id, ranked);
    }

    /// Backwards-compatible helper: if general ranking is passed, update DC2
    pub fn update_ranked_domains(&mut self, ranked: Vec<(String, u64)>) {
        self.update_ranked_domains_for_dc(2, ranked);
    }

    pub fn update_domain_for_dc(&mut self, dc_id: i32, domain: &str) -> bool {
        if self.dc_to_domain.get(&dc_id).map(|s| s.as_str()) == Some(domain) {
            return false;
        }
        self.dc_to_domain.insert(dc_id, domain.to_string());
        true
    }

    pub fn get_active_domain_for_dc(&self, dc_id: i32) -> Option<String> {
        self.dc_to_domain.get(&dc_id).filter(|s| !s.is_empty()).cloned()
    }

    pub fn get_fastest_domain_for_dc(&self, dc_id: i32) -> Option<String> {
        self.dc_rankings
            .get(&dc_id)
            .and_then(|r| r.first())
            .map(|(d, _)| d.clone())
            .or_else(|| {
                // Fallback to DC2 if no specific ranking for this DC
                self.dc_rankings
                    .get(&2)
                    .and_then(|r| r.first())
                    .map(|(d, _)| d.clone())
            })
    }

    pub fn get_fastest_domain(&self) -> Option<String> {
        self.get_fastest_domain_for_dc(2)
    }

    pub fn get_domains_for_dc(&self, dc_id: i32) -> Vec<String> {
        let mut result = Vec::new();
        let mut seen = std::collections::HashSet::new();

        // 1. Current active/confirmed domain for this DC (if any)
        if let Some(d) = self.dc_to_domain.get(&dc_id) {
            if !d.is_empty() {
                result.push(d.clone());
                seen.insert(d.clone());
            }
        }

        // 2. Ranked domains specifically for this DC (in order of lowest latency)
        if let Some(ranked) = self.dc_rankings.get(&dc_id) {
            for (d, _) in ranked {
                if !seen.contains(d) {
                    result.push(d.clone());
                    seen.insert(d.clone());
                }
            }
        } else if let Some(dc2_ranked) = self.dc_rankings.get(&2) {
            // 2b. Fallback to DC2 general ranking if this DC has not been probed yet
            for (d, _) in dc2_ranked {
                if !seen.contains(d) {
                    result.push(d.clone());
                    seen.insert(d.clone());
                }
            }
        }

        // 3. Fallback to remaining unranked domains (shuffled)
        let mut remaining = self.domains.clone();
        let mut rng = rand::thread_rng();
        remaining.shuffle(&mut rng);

        for d in remaining {
            if !seen.contains(&d) {
                result.push(d.clone());
                seen.insert(d);
            }
        }

        result
    }

    pub fn record_latency_for_dc(&mut self, dc_id: i32, domain: &str, latency_ms: u64) {
        if domain.is_empty() {
            return;
        }
        let list = self.dc_rankings.entry(dc_id).or_default();
        if let Some(pos) = list.iter().position(|(d, _)| d == domain) {
            // Exponential smoothing (70% old, 30% new) to smooth out single-packet jitter
            let old_lat = list[pos].1;
            let smoothed = (old_lat * 7 + latency_ms * 3) / 10;
            list[pos].1 = smoothed;
        } else {
            list.push((domain.to_string(), latency_ms));
        }
        list.sort_by_key(|(_, l)| *l);
    }

    pub fn reset_ranking(&mut self) {
        self.dc_rankings.clear();
        self.dc_to_domain.clear();
        if !self.domains.is_empty() {
            let mut rng = rand::thread_rng();
            for dc_id in [1, 2, 3, 4, 5, 203] {
                if let Some(domain) = self.domains.choose(&mut rng) {
                    self.dc_to_domain.insert(dc_id, domain.clone());
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_balancer_per_dc_ranking_and_reset() {
        let mut b = Balancer::new();
        let domains = vec![
            "worker1.dev".to_string(),
            "worker2.dev".to_string(),
            "worker3.dev".to_string(),
        ];
        b.update_domains_list(&domains);

        // Rank DC2: worker1 is fastest (80ms)
        b.update_ranked_domains_for_dc(2, vec![
            ("worker2.dev".to_string(), 120),
            ("worker1.dev".to_string(), 80),
            ("worker3.dev".to_string(), 250),
        ]);

        // Rank DC4 (Media): worker3 is fastest (45ms)
        b.update_ranked_domains_for_dc(4, vec![
            ("worker3.dev".to_string(), 45),
            ("worker2.dev".to_string(), 95),
            ("worker1.dev".to_string(), 180),
        ]);

        // Fastest for DC2 should be worker1
        assert_eq!(b.get_fastest_domain_for_dc(2).as_deref(), Some("worker1.dev"));
        let dc2_domains = b.get_domains_for_dc(2);
        assert_eq!(dc2_domains.first().map(|s| s.as_str()), Some("worker1.dev"));

        // Fastest for DC4 should be worker3
        assert_eq!(b.get_fastest_domain_for_dc(4).as_deref(), Some("worker3.dev"));
        let dc4_domains = b.get_domains_for_dc(4);
        assert_eq!(dc4_domains.first().map(|s| s.as_str()), Some("worker3.dev"));

        // Unprobed DC (e.g. DC5) should fallback to DC2 ranking
        let dc5_domains = b.get_domains_for_dc(5);
        assert_eq!(dc5_domains.first().map(|s| s.as_str()), Some("worker1.dev"));

        // Reset ranking on network switch
        b.reset_ranking();
        assert_eq!(b.get_fastest_domain_for_dc(2), None);
        assert_eq!(b.get_fastest_domain_for_dc(4), None);
        assert_eq!(b.dc_rankings.len(), 0);
    }

    #[test]
    fn test_balancer_stability_hysteresis() {
        let mut b = Balancer::new();
        let domains = vec![
            "workerA.dev".to_string(),
            "workerB.dev".to_string(),
            "workerC.dev".to_string(),
        ];
        b.update_domains_list(&domains);

        // Initial assignment for DC 2
        b.update_domain_for_dc(2, "workerA.dev");

        // Race where workerB is slightly faster (100ms vs 130ms, diff 30ms <= 60ms hysteresis)
        b.update_ranked_domains_for_dc(2, vec![
            ("workerB.dev".to_string(), 100),
            ("workerA.dev".to_string(), 130),
            ("workerC.dev".to_string(), 300),
        ]);

        // DC 2 should PRESERVE workerA.dev for connection stability
        assert_eq!(b.get_active_domain_for_dc(2).as_deref(), Some("workerA.dev"));
        assert_eq!(b.get_domains_for_dc(2)[0], "workerA.dev");
        assert_eq!(b.get_domains_for_dc(2)[1], "workerB.dev");

        // Now workerB is dramatically faster on DC 2 (80ms vs 200ms, diff 120ms > 60ms hysteresis)
        b.update_ranked_domains_for_dc(2, vec![
            ("workerB.dev".to_string(), 80),
            ("workerA.dev".to_string(), 200),
            ("workerC.dev".to_string(), 300),
        ]);

        // DC 2 should switch to workerB.dev
        assert_eq!(b.get_active_domain_for_dc(2).as_deref(), Some("workerB.dev"));

        // If workerB fails on DC 2 (absent in ranked results), should switch immediately to next best
        b.update_ranked_domains_for_dc(2, vec![
            ("workerA.dev".to_string(), 120),
            ("workerC.dev".to_string(), 250),
        ]);
        assert_eq!(b.get_active_domain_for_dc(2).as_deref(), Some("workerA.dev"));
    }
}
