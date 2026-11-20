use mirrlyvpn::config::{effective_udp_tunnel_buffer_sizes, MOBILE_NETWORK};
use mirrlyvpn::prober::{probe_single_warp_endpoint, warp_tcp_fallback_port};
use mirrlyvpn::vpn::{handle_dot_or_blocked_tcp, should_drop_outbound_dns_tcp};
use mirrlyvpn::{
    FreeVpnString, GetAwgStatus, GetVpnLastError, GetVpnStats, GetVpnStatusJson,
    IsVpnTlsEnabled, SetAwgConfig, SetAwgConfigParams, SetVpnNetworkProfile,
    SetVpnSocketBufferSizes, SetVpnTlsEnabled,
};
use std::ffi::{CStr, CString};
use std::sync::atomic::Ordering;
use std::sync::Mutex;
use tokio::net::{TcpListener, UdpSocket};

static FFI_TEST_LOCK: Mutex<()> = Mutex::new(());

struct VpnTlsGuard;
impl Drop for VpnTlsGuard {
    fn drop(&mut self) {
        SetVpnTlsEnabled(1);
    }
}

unsafe fn take_ffi_string(ptr: *mut std::os::raw::c_char) -> String {
    assert!(!ptr.is_null());
    let s = CStr::from_ptr(ptr).to_string_lossy().into_owned();
    FreeVpnString(ptr);
    s
}

#[test]
fn test_vpn_tls_toggle_ffi_and_status_json() {
    let _lock = FFI_TEST_LOCK.lock().unwrap();
    let _guard = VpnTlsGuard;

    // Default must be enabled (1)
    SetVpnTlsEnabled(1);
    assert_eq!(IsVpnTlsEnabled(), 1);
    let json_on: serde_json::Value = unsafe {
        serde_json::from_str(&take_ffi_string(GetVpnStatusJson())).unwrap()
    };
    assert_eq!(json_on["tls_enabled"], serde_json::Value::Bool(true));

    // Disable VPN TLS
    SetVpnTlsEnabled(0);
    assert_eq!(IsVpnTlsEnabled(), 0);
    let json_off: serde_json::Value = unsafe {
        serde_json::from_str(&take_ffi_string(GetVpnStatusJson())).unwrap()
    };
    assert_eq!(json_off["tls_enabled"], serde_json::Value::Bool(false));

    // Re-enable VPN TLS
    SetVpnTlsEnabled(1);
    assert_eq!(IsVpnTlsEnabled(), 1);
    let json_restored: serde_json::Value = unsafe {
        serde_json::from_str(&take_ffi_string(GetVpnStatusJson())).unwrap()
    };
    assert_eq!(json_restored["tls_enabled"], serde_json::Value::Bool(true));
}

#[test]
fn test_dot_853_filtering_respects_vpn_tls_toggle() {
    let _lock = FFI_TEST_LOCK.lock().unwrap();
    let _guard = VpnTlsGuard;

    let mut ipv4_dot = vec![0u8; 40];
    ipv4_dot[0] = 0x45;
    ipv4_dot[9] = 6;
    ipv4_dot[16..20].copy_from_slice(&[8, 8, 8, 8]);
    ipv4_dot[22..24].copy_from_slice(&853u16.to_be_bytes());

    let mut ipv6_dot = vec![0u8; 60];
    ipv6_dot[0] = 0x60;
    ipv6_dot[6] = 6;
    ipv6_dot[42..44].copy_from_slice(&853u16.to_be_bytes());

    // TLS enabled -> public DoT :853 passes through tunnel
    SetVpnTlsEnabled(1);
    let enabled = IsVpnTlsEnabled() != 0;
    assert!(!should_drop_outbound_dns_tcp(&ipv4_dot, enabled));
    assert!(!should_drop_outbound_dns_tcp(&ipv6_dot, enabled));
    assert!(handle_dot_or_blocked_tcp(&ipv4_dot).is_none());

    // TLS disabled -> DoT :853 silently dropped without TCP RST so Android falls back to :53
    SetVpnTlsEnabled(0);
    let disabled = IsVpnTlsEnabled() != 0;
    assert!(should_drop_outbound_dns_tcp(&ipv4_dot, disabled));
    assert!(should_drop_outbound_dns_tcp(&ipv6_dot, disabled));
    assert!(handle_dot_or_blocked_tcp(&ipv4_dot).is_none());

    // Plain DNS TCP :53 to public server stays allowed even with TLS disabled
    let mut ipv4_dns53 = ipv4_dot.clone();
    ipv4_dns53[22..24].copy_from_slice(&53u16.to_be_bytes());
    assert!(!should_drop_outbound_dns_tcp(&ipv4_dns53, disabled));
}

#[test]
fn test_prober_443_tcp_fallback_gating() {
    let _lock = FFI_TEST_LOCK.lock().unwrap();
    let _guard = VpnTlsGuard;

    assert_eq!(warp_tcp_fallback_port(2408, true), Some(443));
    assert_eq!(warp_tcp_fallback_port(4500, true), Some(443));
    assert_eq!(warp_tcp_fallback_port(2408, false), None);
    assert_eq!(warp_tcp_fallback_port(443, false), None);

    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();

    rt.block_on(async {
        // Bind a silent UDP socket and a live TCP listener on the SAME local port
        let tcp_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = tcp_listener.local_addr().unwrap();
        let _silent_udp = UdpSocket::bind(addr).await.unwrap();
        let endpoint = format!("127.0.0.1:{}", addr.port());

        // 1. With VPN TLS enabled, UDP timeout falls back to TCP SYN probe -> TCP_SYN_RESPONSIVE
        SetVpnTlsEnabled(1);
        let res_tls_on = probe_single_warp_endpoint(&endpoint, 0, 150).await;
        assert!(res_tls_on.is_alive);
        assert_eq!(res_tls_on.stage, "TCP_SYN_RESPONSIVE");
        // Drain the accepted TCP connection from the probe
        let _ = tokio::time::timeout(std::time::Duration::from_millis(50), tcp_listener.accept()).await;

        // 2. With VPN TLS disabled, TCP fallback MUST NOT run (never hits TcpListener)
        SetVpnTlsEnabled(0);
        let res_tls_off = probe_single_warp_endpoint(&endpoint, 0, 150).await;
        assert_ne!(
            res_tls_off.stage, "TCP_SYN_RESPONSIVE",
            "TCP fallback must be disabled when VPN TLS is off"
        );
        assert!(
            tokio::time::timeout(std::time::Duration::from_millis(30), tcp_listener.accept())
                .await
                .is_err(),
            "TcpListener must not receive any connection when VPN TLS is disabled"
        );

        // 3. Even when VPN TLS is disabled, direct UDP WireGuard response still works
        let wg_udp = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let wg_addr = wg_udp.local_addr().unwrap();
        let server_task = tokio::spawn(async move {
            let mut buf = [0u8; 256];
            if let Ok((_n, peer_addr)) = wg_udp.recv_from(&mut buf).await {
                let mut resp = vec![0u8; 92];
                resp[0] = 2; // WireGuard Handshake Response type 2
                let _ = wg_udp.send_to(&resp, peer_addr).await;
            }
        });
        let wg_endpoint = format!("127.0.0.1:{}", wg_addr.port());
        let res_udp = probe_single_warp_endpoint(&wg_endpoint, 0, 400).await;
        assert!(res_udp.is_alive);
        assert_eq!(res_udp.stage, "PROTOCOL_RECOGNIZED");
        let _ = server_task.await;
    });
}

#[test]
fn test_ffi_config_and_socket_buffer_overrides() {
    let _lock = FFI_TEST_LOCK.lock().unwrap();

    // Verify UDP buffer presets and overrides
    SetVpnSocketBufferSizes(0, 0);
    SetVpnNetworkProfile(1);
    assert!(MOBILE_NETWORK.load(Ordering::Relaxed));
    assert_eq!(effective_udp_tunnel_buffer_sizes(), (4 * 1024 * 1024, 2 * 1024 * 1024));

    SetVpnNetworkProfile(0);
    assert!(!MOBILE_NETWORK.load(Ordering::Relaxed));
    assert_eq!(effective_udp_tunnel_buffer_sizes(), (8 * 1024 * 1024, 4 * 1024 * 1024));

    assert_eq!(SetVpnSocketBufferSizes(1024 * 1024, 512 * 1024), 0);
    assert_eq!(effective_udp_tunnel_buffer_sizes(), (1024 * 1024, 512 * 1024));
    SetVpnSocketBufferSizes(0, 0);

    // Verify SetAwgConfigParams (14-arg C-ABI parity)
    let ep = CString::new("162.159.198.1:8095").unwrap();
    let priv_k = CString::new("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").unwrap();
    let pub_k = CString::new("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").unwrap();
    let peer_k = CString::new("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=").unwrap();
    let v4 = CString::new("172.16.0.2").unwrap();
    unsafe {
        SetAwgConfigParams(
            ep.as_ptr(),
            priv_k.as_ptr(),
            pub_k.as_ptr(),
            peer_k.as_ptr(),
            v4.as_ptr(),
            3,
            40,
            70,
            0,
            0,
            1,
            2,
            3,
            4,
        );
    }
    let awg_status = unsafe { take_ffi_string(GetAwgStatus()) };
    assert!(awg_status.contains("162.159.198.1:8095"));

    // Verify SetAwgConfig INI error populates GetVpnLastError
    let bad_ini = CString::new("[Interface]\nInvalidKey = 1\n").unwrap();
    assert_eq!(unsafe { SetAwgConfig(bad_ini.as_ptr()) }, -1);
    let last_err = unsafe { take_ffi_string(GetVpnLastError()) };
    assert!(!last_err.is_empty());

    // Verify GetVpnStats returns non-empty summary
    let stats = unsafe { take_ffi_string(GetVpnStats()) };
    assert!(stats.contains("awg="));
}
