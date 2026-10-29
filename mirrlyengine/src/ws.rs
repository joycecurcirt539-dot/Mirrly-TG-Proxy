use crate::config::*;
use crate::crypto::xor_mask_in_place;
use crate::{ldebug, lwarn};
use base64::Engine;
use byteorder::{BigEndian, ByteOrder};
use rand::RngCore;
use rustls::{ClientConfig, RootCertStore};
use rustls_pki_types::ServerName;
use std::collections::HashMap;
use std::future::Future;
use std::net::{IpAddr, SocketAddr};
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::{Duration, Instant};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader, ReadBuf};
use tokio::net::TcpStream;
use tokio_rustls::client::TlsStream;
use tokio_rustls::TlsConnector;
use tokio_util::sync::CancellationToken;

// ---------------------------------------------------------------------------
// WS opcodes
// ---------------------------------------------------------------------------

pub const OP_CONTINUATION: u8 = 0x0;
pub const OP_TEXT: u8 = 0x1;
pub const OP_BINARY: u8 = 0x2;
pub const OP_CLOSE: u8 = 0x8;
pub const OP_PING: u8 = 0x9;
pub const OP_PONG: u8 = 0xA;


// ---------------------------------------------------------------------------
// TLS config: Secure WebPKI Root CA Verification + Browser Fingerprint Emulation
// ---------------------------------------------------------------------------

use once_cell::sync::Lazy;

/// Physical TCP/TLS attempts, dynamically throttled within [2 .. 8] based on radio quality.
static ACTIVE_MOBILE_DIALS: AtomicUsize = AtomicUsize::new(0);
static MOBILE_DIAL_NOTIFY: Lazy<tokio::sync::Notify> = Lazy::new(|| tokio::sync::Notify::new());

pub struct MobileDialPermit;
impl Drop for MobileDialPermit {
    fn drop(&mut self) {
        ACTIVE_MOBILE_DIALS.fetch_sub(1, Ordering::SeqCst);
        MOBILE_DIAL_NOTIFY.notify_waiters();
    }
}

async fn acquire_mobile_dial_permit(cancel: &CancellationToken) -> Result<MobileDialPermit, WsError> {
    let limit = crate::config::adaptive_parallel_socket_limit();
    loop {
        if cancel.is_cancelled() {
            return Err(WsError::Canceled);
        }
        let current = ACTIVE_MOBILE_DIALS.load(Ordering::SeqCst);
        if current < limit {
            if ACTIVE_MOBILE_DIALS.compare_exchange_weak(current, current + 1, Ordering::SeqCst, Ordering::SeqCst).is_ok() {
                return Ok(MobileDialPermit);
            }
            continue;
        }
        tokio::select! {
            _ = cancel.cancelled() => return Err(WsError::Canceled),
            _ = MOBILE_DIAL_NOTIFY.notified() => {},
        }
    }
}

fn order_cipher_suites_for_profile(
    suites: &[rustls::SupportedCipherSuite],
    fp: &str,
) -> Vec<rustls::SupportedCipherSuite> {
    use rustls::CipherSuite;
    let is_firefox = fp.eq_ignore_ascii_case("firefox");
    let is_safari = fp.eq_ignore_ascii_case("safari") || fp.eq_ignore_ascii_case("ios");

    let priority_order: &[CipherSuite] = if is_firefox {
        &[
            CipherSuite::TLS13_AES_128_GCM_SHA256,
            CipherSuite::TLS13_CHACHA20_POLY1305_SHA256,
            CipherSuite::TLS13_AES_256_GCM_SHA384,
            CipherSuite::TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            CipherSuite::TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite::TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite::TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite::TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            CipherSuite::TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        ]
    } else if is_safari {
        &[
            CipherSuite::TLS13_AES_128_GCM_SHA256,
            CipherSuite::TLS13_AES_256_GCM_SHA384,
            CipherSuite::TLS13_CHACHA20_POLY1305_SHA256,
            CipherSuite::TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            CipherSuite::TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            CipherSuite::TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite::TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite::TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite::TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        ]
    } else {
        // Chrome & Default profile
        &[
            CipherSuite::TLS13_AES_128_GCM_SHA256,
            CipherSuite::TLS13_AES_256_GCM_SHA384,
            CipherSuite::TLS13_CHACHA20_POLY1305_SHA256,
            CipherSuite::TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            CipherSuite::TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite::TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            CipherSuite::TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite::TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite::TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        ]
    };

    let mut ordered = Vec::with_capacity(suites.len());
    for &target in priority_order {
        if let Some(&suite) = suites.iter().find(|s| s.suite() == target) {
            if !ordered
                .iter()
                .any(|s: &rustls::SupportedCipherSuite| s.suite() == target)
            {
                ordered.push(suite);
            }
        }
    }
    for &suite in suites {
        if !ordered
            .iter()
            .any(|s: &rustls::SupportedCipherSuite| s.suite() == suite.suite())
        {
            ordered.push(suite);
        }
    }
    ordered
}

pub fn build_tls_config_for_fingerprint(fp: &str) -> Arc<ClientConfig> {
    let mut root_store = RootCertStore::empty();
    root_store.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

    let mut provider = rustls::crypto::ring::default_provider();
    provider.cipher_suites = order_cipher_suites_for_profile(&provider.cipher_suites, fp);

    let mut cfg = ClientConfig::builder_with_provider(Arc::new(provider))
        .with_safe_default_protocol_versions()
        .expect("Safe TLS protocol versions")
        .with_root_certificates(root_store)
        .with_no_client_auth();

    // Emulate modern browser ALPN: HTTP/1.1 for WebSocket RFC 6455
    cfg.alpn_protocols = vec![b"http/1.1".to_vec()];
    cfg.resumption = rustls::client::Resumption::in_memory_sessions(128);

    Arc::new(cfg)
}



pub static TLS_CONFIG_CHROME: Lazy<Arc<ClientConfig>> =
    Lazy::new(|| build_tls_config_for_fingerprint("chrome"));
pub static TLS_CONFIG_FIREFOX: Lazy<Arc<ClientConfig>> =
    Lazy::new(|| build_tls_config_for_fingerprint("firefox"));
pub static TLS_CONFIG_SAFARI: Lazy<Arc<ClientConfig>> =
    Lazy::new(|| build_tls_config_for_fingerprint("safari"));

// SOCKS5 relay transport uses ordinary TLS, independently of camouflage settings.
static TLS_CONFIG_RELAY: Lazy<Arc<ClientConfig>> = Lazy::new(|| {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let mut config = ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .expect("Safe TLS protocol versions")
    .with_root_certificates(roots)
    .with_no_client_auth();
    config.alpn_protocols = vec![b"http/1.1".to_vec()];
    config.resumption = rustls::client::Resumption::in_memory_sessions(128);
    Arc::new(config)
});

fn is_tcp_relay_path(path: &str) -> bool {
    let Some((route, query)) = path.split_once('?') else { return false; };
    matches!(route, "/tcp" | "/tcp-v2")
        && url::form_urlencoded::parse(query.as_bytes())
            .any(|(key, value)| key == "target" && !value.is_empty())
}

fn tls_config_for_ws_path(path: &str, fingerprint: &str) -> Arc<ClientConfig> {
    if is_tcp_relay_path(path) {
        TLS_CONFIG_RELAY.clone()
    } else {
        get_effective_tls_config(fingerprint)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FingerprintCapability {
    /// Native cipher suite ordering and TLS record profile (chrome, firefox, safari, ios, randomized)
    Supported,
    /// Known browser alias mapped to Chrome cipher suites
    Mapped,
    /// Unrecognized string safely falling back to Chrome profile without weakening security
    UnsupportedFallback,
}

pub fn classify_fingerprint(fp: &str) -> (&'static str, FingerprintCapability) {
    let lower = fp.trim().to_ascii_lowercase();
    match lower.as_str() {
        "firefox" => ("firefox", FingerprintCapability::Supported),
        "safari" => ("safari", FingerprintCapability::Supported),
        "ios" => ("ios", FingerprintCapability::Supported),
        "randomized" => ("randomized", FingerprintCapability::Supported),
        "chrome" | "" => ("chrome", FingerprintCapability::Supported),
        "edge" | "360" | "qq" | "android" => ("chrome", FingerprintCapability::Mapped),
        _ => ("chrome", FingerprintCapability::UnsupportedFallback),
    }
}

pub fn get_tls_config_for_fingerprint(fp: &str) -> Arc<ClientConfig> {
    let lower = fp.trim().to_ascii_lowercase();
    match lower.as_str() {
        "firefox" => TLS_CONFIG_FIREFOX.clone(),
        "safari" | "ios" => TLS_CONFIG_SAFARI.clone(),
        "randomized" => {
            let mut rng = rand::thread_rng();
            match rand::RngCore::next_u32(&mut rng) % 3 {
                0 => TLS_CONFIG_CHROME.clone(),
                1 => TLS_CONFIG_FIREFOX.clone(),
                _ => TLS_CONFIG_SAFARI.clone(),
            }
        }
        "chrome" | "" => TLS_CONFIG_CHROME.clone(),
        "edge" | "360" | "qq" | "android" => {
            crate::ldebug!(
                "TLS fingerprint '{}' mapped to rustls Chrome cipher suites",
                lower
            );
            TLS_CONFIG_CHROME.clone()
        }
        unsupported => {
            crate::lwarn!(
                "Unsupported TLS fingerprint '{}'; falling back to Chrome profile without altering security (uTLS Parrot extension simulation not supported)",
                unsupported
            );
            TLS_CONFIG_CHROME.clone()
        }
    }
}

pub static TLS_CONFIG: Lazy<Arc<ClientConfig>> = Lazy::new(|| TLS_CONFIG_CHROME.clone());

pub static TLS_RECORD_PADDING_ENABLED: AtomicBool = AtomicBool::new(true);

pub fn is_tls_record_padding_enabled() -> bool {
    TLS_RECORD_PADDING_ENABLED.load(Ordering::Relaxed)
}

pub fn set_tls_record_padding_enabled(enabled: bool) {
    TLS_RECORD_PADDING_ENABLED.store(enabled, Ordering::Relaxed);
    crate::linfo!("TLS record padding & packet size randomization enabled: {}", enabled);
}

pub static TLS_CLIENT_HELLO_SPLIT_ENABLED: AtomicBool = AtomicBool::new(true);

pub fn is_tls_client_hello_split_enabled() -> bool {
    TLS_CLIENT_HELLO_SPLIT_ENABLED.load(Ordering::Relaxed)
}

pub fn set_tls_client_hello_split_enabled(enabled: bool) {
    TLS_CLIENT_HELLO_SPLIT_ENABLED.store(enabled, Ordering::Relaxed);
    crate::linfo!("TLS ClientHello TCP segmentation (split) enabled: {}", enabled);
}

pub fn get_effective_tls_config(fp: &str) -> Arc<ClientConfig> {
    get_tls_config_for_fingerprint(fp)
}

// ---------------------------------------------------------------------------
// WsHandshakeError
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct WsHandshakeError {
    pub status_code: i32,
    pub status_line: String,
    pub headers: HashMap<String, String>,
    pub location: String,
}

impl WsHandshakeError {
    pub fn is_redirect(&self) -> bool {
        matches!(self.status_code, 301 | 302 | 303 | 307 | 308)
    }
}

#[derive(Debug)]
pub enum WsError {
    Io(std::io::Error),
    Handshake(WsHandshakeError),
    Timeout,
    Canceled,
    Other(String),
}

impl WsError {
    pub fn compact(&self) -> String {
        match self {
            WsError::Canceled => "canceled".to_string(),
            WsError::Timeout => "timeout".to_string(),
            WsError::Handshake(h) => format!("http {}", h.status_code),
            WsError::Io(e) => {
                if e.kind() == std::io::ErrorKind::TimedOut
                    || e.kind() == std::io::ErrorKind::WouldBlock
                {
                    "timeout".to_string()
                } else {
                    e.to_string()
                }
            }
            WsError::Other(s) => s.clone(),
        }
    }
    pub fn handshake_status(&self) -> Option<i32> {
        if let WsError::Handshake(h) = self {
            Some(h.status_code)
        } else {
            None
        }
    }
    pub fn handshake(&self) -> Option<&WsHandshakeError> {
        if let WsError::Handshake(h) = self {
            Some(h)
        } else {
            None
        }
    }
}

pub fn is_http_status_error(err: &WsError, code: i32) -> bool {
    err.handshake_status() == Some(code)
}

pub fn is_cooldown_error(err: &WsError) -> bool {
    if let Some(code) = err.handshake_status() {
        matches!(
            code,
            429 | 500 | 502 | 503 | 504 | 520 | 521 | 522 | 523 | 524
        )
    } else {
        false
    }
}

impl From<std::io::Error> for WsError {
    fn from(e: std::io::Error) -> Self {
        WsError::Io(e)
    }
}

// ---------------------------------------------------------------------------
// TlsClientHelloSplitStream: TCP Segmentation of TLS ClientHello (Anti-DPI / TSPU)
// ---------------------------------------------------------------------------

/// Finds the byte offset inside a TLS ClientHello where the SNI hostname should be split to bypass DPI.
/// If an SNI hostname is found, returns an offset inside the hostname (cutting the domain name in half).
/// If no SNI is found, returns None.
pub fn find_sni_split_pos(buf: &[u8]) -> Option<usize> {
    if buf.len() < 9 || buf[0] != 0x16 {
        return None;
    }
    if buf[5] != 0x01 {
        // Not a ClientHello handshake
        return None;
    }
    // Record header: 5 bytes
    // Handshake header: 4 bytes (Type 1, Length 3)
    // ClientVersion: 2 bytes
    // Random: 32 bytes
    let mut offset = 5 + 4 + 2 + 32;
    if offset >= buf.len() {
        return None;
    }

    // Session ID (1 byte length + data)
    let session_id_len = buf[offset] as usize;
    offset += 1 + session_id_len;

    // Cipher Suites (2 bytes length + data)
    if offset + 2 > buf.len() {
        return None;
    }
    let cs_len = u16::from_be_bytes([buf[offset], buf[offset + 1]]) as usize;
    offset += 2 + cs_len;

    // Compression Methods (1 byte length + data)
    if offset >= buf.len() {
        return None;
    }
    let comp_len = buf[offset] as usize;
    offset += 1 + comp_len;

    // Extensions Length (2 bytes)
    if offset + 2 > buf.len() {
        return None;
    }
    let ext_total_len = u16::from_be_bytes([buf[offset], buf[offset + 1]]) as usize;
    offset += 2;

    let ext_end = (offset + ext_total_len).min(buf.len());
    while offset + 4 <= ext_end {
        let ext_type = u16::from_be_bytes([buf[offset], buf[offset + 1]]);
        let ext_len = u16::from_be_bytes([buf[offset + 2], buf[offset + 3]]) as usize;
        offset += 4;

        if ext_type == 0 {
            // Extension 0: server_name (SNI)
            // ServerNameList length: 2 bytes
            // ServerNameType: 1 byte (0 = host_name)
            // HostName length: 2 bytes
            if offset + 5 <= ext_end {
                let name_type = buf[offset + 2];
                if name_type == 0 {
                    let host_len = u16::from_be_bytes([buf[offset + 3], buf[offset + 4]]) as usize;
                    let host_start = offset + 5;
                    if host_start + host_len <= ext_end && host_len > 4 {
                        let host_bytes = &buf[host_start..host_start + host_len];
                        if let Ok(host_str) = std::str::from_utf8(host_bytes) {
                            if let Some(idx) = host_str.find("workers.dev") {
                                // Split right in the middle of "workers.dev": after "work" ("work" | "ers.dev")
                                // Neither packet contains the full "workers.dev" keyword.
                                let split_inside = host_start + idx + 4;
                                return Some(split_inside);
                            }
                        }
                        // Default for other hosts: split in the middle of the hostname
                        let split_inside = host_start + host_len / 2;
                        return Some(split_inside);
                    }
                }
            }
        }
        offset += ext_len;
    }
    None
}

#[derive(Debug)]
enum SplitStreamState {
    Init,
    /// Flushing a segment before starting the delay.
    Flushing,
    /// Delaying after sending a segment before sending the next one.
    Delaying {
        sleep: Pin<Box<tokio::time::Sleep>>,
    },
    Done,
}

pub struct TlsClientHelloSplitStream<S> {
    inner: S,
    enabled: bool,
    splits: Vec<usize>,
    global_written: usize,
    delay: Duration,
    state: SplitStreamState,
}

impl<S> TlsClientHelloSplitStream<S> {
    pub fn new(inner: S, split_pos: usize, delay: Duration) -> Self {
        let enabled = split_pos > 0;
        let effective_delay = if enabled {
            delay.max(Duration::from_millis(25))
        } else {
            Duration::ZERO
        };
        Self {
            inner,
            enabled,
            splits: Vec::new(),
            global_written: 0,
            delay: effective_delay,
            state: SplitStreamState::Init,
        }
    }

    pub fn get_ref(&self) -> &S {
        &self.inner
    }

    pub fn get_mut(&mut self) -> &mut S {
        &mut self.inner
    }
}

impl<S: AsyncRead + Unpin> AsyncRead for TlsClientHelloSplitStream<S> {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.inner).poll_read(cx, buf)
    }
}

impl<S: AsyncWrite + Unpin> AsyncWrite for TlsClientHelloSplitStream<S> {
    fn poll_write(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<std::io::Result<usize>> {
        loop {
            let this = self.as_mut().get_mut();
            match &mut this.state {
                SplitStreamState::Init => {
                    if !this.enabled || buf.len() < 5 || buf.first() != Some(&0x16) {
                        this.state = SplitStreamState::Done;
                        return Pin::new(&mut this.inner).poll_write(cx, buf);
                    }

                    if this.splits.is_empty() {
                        let mut splits = Vec::with_capacity(2);
                        // Split 1: byte 2 (splits TLS Record header 0x16 0x03)
                        splits.push(2);

                        // Split 2: inside SNI hostname if present
                        if let Some(sni_split) = find_sni_split_pos(buf) {
                            if sni_split > 2 && sni_split < buf.len() {
                                splits.push(sni_split);
                            }
                        }

                        splits.sort_unstable();
                        splits.dedup();

                        crate::ldebug!(
                            "TLS ClientHello anti-DPI split applied: total_len={}, splits={:?}, delay={:?}",
                            buf.len(),
                            splits,
                            this.delay
                        );

                        this.splits = splits;
                        this.global_written = 0;
                    }
                }
                SplitStreamState::Flushing => {
                    match Pin::new(&mut this.inner).poll_flush(cx) {
                        Poll::Ready(Ok(())) => {
                            if this.delay > Duration::ZERO {
                                this.state = SplitStreamState::Delaying {
                                    sleep: Box::pin(tokio::time::sleep(this.delay)),
                                };
                                continue;
                            } else {
                                this.state = SplitStreamState::Init;
                                continue;
                            }
                        }
                        Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                        Poll::Pending => return Poll::Pending,
                    }
                }
                SplitStreamState::Delaying { sleep } => {
                    match sleep.as_mut().poll(cx) {
                        Poll::Ready(()) => {
                            this.state = SplitStreamState::Init;
                            continue;
                        }
                        Poll::Pending => return Poll::Pending,
                    }
                }
                SplitStreamState::Done => {
                    return Pin::new(&mut this.inner).poll_write(cx, buf);
                }
            }

            let this = self.as_mut().get_mut();
            if let Some(&next_split) = this.splits.iter().find(|&&s| s > this.global_written) {
                let bytes_needed = next_split - this.global_written;
                let chunk_len = bytes_needed.min(buf.len());
                let chunk = &buf[..chunk_len];

                let n = match Pin::new(&mut this.inner).poll_write(cx, chunk) {
                    Poll::Ready(Ok(n)) => n,
                    Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                    Poll::Pending => return Poll::Pending,
                };

                if n == 0 {
                    return Poll::Ready(Ok(0));
                }

                this.global_written += n;
                if this.global_written >= next_split {
                    this.state = SplitStreamState::Flushing;
                }
                return Poll::Ready(Ok(n));
            } else {
                let n = match Pin::new(&mut this.inner).poll_write(cx, buf) {
                    Poll::Ready(Ok(n)) => n,
                    Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                    Poll::Pending => return Poll::Pending,
                };
                if n == 0 {
                    return Poll::Ready(Ok(0));
                }
                this.global_written += n;
                this.state = SplitStreamState::Done;
                return Poll::Ready(Ok(n));
            }
        }
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.inner).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Pin::new(&mut self.inner).poll_shutdown(cx)
    }
}

// ---------------------------------------------------------------------------
// WsStream: Unified transport wrapper for TLS and plain TCP WebSocket
// ---------------------------------------------------------------------------

pub enum WsStream {
    Tls(TlsStream<TlsClientHelloSplitStream<TcpStream>>),
    Plain(TcpStream),
}

impl AsyncRead for WsStream {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        match self.get_mut() {
            WsStream::Tls(s) => Pin::new(s).poll_read(cx, buf),
            WsStream::Plain(s) => Pin::new(s).poll_read(cx, buf),
        }
    }
}

impl AsyncWrite for WsStream {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<std::io::Result<usize>> {
        match self.get_mut() {
            WsStream::Tls(s) => Pin::new(s).poll_write(cx, buf),
            WsStream::Plain(s) => Pin::new(s).poll_write(cx, buf),
        }
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        match self.get_mut() {
            WsStream::Tls(s) => Pin::new(s).poll_flush(cx),
            WsStream::Plain(s) => Pin::new(s).poll_flush(cx),
        }
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        match self.get_mut() {
            WsStream::Tls(s) => Pin::new(s).poll_shutdown(cx),
            WsStream::Plain(s) => Pin::new(s).poll_shutdown(cx),
        }
    }
}

// ---------------------------------------------------------------------------
// RawWebSocket
// ---------------------------------------------------------------------------

pub struct RawWebSocket {
    reader: tokio::sync::Mutex<BufReader<tokio::io::ReadHalf<WsStream>>>,
    writer: tokio::sync::Mutex<tokio::io::WriteHalf<WsStream>>,
    pub closed: AtomicBool,
    buffered_payload: tokio::sync::Mutex<Option<Vec<u8>>>,
    pub early_data_sent: bool,
    pub flow_controlled: bool,
    heartbeat: parking_lot::Mutex<HeartbeatState>,
    heartbeat_nonce: AtomicU64,
    recovery_scope: String,
    pub peer_ip: parking_lot::RwLock<Option<std::net::IpAddr>>,
    pub colo: String,
    pub asn: String,
}

#[derive(Debug)]
struct HeartbeatState {
    last_frame_activity: Instant,
    last_send_activity: Instant,
    awaiting_pong: Option<(u64, Instant)>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HeartbeatPolicy {
    pub idle_before_ping: Duration,
    pub pong_deadline: Duration,
}

impl HeartbeatPolicy {
    pub fn effective() -> Self {
        let profile = crate::network_profile::get_profile();
        if profile.websocket_keep_alive_seconds > 0 {
            let idle = Duration::from_secs(profile.websocket_keep_alive_seconds.clamp(15, 60));
            return Self {
                idle_before_ping: idle,
                pong_deadline: Duration::from_secs((idle.as_secs() / 2).clamp(10, 25)),
            };
        }
        match (profile.power_save_mode, profile.screen_on, profile.cellular) {
            (true, _, _) => Self {
                idle_before_ping: Duration::from_secs(60),
                pong_deadline: Duration::from_secs(20),
            },
            (false, false, _) => Self {
                idle_before_ping: Duration::from_secs(45),
                pong_deadline: Duration::from_secs(20),
            },
            (false, true, true) => Self {
                idle_before_ping: Duration::from_secs(25),
                pong_deadline: Duration::from_secs(25),
            },
            (false, true, false) => Self {
                idle_before_ping: Duration::from_secs(45),
                pong_deadline: Duration::from_secs(20),
            },
        }
    }
}

impl RawWebSocket {
    pub fn colo(&self) -> &str {
        &self.colo
    }

    pub fn set_peer_ip(&self, ip: std::net::IpAddr) {
        *self.peer_ip.write() = Some(ip);
    }

    pub fn peer_ip(&self) -> Option<std::net::IpAddr> {
        *self.peer_ip.read()
    }

    pub fn recovery_scope(&self) -> &str {
        &self.recovery_scope
    }

    fn record_frame_activity(&self) {
        let mut state = self.heartbeat.lock();
        state.last_frame_activity = Instant::now();
        // Any received data/control frame proves the return path is alive.
        state.awaiting_pong = None;
    }

    fn record_pong(&self, _payload: &[u8]) {
        let mut state = self.heartbeat.lock();
        state.last_frame_activity = Instant::now();
        // RFC 6455 Section 5.5.3:
        // Any received Pong frame confirms the bidirectional path is alive.
        // Intermediaries and CDNs (e.g. Cloudflare) often return empty pongs (len == 0).
        state.awaiting_pong = None;
    }

    pub fn record_send_activity(&self) {
        let mut state = self.heartbeat.lock();
        state.last_send_activity = Instant::now();
    }

    pub fn heartbeat_is_waiting_for_pong(&self) -> bool {
        self.heartbeat.lock().awaiting_pong.is_some()
    }

    pub fn heartbeat_idle_for(&self) -> Duration {
        self.heartbeat.lock().last_frame_activity.elapsed()
    }

    /// Runs the sole heartbeat policy for this physical WebSocket connection.
    /// The receive loop observes PONG frames; this task only schedules PING and
    /// enforces its deadline, so heartbeat always uses the connection's 5-tuple.
    pub async fn run_heartbeat(&self, cancel: CancellationToken) -> Result<(), WsError> {
        self.run_heartbeat_loop(cancel, None, WS_HEARTBEAT_CHECK_INTERVAL, true)
            .await
    }

    async fn run_heartbeat_loop(
        &self,
        cancel: CancellationToken,
        policy_override: Option<HeartbeatPolicy>,
        check_interval: Duration,
        jitter: bool,
    ) -> Result<(), WsError> {
        loop {
            let delay = if jitter {
                let base_ms = check_interval.as_millis().max(1) as u64;
                let spread_ms = (base_ms / 5).max(1);
                let offset = rand::random::<u64>() % (spread_ms * 2 + 1);
                Duration::from_millis(base_ms + offset - spread_ms)
            } else {
                check_interval
            };
            tokio::select! {
                _ = cancel.cancelled() => return Ok(()),
                _ = tokio::time::sleep(delay) => {}
            }

            if self.is_closed() {
                return Err(WsError::Other("WebSocket closed".to_string()));
            }

            let policy = policy_override.unwrap_or_else(HeartbeatPolicy::effective);
            let now = Instant::now();
            let action = {
                let state = self.heartbeat.lock();
                match state.awaiting_pong {
                    Some((_, sent_at)) if now.duration_since(sent_at) >= policy.pong_deadline
                        && now.duration_since(state.last_frame_activity) >= policy.pong_deadline => 2,
                    Some(_) => 0,
                    None if now.duration_since(state.last_frame_activity)
                        >= policy.idle_before_ping =>
                    {
                        1
                    }
                    None => 0,
                }
            };

            match action {
                1 => {
                    tokio::select! {
                        _ = cancel.cancelled() => return Ok(()),
                        result = self.send_heartbeat_ping() => result?,
                    }
                }
                2 => {
                    crate::recovery::record(
                        &self.recovery_scope,
                        crate::recovery::RecoveryCause::EstablishedStall,
                    );
                    self.closed.store(true, Ordering::Relaxed);
                    return Err(WsError::Other(
                        "WebSocket heartbeat pong deadline exceeded".to_string(),
                    ));
                }
                _ => {}
            }
        }
    }

    #[cfg(any(test, feature = "heartbeat-test-utils"))]
    #[doc(hidden)]
    pub fn from_plain_stream_for_heartbeat_test(stream: TcpStream) -> Self {
        let (read_half, write_half) = tokio::io::split(WsStream::Plain(stream));
        Self {
            reader: tokio::sync::Mutex::new(BufReader::with_capacity(128 * 1024, read_half)),
            writer: tokio::sync::Mutex::new(write_half),
            closed: AtomicBool::new(false),
            buffered_payload: tokio::sync::Mutex::new(None),
            early_data_sent: false,
            flow_controlled: false,
            heartbeat: parking_lot::Mutex::new(HeartbeatState {
                last_frame_activity: Instant::now(),
                last_send_activity: Instant::now(),
                awaiting_pong: None,
            }),
            heartbeat_nonce: AtomicU64::new(0),
            recovery_scope: "heartbeat-test".to_string(),
            peer_ip: parking_lot::RwLock::new(None),
            colo: String::new(),
            asn: String::new(),
        }
    }

    #[cfg(any(test, feature = "heartbeat-test-utils"))]
    #[doc(hidden)]
    pub async fn run_heartbeat_for_test(
        &self,
        cancel: CancellationToken,
        idle_before_ping: Duration,
        pong_deadline: Duration,
        check_interval: Duration,
    ) -> Result<(), WsError> {
        self.run_heartbeat_loop(
            cancel,
            Some(HeartbeatPolicy {
                idle_before_ping,
                pong_deadline,
            }),
            check_interval,
            false,
        )
        .await
    }

    pub fn is_closed(&self) -> bool {
        self.closed.load(Ordering::Relaxed)
    }

    pub fn is_early_data_sent(&self) -> bool {
        self.early_data_sent
    }

    pub async fn inject_initial_payload(&self, data: Vec<u8>) {
        if !data.is_empty() {
            *self.buffered_payload.lock().await = Some(data);
            self.record_frame_activity();
        }
    }

    pub async fn send_text(&self, text: &str) -> Result<(), WsError> {
        if self.is_closed() {
            return Err(WsError::Other("WebSocket closed".to_string()));
        }
        self.record_send_activity();
        self.write_frame(&build_frame(OP_TEXT, text.as_bytes(), true), WS_WRITE_TIMEOUT).await
    }

    pub async fn send(&self, data: &[u8]) -> Result<(), WsError> {
        if self.is_closed() {
            return Err(WsError::Other("WebSocket closed".to_string()));
        }
        self.record_send_activity();
        if data.len() <= MAX_FRAME_PAYLOAD as usize {
            let frame = build_frame(OP_BINARY, data, true);
            self.write_frame(&frame, WS_WRITE_TIMEOUT).await?;
            Ok(())
        } else {
            // RFC 6455 Fragmented message if payload exceeds max frame limit
            let chunks: Vec<&[u8]> = data.chunks(MAX_FRAME_PAYLOAD as usize).collect();
            let total_chunks = chunks.len();
            let mut writer = self.writer.lock().await;
            for (i, chunk) in chunks.into_iter().enumerate() {
                let opcode = if i == 0 { OP_BINARY } else { OP_CONTINUATION };
                let fin = i == total_chunks - 1;
                let frame = build_frame_ext(opcode, chunk, true, fin);
                match tokio::time::timeout(WS_WRITE_TIMEOUT, writer.write_all(&frame)).await {
                    Ok(Ok(())) => {}
                    Ok(Err(e)) => {
                        self.closed.store(true, Ordering::Relaxed);
                        return Err(WsError::Io(e));
                    }
                    Err(_) => {
                        self.closed.store(true, Ordering::Relaxed);
                        return Err(WsError::Timeout);
                    }
                }
            }
            if let Err(e) = writer.flush().await {
                self.closed.store(true, Ordering::Relaxed);
                return Err(WsError::Io(e));
            }
            Ok(())
        }
    }

    pub async fn send_batch(&self, parts: &[Vec<u8>]) -> Result<(), WsError> {
        if self.is_closed() {
            return Err(WsError::Other("WebSocket closed".to_string()));
        }
        self.record_send_activity();
        let mut writer = self.writer.lock().await;
        for part in parts {
            if part.len() <= MAX_FRAME_PAYLOAD as usize {
                let frame = build_frame(OP_BINARY, part, true);
                match tokio::time::timeout(WS_WRITE_TIMEOUT, writer.write_all(&frame)).await {
                    Ok(Ok(())) => {}
                    Ok(Err(e)) => {
                        self.closed.store(true, Ordering::Relaxed);
                        return Err(WsError::Io(e));
                    }
                    Err(_) => {
                        self.closed.store(true, Ordering::Relaxed);
                        return Err(WsError::Timeout);
                    }
                }
            } else {
                let chunks: Vec<&[u8]> = part.chunks(MAX_FRAME_PAYLOAD as usize).collect();
                let total_chunks = chunks.len();
                for (i, chunk) in chunks.into_iter().enumerate() {
                    let opcode = if i == 0 { OP_BINARY } else { OP_CONTINUATION };
                    let fin = i == total_chunks - 1;
                    let frame = build_frame_ext(opcode, chunk, true, fin);
                    match tokio::time::timeout(WS_WRITE_TIMEOUT, writer.write_all(&frame)).await {
                        Ok(Ok(())) => {}
                        Ok(Err(e)) => {
                            self.closed.store(true, Ordering::Relaxed);
                            return Err(WsError::Io(e));
                        }
                        Err(_) => {
                            self.closed.store(true, Ordering::Relaxed);
                            return Err(WsError::Timeout);
                        }
                    }
                }
            }
        }
        if let Err(e) = writer.flush().await {
            self.closed.store(true, Ordering::Relaxed);
            return Err(WsError::Io(e));
        }
        Ok(())
    }

    async fn send_heartbeat_ping(&self) -> Result<(), WsError> {
        if self.is_closed() {
            return Err(WsError::Other("WebSocket closed".to_string()));
        }
        // Queue fairly behind the current frame. Lock contention is neither a
        // failed heartbeat nor evidence that the peer is receiving our traffic.
        let mut writer = self.writer.lock().await;
        if self.is_closed() {
            return Err(WsError::Other("WebSocket closed".to_string()));
        }
        let nonce = self
            .heartbeat_nonce
            .fetch_add(1, Ordering::Relaxed)
            .wrapping_add(1);
        {
            let mut state = self.heartbeat.lock();
            if state.awaiting_pong.is_some() {
                return Ok(());
            }
            state.awaiting_pong = Some((nonce, Instant::now()));
        }
        let frame = build_frame(OP_PING, &nonce.to_be_bytes(), true);
        if let Err(error) = self.write_frame_locked(&mut writer, &frame, WS_CONTROL_TIMEOUT).await {
            self.heartbeat.lock().awaiting_pong = None;
            return Err(error);
        }
        let mut state = self.heartbeat.lock();
        if state.awaiting_pong.map(|pending| pending.0) == Some(nonce) {
            state.awaiting_pong = Some((nonce, Instant::now()));
        }
        Ok(())
    }

    async fn write_frame(&self, frame: &[u8], timeout: Duration) -> Result<(), WsError> {
        let mut writer = self.writer.lock().await;
        self.write_frame_locked(&mut writer, frame, timeout).await
    }

    async fn write_frame_locked(
        &self,
        writer: &mut tokio::io::WriteHalf<WsStream>,
        frame: &[u8],
        timeout: Duration,
    ) -> Result<(), WsError> {
        match tokio::time::timeout(timeout, writer.write_all(frame)).await {
            Ok(Ok(())) => {}
            Ok(Err(e)) => {
                self.closed.store(true, Ordering::Relaxed);
                return Err(WsError::Io(e));
            }
            Err(_) => {
                self.closed.store(true, Ordering::Relaxed);
                return Err(WsError::Timeout);
            }
        }
        match tokio::time::timeout(timeout, writer.flush()).await {
            Ok(Ok(())) => {}
            Ok(Err(e)) => {
                self.closed.store(true, Ordering::Relaxed);
                return Err(WsError::Io(e));
            }
            Err(_) => {
                self.closed.store(true, Ordering::Relaxed);
                return Err(WsError::Timeout);
            }
        }
        Ok(())
    }

    pub async fn recv(&self) -> Result<Vec<u8>, WsError> {
        if let Some(buf) = self.buffered_payload.lock().await.take() {
            self.record_frame_activity();
            return Ok(buf);
        }
        let mut assembling_buf: Option<Vec<u8>> = None;
        while !self.is_closed() {
            let (fin, opcode, payload) = match self.read_frame().await {
                Ok(v) => v,
                Err(e) => {
                    self.closed.store(true, Ordering::Relaxed);
                    return Err(e);
                }
            };
            match opcode {
                OP_CLOSE => {
                    self.closed.store(true, Ordering::Relaxed);
                    let code = if payload.len() >= 2 {
                        u16::from_be_bytes([payload[0], payload[1]])
                    } else {
                        1005
                    };
                    let reason = if payload.len() > 2 {
                        String::from_utf8_lossy(&payload[2..]).to_string()
                    } else {
                        String::new()
                    };
                    ldebug!("WebSocket remote OP_CLOSE code={} reason='{}'", code, reason);
                    let mut close_payload = payload;
                    if close_payload.len() > 2 {
                        close_payload.truncate(2);
                    }
                    let reply = build_frame(OP_CLOSE, &close_payload, true);
                    let _ = self.write_frame(&reply, WS_CONTROL_TIMEOUT).await;
                    return Err(WsError::Io(std::io::Error::new(
                        std::io::ErrorKind::UnexpectedEof,
                        if reason.is_empty() { format!("EOF (code {})", code) } else { format!("EOF (code {}: {})", code, reason) },
                    )));
                }
                OP_PING => {
                    self.record_frame_activity();
                    let pong = build_frame(OP_PONG, &payload, true);
                    let _ = self.write_frame(&pong, WS_CONTROL_TIMEOUT).await;
                    continue;
                }
                OP_PONG => {
                    self.record_pong(&payload);
                    continue;
                }
                OP_TEXT | OP_BINARY => {
                    self.record_frame_activity();
                    if fin {
                        return Ok(payload);
                    } else {
                        assembling_buf = Some(payload);
                    }
                }
                OP_CONTINUATION => {
                    self.record_frame_activity();
                    if let Some(mut buf) = assembling_buf.take() {
                        if (buf.len() as u64) + (payload.len() as u64) > MAX_FRAME_PAYLOAD {
                            self.closed.store(true, Ordering::Relaxed);
                            return Err(WsError::Other(format!(
                                "reassembled frame too large: {} bytes",
                                buf.len() + payload.len()
                            )));
                        }
                        buf.extend_from_slice(&payload);
                        if fin {
                            return Ok(buf);
                        } else {
                            assembling_buf = Some(buf);
                        }
                    } else {
                        return Ok(payload);
                    }
                }
                _ => {}
            }
        }
        Err(WsError::Io(std::io::Error::new(
            std::io::ErrorKind::UnexpectedEof,
            "EOF",
        )))
    }

    pub async fn close(&self) {
        if self.closed.swap(true, Ordering::Relaxed) {
            return;
        }
        let frame = build_frame(OP_CLOSE, &[], true);
        let _ = self.write_frame(&frame, WS_CONTROL_TIMEOUT).await;
    }

    pub async fn recv_with_timeout(&self, dur: Duration) -> Result<Vec<u8>, WsError> {
        let mut assembling_buf: Option<Vec<u8>> = None;
        loop {
            if self.is_closed() {
                return Err(WsError::Io(std::io::Error::new(
                    std::io::ErrorKind::UnexpectedEof,
                    "EOF",
                )));
            }
            let frame = {
                let mut reader = self.reader.lock().await;
                match tokio::time::timeout(dur, read_frame_locked(&mut reader, &self.heartbeat)).await {
                    Ok(Ok(v)) => v,
                    Ok(Err(e)) => {
                        self.closed.store(true, Ordering::Relaxed);
                        return Err(e);
                    }
                    Err(_) => return Err(WsError::Timeout),
                }
            };
            let (fin, opcode, payload) = frame;
            match opcode {
                OP_CLOSE => {
                    self.closed.store(true, Ordering::Relaxed);
                    let code = if payload.len() >= 2 {
                        u16::from_be_bytes([payload[0], payload[1]])
                    } else {
                        1005
                    };
                    let reason = if payload.len() > 2 {
                        String::from_utf8_lossy(&payload[2..]).to_string()
                    } else {
                        String::new()
                    };
                    ldebug!("WebSocket remote OP_CLOSE code={} reason='{}'", code, reason);
                    let mut close_payload = payload;
                    if close_payload.len() > 2 {
                        close_payload.truncate(2);
                    }
                    let reply = build_frame(OP_CLOSE, &close_payload, true);
                    let _ = self.write_frame(&reply, WS_CONTROL_TIMEOUT).await;
                    return Err(WsError::Io(std::io::Error::new(
                        std::io::ErrorKind::UnexpectedEof,
                        if reason.is_empty() { format!("EOF (code {})", code) } else { format!("EOF (code {}: {})", code, reason) },
                    )));
                }
                OP_PING => {
                    self.record_frame_activity();
                    let pong = build_frame(OP_PONG, &payload, true);
                    let _ = self.write_frame(&pong, WS_CONTROL_TIMEOUT).await;
                    continue;
                }
                OP_PONG => {
                    self.record_pong(&payload);
                    continue;
                }
                OP_TEXT | OP_BINARY => {
                    self.record_frame_activity();
                    if fin {
                        return Ok(payload);
                    } else {
                        assembling_buf = Some(payload);
                    }
                }
                OP_CONTINUATION => {
                    self.record_frame_activity();
                    if let Some(mut buf) = assembling_buf.take() {
                        if (buf.len() as u64) + (payload.len() as u64) > MAX_FRAME_PAYLOAD {
                            self.closed.store(true, Ordering::Relaxed);
                            return Err(WsError::Other(format!(
                                "reassembled frame too large: {} bytes",
                                buf.len() + payload.len()
                            )));
                        }
                        buf.extend_from_slice(&payload);
                        if fin {
                            return Ok(buf);
                        } else {
                            assembling_buf = Some(buf);
                        }
                    } else {
                        return Ok(payload);
                    }
                }
                _ => continue,
            }
        }
    }

    async fn read_frame(&self) -> Result<(bool, u8, Vec<u8>), WsError> {
        let mut reader = self.reader.lock().await;
        read_frame_locked(&mut reader, &self.heartbeat).await
    }
}

pub const MAX_FRAME_PAYLOAD: u64 = 16 * 1024 * 1024;

async fn read_frame_locked(
    reader: &mut BufReader<tokio::io::ReadHalf<WsStream>>,
    heartbeat: &parking_lot::Mutex<HeartbeatState>,
) -> Result<(bool, u8, Vec<u8>), WsError> {
    let mut hdr = [0u8; 2];
    read_exact_active(reader, &mut hdr, heartbeat).await?;

    let fin = (hdr[0] & 0x80) != 0;
    let opcode = hdr[0] & 0x0F;
    let mut length = (hdr[1] & 0x7F) as u64;

    if length == 126 {
        let mut buf = [0u8; 2];
        read_exact_active(reader, &mut buf, heartbeat).await?;
        length = BigEndian::read_u16(&buf) as u64;
    } else if length == 127 {
        let mut buf = [0u8; 8];
        read_exact_active(reader, &mut buf, heartbeat).await?;
        length = BigEndian::read_u64(&buf);
    }

    let has_mask = (hdr[1] & 0x80) != 0;
    let mut mask_key = [0u8; 4];
    if has_mask {
        read_exact_active(reader, &mut mask_key, heartbeat).await?;
    }

    if length > MAX_FRAME_PAYLOAD {
        return Err(WsError::Other(format!(
            "frame too large: {} bytes (max {})",
            length, MAX_FRAME_PAYLOAD
        )));
    }
    let mut payload = vec![0u8; length as usize];
    if length > 0 {
        read_exact_active(reader, &mut payload, heartbeat).await?;
    }
    if has_mask {
        xor_mask_in_place(&mut payload, &mask_key);
    }
    Ok((fin, opcode, payload))
}

async fn read_exact_active(
    reader: &mut BufReader<tokio::io::ReadHalf<WsStream>>,
    mut remaining: &mut [u8],
    heartbeat: &parking_lot::Mutex<HeartbeatState>,
) -> Result<(), WsError> {
    while !remaining.is_empty() {
        let n = reader.read(remaining).await?;
        if n == 0 {
            return Err(WsError::Io(std::io::ErrorKind::UnexpectedEof.into()));
        }
        heartbeat.lock().last_frame_activity = Instant::now();
        remaining = &mut remaining[n..];
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Frame builder
// ---------------------------------------------------------------------------

pub fn build_frame_ext(opcode: u8, data: &[u8], mask: bool, fin: bool) -> Vec<u8> {
    let length = data.len();
    let fb = if fin {
        0x80 | (opcode & 0x0F)
    } else {
        opcode & 0x0F
    };

    let mut header_size = 2;
    if mask {
        header_size += 4;
    }
    if length >= 126 && length < 65536 {
        header_size += 2;
    } else if length >= 65536 {
        header_size += 8;
    }

    let total_size = header_size + length;
    let mut result = vec![0u8; total_size];

    let mut pos = 0;
    result[pos] = fb;
    pos += 1;

    let mut mask_key = [0u8; 4];
    if mask {
        rand::thread_rng().fill_bytes(&mut mask_key);
    }

    if length < 126 {
        let mut lb = length as u8;
        if mask {
            lb |= 0x80;
        }
        result[pos] = lb;
        pos += 1;
    } else if length < 65536 {
        let mut lb = 126u8;
        if mask {
            lb |= 0x80;
        }
        result[pos] = lb;
        pos += 1;
        BigEndian::write_u16(&mut result[pos..], length as u16);
        pos += 2;
    } else {
        let mut lb = 127u8;
        if mask {
            lb |= 0x80;
        }
        result[pos] = lb;
        pos += 1;
        BigEndian::write_u64(&mut result[pos..], length as u64);
        pos += 8;
    }

    if mask {
        result[pos..pos + 4].copy_from_slice(&mask_key);
        pos += 4;
        result[pos..pos + length].copy_from_slice(data);
        xor_mask_in_place(&mut result[pos..pos + length], &mask_key);
    } else {
        result[pos..pos + length].copy_from_slice(data);
    }
    result
}

pub fn build_frame(opcode: u8, data: &[u8], mask: bool) -> Vec<u8> {
    build_frame_ext(opcode, data, mask, true)
}

// ---------------------------------------------------------------------------
// Connection helpers
// ---------------------------------------------------------------------------

pub fn set_client_sock_opts(stream: &TcpStream) {
    let _ = stream.set_nodelay(true);
    let sock = socket2::SockRef::from(stream);
    #[allow(unused_mut)]
    let mut ka = socket2::TcpKeepalive::new()
        .with_time(Duration::from_secs(15))
        .with_interval(Duration::from_secs(5));
    #[cfg(any(target_os = "android", unix))]
    {
        ka = ka.with_retries(3);
    }
    let _ = sock.set_tcp_keepalive(&ka);

    #[cfg(any(target_os = "android", unix))]
    unsafe {
        use std::os::unix::io::AsRawFd;
        // Clamp MSS to safe 1240 bytes (MTU 1280) to eliminate PMTUD black holes across
        // cellular networks, GTP-U tunnels, and Wi-Fi tethering hotspots (Redmi/MIUI).
        let mss: libc::c_int = 1240;
        libc::setsockopt(
            sock.as_raw_fd(),
            libc::IPPROTO_TCP,
            libc::TCP_MAXSEG,
            &mss as *const _ as *const libc::c_void,
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        );
    }
}

pub fn set_sock_opts(stream: &TcpStream) {
    let _ = stream.set_nodelay(true);
    let sock = socket2::SockRef::from(stream);

    let target_recv = RECV_BUF.load(Ordering::Relaxed);
    let target_send = SEND_BUF.load(Ordering::Relaxed);

    if target_recv > 0 {
        let recv_size = target_recv.clamp(MIN_SOCKET_BUFFER, MAX_SOCKET_BUFFER) as usize;
        let _ = sock.set_recv_buffer_size(recv_size);
    }
    if target_send > 0 {
        let send_size = target_send.clamp(MIN_SOCKET_BUFFER, MAX_SOCKET_BUFFER) as usize;
        let _ = sock.set_send_buffer_size(send_size);
    }

    let actual_recv = sock.recv_buffer_size().unwrap_or(0) as i32;
    let actual_send = sock.send_buffer_size().unwrap_or(0) as i32;
    LAST_OS_RECV_BUF.store(actual_recv, Ordering::Relaxed);
    LAST_OS_SEND_BUF.store(actual_send, Ordering::Relaxed);
    SOCKETS_CONFIGURED_TOTAL.fetch_add(1, Ordering::Relaxed);
    let actual_pair = ((actual_recv as u32 as u64) << 32) | actual_send as u32 as u64;
    if LAST_LOGGED_OS_BUFFER_PAIR.swap(actual_pair, Ordering::Relaxed) != actual_pair {
        crate::linfo!(
            "Socket buffers applied to new socket: target_recv={} target_send={} os_recv={} os_send={}",
            target_recv,
            target_send,
            actual_recv,
            actual_send
        );
    }

    #[allow(unused_mut)]
    let mut ka = socket2::TcpKeepalive::new()
        .with_time(Duration::from_secs(15))
        .with_interval(Duration::from_secs(5));
    #[cfg(any(target_os = "android", unix))]
    {
        ka = ka.with_retries(3);
    }
    let _ = sock.set_tcp_keepalive(&ka);

    #[cfg(any(target_os = "android", unix))]
    unsafe {
        use std::os::unix::io::AsRawFd;
        let mss: libc::c_int = 1240;
        libc::setsockopt(
            sock.as_raw_fd(),
            libc::IPPROTO_TCP,
            libc::TCP_MAXSEG,
            &mss as *const _ as *const libc::c_void,
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        );
    }
}

pub async fn connect_outbound_tcp(
    addr: &SocketAddr,
    timeout: Duration,
) -> Result<TcpStream, WsError> {
    let domain = if addr.is_ipv6() {
        socket2::Domain::IPV6
    } else {
        socket2::Domain::IPV4
    };

    let socket = socket2::Socket::new(domain, socket2::Type::STREAM, Some(socket2::Protocol::TCP))
        .map_err(WsError::Io)?;

    // Protect socket before connect() to prevent VPN routing loop into virtual TUN
    #[cfg(unix)]
    {
        use std::os::unix::io::AsRawFd;
        let raw_fd = socket.as_raw_fd();
        let _ = crate::protect_socket_fd(raw_fd);
    }

    let _ = socket.set_nonblocking(true);
    let _ = socket.set_nodelay(true);

    #[allow(unused_mut)]
    let mut ka = socket2::TcpKeepalive::new()
        .with_time(Duration::from_secs(15))
        .with_interval(Duration::from_secs(5));
    #[cfg(any(target_os = "android", unix))]
    {
        ka = ka.with_retries(3);
    }
    let _ = socket.set_tcp_keepalive(&ka);

    #[cfg(any(target_os = "android", unix))]
    unsafe {
        use std::os::unix::io::AsRawFd;
        let mss: libc::c_int = 1240;
        libc::setsockopt(
            socket.as_raw_fd(),
            libc::IPPROTO_TCP,
            libc::TCP_MAXSEG,
            &mss as *const _ as *const libc::c_void,
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        );
    }

    let target_recv = RECV_BUF.load(Ordering::Relaxed);
    let target_send = SEND_BUF.load(Ordering::Relaxed);
    if target_recv > 0 {
        let recv_size = target_recv.clamp(MIN_SOCKET_BUFFER, MAX_SOCKET_BUFFER) as usize;
        let _ = socket.set_recv_buffer_size(recv_size);
    }
    if target_send > 0 {
        let send_size = target_send.clamp(MIN_SOCKET_BUFFER, MAX_SOCKET_BUFFER) as usize;
        let _ = socket.set_send_buffer_size(send_size);
    }

    let target_sockaddr = socket2::SockAddr::from(*addr);
    match socket.connect(&target_sockaddr) {
        Ok(()) => {
            let std_stream: std::net::TcpStream = socket.into();
            let tokio_stream = TcpStream::from_std(std_stream).map_err(WsError::Io)?;
            Ok(tokio_stream)
        }
        Err(e) => {
            #[cfg(unix)]
            let is_in_progress = e.raw_os_error() == Some(libc::EINPROGRESS)
                || e.kind() == std::io::ErrorKind::WouldBlock;
            #[cfg(windows)]
            let is_in_progress = e.raw_os_error() == Some(10035)
                || e.kind() == std::io::ErrorKind::WouldBlock;
            #[cfg(not(any(unix, windows)))]
            let is_in_progress = e.kind() == std::io::ErrorKind::WouldBlock;

            if is_in_progress {
                let std_stream: std::net::TcpStream = socket.into();
                let tokio_stream = TcpStream::from_std(std_stream).map_err(WsError::Io)?;
                match tokio::time::timeout(timeout, tokio_stream.writable()).await {
                    Ok(Ok(())) => match tokio_stream.take_error() {
                        Ok(None) => Ok(tokio_stream),
                        Ok(Some(err)) => Err(WsError::Io(err)),
                        Err(err) => Err(WsError::Io(err)),
                    },
                    Ok(Err(io_err)) => Err(WsError::Io(io_err)),
                    Err(_) => Err(WsError::Timeout),
                }
            } else {
                Err(WsError::Io(e))
            }
        }
    }
}

pub fn ws_connect_timeout(timeout: f64) -> Duration {
    if timeout <= 0.0 {
        Duration::from_secs(5)
    } else {
        Duration::from_secs_f64(timeout)
    }
}

pub fn ws_handshake_timeout(total: Duration) -> Duration {
    if total <= Duration::ZERO {
        Duration::from_secs(10)
    } else if total > Duration::from_secs(10) {
        Duration::from_secs(10)
    } else {
        total
    }
}

pub fn compute_sec_websocket_accept(key: &str) -> String {
    const WS_GUID: &[u8] = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    let mut data = Vec::with_capacity(key.len() + WS_GUID.len());
    data.extend_from_slice(key.as_bytes());
    data.extend_from_slice(WS_GUID);
    let digest = ring::digest::digest(&ring::digest::SHA1_FOR_LEGACY_USE_ONLY, &data);
    base64::engine::general_purpose::STANDARD.encode(digest.as_ref())
}

/// Encodes raw binary data as a URL-safe unpadded Base64 string for RFC 8441 / Xray Sec-WebSocket-Protocol Early Data.
pub fn encode_ws_early_data(data: &[u8]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(data)
}

/// Parses the early data maximum byte length from a WebSocket path (e.g., "/vless-ws?ed=2048" -> Some(2048)).
pub fn parse_early_data_header_len(path: &str) -> Option<usize> {
    if let Some(pos) = path.find("ed=") {
        let after = &path[pos + 3..];
        let digits: String = after.chars().take_while(|c| c.is_ascii_digit()).collect();
        if let Ok(limit) = digits.parse::<usize>() {
            if limit > 0 {
                return Some(limit);
            }
        }
    }
    None
}

pub fn server_name(domain: &str) -> ServerName<'static> {
    ServerName::try_from(domain.to_string())
        .unwrap_or_else(|_| ServerName::IpAddress("127.0.0.1".parse::<IpAddr>().unwrap().into()))
}

pub const HAPPY_EYEBALLS_DEFAULT_DELAY: Duration = Duration::from_millis(200);
pub const HAPPY_EYEBALLS_DELAY: Duration = Duration::from_millis(200);

/// Connection Attempt Delay for Happy Eyeballs (RFC 8305 Section 5).
/// Uses the committed FSM profile, so RTT samples cannot bypass its dwell/cooldown.
pub fn compute_happy_eyeballs_delay() -> Duration {
    committed_happy_eyeballs_delay(
        crate::network_profile::get_profile().happy_eyeballs_delay_ms,
        MOBILE_NETWORK.load(Ordering::Relaxed),
    )
}

fn committed_happy_eyeballs_delay(committed_ms: u64, is_mobile: bool) -> Duration {
    let delay = if committed_ms == 0 {
        let profile = crate::network_profile::get_profile();
        let rtt = profile.transport_sli.smoothed_rtt_ms;
        if rtt > 0 {
            crate::config::HAPPY_EYEBALLS_RANGE_MS.clamp(rtt as u64)
        } else if is_mobile {
            180
        } else {
            150
        }
    } else {
        // Bounds are independent of the live transport: a handover cannot
        // silently change a committed timer while the policy is cooling down.
        ((committed_ms.clamp(50, 2000) + 25) / 50) * 50
    };
    Duration::from_millis(delay)
}

pub async fn happy_eyeballs_tcp_connect(
    addrs: &[SocketAddr],
    total_timeout: Duration,
) -> Result<(TcpStream, SocketAddr), WsError> {
    if addrs.is_empty() {
        return Err(WsError::Other(
            "no candidate addresses provided".to_string(),
        ));
    }
    if addrs.len() == 1 {
        let addr = addrs[0];
        let stream = connect_outbound_tcp(&addr, total_timeout).await?;
        return Ok((stream, addr));
    }

    let (tx, mut rx) = tokio::sync::mpsc::channel::<(TcpStream, SocketAddr)>(1);
    let cancel_token = CancellationToken::new();
    let mut tasks: Vec<tokio::task::JoinHandle<()>> = Vec::with_capacity(addrs.len());

    let mut next_idx = 0;
    let num_addrs = addrs.len();

    let deadline = tokio::time::sleep(total_timeout);
    tokio::pin!(deadline);

    let mut stagger_timer = tokio::time::interval(compute_happy_eyeballs_delay());
    let mut remaining_active: usize = 0;

    let (err_tx, mut err_rx) = tokio::sync::mpsc::channel::<std::io::Error>(num_addrs);
    #[allow(unused_assignments)]
    let mut last_err = None;

    loop {
        tokio::select! {
            _ = &mut deadline => {
                cancel_token.cancel();
                for t in tasks {
                    t.abort();
                }
                return Err(WsError::Timeout);
            }
            res = rx.recv() => {
                if let Some((stream, winning_addr)) = res {
                    cancel_token.cancel();
                    for t in tasks {
                        t.abort();
                    }
                    return Ok((stream, winning_addr));
                }
            }
            err = err_rx.recv() => {
                if let Some(e) = err {
                    last_err = Some(e);
                    remaining_active = remaining_active.saturating_sub(1);
                    // Fast failover: immediately launch next candidate on early connection failure
                    if next_idx < num_addrs {
                        let target_addr = addrs[next_idx];
                        next_idx += 1;
                        remaining_active += 1;

                        let tx = tx.clone();
                        let err_tx = err_tx.clone();
                        let cancel = cancel_token.clone();

                        tasks.push(tokio::spawn(async move {
                            tokio::select! {
                                _ = cancel.cancelled() => {}
                                res = connect_outbound_tcp(&target_addr, total_timeout) => {
                                    match res {
                                        Ok(s) => {
                                            let _ = tx.send((s, target_addr)).await;
                                        }
                                        Err(e) => {
                                            let io_err = match e {
                                                WsError::Io(err) => err,
                                                WsError::Timeout => std::io::Error::new(std::io::ErrorKind::TimedOut, "timeout"),
                                                other => std::io::Error::new(std::io::ErrorKind::Other, other.compact()),
                                            };
                                            let _ = err_tx.send(io_err).await;
                                        }
                                    }
                                }
                            }
                        }));
                    } else if remaining_active == 0 {
                        break;
                    }
                }
            }
            _ = stagger_timer.tick(), if next_idx < num_addrs => {
                let target_addr = addrs[next_idx];
                next_idx += 1;
                remaining_active += 1;

                let tx = tx.clone();
                let err_tx = err_tx.clone();
                let cancel = cancel_token.clone();

                tasks.push(tokio::spawn(async move {
                    tokio::select! {
                        _ = cancel.cancelled() => {}
                        res = connect_outbound_tcp(&target_addr, total_timeout) => {
                            match res {
                                Ok(s) => {
                                    let _ = tx.send((s, target_addr)).await;
                                }
                                Err(e) => {
                                    let io_err = match e {
                                        WsError::Io(err) => err,
                                        WsError::Timeout => std::io::Error::new(std::io::ErrorKind::TimedOut, "timeout"),
                                        other => std::io::Error::new(std::io::ErrorKind::Other, other.compact()),
                                    };
                                    let _ = err_tx.send(io_err).await;
                                }
                            }
                        }
                    }
                }));
            }
        }
    }

    cancel_token.cancel();
    for t in tasks {
        t.abort();
    }

    if let Some(e) = last_err {
        Err(WsError::Io(e))
    } else {
        Err(WsError::Other(
            "all connection candidates failed".to_string(),
        ))
    }
}

pub async fn ws_handshake_split_host_fp(
    raw_conn: TcpStream,
    dial_ip: &str,
    tls_sni: &str,
    host_header: &str,
    path: &str,
    fingerprint: &str,
    timeout: Duration,
) -> Result<RawWebSocket, WsError> {
    ws_handshake_split_host_ext(
        raw_conn,
        dial_ip,
        tls_sni,
        host_header,
        path,
        fingerprint,
        None,
        timeout,
    )
    .await
}

pub async fn ws_handshake_split_host(
    raw_conn: TcpStream,
    dial_ip: &str,
    tls_sni: &str,
    host_header: &str,
    path: &str,
    timeout: Duration,
) -> Result<RawWebSocket, WsError> {
    ws_handshake_split_host_ext(
        raw_conn,
        dial_ip,
        tls_sni,
        host_header,
        path,
        "chrome",
        None,
        timeout,
    )
    .await
}

pub async fn ws_handshake_over_stream(
    raw_conn: TcpStream,
    dial_ip: &str,
    domain: &str,
    path: &str,
    timeout: Duration,
) -> Result<RawWebSocket, WsError> {
    ws_handshake_split_host_ext(
        raw_conn, dial_ip, domain, domain, path, "chrome", None, timeout,
    )
    .await
}

pub async fn ws_handshake_over_stream_fp(
    raw_conn: TcpStream,
    dial_ip: &str,
    domain: &str,
    path: &str,
    fingerprint: &str,
    timeout: Duration,
) -> Result<RawWebSocket, WsError> {
    ws_handshake_split_host_ext(
        raw_conn,
        dial_ip,
        domain,
        domain,
        path,
        fingerprint,
        None,
        timeout,
    )
    .await
}

pub async fn ws_upgrade_stream(
    stream: WsStream,
    host_header: &str,
    path: &str,
    early_data: Option<&[u8]>,
    timeout: Duration,
) -> Result<RawWebSocket, WsError> {
    let relay = is_tcp_relay_path(path);
    if relay && early_data.is_some_and(|data| !data.is_empty()) {
        return Err(WsError::Other("TCP relay requires binary frames after readiness ACK".to_string()));
    }
    let (read_half, mut write_half) = tokio::io::split(stream);

    let mut ws_key_bytes = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut ws_key_bytes);
    let ws_key = base64::engine::general_purpose::STANDARD.encode(ws_key_bytes);

    let (sec_ws_proto_header, early_data_applied) = match early_data {
        Some(ed) if !ed.is_empty() => {
            let encoded = encode_ws_early_data(ed);
            (format!("Sec-WebSocket-Protocol: {}\r\n", encoded), true)
        }
        _ if relay => (String::new(), false),
        _ => ("Sec-WebSocket-Protocol: binary\r\n".to_string(), false),
    };

    let effective_path = if path.is_empty() { "/apiws" } else { path };

    let req = format!(
        "GET {} HTTP/1.1\r\n\
         Host: {}\r\n\
         Upgrade: websocket\r\n\
         Connection: Upgrade\r\n\
         Sec-WebSocket-Key: {}\r\n\
         Sec-WebSocket-Version: 13\r\n\
         {}\r\n",
        effective_path,
        host_header,
        ws_key,
        sec_ws_proto_header
    );

    match tokio::time::timeout(timeout, write_half.write_all(req.as_bytes())).await {
        Ok(Ok(())) => {}
        Ok(Err(e)) => return Err(WsError::Io(e)),
        Err(_) => return Err(WsError::Timeout),
    }

    let mut bufreader = BufReader::with_capacity(128 * 1024, read_half);

    let mut response_lines: Vec<String> = Vec::new();
    let read_result = tokio::time::timeout(timeout, async {
        loop {
            let line = read_line(&mut bufreader).await?;
            let line = line.trim_end_matches(['\r', '\n']).to_string();
            if line.is_empty() {
                break;
            }
            response_lines.push(line);
            if response_lines.len() > 100 {
                return Err(WsError::Other("too many HTTP headers".to_string()));
            }
        }
        Ok::<(), WsError>(())
    })
    .await;

    match read_result {
        Ok(Ok(())) => {}
        Ok(Err(e)) => return Err(e),
        Err(_) => return Err(WsError::Timeout),
    }

    if response_lines.is_empty() {
        return Err(WsError::Handshake(WsHandshakeError {
            status_code: 0,
            status_line: "empty response".to_string(),
            headers: HashMap::new(),
            location: String::new(),
        }));
    }

    let first_line = response_lines[0].clone();
    let parts: Vec<&str> = first_line.splitn(3, ' ').collect();
    let mut status_code = 0;
    if parts.len() >= 2 {
        status_code = parts[1].parse::<i32>().unwrap_or(0);
    }

    let mut headers = HashMap::new();
    for hl in &response_lines[1..] {
        if let Some(idx) = hl.find(':') {
            headers.insert(
                hl[..idx].trim().to_lowercase(),
                hl[idx + 1..].trim().to_string(),
            );
        }
    }

    if status_code == 101 {
        // RFC 6455: Upgrade header must be "websocket"
        let upgrade = headers
            .get("upgrade")
            .map(|s| s.to_lowercase())
            .unwrap_or_default();
        if upgrade != "websocket" {
            return Err(WsError::Handshake(WsHandshakeError {
                status_code,
                status_line: "Invalid Upgrade Header".to_string(),
                headers,
                location: String::new(),
            }));
        }

        // RFC 6455 Section 4.2.2: Sec-WebSocket-Accept = Base64(SHA1(Key + GUID))
        let expected_accept = compute_sec_websocket_accept(&ws_key);
        let actual_accept = headers
            .get("sec-websocket-accept")
            .cloned()
            .unwrap_or_default();

        if actual_accept != expected_accept {
            ldebug!(
                " ws handshake invalid Sec-WebSocket-Accept: expected={}, got={}",
                expected_accept,
                actual_accept
            );
            return Err(WsError::Handshake(WsHandshakeError {
                status_code,
                status_line: "Invalid Sec-WebSocket-Accept Header".to_string(),
                headers,
                location: String::new(),
            }));
        }

        let colo = crate::node_independence::extract_cf_colo(&headers);
        let cf_ray = headers.get("cf-ray").cloned().unwrap_or_else(|| "-".to_string());
        ldebug!("WebSocket upgrade 101: colo={} cf-ray={}", colo, cf_ray);
        return Ok(RawWebSocket {
            reader: tokio::sync::Mutex::new(bufreader),
            writer: tokio::sync::Mutex::new(write_half),
            closed: AtomicBool::new(false),
            buffered_payload: tokio::sync::Mutex::new(None),
            early_data_sent: early_data_applied,
            flow_controlled: false,
            heartbeat: parking_lot::Mutex::new(HeartbeatState {
                last_frame_activity: Instant::now(),
                last_send_activity: Instant::now(),
                awaiting_pong: None,
            }),
            heartbeat_nonce: AtomicU64::new(0),
            recovery_scope: host_header.to_string(),
            peer_ip: parking_lot::RwLock::new(None),
            colo,
            asn: "UNKNOWN".to_string(),
        });
    }

    let location = headers.get("location").cloned().unwrap_or_default();
    let cf_ray = headers.get("cf-ray").cloned().unwrap_or_else(|| "-".to_string());
    lwarn!(
        "WebSocket handshake rejected HTTP {}: {} (cf-ray={})",
        status_code,
        first_line,
        cf_ray
    );
    Err(WsError::Handshake(WsHandshakeError {
        status_code,
        status_line: first_line,
        headers,
        location,
    }))
}

pub async fn ws_handshake_split_host_ext(
    raw_conn: TcpStream,
    dial_ip: &str,
    tls_sni: &str,
    host_header: &str,
    path: &str,
    fingerprint: &str,
    early_data: Option<&[u8]>,
    timeout: Duration,
) -> Result<RawWebSocket, WsError> {
    set_sock_opts(&raw_conn);

    let tls_config = tls_config_for_ws_path(path, fingerprint);
    let connector = TlsConnector::from(tls_config);
    let sni = server_name(tls_sni);

    let handshake_timeout = ws_handshake_timeout(timeout);
    let net_gen = crate::network_profile::current_generation();
    let split_enabled = is_tls_client_hello_split_enabled();
    let split_stream = TlsClientHelloSplitStream::new(
        raw_conn,
        if split_enabled { 2 } else { 0 },
        if split_enabled { Duration::from_millis(25) } else { Duration::ZERO },
    );
    let tls_start = std::time::Instant::now();

    let tls_conn =
        match tokio::time::timeout(handshake_timeout, connector.connect(sni, split_stream)).await {
            Ok(Ok(c)) => {
                let duration_ms = tls_start.elapsed().as_millis() as u64;
                let kind = c.get_ref().1.handshake_kind();
                crate::tls_observability::TLS_TRACKER.write().record_success(
                    host_header,
                    net_gen,
                    kind,
                    duration_ms,
                );
                c
            }
            Ok(Err(e)) => {
                let duration_ms = tls_start.elapsed().as_millis() as u64;
                crate::tls_observability::TLS_TRACKER.write().record_failure(
                    host_header,
                    net_gen,
                    duration_ms,
                    &e.to_string(),
                );
                if e.kind() != std::io::ErrorKind::ConnectionReset {
                    ldebug!(" ws tls fail {} via {}: {}", tls_sni, dial_ip, e);
                }
                return Err(WsError::Io(e));
            }
            Err(_) => {
                let duration_ms = tls_start.elapsed().as_millis() as u64;
                crate::tls_observability::TLS_TRACKER.write().record_failure(
                    host_header,
                    net_gen,
                    duration_ms,
                    "handshake_timeout",
                );
                ldebug!(" ws tls fail {} via {}: timeout", tls_sni, dial_ip);
                return Err(WsError::Timeout);
            }
        };

    ws_upgrade_stream(
        WsStream::Tls(tls_conn),
        host_header,
        path,
        early_data,
        timeout,
    )
    .await
}

pub async fn ws_handshake_plain_ext(
    raw_conn: TcpStream,
    host_header: &str,
    path: &str,
    early_data: Option<&[u8]>,
    timeout: Duration,
) -> Result<RawWebSocket, WsError> {
    set_sock_opts(&raw_conn);
    ws_upgrade_stream(
        WsStream::Plain(raw_conn),
        host_header,
        path,
        early_data,
        timeout,
    )
    .await
}

pub async fn ws_connect_happy_eyeballs_split_ext(
    tls_sni: &str,
    host_header: &str,
    path: &str,
    early_data: Option<&[u8]>,
    addrs: &[SocketAddr],
    timeout: Duration,
) -> Result<(RawWebSocket, SocketAddr), WsError> {
    let expected = crate::generation_guard::snapshot();
    if addrs.is_empty() {
        return Err(WsError::Other(
            "no candidate addresses provided".to_string(),
        ));
    }

    let ordered_addrs = crate::recovery::order_socket_addrs_for_host(
        expected.network,
        &host_header,
        addrs,
    );
    let addrs = ordered_addrs.as_slice();

    let stagger_delay = compute_happy_eyeballs_delay();
    let num_addrs = addrs.len();

    let (winner_tx, mut winner_rx) = tokio::sync::mpsc::channel::<(RawWebSocket, SocketAddr)>(1);
    let (err_tx, mut err_rx) = tokio::sync::mpsc::channel::<(usize, WsError)>(num_addrs);
    let cancel_token = CancellationToken::new();
    let mut tasks: Vec<tokio::task::JoinHandle<()>> = Vec::with_capacity(num_addrs);

    let start_time = Instant::now();
    let deadline = tokio::time::sleep(timeout);
    tokio::pin!(deadline);

    let mut stagger_timer = tokio::time::interval(stagger_delay);
    // In Tokio, interval ticks immediately on the first call, so consume it for Candidate 0
    stagger_timer.tick().await;

    let early_data_holder = early_data.map(|d| Arc::new(tokio::sync::Mutex::new(Some(d.to_vec()))));
    let mut next_idx = 0;
    let mut remaining_active: usize = 0;
    let mut last_error = WsError::Other("all connection candidates failed".to_string());

    let spawn_candidate = |idx: usize,
                           addr: SocketAddr,
                           winner_tx: tokio::sync::mpsc::Sender<(RawWebSocket, SocketAddr)>,
                           err_tx: tokio::sync::mpsc::Sender<(usize, WsError)>,
                           cancel: CancellationToken,
                           early_data_holder: Option<Arc<tokio::sync::Mutex<Option<Vec<u8>>>>>,
                           tls_sni: String,
                           host_header: String,
                           path: String|
     -> tokio::task::JoinHandle<()> {
        tokio::spawn(async move {
            if cancel.is_cancelled() {
                return;
            }

            let elapsed = start_time.elapsed();
            let mut remaining = timeout.saturating_sub(elapsed);
            if remaining.is_zero() {
                crate::recovery::record_if_current(
                    expected,
                    &host_header,
                    crate::recovery::RecoveryCause::TcpTimeout,
                );
                let _ = err_tx.send((idx, WsError::Timeout)).await;
                return;
            }

            let candidate_res = async {
                // Throttle parallel candidate dials only when explicitly enabled in advanced settings
                let _mobile_dial_permit = if crate::config::is_dial_throttling_enabled() && MOBILE_NETWORK.load(Ordering::Relaxed) {
                    Some(acquire_mobile_dial_permit(&cancel).await?)
                } else {
                    None
                };

                // 1. TCP Connect
                let tcp_budget = remaining
                    .saturating_sub(Duration::from_millis(50))
                    .min(Duration::from_secs(10));
                let tcp_start = Instant::now();
                let stream = tokio::select! {
                    _ = cancel.cancelled() => return Err(WsError::Canceled),
                    res = connect_outbound_tcp(&addr, tcp_budget) => match res {
                        Ok(stream) => {
                            let tcp_rtt = tcp_start.elapsed().as_millis().max(1) as u64;
                            crate::recovery::record_stage_success(
                                expected.network,
                                &host_header,
                                addr.ip(),
                                crate::recovery::EstablishmentStage::Tcp,
                                tcp_rtt,
                            );
                            stream
                        }
                        Err(e) => {
                            crate::recovery::record_stage_failure(
                                expected.network,
                                &host_header,
                                addr.ip(),
                                crate::recovery::EstablishmentStage::Tcp,
                            );
                            crate::recovery::record_if_current(
                                expected,
                                &host_header,
                                crate::recovery::RecoveryCause::TcpTimeout,
                            );
                            return Err(e);
                        }
                    },
                };

                // Free mobile dial permit immediately after TCP SYN-ACK completes.
                // Subsequent stages (TLS 1.3 handshake and HTTP WebSocket upgrade) are
                // established payload traffic, not SYN connection attempts. Releasing the permit
                // here allows concurrent parallel workers to dial without waiting for full WS upgrade.
                drop(_mobile_dial_permit);

                if cancel.is_cancelled() {
                    return Err(WsError::Canceled);
                }

                // 2. TLS Handshake
                remaining = timeout.saturating_sub(start_time.elapsed());
                let dial_ip = addr.ip().to_string();
                let tls_config =
                    tls_config_for_ws_path(&path, crate::recovery::effective_tls_fingerprint());
                let connector = TlsConnector::from(tls_config);
                let sni = server_name(&tls_sni);
                let handshake_timeout = ws_handshake_timeout(
                    remaining.saturating_sub(Duration::from_millis(50)),
                );

                let split_enabled = is_tls_client_hello_split_enabled();
                let split_stream = TlsClientHelloSplitStream::new(
                    stream,
                    if split_enabled { 2 } else { 0 },
                    if split_enabled { Duration::from_millis(25) } else { Duration::ZERO },
                );

                let tls_start = Instant::now();
                let tls_stream = tokio::select! {
                    _ = cancel.cancelled() => return Err(WsError::Canceled),
                    res = tokio::time::timeout(handshake_timeout, connector.connect(sni, split_stream)) => {
                        match res {
                            Ok(Ok(c)) => {
                                let duration_ms = tls_start.elapsed().as_millis() as u64;
                                let kind = c.get_ref().1.handshake_kind();
                                crate::tls_observability::TLS_TRACKER.write().record_success(
                                    &host_header,
                                    expected.network,
                                    kind,
                                    duration_ms,
                                );
                                c
                            }
                            Ok(Err(e)) => {
                                let duration_ms = tls_start.elapsed().as_millis() as u64;
                                crate::tls_observability::TLS_TRACKER.write().record_failure(
                                    &host_header,
                                    expected.network,
                                    duration_ms,
                                    &e.to_string(),
                                );
                                crate::recovery::record_stage_failure(
                                    expected.network,
                                    &host_header,
                                    addr.ip(),
                                    crate::recovery::EstablishmentStage::Tls,
                                );
                                crate::recovery::record_if_current(
                                    expected,
                                    &host_header,
                                    crate::recovery::RecoveryCause::TlsReset,
                                );
                                if e.kind() != std::io::ErrorKind::ConnectionReset {
                                    ldebug!("HE cand {} via {} tls fail: {}", tls_sni, dial_ip, e);
                                }
                                return Err(WsError::Io(e));
                            }
                            Err(_) => {
                                let duration_ms = tls_start.elapsed().as_millis() as u64;
                                crate::tls_observability::TLS_TRACKER.write().record_failure(
                                    &host_header,
                                    expected.network,
                                    duration_ms,
                                    "handshake_timeout",
                                );
                                crate::recovery::record_stage_failure(
                                    expected.network,
                                    &host_header,
                                    addr.ip(),
                                    crate::recovery::EstablishmentStage::Tls,
                                );
                                crate::recovery::record_if_current(
                                    expected,
                                    &host_header,
                                    crate::recovery::RecoveryCause::TlsReset,
                                );
                                ldebug!("HE cand {} via {} tls fail: timeout", tls_sni, dial_ip);
                                return Err(WsError::Timeout);
                            }
                        }
                    }
                };

                if cancel.is_cancelled() {
                    return Err(WsError::Canceled);
                }

                // 3. Early Data check & WebSocket Upgrade (HTTP 101)
                remaining = timeout.saturating_sub(start_time.elapsed());
                let maybe_ed = if let Some(ref holder) = early_data_holder {
                    let mut guard = holder.lock().await;
                    guard.take()
                } else {
                    None
                };

                let upgrade_res = tokio::select! {
                    _ = cancel.cancelled() => {
                        if let (Some(ref holder), Some(ed)) = (&early_data_holder, maybe_ed) {
                            *holder.lock().await = Some(ed);
                        }
                        return Err(WsError::Canceled);
                    }
                    res = ws_upgrade_stream(
                        WsStream::Tls(tls_stream),
                        &host_header,
                        &path,
                        maybe_ed.as_deref(),
                        remaining.saturating_sub(Duration::from_millis(50)),
                    ) => res,
                };

                let ws = match upgrade_res {
                    Ok(w) => {
                        w.set_peer_ip(addr.ip());
                        let colo = w.colo().to_string();
                        crate::node_independence::NODE_INDEPENDENCE_TRACKER
                            .write()
                            .record_node_handshake_success(&host_header, addr.ip(), &colo);
                        w
                    }
                    Err(e) => {
                        crate::node_independence::NODE_INDEPENDENCE_TRACKER
                            .write()
                            .record_node_failure(&host_header, Some(addr.ip()), None);
                        crate::recovery::record_stage_failure(
                            expected.network,
                            &host_header,
                            addr.ip(),
                            crate::recovery::EstablishmentStage::Wss,
                        );
                        let cause = if is_http_status_error(&e, 429) {
                            crate::recovery::RecoveryCause::RateLimited429
                        } else {
                            crate::recovery::RecoveryCause::HttpUpgradeRejected
                        };
                        crate::recovery::record_if_current(expected, &host_header, cause);
                        if let (Some(ref holder), Some(ed)) = (&early_data_holder, maybe_ed) {
                            *holder.lock().await = Some(ed);
                        }
                        return Err(e);
                    }
                };

                if cancel.is_cancelled() {
                    let _ = ws.close().await;
                    return Err(WsError::Canceled);
                }


                if cancel.is_cancelled() {
                    let _ = ws.close().await;
                    return Err(WsError::Canceled);
                }

                let total_rtt = start_time.elapsed().as_millis().max(1) as u64;
                crate::recovery::record_stage_success(
                    expected.network,
                    &host_header,
                    addr.ip(),
                    crate::recovery::EstablishmentStage::Ready,
                    total_rtt,
                );

                if !crate::recovery::mark_success_if_current(expected, &host_header) {
                    let _ = ws.close().await;
                    return Err(WsError::Canceled);
                }
                Ok((ws, addr))
            }.await;

            match candidate_res {
                Ok((ws, addr)) => {
                    let _ = winner_tx.send((ws, addr)).await;
                }
                Err(WsError::Canceled) => {}
                Err(e) => {
                    let _ = err_tx.send((idx, e)).await;
                }
            }
        })
    };

    // Launch Candidate 0 immediately at t = 0
    let target_addr = addrs[next_idx];
    let cand_idx = next_idx;
    next_idx += 1;
    remaining_active += 1;
    tasks.push(spawn_candidate(
        cand_idx,
        target_addr,
        winner_tx.clone(),
        err_tx.clone(),
        cancel_token.clone(),
        early_data_holder.clone(),
        tls_sni.to_string(),
        host_header.to_string(),
        path.to_string(),
    ));

    let mut winner: Option<(RawWebSocket, SocketAddr)> = None;

    loop {
        tokio::select! {
            _ = &mut deadline => {
                last_error = WsError::Timeout;
                break;
            }
            res = winner_rx.recv() => {
                if let Some((ws, winning_addr)) = res {
                    winner = Some((ws, winning_addr));
                    break;
                }
            }
            err_msg = err_rx.recv() => {
                if let Some((_idx, e)) = err_msg {
                    last_error = e;
                    remaining_active = remaining_active.saturating_sub(1);

                    // Fast Failover: immediately launch next candidate if available
                    if next_idx < num_addrs {
                        let target_addr = addrs[next_idx];
                        let cand_idx = next_idx;
                        next_idx += 1;
                        remaining_active += 1;

                        tasks.push(spawn_candidate(
                            cand_idx,
                            target_addr,
                            winner_tx.clone(),
                            err_tx.clone(),
                            cancel_token.clone(),
                            early_data_holder.clone(),
                            tls_sni.to_string(),
                            host_header.to_string(),
                            path.to_string(),
                        ));
                    } else if remaining_active == 0 {
                        // All launched candidates failed and no more queued
                        break;
                    }
                }
            }
            _ = stagger_timer.tick(), if next_idx < num_addrs => {
                let target_addr = addrs[next_idx];
                let cand_idx = next_idx;
                next_idx += 1;
                remaining_active += 1;

                tasks.push(spawn_candidate(
                    cand_idx,
                    target_addr,
                    winner_tx.clone(),
                    err_tx.clone(),
                    cancel_token.clone(),
                    early_data_holder.clone(),
                    tls_sni.to_string(),
                    host_header.to_string(),
                    path.to_string(),
                ));
            }
        }
    }

    // Deterministic Loser Cancellation (Structured Concurrency)
    cancel_token.cancel();
    for t in &tasks {
        t.abort();
    }
    for t in tasks {
        let _ = tokio::time::timeout(Duration::from_millis(500), t).await;
    }

    // Drain and close any runner-up websockets that completed around the same time
    while let Ok((extra_ws, _)) = winner_rx.try_recv() {
        let _ = extra_ws.close().await;
    }

    winner.ok_or(last_error)
}

pub async fn ws_connect_happy_eyeballs_ext(
    domain: &str,
    path: &str,
    early_data: Option<&[u8]>,
    addrs: &[SocketAddr],
    timeout: Duration,
) -> Result<(RawWebSocket, SocketAddr), WsError> {
    ws_connect_happy_eyeballs_split_ext(domain, domain, path, early_data, addrs, timeout).await
}

pub async fn ws_connect_happy_eyeballs(
    domain: &str,
    path: &str,
    addrs: &[SocketAddr],
    timeout: Duration,
) -> Result<(RawWebSocket, SocketAddr), WsError> {
    ws_connect_happy_eyeballs_ext(domain, path, None, addrs, timeout).await
}

pub async fn ws_connect_once(
    dial_addr: &str,
    domain: &str,
    path: &str,
    timeout: Duration,
) -> Result<RawWebSocket, WsError> {
    if dial_addr.is_empty() {
        return Err(WsError::Other("empty dial address".to_string()));
    }

    if let Ok(ip) = dial_addr.parse::<IpAddr>() {
        let sock_addr = SocketAddr::new(ip, 443);
        let (stream, _) = happy_eyeballs_tcp_connect(&[sock_addr], timeout).await?;
        return ws_handshake_over_stream(stream, dial_addr, domain, path, timeout).await;
    }

    let target_addr = if dial_addr.contains(':') {
        dial_addr.to_string()
    } else {
        format!("{}:443", dial_addr)
    };

    let addrs: Vec<SocketAddr> = match tokio::net::lookup_host(&target_addr).await {
        Ok(iter) => iter.collect(),
        Err(e) => return Err(WsError::Io(e)),
    };
    if addrs.is_empty() {
        return Err(WsError::Other(format!("no address resolved for {}", target_addr)));
    }
    let (raw_conn, _) = happy_eyeballs_tcp_connect(&addrs, timeout).await?;
    ws_handshake_over_stream(raw_conn, dial_addr, domain, path, timeout).await
}

async fn read_line<R: AsyncReadExt + Unpin>(reader: &mut R) -> Result<String, WsError> {
    let mut buf = Vec::with_capacity(128);
    let mut byte = [0u8; 1];
    loop {
        let n = reader.read(&mut byte).await?;
        if n == 0 {
            return Err(WsError::Io(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "EOF",
            )));
        }
        buf.push(byte[0]);
        if byte[0] == b'\n' {
            break;
        }
        if buf.len() > 16384 {
            return Err(WsError::Other("header line too long".to_string()));
        }
    }
    Ok(String::from_utf8_lossy(&buf).to_string())
}

pub async fn ws_connect(
    ip: &str,
    domain: &str,
    path: &str,
    timeout: f64,
) -> Result<RawWebSocket, WsError> {
    let path = if path.is_empty() { "/apiws" } else { path };
    let attempt_timeout = ws_connect_timeout(timeout);

    let candidate_ips = if !ip.trim().is_empty() {
        if let Ok(parsed) = ip.trim().parse::<IpAddr>() {
            vec![parsed]
        } else {
            crate::cfproxy::resolve_dual_stack_ips(ip.trim()).await
        }
    } else {
        crate::cfproxy::resolve_dual_stack_ips(domain).await
    };

    let candidate_addrs: Vec<SocketAddr> = candidate_ips
        .into_iter()
        .map(|ip_addr| SocketAddr::new(ip_addr, 443))
        .collect();

    if candidate_addrs.is_empty() {
        return Err(WsError::Other("no candidate addresses found".to_string()));
    }

    let (ws, _) =
        ws_connect_happy_eyeballs(domain, path, &candidate_addrs, attempt_timeout).await?;
    Ok(ws)
}

pub async fn connect_one_ws(ip: &str, domains: &[String]) -> Option<RawWebSocket> {
    for d in domains {
        if let Ok(ws) = ws_connect(ip, d, "/apiws", crate::config::adaptive_ws_connect_timeout()).await {
            return Some(ws);
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn relay_tls_does_not_use_fingerprint_profiles() {
        for path in ["/tcp?target=149.154.167.50%3A443", "/tcp-v2?target=x&flow=1"] {
            for fp in ["chrome", "firefox", "randomized"] {
                assert!(Arc::ptr_eq(&tls_config_for_ws_path(path, fp), &TLS_CONFIG_RELAY));
            }
        }
        for path in ["/apiws", "/vless-ws", "/tcp-v20", "/tcp/other", "/tcp", "/tcp-v2?ed=2048"] {
            assert!(!is_tcp_relay_path(path));
        }
    }

    #[test]
    fn test_build_frame_unfragmented() {
        let payload = b"hello telegram ws";
        let frame = build_frame(OP_BINARY, payload, false);

        assert_eq!(frame[0], 0x82); // FIN=1 (0x80) | OP_BINARY (0x02)
        assert_eq!(frame[1], payload.len() as u8); // Unmasked
        assert_eq!(&frame[2..], payload);
    }

    #[test]
    fn test_build_frame_fragmented_rfc6455() {
        let chunk1 = b"fragment 1";
        let chunk2 = b"fragment 2";
        let chunk3 = b"fragment 3";

        // Frame 1: OP_BINARY, FIN=0
        let f1 = build_frame_ext(OP_BINARY, chunk1, false, false);
        assert_eq!(f1[0], 0x02); // FIN=0, opcode=2
        assert_eq!(f1[1], chunk1.len() as u8);
        assert_eq!(&f1[2..], chunk1);

        // Frame 2: OP_CONTINUATION, FIN=0
        let f2 = build_frame_ext(OP_CONTINUATION, chunk2, false, false);
        assert_eq!(f2[0], 0x00); // FIN=0, opcode=0
        assert_eq!(f2[1], chunk2.len() as u8);
        assert_eq!(&f2[2..], chunk2);

        // Frame 3: OP_CONTINUATION, FIN=1
        let f3 = build_frame_ext(OP_CONTINUATION, chunk3, false, true);
        assert_eq!(f3[0], 0x80); // FIN=1, opcode=0
        assert_eq!(f3[1], chunk3.len() as u8);
        assert_eq!(&f3[2..], chunk3);
    }

    #[test]
    fn test_build_frame_large_payload() {
        let payload = vec![0x42u8; 70000]; // > 65535 bytes -> 8-byte length
        let frame = build_frame(OP_BINARY, &payload, false);

        assert_eq!(frame[0], 0x82);
        assert_eq!(frame[1], 127);
        let len = BigEndian::read_u64(&frame[2..10]);
        assert_eq!(len, 70000);
        assert_eq!(&frame[10..], &payload[..]);
    }

    #[test]
    fn test_max_frame_payload_limit() {
        assert_eq!(MAX_FRAME_PAYLOAD, 16 * 1024 * 1024);
    }

    #[test]
    fn test_tls_config_alpn_and_profiles() {
        let chrome_cfg = get_tls_config_for_fingerprint("chrome");
        assert_eq!(chrome_cfg.alpn_protocols, vec![b"http/1.1".to_vec()]);

        let ff_cfg = get_tls_config_for_fingerprint("firefox");
        assert_eq!(ff_cfg.alpn_protocols, vec![b"http/1.1".to_vec()]);

        let safari_cfg = get_tls_config_for_fingerprint("safari");
        assert_eq!(safari_cfg.alpn_protocols, vec![b"http/1.1".to_vec()]);

        let rand_cfg = get_tls_config_for_fingerprint("randomized");
        assert_eq!(rand_cfg.alpn_protocols, vec![b"http/1.1".to_vec()]);
    }

    #[test]
    fn test_early_data_encoding_and_path_parsing() {
        let sample = b"hello vless early data";
        let encoded = encode_ws_early_data(sample);
        assert!(!encoded.contains('='));
        assert!(!encoded.contains('+'));
        assert!(!encoded.contains('/'));

        assert_eq!(parse_early_data_header_len("/vless-ws?ed=2048"), Some(2048));
        assert_eq!(
            parse_early_data_header_len("/vless-ws?foo=bar&ed=4096&baz=1"),
            Some(4096)
        );
        assert_eq!(parse_early_data_header_len("/vless-ws"), None);
        assert_eq!(parse_early_data_header_len("/apiws"), None);
        assert_eq!(parse_early_data_header_len("/vless-ws?ed=0"), None);
    }

    #[test]
    fn test_fingerprint_classification_and_capability_matrix() {
        assert_eq!(
            classify_fingerprint("chrome"),
            ("chrome", FingerprintCapability::Supported)
        );
        assert_eq!(
            classify_fingerprint("firefox"),
            ("firefox", FingerprintCapability::Supported)
        );
        assert_eq!(
            classify_fingerprint("safari"),
            ("safari", FingerprintCapability::Supported)
        );
        assert_eq!(
            classify_fingerprint("ios"),
            ("ios", FingerprintCapability::Supported)
        );
        assert_eq!(
            classify_fingerprint("randomized"),
            ("randomized", FingerprintCapability::Supported)
        );
        assert_eq!(
            classify_fingerprint(""),
            ("chrome", FingerprintCapability::Supported)
        );

        assert_eq!(
            classify_fingerprint("edge"),
            ("chrome", FingerprintCapability::Mapped)
        );
        assert_eq!(
            classify_fingerprint("360"),
            ("chrome", FingerprintCapability::Mapped)
        );
        assert_eq!(
            classify_fingerprint("qq"),
            ("chrome", FingerprintCapability::Mapped)
        );
        assert_eq!(
            classify_fingerprint("android"),
            ("chrome", FingerprintCapability::Mapped)
        );

        assert_eq!(
            classify_fingerprint("unknown_parrot"),
            ("chrome", FingerprintCapability::UnsupportedFallback)
        );
        assert_eq!(
            classify_fingerprint("bot-agent"),
            ("chrome", FingerprintCapability::UnsupportedFallback)
        );

        // Ensure get_tls_config_for_fingerprint works reliably across all categories
        let cfg_supported = get_tls_config_for_fingerprint("firefox");
        assert_eq!(cfg_supported.alpn_protocols, vec![b"http/1.1".to_vec()]);

        let cfg_mapped = get_tls_config_for_fingerprint("edge");
        assert_eq!(cfg_mapped.alpn_protocols, vec![b"http/1.1".to_vec()]);

        let cfg_unsupported = get_tls_config_for_fingerprint("nonexistent_fp");
        assert_eq!(cfg_unsupported.alpn_protocols, vec![b"http/1.1".to_vec()]);
    }

    #[test]
    fn test_happy_eyeballs_delay_calculation() {
        MOBILE_NETWORK.store(false, Ordering::Relaxed);
        let wifi_delay = compute_happy_eyeballs_delay();
        assert!(wifi_delay >= Duration::from_millis(100));
        assert!(wifi_delay <= Duration::from_millis(1000));

        MOBILE_NETWORK.store(true, Ordering::Relaxed);
        let mobile_delay = compute_happy_eyeballs_delay();
        assert!(mobile_delay >= Duration::from_millis(180));
        assert!(mobile_delay <= Duration::from_millis(2000));
    }

    #[tokio::test]
    async fn test_tls_client_hello_split_stream_fragmentation() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};

        let (client, mut server) = tokio::io::duplex(1024);
        let mut split = TlsClientHelloSplitStream::new(client, 2, Duration::from_millis(5));

        // Simulated TLS 1.3 ClientHello (starts with 0x16 0x03 0x01, length 2 bytes, then handshake body)
        let mut fake_client_hello = vec![0x16, 0x03, 0x01, 0x00, 0x20];
        fake_client_hello.extend_from_slice(&[0x01, 0x00, 0x00, 0x1C]); // ClientHello handshake header
        fake_client_hello.extend_from_slice(&[0xAA; 28]); // Body

        let write_task = tokio::spawn(async move {
            split.write_all(&fake_client_hello).await.unwrap();
            split.flush().await.unwrap();
            split.write_all(b"subsequent_data").await.unwrap();
            split.flush().await.unwrap();
        });

        // Server reads first segment (exactly 2 bytes for the header split)
        let mut seg1 = [0u8; 2];
        server.read_exact(&mut seg1).await.unwrap();
        assert_eq!(&seg1, &[0x16, 0x03], "First segment must contain exactly 2-byte TLS record prefix");

        // Server reads second segment (remainder of ClientHello, 35 bytes)
        let mut seg2 = [0u8; 35];
        server.read_exact(&mut seg2).await.unwrap();
        assert_eq!(seg2[0], 0x01, "Second segment must start with minor version 0x01");
        assert_eq!(&seg2[7..], &[0xAA; 28]);

        // Server reads subsequent data
        let mut seg3 = [0u8; 15];
        server.read_exact(&mut seg3).await.unwrap();
        assert_eq!(&seg3, b"subsequent_data");

        write_task.await.unwrap();
    }

    #[tokio::test]
    async fn test_tls_client_hello_split_stream_passthrough_non_tls() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};

        let (client, mut server) = tokio::io::duplex(1024);
        let mut split = TlsClientHelloSplitStream::new(client, 5, Duration::from_millis(5));

        let data = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
        let write_task = tokio::spawn(async move {
            split.write_all(data).await.unwrap();
            split.flush().await.unwrap();
        });

        let mut buf = vec![0u8; data.len()];
        server.read_exact(&mut buf).await.unwrap();
        assert_eq!(&buf, data);

        write_task.await.unwrap();
    }

    #[tokio::test]
    async fn test_tls_client_hello_split_stream_disabled_when_zero() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};

        let (client, mut server) = tokio::io::duplex(1024);
        let mut split = TlsClientHelloSplitStream::new(client, 0, Duration::ZERO);

        let fake_client_hello = vec![0x16, 0x03, 0x01, 0x00, 0x05, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE];
        let payload = fake_client_hello.clone();
        let write_task = tokio::spawn(async move {
            split.write_all(&payload).await.unwrap();
            split.flush().await.unwrap();
        });

        let mut buf = vec![0u8; fake_client_hello.len()];
        server.read_exact(&mut buf).await.unwrap();
        assert_eq!(&buf, &fake_client_hello);

        write_task.await.unwrap();
    }
}