use once_cell::sync::Lazy;
use parking_lot::RwLock;
use rand::seq::SliceRandom;
use std::collections::HashMap;

pub struct Balancer {
    domains: Vec<String>,
    ranked_domains: Vec<(String, u64)>, // (domain, latency_ms) sorted by speed
    dc_to_domain: HashMap<i32, String>,
}

pub static BALANCER: Lazy<RwLock<Balancer>> = Lazy::new(|| RwLock::new(Balancer::new()));

impl Balancer {
    pub fn new() -> Self {
        Self {
            domains: Vec::new(),
            ranked_domains: Vec::new(),
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
        if self.ranked_domains.is_empty() {
            let mut rng = rand::thread_rng();
            self.dc_to_domain.clear();
            for dc_id in [1, 2, 3, 4, 5, 203] {
                if let Some(domain) = self.domains.choose(&mut rng) {
                    self.dc_to_domain.insert(dc_id, domain.clone());
                }
            }
        }
    }

    pub fn update_ranked_domains(&mut self, mut ranked: Vec<(String, u64)>) {
        if ranked.is_empty() {
            return;
        }
        // Sort by lowest latency ms
        ranked.sort_by_key(|(_, latency)| *latency);
        self.ranked_domains = ranked;

        // Automatically set the top-1 fastest domain as primary for all DCs
        if let Some((best_domain, _)) = self.ranked_domains.first() {
            for dc_id in [1, 2, 3, 4, 5, 203] {
                self.dc_to_domain.insert(dc_id, best_domain.clone());
            }
        }
    }

    pub fn update_domain_for_dc(&mut self, dc_id: i32, domain: &str) -> bool {
        if self.dc_to_domain.get(&dc_id).map(|s| s.as_str()) == Some(domain) {
            return false;
        }
        self.dc_to_domain.insert(dc_id, domain.to_string());
        true
    }

    pub fn get_fastest_domain(&self) -> Option<String> {
        self.ranked_domains.first().map(|(d, _)| d.clone())
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

        // 2. Ranked domains in order of lowest latency
        for (d, _) in &self.ranked_domains {
            if !seen.contains(d) {
                result.push(d.clone());
                seen.insert(d.clone());
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
}
