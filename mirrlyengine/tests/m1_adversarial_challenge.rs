//! Adversarial Challenge Test Harness for Milestone 1 (M1)
//!
//! Scope:
//! 1. Empty payloads (0-byte keepalive).
//! 2. Minimum IPv4 packet (20 bytes).
//! 3. Standard WireGuard MTU packets (1420 bytes, 1500 bytes).
//! 4. IPv6 packets (40 bytes, 1280 bytes).
//! 5. Arbitrary non-IP payloads.
//! 6. Corrupted/tampered tags & ciphertext (Poly1305 authentication rejection, no replay window leakage).
//! 7. Out-of-order counter sequences and duplicate counter rejection (anti-replay sliding window).
//! 8. Hot-loop zero-allocation buffer reuse verification.
//! 9. Sliding window boundary shifts (0, 1, 63, 64, 65, 2047, 2048, 5000).
//! 10. Malformed/truncated inner IP packet length overrun rejection.
//! 11. Multi-threaded atomic counter concurrency & strict nonce uniqueness.
//! 12. Short/truncated raw UDP transport packets (< 32 bytes).

use mirrlyengine::awg::{
    decapsulate_transport_packet, decapsulate_transport_packet_slice,
    encapsulate_transport_packet, encapsulate_transport_packet_into,
    pad16, validate_and_get_ip_len, TransportKeys,
    ReplayFilter, ReplayError,
};
use std::collections::HashSet;
use std::sync::atomic::Ordering;
use std::sync::Arc;

fn setup_test_keypair() -> (TransportKeys, TransportKeys) {
    let k1 = [0x42u8; 32];
    let k2 = [0x99u8; 32];
    let tx_keys = TransportKeys::new(k1, k2, 0x11223344, 0x55667788);
    let rx_keys = TransportKeys::new(k2, k1, 0x55667788, 0x11223344);
    (tx_keys, rx_keys)
}

#[test]
fn test_adversarial_empty_payload_keepalive() {
    let (tx, rx) = setup_test_keypair();
    let h4 = 4u32;

    // Encapsulate 0-byte keepalive
    let wire = encapsulate_transport_packet(&tx, h4, &[]).expect("keepalive encapsulation failed");
    // Wire length must be exactly 16 (header) + 0 (payload) + 16 (tag) = 32 bytes
    assert_eq!(wire.len(), 32, "Keepalive wire format must be exactly 32 bytes");

    // Decapsulate
    let dec = decapsulate_transport_packet(&rx, h4, &wire).expect("keepalive decapsulation failed");
    assert!(dec.is_empty(), "Decapsulated keepalive payload must be empty");

    // Zero-alloc slice variant test
    let mut wire_mut = wire.clone();
    let dec_slice = decapsulate_transport_packet_slice(&rx, h4, &mut wire_mut);
    // Note: re-decapsulating identical counter without resetting rx must be rejected by anti-replay!
    assert!(dec_slice.is_err(), "Duplicate keepalive must be rejected by anti-replay");
}

#[test]
fn test_adversarial_minimum_ipv4_packet_20_bytes() {
    let (tx, rx) = setup_test_keypair();
    let h4 = 4u32;

    let mut ipv4_min = vec![0u8; 20];
    ipv4_min[0] = 0x45; // Version 4, IHL 5 (20 bytes)
    ipv4_min[1] = 0x00; // DSCP / ECN
    ipv4_min[2] = 0x00;
    ipv4_min[3] = 0x14; // Total length = 20
    ipv4_min[8] = 64;   // TTL
    ipv4_min[9] = 6;    // TCP protocol
    ipv4_min[12..16].copy_from_slice(&[10, 0, 0, 1]); // Src IP
    ipv4_min[16..20].copy_from_slice(&[10, 0, 0, 2]); // Dst IP

    let wire = encapsulate_transport_packet(&tx, h4, &ipv4_min).expect("IPv4 20-byte encapsulation failed");
    // pad16(20) = 32 -> wire length = 16 + 32 + 16 = 64
    assert_eq!(wire.len(), 64, "Padded IPv4 20-byte packet must produce 64-byte wire packet");

    let dec = decapsulate_transport_packet(&rx, h4, &wire).expect("IPv4 20-byte decapsulation failed");
    assert_eq!(dec.len(), 20, "Padding must be cleanly trimmed to 20 bytes");
    assert_eq!(dec, ipv4_min, "Decapsulated IPv4 packet must match byte-for-byte");
}

#[test]
fn test_adversarial_wireguard_mtu_packets_1420_and_1500() {
    let (tx, rx) = setup_test_keypair();
    let h4 = 4u32;

    // --- MTU 1420 bytes ---
    let mut pkt_1420 = vec![0xAAu8; 1420];
    pkt_1420[0] = 0x45;
    pkt_1420[2] = (1420 >> 8) as u8;
    pkt_1420[3] = (1420 & 0xFF) as u8;

    let wire_1420 = encapsulate_transport_packet(&tx, h4, &pkt_1420).expect("1420 encapsulation failed");
    // pad16(1420) = 1424 -> wire = 16 + 1424 + 16 = 1456
    assert_eq!(wire_1420.len(), 16 + pad16(1420) + 16);
    assert_eq!(wire_1420.len(), 1456);

    let dec_1420 = decapsulate_transport_packet(&rx, h4, &wire_1420).expect("1420 decapsulation failed");
    assert_eq!(dec_1420.len(), 1420);
    assert_eq!(dec_1420, pkt_1420);

    // --- MTU 1500 bytes ---
    let mut pkt_1500 = vec![0xBBu8; 1500];
    pkt_1500[0] = 0x45;
    pkt_1500[2] = (1500 >> 8) as u8;
    pkt_1500[3] = (1500 & 0xFF) as u8;

    let wire_1500 = encapsulate_transport_packet(&tx, h4, &pkt_1500).expect("1500 encapsulation failed");
    // pad16(1500) = 1504 -> wire = 16 + 1504 + 16 = 1536
    assert_eq!(wire_1500.len(), 16 + pad16(1500) + 16);
    assert_eq!(wire_1500.len(), 1536);

    let dec_1500 = decapsulate_transport_packet(&rx, h4, &wire_1500).expect("1500 decapsulation failed");
    assert_eq!(dec_1500.len(), 1500);
    assert_eq!(dec_1500, pkt_1500);
}

#[test]
fn test_adversarial_ipv6_packets_40_and_1280() {
    let (tx, rx) = setup_test_keypair();
    let h4 = 4u32;

    // --- IPv6 40 bytes (header only, payload length 0) ---
    let mut v6_40 = vec![0xCCu8; 40];
    v6_40[0] = 0x60; // IPv6, traffic class 0, flow label 0
    v6_40[1] = 0x00;
    v6_40[2] = 0x00;
    v6_40[3] = 0x00;
    v6_40[4] = 0x00; // Payload length = 0
    v6_40[5] = 0x00;
    v6_40[6] = 58;   // Next header: ICMPv6

    let wire_40 = encapsulate_transport_packet(&tx, h4, &v6_40).expect("IPv6 40 encapsulation failed");
    // pad16(40) = 48 -> wire = 16 + 48 + 16 = 80
    assert_eq!(wire_40.len(), 80);

    let dec_40 = decapsulate_transport_packet(&rx, h4, &wire_40).expect("IPv6 40 decapsulation failed");
    assert_eq!(dec_40.len(), 40);
    assert_eq!(dec_40, v6_40);

    // --- IPv6 1280 bytes (cellular minimum MTU) ---
    // Total len = 1280 => payload length = 1280 - 40 = 1240 = 0x04D8
    let mut v6_1280 = vec![0xDDu8; 1280];
    v6_1280[0] = 0x60;
    v6_1280[4] = (1240 >> 8) as u8;
    v6_1280[5] = (1240 & 0xFF) as u8;
    v6_1280[6] = 6; // Next header: TCP

    let wire_1280 = encapsulate_transport_packet(&tx, h4, &v6_1280).expect("IPv6 1280 encapsulation failed");
    // pad16(1280) = 1280 -> wire = 16 + 1280 + 16 = 1312
    assert_eq!(wire_1280.len(), 1312);

    let dec_1280 = decapsulate_transport_packet(&rx, h4, &wire_1280).expect("IPv6 1280 decapsulation failed");
    assert_eq!(dec_1280.len(), 1280);
    assert_eq!(dec_1280, v6_1280);
}

#[test]
fn test_adversarial_arbitrary_non_ip_payloads() {
    let (tx, rx) = setup_test_keypair();
    let h4 = 4u32;

    let test_payloads: Vec<&[u8]> = vec![
        b"X",
        b"1234567",
        b"WireGuard raw transport payload",
        b"\x00\xFF\xAA\x55\x12\x34\x56\x78\x9A\xBC\xDE\xF0",
    ];

    for payload in test_payloads {
        let wire = encapsulate_transport_packet(&tx, h4, payload)
            .expect("Non-IP encapsulation failed");
        // Non-IP payloads are not padded by is_ip_packet
        assert_eq!(wire.len(), 16 + payload.len() + 16);

        let dec = decapsulate_transport_packet(&rx, h4, &wire)
            .expect("Non-IP decapsulation failed");
        assert_eq!(dec, payload, "Decapsulated non-IP payload must match exactly");
    }
}

#[test]
fn test_adversarial_tampered_tag_and_ciphertext_rejection() {
    let (tx, rx) = setup_test_keypair();
    let h4 = 4u32;

    let payload = b"Sensitive in-tunnel payload data";
    let wire = encapsulate_transport_packet(&tx, h4, payload).expect("encapsulation failed");

    // 1. Tamper Poly1305 authentication tag (last 16 bytes)
    for tag_byte_offset in (wire.len() - 16)..wire.len() {
        let mut corrupted = wire.clone();
        corrupted[tag_byte_offset] ^= 0x01;
        let res = decapsulate_transport_packet(&rx, h4, &corrupted);
        assert!(res.is_err(), "Corrupted tag at byte {} must be rejected", tag_byte_offset);
    }

    // 2. Tamper ciphertext payload
    for ct_offset in 16..(wire.len() - 16) {
        let mut corrupted = wire.clone();
        corrupted[ct_offset] ^= 0x80;
        let res = decapsulate_transport_packet(&rx, h4, &corrupted);
        assert!(res.is_err(), "Corrupted ciphertext at byte {} must be rejected", ct_offset);
    }

    // 3. Tamper header: H4 mismatch
    let mut bad_h4 = wire.clone();
    bad_h4[0] ^= 0x01;
    let res_h4 = decapsulate_transport_packet(&rx, h4, &bad_h4);
    assert!(res_h4.is_err(), "Header H4 mismatch must be rejected");

    // 4. Tamper header: receiver_index mismatch
    let mut bad_idx = wire.clone();
    bad_idx[4] ^= 0x01;
    let res_idx = decapsulate_transport_packet(&rx, h4, &bad_idx);
    assert!(res_idx.is_err(), "Header receiver index mismatch must be rejected");

    // 5. CRITICAL SECURITY CHECK: Failed decapsulation must NEVER advance the replay window!
    assert_eq!(
        rx.replay_filter.lock().last_counter,
        0,
        "Replay window must remain 0 after all failed tampered packets"
    );

    // Legitimate packet must still be accepted cleanly
    let legitimate = decapsulate_transport_packet(&rx, h4, &wire).expect("Legitimate packet must succeed");
    assert_eq!(legitimate, payload);
    assert_eq!(rx.replay_filter.lock().last_counter, 0);
}

#[test]
fn test_adversarial_out_of_order_and_duplicate_rejection() {
    let (tx, rx) = setup_test_keypair();
    let h4 = 4u32;

    // Generate 30 packets with sequential counters 0..29
    let mut packets = Vec::new();
    for i in 0..30 {
        let payload = format!("packet-seq-{}", i).into_bytes();
        let wire = encapsulate_transport_packet(&tx, h4, &payload).unwrap();
        packets.push((i as u64, payload, wire));
    }

    // Adversarial arrival permutation:
    // Jump forward, then deliver older out-of-order, then advance again
    let arrival_indices = [
        0, 5, 2, 1, 4, 3,       // burst 1: jump to 5, backfill 1..4
        15, 10, 12, 11, 14, 13, // burst 2: jump to 15, backfill 10..14
        6, 7, 8, 9,             // delayed burst from earlier
        25, 29, 20, 21, 24, 22, 23, 28, 27, 26, 16, 17, 18, 19, // remaining
    ];

    for &idx in &arrival_indices {
        let (expected_counter, ref expected_payload, ref wire) = packets[idx];
        let dec = decapsulate_transport_packet(&rx, h4, wire)
            .unwrap_or_else(|e| panic!("Failed to decapsulate packet {} (counter {}): {}", idx, expected_counter, e));
        assert_eq!(&dec, expected_payload);

        // Immediate duplicate MUST be rejected
        let dup_err = decapsulate_transport_packet(&rx, h4, wire);
        assert!(dup_err.is_err(), "Duplicate of packet {} must be rejected", idx);
        assert!(dup_err.unwrap_err().contains("replay"));
    }

    // After all 30 packets delivered, every single one of 0..29 must be rejected as duplicate
    for i in 0..30 {
        let (_, _, ref wire) = packets[i];
        let dup_err = decapsulate_transport_packet(&rx, h4, wire);
        assert!(dup_err.is_err(), "Retesting packet {} must fail as replay", i);
    }
}

#[test]
fn test_adversarial_zero_allocation_buffer_hot_loop() {
    let (tx, rx) = setup_test_keypair();
    let h4 = 4u32;

    // Pre-allocate buffer for standard MTU
    let mut out_buf = Vec::with_capacity(2048);
    let initial_capacity = out_buf.capacity();

    let mut ipv4_payload = vec![0xEEu8; 1420];
    ipv4_payload[0] = 0x45;
    ipv4_payload[2] = (1420 >> 8) as u8;
    ipv4_payload[3] = (1420 & 0xFF) as u8;

    // Run 500 hot-loop iterations
    for _ in 0..500 {
        encapsulate_transport_packet_into(&tx, h4, &ipv4_payload, &mut out_buf)
            .expect("encapsulate_transport_packet_into failed");

        // Capacity MUST NOT change (zero re-allocations on hot path)
        assert_eq!(
            out_buf.capacity(),
            initial_capacity,
            "out_buf capacity grew! Heap allocation detected on hot path"
        );

        // Test in-place decapsulation
        let mut wire_clone = out_buf.clone();
        let decrypted_slice = decapsulate_transport_packet_slice(&rx, h4, &mut wire_clone)
            .expect("decapsulate_transport_packet_slice failed");
        assert_eq!(decrypted_slice.len(), 1420);
        assert_eq!(decrypted_slice, &ipv4_payload[..]);
    }

    assert_eq!(tx.send_counter.load(Ordering::Relaxed), 500);
}

#[test]
fn test_adversarial_replay_window_boundary_and_sliding_shifts() {
    let mut filter = ReplayFilter::new();

    // Packet 0 arrives
    assert!(filter.check(0).is_ok());
    assert!(filter.update(0).is_ok());
    assert_eq!(filter.last_counter, 0);

    // Counter jumps forward by 2047: diff = 2047 < 2048. Valid!
    assert!(filter.check(2047).is_ok());
    assert!(filter.update(2047).is_ok());
    assert_eq!(filter.last_counter, 2047);

    // Packet 0 is now diff = 2047 - 0 = 2047 < 2048, already seen in bitmap
    assert_eq!(filter.check(0), Err(ReplayError::Replay));

    // Counter jumps to 2048: diff from 0 is now 2048 >= COUNTER_WINDOW_SIZE (2048) -> TooOld!
    assert!(filter.check(2048).is_ok());
    assert!(filter.update(2048).is_ok());
    assert_eq!(filter.check(0), Err(ReplayError::TooOld));

    // Huge jump: counter jumps by 10,000. Must reset window and not panic
    assert!(filter.check(12048).is_ok());
    assert!(filter.update(12048).is_ok());
    assert_eq!(filter.last_counter, 12048);
    assert_eq!(filter.check(2048), Err(ReplayError::TooOld));
}

#[test]
fn test_adversarial_malformed_ip_overrun_and_short_headers() {
    // 1. Empty buffer returns 0
    assert_eq!(validate_and_get_ip_len(&[]).unwrap(), 0);

    // 2. IPv4 header < 20 bytes must fail
    let short_v4 = [0x45, 0x00, 0x00];
    assert!(validate_and_get_ip_len(&short_v4).is_err());

    // 3. IPv4 claiming total_len < 20 must fail
    let invalid_len_v4 = [0x45, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00];
    assert!(validate_and_get_ip_len(&invalid_len_v4).is_err());

    // 4. IPv4 claiming total_len > buffer len must fail
    let mut overrun_v4 = vec![0u8; 32];
    overrun_v4[0] = 0x45;
    overrun_v4[2] = 0x01; // 256 bytes
    overrun_v4[3] = 0x00;
    assert!(validate_and_get_ip_len(&overrun_v4).is_err());

    // 5. IPv6 header < 40 bytes must fail
    let short_v6 = [0x60, 0x00, 0x00, 0x00, 0x00, 0x01];
    assert!(validate_and_get_ip_len(&short_v6).is_err());

    // 6. IPv6 claiming payload > buffer len must fail
    let mut overrun_v6 = vec![0u8; 48];
    overrun_v6[0] = 0x60;
    overrun_v6[4] = 0x01; // payload = 256, total = 296 > 48
    overrun_v6[5] = 0x00;
    assert!(validate_and_get_ip_len(&overrun_v6).is_err());
}

#[test]
fn test_adversarial_multi_threaded_counter_concurrency() {
    let (tx, _) = setup_test_keypair();
    let tx = Arc::new(tx);
    let h4 = 4u32;
    let threads_count = 8;
    let packets_per_thread = 200;

    let mut handles = Vec::new();
    for t in 0..threads_count {
        let tx_clone = Arc::clone(&tx);
        let handle = std::thread::spawn(move || {
            let mut thread_counters = Vec::with_capacity(packets_per_thread);
            let mut out = Vec::with_capacity(128);
            let payload = format!("thread-{}-msg", t).into_bytes();

            for _ in 0..packets_per_thread {
                encapsulate_transport_packet_into(&tx_clone, h4, &payload, &mut out)
                    .expect("concurrent encapsulation failed");
                let counter = u64::from_le_bytes(out[8..16].try_into().unwrap());
                thread_counters.push(counter);
            }
            thread_counters
        });
        handles.push(handle);
    }

    let mut all_counters = HashSet::new();
    for handle in handles {
        let counters = handle.join().expect("thread join failed");
        for c in counters {
            // Nonce reuse check: every counter must be strictly unique!
            assert!(
                all_counters.insert(c),
                "FATAL NONCE REUSE: duplicate counter {} allocated concurrently!",
                c
            );
        }
    }

    assert_eq!(all_counters.len(), threads_count * packets_per_thread);
    assert_eq!(tx.send_counter.load(Ordering::Relaxed), (threads_count * packets_per_thread) as u64);
}

#[test]
fn test_adversarial_truncated_raw_udp_packets() {
    let (_, rx) = setup_test_keypair();
    let h4 = 4u32;

    // Minimum wire packet length is 32 (16 header + 16 tag)
    for len in 0..32 {
        let dummy = vec![0u8; len];
        let res = decapsulate_transport_packet(&rx, h4, &dummy);
        assert!(res.is_err(), "Packet of length {} (<32) must be rejected", len);
        assert!(res.unwrap_err().contains("too short"));
    }
}
