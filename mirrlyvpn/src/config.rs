use once_cell::sync::Lazy;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicI64, Ordering};

// ---------------------------------------------------------------------------
// Network & Transport Configuration
// ---------------------------------------------------------------------------

pub static LOG_VERBOSE: AtomicBool = AtomicBool::new(false);
pub static MOBILE_NETWORK: AtomicBool = AtomicBool::new(false);
pub static IPV6_ONLY_NETWORK: AtomicBool = AtomicBool::new(false);
pub static VPN_TLS_ENABLED: AtomicBool = AtomicBool::new(true);

#[inline]
pub fn is_ipv6_only_network() -> bool {
    IPV6_ONLY_NETWORK.load(Ordering::Relaxed)
}

#[inline]
pub fn set_ipv6_only_network(enabled: bool) {
    IPV6_ONLY_NETWORK.store(enabled, Ordering::Relaxed);
}

#[inline]
pub fn is_vpn_tls_enabled() -> bool {
    VPN_TLS_ENABLED.load(Ordering::Relaxed)
}

#[inline]
pub fn set_vpn_tls_enabled(enabled: bool) {
    VPN_TLS_ENABLED.store(enabled, Ordering::Relaxed);
}

// ---------------------------------------------------------------------------
// UDP Tunnel Socket Buffer Presets & Runtime Overrides
// ---------------------------------------------------------------------------

pub const MIN_SOCKET_BUFFER: i32 = 32 * 1024;
pub const DEFAULT_MAX_SOCKET_BUFFER: i32 = 2 * 1024 * 1024;
pub const HIGH_THROUGHPUT_MAX_SOCKET_BUFFER: i32 = 8 * 1024 * 1024;

/// Dedicated WireGuard / AmneziaWG UDP tunnel socket buffer presets
pub const DEFAULT_UDP_TUNNEL_RECV_BUF: usize = 4 * 1024 * 1024; // 4 MiB (cellular default)
pub const HIGH_THROUGHPUT_UDP_TUNNEL_RECV_BUF: usize = 8 * 1024 * 1024; // 8 MiB (Wi-Fi / 5G)
pub const DEFAULT_UDP_TUNNEL_SEND_BUF: usize = 2 * 1024 * 1024; // 2 MiB
pub const HIGH_THROUGHPUT_UDP_TUNNEL_SEND_BUF: usize = 4 * 1024 * 1024; // 4 MiB

/// Optional explicit socket buffer overrides (0 = use preset for current network profile)
pub static VPN_RECV_BUF_OVERRIDE: AtomicI32 = AtomicI32::new(0);
pub static VPN_SEND_BUF_OVERRIDE: AtomicI32 = AtomicI32::new(0);

pub fn set_vpn_socket_buffer_sizes(recv_size: i32, send_size: i32) -> i32 {
    let clamped_recv = if recv_size <= 0 {
        0
    } else {
        recv_size.clamp(MIN_SOCKET_BUFFER, HIGH_THROUGHPUT_MAX_SOCKET_BUFFER)
    };
    let clamped_send = if send_size <= 0 {
        0
    } else {
        send_size.clamp(MIN_SOCKET_BUFFER, HIGH_THROUGHPUT_MAX_SOCKET_BUFFER)
    };
    VPN_RECV_BUF_OVERRIDE.store(clamped_recv, Ordering::Relaxed);
    VPN_SEND_BUF_OVERRIDE.store(clamped_send, Ordering::Relaxed);
    crate::linfo!(
        "SetVpnSocketBufferSizes: requested_recv={} requested_send={} effective_recv={} effective_send={}",
        recv_size,
        send_size,
        clamped_recv,
        clamped_send
    );
    0
}

pub fn effective_udp_tunnel_buffer_sizes() -> (usize, usize) {
    let is_mobile = MOBILE_NETWORK.load(Ordering::Relaxed);
    let default_recv = if is_mobile {
        DEFAULT_UDP_TUNNEL_RECV_BUF
    } else {
        HIGH_THROUGHPUT_UDP_TUNNEL_RECV_BUF
    };
    let default_send = if is_mobile {
        DEFAULT_UDP_TUNNEL_SEND_BUF
    } else {
        HIGH_THROUGHPUT_UDP_TUNNEL_SEND_BUF
    };

    let ov_recv = VPN_RECV_BUF_OVERRIDE.load(Ordering::Relaxed);
    let ov_send = VPN_SEND_BUF_OVERRIDE.load(Ordering::Relaxed);

    let rcvbuf = if ov_recv > 0 { ov_recv as usize } else { default_recv };
    let sndbuf = if ov_send > 0 { ov_send as usize } else { default_send };
    (rcvbuf, sndbuf)
}

// ---------------------------------------------------------------------------
// VPN Statistics
// ---------------------------------------------------------------------------

#[derive(Default)]
pub struct Stats {
    pub connections_awg: AtomicI64,
    pub vpn_bytes_up: AtomicI64,
    pub vpn_bytes_down: AtomicI64,
    pub vpn_packets_up: AtomicI64,
    pub vpn_packets_down: AtomicI64,
}

pub static STATS: Lazy<Stats> = Lazy::new(Stats::default);

impl Stats {
    pub fn add_vpn_bytes_up(&self, n: i64) {
        self.vpn_bytes_up.fetch_add(n, Ordering::Relaxed);
        self.vpn_packets_up.fetch_add(1, Ordering::Relaxed);
    }

    pub fn add_vpn_bytes_down(&self, n: i64) {
        self.vpn_bytes_down.fetch_add(n, Ordering::Relaxed);
        self.vpn_packets_down.fetch_add(1, Ordering::Relaxed);
    }

    pub fn summary(&self) -> String {
        format!(
            "awg={} pkts_up={} pkts_down={} up={} down={}",
            self.connections_awg.load(Ordering::Relaxed),
            self.vpn_packets_up.load(Ordering::Relaxed),
            self.vpn_packets_down.load(Ordering::Relaxed),
            human_bytes(self.vpn_bytes_up.load(Ordering::Relaxed)),
            human_bytes(self.vpn_bytes_down.load(Ordering::Relaxed)),
        )
    }

    pub fn reset(&self) {
        self.connections_awg.store(0, Ordering::Relaxed);
        self.vpn_bytes_up.store(0, Ordering::Relaxed);
        self.vpn_bytes_down.store(0, Ordering::Relaxed);
        self.vpn_packets_up.store(0, Ordering::Relaxed);
        self.vpn_packets_down.store(0, Ordering::Relaxed);
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
// Logger (Android logcat + stderr)
// ---------------------------------------------------------------------------

#[cfg(target_os = "android")]
fn android_log_line(line: &str) {
    use std::ffi::CString;
    use std::os::raw::c_char;
    extern "C" {
        fn __android_log_print(prio: i32, tag: *const c_char, fmt: *const c_char, ...) -> i32;
    }
    const ANDROID_LOG_INFO: i32 = 4;
    if let (Ok(tag), Ok(fmt), Ok(msg)) = (
        CString::new("MirrlyVpn"),
        CString::new("%s"),
        CString::new(line),
    ) {
        unsafe {
            __android_log_print(
                ANDROID_LOG_INFO,
                tag.as_ptr(),
                fmt.as_ptr(),
                msg.as_ptr(),
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

pub fn now_unix() -> i64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}
