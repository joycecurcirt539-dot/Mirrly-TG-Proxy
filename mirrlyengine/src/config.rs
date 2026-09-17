use once_cell::sync::Lazy;
use parking_lot::RwLock;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicI64, AtomicU64, Ordering};
use std::time::{Duration, Instant};

// ---------------------------------------------------------------------------
// Constants & Configuration
// ---------------------------------------------------------------------------

pub const DEFAULT_PORT: u16 = 1443;
pub const SOCKS5_DEFAULT_PORT: u16 = 10808;
pub static TCP_NODELAY: AtomicBool = AtomicBool::new(true);
pub const DEFAULT_RECV_BUF: usize = 256 * 1024;
pub const DEFAULT_SEND_BUF: usize = 256 * 1024;
pub const DEFAULT_MTPROTO_STANDBY_PER_ACTIVE_SLOT: i32 = 2;

pub const DC_FAIL_COOLDOWN: f64 = 30.0;
pub const WS_FAIL_TIMEOUT: f64 = 2.0;

/// Profile-aware absolute idle timeout for active bridges.
/// Data idle != dead: a flow with active transport health (PONG frames / TCP keepalive)
/// remains open across long idle pauses (10+ minutes). This absolute ceiling retires
/// completely abandoned flows after 15–30 minutes to prevent resource leaks.
pub fn profile_aware_absolute_idle_timeout() -> Duration {
    let profile = crate::network_profile::get_profile();
    match (profile.power_save_mode, profile.screen_on, profile.cellular) {
        (true, _, _) => Duration::from_secs(30 * 60),        // 30 min in power save
        (false, false, _) => Duration::from_secs(30 * 60),    // 30 min screen off
        (false, true, true) => Duration::from_secs(15 * 60),   // 15 min active cellular
        (false, true, false) => Duration::from_secs(30 * 60),  // 30 min active Wi-Fi
    }
}

pub const BRIDGE_READ_TIMEOUT: Duration = Duration::from_secs(1800);
pub const BRIDGE_WRITE_TIMEOUT: Duration = Duration::from_secs(15);
pub const WS_HEARTBEAT_CHECK_INTERVAL: Duration = Duration::from_secs(5);
pub const WS_POOL_HOUSEKEEP_INTERVAL: Duration = Duration::from_secs(10);
pub const WS_WRITE_TIMEOUT: Duration = Duration::from_secs(15);
pub const WS_CONTROL_TIMEOUT: Duration = Duration::from_secs(2);
pub const WS_BRIDGE_CHUNK_SIZE: usize = 64 * 1024;
pub const POOLED_FRAME_CAP: usize = WS_BRIDGE_CHUNK_SIZE + 32;

pub const WS_POOL_REUSE_MAX_AGE: f64 = 30.0;
pub const WS_POOL_CONNECT_TIMEOUT: f64 = 8.0;

pub const CFPROXY_CACHE_FILE_NAME: &str = "cfproxy-domains-cache.txt";
pub const CFPROXY_REFRESH_INTERVAL: Duration = Duration::from_secs(12 * 3600);
pub const CFPROXY_DIAL_PHASE_TIMEOUT: Duration = Duration::from_millis(2500);
pub const CFPROXY_MOBILE_DIAL_TIMEOUT: Duration = Duration::from_millis(4000);
pub const CFPROXY_RACE_TIMEOUT: Duration = Duration::from_millis(2000);
pub const CFPROXY_RACE_INTERVAL: Duration = Duration::from_secs(3600);
pub const CFPROXY_FALLBACK_PARALLEL: usize = 4;
pub const CFPROXY_429_COOLDOWN: Duration = Duration::from_secs(45);
pub const CFPROXY_429_MAX_COOLDOWN: Duration = Duration::from_secs(300);
pub const CFPROXY_GLOBAL_PARALLEL: usize = 4;

pub const MIN_SOCKET_BUFFER: i32 = 32 * 1024;
pub const MAX_SOCKET_BUFFER: i32 = 2 * 1024 * 1024;

pub static REQUESTED_RECV_BUF: AtomicI32 = AtomicI32::new(DEFAULT_RECV_BUF as i32);
pub static REQUESTED_SEND_BUF: AtomicI32 = AtomicI32::new(DEFAULT_SEND_BUF as i32);
pub static RECV_BUF: AtomicI32 = AtomicI32::new(DEFAULT_RECV_BUF as i32);
pub static SEND_BUF: AtomicI32 = AtomicI32::new(DEFAULT_SEND_BUF as i32);
pub static LAST_OS_RECV_BUF: AtomicI32 = AtomicI32::new(0);
pub static LAST_OS_SEND_BUF: AtomicI32 = AtomicI32::new(0);
pub static SOCKETS_CONFIGURED_TOTAL: AtomicU64 = AtomicU64::new(0);
pub static LAST_LOGGED_OS_BUFFER_PAIR: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct SocketBufferStatus {
    pub configured_recv_bytes: i32,
    pub configured_send_bytes: i32,
    pub clamped_recv_bytes: i32,
    pub clamped_send_bytes: i32,
    pub last_os_recv_bytes: i32,
    pub last_os_send_bytes: i32,
    pub sockets_configured_total: u64,
    pub autotune_baseline: bool,
}

pub fn get_socket_buffer_status() -> SocketBufferStatus {
    let configured_recv = REQUESTED_RECV_BUF.load(Ordering::Relaxed);
    let configured_send = REQUESTED_SEND_BUF.load(Ordering::Relaxed);
    let clamped_recv = RECV_BUF.load(Ordering::Relaxed);
    let clamped_send = SEND_BUF.load(Ordering::Relaxed);
    let autotune = clamped_recv == 0 && clamped_send == 0;
    SocketBufferStatus {
        configured_recv_bytes: configured_recv,
        configured_send_bytes: configured_send,
        clamped_recv_bytes: clamped_recv,
        clamped_send_bytes: clamped_send,
        last_os_recv_bytes: LAST_OS_RECV_BUF.load(Ordering::Relaxed),
        last_os_send_bytes: LAST_OS_SEND_BUF.load(Ordering::Relaxed),
        sockets_configured_total: SOCKETS_CONFIGURED_TOTAL.load(Ordering::Relaxed),
        autotune_baseline: autotune,
    }
}

pub static MTPROTO_STANDBY_PER_ACTIVE_SLOT_REQUESTED: AtomicI32 =
    AtomicI32::new(DEFAULT_MTPROTO_STANDBY_PER_ACTIVE_SLOT);
pub static LOG_VERBOSE: AtomicBool = AtomicBool::new(false);
/// Set by the Android connectivity observer. Wi-Fi keeps the existing tuning;
/// cellular uses a smaller shared dial budget and a conservative idle pool.
pub static MOBILE_NETWORK: AtomicBool = AtomicBool::new(false);

// ---------------------------------------------------------------------------
// Transport Pool & Concurrency Metrics (MOB-016)
// ---------------------------------------------------------------------------

pub const MTPROTO_MIN_STANDBY: i32 = 1;
pub const MTPROTO_MAX_STANDBY: i32 = 4;

/// Computes the effective MTProto standby socket limit per active DC slot.
/// On mobile: 1 standby socket to keep resource and radio footprint minimal.
/// On Wi-Fi: requested pool size clamped to 1..4 (native upper bound).
pub fn effective_mtproto_standby(requested: i32, is_mobile: bool) -> usize {
    if is_mobile {
        1
    } else {
        requested.clamp(MTPROTO_MIN_STANDBY, MTPROTO_MAX_STANDBY) as usize
    }
}

/// Global establishment (dial) budget.
/// On mobile: 2 active establishments.
/// On Wi-Fi: 4 active establishments.
pub fn global_establishment_budget(is_mobile: bool) -> usize {
    if is_mobile {
        2
    } else {
        4
    }
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct TransportPoolStatus {
    pub transport: String,
    pub is_mobile: bool,
    pub mtproto_standby_per_active_slot_requested: i32,
    pub mtproto_standby_per_active_slot_effective: i32,
    pub global_establishment_budget: usize,
    pub socks_concurrent_flows: i64,
}

pub fn get_transport_pool_status(running_transport: &str) -> TransportPoolStatus {
    let is_mobile = MOBILE_NETWORK.load(Ordering::Relaxed);
    let requested = MTPROTO_STANDBY_PER_ACTIVE_SLOT_REQUESTED.load(Ordering::Relaxed);
    let effective = effective_mtproto_standby(requested, is_mobile) as i32;
    let budget = global_establishment_budget(is_mobile);
    let flows = if running_transport == "socks5" {
        STATS.connections_active.load(Ordering::Relaxed)
    } else {
        0
    };

    TransportPoolStatus {
        transport: running_transport.to_string(),
        is_mobile,
        mtproto_standby_per_active_slot_requested: requested,
        mtproto_standby_per_active_slot_effective: effective,
        global_establishment_budget: budget,
        socks_concurrent_flows: flows,
    }
}

#[derive(Clone)]
pub struct Cfproxy429State {
    pub until: Option<Instant>,
    pub strikes: i32,
}

impl Default for Cfproxy429State {
    fn default() -> Self {
        Cfproxy429State {
            until: None,
            strikes: 0,
        }
    }
}

// Cloudflare proxy config
pub static CFPROXY_ENABLED: AtomicBool = AtomicBool::new(true);

pub struct CfproxyConfig {
    pub user_domain: String,
    pub domains: Vec<String>,
    pub active: String,
    pub cache_dir: String,
}

pub static CFPROXY: Lazy<RwLock<CfproxyConfig>> = Lazy::new(|| {
    RwLock::new(CfproxyConfig {
        user_domain: String::new(),
        domains: Vec::new(),
        active: String::new(),
        cache_dir: String::new(),
    })
});

pub static CFPROXY_429: Lazy<RwLock<HashMap<String, Cfproxy429State>>> =
    Lazy::new(|| RwLock::new(HashMap::new()));

pub const CFPROXY_DOMAINS_URL: &str =
    "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt";

// MTProto proxy secret
pub static PROXY_SECRET: Lazy<RwLock<String>> =
    Lazy::new(|| RwLock::new("00000000000000000000000000000000".to_string()));

pub static DEFAULT_SOCKS5_WORKER: &str = "mirrly-tg-proxy-worker.brawny-singer.workers.dev";

pub static DEV_SOCKS5_WORKERS: &[&str] = &[
    DEFAULT_SOCKS5_WORKER,
    "mtg-relay-5o77p2.mtg-alfaj.workers.dev",
    "mtg-relay-ki2q2v.mtg-beta.workers.dev",
    "mtg-relay-vndj4a.tammistichtqvc264.workers.dev",
    "mtg-relay-xbl1ts.mtg-beta.workers.dev",
];

pub static LAST_SOCKS5_WORKER: Lazy<RwLock<String>> = Lazy::new(|| RwLock::new(String::new()));

// SOCKS5 User/Password Authentication (RFC 1928 / RFC 1929)
#[derive(Clone, Default, Debug)]
pub struct Socks5AuthConfig {
    pub username: String,
    pub password: String,
}

pub static SOCKS5_AUTH: Lazy<RwLock<Socks5AuthConfig>> = Lazy::new(|| {
    RwLock::new(Socks5AuthConfig {
        username: String::new(),
        password: String::new(),
    })
});

pub static CFPROXY_ENC: &[&str] = &[
    "virkgj.com",
    "vmmzovy.com",
    "mkuosckvso.com",
    "zaewayzmplad.com",
    "twdmbzcm.com",
    "awzwsldi.com",
    "clngqrflngqin.com",
    "tjacxbqtj.com",
    "bxaxtxmrw.com",
    "dmohrsgmohcrwb.com",
    "vwbmtmoi.com",
    "khgrre.com",
    "ulihssf.com",
    "tmhqsdqmfpmk.com",
    "xwuwoqbm.com",
    "orgcnunpj.com",
    "zhkuldz.com",
    "zypoljnslxa.com",
    "efabnxaowuzs.com",
    "zaftuzsftqdq.com",
];

// DC default IPs
pub static DC_DEFAULT_IPS: Lazy<HashMap<i32, &'static str>> = Lazy::new(|| {
    let mut m = HashMap::new();
    m.insert(1, "149.154.175.50");
    m.insert(2, "149.154.167.51");
    m.insert(3, "149.154.175.100");
    m.insert(4, "149.154.167.91");
    m.insert(5, "91.108.56.130");
    m.insert(203, "91.105.192.100");
    m
});

// Telegram protocols & DC mapping
pub fn valid_proto(p: u32) -> bool {
    matches!(p, 0xEFEFEFEF | 0xEEEEEEEE | 0xDDDDDDDD)
}

pub static DC_OVERRIDES: Lazy<HashMap<i32, i32>> = Lazy::new(|| {
    let mut m = HashMap::new();
    m.insert(203, 2);
    m
});

// Global state
pub static DC_OPT: Lazy<RwLock<HashMap<i32, String>>> = Lazy::new(|| RwLock::new(HashMap::new()));
pub static WS_BLACKLIST: Lazy<RwLock<HashMap<(i32, i32), bool>>> =
    Lazy::new(|| RwLock::new(HashMap::new()));
pub static DC_FAIL_UNTIL: Lazy<RwLock<HashMap<(i32, i32), f64>>> =
    Lazy::new(|| RwLock::new(HashMap::new()));

pub static ZERO64: [u8; 64] = [0u8; 64];

// ---------------------------------------------------------------------------
// Stats
// ---------------------------------------------------------------------------

#[derive(Default)]
pub struct Stats {
    pub connections_total: AtomicI64,
    pub connections_active: AtomicI64,
    pub connections_ws: AtomicI64,
    pub connections_tcp_fallback: AtomicI64,
    pub connections_cfproxy: AtomicI64,
    pub connections_masque: AtomicI64,
    pub connections_awg: AtomicI64,
    pub connections_vless: AtomicI64,
    pub connections_opera: AtomicI64,
    pub socks5_v2_sessions: AtomicI64,
    pub socks5_v1_downgrades: AtomicI64,
    pub socks5_fastpath_hits: AtomicI64,
    pub socks5_fallback_triggers: AtomicI64,
    pub connections_http_reject: AtomicI64,
    pub connections_passthrough: AtomicI64,
    pub connections_bad: AtomicI64,
    pub ws_errors: AtomicI64,
    pub bytes_up: AtomicI64,
    pub bytes_down: AtomicI64,
    pub pool_hits: AtomicI64,
    pub pool_misses: AtomicI64,
}

pub static STATS: Lazy<Stats> = Lazy::new(Stats::default);

impl Stats {
    pub fn summary(&self) -> String {
        let ph = self.pool_hits.load(Ordering::Relaxed);
        let pm = self.pool_misses.load(Ordering::Relaxed);
        format!(
            "total={} active={} ws={} cf={} masque={} awg={} vless={} opera={} v2={} v1_down={} fp={} fb={} bad={} err={} pool={}/{} up={} down={}",
            self.connections_total.load(Ordering::Relaxed),
            self.connections_active.load(Ordering::Relaxed),
            self.connections_ws.load(Ordering::Relaxed),
            self.connections_cfproxy.load(Ordering::Relaxed),
            self.connections_masque.load(Ordering::Relaxed),
            self.connections_awg.load(Ordering::Relaxed),
            self.connections_vless.load(Ordering::Relaxed),
            self.connections_opera.load(Ordering::Relaxed),
            self.socks5_v2_sessions.load(Ordering::Relaxed),
            self.socks5_v1_downgrades.load(Ordering::Relaxed),
            self.socks5_fastpath_hits.load(Ordering::Relaxed),
            self.socks5_fallback_triggers.load(Ordering::Relaxed),
            self.connections_bad.load(Ordering::Relaxed),
            self.ws_errors.load(Ordering::Relaxed),
            ph,
            ph + pm,
            human_bytes(self.bytes_up.load(Ordering::Relaxed)),
            human_bytes(self.bytes_down.load(Ordering::Relaxed)),
        )
    }

    pub fn summary_ru(&self) -> String {
        let mut parts = vec![format!(
            "акт:{}",
            self.connections_active.load(Ordering::Relaxed)
        )];
        let ws = self.connections_ws.load(Ordering::Relaxed);
        if ws > 0 {
            parts.push(format!("ws:{}", ws));
        }
        let cf = self.connections_cfproxy.load(Ordering::Relaxed);
        if cf > 0 {
            parts.push(format!("cf:{}", cf));
        }
        let masque = self.connections_masque.load(Ordering::Relaxed);
        if masque > 0 {
            parts.push(format!("masque:{}", masque));
        }
        let awg = self.connections_awg.load(Ordering::Relaxed);
        if awg > 0 {
            parts.push(format!("awg:{}", awg));
        }
        let vless = self.connections_vless.load(Ordering::Relaxed);
        if vless > 0 {
            parts.push(format!("vless:{}", vless));
        }
        let opera = self.connections_opera.load(Ordering::Relaxed);
        if opera > 0 {
            parts.push(format!("opera:{}", opera));
        }
        let v2 = self.socks5_v2_sessions.load(Ordering::Relaxed);
        if v2 > 0 {
            parts.push(format!("v2:{}", v2));
        }
        let v1_down = self.socks5_v1_downgrades.load(Ordering::Relaxed);
        if v1_down > 0 {
            parts.push(format!("v1_down:{}", v1_down));
        }
        let fp = self.socks5_fastpath_hits.load(Ordering::Relaxed);
        if fp > 0 {
            parts.push(format!("fp:{}", fp));
        }
        let fb = self.socks5_fallback_triggers.load(Ordering::Relaxed);
        if fb > 0 {
            parts.push(format!("fb:{}", fb));
        }
        let err = self.ws_errors.load(Ordering::Relaxed);
        if err > 0 {
            parts.push(format!("ош:{}", err));
        }
        parts.push(format!(
            "↑{} ↓{}",
            human_bytes(self.bytes_up.load(Ordering::Relaxed)),
            human_bytes(self.bytes_down.load(Ordering::Relaxed))
        ));
        parts.join(" | ")
    }

    pub fn reset(&self) {
        self.connections_total.store(0, Ordering::Relaxed);
        self.connections_active.store(0, Ordering::Relaxed);
        self.connections_ws.store(0, Ordering::Relaxed);
        self.connections_tcp_fallback.store(0, Ordering::Relaxed);
        self.connections_cfproxy.store(0, Ordering::Relaxed);
        self.connections_masque.store(0, Ordering::Relaxed);
        self.connections_awg.store(0, Ordering::Relaxed);
        self.connections_vless.store(0, Ordering::Relaxed);
        self.connections_opera.store(0, Ordering::Relaxed);
        self.socks5_v2_sessions.store(0, Ordering::Relaxed);
        self.socks5_v1_downgrades.store(0, Ordering::Relaxed);
        self.socks5_fastpath_hits.store(0, Ordering::Relaxed);
        self.socks5_fallback_triggers.store(0, Ordering::Relaxed);
        self.connections_http_reject.store(0, Ordering::Relaxed);
        self.connections_passthrough.store(0, Ordering::Relaxed);
        self.connections_bad.store(0, Ordering::Relaxed);
        self.ws_errors.store(0, Ordering::Relaxed);
        self.bytes_up.store(0, Ordering::Relaxed);
        self.bytes_down.store(0, Ordering::Relaxed);
        self.pool_hits.store(0, Ordering::Relaxed);
        self.pool_misses.store(0, Ordering::Relaxed);
    }
}

pub fn human_bytes(n: i64) -> String {
    let units = ["B", "KB", "MB", "GB", "TB"];
    let mut f = n as f64;
    for (i, u) in units.iter().enumerate() {
        if f.abs() < 1024.0 || i == units.len() - 1 {
            return format!("{:.1}{}", f, u);
        }
        f /= 1024.0;
    }
    format!("{:.1}TB", f)
}

// ---------------------------------------------------------------------------
// Logger (Android log + stderr)
// ---------------------------------------------------------------------------

#[cfg(target_os = "android")]
fn android_log_line(line: &str) {
    use std::ffi::CString;
    unsafe extern "C" {
        fn __android_log_print(prio: i32, tag: *const i8, fmt: *const i8, ...) -> i32;
    }
    const ANDROID_LOG_INFO: i32 = 4;
    if let (Ok(tag), Ok(fmt), Ok(msg)) = (
        CString::new("TgWsProxy"),
        CString::new("%s"),
        CString::new(line),
    ) {
        unsafe {
            __android_log_print(
                ANDROID_LOG_INFO,
                tag.as_ptr() as *const i8,
                fmt.as_ptr() as *const i8,
                msg.as_ptr() as *const i8,
            );
        }
    }
}

#[cfg(not(target_os = "android"))]
fn android_log_line(_line: &str) {}

fn emit(prefix: &str, msg: &str) {
    let line = format!("{}{}", prefix, msg);
    eprintln!("{}", line);
    android_log_line(&line);
}

pub fn log_info(msg: &str) {
    emit("", msg);
}
pub fn log_warn(msg: &str) {
    emit("[WARN] ", msg);
}
pub fn log_error(msg: &str) {
    emit("[ERROR] ", msg);
}
pub fn log_debug(msg: &str) {
    if LOG_VERBOSE.load(Ordering::Relaxed) {
        emit("[DEBUG] ", msg);
    }
}

#[macro_export]
macro_rules! linfo  { ($($a:tt)*) => { $crate::config::log_info(&format!($($a)*)) }; }
#[macro_export]
macro_rules! lwarn  { ($($a:tt)*) => { $crate::config::log_warn(&format!($($a)*)) }; }
#[macro_export]
macro_rules! lerror { ($($a:tt)*) => { $crate::config::log_error(&format!($($a)*)) }; }
#[macro_export]
macro_rules! ldebug { ($($a:tt)*) => { $crate::config::log_debug(&format!($($a)*)) }; }

pub fn init_logging(verbose: bool) {
    LOG_VERBOSE.store(verbose, Ordering::Relaxed);
}

pub fn now_unix_f64() -> f64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs_f64())
        .unwrap_or(0.0)
}

pub fn now_unix() -> i64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

// ---------------------------------------------------------------------------
// Opera VPN Upstream Proxy Configuration
// ---------------------------------------------------------------------------

#[derive(Clone, Debug)]
pub struct OperaVpnConfig {
    pub vless_enabled: bool,
    pub warp_enabled: bool,
    pub endpoint: String,
}

pub static OPERA_VPN: Lazy<RwLock<OperaVpnConfig>> = Lazy::new(|| {
    RwLock::new(OperaVpnConfig {
        vless_enabled: false,
        warp_enabled: false,
        endpoint: "77.111.247.139:443".to_string(),
    })
});

pub fn set_opera_vpn_config(vless_enabled: bool, warp_enabled: bool, endpoint: &str) {
    let mut cfg = OPERA_VPN.write();
    cfg.vless_enabled = vless_enabled;
    cfg.warp_enabled = warp_enabled;
    if !endpoint.trim().is_empty() {
        cfg.endpoint = endpoint.trim().to_string();
    }
    linfo!(
        "Opera VPN config updated: vless={}, warp={}, endpoint={}",
        cfg.vless_enabled,
        cfg.warp_enabled,
        cfg.endpoint
    );
}
