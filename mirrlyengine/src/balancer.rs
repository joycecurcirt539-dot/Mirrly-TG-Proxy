use once_cell::sync::Lazy;
use parking_lot::RwLock;
use rand::seq::SliceRandom;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

pub const STABILITY_HYSTERESIS_MS: u64 = 60;
pub const UNPROBED_DEFAULT_SCORE_MS: u64 = 250;
pub const USEFUL_SUCCESS_BONUS_MS: u64 = 15;
pub const MAX_USEFUL_SUCCESS_BONUS_MS: u64 = 60;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct DcTargetKey {
    pub dc_id: i32,
    pub is_media: bool,
}

impl DcTargetKey {
    pub fn new(dc_id: i32, is_media: bool) -> Self {
        Self { dc_id, is_media }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DomainStats {
    pub domain: String,
    pub probe_rtt_ms: Option<u64>,
    pub useful_success_count: u64,
    pub failure_count: u64,
    pub consecutive_failures: u32,
    pub last_useful_at_ms: u64,
    pub last_failure_at_ms: u64,
}

impl DomainStats {
    pub fn new(domain: String) -> Self {
        Self {
            domain,
            probe_rtt_ms: None,
            useful_success_count: 0,
            failure_count: 0,
            consecutive_failures: 0,
            last_useful_at_ms: 0,
            last_failure_at_ms: 0,
        }
    }

    /// Composite score calculation:
    /// Lower score = better candidate.
    /// Incorporates:
    /// 1. Base probe RTT (or default unprobed baseline).
    /// 2. Useful success bonus: each verified useful RX reduces score by 15ms (up to 60ms).
    /// 3. Failure penalty: consecutive failures rapidly penalize broken domains (+100ms, +250ms, +1000ms),
    ///    plus cumulative failures penalty.
    pub fn calculate_score(&self) -> u64 {
        let base = self.probe_rtt_ms.unwrap_or(UNPROBED_DEFAULT_SCORE_MS);
        let useful_bonus = std::cmp::min(
            self.useful_success_count.saturating_mul(USEFUL_SUCCESS_BONUS_MS),
            MAX_USEFUL_SUCCESS_BONUS_MS,
        );
        let fail_penalty = match self.consecutive_failures {
            0 => 0,
            1 => 100,
            2 => 250,
            _ => 1000,
        } + self.failure_count.saturating_mul(20);

        base.saturating_sub(useful_bonus).saturating_add(fail_penalty)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RankedDomainSummary {
    pub domain: String,
    pub probe_rtt_ms: Option<u64>,
    pub useful_success_count: u64,
    pub failure_count: u64,
    pub consecutive_failures: u32,
    pub score: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TargetRouteStatus {
    pub dc_id: i32,
    pub is_media: bool,
    pub active_domain: Option<String>,
    pub rankings: Vec<RankedDomainSummary>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DomainBalancerStatus {
    pub current_network_generation: u64,
    pub routes: Vec<TargetRouteStatus>,
}

pub fn normalize_domain(domain: &str) -> String {
    let s = domain.trim();
    if let Some(pos) = s.find("://") {
        return normalize_domain(&s[pos + 3..]);
    }
    let host = s.split('/').next().unwrap_or(s);
    let host = host.split(':').next().unwrap_or(host);
    if let Some(rest) = host.strip_prefix("kws") {
        if let Some(dot_idx) = rest.find('.') {
            if rest[..dot_idx].chars().all(|c| c.is_ascii_digit()) {
                return rest[dot_idx + 1..].to_ascii_lowercase();
            }
        }
    }
    host.to_ascii_lowercase()
}

pub struct Balancer {
    domains: Vec<String>,
    current_generation: u64,
    // (network_generation, DcTargetKey) -> domain -> DomainStats
    stats: HashMap<(u64, DcTargetKey), HashMap<String, DomainStats>>,
    // (network_generation, DcTargetKey) -> active_domain
    active_domains: HashMap<(u64, DcTargetKey), String>,
}

pub static BALANCER: Lazy<RwLock<Balancer>> = Lazy::new(|| RwLock::new(Balancer::new()));

impl Balancer {
    pub fn new() -> Self {
        Self {
            domains: Vec::new(),
            current_generation: 1,
            stats: HashMap::new(),
            active_domains: HashMap::new(),
        }
    }

    pub fn current_generation(&self) -> u64 {
        self.current_generation
    }

    pub fn set_network_generation(&mut self, gen: u64) {
        self.notify_generation_change(gen);
    }

    /// When network generation changes (e.g. Wi-Fi <-> Cellular handover or operator switch),
    /// the domain balancer transitions to the new generation.
    /// Stale winners from previous operators/networks are NOT inherited.
    pub fn notify_generation_change(&mut self, new_generation: u64) {
        if new_generation == 0 || new_generation == self.current_generation {
            return;
        }
        self.current_generation = new_generation;
        // Prune older generations to avoid memory leakage and ensure isolation
        self.stats.retain(|(gen, _), _| *gen == new_generation);
        self.active_domains
            .retain(|(gen, _), _| *gen == new_generation);
    }

    pub fn update_domains_list(&mut self, domains_list: &[String]) {
        let mut normalized: Vec<String> = domains_list
            .iter()
            .map(|d| normalize_domain(d))
            .filter(|d| !d.is_empty())
            .collect();
        normalized.sort();
        normalized.dedup();

        let mut current_sorted = self.domains.clone();
        current_sorted.sort();

        if current_sorted == normalized {
            return;
        }

        self.domains = normalized;
    }

    /// Update probe ranking specifically for `(dc_id, is_media)` on the current generation.
    pub fn update_ranked_domains_for_dc(
        &mut self,
        dc_id: i32,
        is_media: bool,
        ranked: Vec<(String, u64)>,
    ) {
        if ranked.is_empty() {
            return;
        }
        let gen = self.current_generation;
        let target_key = DcTargetKey::new(dc_id, is_media);

        for (domain_raw, latency_ms) in ranked {
            let domain = normalize_domain(&domain_raw);
            if domain.is_empty() {
                continue;
            }
            let map = self.stats.entry((gen, target_key)).or_default();
            let entry = map
                .entry(domain.clone())
                .or_insert_with(|| DomainStats::new(domain));

            match entry.probe_rtt_ms {
                Some(old_lat) => {
                    entry.probe_rtt_ms = Some((old_lat * 7 + latency_ms * 3) / 10);
                }
                None => {
                    entry.probe_rtt_ms = Some(latency_ms);
                }
            }
        }

        self.recompute_active_domain_for_target(gen, target_key);
    }

    /// Backwards-compatible helper: if general ranking is passed, update DC2 chat
    pub fn update_ranked_domains(&mut self, ranked: Vec<(String, u64)>) {
        self.update_ranked_domains_for_dc(2, false, ranked);
    }

    pub fn update_domain_for_dc(&mut self, dc_id: i32, is_media: bool, domain: &str) -> bool {
        let domain_norm = normalize_domain(domain);
        if domain_norm.is_empty() {
            return false;
        }
        let gen = self.current_generation;
        let target_key = DcTargetKey::new(dc_id, is_media);

        let current = self.active_domains.get(&(gen, target_key));
        if current.map(|s| s.as_str()) == Some(&domain_norm) {
            return false;
        }
        self.active_domains
            .insert((gen, target_key), domain_norm.clone());

        // Ensure stats entry exists
        let map = self.stats.entry((gen, target_key)).or_default();
        map.entry(domain_norm.clone())
            .or_insert_with(|| DomainStats::new(domain_norm));

        true
    }

    pub fn get_active_domain_for_dc(&self, dc_id: i32, is_media: bool) -> Option<String> {
        let gen = self.current_generation;
        let target_key = DcTargetKey::new(dc_id, is_media);
        self.active_domains
            .get(&(gen, target_key))
            .filter(|s| !s.is_empty())
            .cloned()
    }

    /// Returns the fastest domain specifically for this `(dc_id, is_media)` on the current generation.
    /// MOB-026: Does NOT fall back to DC2! Each DC and media class is strictly isolated.
    pub fn get_fastest_domain_for_dc(&self, dc_id: i32, is_media: bool) -> Option<String> {
        let gen = self.current_generation;
        let target_key = DcTargetKey::new(dc_id, is_media);

        if let Some(active) = self.active_domains.get(&(gen, target_key)) {
            if !active.is_empty() {
                return Some(active.clone());
            }
        }

        if let Some(stats_map) = self.stats.get(&(gen, target_key)) {
            let mut scored: Vec<(&String, u64)> = stats_map
                .iter()
                .map(|(d, s)| (d, s.calculate_score()))
                .collect();
            scored.sort_by_key(|(_, score)| *score);
            if let Some((best, _)) = scored.first() {
                return Some((*best).clone());
            }
        }

        None
    }

    pub fn get_fastest_domain(&self) -> Option<String> {
        self.get_fastest_domain_for_dc(2, false)
    }

    /// Returns domains ordered by composite score for this specific `(dc_id, is_media)` on the current generation.
    /// MOB-026: Does NOT fall back to DC2!
    pub fn get_domains_for_dc(&self, dc_id: i32, is_media: bool) -> Vec<String> {
        let mut result = Vec::new();
        let mut seen = std::collections::HashSet::new();

        let gen = self.current_generation;
        let target_key = DcTargetKey::new(dc_id, is_media);

        // 1. Current active domain for this specific (network_generation, target_key)
        if let Some(active) = self.active_domains.get(&(gen, target_key)) {
            if !active.is_empty() {
                result.push(active.clone());
                seen.insert(active.clone());
            }
        }

        // 2. Ranked domains specifically for this (gen, target_key) sorted by effective score
        if let Some(stats_map) = self.stats.get(&(gen, target_key)) {
            let mut scored: Vec<(&String, u64)> = stats_map
                .iter()
                .map(|(d, s)| (d, s.calculate_score()))
                .collect();
            scored.sort_by_key(|(_, score)| *score);
            for (d, _) in scored {
                if !seen.contains(d) {
                    result.push(d.clone());
                    seen.insert(d.clone());
                }
            }
        }

        // 3. Fallback to remaining unranked configured domains (shuffled for load spread)
        let mut remaining: Vec<String> = self
            .domains
            .iter()
            .map(|d| normalize_domain(d))
            .filter(|d| !seen.contains(d))
            .collect();
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

    pub fn record_probe_rtt(
        &mut self,
        gen: u64,
        dc_id: i32,
        is_media: bool,
        domain: &str,
        latency_ms: u64,
    ) {
        let domain_norm = normalize_domain(domain);
        if domain_norm.is_empty() {
            return;
        }
        if gen != 0 && gen < self.current_generation {
            return; // Stale network event from prior generation
        }
        if gen > self.current_generation {
            self.notify_generation_change(gen);
        }

        let effective_gen = self.current_generation;
        let target_key = DcTargetKey::new(dc_id, is_media);

        let map = self.stats.entry((effective_gen, target_key)).or_default();
        let entry = map
            .entry(domain_norm.clone())
            .or_insert_with(|| DomainStats::new(domain_norm));

        match entry.probe_rtt_ms {
            Some(old_lat) => {
                entry.probe_rtt_ms = Some((old_lat * 7 + latency_ms * 3) / 10);
            }
            None => {
                entry.probe_rtt_ms = Some(latency_ms);
            }
        }

        self.recompute_active_domain_for_target(effective_gen, target_key);
    }

    /// Record verified useful RX on downstream bridge connection.
    /// Proves this domain successfully established end-to-end transport and received Telegram data.
    pub fn record_useful_success(
        &mut self,
        gen: u64,
        dc_id: i32,
        is_media: bool,
        domain: &str,
    ) {
        let domain_norm = normalize_domain(domain);
        if domain_norm.is_empty() {
            return;
        }
        if gen != 0 && gen < self.current_generation {
            return;
        }
        if gen > self.current_generation {
            self.notify_generation_change(gen);
        }

        let effective_gen = self.current_generation;
        let target_key = DcTargetKey::new(dc_id, is_media);

        let map = self.stats.entry((effective_gen, target_key)).or_default();
        let entry = map
            .entry(domain_norm.clone())
            .or_insert_with(|| DomainStats::new(domain_norm.clone()));

        entry.useful_success_count = entry.useful_success_count.saturating_add(1);
        entry.consecutive_failures = 0;

        self.recompute_active_domain_for_target(effective_gen, target_key);
    }

    /// Record connection failure, handshake timeout, or connection reset.
    pub fn record_failure(
        &mut self,
        gen: u64,
        dc_id: i32,
        is_media: bool,
        domain: &str,
    ) {
        let domain_norm = normalize_domain(domain);
        if domain_norm.is_empty() {
            return;
        }
        if gen != 0 && gen < self.current_generation {
            return;
        }
        if gen > self.current_generation {
            self.notify_generation_change(gen);
        }

        let effective_gen = self.current_generation;
        let target_key = DcTargetKey::new(dc_id, is_media);

        let map = self.stats.entry((effective_gen, target_key)).or_default();
        let entry = map
            .entry(domain_norm.clone())
            .or_insert_with(|| DomainStats::new(domain_norm));

        entry.failure_count = entry.failure_count.saturating_add(1);
        entry.consecutive_failures = entry.consecutive_failures.saturating_add(1);

        self.recompute_active_domain_for_target(effective_gen, target_key);
    }

    pub fn reset_ranking(&mut self) {
        self.stats.clear();
        self.active_domains.clear();
    }

    fn recompute_active_domain_for_target(&mut self, gen: u64, target_key: DcTargetKey) {
        let stats_map = match self.stats.get(&(gen, target_key)) {
            Some(m) if !m.is_empty() => m,
            _ => return,
        };

        let mut scored: Vec<(&String, u64)> = stats_map
            .iter()
            .map(|(d, s)| (d, s.calculate_score()))
            .collect();
        scored.sort_by_key(|(_, score)| *score);

        let (best_domain, best_score) = match scored.first() {
            Some((d, s)) => ((*d).clone(), *s),
            None => return,
        };

        let current_active = self.active_domains.get(&(gen, target_key)).cloned();
        let should_switch = match current_active {
            Some(ref cur) if !cur.is_empty() => match stats_map.get(cur) {
                Some(cur_stats) => {
                    if cur_stats.consecutive_failures > 0 {
                        // Current active domain experienced failure: failover immediately
                        true
                    } else {
                        let cur_score = cur_stats.calculate_score();
                        // Stability-First Hysteresis: only switch if the new winner is significantly better
                        cur_score > best_score + STABILITY_HYSTERESIS_MS
                    }
                }
                None => true,
            },
            _ => true,
        };

        if should_switch {
            self.active_domains.insert((gen, target_key), best_domain);
        }
    }

    pub fn get_status_json(&self) -> String {
        let gen = self.current_generation;
        let mut routes = Vec::new();

        let mut targets: Vec<DcTargetKey> = self
            .stats
            .keys()
            .filter_map(|(g, k)| if *g == gen { Some(*k) } else { None })
            .collect();

        for (g, k) in self.active_domains.keys() {
            if *g == gen && !targets.contains(k) {
                targets.push(*k);
            }
        }
        targets.sort_by_key(|k| (k.dc_id, k.is_media));

        for target in targets {
            let active_domain = self.active_domains.get(&(gen, target)).cloned();
            let mut rankings = Vec::new();
            if let Some(stats_map) = self.stats.get(&(gen, target)) {
                let mut list: Vec<&DomainStats> = stats_map.values().collect();
                list.sort_by_key(|s| s.calculate_score());
                for s in list {
                    rankings.push(RankedDomainSummary {
                        domain: s.domain.clone(),
                        probe_rtt_ms: s.probe_rtt_ms,
                        useful_success_count: s.useful_success_count,
                        failure_count: s.failure_count,
                        consecutive_failures: s.consecutive_failures,
                        score: s.calculate_score(),
                    });
                }
            }
            routes.push(TargetRouteStatus {
                dc_id: target.dc_id,
                is_media: target.is_media,
                active_domain,
                rankings,
            });
        }

        let status = DomainBalancerStatus {
            current_network_generation: gen,
            routes,
        };

        serde_json::to_string(&status).unwrap_or_else(|_| "{}".to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_domain_normalization() {
        assert_eq!(normalize_domain("kws2.worker1.dev"), "worker1.dev");
        assert_eq!(normalize_domain("kws4.worker2.dev:443"), "worker2.dev");
        assert_eq!(
            normalize_domain("https://kws203.edge.worker.dev/apiws"),
            "edge.worker.dev"
        );
        assert_eq!(normalize_domain("worker3.dev"), "worker3.dev");
    }

    #[test]
    fn promoted_anycast_candidate_is_prioritized_for_its_dc_only() {
        let mut balancer = Balancer::new();
        balancer.update_domains_list(&["fallback.example".to_string()]);

        assert!(balancer.update_domain_for_dc(2, false, "kws2.fast.example:443"));

        assert_eq!(
            balancer.get_domains_for_dc(2, false).first().map(String::as_str),
            Some("fast.example")
        );
        assert!(balancer.get_domains_for_dc(4, false).is_empty());
    }

    #[test]
    fn test_balancer_per_dc_isolation_no_leakage_from_dc2() {
        let mut b = Balancer::new();
        let domains = vec![
            "worker1.dev".to_string(),
            "worker2.dev".to_string(),
            "worker3.dev".to_string(),
        ];
        b.update_domains_list(&domains);

        // Rank DC2: worker1 is fastest (30ms)
        b.update_ranked_domains_for_dc(
            2,
            false,
            vec![
                ("worker1.dev".to_string(), 30),
                ("worker2.dev".to_string(), 100),
            ],
        );

        assert_eq!(
            b.get_fastest_domain_for_dc(2, false).as_deref(),
            Some("worker1.dev")
        );

        // MOB-026: DC4 is unprobed. It MUST NOT inherit worker1 from DC2!
        assert_eq!(b.get_fastest_domain_for_dc(4, false), None);

        // Rank DC4 explicitly: worker3 is fastest (20ms)
        b.update_ranked_domains_for_dc(
            4,
            false,
            vec![
                ("worker3.dev".to_string(), 20),
                ("worker2.dev".to_string(), 80),
            ],
        );

        assert_eq!(
            b.get_fastest_domain_for_dc(4, false).as_deref(),
            Some("worker3.dev")
        );
        // DC2 winner is still worker1.dev
        assert_eq!(
            b.get_fastest_domain_for_dc(2, false).as_deref(),
            Some("worker1.dev")
        );
    }

    #[test]
    fn test_balancer_media_class_separation() {
        let mut b = Balancer::new();
        let domains = vec!["worker1.dev".to_string(), "worker2.dev".to_string()];
        b.update_domains_list(&domains);

        // DC2 Chat (is_media = false): worker1 is fastest
        b.update_ranked_domains_for_dc(
            2,
            false,
            vec![
                ("worker1.dev".to_string(), 40),
                ("worker2.dev".to_string(), 120),
            ],
        );

        // DC2 Media (is_media = true): worker2 is fastest
        b.update_ranked_domains_for_dc(
            2,
            true,
            vec![
                ("worker2.dev".to_string(), 25),
                ("worker1.dev".to_string(), 150),
            ],
        );

        // Chat uses worker1, Media uses worker2!
        assert_eq!(
            b.get_fastest_domain_for_dc(2, false).as_deref(),
            Some("worker1.dev")
        );
        assert_eq!(
            b.get_fastest_domain_for_dc(2, true).as_deref(),
            Some("worker2.dev")
        );
    }

    #[test]
    fn test_balancer_network_generation_change_does_not_inherit_bad_winner() {
        let mut b = Balancer::new();
        b.set_network_generation(1);

        // On generation 1: worker_bad was assigned/active on DC2
        b.update_domain_for_dc(2, false, "worker_bad.dev");
        assert_eq!(
            b.get_active_domain_for_dc(2, false).as_deref(),
            Some("worker_bad.dev")
        );

        // Network handover (generation 1 -> generation 2)
        b.set_network_generation(2);

        // On generation 2: bad winner from generation 1 is NOT inherited!
        assert_eq!(b.get_active_domain_for_dc(2, false), None);
        assert_eq!(b.get_fastest_domain_for_dc(2, false), None);
    }

    #[test]
    fn test_balancer_useful_success_improves_score_and_overtakes_probe() {
        let mut b = Balancer::new();
        let gen = 1;
        b.set_network_generation(gen);

        // workerA has 60ms probe RTT, 0 useful successes -> score = 60
        b.record_probe_rtt(gen, 2, false, "workerA.dev", 60);

        // workerB has 80ms probe RTT
        b.record_probe_rtt(gen, 2, false, "workerB.dev", 80);

        // Initially workerA is active (60ms vs 80ms)
        assert_eq!(
            b.get_fastest_domain_for_dc(2, false).as_deref(),
            Some("workerA.dev")
        );

        // Now workerB achieves multiple confirmed useful RX (4 * 15 = 60ms bonus)
        // workerB score becomes: 80 - 60 = 20ms
        for _ in 0..4 {
            b.record_useful_success(gen, 2, false, "workerB.dev");
        }

        // workerB (score 20) now beats workerA (score 60) by 40ms (> hysteresis threshold when combined)
        // If workerB has useful success and lower score, workerB overtakes workerA
        // 60 > 20 + 60 is false (40 <= 60), but with 1 more useful success or failure on A:
        b.record_useful_success(gen, 2, false, "workerB.dev");

        // If workerA experiences a single failure:
        b.record_failure(gen, 2, false, "workerA.dev");

        // workerA score jumped from 60 to 60 + 100 + 20 = 180!
        // workerB score is 20. Failover happens immediately!
        assert_eq!(
            b.get_fastest_domain_for_dc(2, false).as_deref(),
            Some("workerB.dev")
        );
    }

    #[test]
    fn test_balancer_status_json() {
        let mut b = Balancer::new();
        b.set_network_generation(3);
        b.record_probe_rtt(3, 2, false, "worker1.dev", 45);
        b.record_useful_success(3, 2, false, "worker1.dev");
        b.record_probe_rtt(3, 4, true, "worker2.dev", 25);

        let json = b.get_status_json();
        assert!(json.contains("\"current_network_generation\":3"));
        assert!(json.contains("\"dc_id\":2"));
        assert!(json.contains("\"is_media\":false"));
        assert!(json.contains("\"dc_id\":4"));
        assert!(json.contains("\"is_media\":true"));
        assert!(json.contains("\"worker1.dev\""));
        assert!(json.contains("\"worker2.dev\""));
    }
}
