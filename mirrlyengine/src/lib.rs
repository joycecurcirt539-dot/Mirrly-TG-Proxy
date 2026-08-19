pub mod cfproxy;
pub mod config;
pub mod crypto;
pub mod dc;
pub mod doh;
pub mod faketls;
pub mod logging;
pub mod proxy;
pub mod socks5;
pub mod stats;
pub mod ws;

use cfproxy::CfManager;
use config::ConfigManager;
use crypto::format_secret_with_prefix;
use dc::{get_ws_path, parse_dc_ips, resolve_dc_addr};
use doh::DohResolver;
use logging::{log_error, log_info};
use parking_lot::{Mutex, RwLock};
use proxy::ProxyServer;
use rustls::ClientConfig;
use socks5::Socks5Server;
use stats::EngineStats;
use std::ffi::{CStr, CString};
use std::net::SocketAddr;
use std::os::raw::{c_char, c_int};
use std::panic::catch_unwind;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::runtime::{Builder, Runtime};
use tokio::sync::watch;
use ws::WsPool;

#[allow(dead_code)]
struct EngineState {
    runtime: Option<Runtime>,
    config: Arc<ConfigManager>,
    stats: Arc<EngineStats>,
    doh: Arc<DohResolver>,
    cf_manager: Arc<CfManager>,
    ws_pool: Arc<WsPool>,
    tls_config: Arc<ClientConfig>,
    shutdown_tx: Option<watch::Sender<bool>>,
    is_running: AtomicBool,
}

static ENGINE: Mutex<Option<EngineState>> = Mutex::new(None);
static GLOBAL_CONFIG: RwLock<Option<Arc<ConfigManager>>> = RwLock::new(None);

fn get_or_init_engine() -> &'static Mutex<Option<EngineState>> {
    &ENGINE
}

fn stop_engine_internal(lock: &mut Option<EngineState>) {
    if let Some(mut state) = lock.take() {
        state.is_running.store(false, Ordering::SeqCst);

        // Signal all background and listener tasks to stop
        if let Some(tx) = state.shutdown_tx.take() {
            let _ = tx.send(true);
        }

        if let Some(rt) = state.runtime.take() {
            let pool = state.ws_pool.clone();
            rt.block_on(async move {
                pool.clear().await;
            });
            rt.shutdown_timeout(Duration::from_millis(300));
        }
    }
}

pub fn ensure_crypto_provider() {
    let _ = rustls::crypto::ring::default_provider().install_default();
}

pub fn get_global_config() -> Arc<ConfigManager> {
    let mut lock = GLOBAL_CONFIG.write();
    if let Some(cfg) = lock.as_ref() {
        cfg.clone()
    } else {
        let cfg = ConfigManager::new();
        *lock = Some(cfg.clone());
        cfg
    }
}

#[derive(Debug)]
struct NoServerCertVerifier;

impl rustls::client::danger::ServerCertVerifier for NoServerCertVerifier {
    fn verify_server_cert(
        &self,
        _end_entity: &rustls_pki_types::CertificateDer<'_>,
        _intermediates: &[rustls_pki_types::CertificateDer<'_>],
        _server_name: &rustls_pki_types::ServerName<'_>,
        _ocsp_response: &[u8],
        _now: rustls_pki_types::UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        Ok(rustls::client::danger::ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &rustls_pki_types::CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &rustls_pki_types::CertificateDer<'_>,
        _dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        Ok(rustls::client::danger::HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        rustls::crypto::ring::default_provider()
            .signature_verification_algorithms
            .supported_schemes()
    }
}

pub fn create_tls_config() -> Arc<ClientConfig> {
    ensure_crypto_provider();
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut config = ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .expect("Failed to initialize safe default protocol versions for rustls")
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(NoServerCertVerifier))
        .with_no_client_auth();

    config.alpn_protocols = vec![b"http/1.1".to_vec()];

    Arc::new(config)
}

fn c_str_to_rust(ptr: *const c_char) -> String {
    if ptr.is_null() {
        return String::new();
    }
    unsafe {
        CStr::from_ptr(ptr)
            .to_str()
            .unwrap_or("")
            .trim()
            .to_string()
    }
}

#[no_mangle]
pub extern "C" fn StartProxy(
    host: *const c_char,
    port: c_int,
    dc_ips: *const c_char,
    secret: *const c_char,
    verbose: c_int,
) -> c_int {
    let res = catch_unwind(|| {
        ensure_crypto_provider();

        let host_str = c_str_to_rust(host);
        let host_str = if host_str.is_empty() {
            "127.0.0.1"
        } else {
            &host_str
        };
        let port_u16 = if port <= 0 || port > 65535 {
            10808
        } else {
            port as u16
        };
        let dc_ips_str = c_str_to_rust(dc_ips);
        let secret_str = c_str_to_rust(secret);
        let is_verbose = verbose != 0;

        log_info(
            "mirrlyengine",
            &format!(
                "StartProxy requested: host={}, port={}, secret_len={}, verbose={}",
                host_str,
                port_u16,
                secret_str.len(),
                is_verbose
            ),
        );

        let bind_addr: SocketAddr = match format!("{}:{}", host_str, port_u16).parse() {
            Ok(addr) => addr,
            Err(e) => {
                log_error("mirrlyengine", &format!("Invalid bind address: {:?}", e));
                return 1;
            }
        };

        let mut lock = get_or_init_engine().lock();

        // Always clean up any existing engine runtime/state before starting
        if lock.is_some() {
            log_info("mirrlyengine", "Stopping existing engine instance before starting MTProto proxy...");
            stop_engine_internal(&mut lock);
        }

        let rt = match Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .thread_name("mirrlyengine-worker")
            .build()
        {
            Ok(r) => r,
            Err(_) => return 2, // Runtime failure
        };

        let _guard = rt.enter();

        let listener = match proxy::create_listener(bind_addr) {
            Ok(l) => l,
            Err(_) => return 3, // EADDRINUSE or bind failure
        };

        let config = get_global_config();
        if !secret_str.is_empty() {
            config.set_secret(secret_str);
        }
        config.set_verbose(is_verbose);
        if !dc_ips_str.is_empty() {
            config.set_dc_ips(parse_dc_ips(&dc_ips_str));
        }

        let stats = EngineStats::new();
        let doh = DohResolver::new();
        let cf_manager = CfManager::new(config.clone(), doh.clone());
        let tls_config = create_tls_config();
        let ws_pool = WsPool::new(tls_config.clone(), doh.clone());

        let (shutdown_tx, shutdown_rx) = watch::channel(false);

        let server = Arc::new(ProxyServer::new(
            config.clone(),
            stats.clone(),
            cf_manager.clone(),
            ws_pool.clone(),
            tls_config.clone(),
        ));

        // Start background domain updater
        let cf_bg = cf_manager.clone();
        rt.spawn(async move {
            cf_bg.fetch_upstream_domains().await;
        });

        // Background 1-Hop WebSocket pre-warming task based on pool_size for primary DCs (DC2 & DC4)
        let ws_pool_bg = ws_pool.clone();
        let cf_manager_bg = cf_manager.clone();
        let config_bg = config.clone();
        let mut shutdown_rx_bg = shutdown_rx.clone();

        rt.spawn(async move {
            let primary_keys: &[(i16, bool)] = &[(2, false), (4, false), (2, true), (4, true)];

            loop {
                let cfg = config_bg.get();
                let pool_size = cfg.pool_size.clamp(2, 16);
                let pool_per_dc = (pool_size / 2).max(1);

                for &(dc_id, is_media) in primary_keys {
                    if cfg.cf_enabled {
                        let target_dc_addr = resolve_dc_addr(dc_id, &cfg.dc_ips);
                        let ws_path = format!("/tcp?target={}", target_dc_addr);
                        let candidates = cf_manager_bg.get_candidate_domains(dc_id, is_media);
                        for domain in candidates {
                            if ws_pool_bg
                                .prewarm_target(dc_id, is_media, &domain, &ws_path, pool_per_dc)
                                .await
                            {
                                log_info(
                                    "mirrlyengine",
                                    &format!(
                                        "Pre-warmed socket in pool for DC{} (media={}) via {}",
                                        dc_id, is_media, domain
                                    ),
                                );
                                break;
                            }
                        }
                    }
                }

                tokio::select! {
                    _ = tokio::time::sleep(Duration::from_secs(3)) => {}
                    _ = shutdown_rx_bg.changed() => {
                        if *shutdown_rx_bg.borrow() {
                            break;
                        }
                    }
                }
            }
        });

        // Spawn main proxy listener
        let server_future = server.run(listener, shutdown_rx);
        rt.spawn(async move {
            let _ = server_future.await;
        });

        *lock = Some(EngineState {
            runtime: Some(rt),
            config,
            stats,
            doh,
            cf_manager,
            ws_pool,
            tls_config,
            shutdown_tx: Some(shutdown_tx),
            is_running: AtomicBool::new(true),
        });

        0 // Success
    });

    res.unwrap_or(-1)
}

#[no_mangle]
pub extern "C" fn StartSocks5Proxy(
    host: *const c_char,
    port: c_int,
    verbose: c_int,
) -> c_int {
    let res = catch_unwind(|| {
        ensure_crypto_provider();

        let host_str = c_str_to_rust(host);
        let host_str = if host_str.is_empty() {
            "127.0.0.1"
        } else {
            &host_str
        };
        let port_u16 = if port <= 0 || port > 65535 {
            10808
        } else {
            port as u16
        };
        let is_verbose = verbose != 0;

        log_info(
            "mirrlyengine",
            &format!(
                "StartSocks5Proxy requested: host={}, port={}, verbose={}",
                host_str, port_u16, is_verbose
            ),
        );

        let bind_addr: SocketAddr = match format!("{}:{}", host_str, port_u16).parse() {
            Ok(addr) => addr,
            Err(e) => {
                log_error("mirrlyengine", &format!("Invalid bind address: {:?}", e));
                return 1;
            }
        };

        let mut lock = get_or_init_engine().lock();

        // Always clean up any existing engine runtime/state before starting
        if lock.is_some() {
            log_info("mirrlyengine", "Stopping existing engine instance before starting SOCKS5 proxy...");
            stop_engine_internal(&mut lock);
        }

        let rt = match Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .thread_name("mirrlyengine-socks5-worker")
            .build()
        {
            Ok(r) => r,
            Err(_) => return 2, // Runtime failure
        };

        let _guard = rt.enter();

        let listener = match proxy::create_listener(bind_addr) {
            Ok(l) => l,
            Err(e) => {
                log_error("mirrlyengine", &format!("Bind SOCKS5 listener failed on {}: {:?}", bind_addr, e));
                return 3; // EADDRINUSE or bind failure
            }
        };

        let config = get_global_config();
        config.set_verbose(is_verbose);

        let stats = EngineStats::new();
        let doh = DohResolver::new();
        let cf_manager = CfManager::new(config.clone(), doh.clone());
        let tls_config = create_tls_config();
        let ws_pool = WsPool::new(tls_config.clone(), doh.clone());

        let (shutdown_tx, shutdown_rx) = watch::channel(false);

        let server = Arc::new(Socks5Server::new(
            config.clone(),
            stats.clone(),
            cf_manager.clone(),
            ws_pool.clone(),
            tls_config.clone(),
        ));

        // Start background domain updater
        let cf_bg = cf_manager.clone();
        rt.spawn(async move {
            cf_bg.fetch_upstream_domains().await;
        });

        // Background 1-Hop WebSocket pre-warming task for primary DCs
        let ws_pool_bg = ws_pool.clone();
        let cf_manager_bg = cf_manager.clone();
        let config_bg = config.clone();
        let mut shutdown_rx_bg = shutdown_rx.clone();

        rt.spawn(async move {
            let primary_keys: &[(i16, bool)] = &[(2, false), (4, false), (2, true), (4, true)];
            let ws_path = get_ws_path(false);

            loop {
                let pool_size = config_bg.get().pool_size.clamp(2, 16);
                let pool_per_dc = (pool_size / 2).max(1);

                for &(dc_id, is_media) in primary_keys {
                    let candidates = cf_manager_bg.get_candidate_domains(dc_id, is_media);
                    for domain in candidates {
                        if ws_pool_bg
                            .prewarm_target(dc_id, is_media, &domain, ws_path, pool_per_dc)
                            .await
                        {
                            break;
                        }
                    }
                }

                tokio::select! {
                    _ = tokio::time::sleep(Duration::from_secs(3)) => {}
                    _ = shutdown_rx_bg.changed() => {
                        if *shutdown_rx_bg.borrow() {
                            break;
                        }
                    }
                }
            }
        });

        // Spawn main SOCKS5 listener
        let server_future = server.run(listener, shutdown_rx);
        rt.spawn(async move {
            let _ = server_future.await;
        });

        *lock = Some(EngineState {
            runtime: Some(rt),
            config,
            stats,
            doh,
            cf_manager,
            ws_pool,
            tls_config,
            shutdown_tx: Some(shutdown_tx),
            is_running: AtomicBool::new(true),
        });

        0 // Success
    });

    res.unwrap_or(-1)
}

#[no_mangle]
pub extern "C" fn StopProxy() -> c_int {
    let res = catch_unwind(|| {
        log_info("mirrlyengine", "StopProxy requested");
        let mut lock = get_or_init_engine().lock();
        stop_engine_internal(&mut lock);
        0
    });

    res.unwrap_or(-1)
}

#[no_mangle]
pub extern "C" fn ResetNetworkSockets() {
    let _ = catch_unwind(|| {
        log_info(
            "mirrlyengine",
            "ResetNetworkSockets requested (Network Wi-Fi <-> LTE changed)",
        );
        let lock = get_or_init_engine().lock();
        if let Some(state) = lock.as_ref() {
            if let Some(rt) = state.runtime.as_ref() {
                let pool = state.ws_pool.clone();
                let doh = state.doh.clone();
                let cf_manager = state.cf_manager.clone();
                let config = state.config.clone();

                rt.spawn(async move {
                    pool.clear().await;
                    doh.clear_cache();

                    let pool_size = config.get().pool_size.clamp(2, 16);
                    let pool_per_dc = (pool_size / 2).max(1);
                    let primary_keys: &[(i16, bool)] = &[(2, false), (4, false), (2, true), (4, true)];
                    let ws_path = get_ws_path(false);

                    for &(dc_id, is_media) in primary_keys {
                        let candidates = cf_manager.get_candidate_domains(dc_id, is_media);
                        for domain in candidates {
                            if pool.prewarm_target(dc_id, is_media, &domain, ws_path, pool_per_dc).await {
                                break;
                            }
                        }
                    }
                });
            }
        }
    });
}

#[no_mangle]
pub extern "C" fn SetPoolSize(size: c_int) {
    let _ = catch_unwind(|| {
        let size_clamped = size.clamp(2, 16) as usize;
        get_global_config().set_pool_size(size_clamped);
        log_info("mirrlyengine", &format!("Pool size updated to {}", size_clamped));
    });
}

#[no_mangle]
pub extern "C" fn SetCfProxyCacheDir(cache_dir: *const c_char) {
    let _ = catch_unwind(|| {
        let dir_str = c_str_to_rust(cache_dir);
        if !dir_str.is_empty() {
            let lock = get_or_init_engine().lock();
            if let Some(state) = lock.as_ref() {
                state.cf_manager.set_cache_dir(&dir_str);
            }
        }
    });
}

#[no_mangle]
pub extern "C" fn SetCfProxyConfig(enabled: c_int, priority: c_int, user_domain: *const c_char) {
    let _ = catch_unwind(|| {
        let is_enabled = enabled != 0;
        let is_priority = priority != 0;
        let domain_str = c_str_to_rust(user_domain);

        let cfg = get_global_config();
        cfg.set_cf_config(is_enabled, is_priority, domain_str.clone());
        log_info(
            "mirrlyengine",
            &format!(
                "SetCfProxyConfig: enabled={}, priority={}, user_domain='{}'",
                is_enabled, is_priority, domain_str
            ),
        );
    });
}

#[no_mangle]
pub extern "C" fn SetSecret(secret: *const c_char) {
    let _ = catch_unwind(|| {
        let sec_str = c_str_to_rust(secret);
        if !sec_str.is_empty() {
            get_global_config().set_secret(sec_str);
        }
    });
}

#[no_mangle]
pub extern "C" fn GetSecretWithPrefix() -> *mut c_char {
    let res = catch_unwind(|| {
        let cfg = get_global_config().get();
        let formatted = format_secret_with_prefix(&cfg.secret);
        CString::new(formatted).map(|c| c.into_raw()).unwrap_or(std::ptr::null_mut())
    });

    res.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn GetStats() -> *mut c_char {
    let res = catch_unwind(|| {
        let lock = get_or_init_engine().lock();
        if let Some(state) = lock.as_ref() {
            let json = state.stats.to_json();
            CString::new(json).map(|c| c.into_raw()).unwrap_or(std::ptr::null_mut())
        } else {
            CString::new("{}".to_string())
                .map(|c| c.into_raw())
                .unwrap_or(std::ptr::null_mut())
        }
    });

    res.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn FreeString(ptr: *mut c_char) {
    let _ = catch_unwind(|| {
        if !ptr.is_null() {
            unsafe {
                let _ = CString::from_raw(ptr);
            }
        }
    });
}
