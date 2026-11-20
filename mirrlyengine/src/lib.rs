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
pub mod traffic_guard;
pub mod snake;

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

pub const START_ERR_ADDR_IN_USE: c_int = -3;
pub const START_ERR_PERMISSION_DENIED: c_int = -4;
pub const START_ERR_OTHER: c_int = -5;

static LAST_START_ERROR: Lazy<parking_lot::RwLock<Option<String>>> =
    Lazy::new(|| parking_lot::RwLock::new(None));

enum BindStartFailure {
    Io { addr: String, err: std::io::Error },
    ChannelClosed { addr: String },
}

fn clear_last_start_error() {
    *LAST_START_ERROR.write() = None;
}

fn classify_start_io_error(err: &std::io::Error) -> c_int {
    match err.kind() {
        std::io::ErrorKind::AddrInUse => START_ERR_ADDR_IN_USE,
        std::io::ErrorKind::PermissionDenied => START_ERR_PERMISSION_DENIED,
        _ => START_ERR_OTHER,
    }
}

fn record_start_error(label: &str, failure: BindStartFailure) -> c_int {
    let (code, message) = match failure {
        BindStartFailure::Io { addr, err } => {
            let code = classify_start_io_error(&err);
            let os_detail = err
                .raw_os_error()
                .map(|os| format!("os error {}", os))
                .unwrap_or_else(|| format!("{:?}", err.kind()));
            (
                code,
                format!("{} listen on {} failed: {} ({})", label, addr, err, os_detail),
            )
        }
        BindStartFailure::ChannelClosed { addr } => (
            START_ERR_OTHER,
            format!("{} listen on {} aborted before bind completed", label, addr),
        ),
    };
    crate::lerror!("{}", message);
    *LAST_START_ERROR.write() = Some(message);
    code
}

fn spawn_bound_listener<F, Fut>(
    rt: &Runtime,
    label: &'static str,
    addr: String,
    serve: F,
) -> Result<tokio::task::JoinHandle<()>, c_int>
where
    F: FnOnce(tokio::net::TcpListener) -> Fut + Send + 'static,
    Fut: std::future::Future<Output = std::io::Result<()>> + Send + 'static,
{
    clear_last_start_error();
    let (tx, rx) = std::sync::mpsc::channel::<Result<(), std::io::Error>>();
    let addr_for_task = addr.clone();

    let handle = rt.spawn(async move {
        match bind_reuse_tcp(&addr_for_task) {
            Ok(listener) => {
                let _ = tx.send(Ok(()));
                if let Err(e) = serve(listener).await {
                    crate::lerror!("{} serve on {}: {}", label, addr_for_task, e);
                }
            }
            Err(e) => {
                let _ = tx.send(Err(e));
            }
        }
    });

    match rx.recv() {
        Ok(Ok(())) => Ok(handle),
        Ok(Err(err)) => {
            handle.abort();
            Err(record_start_error(label, BindStartFailure::Io { addr, err }))
        }
        Err(_) => {
            handle.abort();
            Err(record_start_error(
                label,
                BindStartFailure::ChannelClosed { addr },
            ))
        }
    }
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
    traffic_guard::ensure_traffic_guard_audited();
}

pub(crate) fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        init_crypto_and_panic_hook();

        // Dynamically size worker threads based on available CPU cores:
        // Lower bound 4: prevents reactor starvation during concurrent crypto & blocking I/O
        // Upper bound 8: optimizes battery & thermal efficiency on ARM big.LITTLE architectures
        let detected_cores = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(4);
        let worker_threads = detected_cores.clamp(4, 8);

        crate::linfo!(
            "Tokio multi-thread runtime initialized: {} worker threads (detected {} CPU cores)",
            worker_threads,
            detected_cores
        );

        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(worker_threads)
            .thread_name("mirrly-rt")
            .event_interval(31)
            .max_blocking_threads(32)
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
    cfproxy::init_cfproxy_domains();
    cfproxy::clear_addr_cache();
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

    let addr = format!("{}:{}", host, go_port);
    let pool_task = pool.clone();
    let host_task = host.clone();
    let map_task = dc_opt_map.clone();
    let cancel_root = cancel_tasks.clone();
    let cancel_sessions_task = cancel_sessions.clone();

    let handle = match spawn_bound_listener(rt, "MTProto", addr, move |listener| async move {
        run_proxy(
            pool_task,
            host_task,
            go_port,
            map_task,
            cancel_root,
            cancel_sessions_task,
            listener,
        )
        .await
    }) {
        Ok(h) => h,
        Err(code) => return code,
    };

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
    cfproxy::init_cfproxy_domains();
    cfproxy::clear_addr_cache();
    recovery::reset();
    *LAST_SOCKS5_WORKER.write() = String::new();
    node_independence::NODE_INDEPENDENCE_TRACKER.write().reset();
    tls_observability::TLS_TRACKER.write().reset();
    supervisor::ROUTE_SUPERVISOR.set_intent(supervisor::UPLINK_WORKER, 1, false);

    let rt = runtime();
    let cancel_tasks = CancellationToken::new();
    let cancel_sessions = Arc::new(parking_lot::RwLock::new(cancel_tasks.child_token()));

    let addr = format!("{}:{}", host, go_port);
    let host_task = host.clone();
    let cancel_root = cancel_tasks.clone();
    let cancel_sessions_task = cancel_sessions.clone();

    let handle = match spawn_bound_listener(rt, "SOCKS5", addr, move |listener| async move {
        run_socks5_server(
            host_task,
            go_port,
            cancel_root,
            cancel_sessions_task,
            listener,
        )
        .await
    }) {
        Ok(h) => h,
        Err(code) => return code,
    };

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
    cfproxy::clear_addr_cache();
    recovery::reset();
    balancer::BALANCER.write().reset_ranking();
    node_independence::NODE_INDEPENDENCE_TRACKER.write().reset();
    tls_observability::TLS_TRACKER.write().reset();
    *LAST_SOCKS5_WORKER.write() = String::new();
    supervisor::ROUTE_SUPERVISOR.transition_to(supervisor::RouteKind::None, "stop_proxy");

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
    let (awg_init_sent, awg_resp_received, awg_bytes_recv, cb_open, cb_failures, awg_ep) = awg::get_awg_diagnostics();

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
    awg::reset_awg_circuit_breaker();
    awg::invalidate_active_peer();
    vpn::notify_vpn_reconnect();
}

#[no_mangle]
pub extern "C" fn NotifyVpnReconnect() {
    ResetVpnSockets();
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
                "ResetNetworkRouting: rotating candidate endpoints and addr cache reason={} (keeping active data sessions intact)",
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
            "ResetNetworkSockets: last-level reset reason={} (preserving addr cache, active worker, and 429 backoff)",
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
        // NOTE: HOST_ADDR_CACHE and LAST_SOCKS5_WORKER are deliberately preserved across network handovers (Wi-Fi <-> LTE).
        // Cloudflare Anycast edge IPs and working workers are identical globally; preserving them eliminates 2.2–3.5s cold-start reconnect delays.
        // HTTP 429 backoff is deliberately preserved: a radio handover does not make
        // a rate-limited Worker healthy and clearing it creates a reconnect storm.
        DC_FAIL_UNTIL.write().clear();
        WS_BLACKLIST.write().clear();
        balancer::BALANCER.write().reset_ranking();
        node_independence::NODE_INDEPENDENCE_TRACKER.write().reset();
        supervisor::ROUTE_SUPERVISOR.transition_to_unchecked(
            supervisor::RouteKind::None,
            "supervisor_reset",
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

        // 5. Reset AWG active peer and SOCKS5 warm pool to force clean handshakes on the new network interface
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
    let max_ceiling = config::max_socket_buffer();
    let requested_recv = recv_size.max(0);
    let requested_send = send_size.max(0);
    let recv = if requested_recv == 0 {
        0
    } else {
        requested_recv.clamp(config::MIN_SOCKET_BUFFER, max_ceiling)
    };
    let send = if requested_send == 0 {
        0
    } else {
        requested_send.clamp(config::MIN_SOCKET_BUFFER, max_ceiling)
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
pub extern "C" fn SetMaxSocketBufferCeiling(ceiling: c_int) -> c_int {
    config::set_max_socket_buffer_ceiling(ceiling);
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
pub extern "C" fn SetTlsEnabled(enabled: c_int) {
    let flag = enabled != 0;
    generation_guard::change_config(|| {
        ws::set_tls_enabled(flag);
        cfproxy::clear_winner_cache();
        cfproxy::clear_all_recovery_cooldowns();
        socks5::clear_socks5_warm_pool();
        dns::clear_dns_cache();
    });

    let pool_opt = state_cell().lock().as_ref().and_then(|s| s.pool.clone());
    if let Some(pool) = pool_opt {
        let dc_opt = DC_OPT.read().clone();
        let rt = runtime();
        rt.spawn(async move {
            pool.reset().await;
            pool.prewarm(&dc_opt).await;
        });
    }
}

#[no_mangle]
pub extern "C" fn IsTlsEnabled() -> c_int {
    if ws::is_tls_enabled() {
        1
    } else {
        0
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
pub extern "C" fn SetSocks5UplinkMode(mode: c_int) {
    generation_guard::change_config(|| {
        socks5::clear_socks5_warm_pool();
        config::set_socks5_uplink_mode(mode);
        crate::linfo!("SetSocks5UplinkMode: mode={}", mode);
    });
}

#[no_mangle]
pub extern "C" fn GetSocks5UplinkMode() -> c_int {
    config::get_socks5_uplink_mode()
}

#[no_mangle]
pub extern "C" fn SetUplinkMode(mode: c_int) {
    generation_guard::change_config(|| {
        supervisor::ROUTE_SUPERVISOR.set_intent(mode, 1, true);
    });
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
) {
    generation_guard::change_config(|| supervisor::ROUTE_SUPERVISOR.set_trust_policy(
        is_private_node != 0,
        allow_public_relay_fallback != 0,
    ));
}

#[no_mangle]
pub extern "C" fn GetUsefulRxSliJson() -> *mut c_char {
    let pool_opt = state_cell().lock().as_ref().and_then(|s| s.pool.clone());
    let bal = balancer::BALANCER.read().get_health_summary();
    let tls_on = ws::is_tls_enabled();
    let tls_stats = if tls_on {
        Some(tls_observability::TLS_TRACKER.read().get_status())
    } else {
        None
    };

    let (warm_ready, warm_target, warm_inflight, transport_name) = if let Some(pool) = pool_opt {
        let wp = pool.warm_pool_status();
        (wp.ready_total, wp.target_total, wp.in_flight_total, "mtproto")
    } else {
        let wp = socks5::get_socks5_warm_pool_status();
        (wp.ready_total, wp.target_total, wp.in_flight_total, "socks5")
    };

    let active_conns = STATS.connections_active.load(Ordering::Relaxed);
    let total_conns = STATS.connections_total.load(Ordering::Relaxed);
    let ws_total = STATS.connections_ws.load(Ordering::Relaxed);
    let cf_total = STATS.connections_cfproxy.load(Ordering::Relaxed);
    let awg_total = STATS.connections_awg.load(Ordering::Relaxed);
    let v2_sessions = STATS.socks5_v2_sessions.load(Ordering::Relaxed);
    let v1_downgrades = STATS.socks5_v1_downgrades.load(Ordering::Relaxed);
    let fastpath_hits = STATS.socks5_fastpath_hits.load(Ordering::Relaxed);
    let fallback_triggers = STATS.socks5_fallback_triggers.load(Ordering::Relaxed);
    let bad_conns = STATS.connections_bad.load(Ordering::Relaxed);
    let ws_errors = STATS.ws_errors.load(Ordering::Relaxed);
    let pool_hits = STATS.pool_hits.load(Ordering::Relaxed);
    let pool_misses = STATS.pool_misses.load(Ordering::Relaxed);
    let mtproto_429 = STATS.mtproto_429_cooldown_hits.load(Ordering::Relaxed);
    let socks5_429 = STATS.socks5_429_cooldown_hits.load(Ordering::Relaxed);
    let rx_bytes = STATS.bytes_down.load(Ordering::Relaxed);
    let tx_bytes = STATS.bytes_up.load(Ordering::Relaxed);
    let tls_failures = tls_stats.as_ref().map(|t| t.total_handshake_failures).unwrap_or(0);

    let json = serde_json::json!({
        "transport": transport_name,
        "tls_enabled": tls_on,
        "active_conns": active_conns,
        "connections_total": total_conns,
        "connections_ws": ws_total,
        "connections_cfproxy": cf_total,
        "connections_awg": awg_total,
        "socks5_v2_sessions": v2_sessions,
        "socks5_v1_downgrades": v1_downgrades,
        "socks5_fastpath_hits": fastpath_hits,
        "socks5_fallback_triggers": fallback_triggers,
        "connections_bad": bad_conns,
        "ws_errors": ws_errors,
        "pool_hits": pool_hits,
        "pool_misses": pool_misses,
        "warm_ready": warm_ready,
        "warm_target": warm_target,
        "warm_inflight": warm_inflight,
        "mtproto_429_hits": mtproto_429,
        "socks5_429_hits": socks5_429,
        "rx_bytes": rx_bytes,
        "tx_bytes": tx_bytes,
        "tls_failures": tls_failures,
        "balancer_active_domain": bal.active_domain,
        "balancer_healthy_domains": bal.healthy_domain_count,
        "balancer_total_domains": bal.total_domain_count,
        "balancer_min_rtt_ms": bal.min_probe_rtt_ms,
        "balancer_median_rtt_ms": bal.median_probe_rtt_ms,
        "balancer_useful_success_count": bal.total_useful_success_count,
        "balancer_failure_count": bal.total_failure_count,
        "vpn_established": vpn::IS_VPN_ESTABLISHED.load(Ordering::Relaxed),
    })
    .to_string();

    CString::new(json).unwrap_or_default().into_raw()
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
pub unsafe extern "C" fn ProbeTransportHealth(
    mode: c_int,
    c_domain: *const c_char,
    timeout_ms: c_int,
) -> *mut c_char {
    init_crypto_and_panic_hook();
    let domain = cstr_to_string(c_domain);
    let to_ms = if timeout_ms <= 0 { 2500 } else { timeout_ms as u64 };
    let rt = runtime();
    let res = rt.block_on(async {
        prober::probe_transport_health(mode, &domain, to_ms).await
    });
    let json = serde_json::to_string(&res).unwrap_or_else(|_| "{}".to_string());
    CString::new(json).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "C" fn GetLastStartError() -> *mut c_char {
    let msg = LAST_START_ERROR.read().clone().unwrap_or_default();
    CString::new(msg).unwrap_or_default().into_raw()
}

#[no_mangle]
pub unsafe extern "C" fn FreeString(p: *mut c_char) {
    if !p.is_null() {
        let _ = CString::from_raw(p);
    }
}

#[cfg(test)]
mod start_error_tests {
    use super::*;

    #[test]
    fn start_error_classification_maps_io_kinds() {
        let addr_in_use = std::io::Error::new(std::io::ErrorKind::AddrInUse, "port busy");
        assert_eq!(classify_start_io_error(&addr_in_use), START_ERR_ADDR_IN_USE);

        let perm_denied = std::io::Error::new(std::io::ErrorKind::PermissionDenied, "blocked");
        assert_eq!(classify_start_io_error(&perm_denied), START_ERR_PERMISSION_DENIED);

        let invalid = std::io::Error::new(std::io::ErrorKind::InvalidInput, "bad addr");
        assert_eq!(classify_start_io_error(&invalid), START_ERR_OTHER);
    }

    #[test]
    fn record_start_error_populates_last_start_error_and_ffi_getter() {
        clear_last_start_error();
        let err = std::io::Error::new(std::io::ErrorKind::PermissionDenied, "eacces");
        let code = record_start_error(
            "SOCKS5",
            BindStartFailure::Io {
                addr: "127.0.0.1:10888".to_string(),
                err,
            },
        );
        assert_eq!(code, START_ERR_PERMISSION_DENIED);

        unsafe {
            let ptr = GetLastStartError();
            assert!(!ptr.is_null());
            let s = CStr::from_ptr(ptr).to_string_lossy().into_owned();
            FreeString(ptr);
            assert!(s.contains("SOCKS5 listen on 127.0.0.1:10888 failed"));
        }

        clear_last_start_error();
        unsafe {
            let ptr = GetLastStartError();
            assert!(!ptr.is_null());
            let s = CStr::from_ptr(ptr).to_string_lossy().into_owned();
            FreeString(ptr);
            assert!(s.is_empty());
        }
    }

    #[test]
    fn start_socks5_invalid_host_returns_other_error_with_detail() {
        let bad_host = CString::new("not-an-ip-address").unwrap();
        let code = unsafe { StartSocks5Proxy(bad_host.as_ptr(), 10888, 0) };
        assert_eq!(code, START_ERR_OTHER);

        unsafe {
            let ptr = GetLastStartError();
            let s = CStr::from_ptr(ptr).to_string_lossy().into_owned();
            FreeString(ptr);
            assert!(s.contains("SOCKS5 listen on not-an-ip-address:10888 failed"));
        }
    }
}