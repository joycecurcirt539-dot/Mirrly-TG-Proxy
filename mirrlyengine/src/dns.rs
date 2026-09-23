// mirrlyengine/src/dns.rs
//
// Mirrly TG Proxy - Dual-Scope DNS Resolver (Bootstrap vs User In-Tunnel)
// Copyright (C) 2026 R1Xern (Mirrly Dev)
//
// Implements strict separation between:
// 1. Bootstrap DNS: Resolving proxy uplink endpoints (Worker, VLESS, MASQUE, AWG)
//    prior to or during tunnel establishment. Allows bootstrap DoH and protected system DNS.
// 2. User / In-Tunnel DNS: Resolving user target hostnames in SOCKS5 and VPN TUN.
//    Strictly requires secure DoH / in-tunnel resolution with ZERO plaintext system DNS fallback.

use crate::{ldebug, linfo, lwarn};
use once_cell::sync::Lazy;
use serde::Deserialize;
use std::collections::{HashMap, HashSet};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::sync::atomic::{AtomicI32, Ordering};
use std::time::{Duration, Instant};

/// Resolution scope defining permitted DNS transport and fallback behavior.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DnsScope {
    /// Bootstrap DNS: used for resolving proxy and uplink endpoints (Cloudflare Workers,
    /// VLESS nodes, WireGuard/AWG peers, MASQUE Anycast relays, DoH servers) before or
    /// during tunnel establishment. Allows bootstrap DoH, protected host system DNS,
    /// and Cloudflare Anycast fallback for known CDN endpoints.
    Bootstrap,

    /// User / In-Tunnel DNS: used for user target destinations (SOCKS5 domain destinations,
    /// in-tunnel L3 TCP/UDP connections, VPN TUN traffic).
    /// Strictly requires secure in-tunnel/DoH resolution.
    /// STRICTLY PROHIBITS any plaintext host system DNS fallback (leak prevention and VPN loop safety).
    UserInTunnel,
}

impl std::fmt::Display for DnsScope {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DnsScope::Bootstrap => write!(f, "Bootstrap"),
            DnsScope::UserInTunnel => write!(f, "UserInTunnel"),
        }
    }
}

/// Detailed error classification for DNS resolution failures.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DnsError {
    InvalidTarget(String),
    NxDomain(String),
    Timeout(String),
    ResolutionFailed(String),
    NoAddresses(String),
    Unsupported(String),
}

impl std::fmt::Display for DnsError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DnsError::InvalidTarget(s) => write!(f, "invalid target address: {}", s),
            DnsError::NxDomain(s) => write!(f, "domain not found (NXDOMAIN): {}", s),
            DnsError::Timeout(s) => write!(f, "DNS resolution timed out: {}", s),
            DnsError::ResolutionFailed(s) => {
                write!(f, "DNS resolution failed without plaintext fallback: {}", s)
            }
            DnsError::NoAddresses(s) => write!(f, "no IP addresses resolved for host: {}", s),
            DnsError::Unsupported(s) => write!(f, "unsupported DNS target: {}", s),
        }
    }
}

impl std::error::Error for DnsError {}

impl From<DnsError> for std::io::Error {
    fn from(e: DnsError) -> Self {
        match &e {
            DnsError::NxDomain(_) | DnsError::NoAddresses(_) => {
                std::io::Error::new(std::io::ErrorKind::NotFound, e.to_string())
            }
            DnsError::Timeout(_) => {
                std::io::Error::new(std::io::ErrorKind::TimedOut, e.to_string())
            }
            DnsError::InvalidTarget(_) | DnsError::Unsupported(_) => {
                std::io::Error::new(std::io::ErrorKind::InvalidInput, e.to_string())
            }
            DnsError::ResolutionFailed(_) => {
                std::io::Error::new(std::io::ErrorKind::AddrNotAvailable, e.to_string())
            }
        }
    }
}

// ---------------------------------------------------------------------------
// DoH Protocol Structures (RFC 8427 / JSON DoH)
// ---------------------------------------------------------------------------

#[derive(Deserialize, Debug)]
struct DohAnswer {
    #[serde(rename = "data")]
    data: String,
    #[serde(rename = "type")]
    type_: i32,
}

#[derive(Deserialize, Debug)]
struct DohResponse {
    #[serde(rename = "Status", default)]
    status: i32, // 0 = NOERROR, 3 = NXDOMAIN, 2 = SERVFAIL
    #[serde(rename = "Answer", default)]
    answer: Vec<DohAnswer>,
}

// ---------------------------------------------------------------------------
// Global State & Caches (MOB-022: Generation, Family, Source & Negative TTL)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AddressFamily {
    Ipv4,
    Ipv6,
    DualStack,
    None,
}

impl AddressFamily {
    pub fn from_ips(ips: &[IpAddr]) -> Self {
        if ips.is_empty() {
            return AddressFamily::None;
        }
        let has_v4 = ips.iter().any(|ip| ip.is_ipv4());
        let has_v6 = ips.iter().any(|ip| ip.is_ipv6());
        match (has_v4, has_v6) {
            (true, true) => AddressFamily::DualStack,
            (true, false) => AddressFamily::Ipv4,
            (false, true) => AddressFamily::Ipv6,
            (false, false) => AddressFamily::None,
        }
    }
}

#[derive(Clone, Debug)]
pub struct DnsCacheEntry {
    pub ips: Vec<IpAddr>,
    pub expires_at: Instant,
    pub is_secure: bool,
    pub is_negative: bool,
    pub family: AddressFamily,
    pub resolver_source: String,
    pub network_generation: u64,
}

impl DnsCacheEntry {
    pub fn is_valid(&self, current_gen: u64) -> bool {
        Instant::now() < self.expires_at && self.network_generation == current_gen
    }
}

static DNS_CACHE: Lazy<parking_lot::RwLock<HashMap<String, DnsCacheEntry>>> =
    Lazy::new(|| parking_lot::RwLock::new(HashMap::new()));

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

/// Default DNS policy configured from Kotlin (0 = Auto/Dual-Scope, 1 = Strict Secure).
static DNS_POLICY: AtomicI32 = AtomicI32::new(0);

static DOH_HTTP_CLIENT: Lazy<reqwest::Client> = Lazy::new(|| {
    let _ = rustls::crypto::ring::default_provider().install_default();
    reqwest::Client::builder()
        .timeout(Duration::from_millis(2500))
        .build()
        .unwrap_or_default()
});

pub fn set_dns_policy(policy: i32) {
    DNS_POLICY.store(policy, Ordering::Relaxed);
    linfo!("DnsPolicy: updated global DNS policy to {}", policy);
}

pub fn get_dns_policy() -> i32 {
    DNS_POLICY.load(Ordering::Relaxed)
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
        linfo!("SetDohEndpoints: updated to {} endpoints", list.len());
        *DOH_ENDPOINTS.write() = list;
        clear_dns_cache();
    }
}

pub fn clear_dns_cache() {
    DNS_CACHE.write().clear();
    crate::cfproxy::clear_doh_cache();
}

// ---------------------------------------------------------------------------
// Happy Eyeballs Dual-Stack Interleaving
// ---------------------------------------------------------------------------

pub const NAT64_WELL_KNOWN_PREFIX: [u8; 12] = [0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0];

pub fn synthesize_nat64(v4: Ipv4Addr) -> Ipv6Addr {
    let octets = v4.octets();
    let mut v6_octets = [0u8; 16];
    v6_octets[..12].copy_from_slice(&NAT64_WELL_KNOWN_PREFIX);
    v6_octets[12..16].copy_from_slice(&octets);
    Ipv6Addr::from(v6_octets)
}

pub fn is_nat64_address(v6: &Ipv6Addr) -> bool {
    let octets = v6.octets();
    octets[..12] == NAT64_WELL_KNOWN_PREFIX
}

pub fn extract_ipv4_from_nat64(v6: &Ipv6Addr) -> Option<Ipv4Addr> {
    if is_nat64_address(v6) {
        let octets = v6.octets();
        Some(Ipv4Addr::new(octets[12], octets[13], octets[14], octets[15]))
    } else {
        None
    }
}

pub fn interleave_ips(v6: Vec<IpAddr>, v4: Vec<IpAddr>) -> Vec<IpAddr> {
    let mut interleaved = Vec::with_capacity(v6.len() + v4.len());
    let max_len = v6.len().max(v4.len());
    let ipv6_only = crate::recovery::is_ipv6_only_network();
    if ipv6_only {
        // In IPv6-only network: IPv6 addresses take total precedence
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
    crate::network_profile::filter_ip_family(interleaved)
}

// ---------------------------------------------------------------------------
// Core Dual-Scope Resolution Engine
// ---------------------------------------------------------------------------

/// Resolves a domain name into dual-stack IP addresses according to the specified `DnsScope`.
///
/// Under `DnsScope::UserInTunnel`:
/// - Resolves exclusively via secure DoH.
/// - Returns `DnsError::NxDomain` on non-existent domains.
/// - Returns `DnsError::Timeout` or `DnsError::ResolutionFailed` on network / provider failure.
/// - STRICTLY PROHIBITS any plaintext system DNS fallback (`lookup_host`).
///
/// Under `DnsScope::Bootstrap`:
/// - Resolves via DoH first.
/// - Falls back to protected OS system DNS if DoH fails.
/// - Falls back to Cloudflare Anycast IPs for Cloudflare worker domains.
pub async fn resolve_dual_stack(domain: &str, scope: DnsScope) -> Result<Vec<IpAddr>, DnsError> {
    let domain = domain.trim();
    if domain.is_empty() {
        return Err(DnsError::InvalidTarget("empty domain name".to_string()));
    }

    // 1. Direct numeric IP check (0 ms, immediate return)
    if let Ok(ip) = domain.parse::<IpAddr>() {
        return Ok(crate::network_profile::filter_ip_family(vec![ip]));
    }

    // 2. Cache hit check (MOB-022: validated against current network generation & negative TTL)
    let current_gen = crate::network_profile::current_generation();
    {
        let cache_guard = DNS_CACHE.read();
        if let Some(entry) = cache_guard.get(domain) {
            if entry.is_valid(current_gen) {
                if entry.is_negative {
                    ldebug!(
                        "DNS [scope={}] negative cache hit for '{}' (NXDOMAIN, source={}, gen={})",
                        scope,
                        domain,
                        entry.resolver_source,
                        entry.network_generation
                    );
                    return Err(DnsError::NxDomain(domain.to_string()));
                }
                // For UserInTunnel scope, reject unsecure system-fallback cache entries
                if !entry.ips.is_empty() && (scope != DnsScope::UserInTunnel || entry.is_secure) {
                    ldebug!(
                        "DNS [scope={}] cache hit for '{}': {} IPs ({:?}, secure={}, source={}, gen={})",
                        scope,
                        domain,
                        entry.ips.len(),
                        entry.family,
                        entry.is_secure,
                        entry.resolver_source,
                        entry.network_generation
                    );
                return Ok(crate::network_profile::filter_ip_family(entry.ips.clone()));
                }
            }
        }
    }

    let key = format!("{}:{}:{}", current_gen, scope, domain);
    let domain_str = domain.to_string();
    crate::budget::DNS_SCOPE_SINGLEFLIGHT
        .execute(key, || async move {
            resolve_dual_stack_uncached(&domain_str, scope).await
        })
        .await
}

async fn query_doh_type_scoped(
    client: &reqwest::Client,
    endpoint: &str,
    domain: &str,
    qtype: &str,
    tx_ip: &tokio::sync::mpsc::Sender<IpAddr>,
    tx_nx: &tokio::sync::mpsc::Sender<()>,
) -> bool {
    let url = format!("{}?name={}&type={}", endpoint, domain, qtype);
    match client
        .get(&url)
        .header("Accept", "application/dns-json")
        .send()
        .await
    {
        Ok(resp) if resp.status().as_u16() == 200 => {
            if let Ok(r) = resp.json::<DohResponse>().await {
                if r.status == 3 {
                    let _ = tx_nx.send(()).await;
                    false
                } else {
                    let mut found = false;
                    for ans in r.answer {
                        if ans.type_ == 1 {
                            if let Ok(ip) = ans.data.trim().parse::<Ipv4Addr>() {
                                found = true;
                                let _ = tx_ip.send(IpAddr::V4(ip)).await;
                            }
                        } else if ans.type_ == 28 {
                            if let Ok(ip) = ans.data.trim().parse::<Ipv6Addr>() {
                                found = true;
                                let _ = tx_ip.send(IpAddr::V6(ip)).await;
                            }
                        }
                    }
                    found
                }
            } else {
                false
            }
        }
        _ => false,
    }
}

async fn query_doh_single_scoped(
    client: reqwest::Client,
    endpoint: String,
    domain: String,
    tx_ip: tokio::sync::mpsc::Sender<IpAddr>,
    tx_err: tokio::sync::mpsc::Sender<()>,
    tx_nx: tokio::sync::mpsc::Sender<()>,
) {
    let q_a = query_doh_type_scoped(&client, &endpoint, &domain, "A", &tx_ip, &tx_nx);
    let q_aaaa = query_doh_type_scoped(&client, &endpoint, &domain, "AAAA", &tx_ip, &tx_nx);
    let (res_a, res_aaaa) = tokio::join!(q_a, q_aaaa);
    if !res_a && !res_aaaa {
        let _ = tx_err.send(()).await;
    }
}

async fn resolve_dual_stack_uncached(domain: &str, scope: DnsScope) -> Result<Vec<IpAddr>, DnsError> {
    let current_gen = crate::network_profile::current_generation();
    {
        let cache_guard = DNS_CACHE.read();
        if let Some(entry) = cache_guard.get(domain) {
            if entry.is_valid(current_gen) {
                if entry.is_negative {
                    return Err(DnsError::NxDomain(domain.to_string()));
                }
                if !entry.ips.is_empty() && (scope != DnsScope::UserInTunnel || entry.is_secure) {
                    return Ok(crate::network_profile::filter_ip_family(entry.ips.clone()));
                }
            }
        }
    }

    // MOB-021: Acquire global DNS concurrency permit to limit radio congestion
    let _dns_permit = crate::budget::DNS_BUDGET.acquire(None).await.ok();

    let endpoints = crate::recovery::order_resolver_endpoints({
        let eps = DOH_ENDPOINTS.read().clone();
        if eps.is_empty() {
            vec![
                "https://9.9.9.9/dns-query".to_string(),
                "https://94.140.14.14/resolve".to_string(),
                "https://185.222.222.222/dns-query".to_string(),
            ]
        } else {
            eps
        }
    });

    let primary_ep = endpoints.get(0).cloned();
    let secondary_ep = endpoints.get(1).cloned();

    let client = DOH_HTTP_CLIENT.clone();
    let (tx_ip, mut rx_ip) = tokio::sync::mpsc::channel::<IpAddr>(16);
    let (tx_err, mut rx_err) = tokio::sync::mpsc::channel::<()>(4);
    let (tx_nx, mut rx_nx) = tokio::sync::mpsc::channel::<()>(4);

    struct AbortOnDrop(Vec<tokio::task::JoinHandle<()>>);
    impl Drop for AbortOnDrop {
        fn drop(&mut self) {
            for task in &self.0 {
                task.abort();
            }
        }
    }
    let mut tasks = AbortOnDrop(Vec::new());

    // 1. For Bootstrap scope: concurrent fast system DNS lookup
    if scope == DnsScope::Bootstrap {
        let domain_str = domain.to_string();
        let tx = tx_ip.clone();
        tasks.0.push(tokio::spawn(async move {
            let host = format!("{}:443", domain_str);
            if let Ok(Ok(addrs)) =
                tokio::time::timeout(Duration::from_millis(600), tokio::net::lookup_host(host)).await
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
        let domain_str = domain.to_string();
        let tx_ip = tx_ip.clone();
        let tx_err = tx_err.clone();
        let tx_nx = tx_nx.clone();
        tasks.0.push(tokio::spawn(async move {
            query_doh_single_scoped(client, ep, domain_str, tx_ip, tx_err, tx_nx).await;
        }));
    }

    // MOB-021: Hedge delay (180ms). Second DoH is only launched after hedge delay or primary DoH error.
    let hedge_timer = tokio::time::sleep(Duration::from_millis(180));
    tokio::pin!(hedge_timer);
    let mut hedged = false;

    let race_timeout = Duration::from_millis(1500);
    let deadline = tokio::time::sleep(race_timeout);
    tokio::pin!(deadline);

    let mut seen = HashSet::new();
    let mut v6 = Vec::new();
    let mut v4 = Vec::new();
    let mut nxdomain_count = 0usize;
    let mut timed_out = false;

    loop {
        tokio::select! {
            _ = &mut deadline => {
                timed_out = true;
                break;
            }
            _ = &mut hedge_timer, if !hedged => {
                hedged = true;
                if let Some(sec_ep) = secondary_ep.clone() {
                    let client = client.clone();
                    let domain_str = domain.to_string();
                    let tx_ip = tx_ip.clone();
                    let tx_err = tx_err.clone();
                    let tx_nx = tx_nx.clone();
                    tasks.0.push(tokio::spawn(async move {
                        query_doh_single_scoped(client, sec_ep, domain_str, tx_ip, tx_err, tx_nx).await;
                    }));
                }
            }
            err = rx_err.recv(), if !hedged => {
                if err.is_some() {
                    hedged = true;
                    if let Some(sec_ep) = secondary_ep.clone() {
                        let client = client.clone();
                        let domain_str = domain.to_string();
                        let tx_ip = tx_ip.clone();
                        let tx_err = tx_err.clone();
                        let tx_nx = tx_nx.clone();
                        tasks.0.push(tokio::spawn(async move {
                            query_doh_single_scoped(client, sec_ep, domain_str, tx_ip, tx_err, tx_nx).await;
                        }));
                    }
                }
            }
            msg = rx_ip.recv() => {
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
            nx = rx_nx.recv() => {
                if nx.is_some() {
                    nxdomain_count += 1;
                }
            }
        }
    }

    for task in &tasks.0 {
        task.abort();
    }

    let interleaved = interleave_ips(v6, v4);

    if !interleaved.is_empty() {
        let fam = AddressFamily::from_ips(&interleaved);
        ldebug!(
            "DNS [scope={}] DoH resolved '{}' -> {:?} ({:?}, ttl=300s, gen={})",
            scope,
            domain,
            interleaved,
            fam,
            current_gen
        );
        DNS_CACHE.write().insert(
            domain.to_string(),
            DnsCacheEntry {
                ips: interleaved.clone(),
                expires_at: Instant::now() + Duration::from_secs(300),
                is_secure: true,
                is_negative: false,
                family: fam,
                resolver_source: "DoH".to_string(),
                network_generation: current_gen,
            },
        );
        return Ok(crate::network_profile::filter_ip_family(interleaved));
    }

    // If explicit NXDOMAIN returned and no addresses found (MOB-022: negative cache for 15s to prevent storm)
    if nxdomain_count > 0 && seen.is_empty() {
        ldebug!(
            "DNS [scope={}] domain '{}' returned explicit NXDOMAIN ({} answers), negative cache for 15s",
            scope,
            domain,
            nxdomain_count
        );
        DNS_CACHE.write().insert(
            domain.to_string(),
            DnsCacheEntry {
                ips: Vec::new(),
                expires_at: Instant::now() + Duration::from_secs(15),
                is_secure: true,
                is_negative: true,
                family: AddressFamily::None,
                resolver_source: "NXDOMAIN-NegativeCache".to_string(),
                network_generation: current_gen,
            },
        );
        return Err(DnsError::NxDomain(domain.to_string()));
    }

    // 4. Scope-dependent Fallback Handling
    match scope {
        DnsScope::UserInTunnel => {
            // STRICT PRIVACY: NEVER fall back to plaintext system DNS for user traffic!
            lwarn!(
                "DNS [scope=UserInTunnel] DoH resolution failed for '{}' (timed_out={}). Plaintext system DNS fallback is strictly prohibited. Caching negative TTL (15s).",
                domain,
                timed_out
            );
            DNS_CACHE.write().insert(
                domain.to_string(),
                DnsCacheEntry {
                    ips: Vec::new(),
                    expires_at: Instant::now() + Duration::from_secs(15),
                    is_secure: true,
                    is_negative: true,
                    family: AddressFamily::None,
                    resolver_source: "DoH-Failure-NegativeCache".to_string(),
                    network_generation: current_gen,
                },
            );
            if timed_out {
                Err(DnsError::Timeout(format!("DoH timeout for {}", domain)))
            } else {
                Err(DnsError::ResolutionFailed(format!(
                    "DoH resolution failed for {} without plaintext fallback",
                    domain
                )))
            }
        }
        DnsScope::Bootstrap => {
            // Bootstrap scope allows protected OS system DNS fallback to establish tunnel
            ldebug!(
                "DNS [scope=Bootstrap] DoH unavialable for '{}', attempting protected system fallback",
                domain
            );
            let host_port = format!("{}:443", domain);
            if let Ok(Ok(addrs)) =
                tokio::time::timeout(Duration::from_millis(600), tokio::net::lookup_host(host_port))
                    .await
            {
                let mut sys_ips = Vec::new();
                for a in addrs {
                    if seen.insert(a.ip()) {
                        sys_ips.push(a.ip());
                    }
                }
                if !sys_ips.is_empty() {
                    let fam = AddressFamily::from_ips(&sys_ips);
                    ldebug!(
                        "DNS [scope=Bootstrap] system fallback resolved '{}' -> {:?} ({:?}, gen={})",
                        domain,
                        sys_ips,
                        fam,
                        current_gen
                    );
                    DNS_CACHE.write().insert(
                        domain.to_string(),
                        DnsCacheEntry {
                            ips: sys_ips.clone(),
                            expires_at: Instant::now() + Duration::from_secs(60),
                            is_secure: false, // Tagged as unsecure: never served to UserInTunnel
                            is_negative: false,
                            family: fam,
                            resolver_source: "System-Resolver".to_string(),
                            network_generation: current_gen,
                        },
                    );
                    return Ok(sys_ips);
                }
            }

            // Cloudflare Anycast fallback for worker endpoints
            if is_cloudflare_target_domain(domain) {
                linfo!(
                    "DNS [scope=Bootstrap] applying Cloudflare Anycast fallback for '{}'",
                    domain
                );
                let anycast = crate::cfproxy::default_cf_anycast_dual_stack();
                let fam = AddressFamily::from_ips(&anycast);
                DNS_CACHE.write().insert(
                    domain.to_string(),
                    DnsCacheEntry {
                        ips: anycast.clone(),
                        expires_at: Instant::now() + Duration::from_secs(300),
                        is_secure: false,
                        is_negative: false,
                        family: fam,
                        resolver_source: "Cloudflare-Anycast-Fallback".to_string(),
                        network_generation: current_gen,
                    },
                );
                return Ok(anycast);
            }

            DNS_CACHE.write().insert(
                domain.to_string(),
                DnsCacheEntry {
                    ips: Vec::new(),
                    expires_at: Instant::now() + Duration::from_secs(15),
                    is_secure: false,
                    is_negative: true,
                    family: AddressFamily::None,
                    resolver_source: "Bootstrap-Failure-NegativeCache".to_string(),
                    network_generation: current_gen,
                },
            );

            Err(DnsError::ResolutionFailed(format!(
                "bootstrap resolution failed for {}",
                domain
            )))
        }
    }
}

pub fn is_cloudflare_target_domain(domain: &str) -> bool {
    crate::vless::is_cloudflare_domain(domain)
}

// ---------------------------------------------------------------------------
// Endpoint & Target Address Resolution
// ---------------------------------------------------------------------------

/// Parses target address into `(host, port)` without performing DNS.
pub fn parse_host_and_port(target_addr: &str) -> Result<(String, u16), DnsError> {
    let clean = target_addr.trim();
    if clean.is_empty() {
        return Err(DnsError::InvalidTarget("empty target address".to_string()));
    }

    if clean.starts_with('[') {
        if let Some(close_pos) = clean.find(']') {
            let host = &clean[1..close_pos];
            let rest = &clean[close_pos + 1..];
            let port = if rest.starts_with(':') {
                rest[1..]
                    .parse::<u16>()
                    .map_err(|_| DnsError::InvalidTarget(format!("invalid port in {}", clean)))?
            } else {
                443
            };
            Ok((host.to_string(), port))
        } else {
            Err(DnsError::InvalidTarget(format!(
                "unmatched bracket in target address: {}",
                clean
            )))
        }
    } else if let Some(last_colon) = clean.rfind(':') {
        let first_colon = clean.find(':').unwrap();
        if first_colon != last_colon {
            // Multiple colons without brackets: raw IPv6 address
            if clean.parse::<Ipv6Addr>().is_ok() {
                Ok((clean.to_string(), 443))
            } else {
                let host_part = &clean[..last_colon];
                let port_part = &clean[last_colon + 1..];
                if let Ok(p) = port_part.parse::<u16>() {
                    if host_part.parse::<Ipv6Addr>().is_ok() {
                        Ok((host_part.to_string(), p))
                    } else {
                        Err(DnsError::InvalidTarget(format!(
                            "ambiguous IPv6 address (brackets required): {}",
                            clean
                        )))
                    }
                } else {
                    Err(DnsError::InvalidTarget(format!(
                        "invalid IPv6 address: {}",
                        clean
                    )))
                }
            }
        } else {
            let host = &clean[..last_colon];
            let port = clean[last_colon + 1..]
                .parse::<u16>()
                .map_err(|_| DnsError::InvalidTarget(format!("invalid port in {}", clean)))?;
            Ok((host.to_string(), port))
        }
    } else {
        Ok((clean.to_string(), 443))
    }
}

/// Resolves a target address string into an IP address and port according to `DnsScope`.
pub async fn resolve_target_endpoint(
    target_addr: &str,
    scope: DnsScope,
) -> Result<(IpAddr, u16), DnsError> {
    let (host, port) = parse_host_and_port(target_addr)?;
    let host_trimmed = host.trim_matches('[').trim_matches(']');

    // 1. Literal IPv4
    if let Ok(v4) = host_trimmed.parse::<Ipv4Addr>() {
        return Ok((IpAddr::V4(v4), port));
    }

    // 2. Literal IPv6
    if let Ok(v6) = host_trimmed.parse::<Ipv6Addr>() {
        return Ok((IpAddr::V6(v6), port));
    }

    // 3. DNS resolution under specified scope
    let ips = resolve_dual_stack(host_trimmed, scope).await?;
    if let Some(&first_ip) = ips.first() {
        Ok((first_ip, port))
    } else {
        Err(DnsError::NoAddresses(host_trimmed.to_string()))
    }
}

/// Resolves a target address string into an IPv4 address and port according to `DnsScope`.
pub async fn parse_target_ipv4_scoped(
    target_addr: &str,
    scope: DnsScope,
) -> Result<(Ipv4Addr, u16), String> {
    let (host, port) = parse_host_and_port(target_addr).map_err(|e| e.to_string())?;
    let host_trimmed = host.trim_matches('[').trim_matches(']');

    if let Ok(v4) = host_trimmed.parse::<Ipv4Addr>() {
        return Ok((v4, port));
    }

    if let Ok(v6) = host_trimmed.parse::<Ipv6Addr>() {
        return Err(format!("no IPv4 address resolved for host {}", v6));
    }

    let ips = resolve_dual_stack(host_trimmed, scope)
        .await
        .map_err(|e| e.to_string())?;

    for ip in ips {
        if let IpAddr::V4(v4) = ip {
            return Ok((v4, port));
        }
    }

    Err(format!("no IPv4 address resolved for host {}", host_trimmed))
}

/// Resolves a target address string into an IP address and port defaulting to `DnsScope::UserInTunnel`.
/// Guarantees that any user destination is resolved securely without plaintext DNS leakage.
pub async fn parse_target_endpoint(target_addr: &str) -> Result<(IpAddr, u16), String> {
    resolve_target_endpoint(target_addr, DnsScope::UserInTunnel)
        .await
        .map_err(|e| e.to_string())
}

/// Resolves a target address string into an IPv4 address and port defaulting to `DnsScope::UserInTunnel`.
pub async fn parse_target_ipv4(target_addr: &str) -> Result<(Ipv4Addr, u16), String> {
    parse_target_ipv4_scoped(target_addr, DnsScope::UserInTunnel).await
}

// ---------------------------------------------------------------------------
// Unit Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_parse_host_and_port() {
        assert_eq!(
            parse_host_and_port("1.2.3.4:80").unwrap(),
            ("1.2.3.4".to_string(), 80)
        );
        assert_eq!(
            parse_host_and_port("[2001:db8::1]:443").unwrap(),
            ("2001:db8::1".to_string(), 443)
        );
        assert_eq!(
            parse_host_and_port("2001:db8::1").unwrap(),
            ("2001:db8::1".to_string(), 443)
        );
        assert_eq!(
            parse_host_and_port("example.com:8080").unwrap(),
            ("example.com".to_string(), 8080)
        );
        assert_eq!(
            parse_host_and_port("example.com").unwrap(),
            ("example.com".to_string(), 443)
        );
        assert!(parse_host_and_port("").is_err());
        assert!(parse_host_and_port("[2001:db8::1").is_err());
    }

    #[tokio::test]
    async fn test_resolve_ip_literals_immediate() {
        let (ip1, p1) = resolve_target_endpoint("149.154.167.50:443", DnsScope::UserInTunnel)
            .await
            .unwrap();
        assert_eq!(ip1, IpAddr::V4(Ipv4Addr::new(149, 154, 167, 50)));
        assert_eq!(p1, 443);

        let (ip2, p2) = resolve_target_endpoint("[2606:4700:4700::1111]:443", DnsScope::UserInTunnel)
            .await
            .unwrap();
        assert_eq!(ip2, IpAddr::V6("2606:4700:4700::1111".parse().unwrap()));
        assert_eq!(p2, 443);
    }

    #[tokio::test]
    async fn test_user_in_tunnel_prohibits_system_fallback() {
        // A nonexistent domain under UserInTunnel MUST fail and never fall back to plaintext system DNS
        let res = resolve_dual_stack("invalid-test-domain-mirrly-never-exists.xyz", DnsScope::UserInTunnel).await;
        assert!(res.is_err());
        let err = res.unwrap_err();
        assert!(matches!(err, DnsError::ResolutionFailed(_) | DnsError::NxDomain(_) | DnsError::Timeout(_)));
    }

    #[tokio::test]
    async fn test_cache_separation_secure_vs_unsecure() {
        clear_dns_cache();
        let test_domain = "cached.test.domain.com";

        // Insert unsecure entry (e.g. from bootstrap system DNS fallback)
        let cur_gen = crate::network_profile::current_generation();
        DNS_CACHE.write().insert(
            test_domain.to_string(),
            DnsCacheEntry {
                ips: vec![IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4))],
                expires_at: Instant::now() + Duration::from_secs(60),
                is_secure: false,
                is_negative: false,
                family: AddressFamily::Ipv4,
                resolver_source: "System-Resolver".to_string(),
                network_generation: cur_gen,
            },
        );

        // Bootstrap scope CAN read the unsecure cache entry
        let boot_res = resolve_dual_stack(test_domain, DnsScope::Bootstrap).await.unwrap();
        assert_eq!(boot_res, vec![IpAddr::V4(Ipv4Addr::new(1, 2, 3, 4))]);

        // UserInTunnel scope MUST IGNORE the unsecure entry to prevent DNS pollution/leakage
        let user_res = resolve_dual_stack(test_domain, DnsScope::UserInTunnel).await;
        // User resolution bypasses unsecure cache, attempts DoH, and fails securely since domain doesn't exist
        assert!(user_res.is_err());
    }

    #[tokio::test]
    async fn test_generation_cache_invalidation_after_handover() {
        clear_dns_cache();
        let domain = "wifi-then-lte.example.org";
        let wifi_ip = IpAddr::V4(Ipv4Addr::new(192, 0, 2, 100));

        // 1. Put entry into cache under generation 1 (Wi-Fi)
        DNS_CACHE.write().insert(
            domain.to_string(),
            DnsCacheEntry {
                ips: vec![wifi_ip],
                expires_at: Instant::now() + Duration::from_secs(300),
                is_secure: true,
                is_negative: false,
                family: AddressFamily::Ipv4,
                resolver_source: "DoH".to_string(),
                network_generation: 1,
            },
        );

        // Under generation 1, cache is valid
        assert!(DNS_CACHE.read().get(domain).unwrap().is_valid(1));

        // Handover to LTE -> generation becomes 2
        crate::network_profile::update_generation(2);
        assert_eq!(crate::network_profile::current_generation(), 2);

        // Under generation 2, the Wi-Fi entry MUST be considered invalid!
        assert!(!DNS_CACHE.read().get(domain).unwrap().is_valid(2));

        // Revert back to generation 1 for remaining test suite
        crate::network_profile::update_generation(1);
    }

    #[tokio::test]
    async fn test_negative_cache_prevents_nxdomain_storm() {
        clear_dns_cache();
        let domain = "nonexistent-nxdomain.example.com";
        let cur_gen = crate::network_profile::current_generation();

        // Put negative entry into cache
        DNS_CACHE.write().insert(
            domain.to_string(),
            DnsCacheEntry {
                ips: Vec::new(),
                expires_at: Instant::now() + Duration::from_secs(15),
                is_secure: true,
                is_negative: true,
                family: AddressFamily::None,
                resolver_source: "NXDOMAIN-NegativeCache".to_string(),
                network_generation: cur_gen,
            },
        );

        // Attempting to resolve MUST immediately hit negative cache and return NxDomain without network requests
        let res = resolve_dual_stack(domain, DnsScope::UserInTunnel).await;
        assert!(res.is_err());
        assert!(matches!(res.unwrap_err(), DnsError::NxDomain(_)));
    }

    #[tokio::test]
    async fn test_parse_target_ipv4_scoped() {
        let (ip, port) = parse_target_ipv4_scoped("127.0.0.1:10808", DnsScope::UserInTunnel)
            .await
            .unwrap();
        assert_eq!(ip, Ipv4Addr::new(127, 0, 0, 1));
        assert_eq!(port, 10808);

        // IPv6 literal must produce predictable error
        let err = parse_target_ipv4_scoped("2001:db8::1", DnsScope::UserInTunnel)
            .await
            .unwrap_err();
        assert!(err.contains("no IPv4 address resolved"));
    }

    #[test]
    fn test_nat64_synthesis_and_extraction() {
        let v4 = Ipv4Addr::new(149, 154, 167, 51);
        let v6 = synthesize_nat64(v4);

        assert!(is_nat64_address(&v6));
        assert_eq!(v6.octets()[..12], NAT64_WELL_KNOWN_PREFIX);
        assert_eq!(v6.octets()[12..], [149, 154, 167, 51]);

        let extracted = extract_ipv4_from_nat64(&v6);
        assert_eq!(extracted, Some(v4));

        let non_nat64 = Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 1);
        assert!(!is_nat64_address(&non_nat64));
        assert_eq!(extract_ipv4_from_nat64(&non_nat64), None);
    }

    #[test]
    fn test_interleave_ips_rfc8305_and_ipv6_only() {
        let v6_1 = IpAddr::V6(Ipv6Addr::new(0x2606, 0x4700, 0, 0, 0, 0, 0, 1));
        let v6_2 = IpAddr::V6(Ipv6Addr::new(0x2606, 0x4700, 0, 0, 0, 0, 0, 2));
        let v4_1 = IpAddr::V4(Ipv4Addr::new(104, 21, 1, 1));
        let v4_2 = IpAddr::V4(Ipv4Addr::new(104, 21, 1, 2));

        // Normal dual stack -> RFC 8305 interleaves with IPv6 first
        crate::recovery::set_ipv6_only_network(false);
        let res_dual = interleave_ips(vec![v6_1, v6_2], vec![v4_1, v4_2]);
        assert_eq!(res_dual, vec![v6_1, v4_1, v6_2, v4_2]);

        // AAAA-only hostname (empty v4)
        let res_aaaa_only = interleave_ips(vec![v6_1, v6_2], Vec::new());
        assert_eq!(res_aaaa_only, vec![v6_1, v6_2]);

        // IPv6-only network
        crate::recovery::set_ipv6_only_network(true);
        let res_ipv6_only = interleave_ips(vec![v6_1, v6_2], vec![v4_1, v4_2]);
        assert_eq!(res_ipv6_only[0], v6_1);
        assert_eq!(res_ipv6_only[1], v6_2);
        crate::recovery::set_ipv6_only_network(false);
    }
}
