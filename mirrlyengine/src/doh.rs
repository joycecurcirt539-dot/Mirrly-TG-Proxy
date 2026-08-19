use parking_lot::RwLock;
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

/// Cloudflare Edge Anycast IPs for direct IP fallback when system DNS is poisoned or unavailable.
const CF_ANYCAST_IPS: &[IpAddr] = &[
    IpAddr::V4(Ipv4Addr::new(188, 114, 96, 1)),
    IpAddr::V4(Ipv4Addr::new(188, 114, 97, 1)),
    IpAddr::V4(Ipv4Addr::new(172, 67, 153, 159)),
    IpAddr::V4(Ipv4Addr::new(172, 67, 74, 152)),
    IpAddr::V4(Ipv4Addr::new(104, 21, 234, 180)),
    IpAddr::V4(Ipv4Addr::new(162, 159, 153, 4)),
];

struct CacheEntry {
    ip: IpAddr,
    expires_at: Instant,
}

pub struct DohResolver {
    cache: RwLock<HashMap<String, CacheEntry>>,
    cf_ip_idx: AtomicUsize,
}

impl DohResolver {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            cache: RwLock::new(HashMap::new()),
            cf_ip_idx: AtomicUsize::new(0),
        })
    }

    pub async fn resolve(&self, domain: &str) -> Option<IpAddr> {
        let domain = domain.trim();
        if domain.is_empty() {
            return None;
        }

        // 1. Direct IP check (e.g. if an IP is specified directly in lists or configs)
        if let Ok(ip) = domain.parse::<IpAddr>() {
            return Some(ip);
        }

        // 2. In-memory cache hit (0 ms)
        {
            let cache = self.cache.read();
            if let Some(entry) = cache.get(domain) {
                if Instant::now() < entry.expires_at {
                    return Some(entry.ip);
                }
            }
        }

        // 3. System DNS lookup (Fast non-blocking with 350ms failover)
        let system_lookup = tokio::net::lookup_host(format!("{}:443", domain));
        if let Ok(Ok(mut addrs)) = tokio::time::timeout(Duration::from_millis(350), system_lookup).await {
            if let Some(socket_addr) = addrs.next() {
                let ip = socket_addr.ip();
                let mut cache = self.cache.write();
                cache.insert(
                    domain.to_string(),
                    CacheEntry {
                        ip,
                        expires_at: Instant::now() + Duration::from_secs(300),
                    },
                );
                return Some(ip);
            }
        }

        // 4. Direct IP mapping for Telegram WebSockets gateways (*.web.telegram.org)
        if domain.ends_with(".web.telegram.org") {
            let tg_ip = if domain.contains("pluto") || domain.contains("kws1") {
                IpAddr::V4(Ipv4Addr::new(149, 154, 175, 50))
            } else if domain.contains("venus") || domain.contains("kws2") {
                IpAddr::V4(Ipv4Addr::new(149, 154, 167, 51))
            } else if domain.contains("aurora") || domain.contains("kws3") {
                IpAddr::V4(Ipv4Addr::new(149, 154, 175, 100))
            } else if domain.contains("vesta") || domain.contains("kws4") {
                IpAddr::V4(Ipv4Addr::new(149, 154, 167, 91))
            } else if domain.contains("flora") || domain.contains("kws5") {
                IpAddr::V4(Ipv4Addr::new(91, 108, 56, 130))
            } else {
                IpAddr::V4(Ipv4Addr::new(149, 154, 167, 51))
            };

            let mut cache = self.cache.write();
            cache.insert(
                domain.to_string(),
                CacheEntry {
                    ip: tg_ip,
                    expires_at: Instant::now() + Duration::from_secs(300),
                },
            );
            return Some(tg_ip);
        }

        let idx = self.cf_ip_idx.fetch_add(1, Ordering::Relaxed) % CF_ANYCAST_IPS.len();
        let fallback_ip = CF_ANYCAST_IPS[idx];

        let mut cache = self.cache.write();
        cache.insert(
            domain.to_string(),
            CacheEntry {
                ip: fallback_ip,
                expires_at: Instant::now() + Duration::from_secs(60),
            },
        );

        Some(fallback_ip)
    }

    pub async fn resolve_socket_addr(&self, domain: &str, port: u16) -> Option<SocketAddr> {
        let ip = self.resolve(domain).await?;
        Some(SocketAddr::new(ip, port))
    }

    pub fn clear_cache(&self) {
        let mut cache = self.cache.write();
        cache.clear();
    }
}
