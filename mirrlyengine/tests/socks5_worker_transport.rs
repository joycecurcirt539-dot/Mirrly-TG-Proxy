use mirrlyengine::ws::*;
use mirrlyengine::crypto::xor_mask_in_place;
use std::time::Duration;
use tokio::net::TcpStream;
use tokio::io::{BufReader, AsyncReadExt, AsyncWriteExt, AsyncBufReadExt};

#[tokio::test]
async fn relay_rejects_payload_in_upgrade_headers() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let stream = TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();
    let result = ws_handshake_plain_ext(stream, "relay.test", "/tcp-v2?target=x", Some(b"telegram bytes"), Duration::from_secs(1)).await;
    assert!(matches!(result, Err(WsError::Other(message)) if message.contains("binary frames")));
}

    #[tokio::test]
    async fn relay_upgrade_and_masking_preserve_binary_payload() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let payload: Vec<u8> = (0..65537).map(|n| (n % 256) as u8).collect();
        let expected = payload.clone();
        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let mut reader = BufReader::new(stream);
            let mut request = String::new();
            loop {
                let mut line = String::new();
                reader.read_line(&mut line).await.unwrap();
                request.push_str(&line);
                if line == "\r\n" { break; }
            }
            assert!(request.starts_with("GET /tcp-v2?target=149.154.167.50%3A443 HTTP/1.1\r\n"));
            assert!(!request.contains("User-Agent:"));
            assert!(!request.contains("Sec-WebSocket-Protocol:"));
            let key = request.lines().find_map(|line| line.strip_prefix("Sec-WebSocket-Key: ")).unwrap();
            let response = format!("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {}\r\n\r\n", compute_sec_websocket_accept(key));
            reader.get_mut().write_all(response.as_bytes()).await.unwrap();
            // Delay readiness beyond the old 350 ms limit.
            tokio::time::sleep(Duration::from_millis(500)).await;
            reader.get_mut().write_all(&build_frame(OP_BINARY, &[0x56, 2, 0, 0], false)).await.unwrap();
            let mut header = [0; 2];
            reader.read_exact(&mut header).await.unwrap();
            assert_eq!(header, [0x82, 0xff]); // Binary, FIN, mandatory client mask.
            let mut length = [0; 8];
            reader.read_exact(&mut length).await.unwrap();
            let size = u64::from_be_bytes(length) as usize;
            assert_eq!(size, expected.len());
            let mut mask = [0; 4];
            reader.read_exact(&mut mask).await.unwrap();
            let mut received = vec![0; size];
            reader.read_exact(&mut received).await.unwrap();
            xor_mask_in_place(&mut received, &mask);
            assert_eq!(received, expected);
            reader.get_mut().write_all(&build_frame(OP_BINARY, &received, false)).await.unwrap();
        });
        let stream = TcpStream::connect(address).await.unwrap();
        let ws = ws_handshake_plain_ext(stream, "relay.test", "/tcp-v2?target=149.154.167.50%3A443", None, Duration::from_secs(2)).await.unwrap();
        assert_eq!(ws.recv_with_timeout(Duration::from_secs(2)).await.unwrap(), [0x56, 2, 0, 0]);
        ws.send(&payload).await.unwrap();
        assert_eq!(ws.recv_with_timeout(Duration::from_secs(2)).await.unwrap(), payload);
        server.await.unwrap();
    }

#[test]
fn test_resolve_socks5_telegram_destination_ipv6_to_canonical_ipv4() {
    use mirrlyengine::socks5::*;

    // DC1 IPv6 -> DC1 canonical IPv4
    let (h1, p1) = resolve_socks5_telegram_destination("2001:b28:f23d:f001::a", 443, None, false);
    assert_eq!(h1, "149.154.175.50");
    assert_eq!(p1, 443);

    // DC2 IPv6 -> DC2 canonical IPv4
    let (h2, p2) = resolve_socks5_telegram_destination("2001:67c:4e8:f002::a", 443, None, false);
    assert_eq!(h2, "149.154.167.51");
    assert_eq!(p2, 443);

    // DC4 IPv6 -> DC4 canonical IPv4
    let (h4, p4) = resolve_socks5_telegram_destination("2001:67c:4e8:f004::b", 443, None, false);
    assert_eq!(h4, "149.154.167.91");
    assert_eq!(p4, 443);
}

#[test]
fn test_resolve_socks5_telegram_destination_flowseal_media_fix() {
    use mirrlyengine::socks5::*;

    // Media connection with DC2 -> Flowseal Media Ingest (149.154.167.220:443)
    let (h_media, p_media) = resolve_socks5_telegram_destination("149.154.167.51", 443, Some(2), true);
    assert_eq!(h_media, "149.154.167.220");
    assert_eq!(p_media, 443);

    // Media connection from IPv6 -> Flowseal Media Ingest
    let (h_media_ipv6, p_media_ipv6) = resolve_socks5_telegram_destination("2001:67c:4e8:f002::a", 443, None, true);
    assert_eq!(h_media_ipv6, "149.154.167.220");
    assert_eq!(p_media_ipv6, 443);
}
