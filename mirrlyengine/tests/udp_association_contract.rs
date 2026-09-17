use mirrlyengine::{
    masque::*,
    socks5::*,
    supervisor::{RouteKind, RouteSupervisor, ROUTE_SUPERVISOR},
};
use std::{sync::Arc, time::Duration};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
};
use tokio_util::sync::CancellationToken;
static TEST_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

#[test]
fn transport_matrix_and_pin() {
    let _guard = TEST_LOCK.lock().unwrap();
    let sup = RouteSupervisor::new();
    for (mode, expected) in [
        (UPLINK_WORKER, None),
        (999, None),
        (UPLINK_MASQUE, Some(RouteKind::Masque)),
        (UPLINK_AWG, Some(RouteKind::Awg)),
        (UPLINK_VLESS, Some(RouteKind::VlessDirect)),
        (UPLINK_HYBRID, Some(RouteKind::VlessDirect)),
        (UPLINK_WARP_CASCADE, Some(RouteKind::Masque)),
    ] {
        sup.set_intent(mode, 1, true);
        assert_eq!(sup.pin_udp_route().map(|pin| pin.route), expected);
    }
    sup.set_intent(UPLINK_WARP_CASCADE, 2, true);
    let pin = sup.pin_udp_route().unwrap();
    sup.transition_to(RouteKind::Awg, "test");
    assert_eq!(pin.route, RouteKind::Masque);
    assert_eq!(sup.pin_udp_route().unwrap().route, RouteKind::Awg);
    sup.transition_to(RouteKind::Worker, "test");
    assert!(!sup.supports_udp());
    sup.set_intent(UPLINK_WORKER, 3, false);
    assert!(!sup.udp_pin_is_current(&pin));
}

#[tokio::test]
async fn stale_and_cancelled_pins_never_acquire() {
    let _guard = TEST_LOCK.lock().unwrap();
    let sup = RouteSupervisor::new();
    sup.set_intent(UPLINK_VLESS, 1, false);
    let pin = sup.pin_udp_route().unwrap();
    let cancel = CancellationToken::new();
    cancel.cancel();
    assert!(sup
        .acquire_socks5_udp_uplink(&pin, "192.0.2.1:53", &cancel)
        .await
        .is_none());
    sup.set_intent(UPLINK_WORKER, 2, false);
    assert!(sup
        .acquire_socks5_udp_uplink(&pin, "192.0.2.1:53", &CancellationToken::new())
        .await
        .is_none());
}

async fn start_association(
    mode: i32,
) -> (
    TcpStream,
    Vec<u8>,
    CancellationToken,
    tokio::task::JoinHandle<()>,
) {
    ROUTE_SUPERVISOR.set_intent(mode, 100, false);
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let cancel = CancellationToken::new();
    let root = cancel.clone();
    let sessions = Arc::new(parking_lot::RwLock::new(cancel.clone()));
    let task = tokio::spawn(async move {
        run_socks5_server("127.0.0.1".into(), addr.port(), root, sessions, listener)
            .await
            .unwrap();
    });
    let mut client = TcpStream::connect(addr).await.unwrap();
    client.write_all(&[5, 1, 0]).await.unwrap();
    let mut auth = [0; 2];
    client.read_exact(&mut auth).await.unwrap();
    assert_eq!(auth, [5, 0]);
    client
        .write_all(&[5, 3, 0, 1, 0, 0, 0, 0, 0, 0])
        .await
        .unwrap();
    let mut reply = vec![0; 10];
    tokio::time::timeout(Duration::from_secs(2), client.read_exact(&mut reply))
        .await
        .unwrap()
        .unwrap();
    (client, reply, cancel, task)
}

#[tokio::test]
async fn worker_wire_reply_is_exact_command_not_supported() {
    let _guard = TEST_LOCK.lock().unwrap();
    let (mut client, reply, cancel, task) = start_association(UPLINK_WORKER).await;
    assert_eq!(reply, [5, 7, 0, 1, 0, 0, 0, 0, 0, 0]);
    assert_eq!(client.read(&mut [0; 1]).await.unwrap(), 0);
    cancel.cancel();
    task.await.unwrap();
}

#[tokio::test]
async fn tcp_close_releases_udp_association_socket() {
    let _guard = TEST_LOCK.lock().unwrap();
    let (client, reply, cancel, task) = start_association(UPLINK_MASQUE).await;
    assert_eq!(reply[1], 0);
    let port = u16::from_be_bytes([reply[8], reply[9]]);
    drop(client);
    tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            if tokio::net::UdpSocket::bind((std::net::Ipv4Addr::LOCALHOST, port))
                .await
                .is_ok()
            {
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    cancel.cancel();
    task.await.unwrap();
}

#[tokio::test]
async fn profile_change_closes_idle_association() {
    let _guard = TEST_LOCK.lock().unwrap();
    let (mut client, reply, cancel, task) = start_association(UPLINK_MASQUE).await;
    assert_eq!(reply[1], 0);
    ROUTE_SUPERVISOR.set_intent(UPLINK_WORKER, 101, false);
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(2), client.read(&mut [0; 1]))
            .await
            .unwrap()
            .unwrap(),
        0
    );
    cancel.cancel();
    task.await.unwrap();
}

#[test]
fn session_limits_and_replacement_ownership() {
    let _guard = TEST_LOCK.lock().unwrap();
    let baseline = ACTIVE_GLOBAL_UDP_SESSIONS.load(std::sync::atomic::Ordering::Relaxed);
    let cancel = CancellationToken::new();
    let mut table = UdpSessionTable::new();
    table.max_per_assoc = 1;
    table.max_connecting = 1;
    let (rx, token, old) = match table.get_or_create("a:53", &cancel, vec![1]) {
        SessionAcquireResult::New(rx, token, stats) => (rx, token, stats),
        _ => panic!(),
    };
    assert!(matches!(
        table.get_or_create("b:53", &cancel, vec![1]),
        SessionAcquireResult::Rejected(_)
    ));
    token.cancel();
    drop(rx);
    let (_rx, new_token, _) = match table.get_or_create("a:53", &cancel, vec![1]) {
        SessionAcquireResult::New(rx, token, stats) => (rx, token, stats),
        _ => panic!(),
    };
    table.remove_if_current("a:53", &old);
    assert!(!new_token.is_cancelled());
    assert_eq!(table.sessions.len(), 1);
    assert!(matches!(
        table.get_or_create("a:53", &cancel, vec![0; MAX_TARGET_BYTE_BUDGET + 1]),
        SessionAcquireResult::Rejected(_)
    ));
    for _ in 0..UDP_CHANNEL_CAPACITY - 1 {
        assert!(matches!(
            table.get_or_create("a:53", &cancel, vec![1]),
            SessionAcquireResult::Existing
        ));
    }
    assert!(matches!(
        table.get_or_create("a:53", &cancel, vec![1]),
        SessionAcquireResult::Rejected(_)
    ));
    drop(table);
    assert!(new_token.is_cancelled());
    assert_eq!(
        ACTIVE_GLOBAL_UDP_SESSIONS.load(std::sync::atomic::Ordering::Relaxed),
        baseline
    );
}

#[test]
fn source_endpoint_is_pinned() {
    let _guard = TEST_LOCK.lock().unwrap();
    let mut pin = UdpAssociationPin::new("192.0.2.1:9999".parse().unwrap(), None, 0);
    assert!(pin
        .validate_and_pin_sender("192.0.2.2:5000".parse().unwrap())
        .is_err());
    assert!(pin
        .validate_and_pin_sender("192.0.2.1:5000".parse().unwrap())
        .is_ok());
    assert!(pin
        .validate_and_pin_sender("192.0.2.1:5001".parse().unwrap())
        .is_err());
}

#[tokio::test]
async fn vless_targets_use_pinned_profile_without_fallback() {
    let _guard = TEST_LOCK.lock().unwrap();
    use mirrlyengine::vless::VLESS_CONFIG;
    let original = VLESS_CONFIG.read().clone();
    let primary = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let other = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let mut cfg = original.clone();
    cfg.server_address = "127.0.0.1".into();
    cfg.domain = "127.0.0.1".into();
    cfg.server_port = primary.local_addr().unwrap().port();
    cfg.transport = "tcp".into();
    cfg.security = "none".into();
    cfg.public_key.clear();
    cfg.flow.clear();
    cfg.uuid = [7; 16];
    *VLESS_CONFIG.write() = cfg.clone();
    let sup = RouteSupervisor::new();
    sup.set_intent(UPLINK_VLESS, 1, false);
    let pin = sup.pin_udp_route().unwrap();
    cfg.server_port = other.local_addr().unwrap().port();
    cfg.uuid = [8; 16];
    *VLESS_CONFIG.write() = cfg;
    for target in ["192.0.2.1:53", "192.0.2.2:443"] {
        let uplink = sup
            .acquire_socks5_udp_uplink(&pin, target, &CancellationToken::new())
            .await;
        assert!(uplink.is_some());
        let (mut socket, _) = primary.accept().await.unwrap();
        let mut header = [0; 19];
        socket.read_exact(&mut header).await.unwrap();
        assert_eq!(&header[1..17], &[7; 16]);
        assert_eq!(header[18], 2, "VLESS UDP command");
    }
    drop(primary);
    assert!(sup
        .acquire_socks5_udp_uplink(&pin, "192.0.2.3:53", &CancellationToken::new())
        .await
        .is_none());
    assert!(
        tokio::time::timeout(Duration::from_millis(50), other.accept())
            .await
            .is_err()
    );
    *VLESS_CONFIG.write() = original;
}

#[test]
fn idle_target_is_cancelled_and_releases_budget() {
    let _guard = TEST_LOCK.lock().unwrap();
    let mut table = UdpSessionTable::new();
    let (_rx, cancel, stats) = match table.get_or_create("a:53", &CancellationToken::new(), vec![1])
    {
        SessionAcquireResult::New(rx, cancel, stats) => (rx, cancel, stats),
        _ => panic!(),
    };
    stats.mark_established();
    *stats.last_activity.write() = std::time::Instant::now() - Duration::from_secs(61);
    table.sweep_idle(Duration::from_secs(60));
    assert!(cancel.is_cancelled());
    assert!(table.sessions.is_empty());
}

#[test]
fn loopback_source_must_match_tcp_peer() {
    let _guard = TEST_LOCK.lock().unwrap();
    let mut pin = UdpAssociationPin::new("127.0.0.1:9999".parse().unwrap(), None, 0);
    assert!(pin
        .validate_and_pin_sender("127.0.0.2:5000".parse().unwrap())
        .is_err());
    assert!(pin
        .validate_and_pin_sender("127.0.0.1:5000".parse().unwrap())
        .is_ok());
}
