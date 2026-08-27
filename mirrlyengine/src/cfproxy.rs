use crate::config::*;
use crate::ws::{is_http_status_error, ws_connect_happy_eyeballs, RawWebSocket, WsError};
use crate::{ldebug, lerror, linfo, lwarn};
use serde::Deserialize;
use std::collections::{HashMap, HashSet, VecDeque};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::Semaphore;

use once_cell::sync::Lazy;
static CFPROXY_SEM: Lazy<Semaphore> = Lazy::new(|| Semaphore::new(CFPROXY_GLOBAL_PARALLEL));



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

pub fn clear_cfproxy_429_cooldowns() {
    CFPROXY_429.write().clear();
}

pub fn clear_cfproxy_429_cooldown(domain: &str) {
    let d = normalize_cf_domain(domain);
    if d.is_empty() {
        return;
    }
    CFPROXY_429.write().remove(&d);
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
    let expired = match prev.until {
        None => true,
        Some(u) => u.elapsed() > CFPROXY_429_MAX_COOLDOWN,
    };
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
    let d = normalize_cf_domain(domain);
    if d.is_empty() {
        return;
    }
    let retry_after = retry_after_delay(err);
    let mut map = CFPROXY_429.write();
    let prev = map.get(&d).cloned().unwrap_or_default();
    let delay = next_cfproxy_429_cooldown_delay(&prev, retry_after);
    let mut strikes = prev.strikes + 1;
    let expired = match prev.until {
        None => true,
        Some(u) => u.elapsed() > CFPROXY_429_MAX_COOLDOWN,
    };
    if expired {
        strikes = 1;
    }
    map.insert(
        d.clone(),
        Cfproxy429State {
            until: Some(Instant::now() + delay),
            strikes,
        },
    );
    drop(map);
    ldebug!(" CF cooldown {}: {:.0}s after 429", d, delay.as_secs_f64().ceil());
}

pub fn cfproxy_429_cooldown_remaining(domain: &str) -> Duration {
    let d = normalize_cf_domain(domain);
    if d.is_empty() {
        return Duration::ZERO;
    }
    let map = CFPROXY_429.read();
    let state = match map.get(&d) {
        Some(s) => s.clone(),
        None => return Duration::ZERO,
    };
    drop(map);
    let until = match state.until {
        Some(u) => u,
        None => return Duration::ZERO,
    };
    let now = Instant::now();
    if until <= now {
        CFPROXY_429.write().remove(&d);
        return Duration::ZERO;
    }
    until - now
}

pub async fn acquire_cfproxy_attempt_slot() -> Option<tokio::sync::SemaphorePermit<'static>> {
    CFPROXY_SEM.acquire().await.ok()
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

    let mut cfg = CFPROXY.write();
    if !cached.is_empty() {
        let n = cached.len();
        cfg.domains = merge_cfproxy_domains(&[cached, defaults]);
        crate::balancer::BALANCER.write().update_domains_list(&cfg.domains);
        linfo!(" CF: кеш доменов загружен ({} шт.)", n);
    } else {
        cfg.domains = defaults;
        crate::balancer::BALANCER.write().update_domains_list(&cfg.domains);
    }
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
        {
            let mut cfg = CFPROXY.write();
            cfg.domains = merged.clone();
        }
        crate::balancer::BALANCER.write().update_domains_list(&merged);
        save_cfproxy_domains_to_cache(&merged);
        linfo!(" CF: список доменов обновлен ({} шт.)", new_domains.len());
        return true;
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
    "172.67.153.159",
    "172.67.74.152",
    "104.21.234.180",
    "162.159.153.4",
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
    for i in 0..max_len {
        if i < v6.len() {
            interleaved.push(v6[i]);
        }
        if i < v4.len() {
            interleaved.push(v4[i]);
        }
    }
    interleaved
}

static DOH_CACHE: Lazy<parking_lot::RwLock<std::collections::HashMap<String, (Vec<IpAddr>, Instant)>>> =
    Lazy::new(|| parking_lot::RwLock::new(std::collections::HashMap::new()));

pub fn clear_doh_cache() {
    DOH_CACHE.write().clear();
}

pub async fn resolve_dual_stack_ips(domain: &str) -> Vec<IpAddr> {
    let domain = domain.trim();
    if domain.is_empty() {
        return Vec::new();
    }

    // 1. Direct IP check
    if let Ok(ip) = domain.parse::<IpAddr>() {
        return vec![ip];
    }

    // 2. Cache hit (0 ms)
    if let Some((ips, exp)) = DOH_CACHE.read().get(domain).cloned() {
        if Instant::now() < exp && !ips.is_empty() {
            return ips;
        }
    }

    let endpoints = [
        "https://cloudflare-dns.com/dns-query",
        "https://dns.google/dns-query",
        "https://dns.quad9.net/dns-query",
        "https://dns.adguard-dns.com/dns-query",
    ];

    let client = HTTP_CLIENT.clone();
    let (tx, mut rx) = tokio::sync::mpsc::channel::<IpAddr>(32);
    let mut tasks = Vec::new();

    // 3. Parallel DoH A (IPv4) and AAAA (IPv6) queries
    for u in endpoints {
        // Query A (IPv4)
        {
            let client = client.clone();
            let domain = domain.to_string();
            let tx = tx.clone();
            tasks.push(tokio::spawn(async move {
                let full = format!("{}?name={}&type=A", u, domain);
                if let Ok(resp) = client
                    .get(&full)
                    .header("Accept", "application/dns-json")
                    .send()
                    .await
                {
                    if resp.status().as_u16() == 200 {
                        if let Ok(r) = resp.json::<DohResponse>().await {
                            for ans in r.answer {
                                if ans.type_ == 1 {
                                    if let Ok(ip) = ans.data.trim().parse::<Ipv4Addr>() {
                                        let _ = tx.send(IpAddr::V4(ip)).await;
                                    }
                                }
                            }
                        }
                    }
                }
            }));
        }

        // Query AAAA (IPv6)
        {
            let client = client.clone();
            let domain = domain.to_string();
            let tx = tx.clone();
            tasks.push(tokio::spawn(async move {
                let full = format!("{}?name={}&type=AAAA", u, domain);
                if let Ok(resp) = client
                    .get(&full)
                    .header("Accept", "application/dns-json")
                    .send()
                    .await
                {
                    if resp.status().as_u16() == 200 {
                        if let Ok(r) = resp.json::<DohResponse>().await {
                            for ans in r.answer {
                                if ans.type_ == 28 {
                                    if let Ok(ip) = ans.data.trim().parse::<Ipv6Addr>() {
                                        let _ = tx.send(IpAddr::V6(ip)).await;
                                    }
                                }
                            }
                        }
                    }
                }
            }));
        }
    }

    // 4. Concurrent fast system DNS lookup
    {
        let domain_str = domain.to_string();
        let tx = tx.clone();
        tasks.push(tokio::spawn(async move {
            let host = format!("{}:443", domain_str);
            if let Ok(Ok(addrs)) = tokio::time::timeout(
                Duration::from_millis(600),
                tokio::net::lookup_host(host),
            )
            .await
            {
                for a in addrs {
                    let _ = tx.send(a.ip()).await;
                }
            }
        }));
    }

    drop(tx);

    let deadline = tokio::time::sleep(Duration::from_millis(1200));
    tokio::pin!(deadline);

    let mut seen = HashSet::new();
    let mut v6 = Vec::new();
    let mut v4 = Vec::new();

    loop {
        tokio::select! {
            _ = &mut deadline => break,
            msg = rx.recv() => {
                match msg {
                    Some(ip) => {
                        if seen.insert(ip) {
                            match ip {
                                IpAddr::V6(_) => v6.push(ip),
                                IpAddr::V4(_) => v4.push(ip),
                            }
                            if !v6.is_empty() && !v4.is_empty() && seen.len() >= 4 {
                                break;
                            }
                        }
                    }
                    None => break,
                }
            }
        }
    }

    for t in tasks {
        t.abort();
    }

    let mut interleaved = interleave_dual_stack_ips(v6, v4);
    if interleaved.is_empty() {
        interleaved = default_cf_anycast_dual_stack();
    }

    DOH_CACHE.write().insert(
        domain.to_string(),
        (interleaved.clone(), Instant::now() + Duration::from_secs(300)),
    );

    interleaved
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
    let path = if path.is_empty() { "/apiws" } else { path };

    let attempt_timeout = crate::ws::ws_connect_timeout(timeout);
    let phase_timeout = if path.starts_with("/tcp") {
        attempt_timeout
    } else if attempt_timeout > CFPROXY_DIAL_PHASE_TIMEOUT {
        CFPROXY_DIAL_PHASE_TIMEOUT
    } else {
        attempt_timeout
    };

    let candidate_ips = resolve_dual_stack_ips(domain).await;
    let candidate_addrs: Vec<SocketAddr> = candidate_ips
        .into_iter()
        .map(|ip| SocketAddr::new(ip, 443))
        .collect();

    if candidate_addrs.is_empty() {
        return (None, String::new(), Some(WsError::Other("no candidate addresses resolved".to_string())));
    }

    ldebug!(" CF Happy Eyeballs dial {} with {} dual-stack IPs", domain, candidate_addrs.len());

    match ws_connect_happy_eyeballs(domain, path, &candidate_addrs, phase_timeout).await {
        Ok((ws, winner_addr)) => {
            let winner_ip = winner_addr.ip().to_string();
            ldebug!(" CF Happy Eyeballs connected {} -> {}", domain, winner_ip);
            (Some(ws), winner_ip, None)
        }
        Err(e) => {
            (None, String::new(), Some(e))
        }
    }
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
        Ok((ws, _winner)) => {
            let rtt = start.elapsed().as_millis() as u64;
            tokio::spawn(async move {
                let _ = ws.close().await;
            });
            Some(rtt)
        }
        Err(e) => {
            if is_http_status_error(&e, 429) {
                mark_cfproxy_429_cooldown(&base_domain, &e);
            }
            None
        }
    }
}

pub async fn race_rank_domains(dc: i32) {
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

    ldebug!("Начало Fast Anycast Race для {} доменов (DC{})...", domains.len(), dc);
    let (tx, mut rx) = tokio::sync::mpsc::channel::<(String, u64)>(domains.len());
    let mut handles = Vec::new();

    let stagger_step = Duration::from_millis(100);
    let sem = std::sync::Arc::new(tokio::sync::Semaphore::new(CFPROXY_FALLBACK_PARALLEL));
    for (i, d) in domains.iter().enumerate() {
        let domain = d.clone();
        let tx = tx.clone();
        let sem = sem.clone();
        let delay = stagger_step * (i as u32);

        handles.push(tokio::spawn(async move {
            if delay > Duration::ZERO {
                tokio::time::sleep(delay).await;
            }
            let _permit = match sem.acquire().await {
                Ok(p) => p,
                Err(_) => return,
            };
            if let Some(latency_ms) = probe_domain_latency(&domain, dc, CFPROXY_RACE_TIMEOUT).await {
                let _ = tx.send((domain, latency_ms)).await;
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
                        if !first_winner_set {
                            let current_active = crate::balancer::BALANCER.read().get_active_domain_for_dc(dc);
                            if current_active.is_none() {
                                crate::balancer::BALANCER.write().update_domain_for_dc(dc, &domain);
                                linfo!("⚡ Быстрый лидер гонки Anycast (DC{}, холодный старт): {} ({} ms)", dc, domain, latency_ms);
                            }
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
        linfo!("🏁 Итоги Fast Anycast Race (DC{}, топ-3): {:?}", dc,
            ranked.iter().take(3).map(|(d, l)| format!("{}: {}ms", d, l)).collect::<Vec<_>>()
        );
        crate::balancer::BALANCER.write().update_ranked_domains_for_dc(dc, ranked);
    }
}

pub async fn race_all_primary_dcs() {
    // 1. Primary pair: DC2 (Core/Chats) & DC4 (Media/Files) in parallel
    tokio::join!(
        race_rank_domains(2),
        race_rank_domains(4),
    );

    // 2. Secondary DCs: DC5 (Asia) & DC1 (US) in parallel
    tokio::time::sleep(Duration::from_millis(300)).await;
    tokio::join!(
        race_rank_domains(5),
        race_rank_domains(1),
    );
}

pub async fn start_background_balancer_loop(cancel_token: tokio_util::sync::CancellationToken) {
    // 1. Immediate simultaneous race at startup for primary pair DC2 & DC4 (0ms fast start)
    tokio::join!(
        race_rank_domains(2),
        race_rank_domains(4),
    );

    // 2. Background initial race for remaining secondary DCs (DC5, DC1)
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

    // 3. Periodic race every 60 minutes for all primary DCs
    let mut interval = tokio::time::interval(CFPROXY_RACE_INTERVAL);
    // consume the initial instant tick
    interval.tick().await;

    loop {
        tokio::select! {
            _ = cancel_token.cancelled() => break,
            _ = interval.tick() => {
                ldebug!("Плановый запуск Fast Anycast Race для всех DC (1 раз в 60 минут)...");
                race_all_primary_dcs().await;
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
            (vec![test_ip], Instant::now() + Duration::from_secs(300)),
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
        assert_eq!(cfproxy_429_cooldown_remaining("test429.worker.dev"), Duration::ZERO);
    }

    #[test]
    fn test_interleave_dual_stack_ips() {
        let v6_1 = "2606:4700::1".parse::<IpAddr>().unwrap();
        let v6_2 = "2606:4700::2".parse::<IpAddr>().unwrap();
        let v4_1 = "1.1.1.1".parse::<IpAddr>().unwrap();
        let v4_2 = "1.0.0.1".parse::<IpAddr>().unwrap();

        let interleaved = interleave_dual_stack_ips(vec![v6_1, v6_2], vec![v4_1, v4_2]);
        assert_eq!(interleaved, vec![v6_1, v4_1, v6_2, v4_2]);
    }

    #[tokio::test]
    async fn test_cf_hot_pool_basic() {
        let pool = CfHotPool::new();
        assert!(pool.get(2).await.is_none());

        // Test clear
        pool.clear().await;
        assert!(pool.get(2).await.is_none());
    }

    #[test]
    fn test_decode_cf_domain() {
        assert_eq!(decode_cf_domain("virkgj.com"), "pclead.co.uk");
        assert_eq!(decode_cf_domain("vmmzovy.com"), "offshor.co.uk");
        assert_eq!(decode_cf_domain("mkuosckvso.com"), "cakeisalie.co.uk");
        assert_eq!(decode_cf_domain("cakeisalie.co.uk"), "cakeisalie.co.uk");
        assert_eq!(decode_cf_domain("my-worker.workers.dev"), "my-worker.workers.dev");
        assert_eq!(decode_cf_domain("custom.domain.org"), "custom.domain.org");
    }

    #[test]
    fn test_normalize_cf_domain() {
        assert_eq!(normalize_cf_domain("  example.com.  "), "example.com");
        assert_eq!(normalize_cf_domain("PCLEAD.CO.UK"), "pclead.co.uk");
        assert_eq!(normalize_cf_domain("my-worker.workers.dev."), "my-worker.workers.dev");
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
}
