use parking_lot::RwLock;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;

#[derive(Clone, Debug)]
pub struct EngineConfig {
    pub pool_size: usize,
    pub cache_dir: Option<String>,
    pub cf_enabled: bool,
    pub cf_priority: bool,
    pub cf_user_domain: String,
    pub secret: String,
    pub verbose: bool,
    pub dc_ips: HashMap<i16, SocketAddr>,
}

impl Default for EngineConfig {
    fn default() -> Self {
        Self {
            pool_size: 4,
            cache_dir: None,
            cf_enabled: false,
            cf_priority: false,
            cf_user_domain: String::new(),
            secret: String::new(),
            verbose: false,
            dc_ips: HashMap::new(),
        }
    }
}

pub struct ConfigManager {
    inner: RwLock<EngineConfig>,
}

impl ConfigManager {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inner: RwLock::new(EngineConfig::default()),
        })
    }

    pub fn get(&self) -> EngineConfig {
        self.inner.read().clone()
    }

    pub fn set_pool_size(&self, size: usize) {
        let mut cfg = self.inner.write();
        cfg.pool_size = size.clamp(2, 16);
    }

    pub fn set_cache_dir(&self, dir: String) {
        let mut cfg = self.inner.write();
        cfg.cache_dir = if dir.trim().is_empty() {
            None
        } else {
            Some(dir.trim().to_string())
        };
    }

    pub fn set_cf_config(&self, enabled: bool, priority: bool, user_domain: String) {
        let mut cfg = self.inner.write();
        cfg.cf_enabled = enabled;
        cfg.cf_priority = priority;
        cfg.cf_user_domain = user_domain.trim().to_string();
    }

    pub fn set_secret(&self, secret: String) {
        let mut cfg = self.inner.write();
        cfg.secret = secret.trim().to_string();
    }

    pub fn get_secret(&self) -> String {
        self.inner.read().secret.clone()
    }

    pub fn set_dc_ips(&self, dc_ips_map: HashMap<i16, SocketAddr>) {
        let mut cfg = self.inner.write();
        cfg.dc_ips = dc_ips_map;
    }

    pub fn set_verbose(&self, verbose: bool) {
        let mut cfg = self.inner.write();
        cfg.verbose = verbose;
    }
}
