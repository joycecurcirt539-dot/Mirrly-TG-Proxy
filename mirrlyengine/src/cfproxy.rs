use crate::config::*;
use crate::ws::{
    is_http_status_error, ws_connect_happy_eyeballs, RawWebSocket,
    WsError,
};
use crate::{ldebug, lerror, linfo, lwarn};
use std::net::{IpAddr, SocketAddr};
use once_cell::sync::Lazy;
use std::path::PathBuf;
use std::time::{Duration, Instant};
use tokio_util::sync::CancellationToken;

// ---------------------------------------------------------------------------
// Domain decoding
// ---------------------------------------------------------------------------

pub fn decode_cf_domain(s: &str) -> String {
    let mut s_trim = s.trim();
    while s_trim.ends_with('.') {
        s_trim = &s_trim[..s_trim.len() - 1];
    }
    if !s_trim.to_ascii_lowercase().ends_with(".com") {
        return s_trim.to_string();
    }
    let suffix = ".co.uk";
    let p = &s_trim[..s_trim.len() - 4];
    let mut n = 0i32;
    for c in p.chars() {
        if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') {
            n += 1;
        }
    }
    let mut result: Vec<u8> = Vec::new();
    for &c in p.as_bytes() {
        if c >= b'a' && c <= b'z' {
            let v = (((c - b'a') as i32 - n % 26 + 26) % 26) as u8 + b'a';
            result.push(v);
        } else if c >= b'A' && c <= b'Z' {
            let v = (((c - b'A') as i32 - n % 26 + 26) % 26) as u8 + b'A';
            result.push(v);
        } else {
            result.push(c);
        }
    }
    let mut out = String::from_utf8_lossy(&result).to_string();
    out.push_str(suffix);
    out
}

pub fn normalize_cf_domain(s: &str) -> String {
    let mut s_trim = s.trim();
    if s_trim.is_empty() {
        return String::new();
    }
    while s_trim.ends_with('.') {
        s_trim = &s_trim[..s_trim.len() - 1];
    }
    s_trim.to_lowercase()
}

pub fn parse_cfproxy_domains(body: &str) -> Vec<String> {
    let mut domains = Vec::new();
    for line in body.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let decoded = decode_cf_domain(line);
        let d = normalize_cf_domain(&decoded);
        if !d.is_empty() {
            domains.push(d);
        }
    }
    domains
}

pub fn default_cfproxy_domains() -> Vec<String> {
    let mut domains = Vec::with_capacity(CFPROXY_ENC.len());
    for enc in CFPROXY_ENC {
        let decoded = decode_cf_domain(enc);
        let d = normalize_cf_domain(&decoded);
        if !d.is_empty() {
            domains.push(d);
        }
    }
    domains
}

pub fn merge_cfproxy_domains(lists: &[Vec<String>]) -> Vec<String> {
    let mut seen = std::collections::HashSet::new();
    let mut merged = Vec::new();
    for list in lists {
        for raw in list {
            let d = normalize_cf_domain(raw);
            if d.is_empty() || seen.contains(&d) {
                continue;
            }
            seen.insert(d.clone());
            merged.push(d);
        }
    }
    merged
}

// ---------------------------------------------------------------------------
// 429 cooldown logic
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct CfproxyRecoveryCircuitEntry {
    pub until: Instant,
    pub network_generation: u64,
    pub stage: crate::recovery::EstablishmentStage,
    pub reason: String,
    pub in_flight_trial: bool,
}

static CFPROXY_RECOVERY_CIRCUIT: Lazy<
    parking_lot::RwLock<std::collections::HashMap<String, CfproxyRecoveryCircuitEntry>>,
> = Lazy::new(|| parking_lot::RwLock::new(std::collections::HashMap::new()));

pub fn canonical_cfproxy_cooldown_key(domain: &str) -> String {
    let mut s = domain.trim();
    if let Some(stripped) = s.strip_prefix("mtproto:") {
        s = stripped.trim();
    } else if let Some(stripped) = s.strip_prefix("socks5:") {
        s = stripped.trim();
    }
    let normalized = normalize_cf_domain(s);
    if normalized.is_empty() {
        return String::new();
    }
    if let Some((prefix, base)) = normalized.split_once('.') {
        if prefix.starts_with("kws")
            && prefix.len() > 3
            && prefix[3..].chars().all(|character| character.is_ascii_digit() || character == '-')
        {
            return base.to_string();
        }
    }
    normalized
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ProtoScope {
    Mtproto,
    Socks5,
    Global,
}

impl ProtoScope {
    pub const fn prefix(self) -> &'static str {
        match self {
            ProtoScope::Mtproto => "mtproto:",
            ProtoScope::Socks5 => "socks5:",
            ProtoScope::Global => "",
        }
    }

    pub const fn as_str(self) -> &'static str {
        match self {
            ProtoScope::Mtproto => "mtproto",
            ProtoScope::Socks5 => "socks5",
            ProtoScope::Global => "global",
        }
    }
}

impl std::fmt::Display for ProtoScope {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.as_str())
    }
}

pub fn scoped_cfproxy_cooldown_key(domain: &str, scope: ProtoScope) -> String {
    let canonical = canonical_cfproxy_cooldown_key(domain);
    if canonical.is_empty() {
        return String::new();
    }
    match scope {
        ProtoScope::Global => canonical,
        ProtoScope::Mtproto | ProtoScope::Socks5 => format!("{}{}", scope.prefix(), canonical),
    }
}

// ---------------------------------------------------------------------------
// Isolated statistics tracking per protocol
// ---------------------------------------------------------------------------

static MTPROTO_429_HITS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
static SOCKS5_429_HITS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
static GLOBAL_429_HITS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

pub fn cfproxy_429_hits(scope: ProtoScope) -> u64 {
    match scope {
        ProtoScope::Mtproto => MTPROTO_429_HITS.load(std::sync::atomic::Ordering::Relaxed),
        ProtoScope::Socks5 => SOCKS5_429_HITS.load(std::sync::atomic::Ordering::Relaxed),
        ProtoScope::Global => GLOBAL_429_HITS.load(std::sync::atomic::Ordering::Relaxed),
    }
}

pub fn reset_cfproxy_429_hits(scope: ProtoScope) {
    match scope {
        ProtoScope::Mtproto => MTPROTO_429_HITS.store(0, std::sync::atomic::Ordering::Relaxed),
        ProtoScope::Socks5 => SOCKS5_429_HITS.store(0, std::sync::atomic::Ordering::Relaxed),
        ProtoScope::Global => GLOBAL_429_HITS.store(0, std::sync::atomic::Ordering::Relaxed),
    }
}

pub fn clear_all_cfproxy_429_hits() {
    MTPROTO_429_HITS.store(0, std::sync::atomic::Ordering::Relaxed);
    SOCKS5_429_HITS.store(0, std::sync::atomic::Ordering::Relaxed);
    GLOBAL_429_HITS.store(0, std::sync::atomic::Ordering::Relaxed);
}

pub fn cfproxy_429_strikes_scoped(domain: &str, scope: ProtoScope) -> i32 {
    let key = scoped_cfproxy_cooldown_key(domain, scope);
    if key.is_empty() {
        return 0;
    }
    let map = CFPROXY_429.read();
    map.get(&key)
        .filter(|state| state.until.map(|u| u > Instant::now()).unwrap_or(false))
        .map(|state| state.strikes)
        .unwrap_or(0)
}

pub fn cfproxy_429_strikes(domain: &str) -> i32 {
    let m = cfproxy_429_strikes_scoped(domain, ProtoScope::Mtproto);
    let s = cfproxy_429_strikes_scoped(domain, ProtoScope::Socks5);
    let g = cfproxy_429_strikes_scoped(domain, ProtoScope::Global);
    g.max(m).max(s)
}

pub fn cfproxy_429_active_count_scoped(scope: ProtoScope) -> usize {
    let now = Instant::now();
    let map = CFPROXY_429.read();
    let prefix = scope.prefix();
    map.iter()
        .filter(|(k, v)| {
            let matches_scope = match scope {
                ProtoScope::Global => !k.starts_with("mtproto:") && !k.starts_with("socks5:"),
                ProtoScope::Mtproto | ProtoScope::Socks5 => k.starts_with(prefix),
            };
            matches_scope && v.until.map(|u| u > now).unwrap_or(false)
        })
        .count()
}

pub fn cfproxy_429_active_count() -> usize {
    let now = Instant::now();
    let map = CFPROXY_429.read();
    map.values()
        .filter(|v| v.until.map(|u| u > now).unwrap_or(false))
        .count()
}

pub fn clear_cfproxy_429_cooldowns() {
    CFPROXY_429.write().clear();
    CFPROXY_RECOVERY_CIRCUIT.write().clear();
}

pub fn clear_cfproxy_429_cooldown_scoped(domain: &str, scope: ProtoScope) {
    let key = scoped_cfproxy_cooldown_key(domain, scope);
    if key.is_empty() {
        return;
    }
    CFPROXY_429.write().remove(&key);
}

pub fn clear_cfproxy_429_cooldown(domain: &str) {
    let d = canonical_cfproxy_cooldown_key(domain);
    if d.is_empty() {
        return;
    }
    let mut map = CFPROXY_429.write();
    map.remove(&d);
    map.remove(&format!("{}{}", ProtoScope::Mtproto.prefix(), d));
    map.remove(&format!("{}{}", ProtoScope::Socks5.prefix(), d));
    drop(map);
    CFPROXY_RECOVERY_CIRCUIT.write().remove(&d);
}

pub fn retry_after_delay(err: &WsError) -> Duration {
    let h = match err.handshake() {
        Some(h) => h,
        None => return Duration::ZERO,
    };
    let retry_after = h.headers.get("retry-after").map(|s| s.trim()).unwrap_or("");
    if retry_after.is_empty() {
        return Duration::ZERO;
    }
    if let Ok(seconds) = retry_after.parse::<i64>() {
        if seconds > 0 {
            return Duration::from_secs(seconds as u64);
        }
    }
    Duration::ZERO
}

pub fn next_cfproxy_429_cooldown_delay(prev: &Cfproxy429State, retry_after: Duration) -> Duration {
    if retry_after > Duration::ZERO {
        if retry_after > CFPROXY_429_MAX_COOLDOWN {
            return CFPROXY_429_MAX_COOLDOWN;
        }
        return retry_after;
    }
    let mut strikes = prev.strikes;
    let expired = prev
        .until
        .map(|until| until <= Instant::now())
        .unwrap_or(true);
    if expired {
        strikes = 0;
    }
    let mut delay = CFPROXY_429_COOLDOWN;
    for _ in 0..strikes {
        delay *= 2;
        if delay >= CFPROXY_429_MAX_COOLDOWN {
            return CFPROXY_429_MAX_COOLDOWN;
        }
    }
    if delay > CFPROXY_429_MAX_COOLDOWN {
        return CFPROXY_429_MAX_COOLDOWN;
    }
    delay
}

pub fn mark_cfproxy_429_cooldown_scoped(domain: &str, scope: ProtoScope, err: &WsError) {
    let key = scoped_cfproxy_cooldown_key(domain, scope);
    if key.is_empty() {
        return;
    }
    let d = canonical_cfproxy_cooldown_key(domain);
    let retry_after = retry_after_delay(err);
    let mut map = CFPROXY_429.write();
    let prev = map.get(&key).cloned().unwrap_or_default();
    let delay = next_cfproxy_429_cooldown_delay(&prev, retry_after);
    let mut strikes = prev.strikes + 1;
    let expired = prev
        .until
        .map(|until| until <= Instant::now())
        .unwrap_or(true);
    if expired {
        strikes = 1;
    }
    let new_until = Instant::now() + delay;
    let until = if expired {
        new_until
    } else {
        prev.until
            .map(|existing| existing.max(new_until))
            .unwrap_or(new_until)
    };
    map.insert(
        key.clone(),
        Cfproxy429State {
            until: Some(until),
            strikes,
        },
    );
    drop(map);

    match scope {
        ProtoScope::Mtproto => {
            MTPROTO_429_HITS.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            STATS.mtproto_429_cooldown_hits.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        }
        ProtoScope::Socks5 => {
            SOCKS5_429_HITS.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            STATS.socks5_429_cooldown_hits.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        }
        ProtoScope::Global => {
            GLOBAL_429_HITS.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        }
    }

    ldebug!(
        " CF cooldown [{}] {}: {:.0}s after 429 (strike {})",
        scope,
        d,
        delay.as_secs_f64().ceil(),
        strikes
    );
}

pub fn mark_cfproxy_429_cooldown(domain: &str, err: &WsError) {
    mark_cfproxy_429_cooldown_scoped(domain, ProtoScope::Global, err);
}

pub fn cfproxy_429_cooldown_remaining_scoped(domain: &str, scope: ProtoScope) -> Duration {
    let d = canonical_cfproxy_cooldown_key(domain);
    if d.is_empty() {
        return Duration::ZERO;
    }
    let now = Instant::now();
    let current_net = crate::generation_guard::current_network();

    // Check if domain is the user's primary custom worker.
    // User custom worker must NEVER be suppressed by path-level / recovery circuit breaks
    // caused by mobile network jitter, resets or timeouts. Only strict HTTP 429 applies to it.
    let is_custom_user_worker = {
        let user_d = CFPROXY.read().user_domain.trim().to_string();
        !user_d.is_empty() && canonical_cfproxy_cooldown_key(&user_d) == d
    };

    let recovery_remaining = if is_custom_user_worker {
        Duration::ZERO
    } else {
        CFPROXY_RECOVERY_CIRCUIT
            .read()
            .get(&d)
            .and_then(|entry| {
                // Path-specific TCP/TLS/Relay failures are scoped to network generation.
                // If the network generation has changed, the path failure does NOT block the new network!
                if current_net > 0 && entry.network_generation > 0 && current_net != entry.network_generation {
                    None
                } else {
                    Some(entry.until.saturating_duration_since(now))
                }
            })
            .unwrap_or(Duration::ZERO)
    };

    let key = scoped_cfproxy_cooldown_key(domain, scope);
    let mut map = CFPROXY_429.write();
    let until = match map.get(&key).and_then(|state| state.until) {
        Some(until) => until,
        None => return recovery_remaining,
    };
    if until <= now {
        map.remove(&key);
        return recovery_remaining;
    }
    // 429 is endpoint rate-limit: if until > now, it applies to this protocol scope
    (until - now).max(recovery_remaining)
}

pub fn cfproxy_429_cooldown_remaining(domain: &str) -> Duration {
    let g = cfproxy_429_cooldown_remaining_scoped(domain, ProtoScope::Global);
    let m = cfproxy_429_cooldown_remaining_scoped(domain, ProtoScope::Mtproto);
    let s = cfproxy_429_cooldown_remaining_scoped(domain, ProtoScope::Socks5);
    g.max(m).max(s)
}

pub fn try_acquire_cfproxy_half_open_trial(domain: &str, current_net: u64) -> bool {
    let d = canonical_cfproxy_cooldown_key(domain);
    if d.is_empty() {
        return true;
    }
    // 429 is global: if under 429 cooldown, trial is not permitted
    if cfproxy_429_cooldown_remaining(&d) > Duration::ZERO {
        return false;
    }
    let mut map = CFPROXY_RECOVERY_CIRCUIT.write();
    if let Some(entry) = map.get_mut(&d) {
        let now = Instant::now();
        let net_changed = current_net > 0 && entry.network_generation > 0 && current_net != entry.network_generation;
        let expired = now >= entry.until;

        if net_changed || expired {
            if entry.in_flight_trial {
                // "half-open допускает одну попытку, не толпу"
                return false;
            }
            entry.in_flight_trial = true;
            return true;
        }
        // Still open on current network
        return false;
    }
    true
}

pub fn release_cfproxy_half_open_trial(domain: &str) {
    let d = canonical_cfproxy_cooldown_key(domain);
    if !d.is_empty() {
        let mut map = CFPROXY_RECOVERY_CIRCUIT.write();
        if let Some(entry) = map.get_mut(&d) {
            entry.in_flight_trial = false;
        }
    }
}

pub type CfproxyAttemptPermit = crate::budget::DialPermit;

pub async fn acquire_cfproxy_attempt_slot() -> Option<CfproxyAttemptPermit> {
    crate::budget::DIAL_BUDGET
        .acquire(crate::budget::FlowCategory::UserFlow, None)
        .await
        .ok()
}

// ---------------------------------------------------------------------------
// Cache files
// ---------------------------------------------------------------------------

fn cfproxy_cache_path() -> Option<PathBuf> {
    let dir = CFPROXY.read().cache_dir.trim().to_string();
    if dir.is_empty() {
        return None;
    }
    Some(PathBuf::from(dir).join(CFPROXY_CACHE_FILE_NAME))
}

fn load_cfproxy_domains_from_cache() -> Vec<String> {
    let path = match cfproxy_cache_path() {
        Some(p) => p,
        None => return Vec::new(),
    };
    let data = match std::fs::read_to_string(&path) {
        Ok(d) => d,
        Err(_) => return Vec::new(),
    };
    let list: Vec<String> = data.split('\n').map(|s| s.to_string()).collect();
    merge_cfproxy_domains(&[list])
}

fn save_cfproxy_domains_to_cache(domains: &[String]) {
    let path = match cfproxy_cache_path() {
        Some(p) => p,
        None => return,
    };
    if domains.is_empty() {
        return;
    }
    if let Some(parent) = path.parent() {
        if let Err(e) = std::fs::create_dir_all(parent) {
            ldebug!(" CF: кеш создать не удалось: {}", e);
            return;
        }
    }
    let data = domains.join("\n");
    if let Err(e) = std::fs::write(&path, data) {
        ldebug!(" CF: кеш сохранить не удалось: {}", e);
    }
}

fn should_refresh_cfproxy_domains() -> bool {
    let path = match cfproxy_cache_path() {
        Some(p) => p,
        None => return true,
    };
    let meta = match std::fs::metadata(&path) {
        Ok(m) => m,
        Err(_) => return true,
    };
    let modified = match meta.modified() {
        Ok(t) => t,
        Err(_) => return true,
    };
    match modified.elapsed() {
        Ok(elapsed) => elapsed >= CFPROXY_REFRESH_INTERVAL,
        Err(_) => true,
    }
}

pub fn init_cfproxy_domains() {
    let defaults = default_cfproxy_domains();
    let cached = load_cfproxy_domains_from_cache();

    crate::generation_guard::change_config(|| {
        let mut cfg = CFPROXY.write();
        if !cached.is_empty() {
            let n = cached.len();
            cfg.domains = merge_cfproxy_domains(&[cached, defaults]);
            crate::balancer::BALANCER
                .write()
                .update_domains_list(&cfg.domains);
            linfo!(" CF: кеш доменов загружен ({} шт.)", n);
        } else {
            cfg.domains = defaults;
            crate::balancer::BALANCER
                .write()
                .update_domains_list(&cfg.domains);
        }
    });
}

pub fn start_cfproxy_refresh() {
    if !should_refresh_cfproxy_domains() {
        ldebug!(" CF: кеш свежий, пропускаю обновление списка");
        return;
    }
    tokio::spawn(async move {
        for _ in 0..3 {
            if try_refresh_cfproxy_domains().await {
                return;
            }
            tokio::time::sleep(Duration::from_secs(10)).await;
        }
        ldebug!(" CF: обновить список доменов не удалось, остаюсь на кеше/встроенном списке");
    });
}

static HTTP_CLIENT: Lazy<reqwest::Client> = Lazy::new(|| {
    let _ = rustls::crypto::ring::default_provider().install_default();
    reqwest::Client::builder()
        .timeout(Duration::from_secs(8))
        .build()
        .unwrap_or_default()
});

pub async fn try_refresh_cfproxy_domains() -> bool {
    let expected = crate::generation_guard::snapshot();
    let resp = match HTTP_CLIENT
        .get(CFPROXY_DOMAINS_URL)
        .header("User-Agent", "Mozilla/5.0 tg-ws-proxy-android")
        .send()
        .await
    {
        Ok(r) => r,
        Err(e) => {
            ldebug!(" CF: GitHub недоступен: {}", e);
            return false;
        }
    };
    if resp.status().as_u16() != 200 {
        ldebug!(" CF: GitHub вернул {}", resp.status().as_u16());
        return false;
    }
    let body = match resp.text().await {
        Ok(b) => b,
        Err(e) => {
            ldebug!(" CF: список доменов прочитать не удалось: {}", e);
            return false;
        }
    };

    let new_domains = parse_cfproxy_domains(&body);

    if !new_domains.is_empty() {
        let merged = merge_cfproxy_domains(&[new_domains.clone(), default_cfproxy_domains()]);
        return crate::generation_guard::change_config_if_current(expected, || {
            CFPROXY.write().domains = merged.clone();
            crate::balancer::BALANCER
                .write()
                .update_domains_list(&merged);
            save_cfproxy_domains_to_cache(&merged);
            linfo!(" CF: список доменов обновлен ({} шт.)", new_domains.len());
            true
        })
        .unwrap_or(false);
    }
    false
}

// ---------------------------------------------------------------------------
// Dual-Stack DNS Resolver (Cloudflare Anycast & System DNS)
// ---------------------------------------------------------------------------

pub const CF_ANYCAST_IPS_V4: &[&str] = &[
    "172.67.153.159",
    "172.67.74.152",
    "104.21.234.180",
    "104.16.248.249",
    "104.16.249.249",
    "162.159.153.4",
    "162.159.192.1",
    "141.101.90.0",
    "188.114.96.1",
    "188.114.97.1",
    "188.114.96.3",
    "188.114.97.3",
];

pub const CF_ANYCAST_IPS_V6: &[&str] = &[
    "2606:4700:4700::1111",
    "2606:4700:4700::1001",
    "2a06:98c1:3121::1",
    "2a06:98c1:3120::1",
    "2606:4700:3033::ac43:999f",
    "2606:4700:3037::6815:eab4",
];

pub fn default_cf_anycast_dual_stack() -> Vec<IpAddr> {
    let mut v6 = Vec::new();
    for s in CF_ANYCAST_IPS_V6 {
        if let Ok(ip) = s.parse::<IpAddr>() {
            v6.push(ip);
        }
    }
    let mut v4 = Vec::new();
    for s in CF_ANYCAST_IPS_V4 {
        if let Ok(ip) = s.parse::<IpAddr>() {
            v4.push(ip);
        }
    }
    interleave_dual_stack_ips(v6, v4)
}

pub fn interleave_dual_stack_ips(v6: Vec<IpAddr>, v4: Vec<IpAddr>) -> Vec<IpAddr> {
    let mut interleaved = Vec::with_capacity(v6.len() + v4.len());
    let ipv6_only = crate::recovery::is_ipv6_only_network();
    if ipv6_only {
        // In pure IPv6-only network without CLAT, all IPv6 addresses come first
        interleaved.extend(v6);
        interleaved.extend(v4);
    } else {
        // Cellular networks and Russian ISPs running TSPU heavily blackhole or throttle
        // Cloudflare Anycast IPv6 (2606:4700::/32). Placing all clean IPv4 addresses first
        // avoids the 3-5s IPv6 SYN timeout completely across mobile and Wi-Fi tethering.
        interleaved.extend(v4);
        interleaved.extend(v6);
    }
    interleaved
}

#[derive(Clone, Debug)]
pub struct CfDohCacheEntry {
    pub ips: Vec<IpAddr>,
    pub expires_at: Instant,
    pub is_negative: bool,
    pub family: crate::dns::AddressFamily,
    pub resolver_source: String,
    pub network_generation: u64,
}

static DOH_CACHE: Lazy<
    parking_lot::RwLock<std::collections::HashMap<String, CfDohCacheEntry>>,
> = Lazy::new(|| parking_lot::RwLock::new(std::collections::HashMap::new()));

#[derive(Clone, Debug)]
pub struct CfWinnerCacheEntry {
    pub addr: SocketAddr,
    pub expires_at: Instant,
    pub network_generation: u64,
}

static WINNER_CACHE: Lazy<
    parking_lot::RwLock<std::collections::HashMap<String, CfWinnerCacheEntry>>,
> = Lazy::new(|| parking_lot::RwLock::new(std::collections::HashMap::new()));

pub fn clear_winner_cache() {
    WINNER_CACHE.write().clear();
}

pub fn invalidate_winner_host(domain: &str) {
    let domain = normalize_cf_domain(domain);
    if !domain.is_empty() {
        WINNER_CACHE.write().remove(&domain);
    }
}

pub fn get_cached_winner(domain: &str) -> Option<SocketAddr> {
    let domain = normalize_cf_domain(domain);
    let cur_gen = crate::network_profile::current_generation();
    let entry = WINNER_CACHE.read().get(&domain)?.clone();
    if Instant::now() < entry.expires_at && entry.network_generation == cur_gen {
        Some(entry.addr)
    } else {
        None
    }
}

pub fn set_cached_winner(expected: crate::generation_guard::GenerationStamp, domain: &str, addr: SocketAddr) {
    let domain = normalize_cf_domain(domain);
    if !domain.is_empty() {
        crate::generation_guard::apply_if_current(expected, || {
            WINNER_CACHE.write().insert(
                domain,
                CfWinnerCacheEntry {
                    addr,
                    expires_at: Instant::now() + Duration::from_secs(900),
                    network_generation: expected.network,
                },
            );
        });
    }
}

pub fn clear_doh_cache() {
    DOH_CACHE.write().clear();
    WINNER_CACHE.write().clear();
}

pub fn invalidate_doh_host(domain: &str) {
    let domain = normalize_cf_domain(domain);
    if !domain.is_empty() {
        DOH_CACHE.write().remove(&domain);
        WINNER_CACHE.write().remove(&domain);
    }
}

pub fn cache_resolved_ips_if_current(
    expected: crate::generation_guard::GenerationStamp,
    domain: &str,
    ips: Vec<IpAddr>,
) -> bool {
    let fam = crate::dns::AddressFamily::from_ips(&ips);
    crate::generation_guard::apply_if_current(expected, || {
        DOH_CACHE.write().insert(
            domain.to_string(),
            CfDohCacheEntry {
                ips,
                expires_at: Instant::now() + Duration::from_secs(300),
                is_negative: false,
                family: fam,
                resolver_source: "DoH".to_string(),
                network_generation: expected.network,
            },
        );
    })
    .is_some()
}

pub fn cached_resolved_ips(domain: &str) -> Option<Vec<IpAddr>> {
    let cur_gen = crate::network_profile::current_generation();
    let entry = DOH_CACHE.read().get(domain)?.clone();
    if Instant::now() < entry.expires_at && entry.network_generation == cur_gen && !entry.is_negative && !entry.ips.is_empty() {
        Some(entry.ips)
    } else {
        None
    }
}

pub fn mark_cfproxy_recovery_cooldown(domain: &str, delay: Duration, reason: &str) {
    mark_cfproxy_recovery_circuit_at_stage(
        domain,
        delay,
        reason,
        crate::recovery::EstablishmentStage::Wss,
        crate::generation_guard::current_network(),
    );
}

pub fn mark_cfproxy_recovery_circuit_at_stage(
    domain: &str,
    delay: Duration,
    reason: &str,
    stage: crate::recovery::EstablishmentStage,
    network_gen: u64,
) {
    let key = canonical_cfproxy_cooldown_key(domain);
    if key.is_empty() {
        return;
    }
    let user_d = CFPROXY.read().user_domain.trim().to_string();
    if !user_d.is_empty() && canonical_cfproxy_cooldown_key(&user_d) == key {
        ldebug!(
            " Suppressing recovery circuit cooldown for primary custom worker {}",
            key
        );
        return;
    }
    let until = Instant::now() + delay;
    let mut map = CFPROXY_RECOVERY_CIRCUIT.write();
    let effective_until = map
        .get(&key)
        .map(|old| old.until.max(until))
        .unwrap_or(until);
    map.insert(
        key.clone(),
        CfproxyRecoveryCircuitEntry {
            until: effective_until,
            network_generation: network_gen,
            stage,
            reason: reason.to_string(),
            in_flight_trial: false,
        },
    );
    drop(map);
    ldebug!(
        " CF recovery circuit {}: {}s (stage={:?}, net={}, {})",
        key,
        delay.as_secs(),
        stage,
        network_gen,
        reason
    );
}

pub fn clear_cfproxy_recovery_cooldown(domain: &str) {
    let key = canonical_cfproxy_cooldown_key(domain);
    if !key.is_empty() {
        CFPROXY_RECOVERY_CIRCUIT.write().remove(&key);
    }
}

pub fn clear_all_recovery_cooldowns() {
    CFPROXY_RECOVERY_CIRCUIT.write().clear();
}

pub fn set_doh_endpoints(_endpoints_csv: &str) {
    // No-op: DoH is removed from MTProto and SOCKS5 pipelines
}

pub async fn resolve_dual_stack_ips(domain: &str) -> Vec<IpAddr> {
    let domain = domain.trim();
    if domain.is_empty() {
        return Vec::new();
    }

    // 1. Direct IP check
    if let Ok(ip) = domain.parse::<IpAddr>() {
        return crate::network_profile::filter_ip_family(vec![ip]);
    }

    // 2. Cloudflare Edge Anycast fast-path (0 ms, zero DoH):
    // All Cloudflare Workers (both *.workers.dev/*.pages.dev and custom domains)
    // run on Cloudflare Anycast edge. Connecting directly to Anycast IPs
    // bypasses DoH completely, eliminating DNS latency and TSPU censorship stalls.
    let is_cf = crate::config::is_cloudflare_domain(domain)
        || CFPROXY.read().user_domain.eq_ignore_ascii_case(domain)
        || crate::config::LAST_SOCKS5_WORKER.read().eq_ignore_ascii_case(domain);
    if is_cf {
        let anycast = default_cf_anycast_dual_stack();
        return crate::network_profile::filter_ip_family(anycast);
    }

    // 3. Cache hit (0 ms)
    let cur_gen = crate::network_profile::current_generation();
    if let Some(entry) = DOH_CACHE.read().get(domain).cloned() {
        if Instant::now() < entry.expires_at && entry.network_generation == cur_gen {
            if entry.is_negative {
                return Vec::new();
            }
            if !entry.ips.is_empty() {
                return crate::network_profile::filter_ip_family(entry.ips);
            }
        }
    }

    // 4. Per-host singleflight resolution via system getaddrinfo (zero DoH)
    let expected = crate::generation_guard::snapshot();
    let domain_key = format!("{}:{}:{}:{}", expected.network, expected.profile, expected.config, domain);
    let domain_task = domain.to_string();
    let resolved = crate::budget::DNS_SINGLEFLIGHT
        .execute(domain_key, || async move {
            resolve_dual_stack_ips_uncached(&domain_task, expected).await
        })
        .await;
    crate::network_profile::filter_ip_family(resolved)
}

async fn resolve_dual_stack_ips_uncached(
    domain: &str,
    expected: crate::generation_guard::GenerationStamp,
) -> Vec<IpAddr> {
    if crate::config::is_cloudflare_domain(domain)
        || CFPROXY.read().user_domain.eq_ignore_ascii_case(domain)
        || crate::config::LAST_SOCKS5_WORKER.read().eq_ignore_ascii_case(domain)
    {
        let anycast = default_cf_anycast_dual_stack();
        cache_resolved_ips_if_current(expected, domain, anycast.clone());
        return anycast;
    }

    // Fast system DNS lookup (network-bound, dual-stack via getaddrinfo)
    let host = format!("{}:443", domain);
    let mut addrs_list = Vec::new();
    if let Ok(Ok(addrs)) = tokio::time::timeout(Duration::from_millis(500), tokio::net::lookup_host(host)).await {
        for a in addrs {
            addrs_list.push(a.ip());
        }
    }

    if addrs_list.is_empty() {
        // Fallback to Anycast dual-stack
        let anycast = default_cf_anycast_dual_stack();
        cache_resolved_ips_if_current(expected, domain, anycast.clone());
        return anycast;
    }

    cache_resolved_ips_if_current(expected, domain, addrs_list.clone());
    addrs_list
}

pub async fn resolve_clean_dual_stack_ips(domain: &str) -> Vec<IpAddr> {
    let domain = domain.trim();
    if domain.is_empty() {
        return Vec::new();
    }
    if let Ok(ip) = domain.parse::<IpAddr>() {
        return vec![ip];
    }
    let ips = resolve_dual_stack_ips(domain).await;
    if !crate::config::is_cloudflare_domain(domain) {
        let anycast = default_cf_anycast_dual_stack();
        ips.into_iter().filter(|ip| !anycast.contains(ip)).collect()
    } else {
        ips
    }
}

pub async fn resolve_doh(domain: &str) -> Option<String> {
    let ips = resolve_dual_stack_ips(domain).await;
    ips.first().map(|ip| ip.to_string())
}

// ---------------------------------------------------------------------------
// cfConnectDomain (RFC 8305 Happy Eyeballs Dual-Stack Connection)
// ---------------------------------------------------------------------------

pub async fn cf_connect_domain(
    domain: &str,
    path: &str,
    timeout: f64,
) -> (Option<RawWebSocket>, String, Option<WsError>) {
    cf_connect_domain_with_category(
        domain,
        path,
        None,
        timeout,
        crate::budget::FlowCategory::UserFlow,
        None,
    )
    .await
}

pub async fn cf_connect_domain_ext(
    domain: &str,
    path: &str,
    early_data: Option<&[u8]>,
    timeout: f64,
) -> (Option<RawWebSocket>, String, Option<WsError>) {
    cf_connect_domain_with_category(
        domain,
        path,
        early_data,
        timeout,
        crate::budget::FlowCategory::UserFlow,
        None,
    )
    .await
}

pub async fn cf_connect_domain_with_category(
    domain: &str,
    path: &str,
    early_data: Option<&[u8]>,
    timeout: f64,
    category: crate::budget::FlowCategory,
    cancel_token: Option<&CancellationToken>,
) -> (Option<RawWebSocket>, String, Option<WsError>) {
    // One process-wide admission point prevents simultaneous local clients,
    // pool refills and endpoint races from multiplying dial attempts.
    let expected = crate::generation_guard::snapshot();
    let attempt_permit = match crate::budget::DIAL_BUDGET
        .acquire(category, cancel_token)
        .await
    {
        Ok(permit) => permit,
        Err(e) => {
            return (
                None,
                String::new(),
                Some(WsError::Other(format!("Cloudflare dial budget closed: {}", e))),
            )
        }
    };

    if !crate::generation_guard::is_current(expected) {
        return (
            None,
            String::new(),
            Some(WsError::Canceled),
        );
    }

    let dial_generation = attempt_permit.generation;
    let dial_attempt = async {
        let path = if path.is_empty() { "/apiws" } else { path };

        let is_mobile = MOBILE_NETWORK.load(std::sync::atomic::Ordering::Relaxed);
        let dial_timeout = crate::config::adaptive_dial_timeout(is_mobile);
        let attempt_timeout = crate::ws::ws_connect_timeout(timeout);
        let phase_timeout = if path.starts_with("/tcp") {
            attempt_timeout
        } else {
            attempt_timeout.max(dial_timeout)
        };

        let candidate_ips = resolve_dual_stack_ips(domain).await;
        let is_anycast_fallback = if crate::config::is_cloudflare_domain(domain) {
            let anycast = default_cf_anycast_dual_stack();
            !candidate_ips.is_empty() && candidate_ips.iter().all(|ip| anycast.contains(ip))
        } else {
            false
        };
        let candidate_addrs: Vec<SocketAddr> = candidate_ips
            .into_iter()
            .map(|ip| SocketAddr::new(ip, 443))
            .collect();

        if candidate_addrs.is_empty() {
            crate::recovery::record_if_current(
                expected,
                domain,
                crate::recovery::RecoveryCause::DnsFailure,
            );
            return (
                None,
                String::new(),
                Some(WsError::Other(
                    "dns_failed: no candidate addresses resolved".to_string(),
                )),
            );
        }

        // RFC 8305 Section 6: Stateful Address Selection
        // Prioritize the sticky winning address at the head of candidate_addrs (position 0).
        // Happy Eyeballs dials this address immediately at T=0.
        // If it succeeds, only this address connects. If it stalls, the next candidate starts at T=250ms,
        // preventing connection dropouts without any sequential blocking delays!
        let mut addrs_to_dial = candidate_addrs;
        if let Some(sticky_addr) = get_cached_winner(domain) {
            if let Some(pos) = addrs_to_dial.iter().position(|&a| a == sticky_addr) {
                if pos > 0 {
                    addrs_to_dial.swap(0, pos);
                }
                ldebug!(
                    "CF Stateful address selection: prioritized sticky winner {} -> {} (pos 0)",
                    domain,
                    sticky_addr.ip()
                );
            }
        } else {
            ldebug!(
                " CF Happy Eyeballs dial {} with {} dual-stack IPs (fallback_ip={})",
                domain,
                addrs_to_dial.len(),
                is_anycast_fallback
            );
        }

        match crate::ws::ws_connect_happy_eyeballs_ext(
            domain,
            path,
            early_data,
            &addrs_to_dial,
            phase_timeout,
        )
        .await
        {
            Ok((ws, winner_addr)) => {
                let winner_ip = winner_addr.ip().to_string();
                ldebug!(" CF Happy Eyeballs connected {} -> {}", domain, winner_ip);
                set_cached_winner(expected, domain, winner_addr);
                (Some(ws), winner_ip, None)
            }
            Err(e) => {
                invalidate_winner_host(domain);
                if is_anycast_fallback {
                    crate::recovery::record_if_current(
                        expected,
                        domain,
                        crate::recovery::RecoveryCause::FallbackIpFailed,
                    );
                    (
                        None,
                        String::new(),
                        Some(WsError::Other(format!("fallback_ip_failed: {}", e.compact()))),
                    )
                } else {
                    (None, String::new(), Some(e))
                }
            }
        }
    };

    let result = tokio::select! {
        result = dial_attempt => result,
        _ = crate::budget::DIAL_BUDGET.wait_for_generation_change(dial_generation) => {
            (
                None,
                String::new(),
                Some(WsError::Other(format!(
                    "network generation changed during dial ({})",
                    dial_generation
                ))),
            )
        }
    };
    if !crate::generation_guard::is_current(expected) {
        if let Some(ws) = result.0 {
            let _ = ws.close().await;
        }
        return (None, String::new(), Some(WsError::Canceled));
    }
    result
}

pub async fn cf_connect_fronted(
    server_address: &str,
    server_port: u16,
    tls_sni: &str,
    host_header: &str,
    path: &str,
    timeout: f64,
) -> (Option<RawWebSocket>, String, Option<WsError>) {
    cf_connect_fronted_ext(
        server_address,
        server_port,
        tls_sni,
        host_header,
        path,
        None,
        timeout,
    )
    .await
}

pub async fn cf_connect_fronted_ext(
    server_address: &str,
    server_port: u16,
    tls_sni: &str,
    host_header: &str,
    path: &str,
    early_data: Option<&[u8]>,
    timeout: f64,
) -> (Option<RawWebSocket>, String, Option<WsError>) {
    let expected = crate::generation_guard::snapshot();
    let path = if path.is_empty() {
        "/vless-ws?ed=2048"
    } else {
        path
    };
    let is_mobile = MOBILE_NETWORK.load(std::sync::atomic::Ordering::Relaxed);
    let dial_timeout = crate::config::adaptive_dial_timeout(is_mobile);
    let attempt_timeout = crate::ws::ws_connect_timeout(timeout);
    let phase_timeout = if path.starts_with("/tcp") {
        attempt_timeout
    } else {
        attempt_timeout.max(dial_timeout)
    };

    let server_addr_trimmed = server_address.trim();
    let (parsed_host, target_port) = if let Ok(sa) = server_addr_trimmed.parse::<SocketAddr>() {
        (
            sa.ip().to_string(),
            if server_port > 0 {
                server_port
            } else {
                sa.port()
            },
        )
    } else if let Ok(ip) = server_addr_trimmed.parse::<IpAddr>() {
        (
            ip.to_string(),
            if server_port > 0 { server_port } else { 443 },
        )
    } else if let Some(colon) = server_addr_trimmed.rfind(':') {
        if !server_addr_trimmed.starts_with('[')
            && server_addr_trimmed.chars().filter(|c| *c == ':').count() == 1
        {
            let h = &server_addr_trimmed[..colon];
            let p = server_addr_trimmed[colon + 1..]
                .parse::<u16>()
                .unwrap_or(443);
            (h.to_string(), if server_port > 0 { server_port } else { p })
        } else {
            (
                server_addr_trimmed.to_string(),
                if server_port > 0 { server_port } else { 443 },
            )
        }
    } else {
        (
            server_addr_trimmed.to_string(),
            if server_port > 0 { server_port } else { 443 },
        )
    };

    let (candidate_addrs, is_anycast_fallback): (Vec<SocketAddr>, bool) = if let Ok(ip) = parsed_host.parse::<IpAddr>() {
        (vec![SocketAddr::new(ip, target_port)], false)
    } else {
        let domain_to_resolve = if parsed_host.is_empty() {
            tls_sni.trim()
        } else {
            &parsed_host
        };
        let ips = resolve_dual_stack_ips(domain_to_resolve).await;
        let is_fb = if crate::config::is_cloudflare_domain(domain_to_resolve) {
            let anycast = default_cf_anycast_dual_stack();
            !ips.is_empty() && ips.iter().all(|ip| anycast.contains(ip))
        } else {
            false
        };
        (ips.into_iter().map(|ip| SocketAddr::new(ip, target_port)).collect(), is_fb)
    };

    if candidate_addrs.is_empty() {
        crate::recovery::record_if_current(
            expected,
            tls_sni,
            crate::recovery::RecoveryCause::DnsFailure,
        );
        return (
            None,
            String::new(),
            Some(WsError::Other(
                "dns_failed: no candidate addresses resolved".to_string(),
            )),
        );
    }

    ldebug!(
        " CF fronted dial server={}:{} sni={} host={} with {} candidate addrs (fallback_ip={})",
        server_addr_trimmed,
        target_port,
        tls_sni,
        host_header,
        candidate_addrs.len(),
        is_anycast_fallback
    );

    let result = match crate::ws::ws_connect_happy_eyeballs_split_ext(
        tls_sni,
        host_header,
        path,
        early_data,
        &candidate_addrs,
        phase_timeout,
    )
    .await
    {
        Ok((ws, winner_addr)) => {
            let dial_ip = winner_addr.ip().to_string();
            ldebug!(
                " CF fronted connected server={}:{} sni={} -> {}",
                server_addr_trimmed,
                target_port,
                tls_sni,
                dial_ip
            );
            (Some(ws), dial_ip, None)
        }
        Err(e) => {
            if is_anycast_fallback {
                crate::recovery::record_if_current(
                    expected,
                    tls_sni,
                    crate::recovery::RecoveryCause::FallbackIpFailed,
                );
                (
                    None,
                    String::new(),
                    Some(WsError::Other(format!("fallback_ip_failed: {}", e.compact()))),
                )
            } else {
                (None, String::new(), Some(e))
            }
        }
    };
    if !crate::generation_guard::is_current(expected) {
        if let Some(ws) = result.0 {
            let _ = ws.close().await;
        }
        return (None, String::new(), Some(WsError::Canceled));
    }
    result
}

pub fn log_cf_conn_error(msg: &str, err: &WsError) {
    if let WsError::Io(e) = err {
        if e.kind() == std::io::ErrorKind::ConnectionReset {
            return;
        }
    }
    if is_http_status_error(err, 429) {
        lwarn!("{}", msg);
    } else {
        lerror!("{}", msg);
    }
}

// ---------------------------------------------------------------------------
// Fast Anycast Race & Latency Prober (RFC 8305 Staggered Happy Eyeballs)
// ---------------------------------------------------------------------------

pub async fn probe_domain_latency(domain: &str, dc: i32, timeout: Duration) -> Option<u64> {
    let base_domain = normalize_cf_domain(domain);
    if base_domain.is_empty() {
        return None;
    }
    if cfproxy_429_cooldown_remaining_scoped(&base_domain, ProtoScope::Mtproto) > Duration::ZERO {
        return None;
    }
    let target_host = format!("kws{}.{}", dc, base_domain);
    let candidate_ips = resolve_dual_stack_ips(&target_host).await;
    let candidate_addrs: Vec<SocketAddr> = candidate_ips
        .into_iter()
        .map(|ip| SocketAddr::new(ip, 443))
        .collect();

    if candidate_addrs.is_empty() {
        return None;
    }

    let start = Instant::now();

    match ws_connect_happy_eyeballs(&target_host, "/apiws", &candidate_addrs, timeout).await {
        Ok((ws, winner)) => {
            let rtt = start.elapsed().as_millis() as u64;
            let colo = ws.colo().to_string();
            crate::node_independence::NODE_INDEPENDENCE_TRACKER
                .write()
                .record_node_handshake_success(&base_domain, winner.ip(), &colo);
            tokio::spawn(async move {
                let _ = ws.close().await;
            });
            Some(rtt)
        }
        Err(e) => {
            crate::node_independence::NODE_INDEPENDENCE_TRACKER
                .write()
                .record_node_failure(&base_domain, None, None);
            if is_http_status_error(&e, 429) {
                mark_cfproxy_429_cooldown_scoped(&base_domain, ProtoScope::Mtproto, &e);
            }
            None
        }
    }
}

pub async fn race_rank_domains(dc: i32) {
    let expected = crate::generation_guard::snapshot();
    let domains = {
        let cfg = CFPROXY.read();
        if !cfg.user_domain.is_empty() {
            return; // Custom user worker has 100% priority, skip public CDN race
        }
        if cfg.domains.is_empty() {
            default_cfproxy_domains()
        } else {
            cfg.domains.clone()
        }
    };

    if domains.is_empty() {
        return;
    }

    ldebug!(
        "Начало Fast Anycast Race для {} доменов (DC{})...",
        domains.len(),
        dc
    );
    let (tx, mut rx) = tokio::sync::mpsc::channel::<(String, u64)>(domains.len());
    let mut handles = Vec::new();

    let stagger_step = Duration::from_millis(100);
    for (i, d) in domains.iter().enumerate() {
        let domain = d.clone();
        let tx = tx.clone();
        let delay = stagger_step * (i as u32);

        handles.push(tokio::spawn(async move {
            if delay > Duration::ZERO {
                tokio::time::sleep(delay).await;
            }
            let _permit = match crate::budget::DIAL_BUDGET
                .acquire(crate::budget::FlowCategory::Background, None)
                .await
            {
                Ok(p) => p,
                Err(_) => return,
            };
            if !crate::generation_guard::is_current(expected) {
                return;
            }
            if let Some(latency_ms) = probe_domain_latency(&domain, dc, crate::config::adaptive_race_timeout()).await
            {
                let _ = tx.send((domain.clone(), latency_ms)).await;
                let base = crate::balancer::normalize_domain(&domain);
                crate::balancer::BALANCER.write().record_probe_rtt(
                    crate::network_profile::current_generation(),
                    dc,
                    false,
                    &base,
                    latency_ms,
                );
            }
        }));
    }

    drop(tx);

    let race_deadline = tokio::time::sleep(Duration::from_millis(4000));
    tokio::pin!(race_deadline);

    let mut ranked = Vec::new();
    let mut first_winner_set = false;

    loop {
        tokio::select! {
            _ = &mut race_deadline => break,
            msg = rx.recv() => {
                match msg {
                    Some((domain, latency_ms)) => {
                        if !crate::generation_guard::is_current(expected) {
                            break;
                        }
                        if !first_winner_set {
                            let _ = crate::generation_guard::apply_if_current(expected, || {
                                let mut balancer = crate::balancer::BALANCER.write();
                                if balancer.get_active_domain_for_dc(dc, false).is_none() {
                                    balancer.update_domain_for_dc(dc, false, &domain);
                                    linfo!("Быстрый лидер гонки Anycast (DC{}, холодный старт): {} ({} ms)", dc, domain, latency_ms);
                                }
                            });
                            first_winner_set = true;
                        }
                        ranked.push((domain, latency_ms));
                    }
                    None => break,
                }
            }
        }
    }

    for h in handles {
        h.abort();
    }

    if !ranked.is_empty() {
        ranked.sort_by_key(|(_, l)| *l);
        linfo!(
            "Итоги Fast Anycast Race (DC{}, топ-3): {:?}",
            dc,
            ranked
                .iter()
                .take(3)
                .map(|(d, l)| format!("{}: {}ms", d, l))
                .collect::<Vec<_>>()
        );
        apply_ranked_domains_if_current(expected, dc, ranked);
    }
}

pub fn apply_ranked_domains_if_current(
    expected: crate::generation_guard::GenerationStamp,
    dc: i32,
    ranked: Vec<(String, u64)>,
) -> bool {
    crate::generation_guard::apply_if_current(expected, || {
        crate::balancer::BALANCER
            .write()
            .update_ranked_domains_for_dc(dc, false, ranked);
    })
    .is_some()
}

pub async fn race_all_primary_dcs() {
    // 1. Primary pair: DC2 (Core/Chats) & DC4 (Media/Files) in parallel
    tokio::join!(race_rank_domains(2), race_rank_domains(4),);

    // 2. Secondary DCs: DC5 (Asia) & DC1 (US) in parallel
    tokio::time::sleep(Duration::from_millis(300)).await;
    tokio::join!(race_rank_domains(5), race_rank_domains(1),);
}

pub async fn start_background_balancer_loop(cancel_token: tokio_util::sync::CancellationToken) {
    ldebug!("Balancer background loop started (on-demand mode; background racing disabled)");
    cancel_token.cancelled().await;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_doh_cache_clear() {
        let test_ip = "1.2.3.4".parse::<IpAddr>().unwrap();
        DOH_CACHE.write().insert(
            "test.worker.dev".to_string(),
            CfDohCacheEntry {
                ips: vec![test_ip],
                expires_at: Instant::now() + Duration::from_secs(300),
                is_negative: false,
                family: crate::dns::AddressFamily::Ipv4,
                resolver_source: "DoH".to_string(),
                network_generation: 1,
            },
        );
        assert!(DOH_CACHE.read().contains_key("test.worker.dev"));

        clear_doh_cache();
        assert!(!DOH_CACHE.read().contains_key("test.worker.dev"));
    }

    #[test]
    fn test_cfproxy_429_cooldown_clear() {
        CFPROXY_429.write().insert(
            "test429.worker.dev".to_string(),
            crate::config::Cfproxy429State {
                until: Some(Instant::now() + Duration::from_secs(60)),
                strikes: 1,
            },
        );
        assert!(cfproxy_429_cooldown_remaining("test429.worker.dev") > Duration::ZERO);

        clear_cfproxy_429_cooldowns();
        assert_eq!(
            cfproxy_429_cooldown_remaining("test429.worker.dev"),
            Duration::ZERO
        );
    }

    #[test]
    fn test_interleave_dual_stack_ips() {
        let v6_1 = "2606:4700::1".parse::<IpAddr>().unwrap();
        let v6_2 = "2606:4700::2".parse::<IpAddr>().unwrap();
        let v4_1 = "1.1.1.1".parse::<IpAddr>().unwrap();
        let v4_2 = "1.0.0.1".parse::<IpAddr>().unwrap();

        // Dual-Stack: IPv4 preferred first on Cloudflare Anycast for TSPU bypass
        crate::recovery::set_ipv6_only_network(false);
        let interleaved = interleave_dual_stack_ips(vec![v6_1, v6_2], vec![v4_1, v4_2]);
        assert_eq!(interleaved, vec![v4_1, v4_2, v6_1, v6_2]);

        // IPv6-only network: IPv6 first without IPv4 interleaved ahead
        crate::recovery::set_ipv6_only_network(true);
        let interleaved_v6_only = interleave_dual_stack_ips(vec![v6_1, v6_2], vec![v4_1, v4_2]);
        assert_eq!(interleaved_v6_only, vec![v6_1, v6_2, v4_1, v4_2]);
        crate::recovery::set_ipv6_only_network(false);
    }

    #[test]
    fn test_decode_cf_domain() {
        assert_eq!(decode_cf_domain("virkgj.com"), "pclead.co.uk");
        assert_eq!(decode_cf_domain("vmmzovy.com"), "offshor.co.uk");
        assert_eq!(decode_cf_domain("mkuosckvso.com"), "cakeisalie.co.uk");
        assert_eq!(decode_cf_domain("cakeisalie.co.uk"), "cakeisalie.co.uk");
        assert_eq!(
            decode_cf_domain("my-worker.workers.dev"),
            "my-worker.workers.dev"
        );
        assert_eq!(decode_cf_domain("custom.domain.org"), "custom.domain.org");
    }

    #[test]
    fn test_normalize_cf_domain() {
        assert_eq!(normalize_cf_domain("  example.com.  "), "example.com");
        assert_eq!(normalize_cf_domain("PCLEAD.CO.UK"), "pclead.co.uk");
        assert_eq!(
            normalize_cf_domain("my-worker.workers.dev."),
            "my-worker.workers.dev"
        );
        assert_eq!(normalize_cf_domain(""), "");
    }

    #[test]
    fn test_canonical_cfproxy_cooldown_key() {
        assert_eq!(canonical_cfproxy_cooldown_key("kws2.worker.dev"), "worker.dev");
        assert_eq!(canonical_cfproxy_cooldown_key("kws2-1.worker.dev"), "worker.dev");
        assert_eq!(canonical_cfproxy_cooldown_key("worker.dev"), "worker.dev");
    }

    #[test]
    fn test_parse_cfproxy_domains() {
        let raw_data = "# Remote domains list from GitHub\n\nvirkgj.com\nvmmzovy.com\n# Comment\nmkuosckvso.com\n";
        let parsed = parse_cfproxy_domains(raw_data);
        assert_eq!(parsed.len(), 3);
        assert_eq!(parsed[0], "pclead.co.uk");
        assert_eq!(parsed[1], "offshor.co.uk");
        assert_eq!(parsed[2], "cakeisalie.co.uk");
    }

    #[test]
    fn test_non_cloudflare_domain_forbids_anycast_fallback() {
        assert!(!crate::config::is_cloudflare_domain("api.telegram.org"));
        assert!(!crate::config::is_cloudflare_domain("telegram.org"));
        assert!(!crate::config::is_cloudflare_domain("example.com"));
        assert!(!crate::config::is_cloudflare_domain("evil-cloudflare.com"));
        assert!(!crate::config::is_cloudflare_domain("notcloudflare.com"));

        assert!(crate::config::is_cloudflare_domain("my-worker.workers.dev"));
        assert!(crate::config::is_cloudflare_domain("test.pages.dev"));
        assert!(crate::config::is_cloudflare_domain("cloudflare.com"));
        assert!(crate::config::is_cloudflare_domain("dns.cloudflare.com"));
        assert!(crate::config::is_cloudflare_domain("tunnel.trycloudflare.com"));
    }

    #[test]
    fn test_recovery_cause_fallback_ip_failed_distinct_from_dns_failure() {
        assert_ne!(
            crate::recovery::RecoveryCause::DnsFailure,
            crate::recovery::RecoveryCause::FallbackIpFailed
        );
        assert_eq!(
            crate::recovery::action_for_cause(crate::recovery::RecoveryCause::DnsFailure),
            crate::recovery::RecoveryAction::RotateResolverAddress
        );
        assert_eq!(
            crate::recovery::action_for_cause(crate::recovery::RecoveryCause::FallbackIpFailed),
            crate::recovery::RecoveryAction::TryNextWorker
        );
    }

    #[test]
    fn test_mob030_circuit_breaker_network_scoping_and_single_trial() {
        let domain = "test-worker.workers.dev";
        clear_cfproxy_429_cooldowns();

        // 1. Path-specific failure (e.g. Wss or Ready ACK failure) on Network Gen 1
        let net_gen_1 = 1;
        let net_gen_2 = 2;
        mark_cfproxy_recovery_circuit_at_stage(
            domain,
            Duration::from_secs(60),
            "relay_ack_failed",
            crate::recovery::EstablishmentStage::Ready,
            net_gen_1,
        );

        // On Net Gen 1, cooldown is active
        crate::generation_guard::advance_network(); // let's set current_network
        // Under Net Gen 1, half-open trial is not yet permitted because cooldown hasn't expired
        assert!(!try_acquire_cfproxy_half_open_trial(domain, net_gen_1));

        // When network generation switches to Net Gen 2:
        // Path failure is per-network, so on Net Gen 2 half-open trial IS allowed!
        assert!(try_acquire_cfproxy_half_open_trial(domain, net_gen_2));

        // But half-open allows strictly ONE trial ("не толпу"):
        assert!(!try_acquire_cfproxy_half_open_trial(domain, net_gen_2));

        // Releasing trial allows another single trial:
        release_cfproxy_half_open_trial(domain);
        assert!(try_acquire_cfproxy_half_open_trial(domain, net_gen_2));

        // Successful contract verification clears the circuit completely
        clear_cfproxy_recovery_cooldown(domain);
        assert!(try_acquire_cfproxy_half_open_trial(domain, net_gen_2));

        // 2. 429 rate-limiting is GLOBAL across network switches
        let err_429 = WsError::Handshake(crate::ws::WsHandshakeError {
            status_code: 429,
            status_line: "429 Too Many Requests".to_string(),
            headers: std::collections::HashMap::new(),
            location: String::new(),
        });
        mark_cfproxy_429_cooldown(domain, &err_429);

        // 429 blocks both Net Gen 1 and Net Gen 2
        assert!(cfproxy_429_cooldown_remaining(domain) > Duration::ZERO);
        assert!(!try_acquire_cfproxy_half_open_trial(domain, net_gen_1));
        assert!(!try_acquire_cfproxy_half_open_trial(domain, net_gen_2));

        clear_cfproxy_429_cooldowns();
    }
}
