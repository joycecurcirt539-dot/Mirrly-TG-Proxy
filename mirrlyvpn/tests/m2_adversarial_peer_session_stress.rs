//! Adversarial Challenge Test Harness for Milestone 2 (M2) — Peer Session & Inbound Reciprocal Pool
//!
//! Scope:
//! 1. Buffer Pool Exhaustion & Dynamic Allocation
//! 2. 1024 Buffer Recycling Pool Cap & Non-Blocking Drop
//! 3. TUN Sink Lossless Backpressure
//! 4. TUN Sink Disconnect & Reconnect Lifecycle
//! 5. Adversarial Malformed, Truncated & Replayed Packet Interleaving
//! 6. Orderly Shutdown Under Load

use mirrlyvpn::awg::{
    encapsulate_transport_packet, AwgConfig, AwgPeerSession, TransportKeys, WireGuardSession,
};
use std::sync::Arc;
use std::time::Duration;
use tokio::net::UdpSocket;

fn setup_test_keypair() -> (TransportKeys, TransportKeys) {
    let k1 = [0x42u8; 32];
    let k2 = [0x99u8; 32];
    let tx_keys = TransportKeys::new(k1, k2, 0x11223344, 0x55667788);
    let rx_keys = TransportKeys::new(k2, k1, 0x55667788, 0x11223344);
    (tx_keys, rx_keys)
}

/// Helper: construct a mock connected UDP pair and initialized `AwgPeerSession`
async fn setup_mock_peer_session() -> (UdpSocket, Arc<AwgPeerSession>, TransportKeys) {
    let (tx_keys, rx_keys) = setup_test_keypair();
    let server_sock = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let server_addr = server_sock.local_addr().unwrap();

    let client_sock = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let client_addr = client_sock.local_addr().unwrap();

    client_sock.connect(server_addr).await.unwrap();
    server_sock.connect(client_addr).await.unwrap();

    let client_cfg = AwgConfig::default();
    let session = Arc::new(WireGuardSession::new(client_cfg.clone(), Arc::new(rx_keys)));

    let peer = AwgPeerSession::new_with_session(
        server_addr,
        Arc::new(client_sock),
        session,
        client_cfg,
    );

    (server_sock, peer, tx_keys)
}

#[tokio::test]
async fn test_m2_buffer_pool_exhaustion_and_burst_traffic() {
    let (server_sock, peer, tx_keys) = setup_mock_peer_session().await;

    let (tun_tx, mut tun_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(1024);
    peer.set_tun_sink(Some(tun_tx));

    let burst_count = 500usize;
    for i in 0..burst_count {
        let payload = format!("BURST_PAYLOAD_{:05}", i).into_bytes();
        let wire_pkt = encapsulate_transport_packet(&tx_keys, 4, &payload)
            .expect("server encapsulation must succeed");
        server_sock.send(&wire_pkt).await.expect("send must succeed");
    }

    for i in 0..burst_count {
        let expected_payload = format!("BURST_PAYLOAD_{:05}", i).into_bytes();
        let received = tokio::time::timeout(Duration::from_millis(3000), tun_rx.recv())
            .await
            .expect("packet reception timed out")
            .expect("channel closed unexpectedly");
        assert_eq!(received, expected_payload, "Packet sequence mismatch at index {}", i);
    }

    let secondary_count = 100usize;
    for i in 0..secondary_count {
        let payload = format!("SECONDARY_RECYCLED_{:05}", i).into_bytes();
        let wire_pkt = encapsulate_transport_packet(&tx_keys, 4, &payload)
            .expect("server encapsulation must succeed");
        server_sock.send(&wire_pkt).await.expect("send must succeed");
    }

    for i in 0..secondary_count {
        let expected_payload = format!("SECONDARY_RECYCLED_{:05}", i).into_bytes();
        let received = tokio::time::timeout(Duration::from_millis(3000), tun_rx.recv())
            .await
            .expect("secondary packet reception timed out")
            .expect("channel closed unexpectedly");
        assert_eq!(received, expected_payload, "Secondary packet sequence mismatch at index {}", i);
    }

    peer.close();
}

#[tokio::test]
async fn test_m2_pool_cap_at_1024_and_excess_buffer_drop() {
    let (server_sock, peer, tx_keys) = setup_mock_peer_session().await;

    let (tun_tx, mut tun_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(2048);
    peer.set_tun_sink(Some(tun_tx));

    let total_pkts = 1500usize;
    for i in 0..total_pkts {
        // Note: prefix must not start with 0x45 ('E') or 0x60 ('`'), which
        // `validate_and_get_ip_len` parses as raw IPv4/IPv6 headers.
        let payload = format!("POOL_EXCESS_{:05}", i).into_bytes();
        let wire_pkt = encapsulate_transport_packet(&tx_keys, 4, &payload)
            .expect("server encapsulation must succeed");
        server_sock.send(&wire_pkt).await.expect("send must succeed");
        if i % 128 == 0 {
            tokio::task::yield_now().await;
        }
    }

    for i in 0..total_pkts {
        let expected = format!("POOL_EXCESS_{:05}", i).into_bytes();
        let received = match tokio::time::timeout(Duration::from_millis(2000), tun_rx.recv()).await {
            Ok(Some(pkt)) => pkt,
            Ok(None) => panic!("channel closed prematurely at index {}", i),
            Err(_) => panic!("draining timed out at index {} out of {}", i, total_pkts),
        };
        assert_eq!(received, expected, "Mismatch at index {}", i);
    }

    let probe_payload = b"POST_EXCESS_PROBE";
    let probe_wire = encapsulate_transport_packet(&tx_keys, 4, probe_payload).unwrap();
    server_sock.send(&probe_wire).await.unwrap();

    let probe_received = tokio::time::timeout(Duration::from_millis(1500), tun_rx.recv())
        .await
        .expect("probe reception timed out")
        .expect("channel closed");
    assert_eq!(probe_received, probe_payload);

    peer.close();
}

#[tokio::test]
async fn test_m2_tun_sink_backpressure_slow_consumer_lossless() {
    let (server_sock, peer, tx_keys) = setup_mock_peer_session().await;

    let (tun_tx, mut tun_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(4);
    peer.set_tun_sink(Some(tun_tx));

    let packet_count = 100usize;

    let send_handle = tokio::spawn(async move {
        for i in 0..packet_count {
            let payload = format!("BACKPRESSURE_PKT_{:03}", i).into_bytes();
            let wire_pkt = encapsulate_transport_packet(&tx_keys, 4, &payload)
                .expect("encapsulation failed");
            server_sock.send(&wire_pkt).await.expect("send failed");
            tokio::task::yield_now().await;
        }
    });

    let mut received_packets = Vec::with_capacity(packet_count);
    for _ in 0..packet_count {
        tokio::time::sleep(Duration::from_millis(2)).await;
        let pkt = tokio::time::timeout(Duration::from_millis(4000), tun_rx.recv())
            .await
            .expect("slow consumer timed out waiting for packet")
            .expect("tun_rx channel closed");
        received_packets.push(pkt);
    }

    send_handle.await.expect("sender task panicked");

    assert_eq!(received_packets.len(), packet_count);
    for (i, pkt) in received_packets.iter().enumerate() {
        let expected = format!("BACKPRESSURE_PKT_{:03}", i).into_bytes();
        assert_eq!(pkt, &expected, "Out of order or dropped packet at index {}", i);
    }

    peer.close();
}

#[tokio::test]
async fn test_m2_tun_sink_disconnection_and_reconnection() {
    let (server_sock, peer, tx_keys) = setup_mock_peer_session().await;

    let (tun_tx_1, mut tun_rx_1) = tokio::sync::mpsc::channel::<Vec<u8>>(16);
    peer.set_tun_sink(Some(tun_tx_1));

    let wire_1 = encapsulate_transport_packet(&tx_keys, 4, b"STAGE_1").unwrap();
    server_sock.send(&wire_1).await.unwrap();
    let recv_1 = tun_rx_1.recv().await.unwrap();
    assert_eq!(recv_1, b"STAGE_1");

    drop(tun_rx_1);

    let wire_dead = encapsulate_transport_packet(&tx_keys, 4, b"STAGE_DEAD").unwrap();
    server_sock.send(&wire_dead).await.unwrap();
    tokio::time::sleep(Duration::from_millis(50)).await;

    let (tun_tx_2, mut tun_rx_2) = tokio::sync::mpsc::channel::<Vec<u8>>(16);
    peer.set_tun_sink(Some(tun_tx_2));

    let wire_2 = encapsulate_transport_packet(&tx_keys, 4, b"STAGE_2").unwrap();
    server_sock.send(&wire_2).await.unwrap();
    let recv_2 = tokio::time::timeout(Duration::from_millis(1500), tun_rx_2.recv())
        .await
        .expect("timed out waiting for reconnected sink")
        .expect("tun_rx_2 closed");
    assert_eq!(recv_2, b"STAGE_2");

    peer.close();
}

#[tokio::test]
async fn test_m2_adversarial_malformed_and_garbage_interleaved() {
    let (server_sock, peer, tx_keys) = setup_mock_peer_session().await;

    let (tun_tx, mut tun_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(64);
    peer.set_tun_sink(Some(tun_tx));

    let wire_valid_1 = encapsulate_transport_packet(&tx_keys, 4, b"VALID_1").unwrap();
    server_sock.send(&wire_valid_1).await.unwrap();

    server_sock.send(&[0xFF]).await.unwrap();
    server_sock.send(&[0x01, 0x02, 0x03]).await.unwrap();
    server_sock.send(&[0x04, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06]).await.unwrap();

    let mut corrupt_h4 = encapsulate_transport_packet(&tx_keys, 4, b"CORRUPT_H4").unwrap();
    corrupt_h4[0] = 0x99;
    server_sock.send(&corrupt_h4).await.unwrap();

    let mut corrupt_tag = encapsulate_transport_packet(&tx_keys, 4, b"CORRUPT_TAG").unwrap();
    let last_idx = corrupt_tag.len() - 1;
    corrupt_tag[last_idx] ^= 0xFF;
    server_sock.send(&corrupt_tag).await.unwrap();

    let wire_valid_2 = encapsulate_transport_packet(&tx_keys, 4, b"VALID_2").unwrap();
    server_sock.send(&wire_valid_2).await.unwrap();

    server_sock.send(&wire_valid_2).await.unwrap();

    let wire_valid_3 = encapsulate_transport_packet(&tx_keys, 4, b"VALID_3").unwrap();
    server_sock.send(&wire_valid_3).await.unwrap();

    let r1 = tokio::time::timeout(Duration::from_millis(1500), tun_rx.recv()).await.unwrap().unwrap();
    assert_eq!(r1, b"VALID_1");

    let r2 = tokio::time::timeout(Duration::from_millis(1500), tun_rx.recv()).await.unwrap().unwrap();
    assert_eq!(r2, b"VALID_2");

    let r3 = tokio::time::timeout(Duration::from_millis(1500), tun_rx.recv()).await.unwrap().unwrap();
    assert_eq!(r3, b"VALID_3");

    let stray = tokio::time::timeout(Duration::from_millis(100), tun_rx.recv()).await;
    assert!(stray.is_err(), "No stray packets should leak into TUN sink");

    peer.close();
}

#[tokio::test]
async fn test_m2_cancellation_under_heavy_concurrency() {
    let (server_sock, peer, tx_keys) = setup_mock_peer_session().await;

    let (tun_tx, mut tun_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(1024);
    peer.set_tun_sink(Some(tun_tx));

    let cancel = peer.cancel_token.clone();
    let tx_keys_clone = tx_keys;
    let sender_handle = tokio::spawn(async move {
        let mut idx = 0u64;
        while !cancel.is_cancelled() {
            let payload = format!("STREAM_{}", idx).into_bytes();
            if let Ok(pkt) = encapsulate_transport_packet(&tx_keys_clone, 4, &payload) {
                let _ = server_sock.send(&pkt).await;
            }
            idx += 1;
            tokio::task::yield_now().await;
        }
    });

    let _ = tun_rx.recv().await;

    peer.close();
    assert!(peer.cancel_token.is_cancelled());

    let _ = tokio::time::timeout(Duration::from_millis(1500), sender_handle)
        .await
        .expect("Sender task failed to stop upon cancellation");
}
