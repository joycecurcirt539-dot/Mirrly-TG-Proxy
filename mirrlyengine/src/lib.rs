pub mod balancer;
pub mod cfproxy;
pub mod config;
pub mod crypto;
pub mod proxy;
pub mod socks5;
pub mod ws;

use config::*;
use once_cell::sync::OnceCell;
use parking_lot::Mutex;
use proxy::{parse_cidr_pool, run_proxy, WsPool};
use socks5::run_socks5_server;
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use tokio::runtime::Runtime;
use tokio_util::sync::CancellationToken;

static RUNTIME: OnceCell<Runtime> = OnceCell::new();

struct ProxyState {
    pool: Option<Arc<WsPool>>,
    handle: tokio::task::JoinHandle<()>,
    cancel_tasks: CancellationToken,
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

    if secret_str.len() == 32 {
        if hex::decode(&secret_str).is_ok() {
            *PROXY_SECRET.write() = secret_str.clone();
        }
    }

    cfproxy::init_cfproxy_domains();

    let dc_opt_map: HashMap<i32, String> = parse_cidr_pool(&dc_ips_str);

    let rt = runtime();
    let cancel_tasks = CancellationToken::new();
    let pool = Arc::new(WsPool::new(cancel_tasks.clone()));

    let (tx, rx) = std::sync::mpsc::channel::<Result<(), String>>();

    let pool_task = pool.clone();
    let host_task = host.clone();
    let map_task = dc_opt_map.clone();
    let cancel_root = cancel_tasks.clone();

    let handle = rt.spawn(async move {
        let addr = format!("{}:{}", host_task, go_port);
        match tokio::net::TcpListener::bind(&addr).await {
            Ok(listener) => {
                let _ = tx.send(Ok(()));
                if let Err(e) =
                    run_proxy(pool_task, host_task, go_port, map_task, cancel_root, listener).await
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

    *guard = Some(ProxyState {
        pool: Some(pool),
        handle,
        cancel_tasks,
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
    cfproxy::init_cfproxy_domains();

    let rt = runtime();
    let cancel_tasks = CancellationToken::new();

    let (tx, rx) = std::sync::mpsc::channel::<Result<(), String>>();

    let host_task = host.clone();
    let cancel_root = cancel_tasks.clone();

    let handle = rt.spawn(async move {
        let addr = format!("{}:{}", host_task, go_port);
        match tokio::net::TcpListener::bind(&addr).await {
            Ok(listener) => {
                let _ = tx.send(Ok(()));
                if let Err(e) = run_socks5_server(host_task, go_port, cancel_root, listener).await {
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

    crate::linfo!("StopProxy: stopped successfully");
    0
}

#[no_mangle]
pub extern "C" fn ResetNetworkSockets() {
    let cell = state_cell();
    let guard = cell.lock();
    if let Some(state) = guard.as_ref() {
        if let Some(ref pool) = state.pool {
            let p = pool.clone();
            let rt = runtime();
            rt.spawn(async move {
                p.close_all().await;
            });
        }
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

#[no_mangle]
pub unsafe extern "C" fn SetCfProxyCacheDir(c_cache_dir: *const c_char) {
    let dir = cstr_to_string(c_cache_dir);
    CFPROXY.write().cache_dir = dir.trim().to_string();
}

#[no_mangle]
pub unsafe extern "C" fn SetCfProxyConfig(
    enabled: c_int,
    c_user_domain: *const c_char,
) {
    CFPROXY_ENABLED.store(enabled != 0, Ordering::Relaxed);
    let user_domain = cstr_to_string(c_user_domain);
    let mut cfg = CFPROXY.write();
    cfg.user_domain = user_domain.clone();
    if !user_domain.is_empty() {
        cfg.domains = vec![user_domain.clone()];
        cfg.active = user_domain;
    }
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
pub extern "C" fn GetStats() -> *mut c_char {
    let s = STATS.summary();
    CString::new(s).unwrap_or_default().into_raw()
}

#[no_mangle]
pub extern "C" fn GetSecretWithPrefix() -> *mut c_char {
    let sec = PROXY_SECRET.read().clone();
    CString::new(format!("dd{}", sec)).unwrap_or_default().into_raw()
}

#[no_mangle]
pub unsafe extern "C" fn FreeString(p: *mut c_char) {
    if !p.is_null() {
        let _ = CString::from_raw(p);
    }
}
