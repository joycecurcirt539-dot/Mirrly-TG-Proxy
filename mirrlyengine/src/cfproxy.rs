use crate::config::ConfigManager;
use crate::dc::get_ws_domains;
use crate::doh::DohResolver;
use crate::logging::{log_error, log_info};
use parking_lot::RwLock;
use rand::seq::SliceRandom;
use std::fs;
use std::net::SocketAddr;
use std::path::Path;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

pub const UPSTREAM_DOMAINS_URL: &str =
    "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt";

pub const DEFAULT_EMBEDDED_DOMAINS: &[&str] = &[
    "virkgj.com",
    "vmmzovy.com",
    "mkuosckvso.com",
    "twdmbzcm.com",
    "awzwsldi.com",
    "clngqrflngqin.com",
    "tjacxbqtj.com",
    "bxaxtxmrw.com",
    "dmohrsgmohcrwb.com",
    "vwbmtmoi.com",
    "khgrre.com",
    "ulihssf.com",
    "tmhqsdqmfpmk.com",
    "xwuwoqbm.com",
    "zaewayzmplad.com",
    "orgcnunpj.com",
    "zhkuldz.com",
    "zypoljnslxa.com",
    "efabnxaowuzs.com",
    "zaftuzsftqdq.com",
];

pub struct CfManager {
    config: Arc<ConfigManager>,
    doh: Arc<DohResolver>,
    domains: RwLock<Vec<String>>,
    cache_dir: RwLock<Option<String>>,
    domain_idx: AtomicUsize,
}

impl CfManager {
    pub fn new(config: Arc<ConfigManager>, doh: Arc<DohResolver>) -> Arc<Self> {
        let mut initial_domains: Vec<String> = DEFAULT_EMBEDDED_DOMAINS
            .iter()
            .map(|s| s.to_string())
            .collect();
        initial_domains.shuffle(&mut rand::thread_rng());

        Arc::new(Self {
            config,
            doh,
            domains: RwLock::new(initial_domains),
            cache_dir: RwLock::new(None),
            domain_idx: AtomicUsize::new(0),
        })
    }

    pub fn is_worker_or_user_domain(&self, domain: &str) -> bool {
        if domain.contains("workers.dev") {
            return true;
        }
        let user_dom = self.config.get().cf_user_domain;
        !user_dom.is_empty() && domain == user_dom
    }

    pub fn set_cache_dir(&self, dir: &str) {
        let mut lock = self.cache_dir.write();
        *lock = Some(dir.to_string());

        // Load cached domains from disk if available
        let loaded = load_cached_domains(Some(dir));
        if !loaded.is_empty() {
            let mut dom_lock = self.domains.write();
            *dom_lock = loaded;
        }
    }

    pub fn get_domains(&self) -> Vec<String> {
        let cfg = self.config.get();
        if !cfg.cf_user_domain.is_empty() {
            return vec![cfg.cf_user_domain];
        }

        let dom = self.domains.read().clone();
        if !dom.is_empty() {
            dom
        } else {
            DEFAULT_EMBEDDED_DOMAINS
                .iter()
                .map(|s| s.to_string())
                .collect()
        }
    }

    /// Selects a domain for WebSocket tunneling.
    /// Priority:
    /// 1. User custom domain (if configured)
    /// 2. Round-robin from Cloudflare reverse-proxy edge domains list
    /// 3. Direct Telegram WS fallback (venus.web.telegram.org)
    pub fn select_domain(&self) -> String {
        let cfg = self.config.get();
        if !cfg.cf_user_domain.is_empty() {
            return cfg.cf_user_domain;
        }

        let doms = self.domains.read();
        if doms.is_empty() {
            return "venus.web.telegram.org".to_string();
        }

        let idx = self.domain_idx.fetch_add(1, Ordering::Relaxed) % doms.len();
        doms[idx].clone()
    }

    /// Returns the complete list of target candidate domains for a given DC.
    /// Order: user custom domain > CF CDN domains (bypass DPI) > native Telegram gateways (fallback).
    /// CF domains are prioritized because native Telegram domains are blocked by DPI in restricted regions.
    pub fn get_candidate_domains(&self, dc_id: i16, is_media: bool) -> Vec<String> {
        let cfg = self.config.get();
        let mut candidates = Vec::new();

        // 1. User custom domain has absolute priority if specified
        if !cfg.cf_user_domain.is_empty() {
            candidates.push(cfg.cf_user_domain.clone());
        }

        // 2. Default Cloudflare Worker (active TCP tunnel via cloudflare:sockets)
        if cfg.cf_enabled {
            let default_worker = "mirrly-tg-proxy-worker.brawny-singer.workers.dev".to_string();
            if !candidates.contains(&default_worker) {
                candidates.push(default_worker);
            }
        }

        // 3. Cloudflare CDN fronting / proxy domains (bypass DPI in restricted regions)
        if cfg.cf_enabled {
            let doms = self.domains.read();
            let abs_dc = if dc_id.abs() == 203 { 2 } else { dc_id.abs() };
            let dc_num = if abs_dc == 0 || abs_dc > 5 { 2 } else { abs_dc };

            for d in doms.iter() {
                let kws_sub = if is_media {
                    format!("kws{}-1.{}", dc_num, d)
                } else {
                    format!("kws{}.{}", dc_num, d)
                };
                if !candidates.contains(&kws_sub) {
                    candidates.push(kws_sub);
                }
                if !candidates.contains(d) {
                    candidates.push(d.clone());
                }
            }
        }

        // 3. Native Telegram WebSockets domains as fallback (blocked by DPI in Russia/Iran/China)
        let native_domains = get_ws_domains(dc_id, is_media);
        for d in native_domains {
            if !candidates.contains(&d) {
                candidates.push(d);
            }
        }

        candidates
    }

    pub fn promote_domain(&self, winning_domain: &str) {
        let mut doms = self.domains.write();
        if let Some(pos) = doms.iter().position(|d| d == winning_domain) {
            if pos > 0 {
                let d = doms.remove(pos);
                doms.insert(0, d);
            }
        }
    }

    pub async fn resolve_target(&self, domain: &str) -> Option<SocketAddr> {
        if domain.is_empty() {
            return None;
        }
        self.doh.resolve_socket_addr(domain, 443).await
    }

    /// Background task to fetch the latest cfproxy-domains list from GitHub.
    pub async fn fetch_upstream_domains(self: Arc<Self>) {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(10))
            .user_agent("Mozilla/5.0 tg-ws-proxy-android")
            .danger_accept_invalid_certs(true)
            .build()
            .unwrap_or_default();

        match client.get(UPSTREAM_DOMAINS_URL).send().await {
            Ok(resp) if resp.status().is_success() => {
                if let Ok(text) = resp.text().await {
                    let new_domains: Vec<String> = text
                        .lines()
                        .map(|l| l.trim().to_string())
                        .filter(|l| !l.is_empty() && !l.starts_with('#'))
                        .collect();

                    if !new_domains.is_empty() {
                        log_info(
                            "mirrlyengine",
                            &format!(
                                "Successfully fetched {} upstream cfproxy domains",
                                new_domains.len()
                            ),
                        );
                        {
                            let mut lock = self.domains.write();
                            *lock = new_domains.clone();
                        }
                        let cache_dir = self.cache_dir.read().clone();
                        save_cached_domains(cache_dir.as_deref(), &new_domains);
                    }
                }
            }
            Ok(resp) => {
                log_error(
                    "mirrlyengine",
                    &format!("Upstream domains fetch returned status: {}", resp.status()),
                );
            }
            Err(e) => {
                log_info(
                    "mirrlyengine",
                    &format!("Using local/cached cfproxy domains: {:?}", e),
                );
            }
        }
    }
}

pub fn load_cached_domains(cache_dir: Option<&str>) -> Vec<String> {
    if let Some(dir) = cache_dir {
        let path = Path::new(dir).join("cfproxy-domains-cache.txt");
        if let Ok(content) = fs::read_to_string(path) {
            let domains: Vec<String> = content
                .lines()
                .map(|l| l.trim().to_string())
                .filter(|l| !l.is_empty() && !l.starts_with('#'))
                .collect();
            if !domains.is_empty() {
                return domains;
            }
        }
    }

    DEFAULT_EMBEDDED_DOMAINS
        .iter()
        .map(|s| s.to_string())
        .collect()
}

pub fn save_cached_domains(cache_dir: Option<&str>, domains: &[String]) {
    if let Some(dir) = cache_dir {
        let path = Path::new(dir).join("cfproxy-domains-cache.txt");
        let content = domains.join("\n");
        let _ = fs::write(path, content);
    }
}
