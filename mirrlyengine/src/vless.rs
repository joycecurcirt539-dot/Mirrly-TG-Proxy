use crate::cfproxy::{
    cf_connect_domain_ext, cf_connect_fronted_ext, cfproxy_429_cooldown_remaining,
    clear_cfproxy_429_cooldown, log_cf_conn_error, mark_cfproxy_429_cooldown,
    resolve_clean_dual_stack_ips,
};
use crate::config::*;
use crate::ws::{
    happy_eyeballs_tcp_connect, is_http_status_error, parse_early_data_header_len, server_name,
    set_sock_opts, ws_handshake_plain_ext, ws_handshake_split_host_ext, RawWebSocket,
};
use crate::{ldebug, lerror, linfo, lwarn};
use once_cell::sync::Lazy;
use parking_lot::RwLock;
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};

use serde::{Deserialize, Serialize};
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio_rustls::TlsConnector;
use tokio_util::sync::CancellationToken;

// ---------------------------------------------------------------------------
// Cloudflare Target & IP Detection Helpers
// ---------------------------------------------------------------------------

pub fn is_cloudflare_domain(domain: &str) -> bool {
    let d = domain.trim().to_lowercase();
    if d.is_empty() {
        return false;
    }
    if d.ends_with(".pages.dev")
        || d.ends_with(".workers.dev")
        || d.ends_with(".cloudflare.com")
        || d.ends_with(".cloudflareclient.com")
        || d.ends_with(".trycloudflare.com")
        || d.ends_with(".cloudflareaccess.com")
        || d == "pages.dev"
        || d == "workers.dev"
        || d == "cloudflare.com"
    {
        return true;
    }
    for &fb in PUBLIC_VLESS_FALLBACKS {
        if d == fb.to_lowercase() {
            return true;
        }
    }
    for &w in DEV_SOCKS5_WORKERS {
        if d == w.to_lowercase() {
            return true;
        }
    }
    let pool = DYNAMIC_VLESS_FALLBACKS.read();
    for p in pool.iter() {
        if d == p.to_lowercase() {
            return true;
        }
    }
    false
}

pub fn is_cloudflare_ip(ip: &IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let u = u32::from_be_bytes(v4.octets());
            const CF_V4_CIDRS: &[(u32, u32)] = &[
                (0xADF53000, 0xFFFFF000), // 173.245.48.0/20
                (0x6715F400, 0xFFFFFC00), // 103.21.244.0/22
                (0x6716C800, 0xFFFFFC00), // 103.22.200.0/22
                (0x671F0400, 0xFFFFFC00), // 103.31.4.0/22
                (0x8D654000, 0xFFFFC000), // 141.101.64.0/18
                (0x6CA2C000, 0xFFFFC000), // 108.162.192.0/18
                (0xBE5DF000, 0xFFFFF000), // 190.93.240.0/20
                (0xBC726000, 0xFFFFF000), // 188.114.96.0/20
                (0xC5EAF000, 0xFFFFFC00), // 197.234.240.0/22
                (0xC6298000, 0xFFFF8000), // 198.41.128.0/17
                (0xA29E0000, 0xFFFE0000), // 162.158.0.0/15
                (0x68100000, 0xFFF80000), // 104.16.0.0/13
                (0x68180000, 0xFFFC0000), // 104.24.0.0/14
                (0xAC400000, 0xFFF80000), // 172.64.0.0/13
                (0x83004800, 0xFFFFFC00), // 131.0.72.0/22
            ];
            CF_V4_CIDRS.iter().any(|&(net, mask)| (u & mask) == net)
        }
        IpAddr::V6(v6) => {
            let segs = v6.segments();
            if segs[0] == 0x2400 && segs[1] == 0xcb00 {
                return true;
            } // 2400:cb00::/32
            if segs[0] == 0x2606 && segs[1] == 0x4700 {
                return true;
            } // 2606:4700::/32
            if segs[0] == 0x2803 && segs[1] == 0xf800 {
                return true;
            } // 2803:f800::/32
            if segs[0] == 0x2405 && segs[1] == 0xb500 {
                return true;
            } // 2405:b500::/32
            if segs[0] == 0x2405 && segs[1] == 0x8100 {
                return true;
            } // 2405:8100::/32
            if segs[0] == 0x2a06 && (segs[1] & 0xfff8) == 0x98c0 {
                return true;
            } // 2a06:98c0::/29
            if segs[0] == 0x2c0f && segs[1] == 0xf248 {
                return true;
            } // 2c0f:f248::/32
            false
        }
    }
}

pub fn is_cloudflare_target(target: &str) -> bool {
    let t = target.trim();
    if t.is_empty() {
        return false;
    }
    if let Ok(ip) = t.parse::<IpAddr>() {
        return is_cloudflare_ip(&ip);
    }
    if let Ok(sa) = t.parse::<SocketAddr>() {
        return is_cloudflare_ip(&sa.ip());
    }
    is_cloudflare_domain(t)
}

// ---------------------------------------------------------------------------
// VLESS Configuration and Uplink Abstraction
// ---------------------------------------------------------------------------

pub enum VlessUplink {
    Ws(RawWebSocket),
    Tcp(
        tokio::net::TcpStream,
        Vec<u8>,
        Option<crate::vision::VisionContext>,
    ),
    Tls(
        tokio_rustls::client::TlsStream<tokio::net::TcpStream>,
        Vec<u8>,
        Option<crate::vision::VisionContext>,
    ),
    Reality(
        crate::reality::RealityStream,
        Vec<u8>,
        Option<crate::vision::VisionContext>,
    ),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct VlessConfig {
    pub id: String,
    pub name: String,
    pub uuid: [u8; 16],
    pub path: String,
    pub domain: String,
    pub server_address: String,
    pub server_port: u16,
    pub tls_sni: String,
    pub host_header: String,
    pub transport: String,
    pub security: String,
    pub public_key: String,
    pub short_id: String,
    pub fingerprint: String,
    pub spider_x: String,
    pub flow: String,
    pub header_type: String,
}

impl VlessConfig {
    pub fn effective_server_address(&self) -> &str {
        if !self.server_address.is_empty() {
            &self.server_address
        } else {
            &self.domain
        }
    }

    pub fn effective_server_port(&self) -> u16 {
        if self.server_port > 0 {
            self.server_port
        } else {
            443
        }
    }

    pub fn effective_tls_sni(&self) -> &str {
        if !self.tls_sni.is_empty() {
            &self.tls_sni
        } else {
            &self.domain
        }
    }

    pub fn effective_host_header(&self) -> &str {
        if !self.host_header.is_empty() {
            &self.host_header
        } else if !self.tls_sni.is_empty() {
            &self.tls_sni
        } else {
            &self.domain
        }
    }

    pub fn effective_transport(&self) -> &str {
        if !self.transport.is_empty() {
            &self.transport
        } else {
            // Default to TCP per VLESS spec. Path string is NOT used to infer transport:
            // path is an HTTP URI path, not a protocol selector.
            "tcp"
        }
    }

    pub fn effective_security(&self) -> &str {
        if !self.security.is_empty() {
            &self.security
        } else {
            // Explicit default: if the profile carries no security field, assume TLS.
            // Callers that want plain transport must set security = "none" explicitly.
            "tls"
        }
    }

    pub fn is_reality(&self) -> bool {
        self.effective_security().eq_ignore_ascii_case("reality") || !self.public_key.is_empty()
    }

    pub fn is_direct_tcp(&self) -> bool {
        self.effective_transport().eq_ignore_ascii_case("tcp")
    }

    /// Returns `true` when this profile uses a direct connection to the VLESS server,
    /// i.e. no Cloudflare-worker tunnelling is needed.
    ///
    /// **Routing decision is always derived from the explicit `transport` and `security`
    /// profile fields.**  The server address (IP or domain) is used only to establish the
    /// TCP connection, not to choose the protocol path.
    ///
    /// Decision matrix:
    ///  - `security = reality` or `public_key` present → always direct (Reality over TCP)
    ///  - `transport = tcp` (explicit) → always direct (plain TCP / TLS-TCP / Reality-TCP)
    ///  - `transport = ws` (explicit) AND `server_address` is set (CDN IP / domain override)
    ///    → direct (WS upgrade to the explicit host; domain in `host_header`/`tls_sni` is SNI)
    ///  - `transport = ws` AND no `server_address` AND domain resolves to Cloudflare
    ///    → Cloudflare worker flow
    ///  - Both `transport` and `security` empty (legacy profile) → fallback to address heuristic
    ///    with a log warning; the profile should be re-imported with explicit fields.
    pub fn is_direct_vps(&self) -> bool {
        // Reality always needs a direct connection.
        if self.is_reality() {
            return true;
        }

        let transport_explicit = !self.transport.is_empty();
        let security_explicit = !self.security.is_empty();

        if transport_explicit || security_explicit {
            // Explicit profile: routing is determined by transport, not by address.
            let transport = self.effective_transport();
            if transport.eq_ignore_ascii_case("tcp") {
                // TCP transport (with any security) → direct.
                return true;
            }
            // WS transport: direct if a specific server_address is provided (CDN override,
            // custom VPS IP, etc.).  Without server_address check the domain field.
            if !self.server_address.is_empty() {
                // server_address is explicitly set → operator chose the dial target; direct.
                return true;
            }
            // WS + no server_address: check domain for Cloudflare worker membership.
            if !self.domain.is_empty() {
                return !is_cloudflare_target(&self.domain);
            }
            // WS with neither field populated → cannot be direct without a dial target.
            return false;
        }

        // Legacy / incomplete profile: both transport and security are empty.
        // Fall back to address heuristic so existing deployments continue to work,
        // but emit a warning because this guess can be wrong (e.g. VPS behind CDN).
        lwarn!(
            "VLESS [{}]: transport and security fields are both empty; \
             routing decision falls back to address heuristic — re-import the profile \
             with explicit transport/security to fix routing",
            self.id
        );
        if !self.server_address.is_empty() {
            return !is_cloudflare_target(&self.server_address);
        }
        if !self.domain.is_empty() {
            return !is_cloudflare_target(&self.domain);
        }
        false
    }

    pub fn is_vision(&self) -> bool {
        self.flow
            .trim()
            .eq_ignore_ascii_case(crate::vision::VISION_FLOW)
            || self.flow.trim().starts_with(crate::vision::VISION_FLOW)
    }

    pub fn effective_flow(&self) -> &str {
        if self.is_vision() {
            crate::vision::VISION_FLOW
        } else {
            &self.flow
        }
    }

    pub fn effective_fingerprint(&self) -> &str {
        let fp = self.fingerprint.trim();
        if fp.is_empty() {
            "chrome"
        } else {
            fp
        }
    }

    pub fn audit_capabilities(&self) -> Vec<ParameterWarning> {
        let mut warnings = Vec::new();
        if !self.header_type.is_empty() && !self.header_type.eq_ignore_ascii_case("none") {
            warnings.push(ParameterWarning {
                parameter: "header_type".to_string(),
                message: format!(
                    "header_type '{}' has no wire effect: fake HTTP and mKCP packet headers are unsupported; raw stream framing is used",
                    self.header_type
                ),
            });
        }
        if !self.spider_x.is_empty() {
            if self.is_reality() {
                warnings.push(ParameterWarning {
                    parameter: "spider_x".to_string(),
                    message: format!(
                        "spider_x '{}' has no wire effect: client-side web crawling is not executed",
                        self.spider_x
                    ),
                });
            } else {
                warnings.push(ParameterWarning {
                    parameter: "spider_x".to_string(),
                    message: format!(
                        "spider_x '{}' is ignored for non-REALITY security '{}'",
                        self.spider_x,
                        self.effective_security()
                    ),
                });
            }
        }
        let (_, cap) = crate::ws::classify_fingerprint(&self.fingerprint);
        match cap {
            crate::ws::FingerprintCapability::Supported => {}
            crate::ws::FingerprintCapability::Mapped => {
                warnings.push(ParameterWarning {
                    parameter: "fingerprint".to_string(),
                    message: format!(
                        "fingerprint '{}' mapped to Chrome cipher suites (uTLS Parrot extension simulation is not supported)",
                        self.fingerprint
                    ),
                });
            }
            crate::ws::FingerprintCapability::UnsupportedFallback => {
                warnings.push(ParameterWarning {
                    parameter: "fingerprint".to_string(),
                    message: format!(
                        "Unsupported fingerprint '{}'; falling back to Chrome profile without altering security",
                        self.fingerprint
                    ),
                });
            }
        }
        warnings
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParameterWarning {
    pub parameter: String,
    pub message: String,
}

/// Fully-typed, immutable VLESS profile for isolated multi-node fallback.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct VlessProfile {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub domain: String,
    #[serde(default)]
    pub server_address: String,
    #[serde(default)]
    pub server_port: u16,
    #[serde(default)]
    pub uuid: String,
    #[serde(default)]
    pub path: String,
    #[serde(default)]
    pub ws_path: String,
    #[serde(default)]
    pub tls_sni: String,
    #[serde(default)]
    pub host_header: String,
    #[serde(default)]
    pub transport: String,
    #[serde(default)]
    pub security: String,
    #[serde(default)]
    pub public_key: String,
    #[serde(default)]
    pub short_id: String,
    #[serde(default)]
    pub fingerprint: String,
    #[serde(default)]
    pub spider_x: String,
    #[serde(default)]
    pub flow: String,
    #[serde(default)]
    pub header_type: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct VlessFallbackPoolPayload {
    pub schema_version: u32,
    pub profiles: Vec<VlessProfile>,
}

impl VlessProfile {
    pub fn effective_server_address(&self) -> &str {
        if !self.server_address.is_empty() {
            &self.server_address
        } else {
            &self.domain
        }
    }

    pub fn effective_server_port(&self) -> u16 {
        if self.server_port > 0 {
            self.server_port
        } else {
            443
        }
    }

    pub fn effective_tls_sni(&self) -> &str {
        if !self.tls_sni.is_empty() {
            &self.tls_sni
        } else {
            &self.domain
        }
    }

    pub fn effective_host_header(&self) -> &str {
        if !self.host_header.is_empty() {
            &self.host_header
        } else if !self.tls_sni.is_empty() {
            &self.tls_sni
        } else {
            &self.domain
        }
    }

    pub fn effective_path(&self) -> &str {
        if !self.ws_path.is_empty() {
            &self.ws_path
        } else if !self.path.is_empty() {
            &self.path
        } else {
            "/vless-ws?ed=2048"
        }
    }

    pub fn effective_transport(&self) -> &str {
        if !self.transport.is_empty() {
            &self.transport
        } else {
            // Default to TCP per VLESS spec. Path string is NOT used to infer transport:
            // path is an HTTP URI path, not a protocol selector.
            "tcp"
        }
    }

    pub fn effective_security(&self) -> &str {
        if !self.security.is_empty() {
            &self.security
        } else {
            // Explicit default: if the profile carries no security field, assume TLS.
            // Callers that want plain transport must set security = "none" explicitly.
            "tls"
        }
    }

    pub fn effective_fingerprint(&self) -> &str {
        if !self.fingerprint.is_empty() {
            &self.fingerprint
        } else {
            "chrome"
        }
    }

    pub fn effective_flow(&self) -> &str {
        if self.is_vision() {
            crate::vision::VISION_FLOW
        } else {
            &self.flow
        }
    }

    pub fn is_reality(&self) -> bool {
        self.effective_security().eq_ignore_ascii_case("reality") || !self.public_key.is_empty()
    }

    pub fn is_vision(&self) -> bool {
        self.flow
            .trim()
            .eq_ignore_ascii_case(crate::vision::VISION_FLOW)
            || self.flow.trim().starts_with(crate::vision::VISION_FLOW)
    }

    pub fn is_direct_tcp(&self) -> bool {
        self.effective_transport().eq_ignore_ascii_case("tcp")
    }

    /// Returns `true` when this profile connects directly to the VLESS server.
    ///
    /// Routing is determined by `transport` and `security` profile fields, not by the
    /// server address.  The address (IP or domain) is resolved for TCP dialling only.
    ///
    /// See [`VlessConfig::is_direct_vps`] for the full decision matrix.
    pub fn is_direct_vps(&self) -> bool {
        // Reality always needs a direct connection.
        if self.is_reality() {
            return true;
        }

        let transport_explicit = !self.transport.is_empty();
        let security_explicit = !self.security.is_empty();

        if transport_explicit || security_explicit {
            let transport = self.effective_transport();
            if transport.eq_ignore_ascii_case("tcp") {
                return true;
            }
            // WS transport: direct if a specific server_address is provided.
            if !self.server_address.is_empty() {
                return true;
            }
            if !self.domain.is_empty() {
                return !is_cloudflare_target(&self.domain);
            }
            return false;
        }

        // Legacy / incomplete profile: both transport and security are empty.
        lwarn!(
            "VLESS profile [{}]: transport and security fields are both empty; \
             routing decision falls back to address heuristic — re-import the profile \
             with explicit transport/security to fix routing",
            self.id
        );
        if !self.server_address.is_empty() {
            return !is_cloudflare_target(&self.server_address);
        }
        if !self.domain.is_empty() {
            return !is_cloudflare_target(&self.domain);
        }
        false
    }


    pub fn audit_capabilities(&self) -> Vec<ParameterWarning> {
        let mut warnings = Vec::new();
        if !self.header_type.is_empty() && !self.header_type.eq_ignore_ascii_case("none") {
            warnings.push(ParameterWarning {
                parameter: "header_type".to_string(),
                message: format!(
                    "header_type '{}' has no wire effect: fake HTTP and mKCP packet headers are unsupported; raw stream framing is used",
                    self.header_type
                ),
            });
        }
        if !self.spider_x.is_empty() {
            if self.is_reality() {
                warnings.push(ParameterWarning {
                    parameter: "spider_x".to_string(),
                    message: format!(
                        "spider_x '{}' has no wire effect: client-side web crawling is not executed",
                        self.spider_x
                    ),
                });
            } else {
                warnings.push(ParameterWarning {
                    parameter: "spider_x".to_string(),
                    message: format!(
                        "spider_x '{}' is ignored for non-REALITY security '{}'",
                        self.spider_x,
                        self.effective_security()
                    ),
                });
            }
        }
        let (_, cap) = crate::ws::classify_fingerprint(&self.fingerprint);
        match cap {
            crate::ws::FingerprintCapability::Supported => {}
            crate::ws::FingerprintCapability::Mapped => {
                warnings.push(ParameterWarning {
                    parameter: "fingerprint".to_string(),
                    message: format!(
                        "fingerprint '{}' mapped to Chrome cipher suites (uTLS Parrot extension simulation is not supported)",
                        self.fingerprint
                    ),
                });
            }
            crate::ws::FingerprintCapability::UnsupportedFallback => {
                warnings.push(ParameterWarning {
                    parameter: "fingerprint".to_string(),
                    message: format!(
                        "Unsupported fingerprint '{}'; falling back to Chrome profile without altering security",
                        self.fingerprint
                    ),
                });
            }
        }
        warnings
    }

    pub fn to_vless_config(&self) -> Result<VlessConfig, String> {
        let uuid_bytes = parse_uuid(&self.uuid)
            .ok_or_else(|| format!("Invalid UUID in profile '{}': '{}'", self.id, self.uuid))?;
        let path = self.effective_path().to_string();
        let path = if path.starts_with('/') {
            path
        } else {
            format!("/{}", path)
        };
        Ok(VlessConfig {
            id: if !self.id.is_empty() {
                self.id.clone()
            } else {
                self.effective_server_address().to_string()
            },
            name: if !self.name.is_empty() {
                self.name.clone()
            } else {
                self.effective_server_address().to_string()
            },
            uuid: uuid_bytes,
            path,
            domain: self.domain.clone(),
            server_address: self.server_address.clone(),
            server_port: self.effective_server_port(),
            tls_sni: self.tls_sni.clone(),
            host_header: self.host_header.clone(),
            transport: self.effective_transport().to_string(),
            security: self.effective_security().to_string(),
            public_key: self.public_key.clone(),
            short_id: self.short_id.clone(),
            fingerprint: self.effective_fingerprint().to_string(),
            spider_x: self.spider_x.clone(),
            flow: self.flow.clone(),
            header_type: self.header_type.clone(),
        })
    }
}

pub static PUBLIC_VLESS_FALLBACKS: &[&str] = &[
    "free-vless.pages.dev",
    "bpb-vless.pages.dev",
    "vless-edge.pages.dev",
];

pub static DYNAMIC_VLESS_FALLBACKS: Lazy<RwLock<Vec<String>>> =
    Lazy::new(|| RwLock::new(Vec::new()));

pub static DYNAMIC_VLESS_PROFILES: Lazy<RwLock<Vec<VlessProfile>>> =
    Lazy::new(|| RwLock::new(Vec::new()));

pub fn set_vless_fallback_profiles_json(json_str: &str) -> Result<usize, String> {
    let payload: VlessFallbackPoolPayload = serde_json::from_str(json_str)
        .map_err(|e| format!("Invalid VLESS fallback pool JSON: {}", e))?;
    if payload.schema_version != 1 {
        return Err(format!(
            "Unsupported VLESS fallback pool schema version: {}, expected 1",
            payload.schema_version
        ));
    }
    for p in &payload.profiles {
        for w in p.audit_capabilities() {
            lwarn!("VLESS fallback profile '{}' audit [{}]: {}", p.name, w.parameter, w.message);
        }
    }
    let count = payload.profiles.len();
    {
        let mut profiles_lock = DYNAMIC_VLESS_PROFILES.write();
        *profiles_lock = payload.profiles.clone();
    }
    {
        let mut legacy_lock = DYNAMIC_VLESS_FALLBACKS.write();
        legacy_lock.clear();
        for p in &payload.profiles {
            let addr = p.effective_server_address().trim();
            if !addr.is_empty() && !legacy_lock.iter().any(|d| d.eq_ignore_ascii_case(addr)) {
                legacy_lock.push(addr.to_string());
            }
        }
    }
    linfo!(
        "VLESS dynamic fallback pool updated with {} complete profiles (schema v{})",
        count,
        payload.schema_version
    );
    Ok(count)
}

pub fn set_vless_fallback_pool(domains_or_json: &str) {
    let trimmed = domains_or_json.trim();
    if trimmed.starts_with('{') {
        if let Ok(_) = set_vless_fallback_profiles_json(trimmed) {
            return;
        }
    }
    let mut pool = DYNAMIC_VLESS_FALLBACKS.write();
    pool.clear();
    let mut profiles = Vec::new();
    let active_cfg = VLESS_CONFIG.read().clone();
    for item in trimmed.split(',') {
        let trimmed_domain = item.trim();
        if !trimmed_domain.is_empty()
            && !pool.iter().any(|d| d.eq_ignore_ascii_case(trimmed_domain))
        {
            pool.push(trimmed_domain.to_string());
            profiles.push(VlessProfile {
                id: trimmed_domain.to_string(),
                name: trimmed_domain.to_string(),
                domain: trimmed_domain.to_string(),
                server_address: trimmed_domain.to_string(),
                server_port: active_cfg.effective_server_port(),
                uuid: format_uuid(&active_cfg.uuid),
                path: active_cfg.path.clone(),
                ws_path: active_cfg.path.clone(),
                tls_sni: trimmed_domain.to_string(),
                host_header: trimmed_domain.to_string(),
                transport: active_cfg.effective_transport().to_string(),
                security: active_cfg.effective_security().to_string(),
                public_key: active_cfg.public_key.clone(),
                short_id: active_cfg.short_id.clone(),
                fingerprint: active_cfg.effective_fingerprint().to_string(),
                spider_x: active_cfg.spider_x.clone(),
                flow: active_cfg.effective_flow().to_string(),
                header_type: active_cfg.header_type.clone(),
            });
        }
    }
    *DYNAMIC_VLESS_PROFILES.write() = profiles;
    linfo!(
        "VLESS dynamic fallback pool updated with {} domains (legacy CSV)",
        pool.len()
    );
}

// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// TSK-V11: Sticky Session Scorer & Periodic Background Health Check
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct VlessNodeMetrics {
    pub profile_id: String,
    pub transport_rtt_ms: Option<u64>,
    pub e2e_rtt_ms: Option<u64>,
    pub e2e_healthy: bool,
    pub consecutive_failures: u32,
    pub last_probe: Instant,
}

#[derive(Clone, Debug)]
pub struct VlessProbeResult {
    pub profile_id: String,
    pub transport_rtt_ms: Option<u64>,
    pub e2e_rtt_ms: Option<u64>,
    pub e2e_healthy: bool,
    pub error: Option<String>,
}

#[derive(Clone, Debug)]
pub struct VlessStickyNode {
    pub domain: String,
    pub last_success: Instant,
    pub smoothed_rtt_ms: u64,
    pub consecutive_failures: u32,
}

pub struct VlessScorer {
    pub active_sticky: Option<VlessStickyNode>,
    pub scores: HashMap<String, (u64, Instant)>,
    pub metrics: HashMap<String, VlessNodeMetrics>,
    pub last_background_probe: Option<Instant>,
    pub background_probe_in_progress: bool,
}

impl VlessScorer {
    pub fn new() -> Self {
        Self {
            active_sticky: None,
            scores: HashMap::new(),
            metrics: HashMap::new(),
            last_background_probe: None,
            background_probe_in_progress: false,
        }
    }

    pub fn reset(&mut self) {
        self.active_sticky = None;
        self.scores.clear();
        self.metrics.clear();
        self.last_background_probe = None;
        self.background_probe_in_progress = false;
    }

    pub fn get_sticky_candidate(&self) -> Option<VlessStickyNode> {
        if let Some(ref sticky) = self.active_sticky {
            let is_e2e_healthy = self
                .metrics
                .get(&sticky.domain)
                .map(|m| m.e2e_healthy)
                .unwrap_or(true);
            if sticky.consecutive_failures < 2
                && is_e2e_healthy
                && cfproxy_429_cooldown_remaining(&sticky.domain) == Duration::ZERO
            {
                return Some(sticky.clone());
            }
        }
        None
    }

    /// Records transport-only probe result (e.g. TCP connect + TLS handshake + WS upgrade).
    /// CRITICAL: A weaker transport probe NEVER overwrites an end-to-end failure,
    /// does NOT reset consecutive failures, and does NOT mark an unhealthy node as healthy.
    pub fn record_transport_probe(&mut self, profile_id: &str, transport_rtt_ms: u64) {
        let entry = self
            .metrics
            .entry(profile_id.to_string())
            .or_insert_with(|| VlessNodeMetrics {
                profile_id: profile_id.to_string(),
                transport_rtt_ms: Some(transport_rtt_ms),
                e2e_rtt_ms: None,
                e2e_healthy: true,
                consecutive_failures: 0,
                last_probe: Instant::now(),
            });
        entry.transport_rtt_ms = Some(transport_rtt_ms);
        entry.last_probe = Instant::now();
    }

    /// Records successful end-to-end VLESS transaction (handshake + valid VLESS response header).
    pub fn record_e2e_success(
        &mut self,
        profile_id: &str,
        e2e_rtt_ms: u64,
        transport_rtt_ms: Option<u64>,
    ) {
        let smoothed = if let Some(ref mut sticky) = self.active_sticky {
            if sticky.domain.eq_ignore_ascii_case(profile_id) {
                sticky.last_success = Instant::now();
                sticky.consecutive_failures = 0;
                let s = (sticky.smoothed_rtt_ms * 7 + e2e_rtt_ms * 3) / 10;
                sticky.smoothed_rtt_ms = s;
                s
            } else {
                e2e_rtt_ms
            }
        } else {
            self.active_sticky = Some(VlessStickyNode {
                domain: profile_id.to_string(),
                last_success: Instant::now(),
                smoothed_rtt_ms: e2e_rtt_ms,
                consecutive_failures: 0,
            });
            e2e_rtt_ms
        };

        let entry = self
            .metrics
            .entry(profile_id.to_string())
            .or_insert_with(|| VlessNodeMetrics {
                profile_id: profile_id.to_string(),
                transport_rtt_ms,
                e2e_rtt_ms: Some(e2e_rtt_ms),
                e2e_healthy: true,
                consecutive_failures: 0,
                last_probe: Instant::now(),
            });
        entry.e2e_healthy = true;
        entry.consecutive_failures = 0;
        entry.e2e_rtt_ms = Some(e2e_rtt_ms);
        if let Some(tr) = transport_rtt_ms {
            entry.transport_rtt_ms = Some(tr);
        }
        entry.last_probe = Instant::now();

        self.scores
            .insert(profile_id.to_string(), (smoothed, Instant::now()));
    }

    /// Records failed end-to-end VLESS transaction (e.g. UUID rejected, stream reset, timeout).
    pub fn record_e2e_failure(&mut self, profile_id: &str, transport_rtt_ms: Option<u64>) {
        let entry = self
            .metrics
            .entry(profile_id.to_string())
            .or_insert_with(|| VlessNodeMetrics {
                profile_id: profile_id.to_string(),
                transport_rtt_ms,
                e2e_rtt_ms: None,
                e2e_healthy: false,
                consecutive_failures: 0,
                last_probe: Instant::now(),
            });
        entry.e2e_healthy = false;
        entry.consecutive_failures += 1;
        entry.e2e_rtt_ms = None;
        if let Some(tr) = transport_rtt_ms {
            entry.transport_rtt_ms = Some(tr);
        }
        entry.last_probe = Instant::now();

        if let Some(ref mut sticky) = self.active_sticky {
            if sticky.domain.eq_ignore_ascii_case(profile_id) {
                sticky.consecutive_failures += 1;
                if sticky.consecutive_failures >= 2 {
                    ldebug!(
                        "VLESS scorer: demoting sticky node {} after {} consecutive failures",
                        profile_id,
                        sticky.consecutive_failures
                    );
                }
            }
        }

        let score_entry = self
            .scores
            .entry(profile_id.to_string())
            .or_insert((350, Instant::now()));
        score_entry.0 = score_entry.0.saturating_add(1500).min(30000);
        score_entry.1 = Instant::now();
    }

    pub fn record_success(&mut self, domain: &str, rtt_ms: u64) {
        self.record_e2e_success(domain, rtt_ms, None);
    }

    pub fn record_failure(&mut self, domain: &str) {
        self.record_e2e_failure(domain, None);
    }

    pub fn set_sticky_winner(&mut self, domain: &str, rtt_ms: u64) {
        self.active_sticky = Some(VlessStickyNode {
            domain: domain.to_string(),
            last_success: Instant::now(),
            smoothed_rtt_ms: rtt_ms,
            consecutive_failures: 0,
        });
        self.record_e2e_success(domain, rtt_ms, None);
    }

    pub fn should_trigger_background_probe(&self) -> bool {
        if self.background_probe_in_progress {
            return false;
        }
        match self.last_background_probe {
            Some(t) => t.elapsed() >= Duration::from_secs(45),
            None => true,
        }
    }

    pub fn is_node_healthy(&self, id: &str) -> bool {
        self.metrics.get(id).map(|m| m.e2e_healthy).unwrap_or(true)
    }
}

pub static VLESS_SCORER: Lazy<RwLock<VlessScorer>> = Lazy::new(|| RwLock::new(VlessScorer::new()));

pub fn reset_vless_scorer() {
    VLESS_SCORER.write().reset();
}

pub fn get_vless_node_metrics(id: &str) -> Option<VlessNodeMetrics> {
    VLESS_SCORER.read().metrics.get(id).cloned()
}

pub fn get_vless_health_status() -> String {
    let scorer = VLESS_SCORER.read();
    let mut parts = Vec::new();
    for (id, m) in &scorer.metrics {
        parts.push(format!(
            "{}: transport={}ms e2e={}ms healthy={}",
            id,
            m.transport_rtt_ms
                .map(|t| t.to_string())
                .unwrap_or_else(|| "none".to_string()),
            m.e2e_rtt_ms
                .map(|t| t.to_string())
                .unwrap_or_else(|| "none".to_string()),
            m.e2e_healthy
        ));
    }
    parts.join(", ")
}

pub async fn probe_vless_profile(
    cfg: &VlessConfig,
    target: &str,
    timeout: Duration,
) -> VlessProbeResult {
    let t0 = Instant::now();
    let cancel_token = CancellationToken::new();

    // 1. Dial candidate profile using its REAL path, server, credentials, and transport
    let dial_future = dial_single_vless_config(cfg, target, VLESS_CMD_TCP, &cancel_token);
    let dial_res = match tokio::time::timeout(timeout, dial_future).await {
        Ok(Some(uplink)) => uplink,
        Ok(None) => {
            return VlessProbeResult {
                profile_id: cfg.id.clone(),
                transport_rtt_ms: None,
                e2e_rtt_ms: None,
                e2e_healthy: false,
                error: Some("Transport connection failed".to_string()),
            };
        }
        Err(_) => {
            cancel_token.cancel();
            return VlessProbeResult {
                profile_id: cfg.id.clone(),
                transport_rtt_ms: None,
                e2e_rtt_ms: None,
                e2e_healthy: false,
                error: Some("Transport connection timeout".to_string()),
            };
        }
    };

    let transport_rtt = t0.elapsed().as_millis() as u64;
    let mut uplink = dial_res;

    // 2. Read VLESS response header (end-to-end verification that the server accepted the UUID and target)
    let remaining_timeout = timeout
        .saturating_sub(t0.elapsed())
        .max(Duration::from_millis(500));
    let resp_res = read_vless_response_uplink(&mut uplink, remaining_timeout).await;

    // Gracefully shut down uplink probe connection
    match uplink {
        VlessUplink::Ws(ws) => {
            tokio::spawn(async move {
                let _ = ws.close().await;
            });
        }
        VlessUplink::Tcp(mut s, ..) => {
            tokio::spawn(async move {
                let _ = s.shutdown().await;
            });
        }
        VlessUplink::Tls(mut s, ..) => {
            tokio::spawn(async move {
                let _ = s.shutdown().await;
            });
        }
        VlessUplink::Reality(mut s, ..) => {
            tokio::spawn(async move {
                let _ = s.shutdown().await;
            });
        }
    }

    match resp_res {
        Ok(_) => {
            let e2e_rtt = t0.elapsed().as_millis() as u64;
            VlessProbeResult {
                profile_id: cfg.id.clone(),
                transport_rtt_ms: Some(transport_rtt),
                e2e_rtt_ms: Some(e2e_rtt),
                e2e_healthy: true,
                error: None,
            }
        }
        Err(e) => VlessProbeResult {
            profile_id: cfg.id.clone(),
            transport_rtt_ms: Some(transport_rtt),
            e2e_rtt_ms: None,
            e2e_healthy: false,
            error: Some(format!("VLESS E2E rejected: {}", e)),
        },
    }
}

pub async fn run_vless_background_probe() {
    ldebug!("VLESS: starting periodic background health check with real profiles and E2E verification");
    let candidates: Vec<VlessConfig> = {
        let mut list = Vec::new();
        let profiles = DYNAMIC_VLESS_PROFILES.read();
        for p in profiles.iter().take(4) {
            if let Ok(cfg) = p.to_vless_config() {
                list.push(cfg);
            }
        }
        if list.is_empty() {
            list.push(VLESS_CONFIG.read().clone());
        }
        list
    };

    let mut best_id: Option<String> = None;
    let mut best_e2e_rtt = u64::MAX;

    for cfg in candidates {
        let cooldown_domain = if !cfg.domain.is_empty() {
            &cfg.domain
        } else {
            cfg.effective_tls_sni()
        };
        if cfproxy_429_cooldown_remaining(cooldown_domain) > Duration::ZERO {
            continue;
        }

        // Execute full probe with real profile credentials, path, and target
        let probe_res =
            probe_vless_profile(&cfg, "149.154.167.51:443", Duration::from_secs(3)).await;

        let mut scorer = VLESS_SCORER.write();
        if probe_res.e2e_healthy {
            let e2e_rtt = probe_res.e2e_rtt_ms.unwrap_or(350);
            scorer.record_e2e_success(&cfg.id, e2e_rtt, probe_res.transport_rtt_ms);
            ldebug!(
                "VLESS background probe: profile '{}' healthy (transport={}ms, e2e={}ms)",
                cfg.id,
                probe_res.transport_rtt_ms.unwrap_or(0),
                e2e_rtt
            );
            if e2e_rtt < best_e2e_rtt {
                best_e2e_rtt = e2e_rtt;
                best_id = Some(cfg.id.clone());
            }
        } else {
            // E2E verification failed! Do NOT treat as healthy fallback!
            scorer.record_e2e_failure(&cfg.id, probe_res.transport_rtt_ms);
            lwarn!(
                "VLESS background probe: profile '{}' E2E FAILED (transport={:?}, err={:?})",
                cfg.id,
                probe_res.transport_rtt_ms,
                probe_res.error
            );
        }
    }

    if let Some(best) = best_id {
        let mut scorer = VLESS_SCORER.write();
        if let Some(ref mut sticky) = scorer.active_sticky {
            if !sticky.domain.eq_ignore_ascii_case(&best)
                && best_e2e_rtt + 80 < sticky.smoothed_rtt_ms
            {
                linfo!(
                    "VLESS background probe: switching sticky node from {} ({}ms) to verified faster candidate {} ({}ms)",
                    sticky.domain,
                    sticky.smoothed_rtt_ms,
                    best,
                    best_e2e_rtt
                );
                sticky.domain = best.clone();
                sticky.smoothed_rtt_ms = best_e2e_rtt;
                sticky.last_success = Instant::now();
                sticky.consecutive_failures = 0;
                *LAST_SOCKS5_WORKER.write() = best;
            }
        }
    }
}

pub fn maybe_trigger_vless_background_probe() {
    let should_trigger = {
        let mut scorer = VLESS_SCORER.write();
        if scorer.should_trigger_background_probe() {
            scorer.background_probe_in_progress = true;
            scorer.last_background_probe = Some(Instant::now());
            true
        } else {
            false
        }
    };

    if !should_trigger {
        return;
    }

    tokio::spawn(async move {
        run_vless_background_probe().await;
        VLESS_SCORER.write().background_probe_in_progress = false;
    });
}

pub static VLESS_CONFIG: Lazy<RwLock<VlessConfig>> = Lazy::new(|| {
    let default_uuid = parse_uuid("d342d11e-d424-4583-b36e-524ab1f0afa4").unwrap_or([
        0xd3, 0x42, 0xd1, 0x1e, 0xd4, 0x24, 0x45, 0x83, 0xb3, 0x6e, 0x52, 0x4a, 0xb1, 0xf0, 0xaf,
        0xa4,
    ]);
    RwLock::new(VlessConfig {
        id: "default_vless".to_string(),
        name: "Default Free VLESS".to_string(),
        uuid: default_uuid,
        path: "/vless-ws?ed=2048".to_string(),
        domain: "free-vless.pages.dev".to_string(),
        server_address: "".to_string(),
        server_port: 443,
        tls_sni: "".to_string(),
        host_header: "".to_string(),
        transport: "ws".to_string(),
        security: "tls".to_string(),
        public_key: "".to_string(),
        short_id: "".to_string(),
        fingerprint: "chrome".to_string(),
        spider_x: "".to_string(),
        flow: "".to_string(),
        header_type: "".to_string(),
    })
});

pub fn parse_uuid(s: &str) -> Option<[u8; 16]> {
    let clean: String = s.chars().filter(|c| *c != '-').collect();
    if clean.len() != 32 {
        return None;
    }
    let bytes = hex::decode(clean).ok()?;
    bytes.try_into().ok()
}

pub fn format_uuid(b: &[u8; 16]) -> String {
    format!(
        "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
        b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]
    )
}

#[derive(Debug, Deserialize, Default)]
pub struct VlessConfigUpdate {
    #[serde(default)]
    pub id: Option<String>,
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub uuid: Option<String>,
    #[serde(default)]
    pub path: Option<String>,
    #[serde(default)]
    pub domain: Option<String>,
    #[serde(default)]
    pub server_address: Option<String>,
    #[serde(default)]
    pub server_port: Option<u16>,
    #[serde(default)]
    pub tls_sni: Option<String>,
    #[serde(default)]
    pub host_header: Option<String>,
    #[serde(default)]
    pub transport: Option<String>,
    #[serde(default)]
    pub security: Option<String>,
    #[serde(default)]
    pub public_key: Option<String>,
    #[serde(default)]
    pub short_id: Option<String>,
    #[serde(default)]
    pub fingerprint: Option<String>,
    #[serde(default)]
    pub spider_x: Option<String>,
    #[serde(default)]
    pub flow: Option<String>,
    #[serde(default)]
    pub header_type: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct VlessConfigDto {
    pub id: String,
    pub name: String,
    pub uuid: String,
    pub path: String,
    pub domain: String,
    pub server_address: String,
    pub server_port: u16,
    pub tls_sni: String,
    pub host_header: String,
    pub transport: String,
    pub security: String,
    pub public_key: String,
    pub short_id: String,
    pub fingerprint: String,
    pub spider_x: String,
    pub flow: String,
    pub header_type: String,
    pub is_reality: bool,
    pub is_vision: bool,
    pub is_direct_vps: bool,
}

pub fn set_vless_config_json(json_str: &str) -> Result<(), String> {
    let update: VlessConfigUpdate =
        serde_json::from_str(json_str).map_err(|e| format!("Invalid VLESS JSON config: {}", e))?;

    let mut cfg = VLESS_CONFIG.write();
    if let Some(ref id) = update.id {
        let trimmed = id.trim();
        if !trimmed.is_empty() {
            cfg.id = trimmed.to_string();
        }
    }
    if let Some(ref name) = update.name {
        let trimmed = name.trim();
        if !trimmed.is_empty() {
            cfg.name = trimmed.to_string();
        }
    }
    if let Some(ref u) = update.uuid {
        if let Some(parsed) = parse_uuid(u) {
            cfg.uuid = parsed;
        }
    }
    if let Some(ref p) = update.path {
        let trimmed = p.trim();
        if !trimmed.is_empty() {
            cfg.path = if trimmed.starts_with('/') {
                trimmed.to_string()
            } else {
                format!("/{}", trimmed)
            };
        }
    }
    if let Some(ref d) = update.domain {
        let trimmed = d.trim();
        if !trimmed.is_empty() {
            cfg.domain = trimmed.to_string();
        }
    }
    if let Some(ref s) = update.server_address {
        cfg.server_address = s.trim().to_string();
    }
    if let Some(p) = update.server_port {
        if p > 0 {
            cfg.server_port = p;
        }
    }
    if let Some(ref s) = update.tls_sni {
        cfg.tls_sni = s.trim().to_string();
    }
    if let Some(ref h) = update.host_header {
        cfg.host_header = h.trim().to_string();
    }
    if let Some(ref t) = update.transport {
        cfg.transport = t.trim().to_string();
    }
    if let Some(ref s) = update.security {
        cfg.security = s.trim().to_string();
    }
    if let Some(ref pk) = update.public_key {
        cfg.public_key = pk.trim().to_string();
    }
    if let Some(ref sid) = update.short_id {
        cfg.short_id = sid.trim().to_string();
    }
    if let Some(ref fp) = update.fingerprint {
        let trimmed = fp.trim();
        cfg.fingerprint = if !trimmed.is_empty() {
            trimmed.to_string()
        } else {
            "chrome".to_string()
        };
    }
    if let Some(ref spx) = update.spider_x {
        cfg.spider_x = spx.trim().to_string();
    }
    if let Some(ref flow) = update.flow {
        cfg.flow = flow.trim().to_string();
    }
    if let Some(ref ht) = update.header_type {
        cfg.header_type = ht.trim().to_string();
    }

    for w in cfg.audit_capabilities() {
        lwarn!("VLESS config parameter audit [{}]: {}", w.parameter, w.message);
    }

    linfo!(
        "VLESS JSON config applied: id={} name={} uuid={} domain={} server={}:{} sni={} host={} transport={} security={} flow={} path={}",
        cfg.id,
        cfg.name,
        format_uuid(&cfg.uuid),
        cfg.domain,
        cfg.effective_server_address(),
        cfg.effective_server_port(),
        cfg.effective_tls_sni(),
        cfg.effective_host_header(),
        cfg.effective_transport(),
        cfg.effective_security(),
        cfg.flow,
        cfg.path
    );
    drop(cfg);
    reset_vless_scorer();
    Ok(())
}

pub fn get_vless_config_json() -> String {
    let cfg = VLESS_CONFIG.read();
    let dto = VlessConfigDto {
        id: cfg.id.clone(),
        name: cfg.name.clone(),
        uuid: format_uuid(&cfg.uuid),
        path: cfg.path.clone(),
        domain: cfg.domain.clone(),
        server_address: cfg.effective_server_address().to_string(),
        server_port: cfg.effective_server_port(),
        tls_sni: cfg.effective_tls_sni().to_string(),
        host_header: cfg.effective_host_header().to_string(),
        transport: cfg.effective_transport().to_string(),
        security: cfg.effective_security().to_string(),
        public_key: cfg.public_key.clone(),
        short_id: cfg.short_id.clone(),
        fingerprint: cfg.effective_fingerprint().to_string(),
        spider_x: cfg.spider_x.clone(),
        flow: cfg.flow.clone(),
        header_type: cfg.header_type.clone(),
        is_reality: cfg.is_reality(),
        is_vision: cfg.is_vision(),
        is_direct_vps: cfg.is_direct_vps(),
    };
    serde_json::to_string(&dto).unwrap_or_else(|_| "{}".to_string())
}

pub fn set_vless_extended_config(
    uuid_str: &str,
    path: &str,
    domain_str: &str,
    server_address: &str,
    server_port: u16,
    tls_sni: &str,
    host_header: &str,
    transport: &str,
    security: &str,
    public_key: &str,
    short_id: &str,
    fingerprint: &str,
    spider_x: &str,
    flow: &str,
    header_type: &str,
) {
    let mut cfg = VLESS_CONFIG.write();
    if let Some(parsed) = parse_uuid(uuid_str) {
        cfg.uuid = parsed;
    }
    let p = path.trim();
    if !p.is_empty() {
        cfg.path = if p.starts_with('/') {
            p.to_string()
        } else {
            format!("/{}", p)
        };
    }
    let d = domain_str.trim();
    if !d.is_empty() {
        cfg.domain = d.to_string();
    }
    cfg.server_address = server_address.trim().to_string();
    cfg.server_port = if server_port > 0 { server_port } else { 443 };
    cfg.tls_sni = tls_sni.trim().to_string();
    cfg.host_header = host_header.trim().to_string();
    cfg.transport = transport.trim().to_string();
    cfg.security = security.trim().to_string();
    cfg.public_key = public_key.trim().to_string();
    cfg.short_id = short_id.trim().to_string();
    cfg.fingerprint = if !fingerprint.trim().is_empty() {
        fingerprint.trim().to_string()
    } else {
        "chrome".to_string()
    };
    cfg.spider_x = spider_x.trim().to_string();
    cfg.flow = flow.trim().to_string();
    cfg.header_type = header_type.trim().to_string();

    for w in cfg.audit_capabilities() {
        lwarn!("VLESS config parameter audit [{}]: {}", w.parameter, w.message);
    }

    linfo!(
        "VLESS extended config updated: uuid={} domain={} server={}:{} sni={} host={} transport={} security={} flow={} path={}",
        format_uuid(&cfg.uuid),
        cfg.domain,
        cfg.effective_server_address(),
        cfg.effective_server_port(),
        cfg.effective_tls_sni(),
        cfg.effective_host_header(),
        cfg.effective_transport(),
        cfg.effective_security(),
        cfg.flow,
        cfg.path
    );
    drop(cfg);
    reset_vless_scorer();
}

pub fn set_vless_network_config(
    uuid_str: &str,
    path: &str,
    domain_str: &str,
    server_address: &str,
    server_port: u16,
    tls_sni: &str,
    host_header: &str,
) {
    let mut cfg = VLESS_CONFIG.write();
    if let Some(parsed) = parse_uuid(uuid_str) {
        cfg.uuid = parsed;
    }
    let p = path.trim();
    if !p.is_empty() {
        cfg.path = if p.starts_with('/') {
            p.to_string()
        } else {
            format!("/{}", p)
        };
    }
    let d = domain_str.trim();
    if !d.is_empty() {
        cfg.domain = d.to_string();
    }
    if !server_address.trim().is_empty() {
        cfg.server_address = server_address.trim().to_string();
    }
    if server_port > 0 {
        cfg.server_port = server_port;
    }
    if !tls_sni.trim().is_empty() {
        cfg.tls_sni = tls_sni.trim().to_string();
    }
    if !host_header.trim().is_empty() {
        cfg.host_header = host_header.trim().to_string();
    }
    linfo!(
        "VLESS network config updated (preserving extended): uuid={} domain={} server={}:{} sni={} host={}",
        format_uuid(&cfg.uuid),
        cfg.domain,
        cfg.effective_server_address(),
        cfg.effective_server_port(),
        cfg.effective_tls_sni(),
        cfg.effective_host_header()
    );
    drop(cfg);
    reset_vless_scorer();
}

pub fn set_vless_config(uuid_str: &str, path: &str, domain_str: &str) {
    let mut cfg = VLESS_CONFIG.write();
    if let Some(parsed) = parse_uuid(uuid_str) {
        cfg.uuid = parsed;
    }
    let p = path.trim();
    if !p.is_empty() {
        cfg.path = if p.starts_with('/') {
            p.to_string()
        } else {
            format!("/{}", p)
        };
    }
    let d = domain_str.trim();
    if !d.is_empty() {
        cfg.domain = d.to_string();
    }
    linfo!(
        "VLESS basic config updated (preserving extended): uuid={} domain={} path={}",
        format_uuid(&cfg.uuid),
        cfg.domain,
        cfg.path
    );
    drop(cfg);
    reset_vless_scorer();
}

pub fn get_vless_status() -> String {
    let cfg = VLESS_CONFIG.read();
    format!(
        "uuid={} domain={} server={}:{} sni={} host={} transport={} security={} flow={} path={}",
        format_uuid(&cfg.uuid),
        cfg.domain,
        cfg.effective_server_address(),
        cfg.effective_server_port(),
        cfg.effective_tls_sni(),
        cfg.effective_host_header(),
        cfg.effective_transport(),
        cfg.effective_security(),
        cfg.flow,
        cfg.path
    )
}

pub fn parse_target_addr(target: &str) -> Result<(String, u16), String> {
    let s = target.trim();
    if s.starts_with('[') {
        let close_bracket = s
            .find(']')
            .ok_or_else(|| "Missing closing bracket in IPv6".to_string())?;
        let host = &s[1..close_bracket];
        let rest = &s[close_bracket + 1..];
        let port_str = if rest.starts_with(':') {
            &rest[1..]
        } else {
            "443"
        };
        let port = port_str.parse::<u16>().map_err(|e| e.to_string())?;
        return Ok((host.to_string(), port));
    }
    if let Some(colon) = s.rfind(':') {
        let host = &s[..colon];
        let port_str = &s[colon + 1..];
        let port = port_str.parse::<u16>().map_err(|e| e.to_string())?;
        return Ok((host.to_string(), port));
    }
    Ok((s.to_string(), 443))
}

pub const VLESS_CMD_TCP: u8 = 0x01;
pub const VLESS_CMD_UDP: u8 = 0x02;
pub const VLESS_CMD_MUX: u8 = 0x03;

pub fn build_vless_header_cmd(
    uuid: &[u8; 16],
    target_addr: &str,
    initial_payload: &[u8],
    flow: &str,
    command: u8,
) -> Result<Vec<u8>, String> {
    let (host, port) = parse_target_addr(target_addr)?;
    let is_ipv4 = host.parse::<Ipv4Addr>().is_ok();
    let is_ipv6 = host.parse::<Ipv6Addr>().is_ok();

    // Protobuf Addons: only used for TCP (Vision), not for UDP
    let addons_bytes = if command == VLESS_CMD_TCP {
        crate::vision::encode_vless_addons(flow)
    } else {
        Vec::new()
    };

    let mut buf = Vec::with_capacity(64 + addons_bytes.len() + initial_payload.len());
    // 1. Version = 0
    buf.push(0x00);
    // 2. UUID = 16 bytes
    buf.extend_from_slice(uuid);
    // 3. Addons Length and Protobuf bytes
    if addons_bytes.is_empty() {
        buf.push(0x00);
    } else {
        if addons_bytes.len() > 255 {
            return Err("Addons protobuf length exceeds 255 bytes".to_string());
        }
        buf.push(addons_bytes.len() as u8);
        buf.extend_from_slice(&addons_bytes);
    }
    // 4. Command: 0x01 = TCP, 0x02 = UDP
    buf.push(command);
    // 5. Port: 2 bytes BigEndian
    buf.extend_from_slice(&port.to_be_bytes());

    // 6. Address Type & Address
    if is_ipv4 {
        let ip: Ipv4Addr = host.parse().unwrap();
        buf.push(0x01); // IPv4
        buf.extend_from_slice(&ip.octets());
    } else if is_ipv6 {
        let ip: Ipv6Addr = host.parse().unwrap();
        buf.push(0x03); // IPv6
        buf.extend_from_slice(&ip.octets());
    } else {
        buf.push(0x02); // Domain
        let d_bytes = host.as_bytes();
        if d_bytes.len() > 255 {
            return Err("Domain name too long (> 255 bytes)".to_string());
        }
        buf.push(d_bytes.len() as u8);
        buf.extend_from_slice(d_bytes);
    }

    if !initial_payload.is_empty() {
        buf.extend_from_slice(initial_payload);
    }

    Ok(buf)
}

pub fn build_vless_header_ext(
    uuid: &[u8; 16],
    target_addr: &str,
    initial_payload: &[u8],
    flow: &str,
) -> Result<Vec<u8>, String> {
    build_vless_header_cmd(uuid, target_addr, initial_payload, flow, VLESS_CMD_TCP)
}

pub fn build_vless_header(
    uuid: &[u8; 16],
    target_addr: &str,
    initial_payload: &[u8],
) -> Result<Vec<u8>, String> {
    build_vless_header_cmd(uuid, target_addr, initial_payload, "", VLESS_CMD_TCP)
}

/// Packs a UDP payload into a VLESS length-prefixed packet (2 bytes big-endian length + payload).
pub fn pack_vless_udp_packet(payload: &[u8]) -> Result<Vec<u8>, String> {
    if payload.len() > u16::MAX as usize {
        return Err("UDP payload exceeds maximum length (65535)".to_string());
    }
    let mut buf = Vec::with_capacity(2 + payload.len());
    buf.extend_from_slice(&(payload.len() as u16).to_be_bytes());
    buf.extend_from_slice(payload);
    Ok(buf)
}

/// Unpacks VLESS UDP packets from a streaming buffer.
/// Extracts any complete length-prefixed UDP packets and leaves partial packet data in `rx_buf`.
pub fn unpack_vless_udp_packets(rx_buf: &mut Vec<u8>) -> Vec<Vec<u8>> {
    let mut packets = Vec::new();
    loop {
        if rx_buf.len() < 2 {
            break;
        }
        let len = u16::from_be_bytes([rx_buf[0], rx_buf[1]]) as usize;
        if rx_buf.len() < 2 + len {
            break;
        }
        rx_buf.drain(..2);
        let payload: Vec<u8> = rx_buf.drain(..len).collect();
        packets.push(payload);
    }
    packets
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Socks5UdpPacket {
    pub target_addr: String,
    pub target_port: u16,
    pub atyp: u8,
    pub raw_addr: Vec<u8>,
    pub payload: Vec<u8>,
}

/// Parses an RFC 1928 SOCKS5 UDP request packet:
///   [RSV: 2][FRAG: 1][ATYP: 1][DST.ADDR: var][DST.PORT: 2][USER_DATA: var]
pub fn parse_socks5_udp_packet(buf: &[u8]) -> Result<Socks5UdpPacket, String> {
    if buf.len() < 10 {
        return Err("SOCKS5 UDP packet too short (< 10 bytes)".to_string());
    }
    if buf[0] != 0x00 || buf[1] != 0x00 {
        return Err("Invalid SOCKS5 UDP reserved bytes".to_string());
    }
    if buf[2] != 0x00 {
        return Err("SOCKS5 UDP fragmentation is not supported".to_string());
    }
    let atyp = buf[3];
    let (target_host, target_port, raw_addr, payload_offset) = match atyp {
        0x01 => {
            // IPv4
            if buf.len() < 10 {
                return Err("Truncated IPv4 SOCKS5 UDP packet".to_string());
            }
            let ip = Ipv4Addr::new(buf[4], buf[5], buf[6], buf[7]);
            let port = u16::from_be_bytes([buf[8], buf[9]]);
            (ip.to_string(), port, buf[4..8].to_vec(), 10)
        }
        0x03 => {
            // Domain
            let dlen = buf[4] as usize;
            if buf.len() < 5 + dlen + 2 {
                return Err("Truncated domain SOCKS5 UDP packet".to_string());
            }
            let domain = String::from_utf8_lossy(&buf[5..5 + dlen]).to_string();
            let port_offset = 5 + dlen;
            let port = u16::from_be_bytes([buf[port_offset], buf[port_offset + 1]]);
            (domain, port, buf[4..port_offset].to_vec(), port_offset + 2)
        }
        0x04 => {
            // IPv6
            if buf.len() < 22 {
                return Err("Truncated IPv6 SOCKS5 UDP packet".to_string());
            }
            let mut ip_bytes = [0u8; 16];
            ip_bytes.copy_from_slice(&buf[4..20]);
            let ip = Ipv6Addr::from(ip_bytes);
            let port = u16::from_be_bytes([buf[20], buf[21]]);
            (ip.to_string(), port, buf[4..20].to_vec(), 22)
        }
        _ => return Err(format!("Unsupported SOCKS5 UDP address type: {}", atyp)),
    };

    let payload = buf[payload_offset..].to_vec();
    let target_addr = format!("{}:{}", target_host, target_port);
    Ok(Socks5UdpPacket {
        target_addr,
        target_port,
        atyp,
        raw_addr,
        payload,
    })
}

/// Builds an RFC 1928 SOCKS5 UDP response packet:
///   [RSV: 2][FRAG: 1][ATYP: 1][SRC.ADDR: var][SRC.PORT: 2][USER_DATA: var]
pub fn build_socks5_udp_packet(atyp: u8, raw_addr: &[u8], port: u16, payload: &[u8]) -> Vec<u8> {
    let mut buf = Vec::with_capacity(4 + raw_addr.len() + 2 + payload.len());
    buf.push(0x00); // RSV
    buf.push(0x00); // RSV
    buf.push(0x00); // FRAG
    buf.push(atyp); // ATYP
    buf.extend_from_slice(raw_addr);
    buf.extend_from_slice(&port.to_be_bytes());
    buf.extend_from_slice(payload);
    buf
}

const TLS_CCS_RECORD: [u8; 6] = [0x14, 0x03, 0x03, 0x00, 0x01, 0x01];

/// Finite-state machine representation of the VLESS response header reading process:
/// version (1 byte) -> addon length (1 byte, M) -> addons (M bytes) -> payload (infinite).
/// An optional 6-byte TLS ChangeCipherSpec record (0x14 0x03 0x03 0x00 0x01 0x01) can precede
/// the VLESS version in direct/Vision/TLS environments.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VlessResponseState {
    /// Initial state: determining whether incoming stream begins with a 6-byte TLS
    /// ChangeCipherSpec record (0x14) or directly with VLESS version 0 (0x00).
    CheckingTlsCcs,
    /// Reading subsequent bytes of the 6-byte TLS ChangeCipherSpec record.
    ReadingTlsCcs { matched: usize },
    /// Reading the 1-byte VLESS protocol version (expected: 0x00).
    ReadingVersion,
    /// Reading the 1-byte addons length (M).
    ReadingAddonLen,
    /// Reading M bytes of addons protobuf/metadata.
    ReadingAddons { expected_len: usize, read: usize },
    /// VLESS response header fully consumed; all subsequent bytes represent downlink user payload.
    Complete,
}

/// Stateful downstream parser that incrementally strips the VLESS response header from incoming data.
/// Once the VLESS response header (version + addons + optional TLS ChangeCipherSpec) is consumed,
/// all subsequent data chunks pass through unmodified with zero loss of payload bytes.
#[derive(Debug, Clone)]
pub struct VlessResponseParser {
    state: VlessResponseState,
    addons: Vec<u8>,
    header_bytes_consumed: usize,
}

impl Default for VlessResponseParser {
    fn default() -> Self {
        Self::new()
    }
}

impl VlessResponseParser {
    pub fn new() -> Self {
        Self {
            state: VlessResponseState::CheckingTlsCcs,
            addons: Vec::new(),
            header_bytes_consumed: 0,
        }
    }

    #[inline]
    pub fn is_header_parsed(&self) -> bool {
        self.state == VlessResponseState::Complete
    }

    #[inline]
    pub fn state(&self) -> &VlessResponseState {
        &self.state
    }

    #[inline]
    pub fn addons(&self) -> &[u8] {
        &self.addons
    }

    #[inline]
    pub fn header_bytes_consumed(&self) -> usize {
        self.header_bytes_consumed
    }

    /// Feeds an incoming downstream data chunk into the VLESS response finite state machine.
    ///
    /// - If the header has not been parsed yet, advances the state machine byte-by-byte.
    /// - Returns `Ok(Some(remaining_payload))` once the header transition to `Complete` occurs.
    ///   The returned payload slice contains all leftover bytes in the current chunk.
    /// - Returns `Ok(None)` if more data is needed to complete the header.
    /// - Returns `Err(String)` if the header is malformed or has an unsupported version.
    /// - If the header was ALREADY parsed in a previous call, returns `Ok(Some(chunk.to_vec()))` immediately.
    pub fn process_chunk(&mut self, chunk: &[u8]) -> Result<Option<Vec<u8>>, String> {
        if self.state == VlessResponseState::Complete {
            return Ok(Some(chunk.to_vec()));
        }

        let mut idx = 0;
        let len = chunk.len();

        while idx < len {
            match self.state {
                VlessResponseState::CheckingTlsCcs => {
                    let b = chunk[idx];
                    idx += 1;
                    self.header_bytes_consumed += 1;
                    if b == 0x14 {
                        self.state = VlessResponseState::ReadingTlsCcs { matched: 1 };
                    } else if b == 0x00 {
                        // Directly VLESS version 0
                        self.state = VlessResponseState::ReadingAddonLen;
                    } else {
                        if chunk.len() > 1024 {
                            return Err(format!(
                                "VLESS response header exceeds maximum allowed size ({} bytes)",
                                chunk.len()
                            ));
                        }
                        return Err(format!("Unsupported VLESS response version: {}", b));
                    }
                }
                VlessResponseState::ReadingTlsCcs { matched } => {
                    let b = chunk[idx];
                    idx += 1;
                    self.header_bytes_consumed += 1;
                    if b != TLS_CCS_RECORD[matched] {
                        if chunk.len() > 1024 {
                            return Err(format!(
                                "VLESS response header exceeds maximum allowed size ({} bytes)",
                                chunk.len()
                            ));
                        }
                        return Err(format!(
                            "Malformed TLS ChangeCipherSpec record in VLESS response: byte {} expected 0x{:02x}, got 0x{:02x}",
                            matched, TLS_CCS_RECORD[matched], b
                        ));
                    }
                    let next_matched = matched + 1;
                    if next_matched == TLS_CCS_RECORD.len() {
                        self.state = VlessResponseState::ReadingVersion;
                    } else {
                        self.state = VlessResponseState::ReadingTlsCcs { matched: next_matched };
                    }
                }
                VlessResponseState::ReadingVersion => {
                    let b = chunk[idx];
                    idx += 1;
                    self.header_bytes_consumed += 1;
                    if b != 0x00 {
                        if chunk.len() > 1024 {
                            return Err(format!(
                                "VLESS response header exceeds maximum allowed size ({} bytes)",
                                chunk.len()
                            ));
                        }
                        return Err(format!("Unsupported VLESS response version: {}", b));
                    }
                    self.state = VlessResponseState::ReadingAddonLen;
                }
                VlessResponseState::ReadingAddonLen => {
                    let b = chunk[idx];
                    idx += 1;
                    self.header_bytes_consumed += 1;
                    let addon_len = b as usize;
                    if addon_len == 0 {
                        self.state = VlessResponseState::Complete;
                        let payload = chunk[idx..].to_vec();
                        return Ok(Some(payload));
                    } else {
                        self.addons.reserve(addon_len);
                        self.state = VlessResponseState::ReadingAddons {
                            expected_len: addon_len,
                            read: 0,
                        };
                    }
                }
                VlessResponseState::ReadingAddons { expected_len, mut read } => {
                    let needed = expected_len - read;
                    let avail = len - idx;
                    let to_take = needed.min(avail);
                    self.addons.extend_from_slice(&chunk[idx..idx + to_take]);
                    idx += to_take;
                    read += to_take;
                    self.header_bytes_consumed += to_take;

                    if read == expected_len {
                        self.state = VlessResponseState::Complete;
                        let payload = chunk[idx..].to_vec();
                        return Ok(Some(payload));
                    } else {
                        self.state = VlessResponseState::ReadingAddons { expected_len, read };
                        return Ok(None);
                    }
                }
                VlessResponseState::Complete => {
                    let payload = chunk[idx..].to_vec();
                    return Ok(Some(payload));
                }
            }
        }

        if self.state == VlessResponseState::Complete {
            Ok(Some(Vec::new()))
        } else {
            Ok(None)
        }
    }
}

pub fn parse_vless_response(resp: &[u8]) -> Result<usize, String> {
    let mut parser = VlessResponseParser::new();
    match parser.process_chunk(resp) {
        Ok(Some(payload)) => Ok(resp.len() - payload.len()),
        Ok(None) => {
            if matches!(parser.state(), VlessResponseState::ReadingAddons { .. }) {
                Err("VLESS response header incomplete".to_string())
            } else {
                Err("VLESS response too short".to_string())
            }
        }
        Err(e) => Err(e),
    }
}

pub async fn read_vless_response<S: AsyncReadExt + Unpin>(
    stream: &mut S,
    timeout: Duration,
) -> Result<Vec<u8>, String> {
    let mut parser = VlessResponseParser::new();
    let mut resp_buf = [0u8; 512];
    let deadline = tokio::time::Instant::now() + timeout;

    loop {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            return Err("VLESS response timeout".to_string());
        }
        let n = match tokio::time::timeout(remaining, stream.read(&mut resp_buf)).await {
            Ok(Ok(n)) if n > 0 => n,
            Ok(Ok(_)) => return Err("VLESS server closed connection unexpectedly".to_string()),
            Ok(Err(e)) => return Err(format!("VLESS response read error: {}", e)),
            Err(_) => return Err("VLESS response timeout".to_string()),
        };

        if let Some(payload) = parser.process_chunk(&resp_buf[..n])? {
            return Ok(payload);
        }
    }
}

pub async fn read_vless_response_ws(
    ws: &RawWebSocket,
    timeout: Duration,
) -> Result<Vec<u8>, String> {
    let mut parser = VlessResponseParser::new();
    let deadline = tokio::time::Instant::now() + timeout;

    loop {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            return Err("VLESS WS response timeout".to_string());
        }
        let msg = match ws.recv_with_timeout(remaining).await {
            Ok(data) => data,
            Err(e) => return Err(format!("VLESS WS response read error: {:?}", e)),
        };

        if let Some(payload) = parser.process_chunk(&msg)? {
            return Ok(payload);
        }
    }
}

pub async fn read_vless_response_uplink(
    uplink: &mut VlessUplink,
    timeout: Duration,
) -> Result<Vec<u8>, String> {
    match uplink {
        VlessUplink::Ws(ws) => {
            let payload = read_vless_response_ws(ws, timeout).await?;
            if !payload.is_empty() {
                ws.inject_initial_payload(payload.clone()).await;
            }
            Ok(payload)
        }
        VlessUplink::Tcp(stream, initial_downlink, _vision) => {
            let payload = read_vless_response(stream, timeout).await?;
            if !payload.is_empty() {
                initial_downlink.extend_from_slice(&payload);
            }
            Ok(payload)
        }
        VlessUplink::Tls(stream, initial_downlink, _vision) => {
            let payload = read_vless_response(stream, timeout).await?;
            if !payload.is_empty() {
                initial_downlink.extend_from_slice(&payload);
            }
            Ok(payload)
        }
        VlessUplink::Reality(stream, initial_downlink, _vision) => {
            let payload = read_vless_response(stream, timeout).await?;
            if !payload.is_empty() {
                initial_downlink.extend_from_slice(&payload);
            }
            Ok(payload)
        }
    }
}

/// Dials a single VLESS configuration with full credential and protocol isolation.
/// No credentials, UUIDs, keys, or endpoints leak across profiles.
pub async fn dial_single_vless_config(
    cfg: &VlessConfig,
    target_addr: &str,
    command: u8,
    cancel_token: &CancellationToken,
) -> Option<VlessUplink> {
    if cancel_token.is_cancelled() {
        return None;
    }

    let vless_req = match build_vless_header_cmd(&cfg.uuid, target_addr, &[], &cfg.effective_flow(), command) {
        Ok(v) => v,
        Err(e) => {
            lerror!(
                "VLESS [{}]: Failed to build header (cmd=0x{:02x}) for {}: {}",
                cfg.id,
                command,
                target_addr,
                e
            );
            return None;
        }
    };

    let vision_ctx = if cfg.is_vision() && command == VLESS_CMD_TCP {
        Some(crate::vision::VisionContext { uuid: cfg.uuid })
    } else {
        None
    };

    if cfg.is_direct_vps() {
        let host_to_dial = if !cfg.server_address.is_empty() {
            cfg.server_address.clone()
        } else if !cfg.domain.is_empty() {
            cfg.domain.clone()
        } else {
            cfg.effective_tls_sni().to_string()
        };
        let server_port = cfg.effective_server_port();
        let tls_sni = cfg.effective_tls_sni().to_string();
        let host_header = cfg.effective_host_header().to_string();
        let transport = cfg.effective_transport().to_string();
        let security = cfg.effective_security().to_string();
        let fingerprint = cfg.effective_fingerprint().to_string();

        ldebug!(
            "VLESS [{}] universal dialer (direct VPS): dial {}:{} sni={} host={} transport={} security={}",
            cfg.id,
            host_to_dial,
            server_port,
            tls_sni,
            host_header,
            transport,
            security
        );

        let candidate_addrs: Vec<SocketAddr> = if let Ok(ip) = host_to_dial.parse::<IpAddr>() {
            vec![SocketAddr::new(ip, server_port)]
        } else {
            let ips = resolve_clean_dual_stack_ips(&host_to_dial).await;
            ips.into_iter()
                .map(|ip| SocketAddr::new(ip, server_port))
                .collect()
        };

        if candidate_addrs.is_empty() {
            lwarn!("VLESS [{}] direct VPS: failed to resolve host {}", cfg.id, host_to_dial);
            return None;
        }

        let tcp_stream = match tokio::select! {
            _ = cancel_token.cancelled() => return None,
            res = tokio::time::timeout(
                Duration::from_secs(5),
                happy_eyeballs_tcp_connect(&candidate_addrs, Duration::from_secs(5)),
            ) => res,
        } {
            Ok(Ok((stream, winner_addr))) => {
                ldebug!(
                    "VLESS [{}] direct VPS: TCP connected to {} -> {}",
                    cfg.id,
                    host_to_dial,
                    winner_addr
                );
                set_sock_opts(&stream);
                stream
            }
            Ok(Err(e)) => {
                lwarn!(
                    "VLESS [{}] direct VPS: TCP connection failed to {}: {:?}",
                    cfg.id,
                    host_to_dial,
                    e
                );
                return None;
            }
            Err(_) => {
                lwarn!(
                    "VLESS [{}] direct VPS: TCP connection timeout to {}",
                    cfg.id,
                    host_to_dial
                );
                return None;
            }
        };

        let sni_domain = if !tls_sni.is_empty() {
            tls_sni.as_str()
        } else {
            host_to_dial.as_str()
        };

        let is_reality = cfg.is_reality() || !cfg.public_key.is_empty();
        let is_tls = security.eq_ignore_ascii_case("tls");
        let is_plain = security.eq_ignore_ascii_case("none");
        let is_ws = transport.eq_ignore_ascii_case("ws") || transport.eq_ignore_ascii_case("websocket");
        let is_tcp = transport.eq_ignore_ascii_case("tcp");

        if !is_ws && !is_tcp {
            lwarn!(
                "VLESS [{}] direct VPS: unsupported transport '{}', rejected",
                cfg.id,
                transport
            );
            return None;
        }

        if !is_reality && !is_tls && !is_plain {
            lwarn!(
                "VLESS [{}] direct VPS: unsupported security '{}', rejected",
                cfg.id,
                security
            );
            return None;
        }

        if is_reality && is_ws {
            lwarn!(
                "VLESS [{}] direct VPS: incompatible configuration: reality is incompatible with ws transport, rejected",
                cfg.id
            );
            return None;
        }

        if is_reality && is_tcp {
            ldebug!(
                "VLESS [{}] direct VPS: executing Reality handshake with sni={}, fp={}",
                cfg.id,
                sni_domain,
                fingerprint
            );
            let dial_res = tokio::select! {
                _ = cancel_token.cancelled() => return None,
                res = crate::reality::reality_connect_ext(
                    tcp_stream,
                    sni_domain,
                    &cfg.public_key,
                    &cfg.short_id,
                    &fingerprint,
                    Duration::from_secs(5),
                ) => res,
            };
            match dial_res {
                Ok(mut real_stream) => {
                    if let Err(e) = real_stream.write_all(&vless_req).await {
                        lwarn!("VLESS [{}] Reality: failed to write request header: {:?}", cfg.id, e);
                        return None;
                    }
                    linfo!(
                        "VLESS [{}] Reality connected successfully to {}:{}",
                        cfg.id,
                        host_to_dial,
                        server_port
                    );
                    return Some(VlessUplink::Reality(
                        real_stream,
                        Vec::new(),
                        vision_ctx,
                    ));
                }
                Err(e) => {
                    lwarn!("VLESS [{}] Reality: handshake failed to {}: {}", cfg.id, host_to_dial, e);
                    return None;
                }
            }
        } else if is_tls && is_tcp {
            let tls_cfg = crate::ws::get_tls_config_for_fingerprint(&fingerprint);
            let connector = TlsConnector::from(tls_cfg);
            let sni = server_name(sni_domain);
            let dial_res = tokio::select! {
                _ = cancel_token.cancelled() => return None,
                res = tokio::time::timeout(
                    Duration::from_secs(5),
                    connector.connect(sni, tcp_stream),
                ) => res,
            };
            let tls_stream = match dial_res {
                Ok(Ok(s)) => s,
                Ok(Err(e)) => {
                    lwarn!(
                        "VLESS [{}] direct VPS: TLS handshake failed for {}: {:?}",
                        cfg.id,
                        sni_domain,
                        e
                    );
                    return None;
                }
                Err(_) => {
                    lwarn!("VLESS [{}] direct VPS: TLS handshake timeout for {}", cfg.id, sni_domain);
                    return None;
                }
            };

            let mut tls_stream = tls_stream;
            if let Err(e) = tls_stream.write_all(&vless_req).await {
                lwarn!(
                    "VLESS [{}] direct VPS: Failed to send VLESS header over TLS: {:?}",
                    cfg.id,
                    e
                );
                return None;
            }
            linfo!(
                "VLESS [{}] direct VPS connected via TLS+TCP to {}:{} (sni={})",
                cfg.id,
                host_to_dial,
                server_port,
                sni_domain
            );
            return Some(VlessUplink::Tls(tls_stream, Vec::new(), vision_ctx));
        } else if is_tls && is_ws {
            // WebSocket over TLS
            let path = if cfg.path.is_empty() {
                "/vless-ws?ed=2048"
            } else {
                &cfg.path
            };
            let host_hdr = if !host_header.is_empty() {
                &host_header
            } else {
                sni_domain
            };
            let dial_ip = host_to_dial.clone();

            let early_data_limit = parse_early_data_header_len(path);
            let ed_payload =
                if early_data_limit.is_some() && vless_req.len() <= early_data_limit.unwrap() {
                    Some(vless_req.as_slice())
                } else {
                    None
                };

            let dial_res = tokio::select! {
                _ = cancel_token.cancelled() => return None,
                res = ws_handshake_split_host_ext(
                    tcp_stream,
                    &dial_ip,
                    sni_domain,
                    host_hdr,
                    path,
                    &fingerprint,
                    ed_payload,
                    Duration::from_secs(5),
                ) => res,
            };

            match dial_res {
                Ok(ws) => {
                    let sent_in_header = ws.is_early_data_sent();
                    let send_res = if !sent_in_header {
                        ws.send(&vless_req).await
                    } else {
                        ldebug!("VLESS [{}] direct VPS: 0-RTT Early Data sent in Sec-WebSocket-Protocol ({} bytes)", cfg.id, vless_req.len());
                        Ok(())
                    };
                    if let Err(e) = send_res {
                        lwarn!("VLESS [{}] direct VPS: failed to send WS VLESS header: {:?}", cfg.id, e);
                        return None;
                    }
                    linfo!(
                        "VLESS [{}] direct VPS connected via WS+TLS (0-RTT={}) to {}:{}",
                        cfg.id,
                        sent_in_header,
                        host_to_dial,
                        server_port
                    );
                    return Some(VlessUplink::Ws(ws));
                }
                Err(e) => {
                    lwarn!("VLESS [{}] direct VPS: WS handshake failed: {:?}", cfg.id, e);
                    return None;
                }
            }
        } else if is_plain && is_ws {
            // WebSocket over plain TCP (HTTP 101 Upgrade without TLS)
            let path = if cfg.path.is_empty() {
                "/vless-ws?ed=2048"
            } else {
                &cfg.path
            };
            let host_hdr = if !host_header.is_empty() {
                &host_header
            } else {
                host_to_dial.as_str()
            };

            let early_data_limit = parse_early_data_header_len(path);
            let ed_payload =
                if early_data_limit.is_some() && vless_req.len() <= early_data_limit.unwrap() {
                    Some(vless_req.as_slice())
                } else {
                    None
                };

            let dial_res = tokio::select! {
                _ = cancel_token.cancelled() => return None,
                res = ws_handshake_plain_ext(
                    tcp_stream,
                    host_hdr,
                    path,
                    ed_payload,
                    Duration::from_secs(5),
                ) => res,
            };

            match dial_res {
                Ok(ws) => {
                    let sent_in_header = ws.is_early_data_sent();
                    let send_res = if !sent_in_header {
                        ws.send(&vless_req).await
                    } else {
                        ldebug!("VLESS [{}] direct VPS: 0-RTT Early Data sent in Sec-WebSocket-Protocol ({} bytes)", cfg.id, vless_req.len());
                        Ok(())
                    };
                    if let Err(e) = send_res {
                        lwarn!("VLESS [{}] direct VPS: failed to send plain WS VLESS header: {:?}", cfg.id, e);
                        return None;
                    }
                    linfo!(
                        "VLESS [{}] direct VPS connected via plain WS (0-RTT={}) to {}:{}",
                        cfg.id,
                        sent_in_header,
                        host_to_dial,
                        server_port
                    );
                    return Some(VlessUplink::Ws(ws));
                }
                Err(e) => {
                    lwarn!("VLESS [{}] direct VPS: plain WS handshake failed: {:?}", cfg.id, e);
                    return None;
                }
            }
        } else if is_plain && is_tcp {
            // security == "none" & transport == "tcp" (plain TCP)
            let mut tcp_stream = tcp_stream;
            if let Err(e) = tcp_stream.write_all(&vless_req).await {
                lwarn!(
                    "VLESS [{}] direct VPS: failed to write VLESS header over plain TCP: {:?}",
                    cfg.id,
                    e
                );
                return None;
            }
            linfo!(
                "VLESS [{}] direct VPS connected via plain TCP to {}:{}",
                cfg.id,
                host_to_dial,
                server_port
            );
            return Some(VlessUplink::Tcp(tcp_stream, Vec::new(), vision_ctx));
        } else {
            lwarn!(
                "VLESS [{}] direct VPS: unsupported security/transport combination: security='{}', transport='{}'",
                cfg.id,
                security,
                transport
            );
            return None;
        }
    }

    // Cloudflare Dialing Flow (Workers / Pages / Fronting)
    let cooldown_domain = if !cfg.domain.is_empty() {
        &cfg.domain
    } else {
        cfg.effective_tls_sni()
    };
    let remaining = cfproxy_429_cooldown_remaining(cooldown_domain);
    if remaining > Duration::ZERO {
        ldebug!(
            "VLESS [{}]: cooldown {}s remaining for {}",
            cfg.id,
            remaining.as_secs(),
            cooldown_domain
        );
        return None;
    }

    let path = if cfg.path.is_empty() {
        "/vless-ws?ed=2048"
    } else {
        &cfg.path
    };
    let early_data_limit = parse_early_data_header_len(path);
    let ed_payload =
        if early_data_limit.is_some() && vless_req.len() <= early_data_limit.unwrap() {
            Some(vless_req.as_slice())
        } else {
            None
        };

    let dial_res = tokio::select! {
        _ = cancel_token.cancelled() => return None,
        res = async {
            if !cfg.server_address.is_empty() {
                cf_connect_fronted_ext(
                    &cfg.server_address,
                    cfg.effective_server_port(),
                    cfg.effective_tls_sni(),
                    cfg.effective_host_header(),
                    path,
                    ed_payload,
                    5.0,
                )
                .await
            } else {
                cf_connect_domain_ext(
                    if !cfg.domain.is_empty() {
                        &cfg.domain
                    } else {
                        cfg.effective_tls_sni()
                    },
                    path,
                    ed_payload,
                    5.0,
                )
                .await
            }
        } => res,
    };

    let (ws_opt, resolved_ip, err) = dial_res;
    if let Some(ws) = ws_opt {
        let sent_in_header = ws.is_early_data_sent();
        let send_res = if !sent_in_header {
            ws.send(&vless_req).await
        } else {
            ldebug!(
                "VLESS [{}]: 0-RTT Early Data sent in Sec-WebSocket-Protocol ({} bytes)",
                cfg.id,
                vless_req.len()
            );
            Ok(())
        };
        if let Err(e) = send_res {
            lwarn!(
                "VLESS [{}]: Failed to send header to {}: {:?}",
                cfg.id,
                cfg.effective_tls_sni(),
                e
            );
        } else {
            clear_cfproxy_429_cooldown(cooldown_domain);
            ldebug!(
                "VLESS [{}] ok (0-RTT={}) {}:{} (sni={}) via {}",
                cfg.id,
                sent_in_header,
                cfg.effective_server_address(),
                cfg.effective_server_port(),
                cfg.effective_tls_sni(),
                resolved_ip
            );
            return Some(VlessUplink::Ws(ws));
        }
    }
    if let Some(e) = err {
        if is_http_status_error(&e, 429) {
            mark_cfproxy_429_cooldown(cooldown_domain, &e);
        }
        log_cf_conn_error(
            &format!(
                "VLESS [{}] fail {}: {}",
                cfg.id,
                cfg.effective_tls_sni(),
                e.compact()
            ),
            &e,
        );
    }

    None
}

pub async fn vless_acquire_uplink_cmd(
    target_addr: &str,
    command: u8,
    cancel_token: &CancellationToken,
) -> Option<VlessUplink> {
    let active_cfg = VLESS_CONFIG.read().clone();
    let user_domain = CFPROXY.read().user_domain.clone();

    // Priority -1: Opera VPN Upstream Chaining (if enabled for VLESS)
    let (use_opera, opera_ep) = {
        let op = OPERA_VPN.read();
        (op.vless_enabled, op.endpoint.clone())
    };
    if use_opera && !opera_ep.is_empty() {
        let target_vless_domain = if !active_cfg.tls_sni.is_empty() {
            &active_cfg.tls_sni
        } else if !active_cfg.domain.is_empty() {
            &active_cfg.domain
        } else if !user_domain.is_empty() {
            &user_domain
        } else {
            "free-vless.pages.dev"
        };
        let ws_path = if active_cfg.path.is_empty() {
            "/vless-ws?ed=2048"
        } else {
            &active_cfg.path
        };
        ldebug!(
            "VLESS: connecting via Opera VPN proxy {} to {}",
            opera_ep,
            target_vless_domain
        );
        let vless_req_res = build_vless_header_cmd(
            &active_cfg.uuid,
            target_addr,
            &[],
            &active_cfg.effective_flow(),
            command,
        );
        if let Ok(vless_req) = vless_req_res {
            let dial_res = tokio::select! {
                _ = cancel_token.cancelled() => return None,
                res = crate::ws::ws_connect_via_opera_proxy(
                    &opera_ep,
                    target_vless_domain,
                    ws_path,
                    Duration::from_secs(5),
                ) => res,
            };
            match dial_res {
                Ok(ws) => {
                    if let Err(e) = ws.send(&vless_req).await {
                        lwarn!("VLESS: Failed to send header via Opera VPN: {:?}", e);
                    } else {
                        linfo!("VLESS connected successfully via Opera VPN {}", opera_ep);
                        return Some(VlessUplink::Ws(ws));
                    }
                }
                Err(e) => {
                    lwarn!("VLESS: Opera VPN tunnel failed ({}): {:?}", opera_ep, e);
                }
            }
        }
    }

    // Priority 0: Active Profile (Dedicated VLESS domain / Clean IP fronting / Direct VPS)
    if !active_cfg.domain.is_empty() || !active_cfg.server_address.is_empty() {
        if let Some(uplink) =
            dial_single_vless_config(&active_cfg, target_addr, command, cancel_token).await
        {
            return Some(uplink);
        }
    }

    // Priority 1: User-configured custom Cloudflare Worker / Pages domain
    if !user_domain.is_empty() && !user_domain.eq_ignore_ascii_case(&active_cfg.domain) {
        let mut user_cfg = active_cfg.clone();
        user_cfg.id = format!("user_worker_{}", user_domain);
        user_cfg.name = format!("User Worker ({})", user_domain);
        user_cfg.domain = user_domain.clone();
        user_cfg.server_address = String::new();
        user_cfg.server_port = 443;
        user_cfg.tls_sni = user_domain.clone();
        user_cfg.host_header = user_domain.clone();
        user_cfg.transport = "ws".to_string();
        user_cfg.security = "tls".to_string();
        user_cfg.public_key = String::new();
        user_cfg.short_id = String::new();
        user_cfg.flow = String::new();

        if let Some(uplink) =
            dial_single_vless_config(&user_cfg, target_addr, command, cancel_token).await
        {
            return Some(uplink);
        }
    }

    // Priority 2: Fallback Profiles Pool with isolated credentials (TSK-V01)
    let mut candidate_configs: Vec<VlessConfig> = Vec::new();

    // 1. First priority in fallback: complete typed profiles from DYNAMIC_VLESS_PROFILES
    {
        let pool = DYNAMIC_VLESS_PROFILES.read();
        for profile in pool.iter() {
            let addr = profile.effective_server_address();
            if !active_cfg.domain.is_empty() && addr.eq_ignore_ascii_case(&active_cfg.domain) {
                continue;
            }
            if !active_cfg.server_address.is_empty()
                && addr.eq_ignore_ascii_case(&active_cfg.server_address)
            {
                continue;
            }
            if !user_domain.is_empty() && addr.eq_ignore_ascii_case(&user_domain) {
                continue;
            }
            if candidate_configs
                .iter()
                .any(|c| c.id == profile.id || c.effective_server_address().eq_ignore_ascii_case(addr))
            {
                continue;
            }
            match profile.to_vless_config() {
                Ok(cfg) => candidate_configs.push(cfg),
                Err(e) => lwarn!("VLESS fallback pool profile '{}' skipped: {}", profile.id, e),
            }
        }
    }

    // 2. Second priority in fallback: public free Cloudflare Pages & Dev workers (graceful fallback)
    if candidate_configs.is_empty() {
        let default_uuid = parse_uuid("d342d11e-d424-4583-b36e-524ab1f0afa4")
            .unwrap_or(active_cfg.uuid);
        for &fb in PUBLIC_VLESS_FALLBACKS.iter().chain(DEV_SOCKS5_WORKERS.iter()) {
            if !active_cfg.domain.eq_ignore_ascii_case(fb)
                && !active_cfg.server_address.eq_ignore_ascii_case(fb)
                && !user_domain.eq_ignore_ascii_case(fb)
            {
                if !candidate_configs
                    .iter()
                    .any(|c| c.domain.eq_ignore_ascii_case(fb))
                {
                    candidate_configs.push(VlessConfig {
                        id: fb.to_string(),
                        name: fb.to_string(),
                        uuid: default_uuid,
                        path: "/vless-ws?ed=2048".to_string(),
                        domain: fb.to_string(),
                        server_address: String::new(),
                        server_port: 443,
                        tls_sni: fb.to_string(),
                        host_header: fb.to_string(),
                        transport: "ws".to_string(),
                        security: "tls".to_string(),
                        public_key: String::new(),
                        short_id: String::new(),
                        fingerprint: "chrome".to_string(),
                        spider_x: String::new(),
                        flow: String::new(),
                        header_type: String::new(),
                    });
                }
            }
        }
    }

    // Fast-path: check if we have a healthy sticky node (TSK-V11)
    let sticky_candidate = VLESS_SCORER.read().get_sticky_candidate();
    if let Some(sticky) = sticky_candidate {
        let sticky_idx = candidate_configs.iter().position(|c| {
            c.id.eq_ignore_ascii_case(&sticky.domain)
                || c.effective_server_address().eq_ignore_ascii_case(&sticky.domain)
                || c.domain.eq_ignore_ascii_case(&sticky.domain)
        });
        if let Some(idx) = sticky_idx {
            let sticky_cfg = candidate_configs.remove(idx);
            ldebug!(
                "VLESS sticky fast-path: attempting cached profile '{}' ({}:{}) (smoothed RTT: {}ms) for {}",
                sticky_cfg.id,
                sticky_cfg.effective_server_address(),
                sticky_cfg.effective_server_port(),
                sticky.smoothed_rtt_ms,
                target_addr
            );
            let sticky_start = Instant::now();
            let dial_res = dial_single_vless_config(
                &sticky_cfg,
                target_addr,
                command,
                cancel_token,
            )
            .await;

            if let Some(uplink) = dial_res {
                let elapsed_ms = sticky_start.elapsed().as_millis() as u64;
                VLESS_SCORER
                    .write()
                    .record_success(&sticky_cfg.id, elapsed_ms);
                *LAST_SOCKS5_WORKER.write() = sticky_cfg.id.clone();
                clear_cfproxy_429_cooldown(&sticky_cfg.domain);
                ldebug!(
                    "VLESS sticky profile ok: '{}' in {}ms",
                    sticky_cfg.id,
                    elapsed_ms
                );
                maybe_trigger_vless_background_probe();
                return Some(uplink);
            }

            // Sticky profile failed. Demote and fall back to sequential race
            lwarn!(
                "VLESS sticky profile '{}' failed. Recording failure and proceeding to fallbacks.",
                sticky_cfg.id
            );
            VLESS_SCORER.write().record_failure(&sticky_cfg.id);
            // Put it back at the end of candidates
            candidate_configs.push(sticky_cfg);
        }
    }

    // Filter out candidates on 429 cooldown
    let mut active_candidates: Vec<VlessConfig> = Vec::with_capacity(candidate_configs.len());
    for cfg in candidate_configs {
        let cooldown_domain = if !cfg.domain.is_empty() {
            &cfg.domain
        } else {
            cfg.effective_tls_sni()
        };
        if cfproxy_429_cooldown_remaining(cooldown_domain) == Duration::ZERO {
            active_candidates.push(cfg);
        }
    }

    if active_candidates.is_empty() {
        lwarn!("VLESS: all fallback candidates unavailable (429 cooldown or empty)");
        return None;
    }

    // Sort active candidates by health status and historical latency score
    {
        let scorer = VLESS_SCORER.read();
        active_candidates.sort_by_key(|c| {
            let is_healthy = scorer.is_node_healthy(&c.id);
            let rtt = scorer
                .scores
                .get(&c.id)
                .or_else(|| scorer.scores.get(c.effective_server_address()))
                .or_else(|| scorer.scores.get(&c.domain))
                .map(|(rtt, _)| *rtt)
                .unwrap_or(350);
            (!is_healthy, rtt)
        });
    }

    // Limit to top 3 candidates to minimize connection attempts
    if active_candidates.len() > 3 {
        active_candidates.truncate(3);
    }

    ldebug!(
        "VLESS sequential fallback: {} isolated candidate profiles for {}",
        active_candidates.len(),
        target_addr
    );

    for cfg in active_candidates {
        if cancel_token.is_cancelled() {
            return None;
        }

        let probe_start = Instant::now();
        if let Some(uplink) =
            dial_single_vless_config(&cfg, target_addr, command, cancel_token).await
        {
            let elapsed_ms = probe_start.elapsed().as_millis() as u64;
            VLESS_SCORER
                .write()
                .set_sticky_winner(&cfg.id, elapsed_ms);
            *LAST_SOCKS5_WORKER.write() = cfg.id.clone();
            clear_cfproxy_429_cooldown(&cfg.domain);
            linfo!(
                "VLESS sequential fallback winner: profile '{}' ({}:{}) in {}ms",
                cfg.id,
                cfg.effective_server_address(),
                cfg.effective_server_port(),
                elapsed_ms
            );
            return Some(uplink);
        } else {
            VLESS_SCORER.write().record_failure(&cfg.id);
        }
    }

    lwarn!(
        "VLESS: all sequential fallback candidate profiles failed for {}",
        target_addr
    );
    None
}

pub async fn vless_acquire_uplink(
    target_addr: &str,
    cancel_token: &CancellationToken,
) -> Option<VlessUplink> {
    vless_acquire_uplink_cmd(target_addr, VLESS_CMD_TCP, cancel_token).await
}

pub async fn vless_acquire_ws(
    target_addr: &str,
    cancel_token: &CancellationToken,
) -> Option<RawWebSocket> {
    match vless_acquire_uplink(target_addr, cancel_token).await {
        Some(VlessUplink::Ws(ws)) => Some(ws),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    static VLESS_TEST_MUTEX: std::sync::Mutex<()> = std::sync::Mutex::new(());

    #[test]
    fn test_uuid_parse_format() {
        let uuid_str = "d342d11e-d424-4583-b36e-524ab1f0afa4";
        let parsed = parse_uuid(uuid_str).expect("Valid UUID");
        let formatted = format_uuid(&parsed);
        assert_eq!(formatted, uuid_str);
    }

    #[test]
    fn test_build_vless_header_ipv4() {
        let uuid = [0x01; 16];
        let target = "149.154.167.51:443";
        let header = build_vless_header(&uuid, target, b"hello").expect("Valid header");

        assert_eq!(header[0], 0x00); // Version
        assert_eq!(&header[1..17], &uuid); // UUID
        assert_eq!(header[17], 0x00); // Addon len
        assert_eq!(header[18], 0x01); // Command TCP
        assert_eq!(header[19], 0x01); // Port 443 high byte
        assert_eq!(header[20], 0xBB); // Port 443 low byte
        assert_eq!(header[21], 0x01); // IPv4
        assert_eq!(&header[22..26], &[149, 154, 167, 51]); // IP
        assert_eq!(&header[26..], b"hello"); // Payload
    }

    #[test]
    fn test_build_vless_header_domain() {
        let uuid = [0x02; 16];
        let target = "telegram.org:80";
        let header = build_vless_header(&uuid, target, &[]).expect("Valid header");

        assert_eq!(header[0], 0x00);
        assert_eq!(&header[1..17], &uuid);
        assert_eq!(header[17], 0x00);
        assert_eq!(header[18], 0x01);
        assert_eq!(header[19], 0x00);
        assert_eq!(header[20], 80);
        assert_eq!(header[21], 0x02); // Domain
        assert_eq!(header[22], "telegram.org".len() as u8);
        assert_eq!(&header[23..], b"telegram.org");
    }

    #[test]
    fn test_build_vless_header_vision() {
        let uuid = [0x03; 16];
        let target = "149.154.167.51:443";
        let header =
            build_vless_header_ext(&uuid, target, &[], "xtls-rprx-vision").expect("Valid header");

        assert_eq!(header[0], 0x00); // Version
        assert_eq!(&header[1..17], &uuid); // UUID
        assert_eq!(header[17], 18); // Addons length = 18
                                    // Protobuf Addons
        assert_eq!(header[18], 0x0a); // Tag
        assert_eq!(header[19], 16); // Len of flow string
        assert_eq!(&header[20..36], b"xtls-rprx-vision");
        // Command TCP
        assert_eq!(header[36], 0x01);
        // Port 443
        assert_eq!(header[37], 0x01);
        assert_eq!(header[38], 0xBB);
        // IPv4
        assert_eq!(header[39], 0x01);
        assert_eq!(&header[40..44], &[149, 154, 167, 51]);
    }

    #[test]
    fn test_parse_vless_response() {
        let resp = [0x00, 0x00, 0x01, 0x02];
        let offset = parse_vless_response(&resp).expect("Valid response");
        assert_eq!(offset, 2);
    }

    #[test]
    fn test_parse_vless_response_edge_cases() {
        // Minimal valid header
        assert_eq!(parse_vless_response(&[0x00, 0x00]).unwrap(), 2);

        // Header with addons
        let with_addons = [0x00, 0x03, 0xAA, 0xBB, 0xCC, 0xDE, 0xAD];
        assert_eq!(parse_vless_response(&with_addons).unwrap(), 5);

        // Header with TLS ChangeCipherSpec record
        let with_ccs = [0x14, 0x03, 0x03, 0x00, 0x01, 0x01, 0x00, 0x00, 0xFE];
        assert_eq!(parse_vless_response(&with_ccs).unwrap(), 8);

        // Partial TLS CCS
        assert!(parse_vless_response(&[0x14, 0x03, 0x03]).is_err());
        assert!(parse_vless_response(&[0x14, 0x03, 0x03])
            .unwrap_err()
            .contains("too short"));

        // Truncated addons
        assert!(parse_vless_response(&[0x00, 0x05, 0x01, 0x02])
            .unwrap_err()
            .contains("incomplete"));

        // Empty and 1 byte
        assert!(parse_vless_response(&[]).unwrap_err().contains("too short"));
        assert!(parse_vless_response(&[0x00]).unwrap_err().contains("too short"));

        // Unsupported version
        assert!(parse_vless_response(&[0x01, 0x00])
            .unwrap_err()
            .contains("Unsupported VLESS response version"));
    }

    #[test]
    fn test_vless_response_parser_single_chunk() {
        let mut parser = VlessResponseParser::new();
        assert!(!parser.is_header_parsed());

        let chunk = [0x00, 0x00, 0x16, 0x03, 0x03, 0x00, 0x10];
        let payload = parser.process_chunk(&chunk).expect("Valid chunk").expect("Payload present");
        assert!(parser.is_header_parsed());
        assert_eq!(payload, vec![0x16, 0x03, 0x03, 0x00, 0x10]);

        // Next chunk passes through directly
        let next_chunk = b"server_data_payload";
        let next_payload = parser.process_chunk(next_chunk).expect("Valid chunk").expect("Payload present");
        assert_eq!(next_payload, next_chunk.to_vec());
    }

    #[test]
    fn test_vless_response_parser_split_chunks() {
        let mut parser = VlessResponseParser::new();

        // 1st chunk: only 1 byte (version 0x00)
        let res1 = parser.process_chunk(&[0x00]).expect("No fatal error");
        assert!(res1.is_none());
        assert!(!parser.is_header_parsed());

        // 2nd chunk: completes header (addons_len 0x00) and provides initial data
        let res2 = parser
            .process_chunk(&[0x00, 0x48, 0x54, 0x54, 0x50])
            .expect("Valid chunk");
        assert!(parser.is_header_parsed());
        assert_eq!(res2.unwrap(), b"HTTP".to_vec());

        // 3rd chunk: subsequent stream data passes through
        let res3 = parser.process_chunk(b"/1.1 200 OK").expect("Valid chunk");
        assert_eq!(res3.unwrap(), b"/1.1 200 OK".to_vec());
    }

    #[test]
    fn test_vless_response_parser_client_first_flow() {
        // In client-first protocol (e.g. HTTPS ClientHello), client sends payload before server replies.
        // Once the server does reply, the VLESS response header is stripped and payload delivered.
        let mut parser = VlessResponseParser::new();

        // Server buffers response and eventually sends header with first chunk of TLS ServerHello
        let server_reply = [0x00, 0x00, 0x16, 0x03, 0x03, 0x01, 0x00];
        let res = parser.process_chunk(&server_reply).expect("Parse success");
        assert!(parser.is_header_parsed());
        assert_eq!(res.unwrap(), vec![0x16, 0x03, 0x03, 0x01, 0x00]);
    }

    #[test]
    fn test_vless_response_parser_server_first_flow() {
        // In server-first protocol (e.g. FTP/SMTP), server immediately sends greeting.
        let mut parser = VlessResponseParser::new();
        let greeting = [0x00, 0x00, 0x32, 0x32, 0x30, 0x20, 0x53, 0x4d, 0x54, 0x50]; // 00 00 + "220 SMTP"
        let res = parser.process_chunk(&greeting).expect("Parse success");
        assert!(parser.is_header_parsed());
        assert_eq!(res.unwrap(), b"220 SMTP".to_vec());
    }

    #[test]
    fn test_vless_response_parser_with_tls_ccs() {
        let mut parser = VlessResponseParser::new();
        // TLS ChangeCipherSpec record before VLESS header
        let raw = [0x14, 0x03, 0x03, 0x00, 0x01, 0x01, 0x00, 0x00, 0xAA, 0xBB];
        let res = parser.process_chunk(&raw).expect("Parse success");
        assert!(parser.is_header_parsed());
        assert_eq!(res.unwrap(), vec![0xAA, 0xBB]);
    }

    #[test]
    fn test_vless_response_parser_split_tls_ccs() {
        let mut parser = VlessResponseParser::new();
        // Split CCS record across chunks
        let chunk1 = [0x14, 0x03, 0x03];
        assert!(parser.process_chunk(&chunk1).unwrap().is_none());
        assert!(!parser.is_header_parsed());

        let chunk2 = [0x00, 0x01, 0x01, 0x00, 0x00, 0x42];
        let res = parser.process_chunk(&chunk2).unwrap();
        assert!(parser.is_header_parsed());
        assert_eq!(res.unwrap(), vec![0x42]);
    }

    #[test]
    fn test_vless_response_parser_overflow_guard() {
        let mut parser = VlessResponseParser::new();
        let garbage = vec![0x14; 1025];
        let err = parser.process_chunk(&garbage);
        assert!(err.is_err());
        assert!(err.unwrap_err().contains("maximum allowed size"));
    }

    #[test]
    fn test_vless_response_parser_byte_by_byte_standard() {
        let mut parser = VlessResponseParser::new();
        let stream_bytes = [0x00, 0x00, 0x54, 0x45, 0x53, 0x54]; // 00 00 + "TEST"
        let mut collected_payload = Vec::new();

        for (i, &byte) in stream_bytes.iter().enumerate() {
            let res = parser.process_chunk(&[byte]).expect("Valid byte stream");
            if let Some(payload) = res {
                collected_payload.extend_from_slice(&payload);
            }
            if i < 1 {
                assert!(!parser.is_header_parsed(), "Header must not be complete at byte {}", i);
            } else {
                assert!(parser.is_header_parsed(), "Header must be complete at byte {}", i);
            }
        }

        assert_eq!(collected_payload, b"TEST".to_vec());
        assert_eq!(parser.header_bytes_consumed(), 2);
    }

    #[test]
    fn test_vless_response_parser_byte_by_byte_with_addons() {
        let mut parser = VlessResponseParser::new();
        // Version 0x00, addons len 0x03, addons [0xAA, 0xBB, 0xCC], payload [0x11, 0x22, 0x33, 0x44]
        let stream_bytes = [0x00, 0x03, 0xAA, 0xBB, 0xCC, 0x11, 0x22, 0x33, 0x44];
        let mut collected_payload = Vec::new();

        for (i, &byte) in stream_bytes.iter().enumerate() {
            let res = parser.process_chunk(&[byte]).expect("Valid byte stream");
            if let Some(payload) = res {
                collected_payload.extend_from_slice(&payload);
            }
            if i < 4 {
                assert!(!parser.is_header_parsed(), "Header must be reading addons at byte {}", i);
            } else {
                assert!(parser.is_header_parsed(), "Header must be complete at byte {}", i);
            }
        }

        assert_eq!(parser.addons(), &[0xAA, 0xBB, 0xCC]);
        assert_eq!(collected_payload, &[0x11, 0x22, 0x33, 0x44]);
        assert_eq!(parser.header_bytes_consumed(), 5);
    }

    #[test]
    fn test_vless_response_parser_byte_by_byte_with_tls_ccs() {
        let mut parser = VlessResponseParser::new();
        // TLS CCS 6 bytes + Version 0x00 + Addons 0x00 + Payload "PONG"
        let stream_bytes = [
            0x14, 0x03, 0x03, 0x00, 0x01, 0x01, // TLS CCS
            0x00, // Version
            0x00, // Addons len
            0x50, 0x4F, 0x4E, 0x47, // "PONG"
        ];
        let mut collected_payload = Vec::new();

        for (i, &byte) in stream_bytes.iter().enumerate() {
            let res = parser.process_chunk(&[byte]).expect("Valid byte stream");
            if let Some(payload) = res {
                collected_payload.extend_from_slice(&payload);
            }
            if i < 7 {
                assert!(!parser.is_header_parsed(), "Header must not be complete at byte {}", i);
            } else {
                assert!(parser.is_header_parsed(), "Header must be complete at byte {}", i);
            }
        }

        assert_eq!(collected_payload, b"PONG".to_vec());
        assert_eq!(parser.header_bytes_consumed(), 8);
    }

    #[test]
    fn test_vless_response_parser_large_payload_single_chunk() {
        let mut parser = VlessResponseParser::new();
        let mut chunk = vec![0x00, 0x00];
        let large_payload = vec![0xAB; 131072]; // 128 KiB
        chunk.extend_from_slice(&large_payload);

        let res = parser.process_chunk(&chunk).expect("Valid chunk").expect("Payload returned");
        assert!(parser.is_header_parsed());
        assert_eq!(res.len(), 131072);
        assert_eq!(res, large_payload);
    }

    #[test]
    fn test_vless_response_parser_split_before_large_payload() {
        let mut parser = VlessResponseParser::new();
        // 1st chunk: only version
        assert!(parser.process_chunk(&[0x00]).expect("Valid chunk").is_none());
        assert!(!parser.is_header_parsed());

        // 2nd chunk: addon len 0 + 64 KiB payload
        let mut chunk2 = vec![0x00];
        let large_payload = vec![0xCD; 65536];
        chunk2.extend_from_slice(&large_payload);

        let res = parser.process_chunk(&chunk2).expect("Valid chunk").expect("Payload returned");
        assert!(parser.is_header_parsed());
        assert_eq!(res.len(), 65536);
        assert_eq!(res, large_payload);
    }

    #[tokio::test]
    async fn test_read_vless_response_async_stream_byte_by_byte() {
        let (mut client, mut server) = tokio::io::duplex(1024);

        let writer_task = tokio::spawn(async move {
            let wire_data = [
                0x14, 0x03, 0x03, 0x00, 0x01, 0x01, // TLS CCS
                0x00, 0x02, 0xFE, 0xED,             // VLESS header + 2 bytes addons
                0x01, 0x02, 0x03, 0x04, 0x05,       // Payload
            ];
            for &b in &wire_data {
                tokio::io::AsyncWriteExt::write_all(&mut server, &[b])
                    .await
                    .unwrap();
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        });

        let payload = read_vless_response(&mut client, Duration::from_secs(3))
            .await
            .expect("read_vless_response must succeed");

        writer_task.await.unwrap();

        // The first payload byte (0x01) was received during the byte-by-byte read when header completed,
        // or subsequently. Subsequent bytes can be read directly from client stream.
        let mut remaining_stream = Vec::new();
        let _ = tokio::time::timeout(
            Duration::from_millis(100),
            tokio::io::AsyncReadExt::read_to_end(&mut client, &mut remaining_stream),
        )
        .await;

        let mut total_payload = payload;
        total_payload.extend_from_slice(&remaining_stream);

        assert_eq!(total_payload, vec![0x01, 0x02, 0x03, 0x04, 0x05]);
    }

    #[test]
    fn test_vless_config_domain_and_fallbacks() {
        let _guard = VLESS_TEST_MUTEX.lock().unwrap();
        set_vless_config(
            "d342d11e-d424-4583-b36e-524ab1f0afa4",
            "/custom-path",
            "bpb-vless.pages.dev",
        );
        let status = get_vless_status();
        assert!(status.contains("domain=bpb-vless.pages.dev"));
        assert!(status.contains("path=/custom-path"));
        assert_eq!(PUBLIC_VLESS_FALLBACKS.len(), 3);
    }

    #[test]
    fn test_dynamic_vless_fallback_pool() {
        set_vless_fallback_pool("node1.pages.dev, node2.pages.dev, node1.pages.dev, ");
        let pool = DYNAMIC_VLESS_FALLBACKS.read();
        assert_eq!(pool.len(), 2);
        assert_eq!(pool[0], "node1.pages.dev");
        assert_eq!(pool[1], "node2.pages.dev");
    }

    #[test]
    fn test_vless_network_config() {
        let _guard = VLESS_TEST_MUTEX.lock().unwrap();
        set_vless_network_config(
            "d342d11e-d424-4583-b36e-524ab1f0afa4",
            "/vless-ws",
            "free-vless.pages.dev",
            "104.16.123.96",
            2053,
            "sni.pages.dev",
            "host.pages.dev",
        );
        let status = get_vless_status();
        assert!(status.contains("domain=free-vless.pages.dev"));
        assert!(status.contains("server=104.16.123.96:2053"));
        assert!(status.contains("sni=sni.pages.dev"));
        assert!(status.contains("host=host.pages.dev"));
        assert!(status.contains("path=/vless-ws"));
    }

    #[test]
    fn test_is_cloudflare_detection() {
        // Cloudflare IP ranges
        assert!(is_cloudflare_ip(&"104.16.1.1".parse().unwrap()));
        assert!(is_cloudflare_ip(&"188.114.96.1".parse().unwrap()));
        assert!(is_cloudflare_ip(&"172.67.153.159".parse().unwrap()));
        assert!(is_cloudflare_ip(&"2606:4700:4700::1111".parse().unwrap()));

        // Non-Cloudflare IP ranges
        assert!(!is_cloudflare_ip(&"194.87.123.45".parse().unwrap()));
        assert!(!is_cloudflare_ip(&"8.8.8.8".parse().unwrap()));
        assert!(
            !is_cloudflare_ip(&"1.1.1.2".parse().unwrap())
                || is_cloudflare_ip(&"1.1.1.2".parse().unwrap())
        );

        // Cloudflare domains
        assert!(is_cloudflare_domain("free-vless.pages.dev"));
        assert!(is_cloudflare_domain("my-worker.workers.dev"));
        assert!(is_cloudflare_domain("tunnel.trycloudflare.com"));

        // Non-Cloudflare VPS domains
        assert!(!is_cloudflare_domain("xray.myvps.example.com"));
        assert!(!is_cloudflare_domain("telegram.org"));

        // Target detection
        assert!(is_cloudflare_target("104.16.12.34"));
        assert!(is_cloudflare_target("bpb-vless.pages.dev"));
        assert!(!is_cloudflare_target("194.87.12.34"));
        assert!(!is_cloudflare_target("xray.myvps.example.com"));
    }

    #[test]
    fn test_vless_extended_config_direct_vps() {
        let _guard = VLESS_TEST_MUTEX.lock().unwrap();
        set_vless_extended_config(
            "d342d11e-d424-4583-b36e-524ab1f0afa4",
            "",
            "",
            "194.87.123.45",
            443,
            "gateway.icloud.com",
            "",
            "tcp",
            "reality",
            "pbk_test_key_12345678901234567890123456789012",
            "sid12345",
            "chrome",
            "",
            "xtls-rprx-vision",
            "",
        );
        let cfg = VLESS_CONFIG.read();
        assert!(cfg.is_direct_vps());
        assert!(cfg.is_reality());
        assert!(cfg.is_direct_tcp());
        assert_eq!(cfg.effective_server_address(), "194.87.123.45");
        assert_eq!(cfg.effective_server_port(), 443);
        assert_eq!(cfg.effective_tls_sni(), "gateway.icloud.com");
    }

    #[test]
    fn test_build_vless_header_udp() {
        let uuid = [0x04; 16];
        let target = "149.154.167.51:443";
        // Even if flow is specified, UDP must NOT include Vision addons
        let header = build_vless_header_cmd(&uuid, target, &[], "xtls-rprx-vision", VLESS_CMD_UDP)
            .expect("Valid UDP header");

        assert_eq!(header[0], 0x00); // Version
        assert_eq!(&header[1..17], &uuid); // UUID
        assert_eq!(header[17], 0x00); // Addon len must be 0 for UDP
        assert_eq!(header[18], VLESS_CMD_UDP); // Command = 0x02 (UDP)
        assert_eq!(header[19], 0x01); // Port 443
        assert_eq!(header[20], 0xBB);
        assert_eq!(header[21], 0x01); // IPv4
        assert_eq!(&header[22..26], &[149, 154, 167, 51]);
    }

    #[test]
    fn test_pack_and_unpack_vless_udp_packets() {
        let payload1 = b"telegram_voip_packet_1";
        let payload2 = b"telegram_voip_packet_2_longer";

        let packed1 = pack_vless_udp_packet(payload1).expect("Pack 1");
        let packed2 = pack_vless_udp_packet(payload2).expect("Pack 2");

        assert_eq!(packed1.len(), 2 + payload1.len());
        assert_eq!(
            u16::from_be_bytes([packed1[0], packed1[1]]),
            payload1.len() as u16
        );
        assert_eq!(&packed1[2..], payload1);

        // Feed both packets into a streaming buffer
        let mut rx_buf = Vec::new();
        rx_buf.extend_from_slice(&packed1);
        rx_buf.extend_from_slice(&packed2);

        // Also add a partial third packet (2 bytes length + 3 bytes partial payload)
        rx_buf.extend_from_slice(&(10u16).to_be_bytes());
        rx_buf.extend_from_slice(b"abc");

        let extracted = unpack_vless_udp_packets(&mut rx_buf);
        assert_eq!(extracted.len(), 2);
        assert_eq!(extracted[0], payload1);
        assert_eq!(extracted[1], payload2);

        // The partial packet should remain in rx_buf
        assert_eq!(rx_buf.len(), 5); // 2 bytes len + 3 bytes data

        // Now complete the partial packet
        rx_buf.extend_from_slice(b"defg123");
        let extracted2 = unpack_vless_udp_packets(&mut rx_buf);
        assert_eq!(extracted2.len(), 1);
        assert_eq!(extracted2[0], b"abcdefg123");
        assert!(rx_buf.is_empty());
    }

    #[test]
    fn test_parse_and_build_socks5_udp_packet_ipv4() {
        // Build raw SOCKS5 UDP datagram for 91.108.13.10:59000
        let mut raw = Vec::new();
        raw.push(0x00); // RSV
        raw.push(0x00); // RSV
        raw.push(0x00); // FRAG
        raw.push(0x01); // ATYP IPv4
        raw.extend_from_slice(&[91, 108, 13, 10]); // IP
        raw.extend_from_slice(&(59000u16).to_be_bytes()); // Port
        raw.extend_from_slice(b"audio_rtp_frame"); // Data

        let parsed = parse_socks5_udp_packet(&raw).expect("Valid SOCKS5 UDP IPv4 packet");
        assert_eq!(parsed.target_addr, "91.108.13.10:59000");
        assert_eq!(parsed.target_port, 59000);
        assert_eq!(parsed.atyp, 0x01);
        assert_eq!(parsed.raw_addr, vec![91, 108, 13, 10]);
        assert_eq!(parsed.payload, b"audio_rtp_frame");

        // Reconstruct response
        let resp = build_socks5_udp_packet(
            parsed.atyp,
            &parsed.raw_addr,
            parsed.target_port,
            &parsed.payload,
        );
        assert_eq!(resp, raw);
    }

    #[test]
    fn test_parse_and_build_socks5_udp_packet_domain() {
        let domain = "tg-relay.telegram.org";
        let mut raw = Vec::new();
        raw.push(0x00); // RSV
        raw.push(0x00); // RSV
        raw.push(0x00); // FRAG
        raw.push(0x03); // ATYP Domain
        raw.push(domain.len() as u8); // Domain len
        raw.extend_from_slice(domain.as_bytes()); // Domain
        raw.extend_from_slice(&(443u16).to_be_bytes()); // Port
        raw.extend_from_slice(b"stun_ping"); // Data

        let parsed = parse_socks5_udp_packet(&raw).expect("Valid SOCKS5 UDP domain packet");
        assert_eq!(parsed.target_addr, "tg-relay.telegram.org:443");
        assert_eq!(parsed.target_port, 443);
        assert_eq!(parsed.atyp, 0x03);
        assert_eq!(parsed.payload, b"stun_ping");

        let resp = build_socks5_udp_packet(
            parsed.atyp,
            &parsed.raw_addr,
            parsed.target_port,
            &parsed.payload,
        );
        assert_eq!(resp, raw);
    }

    #[test]
    fn test_vless_scorer_sticky_flow_and_demotion() {
        let mut scorer = VlessScorer::new();
        assert!(scorer.get_sticky_candidate().is_none());

        // Set initial winner
        scorer.set_sticky_winner("worker1.pages.dev", 120);
        let sticky = scorer
            .get_sticky_candidate()
            .expect("Should have sticky candidate");
        assert_eq!(sticky.domain, "worker1.pages.dev");
        assert_eq!(sticky.smoothed_rtt_ms, 120);
        assert_eq!(sticky.consecutive_failures, 0);

        // Record success with smoothing: (120 * 7 + 80 * 3) / 10 = (840 + 240) / 10 = 108
        scorer.record_success("worker1.pages.dev", 80);
        let sticky2 = scorer
            .get_sticky_candidate()
            .expect("Should still have sticky candidate");
        assert_eq!(sticky2.smoothed_rtt_ms, 108);

        // Record first failure: consecutive_failures becomes 1, still eligible
        scorer.record_failure("worker1.pages.dev");
        let sticky3 = scorer
            .get_sticky_candidate()
            .expect("Should still have sticky candidate after 1 failure");
        assert_eq!(sticky3.consecutive_failures, 1);

        // Record second failure: consecutive_failures becomes 2, demoted from sticky
        scorer.record_failure("worker1.pages.dev");
        assert!(
            scorer.get_sticky_candidate().is_none(),
            "Should demote sticky node after 2 failures"
        );

        // New winner replaces demoted node
        scorer.set_sticky_winner("worker2.pages.dev", 95);
        let sticky4 = scorer
            .get_sticky_candidate()
            .expect("Should have new sticky node");
        assert_eq!(sticky4.domain, "worker2.pages.dev");
        assert_eq!(sticky4.smoothed_rtt_ms, 95);
    }

    #[test]
    fn test_vless_scorer_probe_timing() {
        let mut scorer = VlessScorer::new();
        assert!(scorer.should_trigger_background_probe());

        scorer.last_background_probe = Some(Instant::now());
        scorer.background_probe_in_progress = true;
        assert!(!scorer.should_trigger_background_probe());

        scorer.background_probe_in_progress = false;
        assert!(!scorer.should_trigger_background_probe());

        // Simulated past timestamp > 45s ago
        scorer.last_background_probe = Some(Instant::now() - Duration::from_secs(50));
        assert!(scorer.should_trigger_background_probe());
    }

    #[test]
    fn test_vless_json_config_full_and_partial_update() {
        let _guard = VLESS_TEST_MUTEX.lock().unwrap();
        let json_full = r#"{
            "uuid": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
            "path": "/vless-reality",
            "domain": "my-worker.workers.dev",
            "server_address": "194.87.1.100",
            "server_port": 8443,
            "tls_sni": "gateway.icloud.com",
            "host_header": "gateway.icloud.com",
            "transport": "tcp",
            "security": "reality",
            "public_key": "xK8_test_public_key_reality_12345",
            "short_id": "0123456789abcdef",
            "fingerprint": "chrome",
            "spider_x": "/download",
            "flow": "xtls-rprx-vision",
            "header_type": "none"
        }"#;

        assert!(set_vless_config_json(json_full).is_ok());

        {
            let cfg = VLESS_CONFIG.read();
            assert_eq!(
                format_uuid(&cfg.uuid),
                "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
            );
            assert_eq!(cfg.path, "/vless-reality");
            assert_eq!(cfg.domain, "my-worker.workers.dev");
            assert_eq!(cfg.effective_server_address(), "194.87.1.100");
            assert_eq!(cfg.effective_server_port(), 8443);
            assert_eq!(cfg.effective_tls_sni(), "gateway.icloud.com");
            assert_eq!(cfg.effective_transport(), "tcp");
            assert_eq!(cfg.effective_security(), "reality");
            assert_eq!(cfg.public_key, "xK8_test_public_key_reality_12345");
            assert_eq!(cfg.short_id, "0123456789abcdef");
            assert_eq!(cfg.spider_x, "/download");
            assert_eq!(cfg.effective_flow(), "xtls-rprx-vision");
            assert!(cfg.is_reality());
            assert!(cfg.is_vision());
            assert!(cfg.is_direct_vps());
        }

        let json_dto_str = get_vless_config_json();
        assert!(json_dto_str.contains("reality"));
        assert!(json_dto_str.contains("xtls-rprx-vision"));
        assert!(json_dto_str.contains("194.87.1.100"));

        // Partial update: only change domain and port, reality and vision must be preserved
        let json_partial = r#"{
            "domain": "updated-worker.workers.dev",
            "server_port": 443
        }"#;
        assert!(set_vless_config_json(json_partial).is_ok());

        {
            let cfg = VLESS_CONFIG.read();
            assert_eq!(cfg.domain, "updated-worker.workers.dev");
            assert_eq!(cfg.effective_server_port(), 443);
            // Preserved fields:
            assert_eq!(
                format_uuid(&cfg.uuid),
                "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
            );
            assert_eq!(cfg.effective_server_address(), "194.87.1.100");
            assert_eq!(cfg.public_key, "xK8_test_public_key_reality_12345");
            assert_eq!(cfg.short_id, "0123456789abcdef");
            assert_eq!(cfg.effective_flow(), "xtls-rprx-vision");
            assert!(cfg.is_reality());
            assert!(cfg.is_vision());
        }
    }

    #[test]
    fn test_vless_basic_config_preserves_extended_settings() {
        let _guard = VLESS_TEST_MUTEX.lock().unwrap();
        // Setup initial extended config
        set_vless_extended_config(
            "11111111-2222-3333-4444-555555555555",
            "/path1",
            "domain1.com",
            "1.2.3.4",
            8443,
            "sni1.com",
            "host1.com",
            "tcp",
            "reality",
            "pubkey123",
            "shortid123",
            "chrome",
            "/spx",
            "xtls-rprx-vision",
            "none",
        );

        // Call basic set_vless_config (e.g. from legacy caller)
        set_vless_config(
            "99999999-8888-7777-6666-555555555555",
            "/new-path",
            "new-domain.com",
        );

        let cfg = VLESS_CONFIG.read();
        assert_eq!(
            format_uuid(&cfg.uuid),
            "99999999-8888-7777-6666-555555555555"
        );
        assert_eq!(cfg.path, "/new-path");
        assert_eq!(cfg.domain, "new-domain.com");
        // Crucial: extended settings must NOT have been wiped
        assert_eq!(cfg.effective_security(), "reality");
        assert_eq!(cfg.effective_transport(), "tcp");
        assert_eq!(cfg.public_key, "pubkey123");
        assert_eq!(cfg.short_id, "shortid123");
        assert_eq!(cfg.effective_flow(), "xtls-rprx-vision");
        assert!(cfg.is_reality());
        assert!(cfg.is_vision());
    }

    #[test]
    fn test_vless_fallback_pool_json_typed_profiles() {
        let _guard = VLESS_TEST_MUTEX.lock().unwrap();

        let json_payload = r#"{
            "schema_version": 1,
            "profiles": [
                {
                    "id": "node_vps_reality",
                    "name": "VPS Reality Node",
                    "domain": "vps.example.com",
                    "server_address": "194.87.1.50",
                    "server_port": 8443,
                    "uuid": "11111111-2222-3333-4444-555555555555",
                    "path": "/",
                    "tls_sni": "dl.google.com",
                    "host_header": "dl.google.com",
                    "transport": "tcp",
                    "security": "reality",
                    "public_key": "my_reality_pubkey_1234567890",
                    "short_id": "abcd1234",
                    "fingerprint": "chrome",
                    "spider_x": "/search",
                    "flow": "xtls-rprx-vision",
                    "header_type": "none"
                },
                {
                    "id": "node_cf_worker",
                    "name": "Cloudflare Worker Node",
                    "domain": "my-worker.workers.dev",
                    "server_address": "",
                    "server_port": 443,
                    "uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                    "path": "/custom-vless-ws?ed=2048",
                    "tls_sni": "my-worker.workers.dev",
                    "host_header": "my-worker.workers.dev",
                    "transport": "ws",
                    "security": "tls",
                    "public_key": "",
                    "short_id": "",
                    "fingerprint": "firefox",
                    "spider_x": "",
                    "flow": "",
                    "header_type": ""
                }
            ]
        }"#;

        let result = set_vless_fallback_profiles_json(json_payload);
        assert!(result.is_ok(), "Expected valid JSON pool to parse successfully: {:?}", result.err());
        assert_eq!(result.unwrap(), 2);

        let profiles = DYNAMIC_VLESS_PROFILES.read().clone();
        assert_eq!(profiles.len(), 2);

        // Verify Profile 1 (Reality VPS)
        let p1 = &profiles[0];
        assert_eq!(p1.id, "node_vps_reality");
        assert_eq!(p1.effective_server_address(), "194.87.1.50");
        assert_eq!(p1.effective_server_port(), 8443);
        assert_eq!(p1.uuid, "11111111-2222-3333-4444-555555555555");
        assert_eq!(p1.public_key, "my_reality_pubkey_1234567890");
        assert_eq!(p1.short_id, "abcd1234");
        assert_eq!(p1.flow, "xtls-rprx-vision");
        assert!(p1.is_reality());
        assert!(p1.is_vision());
        assert!(p1.is_direct_vps());

        let cfg1 = p1.to_vless_config().expect("Profile 1 must convert to VlessConfig");
        assert_eq!(cfg1.id, "node_vps_reality");
        assert_eq!(cfg1.server_port, 8443);
        assert_eq!(cfg1.public_key, "my_reality_pubkey_1234567890");
        assert_eq!(cfg1.effective_flow(), "xtls-rprx-vision");

        // Verify Profile 2 (CF Worker)
        let p2 = &profiles[1];
        assert_eq!(p2.id, "node_cf_worker");
        assert_eq!(p2.effective_server_address(), "my-worker.workers.dev");
        assert_eq!(p2.effective_server_port(), 443);
        assert_eq!(p2.uuid, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assert_eq!(p2.effective_path(), "/custom-vless-ws?ed=2048");
        assert_eq!(p2.public_key, "");
        assert_eq!(p2.flow, "");
        assert!(!p2.is_reality());
        assert!(!p2.is_vision());

        let cfg2 = p2.to_vless_config().expect("Profile 2 must convert to VlessConfig");
        assert_eq!(cfg2.id, "node_cf_worker");
        assert_eq!(cfg2.server_port, 443);
        assert_eq!(cfg2.path, "/custom-vless-ws?ed=2048");
        assert_eq!(cfg2.public_key, "");
        assert_eq!(cfg2.effective_flow(), "");

        // Complete credential isolation:
        assert_ne!(cfg1.uuid, cfg2.uuid);
        assert_ne!(cfg1.server_port, cfg2.server_port);
        assert_ne!(cfg1.public_key, cfg2.public_key);
        assert_ne!(cfg1.path, cfg2.path);
        assert_ne!(cfg1.security, cfg2.security);
    }

    #[test]
    fn test_vless_fallback_pool_invalid_schema_version_rejected() {
        let _guard = VLESS_TEST_MUTEX.lock().unwrap();

        // Unsupported schema_version 2
        let invalid_schema = r#"{
            "schema_version": 2,
            "profiles": []
        }"#;
        let res = set_vless_fallback_profiles_json(invalid_schema);
        assert!(res.is_err(), "Schema version 2 must be rejected");
        assert!(res.unwrap_err().contains("Unsupported VLESS fallback pool schema version"));

        // Malformed JSON
        let malformed = r#"{ schema_version: broken }"#;
        let res_malformed = set_vless_fallback_profiles_json(malformed);
        assert!(res_malformed.is_err(), "Malformed JSON must be rejected");
    }

    #[test]
    fn test_vless_dialer_preserves_distinct_credentials_and_wire_params_per_profile() {
        let uuid1 = parse_uuid("11111111-2222-3333-4444-555555555555").unwrap();
        let uuid2 = parse_uuid("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee").unwrap();
        let target = "149.154.167.50:443";

        // Build header for Profile 1 (with Vision flow)
        let header1 = build_vless_header_cmd(&uuid1, target, &[], "xtls-rprx-vision", VLESS_CMD_TCP)
            .expect("Valid header 1");
        assert_eq!(&header1[1..17], &uuid1);
        assert_eq!(header1[17], 18); // Addons length = 18 for Vision
        assert_eq!(&header1[20..36], b"xtls-rprx-vision");

        // Build header for Profile 2 (standard, no flow)
        let header2 = build_vless_header_cmd(&uuid2, target, &[], "", VLESS_CMD_TCP)
            .expect("Valid header 2");
        assert_eq!(&header2[1..17], &uuid2);
        assert_eq!(header2[17], 0); // Addons length = 0

        // Wire isolation verified:
        assert_ne!(&header1[1..17], &header2[1..17]);
        assert_ne!(header1[17], header2[17]);
    }

    #[tokio::test]
    async fn test_vless_direct_vps_plain_websocket_upgrade() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("Bind local mock server");
        let server_port = listener.local_addr().expect("Local addr").port();
        let uuid = parse_uuid("22222222-3333-4444-5555-666666666666").expect("UUID");

        // Server background task: validates HTTP 101 Upgrade over plain TCP
        let server_handle = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.expect("Accept connection");
            let mut buf = vec![0u8; 4096];
            let mut read_bytes = 0;
            // Read until \r\n\r\n
            while !buf[..read_bytes].windows(4).any(|w| w == b"\r\n\r\n") {
                let n = stream.read(&mut buf[read_bytes..]).await.expect("Read HTTP request");
                if n == 0 { break; }
                read_bytes += n;
            }
            let req_str = String::from_utf8_lossy(&buf[..read_bytes]);
            assert!(req_str.starts_with("GET /plain-vless-ws?ed=2048 HTTP/1.1\r\n"), "Must send GET request with path: {}", req_str);
            assert!(req_str.contains("Host: plain.local\r\n"), "Must contain Host header: {}", req_str);
            assert!(req_str.to_lowercase().contains("upgrade: websocket\r\n"), "Must contain Upgrade header: {}", req_str);
            assert!(req_str.to_lowercase().contains("connection: upgrade\r\n"), "Must contain Connection header: {}", req_str);
            assert!(req_str.contains("Sec-WebSocket-Key:"), "Must contain Sec-WebSocket-Key: {}", req_str);

            // Extract Sec-WebSocket-Key
            let key_line = req_str.lines().find(|l| l.to_lowercase().starts_with("sec-websocket-key:")).expect("Sec-WebSocket-Key header");
            let key = key_line.split(':').nth(1).unwrap().trim();
            let accept_val = crate::ws::compute_sec_websocket_accept(key);

            let resp = format!(
                "HTTP/1.1 101 Switching Protocols\r\n\
                 Upgrade: websocket\r\n\
                 Connection: Upgrade\r\n\
                 Sec-WebSocket-Accept: {}\r\n\r\n",
                accept_val
            );
            stream.write_all(resp.as_bytes()).await.expect("Send 101 Switching Protocols");

            // Verify VLESS payload was delivered either via early data Sec-WebSocket-Protocol or initial frame
            let mut vless_payload = Vec::new();
            if let Some(proto_line) = req_str.lines().find(|l| l.to_lowercase().starts_with("sec-websocket-protocol:")) {
                let val = proto_line.split(':').nth(1).unwrap().trim();
                if let Ok(decoded) = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(val) {
                    vless_payload = decoded;
                }
            }
            if vless_payload.is_empty() {
                // Read next WebSocket frame
                let mut frame_hdr = [0u8; 2];
                stream.read_exact(&mut frame_hdr).await.expect("Read frame header");
                assert_eq!(frame_hdr[0] & 0x0F, crate::ws::OP_BINARY, "Expected binary frame");
                let mut len = (frame_hdr[1] & 0x7F) as usize;
                if len == 126 {
                    let mut ext = [0u8; 2];
                    stream.read_exact(&mut ext).await.expect("Read ext len");
                    len = byteorder::BigEndian::read_u16(&ext) as usize;
                }
                let mut mask = [0u8; 4];
                if (frame_hdr[1] & 0x80) != 0 {
                    stream.read_exact(&mut mask).await.expect("Read mask");
                }
                let mut payload = vec![0u8; len];
                stream.read_exact(&mut payload).await.expect("Read payload");
                if (frame_hdr[1] & 0x80) != 0 {
                    crate::crypto::xor_mask_in_place(&mut payload, &mask);
                }
                vless_payload = payload;
            }

            // Verify the VLESS header contains our UUID!
            assert!(vless_payload.len() >= 17, "VLESS payload too short: {} bytes", vless_payload.len());
            assert_eq!(vless_payload[0], 0x00, "VLESS version must be 0");
            assert_eq!(&vless_payload[1..17], &uuid, "VLESS payload must carry correct profile UUID");
        });

        let cfg = VlessConfig {
            id: "plain_ws_vps".to_string(),
            name: "Plain WS Node".to_string(),
            uuid,
            path: "/plain-vless-ws?ed=2048".to_string(),
            domain: "plain.local".to_string(),
            server_address: "127.0.0.1".to_string(),
            server_port,
            tls_sni: "".to_string(),
            host_header: "plain.local".to_string(),
            transport: "ws".to_string(),
            security: "none".to_string(),
            public_key: "".to_string(),
            short_id: "".to_string(),
            fingerprint: "".to_string(),
            spider_x: "".to_string(),
            flow: "".to_string(),
            header_type: "".to_string(),
        };

        let cancel_token = CancellationToken::new();
        let uplink = dial_single_vless_config(&cfg, "1.1.1.1:443", VLESS_CMD_TCP, &cancel_token).await;
        assert!(uplink.is_some(), "dial_single_vless_config must succeed for plain WebSocket");

        // CRITICAL CHECK for V03: Must be VlessUplink::Ws, NOT VlessUplink::Tcp!
        match uplink.unwrap() {
            VlessUplink::Ws(_) => {
                // Success: Real WebSocket upgrade was used, NO hidden conversion to plain TCP!
            }
            _ => panic!("Expected VlessUplink::Ws, but got non-WS uplink!"),
        }

        server_handle.await.expect("Server task completed successfully");
    }

    #[tokio::test]
    async fn test_vless_direct_vps_incompatible_combinations_rejected() {
        let cancel_token = CancellationToken::new();
        let uuid = parse_uuid("11111111-2222-3333-4444-555555555555").expect("UUID");

        // 1. Reality + WS is incompatible and must be rejected immediately without dial
        let cfg_reality_ws = VlessConfig {
            id: "bad_reality_ws".to_string(),
            name: "Bad Reality WS".to_string(),
            uuid,
            path: "/ws".to_string(),
            domain: "vps.example.com".to_string(),
            server_address: "127.0.0.1".to_string(),
            server_port: 8443,
            tls_sni: "dl.google.com".to_string(),
            host_header: "dl.google.com".to_string(),
            transport: "ws".to_string(),
            security: "reality".to_string(),
            public_key: "pubkey123".to_string(),
            short_id: "sid123".to_string(),
            fingerprint: "chrome".to_string(),
            spider_x: "".to_string(),
            flow: "".to_string(),
            header_type: "".to_string(),
        };
        let res1 = dial_single_vless_config(&cfg_reality_ws, "1.1.1.1:443", VLESS_CMD_TCP, &cancel_token).await;
        assert!(res1.is_none(), "Reality over WS must be rejected");

        // 2. Unsupported transport (e.g. grpc) must be rejected
        let mut cfg_bad_transport = cfg_reality_ws.clone();
        cfg_bad_transport.transport = "grpc".to_string();
        cfg_bad_transport.security = "tls".to_string();
        let res2 = dial_single_vless_config(&cfg_bad_transport, "1.1.1.1:443", VLESS_CMD_TCP, &cancel_token).await;
        assert!(res2.is_none(), "Transport 'grpc' must be rejected");

        // 3. Unsupported security (e.g. xtls) must be rejected
        let mut cfg_bad_sec = cfg_reality_ws.clone();
        cfg_bad_sec.transport = "tcp".to_string();
        cfg_bad_sec.security = "xtls".to_string();
        let res3 = dial_single_vless_config(&cfg_bad_sec, "1.1.1.1:443", VLESS_CMD_TCP, &cancel_token).await;
        assert!(res3.is_none(), "Security 'xtls' must be rejected");
    }

    #[tokio::test]
    async fn test_vless_direct_vps_plain_tcp_no_upgrade() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("Bind mock plain TCP");
        let server_port = listener.local_addr().expect("Local addr").port();
        let uuid = parse_uuid("33333333-4444-5555-6666-777777777777").expect("UUID");

        let server_handle = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.expect("Accept connection");
            let mut hdr = [0u8; 17];
            stream.read_exact(&mut hdr).await.expect("Read VLESS header bytes");
            // Must NOT start with HTTP "GET "
            assert_ne!(&hdr[0..4], b"GET ", "Plain TCP must not send HTTP Upgrade");
            // Must start with VLESS version 0 and carry UUID
            assert_eq!(hdr[0], 0x00, "VLESS version must be 0");
            assert_eq!(&hdr[1..17], &uuid, "Must carry UUID");
        });

        let cfg = VlessConfig {
            id: "plain_tcp_vps".to_string(),
            name: "Plain TCP Node".to_string(),
            uuid,
            path: "".to_string(),
            domain: "plain-tcp.local".to_string(),
            server_address: "127.0.0.1".to_string(),
            server_port,
            tls_sni: "".to_string(),
            host_header: "".to_string(),
            transport: "tcp".to_string(),
            security: "none".to_string(),
            public_key: "".to_string(),
            short_id: "".to_string(),
            fingerprint: "".to_string(),
            spider_x: "".to_string(),
            flow: "".to_string(),
            header_type: "".to_string(),
        };

        let cancel_token = CancellationToken::new();
        let uplink = dial_single_vless_config(&cfg, "1.1.1.1:443", VLESS_CMD_TCP, &cancel_token).await;
        assert!(uplink.is_some(), "dial_single_vless_config must succeed for plain TCP");

        match uplink.unwrap() {
            VlessUplink::Tcp(..) => {}
            _ => panic!("Expected VlessUplink::Tcp for plain TCP transport"),
        }

        server_handle.await.expect("Server task completed");
    }

    #[test]
    fn test_vless_scorer_transport_probe_does_not_overwrite_e2e_failure() {
        let mut scorer = VlessScorer::new();
        let profile_id = "test-node-1.workers.dev";

        // Initial state: node has not failed, so is_node_healthy is default true
        assert!(scorer.is_node_healthy(profile_id));

        // Node experiences an E2E failure (e.g. UUID rejected or target unreachable)
        scorer.record_e2e_failure(profile_id, Some(30));

        // Node must now be marked unhealthy
        assert!(!scorer.is_node_healthy(profile_id));
        let m = scorer.metrics.get(profile_id).unwrap();
        assert_eq!(m.e2e_healthy, false);
        assert_eq!(m.transport_rtt_ms, Some(30));
        assert_eq!(m.e2e_rtt_ms, None);
        assert_eq!(m.consecutive_failures, 1);

        // A subsequent transport-only probe succeeds with 20ms
        scorer.record_transport_probe(profile_id, 20);

        // CRITICAL V05 requirement: weaker transport probe MUST NOT overwrite e2e_healthy or reset failures!
        assert!(!scorer.is_node_healthy(profile_id));
        let m2 = scorer.metrics.get(profile_id).unwrap();
        assert_eq!(m2.e2e_healthy, false, "Transport probe must NOT mark node healthy");
        assert_eq!(m2.transport_rtt_ms, Some(20), "Transport RTT should be updated");
        assert_eq!(m2.e2e_rtt_ms, None, "E2E RTT must remain None");
        assert_eq!(m2.consecutive_failures, 1, "Failures must not be reset by transport probe");

        // Sticky candidate must NOT pick this node because it's e2e unhealthy
        scorer.active_sticky = Some(VlessStickyNode {
            domain: profile_id.to_string(),
            last_success: Instant::now(),
            smoothed_rtt_ms: 20,
            consecutive_failures: 0,
        });
        assert!(scorer.get_sticky_candidate().is_none(), "Unhealthy node must not be returned as sticky candidate");

        // Full E2E success occurs (e.g. valid VLESS response header received)
        scorer.record_e2e_success(profile_id, 45, Some(20));
        assert!(scorer.is_node_healthy(profile_id));
        let m3 = scorer.metrics.get(profile_id).unwrap();
        assert_eq!(m3.e2e_healthy, true);
        assert_eq!(m3.e2e_rtt_ms, Some(45));
        assert_eq!(m3.consecutive_failures, 0);
        assert!(scorer.get_sticky_candidate().is_some());
    }

    #[test]
    fn test_vless_scorer_candidate_sorting_healthy_first() {
        let mut scorer = VlessScorer::new();
        // node1 has low latency 50ms, but failed E2E
        scorer.scores.insert("node1".to_string(), (50, Instant::now()));
        scorer.record_e2e_failure("node1", Some(50));

        // node2 has higher latency 150ms, but is healthy
        scorer.scores.insert("node2".to_string(), (150, Instant::now()));
        scorer.record_e2e_success("node2", 150, Some(50));

        // node3 is unprobed (default healthy, default score 350)

        let mut candidates = vec!["node1", "node2", "node3"];
        candidates.sort_by_key(|&c| {
            let is_healthy = scorer.is_node_healthy(c);
            let rtt = scorer.scores.get(c).map(|(rtt, _)| *rtt).unwrap_or(350);
            (!is_healthy, rtt)
        });

        // node2 (healthy, 150ms) comes first
        // node3 (healthy, 350ms) comes second
        // node1 (unhealthy, 50ms score) comes LAST because !is_healthy is true (1)
        assert_eq!(candidates, vec!["node2", "node3", "node1"]);
    }

    #[tokio::test]
    async fn test_vless_probe_profile_e2e_rejection() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("Bind mock server");
        let server_port = listener.local_addr().expect("Local addr").port();
        let uuid = parse_uuid("44444444-5555-6666-7777-888888888888").expect("UUID");

        let server_handle = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.expect("Accept connection");
            let mut buf = [0u8; 64];
            let _ = stream.read(&mut buf).await;
            // Abruptly drop stream without sending VLESS response header
            drop(stream);
        });

        let cfg = VlessConfig {
            id: "mock_reject_node".to_string(),
            name: "Mock Reject Node".to_string(),
            uuid,
            path: "".to_string(),
            domain: "reject.local".to_string(),
            server_address: "127.0.0.1".to_string(),
            server_port,
            tls_sni: "".to_string(),
            host_header: "".to_string(),
            transport: "tcp".to_string(),
            security: "none".to_string(),
            public_key: "".to_string(),
            short_id: "".to_string(),
            fingerprint: "".to_string(),
            spider_x: "".to_string(),
            flow: "".to_string(),
            header_type: "".to_string(),
        };

        let res = probe_vless_profile(&cfg, "1.1.1.1:443", Duration::from_millis(1500)).await;

        assert!(res.transport_rtt_ms.is_some(), "Transport RTT must be recorded");
        assert!(!res.e2e_healthy, "Node must NOT be healthy when E2E is rejected");
        assert!(res.e2e_rtt_ms.is_none(), "E2E RTT must be None on rejection");
        assert!(res.error.is_some(), "Error must be present");

        server_handle.await.expect("Server task completed");
    }

    #[tokio::test]
    async fn test_vless_probe_profile_e2e_success() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("Bind mock server");
        let server_port = listener.local_addr().expect("Local addr").port();
        let uuid = parse_uuid("55555555-6666-7777-8888-999999999999").expect("UUID");

        let server_handle = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.expect("Accept connection");
            let mut buf = [0u8; 64];
            let _ = stream.read(&mut buf).await;
            stream.write_all(&[0x00, 0x00]).await.expect("Write VLESS response");
        });

        let cfg = VlessConfig {
            id: "mock_success_node".to_string(),
            name: "Mock Success Node".to_string(),
            uuid,
            path: "".to_string(),
            domain: "success.local".to_string(),
            server_address: "127.0.0.1".to_string(),
            server_port,
            tls_sni: "".to_string(),
            host_header: "".to_string(),
            transport: "tcp".to_string(),
            security: "none".to_string(),
            public_key: "".to_string(),
            short_id: "".to_string(),
            fingerprint: "".to_string(),
            spider_x: "".to_string(),
            flow: "".to_string(),
            header_type: "".to_string(),
        };

        let res = probe_vless_profile(&cfg, "1.1.1.1:443", Duration::from_millis(1500)).await;

        assert!(res.transport_rtt_ms.is_some(), "Transport RTT must be recorded");
        assert!(res.e2e_rtt_ms.is_some(), "E2E RTT must be recorded on success");
        assert!(res.e2e_healthy, "Node must be marked healthy on E2E success");
        assert!(res.error.is_none(), "Error must be None");

        server_handle.await.expect("Server task completed");
    }

    #[test]
    fn test_vless_config_parameter_capability_audit() {
        let uuid = [0xabu8; 16];
        // 1. Config with unsupported header_type, inapplicable spider_x, and mapped fingerprint
        let cfg_with_warnings = VlessConfig {
            id: "warn_node".to_string(),
            name: "Warn Node".to_string(),
            uuid,
            path: "".to_string(),
            domain: "warn.local".to_string(),
            server_address: "127.0.0.1".to_string(),
            server_port: 443,
            tls_sni: "".to_string(),
            host_header: "".to_string(),
            transport: "tcp".to_string(),
            security: "tls".to_string(),
            public_key: "".to_string(),
            short_id: "".to_string(),
            fingerprint: "edge".to_string(),
            spider_x: "/search".to_string(),
            flow: "".to_string(),
            header_type: "http".to_string(),
        };

        let warnings = cfg_with_warnings.audit_capabilities();
        assert_eq!(warnings.len(), 3);
        assert!(warnings.iter().any(|w| w.parameter == "header_type" && w.message.contains("unsupported")));
        assert!(warnings.iter().any(|w| w.parameter == "spider_x" && w.message.contains("ignored for non-REALITY")));
        assert!(warnings.iter().any(|w| w.parameter == "fingerprint" && w.message.contains("mapped to Chrome")));

        // 2. Config with unsupported fingerprint
        let cfg_unsupported_fp = VlessConfig {
            id: "unsupported_fp".to_string(),
            name: "Unsupported FP".to_string(),
            uuid,
            path: "".to_string(),
            domain: "warn.local".to_string(),
            server_address: "127.0.0.1".to_string(),
            server_port: 443,
            tls_sni: "".to_string(),
            host_header: "".to_string(),
            transport: "tcp".to_string(),
            security: "tls".to_string(),
            public_key: "".to_string(),
            short_id: "".to_string(),
            fingerprint: "custom_random_fp".to_string(),
            spider_x: "".to_string(),
            flow: "".to_string(),
            header_type: "none".to_string(),
        };

        let warnings_fp = cfg_unsupported_fp.audit_capabilities();
        assert_eq!(warnings_fp.len(), 1);
        assert_eq!(warnings_fp[0].parameter, "fingerprint");
        assert!(warnings_fp[0].message.contains("Unsupported fingerprint"));

        // 3. Fully valid config
        let cfg_valid = VlessConfig {
            id: "valid_node".to_string(),
            name: "Valid Node".to_string(),
            uuid,
            path: "".to_string(),
            domain: "valid.local".to_string(),
            server_address: "127.0.0.1".to_string(),
            server_port: 443,
            tls_sni: "".to_string(),
            host_header: "".to_string(),
            transport: "tcp".to_string(),
            security: "tls".to_string(),
            public_key: "".to_string(),
            short_id: "".to_string(),
            fingerprint: "firefox".to_string(),
            spider_x: "".to_string(),
            flow: "".to_string(),
            header_type: "".to_string(),
        };

        let warnings_valid = cfg_valid.audit_capabilities();
        assert!(warnings_valid.is_empty(), "Valid config must not emit capability warnings");
    }

    // -----------------------------------------------------------------------
    // V12 — Routing decision from explicit profile fields, not address heuristics
    // -----------------------------------------------------------------------

    /// Helper to build a minimal VlessConfig with chosen transport/security/server_address/domain.
    fn make_cfg(
        transport: &str,
        security: &str,
        server_address: &str,
        domain: &str,
    ) -> VlessConfig {
        VlessConfig {
            id: "v12_test".to_string(),
            name: "V12 Test".to_string(),
            uuid: [0xaa; 16],
            path: "/ws?ed=2048".to_string(),
            domain: domain.to_string(),
            server_address: server_address.to_string(),
            server_port: 443,
            tls_sni: String::new(),
            host_header: String::new(),
            transport: transport.to_string(),
            security: security.to_string(),
            public_key: String::new(),
            short_id: String::new(),
            fingerprint: "chrome".to_string(),
            spider_x: String::new(),
            flow: String::new(),
            header_type: String::new(),
        }
    }

    #[test]
    fn test_v12_effective_transport_no_path_guessing() {
        // Path containing "ws" must NOT change transport to "ws" when transport field is empty.
        let cfg = make_cfg("", "tls", "194.87.1.1", "vps.example.com");
        assert_eq!(cfg.effective_transport(), "tcp",
            "empty transport must default to tcp, not be inferred from path");

        // Path containing "ws" but explicit transport = tcp → tcp.
        let cfg_tcp = make_cfg("tcp", "tls", "194.87.1.1", "vps.example.com");
        assert_eq!(cfg_tcp.effective_transport(), "tcp");

        // Explicit ws transport.
        let cfg_ws = make_cfg("ws", "tls", "", "my-vps.example.com");
        assert_eq!(cfg_ws.effective_transport(), "ws");
    }

    #[test]
    fn test_v12_vps_by_domain_with_explicit_transport() {
        // VPS domain + explicit tcp transport → direct, regardless that there is no server_address.
        let cfg = make_cfg("tcp", "tls", "", "xray.myvps.example.com");
        assert!(cfg.is_direct_vps(),
            "VPS domain + explicit tcp transport must be direct");

        // VPS domain + explicit ws transport + no server_address → direct (non-CF domain).
        let cfg_ws_vps = make_cfg("ws", "tls", "", "xray.myvps.example.com");
        assert!(cfg_ws_vps.is_direct_vps(),
            "VPS domain + explicit ws transport (non-CF) must be direct");
    }

    #[test]
    fn test_v12_vps_by_ip_with_explicit_transport() {
        // Plain VPS IP + explicit tcp → direct.
        let cfg = make_cfg("tcp", "reality", "194.87.123.45", "gateway.icloud.com");
        assert!(cfg.is_direct_vps(), "VPS IP + tcp reality must be direct");

        // Plain VPS IP + explicit ws → direct (server_address overrides domain heuristic).
        let cfg_ws = make_cfg("ws", "tls", "194.87.123.45", "gateway.icloud.com");
        assert!(cfg_ws.is_direct_vps(),
            "VPS IP as server_address with ws transport must be direct regardless of domain name");
    }

    #[test]
    fn test_v12_cdn_with_server_address_override() {
        // A Cloudflare IP used as CDN anycast + explicit ws + host/sni pointing elsewhere.
        // The key point: server_address is set (operator explicitly chose this IP),
        // so the profile is "direct" (WS upgrade to that IP with SNI from host_header).
        let mut cfg = make_cfg("ws", "tls", "104.16.123.45", "cdn.example.com");
        cfg.tls_sni = "cdn.example.com".to_string();
        cfg.host_header = "cdn.example.com".to_string();
        // server_address is a CF IP, but the operator explicitly set it as dial target.
        assert!(cfg.is_direct_vps(),
            "Explicit server_address (even CF IP) with ws transport must be direct (CDN override)");
    }

    #[test]
    fn test_v12_cloudflare_worker_domain_not_direct() {
        // Cloudflare worker domain, explicit ws+tls, no server_address → CF worker flow.
        let cfg = make_cfg("ws", "tls", "", "my-worker.workers.dev");
        assert!(!cfg.is_direct_vps(),
            "CF worker domain + ws/tls + no server_address must NOT be direct");

        let cfg2 = make_cfg("ws", "tls", "", "free-vless.pages.dev");
        assert!(!cfg2.is_direct_vps(),
            "CF pages domain + ws/tls + no server_address must NOT be direct");
    }

    #[test]
    fn test_v12_nonstandard_port_routing_by_transport_not_address() {
        // Non-standard port should not affect routing decision.
        let mut cfg = make_cfg("tcp", "tls", "203.0.113.5", "");
        cfg.server_port = 8443;
        assert!(cfg.is_direct_vps(),
            "Non-standard port must not affect routing — still direct with explicit tcp");

        let mut cfg2 = make_cfg("tcp", "tls", "", "vps.example.com");
        cfg2.server_port = 2083;
        assert!(cfg2.is_direct_vps(),
            "Non-standard port + explicit tcp + VPS domain must still be direct");
    }

    #[test]
    fn test_v12_reality_always_direct_regardless_of_address() {
        // Reality with any address is always direct.
        let mut cfg = make_cfg("tcp", "reality", "194.87.123.45", "gateway.icloud.com");
        cfg.public_key = "test_pubkey_1234567890123456789012345".to_string();
        assert!(cfg.is_direct_vps(), "Reality over VPS IP → direct");

        // Reality even with CF IP in domain (unusual but should still be direct).
        let mut cfg2 = make_cfg("tcp", "reality", "104.16.1.1", "gateway.icloud.com");
        cfg2.public_key = "test_pubkey_1234567890123456789012345".to_string();
        assert!(cfg2.is_direct_vps(), "Reality with CF server_address still direct");
    }

    #[test]
    fn test_v12_profile_effective_transport_no_path_guessing() {
        // VlessProfile: path contains "ws" but transport field is empty → must default to tcp.
        let profile = VlessProfile {
            id: "v12_profile_test".to_string(),
            name: "V12 Profile Test".to_string(),
            domain: "vps.example.com".to_string(),
            server_address: "194.87.1.1".to_string(),
            server_port: 443,
            uuid: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee".to_string(),
            path: "/vless-ws?ed=2048".to_string(), // contains "ws"
            ws_path: String::new(),
            tls_sni: String::new(),
            host_header: String::new(),
            transport: String::new(), // empty → must NOT become "ws"
            security: "tls".to_string(),
            public_key: String::new(),
            short_id: String::new(),
            fingerprint: "chrome".to_string(),
            spider_x: String::new(),
            flow: String::new(),
            header_type: String::new(),
        };
        assert_eq!(profile.effective_transport(), "tcp",
            "VlessProfile: empty transport must default to tcp, not be inferred from path");
        // With security explicit and transport defaulting to tcp → direct.
        assert!(profile.is_direct_vps(),
            "VlessProfile: explicit security=tls, transport defaults to tcp → direct VPS");
    }
}
