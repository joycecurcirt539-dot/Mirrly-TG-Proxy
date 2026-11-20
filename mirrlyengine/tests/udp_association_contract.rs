use mirrlyengine::{
    config::SOCKS5_UPLINK_FLOWSEAL,
    socks5::*,
    supervisor::{RouteKind, RouteSupervisor, ROUTE_SUPERVISOR, UPLINK_WORKER},
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
    for mode in [UPLINK_WORKER, SOCKS5_UPLINK_FLOWSEAL, 4, 999] {
        sup.set_intent(mode, 1, true);
        assert!(sup.pin_udp_route().is_none());
        assert!(!sup.supports_udp());
        assert_eq!(sup.get_active_route(), RouteKind::None);
    }
    sup.transition_to(RouteKind::None, "test");
    sup.set_intent(UPLINK_WORKER, 3, false);
    assert!(!sup.supports_udp());
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
    let initial_gen = ROUTE_SUPERVISOR.get_generation();
    let watch_cancel = cancel.clone();
    tokio::spawn(async move {
        loop {
            if watch_cancel.is_cancelled() {
                break;
            }
            if ROUTE_SUPERVISOR.get_generation() != initial_gen {
                watch_cancel.cancel();
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    });
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
    let mut reply = Vec::new();
    let mut buf = [0u8; 10];
    let _ = tokio::time::timeout(Duration::from_secs(2), async {
        while reply.len() < 10 {
            match client.read(&mut buf[..10 - reply.len()]).await {
                Ok(0) => break,
                Ok(n) => reply.extend_from_slice(&buf[..n]),
                Err(e) if e.kind() == std::io::ErrorKind::ConnectionReset => break,
                Err(e) => panic!("read failed: {:?}", e),
            }
        }
    })
    .await;
    (client, reply, cancel, task)
}

#[tokio::test]
async fn worker_wire_reply_is_exact_command_not_supported() {
    let _guard = TEST_LOCK.lock().unwrap();
    for mode in [UPLINK_WORKER, SOCKS5_UPLINK_FLOWSEAL, 4] {
        let (mut client, reply, cancel, task) = start_association(mode).await;
        assert_eq!(
            reply,
            vec![5, 7, 0, 1, 0, 0, 0, 0, 0, 0],
            "expected exact SOCKS5 0x07 rejection for mode={}, got {:?}",
            mode,
            reply
        );
        let eof_or_reset = match client.read(&mut [0; 1]).await {
            Ok(0) => true,
            Err(e) if e.kind() == std::io::ErrorKind::ConnectionReset => true,
            _ => false,
        };
        assert!(eof_or_reset, "expected EOF or ConnectionReset for mode={}", mode);
        cancel.cancel();
        task.await.unwrap();
    }
}

#[test]
fn session_limits_and_replacement_ownership() {
    let _guard = TEST_LOCK.lock().unwrap();
    let cancel = CancellationToken::new();
    let mut table = UdpSessionTable::new(1, 1);
    
    // Acquire slot for target "a:53"
    let (rx, token, old) = match table.get_or_create("a:53", &cancel, vec![1]) {
        SessionAcquireResult::New(rx, token, stats) => (rx, token, stats),
        _ => panic!("Expected New session for target a:53"),
    };
    assert!(!token.is_cancelled());

    // Exceeds max_connecting limit (1) -> rejected
    assert!(matches!(
        table.get_or_create("b:53", &cancel, vec![1]),
        SessionAcquireResult::Rejected(_)
    ));

    // Marking established frees the connecting slot, but active conversation protects from eviction
    old.mark_established();
    old.on_packet_sent(0);
    old.on_packet_sent(0);
    old.on_packet_sent(0);
    old.on_packet_received();
    assert!(matches!(
        table.get_or_create("b:53", &cancel, vec![1]),
        SessionAcquireResult::Rejected(_)
    ));

    // Existing active session receives packet as unit variant SessionAcquireResult::Existing
    assert!(matches!(
        table.get_or_create("a:53", &cancel, vec![1]),
        SessionAcquireResult::Existing
    ));

    // When the old token is cancelled and rx is dropped, next call removes stale entry and creates New
    token.cancel();
    drop(rx);
    let (_rx2, token2, _old2) = match table.get_or_create("a:53", &cancel, vec![2]) {
        SessionAcquireResult::New(rx2, token2, stats2) => (rx2, token2, stats2),
        _ => panic!("Expected New session after cancellation of previous session"),
    };
    assert!(token.is_cancelled());
    assert!(!token2.is_cancelled());

    // Dropping table cancels all active session tokens
    drop(table);
    assert!(token2.is_cancelled());
}
