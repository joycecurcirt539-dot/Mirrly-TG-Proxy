pub mod awg;
pub mod balancer;
pub mod cfproxy;
pub mod config;
pub mod crypto;
pub mod faketls;
pub mod masque;
pub mod proxy;
pub mod reality;
pub mod socks5;
pub mod vision;
pub mod vless;
pub mod ws;

use config::*;
use once_cell::sync::OnceCell;
use parking_lot::Mutex;
use proxy::{parse_cidr_pool, run_proxy, WsPool};
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

    if guard.is_some() {
        return -1;
    }

    let host = cstr_to_string(c_host);
    let go_port = port as u16;
    let dc_ips_str = cstr_to_string(c_dc_ips);
    let secret_str = cstr_to_string(c_secret);
    let is_verbose = verbose != 0;

    init_logging(is_verbose);
    cfproxy::clear_cfproxy_429_cooldowns();
    cfproxy::clear_doh_cache();
    balancer::BALANCER.write().reset_ranking();

    if secret_str.len() == 32 {
        if hex::decode(&secret_str).is_ok() {
            *PROXY_SECRET.write() = secret_str.clone();
        }
    }

    cfproxy::init_cfproxy_domains();

    let dc_opt_map: HashMap<i32, String> = parse_cidr_pool(&dc_ips_str);
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
        match tokio::net::TcpListener::bind(&addr).await {
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

    if guard.is_some() {
        return -1;
    }

    let host = cstr_to_string(c_host);
    let go_port = port as u16;
    let is_verbose = verbose != 0;

    init_logging(is_verbose);
    cfproxy::clear_cfproxy_429_cooldowns();
    cfproxy::clear_doh_cache();
    balancer::BALANCER.write().reset_ranking();
    *LAST_SOCKS5_WORKER.write() = String::new();
    vless::reset_vless_scorer();
    cfproxy::init_cfproxy_domains();

    let rt = runtime();
    let cancel_tasks = CancellationToken::new();
    let cancel_sessions = Arc::new(parking_lot::RwLock::new(cancel_tasks.child_token()));

    let (tx, rx) = std::sync::mpsc::channel::<Result<(), String>>();

    let host_task = host.clone();
    let cancel_root = cancel_tasks.clone();
    let cancel_sessions_task = cancel_sessions.clone();

    let handle = rt.spawn(async move {
        let addr = format!("{}:{}", host_task, go_port);
        match tokio::net::TcpListener::bind(&addr).await {
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

    let cancel_balancer = cancel_tasks.clone();
    rt.spawn(async move {
        cfproxy::start_background_balancer_loop(cancel_balancer).await;
    });

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

    crate::linfo!("StopProxy: cancelling all tasks");
    state.cancel_tasks.cancel();

    let rt = runtime();
    let pool_opt = state.pool;
    let handle = state.handle;
    rt.spawn(async move {
        let _ = tokio::time::timeout(std::time::Duration::from_secs(2), handle).await;
        if let Some(pool) = pool_opt {
            pool.close_all().await;
        }
    });

    STATS.reset();
    WS_BLACKLIST.write().clear();
    DC_FAIL_UNTIL.write().clear();
    cfproxy::clear_cfproxy_429_cooldowns();
    cfproxy::clear_doh_cache();
    balancer::BALANCER.write().reset_ranking();
    *LAST_SOCKS5_WORKER.write() = String::new();
    vless::reset_vless_scorer();
    masque::reset_active_cascade_stage();

    crate::linfo!("StopProxy: stopped successfully");
    0
}

#[no_mangle]
pub extern "C" fn ResetNetworkSockets() {
    let cell = state_cell();
    let guard = cell.lock();
    if let Some(state) = guard.as_ref() {
        crate::linfo!("ResetNetworkSockets: resetting active sessions, 429 limits, and balancer ranking (preserving DoH cache & active worker)");

        // 1. Atomically rotate the sessions token and cancel the old token (cancels all active bridge sessions)
        let old_token = {
            let mut lock = state.cancel_sessions.write();
            let old = lock.clone();
            *lock = state.cancel_tasks.child_token();
            old
        };
        old_token.cancel();

        // 2. Clear failure cooldowns, 429 limits, and reset balancer ranking so the new network interface gets a fresh start.
        // NOTE: DOH_CACHE and LAST_SOCKS5_WORKER are deliberately preserved across network handovers (Wi-Fi <-> LTE).
        // Cloudflare Anycast edge IPs and working workers are identical globally; preserving them eliminates 2.2–3.5s cold-start reconnect delays.
        cfproxy::clear_cfproxy_429_cooldowns();
        DC_FAIL_UNTIL.write().clear();
        WS_BLACKLIST.write().clear();
        balancer::BALANCER.write().reset_ranking();
        vless::reset_vless_scorer();
        masque::reset_active_cascade_stage();

        // 3. Trigger immediate Fast Anycast Race in the background to rank domains on the new network interface
        let rt = runtime();
        rt.spawn(async move {
            cfproxy::race_all_primary_dcs().await;
        });

        // 4. If pool exists (MTProto mode), cleanly reset pool epoch, close all idle sockets and warmup on the new network interface
        if let Some(ref pool) = state.pool {
            let p = pool.clone();
            let rt = runtime();
            rt.spawn(async move {
                let map = DC_OPT.read().clone();
                p.reset_and_warmup(&map).await;
            });
        }

        // 5. Reset QUIC MASQUE endpoint to force clean handshake on the new network interface
        masque::reset_quic_endpoint();
    }
}

#[no_mangle]
pub extern "C" fn SetPoolSize(size: c_int) {
    let mut n = size;
    if n < 2 {
        n = 2;
    }
    if n > 16 {
        n = 16;
    }
    POOL_SIZE.store(n, Ordering::Relaxed);
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
    CFPROXY.write().cache_dir = dir.trim().to_string();
}

#[no_mangle]
pub unsafe extern "C" fn SetCfProxyConfig(enabled: c_int, c_user_domain: *const c_char) {
    CFPROXY_ENABLED.store(enabled != 0, Ordering::Relaxed);
    let user_domain = cstr_to_string(c_user_domain);
    *LAST_SOCKS5_WORKER.write() = String::new();
    vless::reset_vless_scorer();
    cfproxy::clear_cfproxy_429_cooldowns();
    let mut cfg = CFPROXY.write();
    cfg.user_domain = user_domain.clone();
    cfg.active = user_domain;
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
pub unsafe extern "C" fn SetDohEndpoints(c_endpoints: *const c_char) {
    let endpoints_str = cstr_to_string(c_endpoints);
    cfproxy::set_doh_endpoints(&endpoints_str);
}

#[no_mangle]
pub extern "C" fn SetUplinkMode(mode: c_int) {
    masque::set_uplink_mode(mode);
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
    masque::set_warp_config(&endpoint, &sni, &auth_token, &client_ipv4, &client_ipv6);
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
    masque::set_warp_crypto(&p256_priv, &client_cert, &peer_pub);
}

#[no_mangle]
pub unsafe extern "C" fn SetWarpUriTemplate(c_uri_template: *const c_char) {
    let uri_template = cstr_to_string(c_uri_template);
    masque::set_warp_uri_template(&uri_template);
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
    masque::set_warp_full_config(
        &endpoint,
        &sni,
        &auth_token,
        &client_ipv4,
        &client_ipv6,
        &p256_priv,
        &client_cert,
        &peer_pub,
        &uri_template,
    );
}

#[no_mangle]
pub extern "C" fn ClearWarpCrypto() {
    masque::clear_warp_crypto();
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
    masque::set_sticky_endpoint(&endpoint);
}

#[no_mangle]
pub extern "C" fn ClearWarpStickyProfile() {
    masque::clear_sticky_endpoint();
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
pub unsafe extern "C" fn SetVlessConfig(
    c_uuid: *const c_char,
    c_path: *const c_char,
    c_domain: *const c_char,
) {
    let uuid_str = cstr_to_string(c_uuid);
    let path = cstr_to_string(c_path);
    let domain = cstr_to_string(c_domain);
    vless::set_vless_config(&uuid_str, &path, &domain);
}

#[no_mangle]
pub unsafe extern "C" fn SetVlessExtendedConfig(
    c_uuid: *const c_char,
    c_path: *const c_char,
    c_domain: *const c_char,
    c_server_address: *const c_char,
    server_port: c_int,
    c_tls_sni: *const c_char,
    c_host_header: *const c_char,
    c_transport: *const c_char,
    c_security: *const c_char,
    c_public_key: *const c_char,
    c_short_id: *const c_char,
    c_fingerprint: *const c_char,
    c_spider_x: *const c_char,
    c_flow: *const c_char,
    c_header_type: *const c_char,
) {
    let uuid_str = cstr_to_string(c_uuid);
    let path = cstr_to_string(c_path);
    let domain = cstr_to_string(c_domain);
    let server_address = cstr_to_string(c_server_address);
    let tls_sni = cstr_to_string(c_tls_sni);
    let host_header = cstr_to_string(c_host_header);
    let transport = cstr_to_string(c_transport);
    let security = cstr_to_string(c_security);
    let public_key = cstr_to_string(c_public_key);
    let short_id = cstr_to_string(c_short_id);
    let fingerprint = cstr_to_string(c_fingerprint);
    let spider_x = cstr_to_string(c_spider_x);
    let flow = cstr_to_string(c_flow);
    let header_type = cstr_to_string(c_header_type);

    vless::set_vless_extended_config(
        &uuid_str,
        &path,
        &domain,
        &server_address,
        if server_port > 0 {
            server_port as u16
        } else {
            443
        },
        &tls_sni,
        &host_header,
        &transport,
        &security,
        &public_key,
        &short_id,
        &fingerprint,
        &spider_x,
        &flow,
        &header_type,
    );
}

#[no_mangle]
pub unsafe extern "C" fn SetVlessNetworkConfig(
    c_uuid: *const c_char,
    c_path: *const c_char,
    c_domain: *const c_char,
    c_server_address: *const c_char,
    server_port: c_int,
    c_tls_sni: *const c_char,
    c_host_header: *const c_char,
) {
    let uuid_str = cstr_to_string(c_uuid);
    let path = cstr_to_string(c_path);
    let domain = cstr_to_string(c_domain);
    let server_address = cstr_to_string(c_server_address);
    let tls_sni = cstr_to_string(c_tls_sni);
    let host_header = cstr_to_string(c_host_header);
    vless::set_vless_network_config(
        &uuid_str,
        &path,
        &domain,
        &server_address,
        if server_port > 0 {
            server_port as u16
        } else {
            443
        },
        &tls_sni,
        &host_header,
    );
}

#[no_mangle]
pub unsafe extern "C" fn SetVlessFallbackPool(c_domains: *const c_char) {
    let domains = cstr_to_string(c_domains);
    vless::set_vless_fallback_pool(&domains);
}

#[no_mangle]
pub unsafe extern "C" fn SetVlessFallbackProfilesJson(c_json: *const c_char) -> c_int {
    let json_str = cstr_to_string(c_json);
    if json_str.is_empty() {
        return -1;
    }
    match vless::set_vless_fallback_profiles_json(&json_str) {
        Ok(count) => count as c_int,
        Err(e) => {
            crate::lerror!("SetVlessFallbackProfilesJson error: {}", e);
            -1
        }
    }
}

#[no_mangle]
pub extern "C" fn GetVlessStatus() -> *mut c_char {
    let s = vless::get_vless_status();
    CString::new(s).unwrap_or_default().into_raw()
}

#[no_mangle]
pub unsafe extern "C" fn SetVlessConfigJson(c_json: *const c_char) -> c_int {
    let json_str = cstr_to_string(c_json);
    if json_str.is_empty() {
        return -1;
    }
    match vless::set_vless_config_json(&json_str) {
        Ok(()) => 0,
        Err(e) => {
            crate::lerror!("SetVlessConfigJson error: {}", e);
            -1
        }
    }
}

#[no_mangle]
pub extern "C" fn GetVlessConfigJson() -> *mut c_char {
    let s = vless::get_vless_config_json();
    CString::new(s).unwrap_or_default().into_raw()
}

#[no_mangle]
pub unsafe extern "C" fn SetOperaVpnConfig(
    vless_enabled: c_int,
    warp_enabled: c_int,
    c_endpoint: *const c_char,
) {
    let endpoint = cstr_to_string(c_endpoint);
    config::set_opera_vpn_config(vless_enabled != 0, warp_enabled != 0, &endpoint);
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
    );
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
    match awg::set_awg_config_ini(&ini_str) {
        Ok(()) => 0,
        Err(e) => {
            crate::lerror!("SetAwgConfig (INI) error: {}", e);
            -1
        }
    }
}

#[no_mangle]
pub extern "C" fn SetAwgWarpMode() {
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
    masque::get_active_cascade_stage()
}

#[no_mangle]
pub unsafe extern "C" fn StartAwgSocks5Proxy(
    c_host: *const c_char,
    port: c_int,
    verbose: c_int,
) -> c_int {
    init_crypto_and_panic_hook();

    let cell = state_cell();
    let mut guard = cell.lock();

    if guard.is_some() {
        return -1;
    }

    let host = cstr_to_string(c_host);
    let go_port = port as u16;
    let is_verbose = verbose != 0;

    init_logging(is_verbose);
    cfproxy::clear_cfproxy_429_cooldowns();
    cfproxy::clear_doh_cache();
    balancer::BALANCER.write().reset_ranking();

    let rt = runtime();
    let cancel_tasks = CancellationToken::new();
    let cancel_sessions = Arc::new(parking_lot::RwLock::new(cancel_tasks.child_token()));

    let (tx, rx) = std::sync::mpsc::channel::<Result<(), String>>();

    let host_task = host.clone();
    let cancel_root = cancel_tasks.clone();
    let cancel_sessions_task = cancel_sessions.clone();

    let handle = rt.spawn(async move {
        let addr = format!("{}:{}", host_task, go_port);
        match tokio::net::TcpListener::bind(&addr).await {
            Ok(listener) => {
                let _ = tx.send(Ok(()));
                linfo!("AWG-SOCKS5 proxy listening on {}", addr);
                loop {
                    tokio::select! {
                        _ = cancel_root.cancelled() => break,
                        accept = listener.accept() => {
                            match accept {
                                Ok((mut client, peer)) => {
                                    let cancel = cancel_sessions_task.read().child_token();
                                    tokio::spawn(async move {
                                        let target_addr = match awg_socks5_handshake(&mut client).await {
                                            Ok(t) => t,
                                            Err(e) => {
                                                crate::ldebug!("AWG SOCKS5 handshake failed from {}: {}", peer, e);
                                                return;
                                            }
                                        };
                                        match awg::awg_acquire_tunnel(&target_addr, &cancel).await {
                                            Some(tunnel) => {
                                                if let Err(e) = tunnel.run_smoltcp_bridge(client, cancel).await {
                                                    crate::ldebug!("AWG smoltcp bridge ended for {}: {}", target_addr, e);
                                                }
                                                tunnel.close().await;
                                            }
                                            None => {
                                                crate::lwarn!("AWG: could not establish tunnel to {}", target_addr);
                                            }
                                        }
                                    });
                                }
                                Err(_) => continue,
                            }
                        }
                    }
                }
            }
            Err(e) => {
                let _ = tx.send(Err(format!("listen AWG-SOCKS5 on {}: {}", addr, e)));
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

/// Minimal SOCKS5 server-side handshake: reads CONNECT request, replies with success,
/// returns the requested target address as "host:port".
async fn awg_socks5_handshake(stream: &mut tokio::net::TcpStream) -> Result<String, String> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let mut buf = [0u8; 512];

    // Phase 1: client greeting  [VER=5, NMETHODS, METHODS...]
    let n = stream.read(&mut buf).await.map_err(|e| e.to_string())?;
    if n < 2 || buf[0] != 5 {
        return Err("not a SOCKS5 greeting".to_string());
    }
    // Reply: no auth required
    stream
        .write_all(&[0x05, 0x00])
        .await
        .map_err(|e| e.to_string())?;

    // Phase 2: CONNECT request  [VER=5, CMD=1, RSV=0, ATYP, ...]
    let n = stream.read(&mut buf).await.map_err(|e| e.to_string())?;
    if n < 7 || buf[0] != 5 || buf[1] != 1 {
        return Err("not a SOCKS5 CONNECT".to_string());
    }

    let atyp = buf[3];
    let target = match atyp {
        0x01 => {
            // IPv4
            if n < 10 {
                return Err("SOCKS5 IPv4 request too short".to_string());
            }
            let ip = std::net::Ipv4Addr::new(buf[4], buf[5], buf[6], buf[7]);
            let port = u16::from_be_bytes([buf[8], buf[9]]);
            format!("{}:{}", ip, port)
        }
        0x03 => {
            // Domain
            let domain_len = buf[4] as usize;
            if n < 5 + domain_len + 2 {
                return Err("SOCKS5 domain request too short".to_string());
            }
            let domain = std::str::from_utf8(&buf[5..5 + domain_len]).map_err(|e| e.to_string())?;
            let port = u16::from_be_bytes([buf[5 + domain_len], buf[5 + domain_len + 1]]);
            format!("{}:{}", domain, port)
        }
        0x04 => {
            // IPv6
            if n < 22 {
                return Err("SOCKS5 IPv6 request too short".to_string());
            }
            let mut ip6 = [0u8; 16];
            ip6.copy_from_slice(&buf[4..20]);
            let ip = std::net::Ipv6Addr::from(ip6);
            let port = u16::from_be_bytes([buf[20], buf[21]]);
            format!("[{}]:{}", ip, port)
        }
        _ => return Err(format!("unsupported SOCKS5 ATYP={}", atyp)),
    };

    // Reply: success (BND.ADDR = 0.0.0.0:0)
    stream
        .write_all(&[0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00])
        .await
        .map_err(|e| e.to_string())?;

    Ok(target)
}

#[no_mangle]
pub unsafe extern "C" fn FreeString(p: *mut c_char) {
    if !p.is_null() {
        let _ = CString::from_raw(p);
    }
}

