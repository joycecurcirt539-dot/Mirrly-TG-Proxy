// mirrlyengine/src/dns.rs
//
// Mirrly TG Proxy - Minimal System Hostname & Literal Endpoint Resolver
// Copyright (C) 2026 R1Xern (Mirrly Dev)

use once_cell::sync::Lazy;
use std::collections::{HashMap, HashSet};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::time::{Duration, Instant};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DnsError {
    InvalidTarget(String),
    Timeout(String),
    ResolutionFailed(String),
    NoAddresses(String),
}

impl std::fmt::Display for DnsError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DnsError::InvalidTarget(s) => write!(f, "invalid target address: {}", s),
            DnsError::Timeout(s) => write!(f, "host lookup timed out: {}", s),
            DnsError::ResolutionFailed(s) => write!(f, "host resolution failed: {}", s),
            DnsError::NoAddresses(s) => write!(f, "no IP addresses resolved for host: {}", s),
        }
    }
}

impl std::error::Error for DnsError {}

impl From<DnsError> for std::io::Error {
    fn from(e: DnsError) -> Self {
        match &e {
            DnsError::NoAddresses(_) => {
                std::io::Error::new(std::io::ErrorKind::NotFound, e.to_string())
            }
            DnsError::Timeout(_) => {
                std::io::Error::new(std::io::ErrorKind::TimedOut, e.to_string())
            }
            DnsError::InvalidTarget(_) => {
                std::io::Error::new(std::io::ErrorKind::InvalidInput, e.to_string())
            }
            DnsError::ResolutionFailed(_) => {
                std::io::Error::new(std::io::ErrorKind::AddrNotAvailable, e.to_string())
            }
        }
    }
}

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
struct HostCacheEntry {
    ips: Vec<IpAddr>,
    expires_at: Instant,
    network_generation: u64,
}

impl HostCacheEntry {
    fn is_valid(&self, current_gen: u64) -> bool {
        Instant::now() < self.expires_at && self.network_generation == current_gen
    }
}

static HOST_CACHE: Lazy<parking_lot::RwLock<HashMap<String, HostCacheEntry>>> =
    Lazy::new(|| parking_lot::RwLock::new(HashMap::new()));

pub fn clear_dns_cache() {
    HOST_CACHE.write().clear();
    crate::cfproxy::clear_addr_cache();
}

// ---------------------------------------------------------------------------
// Happy Eyeballs Dual-Stack Interleaving & NAT64
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
    v6.octets()[..12] == NAT64_WELL_KNOWN_PREFIX
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
    if crate::recovery::is_ipv6_only_network() {
        interleaved.extend(v6);
        interleaved.extend(v4);
    } else {
        // Place IPv4 first on dual-stack cellular/ISP links where IPv6 routes stall.
        interleaved.extend(v4);
        interleaved.extend(v6);
    }
    crate::network_profile::filter_ip_family(interleaved)
}

// ---------------------------------------------------------------------------
// Host & Endpoint Resolution (System getaddrinfo + IP literals)
// ---------------------------------------------------------------------------

pub async fn resolve_dual_stack(domain: &str) -> Result<Vec<IpAddr>, DnsError> {
    let domain = domain.trim().trim_matches('[').trim_matches(']');
    if domain.is_empty() {
        return Err(DnsError::InvalidTarget("empty domain name".to_string()));
    }

    if let Ok(ip) = domain.parse::<IpAddr>() {
        return Ok(crate::network_profile::filter_ip_family(vec![ip]));
    }

    let current_gen = crate::network_profile::current_generation();
    if let Some(entry) = HOST_CACHE.read().get(domain).cloned() {
        if entry.is_valid(current_gen) && !entry.ips.is_empty() {
            return Ok(crate::network_profile::filter_ip_family(entry.ips));
        }
    }

    let key = format!("sys:{}:{}", current_gen, domain);
    let domain_owned = domain.to_string();
    let ips = crate::budget::DNS_SINGLEFLIGHT
        .execute(key, || async move {
            let _permit = crate::budget::DNS_BUDGET.acquire(None).await.ok();
            let host_port = format!("{}:{}", domain_owned, crate::ws::default_ws_port());
            let Ok(Ok(addrs)) = tokio::time::timeout(
                Duration::from_millis(1200),
                tokio::net::lookup_host(host_port),
            )
            .await
            else {
                return Vec::new();
            };

            let mut seen = HashSet::new();
            let mut v6 = Vec::new();
            let mut v4 = Vec::new();
            for a in addrs {
                let ip = a.ip();
                if seen.insert(ip) {
                    match ip {
                        IpAddr::V6(_) => v6.push(ip),
                        IpAddr::V4(_) => v4.push(ip),
                    }
                }
            }
            let ordered = interleave_ips(v6, v4);
            if !ordered.is_empty() {
                HOST_CACHE.write().insert(
                    domain_owned.clone(),
                    HostCacheEntry {
                        ips: ordered.clone(),
                        expires_at: Instant::now() + Duration::from_secs(60),
                        network_generation: current_gen,
                    },
                );
                ldebug!("System resolver '{}' -> {} IPs (gen={})", domain_owned, ordered.len(), current_gen);
            }
            ordered
        })
        .await;

    if ips.is_empty() {
        Err(DnsError::ResolutionFailed(format!(
            "system resolution failed for {}",
            domain
        )))
    } else {
        Ok(crate::network_profile::filter_ip_family(ips))
    }
}

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

pub async fn resolve_target_endpoint(target_addr: &str) -> Result<(IpAddr, u16), DnsError> {
    let (host, port) = parse_host_and_port(target_addr)?;
    let host_trimmed = host.trim_matches('[').trim_matches(']');

    if let Ok(v4) = host_trimmed.parse::<Ipv4Addr>() {
        return Ok((IpAddr::V4(v4), port));
    }
    if let Ok(v6) = host_trimmed.parse::<Ipv6Addr>() {
        return Ok((IpAddr::V6(v6), port));
    }

    let ips = resolve_dual_stack(host_trimmed).await?;
    ips.first()
        .copied()
        .map(|ip| (ip, port))
        .ok_or_else(|| DnsError::NoAddresses(host_trimmed.to_string()))
}

pub async fn parse_target_endpoint(target_addr: &str) -> Result<(IpAddr, u16), String> {
    resolve_target_endpoint(target_addr)
        .await
        .map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_parse_host_and_port() {
        assert_eq!(parse_host_and_port("1.2.3.4:80").unwrap(), ("1.2.3.4".to_string(), 80));
        assert_eq!(parse_host_and_port("[2001:db8::1]:443").unwrap(), ("2001:db8::1".to_string(), 443));
        assert_eq!(parse_host_and_port("2001:db8::1").unwrap(), ("2001:db8::1".to_string(), 443));
        assert_eq!(parse_host_and_port("example.com:8080").unwrap(), ("example.com".to_string(), 8080));
        assert_eq!(parse_host_and_port("example.com").unwrap(), ("example.com".to_string(), 443));
        assert!(parse_host_and_port("").is_err());
        assert!(parse_host_and_port("[2001:db8::1").is_err());
    }

    #[tokio::test]
    async fn test_resolve_ip_literals_immediate() {
        let (ip1, p1) = resolve_target_endpoint("149.154.167.50:443").await.unwrap();
        assert_eq!(ip1, IpAddr::V4(Ipv4Addr::new(149, 154, 167, 50)));
        assert_eq!(p1, 443);

        let (ip2, p2) = resolve_target_endpoint("[2606:4700:4700::1111]:443").await.unwrap();
        assert_eq!(ip2, IpAddr::V6("2606:4700:4700::1111".parse().unwrap()));
        assert_eq!(p2, 443);
    }

    #[tokio::test]
    async fn test_cache_invalidation_on_generation_change() {
        clear_dns_cache();
        let domain = "gen-test-cache.example.org";
        let gen1 = crate::network_profile::current_generation();
        HOST_CACHE.write().insert(
            domain.to_string(),
            HostCacheEntry {
                ips: vec![IpAddr::V4(Ipv4Addr::new(104, 21, 1, 1))],
                expires_at: Instant::now() + Duration::from_secs(60),
                network_generation: gen1,
            },
        );
        let next_gen = gen1 + 1;
        crate::network_profile::update_generation(next_gen);
        assert!(!HOST_CACHE.read().get(domain).unwrap().is_valid(next_gen));
    }

    #[test]
    fn test_nat64_synthesis_and_extraction() {
        let v4 = Ipv4Addr::new(149, 154, 167, 51);
        let v6 = synthesize_nat64(v4);
        assert!(is_nat64_address(&v6));
        assert_eq!(extract_ipv4_from_nat64(&v6), Some(v4));
        assert_eq!(extract_ipv4_from_nat64(&Ipv6Addr::LOCALHOST), None);
    }

    #[test]
    fn test_interleave_ips_rfc8305_and_ipv6_only() {
        let v6_1 = IpAddr::V6(Ipv6Addr::new(0x2606, 0x4700, 0, 0, 0, 0, 0, 1));
        let v4_1 = IpAddr::V4(Ipv4Addr::new(104, 21, 1, 1));

        let mut prof = crate::network_profile::get_profile();
        let prev_pref = prof.ip_family_preference.clone();
        prof.generation = crate::generation_guard::snapshot().network.max(1) + 1;
        prof.ip_family_preference = "DUAL_STACK".to_string();
        crate::network_profile::apply_profile(prof);

        crate::recovery::set_ipv6_only_network(false);
        assert_eq!(interleave_ips(vec![v6_1], vec![v4_1]), vec![v4_1, v6_1]);

        crate::recovery::set_ipv6_only_network(true);
        assert_eq!(interleave_ips(vec![v6_1], vec![v4_1]), vec![v6_1, v4_1]);
        crate::recovery::set_ipv6_only_network(false);

        let mut prof_restore = crate::network_profile::get_profile();
        prof_restore.generation = crate::generation_guard::snapshot().network.max(1) + 1;
        prof_restore.ip_family_preference = prev_pref;
        crate::network_profile::apply_profile(prof_restore);
    }
}
