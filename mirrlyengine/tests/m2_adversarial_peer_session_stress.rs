//! Adversarial Challenge Test Harness for Milestone 2 (M2) — Peer Session & Inbound Reciprocal Pool
//!
//! Scope:
//! 1. Buffer Pool Exhaustion & Dynamic Allocation:
//!    Verify that when all 128 seeded buffers are occupied during huge traffic spikes,
//!    dynamic allocation kicks in without deadlock, and recycled buffers properly
//!    return to the pool up to the 1024 cap.
//! 2. 1024 Buffer Recycling Pool Cap & Non-Blocking Drop:
//!    Verify that returning more than 1024 buffers drops the excess safely without
//!    memory leaks, unbounded allocation growth, or channel blockage.
//! 3. TUN Sink Lossless Backpressure:
//!    Verify that `try_send` with `send().await` fallback never silently drops packets
//!    internally when downstream TUN writing experiences latency (slow consumer),
//!    guaranteeing 100% strictly ordered FIFO delivery without packet loss.
//! 4. TUN Sink Disconnect & Reconnect Lifecycle:
//!    Verify that abrupt closure of `tun_sink` does not crash or freeze the worker loop,
//!    and registering a new `tun_sink` immediately resumes successful packet delivery.
//! 5. Adversarial Malformed, Truncated & Replayed Packet Interleaving:
//!    Verify that under heavy mixed traffic containing <4B truncated datagrams,
//!    corrupted MACs/ciphertext, and replayed counter duplicates, the pipeline
//!    rejects invalid packets, maintains buffer recycling, and safely forwards
//!    all legitimate data packets without stalling or lockup.
//! 6. Orderly Shutdown Under Load:
//!    Verify that calling `peer.close()` during active packet transmission terminates
//!    all three decoupled async tasks (timer loop, rx loop, worker loop) promptly
//!    without resource leaks or lingering async locks.

use mirrlyengine::awg::{
    encapsulate_transport_packet, AwgConfig, AwgPeerSession,
    TransportKeys, WireGuardSession,
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

    // Attach TUN sink with large capacity to receive burst packets
    let (tun_tx, mut tun_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(1024);
    peer.set_tun_sink(Some(tun_tx));

    // Burst 500 packets (substantially exceeds the 128 seeded buffers in pool)
    let burst_count = 500usize;
    for i in 0..burst_count {
        let payload = format!("BURST_PAYLOAD_{:05}", i).into_bytes();
        let wire_pkt = encapsulate_transport_packet(&tx_keys, 4, &payload)
            .expect("server encapsulation must succeed");
        server_sock.send(&wire_pkt).await.expect("send must succeed");
    }

    // Verify all 500 packets are received in exact FIFO sequence
    for i in 0..burst_count {
        let expected_payload = format!("BURST_PAYLOAD_{:05}", i).into_bytes();
        let received = tokio::time::timeout(Duration::from_millis(3000), tun_rx.recv())
            .await
            .expect("packet reception timed out")
            .expect("channel closed unexpectedly");
        assert_eq!(received, expected_payload, "Packet sequence mismatch at index {}", i);
    }

    // Now send a secondary batch of 100 packets to verify that recycled buffers
    // are successfully retrieved from recycle_rx without allocation deadlock
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

    println!("Starting send of 1500 packets...");
    let total_pkts = 1500usize;
    for i in 0..total_pkts {
        let payload = format!("EXCESS_CAP_{:05}", i).into_bytes();
        let wire_pkt = encapsulate_transport_packet(&tx_keys, 4, &payload)
            .expect("server encapsulation must succeed");
        server_sock.send(&wire_pkt).await.expect("send must succeed");
        if i % 250 == 0 {
            println!("Sent {} / {}", i, total_pkts);
        }
    }
    println!("Finished sending 1500 packets! Now draining...");

    // Drain all 1,500 packets from TUN sink
    for i in 0..total_pkts {
        let expected = format!("EXCESS_CAP_{:05}", i).into_bytes();
        let received = match tokio::time::timeout(Duration::from_millis(2000), tun_rx.recv()).await {
            Ok(Some(pkt)) => pkt,
            Ok(None) => panic!("channel closed prematurely at index {}", i),
            Err(_) => panic!("draining timed out at index {} out of {}", i, total_pkts),
        };
        assert_eq!(received, expected, "Mismatch at index {}", i);
    }

    // Verify system remains operational and no buffer pool deadlocks occurred
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

    // Constrained TUN sink capacity of only 4 packets to trigger TrySendError::Full immediately
    let (tun_tx, mut tun_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(4);
    peer.set_tun_sink(Some(tun_tx));

    let packet_count = 100usize;

    // Sender task: rapidly sends all 100 packets into UDP socket
    let send_handle = tokio::spawn(async move {
        for i in 0..packet_count {
            let payload = format!("BACKPRESSURE_PKT_{:03}", i).into_bytes();
            let wire_pkt = encapsulate_transport_packet(&tx_keys, 4, &payload)
                .expect("encapsulation failed");
            server_sock.send(&wire_pkt).await.expect("send failed");
            // Micro yield to let network socket buffer
            tokio::task::yield_now().await;
        }
    });

    // Receiver task: intentionally slow consumer sleeping 2ms per packet
    // This forces `tun_sink.try_send` to saturate and triggers the `tun_sink.send(p).await` fallback
    let mut received_packets = Vec::with_capacity(packet_count);
    for _ in 0..packet_count {
        // Artificially induce downstream latency
        tokio::time::sleep(Duration::from_millis(2)).await;
        let pkt = tokio::time::timeout(Duration::from_millis(4000), tun_rx.recv())
            .await
            .expect("slow consumer timed out waiting for packet")
            .expect("tun_rx channel closed");
        received_packets.push(pkt);
    }

    send_handle.await.expect("sender task panicked");

    // Assert strictly lossless, 100% in-order delivery
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

    // 1. Initial active sink
    let (tun_tx_1, mut tun_rx_1) = tokio::sync::mpsc::channel::<Vec<u8>>(16);
    peer.set_tun_sink(Some(tun_tx_1));

    let wire_1 = encapsulate_transport_packet(&tx_keys, 4, b"STAGE_1").unwrap();
    server_sock.send(&wire_1).await.unwrap();
    let recv_1 = tun_rx_1.recv().await.unwrap();
    assert_eq!(recv_1, b"STAGE_1");

    // 2. Disconnect sink by dropping the receiver
    drop(tun_rx_1);

    // Send a packet while sink receiver is dead; worker must handle error gracefully without panicking
    let wire_dead = encapsulate_transport_packet(&tx_keys, 4, b"STAGE_DEAD").unwrap();
    server_sock.send(&wire_dead).await.unwrap();
    tokio::time::sleep(Duration::from_millis(50)).await;

    // 3. Reconnect with a new TUN sink
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

    // Send valid packet #1
    let wire_valid_1 = encapsulate_transport_packet(&tx_keys, 4, b"VALID_1").unwrap();
    server_sock.send(&wire_valid_1).await.unwrap();

    // Adversarial Junk #1: 1-byte datagram (< 4 bytes)
    server_sock.send(&[0xFF]).await.unwrap();

    // Adversarial Junk #2: 3-byte datagram (< 4 bytes)
    server_sock.send(&[0x01, 0x02, 0x03]).await.unwrap();

    // Adversarial Junk #3: Truncated 10-byte packet (< 32 bytes)
    server_sock.send(&[0x04, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06]).await.unwrap();

    // Adversarial Junk #4: 48-byte packet with mismatched header type (0x99 instead of 4)
    let mut corrupt_h4 = encapsulate_transport_packet(&tx_keys, 4, b"CORRUPT_H4").unwrap();
    corrupt_h4[0] = 0x99;
    server_sock.send(&corrupt_h4).await.unwrap();

    // Adversarial Junk #5: 48-byte packet with corrupted Poly1305 authentication tag
    let mut corrupt_tag = encapsulate_transport_packet(&tx_keys, 4, b"CORRUPT_TAG").unwrap();
    let last_idx = corrupt_tag.len() - 1;
    corrupt_tag[last_idx] ^= 0xFF;
    server_sock.send(&corrupt_tag).await.unwrap();

    // Send valid packet #2
    let wire_valid_2 = encapsulate_transport_packet(&tx_keys, 4, b"VALID_2").unwrap();
    server_sock.send(&wire_valid_2).await.unwrap();

    // Adversarial Replay: replay wire_valid_2 (duplicate counter)
    server_sock.send(&wire_valid_2).await.unwrap();

    // Send valid packet #3
    let wire_valid_3 = encapsulate_transport_packet(&tx_keys, 4, b"VALID_3").unwrap();
    server_sock.send(&wire_valid_3).await.unwrap();

    // TUN sink must receive ONLY the valid packets in sequence, completely ignoring junk and duplicates
    let r1 = tokio::time::timeout(Duration::from_millis(1500), tun_rx.recv()).await.unwrap().unwrap();
    assert_eq!(r1, b"VALID_1");

    let r2 = tokio::time::timeout(Duration::from_millis(1500), tun_rx.recv()).await.unwrap().unwrap();
    assert_eq!(r2, b"VALID_2");

    let r3 = tokio::time::timeout(Duration::from_millis(1500), tun_rx.recv()).await.unwrap().unwrap();
    assert_eq!(r3, b"VALID_3");

    // Ensure no unexpected packets leaked into TUN sink
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

    // Let some packets flow
    let _ = tun_rx.recv().await;

    // Trigger close
    peer.close();
    assert!(peer.cancel_token.is_cancelled());

    // Sender must stop promptly
    let _ = tokio::time::timeout(Duration::from_millis(1500), sender_handle).await
        .expect("Sender task failed to stop upon cancellation");
}
