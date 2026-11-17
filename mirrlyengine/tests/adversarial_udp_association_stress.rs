use mirrlyengine::socks5::*;
use mirrlyengine::supervisor::{ROUTE_SUPERVISOR, UPLINK_AWG, UPLINK_WORKER};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio_util::sync::CancellationToken;

static UDP_TEST_LOCK: parking_lot::Mutex<()> = parking_lot::Mutex::new(());

#[test]
fn test_udp_session_budget_and_saturation() {
    let _lock = UDP_TEST_LOCK.lock();
    let cancel = CancellationToken::new();
    let mut table = UdpSessionTable::new(5, 5);

    // Initial packet of 1024 bytes -> New session
    let res = table.get_or_create("target1:53", &cancel, vec![0u8; 1024]);
    let (rx, token, stats) = match res {
        SessionAcquireResult::New(rx, token, stats) => (rx, token, stats),
        other => panic!("Expected New, got {:?}", other),
    };
    assert_eq!(stats.buffered_bytes.load(std::sync::atomic::Ordering::Relaxed), 1024);

    // Queue packets until budget (MAX_TARGET_BYTE_BUDGET = 128KB) is reached
    // We already have 1024 bytes. Queue another 127 * 1024 bytes.
    let res2 = table.get_or_create("target1:53", &cancel, vec![0u8; 127 * 1024]);
    assert!(matches!(res2, SessionAcquireResult::Existing));
    assert_eq!(
        stats.buffered_bytes.load(std::sync::atomic::Ordering::Relaxed),
        MAX_TARGET_BYTE_BUDGET
    );

    // Any additional packet must be rejected because budget is exhausted
    let res3 = table.get_or_create("target1:53", &cancel, vec![0u8; 1]);
    assert!(matches!(res3, SessionAcquireResult::Rejected(_)));

    // Draining packets via on_packet_sent restores the budget
    stats.on_packet_sent(64 * 1024);
    assert_eq!(
        stats.buffered_bytes.load(std::sync::atomic::Ordering::Relaxed),
        64 * 1024
    );

    // Now we can push up to 64KB again
    let res4 = table.get_or_create("target1:53", &cancel, vec![0u8; 32 * 1024]);
    assert!(matches!(res4, SessionAcquireResult::Existing));

    drop(rx);
    token.cancel();
}

#[test]
fn test_udp_active_conversation_eviction_immunity() {
    let _lock = UDP_TEST_LOCK.lock();
    let cancel = CancellationToken::new();
    // Capacity of table: max 2 sessions, max 2 connecting
    let mut table = UdpSessionTable::new(2, 2);

    // Session 1: target1
    let (_rx1, _token1, stats1) = match table.get_or_create("target1:53", &cancel, vec![0u8; 100]) {
        SessionAcquireResult::New(rx, tok, st) => (rx, tok, st),
        _ => panic!("Expected New"),
    };

    // Session 2: target2
    let (_rx2, _token2, stats2) = match table.get_or_create("target2:53", &cancel, vec![0u8; 100]) {
        SessionAcquireResult::New(rx, tok, st) => (rx, tok, st),
        _ => panic!("Expected New"),
    };

    // Make Session 1 an active conversation: >=3 sent and >=1 recv
    stats1.mark_established();
    stats1.on_packet_sent(0);
    stats1.on_packet_sent(0);
    stats1.on_packet_sent(0);
    stats1.on_packet_received();
    assert!(stats1.is_active_conversation(std::time::Instant::now()));

    // Make Session 2 established but NOT an active conversation (0 recv)
    stats2.mark_established();
    stats2.on_packet_sent(0);
    assert!(!stats2.is_active_conversation(std::time::Instant::now()));

    // Now attempt to add Session 3: table is at capacity (2).
    // Session 1 is protected from eviction (active conversation).
    // Session 2 is eligible for eviction and should be replaced!
    let res3 = table.get_or_create("target3:53", &cancel, vec![0u8; 100]);
    assert!(matches!(res3, SessionAcquireResult::New(_, _, _)));

    // Session 1 still exists
    let res_check1 = table.get_or_create("target1:53", &cancel, vec![0u8; 10]);
    assert!(matches!(res_check1, SessionAcquireResult::Existing));

    // Session 2 was evicted and cancelled
    assert!(_token2.is_cancelled());
}

#[tokio::test]
async fn test_concurrent_udp_associations_and_rapid_supervisor_flip() {
    let _lock = UDP_TEST_LOCK.lock();

    // Start with UPLINK_AWG
    ROUTE_SUPERVISOR.set_intent(UPLINK_AWG, 200, false);
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();

    let root_cancel = CancellationToken::new();
    let sessions_token = Arc::new(parking_lot::RwLock::new(root_cancel.clone()));

    let server_cancel = root_cancel.clone();
    let server_task = tokio::spawn(async move {
        let _ = run_socks5_server(
            "127.0.0.1".into(),
            addr.port(),
            server_cancel,
            sessions_token,
            listener,
        )
        .await;
    });

    // Spawn 5 clients attempting UDP association
    let mut client_tasks = Vec::new();
    for i in 0..5 {
        let task = tokio::spawn(async move {
            let mut client = match TcpStream::connect(addr).await {
                Ok(c) => c,
                Err(_) => return,
            };
            // Handshake
            if client.write_all(&[5, 1, 0]).await.is_err() {
                return;
            }
            let mut auth = [0u8; 2];
            if client.read_exact(&mut auth).await.is_err() || auth != [5, 0] {
                return;
            }
            // UDP Associate command
            if client.write_all(&[5, 3, 0, 1, 0, 0, 0, 0, 0, 0]).await.is_err() {
                return;
            }
            let mut reply = [0u8; 10];
            let _ = tokio::time::timeout(Duration::from_millis(500), client.read_exact(&mut reply)).await;
            
            // Hold connection briefly
            tokio::time::sleep(Duration::from_millis(50 * (i + 1))).await;
        });
        client_tasks.push(task);
    }

    // While clients are associating, simulate sudden network generation change
    tokio::time::sleep(Duration::from_millis(30)).await;
    ROUTE_SUPERVISOR.set_intent(UPLINK_WORKER, 201, false);

    // Wait for clients
    for ct in client_tasks {
        let _ = ct.await;
    }

    // Stop server
    root_cancel.cancel();
    let _ = server_task.await;
}
