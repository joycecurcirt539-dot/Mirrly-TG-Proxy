use crate::config::*;
use crate::ws::{
    is_http_status_error, ws_connect_happy_eyeballs, RawWebSocket,
    WsError,
};
use crate::{ldebug, lerror, linfo, lwarn};
use serde::Deserialize;
use std::collections::HashSet;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
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
    let normalized = normalize_cf_domain(domain);
    if normalized.is_empty() {
        return String::new();
    }
    if let Some((prefix, base)) = normalized.split_once('.') {
        if prefix.starts_with("kws")
            && prefix.len() > 3
            && prefix[3..].chars().all(|character| character.is_ascii_digit())
        {
            return base.to_string();
        }
    }
    normalized
}

pub fn clear_cfproxy_429_cooldowns() {
    CFPROXY_429.write().clear();
    CFPROXY_RECOVERY_CIRCUIT.write().clear();
}

pub fn clear_cfproxy_429_cooldown(domain: &str) {
    let d = canonical_cfproxy_cooldown_key(domain);
    if d.is_empty() {
        return;
    }
    CFPROXY_429.write().remove(&d);
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

pub fn mark_cfproxy_429_cooldown(domain: &str, err: &WsError) {
    let d = canonical_cfproxy_cooldown_key(domain);
    if d.is_empty() {
        return;
    }
    let retry_after = retry_after_delay(err);
    let mut map = CFPROXY_429.write();
    let prev = map.get(&d).cloned().unwrap_or_default();
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
        d.clone(),
        Cfproxy429State {
            until: Some(until),
            strikes,
        },
    );
    drop(map);
    ldebug!(
        " CF cooldown {}: {:.0}s after 429",
        d,
        delay.as_secs_f64().ceil()
    );
}

pub fn cfproxy_429_cooldown_remaining(domain: &str) -> Duration {
    let d = canonical_cfproxy_cooldown_key(domain);
    if d.is_empty() {
        return Duration::ZERO;
    }
    let now = Instant::now();
    let current_net = crate::generation_guard::current_network();
    let recovery_remaining = CFPROXY_RECOVERY_CIRCUIT
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
        .unwrap_or(Duration::ZERO);

    let mut map = CFPROXY_429.write();
    let until = match map.get(&d).and_then(|state| state.until) {
        Some(until) => until,
        None => return recovery_remaining,
    };
    if until <= now {
        map.remove(&d);
        return recovery_remaining;
    }
    // 429 is global by endpoint: if until > now, it applies globally
    (until - now).max(recovery_remaining)
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
// Dual-Stack DNS over HTTPS (DoH) & System DNS Resolver (RFC 8305 Dual-Stack)
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
struct DohAnswer {
    #[serde(rename = "data")]
    data: String,
    #[serde(rename = "type")]
    type_: i32,
}
#[derive(Deserialize)]
struct DohResponse {
    #[serde(rename = "Answer", default)]
    answer: Vec<DohAnswer>,
}

pub const CF_ANYCAST_IPS_V4: &[&str] = &[
    "188.114.96.1",
    "188.114.97.1",
    "188.114.96.3",
    "188.114.97.3",
    "172.67.153.159",
    "172.67.74.152",
    "104.21.234.180",
    "104.16.248.249",
    "104.16.249.249",
    "162.159.153.4",
    "162.159.192.1",
    "141.101.90.0",
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
    let max_len = v6.len().max(v4.len());
    let ipv6_only = crate::recovery::is_ipv6_only_network();
    if ipv6_only {
        // In IPv6-only network, all IPv6 addresses come first
        interleaved.extend(v6);
        interleaved.extend(v4);
    } else {
        // RFC 8305 Dual-Stack: Interleave with IPv6 first
        for i in 0..max_len {
            if i < v6.len() {
                interleaved.push(v6[i]);
            }
            if i < v4.len() {
                interleaved.push(v4[i]);
            }
        }
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

static DOH_ENDPOINTS: Lazy<parking_lot::RwLock<Vec<String>>> = Lazy::new(|| {
    parking_lot::RwLock::new(vec![
        "https://9.9.9.9/dns-query".to_string(),
        "https://94.140.14.14/dns-query".to_string(),
        "https://dns.comms.one/dns-query".to_string(),
        "https://geohide.org/dns-query".to_string(),
        "https://195.201.201.32/dns-query".to_string(),
        "https://194.242.2.2/dns-query".to_string(),
        "https://76.76.2.0/dns-query".to_string(),
    ])
});

pub fn clear_doh_cache() {
    DOH_CACHE.write().clear();
}

pub fn invalidate_doh_host(domain: &str) {
    let domain = normalize_cf_domain(domain);
    if !domain.is_empty() {
        DOH_CACHE.write().remove(&domain);
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

pub fn set_doh_endpoints(endpoints_csv: &str) {
    let mut list = Vec::new();
    for item in endpoints_csv.split(',') {
        let trimmed = item.trim();
        if !trimmed.is_empty() {
            list.push(trimmed.to_string());
        }
    }
    if !list.is_empty() {
        crate::linfo!("SetDohEndpoints: updated to {} endpoints", list.len());
        *DOH_ENDPOINTS.write() = list;
        clear_doh_cache();
    }
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

    // 2. Cache hit (0 ms, MOB-022: validated against current network generation & negative TTL)
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

    // 3. Per-host DNS singleflight: deduplicates concurrent resolutions for the same domain
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

async fn query_cf_doh_type(
    client: &reqwest::Client,
    endpoint: &str,
    domain: &str,
    qtype: &str,
    tx: &tokio::sync::mpsc::Sender<IpAddr>,
) -> bool {
    let full = format!("{}?name={}&type={}", endpoint, domain, qtype);
    match client
        .get(&full)
        .header("Accept", "application/dns-json")
        .send()
        .await
    {
        Ok(resp) if resp.status().as_u16() == 200 => {
            if let Ok(r) = resp.json::<DohResponse>().await {
                let mut found = false;
                for ans in r.answer {
                    if ans.type_ == 1 {
                        if let Ok(ip) = ans.data.trim().parse::<Ipv4Addr>() {
                            found = true;
                            let _ = tx.send(IpAddr::V4(ip)).await;
                        }
                    } else if ans.type_ == 28 {
                        if let Ok(ip) = ans.data.trim().parse::<Ipv6Addr>() {
                            found = true;
                            let _ = tx.send(IpAddr::V6(ip)).await;
                        }
                    }
                }
                found
            } else {
                false
            }
        }
        _ => false,
    }
}

async fn query_doh_single(
    client: reqwest::Client,
    endpoint: String,
    domain: String,
    tx: tokio::sync::mpsc::Sender<IpAddr>,
    tx_err: tokio::sync::mpsc::Sender<()>,
) {
    let q_a = query_cf_doh_type(&client, &endpoint, &domain, "A", &tx);
    let q_aaaa = query_cf_doh_type(&client, &endpoint, &domain, "AAAA", &tx);
    let (res_a, res_aaaa) = tokio::join!(q_a, q_aaaa);
    if !res_a && !res_aaaa {
        let _ = tx_err.send(()).await;
    }
}

async fn resolve_dual_stack_ips_uncached(
    domain: &str,
    expected: crate::generation_guard::GenerationStamp,
) -> Vec<IpAddr> {
    let cur_gen = crate::network_profile::current_generation();
    if let Some(entry) = DOH_CACHE.read().get(domain).cloned() {
        if Instant::now() < entry.expires_at && entry.network_generation == cur_gen {
            if entry.is_negative {
                return Vec::new();
            }
            if !entry.ips.is_empty() {
                return entry.ips;
            }
        }
    }

    // MOB-021: Acquire global DNS concurrency permit to limit radio congestion
    let _dns_permit = crate::budget::DNS_BUDGET.acquire(None).await.ok();

    let endpoints = crate::recovery::order_resolver_endpoints({
        let guard = DOH_ENDPOINTS.read();
        if guard.is_empty() {
            vec![
                "https://94.140.14.14/resolve".to_string(),
                "https://185.222.222.222/dns-query".to_string(),
            ]
        } else {
            guard.clone()
        }
    });

    let primary_ep = endpoints.get(0).cloned();
    let secondary_ep = endpoints.get(1).cloned();

    let client = HTTP_CLIENT.clone();
    let (tx, mut rx) = tokio::sync::mpsc::channel::<IpAddr>(16);
    let (tx_err, mut rx_err) = tokio::sync::mpsc::channel::<()>(4);

    struct AbortOnDrop(Vec<tokio::task::JoinHandle<()>>);
    impl Drop for AbortOnDrop {
        fn drop(&mut self) {
            for task in &self.0 {
                task.abort();
            }
        }
    }
    let mut tasks = AbortOnDrop(Vec::new());

    // 1. Concurrent fast system DNS lookup (network-bound, dual-stack via getaddrinfo)
    {
        let domain_str = domain.to_string();
        let tx = tx.clone();
        tasks.0.push(tokio::spawn(async move {
            let host = format!("{}:443", domain_str);
            if let Ok(Ok(addrs)) =
                tokio::time::timeout(Duration::from_millis(600), tokio::net::lookup_host(host))
                    .await
            {
                for a in addrs {
                    let _ = tx.send(a.ip()).await;
                }
            }
        }));
    }

    // 2. Primary selected DoH query (single HTTP query for A records)
    if let Some(ep) = primary_ep {
        let client = client.clone();
        let domain = domain.to_string();
        let tx = tx.clone();
        let tx_err = tx_err.clone();
        tasks.0.push(tokio::spawn(async move {
            query_doh_single(client, ep, domain, tx, tx_err).await;
        }));
    }

    // MOB-021: Hedge delay (180ms). Second DoH is only launched after hedge delay or primary DoH error.
    let hedge_timer = tokio::time::sleep(Duration::from_millis(180));
    tokio::pin!(hedge_timer);
    let mut hedged = false;

    let deadline = tokio::time::sleep(Duration::from_millis(1200));
    tokio::pin!(deadline);

    let mut seen = HashSet::new();
    let mut v6 = Vec::new();
    let mut v4 = Vec::new();

    loop {
        tokio::select! {
            _ = &mut deadline => break,
            _ = &mut hedge_timer, if !hedged => {
                hedged = true;
                if let Some(sec_ep) = secondary_ep.clone() {
                    let client = client.clone();
                    let domain = domain.to_string();
                    let tx = tx.clone();
                    let tx_err = tx_err.clone();
                    tasks.0.push(tokio::spawn(async move {
                        query_doh_single(client, sec_ep, domain, tx, tx_err).await;
                    }));
                }
            }
            err = rx_err.recv(), if !hedged => {
                if err.is_some() {
                    hedged = true;
                    if let Some(sec_ep) = secondary_ep.clone() {
                        let client = client.clone();
                        let domain = domain.to_string();
                        let tx = tx.clone();
                        let tx_err = tx_err.clone();
                        tasks.0.push(tokio::spawn(async move {
                            query_doh_single(client, sec_ep, domain, tx, tx_err).await;
                        }));
                    }
                }
            }
            msg = rx.recv() => {
                match msg {
                    Some(ip) => {
                        if seen.insert(ip) {
                            match ip {
                                IpAddr::V6(_) => v6.push(ip),
                                IpAddr::V4(_) => v4.push(ip),
                            }
                            if (!v4.is_empty() && !v6.is_empty()) || seen.len() >= 4 {
                                break;
                            }
                        }
                    }
                    None => break,
                }
            }
        }
    }

    for task in &tasks.0 {
        task.abort();
    }

    if !crate::generation_guard::is_current(expected) {
        return Vec::new();
    }

    let mut interleaved = interleave_dual_stack_ips(v6, v4);
    if interleaved.is_empty() {
        crate::recovery::record_if_current(
            expected,
            domain,
            crate::recovery::RecoveryCause::DnsFailure,
        );
        if crate::vless::is_cloudflare_domain(domain) {
            crate::linfo!(
                "DNS failure for confirmed Cloudflare domain '{}'; applying explicit Anycast Fallback policy",
                domain
            );
            interleaved = default_cf_anycast_dual_stack();
            let fam = crate::dns::AddressFamily::from_ips(&interleaved);
            let _ = crate::generation_guard::apply_if_current(expected, || {
                DOH_CACHE.write().insert(
                    domain.to_string(),
                    CfDohCacheEntry {
                        ips: interleaved.clone(),
                        expires_at: Instant::now() + Duration::from_secs(300),
                        is_negative: false,
                        family: fam,
                        resolver_source: "Cloudflare-Anycast-Fallback".to_string(),
                        network_generation: expected.network,
                    },
                );
            });
        } else {
            crate::lwarn!(
                "DNS failure for non-Cloudflare domain '{}'; Anycast fallback strictly forbidden",
                domain
            );
            let _ = crate::generation_guard::apply_if_current(expected, || {
                DOH_CACHE.write().insert(
                    domain.to_string(),
                    CfDohCacheEntry {
                        ips: Vec::new(),
                        expires_at: Instant::now() + Duration::from_secs(15),
                        is_negative: true,
                        family: crate::dns::AddressFamily::None,
                        resolver_source: "DnsFailure-NonCloudflare".to_string(),
                        network_generation: expected.network,
                    },
                );
            });
        }
    } else {
        cache_resolved_ips_if_current(expected, domain, interleaved.clone());
    }
    if crate::generation_guard::is_current(expected) {
        interleaved
    } else {
        Vec::new()
    }
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
    if !crate::vless::is_cloudflare_domain(domain) {
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

        let attempt_timeout = if MOBILE_NETWORK.load(std::sync::atomic::Ordering::Relaxed) {
            crate::ws::ws_connect_timeout(timeout).max(CFPROXY_MOBILE_DIAL_TIMEOUT)
        } else {
            crate::ws::ws_connect_timeout(timeout)
        };
        let phase_timeout = if path.starts_with("/tcp") {
            attempt_timeout
        } else if MOBILE_NETWORK.load(std::sync::atomic::Ordering::Relaxed) {
            attempt_timeout.min(CFPROXY_MOBILE_DIAL_TIMEOUT)
        } else if attempt_timeout > CFPROXY_DIAL_PHASE_TIMEOUT {
            CFPROXY_DIAL_PHASE_TIMEOUT
        } else {
            attempt_timeout
        };

        let candidate_ips = resolve_dual_stack_ips(domain).await;
        let is_anycast_fallback = if crate::vless::is_cloudflare_domain(domain) {
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

        ldebug!(
            " CF Happy Eyeballs dial {} with {} dual-stack IPs (fallback_ip={})",
            domain,
            candidate_addrs.len(),
            is_anycast_fallback
        );

        match crate::ws::ws_connect_happy_eyeballs_ext(
            domain,
            path,
            early_data,
            &candidate_addrs,
            phase_timeout,
        )
        .await
        {
            Ok((ws, winner_addr)) => {
                let winner_ip = winner_addr.ip().to_string();
                ldebug!(" CF Happy Eyeballs connected {} -> {}", domain, winner_ip);
                (Some(ws), winner_ip, None)
            }
            Err(e) => {
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
    let attempt_timeout = crate::ws::ws_connect_timeout(timeout);
    let phase_timeout = if path.starts_with("/tcp") {
        attempt_timeout
    } else if attempt_timeout > CFPROXY_DIAL_PHASE_TIMEOUT {
        CFPROXY_DIAL_PHASE_TIMEOUT
    } else {
        attempt_timeout
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
        let is_fb = if crate::vless::is_cloudflare_domain(domain_to_resolve) {
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
    if cfproxy_429_cooldown_remaining(&base_domain) > Duration::ZERO {
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
                mark_cfproxy_429_cooldown(&base_domain, &e);
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
            if let Some(latency_ms) = probe_domain_latency(&domain, dc, CFPROXY_RACE_TIMEOUT).await
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
    if !MOBILE_NETWORK.load(std::sync::atomic::Ordering::Relaxed) {
        // Wi-Fi keeps the established eager ranking behaviour.
        tokio::join!(race_rank_domains(2), race_rank_domains(4),);

        let cancel_init = cancel_token.clone();
        tokio::spawn(async move {
            tokio::select! {
                _ = cancel_init.cancelled() => return,
                _ = tokio::time::sleep(Duration::from_millis(500)) => {
                    tokio::join!(
                        race_rank_domains(5),
                        race_rank_domains(1),
                    );
                }
            }
        });
    } else {
        ldebug!("Cellular profile: startup CDN ranking deferred to real MTProto demand");
    }

    // 3. Periodic race every 60 minutes for all primary DCs
    let mut interval = tokio::time::interval(CFPROXY_RACE_INTERVAL);
    // consume the initial instant tick
    interval.tick().await;

    loop {
        tokio::select! {
            _ = cancel_token.cancelled() => break,
            _ = interval.tick() => {
                if MOBILE_NETWORK.load(std::sync::atomic::Ordering::Relaxed) {
                    ldebug!("Cellular profile: periodic full CDN race skipped");
                } else {
                    ldebug!("Плановый запуск Fast Anycast Race для всех DC (1 раз в 60 минут)...");
                    race_all_primary_dcs().await;
                }
            }
        }
    }
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

        // RFC 8305 Dual-Stack: IPv6 preferred first
        crate::recovery::set_ipv6_only_network(false);
        let interleaved = interleave_dual_stack_ips(vec![v6_1, v6_2], vec![v4_1, v4_2]);
        assert_eq!(interleaved, vec![v6_1, v4_1, v6_2, v4_2]);

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
        assert!(!crate::vless::is_cloudflare_domain("api.telegram.org"));
        assert!(!crate::vless::is_cloudflare_domain("telegram.org"));
        assert!(!crate::vless::is_cloudflare_domain("example.com"));
        assert!(!crate::vless::is_cloudflare_domain("evil-cloudflare.com"));
        assert!(!crate::vless::is_cloudflare_domain("notcloudflare.com"));

        assert!(crate::vless::is_cloudflare_domain("my-worker.workers.dev"));
        assert!(crate::vless::is_cloudflare_domain("test.pages.dev"));
        assert!(crate::vless::is_cloudflare_domain("cloudflare.com"));
        assert!(crate::vless::is_cloudflare_domain("dns.cloudflare.com"));
        assert!(crate::vless::is_cloudflare_domain("tunnel.trycloudflare.com"));
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
        let dummy_headers = HashMap::new();
        let err_429 = WsError::HttpUpgradeFailed {
            status_code: 429,
            headers: dummy_headers,
        };
        mark_cfproxy_429_cooldown(domain, &err_429);

        // 429 blocks both Net Gen 1 and Net Gen 2
        assert!(cfproxy_429_cooldown_remaining(domain) > Duration::ZERO);
        assert!(!try_acquire_cfproxy_half_open_trial(domain, net_gen_1));
        assert!(!try_acquire_cfproxy_half_open_trial(domain, net_gen_2));

        clear_cfproxy_429_cooldowns();
    }
}
