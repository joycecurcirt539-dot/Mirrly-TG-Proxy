use mirrlyengine::prober::ping_worker_endpoint;
use mirrlyengine::tls_observability::TLS_TRACKER;
use mirrlyengine::ws::*;
use mirrlyengine::{IsTlsEnabled, SetTlsEnabled};
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpListener;

struct TlsGuard;
impl Drop for TlsGuard {
    fn drop(&mut self) {
        SetTlsEnabled(0);
    }
}

#[tokio::test]
async fn disabled_tls_uses_plain_ws_http_probe() {
    let _guard = TlsGuard;
    assert_eq!(IsTlsEnabled(), 0);
    assert_eq!(default_ws_port(), 80);

    TLS_TRACKER.write().reset();

    // 1. Happy Eyeballs WebSocket upgrade must go over plaintext TCP without TLS ClientHello.
    let ws_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let ws_addr = ws_listener.local_addr().unwrap();

    let ws_server = tokio::spawn(async move {
        let (stream, _) = ws_listener.accept().await.unwrap();
        let mut reader = BufReader::new(stream);
        let mut request = String::new();
        loop {
            let mut line = String::new();
            reader.read_line(&mut line).await.unwrap();
            request.push_str(&line);
            if line == "\r\n" {
                break;
            }
        }
        assert!(request.starts_with("GET /apiws HTTP/1.1\r\n"));
        let key = request
            .lines()
            .find_map(|line| line.strip_prefix("Sec-WebSocket-Key: "))
            .unwrap();
        let response = format!(
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {}\r\n\r\n",
            compute_sec_websocket_accept(key)
        );
        reader
            .get_mut()
            .write_all(response.as_bytes())
            .await
            .unwrap();

        let mut hdr = [0u8; 2];
        reader.read_exact(&mut hdr).await.unwrap();
        assert_eq!(hdr[0], 0x82);
        let len = (hdr[1] & 0x7F) as usize;
        let mut mask = [0u8; 4];
        reader.read_exact(&mut mask).await.unwrap();
        let mut body = vec![0u8; len];
        reader.read_exact(&mut body).await.unwrap();
        mirrlyengine::crypto::xor_mask_in_place(&mut body, &mask);
        assert_eq!(&body, b"plain-ws-ping");

        reader
            .get_mut()
            .write_all(&build_frame(OP_BINARY, b"plain-ws-pong", false))
            .await
            .unwrap();
    });

    let (ws, winner) = ws_connect_happy_eyeballs_split_ext(
        "plain.test",
        "plain.test",
        "/apiws",
        None,
        &[ws_addr],
        Duration::from_secs(2),
    )
    .await
    .unwrap();
    assert_eq!(winner, ws_addr);
    ws.send(b"plain-ws-ping").await.unwrap();
    let reply = ws.recv_with_timeout(Duration::from_secs(2)).await.unwrap();
    assert_eq!(&reply, b"plain-ws-pong");
    ws_server.await.unwrap();

    let tls_status = TLS_TRACKER.read().get_status();
    assert_eq!(tls_status.total_full_handshakes, 0);
    assert_eq!(tls_status.total_resumed_handshakes, 0);
    assert_eq!(tls_status.total_handshake_failures, 0);

    // 2. Worker probe must use HTTP without following redirects into HTTPS.
    let http_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let http_addr = http_listener.local_addr().unwrap();
    let http_server = tokio::spawn(async move {
        let (mut stream, _) = http_listener.accept().await.unwrap();
        let mut buf = [0u8; 512];
        let n = stream.read(&mut buf).await.unwrap();
        let req = String::from_utf8_lossy(&buf[..n]);
        assert!(req.starts_with("GET / HTTP/1.1\r\n"));
        stream
            .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
            .await
            .unwrap();
    });

    let (status, rtt) = ping_worker_endpoint(&http_addr.to_string(), 2000).await;
    assert_eq!(status, "ONLINE");
    assert!(rtt.is_some());
    http_server.await.unwrap();

    SetTlsEnabled(1);
    assert_eq!(IsTlsEnabled(), 1);
    assert_eq!(default_ws_port(), 443);

    SetTlsEnabled(0);
    assert_eq!(IsTlsEnabled(), 0);
    assert_eq!(default_ws_port(), 80);
}
