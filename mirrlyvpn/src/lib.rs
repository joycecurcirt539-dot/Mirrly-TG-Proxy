#[macro_use]
pub mod config;
pub mod awg;
pub mod dns;
pub mod generation_guard;
pub mod network_profile;
pub mod prober;
pub mod vpn;

use config::*;
use once_cell::sync::{Lazy, OnceCell};
use parking_lot::Mutex;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicU8, Ordering};
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;

static RUNTIME: OnceCell<Runtime> = OnceCell::new();

pub type ProtectCallback = extern "C" fn(c_int) -> c_int;
static PROTECT_SOCKET_CB: parking_lot::RwLock<Option<ProtectCallback>> =
    parking_lot::RwLock::new(None);

pub fn protect_socket_fd(fd: std::os::raw::c_int) -> bool {
    if let Some(cb) = *PROTECT_SOCKET_CB.read() {
        cb(fd) != 0
    } else {
        true
    }
}

pub fn is_protect_callback_registered() -> bool {
    PROTECT_SOCKET_CB.read().is_some()
}

struct VpnState {
    session_id: u64,
    handle: tokio::task::JoinHandle<()>,
    cancel_token: CancellationToken,
}

static VPN_STATE: OnceCell<Mutex<Option<VpnState>>> = OnceCell::new();
static LAST_VPN_ERROR: Lazy<parking_lot::RwLock<Option<String>>> =
    Lazy::new(|| parking_lot::RwLock::new(None));
static IS_VPN_RUNNING: AtomicBool = AtomicBool::new(false);
static NEXT_VPN_SESSION_ID: AtomicU64 = AtomicU64::new(1);
pub static BATTERY_QOS_LEVEL: AtomicU8 = AtomicU8::new(0);

fn vpn_state_cell() -> &'static Mutex<Option<VpnState>> {
    VPN_STATE.get_or_init(|| Mutex::new(None))
}

fn init_panic_hook() {
    static PANIC_HOOK_SET: OnceCell<()> = OnceCell::new();
    PANIC_HOOK_SET.get_or_init(|| {
        std::panic::set_hook(Box::new(|info| {
            crate::lerror!("MIRRLYVPN PANIC: {}", info);
        }));
    });
}

pub(crate) fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        init_panic_hook();

        let detected_cores = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(4);
        let worker_threads = detected_cores.clamp(4, 8);

        crate::linfo!(
            "MirrlyVPN Tokio multi-thread runtime initialized: {} worker threads (detected {} CPU cores)",
            worker_threads,
            detected_cores
        );

        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(worker_threads)
            .thread_name("mirrly-vpn-rt")
            .event_interval(31)
            .max_blocking_threads(32)
            .enable_all()
            .build()
            .expect("failed to build mirrlyvpn tokio runtime")
    })
}

fn cstr_to_string(p: *const c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    unsafe { CStr::from_ptr(p).to_string_lossy().into_owned() }
}

pub fn get_battery_qos_level() -> u8 {
    BATTERY_QOS_LEVEL.load(Ordering::Relaxed)
}

// ---------------------------------------------------------------------------
// Frozen C-ABI Exports (26 symbols matching VpnLibrary 1-to-1)
// ---------------------------------------------------------------------------

#[no_mangle]
pub unsafe extern "C" fn StartVpn(tun_fd: c_int, verbose: c_int) -> c_int {
    init_panic_hook();

    if tun_fd < 0 {
        let msg = format!("StartVpn: invalid tun_fd={}", tun_fd);
        crate::lerror!("{}", msg);
        *LAST_VPN_ERROR.write() = Some(msg);
        return -1;
    }

    let cell = vpn_state_cell();
    let mut guard = cell.lock();

    if guard.is_some() {
        return -1;
    }

    let is_verbose = verbose != 0;
    init_logging(is_verbose);

    let rt = runtime();
    let cancel_token = CancellationToken::new();
    let cancel_task = cancel_token.clone();
    let session_id = NEXT_VPN_SESSION_ID.fetch_add(1, Ordering::Relaxed);

    crate::STATS.reset();
    crate::awg::reset_awg_circuit_breaker();
    *LAST_VPN_ERROR.write() = None;
    IS_VPN_RUNNING.store(true, Ordering::SeqCst);

    let handle = rt.spawn(async move {
        let res = vpn::run_vpn(tun_fd, cancel_task).await;
        if let Err(ref e) = res {
            crate::lerror!("VPN fatal error: {}", e);
            *LAST_VPN_ERROR.write() = Some(e.clone());
        }
        let cell = vpn_state_cell();
        let mut guard = cell.lock();
        if guard.as_ref().map(|s| s.session_id) == Some(session_id) {
            IS_VPN_RUNNING.store(false, Ordering::SeqCst);
            let _ = guard.take();
        }
    });

    *guard = Some(VpnState {
        session_id,
        handle,
        cancel_token,
    });

    crate::linfo!("StartVpn: native L3 VPN started with tun_fd={}", tun_fd);
    0
}

#[no_mangle]
pub unsafe extern "C" fn StopVpn() -> c_int {
    IS_VPN_RUNNING.store(false, Ordering::SeqCst);
    vpn::IS_VPN_ESTABLISHED.store(false, Ordering::SeqCst);
    let cell = vpn_state_cell();
    let mut guard = cell.lock();

    if let Some(state) = guard.take() {
        crate::linfo!("StopVpn: stopping native L3 VPN");
        state.cancel_token.cancel();
        state.handle.abort();
        awg::invalidate_active_peer();

        let rt = runtime();
        rt.spawn(async move {
            let _ = state.handle.await;
            crate::linfo!("StopVpn: native L3 VPN background handle terminated");
        });

        crate::linfo!("StopVpn: native L3 VPN completely stopped");
        0
    } else {
        awg::invalidate_active_peer();
        0
    }
}

#[no_mangle]
pub unsafe extern "C" fn SetVpnUplinkMode(mode: c_int) -> c_int {
    vpn::set_vpn_uplink_mode(mode);
    0
}

#[no_mangle]
pub unsafe extern "C" fn GetVpnStatusJson() -> *mut c_char {
    let is_running = IS_VPN_RUNNING.load(Ordering::Relaxed);
    let is_established = vpn::is_vpn_established();
    let tls_enabled = crate::config::is_vpn_tls_enabled();
    let last_err = LAST_VPN_ERROR.read().clone().unwrap_or_default();
    let up = crate::STATS.vpn_bytes_up.load(Ordering::Relaxed);
    let down = crate::STATS.vpn_bytes_down.load(Ordering::Relaxed);
    let pkts_up = crate::STATS.vpn_packets_up.load(Ordering::Relaxed);
    let pkts_down = crate::STATS.vpn_packets_down.load(Ordering::Relaxed);
    let connections_awg = crate::STATS.connections_awg.load(Ordering::Relaxed);
    let awg_status = awg::get_awg_status();
    let uplink_mode = vpn::get_vpn_uplink_mode();
    let (awg_init_sent, awg_resp_received, awg_bytes_recv, cb_open, cb_failures, awg_ep) =
        awg::get_awg_diagnostics();

    let json = serde_json::json!({
        "is_running": is_running,
        "is_established": is_established,
        "tls_enabled": tls_enabled,
        "last_error": last_err,
        "bytes_up": up,
        "bytes_down": down,
        "packets_up": pkts_up,
        "packets_down": pkts_down,
        "connections_awg": connections_awg,
        "uplink_mode": uplink_mode.as_str(),
        "uplink_mode_id": uplink_mode as i32,
        "awg": awg_status,
        "awg_endpoint": awg_ep,
        "awg_init_sent": awg_init_sent,
        "awg_resp_received": awg_resp_received,
        "awg_bytes_received": awg_bytes_recv,
        "circuit_breaker_open": cb_open,
        "circuit_breaker_failures": cb_failures,
    });

    let s = CString::new(json.to_string()).unwrap_or_else(|_| CString::new("{}").unwrap());
    s.into_raw()
}

#[no_mangle]
pub extern "C" fn ResetVpnSockets() {
    generation_guard::invalidate_profile();
    awg::reset_awg_circuit_breaker();
    awg::invalidate_active_peer();
    dns::clear_dns_cache();
    vpn::notify_vpn_reconnect();
}

#[no_mangle]
pub extern "C" fn NotifyVpnReconnect() {
    ResetVpnSockets();
}

#[no_mangle]
pub unsafe extern "C" fn SetAwgConfigParams(
    c_endpoint: *const c_char,
    c_private_key_b64: *const c_char,
    c_public_key_b64: *const c_char,
    c_peer_pub_b64: *const c_char,
    c_client_ipv4: *const c_char,
    jc: c_int,
    jmin: c_int,
    jmax: c_int,
    s1: c_int,
    s2: c_int,
    h1: c_int,
    h2: c_int,
    h3: c_int,
    h4: c_int,
) {
    let endpoint = cstr_to_string(c_endpoint);
    let private_key_b64 = cstr_to_string(c_private_key_b64);
    let public_key_b64 = cstr_to_string(c_public_key_b64);
    let peer_pub_b64 = cstr_to_string(c_peer_pub_b64);
    let client_ipv4 = cstr_to_string(c_client_ipv4);
    generation_guard::change_config(|| {
        awg::set_awg_config(
            &endpoint,
            &private_key_b64,
            &public_key_b64,
            &peer_pub_b64,
            &client_ipv4,
            jc.max(0) as u32,
            jmin.max(0) as usize,
            jmax.max(0) as usize,
            s1.max(0) as usize,
            s2.max(0) as usize,
            h1.max(0) as u32,
            h2.max(0) as u32,
            h3.max(0) as u32,
            h4.max(0) as u32,
        )
    });
}

#[no_mangle]
pub unsafe extern "C" fn SetAwgConfig(c_ini: *const c_char) -> c_int {
    let ini_str = cstr_to_string(c_ini);
    if ini_str.is_empty() {
        return -1;
    }
    match generation_guard::change_config(|| awg::set_awg_config_ini(&ini_str)) {
        Ok(()) => 0,
        Err(e) => {
            crate::lerror!("SetAwgConfig (INI) error: {}", e);
            *LAST_VPN_ERROR.write() = Some(e);
            -1
        }
    }
}

#[no_mangle]
pub extern "C" fn SetAwgWarpMode() {
    generation_guard::change_config(|| {
        let mut cfg = awg::AWG_CONFIG.write();
        cfg.awg_params = awg::AwgParams::warp_recommended();
        cfg.profile_name = "Cloudflare WARP".to_string();
        cfg.fallback_endpoints = awg::CLOUDFLARE_WARP_ANYCAST_POOL
            .iter()
            .map(|&s| s.to_string())
            .collect();
        crate::linfo!(
            "AWG: preset WARP mode applied (profile='{}', Jc={}, Jmin={}, Jmax={}, fallbacks={})",
            cfg.profile_name,
            cfg.awg_params.jc,
            cfg.awg_params.jmin,
            cfg.awg_params.jmax,
            cfg.fallback_endpoints.len()
        );
        drop(cfg);
        awg::invalidate_active_peer();
    });
    awg::reset_awg_circuit_breaker();
}

#[no_mangle]
pub extern "C" fn SetAwgKeepalive(secs: c_int) {
    let ka = if secs > 0 { Some(secs as u16) } else { None };
    awg::set_awg_keepalive(ka);
}

#[no_mangle]
pub extern "C" fn GetAwgStatus() -> *mut c_char {
    let s = awg::get_awg_status();
    CString::new(s).unwrap_or_default().into_raw()
}

#[no_mangle]
pub unsafe extern "C" fn ProbeWarpEndpoints(
    endpoints_csv: *const c_char,
    protocol: c_int,
    timeout_ms: c_int,
) -> *mut c_char {
    init_panic_hook();
    let csv = cstr_to_string(endpoints_csv);
    let to_ms = if timeout_ms <= 0 { 600 } else { timeout_ms as u64 };
    let rt = runtime();
    let results = rt.block_on(async { prober::probe_warp_endpoints(&csv, protocol, to_ms).await });
    let s = serde_json::to_string(&results).unwrap_or_else(|_| "[]".to_string());
    CString::new(s).unwrap_or_default().into_raw()
}

#[no_mangle]
pub unsafe extern "C" fn ProbeVpnTransportHealth(
    domain_override: *const c_char,
    timeout_ms: c_int,
) -> *mut c_char {
    init_panic_hook();
    let domain = cstr_to_string(domain_override);
    let to_ms = if timeout_ms <= 0 { 2500 } else { timeout_ms as u64 };
    let rt = runtime();
    let res = rt.block_on(async { prober::probe_vpn_transport_health(&domain, to_ms).await });
    let json = serde_json::to_string(&res).unwrap_or_else(|_| "{}".to_string());
    CString::new(json).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "C" fn SetVpnTlsEnabled(enabled: c_int) {
    let flag = enabled != 0;
    generation_guard::change_config(|| {
        config::set_vpn_tls_enabled(flag);
        dns::clear_dns_cache();
    });
    crate::linfo!("SetVpnTlsEnabled: tls_enabled={}", flag);
}

#[no_mangle]
pub extern "C" fn IsVpnTlsEnabled() -> c_int {
    if config::is_vpn_tls_enabled() {
        1
    } else {
        0
    }
}

#[no_mangle]
pub unsafe extern "C" fn SetVpnProtectSocketCallback(cb: Option<ProtectCallback>) {
    *PROTECT_SOCKET_CB.write() = cb;
}

#[no_mangle]
pub extern "C" fn SetVpnNetworkGeneration(gen: i64) {
    if gen > 0 {
        network_profile::update_generation(gen as u64);
    }
}

#[no_mangle]
pub extern "C" fn SetVpnNetworkProfile(is_mobile: c_int) {
    network_profile::update_legacy_transport(is_mobile != 0);
}

#[no_mangle]
pub unsafe extern "C" fn SetVpnNetworkProfileJson(c_json: *const c_char) -> c_int {
    let json = cstr_to_string(c_json);
    match network_profile::set_profile_json(&json) {
        Ok(_) => 0,
        Err(error) => {
            crate::lerror!("SetVpnNetworkProfileJson error: {}", error);
            -1
        }
    }
}

#[no_mangle]
pub extern "C" fn SetVpnIpv6OnlyNetwork(is_ipv6_only: c_int) {
    let flag = is_ipv6_only != 0;
    config::set_ipv6_only_network(flag);
    crate::linfo!("SetVpnIpv6OnlyNetwork: ipv6_only={}", flag);
}

#[no_mangle]
pub extern "C" fn SetVpnBatteryQoSLevel(level: c_int) {
    let lvl = (level.clamp(0, 2)) as u8;
    BATTERY_QOS_LEVEL.store(lvl, Ordering::Relaxed);
    crate::linfo!("SetVpnBatteryQoSLevel: set to {}", lvl);
    awg::notify_active_peer_timer();
}

#[no_mangle]
pub extern "C" fn OnVpnScreenWakeup() {
    crate::linfo!("OnVpnScreenWakeup: waking active AWG peer timer");
    awg::notify_active_peer_timer();
}

#[no_mangle]
pub extern "C" fn SetVpnSocketBufferSizes(recv_size: c_int, send_size: c_int) -> c_int {
    config::set_vpn_socket_buffer_sizes(recv_size, send_size)
}

#[no_mangle]
pub extern "C" fn GetVpnStats() -> *mut c_char {
    let s = STATS.summary();
    CString::new(s).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "C" fn GetVpnLastError() -> *mut c_char {
    let msg = LAST_VPN_ERROR.read().clone().unwrap_or_default();
    CString::new(msg).unwrap_or_default().into_raw()
}

#[no_mangle]
pub unsafe extern "C" fn FreeVpnString(p: *mut c_char) {
    if !p.is_null() {
        let _ = CString::from_raw(p);
    }
}
