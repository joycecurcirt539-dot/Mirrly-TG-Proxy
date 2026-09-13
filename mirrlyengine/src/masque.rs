use crate::config::STATS;
use crate::{ldebug, lerror, linfo, lwarn};
use base64::Engine;
use once_cell::sync::Lazy;
use parking_lot::RwLock;
use rustls::RootCertStore;
use rustls_pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use smoltcp::iface::{Config as SmolConfig, Interface, SocketSet};
use smoltcp::phy::{Device, DeviceCapabilities, Medium, RxToken, TxToken};
use smoltcp::socket::tcp::{self, Socket as TcpSocket, State as TcpState};
use smoltcp::time::Instant as SmolInstant;
use smoltcp::wire::{
    HardwareAddress, IpAddress, IpCidr, IpEndpoint, Ipv4Address, Ipv4Cidr, Ipv6Address, Ipv6Cidr,
};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicI64, AtomicU32, AtomicU64, AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio_util::sync::CancellationToken;

// ---------------------------------------------------------------------------
// Uplink Mode
// 0 = Cloudflare Worker WSS (Default, proven)
// 1 = Cloudflare WARP MASQUE HTTP/3 (Beta, QUIC Anycast)
// 2 = Hybrid Failover (Worker first, fallback to WARP MASQUE on error/cooldown)
// 3 = VLESS over WebSocket (Beta, TLS 1.3 Anycast)
// 4 = WARP AmneziaWG (AWG) — обфусцированный WireGuard Anycast
// 5 = WARP Cascade — MASQUE -> AWG -> Worker WSS (двухканальный каскадный failover)
// ---------------------------------------------------------------------------
pub const UPLINK_WORKER: i32 = 0;
pub const UPLINK_MASQUE: i32 = 1;
pub const UPLINK_HYBRID: i32 = 2;
pub const UPLINK_VLESS: i32 = 3;
pub const UPLINK_AWG: i32 = 4;
pub const UPLINK_WARP_CASCADE: i32 = 5;

pub static UPLINK_MODE: AtomicI32 = AtomicI32::new(UPLINK_WORKER);

// MASQUE Anycast Circuit Breaker (предотвращение многократных задержек рукопожатий при блокировке Anycast UDP)
static MASQUE_COOLDOWN_UNTIL_MS: AtomicI64 = AtomicI64::new(0);

pub fn is_masque_on_cooldown() -> bool {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;
    now < MASQUE_COOLDOWN_UNTIL_MS.load(Ordering::Relaxed)
}

pub fn set_masque_cooldown(seconds: u64) {
    let until = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
        + (seconds as i64 * 1000);
    MASQUE_COOLDOWN_UNTIL_MS.store(until, Ordering::Relaxed);
}

pub fn reset_masque_cooldown() {
    MASQUE_COOLDOWN_UNTIL_MS.store(0, Ordering::Relaxed);
}

// ---------------------------------------------------------------------------
// Active Cascade Stage (0=None/Direct, 1=MASQUE HTTP/3, 2=AmneziaWG, 3=Worker WSS)
// ---------------------------------------------------------------------------
pub const CASCADE_STAGE_NONE: i32 = 0;
pub const CASCADE_STAGE_MASQUE: i32 = 1;
pub const CASCADE_STAGE_AWG: i32 = 2;
pub const CASCADE_STAGE_WORKER: i32 = 3;

pub static ACTIVE_CASCADE_STAGE: AtomicI32 = AtomicI32::new(CASCADE_STAGE_NONE);

pub fn set_active_cascade_stage(stage: i32) {
    ACTIVE_CASCADE_STAGE.store(stage, Ordering::Relaxed);
}

pub fn get_active_cascade_stage() -> i32 {
    ACTIVE_CASCADE_STAGE.load(Ordering::Relaxed)
}

pub fn reset_active_cascade_stage() {
    ACTIVE_CASCADE_STAGE.store(CASCADE_STAGE_NONE, Ordering::Relaxed);
}

// Cloudflare WARP MASQUE Configuration
pub const MASQUE_L4_SNI: &str = "consumer-masque-proxy.cloudflareclient.com";
pub const MASQUE_IP_SNI: &str = "consumer-masque.cloudflareclient.com";
pub const MASQUE_DEFAULT_URI_PATH: &str = "/.well-known/masque/ip/";
pub const MASQUE_CONNECT_IP_PROTO: &str = "connect-ip";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WarpMasqueConfig {
    pub endpoint: String,
    pub sni: String,
    pub auth_token: String,
    pub client_ipv4: String,
    pub client_ipv6: String,
    pub p256_priv_key_base64: String,
    pub client_cert_base64: String,
    pub peer_pub_key: String,
    pub uri_template: String,
}

impl WarpMasqueConfig {
    pub fn validate_and_normalize(&mut self) {
        self.endpoint = self.endpoint.trim().to_string();
        if self.endpoint.is_empty() {
            self.endpoint = "188.114.96.1:8095".to_string();
        }

        let trimmed_sni = self.sni.trim();
        if trimmed_sni.is_empty() || trimmed_sni == MASQUE_L4_SNI {
            self.sni = MASQUE_IP_SNI.to_string();
        } else {
            self.sni = trimmed_sni.to_string();
        }

        self.auth_token = self.auth_token.trim().to_string();
        self.client_ipv4 = self.client_ipv4.trim().to_string();
        self.client_ipv6 = self.client_ipv6.trim().to_string();
        self.p256_priv_key_base64 = self.p256_priv_key_base64.trim().to_string();
        self.client_cert_base64 = self.client_cert_base64.trim().to_string();
        self.peer_pub_key = self.peer_pub_key.trim().to_string();

        let trimmed_uri = self.uri_template.trim();
        if trimmed_uri.is_empty() || trimmed_uri == "/" {
            self.uri_template = MASQUE_DEFAULT_URI_PATH.to_string();
        } else {
            self.uri_template = trimmed_uri.to_string();
        }
    }
}

/// Supported server authentication schemes for MASQUE tunnels (TSK-M02).
/// API management tokens (Cloudflare REST API /reg) and Gateway credentials (mTLS / HTTP Bearer)
/// have separate scopes and lifecycles; their interchangeability is rejected.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MasqueAuthScheme {
    /// Pure mTLS: Transport-layer authentication using client certificate (P-256) and private key.
    /// Standard for Cloudflare Anycast WARP MASQUE.
    /// Pure mTLS does NOT send API management tokens in HTTP/3 CONNECT Authorization headers.
    Mtls,
    /// Gateway Bearer: Application-layer authentication using a dedicated Gateway Bearer token
    /// in the HTTP/3 `Authorization: Bearer <token>` header (e.g. RFC 9484 proxies or Zero Trust Gateway).
    GatewayBearer,
    /// Dual mTLS + Gateway Bearer: Both transport-level client certificate and application-level Gateway token.
    MtlsWithGatewayBearer,
    /// Anonymous / Unauthenticated: Standard TLS 1.3 without client credentials.
    Anonymous,
}

/// Strongly typed credentials for MASQUE tunnel establishment.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MasqueCredentials {
    pub scheme: MasqueAuthScheme,
    pub client_cert_der: Option<Vec<u8>>,
    pub client_key_der: Option<Vec<u8>>,
    /// Explicit Gateway credential for HTTP/3 Authorization header.
    /// Distinguishable from Cloudflare REST API management token.
    pub gateway_bearer_token: Option<String>,
    /// Cloudflare REST API management token (retained for account operations, never leaked to gateway).
    pub api_management_token: Option<String>,
}

impl MasqueCredentials {
    pub fn identity_hash(&self) -> [u8; 32] {
        use sha2::{Digest, Sha256};
        let mut hasher = Sha256::new();
        match self.scheme {
            MasqueAuthScheme::Mtls => hasher.update(b"scheme:mtls\n"),
            MasqueAuthScheme::GatewayBearer => hasher.update(b"scheme:bearer\n"),
            MasqueAuthScheme::MtlsWithGatewayBearer => hasher.update(b"scheme:mtls+bearer\n"),
            MasqueAuthScheme::Anonymous => hasher.update(b"scheme:anon\n"),
        }
        if let Some(ref cert) = self.client_cert_der {
            hasher.update(b"cert:");
            hasher.update(cert);
            hasher.update(b"\n");
        }
        if let Some(ref key) = self.client_key_der {
            hasher.update(b"key:");
            hasher.update(key);
            hasher.update(b"\n");
        }
        if let Some(ref token) = self.gateway_bearer_token {
            hasher.update(b"gw_token:");
            hasher.update(token.as_bytes());
            hasher.update(b"\n");
        }
        if let Some(ref mgmt) = self.api_management_token {
            hasher.update(b"mgmt_token:");
            hasher.update(mgmt.as_bytes());
            hasher.update(b"\n");
        }
        let res = hasher.finalize();
        let mut out = [0u8; 32];
        out.copy_from_slice(&res);
        out
    }
}

impl WarpMasqueConfig {
    pub fn resolve_credentials(&self) -> MasqueCredentials {
        let cert_der = if !self.client_cert_base64.trim().is_empty() {
            base64::engine::general_purpose::STANDARD
                .decode(self.client_cert_base64.trim())
                .ok()
        } else {
            None
        };
        let key_der = if !self.p256_priv_key_base64.trim().is_empty() {
            base64::engine::general_purpose::STANDARD
                .decode(self.p256_priv_key_base64.trim())
                .ok()
        } else {
            None
        };

        let has_mtls = cert_der.is_some() && key_der.is_some();
        let raw_token = self.auth_token.trim();

        // Explicit gateway token prefixes for Zero Trust or custom proxy auth
        let (is_explicit_gateway, clean_token) = if let Some(t) = raw_token.strip_prefix("gateway:") {
            (true, t.trim())
        } else if let Some(t) = raw_token.strip_prefix("bearer:") {
            (true, t.trim())
        } else {
            (false, raw_token)
        };

        if has_mtls {
            if is_explicit_gateway && !clean_token.is_empty() {
                MasqueCredentials {
                    scheme: MasqueAuthScheme::MtlsWithGatewayBearer,
                    client_cert_der: cert_der,
                    client_key_der: key_der,
                    gateway_bearer_token: Some(clean_token.to_string()),
                    api_management_token: None,
                }
            } else {
                // Pure mTLS: Transport-level client cert.
                // The API token is a management token (from /reg), NOT a gateway credential.
                // Do NOT send it in HTTP/3 Authorization headers!
                MasqueCredentials {
                    scheme: MasqueAuthScheme::Mtls,
                    client_cert_der: cert_der,
                    client_key_der: key_der,
                    gateway_bearer_token: None,
                    api_management_token: if !raw_token.is_empty() {
                        Some(raw_token.to_string())
                    } else {
                        None
                    },
                }
            }
        } else if !clean_token.is_empty() {
            MasqueCredentials {
                scheme: MasqueAuthScheme::GatewayBearer,
                client_cert_der: None,
                client_key_der: None,
                gateway_bearer_token: Some(clean_token.to_string()),
                api_management_token: None,
            }
        } else {
            MasqueCredentials {
                scheme: MasqueAuthScheme::Anonymous,
                client_cert_der: None,
                client_key_der: None,
                gateway_bearer_token: None,
                api_management_token: None,
            }
        }
    }
}

pub static WARP_CONFIG: Lazy<RwLock<WarpMasqueConfig>> = Lazy::new(|| {
    RwLock::new(WarpMasqueConfig {
        endpoint: "188.114.96.1:8095".to_string(),
        sni: MASQUE_IP_SNI.to_string(),
        auth_token: String::new(),
        client_ipv4: "172.16.0.2".to_string(),
        client_ipv6: String::new(),
        p256_priv_key_base64: String::new(),
        client_cert_base64: String::new(),
        peer_pub_key: String::new(),
        uri_template: MASQUE_DEFAULT_URI_PATH.to_string(),
    })
});

// Cloudflare Anycast MASQUE endpoints pool for fast failover and round-robin rotation.
// Addresses operate via Anycast BGP routing; geographic PoP location and network capabilities
// are established dynamically through protocol handshakes, not assumed statically.
pub static CF_MASQUE_ENDPOINTS: &[&str] = &[
    "188.114.96.1:8095",
    "188.114.97.1:8443",
    "188.114.96.2:500",
    "188.114.97.2:1701",
    "188.114.96.3:854",
    "188.114.97.3:859",
    "188.114.96.5:864",
    "188.114.97.5:878",
    "188.114.96.10:880",
    "188.114.97.10:890",
    "188.114.96.1:891",
    "188.114.97.1:894",
    "188.114.96.2:903",
    "188.114.97.2:908",
    "188.114.96.3:928",
    "188.114.97.3:934",
    "188.114.96.5:939",
    "188.114.97.5:942",
    "188.114.96.10:943",
    "188.114.97.10:945",
    "162.159.192.1:8095",
    "162.159.193.1:8443",
    "162.159.192.2:500",
    "162.159.193.2:1701",
    "162.159.192.5:946",
    "162.159.193.5:955",
    "162.159.195.1:968",
    "162.159.195.2:987",
    "162.159.204.1:988",
    "162.159.204.2:1002",
    "162.159.192.1:1010",
    "162.159.193.1:1014",
    "162.159.195.1:1070",
    "162.159.195.2:1074",
    "162.159.204.1:1194",
    "162.159.204.2:8981",
    "162.159.192.2:4500",
    "162.159.193.2:2408",
    "162.159.195.1:4443",
    "162.159.195.2:443",
    "188.114.96.1:4443",
    "188.114.97.1:443",
    "188.114.96.2:8095",
    "188.114.97.2:8443",
    // Cloudflare IPv6 Anycast Nodes (Bypass IPv4 TSPU filters)
    "[2606:4700:d0::a29f:c001]:8095",
    "[2606:4700:d0::a29f:c101]:8443",
    "[2606:4700:110::a29f:c001]:500",
    "[2606:4700:d0::a29f:c001]:1701",
    "[2606:4700:d0::a29f:c101]:854",
    "[2606:4700:110::a29f:c001]:443",
];

// TSK-M05: Атомарный курсор циклической ротации пула Anycast-эндпоинтов
static CF_POOL_CURSOR: AtomicUsize = AtomicUsize::new(0);

pub fn get_cf_pool_cursor() -> usize {
    CF_POOL_CURSOR.load(Ordering::Relaxed)
}

pub fn reset_cf_pool_cursor() {
    CF_POOL_CURSOR.store(0, Ordering::Relaxed);
}

pub fn advance_cf_pool_cursor(step: usize) -> usize {
    CF_POOL_CURSOR.fetch_add(step, Ordering::Relaxed)
}

/// Выбор списка кандидатов для подключения с сохранением пользовательского порта
/// и честной циклической ротацией по всему пулу CF_MASQUE_ENDPOINTS.
pub fn select_rotated_candidate_endpoints(
    configured_ep: &str,
    sticky_ep: Option<&str>,
    max_candidates: usize,
) -> Vec<String> {
    let mut candidates = Vec::with_capacity(max_candidates + 2);
    if let Some(sticky) = sticky_ep {
        let trimmed = sticky.trim();
        if !trimmed.is_empty() {
            candidates.push(trimmed.to_string());
        }
    }
    let conf = configured_ep.trim();
    if !conf.is_empty() && !candidates.iter().any(|c| c == conf) {
        candidates.push(conf.to_string());
    }

    let pool_len = CF_MASQUE_ENDPOINTS.len();
    if pool_len == 0 {
        return candidates;
    }
    let start_cursor = CF_POOL_CURSOR.fetch_add(max_candidates, Ordering::Relaxed) % pool_len;
    for i in 0..pool_len {
        let idx = (start_cursor + i) % pool_len;
        let ep = CF_MASQUE_ENDPOINTS[idx];
        if !candidates.iter().any(|c| c == ep) {
            candidates.push(ep.to_string());
            if candidates.len() >= max_candidates {
                break;
            }
        }
    }
    candidates
}

// Sticky Profile: закрепление стабильного Anycast-узла для предотвращения сброса MTProto-сессий Telegram
static STICKY_ENDPOINT: Lazy<parking_lot::RwLock<Option<String>>> =
    Lazy::new(|| parking_lot::RwLock::new(None));
static STICKY_CONSECUTIVE_TIMEOUTS: AtomicU32 = AtomicU32::new(0);

pub fn get_sticky_endpoint() -> Option<String> {
    STICKY_ENDPOINT.read().clone()
}

pub fn set_sticky_endpoint(endpoint: &str) {
    let ep = endpoint.trim();
    if !ep.is_empty() {
        let mut w = STICKY_ENDPOINT.write();
        *w = Some(ep.to_string());
        STICKY_CONSECUTIVE_TIMEOUTS.store(0, Ordering::Relaxed);
        linfo!(
            "MASQUE: Sticky Profile set to {} (consecutive timeouts reset)",
            ep
        );
    }
}

pub fn clear_sticky_endpoint() {
    let mut w = STICKY_ENDPOINT.write();
    *w = None;
    STICKY_CONSECUTIVE_TIMEOUTS.store(0, Ordering::Relaxed);
    ldebug!("MASQUE: Sticky Profile cleared");
}

pub fn record_sticky_success(endpoint: &str) {
    let ep = endpoint.trim();
    if !ep.is_empty() {
        let mut w = STICKY_ENDPOINT.write();
        *w = Some(ep.to_string());
        STICKY_CONSECUTIVE_TIMEOUTS.store(0, Ordering::Relaxed);
        ldebug!("MASQUE: Sticky Profile confirmed success: {}", ep);
    }
}

pub fn record_sticky_timeout() -> bool {
    let prev = STICKY_CONSECUTIVE_TIMEOUTS.fetch_add(1, Ordering::Relaxed);
    let count = prev + 1;
    if count >= 3 {
        let mut w = STICKY_ENDPOINT.write();
        let old = w.take();
        STICKY_CONSECUTIVE_TIMEOUTS.store(0, Ordering::Relaxed);
        lwarn!(
            "MASQUE: Sticky profile {:?} invalidated after 3 consecutive timeouts!",
            old
        );
        true
    } else {
        ldebug!("MASQUE: Sticky profile timeout recorded ({}/3)", count);
        false
    }
}

pub fn get_uplink_mode() -> i32 {
    UPLINK_MODE.load(Ordering::Relaxed)
}

pub fn set_uplink_mode(mode: i32) {
    UPLINK_MODE.store(mode, Ordering::Relaxed);
    linfo!(
        "Uplink mode updated: {}",
        match mode {
            UPLINK_MASQUE => "WARP MASQUE (HTTP/3 Beta)",
            UPLINK_HYBRID => "Hybrid Failover (Worker + WARP)",
            UPLINK_VLESS => "VLESS over WebSocket (TLS 1.3 Beta)",
            UPLINK_AWG => "WARP AmneziaWG (AWG)",
            UPLINK_WARP_CASCADE => "WARP Cascade (MASQUE -> AWG)",
            _ => "Cloudflare Worker (WSS)",
        }
    );
}

pub static WARP_CONFIG_GENERATION: AtomicU64 = AtomicU64::new(1);

#[inline]
pub fn get_warp_config_generation() -> u64 {
    WARP_CONFIG_GENERATION.load(Ordering::SeqCst)
}

#[inline]
pub fn increment_warp_config_generation() -> u64 {
    WARP_CONFIG_GENERATION.fetch_add(1, Ordering::SeqCst) + 1
}

pub fn set_warp_config(
    endpoint: &str,
    sni: &str,
    auth_token: &str,
    client_ipv4: &str,
    client_ipv6: &str,
) {
    {
        let mut cfg = WARP_CONFIG.write();
        let trimmed_ep = endpoint.trim();
        if !trimmed_ep.is_empty() {
            cfg.endpoint = trimmed_ep.to_string();
        } else {
            cfg.endpoint = "188.114.96.1:8095".to_string();
        }
        let trimmed_sni = sni.trim();
        if !trimmed_sni.is_empty() {
            if trimmed_sni == MASQUE_L4_SNI {
                cfg.sni = MASQUE_IP_SNI.to_string();
            } else {
                cfg.sni = trimmed_sni.to_string();
            }
        } else {
            cfg.sni = MASQUE_IP_SNI.to_string();
        }
        cfg.auth_token = auth_token.trim().to_string();
        // Clear or update IP fields explicitly (empty value cleans past profile)
        cfg.client_ipv4 = client_ipv4.trim().to_string();
        cfg.client_ipv6 = client_ipv6.trim().to_string();

        linfo!(
            "WARP MASQUE config updated: endpoint={}, sni={}, token_len={}, ipv4={}, ipv6={}",
            cfg.endpoint,
            cfg.sni,
            cfg.auth_token.len(),
            cfg.client_ipv4,
            cfg.client_ipv6
        );
    }
    reset_quic_endpoint();
}

pub fn set_warp_uri_template(uri_template: &str) {
    {
        let mut cfg = WARP_CONFIG.write();
        let trimmed = uri_template.trim();
        if !trimmed.is_empty() && trimmed != "/" {
            cfg.uri_template = trimmed.to_string();
        } else {
            cfg.uri_template = MASQUE_DEFAULT_URI_PATH.to_string();
        }
        linfo!(
            "WARP MASQUE URI template updated: {}",
            cfg.uri_template
        );
    }
    reset_quic_endpoint();
}

pub fn set_warp_crypto(p256_priv_key_base64: &str, client_cert_base64: &str, peer_pub_key: &str) {
    {
        let mut cfg = WARP_CONFIG.write();
        cfg.p256_priv_key_base64 = p256_priv_key_base64.trim().to_string();
        cfg.client_cert_base64 = client_cert_base64.trim().to_string();
        cfg.peer_pub_key = peer_pub_key.trim().to_string();
        linfo!(
            "WARP MASQUE crypto updated: priv_key_len={}, cert_len={}, peer_pub_len={}",
            cfg.p256_priv_key_base64.len(),
            cfg.client_cert_base64.len(),
            cfg.peer_pub_key.len()
        );
    }
    reset_quic_endpoint();
}

pub fn clear_warp_crypto() {
    set_warp_crypto("", "", "");
}

/// Unified atomic configuration snapshot setter with validation and generation increment (M13).
pub fn set_warp_full_config(
    endpoint: &str,
    sni: &str,
    auth_token: &str,
    client_ipv4: &str,
    client_ipv6: &str,
    p256_priv_key_base64: &str,
    client_cert_base64: &str,
    peer_pub_key: &str,
    uri_template: &str,
) {
    let mut new_cfg = WarpMasqueConfig {
        endpoint: endpoint.to_string(),
        sni: sni.to_string(),
        auth_token: auth_token.to_string(),
        client_ipv4: client_ipv4.to_string(),
        client_ipv6: client_ipv6.to_string(),
        p256_priv_key_base64: p256_priv_key_base64.to_string(),
        client_cert_base64: client_cert_base64.to_string(),
        peer_pub_key: peer_pub_key.to_string(),
        uri_template: uri_template.to_string(),
    };
    new_cfg.validate_and_normalize();

    {
        let mut cfg = WARP_CONFIG.write();
        *cfg = new_cfg;
        linfo!(
            "WARP MASQUE full config snapshot applied: endpoint={}, sni={}, ipv4={}, ipv6={}, cert_len={}",
            cfg.endpoint,
            cfg.sni,
            cfg.client_ipv4,
            cfg.client_ipv6,
            cfg.client_cert_base64.len()
        );
    }
    reset_quic_endpoint();
}

pub fn get_warp_config_snapshot() -> WarpMasqueConfig {
    WARP_CONFIG.read().clone()
}

// ---------------------------------------------------------------------------
// Strongly Typed Authentication & Authorization Verification Engine (TSK-M02)
// ---------------------------------------------------------------------------

/// Strongly typed authentication and authorization failures for MASQUE.
/// Fatal auth failures must immediately abort dial failover without masking.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MasqueAuthError {
    /// Server rejected client certificate as untrusted CA (TLS alert 48 unknown_ca, 46 certificate_unknown)
    UntrustedCertificate(String),
    /// Server rejected client certificate because it was revoked (TLS alert 44 certificate_revoked)
    CertificateRevoked(String),
    /// Server rejected client certificate because it has expired (TLS alert 45 certificate_expired)
    CertificateExpired(String),
    /// Bad or corrupt client certificate (TLS alert 42 bad_certificate)
    BadCertificate(String),
    /// Server requires client certificate authentication (TLS alert 116 certificate_required)
    CertificateRequired(String),
    /// Key mismatch or unsupported certificate type (TLS alert 43 unsupported_certificate)
    KeyMismatch(String),
    /// HTTP 401 Unauthorized (missing or invalid gateway bearer token)
    Unauthorized(String),
    /// HTTP 403 Forbidden (revoked account, expired identity, unauthorized device)
    Forbidden(String),
    /// HTTP 407 Proxy Authentication Required
    ProxyAuthRequired(String),
}

impl std::fmt::Display for MasqueAuthError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::UntrustedCertificate(msg) => write!(f, "Untrusted certificate: {}", msg),
            Self::CertificateRevoked(msg) => write!(f, "Certificate revoked: {}", msg),
            Self::CertificateExpired(msg) => write!(f, "Certificate expired: {}", msg),
            Self::BadCertificate(msg) => write!(f, "Bad certificate: {}", msg),
            Self::CertificateRequired(msg) => write!(f, "Certificate required: {}", msg),
            Self::KeyMismatch(msg) => write!(f, "Key mismatch: {}", msg),
            Self::Unauthorized(msg) => write!(f, "Unauthorized (HTTP 401): {}", msg),
            Self::Forbidden(msg) => write!(f, "Forbidden (HTTP 403): {}", msg),
            Self::ProxyAuthRequired(msg) => write!(f, "Proxy auth required (HTTP 407): {}", msg),
        }
    }
}

impl std::error::Error for MasqueAuthError {}

pub fn classify_tls_auth_error(err_str: &str) -> Option<MasqueAuthError> {
    let l = err_str.to_ascii_lowercase();
    if l.contains("certificate_revoked") || l.contains("0x12c") {
        Some(MasqueAuthError::CertificateRevoked(err_str.to_string()))
    } else if l.contains("certificate_expired") || l.contains("0x12d") {
        Some(MasqueAuthError::CertificateExpired(err_str.to_string()))
    } else if l.contains("unknown_ca") || l.contains("0x130") || l.contains("certificate_unknown") || l.contains("0x12e") {
        Some(MasqueAuthError::UntrustedCertificate(err_str.to_string()))
    } else if l.contains("certificate_required") || l.contains("0x174") || l.contains("certificaterequired") {
        Some(MasqueAuthError::CertificateRequired(err_str.to_string()))
    } else if l.contains("unsupported_certificate") || l.contains("0x12b") || l.contains("key mismatch") {
        Some(MasqueAuthError::KeyMismatch(err_str.to_string()))
    } else if l.contains("bad_certificate")
        || l.contains("bad certificate")
        || l.contains("0x12a")
        || l.contains("crypto error 298")
        || l.contains("crypto error 299")
        || l.contains("crypto error 302")
        || l.contains("crypto error 304")
    {
        Some(MasqueAuthError::BadCertificate(err_str.to_string()))
    } else {
        None
    }
}

pub fn classify_http_auth_error(status: u16, msg: &str) -> Option<MasqueAuthError> {
    match status {
        401 => Some(MasqueAuthError::Unauthorized(format!("HTTP 401 Unauthorized: {}", msg))),
        403 => Some(MasqueAuthError::Forbidden(format!("HTTP 403 Forbidden: {}", msg))),
        407 => Some(MasqueAuthError::ProxyAuthRequired(format!("HTTP 407 Proxy Authentication Required: {}", msg))),
        _ => None,
    }
}

static LAST_AUTH_ERROR: Lazy<parking_lot::RwLock<Option<MasqueAuthError>>> =
    Lazy::new(|| parking_lot::RwLock::new(None));

pub fn get_last_masque_auth_error() -> Option<MasqueAuthError> {
    LAST_AUTH_ERROR.read().clone()
}

pub fn reset_masque_auth_error() {
    let mut w = LAST_AUTH_ERROR.write();
    *w = None;
}

pub fn record_masque_auth_failure(err: MasqueAuthError) {
    lerror!("MASQUE FATAL AUTHENTICATION FAILURE: {}", err);
    let mut w = LAST_AUTH_ERROR.write();
    *w = Some(err);
    // Set a cooldown so we do not spam Cloudflare Anycast with an invalid identity
    set_masque_cooldown(120);
}

pub fn is_mtls_rejected() -> bool {
    matches!(
        get_last_masque_auth_error(),
        Some(
            MasqueAuthError::UntrustedCertificate(_)
                | MasqueAuthError::CertificateRevoked(_)
                | MasqueAuthError::CertificateExpired(_)
                | MasqueAuthError::BadCertificate(_)
                | MasqueAuthError::CertificateRequired(_)
                | MasqueAuthError::KeyMismatch(_)
        )
    )
}

pub fn set_mtls_rejected(rejected: bool) {
    if rejected {
        record_masque_auth_failure(MasqueAuthError::BadCertificate("mTLS rejected".to_string()));
    } else {
        reset_masque_auth_error();
    }
}

pub fn is_cert_rejection_error(err: &str) -> bool {
    classify_tls_auth_error(err).is_some()
}

pub fn create_quic_client_config(
    client_cert_der: Option<Vec<u8>>,
    client_key_der: Option<Vec<u8>>,
) -> Result<quinn::ClientConfig, String> {
    let mut root_store = RootCertStore::empty();
    root_store.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

    let crypto_builder = rustls::ClientConfig::builder().with_root_certificates(root_store);

    let mut client_crypto =
        if let (Some(cert_bytes), Some(key_bytes)) = (client_cert_der, client_key_der) {
            let cert = CertificateDer::from(cert_bytes);
            let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key_bytes));
            crypto_builder
                .with_client_auth_cert(vec![cert], key)
                .map_err(|e| format!("quic mtls error: {}", e))?
        } else {
            crypto_builder.with_no_client_auth()
        };
    client_crypto.alpn_protocols = vec![b"h3".to_vec()];

    let mut client_config = quinn::ClientConfig::new(Arc::new(
        quinn::crypto::rustls::QuicClientConfig::try_from(client_crypto)
            .map_err(|e| format!("quic crypto config error: {}", e))?,
    ));

    let mut transport = quinn::TransportConfig::default();
    transport.max_idle_timeout(Some(Duration::from_secs(60).try_into().unwrap()));
    transport.keep_alive_interval(Some(Duration::from_secs(15)));
    transport.max_concurrent_uni_streams(64u32.into());
    transport.datagram_receive_buffer_size(Some(1024 * 1024));
    transport.datagram_send_buffer_size(1024 * 1024);

    client_config.transport_config(Arc::new(transport));

    Ok(client_config)
}

pub fn create_standard_quic_client_config() -> Result<quinn::ClientConfig, String> {
    create_quic_client_config(None, None)
}

pub const MASQUE_DATAGRAM_QUEUE_CAPACITY: usize = 1024;
pub const MASQUE_CAPSULE_QUEUE_CAPACITY: usize = 64;
pub const MAX_TUN_RX_QUEUE: usize = 1024;

pub static DATAGRAM_DROPPED_OVERFLOW: AtomicU64 = AtomicU64::new(0);

#[inline]
pub fn get_datagram_dropped_overflow_count() -> u64 {
    DATAGRAM_DROPPED_OVERFLOW.load(Ordering::Relaxed)
}

#[inline]
pub fn reset_datagram_dropped_overflow_count() {
    DATAGRAM_DROPPED_OVERFLOW.store(0, Ordering::Relaxed);
}

pub type DatagramSender = tokio::sync::mpsc::Sender<bytes::Bytes>;
pub type DatagramDispatcher = Arc<parking_lot::RwLock<HashMap<u64, DatagramSender>>>;

pub fn create_datagram_dispatcher_with_cancel(
    conn: quinn::Connection,
    cancel_token: CancellationToken,
) -> (DatagramDispatcher, tokio::task::JoinHandle<()>) {
    let dispatcher: DatagramDispatcher = Arc::new(parking_lot::RwLock::new(HashMap::new()));
    let disp_clone = dispatcher.clone();
    let conn_clone = conn.clone();

    let handle = tokio::spawn(async move {
        loop {
            tokio::select! {
                _ = cancel_token.cancelled() => {
                    ldebug!("MASQUE: dispatcher task cancelled");
                    break;
                }
                dgram_res = conn_clone.read_datagram() => {
                    match dgram_res {
                        Ok(dgram) => {
                            if let Some((qid, ctx, header_len)) = decode_h3_datagram_offset(&dgram) {
                                if ctx == 0 {
                                    let payload = dgram.slice(header_len..);
                                    let mut dead_qid = None;
                                    {
                                        let guard = disp_clone.read();
                                        if let Some(tx) = guard.get(&qid) {
                                            match tx.try_send(payload) {
                                                Ok(_) => {}
                                                Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => {
                                                    DATAGRAM_DROPPED_OVERFLOW.fetch_add(1, Ordering::Relaxed);
                                                    ldebug!(
                                                        "MASQUE: datagram queue full for qid {}, dropping packet (overflow policy)",
                                                        qid
                                                    );
                                                }
                                                Err(tokio::sync::mpsc::error::TrySendError::Closed(_)) => {
                                                    dead_qid = Some(qid);
                                                }
                                            }
                                        }
                                    }
                                    if let Some(dead) = dead_qid {
                                        disp_clone.write().remove(&dead);
                                    }
                                }
                            }
                        }
                        Err(e) => {
                            ldebug!("MASQUE: connection datagram loop terminated: {}", e);
                            break;
                        }
                    }
                }
            }
        }
        disp_clone.write().clear();
    });

    (dispatcher, handle)
}

pub fn create_datagram_dispatcher(conn: quinn::Connection) -> DatagramDispatcher {
    create_datagram_dispatcher_with_cancel(conn, CancellationToken::new()).0
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MasqueConnectVariant {
    /// RFC 9484 Extended CONNECT with `:protocol = connect-ip`
    Rfc9484ConnectIp {
        uri_template: Option<String>,
    },
    /// Legacy Cloudflare Extended CONNECT with `:protocol = cf-connect-ip`
    LegacyCfConnectIp {
        uri_template: Option<String>,
    },
    /// Standard RFC 9114 HTTP/3 CONNECT for Layer 4 TCP proxying (usque l4-socks mode)
    StandardRfc9114L4,
}

impl MasqueConnectVariant {
    #[inline]
    pub fn protocol_header(&self) -> Option<&'static str> {
        match self {
            Self::Rfc9484ConnectIp { .. } => Some(MASQUE_CONNECT_IP_PROTO),
            Self::LegacyCfConnectIp { .. } => Some("cf-connect-ip"),
            Self::StandardRfc9114L4 => None,
        }
    }

    #[inline]
    pub fn uri_path<'a>(&'a self, fallback: Option<&'a str>) -> Option<&'a str> {
        match self {
            Self::Rfc9484ConnectIp { uri_template } => uri_template.as_deref().or(fallback),
            Self::LegacyCfConnectIp { uri_template } => uri_template.as_deref().or(fallback),
            Self::StandardRfc9114L4 => None,
        }
    }

    #[inline]
    pub fn is_raw_l4(&self) -> bool {
        matches!(self, Self::StandardRfc9114L4)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum QuicAddressFamily {
    Ipv4,
    Ipv6,
}

impl QuicAddressFamily {
    #[inline]
    pub fn from_socket_addr(addr: &SocketAddr) -> Self {
        match addr {
            SocketAddr::V4(_) => Self::Ipv4,
            SocketAddr::V6(_) => Self::Ipv6,
        }
    }

    #[inline]
    pub fn is_ipv4(&self) -> bool {
        matches!(self, Self::Ipv4)
    }

    #[inline]
    pub fn is_ipv6(&self) -> bool {
        matches!(self, Self::Ipv6)
    }

    #[inline]
    pub fn default_bind_addr(&self) -> SocketAddr {
        match self {
            Self::Ipv4 => SocketAddr::new(std::net::IpAddr::V4(std::net::Ipv4Addr::UNSPECIFIED), 0),
            Self::Ipv6 => SocketAddr::new(std::net::IpAddr::V6(std::net::Ipv6Addr::UNSPECIFIED), 0),
        }
    }

    #[inline]
    pub fn domain(&self) -> socket2::Domain {
        match self {
            Self::Ipv4 => socket2::Domain::IPV4,
            Self::Ipv6 => socket2::Domain::IPV6,
        }
    }

    #[inline]
    pub fn is_compatible_with(&self, addr: &SocketAddr) -> bool {
        match (self, addr) {
            (Self::Ipv4, SocketAddr::V4(_)) => true,
            (Self::Ipv6, SocketAddr::V6(_)) => true,
            _ => false,
        }
    }
}

static UNDERLYING_NETWORK_GENERATION: AtomicU64 = AtomicU64::new(1);

#[inline]
pub fn get_underlying_network_generation() -> u64 {
    UNDERLYING_NETWORK_GENERATION.load(Ordering::Relaxed)
}

#[inline]
pub fn increment_underlying_network_generation() -> u64 {
    UNDERLYING_NETWORK_GENERATION.fetch_add(1, Ordering::SeqCst) + 1
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MasqueSessionKey {
    pub sni: String,
    pub endpoint_addr: SocketAddr,
    pub address_family: QuicAddressFamily,
    pub identity_hash: [u8; 32],
    pub config_generation: u64,
    pub network_generation: u64,
    pub variant: MasqueConnectVariant,
}

impl MasqueSessionKey {
    #[inline]
    pub fn is_valid_for(
        &self,
        expected_sni: &str,
        expected_generation: u64,
        expected_network_generation: u64,
        expected_identity_hash: &[u8; 32],
        expected_family: QuicAddressFamily,
    ) -> bool {
        self.sni == expected_sni
            && self.config_generation == expected_generation
            && self.network_generation == expected_network_generation
            && &self.identity_hash == expected_identity_hash
            && self.address_family == expected_family
            && self.address_family.is_compatible_with(&self.endpoint_addr)
    }
}

#[derive(Clone)]
pub struct ActiveQuicSession {
    pub key: MasqueSessionKey,
    pub conn: quinn::Connection,
    pub dispatcher: DatagramDispatcher,
    pub control_stream: Arc<tokio::sync::Mutex<quinn::SendStream>>,
    pub server_settings: Http3ServerSettings,
    pub goaway_received: Arc<AtomicBool>,
    pub cancel_token: CancellationToken,
    pub session_tasks: Arc<parking_lot::Mutex<Vec<tokio::task::JoinHandle<()>>>>,
}

impl ActiveQuicSession {
    pub fn new(
        key: MasqueSessionKey,
        conn: quinn::Connection,
        dispatcher: DatagramDispatcher,
        control_stream: Arc<tokio::sync::Mutex<quinn::SendStream>>,
        server_settings: Http3ServerSettings,
        goaway_received: Arc<AtomicBool>,
        cancel_token: CancellationToken,
        session_tasks: Vec<tokio::task::JoinHandle<()>>,
    ) -> Self {
        Self {
            key,
            conn,
            dispatcher,
            control_stream,
            server_settings,
            goaway_received,
            cancel_token,
            session_tasks: Arc::new(parking_lot::Mutex::new(session_tasks)),
        }
    }

    /// Gracefully cancels, closes the QUIC connection, and aborts/drains all associated background tasks.
    pub async fn shutdown(&self, reason: &[u8]) {
        self.abort_sync(reason);
    }

    /// Synchronously cancels, closes the QUIC connection, and aborts all associated background tasks.
    pub fn abort_sync(&self, reason: &[u8]) {
        self.cancel_token.cancel();
        self.dispatcher.write().clear();
        self.conn.close(0u32.into(), reason);
        let mut tasks = self.session_tasks.lock();
        for handle in tasks.drain(..) {
            handle.abort();
        }
    }
}

pub static ACTIVE_QUIC_SESSIONS: Lazy<parking_lot::Mutex<HashMap<QuicAddressFamily, ActiveQuicSession>>> =
    Lazy::new(|| parking_lot::Mutex::new(HashMap::new()));

pub static DIAL_LOCK_V4: Lazy<tokio::sync::Mutex<()>> = Lazy::new(|| tokio::sync::Mutex::new(()));
pub static DIAL_LOCK_V6: Lazy<tokio::sync::Mutex<()>> = Lazy::new(|| tokio::sync::Mutex::new(()));

pub fn get_dial_lock_for_family(family: QuicAddressFamily) -> &'static tokio::sync::Mutex<()> {
    match family {
        QuicAddressFamily::Ipv4 => &DIAL_LOCK_V4,
        QuicAddressFamily::Ipv6 => &DIAL_LOCK_V6,
    }
}

pub fn get_active_quic_session(family: QuicAddressFamily) -> Option<ActiveQuicSession> {
    ACTIVE_QUIC_SESSIONS.lock().get(&family).cloned()
}

pub fn set_active_quic_session(session: ActiveQuicSession) {
    replace_active_quic_session(session);
}

pub fn replace_active_quic_session(session: ActiveQuicSession) {
    let family = session.key.address_family;
    let cur_cfg_gen = get_warp_config_generation();
    let cur_net_gen = get_underlying_network_generation();

    // Guard against stale dials completing after configuration or network changed (M13)
    if session.key.config_generation != cur_cfg_gen || session.key.network_generation != cur_net_gen {
        ldebug!(
            "MASQUE: rejecting stale session dial for {:?}: session cfg_gen={} vs cur={}, session net_gen={} vs cur={}",
            family,
            session.key.config_generation,
            cur_cfg_gen,
            session.key.network_generation,
            cur_net_gen
        );
        session.abort_sync(b"stale dial generation");
        return;
    }

    let old_opt = {
        let mut guard = ACTIVE_QUIC_SESSIONS.lock();
        if session.key.config_generation != get_warp_config_generation()
            || session.key.network_generation != get_underlying_network_generation()
        {
            drop(guard);
            session.abort_sync(b"stale dial generation");
            return;
        }
        guard.insert(family, session)
    };
    if let Some(old) = old_opt {
        ldebug!(
            "MASQUE: replacing active session for {:?}, draining old tasks and connection",
            family
        );
        old.abort_sync(b"session replaced");
    }
}

pub fn clear_active_quic_sessions() {
    let mut guard = ACTIVE_QUIC_SESSIONS.lock();
    for (_family, session) in guard.drain() {
        session.abort_sync(b"endpoint reset");
    }
}

pub fn remove_active_quic_session_by_conn(conn_id: usize) {
    let mut guard = ACTIVE_QUIC_SESSIONS.lock();
    let mut to_abort = Vec::new();
    guard.retain(|_, active| {
        if active.conn.stable_id() == conn_id {
            to_abort.push(active.clone());
            false
        } else {
            true
        }
    });
    drop(guard);
    for session in to_abort {
        session.abort_sync(b"connection removed");
    }
}

#[derive(Default)]
pub struct QuicEndpointsCache {
    pub v4: Option<quinn::Endpoint>,
    pub v6: Option<quinn::Endpoint>,
}

pub static QUIC_ENDPOINTS: Lazy<parking_lot::Mutex<QuicEndpointsCache>> =
    Lazy::new(|| parking_lot::Mutex::new(QuicEndpointsCache::default()));

pub fn reset_quic_endpoint() {
    increment_warp_config_generation();
    increment_underlying_network_generation();
    reset_masque_auth_error();
    clear_sticky_endpoint();
    clear_active_quic_sessions();
    {
        let mut guard = QUIC_ENDPOINTS.lock();
        if let Some(ep) = guard.v4.take() {
            ep.close(0u32.into(), b"endpoint reset");
        }
        if let Some(ep) = guard.v6.take() {
            ep.close(0u32.into(), b"endpoint reset");
        }
        ldebug!("QUIC endpoints reset (v4 and v6)");
    }
    reset_masque_cooldown();
}

pub fn get_or_create_quic_endpoint_for_family(
    family: QuicAddressFamily,
) -> Result<quinn::Endpoint, String> {
    let mut guard = QUIC_ENDPOINTS.lock();
    let slot = match family {
        QuicAddressFamily::Ipv4 => &mut guard.v4,
        QuicAddressFamily::Ipv6 => &mut guard.v6,
    };
    if let Some(ref ep) = *slot {
        return Ok(ep.clone());
    }

    let bind_addr = family.default_bind_addr();
    let mut endpoint = quinn::Endpoint::client(bind_addr)
        .map_err(|e| format!("failed to bind quic {:?} endpoint to {}: {}", family, bind_addr, e))?;

    // Android UDP buffer optimization: ensure 2MB receive buffer and 1MB send buffer to prevent RX packet drops
    if let Ok(socket) = socket2::Socket::new(
        family.domain(),
        socket2::Type::DGRAM,
        Some(socket2::Protocol::UDP),
    ) {
        if family == QuicAddressFamily::Ipv6 {
            let _ = socket.set_only_v6(true);
        }
        let _ = socket.set_recv_buffer_size(2 * 1024 * 1024);
        let _ = socket.set_send_buffer_size(1024 * 1024);
        let _ = socket.set_nonblocking(true);
        if socket.bind(&bind_addr.into()).is_ok() {
            let std_socket: std::net::UdpSocket = socket.into();
            let _ = endpoint.rebind(std_socket);
        }
    }

    let default_cfg = create_standard_quic_client_config()?;
    endpoint.set_default_client_config(default_cfg);

    *slot = Some(endpoint.clone());
    Ok(endpoint)
}

#[inline]
pub fn get_or_create_quic_endpoint() -> Result<quinn::Endpoint, String> {
    get_or_create_quic_endpoint_for_family(QuicAddressFamily::Ipv4)
}

// ---------------------------------------------------------------------------
// HTTP/3 (RFC 9114) & QPACK (RFC 9204) Binary Framing for MASQUE (RFC 9484 / RFC 9298)
// ---------------------------------------------------------------------------

pub const H3_FRAME_DATA: u64 = 0x00;
pub const H3_FRAME_HEADERS: u64 = 0x01;
pub const H3_FRAME_CANCEL_PUSH: u64 = 0x03;
pub const H3_FRAME_SETTINGS: u64 = 0x04;
pub const H3_FRAME_PUSH_PROMISE: u64 = 0x05;
pub const H3_FRAME_GOAWAY: u64 = 0x07;
pub const H3_FRAME_MAX_PUSH_ID: u64 = 0x0D;

pub const H3_STREAM_CONTROL: u64 = 0x00;
pub const H3_STREAM_PUSH: u64 = 0x01;
pub const H3_STREAM_QPACK_ENCODER: u64 = 0x02;
pub const H3_STREAM_QPACK_DECODER: u64 = 0x03;

pub const H3_NO_ERROR: u32 = 0x0100;
pub const H3_GENERAL_PROTOCOL_ERROR: u32 = 0x0101;
pub const H3_INTERNAL_ERROR: u32 = 0x0102;
pub const H3_STREAM_CREATION_ERROR: u32 = 0x0103;
pub const H3_CLOSED_CRITICAL_STREAM: u32 = 0x0104;
pub const H3_CLOSED_CRITICAL_STREAM_RFC9114: u32 = 0x0104;
pub const H3_FRAME_UNEXPECTED: u32 = 0x0105;
pub const H3_FRAME_ERROR: u32 = 0x0106;
pub const H3_EXCESSIVE_LOAD: u32 = 0x0107;
pub const H3_ID_ERROR: u32 = 0x0108;
pub const H3_SETTINGS_ERROR: u32 = 0x0109;
pub const H3_MISSING_SETTINGS: u32 = 0x010a;
pub const H3_REQUEST_REJECTED: u32 = 0x010b;
pub const H3_REQUEST_CANCELLED: u32 = 0x010c;
pub const H3_REQUEST_INCOMPLETE: u32 = 0x010d;
pub const H3_MESSAGE_ERROR: u32 = 0x010e;
pub const H3_CONNECT_ERROR: u32 = 0x010f;
pub const H3_VERSION_FALLBACK: u32 = 0x0110;

pub const MAX_CONCURRENT_UNKNOWN_UNI_STREAMS: usize = 16;

pub const SETTINGS_QPACK_MAX_TABLE_CAPACITY: u64 = 0x01;
pub const SETTINGS_ENABLE_CONNECT_PROTOCOL: u64 = 0x08;
pub const SETTINGS_H3_DATAGRAM: u64 = 0x33;
pub const SETTINGS_H3_DATAGRAM_DRAFT00: u64 = 0x0276;
pub const SETTINGS_QPACK_BLOCKED_STREAMS: u64 = 0x07;

/// Encodes a QUIC/HTTP3 variable-length integer (RFC 9000 Section 16).
pub fn encode_varint(buf: &mut Vec<u8>, val: u64) {
    if val <= 63 {
        buf.push(val as u8);
    } else if val <= 16383 {
        buf.push((0x40 | (val >> 8)) as u8);
        buf.push((val & 0xFF) as u8);
    } else if val <= 1073741823 {
        buf.push((0x80 | (val >> 24)) as u8);
        buf.push(((val >> 16) & 0xFF) as u8);
        buf.push(((val >> 8) & 0xFF) as u8);
        buf.push((val & 0xFF) as u8);
    } else {
        buf.push((0xC0 | (val >> 56)) as u8);
        buf.push(((val >> 48) & 0xFF) as u8);
        buf.push(((val >> 40) & 0xFF) as u8);
        buf.push(((val >> 32) & 0xFF) as u8);
        buf.push(((val >> 24) & 0xFF) as u8);
        buf.push(((val >> 16) & 0xFF) as u8);
        buf.push(((val >> 8) & 0xFF) as u8);
        buf.push((val & 0xFF) as u8);
    }
}

/// Returns the length in bytes of a QUIC variable-length integer (RFC 9000 Section 16).
#[inline]
pub fn varint_len(val: u64) -> usize {
    if val <= 63 {
        1
    } else if val <= 16383 {
        2
    } else if val <= 1073741823 {
        4
    } else {
        8
    }
}

/// Decodes a QUIC/HTTP3 variable-length integer from a slice.
/// Returns (value, bytes_consumed).
pub fn decode_varint(data: &[u8]) -> Option<(u64, usize)> {
    if data.is_empty() {
        return None;
    }
    let first = data[0];
    let prefix = first >> 6;
    let len = 1usize << prefix;
    if data.len() < len {
        return None;
    }
    let mut val = (first & 0x3F) as u64;
    for i in 1..len {
        val = (val << 8) | (data[i] as u64);
    }
    Some((val, len))
}

pub async fn read_stream_exact(
    stream: &mut quinn::RecvStream,
    buf: &mut [u8],
) -> Result<(), String> {
    let mut pos = 0;
    while pos < buf.len() {
        match stream.read(&mut buf[pos..]).await {
            Ok(Some(0)) | Ok(None) => return Err("Unexpected EOF reading stream".to_string()),
            Ok(Some(n)) => pos += n,
            Err(e) => return Err(format!("Stream read error: {}", e)),
        }
    }
    Ok(())
}

pub async fn decode_varint_from_stream(stream: &mut quinn::RecvStream) -> Result<u64, String> {
    let mut first_buf = [0u8; 1];
    read_stream_exact(stream, &mut first_buf).await?;
    let first = first_buf[0];
    let prefix = first >> 6;
    let len = 1usize << prefix;
    let mut val = (first & 0x3F) as u64;
    if len > 1 {
        let mut rest = [0u8; 8];
        read_stream_exact(stream, &mut rest[..len - 1]).await?;
        for i in 0..len - 1 {
            val = (val << 8) | (rest[i] as u64);
        }
    }
    Ok(val)
}

/// Encodes an HTTP/3 Datagram per RFC 9297 and RFC 9484.
/// Format: Quarter Stream ID (varint) + Context ID (varint) + Payload.
/// For CONNECT-IP, Context ID 0 indicates an IP packet payload.
pub fn encode_h3_datagram(quarter_stream_id: u64, context_id: u64, payload: &[u8]) -> Vec<u8> {
    let header_cap = varint_len(quarter_stream_id).saturating_add(varint_len(context_id));
    let mut buf = Vec::with_capacity(header_cap.saturating_add(payload.len()));
    encode_varint(&mut buf, quarter_stream_id);
    encode_varint(&mut buf, context_id);
    buf.extend_from_slice(payload);
    buf
}

/// Decodes an HTTP/3 Datagram per RFC 9297 and RFC 9484.
/// Returns (quarter_stream_id, context_id, header_len).
#[inline]
pub fn decode_h3_datagram_offset(datagram: &[u8]) -> Option<(u64, u64, usize)> {
    let (quarter_stream_id, q_len) = decode_varint(datagram)?;
    let (context_id, c_len) = decode_varint(&datagram[q_len..])?;
    let header_len = q_len.checked_add(c_len)?;
    if datagram.len() < header_len {
        return None;
    }
    Some((quarter_stream_id, context_id, header_len))
}

/// Decodes an HTTP/3 Datagram per RFC 9297 and RFC 9484.
/// Returns (quarter_stream_id, context_id, payload_slice).
pub fn decode_h3_datagram(datagram: &[u8]) -> Option<(u64, u64, &[u8])> {
    let (quarter_stream_id, context_id, header_len) = decode_h3_datagram_offset(datagram)?;
    let payload = &datagram[header_len..];
    Some((quarter_stream_id, context_id, payload))
}

pub async fn discard_stream_bytes(
    stream: &mut quinn::RecvStream,
    mut count: u64,
) -> Result<(), String> {
    let mut buf = [0u8; 1024];
    while count > 0 {
        let to_read = std::cmp::min(count, buf.len() as u64) as usize;
        match stream.read(&mut buf[..to_read]).await {
            Ok(Some(0)) | Ok(None) => return Err("Unexpected EOF discarding bytes".to_string()),
            Ok(Some(n)) => count -= n as u64,
            Err(e) => return Err(format!("Stream discard error: {}", e)),
        }
    }
    Ok(())
}

#[inline]
pub fn is_reserved_h2_setting(id: u64) -> bool {
    matches!(id, 0x00 | 0x02 | 0x03 | 0x04 | 0x05)
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Http3ServerSettings {
    pub enable_connect_protocol: bool,
    pub h3_datagram: bool,
    pub max_field_section_size: Option<u64>,
    pub qpack_max_table_capacity: Option<u64>,
    pub qpack_blocked_streams: Option<u64>,
}

impl Http3ServerSettings {
    pub fn parse(payload: &[u8]) -> Result<Self, u32> {
        let mut settings = Self::default();
        let mut offset = 0;
        while offset < payload.len() {
            let (id, n_id) = decode_varint(&payload[offset..]).ok_or(H3_SETTINGS_ERROR)?;
            offset += n_id;
            if offset >= payload.len() && n_id == 0 {
                return Err(H3_SETTINGS_ERROR);
            }
            let (val, n_val) = decode_varint(&payload[offset..]).ok_or(H3_SETTINGS_ERROR)?;
            offset += n_val;

            if is_reserved_h2_setting(id) {
                return Err(H3_SETTINGS_ERROR);
            }

            match id {
                SETTINGS_ENABLE_CONNECT_PROTOCOL => {
                    if val > 1 {
                        return Err(H3_SETTINGS_ERROR);
                    }
                    settings.enable_connect_protocol = val == 1;
                }
                SETTINGS_H3_DATAGRAM | SETTINGS_H3_DATAGRAM_DRAFT00 => {
                    if val > 1 {
                        return Err(H3_SETTINGS_ERROR);
                    }
                    if val == 1 {
                        settings.h3_datagram = true;
                    }
                }
                0x06 => {
                    settings.max_field_section_size = Some(val);
                }
                SETTINGS_QPACK_MAX_TABLE_CAPACITY => {
                    settings.qpack_max_table_capacity = Some(val);
                }
                SETTINGS_QPACK_BLOCKED_STREAMS => {
                    settings.qpack_blocked_streams = Some(val);
                }
                _ => {
                    // RFC 9114 §7.2.4: Unrecognized settings parameters MUST be ignored
                }
            }
        }
        Ok(settings)
    }
}

#[derive(Clone)]
pub struct ServerStreamSupervisor {
    pub server_settings_rx: tokio::sync::watch::Receiver<Option<Http3ServerSettings>>,
    pub goaway_received: Arc<AtomicBool>,
    pub goaway_stream_id: Arc<AtomicU64>,
}

pub fn spawn_server_stream_supervisor_with_cancel(
    conn: quinn::Connection,
    cancel_token: CancellationToken,
) -> (ServerStreamSupervisor, tokio::task::JoinHandle<()>) {
    let (settings_tx, settings_rx) = tokio::sync::watch::channel(None);
    let goaway_received = Arc::new(AtomicBool::new(false));
    let goaway_stream_id = Arc::new(AtomicU64::new(u64::MAX));

    let supervisor = ServerStreamSupervisor {
        server_settings_rx: settings_rx,
        goaway_received: goaway_received.clone(),
        goaway_stream_id: goaway_stream_id.clone(),
    };

    let conn_clone = conn.clone();
    let cancel_token_task = cancel_token.clone();
    let handle = tokio::spawn(async move {
        let server_control_seen = Arc::new(AtomicBool::new(false));
        let qpack_encoder_seen = Arc::new(AtomicBool::new(false));
        let qpack_decoder_seen = Arc::new(AtomicBool::new(false));
        let unknown_uni_count = Arc::new(AtomicUsize::new(0));

        loop {
            tokio::select! {
                _ = cancel_token_task.cancelled() => {
                    ldebug!("MASQUE: server stream supervisor task cancelled");
                    break;
                }
                accept_res = conn_clone.accept_uni() => {
                    let mut stream = match accept_res {
                        Ok(s) => s,
                        Err(e) => {
                            ldebug!("MASQUE: server stream accept loop ended: {}", e);
                            break;
                        }
                    };

                    let conn_drain = conn_clone.clone();
                    let server_control_seen = server_control_seen.clone();
                    let qpack_encoder_seen = qpack_encoder_seen.clone();
                    let qpack_decoder_seen = qpack_decoder_seen.clone();
                    let unknown_uni_count = unknown_uni_count.clone();
                    let settings_tx = settings_tx.clone();
                    let goaway_received = goaway_received.clone();
                    let goaway_stream_id = goaway_stream_id.clone();
                    let cancel_token_child = cancel_token_task.clone();

                    tokio::spawn(async move {
                        tokio::select! {
                            _ = cancel_token_child.cancelled() => {}
                            _ = async {
                                let stream_type = match decode_varint_from_stream(&mut stream).await {
                                    Ok(t) => t,
                                    Err(e) => {
                                        ldebug!("MASQUE: failed to decode stream type on uni stream: {}", e);
                                        return;
                                    }
                                };

                                match stream_type {
                                    H3_STREAM_CONTROL => {
                                        if server_control_seen.swap(true, Ordering::SeqCst) {
                                            lwarn!("MASQUE: multiple server control streams received; closing connection");
                                            conn_drain.close(
                                                quinn::VarInt::from_u32(H3_STREAM_CREATION_ERROR),
                                                b"multiple control streams created",
                                            );
                                            return;
                                        }

                                        // RFC 9114 §7.2.4: First frame on control stream MUST be SETTINGS
                                        let first_frame_type = match decode_varint_from_stream(&mut stream).await {
                                            Ok(t) => t,
                                            Err(_) => {
                                                if conn_drain.close_reason().is_none() {
                                                    conn_drain.close(
                                                        quinn::VarInt::from_u32(H3_CLOSED_CRITICAL_STREAM),
                                                        b"control stream closed before first frame",
                                                    );
                                                }
                                                return;
                                            }
                                        };
                                        let first_frame_len = match decode_varint_from_stream(&mut stream).await {
                                            Ok(l) => l,
                                            Err(_) => {
                                                if conn_drain.close_reason().is_none() {
                                                    conn_drain.close(
                                                        quinn::VarInt::from_u32(H3_CLOSED_CRITICAL_STREAM),
                                                        b"control stream closed during frame len",
                                                    );
                                                }
                                                return;
                                            }
                                        };

                                        if first_frame_type != H3_FRAME_SETTINGS {
                                            lwarn!(
                                                "MASQUE: first frame on server control stream was 0x{:x}, expected SETTINGS (0x04)",
                                                first_frame_type
                                            );
                                            conn_drain.close(
                                                quinn::VarInt::from_u32(H3_MISSING_SETTINGS),
                                                b"first frame must be SETTINGS",
                                            );
                                            return;
                                        }

                                        if first_frame_len > 65536 {
                                            conn_drain.close(
                                                quinn::VarInt::from_u32(H3_EXCESSIVE_LOAD),
                                                b"SETTINGS frame too large",
                                            );
                                            return;
                                        }

                                        let mut settings_buf = vec![0u8; first_frame_len as usize];
                                        if let Err(_e) = read_stream_exact(&mut stream, &mut settings_buf).await {
                                            if conn_drain.close_reason().is_none() {
                                                conn_drain.close(
                                                    quinn::VarInt::from_u32(H3_CLOSED_CRITICAL_STREAM),
                                                    b"failed to read SETTINGS frame payload",
                                                );
                                            }
                                            return;
                                        }

                                        let parsed_settings = match Http3ServerSettings::parse(&settings_buf) {
                                            Ok(s) => s,
                                            Err(err_code) => {
                                                lwarn!("MASQUE: invalid server SETTINGS payload, err=0x{:x}", err_code);
                                                conn_drain.close(
                                                    quinn::VarInt::from_u32(err_code),
                                                    b"invalid SETTINGS frame",
                                                );
                                                return;
                                            }
                                        };

                                        ldebug!(
                                            "MASQUE: server SETTINGS negotiated: connect_proto={}, datagram={}, max_field_sec={:?}",
                                            parsed_settings.enable_connect_protocol,
                                            parsed_settings.h3_datagram,
                                            parsed_settings.max_field_section_size
                                        );

                                        let _ = settings_tx.send(Some(parsed_settings));

                                        // Loop reading subsequent frames on control stream
                                        loop {
                                            let frame_type = match decode_varint_from_stream(&mut stream).await {
                                                Ok(t) => t,
                                                Err(_) => {
                                                    if conn_drain.close_reason().is_none() {
                                                        lwarn!("MASQUE: server control stream closed unexpectedly (FIN); closing connection");
                                                        conn_drain.close(
                                                            quinn::VarInt::from_u32(H3_CLOSED_CRITICAL_STREAM),
                                                            b"server control stream closed",
                                                        );
                                                    }
                                                    remove_active_quic_session_by_conn(conn_drain.stable_id());
                                                    break;
                                                }
                                            };

                                            let frame_len = match decode_varint_from_stream(&mut stream).await {
                                                Ok(l) => l,
                                                Err(_) => {
                                                    if conn_drain.close_reason().is_none() {
                                                        conn_drain.close(
                                                            quinn::VarInt::from_u32(H3_CLOSED_CRITICAL_STREAM),
                                                            b"server control stream closed during frame header",
                                                        );
                                                    }
                                                    break;
                                                }
                                            };

                                            match frame_type {
                                                H3_FRAME_SETTINGS => {
                                                    // RFC 9114 §7.2.4: Duplicate SETTINGS is a connection error
                                                    lwarn!("MASQUE: duplicate SETTINGS frame received on control stream");
                                                    conn_drain.close(
                                                        quinn::VarInt::from_u32(H3_FRAME_UNEXPECTED),
                                                        b"duplicate SETTINGS frame",
                                                    );
                                                    break;
                                                }
                                                H3_FRAME_DATA | H3_FRAME_HEADERS | H3_FRAME_MAX_PUSH_ID => {
                                                    lwarn!("MASQUE: unexpected frame 0x{:x} on control stream", frame_type);
                                                    conn_drain.close(
                                                        quinn::VarInt::from_u32(H3_FRAME_UNEXPECTED),
                                                        b"unexpected frame on control stream",
                                                    );
                                                    break;
                                                }
                                                H3_FRAME_GOAWAY => {
                                                    let mut payload = vec![0u8; frame_len as usize];
                                                    if read_stream_exact(&mut stream, &mut payload).await.is_ok() {
                                                        if let Some((max_stream_id, _)) = decode_varint(&payload) {
                                                            linfo!(
                                                                "MASQUE: server sent GOAWAY (stream_id: {}); terminating session reuse",
                                                                max_stream_id
                                                            );
                                                            goaway_stream_id.store(max_stream_id, Ordering::SeqCst);
                                                        }
                                                    }
                                                    goaway_received.store(true, Ordering::SeqCst);
                                                    remove_active_quic_session_by_conn(conn_drain.stable_id());
                                                }
                                                H3_FRAME_CANCEL_PUSH => {
                                                    let _ = discard_stream_bytes(&mut stream, frame_len).await;
                                                }
                                                _ => {
                                                    // RFC 9114 §7.2.8: Endpoints MUST ignore unrecognized frame types
                                                    let _ = discard_stream_bytes(&mut stream, frame_len).await;
                                                }
                                            }
                                        }
                                    }
                                    H3_STREAM_QPACK_ENCODER => {
                                        if qpack_encoder_seen.swap(true, Ordering::SeqCst) {
                                            lwarn!("MASQUE: multiple QPACK encoder streams received");
                                            conn_drain.close(
                                                quinn::VarInt::from_u32(H3_STREAM_CREATION_ERROR),
                                                b"multiple QPACK encoder streams",
                                            );
                                            return;
                                        }
                                        let mut sink = [0u8; 1024];
                                        while let Ok(Some(_)) = stream.read(&mut sink).await {}
                                    }
                                    H3_STREAM_QPACK_DECODER => {
                                        if qpack_decoder_seen.swap(true, Ordering::SeqCst) {
                                            lwarn!("MASQUE: multiple QPACK decoder streams received");
                                            conn_drain.close(
                                                quinn::VarInt::from_u32(H3_STREAM_CREATION_ERROR),
                                                b"multiple QPACK decoder streams",
                                            );
                                            return;
                                        }
                                        let mut sink = [0u8; 1024];
                                        while let Ok(Some(_)) = stream.read(&mut sink).await {}
                                    }
                                    H3_STREAM_PUSH => {
                                        let mut sink = [0u8; 1024];
                                        while let Ok(Some(_)) = stream.read(&mut sink).await {}
                                    }
                                    _ => {
                                        // Unknown unidirectional stream: limit concurrent count to avoid resource exhaustion
                                        if unknown_uni_count.fetch_add(1, Ordering::SeqCst) >= MAX_CONCURRENT_UNKNOWN_UNI_STREAMS {
                                            lwarn!("MASQUE: excessive unknown unidirectional streams received");
                                            conn_drain.close(
                                                quinn::VarInt::from_u32(H3_EXCESSIVE_LOAD),
                                                b"too many unknown uni streams",
                                            );
                                            return;
                                        }
                                        let mut sink = [0u8; 1024];
                                        while let Ok(Some(_)) = stream.read(&mut sink).await {}
                                        unknown_uni_count.fetch_sub(1, Ordering::SeqCst);
                                    }
                                }
                            } => {}
                        }
                    });
                }
            }
        }
    });

    (supervisor, handle)
}

pub fn spawn_server_stream_supervisor(conn: quinn::Connection) -> ServerStreamSupervisor {
    spawn_server_stream_supervisor_with_cancel(conn, CancellationToken::new()).0
}

// ---------------------------------------------------------------------------
// Capsule Protocol (RFC 9297 & RFC 9484 CONNECT-IP)
// ---------------------------------------------------------------------------

pub const CAPSULE_ADDRESS_ASSIGN: u64 = 0x01;
pub const CAPSULE_ADDRESS_REQUEST: u64 = 0x02;
pub const CAPSULE_ROUTE_ADVERTISEMENT: u64 = 0x03;

pub const MAX_H3_FRAME_HEADER_SIZE: usize = 16;
pub const MAX_H3_DATA_FRAME_PAYLOAD_SIZE: usize = 16 * 1024 * 1024;
pub const MAX_H3_BUFFER_SIZE: usize = 64 * 1024;
pub const MAX_CAPSULE_PAYLOAD_SIZE: usize = 64 * 1024;
pub const MAX_CAPSULE_BUFFER_SIZE: usize = 128 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AssignedAddress {
    pub request_id: u64,
    pub ip_version: u8,
    pub ip_addr: std::net::IpAddr,
    pub prefix_len: u8,
}

/// Address lifecycle state for proxy IP assignment (RFC 9484 §4.7.1 & §4.7.2).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AddressState<T> {
    /// Address has not been assigned by peer; using out-of-band configured address if available.
    Unassigned,
    /// Peer has explicitly assigned this IP address via ADDRESS_ASSIGN (prefix_len > 0).
    Assigned(T),
    /// Peer has explicitly withdrawn this address via ADDRESS_ASSIGN (prefix_len == 0 or empty capsule).
    Withdrawn,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IpAddressRange {
    pub ip_version: u8,
    pub start_ip: std::net::IpAddr,
    pub end_ip: std::net::IpAddr,
    pub ip_protocol: u8,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Capsule {
    AddressAssign(Vec<AssignedAddress>),
    AddressRequest(Vec<AssignedAddress>),
    RouteAdvertisement(Vec<IpAddressRange>),
    MalformedKnown { capsule_type: u64, error: String },
    Unknown { capsule_type: u64, payload: Vec<u8> },
}

/// Encodes a Capsule per RFC 9297: Type (varint) + Length (varint) + Value.
pub fn encode_capsule(capsule_type: u64, payload: &[u8]) -> Vec<u8> {
    let mut buf = Vec::with_capacity(16 + payload.len());
    encode_varint(&mut buf, capsule_type);
    encode_varint(&mut buf, payload.len() as u64);
    buf.extend_from_slice(payload);
    buf
}

/// Wraps payload bytes into an HTTP/3 DATA frame (Type 0x00).
pub fn wrap_in_h3_data_frame(payload: &[u8]) -> Vec<u8> {
    let mut frame = Vec::with_capacity(10 + payload.len());
    encode_varint(&mut frame, H3_FRAME_DATA);
    encode_varint(&mut frame, payload.len() as u64);
    frame.extend_from_slice(payload);
    frame
}

/// Encodes an ADDRESS_ASSIGN capsule (Type 0x01) per RFC 9484 §4.7.1.
pub fn encode_address_assign(addresses: &[AssignedAddress]) -> Vec<u8> {
    let mut payload = Vec::new();
    for addr in addresses {
        encode_varint(&mut payload, addr.request_id);
        payload.push(addr.ip_version);
        match addr.ip_addr {
            std::net::IpAddr::V4(v4) => payload.extend_from_slice(&v4.octets()),
            std::net::IpAddr::V6(v6) => payload.extend_from_slice(&v6.octets()),
        }
        payload.push(addr.prefix_len);
    }
    encode_capsule(CAPSULE_ADDRESS_ASSIGN, &payload)
}

/// Encodes an ADDRESS_REQUEST capsule (Type 0x02) per RFC 9484 §4.7.2.
pub fn encode_address_request(requests: &[AssignedAddress]) -> Vec<u8> {
    let mut payload = Vec::new();
    for req in requests {
        encode_varint(&mut payload, req.request_id);
        payload.push(req.ip_version);
        match req.ip_addr {
            std::net::IpAddr::V4(v4) => payload.extend_from_slice(&v4.octets()),
            std::net::IpAddr::V6(v6) => payload.extend_from_slice(&v6.octets()),
        }
        payload.push(req.prefix_len);
    }
    encode_capsule(CAPSULE_ADDRESS_REQUEST, &payload)
}

/// Encodes a ROUTE_ADVERTISEMENT capsule (Type 0x03) per RFC 9484 §4.7.3.
pub fn encode_route_advertisement(routes: &[IpAddressRange]) -> Vec<u8> {
    let mut payload = Vec::new();
    for route in routes {
        payload.push(route.ip_version);
        match (route.start_ip, route.end_ip) {
            (std::net::IpAddr::V4(s), std::net::IpAddr::V4(e)) => {
                payload.extend_from_slice(&s.octets());
                payload.extend_from_slice(&e.octets());
            }
            (std::net::IpAddr::V6(s), std::net::IpAddr::V6(e)) => {
                payload.extend_from_slice(&s.octets());
                payload.extend_from_slice(&e.octets());
            }
            _ => continue,
        }
        payload.push(route.ip_protocol);
    }
    encode_capsule(CAPSULE_ROUTE_ADVERTISEMENT, &payload)
}

/// Decodes the payload of an ADDRESS_ASSIGN (or ADDRESS_REQUEST) capsule per RFC 9484.
pub fn parse_address_assign_payload(data: &[u8]) -> Result<Vec<AssignedAddress>, String> {
    let mut addrs = Vec::new();
    let mut offset = 0;
    while offset < data.len() {
        let (req_id, req_len) = decode_varint(&data[offset..])
            .ok_or_else(|| "truncated request_id in ADDRESS_ASSIGN".to_string())?;
        offset = offset
            .checked_add(req_len)
            .ok_or_else(|| "offset overflow in ADDRESS_ASSIGN".to_string())?;

        if offset >= data.len() {
            return Err("missing ip_version in ADDRESS_ASSIGN".to_string());
        }
        let version = data[offset];
        offset = offset
            .checked_add(1)
            .ok_or_else(|| "offset overflow in ADDRESS_ASSIGN".to_string())?;

        match version {
            4 => {
                if offset.checked_add(5).map_or(true, |end| end > data.len()) {
                    return Err("truncated IPv4 assigned address".to_string());
                }
                let mut octets = [0u8; 4];
                octets.copy_from_slice(&data[offset..offset + 4]);
                offset += 4;
                let prefix_len = data[offset];
                offset += 1;
                if prefix_len > 32 {
                    return Err(format!(
                        "invalid IPv4 prefix_len {} > 32 in ADDRESS_ASSIGN",
                        prefix_len
                    ));
                }
                addrs.push(AssignedAddress {
                    request_id: req_id,
                    ip_version: 4,
                    ip_addr: std::net::IpAddr::V4(std::net::Ipv4Addr::from(octets)),
                    prefix_len,
                });
            }
            6 => {
                if offset.checked_add(17).map_or(true, |end| end > data.len()) {
                    return Err("truncated IPv6 assigned address".to_string());
                }
                let mut octets = [0u8; 16];
                octets.copy_from_slice(&data[offset..offset + 16]);
                offset += 16;
                let prefix_len = data[offset];
                offset += 1;
                if prefix_len > 128 {
                    return Err(format!(
                        "invalid IPv6 prefix_len {} > 128 in ADDRESS_ASSIGN",
                        prefix_len
                    ));
                }
                addrs.push(AssignedAddress {
                    request_id: req_id,
                    ip_version: 6,
                    ip_addr: std::net::IpAddr::V6(std::net::Ipv6Addr::from(octets)),
                    prefix_len,
                });
            }
            other => {
                return Err(format!("invalid IP version {} in ADDRESS_ASSIGN", other));
            }
        }
    }
    Ok(addrs)
}

/// Decodes the payload of a ROUTE_ADVERTISEMENT capsule per RFC 9484 §4.7.3.
pub fn parse_route_advertisement_payload(data: &[u8]) -> Result<Vec<IpAddressRange>, String> {
    let mut ranges = Vec::new();
    let mut offset = 0;
    while offset < data.len() {
        if offset >= data.len() {
            return Err("unexpected end of data in ROUTE_ADVERTISEMENT".to_string());
        }
        let version = data[offset];
        offset = offset
            .checked_add(1)
            .ok_or_else(|| "offset overflow in ROUTE_ADVERTISEMENT".to_string())?;

        match version {
            4 => {
                if offset.checked_add(9).map_or(true, |end| end > data.len()) {
                    return Err("truncated IPv4 route range in ROUTE_ADVERTISEMENT".to_string());
                }
                let mut start = [0u8; 4];
                start.copy_from_slice(&data[offset..offset + 4]);
                offset += 4;

                let mut end = [0u8; 4];
                end.copy_from_slice(&data[offset..offset + 4]);
                offset += 4;

                let ip_proto = data[offset];
                offset += 1;

                let start_v4 = std::net::Ipv4Addr::from(start);
                let end_v4 = std::net::Ipv4Addr::from(end);
                if u32::from(start_v4) > u32::from(end_v4) {
                    return Err(format!(
                        "invalid IPv4 route range: start {} > end {}",
                        start_v4, end_v4
                    ));
                }

                ranges.push(IpAddressRange {
                    ip_version: 4,
                    start_ip: std::net::IpAddr::V4(start_v4),
                    end_ip: std::net::IpAddr::V4(end_v4),
                    ip_protocol: ip_proto,
                });
            }
            6 => {
                if offset.checked_add(33).map_or(true, |end| end > data.len()) {
                    return Err("truncated IPv6 route range in ROUTE_ADVERTISEMENT".to_string());
                }
                let mut start = [0u8; 16];
                start.copy_from_slice(&data[offset..offset + 16]);
                offset += 16;

                let mut end = [0u8; 16];
                end.copy_from_slice(&data[offset..offset + 16]);
                offset += 16;

                let ip_proto = data[offset];
                offset += 1;

                let start_v6 = std::net::Ipv6Addr::from(start);
                let end_v6 = std::net::Ipv6Addr::from(end);
                if u128::from(start_v6) > u128::from(end_v6) {
                    return Err(format!(
                        "invalid IPv6 route range: start {} > end {}",
                        start_v6, end_v6
                    ));
                }

                ranges.push(IpAddressRange {
                    ip_version: 6,
                    start_ip: std::net::IpAddr::V6(start_v6),
                    end_ip: std::net::IpAddr::V6(end_v6),
                    ip_protocol: ip_proto,
                });
            }
            other => {
                return Err(format!(
                    "invalid IP version {} in ROUTE_ADVERTISEMENT",
                    other
                ));
            }
        }
    }
    Ok(ranges)
}

/// Decodes the next Capsule from a byte buffer. Returns (Capsule, bytes_consumed).
pub fn decode_next_capsule(buf: &[u8]) -> Option<(Capsule, usize)> {
    let (capsule_type, type_len) = decode_varint(buf)?;
    let (capsule_len_u64, len_len) = decode_varint(&buf[type_len..])?;
    let capsule_len = usize::try_from(capsule_len_u64).ok()?;
    if capsule_len > MAX_CAPSULE_PAYLOAD_SIZE {
        return None;
    }
    let header_len = type_len.checked_add(len_len)?;
    let total_len = header_len.checked_add(capsule_len)?;
    if buf.len() < total_len {
        return None;
    }
    let payload = &buf[header_len..total_len];
    let capsule = match capsule_type {
        CAPSULE_ADDRESS_ASSIGN => match parse_address_assign_payload(payload) {
            Ok(addrs) => Capsule::AddressAssign(addrs),
            Err(e) => {
                lwarn!("MASQUE: rejected malformed ADDRESS_ASSIGN capsule: {}", e);
                Capsule::MalformedKnown {
                    capsule_type,
                    error: e,
                }
            }
        },
        CAPSULE_ADDRESS_REQUEST => match parse_address_assign_payload(payload) {
            Ok(addrs) => Capsule::AddressRequest(addrs),
            Err(e) => {
                lwarn!("MASQUE: rejected malformed ADDRESS_REQUEST capsule: {}", e);
                Capsule::MalformedKnown {
                    capsule_type,
                    error: e,
                }
            }
        },
        CAPSULE_ROUTE_ADVERTISEMENT => match parse_route_advertisement_payload(payload) {
            Ok(routes) => Capsule::RouteAdvertisement(routes),
            Err(e) => {
                lwarn!("MASQUE: rejected malformed ROUTE_ADVERTISEMENT capsule: {}", e);
                Capsule::MalformedKnown {
                    capsule_type,
                    error: e,
                }
            }
        },
        other => Capsule::Unknown {
            capsule_type: other,
            payload: payload.to_vec(),
        },
    };

    Some((capsule, total_len))
}

/// Spawns a background task reading HTTP/3 frames from recv_stream,
/// unwrapping H3 DATA frames (0x00) into capsules, and sending decoded capsules to an mpsc channel.
pub fn spawn_capsule_reader(
    mut recv_stream: quinn::RecvStream,
    initial_buf: Vec<u8>,
) -> tokio::sync::mpsc::Receiver<Capsule> {
    let (tx, rx) = tokio::sync::mpsc::channel(MASQUE_CAPSULE_QUEUE_CAPACITY);
    tokio::spawn(async move {
        let mut read_buf = initial_buf;
        let mut capsule_buf = Vec::new();
        let mut temp = [0u8; 4096];

        loop {
            // 1. Unframe HTTP/3 frames from read_buf into capsule_buf
            while !read_buf.is_empty() {
                if let Some((ftype, tlen)) = decode_varint(&read_buf) {
                    if let Some((flen_u64, llen)) = decode_varint(&read_buf[tlen..]) {
                        let flen = match usize::try_from(flen_u64) {
                            Ok(l) => l,
                            Err(_) => {
                                lwarn!("MASQUE capsule reader: HTTP/3 frame length {} exceeds addressable memory; aborting", flen_u64);
                                return;
                            }
                        };
                        if flen > MAX_H3_DATA_FRAME_PAYLOAD_SIZE {
                            lwarn!("MASQUE capsule reader: HTTP/3 frame length {} exceeds max allowed limit {}; aborting", flen, MAX_H3_DATA_FRAME_PAYLOAD_SIZE);
                            return;
                        }
                        let header_len = match tlen.checked_add(llen) {
                            Some(hl) => hl,
                            None => {
                                lwarn!("MASQUE capsule reader: frame header length overflow; aborting");
                                return;
                            }
                        };
                        let total_len = match header_len.checked_add(flen) {
                            Some(tl) => tl,
                            None => {
                                lwarn!("MASQUE capsule reader: total frame length overflow; aborting");
                                return;
                            }
                        };
                        if read_buf.len() >= total_len {
                            let payload = &read_buf[header_len..total_len];
                            if ftype == H3_FRAME_DATA {
                                if capsule_buf.len().saturating_add(payload.len()) > MAX_CAPSULE_BUFFER_SIZE {
                                    lwarn!("MASQUE capsule reader: capsule_buf exceeded max buffer limit {}; aborting", MAX_CAPSULE_BUFFER_SIZE);
                                    return;
                                }
                                capsule_buf.extend_from_slice(payload);
                            }
                            read_buf.drain(..total_len);
                            continue;
                        }
                    }
                }
                break;
            }

            // Check read_buf limit
            if read_buf.len() > MAX_H3_BUFFER_SIZE {
                lwarn!("MASQUE capsule reader: read_buf exceeded max limit {}; aborting", MAX_H3_BUFFER_SIZE);
                return;
            }

            // 2. Decode complete Capsules from capsule_buf
            while !capsule_buf.is_empty() {
                if let Some((capsule, consumed)) = decode_next_capsule(&capsule_buf) {
                    capsule_buf.drain(..consumed);
                    match tx.try_send(capsule) {
                        Ok(_) => {}
                        Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => {
                            lwarn!(
                                "MASQUE capsule reader: capsule queue full ({}), dropping capsule",
                                MASQUE_CAPSULE_QUEUE_CAPACITY
                            );
                        }
                        Err(tokio::sync::mpsc::error::TrySendError::Closed(_)) => {
                            ldebug!("MASQUE capsule reader: receiver dropped, terminating task");
                            return;
                        }
                    }
                } else {
                    // Check if capsule_buf starts with an invalid/oversized varint length:
                    if let Some((_, tlen)) = decode_varint(&capsule_buf) {
                        if let Some((clen_u64, _)) = decode_varint(&capsule_buf[tlen..]) {
                            if usize::try_from(clen_u64).map_or(true, |l| l > MAX_CAPSULE_PAYLOAD_SIZE) {
                                lwarn!("MASQUE capsule reader: oversized capsule length {} in capsule_buf; aborting", clen_u64);
                                return;
                            }
                        }
                    }
                    break;
                }
            }

            // 3. Read more bytes from recv_stream
            match recv_stream.read(&mut temp).await {
                Ok(Some(n)) if n > 0 => {
                    if read_buf.len().saturating_add(n) > MAX_H3_BUFFER_SIZE {
                        lwarn!("MASQUE capsule reader: read_buf would exceed limit {}; aborting", MAX_H3_BUFFER_SIZE);
                        return;
                    }
                    read_buf.extend_from_slice(&temp[..n]);
                }
                _ => break,
            }
        }
    });

    rx
}

fn encode_qpack_int(buf: &mut Vec<u8>, prefix_bits: u8, prefix_mask: u8, mut val: u64) {
    let max_prefix = (1u64 << prefix_bits) - 1;
    if val < max_prefix {
        buf.push(prefix_mask | (val as u8));
    } else {
        buf.push(prefix_mask | (max_prefix as u8));
        val -= max_prefix;
        while val >= 128 {
            buf.push(((val & 0x7F) as u8) | 0x80);
            val >>= 7;
        }
        buf.push(val as u8);
    }
}

fn encode_qpack_string(buf: &mut Vec<u8>, s: &[u8]) {
    encode_qpack_int(buf, 7, 0x00, s.len() as u64);
    buf.extend_from_slice(s);
}

fn qpack_encode_indexed(buf: &mut Vec<u8>, static_index: u64) {
    encode_qpack_int(buf, 6, 0xC0, static_index);
}

fn qpack_encode_name_ref(buf: &mut Vec<u8>, static_name_index: u64, value: &str) {
    encode_qpack_int(buf, 4, 0x50, static_name_index);
    encode_qpack_string(buf, value.as_bytes());
}

fn qpack_encode_literal(buf: &mut Vec<u8>, name: &str, value: &str) {
    encode_qpack_int(buf, 3, 0x20, name.len() as u64);
    buf.extend_from_slice(name.as_bytes());
    encode_qpack_string(buf, value.as_bytes());
}

/// Builds the CONNECT-IP URI template path according to RFC 9484.
/// If custom_path is None, empty, or "/", returns MASQUE_DEFAULT_URI_PATH ("/.well-known/masque/ip/").
/// If custom_path is provided, resolves template variables:
/// {target} -> target address or wildcard (e.g. "149.154.167.50:443", "2001%3Adb8%3A%3A1%3A443", or "*")
/// {target_ip} / {host} -> target host IP or wildcard (e.g. "149.154.167.50", "2001%3Adb8%3A%3A1", or "*")
/// {target_port} / {port} -> target port or wildcard (e.g. "443" or "*")
/// {ipproto} -> "6" (TCP per RFC 9484) or wildcard
pub fn build_connect_ip_path(target_addr: &str, custom_path: Option<&str>) -> String {
    if let Some(p) = custom_path {
        let trimmed = p.trim();
        if !trimmed.is_empty() && trimmed != "/" {
            let mut resolved = trimmed.to_string();
            if !target_addr.is_empty() {
                let (host, port) = if let Some(colon_pos) = target_addr.rfind(':') {
                    (&target_addr[..colon_pos], &target_addr[colon_pos + 1..])
                } else {
                    (target_addr, "")
                };
                let host_clean = host.trim_matches('[').trim_matches(']');
                let host_encoded = host_clean.replace(':', "%3A");
                let port_str = if port.is_empty() { "443" } else { port };
                let target_val = if !port.is_empty() {
                    format!("{}:{}", host_encoded, port_str)
                } else {
                    host_encoded.clone()
                };
                resolved = resolved
                    .replace("{target}", &target_val)
                    .replace("{target_ip}", &host_encoded)
                    .replace("{target_port}", port_str)
                    .replace("{host}", &host_encoded)
                    .replace("{port}", port_str)
                    .replace("{ipproto}", "6");
            } else {
                // Wildcard fallback per RFC 9484 §4.6
                resolved = resolved
                    .replace("{target}", "*")
                    .replace("{target_ip}", "*")
                    .replace("{target_port}", "*")
                    .replace("{host}", "*")
                    .replace("{port}", "*")
                    .replace("{ipproto}", "*");
            }
            if resolved != "/" && !resolved.is_empty() {
                return resolved;
            }
        }
    }
    MASQUE_DEFAULT_URI_PATH.to_string()
}

pub fn build_h3_connect_headers(
    target_addr: &str,
    sni: &str,
    protocol: Option<&str>,
    path: Option<&str>,
    gateway_bearer_token: Option<&str>,
) -> Vec<u8> {
    let mut qpack = Vec::new();
    // QPACK Prefix (RFC 9204 Section 4.5.1): Required Insert Count = 0, Sign = 0, Delta Base = 0
    qpack.push(0x00);
    qpack.push(0x00);

    // 1. :method = CONNECT (Static Table Index 15)
    qpack_encode_indexed(&mut qpack, 15);

    if let Some(proto) = protocol {
        // Extended CONNECT (RFC 9220 / RFC 9484 / Cloudflare cf-connect-ip)
        // 2. :protocol = proto ("connect-ip" or "cf-connect-ip")
        qpack_encode_literal(&mut qpack, ":protocol", proto);

        // 3. :scheme = https (Static Table Index 23)
        qpack_encode_indexed(&mut qpack, 23);

        // 4. :authority = MASQUE Gateway Host (RFC 9484 Section 4)
        // According to RFC 9484 §4, :authority points to the proxy gateway itself (consumer-masque.cloudflareclient.com or custom SNI),
        // NEVER target_addr and never the obsolete hardcoded cloudflareaccess.com.
        let auth = if !sni.is_empty() && sni != MASQUE_L4_SNI {
            sni
        } else {
            MASQUE_IP_SNI
        };
        qpack_encode_name_ref(&mut qpack, 0, auth);

        // 5. :path = URI template path (RFC 9484 Section 3 & 4)
        // For CONNECT-IP, :path MUST NEVER be "/"! Passing "/" causes Cloudflare Anycast server
        // to not recognize the IP tunnel context and drop the connection.
        let resolved_path = build_connect_ip_path(target_addr, path);

        // Static Table Name Reference Index 1: :path
        qpack_encode_name_ref(&mut qpack, 1, &resolved_path);

        // 6. capsule-protocol: ?1 (RFC 9297 Capsule Protocol for MASQUE IP tunnel)
        qpack_encode_literal(&mut qpack, "capsule-protocol", "?1");
    } else {
        // Standard HTTP/3 CONNECT for Layer 4 TCP proxying (RFC 9114 Section 4.4, usque l4-socks mode)
        // :authority = target_addr (Static Table Index 0)
        qpack_encode_name_ref(&mut qpack, 0, target_addr);
        // Note: Standard CONNECT does NOT use :scheme, :path, :protocol, or capsule-protocol!
    }

    // Optional Gateway Bearer token authorization (Static Table Index 84: authorization).
    // Note (TSK-M02): Pure mTLS does NOT send Authorization headers.
    // Cloudflare REST API management token must never be sent here as a gateway credential.
    if let Some(token) = gateway_bearer_token {
        let trimmed = token.trim();
        if !trimmed.is_empty() {
            let auth_val = if trimmed.starts_with("Bearer ") || trimmed.starts_with("bearer ") {
                trimmed.to_string()
            } else {
                format!("Bearer {}", trimmed)
            };
            qpack_encode_name_ref(&mut qpack, 84, &auth_val);
        }
    }

    // Wrap in HTTP/3 HEADERS frame (Type 0x01)
    let mut frame = Vec::with_capacity(qpack.len() + 10);
    encode_varint(&mut frame, H3_FRAME_HEADERS);
    encode_varint(&mut frame, qpack.len() as u64);
    frame.extend_from_slice(&qpack);
    frame
}

// ---------------------------------------------------------------------------
// RFC 7541 / RFC 9204 QPACK Huffman Code Table and Decoder
// ---------------------------------------------------------------------------

pub static HUFFMAN_CODE_TABLE: &[(u8, u32); 257] = &[
    (13, 0x1ff8),    (23, 0x7fffd8),    (28, 0xfffffe2),    (28, 0xfffffe3),
    (28, 0xfffffe4),    (28, 0xfffffe5),    (28, 0xfffffe6),    (28, 0xfffffe7),
    (28, 0xfffffe8),    (24, 0xffffea),    (30, 0x3ffffffc),    (28, 0xfffffe9),
    (28, 0xfffffea),    (30, 0x3ffffffd),    (28, 0xfffffeb),    (28, 0xfffffec),
    (28, 0xfffffed),    (28, 0xfffffee),    (28, 0xfffffef),    (28, 0xffffff0),
    (28, 0xffffff1),    (28, 0xffffff2),    (30, 0x3ffffffe),    (28, 0xffffff3),
    (28, 0xffffff4),    (28, 0xffffff5),    (28, 0xffffff6),    (28, 0xffffff7),
    (28, 0xffffff8),    (28, 0xffffff9),    (28, 0xffffffa),    (28, 0xffffffb),
    (6, 0x14),    (10, 0x3f8),    (10, 0x3f9),    (12, 0xffa),
    (13, 0x1ff9),    (6, 0x15),    (8, 0xf8),    (11, 0x7fa),
    (10, 0x3fa),    (10, 0x3fb),    (8, 0xf9),    (11, 0x7fb),
    (8, 0xfa),    (6, 0x16),    (6, 0x17),    (6, 0x18),
    (5, 0x0),    (5, 0x1),    (5, 0x2),    (6, 0x19),
    (6, 0x1a),    (6, 0x1b),    (6, 0x1c),    (6, 0x1d),
    (6, 0x1e),    (6, 0x1f),    (7, 0x5c),    (8, 0xfb),
    (15, 0x7ffc),    (6, 0x20),    (12, 0xffb),    (10, 0x3fc),
    (13, 0x1ffa),    (6, 0x21),    (7, 0x5d),    (7, 0x5e),
    (7, 0x5f),    (7, 0x60),    (7, 0x61),    (7, 0x62),
    (7, 0x63),    (7, 0x64),    (7, 0x65),    (7, 0x66),
    (7, 0x67),    (7, 0x68),    (7, 0x69),    (7, 0x6a),
    (7, 0x6b),    (7, 0x6c),    (7, 0x6d),    (7, 0x6e),
    (7, 0x6f),    (7, 0x70),    (7, 0x71),    (7, 0x72),
    (8, 0xfc),    (7, 0x73),    (8, 0xfd),    (13, 0x1ffb),
    (19, 0x7fff0),    (13, 0x1ffc),    (14, 0x3ffc),    (6, 0x22),
    (15, 0x7ffd),    (5, 0x3),    (6, 0x23),    (5, 0x4),
    (6, 0x24),    (5, 0x5),    (6, 0x25),    (6, 0x26),
    (6, 0x27),    (5, 0x6),    (7, 0x74),    (7, 0x75),
    (6, 0x28),    (6, 0x29),    (6, 0x2a),    (5, 0x7),
    (6, 0x2b),    (7, 0x76),    (6, 0x2c),    (5, 0x8),
    (5, 0x9),    (6, 0x2d),    (7, 0x77),    (7, 0x78),
    (7, 0x79),    (7, 0x7a),    (7, 0x7b),    (15, 0x7ffe),
    (11, 0x7fc),    (14, 0x3ffd),    (13, 0x1ffd),    (28, 0xffffffc),
    (20, 0xfffe6),    (22, 0x3fffd2),    (20, 0xfffe7),    (20, 0xfffe8),
    (22, 0x3fffd3),    (22, 0x3fffd4),    (22, 0x3fffd5),    (23, 0x7fffd9),
    (22, 0x3fffd6),    (23, 0x7fffda),    (23, 0x7fffdb),    (23, 0x7fffdc),
    (23, 0x7fffdd),    (23, 0x7fffde),    (24, 0xffffeb),    (23, 0x7fffdf),
    (24, 0xffffec),    (24, 0xffffed),    (22, 0x3fffd7),    (23, 0x7fffe0),
    (24, 0xffffee),    (23, 0x7fffe1),    (23, 0x7fffe2),    (23, 0x7fffe3),
    (23, 0x7fffe4),    (21, 0x1fffdc),    (22, 0x3fffd8),    (23, 0x7fffe5),
    (22, 0x3fffd9),    (23, 0x7fffe6),    (23, 0x7fffe7),    (24, 0xffffef),
    (22, 0x3fffda),    (21, 0x1fffdd),    (20, 0xfffe9),    (22, 0x3fffdb),
    (22, 0x3fffdc),    (23, 0x7fffe8),    (23, 0x7fffe9),    (21, 0x1fffde),
    (23, 0x7fffea),    (22, 0x3fffdd),    (22, 0x3fffde),    (24, 0xfffff0),
    (21, 0x1fffdf),    (22, 0x3fffdf),    (23, 0x7fffeb),    (23, 0x7fffec),
    (21, 0x1fffe0),    (21, 0x1fffe1),    (22, 0x3fffe0),    (21, 0x1fffe2),
    (23, 0x7fffed),    (22, 0x3fffe1),    (23, 0x7fffee),    (23, 0x7fffef),
    (20, 0xfffea),    (22, 0x3fffe2),    (22, 0x3fffe3),    (22, 0x3fffe4),
    (23, 0x7ffff0),    (22, 0x3fffe5),    (22, 0x3fffe6),    (23, 0x7ffff1),
    (26, 0x3ffffe0),    (26, 0x3ffffe1),    (20, 0xfffeb),    (19, 0x7fff1),
    (22, 0x3fffe7),    (23, 0x7ffff2),    (22, 0x3fffe8),    (25, 0x1ffffec),
    (26, 0x3ffffe2),    (26, 0x3ffffe3),    (26, 0x3ffffe4),    (27, 0x7ffffde),
    (27, 0x7ffffdf),    (26, 0x3ffffe5),    (24, 0xfffff1),    (25, 0x1ffffed),
    (19, 0x7fff2),    (21, 0x1fffe3),    (26, 0x3ffffe6),    (27, 0x7ffffe0),
    (27, 0x7ffffe1),    (26, 0x3ffffe7),    (27, 0x7ffffe2),    (24, 0xfffff2),
    (21, 0x1fffe4),    (21, 0x1fffe5),    (26, 0x3ffffe8),    (26, 0x3ffffe9),
    (28, 0xffffffd),    (27, 0x7ffffe3),    (27, 0x7ffffe4),    (27, 0x7ffffe5),
    (20, 0xfffec),    (24, 0xfffff3),    (20, 0xfffed),    (21, 0x1fffe6),
    (22, 0x3fffe9),    (21, 0x1fffe7),    (21, 0x1fffe8),    (23, 0x7ffff3),
    (22, 0x3fffea),    (22, 0x3fffeb),    (25, 0x1ffffee),    (25, 0x1ffffef),
    (24, 0xfffff4),    (24, 0xfffff5),    (26, 0x3ffffea),    (23, 0x7ffff4),
    (26, 0x3ffffeb),    (27, 0x7ffffe6),    (26, 0x3ffffec),    (26, 0x3ffffed),
    (27, 0x7ffffe7),    (27, 0x7ffffe8),    (27, 0x7ffffe9),    (27, 0x7ffffea),
    (27, 0x7ffffeb),    (28, 0xffffffe),    (27, 0x7ffffec),    (27, 0x7ffffed),
    (27, 0x7ffffee),    (27, 0x7ffffef),    (27, 0x7fffff0),    (26, 0x3ffffee),
    (30, 0x3fffffff),
];

#[derive(Clone, Copy, Debug)]
pub struct HuffmanNode {
    pub children: [u16; 2],
    pub symbol: Option<u16>,
}

pub static HUFFMAN_TREE: Lazy<Vec<HuffmanNode>> = Lazy::new(|| {
    let mut tree = vec![HuffmanNode {
        children: [0, 0],
        symbol: None,
    }];

    for (sym, &(nbits, code)) in HUFFMAN_CODE_TABLE.iter().enumerate() {
        let mut curr = 0usize;
        for bit_pos in (0..nbits).rev() {
            let bit = ((code >> bit_pos) & 1) as usize;
            let mut next = tree[curr].children[bit] as usize;
            if next == 0 {
                next = tree.len();
                tree.push(HuffmanNode {
                    children: [0, 0],
                    symbol: None,
                });
                tree[curr].children[bit] = next as u16;
            }
            curr = next;
        }
        tree[curr].symbol = Some(sym as u16);
    }

    tree
});

pub fn qpack_huffman_decode(data: &[u8]) -> Result<Vec<u8>, String> {
    let tree = &*HUFFMAN_TREE;
    let mut out = Vec::with_capacity(data.len());
    let mut current_idx = 0usize;
    let mut bits_in_current_symbol = 0usize;

    for &byte in data {
        for bit_pos in (0..8).rev() {
            let bit = ((byte >> bit_pos) & 1) as usize;
            let next_idx = tree[current_idx].children[bit] as usize;
            if next_idx == 0 {
                return Err("invalid Huffman code sequence".to_string());
            }
            current_idx = next_idx;
            bits_in_current_symbol += 1;

            if let Some(sym) = tree[current_idx].symbol {
                if sym == 256 {
                    return Err("Huffman stream contains EOS symbol".to_string());
                }
                out.push(sym as u8);
                current_idx = 0;
                bits_in_current_symbol = 0;
            }
        }
    }

    if current_idx != 0 {
        if bits_in_current_symbol > 7 {
            return Err("Huffman padding longer than 7 bits".to_string());
        }
        let mut test_idx = 0usize;
        for _ in 0..bits_in_current_symbol {
            test_idx = tree[test_idx].children[1] as usize;
            if test_idx == 0 {
                return Err("invalid Huffman padding sequence".to_string());
            }
        }
        if test_idx != current_idx {
            return Err("Huffman padding contains zero bit".to_string());
        }
    }

    Ok(out)
}

pub fn qpack_huffman_encode(data: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    let mut current_byte = 0u8;
    let mut bits_left = 8usize;

    for &byte in data {
        let (nbits, code) = HUFFMAN_CODE_TABLE[byte as usize];
        let mut rem_bits = nbits as usize;
        while rem_bits > 0 {
            if bits_left == 0 {
                out.push(current_byte);
                current_byte = 0;
                bits_left = 8;
            }
            let to_write = rem_bits.min(bits_left);
            let shift = rem_bits - to_write;
            let mask = ((1u32 << to_write) - 1) as u8;
            let bits = ((code >> shift) as u8) & mask;
            current_byte |= bits << (bits_left - to_write);
            bits_left -= to_write;
            rem_bits -= to_write;
        }
    }

    if bits_left < 8 {
        let pad_mask = ((1u32 << bits_left) - 1) as u8;
        current_byte |= pad_mask;
        out.push(current_byte);
    }

    out
}

pub fn decode_qpack_int(data: &[u8], prefix_bits: u8) -> Result<(u64, usize), String> {
    if data.is_empty() {
        return Err("truncated QPACK integer: empty slice".to_string());
    }
    if prefix_bits == 0 || prefix_bits > 8 {
        return Err(format!("invalid prefix_bits: {}", prefix_bits));
    }
    let max_prefix = (1u64 << prefix_bits) - 1;
    let mut val = (data[0] & (max_prefix as u8)) as u64;
    if val < max_prefix {
        return Ok((val, 1));
    }

    let mut shift = 0;
    let mut offset = 1;

    while offset < data.len() {
        let b = data[offset];
        offset += 1;

        let part = (b & 0x7F) as u64;
        if shift >= 63 {
            return Err("QPACK integer overflow".to_string());
        }
        val = val
            .checked_add(part << shift)
            .ok_or_else(|| "QPACK integer overflow".to_string())?;

        if (b & 0x80) == 0 {
            return Ok((val, offset));
        }
        shift += 7;
    }

    Err("truncated QPACK integer: missing continuation byte".to_string())
}

pub fn decode_qpack_string(data: &[u8]) -> Result<(Vec<u8>, usize), String> {
    if data.is_empty() {
        return Err("truncated QPACK string: empty slice".to_string());
    }
    let is_huffman = (data[0] & 0x80) != 0;
    let (str_len, int_len) = decode_qpack_int(data, 7)?;
    let str_len = str_len as usize;

    if data.len() < int_len + str_len {
        return Err(format!(
            "truncated QPACK string data: need {} bytes, have {}",
            int_len + str_len,
            data.len()
        ));
    }

    let raw_bytes = &data[int_len..int_len + str_len];
    let decoded = if is_huffman {
        qpack_huffman_decode(raw_bytes)?
    } else {
        raw_bytes.to_vec()
    };

    Ok((decoded, int_len + str_len))
}

pub fn get_qpack_static_status(index: u64) -> Option<u16> {
    match index {
        24 => Some(103),
        25 => Some(200),
        26 => Some(304),
        27 => Some(404),
        28 => Some(503),
        63 => Some(100),
        64 => Some(204),
        65 => Some(206),
        66 => Some(302),
        67 => Some(400),
        68 => Some(403),
        69 => Some(421),
        70 => Some(425),
        71 => Some(500),
        _ => None,
    }
}

pub fn is_qpack_static_status_name(index: u64) -> bool {
    matches!(index, 24..=28 | 63..=71)
}

/// Parses the `:status` pseudo-header from an HTTP/3 HEADERS frame QPACK payload according to RFC 9204 & RFC 9114.
/// Strict validation:
/// - Decodes QPACK prefix (Required Insert Count & Delta Base)
/// - Parses Field Lines (Static table indices, literal field lines with name reference, and literal field lines with literal names)
/// - Supports both raw ASCII and RFC 7541 / RFC 9204 Huffman-encoded values
/// - Enforces exactly one valid `:status` pseudo-header (100..=599)
/// - Rejects duplicate, missing, or malformed `:status` headers
pub fn parse_h3_response_status(qpack_payload: &[u8]) -> Result<u16, String> {
    if qpack_payload.len() < 2 {
        return Err("QPACK payload too short for field section prefix".to_string());
    }
    let mut offset = 0;

    // 1. Required Insert Count (8-bit prefix, RFC 9204 Section 4.5.1)
    let (_ric, ric_len) = decode_qpack_int(&qpack_payload[offset..], 8)?;
    offset += ric_len;

    if offset >= qpack_payload.len() {
        return Err("QPACK payload truncated after Required Insert Count".to_string());
    }

    // 2. Delta Base (Sign bit in bit 7, 7-bit prefix integer, RFC 9204 Section 4.5.1)
    let _sign = (qpack_payload[offset] & 0x80) != 0;
    let (_delta_base, db_len) = decode_qpack_int(&qpack_payload[offset..], 7)?;
    offset += db_len;

    let mut found_status: Option<u16> = None;

    while offset < qpack_payload.len() {
        let first = qpack_payload[offset];

        // Section 4.5.2: Indexed Field Line (1 T Index(6+))
        if (first & 0x80) != 0 {
            let is_static = (first & 0x40) != 0;
            let (index, consumed) = decode_qpack_int(&qpack_payload[offset..], 6)?;
            offset += consumed;

            if is_static {
                if let Some(status) = get_qpack_static_status(index) {
                    if found_status.is_some() {
                        return Err("duplicate :status pseudo-header field line".to_string());
                    }
                    found_status = Some(status);
                }
            }
        }
        // Section 4.5.4: Literal Field Line with Name Reference (01 N T NameIndex(4+))
        else if (first & 0xC0) == 0x40 {
            let is_static = (first & 0x10) != 0;
            let (name_index, consumed_name) = decode_qpack_int(&qpack_payload[offset..], 4)?;
            offset += consumed_name;

            let (value_bytes, consumed_val) = decode_qpack_string(&qpack_payload[offset..])?;
            offset += consumed_val;

            if is_static && is_qpack_static_status_name(name_index) {
                let status_str = std::str::from_utf8(&value_bytes)
                    .map_err(|_| "invalid UTF-8 in :status value".to_string())?;
                let status_code = status_str
                    .parse::<u16>()
                    .map_err(|_| format!("non-numeric :status value: {}", status_str))?;
                if !(100..=599).contains(&status_code) {
                    return Err(format!("out-of-range :status value: {}", status_code));
                }
                if found_status.is_some() {
                    return Err("duplicate :status pseudo-header field line".to_string());
                }
                found_status = Some(status_code);
            }
        }
        // Section 4.5.6: Literal Field Line with Literal Name (001 N H NameLen(3+))
        else if (first & 0xE0) == 0x20 {
            let is_name_huffman = (first & 0x08) != 0;
            let (name_len, consumed_name_int) = decode_qpack_int(&qpack_payload[offset..], 3)?;
            offset += consumed_name_int;
            let name_len = name_len as usize;

            if qpack_payload.len() < offset + name_len {
                return Err("truncated QPACK literal name".to_string());
            }
            let raw_name = &qpack_payload[offset..offset + name_len];
            let name_bytes = if is_name_huffman {
                qpack_huffman_decode(raw_name)?
            } else {
                raw_name.to_vec()
            };
            offset += name_len;

            let (value_bytes, consumed_val) = decode_qpack_string(&qpack_payload[offset..])?;
            offset += consumed_val;

            if name_bytes == b":status" {
                let status_str = std::str::from_utf8(&value_bytes)
                    .map_err(|_| "invalid UTF-8 in :status value".to_string())?;
                let status_code = status_str
                    .parse::<u16>()
                    .map_err(|_| format!("non-numeric :status value: {}", status_str))?;
                if !(100..=599).contains(&status_code) {
                    return Err(format!("out-of-range :status value: {}", status_code));
                }
                if found_status.is_some() {
                    return Err("duplicate :status pseudo-header field line".to_string());
                }
                found_status = Some(status_code);
            }
        }
        // Section 4.5.3: Indexed Field Line with Post-Base Index (0001 Index(4+))
        else if (first & 0xF0) == 0x10 {
            let (_index, consumed) = decode_qpack_int(&qpack_payload[offset..], 4)?;
            offset += consumed;
        }
        // Section 4.5.5: Literal Field Line with Post-Base Name Reference (0000 N NameIdx(3+))
        else if (first & 0xF0) == 0x00 {
            let (_name_idx, consumed_name) = decode_qpack_int(&qpack_payload[offset..], 3)?;
            offset += consumed_name;
            let (_value_bytes, consumed_val) = decode_qpack_string(&qpack_payload[offset..])?;
            offset += consumed_val;
        } else {
            return Err(format!("unknown QPACK field line pattern: 0x{:02x}", first));
        }
    }

    found_status.ok_or_else(|| "missing :status pseudo-header in H3 HEADERS frame".to_string())
}

// ---------------------------------------------------------------------------
// H3FrameReader: De-frames HTTP/3 DATA frames (Type 0x00) with buffer persistence
// ---------------------------------------------------------------------------

pub struct H3FrameReader {
    recv_stream: quinn::RecvStream,
    read_buf: Vec<u8>,
    remaining_frame_len: usize,
    current_frame_is_data: bool,
}

impl H3FrameReader {
    pub fn new_with_buffer(recv_stream: quinn::RecvStream, initial_buf: Vec<u8>) -> Self {
        Self {
            recv_stream,
            read_buf: initial_buf,
            remaining_frame_len: 0,
            current_frame_is_data: false,
        }
    }

    pub async fn read_data(&mut self, out: &mut [u8]) -> Result<usize, std::io::Error> {
        if out.is_empty() {
            return Ok(0);
        }
        let mut temp_chunk = [0u8; 16 * 1024];
        loop {
            // 1. If currently inside a DATA frame payload:
            if self.current_frame_is_data && self.remaining_frame_len > 0 {
                if !self.read_buf.is_empty() {
                    let to_copy = self
                        .read_buf
                        .len()
                        .min(self.remaining_frame_len)
                        .min(out.len());
                    out[..to_copy].copy_from_slice(&self.read_buf[..to_copy]);
                    self.read_buf.drain(..to_copy);
                    self.remaining_frame_len -= to_copy;
                    if self.remaining_frame_len == 0 {
                        self.current_frame_is_data = false;
                    }
                    return Ok(to_copy);
                }

                let read_limit = temp_chunk.len().min(self.remaining_frame_len);
                match self.recv_stream.read(&mut temp_chunk[..read_limit]).await {
                    Ok(Some(n)) if n > 0 => {
                        let to_copy = n.min(out.len());
                        out[..to_copy].copy_from_slice(&temp_chunk[..to_copy]);
                        if n > to_copy {
                            let excess = n - to_copy;
                            if self.read_buf.len().saturating_add(excess) > MAX_H3_BUFFER_SIZE {
                                return Err(std::io::Error::new(
                                    std::io::ErrorKind::InvalidData,
                                    format!("HTTP/3 buffer exceeded limit {}", MAX_H3_BUFFER_SIZE),
                                ));
                            }
                            self.read_buf.extend_from_slice(&temp_chunk[to_copy..n]);
                        }
                        self.remaining_frame_len -= n;
                        if self.remaining_frame_len == 0 {
                            self.current_frame_is_data = false;
                        }
                        return Ok(to_copy);
                    }
                    Ok(Some(_)) | Ok(None) => {
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::UnexpectedEof,
                            "truncated HTTP/3 DATA frame payload at stream EOF",
                        ));
                    }
                    Err(e) => {
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::Other,
                            e.to_string(),
                        ));
                    }
                }
            }

            // 2. If skipping a non-DATA frame payload:
            if !self.current_frame_is_data && self.remaining_frame_len > 0 {
                if !self.read_buf.is_empty() {
                    let to_drain = self.read_buf.len().min(self.remaining_frame_len);
                    self.read_buf.drain(..to_drain);
                    self.remaining_frame_len -= to_drain;
                    continue;
                }
                let skip_limit = temp_chunk.len().min(self.remaining_frame_len);
                match self.recv_stream.read(&mut temp_chunk[..skip_limit]).await {
                    Ok(Some(n)) if n > 0 => {
                        self.remaining_frame_len -= n;
                        continue;
                    }
                    Ok(Some(_)) | Ok(None) => {
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::UnexpectedEof,
                            "truncated HTTP/3 non-DATA frame payload at stream EOF",
                        ));
                    }
                    Err(e) => {
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::Other,
                            e.to_string(),
                        ));
                    }
                }
            }

            // 3. At frame boundary: decode frame_type and frame_len
            if let Some((ftype, tlen)) = decode_varint(&self.read_buf) {
                if let Some((flen_u64, llen)) = decode_varint(&self.read_buf[tlen..]) {
                    let flen = usize::try_from(flen_u64).map_err(|_| {
                        std::io::Error::new(
                            std::io::ErrorKind::InvalidData,
                            format!("HTTP/3 frame length {} exceeds addressable memory", flen_u64),
                        )
                    })?;
                    if flen > MAX_H3_DATA_FRAME_PAYLOAD_SIZE {
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::InvalidData,
                            format!(
                                "HTTP/3 frame length {} exceeds maximum limit {}",
                                flen, MAX_H3_DATA_FRAME_PAYLOAD_SIZE
                            ),
                        ));
                    }
                    let header_len = tlen.checked_add(llen).ok_or_else(|| {
                        std::io::Error::new(
                            std::io::ErrorKind::InvalidData,
                            "integer overflow in HTTP/3 frame header length",
                        )
                    })?;
                    self.read_buf.drain(..header_len);
                    self.remaining_frame_len = flen;
                    self.current_frame_is_data = ftype == H3_FRAME_DATA;
                    continue;
                }
            }

            // Enforce buffer size before accumulating more bytes
            if self.read_buf.len() >= MAX_H3_BUFFER_SIZE {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    format!("HTTP/3 read_buf exceeded limit {}", MAX_H3_BUFFER_SIZE),
                ));
            }

            // Need more bytes to decode next frame header
            match self.recv_stream.read(&mut temp_chunk).await {
                Ok(Some(n)) if n > 0 => {
                    if self.read_buf.len().saturating_add(n) > MAX_H3_BUFFER_SIZE {
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::InvalidData,
                            format!("HTTP/3 read_buf would exceed limit {}", MAX_H3_BUFFER_SIZE),
                        ));
                    }
                    self.read_buf.extend_from_slice(&temp_chunk[..n]);
                }
                Ok(Some(_)) | Ok(None) => {
                    if !self.read_buf.is_empty() {
                        // Incomplete frame header at stream EOF: never inject as user payload!
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::UnexpectedEof,
                            "truncated HTTP/3 frame header at stream EOF",
                        ));
                    }
                    return Ok(0);
                }
                Err(e) => {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::Other,
                        e.to_string(),
                    ));
                }
            }
        }
    }
}

pub(crate) fn allocate_connect_step_budget(
    remaining_budget: Duration,
    remaining_fallbacks: usize,
    min_fallback_reserve: Duration,
    max_step_budget: Duration,
) -> Duration {
    let reserved = min_fallback_reserve.saturating_mul(remaining_fallbacks as u32);
    let available_for_step = remaining_budget.saturating_sub(reserved);
    if available_for_step.is_zero() {
        let steps = (remaining_fallbacks + 1) as u32;
        (remaining_budget / steps).max(Duration::from_millis(150))
    } else {
        available_for_step.min(max_step_budget).max(Duration::from_millis(200))
    }
}

async fn read_h3_headers_response_with_cancel(
    recv_stream: &mut quinn::RecvStream,
    timeout: Duration,
    cancel: Option<&CancellationToken>,
) -> Result<(u16, Vec<u8>), String> {
    let mut header_buf = Vec::with_capacity(2048);
    let mut temp = [0u8; 1024];

    let read_fut = async {
        loop {
            if let Some((ftype, tlen)) = decode_varint(&header_buf) {
                if ftype != H3_FRAME_HEADERS {
                    return Err(format!(
                        "expected H3 HEADERS frame (0x01), got 0x{:02x}",
                        ftype
                    ));
                }
                if let Some((flen, llen)) = decode_varint(&header_buf[tlen..]) {
                    if flen > 64 * 1024 {
                        return Err(format!(
                            "H3 response headers frame length ({} bytes) exceeded 64KB limit",
                            flen
                        ));
                    }
                    let flen_usize = usize::try_from(flen).map_err(|_| {
                        "H3 response headers length exceeds addressable memory".to_string()
                    })?;
                    let header_len = tlen.checked_add(llen).ok_or_else(|| {
                        "overflow in H3 response headers length".to_string()
                    })?;
                    let total_needed = header_len.checked_add(flen_usize).ok_or_else(|| {
                        "overflow in H3 response headers total length".to_string()
                    })?;
                    if header_buf.len() >= total_needed {
                        let payload = &header_buf[header_len..total_needed];
                        let status = parse_h3_response_status(payload)?;

                        // Handle 1xx informational responses per RFC 9114 Section 4.1:
                        // skip intermediate headers and wait for final response
                        if (100..200).contains(&status) {
                            header_buf.drain(..total_needed);
                            continue;
                        }

                        let leftovers = header_buf[total_needed..].to_vec();
                        return Ok((status, leftovers));
                    }
                }
            }

            match recv_stream.read(&mut temp).await {
                Ok(Some(n)) if n > 0 => {
                    header_buf.extend_from_slice(&temp[..n]);
                    if header_buf.len() > 64 * 1024 {
                        return Err("H3 response headers exceeded 64KB".to_string());
                    }
                }
                Ok(Some(_)) | Ok(None) => {
                    return Err("stream closed while waiting for H3 response headers".to_string());
                }
                Err(e) => {
                    return Err(format!("error reading H3 response headers: {}", e));
                }
            }
        }
    };

    let read_with_cancel = async {
        if let Some(c) = cancel {
            tokio::select! {
                _ = c.cancelled() => Err("operation cancelled".to_string()),
                res = read_fut => res,
            }
        } else {
            read_fut.await
        }
    };

    match tokio::time::timeout(timeout, read_with_cancel).await {
        Ok(res) => res,
        Err(_) => Err(format!("timeout waiting for H3 response headers after {:?}", timeout)),
    }
}

#[allow(dead_code)]
async fn read_h3_headers_response(
    recv_stream: &mut quinn::RecvStream,
    timeout: Duration,
) -> Result<(u16, Vec<u8>), String> {
    read_h3_headers_response_with_cancel(recv_stream, timeout, None).await
}

// ---------------------------------------------------------------------------
// VirtualTunDevice: In-Memory smoltcp Device for Layer 3 IP Packet Processing
// ---------------------------------------------------------------------------

pub struct VirtualTunDevice {
    pub rx_queue: std::collections::VecDeque<Vec<u8>>,
    pub tx_queue: std::collections::VecDeque<Vec<u8>>,
    pub mtu: usize,
}

impl VirtualTunDevice {
    pub const DEFAULT_MTU: usize = 1380;

    pub fn new() -> Self {
        Self::new_with_mtu(Self::DEFAULT_MTU)
    }

    pub fn new_with_mtu(mtu: usize) -> Self {
        Self {
            rx_queue: std::collections::VecDeque::with_capacity(64),
            tx_queue: std::collections::VecDeque::with_capacity(64),
            mtu,
        }
    }

    #[inline]
    pub fn mtu(&self) -> usize {
        self.mtu
    }

    #[inline]
    pub fn set_mtu(&mut self, mtu: usize) {
        self.mtu = mtu;
    }
}

impl Default for VirtualTunDevice {
    fn default() -> Self {
        Self::new()
    }
}

pub struct VirtualRxToken(pub Vec<u8>);

impl RxToken for VirtualRxToken {
    fn consume<R, F>(mut self, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        f(&mut self.0)
    }
}

pub struct VirtualTxToken<'a>(pub &'a mut std::collections::VecDeque<Vec<u8>>);

impl<'a> TxToken for VirtualTxToken<'a> {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        let mut buf = vec![0u8; len];
        let res = f(&mut buf);
        self.0.push_back(buf);
        res
    }
}

impl Device for VirtualTunDevice {
    type RxToken<'a>
        = VirtualRxToken
    where
        Self: 'a;
    type TxToken<'a>
        = VirtualTxToken<'a>
    where
        Self: 'a;

    fn receive(
        &mut self,
        _timestamp: SmolInstant,
    ) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        self.rx_queue
            .pop_front()
            .map(|pkt| (VirtualRxToken(pkt), VirtualTxToken(&mut self.tx_queue)))
    }

    fn transmit(&mut self, _timestamp: SmolInstant) -> Option<Self::TxToken<'_>> {
        Some(VirtualTxToken(&mut self.tx_queue))
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut caps = DeviceCapabilities::default();
        caps.medium = Medium::Ip;
        caps.max_transmission_unit = self.mtu;
        caps
    }
}

/// Computes the 16-bit one's complement Internet Checksum (RFC 1071).
pub fn calc_internet_checksum(data: &[u8]) -> u16 {
    let mut sum = 0u32;
    let mut i = 0;
    while i + 1 < data.len() {
        let word = u16::from_be_bytes([data[i], data[i + 1]]);
        sum = sum.wrapping_add(word as u32);
        i += 2;
    }
    if i < data.len() {
        let word = u16::from_be_bytes([data[i], 0]);
        sum = sum.wrapping_add(word as u32);
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

/// Builds an ICMPv4 Destination Unreachable (Fragmentation Needed and DF set, Code 4)
/// packet per RFC 792 and RFC 1191.
///
/// When an outgoing IPv4 packet exceeds the path datagram budget, this ICMP packet
/// is injected into the local smoltcp stack. smoltcp parses Code 4, extracts the Next-Hop MTU,
/// and immediately adapts its TCP socket MSS without dropping connections or stalling.
pub fn build_icmpv4_fragmentation_needed(
    router_ip: std::net::Ipv4Addr,
    client_ip: std::net::Ipv4Addr,
    next_hop_mtu: u16,
    invoking_pkt: &[u8],
) -> Vec<u8> {
    let payload_len = invoking_pkt.len().min(548);
    let icmp_len = 8 + payload_len;
    let total_len = 20 + icmp_len;

    let mut pkt = vec![0u8; total_len];

    // IPv4 Header (20 bytes)
    pkt[0] = 0x45; // Version 4, IHL 5 (20 bytes)
    pkt[1] = 0x00; // DSCP/ECN
    pkt[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
    pkt[4..6].copy_from_slice(&[0x00, 0x00]); // ID
    pkt[6..8].copy_from_slice(&[0x00, 0x00]); // Flags / Frag offset
    pkt[8] = 64; // TTL
    pkt[9] = 1; // Protocol: ICMP (1)
    pkt[10..12].copy_from_slice(&[0x00, 0x00]); // Checksum placeholder
    pkt[12..16].copy_from_slice(&router_ip.octets());
    pkt[16..20].copy_from_slice(&client_ip.octets());

    let ip_cksum = calc_internet_checksum(&pkt[0..20]);
    pkt[10..12].copy_from_slice(&ip_cksum.to_be_bytes());

    // ICMPv4 Header (8 bytes) + Invoking payload
    pkt[20] = 3; // Type: Destination Unreachable
    pkt[21] = 4; // Code: Fragmentation Needed and DF set
    pkt[22..24].copy_from_slice(&[0x00, 0x00]); // Checksum placeholder
    pkt[24..26].copy_from_slice(&[0x00, 0x00]); // Unused (0)
    pkt[26..28].copy_from_slice(&next_hop_mtu.to_be_bytes()); // Next-Hop MTU (RFC 1191)

    pkt[28..28 + payload_len].copy_from_slice(&invoking_pkt[..payload_len]);

    let icmp_cksum = calc_internet_checksum(&pkt[20..]);
    pkt[22..24].copy_from_slice(&icmp_cksum.to_be_bytes());

    pkt
}

/// Builds an ICMPv6 Packet Too Big (Type 2, Code 0) packet per RFC 4443 and RFC 8200.
///
/// When an outgoing IPv6 packet exceeds the path datagram budget, this ICMPv6 packet
/// is injected into the local smoltcp stack. smoltcp parses Type 2, extracts the MTU,
/// and immediately adapts its TCP socket MSS.
pub fn build_icmpv6_packet_too_big(
    router_ip: std::net::Ipv6Addr,
    client_ip: std::net::Ipv6Addr,
    mtu: u32,
    invoking_pkt: &[u8],
) -> Vec<u8> {
    let payload_len = invoking_pkt.len().min(1232);
    let icmp_len = 8 + payload_len;
    let total_len = 40 + icmp_len;

    let mut pkt = vec![0u8; total_len];

    // IPv6 Header (40 bytes)
    pkt[0..4].copy_from_slice(&[0x60, 0x00, 0x00, 0x00]); // Version 6, TC 0, Flow Label 0
    pkt[4..6].copy_from_slice(&(icmp_len as u16).to_be_bytes()); // Payload Length
    pkt[6] = 58; // Next Header: ICMPv6 (58)
    pkt[7] = 64; // Hop Limit
    pkt[8..24].copy_from_slice(&router_ip.octets());
    pkt[24..40].copy_from_slice(&client_ip.octets());

    // ICMPv6 Body (8 bytes header + payload)
    pkt[40] = 2; // Type: Packet Too Big
    pkt[41] = 0; // Code: 0
    pkt[42..44].copy_from_slice(&[0x00, 0x00]); // Checksum placeholder
    pkt[44..48].copy_from_slice(&mtu.to_be_bytes()); // MTU (RFC 4443)

    pkt[48..48 + payload_len].copy_from_slice(&invoking_pkt[..payload_len]);

    // RFC 4443 Section 2.3: ICMPv6 checksum includes IPv6 pseudo-header
    let mut pseudo = Vec::with_capacity(40 + icmp_len);
    pseudo.extend_from_slice(&router_ip.octets()); // Source IP
    pseudo.extend_from_slice(&client_ip.octets()); // Dest IP
    pseudo.extend_from_slice(&(icmp_len as u32).to_be_bytes()); // Upper-layer packet length
    pseudo.extend_from_slice(&[0, 0, 0, 58]); // 3 zero bytes + Next Header (58)
    pseudo.extend_from_slice(&pkt[40..]); // ICMPv6 body

    let icmp_cksum = calc_internet_checksum(&pseudo);
    pkt[42..44].copy_from_slice(&icmp_cksum.to_be_bytes());

    pkt
}

/// Helper to construct the appropriate ICMP Packet Too Big / Fragmentation Needed
/// packet based on the IP version of the invoking packet.
pub fn build_icmp_ptb_for_packet(
    target_ip: std::net::IpAddr,
    client_v4: Option<std::net::Ipv4Addr>,
    client_v6: Option<std::net::Ipv6Addr>,
    next_hop_mtu: usize,
    invoking_pkt: &[u8],
) -> Option<Vec<u8>> {
    if invoking_pkt.is_empty() {
        return None;
    }
    let version = invoking_pkt[0] >> 4;
    match version {
        4 => {
            let client = client_v4.or_else(|| {
                if invoking_pkt.len() >= 20 {
                    let mut src_bytes = [0u8; 4];
                    src_bytes.copy_from_slice(&invoking_pkt[12..16]);
                    Some(std::net::Ipv4Addr::from(src_bytes))
                } else {
                    None
                }
            })?;
            let router = match target_ip {
                std::net::IpAddr::V4(v4) => v4,
                std::net::IpAddr::V6(_) => std::net::Ipv4Addr::new(127, 0, 0, 1),
            };
            Some(build_icmpv4_fragmentation_needed(
                router,
                client,
                next_hop_mtu.clamp(576, 65535) as u16,
                invoking_pkt,
            ))
        }
        6 => {
            let client = client_v6.or_else(|| {
                if invoking_pkt.len() >= 40 {
                    let mut src_bytes = [0u8; 16];
                    src_bytes.copy_from_slice(&invoking_pkt[8..24]);
                    Some(std::net::Ipv6Addr::from(src_bytes))
                } else {
                    None
                }
            })?;
            let router = match target_ip {
                std::net::IpAddr::V6(v6) => v6,
                std::net::IpAddr::V4(_) => "::1".parse::<std::net::Ipv6Addr>().unwrap(),
            };
            Some(build_icmpv6_packet_too_big(
                router,
                client,
                next_hop_mtu.clamp(1200, 65535) as u32,
                invoking_pkt,
            ))
        }
        _ => None,
    }
}

/// Calculates allowable IP payload MTU from QUIC connection datagram capabilities,
/// deducting HTTP/3 Datagram header overhead (Quarter Stream ID + Context ID).
///
/// If QUIC path MTU (PMTU) is dynamically discovered or updated, this reflects the true budget.
/// Fallback baselines are chosen according to the outer IP transport family (IPv4 vs IPv6).
pub fn calculate_effective_mtu(
    connection: &quinn::Connection,
    quarter_stream_id: u64,
    outer_is_v6: bool,
    target_is_v6: bool,
) -> usize {
    let max_dgram = connection.max_datagram_size().unwrap_or(if outer_is_v6 {
        1200
    } else {
        1200
    });

    let h3_overhead = varint_len(quarter_stream_id).saturating_add(varint_len(0));
    let max_ip_payload = max_dgram.saturating_sub(h3_overhead);

    if target_is_v6 {
        max_ip_payload.clamp(1200, 1500)
    } else {
        max_ip_payload.clamp(576, 1500)
    }
}

/// Resolves a target address string into an IP address (IPv4 or IPv6) and port.
pub async fn parse_target_endpoint(target_addr: &str) -> Result<(std::net::IpAddr, u16), String> {
    let clean = target_addr.trim();
    if clean.is_empty() {
        return Err("empty target address".to_string());
    }

    let (host_clean, port) = if clean.starts_with('[') {
        // Bracketed notation, e.g. [2001:db8::1]:443 or [2001:db8::1] or [127.0.0.1]:80
        if let Some(close_pos) = clean.find(']') {
            let host = &clean[1..close_pos];
            let rest = &clean[close_pos + 1..];
            let port = if rest.starts_with(':') {
                rest[1..].parse::<u16>().unwrap_or(443)
            } else {
                443
            };
            (host, port)
        } else {
            return Err(format!("unmatched bracket in target address: {}", clean));
        }
    } else if let Some(last_colon) = clean.rfind(':') {
        // Could be host:port OR IPv6 literal without brackets like 2001:db8::1
        let first_colon = clean.find(':').unwrap();
        if first_colon != last_colon {
            // Multiple colons without brackets -> IPv6 address
            if clean.parse::<std::net::Ipv6Addr>().is_ok() {
                (clean, 443)
            } else {
                // Try splitting if last token is numeric port
                let host_part = &clean[..last_colon];
                let port_part = &clean[last_colon + 1..];
                if let Ok(p) = port_part.parse::<u16>() {
                    if host_part.parse::<std::net::Ipv6Addr>().is_ok() {
                        (host_part, p)
                    } else {
                        return Err(format!("ambiguous IPv6 address (brackets required): {}", clean));
                    }
                } else {
                    return Err(format!("invalid IPv6 address: {}", clean));
                }
            }
        } else {
            // Single colon: host:port
            let host = &clean[..last_colon];
            let port = clean[last_colon + 1..].parse::<u16>().unwrap_or(443);
            (host, port)
        }
    } else {
        (clean, 443)
    };

    let host_trimmed = host_clean.trim_matches('[').trim_matches(']');

    // 1. Literal IPv4
    if let Ok(v4) = host_trimmed.parse::<std::net::Ipv4Addr>() {
        return Ok((std::net::IpAddr::V4(v4), port));
    }

    // 2. Literal IPv6
    if let Ok(v6) = host_trimmed.parse::<std::net::Ipv6Addr>() {
        return Ok((std::net::IpAddr::V6(v6), port));
    }

    // 3. Hostname DNS resolution
    match tokio::net::lookup_host(format!("{}:{}", host_trimmed, port)).await {
        Ok(iter) => {
            let mut first_v4 = None;
            let mut first_v6 = None;
            for addr in iter {
                match addr {
                    SocketAddr::V4(v4) => {
                        if first_v4.is_none() {
                            first_v4 = Some((std::net::IpAddr::V4(*v4.ip()), v4.port()));
                        }
                    }
                    SocketAddr::V6(v6) => {
                        if first_v6.is_none() {
                            first_v6 = Some((std::net::IpAddr::V6(*v6.ip()), v6.port()));
                        }
                    }
                }
            }
            if let Some(v4) = first_v4 {
                return Ok(v4);
            }
            if let Some(v6) = first_v6 {
                return Ok(v6);
            }
            Err(format!("no IP address resolved for host {}", host_trimmed))
        }
        Err(e) => Err(format!("DNS resolution failed for {}: {}", host_trimmed, e)),
    }
}

/// Resolves a target address string into an IPv4 address and port.
pub async fn parse_target_ipv4(target_addr: &str) -> Result<(std::net::Ipv4Addr, u16), String> {
    match parse_target_endpoint(target_addr).await? {
        (std::net::IpAddr::V4(v4), port) => Ok((v4, port)),
        (std::net::IpAddr::V6(v6), _) => {
            Err(format!("no IPv4 address resolved for host {}", v6))
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct AddressChangeSummary {
    pub v4_changed: bool,
    pub v6_changed: bool,
    pub routes_changed: bool,
}

pub fn handle_incoming_capsule(
    capsule: Capsule,
    address_state_v4: &Arc<parking_lot::RwLock<AddressState<std::net::Ipv4Addr>>>,
    address_state_v6: &Arc<parking_lot::RwLock<AddressState<std::net::Ipv6Addr>>>,
    assigned_ipv4: &Arc<parking_lot::RwLock<Option<std::net::Ipv4Addr>>>,
    assigned_ipv6: &Arc<parking_lot::RwLock<Option<std::net::Ipv6Addr>>>,
    advertised_routes: &Arc<parking_lot::RwLock<Vec<IpAddressRange>>>,
) -> AddressChangeSummary {
    let mut summary = AddressChangeSummary::default();
    match capsule {
        Capsule::AddressAssign(addrs) => {
            if addrs.is_empty() {
                // RFC 9484 §4.7.1: Empty ADDRESS_ASSIGN withdraws all previously assigned addresses
                linfo!("MASQUE: server sent empty ADDRESS_ASSIGN capsule (withdrawing all assigned addresses)");
                let old_v4 = *address_state_v4.read();
                let old_v6 = *address_state_v6.read();
                *address_state_v4.write() = AddressState::Withdrawn;
                *assigned_ipv4.write() = None;
                *address_state_v6.write() = AddressState::Withdrawn;
                *assigned_ipv6.write() = None;
                if old_v4 != AddressState::Withdrawn {
                    summary.v4_changed = true;
                }
                if old_v6 != AddressState::Withdrawn {
                    summary.v6_changed = true;
                }
                return summary;
            }
            for addr in addrs {
                match addr.ip_addr {
                    std::net::IpAddr::V4(v4) => {
                        let old_v4 = *address_state_v4.read();
                        if addr.prefix_len == 0 {
                            // RFC 9484 §4.7.1: Prefix Length 0 indicates address withdrawal
                            linfo!(
                                "MASQUE: server withdrew IPv4 {} (prefix_len = 0, req_id {})",
                                v4,
                                addr.request_id
                            );
                            *address_state_v4.write() = AddressState::Withdrawn;
                            *assigned_ipv4.write() = None;
                            if old_v4 != AddressState::Withdrawn {
                                summary.v4_changed = true;
                            }
                        } else {
                            linfo!(
                                "MASQUE: server assigned IPv4 {}/{} (req_id {})",
                                v4,
                                addr.prefix_len,
                                addr.request_id
                            );
                            *address_state_v4.write() = AddressState::Assigned(v4);
                            *assigned_ipv4.write() = Some(v4);
                            if old_v4 != AddressState::Assigned(v4) {
                                summary.v4_changed = true;
                            }
                        }
                    }
                    std::net::IpAddr::V6(v6) => {
                        let old_v6 = *address_state_v6.read();
                        if addr.prefix_len == 0 {
                            // RFC 9484 §4.7.1: Prefix Length 0 indicates address withdrawal
                            linfo!(
                                "MASQUE: server withdrew IPv6 {} (prefix_len = 0, req_id {})",
                                v6,
                                addr.request_id
                            );
                            *address_state_v6.write() = AddressState::Withdrawn;
                            *assigned_ipv6.write() = None;
                            if old_v6 != AddressState::Withdrawn {
                                summary.v6_changed = true;
                            }
                        } else {
                            linfo!(
                                "MASQUE: server assigned IPv6 {}/{} (req_id {})",
                                v6,
                                addr.prefix_len,
                                addr.request_id
                            );
                            *address_state_v6.write() = AddressState::Assigned(v6);
                            *assigned_ipv6.write() = Some(v6);
                            if old_v6 != AddressState::Assigned(v6) {
                                summary.v6_changed = true;
                            }
                        }
                    }
                }
            }
        }
        Capsule::AddressRequest(addrs) => {
            ldebug!(
                "MASQUE: received peer ADDRESS_REQUEST capsule with {} addrs",
                addrs.len()
            );
        }
        Capsule::RouteAdvertisement(routes) => {
            linfo!("MASQUE: server advertised {} route(s)", routes.len());
            for r in &routes {
                ldebug!(
                    "MASQUE: route {} - {} (proto {})",
                    r.start_ip,
                    r.end_ip,
                    r.ip_protocol
                );
            }
            if !routes.is_empty() {
                advertised_routes.write().extend(routes);
                summary.routes_changed = true;
            }
        }
        Capsule::MalformedKnown {
            capsule_type,
            error,
        } => {
            lwarn!(
                "MASQUE: rejected malformed known capsule 0x{:02x}: {}",
                capsule_type,
                error
            );
        }
        Capsule::Unknown {
            capsule_type,
            payload,
        } => {
            ldebug!(
                "MASQUE: ignored unknown capsule 0x{:02x} ({} bytes)",
                capsule_type,
                payload.len()
            );
        }
    }
    summary
}

pub fn update_smoltcp_addresses_and_routes(
    iface: &mut Interface,
    effective_v4: Option<std::net::Ipv4Addr>,
    effective_v6: Option<std::net::Ipv6Addr>,
) {
    iface.update_ip_addrs(|addrs| {
        addrs.clear();
        if let Some(v4) = effective_v4 {
            let smol_v4 = Ipv4Address::from(v4);
            let _ = addrs.push(IpCidr::Ipv4(Ipv4Cidr::new(smol_v4, 32)));
        }
        if let Some(v6) = effective_v6 {
            let smol_v6 = Ipv6Address::from(v6);
            let _ = addrs.push(IpCidr::Ipv6(Ipv6Cidr::new(smol_v6, 128)));
        }
    });

    iface.routes_mut().remove_default_ipv4_route();
    if let Some(v4) = effective_v4 {
        let smol_v4 = Ipv4Address::from(v4);
        if let Err(e) = iface.routes_mut().add_default_ipv4_route(smol_v4) {
            lwarn!("MASQUE: add_default_ipv4_route warning: {:?}", e);
        }
    }

    iface.routes_mut().remove_default_ipv6_route();
    if let Some(v6) = effective_v6 {
        let smol_v6 = Ipv6Address::from(v6);
        if let Err(e) = iface.routes_mut().add_default_ipv6_route(smol_v6) {
            lwarn!("MASQUE: add_default_ipv6_route warning: {:?}", e);
        }
    }
}

// ---------------------------------------------------------------------------
// MasqueTunnel: Bidirectional QUIC Stream Tunnel with L3 IP Datagram Encapsulation
// ---------------------------------------------------------------------------

pub struct MasqueTunnel {
    pub send_stream: tokio::sync::Mutex<quinn::SendStream>,
    pub recv_reader: tokio::sync::Mutex<Option<H3FrameReader>>,
    pub capsule_rx: tokio::sync::Mutex<Option<tokio::sync::mpsc::Receiver<Capsule>>>,
    pub address_state_v4: Arc<parking_lot::RwLock<AddressState<std::net::Ipv4Addr>>>,
    pub address_state_v6: Arc<parking_lot::RwLock<AddressState<std::net::Ipv6Addr>>>,
    pub assigned_ipv4: Arc<parking_lot::RwLock<Option<std::net::Ipv4Addr>>>,
    pub assigned_ipv6: Arc<parking_lot::RwLock<Option<std::net::Ipv6Addr>>>,
    pub advertised_routes: Arc<parking_lot::RwLock<Vec<IpAddressRange>>>,
    pub connection: quinn::Connection,
    pub endpoint_addr: SocketAddr,
    pub stream_id: quinn::StreamId,
    pub quarter_stream_id: u64,
    pub target_addr: String,
    pub is_raw_l4: bool,
    pub dgram_rx: tokio::sync::Mutex<tokio::sync::mpsc::Receiver<bytes::Bytes>>,
    pub dgram_dispatcher: DatagramDispatcher,
    pub control_stream: Arc<tokio::sync::Mutex<quinn::SendStream>>,
}

impl Drop for MasqueTunnel {
    fn drop(&mut self) {
        self.dgram_dispatcher
            .write()
            .remove(&self.quarter_stream_id);
    }
}

impl MasqueTunnel {
    pub fn effective_ipv4(&self, oob_v4: Option<std::net::Ipv4Addr>) -> Option<std::net::Ipv4Addr> {
        match *self.address_state_v4.read() {
            AddressState::Assigned(ip) => Some(ip),
            AddressState::Unassigned => oob_v4,
            AddressState::Withdrawn => None,
        }
    }

    pub fn effective_ipv6(&self, oob_v6: Option<std::net::Ipv6Addr>) -> Option<std::net::Ipv6Addr> {
        match *self.address_state_v6.read() {
            AddressState::Assigned(ip) => Some(ip),
            AddressState::Unassigned => oob_v6,
            AddressState::Withdrawn => None,
        }
    }

    pub fn from_negotiated_stream(
        send_stream: quinn::SendStream,
        recv_stream: quinn::RecvStream,
        leftovers: Vec<u8>,
        connection: quinn::Connection,
        endpoint_addr: SocketAddr,
        target_addr: String,
        variant: &MasqueConnectVariant,
        dispatcher: DatagramDispatcher,
        control_stream: Arc<tokio::sync::Mutex<quinn::SendStream>>,
    ) -> Self {
        let stream_id = send_stream.id();
        let raw_stream_id = u64::from(quinn::VarInt::from(stream_id));
        let quarter_stream_id = raw_stream_id / 4;

        let (recv_reader, capsule_rx, is_raw_l4, dgram_rx) = match variant {
            MasqueConnectVariant::StandardRfc9114L4 => {
                let (_dgram_tx, dgram_rx) = tokio::sync::mpsc::channel(MASQUE_DATAGRAM_QUEUE_CAPACITY);
                let reader = H3FrameReader::new_with_buffer(recv_stream, leftovers);
                (
                    tokio::sync::Mutex::new(Some(reader)),
                    tokio::sync::Mutex::new(None),
                    true,
                    tokio::sync::Mutex::new(dgram_rx),
                )
            }
            MasqueConnectVariant::Rfc9484ConnectIp { .. }
            | MasqueConnectVariant::LegacyCfConnectIp { .. } => {
                let (dgram_tx, dgram_rx) = tokio::sync::mpsc::channel(MASQUE_DATAGRAM_QUEUE_CAPACITY);
                dispatcher.write().insert(quarter_stream_id, dgram_tx);
                let capsule_rx = spawn_capsule_reader(recv_stream, leftovers);
                (
                    tokio::sync::Mutex::new(None),
                    tokio::sync::Mutex::new(Some(capsule_rx)),
                    false,
                    tokio::sync::Mutex::new(dgram_rx),
                )
            }
        };

        Self {
            send_stream: tokio::sync::Mutex::new(send_stream),
            recv_reader,
            capsule_rx,
            address_state_v4: Arc::new(parking_lot::RwLock::new(AddressState::Unassigned)),
            address_state_v6: Arc::new(parking_lot::RwLock::new(AddressState::Unassigned)),
            assigned_ipv4: Arc::new(parking_lot::RwLock::new(None)),
            assigned_ipv6: Arc::new(parking_lot::RwLock::new(None)),
            advertised_routes: Arc::new(parking_lot::RwLock::new(Vec::new())),
            connection,
            endpoint_addr,
            stream_id,
            quarter_stream_id,
            target_addr,
            is_raw_l4,
            dgram_rx,
            dgram_dispatcher: dispatcher,
            control_stream,
        }
    }
    pub async fn send(&self, data: &[u8]) -> Result<(), std::io::Error> {
        if data.is_empty() {
            return Ok(());
        }
        let mut frame = Vec::with_capacity(data.len() + 10);
        encode_varint(&mut frame, H3_FRAME_DATA);
        encode_varint(&mut frame, data.len() as u64);
        frame.extend_from_slice(data);
        let mut lock = self.send_stream.lock().await;
        lock.write_all(&frame)
            .await
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e.to_string()))
    }

    pub async fn recv_with_timeout(&self, timeout: Duration) -> Result<Vec<u8>, std::io::Error> {
        let mut lock = self.recv_reader.lock().await;
        if let Some(reader) = lock.as_mut() {
            let mut buf = vec![0u8; 64 * 1024];
            let res = tokio::time::timeout(timeout, reader.read_data(&mut buf)).await;
            match res {
                Ok(Ok(n)) => {
                    if n == 0 {
                        Err(std::io::Error::new(
                            std::io::ErrorKind::UnexpectedEof,
                            "quic stream closed",
                        ))
                    } else {
                        buf.truncate(n);
                        Ok(buf)
                    }
                }
                Ok(Err(e)) => Err(e),
                Err(_) => Err(std::io::Error::new(
                    std::io::ErrorKind::TimedOut,
                    "quic stream read timeout",
                )),
            }
        } else {
            Err(std::io::Error::new(
                std::io::ErrorKind::Other,
                "recv_reader not available for this tunnel mode (capsule reader active)",
            ))
        }
    }

    pub async fn close(&self) {
        self.dgram_dispatcher
            .write()
            .remove(&self.quarter_stream_id);
        let mut lock = self.send_stream.lock().await;
        let _ = lock.finish();
        // Do NOT close the underlying QUIC connection to allow multiplexing subsequent SOCKS5 requests!
    }

    /// Flushes outgoing packets from `dev.tx_queue` to the QUIC datagram channel.
    ///
    /// Validates packet length against the dynamic datagram budget (`calculate_effective_mtu`).
    /// - If a packet exceeds the budget or `send_datagram` returns `TooLarge`:
    ///   the device MTU is updated, an ICMP PTB/Fragmentation Needed packet is synthesized and injected
    ///   into `dev.rx_queue`, and `iface.poll` is called so smoltcp immediately reduces TCP MSS.
    /// - If `send_datagram` encounters `Blocked` (backpressure):
    ///   it attempts `send_datagram_wait` with a 50ms bounded timeout. If still blocked,
    ///   the packet is requeued at the front of `dev.tx_queue` without data loss.
    /// - Fatal errors (`ConnectionLost`, `UnsupportedByPeer`, `Disabled`) are propagated.
    pub async fn flush_smoltcp_tx(
        &self,
        dev: &mut VirtualTunDevice,
        iface: &mut Interface,
        sockets: &mut SocketSet<'_>,
        target_ip: std::net::IpAddr,
        current_effective_v4: Option<std::net::Ipv4Addr>,
        current_effective_v6: Option<std::net::Ipv6Addr>,
    ) -> Result<(), std::io::Error> {
        let outer_is_v6 = self.endpoint_addr.is_ipv6();
        while let Some(pkt) = dev.tx_queue.pop_front() {
            let effective_mtu = calculate_effective_mtu(
                &self.connection,
                self.quarter_stream_id,
                outer_is_v6,
                target_ip.is_ipv6(),
            );

            if pkt.len() > effective_mtu {
                dev.set_mtu(effective_mtu);
                if let Some(icmp_pkt) = build_icmp_ptb_for_packet(
                    target_ip,
                    current_effective_v4,
                    current_effective_v6,
                    effective_mtu,
                    &pkt,
                ) {
                    dev.rx_queue.push_back(icmp_pkt);
                    iface.poll(SmolInstant::now(), dev, sockets);
                }
                continue;
            }

            let dgram = encode_h3_datagram(self.quarter_stream_id, 0, &pkt);
            let bytes_dgram = bytes::Bytes::from(dgram);

            let wait_res = tokio::time::timeout(
                Duration::from_millis(50),
                self.connection.send_datagram_wait(bytes_dgram),
            )
            .await;

            match wait_res {
                Ok(Ok(())) => {}
                Ok(Err(quinn::SendDatagramError::TooLarge)) => {
                    let reduced_mtu = effective_mtu
                        .saturating_sub(64)
                        .max(if target_ip.is_ipv6() { 1200 } else { 576 });
                    dev.set_mtu(reduced_mtu);
                    if let Some(icmp_pkt) = build_icmp_ptb_for_packet(
                        target_ip,
                        current_effective_v4,
                        current_effective_v6,
                        reduced_mtu,
                        &pkt,
                    ) {
                        dev.rx_queue.push_back(icmp_pkt);
                        iface.poll(SmolInstant::now(), dev, sockets);
                    }
                    continue;
                }
                Ok(Err(quinn::SendDatagramError::ConnectionLost(e))) => {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::ConnectionReset,
                        format!("QUIC connection lost: {}", e),
                    ));
                }
                Ok(Err(quinn::SendDatagramError::UnsupportedByPeer))
                | Ok(Err(quinn::SendDatagramError::Disabled)) => {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::Unsupported,
                        "QUIC datagrams unsupported or disabled by peer",
                    ));
                }
                Err(_) => {
                    // Backpressure: queue capacity exceeded. Requeue packet at front of tx_queue without data loss.
                    dev.tx_queue.push_front(pkt);
                    break;
                }
            }
        }
        Ok(())
    }

    pub async fn run_smoltcp_bridge(
        &self,
        client: tokio::net::TcpStream,
        cancel_token: CancellationToken,
    ) -> Result<(), std::io::Error> {
        if self.is_raw_l4 {
            return self.run_raw_l4_bridge(client, cancel_token).await;
        }

        let (target_ip, target_port) = match parse_target_endpoint(&self.target_addr).await {
            Ok(res) => res,
            Err(e) => {
                lerror!(
                    "MASQUE: cannot resolve target endpoint for '{}': {}",
                    self.target_addr,
                    e
                );
                return Err(std::io::Error::new(std::io::ErrorKind::AddrNotAvailable, e));
            }
        };

        let (client_v4_opt, client_v6_opt) = {
            let cfg = WARP_CONFIG.read();
            let v4 = if !cfg.client_ipv4.trim().is_empty() {
                cfg.client_ipv4.trim().parse::<std::net::Ipv4Addr>().ok()
            } else {
                Some(std::net::Ipv4Addr::new(172, 16, 0, 2))
            };
            let v6 = if !cfg.client_ipv6.trim().is_empty() {
                cfg.client_ipv6.trim().parse::<std::net::Ipv6Addr>().ok()
            } else {
                None
            };
            (v4, v6)
        };

        // RFC 9484 §4.7.2: Client sends ADDRESS_REQUEST capsule to request or confirm
        // assigned IP addresses from the proxy.
        // NOTE: In RFC 9484, ADDRESS_ASSIGN (0x01) assigns addresses to the PEER.
        // A client sending ADDRESS_ASSIGN to the proxy attempts to assign an address to the proxy itself!
        // To request an address or propose a preferred address, ADDRESS_REQUEST (0x02) MUST be used.
        let mut req_caps = Vec::new();
        if let Some(v4) = client_v4_opt {
            req_caps.push(AssignedAddress {
                request_id: 0,
                ip_version: 4,
                ip_addr: std::net::IpAddr::V4(v4),
                prefix_len: 32,
            });
        } else {
            req_caps.push(AssignedAddress {
                request_id: 0,
                ip_version: 4,
                ip_addr: std::net::IpAddr::V4(std::net::Ipv4Addr::UNSPECIFIED),
                prefix_len: 0,
            });
        }
        if let Some(v6) = client_v6_opt {
            req_caps.push(AssignedAddress {
                request_id: 1,
                ip_version: 6,
                ip_addr: std::net::IpAddr::V6(v6),
                prefix_len: 128,
            });
        } else {
            req_caps.push(AssignedAddress {
                request_id: 1,
                ip_version: 6,
                ip_addr: std::net::IpAddr::V6(std::net::Ipv6Addr::UNSPECIFIED),
                prefix_len: 0,
            });
        }
        let initial_request = encode_address_request(&req_caps);
        let framed_request = wrap_in_h3_data_frame(&initial_request);
        {
            let mut lock = self.send_stream.lock().await;
            if let Err(e) = lock.write_all(&framed_request).await {
                lwarn!(
                    "MASQUE: failed to send client ADDRESS_REQUEST capsule: {}",
                    e
                );
            }
        }

        let mut capsule_rx = self.capsule_rx.lock().await.take();

        // 1. Initial Capsule Drain with non-zero timeout (RFC 9484 / RFC 9297)
        if let Some(ref mut rx) = capsule_rx {
            let drain_deadline = tokio::time::Instant::now() + Duration::from_millis(60);
            loop {
                let now = tokio::time::Instant::now();
                if now >= drain_deadline {
                    break;
                }
                let remaining = drain_deadline - now;
                match tokio::time::timeout(remaining, rx.recv()).await {
                    Ok(Some(capsule)) => {
                        handle_incoming_capsule(
                            capsule,
                            &self.address_state_v4,
                            &self.address_state_v6,
                            &self.assigned_ipv4,
                            &self.assigned_ipv6,
                            &self.advertised_routes,
                        );
                    }
                    _ => break,
                }
            }
        }

        let mut current_effective_v4 = self.effective_ipv4(client_v4_opt);
        let mut current_effective_v6 = self.effective_ipv6(client_v6_opt);

        ldebug!(
            "MASQUE smoltcp bridge address resolution for target {}: effective IPv4={:?}, effective IPv6={:?}",
            self.target_addr, current_effective_v4, current_effective_v6
        );

        match target_ip {
            std::net::IpAddr::V4(target_v4) => {
                if current_effective_v4.is_none() {
                    lerror!(
                        "MASQUE: target '{}' is IPv4 ({}:{}), but no client IPv4 address is configured or assigned (or address was withdrawn by proxy); aborting without direct bypass",
                        self.target_addr,
                        target_v4,
                        target_port
                    );
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::AddrNotAvailable,
                        format!("IPv4 target '{}' is unsupported: no client IPv4 configured or address withdrawn by proxy", self.target_addr),
                    ));
                }
            }
            std::net::IpAddr::V6(target_v6) => {
                if current_effective_v6.is_none() {
                    lerror!(
                        "MASQUE: target '{}' is IPv6 ([{}]:{}), but no client IPv6 address is configured or assigned; aborting without direct bypass",
                        self.target_addr,
                        target_v6,
                        target_port
                    );
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::AddrNotAvailable,
                        format!("IPv6 target '{}' is unsupported: no client IPv6 configured or assigned", self.target_addr),
                    ));
                }
            }
        }

        let outer_is_v6 = self.endpoint_addr.is_ipv6();
        let target_is_v6 = target_ip.is_ipv6();
        let initial_mtu = calculate_effective_mtu(
            &self.connection,
            self.quarter_stream_id,
            outer_is_v6,
            target_is_v6,
        );

        let mut dev = VirtualTunDevice::new_with_mtu(initial_mtu);
        let mut iface_cfg = SmolConfig::new(HardwareAddress::Ip);
        iface_cfg.random_seed = rand::random();

        let mut iface = Interface::new(iface_cfg, &mut dev, SmolInstant::now());
        update_smoltcp_addresses_and_routes(&mut iface, current_effective_v4, current_effective_v6);

        let rx_buf = tcp::SocketBuffer::new(vec![0u8; 128 * 1024]);
        let tx_buf = tcp::SocketBuffer::new(vec![0u8; 128 * 1024]);
        let mut socket = TcpSocket::new(rx_buf, tx_buf);

        let local_port = 40000 + (rand::random::<u16>() % 20000);
        let (remote_ep, local_ep) = match target_ip {
            std::net::IpAddr::V4(v4) => {
                let v4_client = current_effective_v4.unwrap();
                ldebug!(
                    "MASQUE smoltcp bridge starting (IPv4): local {} -> remote {}:{} (stream #{}, qid #{})",
                    v4_client,
                    v4,
                    target_port,
                    self.stream_id.index(),
                    self.quarter_stream_id
                );
                let smol_client_v4 = Ipv4Address::from(v4_client);
                let smol_target = Ipv4Address::from(v4);
                (
                    IpEndpoint::new(IpAddress::Ipv4(smol_target), target_port),
                    IpEndpoint::new(IpAddress::Ipv4(smol_client_v4), local_port),
                )
            }
            std::net::IpAddr::V6(v6) => {
                let c6 = current_effective_v6.unwrap();
                ldebug!(
                    "MASQUE smoltcp bridge starting (IPv6): local [{}] -> remote [{}]:{} (stream #{}, qid #{})",
                    c6,
                    v6,
                    target_port,
                    self.stream_id.index(),
                    self.quarter_stream_id
                );
                let smol_client_v6 = Ipv6Address::from(c6);
                let smol_target_v6 = Ipv6Address::from(v6);
                (
                    IpEndpoint::new(IpAddress::Ipv6(smol_target_v6), target_port),
                    IpEndpoint::new(IpAddress::Ipv6(smol_client_v6), local_port),
                )
            }
        };

        if let Err(e) = socket.connect(iface.context(), remote_ep, local_ep) {
            lerror!("MASQUE: smoltcp socket connect error: {:?}", e);
            return Err(std::io::Error::new(
                std::io::ErrorKind::ConnectionRefused,
                format!("{:?}", e),
            ));
        }

        let mut sockets = SocketSet::new(vec![]);
        let sock_handle = sockets.add(socket);

        // Initial poll to emit SYN packet into tx_queue
        iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
        self.flush_smoltcp_tx(
            &mut dev,
            &mut iface,
            &mut sockets,
            target_ip,
            current_effective_v4,
            current_effective_v6,
        )
        .await?;

        let (mut c_read, mut c_write) = client.into_split();
        let mut dgram_rx = self.dgram_rx.lock().await;

        let mut pending_client_data: Vec<u8> = Vec::with_capacity(32 * 1024);
        let mut read_temp_buf = [0u8; 16 * 1024];
        let mut recv_temp_buf = [0u8; 16 * 1024];

        let handshake_deadline = tokio::time::Instant::now() + Duration::from_secs(10);
        let mut handshake_logged = false;

        loop {
            if cancel_token.is_cancelled() {
                let socket = sockets.get_mut::<TcpSocket>(sock_handle);
                socket.abort();
                iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
                let _ = self
                    .flush_smoltcp_tx(
                        &mut dev,
                        &mut iface,
                        &mut sockets,
                        target_ip,
                        current_effective_v4,
                        current_effective_v6,
                    )
                    .await;
                break;
            }

            let mut needs_poll = false;

            // 1. Drain pending client data into smoltcp socket if socket can accept data
            {
                let socket = sockets.get_mut::<TcpSocket>(sock_handle);
                if !pending_client_data.is_empty() && socket.can_send() {
                    match socket.send_slice(&pending_client_data) {
                        Ok(n) if n > 0 => {
                            pending_client_data.drain(..n);
                            STATS.bytes_up.fetch_add(n as i64, Ordering::Relaxed);
                            needs_poll = true;
                        }
                        _ => {}
                    }
                }
            }

            // 2. Drain smoltcp socket to Telegram client c_write
            loop {
                let socket = sockets.get_mut::<TcpSocket>(sock_handle);
                if !socket.can_recv() {
                    break;
                }
                match socket.recv_slice(&mut recv_temp_buf) {
                    Ok(n) if n > 0 => {
                        STATS.bytes_down.fetch_add(n as i64, Ordering::Relaxed);
                        if let Err(e) = c_write.write_all(&recv_temp_buf[..n]).await {
                            ldebug!("MASQUE: client write error: {}", e);
                            let socket = sockets.get_mut::<TcpSocket>(sock_handle);
                            socket.abort();
                            return Err(e);
                        }
                        needs_poll = true;
                    }
                    _ => break,
                }
            }

            // 3. Poll if state changed
            if needs_poll {
                iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
                self.flush_smoltcp_tx(
                    &mut dev,
                    &mut iface,
                    &mut sockets,
                    target_ip,
                    current_effective_v4,
                    current_effective_v6,
                )
                .await?;
            }

            // 4. Check TCP socket state
            let (state, may_send) = {
                let socket = sockets.get_mut::<TcpSocket>(sock_handle);
                (socket.state(), socket.may_send())
            };

            if state == TcpState::Established && !handshake_logged {
                ldebug!(
                    "MASQUE smoltcp: TCP connection established to {}",
                    self.target_addr
                );
                handshake_logged = true;
            }

            if state == TcpState::Closed {
                ldebug!("MASQUE smoltcp: TCP socket closed gracefully");
                break;
            }

            if state == TcpState::CloseWait && pending_client_data.is_empty() {
                let socket = sockets.get_mut::<TcpSocket>(sock_handle);
                socket.close();
                iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
                self.flush_smoltcp_tx(
                    &mut dev,
                    &mut iface,
                    &mut sockets,
                    target_ip,
                    current_effective_v4,
                    current_effective_v6,
                )
                .await?;
            }

            if !handshake_logged && tokio::time::Instant::now() > handshake_deadline {
                lwarn!(
                    "MASQUE smoltcp: handshake timeout (10s) to {}",
                    self.target_addr
                );
                let socket = sockets.get_mut::<TcpSocket>(sock_handle);
                socket.abort();
                return Err(std::io::Error::new(
                    std::io::ErrorKind::TimedOut,
                    "smoltcp handshake timeout",
                ));
            }

            // 5. Select on events
            let can_read_client = pending_client_data.len() < 64 * 1024 && may_send;
            let poll_delay = iface.poll_delay(SmolInstant::now(), &sockets);
            let sleep_dur = match poll_delay {
                Some(d) => Duration::from_micros(d.total_micros())
                    .clamp(Duration::from_millis(1), Duration::from_millis(300)),
                None => Duration::from_millis(300),
            };

            tokio::select! {
                _ = cancel_token.cancelled() => {
                    let socket = sockets.get_mut::<TcpSocket>(sock_handle);
                    socket.abort();
                    break;
                }
                res = c_read.read(&mut read_temp_buf), if can_read_client => {
                    match res {
                        Ok(0) => {
                            let socket = sockets.get_mut::<TcpSocket>(sock_handle);
                            socket.close();
                            iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
                            self.flush_smoltcp_tx(
                                &mut dev,
                                &mut iface,
                                &mut sockets,
                                target_ip,
                                current_effective_v4,
                                current_effective_v6,
                            ).await?;
                        }
                        Ok(n) => {
                            pending_client_data.extend_from_slice(&read_temp_buf[..n]);
                            let socket = sockets.get_mut::<TcpSocket>(sock_handle);
                            if socket.can_send() {
                                if let Ok(sent) = socket.send_slice(&pending_client_data) {
                                    if sent > 0 {
                                        pending_client_data.drain(..sent);
                                        STATS.bytes_up.fetch_add(sent as i64, Ordering::Relaxed);
                                    }
                                }
                            }
                            iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
                            self.flush_smoltcp_tx(
                                &mut dev,
                                &mut iface,
                                &mut sockets,
                                target_ip,
                                current_effective_v4,
                                current_effective_v6,
                            ).await?;
                        }
                        Err(e) => {
                            ldebug!("MASQUE: client read error: {}", e);
                            let socket = sockets.get_mut::<TcpSocket>(sock_handle);
                            socket.abort();
                            return Err(e);
                        }
                    }
                }
                dgram_res = dgram_rx.recv() => {
                    match dgram_res {
                        Some(ip_pkt) => {
                            if dev.rx_queue.len() < MAX_TUN_RX_QUEUE {
                                dev.rx_queue.push_back(ip_pkt.to_vec());
                            } else {
                                DATAGRAM_DROPPED_OVERFLOW.fetch_add(1, Ordering::Relaxed);
                                ldebug!(
                                    "MASQUE: VirtualTunDevice rx_queue at capacity ({}), dropping datagram",
                                    MAX_TUN_RX_QUEUE
                                );
                            }
                            iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
                            self.flush_smoltcp_tx(
                                &mut dev,
                                &mut iface,
                                &mut sockets,
                                target_ip,
                                current_effective_v4,
                                current_effective_v6,
                            ).await?;
                        }
                        None => {
                            ldebug!("MASQUE: dgram_rx closed");
                            break;
                        }
                    }
                }
                capsule_res = async {
                    if let Some(ref mut rx) = capsule_rx {
                        rx.recv().await
                    } else {
                        std::future::pending().await
                    }
                } => {
                    match capsule_res {
                        Some(capsule) => {
                            let prev_v4 = current_effective_v4;
                            let prev_v6 = current_effective_v6;
                            handle_incoming_capsule(
                                capsule,
                                &self.address_state_v4,
                                &self.address_state_v6,
                                &self.assigned_ipv4,
                                &self.assigned_ipv6,
                                &self.advertised_routes,
                            );
                            let new_v4 = self.effective_ipv4(client_v4_opt);
                            let new_v6 = self.effective_ipv6(client_v6_opt);

                            if new_v4 != prev_v4 || new_v6 != prev_v6 {
                                linfo!(
                                    "MASQUE: dynamic address update: v4 {:?} -> {:?}, v6 {:?} -> {:?}",
                                    prev_v4, new_v4, prev_v6, new_v6
                                );
                                current_effective_v4 = new_v4;
                                current_effective_v6 = new_v6;
                                update_smoltcp_addresses_and_routes(&mut iface, new_v4, new_v6);

                                let (active_withdrawn, active_changed) = match target_ip {
                                    std::net::IpAddr::V4(_) => (new_v4.is_none(), new_v4 != prev_v4),
                                    std::net::IpAddr::V6(_) => (new_v6.is_none(), new_v6 != prev_v6),
                                };

                                let socket = sockets.get_mut::<TcpSocket>(sock_handle);
                                if active_withdrawn {
                                    lwarn!("MASQUE: active target IP family address withdrawn by proxy; aborting socket");
                                    socket.abort();
                                    return Err(std::io::Error::new(
                                        std::io::ErrorKind::AddrNotAvailable,
                                        "active client address was withdrawn by proxy",
                                    ));
                                } else if active_changed && socket.state() == TcpState::SynSent {
                                    ldebug!("MASQUE: delayed assignment arrived during SYN_SENT; rebinding to new source IP");
                                    socket.abort();
                                    let new_local_ep = match (target_ip, new_v4, new_v6) {
                                        (std::net::IpAddr::V4(_), Some(v4), _) => {
                                            IpEndpoint::new(IpAddress::Ipv4(Ipv4Address::from(v4)), local_port)
                                        }
                                        (std::net::IpAddr::V6(_), _, Some(v6)) => {
                                            IpEndpoint::new(IpAddress::Ipv6(Ipv6Address::from(v6)), local_port)
                                        }
                                        _ => unreachable!(),
                                    };
                                    if let Err(e) = socket.connect(iface.context(), remote_ep, new_local_ep) {
                                        lwarn!("MASQUE: reconnect with updated IP failed: {:?}", e);
                                    } else {
                                        iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
                                        self.flush_smoltcp_tx(
                                            &mut dev,
                                            &mut iface,
                                            &mut sockets,
                                            target_ip,
                                            current_effective_v4,
                                            current_effective_v6,
                                        ).await?;
                                    }
                                }
                                iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
                                self.flush_smoltcp_tx(
                                    &mut dev,
                                    &mut iface,
                                    &mut sockets,
                                    target_ip,
                                    current_effective_v4,
                                    current_effective_v6,
                                ).await?;
                            }
                        }
                        None => {
                            capsule_rx = None;
                        }
                    }
                }
                _ = tokio::time::sleep(sleep_dur) => {
                    iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
                    self.flush_smoltcp_tx(
                        &mut dev,
                        &mut iface,
                        &mut sockets,
                        target_ip,
                        current_effective_v4,
                        current_effective_v6,
                    ).await?;
                }
            }
        }

        Ok(())
    }

    pub async fn run_raw_l4_bridge(
        &self,
        client: tokio::net::TcpStream,
        cancel_token: CancellationToken,
    ) -> Result<(), std::io::Error> {
        let (mut c_read, mut c_write) = client.into_split();
        let cancel = Arc::new(tokio::sync::Notify::new());

        let cancel_up = cancel.clone();
        let cancel_token_up = cancel_token.clone();
        let mut send_lock = self.send_stream.lock().await;

        let up = async {
            let mut buf = vec![0u8; crate::config::WS_BRIDGE_CHUNK_SIZE];
            loop {
                let res = tokio::select! {
                    _ = cancel_token_up.cancelled() => break,
                    _ = cancel_up.notified() => break,
                    r = tokio::time::timeout(crate::config::BRIDGE_READ_TIMEOUT, c_read.read(&mut buf)) => r,
                };
                match res {
                    Ok(Ok(0)) | Ok(Err(_)) | Err(_) => break,
                    Ok(Ok(n)) => {
                        STATS.bytes_up.fetch_add(n as i64, Ordering::Relaxed);
                        let mut frame = Vec::with_capacity(n + 10);
                        encode_varint(&mut frame, H3_FRAME_DATA);
                        encode_varint(&mut frame, n as u64);
                        frame.extend_from_slice(&buf[..n]);
                        if send_lock.write_all(&frame).await.is_err() {
                            break;
                        }
                    }
                }
            }
            cancel_up.notify_waiters();
        };

        let cancel_down = cancel.clone();
        let cancel_token_down = cancel_token.clone();
        let mut recv_lock = self.recv_reader.lock().await;

        let down = async {
            let mut buf = vec![0u8; 64 * 1024];
            if let Some(reader) = recv_lock.as_mut() {
                loop {
                    let res = tokio::select! {
                        _ = cancel_token_down.cancelled() => break,
                        _ = cancel_down.notified() => break,
                        r = tokio::time::timeout(crate::config::BRIDGE_READ_TIMEOUT, reader.read_data(&mut buf)) => r,
                    };
                    match res {
                        Ok(Ok(n)) if n > 0 => {
                            STATS.bytes_down.fetch_add(n as i64, Ordering::Relaxed);
                            if c_write.write_all(&buf[..n]).await.is_err() {
                                break;
                            }
                        }
                        _ => break,
                    }
                }
            }
            cancel_down.notify_waiters();
        };

        tokio::select! {
            _ = up => {},
            _ = down => {},
        }
        cancel.notify_waiters();
        Ok(())
    }
}

pub async fn masque_acquire_tunnel(
    target_addr: &str,
    cancel_token: &CancellationToken,
) -> Option<MasqueTunnel> {
    if let Some(err) = get_last_masque_auth_error() {
        lwarn!(
            "MASQUE: cannot acquire tunnel, fatal auth error active: {}",
            err
        );
        return None;
    }
    if is_masque_on_cooldown() {
        ldebug!("MASQUE: Anycast на кулдауне (блокировка ТСПУ), переход на альтернативный Anycast-канал");
        return None;
    }

    let overall_start = tokio::time::Instant::now();
    let overall_budget = if get_uplink_mode() == UPLINK_MASQUE {
        Duration::from_millis(5000)
    } else {
        Duration::from_millis(3500)
    };
    let overall_deadline = overall_start + overall_budget;

    let (configured_ep, sni, creds, uri_template) = {
        let cfg = WARP_CONFIG.read();
        (
            cfg.endpoint.clone(),
            cfg.sni.clone(),
            cfg.resolve_credentials(),
            cfg.uri_template.clone(),
        )
    };
    let uri_path_opt = if uri_template.is_empty() || uri_template == "/" {
        None
    } else {
        Some(uri_template.as_str())
    };

    let sticky_ep_opt = get_sticky_endpoint();
    let max_candidates = if get_uplink_mode() == UPLINK_MASQUE {
        4
    } else {
        2
    };
    let candidate_endpoints = select_rotated_candidate_endpoints(
        &configured_ep,
        sticky_ep_opt.as_deref(),
        max_candidates,
    );

    let preferred_family = candidate_endpoints
        .first()
        .and_then(|ep_str| ep_str.parse::<SocketAddr>().ok())
        .map(|sa| QuicAddressFamily::from_socket_addr(&sa))
        .unwrap_or_else(|| {
            if configured_ep.starts_with('[') {
                QuicAddressFamily::Ipv6
            } else {
                QuicAddressFamily::Ipv4
            }
        });

    let current_generation = get_warp_config_generation();
    let current_network_generation = get_underlying_network_generation();
    let current_identity_hash = creds.identity_hash();

    // 1. Fast Path: Check if we have an active, healthy pooled QUIC connection
    // strictly validating negotiated protocol, SNI, address family, endpoint, identity hash,
    // and both config generation and underlying network generation.
    let cached_session_opt = {
        let mut guard = ACTIVE_QUIC_SESSIONS.lock();
        if let Some(s) = guard.get(&preferred_family) {
            let matches_candidates = candidate_endpoints
                .iter()
                .any(|ep| ep.parse::<SocketAddr>().ok() == Some(s.key.endpoint_addr));

            if matches_candidates
                && s.conn.close_reason().is_none()
                && !s.goaway_received.load(Ordering::SeqCst)
                && s.key.is_valid_for(
                    &sni,
                    current_generation,
                    current_network_generation,
                    &current_identity_hash,
                    preferred_family,
                )
            {
                Some((
                    s.key.clone(),
                    s.conn.clone(),
                    s.dispatcher.clone(),
                    s.control_stream.clone(),
                ))
            } else {
                ldebug!(
                    "MASQUE: invalidating active session for {:?} (closed={}, goaway={}, candidate_match={}, valid_for_key={})",
                    preferred_family,
                    s.conn.close_reason().is_some(),
                    s.goaway_received.load(Ordering::SeqCst),
                    matches_candidates,
                    s.key.is_valid_for(
                        &sni,
                        current_generation,
                        current_network_generation,
                        &current_identity_hash,
                        preferred_family,
                    )
                );
                if let Some(stale) = guard.remove(&preferred_family) {
                    stale.abort_sync(b"stale session invalidated");
                }
                None
            }
        } else {
            None
        }
    };

    if let Some((session_key, conn, dispatcher, control_stream)) = cached_session_opt {
        let cached_timeout = overall_deadline
            .saturating_duration_since(tokio::time::Instant::now())
            .min(Duration::from_millis(1500));

        let cached_res = if cancel_token.is_cancelled() {
            None
        } else {
            tokio::select! {
                _ = cancel_token.cancelled() => {
                    ldebug!("MASQUE: cached session acquisition cancelled by token");
                    return None;
                }
                _ = tokio::time::sleep(cached_timeout) => {
                    ldebug!("MASQUE: cached session acquisition timed out after {:?}", cached_timeout);
                    None
                }
                res = async {
                    let (mut send_stream, mut recv_stream) = conn.open_bi().await.map_err(|e| format!("open_bi: {}", e))?;
                    let req_frame = build_h3_connect_headers(
                        target_addr,
                        &session_key.sni,
                        session_key.variant.protocol_header(),
                        session_key.variant.uri_path(uri_path_opt),
                        creds.gateway_bearer_token.as_deref(),
                    );
                    send_stream.write_all(&req_frame).await.map_err(|e| format!("write_all: {}", e))?;
                    let read_budget = cached_timeout.saturating_sub(Duration::from_millis(50));
                    let (status, leftovers) = read_h3_headers_response_with_cancel(&mut recv_stream, read_budget, Some(cancel_token)).await?;
                    Ok::<_, String>((send_stream, recv_stream, status, leftovers))
                } => res.ok(),
            }
        };

        if let Some((send_stream, recv_stream, status, leftovers)) = cached_res {
            if (200..300).contains(&status) {
                ldebug!(
                    "MASQUE: stream multiplexed on active QUIC session {} -> {} (variant={:?})",
                    session_key.endpoint_addr,
                    target_addr,
                    session_key.variant
                );
                record_sticky_success(&session_key.endpoint_addr.to_string());

                return Some(MasqueTunnel::from_negotiated_stream(
                    send_stream,
                    recv_stream,
                    leftovers,
                    conn,
                    session_key.endpoint_addr,
                    target_addr.to_string(),
                    &session_key.variant,
                    dispatcher,
                    control_stream.clone(),
                ));
            } else if let Some(auth_err) = classify_http_auth_error(status, "active session") {
                lerror!("MASQUE: active session rejected with auth error: {}", auth_err);
                record_masque_auth_failure(auth_err);
                let mut guard = ACTIVE_QUIC_SESSIONS.lock();
                if let Some(stale) = guard.remove(&preferred_family) {
                    stale.abort_sync(b"active session auth failure");
                }
                return None; // Fatal auth failure: do not dial candidate endpoints
            } else {
                ldebug!("MASQUE: cached session returned HTTP {}, invalidating session", status);
            }
        } else if cancel_token.is_cancelled() {
            return None;
        }

        ldebug!(
            "MASQUE: pooled connection rejected target {}, invalidating session",
            target_addr
        );
        record_sticky_timeout();
        // Invalidate stale pooled session
        let mut guard = ACTIVE_QUIC_SESSIONS.lock();
        if let Some(stale) = guard.remove(&preferred_family) {
            stale.abort_sync(b"pooled connection failed");
        }
    }

    // Single-Flight: serialize connection dials per address family to prevent duplicate QUIC handshakes
    let dial_lock = get_dial_lock_for_family(preferred_family);
    let _dial_guard = tokio::select! {
        _ = cancel_token.cancelled() => return None,
        _ = tokio::time::sleep_until(overall_deadline) => {
            ldebug!("MASQUE: dial lock wait exceeded overall deadline ({:?})", overall_budget);
            return None;
        }
        guard = dial_lock.lock() => guard,
    };

    // Double-check: Did a concurrent task establish a valid session while we waited for dial_lock?
    let post_lock_session_opt = {
        let guard = ACTIVE_QUIC_SESSIONS.lock();
        if let Some(s) = guard.get(&preferred_family) {
            let matches_candidates = candidate_endpoints
                .iter()
                .any(|ep| ep.parse::<SocketAddr>().ok() == Some(s.key.endpoint_addr));

            if matches_candidates
                && s.conn.close_reason().is_none()
                && !s.goaway_received.load(Ordering::SeqCst)
                && s.key.is_valid_for(
                    &sni,
                    current_generation,
                    current_network_generation,
                    &current_identity_hash,
                    preferred_family,
                )
            {
                Some((
                    s.key.clone(),
                    s.conn.clone(),
                    s.dispatcher.clone(),
                    s.control_stream.clone(),
                ))
            } else {
                None
            }
        } else {
            None
        }
    };

    if let Some((session_key, conn, dispatcher, control_stream)) = post_lock_session_opt {
        drop(_dial_guard); // Release lock immediately so subsequent waiters can proceed
        let post_lock_timeout = overall_deadline
            .saturating_duration_since(tokio::time::Instant::now())
            .min(Duration::from_millis(1500));

        let post_lock_res = if cancel_token.is_cancelled() {
            None
        } else {
            tokio::select! {
                _ = cancel_token.cancelled() => {
                    ldebug!("MASQUE: single-flight session acquisition cancelled by token");
                    return None;
                }
                _ = tokio::time::sleep(post_lock_timeout) => {
                    ldebug!("MASQUE: single-flight session acquisition timed out after {:?}", post_lock_timeout);
                    None
                }
                res = async {
                    let (mut send_stream, mut recv_stream) = conn.open_bi().await.map_err(|e| format!("open_bi: {}", e))?;
                    let req_frame = build_h3_connect_headers(
                        target_addr,
                        &session_key.sni,
                        session_key.variant.protocol_header(),
                        session_key.variant.uri_path(uri_path_opt),
                        creds.gateway_bearer_token.as_deref(),
                    );
                    send_stream.write_all(&req_frame).await.map_err(|e| format!("write_all: {}", e))?;
                    let read_budget = post_lock_timeout.saturating_sub(Duration::from_millis(50));
                    let (status, leftovers) = read_h3_headers_response_with_cancel(&mut recv_stream, read_budget, Some(cancel_token)).await?;
                    Ok::<_, String>((send_stream, recv_stream, status, leftovers))
                } => res.ok(),
            }
        };

        if let Some((send_stream, recv_stream, status, leftovers)) = post_lock_res {
            if (200..300).contains(&status) {
                ldebug!(
                    "MASQUE: single-flight stream multiplexed on session {} -> {} (variant={:?})",
                    session_key.endpoint_addr,
                    target_addr,
                    session_key.variant
                );
                record_sticky_success(&session_key.endpoint_addr.to_string());

                return Some(MasqueTunnel::from_negotiated_stream(
                    send_stream,
                    recv_stream,
                    leftovers,
                    conn,
                    session_key.endpoint_addr,
                    target_addr.to_string(),
                    &session_key.variant,
                    dispatcher,
                    control_stream,
                ));
            } else if let Some(auth_err) = classify_http_auth_error(status, "single-flight session") {
                lerror!("MASQUE: single-flight session rejected with auth error: {}", auth_err);
                record_masque_auth_failure(auth_err);
                let mut guard = ACTIVE_QUIC_SESSIONS.lock();
                if let Some(stale) = guard.remove(&preferred_family) {
                    stale.abort_sync(b"auth failure");
                }
                return None;
            }
        }
        return None;
    }

    if LAST_AUTH_ERROR.read().is_some() {
        return None;
    }

    ldebug!(
        "MASQUE: acquiring tunnel for {} across {} endpoints (scheme={:?}, preferred_family={:?})",
        target_addr,
        candidate_endpoints.len(),
        creds.scheme,
        preferred_family
    );

    let dial_timeout_per_ep = if get_uplink_mode() == UPLINK_MASQUE {
        Duration::from_millis(2500)
    } else {
        Duration::from_millis(1500)
    };

    let quic_client_config = match creds.scheme {
        MasqueAuthScheme::Mtls | MasqueAuthScheme::MtlsWithGatewayBearer => {
            match create_quic_client_config(creds.client_cert_der.clone(), creds.client_key_der.clone()) {
                Ok(cfg) => cfg,
                Err(e) => {
                    let auth_err = classify_tls_auth_error(&e).unwrap_or_else(|| {
                        MasqueAuthError::BadCertificate(format!("Failed to build mTLS client config: {}", e))
                    });
                    record_masque_auth_failure(auth_err);
                    return None;
                }
            }
        }
        MasqueAuthScheme::GatewayBearer | MasqueAuthScheme::Anonymous => {
            match create_standard_quic_client_config() {
                Ok(cfg) => cfg,
                Err(e) => {
                    lerror!("MASQUE: failed to build standard QUIC config: {}", e);
                    return None;
                }
            }
        }
    };

    enum DialResult {
        Success(MasqueTunnel),
        AuthFailure(MasqueAuthError),
        NetworkFailure(String),
        Cancelled,
    }

    for ep_str in candidate_endpoints {
        if cancel_token.is_cancelled() {
            return None;
        }
        if LAST_AUTH_ERROR.read().is_some() {
            return None;
        }

        let now = tokio::time::Instant::now();
        if now >= overall_deadline {
            ldebug!(
                "MASQUE: overall deadline reached ({:?}), terminating candidate dial loop",
                overall_budget
            );
            break;
        }

        let remaining_overall = overall_deadline.saturating_duration_since(now);
        if remaining_overall < Duration::from_millis(400) {
            ldebug!(
                "MASQUE: residual overall budget ({:?}) insufficient for candidate {}, stopping dial loop",
                remaining_overall,
                ep_str
            );
            break;
        }

        let ep_budget = dial_timeout_per_ep.min(remaining_overall);
        let ep_deadline = now + ep_budget;

        let sock_addr: SocketAddr = match ep_str.parse() {
            Ok(a) => a,
            Err(_) => {
                let lookup_budget = Duration::from_millis(500).min(ep_budget / 2);
                let lookup_res = tokio::select! {
                    _ = cancel_token.cancelled() => return None,
                    _ = tokio::time::sleep(lookup_budget) => None,
                    res = tokio::net::lookup_host(&ep_str) => res.ok(),
                };
                match lookup_res {
                    Some(mut addrs) => match addrs.next() {
                        Some(a) => a,
                        None => continue,
                    },
                    None => continue,
                }
            }
        };

        let ep_family = QuicAddressFamily::from_socket_addr(&sock_addr);
        let endpoint_client = match get_or_create_quic_endpoint_for_family(ep_family) {
            Ok(ep) => ep,
            Err(e) => {
                lwarn!("MASQUE: cannot bind QUIC endpoint for {:?}: {}", ep_family, e);
                continue;
            }
        };

        ldebug!("MASQUE: dialing QUIC Anycast {} (SNI: {}, family: {:?})", sock_addr, sni, ep_family);

        let creds_clone = creds.clone();
        let qcfg = quic_client_config.clone();
        let sni_clone = sni.clone();
        let session_cancel = CancellationToken::new();
        let session_cancel_dial = session_cancel.clone();
        let cancel_token_clone = cancel_token.clone();

        let dial_fut = async move {
            let handshake_budget = (ep_budget / 2)
                .min(Duration::from_millis(1200))
                .max(Duration::from_millis(350));

            let conn = {
                let connect_start = tokio::time::Instant::now();
                let connecting = match endpoint_client.connect_with(qcfg, sock_addr, &sni_clone) {
                    Ok(c) => c,
                    Err(e) => {
                        let err_str = e.to_string();
                        if let Some(auth_err) = classify_tls_auth_error(&err_str) {
                            return DialResult::AuthFailure(auth_err);
                        }
                        return DialResult::NetworkFailure(format!("connect_with failed: {}", err_str));
                    }
                };
                tokio::select! {
                    _ = cancel_token_clone.cancelled() => return DialResult::Cancelled,
                    _ = session_cancel_dial.cancelled() => return DialResult::Cancelled,
                    _ = tokio::time::sleep(handshake_budget) => {
                        return DialResult::NetworkFailure(format!(
                            "handshake timeout after {:?}",
                            handshake_budget
                        ));
                    }
                    res = connecting => {
                        match res {
                            Ok(c) => {
                                ldebug!("MASQUE: QUIC handshake successful with {} in {:?}", sock_addr, connect_start.elapsed());
                                c
                            }
                            Err(e) => {
                                let err_str = e.to_string();
                                if let Some(auth_err) = classify_tls_auth_error(&err_str) {
                                    return DialResult::AuthFailure(auth_err);
                                }
                                return DialResult::NetworkFailure(format!("handshake failed: {}", err_str));
                            }
                        }
                    }
                }
            };

            let (dispatcher, dispatcher_handle) =
                create_datagram_dispatcher_with_cancel(conn.clone(), session_cancel_dial.clone());

            // 1. Send HTTP/3 Control Stream SETTINGS (RFC 9114 Section 6.2.1)
            let ctrl_open_budget = ep_deadline
                .saturating_duration_since(tokio::time::Instant::now())
                .min(Duration::from_millis(400));
            let mut ctrl_stream = match tokio::select! {
                _ = cancel_token_clone.cancelled() => return DialResult::Cancelled,
                _ = session_cancel_dial.cancelled() => return DialResult::Cancelled,
                _ = tokio::time::sleep(ctrl_open_budget) => {
                    session_cancel_dial.cancel();
                    dispatcher_handle.abort();
                    conn.close(0u32.into(), b"control stream open timeout");
                    return DialResult::NetworkFailure(format!(
                        "control stream open timeout after {:?}",
                        ctrl_open_budget
                    ));
                }
                res = conn.open_uni() => res,
            } {
                Ok(s) => s,
                Err(e) => {
                    session_cancel_dial.cancel();
                    dispatcher_handle.abort();
                    conn.close(0u32.into(), b"failed to open control stream");
                    return DialResult::NetworkFailure(format!(
                        "failed to open control stream: {}",
                        e
                    ));
                }
            };

            let mut ctrl_buf = Vec::with_capacity(32);
            // Stream Type: 0x00 (Control Stream)
            encode_varint(&mut ctrl_buf, H3_STREAM_CONTROL);

            let mut settings_payload = Vec::with_capacity(20);
            // SETTINGS_ENABLE_CONNECT_PROTOCOL = 0x08, value = 1 (RFC 9220 Extended CONNECT)
            encode_varint(&mut settings_payload, SETTINGS_ENABLE_CONNECT_PROTOCOL);
            encode_varint(&mut settings_payload, 1);
            // SETTINGS_H3_DATAGRAM = 0x33, value = 1 (RFC 9297)
            encode_varint(&mut settings_payload, SETTINGS_H3_DATAGRAM);
            encode_varint(&mut settings_payload, 1);
            // SETTINGS_H3_DATAGRAM_DRAFT00 = 0x0276, value = 1 (Cloudflare legacy masque datagram)
            encode_varint(&mut settings_payload, SETTINGS_H3_DATAGRAM_DRAFT00);
            encode_varint(&mut settings_payload, 1);
            // SETTINGS_QPACK_MAX_TABLE_CAPACITY = 0x01, value = 0
            encode_varint(&mut settings_payload, SETTINGS_QPACK_MAX_TABLE_CAPACITY);
            encode_varint(&mut settings_payload, 0);
            // SETTINGS_QPACK_BLOCKED_STREAMS = 0x07, value = 0
            encode_varint(&mut settings_payload, SETTINGS_QPACK_BLOCKED_STREAMS);
            encode_varint(&mut settings_payload, 0);

            // Frame Type: 0x04 (SETTINGS)
            encode_varint(&mut ctrl_buf, H3_FRAME_SETTINGS);
            encode_varint(&mut ctrl_buf, settings_payload.len() as u64);
            ctrl_buf.extend_from_slice(&settings_payload);

            let ctrl_write_budget = ep_deadline
                .saturating_duration_since(tokio::time::Instant::now())
                .min(Duration::from_millis(400));
            let write_res = tokio::select! {
                _ = cancel_token_clone.cancelled() => return DialResult::Cancelled,
                _ = session_cancel_dial.cancelled() => return DialResult::Cancelled,
                _ = tokio::time::sleep(ctrl_write_budget) => Err("control stream write timeout".to_string()),
                res = ctrl_stream.write_all(&ctrl_buf) => res.map_err(|e| e.to_string()),
            };
            if let Err(e) = write_res {
                session_cancel_dial.cancel();
                dispatcher_handle.abort();
                conn.close(0u32.into(), b"failed to write settings");
                return DialResult::NetworkFailure(format!(
                    "failed to write HTTP/3 SETTINGS: {}",
                    e
                ));
            }

            let control_stream_arc = Arc::new(tokio::sync::Mutex::new(ctrl_stream));

            // Spawn background supervisor for server unidirectional streams (RFC 9114 Section 6.2)
            let (supervisor, supervisor_handle) =
                spawn_server_stream_supervisor_with_cancel(conn.clone(), session_cancel_dial.clone());

            // Await server SETTINGS frame and verify capabilities
            let settings_budget = ep_deadline
                .saturating_duration_since(tokio::time::Instant::now())
                .min(Duration::from_millis(600))
                .max(Duration::from_millis(200));

            let server_settings = match tokio::select! {
                _ = cancel_token_clone.cancelled() => return DialResult::Cancelled,
                _ = session_cancel_dial.cancelled() => return DialResult::Cancelled,
                _ = tokio::time::sleep(settings_budget) => {
                    session_cancel_dial.cancel();
                    dispatcher_handle.abort();
                    supervisor_handle.abort();
                    conn.close(0u32.into(), b"timeout waiting for server settings");
                    return DialResult::NetworkFailure(format!(
                        "server SETTINGS timeout after {:?}",
                        settings_budget
                    ));
                }
                res = async {
                    let mut rx = supervisor.server_settings_rx.clone();
                    loop {
                        if let Some(ref s) = *rx.borrow() {
                            return Ok(s.clone());
                        }
                        if rx.changed().await.is_err() {
                            return Err("server control stream terminated before SETTINGS".to_string());
                        }
                    }
                } => res,
            } {
                Ok(s) => s,
                Err(e) => {
                    session_cancel_dial.cancel();
                    dispatcher_handle.abort();
                    supervisor_handle.abort();
                    conn.close(0u32.into(), b"server settings error");
                    return DialResult::NetworkFailure(format!("server SETTINGS error: {}", e));
                }
            };

            // RFC 9220 / RFC 9297 capability verification:
            let requires_ip_tunnel = get_uplink_mode() == UPLINK_MASQUE || get_uplink_mode() == UPLINK_HYBRID;
            if requires_ip_tunnel {
                if !server_settings.enable_connect_protocol {
                    session_cancel_dial.cancel();
                    dispatcher_handle.abort();
                    supervisor_handle.abort();
                    conn.close(
                        quinn::VarInt::from_u32(H3_SETTINGS_ERROR),
                        b"missing SETTINGS_ENABLE_CONNECT_PROTOCOL",
                    );
                    return DialResult::NetworkFailure(
                        "peer rejected: missing SETTINGS_ENABLE_CONNECT_PROTOCOL (Extended CONNECT not supported)".to_string(),
                    );
                }
                if !server_settings.h3_datagram {
                    session_cancel_dial.cancel();
                    dispatcher_handle.abort();
                    supervisor_handle.abort();
                    conn.close(
                        quinn::VarInt::from_u32(H3_SETTINGS_ERROR),
                        b"missing SETTINGS_H3_DATAGRAM",
                    );
                    return DialResult::NetworkFailure(
                        "peer rejected: missing SETTINGS_H3_DATAGRAM (HTTP/3 Datagrams not supported)".to_string(),
                    );
                }
            }

            // 2. CONNECT Attempts: RFC 9484 connect-ip -> cf-connect-ip -> standard CONNECT
            // Each branch gets an allocated residual budget so fallback branches are guaranteed execution time.
            let candidate_variants: [(Option<&str>, Option<&str>, &str, usize); 3] = [
                (Some(MASQUE_CONNECT_IP_PROTO), uri_path_opt, "RFC 9484 connect-ip", 0),
                (Some("cf-connect-ip"), uri_path_opt, "cf-connect-ip", 1),
                (None, None, "standard CONNECT", 2),
            ];

            let mut last_status_info = Vec::with_capacity(3);

            for (proto, path, variant_name, idx) in candidate_variants {
                if cancel_token_clone.is_cancelled() || session_cancel_dial.is_cancelled() {
                    return DialResult::Cancelled;
                }

                let remaining_fallbacks = 2 - idx;
                let remaining_for_connect = ep_deadline.saturating_duration_since(tokio::time::Instant::now());
                if remaining_for_connect < Duration::from_millis(150) {
                    ldebug!(
                        "MASQUE: insufficient residual budget ({:?}) for {}, skipping fallback",
                        remaining_for_connect,
                        variant_name
                    );
                    continue;
                }

                let step_budget = allocate_connect_step_budget(
                    remaining_for_connect,
                    remaining_fallbacks,
                    Duration::from_millis(350),
                    Duration::from_millis(1000),
                );
                let step_deadline = tokio::time::Instant::now() + step_budget;

                let (mut send_stream, mut recv_stream) = match tokio::select! {
                    _ = cancel_token_clone.cancelled() => return DialResult::Cancelled,
                    _ = session_cancel_dial.cancelled() => return DialResult::Cancelled,
                    _ = tokio::time::sleep_until(step_deadline) => {
                        ldebug!("MASQUE: {} open_bi timed out after {:?}", variant_name, step_budget);
                        continue;
                    }
                    res = conn.open_bi() => res,
                } {
                    Ok(bi) => bi,
                    Err(e) => {
                        ldebug!("MASQUE: {} open_bi failed: {}, trying next fallback", variant_name, e);
                        continue;
                    }
                };

                let req_frame = build_h3_connect_headers(
                    target_addr,
                    &sni_clone,
                    proto,
                    path,
                    creds_clone.gateway_bearer_token.as_deref(),
                );

                let write_res = tokio::select! {
                    _ = cancel_token_clone.cancelled() => return DialResult::Cancelled,
                    _ = session_cancel_dial.cancelled() => return DialResult::Cancelled,
                    _ = tokio::time::sleep_until(step_deadline) => {
                        ldebug!("MASQUE: {} write_all timed out", variant_name);
                        Err("write_all timeout".to_string())
                    }
                    res = send_stream.write_all(&req_frame) => res.map_err(|e| e.to_string()),
                };
                if write_res.is_err() {
                    continue;
                }

                let read_budget = step_deadline.saturating_duration_since(tokio::time::Instant::now());
                let (status, leftovers) = match read_h3_headers_response_with_cancel(
                    &mut recv_stream,
                    read_budget,
                    Some(&cancel_token_clone),
                ).await {
                    Ok(res) => res,
                    Err(e) => {
                        if cancel_token_clone.is_cancelled() || session_cancel_dial.is_cancelled() {
                            return DialResult::Cancelled;
                        }
                        ldebug!(
                            "MASQUE: {} response error or timeout after {:?}: {}, trying next fallback",
                            variant_name,
                            step_budget,
                            e
                        );
                        last_status_info.push(format!("{}=timeout", variant_name));
                        continue;
                    }
                };

                last_status_info.push(format!("{}={}", variant_name, status));

                if (200..300).contains(&status) {
                    let negotiated_variant = match idx {
                        0 => MasqueConnectVariant::Rfc9484ConnectIp {
                            uri_template: uri_path_opt.map(|s| s.to_string()),
                        },
                        1 => MasqueConnectVariant::LegacyCfConnectIp {
                            uri_template: uri_path_opt.map(|s| s.to_string()),
                        },
                        _ => MasqueConnectVariant::StandardRfc9114L4,
                    };
                    let session_key = MasqueSessionKey {
                        sni: sni_clone.clone(),
                        endpoint_addr: sock_addr,
                        address_family: ep_family,
                        identity_hash: creds_clone.identity_hash(),
                        config_generation: current_generation,
                        network_generation: current_network_generation,
                        variant: negotiated_variant.clone(),
                    };
                    let session = ActiveQuicSession::new(
                        session_key,
                        conn.clone(),
                        dispatcher.clone(),
                        control_stream_arc.clone(),
                        server_settings.clone(),
                        supervisor.goaway_received.clone(),
                        session_cancel_dial,
                        vec![dispatcher_handle, supervisor_handle],
                    );
                    replace_active_quic_session(session);

                    return DialResult::Success(MasqueTunnel::from_negotiated_stream(
                        send_stream,
                        recv_stream,
                        leftovers,
                        conn,
                        sock_addr,
                        target_addr.to_string(),
                        &negotiated_variant,
                        dispatcher,
                        control_stream_arc.clone(),
                    ));
                }

                if let Some(auth_err) = classify_http_auth_error(status, variant_name) {
                    session_cancel_dial.cancel();
                    dispatcher_handle.abort();
                    supervisor_handle.abort();
                    conn.close(0u32.into(), b"auth failure");
                    return DialResult::AuthFailure(auth_err);
                }

                ldebug!(
                    "MASQUE: {} returned HTTP {}, retrying next fallback branch",
                    variant_name,
                    status
                );
            }

            session_cancel_dial.cancel();
            dispatcher_handle.abort();
            supervisor_handle.abort();
            conn.close(0u32.into(), b"all CONNECT variants rejected");

            DialResult::NetworkFailure(format!(
                "MASQUE CONNECT variants rejected: [{}]",
                last_status_info.join(", ")
            ))
        };

        match tokio::select! {
            _ = cancel_token.cancelled() => DialResult::Cancelled,
            _ = tokio::time::sleep_until(ep_deadline) => {
                session_cancel.cancel();
                DialResult::NetworkFailure(format!("candidate {} timeout after {:?}", sock_addr, ep_budget))
            }
            res = dial_fut => res,
        } {
            DialResult::Success(tunnel) => {
                let latest_cfg_gen = get_warp_config_generation();
                let latest_net_gen = get_underlying_network_generation();
                if latest_cfg_gen != current_generation || latest_net_gen != current_network_generation {
                    lwarn!(
                        "MASQUE: dial completed for {} but config changed during dial (gen {} -> {}, net {} -> {}), dropping stale tunnel",
                        sock_addr,
                        current_generation,
                        latest_cfg_gen,
                        current_network_generation,
                        latest_net_gen
                    );
                    let _ = tunnel.send_stream.lock().await.finish();
                    drop(tunnel);
                    return None;
                }
                linfo!(
                    "MASQUE: tunnel established via QUIC {} -> {} (Sticky Profile pinned)",
                    sock_addr,
                    target_addr
                );
                reset_masque_cooldown();
                record_sticky_success(&ep_str);
                return Some(tunnel);
            }
            DialResult::AuthFailure(auth_err) => {
                session_cancel.cancel();
                lerror!(
                    "MASQUE: Fatal authentication failure on {}: {}. Aborting candidate endpoint failover.",
                    sock_addr,
                    auth_err
                );
                record_masque_auth_failure(auth_err);
                // CRITICAL (TSK-M02): Abort immediately without trying any further ports or endpoints!
                return None;
            }
            DialResult::Cancelled => {
                session_cancel.cancel();
                ldebug!("MASQUE: candidate {} dial cancelled promptly", sock_addr);
                return None;
            }
            DialResult::NetworkFailure(e) => {
                session_cancel.cancel();
                ldebug!("MASQUE endpoint {} dial error: {}", sock_addr, e);
                if sticky_ep_opt.as_deref() == Some(&ep_str) {
                    record_sticky_timeout();
                }
            }
        }
    }

    if cancel_token.is_cancelled() {
        return None;
    }

    let cooldown_secs = if get_uplink_mode() == UPLINK_MASQUE {
        5
    } else {
        60
    };
    set_masque_cooldown(cooldown_secs);
    lwarn!(
        "MASQUE: Anycast эндпоинты недоступны для {} (общий бюджет {:?}), кулдаун {}с",
        target_addr,
        overall_budget,
        cooldown_secs
    );
    None
}

// ---------------------------------------------------------------------------
// Unit Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_varint_roundtrip() {
        let test_values = [
            0u64,
            1,
            63,
            64,
            255,
            16383,
            16384,
            1073741823,
            1073741824,
            4611686018427387903,
        ];
        for &val in &test_values {
            let mut buf = Vec::new();
            encode_varint(&mut buf, val);
            let (decoded, consumed) = decode_varint(&buf).expect("must decode varint");
            assert_eq!(decoded, val);
            assert_eq!(consumed, buf.len());
        }
    }

    #[test]
    fn test_build_connect_ip_path() {
        assert_eq!(
            build_connect_ip_path("149.154.167.50:443", None),
            "/.well-known/masque/ip/"
        );
        // Passing "/" must normalize to MASQUE_DEFAULT_URI_PATH
        assert_eq!(
            build_connect_ip_path("149.154.167.50:443", Some("/")),
            "/.well-known/masque/ip/"
        );
        assert_eq!(
            build_connect_ip_path("149.154.167.50:443", Some("   ")),
            "/.well-known/masque/ip/"
        );
        assert_eq!(
            build_connect_ip_path(
                "149.154.167.50:443",
                Some("/.well-known/masque/ip/{target_ip}/{target_port}/")
            ),
            "/.well-known/masque/ip/149.154.167.50/443/"
        );
        // IPv6 literal must have colons percent-encoded per RFC 9484 §4.6
        assert_eq!(
            build_connect_ip_path(
                "[2001:db8::1]:443",
                Some("/.well-known/masque/ip/{target_ip}/{target_port}/")
            ),
            "/.well-known/masque/ip/2001%3Adb8%3A%3A1/443/"
        );
        assert_eq!(
            build_connect_ip_path("91.108.56.165:443", Some("/masque/{host}:{port}")),
            "/masque/91.108.56.165:443"
        );
    }

    #[test]
    fn test_build_h3_connect_rfc9484() {
        let frame = build_h3_connect_headers(
            "149.154.167.50:443",
            MASQUE_IP_SNI,
            Some(MASQUE_CONNECT_IP_PROTO),
            None,
            Some("test-token"),
        );
        // Verify frame header
        let (ftype, tlen) = decode_varint(&frame).expect("frame type varint");
        assert_eq!(ftype, H3_FRAME_HEADERS);
        let (flen, llen) = decode_varint(&frame[tlen..]).expect("frame len varint");
        assert_eq!(frame.len(), tlen + llen + (flen as usize));

        let qpack_payload = &frame[(tlen + llen)..];
        // Must start with Required Insert Count = 0, Sign/Delta Base = 0
        assert_eq!(qpack_payload[0], 0x00);
        assert_eq!(qpack_payload[1], 0x00);
        // :method = CONNECT (Static Table Index 15 -> 0xCF)
        assert_eq!(qpack_payload[2], 0xCF);
        // Contains :protocol and connect-ip
        assert!(qpack_payload.windows(10).any(|w| w == b"connect-ip"));
        // Contains authority consumer-masque.cloudflareclient.com
        assert!(qpack_payload
            .windows(MASQUE_IP_SNI.len())
            .any(|w| w == MASQUE_IP_SNI.as_bytes()));
        // Must NOT contain obsolete cloudflareaccess.com
        assert!(!qpack_payload
            .windows(20)
            .any(|w| w == b"cloudflareaccess.com"));
        // Contains path /.well-known/masque/ip/
        assert!(qpack_payload
            .windows(MASQUE_DEFAULT_URI_PATH.len())
            .any(|w| w == MASQUE_DEFAULT_URI_PATH.as_bytes()));
        // Contains capsule-protocol
        assert!(qpack_payload.windows(16).any(|w| w == b"capsule-protocol"));
        // Contains Bearer test-token
        assert!(qpack_payload.windows(17).any(|w| w == b"Bearer test-token"));
    }

    #[test]
    fn test_build_h3_connect_slash_path_fallback() {
        // Explicitly pass "/" as path: it MUST be normalized to MASQUE_DEFAULT_URI_PATH, NEVER "/"!
        let frame = build_h3_connect_headers(
            "149.154.167.50:443",
            MASQUE_IP_SNI,
            Some(MASQUE_CONNECT_IP_PROTO),
            Some("/"),
            None,
        );
        let (ftype, tlen) = decode_varint(&frame).expect("frame type varint");
        assert_eq!(ftype, H3_FRAME_HEADERS);
        let (flen, llen) = decode_varint(&frame[tlen..]).expect("frame len varint");
        assert_eq!(frame.len(), tlen + llen + (flen as usize));

        let qpack_payload = &frame[(tlen + llen)..];
        // Must contain full path /.well-known/masque/ip/
        assert!(qpack_payload
            .windows(MASQUE_DEFAULT_URI_PATH.len())
            .any(|w| w == MASQUE_DEFAULT_URI_PATH.as_bytes()));
        // Must contain authority consumer-masque.cloudflareclient.com
        assert!(qpack_payload
            .windows(MASQUE_IP_SNI.len())
            .any(|w| w == MASQUE_IP_SNI.as_bytes()));
        // Must contain capsule-protocol
        assert!(qpack_payload.windows(16).any(|w| w == b"capsule-protocol"));
    }

    #[test]
    fn test_build_h3_connect_scoped_path() {
        let frame = build_h3_connect_headers(
            "149.154.167.50:443",
            MASQUE_IP_SNI,
            Some(MASQUE_CONNECT_IP_PROTO),
            Some("/.well-known/masque/ip/{target_ip}/{target_port}/"),
            None,
        );
        let (ftype, tlen) = decode_varint(&frame).expect("frame type varint");
        assert_eq!(ftype, H3_FRAME_HEADERS);
        let (flen, llen) = decode_varint(&frame[tlen..]).expect("frame len varint");
        assert_eq!(frame.len(), tlen + llen + (flen as usize));

        let qpack_payload = &frame[(tlen + llen)..];
        let expected_path = b"/.well-known/masque/ip/149.154.167.50/443/";
        assert!(qpack_payload
            .windows(expected_path.len())
            .any(|w| w == expected_path));
    }

    #[test]
    fn test_build_h3_connect_legacy_cf() {
        let frame = build_h3_connect_headers(
            "149.154.167.50:443",
            MASQUE_IP_SNI,
            Some("cf-connect-ip"),
            None,
            Some("test-token"),
        );
        let (ftype, tlen) = decode_varint(&frame).expect("frame type varint");
        assert_eq!(ftype, H3_FRAME_HEADERS);
        let (flen, llen) = decode_varint(&frame[tlen..]).expect("frame len varint");
        assert_eq!(frame.len(), tlen + llen + (flen as usize));

        let qpack_payload = &frame[(tlen + llen)..];
        assert_eq!(qpack_payload[0], 0x00);
        assert_eq!(qpack_payload[1], 0x00);
        assert_eq!(qpack_payload[2], 0xCF);
        // Contains :protocol and cf-connect-ip
        assert!(qpack_payload.windows(13).any(|w| w == b"cf-connect-ip"));
        // Contains authority consumer-masque.cloudflareclient.com
        assert!(qpack_payload
            .windows(MASQUE_IP_SNI.len())
            .any(|w| w == MASQUE_IP_SNI.as_bytes()));
        // Contains path /.well-known/masque/ip/ (never "/")
        assert!(qpack_payload
            .windows(MASQUE_DEFAULT_URI_PATH.len())
            .any(|w| w == MASQUE_DEFAULT_URI_PATH.as_bytes()));
        // Contains capsule-protocol
        assert!(qpack_payload.windows(16).any(|w| w == b"capsule-protocol"));
    }

    #[test]
    fn test_build_h3_connect_standard() {
        let frame = build_h3_connect_headers("149.154.167.50:443", MASQUE_L4_SNI, None, None, None);
        let (ftype, tlen) = decode_varint(&frame).expect("frame type varint");
        assert_eq!(ftype, H3_FRAME_HEADERS);
        let (flen, llen) = decode_varint(&frame[tlen..]).expect("frame len varint");
        assert_eq!(frame.len(), tlen + llen + (flen as usize));

        let qpack_payload = &frame[(tlen + llen)..];
        assert_eq!(qpack_payload[0], 0x00);
        assert_eq!(qpack_payload[1], 0x00);
        // :method = CONNECT
        assert_eq!(qpack_payload[2], 0xCF);
        // Does NOT contain :protocol
        assert!(!qpack_payload.windows(9).any(|w| w == b":protocol"));
        // Does NOT contain capsule-protocol
        assert!(!qpack_payload.windows(16).any(|w| w == b"capsule-protocol"));
        // Contains target
        assert!(qpack_payload
            .windows(18)
            .any(|w| w == b"149.154.167.50:443"));
    }

    #[test]
    fn test_qpack_huffman_codec_roundtrip() {
        let test_strings: &[&[u8]] = &[
            b"",
            b"200",
            b"403",
            b"404",
            b"407",
            b"429",
            b"500",
            b"503",
            b":status",
            b":path",
            b"content-length",
            b"https://example.com/test?a=1&b=2",
            b"Lorem ipsum dolor sit amet, consectetur adipiscing elit.",
        ];

        for &original in test_strings {
            let encoded = qpack_huffman_encode(original);
            let decoded = qpack_huffman_decode(&encoded).expect("huffman decode success");
            assert_eq!(decoded.as_slice(), original, "roundtrip match for {:?}", original);
        }
    }

    #[test]
    fn test_qpack_huffman_invalid_padding_and_eos() {
        // 1. Invalid padding containing a 0-bit
        // Single byte 0x00 is invalid padding (contains zero bits)
        assert!(qpack_huffman_decode(&[0x00]).is_err());

        // 2. Padding longer than 7 bits
        // Two bytes of all 1s (0xFF, 0xFF): 16 bits of 1s (exceeds 7-bit padding limit)
        assert!(qpack_huffman_decode(&[0xFF, 0xFF]).is_err());

        // 3. Huffman stream with EOS symbol (symbol 256: 30 bits of 1s)
        // 0x3fffffff = 30 ones + 2 padding ones = 4 bytes of 0xFF
        assert!(qpack_huffman_decode(&[0xFF, 0xFF, 0xFF, 0xFF]).is_err());
    }

    #[test]
    fn test_parse_h3_response_status_static_indices() {
        // RFC 9204 Appendix A Static Table entries for :status:
        // Index 24: 103 (0xC0 | 24 = 0xD8)
        assert_eq!(parse_h3_response_status(&[0x00, 0x00, 0xD8]).unwrap(), 103);
        // Index 25: 200 (0xC0 | 25 = 0xD9)
        assert_eq!(parse_h3_response_status(&[0x00, 0x00, 0xD9]).unwrap(), 200);
        // Index 26: 304 (0xC0 | 26 = 0xDA)
        assert_eq!(parse_h3_response_status(&[0x00, 0x00, 0xDA]).unwrap(), 304);
        // Index 27: 404 (0xC0 | 27 = 0xDB)
        assert_eq!(parse_h3_response_status(&[0x00, 0x00, 0xDB]).unwrap(), 404);
        // Index 28: 503 (0xC0 | 28 = 0xDC)
        assert_eq!(parse_h3_response_status(&[0x00, 0x00, 0xDC]).unwrap(), 503);

        // Multi-byte static table entries (Index >= 63, prefix 63 = 0x3F):
        // Index 63: 100 -> [0xFF, 0x00]
        assert_eq!(parse_h3_response_status(&[0x00, 0x00, 0xFF, 0x00]).unwrap(), 100);
        // Index 64: 204 -> [0xFF, 0x01]
        assert_eq!(parse_h3_response_status(&[0x00, 0x00, 0xFF, 0x01]).unwrap(), 204);
        // Index 67: 400 -> [0xFF, 0x04]
        assert_eq!(parse_h3_response_status(&[0x00, 0x00, 0xFF, 0x04]).unwrap(), 400);
        // Index 68: 403 -> [0xFF, 0x05] (CRITICAL AUDIT ITEM)
        assert_eq!(parse_h3_response_status(&[0x00, 0x00, 0xFF, 0x05]).unwrap(), 403);
        // Index 71: 500 -> [0xFF, 0x08] (CRITICAL AUDIT ITEM)
        assert_eq!(parse_h3_response_status(&[0x00, 0x00, 0xFF, 0x08]).unwrap(), 500);
    }

    #[test]
    fn test_parse_h3_response_status_literal_name_ref_raw_and_huffman() {
        // Name Reference to Static Table Index 25 (:status)
        // Prefix: 0x40 (Literal Name Ref) | 0x10 (Static) | 0x0F (4-bit prefix full) = 0x5F
        // Index continuation: 25 - 15 = 10 (0x0A) -> [0x5F, 0x0A]

        // 1. Raw ASCII 407
        let mut p407 = vec![0x00, 0x00, 0x5F, 0x0A];
        p407.push(0x03); // Raw string length 3
        p407.extend_from_slice(b"407");
        assert_eq!(parse_h3_response_status(&p407).unwrap(), 407);

        // 2. Raw ASCII 429
        let mut p429 = vec![0x00, 0x00, 0x5F, 0x0A];
        p429.push(0x03);
        p429.extend_from_slice(b"429");
        assert_eq!(parse_h3_response_status(&p429).unwrap(), 429);

        // 3. Huffman encoded status "429"
        let huff_429 = qpack_huffman_encode(b"429");
        let mut p429_huff = vec![0x00, 0x00, 0x5F, 0x0A];
        p429_huff.push(0x80 | (huff_429.len() as u8)); // H=1 + length
        p429_huff.extend_from_slice(&huff_429);
        assert_eq!(parse_h3_response_status(&p429_huff).unwrap(), 429);

        // 4. Huffman encoded status "200"
        let huff_200 = qpack_huffman_encode(b"200");
        let mut p200_huff = vec![0x00, 0x00, 0x5F, 0x0A];
        p200_huff.push(0x80 | (huff_200.len() as u8));
        p200_huff.extend_from_slice(&huff_200);
        assert_eq!(parse_h3_response_status(&p200_huff).unwrap(), 200);
    }

    #[test]
    fn test_parse_h3_response_status_literal_name_literal_value() {
        // Section 4.5.6: Literal Field Line with Literal Name (001 N H NameLen(3+))
        // Raw name ":status" (7 bytes) -> encode_qpack_int with 3 bits prefix and mask 0x20:
        // Max 3-bit prefix is 7. Since 7 == 7, prefix is 7 (0x27), continuation is 0 (0x00).
        let mut payload = vec![0x00, 0x00];
        encode_qpack_int(&mut payload, 3, 0x20, 7);
        payload.extend_from_slice(b":status");
        payload.push(0x03);
        payload.extend_from_slice(b"403");
        assert_eq!(parse_h3_response_status(&payload).unwrap(), 403);

        // Huffman name ":status" and Huffman value "503"
        let huff_name = qpack_huffman_encode(b":status");
        let huff_val = qpack_huffman_encode(b"503");
        let mut huff_payload = vec![0x00, 0x00];
        // 0x20 | 0x08 (H=1) = 0x28 mask
        encode_qpack_int(&mut huff_payload, 3, 0x28, huff_name.len() as u64);
        huff_payload.extend_from_slice(&huff_name);
        huff_payload.push(0x80 | (huff_val.len() as u8));
        huff_payload.extend_from_slice(&huff_val);
        assert_eq!(parse_h3_response_status(&huff_payload).unwrap(), 503);
    }

    #[test]
    fn test_parse_h3_response_status_ignores_foreign_digits_in_other_headers() {
        // Response with two headers:
        // 1. content-length: 200 (Name Reference Index 29, static)
        // 2. :status: 403 (Static Table Index 68 -> [0xFF, 0x05])
        // The old buggy parser scanned for ASCII digits "200" and returned 200 OK!
        // The compliant QPACK parser MUST return 403!
        let mut payload = vec![0x00, 0x00];

        // Header 1: content-length (Index 29) = "200"
        // 0x40 | 0x10 | 0x0F = 0x5F; 29 - 15 = 14 (0x0E) -> [0x5F, 0x0E]
        payload.extend_from_slice(&[0x5F, 0x0E, 0x03, b'2', b'0', b'0']);

        // Header 2: :status = 403 (Index 68)
        payload.extend_from_slice(&[0xFF, 0x05]);

        let status = parse_h3_response_status(&payload).expect("parsed successfully");
        assert_eq!(status, 403, "must parse actual :status 403, NOT foreign digits 200 from content-length");
    }

    #[test]
    fn test_parse_h3_response_status_rejects_missing_and_duplicate_status() {
        // 1. Missing :status pseudo-header
        // Payload with only content-length: 100
        let only_cl = vec![0x00, 0x00, 0x5F, 0x0E, 0x03, b'1', b'0', b'0'];
        let err = parse_h3_response_status(&only_cl).unwrap_err();
        assert!(err.contains("missing :status"));

        // 2. Duplicate :status pseudo-header
        // Static 200 (0xD9) followed by Static 404 (0xDB)
        let dup_status = vec![0x00, 0x00, 0xD9, 0xDB];
        let err = parse_h3_response_status(&dup_status).unwrap_err();
        assert!(err.contains("duplicate :status"));

        // 3. Non-numeric status value
        let mut non_num = vec![0x00, 0x00, 0x5F, 0x0A, 0x03];
        non_num.extend_from_slice(b"abc");
        let err = parse_h3_response_status(&non_num).unwrap_err();
        assert!(err.contains("non-numeric :status"));

        // 4. Out-of-range status value (999)
        let mut out_of_range = vec![0x00, 0x00, 0x5F, 0x0A, 0x03];
        out_of_range.extend_from_slice(b"999");
        let err = parse_h3_response_status(&out_of_range).unwrap_err();
        assert!(err.contains("out-of-range :status"));
    }

    #[test]
    fn test_parse_h3_response_status_malformed_input_no_panic() {
        // Truncated prefix (< 2 bytes)
        assert!(parse_h3_response_status(&[]).is_err());
        assert!(parse_h3_response_status(&[0x00]).is_err());

        // Truncated multi-byte integer
        assert!(parse_h3_response_status(&[0x00, 0x00, 0xFF]).is_err());

        // Truncated string literal (claims 10 bytes, has 2)
        assert!(parse_h3_response_status(&[0x00, 0x00, 0x5F, 0x0A, 0x0A, b'2', b'0']).is_err());
    }

    #[test]
    fn test_smoltcp_stack_types() {
        use smoltcp::iface::{Config, Interface, SocketSet};
        use smoltcp::phy::{Device, DeviceCapabilities, Medium, RxToken, TxToken};
        use smoltcp::socket::tcp::{self, Socket as TcpSocket};
        use smoltcp::time::Instant as SmolInstant;
        use smoltcp::wire::{
            HardwareAddress, IpAddress, IpCidr, IpEndpoint, Ipv4Address, Ipv4Cidr,
        };

        struct TestDev {
            rx: std::collections::VecDeque<Vec<u8>>,
            tx: std::collections::VecDeque<Vec<u8>>,
        }
        impl Device for TestDev {
            type RxToken<'a>
                = TestRx
            where
                Self: 'a;
            type TxToken<'a>
                = TestTx<'a>
            where
                Self: 'a;
            fn receive(
                &mut self,
                _timestamp: SmolInstant,
            ) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
                self.rx
                    .pop_front()
                    .map(|p| (TestRx(p), TestTx(&mut self.tx)))
            }
            fn transmit(&mut self, _timestamp: SmolInstant) -> Option<Self::TxToken<'_>> {
                Some(TestTx(&mut self.tx))
            }
            fn capabilities(&self) -> DeviceCapabilities {
                let mut c = DeviceCapabilities::default();
                c.medium = Medium::Ip;
                c.max_transmission_unit = 1500;
                c
            }
        }
        struct TestRx(Vec<u8>);
        impl RxToken for TestRx {
            fn consume<R, F>(mut self, f: F) -> R
            where
                F: FnOnce(&mut [u8]) -> R,
            {
                f(&mut self.0)
            }
        }
        struct TestTx<'a>(&'a mut std::collections::VecDeque<Vec<u8>>);
        impl<'a> TxToken for TestTx<'a> {
            fn consume<R, F>(self, len: usize, f: F) -> R
            where
                F: FnOnce(&mut [u8]) -> R,
            {
                let mut buf = vec![0u8; len];
                let r = f(&mut buf);
                self.0.push_back(buf);
                r
            }
        }

        let mut dev = TestDev {
            rx: std::collections::VecDeque::new(),
            tx: std::collections::VecDeque::new(),
        };
        let mut cfg = Config::new(HardwareAddress::Ip);
        cfg.random_seed = 12345;
        let mut iface = Interface::new(cfg, &mut dev, SmolInstant::now());
        use smoltcp::wire::{Ipv6Address, Ipv6Cidr};
        let client_v6 = Ipv6Address::new(0x2606, 0x4700, 0x110, 0, 0, 0, 0, 2);
        iface.update_ip_addrs(|addrs| {
            let _ = addrs.push(IpCidr::Ipv4(Ipv4Cidr::new(
                Ipv4Address::new(172, 16, 0, 2),
                32,
            )));
            let _ = addrs.push(IpCidr::Ipv6(Ipv6Cidr::new(client_v6, 128)));
        });
        iface
            .routes_mut()
            .add_default_ipv4_route(Ipv4Address::new(172, 16, 0, 2))
            .unwrap();
        iface
            .routes_mut()
            .add_default_ipv6_route(client_v6)
            .unwrap();

        let tcp_rx_buffer = tcp::SocketBuffer::new(vec![0; 65535]);
        let tcp_tx_buffer = tcp::SocketBuffer::new(vec![0; 65535]);
        let mut socket = TcpSocket::new(tcp_rx_buffer, tcp_tx_buffer);
        let remote = IpEndpoint::new(IpAddress::Ipv4(Ipv4Address::new(149, 154, 167, 50)), 443);
        let local = IpEndpoint::new(IpAddress::Ipv4(Ipv4Address::new(172, 16, 0, 2)), 45000);
        socket.connect(iface.context(), remote, local).unwrap();
        let mut sockets = SocketSet::new(vec![]);
        let handle = sockets.add(socket);
        assert!(sockets.get::<TcpSocket>(handle).is_open());

        // Polling iface should generate a SYN packet into dev.tx
        iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
        assert!(!dev.tx.is_empty(), "must have generated SYN packet");
        let syn_pkt = dev.tx.pop_front().unwrap();
        assert!(syn_pkt.len() >= 40, "IPv4 + TCP header >= 40 bytes");
        // Verify IPv4 version (0x45)
        assert_eq!(syn_pkt[0] >> 4, 4);
        // Verify protocol TCP (6)
        assert_eq!(syn_pkt[9], 6);

        // IPv6 TCP socket test
        let target_v6 = Ipv6Address::new(0x2001, 0x4860, 0x4860, 0, 0, 0, 0, 0x8888);
        let remote_v6 = IpEndpoint::new(IpAddress::Ipv6(target_v6), 443);
        let local_v6 = IpEndpoint::new(IpAddress::Ipv6(client_v6), 46000);
        let mut socket6 = TcpSocket::new(
            tcp::SocketBuffer::new(vec![0; 65535]),
            tcp::SocketBuffer::new(vec![0; 65535]),
        );
        socket6.connect(iface.context(), remote_v6, local_v6).unwrap();
        let handle6 = sockets.add(socket6);
        assert!(sockets.get::<TcpSocket>(handle6).is_open());

        iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
        let syn6_pkt = dev.tx.pop_back().unwrap();
        assert!(syn6_pkt.len() >= 60, "IPv6 header (40B) + TCP header (20B) >= 60B");
        assert_eq!(syn6_pkt[0] >> 4, 6, "must be IPv6 packet");
        assert_eq!(syn6_pkt[6], 6, "IPv6 next header must be TCP");

        // Test quinn::StreamId conversion
        let sid = quinn::StreamId::from(quinn::VarInt::from_u32(12));
        assert_eq!(sid.index(), 3);
        assert_eq!(u64::from(quinn::VarInt::from(sid)), 12);
        let qid = (u64::from(quinn::VarInt::from(sid))) / 4;
        assert_eq!(qid, 3);
        assert_eq!(sid.index(), qid);
    }

    #[test]
    fn test_h3_datagram_roundtrip() {
        let fake_ip_pkt = vec![
            0x45, 0x00, 0x00, 0x28, 0x12, 0x34, 0x00, 0x00, 0x40, 0x06, 0x00, 0x00,
        ];
        let encoded = encode_h3_datagram(42, 0, &fake_ip_pkt);
        let (qid, ctx, payload) = decode_h3_datagram(&encoded).expect("must decode h3 datagram");
        assert_eq!(qid, 42);
        assert_eq!(ctx, 0);
        assert_eq!(payload, fake_ip_pkt.as_slice());
    }

    #[tokio::test]
    async fn test_parse_target_endpoint_ipv4_and_ipv6_and_brackets() {
        // 1. IPv4 literal with port
        let (ip1, p1) = parse_target_endpoint("149.154.167.50:443").await.unwrap();
        assert_eq!(ip1, std::net::IpAddr::V4(std::net::Ipv4Addr::new(149, 154, 167, 50)));
        assert_eq!(p1, 443);

        // 2. IPv4 literal with brackets
        let (ip2, p2) = parse_target_endpoint("[91.108.56.165]:80").await.unwrap();
        assert_eq!(ip2, std::net::IpAddr::V4(std::net::Ipv4Addr::new(91, 108, 56, 165)));
        assert_eq!(p2, 80);

        // 3. IPv6 literal with brackets and port
        let (ip3, p3) = parse_target_endpoint("[2606:4700:4700::1111]:443").await.unwrap();
        assert_eq!(ip3, std::net::IpAddr::V6("2606:4700:4700::1111".parse().unwrap()));
        assert_eq!(p3, 443);

        // 4. IPv6 literal with brackets without port
        let (ip4, p4) = parse_target_endpoint("[2001:db8::1]").await.unwrap();
        assert_eq!(ip4, std::net::IpAddr::V6("2001:db8::1".parse().unwrap()));
        assert_eq!(p4, 443);

        // 5. Raw IPv6 literal without brackets
        let (ip5, p5) = parse_target_endpoint("2001:db8::1").await.unwrap();
        assert_eq!(ip5, std::net::IpAddr::V6("2001:db8::1".parse().unwrap()));
        assert_eq!(p5, 443);

        // 6. Invalid / empty strings
        assert!(parse_target_endpoint("").await.is_err());
        assert!(parse_target_endpoint("[2001:db8::1").await.is_err());
    }

    #[tokio::test]
    async fn test_parse_target_ipv4_rejects_ipv6_with_predictable_error() {
        let err = parse_target_ipv4("[2606:4700:4700::1111]:443").await.unwrap_err();
        assert!(err.contains("no IPv4 address resolved"));

        let err2 = parse_target_ipv4("2001:db8::1").await.unwrap_err();
        assert!(err2.contains("no IPv4 address resolved"));
    }

    #[tokio::test]
    async fn test_parse_target_ipv4() {
        let (ip, port) = parse_target_ipv4("149.154.167.50:443").await.unwrap();
        assert_eq!(ip, std::net::Ipv4Addr::new(149, 154, 167, 50));
        assert_eq!(port, 443);

        let (ip2, port2) = parse_target_ipv4("[91.108.56.165]:80").await.unwrap();
        assert_eq!(ip2, std::net::Ipv4Addr::new(91, 108, 56, 165));
        assert_eq!(port2, 80);

        let (ip3, port3) = parse_target_ipv4("127.0.0.1:10808").await.unwrap();
        assert_eq!(ip3, std::net::Ipv4Addr::new(127, 0, 0, 1));
        assert_eq!(port3, 10808);
    }

    #[test]
    fn test_virtual_tun_device_encapsulation() {
        let mut dev = VirtualTunDevice::new();
        let mut cfg = SmolConfig::new(HardwareAddress::Ip);
        cfg.random_seed = 54321;
        let mut iface = Interface::new(cfg, &mut dev, SmolInstant::now());
        let local_ip = Ipv4Address::new(172, 16, 0, 2);
        iface.update_ip_addrs(|addrs| {
            let _ = addrs.push(IpCidr::Ipv4(Ipv4Cidr::new(local_ip, 32)));
        });
        iface.routes_mut().add_default_ipv4_route(local_ip).unwrap();

        let rx_buf = tcp::SocketBuffer::new(vec![0; 65535]);
        let tx_buf = tcp::SocketBuffer::new(vec![0; 65535]);
        let mut socket = TcpSocket::new(rx_buf, tx_buf);
        let remote = IpEndpoint::new(IpAddress::Ipv4(Ipv4Address::new(149, 154, 167, 50)), 443);
        let local = IpEndpoint::new(IpAddress::Ipv4(local_ip), 45100);
        socket.connect(iface.context(), remote, local).unwrap();

        let mut sockets = SocketSet::new(vec![]);
        let handle = sockets.add(socket);

        // Interface poll triggers SYN packet generation via VirtualTunDevice
        iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
        assert_eq!(dev.tx_queue.len(), 1, "must generate exactly 1 SYN packet");
        let syn_packet = dev.tx_queue.pop_front().unwrap();

        // Validate IPv4 header (RFC 791)
        assert_eq!(syn_packet[0] >> 4, 4, "IPv4 version must be 4");
        let ihl = (syn_packet[0] & 0x0F) as usize * 4;
        assert!(ihl >= 20, "IHL must be at least 20 bytes");
        assert_eq!(syn_packet[9], 6, "IP protocol must be TCP (6)");
        assert_eq!(
            &syn_packet[12..16],
            &[172, 16, 0, 2],
            "Source IP must be 172.16.0.2"
        );
        assert_eq!(
            &syn_packet[16..20],
            &[149, 154, 167, 50],
            "Dest IP must be 149.154.167.50"
        );

        // Validate TCP header (RFC 793)
        let tcp_hdr = &syn_packet[ihl..];
        let src_port = u16::from_be_bytes([tcp_hdr[0], tcp_hdr[1]]);
        let dst_port = u16::from_be_bytes([tcp_hdr[2], tcp_hdr[3]]);
        assert_eq!(src_port, 45100);
        assert_eq!(dst_port, 443);
        let flags = tcp_hdr[13];
        assert_eq!(flags & 0x02, 0x02, "SYN flag must be set");

        // Encapsulate into RFC 9297 / RFC 9484 HTTP/3 Datagram
        let qid = 0u64;
        let dgram = encode_h3_datagram(qid, 0, &syn_packet);
        let (dec_qid, dec_ctx, dec_payload) = decode_h3_datagram(&dgram).unwrap();
        assert_eq!(dec_qid, 0);
        assert_eq!(dec_ctx, 0);
        assert_eq!(dec_payload, syn_packet.as_slice());
        assert!(sockets.get::<TcpSocket>(handle).is_open());
    }

    #[test]
    fn test_virtual_tun_device_ipv6_encapsulation() {
        let mut dev = VirtualTunDevice::new();
        let mut cfg = SmolConfig::new(HardwareAddress::Ip);
        cfg.random_seed = 54321;
        let mut iface = Interface::new(cfg, &mut dev, SmolInstant::now());
        let local_ip_v6 = "2606:4700:110:812c:a554:b442:26d4:1330".parse::<std::net::Ipv6Addr>().unwrap();
        let smol_local_v6 = Ipv6Address::from(local_ip_v6);

        iface.update_ip_addrs(|addrs| {
            let _ = addrs.push(IpCidr::Ipv6(Ipv6Cidr::new(smol_local_v6, 128)));
        });
        iface.routes_mut().add_default_ipv6_route(smol_local_v6).unwrap();

        let rx_buf = tcp::SocketBuffer::new(vec![0; 65535]);
        let tx_buf = tcp::SocketBuffer::new(vec![0; 65535]);
        let mut socket = TcpSocket::new(rx_buf, tx_buf);
        let target_v6 = "2606:4700:4700::1111".parse::<std::net::Ipv6Addr>().unwrap();
        let smol_target_v6 = Ipv6Address::from(target_v6);
        let remote = IpEndpoint::new(IpAddress::Ipv6(smol_target_v6), 443);
        let local = IpEndpoint::new(IpAddress::Ipv6(smol_local_v6), 45200);
        socket.connect(iface.context(), remote, local).unwrap();

        let mut sockets = SocketSet::new(vec![]);
        let handle = sockets.add(socket);

        // Interface poll triggers IPv6 SYN packet generation via VirtualTunDevice
        iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
        assert_eq!(dev.tx_queue.len(), 1, "must generate exactly 1 IPv6 SYN packet");
        let syn_packet = dev.tx_queue.pop_front().unwrap();

        // Validate IPv6 header (RFC 8200)
        assert_eq!(syn_packet[0] >> 4, 6, "IPv6 version must be 6");
        assert_eq!(syn_packet[6], 6, "Next Header must be TCP (6)");
        assert_eq!(
            &syn_packet[8..24],
            &local_ip_v6.octets(),
            "Source IP must match client IPv6"
        );
        assert_eq!(
            &syn_packet[24..40],
            &target_v6.octets(),
            "Dest IP must match target IPv6"
        );

        // Validate TCP header (RFC 793) at offset 40 (standard IPv6 header size)
        let tcp_hdr = &syn_packet[40..];
        let src_port = u16::from_be_bytes([tcp_hdr[0], tcp_hdr[1]]);
        let dst_port = u16::from_be_bytes([tcp_hdr[2], tcp_hdr[3]]);
        assert_eq!(src_port, 45200);
        assert_eq!(dst_port, 443);
        let flags = tcp_hdr[13];
        assert_eq!(flags & 0x02, 0x02, "SYN flag must be set");

        // Encapsulate into RFC 9297 / RFC 9484 HTTP/3 Datagram
        let qid = 4u64;
        let dgram = encode_h3_datagram(qid, 0, &syn_packet);
        let (dec_qid, dec_ctx, dec_payload) = decode_h3_datagram(&dgram).unwrap();
        assert_eq!(dec_qid, 4);
        assert_eq!(dec_ctx, 0);
        assert_eq!(dec_payload, syn_packet.as_slice());
        assert!(sockets.get::<TcpSocket>(handle).is_open());
    }

    #[tokio::test]
    async fn test_masque_smoltcp_bridge_rejects_ipv6_without_client_ipv6() {
        let target_v6_addr = "[2606:4700:4700::1111]:443";
        let (parsed_ip, port) = parse_target_endpoint(target_v6_addr).await.unwrap();
        assert!(matches!(parsed_ip, std::net::IpAddr::V6(_)));
        assert_eq!(port, 443);

        // When client has no IPv6 configured or assigned, bridge validation must fail with AddrNotAvailable
        let effective_client_v6: Option<std::net::Ipv6Addr> = None;
        let check_res = match parsed_ip {
            std::net::IpAddr::V6(_target_v6) => {
                if effective_client_v6.is_none() {
                    Err(std::io::Error::new(
                        std::io::ErrorKind::AddrNotAvailable,
                        format!("IPv6 target '{}' is unsupported: no client IPv6 configured or assigned", target_v6_addr),
                    ))
                } else {
                    Ok(())
                }
            }
            std::net::IpAddr::V4(_) => Ok(()),
        };

        assert!(check_res.is_err());
        assert_eq!(check_res.unwrap_err().kind(), std::io::ErrorKind::AddrNotAvailable);
    }

    #[test]
    fn test_capsule_address_assign_roundtrip() {
        let v4_addr = std::net::Ipv4Addr::new(172, 16, 0, 2);
        let v6_addr: std::net::Ipv6Addr = "2606:4700:110:8750:39a1:ef87:7582:bfec".parse().unwrap();
        let addrs = vec![
            AssignedAddress {
                request_id: 0,
                ip_version: 4,
                ip_addr: std::net::IpAddr::V4(v4_addr),
                prefix_len: 32,
            },
            AssignedAddress {
                request_id: 1,
                ip_version: 6,
                ip_addr: std::net::IpAddr::V6(v6_addr),
                prefix_len: 128,
            },
        ];

        let encoded = encode_address_assign(&addrs);
        let (capsule, consumed) =
            decode_next_capsule(&encoded).expect("must decode ADDRESS_ASSIGN capsule");
        assert_eq!(consumed, encoded.len());

        match capsule {
            Capsule::AddressAssign(decoded_addrs) => {
                assert_eq!(decoded_addrs.len(), 2);
                assert_eq!(decoded_addrs[0].request_id, 0);
                assert_eq!(decoded_addrs[0].ip_version, 4);
                assert_eq!(decoded_addrs[0].ip_addr, std::net::IpAddr::V4(v4_addr));
                assert_eq!(decoded_addrs[0].prefix_len, 32);

                assert_eq!(decoded_addrs[1].request_id, 1);
                assert_eq!(decoded_addrs[1].ip_version, 6);
                assert_eq!(decoded_addrs[1].ip_addr, std::net::IpAddr::V6(v6_addr));
                assert_eq!(decoded_addrs[1].prefix_len, 128);
            }
            other => panic!("expected Capsule::AddressAssign, got {:?}", other),
        }
    }

    #[test]
    fn test_capsule_route_advertisement_roundtrip() {
        let start_v4: std::net::IpAddr = "149.154.167.0".parse().unwrap();
        let end_v4: std::net::IpAddr = "149.154.167.255".parse().unwrap();
        let start_v6: std::net::IpAddr = "2001:db8::1".parse().unwrap();
        let end_v6: std::net::IpAddr = "2001:db8::ff".parse().unwrap();

        let routes = vec![
            IpAddressRange {
                ip_version: 4,
                start_ip: start_v4,
                end_ip: end_v4,
                ip_protocol: 6,
            },
            IpAddressRange {
                ip_version: 6,
                start_ip: start_v6,
                end_ip: end_v6,
                ip_protocol: 6,
            },
        ];

        let encoded = encode_route_advertisement(&routes);
        let (capsule, consumed) =
            decode_next_capsule(&encoded).expect("must decode ROUTE_ADVERTISEMENT capsule");
        assert_eq!(consumed, encoded.len());

        match capsule {
            Capsule::RouteAdvertisement(decoded_routes) => {
                assert_eq!(decoded_routes.len(), 2);
                assert_eq!(decoded_routes[0].ip_version, 4);
                assert_eq!(decoded_routes[0].start_ip, start_v4);
                assert_eq!(decoded_routes[0].end_ip, end_v4);
                assert_eq!(decoded_routes[0].ip_protocol, 6);

                assert_eq!(decoded_routes[1].ip_version, 6);
                assert_eq!(decoded_routes[1].start_ip, start_v6);
                assert_eq!(decoded_routes[1].end_ip, end_v6);
                assert_eq!(decoded_routes[1].ip_protocol, 6);
            }
            other => panic!("expected Capsule::RouteAdvertisement, got {:?}", other),
        }
    }

    #[test]
    fn test_capsule_address_request_roundtrip() {
        let v4_addr = std::net::Ipv4Addr::new(10, 0, 0, 1);
        let reqs = vec![AssignedAddress {
            request_id: 42,
            ip_version: 4,
            ip_addr: std::net::IpAddr::V4(v4_addr),
            prefix_len: 24,
        }];

        let encoded = encode_address_request(&reqs);
        let (capsule, consumed) =
            decode_next_capsule(&encoded).expect("must decode ADDRESS_REQUEST capsule");
        assert_eq!(consumed, encoded.len());

        match capsule {
            Capsule::AddressRequest(decoded_reqs) => {
                assert_eq!(decoded_reqs.len(), 1);
                assert_eq!(decoded_reqs[0].request_id, 42);
                assert_eq!(decoded_reqs[0].ip_version, 4);
                assert_eq!(decoded_reqs[0].ip_addr, std::net::IpAddr::V4(v4_addr));
                assert_eq!(decoded_reqs[0].prefix_len, 24);
            }
            other => panic!("expected Capsule::AddressRequest, got {:?}", other),
        }
    }

    #[test]
    fn test_capsule_unknown_gracefully_skipped() {
        // RFC 9297 Section 3.3: Unknown capsule types MUST be ignored
        let unknown_type = 0x3F88u64;
        let unknown_payload = b"future_masque_capsule_extension_bytes";
        let encoded = encode_capsule(unknown_type, unknown_payload);

        let (capsule, consumed) =
            decode_next_capsule(&encoded).expect("must decode unknown capsule");
        assert_eq!(consumed, encoded.len());

        match capsule {
            Capsule::Unknown {
                capsule_type,
                payload,
            } => {
                assert_eq!(capsule_type, unknown_type);
                assert_eq!(payload, unknown_payload);
            }
            other => panic!("expected Capsule::Unknown, got {:?}", other),
        }
    }

    #[test]
    fn test_h3_capsule_stream_framing() {
        // Test wrapping capsules into HTTP/3 DATA frames (0x00)
        let addr = AssignedAddress {
            request_id: 0,
            ip_version: 4,
            ip_addr: std::net::IpAddr::V4(std::net::Ipv4Addr::new(172, 16, 0, 2)),
            prefix_len: 32,
        };
        let capsule_bytes = encode_address_assign(&[addr]);
        let h3_data_frame = wrap_in_h3_data_frame(&capsule_bytes);

        // De-frame outer H3 DATA frame
        let (ftype, tlen) = decode_varint(&h3_data_frame).expect("varint ftype");
        assert_eq!(ftype, H3_FRAME_DATA);
        let (flen, llen) = decode_varint(&h3_data_frame[tlen..]).expect("varint flen");
        assert_eq!(flen as usize, capsule_bytes.len());
        assert_eq!(h3_data_frame.len(), tlen + llen + capsule_bytes.len());

        // Decode capsule inside frame payload
        let payload = &h3_data_frame[(tlen + llen)..];
        let (capsule, consumed) = decode_next_capsule(payload).expect("decode capsule");
        assert_eq!(consumed, payload.len());
        match capsule {
            Capsule::AddressAssign(addrs) => {
                assert_eq!(addrs.len(), 1);
                assert_eq!(addrs[0].ip_version, 4);
                assert_eq!(
                    addrs[0].ip_addr,
                    std::net::IpAddr::V4(std::net::Ipv4Addr::new(172, 16, 0, 2))
                );
            }
            _ => panic!("expected AddressAssign"),
        }
    }

    #[test]
    fn test_client_capsule_request_uses_address_request_not_assign() {
        // RFC 9484 §4.7.2: Client must use ADDRESS_REQUEST (0x02) to request or propose IP addresses.
        // ADDRESS_ASSIGN (0x01) is used by an endpoint to assign addresses to the peer.
        let v4_req = AssignedAddress {
            request_id: 0,
            ip_version: 4,
            ip_addr: std::net::IpAddr::V4(std::net::Ipv4Addr::new(172, 16, 0, 2)),
            prefix_len: 32,
        };
        let v6_req = AssignedAddress {
            request_id: 1,
            ip_version: 6,
            ip_addr: std::net::IpAddr::V6("2606:4700:110::2".parse().unwrap()),
            prefix_len: 128,
        };

        let encoded_req = encode_address_request(&[v4_req.clone(), v6_req.clone()]);
        // Verify capsule type is 0x02 (CAPSULE_ADDRESS_REQUEST), NOT 0x01 (CAPSULE_ADDRESS_ASSIGN)
        let (capsule_type, _) = decode_varint(&encoded_req).expect("decode varint type");
        assert_eq!(capsule_type, CAPSULE_ADDRESS_REQUEST);
        assert_ne!(capsule_type, CAPSULE_ADDRESS_ASSIGN);

        let (decoded, consumed) = decode_next_capsule(&encoded_req).expect("decode capsule");
        assert_eq!(consumed, encoded_req.len());
        match decoded {
            Capsule::AddressRequest(reqs) => {
                assert_eq!(reqs.len(), 2);
                assert_eq!(reqs[0], v4_req);
                assert_eq!(reqs[1], v6_req);
            }
            other => panic!("expected Capsule::AddressRequest, got {:?}", other),
        }
    }

    #[test]
    fn test_capsule_address_withdrawal_prefix_len_zero() {
        // RFC 9484 §4.7.1: Prefix Length 0 indicates address withdrawal.
        let address_state_v4 = Arc::new(parking_lot::RwLock::new(AddressState::Assigned(
            std::net::Ipv4Addr::new(172, 16, 0, 2),
        )));
        let address_state_v6 = Arc::new(parking_lot::RwLock::new(AddressState::Unassigned));
        let assigned_ipv4 = Arc::new(parking_lot::RwLock::new(Some(std::net::Ipv4Addr::new(
            172, 16, 0, 2,
        ))));
        let assigned_ipv6 = Arc::new(parking_lot::RwLock::new(None));
        let routes = Arc::new(parking_lot::RwLock::new(Vec::new()));

        // Server sends ADDRESS_ASSIGN with prefix_len = 0 to withdraw 172.16.0.2
        let withdraw_capsule = Capsule::AddressAssign(vec![AssignedAddress {
            request_id: 0,
            ip_version: 4,
            ip_addr: std::net::IpAddr::V4(std::net::Ipv4Addr::new(172, 16, 0, 2)),
            prefix_len: 0,
        }]);

        let summary = handle_incoming_capsule(
            withdraw_capsule,
            &address_state_v4,
            &address_state_v6,
            &assigned_ipv4,
            &assigned_ipv6,
            &routes,
        );

        assert!(summary.v4_changed);
        assert!(!summary.v6_changed);
        assert_eq!(*address_state_v4.read(), AddressState::Withdrawn);
        assert_eq!(*assigned_ipv4.read(), None);

        // Effective IP resolution must return None on Withdrawn, even if out-of-band IP was configured!
        let oob_v4 = Some(std::net::Ipv4Addr::new(172, 16, 0, 2));
        let effective = match *address_state_v4.read() {
            AddressState::Assigned(ip) => Some(ip),
            AddressState::Unassigned => oob_v4,
            AddressState::Withdrawn => None,
        };
        assert_eq!(effective, None, "Withdrawn IP must NOT fall back to old source IP");
    }

    #[test]
    fn test_capsule_empty_address_assign_withdraws_all() {
        // RFC 9484 §4.7.1: Empty ADDRESS_ASSIGN withdraws all previously assigned addresses.
        let address_state_v4 = Arc::new(parking_lot::RwLock::new(AddressState::Assigned(
            std::net::Ipv4Addr::new(172, 16, 0, 2),
        )));
        let address_state_v6 = Arc::new(parking_lot::RwLock::new(AddressState::Assigned(
            "2606:4700::1".parse().unwrap(),
        )));
        let assigned_ipv4 = Arc::new(parking_lot::RwLock::new(Some(std::net::Ipv4Addr::new(
            172, 16, 0, 2,
        ))));
        let assigned_ipv6 = Arc::new(parking_lot::RwLock::new(Some(
            "2606:4700::1".parse().unwrap(),
        )));
        let routes = Arc::new(parking_lot::RwLock::new(Vec::new()));

        let empty_capsule = Capsule::AddressAssign(vec![]);
        let summary = handle_incoming_capsule(
            empty_capsule,
            &address_state_v4,
            &address_state_v6,
            &assigned_ipv4,
            &assigned_ipv6,
            &routes,
        );

        assert!(summary.v4_changed);
        assert!(summary.v6_changed);
        assert_eq!(*address_state_v4.read(), AddressState::Withdrawn);
        assert_eq!(*address_state_v6.read(), AddressState::Withdrawn);
        assert_eq!(*assigned_ipv4.read(), None);
        assert_eq!(*assigned_ipv6.read(), None);
    }

    #[test]
    fn test_delayed_assignment_and_reassignment_updates_source_ip_and_routes() {
        let mut dev = VirtualTunDevice::new();
        let mut iface_cfg = SmolConfig::new(HardwareAddress::Ip);
        iface_cfg.random_seed = 0x1234;
        let mut iface = Interface::new(iface_cfg, &mut dev, SmolInstant::now());

        let address_state_v4 = Arc::new(parking_lot::RwLock::new(AddressState::Unassigned));
        let address_state_v6 = Arc::new(parking_lot::RwLock::new(AddressState::Unassigned));
        let assigned_ipv4 = Arc::new(parking_lot::RwLock::new(None));
        let assigned_ipv6 = Arc::new(parking_lot::RwLock::new(None));
        let routes = Arc::new(parking_lot::RwLock::new(Vec::new()));

        // Initial setup with out-of-band IP 172.16.0.2
        let initial_v4 = std::net::Ipv4Addr::new(172, 16, 0, 2);
        update_smoltcp_addresses_and_routes(&mut iface, Some(initial_v4), None);
        assert_eq!(iface.ip_addrs().len(), 1);
        assert_eq!(
            iface.ip_addrs()[0],
            IpCidr::Ipv4(Ipv4Cidr::new(Ipv4Address::new(172, 16, 0, 2), 32))
        );

        // 1. Delayed assignment arrives: server assigns 172.16.1.99
        let delayed_ip = std::net::Ipv4Addr::new(172, 16, 1, 99);
        let delayed_capsule = Capsule::AddressAssign(vec![AssignedAddress {
            request_id: 0,
            ip_version: 4,
            ip_addr: std::net::IpAddr::V4(delayed_ip),
            prefix_len: 32,
        }]);
        let summary = handle_incoming_capsule(
            delayed_capsule,
            &address_state_v4,
            &address_state_v6,
            &assigned_ipv4,
            &assigned_ipv6,
            &routes,
        );
        assert!(summary.v4_changed);
        update_smoltcp_addresses_and_routes(&mut iface, Some(delayed_ip), None);

        // Check that old IP 172.16.0.2 was replaced and is no longer present!
        assert_eq!(iface.ip_addrs().len(), 1);
        assert_eq!(
            iface.ip_addrs()[0],
            IpCidr::Ipv4(Ipv4Cidr::new(Ipv4Address::new(172, 16, 1, 99), 32))
        );

        // 2. Address withdrawal arrives: server withdraws 172.16.1.99
        let withdraw_capsule = Capsule::AddressAssign(vec![AssignedAddress {
            request_id: 0,
            ip_version: 4,
            ip_addr: std::net::IpAddr::V4(delayed_ip),
            prefix_len: 0,
        }]);
        let summary = handle_incoming_capsule(
            withdraw_capsule,
            &address_state_v4,
            &address_state_v6,
            &assigned_ipv4,
            &assigned_ipv6,
            &routes,
        );
        assert!(summary.v4_changed);
        update_smoltcp_addresses_and_routes(&mut iface, None, None);

        // Check that interface has NO IP addresses and default route is cleared!
        assert!(iface.ip_addrs().is_empty());

        // 3. Re-assignment arrives: server assigns new IP 172.16.2.200
        let reassign_ip = std::net::Ipv4Addr::new(172, 16, 2, 200);
        let reassign_capsule = Capsule::AddressAssign(vec![AssignedAddress {
            request_id: 0,
            ip_version: 4,
            ip_addr: std::net::IpAddr::V4(reassign_ip),
            prefix_len: 32,
        }]);
        let summary = handle_incoming_capsule(
            reassign_capsule,
            &address_state_v4,
            &address_state_v6,
            &assigned_ipv4,
            &assigned_ipv6,
            &routes,
        );
        assert!(summary.v4_changed);
        update_smoltcp_addresses_and_routes(&mut iface, Some(reassign_ip), None);

        // Check that interface now has 172.16.2.200 and neither old IP is present!
        assert_eq!(iface.ip_addrs().len(), 1);
        assert_eq!(
            iface.ip_addrs()[0],
            IpCidr::Ipv4(Ipv4Cidr::new(Ipv4Address::new(172, 16, 2, 200), 32))
        );
    }

    #[test]
    fn test_bidirectional_capsules_handling() {
        // Both client and server can send capsules:
        // Client -> Server: ADDRESS_REQUEST
        let req_capsule = encode_address_request(&[AssignedAddress {
            request_id: 10,
            ip_version: 4,
            ip_addr: std::net::IpAddr::V4(std::net::Ipv4Addr::new(10, 20, 30, 40)),
            prefix_len: 32,
        }]);
        let (c1, _) = decode_next_capsule(&req_capsule).expect("decode address request");
        assert!(matches!(c1, Capsule::AddressRequest(_)));

        // Server -> Client: ADDRESS_ASSIGN
        let assign_capsule = encode_address_assign(&[AssignedAddress {
            request_id: 10,
            ip_version: 4,
            ip_addr: std::net::IpAddr::V4(std::net::Ipv4Addr::new(10, 20, 30, 40)),
            prefix_len: 32,
        }]);
        let (c2, _) = decode_next_capsule(&assign_capsule).expect("decode address assign");
        assert!(matches!(c2, Capsule::AddressAssign(_)));

        // Server -> Client: ROUTE_ADVERTISEMENT
        let route_capsule = encode_route_advertisement(&[IpAddressRange {
            ip_version: 4,
            start_ip: std::net::IpAddr::V4(std::net::Ipv4Addr::new(0, 0, 0, 0)),
            end_ip: std::net::IpAddr::V4(std::net::Ipv4Addr::new(255, 255, 255, 255)),
            ip_protocol: 0,
        }]);
        let (c3, _) = decode_next_capsule(&route_capsule).expect("decode route advertisement");
        assert!(matches!(c3, Capsule::RouteAdvertisement(_)));
    }

    #[test]
    fn test_capsule_decoder_separates_malformed_known_from_unknown() {
        // 1. Malformed ADDRESS_ASSIGN with prefix_len > 32 for IPv4
        let mut malformed_assign_v4 = Vec::new();
        encode_varint(&mut malformed_assign_v4, CAPSULE_ADDRESS_ASSIGN);
        let payload_v4 = [
            0x00, // request_id = 0
            4,    // ip_version = 4
            10, 0, 0, 1, // IPv4
            33,   // prefix_len = 33 (INVALID!)
        ];
        encode_varint(&mut malformed_assign_v4, payload_v4.len() as u64);
        malformed_assign_v4.extend_from_slice(&payload_v4);

        let (capsule, consumed) = decode_next_capsule(&malformed_assign_v4).expect("decode malformed assign v4");
        assert_eq!(consumed, malformed_assign_v4.len());
        match capsule {
            Capsule::MalformedKnown { capsule_type, error } => {
                assert_eq!(capsule_type, CAPSULE_ADDRESS_ASSIGN);
                assert!(error.contains("prefix_len"));
            }
            other => panic!("expected Capsule::MalformedKnown, got {:?}", other),
        }

        // 2. Malformed ADDRESS_ASSIGN with prefix_len > 128 for IPv6
        let mut malformed_assign_v6 = Vec::new();
        encode_varint(&mut malformed_assign_v6, CAPSULE_ADDRESS_ASSIGN);
        let mut payload_v6 = Vec::new();
        payload_v6.push(0x01); // request_id = 1
        payload_v6.push(6);    // ip_version = 6
        payload_v6.extend_from_slice(&[0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1]); // IPv6
        payload_v6.push(129);  // prefix_len = 129 (INVALID!)
        encode_varint(&mut malformed_assign_v6, payload_v6.len() as u64);
        malformed_assign_v6.extend_from_slice(&payload_v6);

        let (capsule_v6, consumed_v6) = decode_next_capsule(&malformed_assign_v6).expect("decode malformed assign v6");
        assert_eq!(consumed_v6, malformed_assign_v6.len());
        match capsule_v6 {
            Capsule::MalformedKnown { capsule_type, error } => {
                assert_eq!(capsule_type, CAPSULE_ADDRESS_ASSIGN);
                assert!(error.contains("prefix_len"));
            }
            other => panic!("expected Capsule::MalformedKnown, got {:?}", other),
        }

        // 3. Malformed ROUTE_ADVERTISEMENT with start_ip > end_ip
        let mut malformed_route = Vec::new();
        encode_varint(&mut malformed_route, CAPSULE_ROUTE_ADVERTISEMENT);
        let payload_route = [
            4,             // ip_version = 4
            10, 0, 0, 20,  // start = 10.0.0.20
            10, 0, 0, 10,  // end = 10.0.0.10 (INVALID: start > end!)
            6,             // ip_protocol = 6
        ];
        encode_varint(&mut malformed_route, payload_route.len() as u64);
        malformed_route.extend_from_slice(&payload_route);

        let (capsule_route, consumed_route) = decode_next_capsule(&malformed_route).expect("decode malformed route");
        assert_eq!(consumed_route, malformed_route.len());
        match capsule_route {
            Capsule::MalformedKnown { capsule_type, error } => {
                assert_eq!(capsule_type, CAPSULE_ROUTE_ADVERTISEMENT);
                assert!(error.contains("start") && error.contains("end"));
            }
            other => panic!("expected Capsule::MalformedKnown, got {:?}", other),
        }

        // 4. Truly Unknown capsule type (RFC 9297 §3.3) must remain Capsule::Unknown
        let unknown_type = 0x9999u64;
        let unknown_payload = b"unknown_extension_data";
        let unknown_capsule_bytes = encode_capsule(unknown_type, unknown_payload);
        let (c_unknown, consumed_unk) = decode_next_capsule(&unknown_capsule_bytes).expect("decode unknown");
        assert_eq!(consumed_unk, unknown_capsule_bytes.len());
        match c_unknown {
            Capsule::Unknown { capsule_type, payload } => {
                assert_eq!(capsule_type, unknown_type);
                assert_eq!(payload, unknown_payload);
            }
            other => panic!("expected Capsule::Unknown, got {:?}", other),
        }

        // 5. Verify handle_incoming_capsule safely ignores MalformedKnown without altering state
        let address_state_v4 = Arc::new(parking_lot::RwLock::new(AddressState::Unassigned));
        let address_state_v6 = Arc::new(parking_lot::RwLock::new(AddressState::Unassigned));
        let assigned_ipv4 = Arc::new(parking_lot::RwLock::new(None));
        let assigned_ipv6 = Arc::new(parking_lot::RwLock::new(None));
        let routes = Arc::new(parking_lot::RwLock::new(Vec::new()));

        let summary = handle_incoming_capsule(
            Capsule::MalformedKnown {
                capsule_type: CAPSULE_ADDRESS_ASSIGN,
                error: "test corruption".to_string(),
            },
            &address_state_v4,
            &address_state_v6,
            &assigned_ipv4,
            &assigned_ipv6,
            &routes,
        );
        assert!(!summary.v4_changed);
        assert!(!summary.v6_changed);
        assert!(!summary.routes_changed);
        assert_eq!(*address_state_v4.read(), AddressState::Unassigned);
        assert_eq!(*assigned_ipv4.read(), None);
    }

    #[test]
    fn test_capsule_decoder_checked_arithmetic_32bit_and_oversized() {
        // 1. Oversized capsule length exceeding MAX_CAPSULE_PAYLOAD_SIZE (64KB)
        let mut oversized = Vec::new();
        encode_varint(&mut oversized, CAPSULE_ADDRESS_ASSIGN);
        encode_varint(&mut oversized, (MAX_CAPSULE_PAYLOAD_SIZE as u64) + 100);
        oversized.resize(1024, 0xAA);
        assert!(
            decode_next_capsule(&oversized).is_none(),
            "Oversized capsule length must be safely rejected"
        );

        // 2. 64-bit varint length with high bits set (simulating 32-bit truncation overflow)
        let mut overflow_len_buf = Vec::new();
        encode_varint(&mut overflow_len_buf, CAPSULE_ADDRESS_ASSIGN);
        let huge_len = (1u64 << 33) | 5;
        encode_varint(&mut overflow_len_buf, huge_len);
        overflow_len_buf.extend_from_slice(&[1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
        assert!(
            decode_next_capsule(&overflow_len_buf).is_none(),
            "64-bit huge length must not truncate to 5 on 32-bit platforms and must return None"
        );

        // 3. Incomplete varint at buffer end
        let partial_buf = [0x40]; // 2-byte varint indicator, but only 1 byte present
        assert!(decode_next_capsule(&partial_buf).is_none());
    }

    // -----------------------------------------------------------------------
    // TSK-M04: Integration Mock MASQUE Server & Full-Stack Tunneled Echo Tests
    // -----------------------------------------------------------------------

    const MOCK_SERVER_CERT_B64: &str = "\
MIICyTCCAbGgAwIBAgIJAKX5+7co0bqCMA0GCSqGSIb3DQEBCwUAMBQxEjAQBgNV\
BAMTCWxvY2FsaG9zdDAeFw0yNjA5MDgyMjU2MThaFw0zNjA5MDkyMjU2MThaMBQx\
EjAQBgNVBAMTCWxvY2FsaG9zdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoC\
ggEBAMYIG+XOuvbxyd9bauFs7Mud9F4hJpsmYfFx2HYPhq29wjRBudx2k1+w8vYa\
TouioczKhKzcV8xcrzOcI41NgQahBNUukbqVvyABiMw/TcdtH2USXCbT4mYl+0el\
UZB7GxYUeRLPcgnrNO5B1MVm3wSxoxigcYliyG4BVJID0wfKaaSBCS3KEs9xQ/zw\
3wPoeeXYlTubg5jv98KL/0uX+pUgK8r7DdFeplBHDfVAN0vk8EXO/E4qr7q0PIjZ\
szG//v8mkTFdrrVg7OrKfq+8UmV3kxRXrRiGXSRQBy8vuB5fVlbT8wi8kd+QnoYC\
X4oMas10koDj9C4hLYfLp901yGUCAwEAAaMeMBwwGgYDVR0RBBMwEYIJbG9jYWxo\
b3N0hwR/AAABMA0GCSqGSIb3DQEBCwUAA4IBAQC+BiaUCIclBznt2YmR7Cm7sgEa\
tbdR80ve9fQA3U/9bII0ychiYFj4sybo7owzk3u9kq5ARapZQB1WMKWM6a9zZPmp\
/7uUvjVXoxQs7gdIRdf8+rbNqVvuknsKTGVvPVtjh8WOKiBzYFjaFzBYVPTWnzLE\
MMDaUbyqZV/IFDuaGAFMXtc9qpVF8DtRqoJP5cuhwfe3A8uVbSBLLPI7wZKcxJoY\
lyOwsq7e8jziNdQGishyjqc2UdC6Pgw2Kn+65DRlu/rtG/sfV2tZ2RLYHHBGAVEJ\
+ZtpqNgIUu6NTeHOYBUw+99EpPZi1f7tgaSVz0WqJcxJOgIcE4lnc+pgyV6+";

    const MOCK_SERVER_KEY_B64: &str = "\
MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQDGCBvlzrr28cnf\
W2rhbOzLnfReISabJmHxcdh2D4atvcI0QbncdpNfsPL2Gk6LoqHMyoSs3FfMXK8z\
nCONTYEGoQTVLpG6lb8gAYjMP03HbR9lElwm0+JmJftHpVGQexsWFHkSz3IJ6zTu\
QdTFZt8EsaMYoHGJYshuAVSSA9MHymmkgQktyhLPcUP88N8D6Hnl2JU7m4OY7/fC\
i/9Ll/qVICvK+w3RXqZQRw31QDdL5PBFzvxOKq+6tDyI2bMxv/7/JpExXa61YOzq\
yn6vvFJld5MUV60Yhl0kUAcvL7geX1ZW0/MIvJHfkJ6GAl+KDGrNdJKA4/QuIS2H\
y6fdNchlAgMBAAECggEBAKkqZCp73tr00S4sObE4C1AlLor6ZrBCqYhHaFHyEWp5\
n1xkiRD7eglUbzgsYMyHsQ/wMEY6NAYvZrr/tp8OhhnTkv1JOtPY99uvc9yGdzrU\
zOzaMj444j3AVFdvsa1qfEjwVDztWM2AT0b4lXnL1td7p4dyB4sFB5JxMH1LJwqP\
LSIpUsHImy0R3bCPrRXVTstuSs1duVSh0LzSYdg0doW2BV3vNgC0BwAWPxRhzyxs\
M6YFlrA6XtIHdtxI+VVQWCteaTAY/7jT/+ZBXy0arDDaghr2XFYspSlE6vlyU07c\
cdjMK2p7Gn5paD/Rh72lF2HqgUPABVJl/TuKypTfuEECgYEA758L0Sxzj4UB1LNG\
qeWjriTjThezKIr0DLLSp2tgXXQV9fPELA2NLjJvSPn8wNI//vamXZg0clqsEhQY\
RRVAAOk8W/XCMg1xbYOzHJ+toqoWVNQKcbZQVUzN4dRlsilwrx8EE68SGp41WsoN\
rg1OVte0nvWercUIj0pZJwCD3a8CgYEA05FRRdsFdhhnoVIXO3VSmkqqZyH3ZZRV\
vR7tg988vbas8vNvRe0u3TXHrV/JVhJbtJK4QWaATdp6sxvcxIaLDUAG8MIlQU/U\
UGJ56OkW1po0vv6P2cyM0v2Uow62EHym8xCa9K8Ep208ptQzlF8y5cL3LOmSpAyQ\
wTSOyix4NCsCgYEA4Szim/RbvBjPeaPm2a1UvUco26/lykmZwX0h+/YpnmiCYphq\
VsN9UlZOwZM587EgNmZuPDHVx0hxvqNnkzO+65xB/mDZ1tNPLgy++j0LnHqddaW1\
EtqybxY3uuovjtbmb4qD+ENijeTyWpjZdiBG59DYiTBjpwQrYQwK713KjT0CgYAT\
iYPb05H5id9oAlCq6Par0OFTjMtv0fbo9AYy+1Z8nnXyKZDJAFInk2PRGttY23Ek\
h7gEMhb/GYHjMFt+un30x0jcytDt6xVMJwvi+kNlpe/hA1j9X+pRQaGMPTuK4jf4\
kjv9BHyFiYzkSwxIU1I77Xkt6JPmGlLzxIq7GMgw6QKBgQCzR9whDxHz7OOnn8q7\
8dlF2pc5ZJhHrre6ky3v8JBIqHIAPIMnah24G3Iv3+TVZS/FF1fDhIu6s7ycMSwP\
z0psoM1GQlJ+8jC6f7D+RQ8hnt0wMhqgJkJvwfn8Es4FEGKxglKLRmJCd/0iOYOr\
AqkxicUTJL062AfcY8ynPW+weQ==";

    fn build_mock_ipv4_tcp_packet(
        src_ip: std::net::Ipv4Addr,
        dst_ip: std::net::Ipv4Addr,
        src_port: u16,
        dst_port: u16,
        seq: u32,
        ack: u32,
        flags: u8,
        payload: &[u8],
    ) -> Vec<u8> {
        let ip_header_len = 20usize;
        let tcp_header_len = 20usize;
        let total_len = ip_header_len + tcp_header_len + payload.len();

        let mut pkt = vec![0u8; total_len];

        // 1. IPv4 Header (RFC 791)
        pkt[0] = 0x45; // Version 4, IHL 5 (20 bytes)
        pkt[1] = 0x00; // DSCP / ECN
        pkt[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
        pkt[4..6].copy_from_slice(&1u16.to_be_bytes()); // Identification
        pkt[6..8].copy_from_slice(&0x4000u16.to_be_bytes()); // Flags (Don't Fragment)
        pkt[8] = 64; // TTL
        pkt[9] = 6; // Protocol = TCP
        pkt[10..12].copy_from_slice(&[0, 0]); // Checksum placeholder
        pkt[12..16].copy_from_slice(&src_ip.octets());
        pkt[16..20].copy_from_slice(&dst_ip.octets());

        let ip_checksum = calc_internet_checksum(&pkt[0..20]);
        pkt[10..12].copy_from_slice(&ip_checksum.to_be_bytes());

        // 2. TCP Header (RFC 793)
        let tcp_offset = 20;
        pkt[tcp_offset..tcp_offset + 2].copy_from_slice(&src_port.to_be_bytes());
        pkt[tcp_offset + 2..tcp_offset + 4].copy_from_slice(&dst_port.to_be_bytes());
        pkt[tcp_offset + 4..tcp_offset + 8].copy_from_slice(&seq.to_be_bytes());
        pkt[tcp_offset + 8..tcp_offset + 12].copy_from_slice(&ack.to_be_bytes());
        pkt[tcp_offset + 12] = 0x50; // Data offset: 5 (20 bytes)
        pkt[tcp_offset + 13] = flags;
        pkt[tcp_offset + 14..tcp_offset + 16].copy_from_slice(&65535u16.to_be_bytes()); // Window Size
        pkt[tcp_offset + 16..tcp_offset + 18].copy_from_slice(&[0, 0]); // Checksum placeholder
        pkt[tcp_offset + 18..tcp_offset + 20].copy_from_slice(&[0, 0]); // Urgent pointer

        if !payload.is_empty() {
            pkt[40..].copy_from_slice(payload);
        }

        // 3. TCP Checksum via IPv4 Pseudo-Header
        let tcp_segment_len = (tcp_header_len + payload.len()) as u16;
        let mut pseudo = Vec::with_capacity(12 + tcp_segment_len as usize);
        pseudo.extend_from_slice(&src_ip.octets());
        pseudo.extend_from_slice(&dst_ip.octets());
        pseudo.push(0);
        pseudo.push(6); // IPPROTO_TCP
        pseudo.extend_from_slice(&tcp_segment_len.to_be_bytes());
        pseudo.extend_from_slice(&pkt[tcp_offset..]);

        let tcp_checksum = calc_internet_checksum(&pseudo);
        pkt[tcp_offset + 16..tcp_offset + 18].copy_from_slice(&tcp_checksum.to_be_bytes());

        pkt
    }

    fn make_mock_quic_server(
    ) -> Result<(quinn::Endpoint, SocketAddr, Vec<u8>), Box<dyn std::error::Error + Send + Sync>>
    {
        let cert_der = base64::engine::general_purpose::STANDARD.decode(MOCK_SERVER_CERT_B64)?;
        let key_der = base64::engine::general_purpose::STANDARD.decode(MOCK_SERVER_KEY_B64)?;

        let cert = CertificateDer::from(cert_der.clone());
        let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key_der));

        let mut server_crypto = rustls::ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![cert], key)?;
        server_crypto.alpn_protocols = vec![b"h3".to_vec()];

        let quinn_server_crypto = quinn::crypto::rustls::QuicServerConfig::try_from(server_crypto)?;
        let mut server_config = quinn::ServerConfig::with_crypto(Arc::new(quinn_server_crypto));

        let mut transport = quinn::TransportConfig::default();
        transport.max_idle_timeout(Some(Duration::from_secs(30).try_into().unwrap()));
        transport.datagram_receive_buffer_size(Some(1024 * 1024));
        transport.datagram_send_buffer_size(1024 * 1024);
        server_config.transport_config(Arc::new(transport));

        let endpoint = quinn::Endpoint::server(server_config, "127.0.0.1:0".parse()?)?;
        let local_addr = endpoint.local_addr()?;
        Ok((endpoint, local_addr, cert_der))
    }

    fn make_mock_quic_server_ipv6(
    ) -> Result<(quinn::Endpoint, SocketAddr, Vec<u8>), Box<dyn std::error::Error + Send + Sync>>
    {
        let cert_der = base64::engine::general_purpose::STANDARD.decode(MOCK_SERVER_CERT_B64)?;
        let key_der = base64::engine::general_purpose::STANDARD.decode(MOCK_SERVER_KEY_B64)?;

        let cert = CertificateDer::from(cert_der.clone());
        let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key_der));

        let mut server_crypto = rustls::ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![cert], key)?;
        server_crypto.alpn_protocols = vec![b"h3".to_vec()];

        let quinn_server_crypto = quinn::crypto::rustls::QuicServerConfig::try_from(server_crypto)?;
        let mut server_config = quinn::ServerConfig::with_crypto(Arc::new(quinn_server_crypto));

        let mut transport = quinn::TransportConfig::default();
        transport.max_idle_timeout(Some(Duration::from_secs(30).try_into().unwrap()));
        transport.datagram_receive_buffer_size(Some(1024 * 1024));
        transport.datagram_send_buffer_size(1024 * 1024);
        server_config.transport_config(Arc::new(transport));

        let endpoint = quinn::Endpoint::server(server_config, "[::1]:0".parse()?)?;
        let local_addr = endpoint.local_addr()?;
        Ok((endpoint, local_addr, cert_der))
    }

    fn make_mock_quic_client_config(
        cert_der: &[u8],
    ) -> Result<quinn::ClientConfig, Box<dyn std::error::Error + Send + Sync>> {
        let mut root_store = RootCertStore::empty();
        root_store.add(CertificateDer::from(cert_der.to_vec()))?;

        let mut client_crypto = rustls::ClientConfig::builder()
            .with_root_certificates(root_store)
            .with_no_client_auth();
        client_crypto.alpn_protocols = vec![b"h3".to_vec()];

        let mut client_config = quinn::ClientConfig::new(Arc::new(
            quinn::crypto::rustls::QuicClientConfig::try_from(client_crypto)?,
        ));

        let mut transport = quinn::TransportConfig::default();
        transport.max_idle_timeout(Some(Duration::from_secs(30).try_into().unwrap()));
        transport.max_concurrent_uni_streams(64u32.into());
        transport.datagram_receive_buffer_size(Some(1024 * 1024));
        transport.datagram_send_buffer_size(1024 * 1024);
        client_config.transport_config(Arc::new(transport));

        Ok(client_config)
    }

    #[test]
    fn test_mock_packet_checksum_validation() {
        let pkt = build_mock_ipv4_tcp_packet(
            std::net::Ipv4Addr::new(149, 154, 167, 50),
            std::net::Ipv4Addr::new(172, 16, 0, 2),
            443,
            45000,
            1000,
            2000,
            0x12,
            &[],
        );
        let ip_cksum = calc_internet_checksum(&pkt[0..20]);
        assert_eq!(ip_cksum, 0, "IP header checksum validation");

        let tcp_segment_len = 20u16;
        let mut pseudo = Vec::new();
        pseudo.extend_from_slice(&pkt[12..16]);
        pseudo.extend_from_slice(&pkt[16..20]);
        pseudo.push(0);
        pseudo.push(6);
        pseudo.extend_from_slice(&tcp_segment_len.to_be_bytes());
        pseudo.extend_from_slice(&pkt[20..]);
        let tcp_cksum = calc_internet_checksum(&pseudo);
        assert_eq!(tcp_cksum, 0, "TCP pseudo-header checksum validation");
    }

    #[tokio::test]
    async fn test_masque_mock_full_stack_capsule_and_datagram_echo() {
        // 1. Setup Mock Server
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");

        // 2. Setup Client Config & Endpoint
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        // 3. Spawn Mock Server Task
        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("mock server accept");
            let server_conn = incoming.await.expect("mock server handshake");

            // Accept bidirectional request stream for CONNECT-IP
            let (mut s_send, mut s_recv) = server_conn.accept_bi().await.expect("server accept_bi");

            // Read client H3 CONNECT-IP request headers
            let mut req_buf = vec![0u8; 2048];
            let n = s_recv
                .read(&mut req_buf)
                .await
                .expect("read request")
                .expect("not eof");
            req_buf.truncate(n);
            let (ftype, _) = decode_varint(&req_buf).expect("decode ftype");
            assert_eq!(
                ftype, H3_FRAME_HEADERS,
                "server must receive H3 HEADERS frame"
            );

            // Send H3 200 OK headers frame (Static table index 25 status 200 = 0xD9)
            let resp_headers = [0x01, 0x03, 0x00, 0x00, 0xD9];
            s_send.write_all(&resp_headers).await.expect("write 200 ok");

            // Send Capsule: ADDRESS_ASSIGN (0x01) assigning 172.16.0.2/32
            let assign_capsule = encode_address_assign(&[AssignedAddress {
                request_id: 0,
                ip_version: 4,
                ip_addr: std::net::IpAddr::V4(std::net::Ipv4Addr::new(172, 16, 0, 2)),
                prefix_len: 32,
            }]);
            let assign_frame = wrap_in_h3_data_frame(&assign_capsule);
            s_send
                .write_all(&assign_frame)
                .await
                .expect("write address assign");

            // Send Capsule: ROUTE_ADVERTISEMENT (0x03)
            let route_capsule = encode_route_advertisement(&[IpAddressRange {
                ip_version: 4,
                start_ip: std::net::IpAddr::V4(std::net::Ipv4Addr::new(0, 0, 0, 0)),
                end_ip: std::net::IpAddr::V4(std::net::Ipv4Addr::new(255, 255, 255, 255)),
                ip_protocol: 0,
            }]);
            let route_frame = wrap_in_h3_data_frame(&route_capsule);
            s_send
                .write_all(&route_frame)
                .await
                .expect("write route advertisement");

            // Drain client stream in background (capsules sent by client)
            tokio::spawn(async move {
                let mut sink = [0u8; 1024];
                while let Ok(Some(_)) = s_recv.read(&mut sink).await {}
            });

            // Datagram processing loop
            let mut syn_received = false;
            let mut echo_completed = false;

            while !echo_completed {
                let dgram_bytes = match server_conn.read_datagram().await {
                    Ok(b) => b,
                    Err(_) => break,
                };

                let (quarter_stream_id, context_id, ip_pkt) = match decode_h3_datagram(&dgram_bytes)
                {
                    Some(parts) => parts,
                    None => continue,
                };
                assert_eq!(context_id, 0);

                if ip_pkt.len() < 40 {
                    continue;
                }
                let ip_version = ip_pkt[0] >> 4;
                assert_eq!(ip_version, 4);
                let ip_hdr_len = (ip_pkt[0] & 0x0F) as usize * 4;
                let src_ip =
                    std::net::Ipv4Addr::new(ip_pkt[12], ip_pkt[13], ip_pkt[14], ip_pkt[15]);
                let dst_ip =
                    std::net::Ipv4Addr::new(ip_pkt[16], ip_pkt[17], ip_pkt[18], ip_pkt[19]);

                let src_port = u16::from_be_bytes([ip_pkt[ip_hdr_len], ip_pkt[ip_hdr_len + 1]]);
                let dst_port = u16::from_be_bytes([ip_pkt[ip_hdr_len + 2], ip_pkt[ip_hdr_len + 3]]);
                let seq = u32::from_be_bytes([
                    ip_pkt[ip_hdr_len + 4],
                    ip_pkt[ip_hdr_len + 5],
                    ip_pkt[ip_hdr_len + 6],
                    ip_pkt[ip_hdr_len + 7],
                ]);
                let tcp_data_offset = ((ip_pkt[ip_hdr_len + 12] >> 4) as usize) * 4;
                let flags = ip_pkt[ip_hdr_len + 13];
                let tcp_payload = &ip_pkt[ip_hdr_len + tcp_data_offset..];

                // 1. Handle TCP SYN (flags & 0x02)
                if (flags & 0x02) != 0 && !syn_received {
                    syn_received = true;
                    let syn_ack = build_mock_ipv4_tcp_packet(
                        dst_ip,
                        src_ip,
                        dst_port,
                        src_port,
                        1000,
                        seq.wrapping_add(1),
                        0x12, // SYN | ACK
                        &[],
                    );
                    let out_dgram = encode_h3_datagram(quarter_stream_id, 0, &syn_ack);
                    let _ = server_conn.send_datagram(bytes::Bytes::from(out_dgram));
                    continue;
                }

                // 2. Handle TCP Data with "PING TELEGRAM DC"
                if tcp_payload == b"PING TELEGRAM DC" {
                    let pong_pkt = build_mock_ipv4_tcp_packet(
                        dst_ip,
                        src_ip,
                        dst_port,
                        src_port,
                        1001,
                        seq.wrapping_add(tcp_payload.len() as u32),
                        0x18, // PSH | ACK
                        b"PONG TELEGRAM DC",
                    );
                    let out_dgram = encode_h3_datagram(quarter_stream_id, 0, &pong_pkt);
                    let _ = server_conn.send_datagram(bytes::Bytes::from(out_dgram));
                    echo_completed = true;
                }
            }
        });

        // 4. Client Connects to Mock QUIC Server
        let connecting = client_endpoint
            .connect(server_addr, "localhost")
            .expect("client connect");
        let client_conn = connecting.await.expect("client handshake");
        let dispatcher = create_datagram_dispatcher(client_conn.clone());

        let (mut send_stream, mut recv_stream) = client_conn.open_bi().await.expect("open_bi");

        let req_frame = build_h3_connect_headers(
            "149.154.167.50:443",
            "localhost",
            Some(MASQUE_CONNECT_IP_PROTO),
            Some("/.well-known/masque/ip/149.154.167.50/443/"),
            None,
        );
        send_stream
            .write_all(&req_frame)
            .await
            .expect("send connect headers");

        let (status, leftovers) =
            read_h3_headers_response(&mut recv_stream, Duration::from_secs(3))
                .await
                .expect("read 200 ok");
        assert_eq!(status, 200);

        let stream_id = send_stream.id();
        let raw_stream_id = u64::from(quinn::VarInt::from(stream_id));
        let quarter_stream_id = raw_stream_id / 4;
        let (dgram_tx, dgram_rx) = tokio::sync::mpsc::channel(MASQUE_DATAGRAM_QUEUE_CAPACITY);
        dispatcher.write().insert(quarter_stream_id, dgram_tx);
        let capsule_rx = spawn_capsule_reader(recv_stream, leftovers);
        let client_ctrl = client_conn.open_uni().await.expect("client open control");
        let control_stream = Arc::new(tokio::sync::Mutex::new(client_ctrl));

        let tunnel = Arc::new(MasqueTunnel {
            send_stream: tokio::sync::Mutex::new(send_stream),
            recv_reader: tokio::sync::Mutex::new(None),
            capsule_rx: tokio::sync::Mutex::new(Some(capsule_rx)),
            address_state_v4: Arc::new(parking_lot::RwLock::new(AddressState::Unassigned)),
            address_state_v6: Arc::new(parking_lot::RwLock::new(AddressState::Unassigned)),
            assigned_ipv4: Arc::new(parking_lot::RwLock::new(None)),
            assigned_ipv6: Arc::new(parking_lot::RwLock::new(None)),
            advertised_routes: Arc::new(parking_lot::RwLock::new(Vec::new())),
            connection: client_conn,
            endpoint_addr: server_addr,
            stream_id,
            quarter_stream_id,
            target_addr: "149.154.167.50:443".to_string(),
            is_raw_l4: false,
            dgram_rx: tokio::sync::Mutex::new(dgram_rx),
            dgram_dispatcher: dispatcher,
            control_stream,
        });

        // 5. Setup Local TCP Bridge and Verify End-to-End Tunneling
        let local_listener = tokio::net::TcpListener::bind("127.0.0.1:0")
            .await
            .expect("local listener bind");
        let local_addr = local_listener.local_addr().expect("local listener addr");

        let cancel_token = CancellationToken::new();
        let cancel_token_clone = cancel_token.clone();
        let tunnel_clone = tunnel.clone();

        let bridge_task = tokio::spawn(async move {
            let (client_stream, _) = local_listener
                .accept()
                .await
                .expect("accept downstream client");
            tunnel_clone
                .run_smoltcp_bridge(client_stream, cancel_token_clone)
                .await
        });

        // Simulate local Telegram downstream TCP connection
        let mut app_client = tokio::net::TcpStream::connect(local_addr)
            .await
            .expect("connect to bridge");

        // Send test Telegram packet through local TCP stream
        app_client
            .write_all(b"PING TELEGRAM DC")
            .await
            .expect("write ping");

        let mut reply_buf = [0u8; 64];
        let n = tokio::time::timeout(Duration::from_secs(5), app_client.read(&mut reply_buf))
            .await
            .expect("read timeout")
            .expect("read pong");

        assert_eq!(&reply_buf[..n], b"PONG TELEGRAM DC");

        // Verify that server capsules were received and processed
        assert_eq!(
            *tunnel.assigned_ipv4.read(),
            Some(std::net::Ipv4Addr::new(172, 16, 0, 2))
        );
        assert_eq!(tunnel.advertised_routes.read().len(), 1);

        cancel_token.cancel();
        let _ = bridge_task.await;
        let _ = server_task.await;
    }

    #[test]
    fn test_cf_masque_endpoints_pool_has_50_items() {
        assert_eq!(
            CF_MASQUE_ENDPOINTS.len(),
            50,
            "CF_MASQUE_ENDPOINTS pool must contain exactly 50 Anycast endpoints"
        );
        let unique: std::collections::HashSet<_> = CF_MASQUE_ENDPOINTS.iter().collect();
        assert_eq!(
            unique.len(),
            50,
            "All 50 Anycast endpoints in pool must be distinct"
        );
    }

    #[test]
    fn test_user_specified_port_and_override_preserved_in_selection() {
        // Test custom user override with non-standard port
        let user_custom = "198.51.100.123:4433";
        let candidates = select_rotated_candidate_endpoints(user_custom, None, 4);

        assert!(!candidates.is_empty());
        assert_eq!(
            candidates[0], user_custom,
            "Configured user endpoint and port must be placed first without modification"
        );
        assert!(
            candidates[0].ends_with(":4433"),
            "User-defined custom port 4433 must be strictly preserved"
        );

        // Test with both sticky and user override
        let sticky = "188.114.96.1:8443";
        let candidates_with_sticky = select_rotated_candidate_endpoints(user_custom, Some(sticky), 4);
        assert_eq!(candidates_with_sticky[0], sticky);
        assert_eq!(candidates_with_sticky[1], user_custom);
    }

    #[test]
    fn test_cf_masque_endpoints_pool_rotation_covers_entire_pool() {
        reset_cf_pool_cursor();
        assert_eq!(get_cf_pool_cursor(), 0);

        let mut covered_endpoints = std::collections::HashSet::new();
        // 50 endpoints in pool / 4 per dial = 12.5 -> 13 dials should visit every single endpoint in the pool
        for _ in 0..15 {
            let candidates = select_rotated_candidate_endpoints("", None, 4);
            assert_eq!(candidates.len(), 4);
            for ep in candidates {
                covered_endpoints.insert(ep);
            }
        }

        assert_eq!(
            covered_endpoints.len(),
            50,
            "Round-robin rotation must cover all 50 endpoints in CF_MASQUE_ENDPOINTS without starvation"
        );
    }

    #[test]
    fn test_sticky_endpoint_lifecycle_and_timeout_invalidation() {
        clear_sticky_endpoint();
        assert_eq!(get_sticky_endpoint(), None);

        set_sticky_endpoint("188.114.96.1:8095");
        assert_eq!(get_sticky_endpoint(), Some("188.114.96.1:8095".to_string()));

        record_sticky_success("188.114.97.1:8443");
        assert_eq!(get_sticky_endpoint(), Some("188.114.97.1:8443".to_string()));

        // Timeout 1: not invalidated
        let inv1 = record_sticky_timeout();
        assert!(!inv1);
        assert_eq!(get_sticky_endpoint(), Some("188.114.97.1:8443".to_string()));

        // Timeout 2: not invalidated
        let inv2 = record_sticky_timeout();
        assert!(!inv2);
        assert_eq!(get_sticky_endpoint(), Some("188.114.97.1:8443".to_string()));

        // Timeout 3: invalidated after 3 consecutive timeouts!
        let inv3 = record_sticky_timeout();
        assert!(inv3);
        assert_eq!(get_sticky_endpoint(), None);

        // Success resets counter and sets new sticky endpoint
        record_sticky_success("162.159.192.1:8095");
        assert_eq!(
            get_sticky_endpoint(),
            Some("162.159.192.1:8095".to_string())
        );
        clear_sticky_endpoint();
        assert_eq!(get_sticky_endpoint(), None);
    }

    #[test]
    fn test_is_cert_rejection_error() {
        // Standard TLS alert strings
        assert!(is_cert_rejection_error(
            "connection closed: bad_certificate"
        ));
        assert!(is_cert_rejection_error(
            "TLS alert: Bad Certificate (alert 42)"
        ));
        assert!(is_cert_rejection_error("certificate_unknown"));
        assert!(is_cert_rejection_error("unknown_ca"));
        assert!(is_cert_rejection_error("unsupported_certificate"));
        assert!(is_cert_rejection_error("certificate_required"));

        // RFC 9000 CRYPTO_ERROR codes (0x0100 + Alert)
        assert!(is_cert_rejection_error("QUIC transport error 0x12a"));
        assert!(is_cert_rejection_error("quic crypto error 0x12b"));
        assert!(is_cert_rejection_error("crypto error 0x12e"));
        assert!(is_cert_rejection_error("peer alert 0x130"));
        assert!(is_cert_rejection_error("crypto error 298")); // 0x12a in decimal

        // Non-cert network errors should return false
        assert!(!is_cert_rejection_error("connection timed out"));
        assert!(!is_cert_rejection_error("connection reset by peer"));
        assert!(!is_cert_rejection_error("network is unreachable"));
        assert!(!is_cert_rejection_error("broken pipe"));
    }

    #[test]
    fn test_create_standard_quic_client_config() {
        let cfg = create_standard_quic_client_config();
        assert!(
            cfg.is_ok(),
            "Standard QUIC client config must build successfully without mTLS"
        );
    }

    #[test]
    fn test_build_h3_connect_headers_with_bearer_token() {
        let frame = build_h3_connect_headers(
            "149.154.167.50:443",
            "consumer-masque.cloudflareclient.com",
            Some("connect-ip"),
            Some("/.well-known/masque/ip/149.154.167.50/443/"),
            Some("test_auth_token_xyz_123"),
        );

        assert!(!frame.is_empty());
        // Frame must contain H3_FRAME_HEADERS (0x01)
        assert_eq!(frame[0], 0x01);

        // Frame must contain the token in encoded form
        let text = String::from_utf8_lossy(&frame);
        assert!(text.contains("test_auth_token_xyz_123"));
        assert!(text.contains("Bearer"));
    }

    #[test]
    fn test_mtls_rejected_flag_lifecycle() {
        set_mtls_rejected(false);
        assert!(!is_mtls_rejected());

        set_mtls_rejected(true);
        assert!(is_mtls_rejected());

        reset_quic_endpoint();
        assert!(
            !is_mtls_rejected(),
            "reset_quic_endpoint must reset MTLS_REJECTED to false"
        );
    }

    #[tokio::test]
    async fn test_h3_control_stream_drop_behavior_in_quinn_and_retention() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("accept");
            let server_conn = incoming.await.expect("handshake");

            // Stream 1: Client drops SendStream without holding handle
            let mut s1 = server_conn.accept_uni().await.expect("accept s1");
            let mut buf1 = [0u8; 16];
            let n1 = s1.read(&mut buf1).await.expect("read s1").expect("not eof");
            assert_eq!(&buf1[..n1], b"DROP_TEST");
            // In Quinn, drop(SendStream) sends FIN. Subsequent read must return Ok(None) (EOF)
            let next1 = s1.read(&mut buf1).await.expect("read s1 eof");
            assert!(
                next1.is_none(),
                "Dropping Quinn SendStream implicitly finishes stream and sends FIN"
            );

            // Stream 2: Client retains SendStream in Arc<Mutex<SendStream>> (as in ActiveQuicSession / MasqueTunnel)
            let mut s2 = server_conn.accept_uni().await.expect("accept s2");
            let mut buf2 = [0u8; 16];
            let n2 = s2.read(&mut buf2).await.expect("read s2").expect("not eof");
            assert_eq!(&buf2[..n2], b"RETAIN_TEST");
            // Reading with timeout: must timeout, NOT return Ok(None), because FIN was NOT sent!
            let timeout_res =
                tokio::time::timeout(Duration::from_millis(150), s2.read(&mut buf2)).await;
            assert!(
                timeout_res.is_err(),
                "Retained SendStream does NOT send FIN, stream stays open"
            );
        });

        let connecting = client_endpoint.connect(server_addr, "localhost").unwrap();
        let client_conn = connecting.await.expect("client handshake");

        // 1. Open stream and drop it immediately
        {
            let mut uni1 = client_conn.open_uni().await.expect("open_uni 1");
            uni1.write_all(b"DROP_TEST").await.expect("write 1");
            // uni1 dropped here -> Quinn sends FIN
        }

        // 2. Open stream and retain it in Arc<Mutex<SendStream>>
        let retained_stream = {
            let mut uni2 = client_conn.open_uni().await.expect("open_uni 2");
            uni2.write_all(b"RETAIN_TEST").await.expect("write 2");
            Arc::new(tokio::sync::Mutex::new(uni2))
        };

        server_task.await.expect("server task completed");
        drop(retained_stream);
    }

    #[tokio::test]
    async fn test_h3_control_stream_kept_alive_no_fin_after_settings() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("accept");
            let server_conn = incoming.await.expect("handshake");

            // Server accepts client's unidirectional control stream
            let mut ctrl_recv = server_conn.accept_uni().await.expect("accept control stream");

            // Verify Stream Type is H3_STREAM_CONTROL (0x00)
            let stream_type = decode_varint_from_stream(&mut ctrl_recv)
                .await
                .expect("decode stream type");
            assert_eq!(
                stream_type, H3_STREAM_CONTROL,
                "Must be HTTP/3 control stream"
            );

            // Verify first frame is SETTINGS (0x04)
            let frame_type = decode_varint_from_stream(&mut ctrl_recv)
                .await
                .expect("decode frame type");
            assert_eq!(
                frame_type, H3_FRAME_SETTINGS,
                "First frame on control stream must be SETTINGS"
            );
            let frame_len = decode_varint_from_stream(&mut ctrl_recv)
                .await
                .expect("decode frame len");
            let mut payload = vec![0u8; frame_len as usize];
            read_stream_exact(&mut ctrl_recv, &mut payload)
                .await
                .expect("read settings payload");

            // Strict HTTP/3 check: verify that reading further does NOT yield FIN (times out)
            let mut sink = [0u8; 128];
            let no_fin =
                tokio::time::timeout(Duration::from_millis(200), ctrl_recv.read(&mut sink)).await;
            assert!(
                no_fin.is_err(),
                "Strict HTTP/3 peer must NOT receive FIN on control stream after SETTINGS"
            );
        });

        let connecting = client_endpoint.connect(server_addr, "localhost").unwrap();
        let client_conn = connecting.await.expect("client handshake");

        // Client opens control stream and sends SETTINGS exactly like production connect_masque_with_profile
        let mut ctrl_stream = client_conn.open_uni().await.expect("open control stream");
        let mut ctrl_buf = Vec::new();
        encode_varint(&mut ctrl_buf, H3_STREAM_CONTROL);
        let mut settings_payload = Vec::new();
        encode_varint(&mut settings_payload, SETTINGS_ENABLE_CONNECT_PROTOCOL);
        encode_varint(&mut settings_payload, 1);
        encode_varint(&mut ctrl_buf, H3_FRAME_SETTINGS);
        encode_varint(&mut ctrl_buf, settings_payload.len() as u64);
        ctrl_buf.extend_from_slice(&settings_payload);
        ctrl_stream.write_all(&ctrl_buf).await.expect("write settings");

        // Retain control stream in Arc
        let retained_ctrl = Arc::new(tokio::sync::Mutex::new(ctrl_stream));

        server_task.await.expect("server task completed");
        drop(retained_ctrl);
    }

    #[tokio::test]
    async fn test_h3_session_parallel_and_sequential_connect_requests() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("accept");
            let server_conn = incoming.await.expect("handshake");

            // Accept and verify control stream
            let mut ctrl_recv = server_conn.accept_uni().await.expect("accept control stream");
            let stream_type = decode_varint_from_stream(&mut ctrl_recv).await.unwrap();
            assert_eq!(stream_type, H3_STREAM_CONTROL);
            let frame_type = decode_varint_from_stream(&mut ctrl_recv).await.unwrap();
            assert_eq!(frame_type, H3_FRAME_SETTINGS);
            let frame_len = decode_varint_from_stream(&mut ctrl_recv).await.unwrap();
            assert_eq!(frame_len, 0);

            // Handle total 8 CONNECT requests (3 sequential + 5 parallel)
            let conn_for_bi = server_conn.clone();
            let bi_handler = tokio::spawn(async move {
                for _ in 0..8 {
                    let (mut s_send, mut s_recv) = conn_for_bi.accept_bi().await.expect("accept_bi");
                    tokio::spawn(async move {
                        let mut req_buf = [0u8; 1024];
                        let n = s_recv.read(&mut req_buf).await.unwrap().unwrap();
                        assert!(n > 0);
                        // Return 200 OK headers frame
                        let resp = [0x01, 0x03, 0x00, 0x00, 0xD9];
                        s_send.write_all(&resp).await.unwrap();
                    });
                }
            });

            // Verify control stream is still alive without FIN
            let mut sink = [0u8; 16];
            let read_res = tokio::time::timeout(Duration::from_millis(150), ctrl_recv.read(&mut sink)).await;
            assert!(read_res.is_err(), "Control stream remained active during all CONNECT requests");

            bi_handler.await.expect("bi_handler completed");
            server_conn
        });

        let connecting = client_endpoint.connect(server_addr, "localhost").unwrap();
        let client_conn = connecting.await.expect("client handshake");

        // Client opens and retains control stream
        let mut ctrl_stream = client_conn.open_uni().await.expect("open control");
        let mut ctrl_buf = Vec::new();
        encode_varint(&mut ctrl_buf, H3_STREAM_CONTROL);
        encode_varint(&mut ctrl_buf, H3_FRAME_SETTINGS);
        encode_varint(&mut ctrl_buf, 0);
        ctrl_stream.write_all(&ctrl_buf).await.expect("write settings");
        let retained_ctrl = Arc::new(tokio::sync::Mutex::new(ctrl_stream));

        // 1. 3 Sequential CONNECT requests
        for i in 1..=3 {
            let (mut s, mut r) = client_conn.open_bi().await.expect("open bi seq");
            let req = build_h3_connect_headers(
                &format!("149.154.167.{}:443", 50 + i),
                "localhost",
                Some(MASQUE_CONNECT_IP_PROTO),
                None,
                None,
            );
            s.write_all(&req).await.expect("send req");
            let (status, _) = read_h3_headers_response(&mut r, Duration::from_secs(3))
                .await
                .expect("read 200");
            assert_eq!(status, 200);
        }

        // 2. 5 Concurrent Parallel CONNECT requests
        let mut handles = Vec::new();
        for i in 1..=5 {
            let conn = client_conn.clone();
            handles.push(tokio::spawn(async move {
                let (mut s, mut r) = conn.open_bi().await.expect("open bi par");
                let req = build_h3_connect_headers(
                    &format!("91.108.56.{}:443", 100 + i),
                    "localhost",
                    Some(MASQUE_CONNECT_IP_PROTO),
                    None,
                    None,
                );
                s.write_all(&req).await.expect("send req par");
                let (status, _) = read_h3_headers_response(&mut r, Duration::from_secs(3))
                    .await
                    .expect("read 200 par");
                assert_eq!(status, 200);
            }));
        }

        for h in handles {
            h.await.expect("parallel request task finished");
        }

        let _server_conn = server_task.await.expect("server completed");
        drop(retained_ctrl);
    }

    #[tokio::test]
    async fn test_h3_critical_server_control_stream_closure_handling() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("accept");
            let server_conn = incoming.await.expect("handshake");

            // Server opens a unidirectional control stream to the client
            let mut server_ctrl = server_conn.open_uni().await.expect("server open control");
            let mut ctrl_buf = Vec::new();
            encode_varint(&mut ctrl_buf, H3_STREAM_CONTROL);
            encode_varint(&mut ctrl_buf, H3_FRAME_SETTINGS);
            encode_varint(&mut ctrl_buf, 0); // empty settings
            server_ctrl.write_all(&ctrl_buf).await.expect("write settings");

            // Wait a moment for client to process settings
            tokio::time::sleep(Duration::from_millis(50)).await;

            // Server closes (finishes) its control stream -> this is an RFC 9114 protocol violation
            server_ctrl.finish().expect("server ctrl finish");

            // Wait for connection to be closed by client with H3_CLOSED_CRITICAL_STREAM
            let close_reason = server_conn.closed().await;
            close_reason
        });

        let connecting = client_endpoint.connect(server_addr, "localhost").unwrap();
        let client_conn = connecting.await.expect("client handshake");

        // Run production supervisor
        let _supervisor = spawn_server_stream_supervisor(client_conn);

        let server_close_reason = server_task.await.expect("server task finished");

        // Verify the connection close error code is H3_CLOSED_CRITICAL_STREAM (0x0104)
        match server_close_reason {
            quinn::ConnectionError::ApplicationClosed(app_close) => {
                assert_eq!(
                    u64::from(app_close.error_code),
                    H3_CLOSED_CRITICAL_STREAM as u64,
                    "Connection must be closed with H3_CLOSED_CRITICAL_STREAM (0x0104)"
                );
                assert_eq!(u64::from(app_close.error_code), 0x0104);
            }
            other => {
                panic!("Expected ApplicationClosed(0x0104), got {:?}", other);
            }
        }
    }

    #[tokio::test]
    async fn test_read_h3_headers_response_strict_behavior() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let (done_tx, done_rx) = tokio::sync::oneshot::channel::<()>();

        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("accept");
            let server_conn = incoming.await.expect("handshake");

            // 1. First bi-stream: 103 Early Hints followed by 200 OK
            let (mut s1, mut r1) = server_conn.accept_bi().await.expect("accept bi 1");
            let mut drain = [0u8; 512];
            let _ = r1.read(&mut drain).await;
            // Send 103 Early Hints frame: HEADERS (0x01), len 3, QPACK: 0x00, 0x00, 0xD8 (Index 24)
            s1.write_all(&[0x01, 0x03, 0x00, 0x00, 0xD8]).await.expect("write 103");
            // Followed by 200 OK frame: HEADERS (0x01), len 3, QPACK: 0x00, 0x00, 0xD9 (Index 25)
            s1.write_all(&[0x01, 0x03, 0x00, 0x00, 0xD9]).await.expect("write 200");
            let _ = s1.finish();

            // 2. Second bi-stream: 403 Forbidden (Static Index 68)
            let (mut s2, mut r2) = server_conn.accept_bi().await.expect("accept bi 2");
            let _ = r2.read(&mut drain).await;
            // HEADERS (0x01), len 4, QPACK: 0x00, 0x00, 0xFF, 0x05
            s2.write_all(&[0x01, 0x04, 0x00, 0x00, 0xFF, 0x05]).await.expect("write 403");
            let _ = s2.finish();

            // 3. Third bi-stream: Protocol Error (DATA frame 0x00 before HEADERS)
            let (mut s3, mut r3) = server_conn.accept_bi().await.expect("accept bi 3");
            let _ = r3.read(&mut drain).await;
            // DATA (0x00), len 4, payload "test"
            s3.write_all(&[0x00, 0x04, b't', b'e', b's', b't']).await.expect("write DATA");
            let _ = s3.finish();

            // 4. Fourth bi-stream: Missing :status in HEADERS
            let (mut s4, mut r4) = server_conn.accept_bi().await.expect("accept bi 4");
            let _ = r4.read(&mut drain).await;
            // HEADERS (0x01), len 8, QPACK: 0x00, 0x00, 0x5F, 0x0E, 0x03, b'1', b'0', b'0' (content-length: 100)
            s4.write_all(&[0x01, 0x08, 0x00, 0x00, 0x5F, 0x0E, 0x03, b'1', b'0', b'0']).await.expect("write no status");
            let _ = s4.finish();

            // Keep server connection alive until client finishes assertions
            let _ = done_rx.await;
        });

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .expect("client connect")
            .await
            .expect("handshake");

        // Case 1: Verify 1xx is skipped and final 200 is returned
        let (mut s1, mut r1) = client_conn.open_bi().await.expect("open bi 1");
        s1.write_all(b"REQ1").await.expect("write req 1");
        let (status1, _) = read_h3_headers_response(&mut r1, Duration::from_secs(3))
            .await
            .expect("read response 1");
        assert_eq!(status1, 200, "1xx informational response must be skipped to final 200");

        // Case 2: Verify 403 (Index 68) is parsed accurately
        let (mut s2, mut r2) = client_conn.open_bi().await.expect("open bi 2");
        s2.write_all(b"REQ2").await.expect("write req 2");
        let (status2, _) = read_h3_headers_response(&mut r2, Duration::from_secs(3))
            .await
            .expect("read response 2");
        assert_eq!(status2, 403, "Index 68 must return status 403");

        // Case 3: Verify protocol error when first frame is DATA instead of HEADERS
        let (mut s3, mut r3) = client_conn.open_bi().await.expect("open bi 3");
        s3.write_all(b"REQ3").await.expect("write req 3");
        let err3 = read_h3_headers_response(&mut r3, Duration::from_secs(3))
            .await
            .expect_err("must reject DATA frame as first frame");
        assert!(err3.contains("expected H3 HEADERS frame"), "err: {}", err3);

        // Case 4: Verify error when :status pseudo-header is missing
        let (mut s4, mut r4) = client_conn.open_bi().await.expect("open bi 4");
        s4.write_all(b"REQ4").await.expect("write req 4");
        let err4 = read_h3_headers_response(&mut r4, Duration::from_secs(3))
            .await
            .expect_err("must reject missing :status");
        assert!(err4.contains("missing :status"), "err: {}", err4);

        let _ = done_tx.send(());
        server_task.await.expect("server task completed");
    }

    #[test]
    fn test_masque_credentials_resolution_schemes() {
        // 1. Pure mTLS with client cert and private key (Cloudflare Anycast MASQUE)
        // API management token is NOT sent as a gateway credential
        let cfg_mtls = WarpMasqueConfig {
            endpoint: "188.114.96.1:8095".to_string(),
            sni: MASQUE_IP_SNI.to_string(),
            auth_token: "api_management_token_from_reg".to_string(),
            client_ipv4: "172.16.0.2".to_string(),
            client_ipv6: "".to_string(),
            p256_priv_key_base64: "dGVzdF9rZXk=".to_string(),
            client_cert_base64: "dGVzdF9jZXJ0".to_string(),
            peer_pub_key: "".to_string(),
            uri_template: MASQUE_DEFAULT_URI_PATH.to_string(),
        };
        let creds_mtls = cfg_mtls.resolve_credentials();
        assert_eq!(creds_mtls.scheme, MasqueAuthScheme::Mtls);
        assert!(creds_mtls.client_cert_der.is_some());
        assert!(creds_mtls.client_key_der.is_some());
        assert_eq!(creds_mtls.gateway_bearer_token, None, "Pure mTLS must NEVER leak API management token as Gateway Bearer token!");
        assert_eq!(creds_mtls.api_management_token.as_deref(), Some("api_management_token_from_reg"));

        // 2. Dual mTLS + explicit Gateway Bearer token
        let cfg_dual = WarpMasqueConfig {
            endpoint: "188.114.96.1:8095".to_string(),
            sni: MASQUE_IP_SNI.to_string(),
            auth_token: "gateway:explicit_gateway_jwt_token".to_string(),
            client_ipv4: "172.16.0.2".to_string(),
            client_ipv6: "".to_string(),
            p256_priv_key_base64: "dGVzdF9rZXk=".to_string(),
            client_cert_base64: "dGVzdF9jZXJ0".to_string(),
            peer_pub_key: "".to_string(),
            uri_template: MASQUE_DEFAULT_URI_PATH.to_string(),
        };
        let creds_dual = cfg_dual.resolve_credentials();
        assert_eq!(creds_dual.scheme, MasqueAuthScheme::MtlsWithGatewayBearer);
        assert_eq!(creds_dual.gateway_bearer_token.as_deref(), Some("explicit_gateway_jwt_token"));

        // 3. Application-level Gateway Bearer token without mTLS (e.g. Zero Trust Gateway)
        let cfg_bearer = WarpMasqueConfig {
            endpoint: "188.114.96.1:8095".to_string(),
            sni: MASQUE_IP_SNI.to_string(),
            auth_token: "bearer:user_auth_token_999".to_string(),
            client_ipv4: "172.16.0.2".to_string(),
            client_ipv6: "".to_string(),
            p256_priv_key_base64: "".to_string(),
            client_cert_base64: "".to_string(),
            peer_pub_key: "".to_string(),
            uri_template: MASQUE_DEFAULT_URI_PATH.to_string(),
        };
        let creds_bearer = cfg_bearer.resolve_credentials();
        assert_eq!(creds_bearer.scheme, MasqueAuthScheme::GatewayBearer);
        assert_eq!(creds_bearer.gateway_bearer_token.as_deref(), Some("user_auth_token_999"));
        assert!(creds_bearer.client_cert_der.is_none());

        // 4. Anonymous / No Auth
        let cfg_anon = WarpMasqueConfig {
            endpoint: "188.114.96.1:8095".to_string(),
            sni: MASQUE_IP_SNI.to_string(),
            auth_token: "".to_string(),
            client_ipv4: "172.16.0.2".to_string(),
            client_ipv6: "".to_string(),
            p256_priv_key_base64: "".to_string(),
            client_cert_base64: "".to_string(),
            peer_pub_key: "".to_string(),
            uri_template: MASQUE_DEFAULT_URI_PATH.to_string(),
        };
        let creds_anon = cfg_anon.resolve_credentials();
        assert_eq!(creds_anon.scheme, MasqueAuthScheme::Anonymous);
        assert!(creds_anon.gateway_bearer_token.is_none());
        assert!(creds_anon.client_cert_der.is_none());
    }

    #[test]
    fn test_classify_tls_and_http_auth_errors() {
        // TLS alerts classification
        assert!(matches!(
            classify_tls_auth_error("tls alert received: certificate_revoked (code 0x12c)"),
            Some(MasqueAuthError::CertificateRevoked(_))
        ));
        assert!(matches!(
            classify_tls_auth_error("tls alert 45 certificate_expired"),
            Some(MasqueAuthError::CertificateExpired(_))
        ));
        assert!(matches!(
            classify_tls_auth_error("server rejected client cert: unknown_ca (0x130)"),
            Some(MasqueAuthError::UntrustedCertificate(_))
        ));
        assert!(matches!(
            classify_tls_auth_error("certificate_required: peer did not supply certificate"),
            Some(MasqueAuthError::CertificateRequired(_))
        ));
        assert!(matches!(
            classify_tls_auth_error("crypto error: bad_certificate (0x12a)"),
            Some(MasqueAuthError::BadCertificate(_))
        ));
        assert!(matches!(
            classify_tls_auth_error("tls alert: unsupported_certificate (0x12b)"),
            Some(MasqueAuthError::KeyMismatch(_))
        ));

        // Non-auth TLS / transport errors must NOT be classified as auth errors
        assert!(classify_tls_auth_error("connection refused").is_none());
        assert!(classify_tls_auth_error("connection timed out").is_none());
        assert!(classify_tls_auth_error("network is unreachable").is_none());

        // HTTP response status classification
        assert!(matches!(
            classify_http_auth_error(401, "missing token"),
            Some(MasqueAuthError::Unauthorized(_))
        ));
        assert!(matches!(
            classify_http_auth_error(403, "revoked device"),
            Some(MasqueAuthError::Forbidden(_))
        ));
        assert!(matches!(
            classify_http_auth_error(407, "proxy auth required"),
            Some(MasqueAuthError::ProxyAuthRequired(_))
        ));

        // Non-auth HTTP statuses must NOT be classified as auth errors
        assert!(classify_http_auth_error(200, "ok").is_none());
        assert!(classify_http_auth_error(404, "not found").is_none());
        assert!(classify_http_auth_error(500, "internal error").is_none());
        assert!(classify_http_auth_error(502, "bad gateway").is_none());
    }

    #[tokio::test]
    async fn test_auth_failure_matrix_independent_mock_server() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");

        let (done_tx, done_rx) = tokio::sync::oneshot::channel::<()>();

        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("accept client");
            let server_conn = incoming.await.expect("handshake");
            for _ in 0..3 {
                let (mut s, mut r) = server_conn.accept_bi().await.expect("accept bi");

                let mut req_buf = vec![0u8; 2048];
                let n = r.read(&mut req_buf).await.expect("read").expect("not eof");
                req_buf.truncate(n);

                let req_str = String::from_utf8_lossy(&req_buf);

                // Check authorization header
                if req_str.contains("valid_secret_token_123") {
                    // Valid token: return 200 OK (Static Table Index 25)
                    let resp = [0x01, 0x03, 0x00, 0x00, 0xD9];
                    s.write_all(&resp).await.expect("write 200");
                } else if req_str.contains("wrong_token_456") {
                    // Wrong token: return 403 Forbidden (Index 68 -> QPACK: 0x00, 0x00, 0xFF, 0x05)
                    let resp = [0x01, 0x04, 0x00, 0x00, 0xFF, 0x05];
                    s.write_all(&resp).await.expect("write 403");
                } else {
                    // Missing token: return 401 Unauthorized via literal Name Ref to :status
                    let resp = [0x01, 0x08, 0x00, 0x00, 0x5F, 0x0A, 0x03, b'4', b'0', b'1'];
                    s.write_all(&resp).await.expect("write 401");
                }
                let _ = s.finish();
            }
            let _ = done_rx.await;
        });

        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let conn = client_endpoint.connect(server_addr, "localhost").unwrap().await.unwrap();

        // Case 1: Valid token -> 200 OK
        {
            let (mut s, mut r) = conn.open_bi().await.unwrap();
            let req = build_h3_connect_headers("149.154.167.50:443", "localhost", Some(MASQUE_CONNECT_IP_PROTO), None, Some("valid_secret_token_123"));
            s.write_all(&req).await.unwrap();
            let (status, _) = read_h3_headers_response(&mut r, Duration::from_secs(3)).await.unwrap();
            assert_eq!(status, 200, "Valid token must succeed with HTTP 200");
            assert!(classify_http_auth_error(status, "valid").is_none());
        }

        // Case 2: Wrong token -> 403 Forbidden (Auth failure)
        {
            let (mut s, mut r) = conn.open_bi().await.unwrap();
            let req = build_h3_connect_headers("149.154.167.50:443", "localhost", Some(MASQUE_CONNECT_IP_PROTO), None, Some("wrong_token_456"));
            s.write_all(&req).await.unwrap();
            let (status, _) = read_h3_headers_response(&mut r, Duration::from_secs(3)).await.unwrap();
            assert_eq!(status, 403, "Wrong token must return HTTP 403 Forbidden");
            let auth_err = classify_http_auth_error(status, "wrong token");
            assert!(matches!(auth_err, Some(MasqueAuthError::Forbidden(_))));
        }

        // Case 3: Missing token -> 401 Unauthorized (Auth failure)
        {
            let (mut s, mut r) = conn.open_bi().await.unwrap();
            let req = build_h3_connect_headers("149.154.167.50:443", "localhost", Some(MASQUE_CONNECT_IP_PROTO), None, None);
            s.write_all(&req).await.unwrap();
            let (status, _) = read_h3_headers_response(&mut r, Duration::from_secs(3)).await.unwrap();
            assert_eq!(status, 401, "Missing token must return HTTP 401 Unauthorized");
            let auth_err = classify_http_auth_error(status, "missing token");
            assert!(matches!(auth_err, Some(MasqueAuthError::Unauthorized(_))));
        }

        let _ = done_tx.send(());
        server_task.await.unwrap();
    }

    #[tokio::test]
    async fn test_auth_failure_aborts_without_port_failover_and_never_ready() {
        // Ensure clean initial state
        reset_masque_auth_error();
        assert!(get_last_masque_auth_error().is_none());
        assert!(!is_mtls_rejected());

        // Simulate recording a fatal certificate revocation error
        let fatal_err = MasqueAuthError::CertificateRevoked("TLS alert 44 certificate_revoked from Anycast gateway".to_string());
        record_masque_auth_failure(fatal_err.clone());

        // Verify state
        assert_eq!(get_last_masque_auth_error(), Some(fatal_err));
        assert!(is_mtls_rejected(), "is_mtls_rejected must be true when cert revocation is recorded");

        // When a fatal auth error is active, masque_acquire_tunnel must abort immediately
        // and return None WITHOUT trying Anycast ports/endpoints!
        let cancel = CancellationToken::new();
        let tunnel = masque_acquire_tunnel("149.154.167.50:443", &cancel).await;
        assert!(tunnel.is_none(), "masque_acquire_tunnel must return None immediately on auth failure");

        // Verify that without a tunnel, a session can NEVER become Ready via B04
        assert!(ACTIVE_QUIC_SESSIONS.lock().is_empty(), "Active sessions must remain empty on auth rejection");

        // Clean up
        reset_masque_auth_error();
        assert!(get_last_masque_auth_error().is_none());
    }

    #[test]
    fn test_masque_session_key_and_generation_invalidation() {
        let creds1 = MasqueCredentials {
            scheme: MasqueAuthScheme::Mtls,
            client_cert_der: Some(vec![1, 2, 3, 4]),
            client_key_der: Some(vec![5, 6, 7, 8]),
            gateway_bearer_token: None,
            api_management_token: Some("token_1".to_string()),
        };
        let hash1 = creds1.identity_hash();

        let creds2 = MasqueCredentials {
            scheme: MasqueAuthScheme::Mtls,
            client_cert_der: Some(vec![9, 9, 9, 9]),
            client_key_der: Some(vec![5, 6, 7, 8]),
            gateway_bearer_token: None,
            api_management_token: Some("token_1".to_string()),
        };
        let hash2 = creds2.identity_hash();
        assert_ne!(hash1, hash2, "Different client certificate must yield different identity hash");

        let key_gen = get_warp_config_generation();
        let key = MasqueSessionKey {
            sni: "gateway.icloud.com".to_string(),
            endpoint_addr: "162.159.192.1:443".parse().unwrap(),
            address_family: QuicAddressFamily::Ipv4,
            identity_hash: hash1,
            config_generation: key_gen,
            network_generation: 1,
            variant: MasqueConnectVariant::LegacyCfConnectIp {
                uri_template: Some("/masque".to_string()),
            },
        };

        // Matching credentials, generation, network generation, and SNI
        assert!(key.is_valid_for("gateway.icloud.com", key_gen, 1, &hash1, QuicAddressFamily::Ipv4));

        // Mismatched SNI
        assert!(!key.is_valid_for("other.sni.com", key_gen, 1, &hash1, QuicAddressFamily::Ipv4));

        // Mismatched config generation (profile updated)
        assert!(!key.is_valid_for("gateway.icloud.com", key_gen + 1, 1, &hash1, QuicAddressFamily::Ipv4));

        // Mismatched network generation (interface reset)
        assert!(!key.is_valid_for("gateway.icloud.com", key_gen, 2, &hash1, QuicAddressFamily::Ipv4));

        // Mismatched address family (IPv4 key requested for IPv6 candidate)
        assert!(!key.is_valid_for("gateway.icloud.com", key_gen, 1, &hash1, QuicAddressFamily::Ipv6));

        // Mismatched identity hash (different identity/certificate)
        assert!(!key.is_valid_for("gateway.icloud.com", key_gen, 1, &hash2, QuicAddressFamily::Ipv4));

        // Configuration generation increments on setters
        let gen_before = get_warp_config_generation();
        set_warp_config("188.114.96.1:8095", "gateway.icloud.com", "token_abc", "172.16.0.2", "");
        let gen_after = get_warp_config_generation();
        assert!(gen_after > gen_before, "set_warp_config must increment config generation");

        set_warp_crypto("YWJj", "ZGVm", "Z2hp");
        let gen_crypto = get_warp_config_generation();
        assert!(gen_crypto > gen_after, "set_warp_crypto must increment config generation");

        assert!(!key.is_valid_for("gateway.icloud.com", gen_crypto, 1, &hash1, QuicAddressFamily::Ipv4), "Old session key must be invalid after profile updates");
    }

    #[test]
    fn test_masque_connect_variants_and_adapters() {
        let v_rfc = MasqueConnectVariant::Rfc9484ConnectIp {
            uri_template: Some("/custom-rfc".to_string()),
        };
        assert_eq!(v_rfc.protocol_header(), Some(MASQUE_CONNECT_IP_PROTO));
        assert_eq!(v_rfc.uri_path(None), Some("/custom-rfc"));
        assert!(!v_rfc.is_raw_l4());

        let v_cf = MasqueConnectVariant::LegacyCfConnectIp {
            uri_template: None,
        };
        assert_eq!(v_cf.protocol_header(), Some("cf-connect-ip"));
        assert_eq!(v_cf.uri_path(Some("/fallback")), Some("/fallback"));
        assert!(!v_cf.is_raw_l4());

        let v_l4 = MasqueConnectVariant::StandardRfc9114L4;
        assert_eq!(v_l4.protocol_header(), None);
        assert_eq!(v_l4.uri_path(Some("/ignored")), None);
        assert!(v_l4.is_raw_l4());
    }

    #[tokio::test]
    async fn test_multiplexing_preserves_negotiated_variant_on_peer_single_allowed() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");

        let (done_tx, done_rx) = tokio::sync::oneshot::channel::<()>();

        // Mock server: strictly enforces cf-connect-ip only!
        // If client sends connect-ip -> server rejects with HTTP 400.
        // If client sends cf-connect-ip -> server accepts with HTTP 200.
        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("accept client");
            let server_conn = incoming.await.expect("handshake");

            // bi 1: Client dials with connect-ip (Variant 1) -> Server rejects
            let (mut s1, mut r1) = server_conn.accept_bi().await.expect("accept bi 1");
            let mut req1 = vec![0u8; 1024];
            let n1 = r1.read(&mut req1).await.expect("read 1").expect("not eof");
            req1.truncate(n1);
            let s_req1 = String::from_utf8_lossy(&req1);
            assert!(s_req1.contains("connect-ip"), "First attempt must be RFC 9484 connect-ip");
            // Reject with 400 Bad Request
            let resp_400 = [0x01, 0x08, 0x00, 0x00, 0x5F, 0x0A, 0x03, b'4', b'0', b'0'];
            s1.write_all(&resp_400).await.expect("write 400");
            let _ = s1.finish();

            // bi 2: Client falls back to cf-connect-ip (Variant 2) -> Server accepts
            let (mut s2, mut r2) = server_conn.accept_bi().await.expect("accept bi 2");
            let mut req2 = vec![0u8; 1024];
            let n2 = r2.read(&mut req2).await.expect("read 2").expect("not eof");
            req2.truncate(n2);
            let s_req2 = String::from_utf8_lossy(&req2);
            assert!(s_req2.contains("cf-connect-ip"), "Fallback attempt must be cf-connect-ip");
            // Accept with 200 OK
            let resp_200 = [0x01, 0x03, 0x00, 0x00, 0xD9];
            s2.write_all(&resp_200).await.expect("write 200");

            // bi 3: Second request on cached session! Must use cf-connect-ip directly!
            let (mut s3, mut r3) = server_conn.accept_bi().await.expect("accept bi 3 (cached)");
            let mut req3 = vec![0u8; 1024];
            let n3 = r3.read(&mut req3).await.expect("read 3").expect("not eof");
            req3.truncate(n3);
            let s_req3 = String::from_utf8_lossy(&req3);
            assert!(
                s_req3.contains("cf-connect-ip"),
                "Multiplexed request on cached session MUST preserve negotiated cf-connect-ip!"
            );

            // Accept with 200 OK
            s3.write_all(&resp_200).await.expect("write 200 to bi 3");

            let _ = done_rx.await;
        });

        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let conn = client_endpoint.connect(server_addr, "localhost").unwrap().await.unwrap();
        let dispatcher = create_datagram_dispatcher(conn.clone());
        let ctrl_s = conn.open_uni().await.unwrap();
        let ctrl_arc = Arc::new(tokio::sync::Mutex::new(ctrl_s));

        // Simulate Request 1 negotiation:
        // Client tries Variant 1: connect-ip
        let (mut c_s1, mut c_r1) = conn.open_bi().await.unwrap();
        let req1 = build_h3_connect_headers("149.154.167.50:443", "localhost", Some(MASQUE_CONNECT_IP_PROTO), None, None);
        c_s1.write_all(&req1).await.unwrap();
        let (status1, _) = read_h3_headers_response(&mut c_r1, Duration::from_secs(3)).await.unwrap();
        assert_eq!(status1, 400, "Server rejected connect-ip with 400");

        // Client falls back to Variant 2: cf-connect-ip
        let (mut c_s2, mut c_r2) = conn.open_bi().await.unwrap();
        let req2 = build_h3_connect_headers("149.154.167.50:443", "localhost", Some("cf-connect-ip"), None, None);
        c_s2.write_all(&req2).await.unwrap();
        let (status2, leftovers2) = read_h3_headers_response(&mut c_r2, Duration::from_secs(3)).await.unwrap();
        assert_eq!(status2, 200, "Server accepted cf-connect-ip with 200");

        let negotiated_variant = MasqueConnectVariant::LegacyCfConnectIp { uri_template: None };
        let server_family = QuicAddressFamily::from_socket_addr(&server_addr);
        let session_key = MasqueSessionKey {
            sni: "localhost".to_string(),
            endpoint_addr: server_addr,
            address_family: server_family,
            identity_hash: [42u8; 32],
            config_generation: 1,
            network_generation: 1,
            variant: negotiated_variant.clone(),
        };

        // Cache session
        {
            let mut guard = ACTIVE_QUIC_SESSIONS.lock();
            guard.insert(server_family, ActiveQuicSession {
                key: session_key.clone(),
                conn: conn.clone(),
                dispatcher: dispatcher.clone(),
                control_stream: ctrl_arc.clone(),
                server_settings: Http3ServerSettings {
                    enable_connect_protocol: true,
                    h3_datagram: true,
                    ..Default::default()
                },
                goaway_received: Arc::new(AtomicBool::new(false)),
                cancel_token: CancellationToken::new(),
                session_tasks: Arc::new(parking_lot::Mutex::new(Vec::new())),
            });
        }

        // Request 1 tunnel instance via from_negotiated_stream
        let tunnel1 = MasqueTunnel::from_negotiated_stream(
            c_s2, c_r2, leftovers2, conn.clone(), server_addr, "149.154.167.50:443".to_string(),
            &negotiated_variant, dispatcher.clone(), ctrl_arc.clone(),
        );
        assert!(!tunnel1.is_raw_l4);
        assert!(tunnel1.capsule_rx.lock().await.is_some());
        assert!(tunnel1.recv_reader.lock().await.is_none());

        // Now Request 2 on the cached session:
        let cached = {
            let guard = ACTIVE_QUIC_SESSIONS.lock();
            guard.get(&server_family).map(|s| (s.key.clone(), s.conn.clone(), s.dispatcher.clone(), s.control_stream.clone()))
        }.expect("cached session must exist");

        let (cached_key, cached_conn, cached_disp, cached_ctrl) = cached;
        assert_eq!(cached_key.variant, negotiated_variant, "Cached session key must preserve LegacyCfConnectIp");

        let (mut c_s3, mut c_r3) = cached_conn.open_bi().await.unwrap();
        // Client uses preserved variant from session_key
        let req3 = build_h3_connect_headers("149.154.167.51:443", &cached_key.sni, cached_key.variant.protocol_header(), cached_key.variant.uri_path(None), None);
        c_s3.write_all(&req3).await.unwrap();
        let (status3, leftovers3) = read_h3_headers_response(&mut c_r3, Duration::from_secs(3)).await.unwrap();
        assert_eq!(status3, 200, "Server accepted Request 2 on preserved variant");

        let tunnel2 = MasqueTunnel::from_negotiated_stream(
            c_s3, c_r3, leftovers3, cached_conn, server_addr, "149.154.167.51:443".to_string(),
            &cached_key.variant, cached_disp, cached_ctrl,
        );
        assert!(!tunnel2.is_raw_l4);
        assert!(tunnel2.capsule_rx.lock().await.is_some());
        assert!(tunnel2.recv_reader.lock().await.is_none());

        let _ = done_tx.send(());
        server_task.await.unwrap();
    }

    #[tokio::test]
    async fn test_l4_variant_multiplexing_preserves_raw_stream_adapter() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");

        let (done_tx, done_rx) = tokio::sync::oneshot::channel::<()>();

        // Mock server: strictly enforces Standard RFC 9114 L4 CONNECT (:protocol MUST NOT be present)
        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("accept client");
            let server_conn = incoming.await.expect("handshake");

            // bi 1: Request 1 (negotiated L4 CONNECT)
            let (mut s1, mut r1) = server_conn.accept_bi().await.expect("accept bi 1");
            let mut req1 = vec![0u8; 1024];
            let n1 = r1.read(&mut req1).await.expect("read 1").expect("not eof");
            req1.truncate(n1);
            let s_req1 = String::from_utf8_lossy(&req1);
            assert!(!s_req1.contains("connect-ip"), "L4 CONNECT must not contain connect-ip header");
            assert!(!s_req1.contains("cf-connect-ip"), "L4 CONNECT must not contain cf-connect-ip header");
            // Reply 200 OK + send DATA frame with "stream_echo_1"
            let resp_200 = [0x01, 0x03, 0x00, 0x00, 0xD9];
            s1.write_all(&resp_200).await.expect("write 200");
            let data_frame_1 = [0x00, 0x0D, b's', b't', b'r', b'e', b'a', b'm', b'_', b'e', b'c', b'h', b'o', b'_', b'1'];
            s1.write_all(&data_frame_1).await.expect("write data 1");

            // bi 2: Request 2 on cached session! Must use Standard L4 CONNECT directly!
            let (mut s2, mut r2) = server_conn.accept_bi().await.expect("accept bi 2 (cached)");
            let mut req2 = vec![0u8; 1024];
            let n2 = r2.read(&mut req2).await.expect("read 2").expect("not eof");
            req2.truncate(n2);
            let s_req2 = String::from_utf8_lossy(&req2);
            assert!(!s_req2.contains("connect-ip"), "Cached L4 session must not send connect-ip");
            assert!(!s_req2.contains("cf-connect-ip"), "Cached L4 session must not send cf-connect-ip");
            s2.write_all(&resp_200).await.expect("write 200 to bi 2");
            let data_frame_2 = [0x00, 0x0D, b's', b't', b'r', b'e', b'a', b'm', b'_', b'e', b'c', b'h', b'o', b'_', b'2'];
            s2.write_all(&data_frame_2).await.expect("write data 2");

            let _ = done_rx.await;
        });

        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let conn = client_endpoint.connect(server_addr, "localhost").unwrap().await.unwrap();
        let dispatcher = create_datagram_dispatcher(conn.clone());
        let ctrl_s = conn.open_uni().await.unwrap();
        let ctrl_arc = Arc::new(tokio::sync::Mutex::new(ctrl_s));

        // 1. Request 1
        let (mut c_s1, mut c_r1) = conn.open_bi().await.unwrap();
        let req1 = build_h3_connect_headers("149.154.167.50:443", "localhost", None, None, None);
        c_s1.write_all(&req1).await.unwrap();
        let (status1, leftovers1) = read_h3_headers_response(&mut c_r1, Duration::from_secs(3)).await.unwrap();
        assert_eq!(status1, 200);

        let negotiated_variant = MasqueConnectVariant::StandardRfc9114L4;
        let server_family = QuicAddressFamily::from_socket_addr(&server_addr);
        let session_key = MasqueSessionKey {
            sni: "localhost".to_string(),
            endpoint_addr: server_addr,
            address_family: server_family,
            identity_hash: [77u8; 32],
            config_generation: 1,
            network_generation: 1,
            variant: negotiated_variant.clone(),
        };

        {
            let mut guard = ACTIVE_QUIC_SESSIONS.lock();
            guard.insert(server_family, ActiveQuicSession {
                key: session_key.clone(),
                conn: conn.clone(),
                dispatcher: dispatcher.clone(),
                control_stream: ctrl_arc.clone(),
                server_settings: Http3ServerSettings {
                    enable_connect_protocol: false,
                    h3_datagram: false,
                    ..Default::default()
                },
                goaway_received: Arc::new(AtomicBool::new(false)),
                cancel_token: CancellationToken::new(),
                session_tasks: Arc::new(parking_lot::Mutex::new(Vec::new())),
            });
        }

        let tunnel1 = MasqueTunnel::from_negotiated_stream(
            c_s1, c_r1, leftovers1, conn.clone(), server_addr, "149.154.167.50:443".to_string(),
            &negotiated_variant, dispatcher.clone(), ctrl_arc.clone(),
        );
        assert!(tunnel1.is_raw_l4, "L4 tunnel must set is_raw_l4 to true");
        assert!(tunnel1.recv_reader.lock().await.is_some(), "L4 tunnel must attach H3FrameReader adapter");
        assert!(tunnel1.capsule_rx.lock().await.is_none(), "L4 tunnel must not attach capsule receiver");

        let data1 = tunnel1.recv_with_timeout(Duration::from_secs(2)).await.unwrap();
        assert_eq!(&data1, b"stream_echo_1", "L4 adapter must receive DATA frame payload on Request 1");

        // 2. Request 2 on cached session
        let cached = {
            let guard = ACTIVE_QUIC_SESSIONS.lock();
            guard.get(&server_family).map(|s| (s.key.clone(), s.conn.clone(), s.dispatcher.clone(), s.control_stream.clone()))
        }.expect("cached session must exist");

        let (cached_key, cached_conn, cached_disp, cached_ctrl) = cached;
        assert_eq!(cached_key.variant, MasqueConnectVariant::StandardRfc9114L4);

        let (mut c_s2, mut c_r2) = cached_conn.open_bi().await.unwrap();
        let req2 = build_h3_connect_headers("149.154.167.51:443", &cached_key.sni, cached_key.variant.protocol_header(), cached_key.variant.uri_path(None), None);
        c_s2.write_all(&req2).await.unwrap();
        let (status2, leftovers2) = read_h3_headers_response(&mut c_r2, Duration::from_secs(3)).await.unwrap();
        assert_eq!(status2, 200);

        let tunnel2 = MasqueTunnel::from_negotiated_stream(
            c_s2, c_r2, leftovers2, cached_conn, server_addr, "149.154.167.51:443".to_string(),
            &cached_key.variant, cached_disp, cached_ctrl,
        );
        assert!(tunnel2.is_raw_l4, "Multiplexed tunnel on cached session must preserve is_raw_l4");
        assert!(tunnel2.recv_reader.lock().await.is_some(), "Multiplexed tunnel must preserve H3FrameReader adapter");
        assert!(tunnel2.capsule_rx.lock().await.is_none());

        let data2 = tunnel2.recv_with_timeout(Duration::from_secs(2)).await.unwrap();
        assert_eq!(&data2, b"stream_echo_2", "L4 adapter must receive DATA frame payload on multiplexed Request 2");

        let _ = done_tx.send(());
        server_task.await.unwrap();
    }

    #[test]
    fn test_profile_change_does_not_reuse_old_session() {
        reset_quic_endpoint();

        let initial_gen = get_warp_config_generation();
        let initial_net_gen = get_underlying_network_generation();
        let initial_hash = [11u8; 32];
        let key = MasqueSessionKey {
            sni: "gateway.icloud.com".to_string(),
            endpoint_addr: "162.159.192.1:443".parse().unwrap(),
            address_family: QuicAddressFamily::Ipv4,
            identity_hash: initial_hash,
            config_generation: initial_gen,
            network_generation: initial_net_gen,
            variant: MasqueConnectVariant::Rfc9484ConnectIp { uri_template: None },
        };

        // Key is initially valid
        assert!(key.is_valid_for("gateway.icloud.com", initial_gen, initial_net_gen, &initial_hash, QuicAddressFamily::Ipv4));

        // Profile change: user changes crypto or config
        set_warp_crypto("bXlfbmV3X2tleQ==", "bXlfbmV3X2NlcnQ=", "cGVlcl9rZXk=");

        let new_gen = get_warp_config_generation();
        assert!(new_gen > initial_gen, "Config generation must increase upon profile change");

        // Old key is now INVALID against current generation!
        assert!(!key.is_valid_for("gateway.icloud.com", new_gen, initial_net_gen, &initial_hash, QuicAddressFamily::Ipv4));

        // ACTIVE_QUIC_SESSIONS was dropped and cleared
        assert!(ACTIVE_QUIC_SESSIONS.lock().is_empty(), "Active sessions must be cleared after profile change");
    }

    #[test]
    fn test_http3_server_settings_parser_and_reserved_h2_rejection() {
        // 1. Valid settings with Extended CONNECT (0x08 = 1) and Datagrams (0x33 = 1)
        let mut buf = Vec::new();
        encode_varint(&mut buf, SETTINGS_ENABLE_CONNECT_PROTOCOL);
        encode_varint(&mut buf, 1);
        encode_varint(&mut buf, SETTINGS_H3_DATAGRAM);
        encode_varint(&mut buf, 1);
        encode_varint(&mut buf, 0x06); // MAX_FIELD_SECTION_SIZE
        encode_varint(&mut buf, 8192);

        let parsed = Http3ServerSettings::parse(&buf).expect("valid settings");
        assert!(parsed.enable_connect_protocol);
        assert!(parsed.h3_datagram);
        assert_eq!(parsed.max_field_section_size, Some(8192));

        // 2. Missing Extended CONNECT / Datagrams
        let empty_parsed = Http3ServerSettings::parse(&[]).expect("empty settings");
        assert!(!empty_parsed.enable_connect_protocol);
        assert!(!empty_parsed.h3_datagram);
        assert_eq!(empty_parsed.max_field_section_size, None);

        // 3. Reserved H2 setting identifier 0x02 (SETTINGS_ENABLE_PUSH) must be rejected with H3_SETTINGS_ERROR (0x0109)
        let mut h2_buf = Vec::new();
        encode_varint(&mut h2_buf, 0x02); // Reserved HTTP/2 setting ID
        encode_varint(&mut h2_buf, 0);
        let err = Http3ServerSettings::parse(&h2_buf).expect_err("must reject reserved H2 setting");
        assert_eq!(err, H3_SETTINGS_ERROR);

        // 4. Invalid boolean value > 1 for SETTINGS_ENABLE_CONNECT_PROTOCOL
        let mut inv_buf = Vec::new();
        encode_varint(&mut inv_buf, SETTINGS_ENABLE_CONNECT_PROTOCOL);
        encode_varint(&mut inv_buf, 2); // invalid boolean
        let err_bool = Http3ServerSettings::parse(&inv_buf).expect_err("must reject boolean > 1");
        assert_eq!(err_bool, H3_SETTINGS_ERROR);
    }

    #[tokio::test]
    async fn test_server_goaway_frame_terminates_session_reuse() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let (server_ready_tx, server_ready_rx) = tokio::sync::oneshot::channel::<()>();
        let (done_tx, done_rx) = tokio::sync::oneshot::channel::<()>();

        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("accept");
            let server_conn = incoming.await.expect("handshake");

            let mut server_ctrl = server_conn.open_uni().await.expect("server open control");
            let mut ctrl_buf = Vec::new();
            encode_varint(&mut ctrl_buf, H3_STREAM_CONTROL);
            encode_varint(&mut ctrl_buf, H3_FRAME_SETTINGS);
            encode_varint(&mut ctrl_buf, 0); // empty settings
            server_ctrl.write_all(&ctrl_buf).await.expect("write settings");

            let _ = server_ready_rx.await;

            // Send GOAWAY frame with stream_id = 42
            let mut goaway_buf = Vec::new();
            encode_varint(&mut goaway_buf, H3_FRAME_GOAWAY);
            let mut goaway_payload = Vec::new();
            encode_varint(&mut goaway_payload, 42);
            encode_varint(&mut goaway_buf, goaway_payload.len() as u64);
            goaway_buf.extend_from_slice(&goaway_payload);

            server_ctrl.write_all(&goaway_buf).await.expect("write goaway");

            let _ = done_rx.await;
        });

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .expect("client handshake");

        let supervisor = spawn_server_stream_supervisor(client_conn.clone());

        // Wait for settings
        let mut rx = supervisor.server_settings_rx.clone();
        let _ = rx.changed().await;

        let server_family = QuicAddressFamily::from_socket_addr(&server_addr);
        let session_key = MasqueSessionKey {
            sni: "localhost".to_string(),
            endpoint_addr: server_addr,
            address_family: server_family,
            identity_hash: [55u8; 32],
            config_generation: 1,
            network_generation: 1,
            variant: MasqueConnectVariant::StandardRfc9114L4,
        };

        // Cache session in ACTIVE_QUIC_SESSIONS
        {
            let mut guard = ACTIVE_QUIC_SESSIONS.lock();
            let ctrl_s = client_conn.open_uni().await.unwrap();
            guard.insert(server_family, ActiveQuicSession {
                key: session_key,
                conn: client_conn.clone(),
                dispatcher: create_datagram_dispatcher(client_conn.clone()),
                control_stream: Arc::new(tokio::sync::Mutex::new(ctrl_s)),
                server_settings: Http3ServerSettings::default(),
                goaway_received: supervisor.goaway_received.clone(),
                cancel_token: CancellationToken::new(),
                session_tasks: Arc::new(parking_lot::Mutex::new(Vec::new())),
            });
        }

        assert!(ACTIVE_QUIC_SESSIONS.lock().get(&server_family).is_some());
        assert!(!supervisor.goaway_received.load(Ordering::SeqCst));

        // Signal server to send GOAWAY
        let _ = server_ready_tx.send(());

        // Wait for supervisor to receive and process GOAWAY
        let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
        while !supervisor.goaway_received.load(Ordering::SeqCst) && tokio::time::Instant::now() < deadline {
            tokio::time::sleep(Duration::from_millis(20)).await;
        }

        assert!(supervisor.goaway_received.load(Ordering::SeqCst), "Supervisor must mark goaway_received");
        assert_eq!(supervisor.goaway_stream_id.load(Ordering::SeqCst), 42);

        // Verify ACTIVE_QUIC_SESSIONS was cleared automatically upon GOAWAY receipt
        assert!(ACTIVE_QUIC_SESSIONS.lock().is_empty(), "Cached session must be purged after GOAWAY");

        let _ = done_tx.send(());
        server_task.await.unwrap();
    }

    #[tokio::test]
    async fn test_duplicate_server_control_stream_closes_with_stream_creation_error() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("accept");
            let server_conn = incoming.await.expect("handshake");

            // Control stream 1
            let mut ctrl1 = server_conn.open_uni().await.expect("ctrl 1");
            let mut buf1 = Vec::new();
            encode_varint(&mut buf1, H3_STREAM_CONTROL);
            encode_varint(&mut buf1, H3_FRAME_SETTINGS);
            encode_varint(&mut buf1, 0);
            ctrl1.write_all(&buf1).await.expect("write ctrl 1");

            tokio::time::sleep(Duration::from_millis(50)).await;

            // Control stream 2 (Violation of RFC 9114 §6.2.1)
            let mut ctrl2 = server_conn.open_uni().await.expect("ctrl 2");
            let mut buf2 = Vec::new();
            encode_varint(&mut buf2, H3_STREAM_CONTROL);
            ctrl2.write_all(&buf2).await.expect("write ctrl 2");

            let close_reason = server_conn.closed().await;
            close_reason
        });

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .expect("client handshake");

        let _supervisor = spawn_server_stream_supervisor(client_conn);

        let server_close_reason = server_task.await.expect("server task finished");

        match server_close_reason {
            quinn::ConnectionError::ApplicationClosed(app_close) => {
                assert_eq!(
                    u64::from(app_close.error_code),
                    H3_STREAM_CREATION_ERROR as u64,
                    "Duplicate control stream must close with H3_STREAM_CREATION_ERROR (0x0103)"
                );
            }
            other => panic!("Expected ApplicationClosed(0x0103), got {:?}", other),
        }
    }

    #[tokio::test]
    async fn test_first_frame_not_settings_closes_with_missing_settings() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("accept");
            let server_conn = incoming.await.expect("handshake");

            let mut server_ctrl = server_conn.open_uni().await.expect("server open control");
            let mut ctrl_buf = Vec::new();
            encode_varint(&mut ctrl_buf, H3_STREAM_CONTROL);
            // RFC 9114 §7.2.4: First frame MUST be SETTINGS. Sending GOAWAY (0x07) instead!
            encode_varint(&mut ctrl_buf, H3_FRAME_GOAWAY);
            encode_varint(&mut ctrl_buf, 1);
            encode_varint(&mut ctrl_buf, 0);
            server_ctrl.write_all(&ctrl_buf).await.expect("write non-settings first");

            let close_reason = server_conn.closed().await;
            close_reason
        });

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .expect("client handshake");

        let _supervisor = spawn_server_stream_supervisor(client_conn);

        let server_close_reason = server_task.await.expect("server task finished");

        match server_close_reason {
            quinn::ConnectionError::ApplicationClosed(app_close) => {
                assert_eq!(
                    u64::from(app_close.error_code),
                    H3_MISSING_SETTINGS as u64,
                    "Non-SETTINGS first frame must close with H3_MISSING_SETTINGS (0x010a)"
                );
            }
            other => panic!("Expected ApplicationClosed(0x010a), got {:?}", other),
        }
    }

    #[tokio::test]
    async fn test_duplicate_settings_frame_closes_with_frame_unexpected() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("accept");
            let server_conn = incoming.await.expect("handshake");

            let mut server_ctrl = server_conn.open_uni().await.expect("server open control");
            let mut ctrl_buf = Vec::new();
            encode_varint(&mut ctrl_buf, H3_STREAM_CONTROL);
            // First SETTINGS frame
            encode_varint(&mut ctrl_buf, H3_FRAME_SETTINGS);
            encode_varint(&mut ctrl_buf, 0);
            server_ctrl.write_all(&ctrl_buf).await.expect("write first settings");

            tokio::time::sleep(Duration::from_millis(50)).await;

            // Second duplicate SETTINGS frame (RFC 9114 §7.2.4 violation)
            let mut dup_buf = Vec::new();
            encode_varint(&mut dup_buf, H3_FRAME_SETTINGS);
            encode_varint(&mut dup_buf, 0);
            server_ctrl.write_all(&dup_buf).await.expect("write dup settings");

            let close_reason = server_conn.closed().await;
            close_reason
        });

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .expect("client handshake");

        let _supervisor = spawn_server_stream_supervisor(client_conn);

        let server_close_reason = server_task.await.expect("server task finished");

        match server_close_reason {
            quinn::ConnectionError::ApplicationClosed(app_close) => {
                assert_eq!(
                    u64::from(app_close.error_code),
                    H3_FRAME_UNEXPECTED as u64,
                    "Duplicate SETTINGS frame must close with H3_FRAME_UNEXPECTED (0x0105)"
                );
            }
            other => panic!("Expected ApplicationClosed(0x0105), got {:?}", other),
        }
    }

    #[tokio::test]
    async fn test_excessive_unknown_uni_streams_close_with_excessive_load() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("accept");
            let server_conn = incoming.await.expect("handshake");

            let mut streams = Vec::new();
            // Open 17 unknown unidirectional streams (exceeding MAX_CONCURRENT_UNKNOWN_UNI_STREAMS = 16)
            for _ in 0..17 {
                if let Ok(mut uni) = server_conn.open_uni().await {
                    let mut type_buf = Vec::new();
                    encode_varint(&mut type_buf, 0x9999); // unknown uni stream type
                    let _ = uni.write_all(&type_buf).await;
                    streams.push(uni);
                }
            }

            let close_reason = server_conn.closed().await;
            close_reason
        });

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .expect("client handshake");

        let _supervisor = spawn_server_stream_supervisor(client_conn);

        let server_close_reason = server_task.await.expect("server task finished");

        match server_close_reason {
            quinn::ConnectionError::ApplicationClosed(app_close) => {
                assert_eq!(
                    u64::from(app_close.error_code),
                    H3_EXCESSIVE_LOAD as u64,
                    "Excessive unknown unidirectional streams must close with H3_EXCESSIVE_LOAD (0x0107)"
                );
            }
            other => panic!("Expected ApplicationClosed(0x0107), got {:?}", other),
        }
    }

    #[tokio::test]
    async fn test_duplicate_qpack_streams_close_with_stream_creation_error() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("accept");
            let server_conn = incoming.await.expect("handshake");

            // First QPACK Encoder stream
            let mut q1 = server_conn.open_uni().await.expect("q1");
            let mut buf1 = Vec::new();
            encode_varint(&mut buf1, H3_STREAM_QPACK_ENCODER);
            q1.write_all(&buf1).await.expect("write q1");

            tokio::time::sleep(Duration::from_millis(50)).await;

            // Second duplicate QPACK Encoder stream (RFC 9204 §4.2 violation)
            let mut q2 = server_conn.open_uni().await.expect("q2");
            let mut buf2 = Vec::new();
            encode_varint(&mut buf2, H3_STREAM_QPACK_ENCODER);
            q2.write_all(&buf2).await.expect("write q2");

            let close_reason = server_conn.closed().await;
            close_reason
        });

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .expect("client handshake");

        let _supervisor = spawn_server_stream_supervisor(client_conn);

        let server_close_reason = server_task.await.expect("server task finished");

        match server_close_reason {
            quinn::ConnectionError::ApplicationClosed(app_close) => {
                assert_eq!(
                    u64::from(app_close.error_code),
                    H3_STREAM_CREATION_ERROR as u64,
                    "Duplicate QPACK stream must close with H3_STREAM_CREATION_ERROR (0x0103)"
                );
            }
            other => panic!("Expected ApplicationClosed(0x0103), got {:?}", other),
        }
    }

    #[tokio::test]
    async fn test_peer_missing_connect_or_datagram_settings_rejected() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("accept");
            let server_conn = incoming.await.expect("handshake");

            let mut server_ctrl = server_conn.open_uni().await.expect("server open control");
            let mut ctrl_buf = Vec::new();
            encode_varint(&mut ctrl_buf, H3_STREAM_CONTROL);
            // SETTINGS frame without CONNECT_PROTOCOL or H3_DATAGRAM
            encode_varint(&mut ctrl_buf, H3_FRAME_SETTINGS);
            let mut payload = Vec::new();
            encode_varint(&mut payload, 0x06); // SETTINGS_MAX_FIELD_SECTION_SIZE
            encode_varint(&mut payload, 8192);
            encode_varint(&mut ctrl_buf, payload.len() as u64);
            ctrl_buf.extend_from_slice(&payload);
            server_ctrl.write_all(&ctrl_buf).await.expect("write settings");

            let close_reason = server_conn.closed().await;
            close_reason
        });

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .expect("client handshake");

        let supervisor = spawn_server_stream_supervisor(client_conn.clone());

        // Wait for settings
        let mut rx = supervisor.server_settings_rx.clone();
        let settings = tokio::time::timeout(Duration::from_millis(500), async {
            loop {
                if let Some(ref s) = *rx.borrow() {
                    return s.clone();
                }
                rx.changed().await.unwrap();
            }
        })
        .await
        .expect("received settings");

        // Verify capability rejection logic:
        assert!(!settings.enable_connect_protocol);
        assert!(!settings.h3_datagram);

        // Perform rejection close as done in masque_acquire_tunnel
        if !settings.enable_connect_protocol {
            client_conn.close(
                quinn::VarInt::from_u32(H3_SETTINGS_ERROR),
                b"missing SETTINGS_ENABLE_CONNECT_PROTOCOL",
            );
        }

        let server_close_reason = server_task.await.expect("server task finished");
        match server_close_reason {
            quinn::ConnectionError::ApplicationClosed(app_close) => {
                assert_eq!(
                    u64::from(app_close.error_code),
                    H3_SETTINGS_ERROR as u64,
                    "Peer missing SETTINGS_ENABLE_CONNECT_PROTOCOL must close with H3_SETTINGS_ERROR (0x0109)"
                );
            }
            other => panic!("Expected ApplicationClosed(0x0109), got {:?}", other),
        }
    }

    #[tokio::test]
    async fn test_quic_address_family_and_endpoints_cache_separates_ipv4_and_ipv6() {
        let v4_addr: SocketAddr = "162.159.192.1:443".parse().unwrap();
        let v6_addr: SocketAddr = "[2606:4700:d0::a29f:c001]:8095".parse().unwrap();

        let fam4 = QuicAddressFamily::from_socket_addr(&v4_addr);
        let fam6 = QuicAddressFamily::from_socket_addr(&v6_addr);

        assert_eq!(fam4, QuicAddressFamily::Ipv4);
        assert_eq!(fam6, QuicAddressFamily::Ipv6);
        assert!(fam4.is_ipv4());
        assert!(!fam4.is_ipv6());
        assert!(fam6.is_ipv6());
        assert!(!fam6.is_ipv4());

        assert!(fam4.is_compatible_with(&v4_addr));
        assert!(!fam4.is_compatible_with(&v6_addr));
        assert!(fam6.is_compatible_with(&v6_addr));
        assert!(!fam6.is_compatible_with(&v4_addr));

        reset_quic_endpoint();

        let ep4 = get_or_create_quic_endpoint_for_family(QuicAddressFamily::Ipv4)
            .expect("bind IPv4 endpoint");
        assert!(ep4.local_addr().unwrap().is_ipv4());

        // Check if OS supports IPv6 socket creation
        if let Ok(ep6) = get_or_create_quic_endpoint_for_family(QuicAddressFamily::Ipv6) {
            assert!(ep6.local_addr().unwrap().is_ipv6());
            let guard = QUIC_ENDPOINTS.lock();
            assert!(guard.v4.is_some());
            assert!(guard.v6.is_some());
        }

        reset_quic_endpoint();
        let guard = QUIC_ENDPOINTS.lock();
        assert!(guard.v4.is_none());
        assert!(guard.v6.is_none());
        assert!(ACTIVE_QUIC_SESSIONS.lock().is_empty());
    }

    #[test]
    fn test_address_family_switch_does_not_reuse_incompatible_session() {
        reset_quic_endpoint();

        let gen_cfg = get_warp_config_generation();
        let gen_net = get_underlying_network_generation();
        let hash = [88u8; 32];

        let v4_key = MasqueSessionKey {
            sni: "gateway.icloud.com".to_string(),
            endpoint_addr: "162.159.192.1:443".parse().unwrap(),
            address_family: QuicAddressFamily::Ipv4,
            identity_hash: hash,
            config_generation: gen_cfg,
            network_generation: gen_net,
            variant: MasqueConnectVariant::StandardRfc9114L4,
        };

        // v4_key is valid for IPv4 request
        assert!(v4_key.is_valid_for("gateway.icloud.com", gen_cfg, gen_net, &hash, QuicAddressFamily::Ipv4));

        // When candidate family switches to IPv6, v4_key MUST NOT match!
        assert!(
            !v4_key.is_valid_for("gateway.icloud.com", gen_cfg, gen_net, &hash, QuicAddressFamily::Ipv6),
            "IPv4 session key must never be valid for IPv6 candidate requests"
        );

        // Active session lookup for IPv6 returns None when only IPv4 exists
        let v6_lookup = get_active_quic_session(QuicAddressFamily::Ipv6);
        assert!(v6_lookup.is_none(), "Querying active session for IPv6 must yield None");
    }

    #[test]
    fn test_underlying_network_generation_invalidates_cached_sessions() {
        reset_quic_endpoint();

        let gen_cfg = get_warp_config_generation();
        let gen_net = get_underlying_network_generation();
        let hash = [99u8; 32];

        let key = MasqueSessionKey {
            sni: "gateway.icloud.com".to_string(),
            endpoint_addr: "162.159.192.1:443".parse().unwrap(),
            address_family: QuicAddressFamily::Ipv4,
            identity_hash: hash,
            config_generation: gen_cfg,
            network_generation: gen_net,
            variant: MasqueConnectVariant::StandardRfc9114L4,
        };

        assert!(key.is_valid_for("gateway.icloud.com", gen_cfg, gen_net, &hash, QuicAddressFamily::Ipv4));

        // Network reset event (e.g. Wi-Fi to LTE)
        reset_quic_endpoint();

        let new_net_gen = get_underlying_network_generation();
        assert!(new_net_gen > gen_net, "Underlying network generation must increase after network reset");

        // Old key is now invalid because of network generation bump
        assert!(
            !key.is_valid_for("gateway.icloud.com", gen_cfg, new_net_gen, &hash, QuicAddressFamily::Ipv4),
            "Old session must be invalidated when network generation increases"
        );
        assert!(ACTIVE_QUIC_SESSIONS.lock().is_empty(), "All sessions must be purged on network reset");
    }

    #[tokio::test]
    async fn test_quic_ipv6_endpoint_handshake_and_connect() {
        let (mock_endpoint, server_addr, cert_der) = match make_mock_quic_server_ipv6() {
            Ok(res) => res,
            Err(e) => {
                lwarn!("IPv6 loopback not supported in current environment: {}", e);
                return;
            }
        };

        assert!(server_addr.is_ipv6(), "Mock server must be bound to an IPv6 address");

        let (done_tx, done_rx) = tokio::sync::oneshot::channel::<()>();

        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("accept client on IPv6");
            let server_conn = incoming.await.expect("IPv6 handshake");
            let (mut s, mut r) = server_conn.accept_bi().await.expect("accept bi stream on IPv6");

            let mut buf = vec![0u8; 64];
            let n = r.read(&mut buf).await.expect("read from client").expect("data");
            buf.truncate(n);
            assert_eq!(&buf, b"ping_ipv6");

            s.write_all(b"pong_ipv6").await.expect("write reply");
            s.finish().expect("finish stream");

            let _ = done_rx.await;
        });

        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let bind_addr = QuicAddressFamily::Ipv6.default_bind_addr();
        let mut client_endpoint = match quinn::Endpoint::client(bind_addr) {
            Ok(ep) => ep,
            Err(e) => {
                lwarn!("IPv6 client endpoint bind failed: {}", e);
                let _ = done_tx.send(());
                let _ = server_task.await;
                return;
            }
        };
        client_endpoint.set_default_client_config(client_config);

        assert!(
            client_endpoint.local_addr().unwrap().is_ipv6(),
            "Client endpoint must be bound to an IPv6 socket"
        );

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .expect("connect call")
            .await
            .expect("IPv6 QUIC connection established");

        let (mut send_stream, mut recv_stream) = client_conn.open_bi().await.expect("open bi stream");
        send_stream.write_all(b"ping_ipv6").await.expect("write ping");
        send_stream.finish().expect("finish ping");

        let mut reply = vec![0u8; 64];
        let n = recv_stream.read(&mut reply).await.expect("read reply").expect("reply bytes");
        reply.truncate(n);
        assert_eq!(&reply, b"pong_ipv6", "IPv6 QUIC connection must successfully transfer payload");

        let _ = done_tx.send(());
        server_task.await.unwrap();
    }

    #[tokio::test]
    async fn test_h3_frame_reader_truncated_frame_never_injected_as_payload() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let (done_tx, done_rx) = tokio::sync::oneshot::channel();
        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("server accept");
            let server_conn = incoming.await.expect("server conn");
            let (mut s_send, mut s_recv) = server_conn.accept_bi().await.expect("accept_bi");

            let mut sink = [0u8; 16];
            let _ = s_recv.read(&mut sink).await;

            // Send an incomplete HTTP/3 frame header:
            // 0x00 (H3 DATA frame type), followed by 0x40 (start of a 2-byte varint length, missing 2nd byte!)
            let truncated_header = [0x00, 0x40];
            s_send.write_all(&truncated_header).await.expect("write truncated header");
            // Abruptly close the stream at EOF with incomplete frame header
            s_send.finish().expect("finish stream");
            let _ = done_rx.await;
        });

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .unwrap();
        let (mut c_send, c_recv) = client_conn.open_bi().await.unwrap();
        c_send.write_all(b"ping").await.unwrap();

        let mut reader = H3FrameReader::new_with_buffer(c_recv, Vec::new());
        let mut out = [0xFFu8; 128];
        let res = reader.read_data(&mut out).await;

        // Verify that truncated frame header at EOF returns UnexpectedEof error
        assert!(res.is_err(), "Must return error on truncated frame header at EOF");
        let err = res.unwrap_err();
        assert_eq!(err.kind(), std::io::ErrorKind::UnexpectedEof);
        assert!(err.to_string().contains("truncated HTTP/3 frame header at stream EOF"));

        // CRITICAL ACCEPTANCE CRITERIA:
        // Out buffer must NEVER contain the raw unconsumed header bytes [0x00, 0x40]!
        assert_eq!(out[0], 0xFF, "Raw header bytes must NEVER be injected into out payload");
        assert_eq!(out[1], 0xFF, "Raw header bytes must NEVER be injected into out payload");

        let _ = done_tx.send(());
        let _ = server_task.await;
    }

    #[tokio::test]
    async fn test_h3_frame_reader_truncated_payload_never_injected_as_payload() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let (done_tx, done_rx) = tokio::sync::oneshot::channel();
        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("server accept");
            let server_conn = incoming.await.expect("server conn");
            let (mut s_send, mut s_recv) = server_conn.accept_bi().await.expect("accept_bi");

            let mut sink = [0u8; 16];
            let _ = s_recv.read(&mut sink).await;

            // Send H3 DATA frame with declared length 10, but only send 4 bytes of payload!
            let mut frame = Vec::new();
            encode_varint(&mut frame, H3_FRAME_DATA);
            encode_varint(&mut frame, 10);
            frame.extend_from_slice(&[0x11, 0x22, 0x33, 0x44]); // Only 4 bytes sent!
            s_send.write_all(&frame).await.expect("write partial payload");
            // Prematurely close stream
            s_send.finish().expect("finish stream");
            let _ = done_rx.await;
        });

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .unwrap();
        let (mut c_send, c_recv) = client_conn.open_bi().await.unwrap();
        c_send.write_all(b"ping").await.unwrap();

        let mut reader = H3FrameReader::new_with_buffer(c_recv, Vec::new());
        let mut out = [0u8; 128];

        // First read gets the 4 available bytes
        let n1 = reader.read_data(&mut out).await.expect("read 4 bytes");
        assert_eq!(n1, 4);
        assert_eq!(&out[..4], &[0x11, 0x22, 0x33, 0x44]);

        // Next read attempts to read remaining 6 bytes, but stream was closed prematurely!
        let res2 = reader.read_data(&mut out).await;
        assert!(res2.is_err(), "Must error on truncated frame payload at EOF");
        let err = res2.unwrap_err();
        assert_eq!(err.kind(), std::io::ErrorKind::UnexpectedEof);
        assert!(err.to_string().contains("truncated HTTP/3 DATA frame payload at stream EOF"));

        let _ = done_tx.send(());
        let _ = server_task.await;
    }

    #[tokio::test]
    async fn test_h3_frame_reader_oversized_payload_rejected() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let (done_tx, done_rx) = tokio::sync::oneshot::channel();
        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("server accept");
            let server_conn = incoming.await.expect("server conn");
            let (mut s_send, mut s_recv) = server_conn.accept_bi().await.expect("accept_bi");

            let mut sink = [0u8; 16];
            let _ = s_recv.read(&mut sink).await;

            // Send H3 DATA frame with declared length exceeding MAX_H3_DATA_FRAME_PAYLOAD_SIZE (16MB)
            let mut frame = Vec::new();
            encode_varint(&mut frame, H3_FRAME_DATA);
            encode_varint(&mut frame, (MAX_H3_DATA_FRAME_PAYLOAD_SIZE as u64) + 1024);
            s_send.write_all(&frame).await.expect("write oversized frame header");
            let _ = done_rx.await;
        });

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .unwrap();
        let (mut c_send, c_recv) = client_conn.open_bi().await.unwrap();
        c_send.write_all(b"ping").await.unwrap();

        let mut reader = H3FrameReader::new_with_buffer(c_recv, Vec::new());
        let mut out = [0u8; 128];
        let res = reader.read_data(&mut out).await;

        assert!(res.is_err(), "Must reject oversized HTTP/3 frame length");
        let err = res.unwrap_err();
        assert_eq!(err.kind(), std::io::ErrorKind::InvalidData);
        assert!(err.to_string().contains("exceeds maximum limit"));

        let _ = done_tx.send(());
        let _ = server_task.await;
    }

    #[tokio::test]
    async fn test_h3_frame_reader_clean_eof_at_frame_boundary() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let (done_tx, done_rx) = tokio::sync::oneshot::channel();
        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("server accept");
            let server_conn = incoming.await.expect("server conn");
            let (mut s_send, mut s_recv) = server_conn.accept_bi().await.expect("accept_bi");

            let mut sink = [0u8; 16];
            let _ = s_recv.read(&mut sink).await;

            // Send full H3 DATA frame with 3 bytes payload, then clean finish
            let mut frame = Vec::new();
            encode_varint(&mut frame, H3_FRAME_DATA);
            encode_varint(&mut frame, 3);
            frame.extend_from_slice(&[1, 2, 3]);
            s_send.write_all(&frame).await.expect("write frame");
            s_send.finish().expect("clean finish");
            let _ = done_rx.await;
        });

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .unwrap();
        let (mut c_send, c_recv) = client_conn.open_bi().await.unwrap();
        c_send.write_all(b"ping").await.unwrap();

        let mut reader = H3FrameReader::new_with_buffer(c_recv, Vec::new());
        let mut out = [0u8; 128];

        let n = reader.read_data(&mut out).await.expect("read 3 bytes");
        assert_eq!(n, 3);
        assert_eq!(&out[..3], &[1, 2, 3]);

        // Next read at frame boundary with stream closed must return clean EOF Ok(0)
        let eof = reader.read_data(&mut out).await.expect("clean EOF");
        assert_eq!(eof, 0, "Must return Ok(0) at clean stream EOF");

        let _ = done_tx.send(());
        let _ = server_task.await;
    }

    #[test]
    fn test_varint_len_rfc9000_boundaries() {
        assert_eq!(varint_len(0), 1);
        assert_eq!(varint_len(63), 1);
        assert_eq!(varint_len(64), 2);
        assert_eq!(varint_len(16383), 2);
        assert_eq!(varint_len(16384), 4);
        assert_eq!(varint_len(1073741823), 4);
        assert_eq!(varint_len(1073741824), 8);
        assert_eq!(varint_len(4611686018427387903), 8);
    }

    #[test]
    fn test_virtual_tun_device_dynamic_mtu_and_capabilities() {
        let default_dev = VirtualTunDevice::new();
        assert_eq!(default_dev.mtu(), 1380);
        assert_eq!(default_dev.capabilities().max_transmission_unit, 1380);

        let mut custom_dev = VirtualTunDevice::new_with_mtu(1240);
        assert_eq!(custom_dev.mtu(), 1240);
        assert_eq!(custom_dev.capabilities().max_transmission_unit, 1240);

        custom_dev.set_mtu(1180);
        assert_eq!(custom_dev.mtu(), 1180);
        assert_eq!(custom_dev.capabilities().max_transmission_unit, 1180);
    }

    #[test]
    fn test_smoltcp_syn_mss_reflects_virtual_tun_device_mtu() {
        // 1. With MTU 1240, MSS must be 1200 (1240 - 40 bytes IPv4/TCP standard headers)
        let mut dev = VirtualTunDevice::new_with_mtu(1240);
        let mut cfg = SmolConfig::new(HardwareAddress::Ip);
        cfg.random_seed = 12345;
        let mut iface = Interface::new(cfg, &mut dev, SmolInstant::now());
        let local_ip = Ipv4Address::new(172, 16, 0, 2);
        iface.update_ip_addrs(|addrs| {
            let _ = addrs.push(IpCidr::Ipv4(Ipv4Cidr::new(local_ip, 32)));
        });
        iface.routes_mut().add_default_ipv4_route(local_ip).unwrap();

        let rx_buf = tcp::SocketBuffer::new(vec![0; 65535]);
        let tx_buf = tcp::SocketBuffer::new(vec![0; 65535]);
        let mut socket = TcpSocket::new(rx_buf, tx_buf);
        let remote = IpEndpoint::new(IpAddress::Ipv4(Ipv4Address::new(149, 154, 167, 50)), 443);
        let local = IpEndpoint::new(IpAddress::Ipv4(local_ip), 45300);
        socket.connect(iface.context(), remote, local).unwrap();

        let mut sockets = SocketSet::new(vec![]);
        sockets.add(socket);

        iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
        assert_eq!(dev.tx_queue.len(), 1);
        let syn_packet = dev.tx_queue.pop_front().unwrap();

        let ihl = (syn_packet[0] & 0x0F) as usize * 4;
        let tcp_hdr = &syn_packet[ihl..];
        // Parse TCP options looking for MSS (Kind 2, Length 4, Value u16)
        let data_offset = (tcp_hdr[12] >> 4) as usize * 4;
        let options = &tcp_hdr[20..data_offset];
        let mut found_mss = None;
        let mut opt_idx = 0;
        while opt_idx < options.len() {
            match options[opt_idx] {
                0 => break, // End of Option List
                1 => opt_idx += 1, // NOP
                2 => { // MSS Option
                    assert!(opt_idx + 4 <= options.len());
                    assert_eq!(options[opt_idx + 1], 4);
                    let mss = u16::from_be_bytes([options[opt_idx + 2], options[opt_idx + 3]]);
                    found_mss = Some(mss);
                    break;
                }
                _ => {
                    let len = options[opt_idx + 1] as usize;
                    opt_idx += len.max(1);
                }
            }
        }
        assert_eq!(found_mss, Some(1200), "Smoltcp SYN MSS must equal MTU(1240) - 40 = 1200");

        // 2. With MTU 1440, MSS must be 1400
        let mut dev2 = VirtualTunDevice::new_with_mtu(1440);
        let mut cfg2 = SmolConfig::new(HardwareAddress::Ip);
        cfg2.random_seed = 54321;
        let mut iface2 = Interface::new(cfg2, &mut dev2, SmolInstant::now());
        iface2.update_ip_addrs(|addrs| {
            let _ = addrs.push(IpCidr::Ipv4(Ipv4Cidr::new(local_ip, 32)));
        });
        iface2.routes_mut().add_default_ipv4_route(local_ip).unwrap();

        let rx_buf2 = tcp::SocketBuffer::new(vec![0; 65535]);
        let tx_buf2 = tcp::SocketBuffer::new(vec![0; 65535]);
        let mut socket2 = TcpSocket::new(rx_buf2, tx_buf2);
        socket2.connect(iface2.context(), remote, local).unwrap();
        let mut sockets2 = SocketSet::new(vec![]);
        sockets2.add(socket2);

        iface2.poll(SmolInstant::now(), &mut dev2, &mut sockets2);
        assert_eq!(dev2.tx_queue.len(), 1);
        let syn_packet2 = dev2.tx_queue.pop_front().unwrap();
        let ihl2 = (syn_packet2[0] & 0x0F) as usize * 4;
        let tcp_hdr2 = &syn_packet2[ihl2..];
        let data_offset2 = (tcp_hdr2[12] >> 4) as usize * 4;
        let options2 = &tcp_hdr2[20..data_offset2];
        let mut found_mss2 = None;
        let mut opt_idx2 = 0;
        while opt_idx2 < options2.len() {
            match options2[opt_idx2] {
                0 => break,
                1 => opt_idx2 += 1,
                2 => {
                    let mss = u16::from_be_bytes([options2[opt_idx2 + 2], options2[opt_idx2 + 3]]);
                    found_mss2 = Some(mss);
                    break;
                }
                _ => {
                    let len = options2[opt_idx2 + 1] as usize;
                    opt_idx2 += len.max(1);
                }
            }
        }
        assert_eq!(found_mss2, Some(1400), "Smoltcp SYN MSS must equal MTU(1440) - 40 = 1400");
    }

    #[test]
    fn test_build_icmpv4_fragmentation_needed_rfc792_rfc1191_structure() {
        let router_ip = std::net::Ipv4Addr::new(149, 154, 167, 50);
        let client_ip = std::net::Ipv4Addr::new(172, 16, 0, 2);
        let next_hop_mtu = 1200u16;

        // Mock an invoking packet that was too large (e.g. 1380 bytes)
        let mut invoking_pkt = vec![0u8; 1380];
        invoking_pkt[0] = 0x45;
        invoking_pkt[9] = 6; // TCP
        invoking_pkt[12..16].copy_from_slice(&client_ip.octets());
        invoking_pkt[16..20].copy_from_slice(&router_ip.octets());
        invoking_pkt[20..22].copy_from_slice(&45000u16.to_be_bytes()); // src port
        invoking_pkt[22..24].copy_from_slice(&443u16.to_be_bytes()); // dst port

        let icmp_pkt = build_icmpv4_fragmentation_needed(
            router_ip,
            client_ip,
            next_hop_mtu,
            &invoking_pkt,
        );

        // 1. Validate IPv4 Header (RFC 791)
        assert_eq!(icmp_pkt[0], 0x45, "IPv4 Version 4, IHL 5");
        assert_eq!(icmp_pkt[9], 1, "IP Protocol must be ICMP (1)");
        assert_eq!(&icmp_pkt[12..16], &router_ip.octets(), "Source IP is router");
        assert_eq!(&icmp_pkt[16..20], &client_ip.octets(), "Dest IP is client");

        // IPv4 Header Checksum must verify to 0
        let ip_hdr_cksum = calc_internet_checksum(&icmp_pkt[0..20]);
        assert_eq!(ip_hdr_cksum, 0, "IPv4 header checksum must be valid (0)");

        // 2. Validate ICMP Header (RFC 792 / RFC 1191)
        assert_eq!(icmp_pkt[20], 3, "ICMP Type 3 = Destination Unreachable");
        assert_eq!(icmp_pkt[21], 4, "ICMP Code 4 = Fragmentation Needed and DF set");

        // Next-Hop MTU at bytes 26..28 per RFC 1191
        let pmtu = u16::from_be_bytes([icmp_pkt[26], icmp_pkt[27]]);
        assert_eq!(pmtu, next_hop_mtu, "Next-Hop MTU must match requested MTU");

        // ICMP Checksum must verify to 0
        let icmp_cksum = calc_internet_checksum(&icmp_pkt[20..]);
        assert_eq!(icmp_cksum, 0, "ICMP checksum must be valid (0)");

        // Original packet payload starting at offset 28
        assert_eq!(&icmp_pkt[28..48], &invoking_pkt[..20], "Original IP header preserved");
    }

    #[test]
    fn test_build_icmpv6_packet_too_big_rfc4443_rfc8200_structure() {
        let router_ip = "2606:4700:4700::1111".parse::<std::net::Ipv6Addr>().unwrap();
        let client_ip = "2606:4700:110:812c:a554:b442:26d4:1330".parse::<std::net::Ipv6Addr>().unwrap();
        let next_hop_mtu = 1240u32;

        let mut invoking_pkt = vec![0u8; 1400];
        invoking_pkt[0] = 0x60;
        invoking_pkt[6] = 6; // TCP
        invoking_pkt[8..24].copy_from_slice(&client_ip.octets());
        invoking_pkt[24..40].copy_from_slice(&router_ip.octets());

        let icmp_pkt = build_icmpv6_packet_too_big(
            router_ip,
            client_ip,
            next_hop_mtu,
            &invoking_pkt,
        );

        // 1. Validate IPv6 Header (RFC 8200)
        assert_eq!(icmp_pkt[0] >> 4, 6, "IPv6 Version 6");
        assert_eq!(icmp_pkt[6], 58, "Next Header must be ICMPv6 (58)");
        assert_eq!(&icmp_pkt[8..24], &router_ip.octets(), "Source IPv6 is router");
        assert_eq!(&icmp_pkt[24..40], &client_ip.octets(), "Dest IPv6 is client");

        // 2. Validate ICMPv6 Header (RFC 4443)
        assert_eq!(icmp_pkt[40], 2, "ICMPv6 Type 2 = Packet Too Big");
        assert_eq!(icmp_pkt[41], 0, "ICMPv6 Code 0");

        // MTU field at bytes 44..48
        let pmtu = u32::from_be_bytes([icmp_pkt[44], icmp_pkt[45], icmp_pkt[46], icmp_pkt[47]]);
        assert_eq!(pmtu, next_hop_mtu, "ICMPv6 MTU field must match requested MTU");

        // ICMPv6 Checksum over pseudo-header + body must verify to 0
        let icmp_len = (icmp_pkt.len() - 40) as u32;
        let mut pseudo = Vec::new();
        pseudo.extend_from_slice(&router_ip.octets());
        pseudo.extend_from_slice(&client_ip.octets());
        pseudo.extend_from_slice(&icmp_len.to_be_bytes());
        pseudo.extend_from_slice(&[0, 0, 0, 58]);
        pseudo.extend_from_slice(&icmp_pkt[40..]);
        let icmpv6_cksum = calc_internet_checksum(&pseudo);
        assert_eq!(icmpv6_cksum, 0, "ICMPv6 pseudo-header checksum must be valid (0)");

        // Original payload at offset 48
        assert_eq!(&icmp_pkt[48..88], &invoking_pkt[..40], "Original IPv6 header preserved");
    }

    #[test]
    fn test_build_icmp_ptb_for_packet_automatic_family_detection() {
        let target_v4 = std::net::IpAddr::V4(std::net::Ipv4Addr::new(149, 154, 167, 50));
        let client_v4 = Some(std::net::Ipv4Addr::new(172, 16, 0, 2));

        let mut v4_pkt = vec![0u8; 1380];
        v4_pkt[0] = 0x45;
        let icmp_v4 = build_icmp_ptb_for_packet(target_v4, client_v4, None, 1200, &v4_pkt);
        assert!(icmp_v4.is_some());
        let res_v4 = icmp_v4.unwrap();
        assert_eq!(res_v4[0], 0x45, "Must produce IPv4 ICMP packet");
        assert_eq!(res_v4[20], 3, "ICMP Type 3");
        assert_eq!(res_v4[21], 4, "ICMP Code 4");

        let target_v6 = "2606:4700:4700::1111".parse::<std::net::IpAddr>().unwrap();
        let client_v6 = "2606:4700:110:812c:a554:b442:26d4:1330".parse::<std::net::Ipv6Addr>().ok();

        let mut v6_pkt = vec![0u8; 1400];
        v6_pkt[0] = 0x60;
        let icmp_v6 = build_icmp_ptb_for_packet(target_v6, None, client_v6, 1240, &v6_pkt);
        assert!(icmp_v6.is_some());
        let res_v6 = icmp_v6.unwrap();
        assert_eq!(res_v6[0] >> 4, 6, "Must produce IPv6 ICMP packet");
        assert_eq!(res_v6[40], 2, "ICMPv6 Type 2");
        assert_eq!(res_v6[41], 0, "ICMPv6 Code 0");
    }

    #[tokio::test]
    async fn test_flush_smoltcp_tx_too_large_reduces_mtu_and_injects_icmp_ptb() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("server accept");
            let _server_conn = incoming.await.expect("server conn");
            tokio::time::sleep(Duration::from_millis(500)).await;
        });

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .unwrap();

        let (c_send, c_recv) = client_conn.open_bi().await.unwrap();
        let ctrl_stream = Arc::new(tokio::sync::Mutex::new(c_send));
        let dispatcher: DatagramDispatcher = Arc::new(parking_lot::RwLock::new(HashMap::new()));

        let tunnel = MasqueTunnel::from_negotiated_stream(
            client_conn.open_uni().await.unwrap(),
            c_recv,
            Vec::new(),
            client_conn,
            server_addr,
            "149.154.167.50:443".to_string(),
            &MasqueConnectVariant::Rfc9484ConnectIp {
                uri_template: Some("/.well-known/masque/ip/".to_string()),
            },
            dispatcher,
            ctrl_stream,
        );

        let mut dev = VirtualTunDevice::new_with_mtu(1380);
        let mut iface_cfg = SmolConfig::new(HardwareAddress::Ip);
        iface_cfg.random_seed = 42;
        let mut iface = Interface::new(iface_cfg, &mut dev, SmolInstant::now());
        let local_ip = Ipv4Address::new(172, 16, 0, 2);
        iface.update_ip_addrs(|addrs| {
            let _ = addrs.push(IpCidr::Ipv4(Ipv4Cidr::new(local_ip, 32)));
        });
        iface.routes_mut().add_default_ipv4_route(local_ip).unwrap();

        let rx_buf = tcp::SocketBuffer::new(vec![0; 65535]);
        let tx_buf = tcp::SocketBuffer::new(vec![0; 65535]);
        let socket = TcpSocket::new(rx_buf, tx_buf);
        let mut sockets = SocketSet::new(vec![]);
        sockets.add(socket);

        // Enqueue an oversized packet exceeding the effective datagram budget (e.g. 1380 bytes)
        let mut oversized_pkt = vec![0u8; 1380];
        oversized_pkt[0] = 0x45;
        oversized_pkt[9] = 6;
        oversized_pkt[12..16].copy_from_slice(&[172, 16, 0, 2]);
        oversized_pkt[16..20].copy_from_slice(&[149, 154, 167, 50]);
        dev.tx_queue.push_back(oversized_pkt);

        let target_ip = std::net::IpAddr::V4(std::net::Ipv4Addr::new(149, 154, 167, 50));
        let client_v4 = Some(std::net::Ipv4Addr::new(172, 16, 0, 2));

        // Flush must detect the packet exceeds budget, reduce dev MTU, and inject ICMP PTB
        tunnel
            .flush_smoltcp_tx(&mut dev, &mut iface, &mut sockets, target_ip, client_v4, None)
            .await
            .expect("flush must succeed");

        assert_eq!(dev.tx_queue.len(), 0, "Oversized packet must not remain in tx_queue");
        let effective = calculate_effective_mtu(&tunnel.connection, tunnel.quarter_stream_id, false, false);
        assert_eq!(dev.mtu(), effective, "Device MTU must be dynamically lowered to effective datagram budget");

        let _ = server_task.await;
    }

    #[tokio::test]
    async fn test_calculate_effective_mtu_adapts_to_stream_id_and_outer_family() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("server accept");
            let _server_conn = incoming.await.expect("server conn");
            tokio::time::sleep(Duration::from_millis(300)).await;
        });

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .unwrap();

        // 1. quarter_stream_id < 64 (1-byte varint) + context_id 0 (1-byte varint) = 2 bytes overhead
        let mtu_small_id_v4 = calculate_effective_mtu(&client_conn, 0, false, false);
        let mtu_small_id_v6 = calculate_effective_mtu(&client_conn, 0, false, true);
        assert!(mtu_small_id_v4 >= 576 && mtu_small_id_v4 <= 1500);
        assert!(mtu_small_id_v6 >= 1200 && mtu_small_id_v6 <= 1500);

        // 2. quarter_stream_id >= 16384 (4-byte varint) + context_id 0 (1 byte) = 5 bytes overhead
        let mtu_large_id_v4 = calculate_effective_mtu(&client_conn, 20000, false, false);
        // The larger varint overhead must strictly reduce the available IP MTU by 3 bytes
        assert_eq!(mtu_small_id_v4.saturating_sub(mtu_large_id_v4), 3, "4-byte varint has 3 more bytes overhead than 1-byte varint");

        let _ = server_task.await;
    }

    #[tokio::test]
    async fn test_datagram_dispatcher_bounded_queue_and_overflow_tail_drop() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let (server_conn_tx, server_conn_rx) = tokio::sync::oneshot::channel();
        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("server accept");
            let server_conn = incoming.await.expect("server conn");
            let _ = server_conn_tx.send(server_conn);
        });

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .unwrap();
        let server_conn = server_conn_rx.await.expect("server conn received");

        let cancel_token = CancellationToken::new();
        let (dispatcher, disp_task) =
            create_datagram_dispatcher_with_cancel(client_conn.clone(), cancel_token.clone());

        // Register a small bounded channel (capacity 4) for quarter_stream_id = 42
        let (tx, mut rx) = tokio::sync::mpsc::channel(4);
        dispatcher.write().insert(42, tx);

        reset_datagram_dropped_overflow_count();

        // Server sends 10 datagrams to quarter_stream_id = 42
        let payload = vec![0x11, 0x22, 0x33, 0x44];
        for _ in 0..10 {
            let dgram = encode_h3_datagram(42, 0, &payload);
            server_conn.send_datagram(bytes::Bytes::from(dgram)).unwrap();
        }

        // Allow dispatcher task to read from QUIC connection and route to channel
        let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
        while get_datagram_dropped_overflow_count() < 6 && tokio::time::Instant::now() < deadline {
            tokio::time::sleep(Duration::from_millis(20)).await;
        }

        // Bounded queue capacity = 4, sent = 10 -> at least 6 dropped by overflow tail-drop policy
        let dropped = get_datagram_dropped_overflow_count();
        assert!(
            dropped >= 6,
            "Expected at least 6 dropped packets due to bounded channel overflow, got {}",
            dropped
        );

        // Exactly 4 packets should be present in rx
        let mut received = 0;
        while let Ok(pkt) = rx.try_recv() {
            assert_eq!(&pkt[..], &payload[..]);
            received += 1;
        }
        assert_eq!(received, 4, "Receiver must yield exactly 4 packets (capacity)");

        cancel_token.cancel();
        let _ = disp_task.await;
        let _ = server_task.await;
    }

    #[tokio::test]
    async fn test_datagram_dispatcher_prunes_closed_stream_receiver() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let (server_conn_tx, server_conn_rx) = tokio::sync::oneshot::channel();
        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("server accept");
            let server_conn = incoming.await.expect("server conn");
            let _ = server_conn_tx.send(server_conn);
        });

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .unwrap();
        let server_conn = server_conn_rx.await.expect("server conn received");

        let cancel_token = CancellationToken::new();
        let (dispatcher, disp_task) =
            create_datagram_dispatcher_with_cancel(client_conn.clone(), cancel_token.clone());

        // Register a receiver and immediately drop it
        let (tx, rx) = tokio::sync::mpsc::channel(10);
        dispatcher.write().insert(99, tx);
        assert!(dispatcher.read().contains_key(&99));
        drop(rx); // Closed channel

        // Server sends datagram to quarter_stream_id = 99
        let dgram = encode_h3_datagram(99, 0, &[0xAA, 0xBB]);
        server_conn.send_datagram(bytes::Bytes::from(dgram)).unwrap();

        // Allow dispatcher to process datagram, detect closed receiver and prune entry
        let deadline = tokio::time::Instant::now() + Duration::from_secs(2);
        while dispatcher.read().contains_key(&99) && tokio::time::Instant::now() < deadline {
            tokio::time::sleep(Duration::from_millis(20)).await;
        }

        assert!(
            !dispatcher.read().contains_key(&99),
            "Closed stream receiver must be pruned from dispatcher to prevent memory leak"
        );

        cancel_token.cancel();
        let _ = disp_task.await;
        let _ = server_task.await;
    }

    #[tokio::test]
    async fn test_active_quic_session_owns_and_drains_tasks_and_connection() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("server accept");
            let _server_conn = incoming.await.expect("server conn");
            tokio::time::sleep(Duration::from_millis(500)).await;
        });

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .unwrap();

        let cancel_token = CancellationToken::new();
        let c1 = cancel_token.clone();
        let (task1_done_tx, mut task1_done_rx) = tokio::sync::mpsc::channel(1);
        let task1 = tokio::spawn(async move {
            tokio::select! {
                _ = c1.cancelled() => {}
                _ = tokio::time::sleep(Duration::from_secs(30)) => {}
            }
            let _ = task1_done_tx.send(()).await;
        });

        let task2 = tokio::spawn(async move {
            tokio::time::sleep(Duration::from_secs(30)).await;
        });

        let (dispatcher, disp_task) =
            create_datagram_dispatcher_with_cancel(client_conn.clone(), cancel_token.clone());
        let (tx, _rx) = tokio::sync::mpsc::channel(10);
        dispatcher.write().insert(1, tx);

        let ctrl_s = client_conn.open_uni().await.unwrap();
        let session_key = MasqueSessionKey {
            sni: "localhost".to_string(),
            endpoint_addr: server_addr,
            address_family: QuicAddressFamily::Ipv4,
            identity_hash: [1u8; 32],
            config_generation: 1,
            network_generation: 1,
            variant: MasqueConnectVariant::Rfc9484ConnectIp {
                uri_template: None,
            },
        };

        let session = ActiveQuicSession::new(
            session_key,
            client_conn.clone(),
            dispatcher.clone(),
            Arc::new(tokio::sync::Mutex::new(ctrl_s)),
            Http3ServerSettings::default(),
            Arc::new(AtomicBool::new(false)),
            cancel_token.clone(),
            vec![task1, task2, disp_task],
        );

        // Perform shutdown
        session.shutdown(b"test session shutdown").await;

        assert!(session.cancel_token.is_cancelled(), "Cancellation token must be marked cancelled");
        assert!(session.dispatcher.read().is_empty(), "Dispatcher table must be cleared");

        // Task 1 should have exited reactively via cancel_token
        let task1_completed = tokio::time::timeout(Duration::from_millis(300), task1_done_rx.recv())
            .await
            .is_ok();
        assert!(task1_completed, "Task 1 must terminate promptly upon cancellation");

        // Tasks in session_tasks must be drained
        let drained_tasks = session.session_tasks.lock();
        assert!(drained_tasks.is_empty(), "session_tasks must be emptied after shutdown");

        let _ = server_task.await;
    }

    #[tokio::test]
    async fn test_single_flight_session_dial_prevents_duplicate_connections() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let server_task = tokio::spawn(async move {
            let incoming = mock_endpoint.accept().await.expect("server accept");
            let _server_conn = incoming.await.expect("server conn");
            tokio::time::sleep(Duration::from_millis(500)).await;
        });

        clear_active_quic_sessions();
        assert!(get_active_quic_session(QuicAddressFamily::Ipv4).is_none());

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .unwrap();

        let ctrl_s = client_conn.open_uni().await.unwrap();
        let session_key = MasqueSessionKey {
            sni: "localhost".to_string(),
            endpoint_addr: server_addr,
            address_family: QuicAddressFamily::Ipv4,
            identity_hash: [2u8; 32],
            config_generation: get_warp_config_generation(),
            network_generation: get_underlying_network_generation(),
            variant: MasqueConnectVariant::Rfc9484ConnectIp {
                uri_template: None,
            },
        };

        let session = ActiveQuicSession::new(
            session_key,
            client_conn.clone(),
            create_datagram_dispatcher(client_conn.clone()),
            Arc::new(tokio::sync::Mutex::new(ctrl_s)),
            Http3ServerSettings::default(),
            Arc::new(AtomicBool::new(false)),
            CancellationToken::new(),
            vec![],
        );

        // Simulate Dialer A acquiring dial lock and storing session
        {
            let _dial_guard = get_dial_lock_for_family(QuicAddressFamily::Ipv4).lock().await;
            assert!(get_active_quic_session(QuicAddressFamily::Ipv4).is_none());
            replace_active_quic_session(session.clone());
        }

        // Simulate Dialer B acquiring dial lock: double check finds already active session
        let reused_session = {
            let _dial_guard = get_dial_lock_for_family(QuicAddressFamily::Ipv4).lock().await;
            get_active_quic_session(QuicAddressFamily::Ipv4)
        };

        assert!(reused_session.is_some(), "Dialer B must find and reuse cached session under single-flight lock");
        assert_eq!(reused_session.unwrap().key.identity_hash, [2u8; 32]);

        clear_active_quic_sessions();
        assert!(get_active_quic_session(QuicAddressFamily::Ipv4).is_none());

        let _ = server_task.await;
    }

    #[tokio::test]
    async fn test_concurrent_dial_and_stop_lifecycle_drains_tasks_and_endpoints() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let server_task = tokio::spawn(async move {
            loop {
                match mock_endpoint.accept().await {
                    Some(incoming) => {
                        let _ = incoming.await;
                    }
                    None => break,
                }
            }
        });

        reset_quic_endpoint();
        assert!(get_active_quic_session(QuicAddressFamily::Ipv4).is_none());

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .unwrap();

        let cancel_token = CancellationToken::new();
        let (dispatcher, disp_task) =
            create_datagram_dispatcher_with_cancel(client_conn.clone(), cancel_token.clone());

        let ctrl_s = client_conn.open_uni().await.unwrap();
        let session_key = MasqueSessionKey {
            sni: "localhost".to_string(),
            endpoint_addr: server_addr,
            address_family: QuicAddressFamily::Ipv4,
            identity_hash: [3u8; 32],
            config_generation: get_warp_config_generation(),
            network_generation: get_underlying_network_generation(),
            variant: MasqueConnectVariant::Rfc9484ConnectIp { uri_template: None },
        };

        // Long-running session task
        let supervisor_task = tokio::spawn(async move {
            tokio::time::sleep(Duration::from_secs(60)).await;
        });

        let session = ActiveQuicSession::new(
            session_key,
            client_conn.clone(),
            dispatcher.clone(),
            Arc::new(tokio::sync::Mutex::new(ctrl_s)),
            Http3ServerSettings::default(),
            Arc::new(AtomicBool::new(false)),
            cancel_token.clone(),
            vec![disp_task, supervisor_task],
        );

        // 5 concurrent tasks attempt to acquire session using single-flight pattern
        let mut join_handles = Vec::new();
        for _ in 0..5 {
            let session_clone = session.clone();
            let handle = tokio::spawn(async move {
                let _guard = get_dial_lock_for_family(QuicAddressFamily::Ipv4).lock().await;
                if get_active_quic_session(QuicAddressFamily::Ipv4).is_none() {
                    replace_active_quic_session(session_clone);
                    1 // Creator
                } else {
                    0 // Reuser
                }
            });
            join_handles.push(handle);
        }

        let mut creator_count = 0;
        for h in join_handles {
            creator_count += h.await.unwrap();
        }

        assert_eq!(creator_count, 1, "Exactly one task must create the session, other 4 must reuse it");
        assert!(get_active_quic_session(QuicAddressFamily::Ipv4).is_some());

        // Stop/Reset lifecycle
        reset_quic_endpoint();

        // After stop: ACTIVE_QUIC_SESSIONS is empty, cancel_token is cancelled, tasks drained
        assert!(get_active_quic_session(QuicAddressFamily::Ipv4).is_none());
        assert!(session.cancel_token.is_cancelled());
        assert!(session.session_tasks.lock().is_empty());
        assert!(session.dispatcher.read().is_empty());

        server_task.abort();
    }

    #[test]
    fn test_config_empty_ip_fields_clear_previous_values() {
        // Set profile A with both IPv4 and IPv6
        set_warp_config(
            "188.114.96.1:8095",
            "consumer-masque.cloudflareclient.com",
            "token_a",
            "172.16.0.2",
            "2606:4700:110:8874::1",
        );
        let cfg_a = get_warp_config_snapshot();
        assert_eq!(cfg_a.client_ipv4, "172.16.0.2");
        assert_eq!(cfg_a.client_ipv6, "2606:4700:110:8874::1");

        // Set profile B with empty IPv6 and different IPv4
        set_warp_config(
            "162.159.192.1:8095",
            "consumer-masque.cloudflareclient.com",
            "token_b",
            "172.16.0.3",
            "", // Explicitly empty
        );
        let cfg_b = get_warp_config_snapshot();
        assert_eq!(cfg_b.client_ipv4, "172.16.0.3");
        assert!(
            cfg_b.client_ipv6.is_empty(),
            "Empty IPv6 in profile B must clear previous profile A IPv6 value"
        );
        assert_ne!(cfg_b.client_ipv6, "2606:4700:110:8874::1");
    }

    #[test]
    fn test_config_empty_crypto_clears_secrets_and_reverts_to_anonymous() {
        // Set profile with mTLS credentials
        set_warp_crypto("YWJj", "ZGVm", "Z2hp");
        let creds_mtls = WARP_CONFIG.read().resolve_credentials();
        assert_eq!(creds_mtls.scheme, MasqueAuthScheme::Mtls);

        // Explicitly clear crypto
        clear_warp_crypto();
        let cfg_cleared = get_warp_config_snapshot();
        assert!(cfg_cleared.p256_priv_key_base64.is_empty());
        assert!(cfg_cleared.client_cert_base64.is_empty());
        assert!(cfg_cleared.peer_pub_key.is_empty());

        let creds_anon = cfg_cleared.resolve_credentials();
        assert_eq!(
            creds_anon.scheme,
            MasqueAuthScheme::Anonymous,
            "Empty crypto must revert to Anonymous scheme without residual secrets"
        );
        assert!(creds_anon.client_cert_der.is_none());
        assert!(creds_anon.client_key_der.is_none());
    }

    #[tokio::test]
    async fn test_rapid_config_changes_abort_stale_dials_and_converge_to_latest() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let server_task = tokio::spawn(async move {
            loop {
                match mock_endpoint.accept().await {
                    Some(incoming) => {
                        let _ = incoming.await;
                    }
                    None => break,
                }
            }
        });

        // Initialize state
        reset_quic_endpoint();
        let stale_gen = get_warp_config_generation();
        let stale_net_gen = get_underlying_network_generation();

        let client_conn = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .unwrap();

        let ctrl_s = client_conn.open_uni().await.unwrap();
        let cancel_token_stale = CancellationToken::new();
        let (dispatcher, disp_task) =
            create_datagram_dispatcher_with_cancel(client_conn.clone(), cancel_token_stale.clone());

        // Session created with generation stale_gen
        let session_key_stale = MasqueSessionKey {
            sni: "localhost".to_string(),
            endpoint_addr: server_addr,
            address_family: QuicAddressFamily::Ipv4,
            identity_hash: [42u8; 32],
            config_generation: stale_gen,
            network_generation: stale_net_gen,
            variant: MasqueConnectVariant::Rfc9484ConnectIp { uri_template: None },
        };

        let session_stale = ActiveQuicSession::new(
            session_key_stale,
            client_conn.clone(),
            dispatcher.clone(),
            Arc::new(tokio::sync::Mutex::new(ctrl_s)),
            Http3ServerSettings::default(),
            Arc::new(AtomicBool::new(false)),
            cancel_token_stale.clone(),
            vec![disp_task],
        );

        // Rapid configuration transitions occur: A -> B -> C
        set_warp_config("188.114.96.1:8095", "consumer-masque.cloudflareclient.com", "tok1", "172.16.0.2", "");
        set_warp_config("188.114.96.2:500", "consumer-masque.cloudflareclient.com", "tok2", "172.16.0.3", "");
        set_warp_full_config(
            "162.159.192.1:8095",
            "consumer-masque.cloudflareclient.com",
            "tok3",
            "172.16.0.4",
            "",
            "",
            "",
            "",
            "/masque",
        );

        let latest_gen = get_warp_config_generation();
        assert!(latest_gen > stale_gen, "Generations must advance on rapid config changes");

        // Stale session dial finishes after config has advanced
        replace_active_quic_session(session_stale.clone());

        // Stale session must be rejected, aborted, and NOT stored in active sessions
        assert!(
            get_active_quic_session(QuicAddressFamily::Ipv4).is_none(),
            "Stale dial from old generation must not be stored in ACTIVE_QUIC_SESSIONS"
        );
        assert!(
            cancel_token_stale.is_cancelled(),
            "Stale dial session cancellation token must be aborted"
        );

        // Create a new session with fresh connection and latest_gen
        let client_conn_fresh = client_endpoint
            .connect(server_addr, "localhost")
            .unwrap()
            .await
            .unwrap();

        let cancel_token_fresh = CancellationToken::new();
        let (dispatcher_fresh, disp_task_fresh) =
            create_datagram_dispatcher_with_cancel(client_conn_fresh.clone(), cancel_token_fresh.clone());
        let ctrl_s_fresh = client_conn_fresh.open_uni().await.unwrap();

        let session_key_fresh = MasqueSessionKey {
            sni: "localhost".to_string(),
            endpoint_addr: server_addr,
            address_family: QuicAddressFamily::Ipv4,
            identity_hash: [42u8; 32],
            config_generation: latest_gen,
            network_generation: get_underlying_network_generation(),
            variant: MasqueConnectVariant::Rfc9484ConnectIp { uri_template: None },
        };

        let session_fresh = ActiveQuicSession::new(
            session_key_fresh,
            client_conn_fresh.clone(),
            dispatcher_fresh,
            Arc::new(tokio::sync::Mutex::new(ctrl_s_fresh)),
            Http3ServerSettings::default(),
            Arc::new(AtomicBool::new(false)),
            cancel_token_fresh,
            vec![disp_task_fresh],
        );

        replace_active_quic_session(session_fresh);
        let active = get_active_quic_session(QuicAddressFamily::Ipv4);
        assert!(active.is_some(), "Fresh session matching latest generation must be accepted");
        assert_eq!(active.unwrap().key.config_generation, latest_gen);

        reset_quic_endpoint();
        server_task.abort();
    }

    #[test]
    fn test_set_warp_full_config_atomic_snapshot_clears_sticky_and_resets_sessions() {
        // Set sticky endpoint for profile A
        set_sticky_endpoint("188.114.96.1:8095");
        assert_eq!(
            get_sticky_endpoint(),
            Some("188.114.96.1:8095".to_string()),
            "Sticky profile must be active"
        );

        // Apply new profile B via atomic snapshot
        set_warp_full_config(
            "162.159.192.1:8095",
            "consumer-masque.cloudflareclient.com",
            "token_b",
            "172.16.0.10",
            "",
            "",
            "",
            "",
            "/.well-known/masque/ip/",
        );

        // Sticky endpoint must be cleared so profile B does NOT route via profile A's sticky node
        assert!(
            get_sticky_endpoint().is_none(),
            "Applying new configuration snapshot must invalidate old sticky Anycast endpoint"
        );

        // Active sessions must be cleared
        assert!(
            get_active_quic_session(QuicAddressFamily::Ipv4).is_none(),
            "Active QUIC sessions must be reset on profile change"
        );

        let cfg = get_warp_config_snapshot();
        assert_eq!(cfg.endpoint, "162.159.192.1:8095");
        assert_eq!(cfg.client_ipv4, "172.16.0.10");
        assert!(cfg.client_ipv6.is_empty());
    }

    #[test]
    fn test_allocate_connect_step_budget_reserves_fallback_slices() {
        let min_reserve = Duration::from_millis(350);
        let max_budget = Duration::from_millis(1000);

        // Scenario 1: Abundant budget (1500ms), 2 fallbacks left (RFC 9484 attempt)
        // Reserved for future = 2 * 350ms = 700ms.
        // Available for this step = 1500ms - 700ms = 800ms <= max_budget.
        let b1 = allocate_connect_step_budget(Duration::from_millis(1500), 2, min_reserve, max_budget);
        assert_eq!(b1, Duration::from_millis(800));

        // Scenario 2: After RFC 9484 times out, 700ms remains, 1 fallback left (cf-connect-ip attempt)
        // Reserved for future = 1 * 350ms = 350ms.
        // Available for this step = 700ms - 350ms = 350ms.
        let b2 = allocate_connect_step_budget(Duration::from_millis(700), 1, min_reserve, max_budget);
        assert_eq!(b2, Duration::from_millis(350));

        // Scenario 3: After cf-connect-ip times out, 350ms remains, 0 fallbacks left (standard CONNECT attempt)
        // Reserved = 0ms.
        // Available for this step = 350ms.
        let b3 = allocate_connect_step_budget(Duration::from_millis(350), 0, min_reserve, max_budget);
        assert_eq!(b3, Duration::from_millis(350));

        // Guaranteed execution: the last fallback ALWAYS gets non-zero budget!
        assert!(b3 >= min_reserve);

        // Scenario 4: Tight budget (200ms), 2 fallbacks left
        let b_tight = allocate_connect_step_budget(Duration::from_millis(200), 2, min_reserve, max_budget);
        assert!(b_tight >= Duration::from_millis(66));
    }

    #[tokio::test]
    async fn test_read_h3_headers_response_with_cancel_instant_abort() {
        let (mock_endpoint, server_addr, cert_der) =
            make_mock_quic_server().expect("mock server init");
        let client_config = make_mock_quic_client_config(&cert_der).expect("client config");
        let mut client_endpoint =
            quinn::Endpoint::client("127.0.0.1:0".parse().unwrap()).expect("client endpoint");
        client_endpoint.set_default_client_config(client_config);

        let server_task = tokio::spawn(async move {
            if let Some(incoming) = mock_endpoint.accept().await {
                if let Ok(conn) = incoming.await {
                    if let Ok((_s, _r)) = conn.accept_bi().await {
                        tokio::time::sleep(Duration::from_secs(5)).await;
                    }
                }
            }
        });

        let client_conn = client_endpoint.connect(server_addr, "localhost").unwrap().await.unwrap();
        let (_s, mut r) = client_conn.open_bi().await.unwrap();

        let cancel = CancellationToken::new();
        let cancel_clone = cancel.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(30)).await;
            cancel_clone.cancel();
        });

        let start = tokio::time::Instant::now();
        let res = read_h3_headers_response_with_cancel(&mut r, Duration::from_secs(5), Some(&cancel)).await;
        let elapsed = start.elapsed();

        assert!(res.is_err(), "Must return error upon cancellation");
        assert!(elapsed < Duration::from_millis(350), "Cancellation must abort promptly (took {:?})", elapsed);

        reset_quic_endpoint();
        server_task.abort();
    }

    #[tokio::test]
    async fn test_masque_acquire_tunnel_respects_cancellation_promptly() {
        reset_masque_auth_error();
        reset_masque_cooldown();

        // Configure an unroutable blackhole endpoint
        set_warp_full_config(
            "192.0.2.1:8095",
            "blackhole.cloudflareclient.com",
            "dummy_token",
            "172.16.0.2",
            "",
            "",
            "",
            "",
            "/",
        );

        let cancel = CancellationToken::new();
        let cancel_clone = cancel.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(30)).await;
            cancel_clone.cancel();
        });

        let start = tokio::time::Instant::now();
        let tunnel = masque_acquire_tunnel("149.154.167.50:443", &cancel).await;
        let elapsed = start.elapsed();

        assert!(tunnel.is_none(), "Must return None when cancelled");
        assert!(
            elapsed < Duration::from_millis(400),
            "masque_acquire_tunnel must abort promptly on cancel (took {:?})",
            elapsed
        );
    }

    #[tokio::test]
    async fn test_masque_acquire_tunnel_aborts_immediately_on_auth_failure_without_looping_pool() {
        reset_masque_auth_error();
        reset_masque_cooldown();

        let fatal_err = MasqueAuthError::BadCertificate("mTLS client certificate expired".to_string());
        record_masque_auth_failure(fatal_err);

        let cancel = CancellationToken::new();
        let start = tokio::time::Instant::now();
        let tunnel = masque_acquire_tunnel("149.154.167.50:443", &cancel).await;
        let elapsed = start.elapsed();

        assert!(tunnel.is_none(), "Must return None immediately on auth failure");
        assert!(
            elapsed < Duration::from_millis(50),
            "Must abort without trying candidate endpoints (took {:?})",
            elapsed
        );

        reset_masque_auth_error();
    }
}


