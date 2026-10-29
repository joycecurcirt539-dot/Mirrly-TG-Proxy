#[macro_use]
pub mod config;
pub mod awg;
pub mod balancer;
pub mod budget;
pub mod bridge;
pub mod cfproxy;
pub mod crypto;
pub mod dns;
pub mod faketls;
pub mod generation_guard;
pub mod masque;
pub mod network_profile;
pub mod node_independence;
pub mod prober;
pub mod proxy;
pub mod recovery;
pub mod socks5;
pub mod supervisor;
pub mod tls_observability;
pub mod vpn;
pub mod ws;

pub use cfproxy::ProtoScope;

use config::*;
use once_cell::sync::{Lazy, OnceCell};
use parking_lot::Mutex;
use proxy::{run_proxy, WsPool};
use socks5::run_socks5_server;
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::Arc;
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;

static RUNTIME: OnceCell<Runtime> = OnceCell::new();

struct ProxyState {
    pool: Option<Arc<WsPool>>,
    handle: tokio::task::JoinHandle<()>,
    cancel_tasks: CancellationToken,
    cancel_sessions: Arc<parking_lot::RwLock<CancellationToken>>,
}

static STATE: OnceCell<Mutex<Option<ProxyState>>> = OnceCell::new();

fn state_cell() -> &'static Mutex<Option<ProxyState>> {
    STATE.get_or_init(|| Mutex::new(None))
}

pub type ProtectCallback = extern "C" fn(c_int) -> c_int;
static PROTECT_SOCKET_CB: parking_lot::RwLock<Option<ProtectCallback>> = parking_lot::RwLock::new(None);

#[no_mangle]
pub unsafe extern "C" fn SetProtectSocketCallback(cb: Option<ProtectCallback>) {
    *PROTECT_SOCKET_CB.write() = cb;
}

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

pub(crate) fn bind_reuse_tcp(addr: &str) -> std::io::Result<tokio::net::TcpListener> {
    let sock_addr: std::net::SocketAddr = addr
        .parse()
        .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidInput, e))?;
    let domain = if sock_addr.is_ipv6() {
        socket2::Domain::IPV6
    } else {
        socket2::Domain::IPV4
    };
    let socket = socket2::Socket::new(domain, socket2::Type::STREAM, Some(socket2::Protocol::TCP))?;
    let _ = socket.set_reuse_address(true);
    #[cfg(all(unix, not(target_os = "solaris"), not(target_os = "illumos")))]
    {
        let _ = socket.set_reuse_port(true);
    }
    socket.set_nonblocking(true)?;
    socket.bind(&sock_addr.into())?;
    socket.listen(1024)?;
    let std_listener: std::net::TcpListener = socket.into();
    tokio::net::TcpListener::from_std(std_listener)
}

struct VpnState {
    handle: tokio::task::JoinHandle<()>,
    cancel_token: CancellationToken,
}

static VPN_STATE: OnceCell<Mutex<Option<VpnState>>> = OnceCell::new();
static LAST_VPN_ERROR: Lazy<parking_lot::RwLock<Option<String>>> = Lazy::new(|| parking_lot::RwLock::new(None));
static IS_VPN_RUNNING: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

fn vpn_state_cell() -> &'static Mutex<Option<VpnState>> {
    VPN_STATE.get_or_init(|| Mutex::new(None))
}

fn init_crypto_and_panic_hook() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    static PANIC_HOOK_SET: OnceCell<()> = OnceCell::new();
    PANIC_HOOK_SET.get_or_init(|| {
        std::panic::set_hook(Box::new(|info| {
            crate::lerror!("RUST ENGINE PANIC: {}", info);
        }));
    });
}

fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        init_crypto_and_panic_hook();
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .thread_name("mirrly-rt")
            .enable_all()
            .build()
            .expect("failed to build global tokio runtime")
    })
}

fn cstr_to_string(p: *const c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    unsafe { CStr::from_ptr(p).to_string_lossy().into_owned() }
}

// ---------------------------------------------------------------------------
// Exports
// ---------------------------------------------------------------------------

#[no_mangle]
pub unsafe extern "C" fn StartProxy(
    c_host: *const c_char,
    port: c_int,
    c_dc_ips: *const c_char,
    c_secret: *const c_char,
    verbose: c_int,
) -> c_int {
    init_crypto_and_panic_hook();

    let cell = state_cell();
    let mut guard = cell.lock();

    if let Some(old_state) = guard.take() {
        crate::linfo!("StartProxy: taking down previous active state before restart");
        old_state.cancel_tasks.cancel();
        old_state.handle.abort();
    }

    generation_guard::change_config(|| {});

    let host = cstr_to_string(c_host);
    let go_port = port as u16;
    let _dc_ips_str = cstr_to_string(c_dc_ips);
    let secret_str = cstr_to_string(c_secret);
    let is_verbose = verbose != 0;

    init_logging(is_verbose);
    cfproxy::clear_doh_cache();
    recovery::reset();
    balancer::BALANCER.write().reset_ranking();
    node_independence::NODE_INDEPENDENCE_TRACKER.write().reset();
    tls_observability::TLS_TRACKER.write().reset();

    if secret_str.len() == 32 {
        if hex::decode(&secret_str).is_ok() {
            *PROXY_SECRET.write() = secret_str.clone();
        }
    }

    cfproxy::init_cfproxy_domains();

    let dc_opt_map: HashMap<i32, String> = HashMap::new();
    *DC_OPT.write() = dc_opt_map.clone();

    let rt = runtime();
    let cancel_tasks = CancellationToken::new();
    let cancel_sessions = Arc::new(parking_lot::RwLock::new(cancel_tasks.child_token()));
    let pool = Arc::new(WsPool::new(cancel_tasks.clone()));

    let (tx, rx) = std::sync::mpsc::channel::<Result<(), String>>();

    let pool_task = pool.clone();
    let host_task = host.clone();
    let map_task = dc_opt_map.clone();
    let cancel_root = cancel_tasks.clone();
    let cancel_sessions_task = cancel_sessions.clone();

    let handle = rt.spawn(async move {
        let addr = format!("{}:{}", host_task, go_port);
        match bind_reuse_tcp(&addr) {
            Ok(listener) => {
                let _ = tx.send(Ok(()));
                if let Err(e) = run_proxy(
                    pool_task,
                    host_task,
                    go_port,
                    map_task,
                    cancel_root,
                    cancel_sessions_task,
                    listener,
                )
                .await
                {
                    crate::lerror!("listen on {}: {}", addr, e);
                }
            }
            Err(e) => {
                let _ = tx.send(Err(format!("listen on {}: {}", addr, e)));
            }
        }
    });

    match rx.recv() {
        Ok(Ok(())) => {}
        _ => {
            handle.abort();
            return -3;
        }
    }

    let cancel_balancer = cancel_tasks.clone();
    rt.spawn(async move {
        cfproxy::start_background_balancer_loop(cancel_balancer).await;
    });

    *guard = Some(ProxyState {
        pool: Some(pool),
        handle,
        cancel_tasks,
        cancel_sessions,
    });

    0
}

#[no_mangle]
pub unsafe extern "C" fn StartSocks5Proxy(
    c_host: *const c_char,
    port: c_int,
    verbose: c_int,
) -> c_int {
    init_crypto_and_panic_hook();

    let cell = state_cell();
    let mut guard = cell.lock();

    if let Some(old_state) = guard.take() {
        crate::linfo!("StartSocks5Proxy: taking down previous active state before restart");
        old_state.cancel_tasks.cancel();
        old_state.handle.abort();
    }

    generation_guard::change_config(|| {});

    let host = cstr_to_string(c_host);
    let go_port = port as u16;
    let is_verbose = verbose != 0;

    init_logging(is_verbose);
    cfproxy::clear_doh_cache();
    recovery::reset();
    *LAST_SOCKS5_WORKER.write() = String::new();
    node_independence::NODE_INDEPENDENCE_TRACKER.write().reset();
    tls_observability::TLS_TRACKER.write().reset();
    supervisor::ROUTE_SUPERVISOR.set_intent(crate::masque::UPLINK_WORKER, 1, false);

    let rt = runtime();
    let cancel_tasks = CancellationToken::new();
    let cancel_sessions = Arc::new(parking_lot::RwLock::new(cancel_tasks.child_token()));

    let (tx, rx) = std::sync::mpsc::channel::<Result<(), String>>();

    let host_task = host.clone();
    let cancel_root = cancel_tasks.clone();
    let cancel_sessions_task = cancel_sessions.clone();

    let handle = rt.spawn(async move {
        let addr = format!("{}:{}", host_task, go_port);
        match bind_reuse_tcp(&addr) {
            Ok(listener) => {
                let _ = tx.send(Ok(()));
                if let Err(e) = run_socks5_server(
                    host_task,
                    go_port,
                    cancel_root,
                    cancel_sessions_task,
                    listener,
                )
                .await
                {
                    crate::lerror!("listen socks5 on {}: {}", addr, e);
                }
            }
            Err(e) => {
                let _ = tx.send(Err(format!("listen socks5 on {}: {}", addr, e)));
            }
        }
    });

    match rx.recv() {
        Ok(Ok(())) => {}
        _ => {
            handle.abort();
            return -3;
        }
    }

    *guard = Some(ProxyState {
        pool: None,
        handle,
        cancel_tasks,
        cancel_sessions,
    });

    0
}

#[no_mangle]
pub extern "C" fn StopProxy() -> c_int {
    let cell = state_cell();
    let mut guard = cell.lock();

    let state = match guard.take() {
        Some(s) => s,
        None => return 0,
    };

    generation_guard::change_config(|| {});

    crate::linfo!("StopProxy: cancelling all tasks");
    state.cancel_tasks.cancel();

    // Keep STATE locked until the old listener and pool finish shutting down.
    // Detached cleanup allowed a new proxy to start over the old instance.
    let rt = runtime();
    let pool_opt = state.pool;
    let handle = state.handle;
    handle.abort();
    rt.spawn(async move {
        let _ = handle.await;
        if let Some(pool) = pool_opt {
            pool.close_all().await;
        }
    });

    STATS.reset();
    WS_BLACKLIST.write().clear();
    DC_FAIL_UNTIL.write().clear();
    cfproxy::clear_doh_cache();
    recovery::reset();
    balancer::BALANCER.write().reset_ranking();
    node_independence::NODE_INDEPENDENCE_TRACKER.write().reset();
    tls_observability::TLS_TRACKER.write().reset();
    *LAST_SOCKS5_WORKER.write() = String::new();
    masque::reset_active_cascade_stage();

    crate::linfo!("StopProxy: stopped successfully");
    0
}

#[no_mangle]
pub unsafe extern "C" fn StartVpn(tun_fd: c_int, verbose: c_int) -> c_int {
    init_crypto_and_panic_hook();

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

    crate::awg::reset_awg_circuit_breaker();
    *LAST_VPN_ERROR.write() = None;
    IS_VPN_RUNNING.store(true, Ordering::SeqCst);

    let handle = rt.spawn(async move {
        let res = vpn::run_vpn(tun_fd, cancel_task).await;
        IS_VPN_RUNNING.store(false, Ordering::SeqCst);
        if let Err(ref e) = res {
            crate::lerror!("VPN fatal error: {}", e);
            *LAST_VPN_ERROR.write() = Some(e.clone());
        }
        let cell = vpn_state_cell();
        let _ = cell.lock().take();
    });

    *guard = Some(VpnState {
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

        let rt = runtime();
        rt.spawn(async move {
            let _ = state.handle.await;
            crate::linfo!("StopVpn: native L3 VPN background handle terminated");
        });

        crate::linfo!("StopVpn: native L3 VPN completely stopped");
        0
    } else {
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
    let last_err = LAST_VPN_ERROR.read().clone().unwrap_or_default();
    let up = crate::STATS.vpn_bytes_up.load(Ordering::Relaxed);
    let down = crate::STATS.vpn_bytes_down.load(Ordering::Relaxed);
    let pkts_up = crate::STATS.vpn_packets_up.load(Ordering::Relaxed);
    let pkts_down = crate::STATS.vpn_packets_down.load(Ordering::Relaxed);
    let awg_status = awg::get_awg_status();
    let uplink_mode = vpn::get_vpn_uplink_mode();

    let json = serde_json::json!({
        "is_running": is_running,
        "is_established": is_established,
        "last_error": last_err,
        "bytes_up": up,
        "bytes_down": down,
        "packets_up": pkts_up,
        "packets_down": pkts_down,
        "uplink_mode": uplink_mode.as_str(),
        "uplink_mode_id": uplink_mode as i32,
        "awg": awg_status
    });

    let s = CString::new(json.to_string()).unwrap_or_else(|_| CString::new("{}").unwrap());
    s.into_raw()
}

#[no_mangle]
pub extern "C" fn ResetVpnSockets() {
    awg::reset_awg_circuit_breaker();
    awg::invalidate_active_peer();
    masque::reset_quic_endpoint();
    vpn::notify_vpn_reconnect();
}

#[no_mangle]
pub extern "C" fn ResetNetworkSockets() {
    reset_network_sockets_for_reason("external/network-generation reset");
}

#[no_mangle]
pub extern "C" fn EmergencyKillAllSockets() {
    reset_network_sockets_for_reason("emergency kill all sockets");
}

pub(crate) fn reset_network_sockets_for_reason(reason: &str) {
    let cell = state_cell();
    let guard = cell.lock();
    generation_guard::invalidate_profile();
    awg::invalidate_active_peer();
    masque::reset_quic_endpoint();
    recovery::reset();
    vpn::notify_vpn_reconnect();
    reset_network_sockets_for_reason_locked(guard.as_ref(), reason);
}

pub(crate) fn reset_network_routing_for_reason_if_current(
    expected: generation_guard::GenerationStamp,
    reason: &str,
) -> bool {
    let cell = state_cell();
    let guard = cell.lock();
    generation_guard::invalidate_profile_if_current(expected, || {
        if let Some(_state) = guard.as_ref() {
            crate::linfo!(
                "ResetNetworkRouting: rotating candidate endpoints and DoH cache reason={} (keeping active data sessions intact)",
                reason
            );
            DC_FAIL_UNTIL.write().clear();
            WS_BLACKLIST.write().clear();
            balancer::BALANCER.write().reset_ranking();
            node_independence::NODE_INDEPENDENCE_TRACKER.write().reset();
        }
    })
    .is_some()
}

fn reset_network_sockets_for_reason_locked(state: Option<&ProxyState>, reason: &str) {
    if let Some(state) = state {
        crate::linfo!(
            "ResetNetworkSockets: last-level reset reason={} (preserving DoH cache, active worker, and 429 backoff)",
            reason
        );

        // 1. Atomically rotate the sessions token and cancel the old token (cancels all active bridge sessions)
        let old_token = {
            let mut lock = state.cancel_sessions.write();
            let old = lock.clone();
            *lock = state.cancel_tasks.child_token();
            old
        };
        old_token.cancel();

        // 2. Clear interface-specific failures and reset ranking for the new network.
        // NOTE: DOH_CACHE and LAST_SOCKS5_WORKER are deliberately preserved across network handovers (Wi-Fi <-> LTE).
        // Cloudflare Anycast edge IPs and working workers are identical globally; preserving them eliminates 2.2–3.5s cold-start reconnect delays.
        // HTTP 429 backoff is deliberately preserved: a radio handover does not make
        // a rate-limited Worker healthy and clearing it creates a reconnect storm.
        DC_FAIL_UNTIL.write().clear();
        WS_BLACKLIST.write().clear();
        balancer::BALANCER.write().reset_ranking();
        node_independence::NODE_INDEPENDENCE_TRACKER.write().reset();
        supervisor::ROUTE_SUPERVISOR.transition_to_unchecked(
            supervisor::RouteKind::None,
            "masque_reset_stage",
        );

        // 3. Eager background racing removed: the first real request ranks its winner on demand.

        // 4. If pool exists (MTProto mode), cleanly reset pool epoch, close all idle sockets and warmup on the new network interface
        if let Some(ref pool) = state.pool {
            let p = pool.clone();
            let rt = runtime();
            rt.spawn(async move {
                let map = DC_OPT.read().clone();
                p.reset_and_warmup(&map).await;
            });
        }

        // 5. Reset QUIC MASQUE endpoint, AWG active peer, and SOCKS5 warm pool to force clean handshakes on the new network interface
        masque::reset_quic_endpoint();
        awg::invalidate_active_peer();
        socks5::clear_socks5_warm_pool();
        let user_domain = CFPROXY.read().user_domain.clone();
        if !user_domain.is_empty() {
            socks5::trigger_warm_socks5_pool_prime(user_domain, state.cancel_tasks.clone());
        }
        recovery::reset();
    }
}

/// Enter offline dormancy without launching DNS, CDN races or pool warmup.
/// A later ResetNetworkSockets performs the single recovery cycle.
#[no_mangle]
pub extern "C" fn SuspendNetworkSockets() {
    let cell = state_cell();
    let guard = cell.lock();
    if let Some(state) = guard.as_ref() {
        crate::linfo!("SuspendNetworkSockets: cancelling sessions without reconnect work");
        let old_token = {
            let mut lock = state.cancel_sessions.write();
            let old = lock.clone();
            *lock = state.cancel_tasks.child_token();
            old
        };
        old_token.cancel();

        if let Some(ref pool) = state.pool {
            let pool = pool.clone();
            runtime().spawn(async move {
                pool.reset().await;
            });
        }
        masque::reset_quic_endpoint();
    }
}

/// Add missing idle MTProto sockets without rotating the session token or
/// closing established bridges. Also primes SOCKS5 warm pool if SOCKS5 is active.
#[no_mangle]
pub extern "C" fn WarmupWsPool() {
    let cell = state_cell();
    let guard = cell.lock();
    if let Some(pool) = guard.as_ref().and_then(|state| state.pool.clone()) {
        runtime().spawn(async move {
            let map = DC_OPT.read().clone();
            pool.prewarm(&map).await;
        });
    }
    let user_domain = CFPROXY.read().user_domain.clone();
    if !user_domain.is_empty() {
        if let Some(state) = guard.as_ref() {
            socks5::trigger_warm_socks5_pool_prime(user_domain, state.cancel_tasks.clone());
        }
    }
}

/// Predictive screen wakeup hook.
/// Clears transient cooldowns caused by phone sleep, triggers MTProto prewarm and wakes timers.
#[no_mangle]
pub extern "C" fn OnScreenWakeup() {
    crate::linfo!("OnScreenWakeup: triggering predictive wake and preserving endpoint rate-limit quarantine");
    cfproxy::clear_all_recovery_cooldowns();

    let cell = state_cell();
    let guard = cell.lock();
    if let Some(pool) = guard.as_ref().and_then(|state| state.pool.clone()) {
        runtime().spawn(async move {
            let map = DC_OPT.read().clone();
            pool.prewarm(&map).await;
        });
    }

    awg::notify_active_peer_timer();
}

#[no_mangle]
pub extern "C" fn SetMtprotoStandbyPerActiveSlot(size: c_int) -> c_int {
    let requested = size.clamp(config::MTPROTO_MIN_STANDBY, config::MTPROTO_MAX_STANDBY);
    MTPROTO_STANDBY_PER_ACTIVE_SLOT_REQUESTED.store(requested, Ordering::Relaxed);
    let effective = config::effective_mtproto_standby(
        requested,
        config::MOBILE_NETWORK.load(Ordering::Relaxed),
    ) as c_int;
    crate::linfo!(
        "SetMtprotoStandbyPerActiveSlot: requested={} effective={} is_mobile={}",
        requested,
        effective,
        config::MOBILE_NETWORK.load(Ordering::Relaxed)
    );
    effective
}

#[no_mangle]
pub extern "C" fn SetPoolSize(size: c_int) -> c_int {
    let standby = if size <= config::MTPROTO_MAX_STANDBY {
        size
    } else {
        (size / 4).clamp(config::MTPROTO_MIN_STANDBY, config::MTPROTO_MAX_STANDBY)
    };
    let effective = SetMtprotoStandbyPerActiveSlot(standby);
    crate::linfo!(
        "SetPoolSize: requested={} mapped_standby={} effective={}",
        size,
        standby,
        effective
    );
    effective
}

#[no_mangle]
pub extern "C" fn set_pool_size(size: c_int) -> c_int {
    SetPoolSize(size)
}

#[no_mangle]
pub extern "C" fn SetIpv6OnlyNetwork(is_ipv6_only: c_int) {
    let flag = is_ipv6_only != 0;
    crate::recovery::set_ipv6_only_network(flag);
    crate::linfo!("SetIpv6OnlyNetwork: ipv6_only={}", flag);
}

#[no_mangle]
pub extern "C" fn GetTransportPoolStatusJson() -> *mut c_char {
    let cell = state_cell();
    let guard = cell.lock();
    let transport = match guard.as_ref() {
        Some(state) => {
            if state.pool.is_some() {
                "mtproto"
            } else {
                "socks5"
            }
        }
        None => "idle",
    };
    let status = config::get_transport_pool_status(transport);
    let json = serde_json::to_string(&status).unwrap_or_else(|_| "{}".to_string());
    CString::new(json).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "C" fn GetWarmPoolStatusJson() -> *mut c_char {
    let status = socks5::get_socks5_warm_pool_status();
    let json = serde_json::to_string(&status).unwrap_or_else(|_| "{}".to_string());
    CString::new(json).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "C" fn SetSocketBufferSizes(recv_size: c_int, send_size: c_int) -> c_int {
    let requested_recv = recv_size.max(0);
    let requested_send = send_size.max(0);
    let recv = if requested_recv == 0 {
        0
    } else {
        requested_recv.clamp(config::MIN_SOCKET_BUFFER, config::MAX_SOCKET_BUFFER)
    };
    let send = if requested_send == 0 {
        0
    } else {
        requested_send.clamp(config::MIN_SOCKET_BUFFER, config::MAX_SOCKET_BUFFER)
    };
    REQUESTED_RECV_BUF.store(requested_recv, Ordering::Relaxed);
    REQUESTED_SEND_BUF.store(requested_send, Ordering::Relaxed);
    RECV_BUF.store(recv, Ordering::Relaxed);
    SEND_BUF.store(send, Ordering::Relaxed);
    crate::linfo!(
        "SetSocketBufferSizes: requested_recv={} requested_send={} effective_recv={} effective_send={} autotune_baseline={}",
        requested_recv,
        requested_send,
        recv,
        send,
        recv == 0 && send == 0
    );
    0
}

#[no_mangle]
pub extern "C" fn GetSocketBufferStatusJson() -> *mut c_char {
    let status = config::get_socket_buffer_status();
    let json = serde_json::to_string(&status).unwrap_or_else(|_| "{}".to_string());
    CString::new(json).unwrap_or_default().into_raw()
}

#[no_mangle]
pub unsafe extern "C" fn GetLastOsSocketBufferSizes(
    recv_out: *mut c_int,
    send_out: *mut c_int,
) -> c_int {
    if recv_out.is_null() || send_out.is_null() {
        return -1;
    }
    unsafe {
        *recv_out = config::LAST_OS_RECV_BUF.load(Ordering::Relaxed);
        *send_out = config::LAST_OS_SEND_BUF.load(Ordering::Relaxed);
    }
    0
}

#[no_mangle]
pub extern "C" fn SetNetworkProfile(is_mobile: c_int) {
    network_profile::update_legacy_transport(is_mobile != 0);
}

#[no_mangle]
pub extern "C" fn SetDialThrottlingEnabled(enabled: c_int) {
    config::set_dial_throttling_enabled(enabled != 0);
}

#[no_mangle]
pub extern "C" fn IsDialThrottlingEnabled() -> c_int {
    if config::is_dial_throttling_enabled() { 1 } else { 0 }
}

#[no_mangle]
pub unsafe extern "C" fn SetNetworkProfileJson(c_json: *const c_char) -> c_int {
    let json = cstr_to_string(c_json);
    match network_profile::set_profile_json(&json) {
        Ok(_) => 0,
        Err(error) => {
            crate::lerror!("SetNetworkProfileJson error: {}", error);
            -1
        }
    }
}

#[no_mangle]
pub extern "C" fn GetNetworkProfileJson() -> *mut c_char {
    CString::new(network_profile::get_profile_json())
        .unwrap_or_default()
        .into_raw()
}

pub static BATTERY_QOS_LEVEL: AtomicU8 = AtomicU8::new(0);

#[no_mangle]
pub extern "C" fn SetBatteryQoSLevel(level: c_int) {
    let lvl = (level.clamp(0, 2)) as u8;
    BATTERY_QOS_LEVEL.store(lvl, Ordering::Relaxed);
    crate::linfo!("SetBatteryQoSLevel: set to {}", lvl);
    awg::notify_active_peer_timer();
}

pub fn get_battery_qos_level() -> u8 {
    BATTERY_QOS_LEVEL.load(Ordering::Relaxed)
}

#[no_mangle]
pub extern "C" fn SetTcpNoDelay(enabled: c_int) {
    let nodelay = enabled != 0;
    TCP_NODELAY.store(nodelay, Ordering::Relaxed);
    crate::linfo!("SetTcpNoDelay: set to {}", nodelay);
}

#[no_mangle]
pub unsafe extern "C" fn SetCfProxyCacheDir(c_cache_dir: *const c_char) {
    let dir = cstr_to_string(c_cache_dir);
    generation_guard::change_config(|| {
        CFPROXY.write().cache_dir = dir.trim().to_string();
    });
}

#[no_mangle]
pub unsafe extern "C" fn SetCfProxyConfig(enabled: c_int, c_user_domain: *const c_char) {
    let user_domain = cstr_to_string(c_user_domain);
    let _is_cdn = user_domain.trim().is_empty() && (enabled != 0);
    generation_guard::change_config(|| {
        CFPROXY_ENABLED.store(enabled != 0, Ordering::Relaxed);
        *LAST_SOCKS5_WORKER.write() = String::new();
        let mut cfg = CFPROXY.write();
        cfg.user_domain = user_domain.clone();
        cfg.active = user_domain;
    });

    let pool_opt = state_cell().lock().as_ref().and_then(|s| s.pool.clone());
    if let Some(pool) = pool_opt {
        let dc_opt = DC_OPT.read().clone();
        let rt = runtime();
        rt.spawn(async move {
            pool.reset().await;
            if enabled != 0 {
                pool.prewarm(&dc_opt).await;
            }
        });
    }
}

#[no_mangle]
pub extern "C" fn SetTlsRecordPaddingEnabled(enabled: c_int) {
    let flag = enabled != 0;
    ws::set_tls_record_padding_enabled(flag);
    generation_guard::change_config(|| {});
}

#[no_mangle]
pub extern "C" fn IsTlsRecordPaddingEnabled() -> c_int {
    if ws::is_tls_record_padding_enabled() {
        1
    } else {
        0
    }
}

#[no_mangle]
pub extern "C" fn SetTlsClientHelloSplitEnabled(enabled: c_int) {
    let flag = enabled != 0;
    ws::set_tls_client_hello_split_enabled(flag);
    generation_guard::change_config(|| {});
}

#[no_mangle]
pub extern "C" fn IsTlsClientHelloSplitEnabled() -> c_int {
    if ws::is_tls_client_hello_split_enabled() {
        1
    } else {
        0
    }
}

/// Promotes a preflight-verified Flowseal Anycast domain for one Telegram DC.
/// The balancer normalizes `kws{dc}.domain:443` into its base domain; it keeps
/// the remaining CDN candidates available for the normal Happy Eyeballs race.
#[no_mangle]
pub unsafe extern "C" fn PromoteCfproxyDomainForDc(
    dc_id: c_int,
    is_media: c_int,
    c_domain: *const c_char,
) -> c_int {
    if !(1..=5).contains(&dc_id) {
        return 0;
    }
    let domain = cstr_to_string(c_domain);
    if domain.trim().is_empty() {
        return 0;
    }

    generation_guard::change_config(|| {
        crate::balancer::BALANCER
            .write()
            .update_domain_for_dc(dc_id, is_media != 0, &domain);
    });
    crate::linfo!("MTProto Anycast candidate promoted for DC{}: {}", dc_id, domain);
    1
}

#[no_mangle]
pub unsafe extern "C" fn SetWorkerProtocol(_c_domain: *const c_char, _proto_ver: c_int) {
    // Protocol is unified to modern WSS relay (/apiws); proto_ver subnegotiation is deprecated.
    // Retained for backward FFI binary compatibility with JNA bindings.
}

#[no_mangle]
pub unsafe extern "C" fn SetSecret(c_secret: *const c_char) {
    let s = cstr_to_string(c_secret);
    if s.len() != 32 || hex::decode(&s).is_err() {
        return;
    }
    *PROXY_SECRET.write() = s;
}

#[no_mangle]
pub unsafe extern "C" fn SetSocks5Auth(c_username: *const c_char, c_password: *const c_char) {
    let username = cstr_to_string(c_username);
    let password = cstr_to_string(c_password);
    let mut auth = SOCKS5_AUTH.write();
    auth.username = username;
    auth.password = password;
}

#[no_mangle]
pub extern "C" fn SetSocks5Optimistic(enabled: c_int) {
    let flag = enabled != 0;
    SOCKS5_OPTIMISTIC_RESPONSE.store(flag, Ordering::Relaxed);
    crate::linfo!("SetSocks5Optimistic: enabled={}", flag);
}

#[no_mangle]
pub extern "C" fn IsSocks5Optimistic() -> c_int {
    if SOCKS5_OPTIMISTIC_RESPONSE.load(Ordering::Relaxed) {
        1
    } else {
        0
    }
}

#[no_mangle]
pub unsafe extern "C" fn SetDohEndpoints(c_endpoints: *const c_char) {
    let endpoints_str = cstr_to_string(c_endpoints);
    generation_guard::change_config(|| {
        cfproxy::set_doh_endpoints(&endpoints_str);
        dns::set_doh_endpoints(&endpoints_str);
    });
}

#[no_mangle]
pub extern "C" fn SetDnsPolicy(policy: c_int) {
    dns::set_dns_policy(policy);
}

#[no_mangle]
pub extern "C" fn SetUplinkMode(mode: c_int) {
    generation_guard::change_config(|| {
        masque::set_uplink_mode(mode);
        supervisor::ROUTE_SUPERVISOR.set_intent(mode, 1, true);
    });
}

#[no_mangle]
pub unsafe extern "C" fn SetWarpConfig(
    c_endpoint: *const c_char,
    c_sni: *const c_char,
    c_auth_token: *const c_char,
    c_client_ipv4: *const c_char,
    c_client_ipv6: *const c_char,
) {
    let endpoint = cstr_to_string(c_endpoint);
    let sni = cstr_to_string(c_sni);
    let auth_token = cstr_to_string(c_auth_token);
    let client_ipv4 = cstr_to_string(c_client_ipv4);
    let client_ipv6 = cstr_to_string(c_client_ipv6);
    generation_guard::change_config(|| masque::set_warp_config(&endpoint, &sni, &auth_token, &client_ipv4, &client_ipv6));
}

#[no_mangle]
pub unsafe extern "C" fn SetWarpCrypto(
    c_p256_priv: *const c_char,
    c_client_cert: *const c_char,
    c_peer_pub: *const c_char,
) {
    let p256_priv = cstr_to_string(c_p256_priv);
    let client_cert = cstr_to_string(c_client_cert);
    let peer_pub = cstr_to_string(c_peer_pub);
    generation_guard::change_config(|| masque::set_warp_crypto(&p256_priv, &client_cert, &peer_pub));
}

#[no_mangle]
pub unsafe extern "C" fn SetWarpUriTemplate(c_uri_template: *const c_char) {
    let uri_template = cstr_to_string(c_uri_template);
    generation_guard::change_config(|| masque::set_warp_uri_template(&uri_template));
}

#[no_mangle]
pub unsafe extern "C" fn SetWarpFullConfig(
    c_endpoint: *const c_char,
    c_sni: *const c_char,
    c_auth_token: *const c_char,
    c_client_ipv4: *const c_char,
    c_client_ipv6: *const c_char,
    c_p256_priv: *const c_char,
    c_client_cert: *const c_char,
    c_peer_pub: *const c_char,
    c_uri_template: *const c_char,
) {
    let endpoint = cstr_to_string(c_endpoint);
    let sni = cstr_to_string(c_sni);
    let auth_token = cstr_to_string(c_auth_token);
    let client_ipv4 = cstr_to_string(c_client_ipv4);
    let client_ipv6 = cstr_to_string(c_client_ipv6);
    let p256_priv = cstr_to_string(c_p256_priv);
    let client_cert = cstr_to_string(c_client_cert);
    let peer_pub = cstr_to_string(c_peer_pub);
    let uri_template = cstr_to_string(c_uri_template);
    generation_guard::change_config(|| masque::set_warp_full_config(
        &endpoint,
        &sni,
        &auth_token,
        &client_ipv4,
        &client_ipv6,
        &p256_priv,
        &client_cert,
        &peer_pub,
        &uri_template,
    ));
}

#[no_mangle]
pub extern "C" fn ClearWarpCrypto() {
    generation_guard::change_config(|| masque::clear_warp_crypto());
}

#[no_mangle]
pub extern "C" fn GetWarpConfigGeneration() -> u64 {
    masque::get_warp_config_generation()
}

#[no_mangle]
pub extern "C" fn GetWarpStatus() -> *mut c_char {
    let mode = masque::get_uplink_mode();
    let cfg = masque::WARP_CONFIG.read();
    let creds = cfg.resolve_credentials();
    let sticky = masque::get_sticky_endpoint().unwrap_or_else(|| "none".to_string());
    let last_err = match masque::get_last_masque_auth_error() {
        Some(e) => e.to_string(),
        None => "none".to_string(),
    };
    let s = format!(
        "mode={} ep={} sticky={} sni={} ipv4={} auth_scheme={:?} last_auth_err={}",
        mode, cfg.endpoint, sticky, cfg.sni, cfg.client_ipv4, creds.scheme, last_err
    );
    CString::new(s).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "C" fn GetMasqueAuthError() -> *mut c_char {
    match masque::get_last_masque_auth_error() {
        Some(err) => CString::new(err.to_string()).unwrap_or_default().into_raw(),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "C" fn ResetMasqueAuthError() {
    masque::reset_masque_auth_error();
}

#[no_mangle]
pub extern "C" fn GetWarpStickyEndpoint() -> *mut c_char {
    match masque::get_sticky_endpoint() {
        Some(ep) => CString::new(ep).unwrap_or_default().into_raw(),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "C" fn SetWarpStickyEndpoint(c_endpoint: *const c_char) {
    let endpoint = cstr_to_string(c_endpoint);
    generation_guard::change_config(|| masque::set_sticky_endpoint(&endpoint));
}

#[no_mangle]
pub extern "C" fn ClearWarpStickyProfile() {
    generation_guard::change_config(|| masque::clear_sticky_endpoint());
}

#[no_mangle]
pub extern "C" fn RecordWarpStickySuccess() {
    if let Some(ep) = masque::get_sticky_endpoint() {
        masque::record_sticky_success(&ep);
    }
}

#[no_mangle]
pub extern "C" fn RecordWarpStickyTimeout() -> bool {
    masque::record_sticky_timeout()
}

#[no_mangle]
pub extern "C" fn GetStats() -> *mut c_char {
    let s = STATS.summary();
    CString::new(s).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "C" fn GetSecretWithPrefix() -> *mut c_char {
    let sec = PROXY_SECRET.read().clone();
    CString::new(format!("dd{}", sec))
        .unwrap_or_default()
        .into_raw()
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
    generation_guard::change_config(|| awg::set_awg_config(
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
    ));
}

/// Kotlin interface: SetAwgConfig(ini: String): Int
/// Parses an AmneziaWG INI config string and loads it into the native engine.
/// Returns 0 on success, -1 on parse error.
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
pub extern "C" fn GetActiveCascadeStage() -> c_int {
    supervisor::get_active_cascade_stage()
}

#[no_mangle]
pub extern "C" fn GetRouteGeneration() -> i64 {
    supervisor::get_route_generation() as i64
}

#[no_mangle]
pub extern "C" fn GetRouteStateJson() -> *mut c_char {
    let s = supervisor::ROUTE_SUPERVISOR.get_route_state_json();
    CString::new(s).unwrap_or_default().into_raw()
}

#[no_mangle]
pub unsafe extern "C" fn ReportRouteFailure(c_reason: *const c_char) -> c_int {
    let reason = cstr_to_string(c_reason);
    supervisor::ROUTE_SUPERVISOR.report_route_failure(&reason)
}

#[no_mangle]
pub extern "C" fn SetRoutingIntent(mode: c_int, generation: i64, fallback_enabled: c_int) {
    generation_guard::change_config(|| supervisor::ROUTE_SUPERVISOR.set_intent(
        mode as i32,
        if generation > 0 { generation as u64 } else { 1 },
        fallback_enabled != 0,
    ));
}

#[no_mangle]
pub extern "C" fn ResetRouteSupervisor() {
    supervisor::ROUTE_SUPERVISOR.reset();
}

#[no_mangle]
pub extern "C" fn SetTrustPolicy(
    is_private_node: c_int,
    allow_public_relay_fallback: c_int,
    allow_opera_direct_exit: c_int,
    allow_opera_transport_hop: c_int,
) {
    generation_guard::change_config(|| supervisor::ROUTE_SUPERVISOR.set_trust_policy(
        is_private_node != 0,
        allow_public_relay_fallback != 0,
        allow_opera_direct_exit != 0,
        allow_opera_transport_hop != 0,
    ));
}

#[no_mangle]
pub extern "C" fn GetUsefulRxSliJson() -> *mut c_char {
    CString::new("{}").unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "C" fn SetNetworkGeneration(gen: i64) {
    if gen > 0 {
        network_profile::update_generation(gen as u64);
    }
}

#[no_mangle]
pub extern "C" fn GetDomainBalancerStatusJson() -> *mut c_char {
    let s = balancer::BALANCER.read().get_status_json();
    CString::new(s).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "C" fn GetDialBudgetStatsJson() -> *mut c_char {
    let rt = runtime();
    let stats = rt.block_on(async { budget::DIAL_BUDGET.stats().await });
    let s = serde_json::to_string(&stats).unwrap_or_else(|_| "{}".to_string());
    CString::new(s).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "C" fn GetNodeIndependenceStatusJson() -> *mut c_char {
    let s = node_independence::NODE_INDEPENDENCE_TRACKER.read().get_all_telemetry_json();
    CString::new(s).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "C" fn GetTlsObservabilityStatusJson() -> *mut c_char {
    let s = tls_observability::TLS_TRACKER.read().get_status_json();
    CString::new(s).unwrap_or_default().into_raw()
}

#[no_mangle]
pub unsafe extern "C" fn ResolveDns(
    c_domain: *const c_char,
    scope: c_int,
) -> *mut c_char {
    init_crypto_and_panic_hook();
    let domain = cstr_to_string(c_domain);
    if domain.trim().is_empty() {
        return CString::new("{\"status\":3,\"ips\":[],\"error\":\"Empty domain\"}").unwrap_or_default().into_raw();
    }
    let dns_scope = if scope == 1 {
        dns::DnsScope::UserInTunnel
    } else {
        dns::DnsScope::Bootstrap
    };

    let rt = runtime();
    let res = rt.block_on(async {
        dns::resolve_dual_stack(&domain, dns_scope).await
    });

    let json_str = match res {
        Ok(ips) => {
            let ip_strs: Vec<String> = ips.into_iter().map(|ip| ip.to_string()).collect();
            serde_json::json!({
                "status": 0,
                "ips": ip_strs
            }).to_string()
        }
        Err(e) => {
            serde_json::json!({
                "status": 3,
                "ips": [],
                "error": e.to_string()
            }).to_string()
        }
    };
    CString::new(json_str).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "C" fn ClearDnsCache() {
    dns::clear_dns_cache();
}


#[no_mangle]
pub unsafe extern "C" fn ProbeWarpEndpoints(
    c_endpoints_csv: *const c_char,
    protocol: c_int,
    timeout_ms: c_int,
) -> *mut c_char {
    init_crypto_and_panic_hook();
    let endpoints_csv = cstr_to_string(c_endpoints_csv);
    let to_ms = if timeout_ms <= 0 { 600 } else { timeout_ms as u64 };
    let rt = runtime();
    let results = rt.block_on(async {
        prober::probe_warp_endpoints(&endpoints_csv, protocol, to_ms).await
    });
    let s = serde_json::to_string(&results).unwrap_or_else(|_| "[]".to_string());
    CString::new(s).unwrap_or_default().into_raw()
}

#[no_mangle]
pub unsafe extern "C" fn PingHost(
    c_host: *const c_char,
    port: c_int,
    timeout_ms: c_int,
) -> *mut c_char {
    init_crypto_and_panic_hook();
    let host = cstr_to_string(c_host);
    let p = if port <= 0 { 443 } else { port as u16 };
    let to_ms = if timeout_ms <= 0 { 2500 } else { timeout_ms as u64 };
    let rt = runtime();
    let (success, rtt_ms, ip_or_err) = rt.block_on(async {
        prober::ping_tcp_host(&host, p, to_ms).await
    });
    let json_val = if success {
        serde_json::json!({
            "success": true,
            "rtt_ms": rtt_ms,
            "ip": ip_or_err
        })
    } else {
        serde_json::json!({
            "success": false,
            "rtt_ms": -1,
            "error": ip_or_err
        })
    };
    CString::new(json_val.to_string()).unwrap_or_default().into_raw()
}

#[no_mangle]
pub unsafe extern "C" fn PingWorker(
    c_domain: *const c_char,
    timeout_ms: c_int,
) -> *mut c_char {
    init_crypto_and_panic_hook();
    let domain = cstr_to_string(c_domain);
    let to_ms = if timeout_ms <= 0 { 5000 } else { timeout_ms as u64 };
    let rt = runtime();
    let (status, rtt_opt) = rt.block_on(async {
        prober::ping_worker_endpoint(&domain, to_ms).await
    });
    let json_val = serde_json::json!({
        "status": status,
        "rtt_ms": rtt_opt
    });
    CString::new(json_val.to_string()).unwrap_or_default().into_raw()
}

#[no_mangle]
pub unsafe extern "C" fn FreeString(p: *mut c_char) {
    if !p.is_null() {
        let _ = CString::from_raw(p);
    }
}