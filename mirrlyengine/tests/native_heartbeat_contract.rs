#![cfg(feature = "heartbeat-test-utils")]

use mirrlyengine::ws::{RawWebSocket, WsError, OP_BINARY, OP_PING};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio_util::sync::CancellationToken;

async fn socket_pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let client = TcpStream::connect(listener.local_addr().unwrap())
        .await
        .unwrap();
    let (server, _) = listener.accept().await.unwrap();
    assert_eq!(client.peer_addr().unwrap(), server.local_addr().unwrap());
    assert_eq!(client.local_addr().unwrap(), server.peer_addr().unwrap());
    (client, server)
}

async fn read_frame(stream: &mut TcpStream) -> (u8, Vec<u8>) {
    let mut header = [0u8; 2];
    stream.read_exact(&mut header).await.unwrap();
    let mut length = (header[1] & 0x7f) as usize;
    if length == 126 {
        let mut extended = [0u8; 2];
        stream.read_exact(&mut extended).await.unwrap();
        length = u16::from_be_bytes(extended) as usize;
    } else if length == 127 {
        let mut extended = [0u8; 8];
        stream.read_exact(&mut extended).await.unwrap();
        length = u64::from_be_bytes(extended) as usize;
    }
    let masked = header[1] & 0x80 != 0;
    let mut mask = [0u8; 4];
    if masked {
        stream.read_exact(&mut mask).await.unwrap();
    }
    let mut payload = vec![0u8; length];
    stream.read_exact(&mut payload).await.unwrap();
    if masked {
        for (index, byte) in payload.iter_mut().enumerate() {
            *byte ^= mask[index % 4];
        }
    }
    (header[0] & 0x0f, payload)
}

fn pong_frame(payload: &[u8]) -> Vec<u8> {
    let mut frame = vec![0x8a, payload.len() as u8];
    frame.extend_from_slice(payload);
    frame
}

#[tokio::test]
async fn active_frames_suppress_ping_and_pong_uses_same_five_tuple() {
    let (client, mut server) = socket_pair().await;
    let ws = Arc::new(RawWebSocket::from_plain_stream_for_heartbeat_test(client));
    let cancel = CancellationToken::new();
    let heartbeat_ws = ws.clone();
    let heartbeat_cancel = cancel.clone();
    let heartbeat = tokio::spawn(async move {
        heartbeat_ws
            .run_heartbeat_for_test(
                heartbeat_cancel,
                Duration::from_millis(150),
                Duration::from_millis(80),
                Duration::from_millis(5),
            )
            .await
    });

    for _ in 0..4 {
        server.write_all(&[0x82, 6, b'a', b'c', b't', b'i', b'v', b'e']).await.unwrap();
        assert_eq!(ws.recv().await.unwrap(), b"active");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    assert!(
        tokio::time::timeout(Duration::from_millis(50), read_frame(&mut server))
            .await
            .is_err()
    );

    let (opcode, nonce) = tokio::time::timeout(Duration::from_millis(130), read_frame(&mut server))
        .await
        .unwrap();
    assert_eq!(opcode, OP_PING);
    assert_eq!(nonce.len(), 8);

    let receive_ws = ws.clone();
    let receiver = tokio::spawn(async move {
        receive_ws
            .recv_with_timeout(Duration::from_millis(120))
            .await
    });
    server.write_all(&pong_frame(&nonce)).await.unwrap();
    tokio::time::sleep(Duration::from_millis(15)).await;
    assert!(!ws.heartbeat_is_waiting_for_pong());

    cancel.cancel();
    assert!(heartbeat.await.unwrap().is_ok());
    receiver.abort();
}

#[tokio::test]
async fn missing_pong_detects_blackhole() {
    let (client, mut server) = socket_pair().await;
    let ws = Arc::new(RawWebSocket::from_plain_stream_for_heartbeat_test(client));
    let heartbeat_ws = ws.clone();
    let heartbeat = tokio::spawn(async move {
        heartbeat_ws
            .run_heartbeat_for_test(
                CancellationToken::new(),
                Duration::from_millis(20),
                Duration::from_millis(30),
                Duration::from_millis(5),
            )
            .await
    });

    let (opcode, nonce) = tokio::time::timeout(Duration::from_millis(60), read_frame(&mut server))
        .await
        .unwrap();
    assert_eq!(opcode, OP_PING);
    assert_eq!(nonce.len(), 8);

    let error = tokio::time::timeout(Duration::from_millis(80), heartbeat)
        .await
        .expect("blackhole must trip pong deadline")
        .unwrap()
        .unwrap_err();
    assert!(matches!(error, WsError::Other(message) if message.contains("pong deadline")));
    assert!(ws.is_closed());
}

#[tokio::test]
async fn continuous_upload_does_not_hide_a_dead_return_path() {
    let (client, mut server) = socket_pair().await;
    let ws = Arc::new(RawWebSocket::from_plain_stream_for_heartbeat_test(client));
    let cancel = CancellationToken::new();
    let heartbeat_ws = ws.clone();
    let heartbeat_cancel = cancel.clone();
    let heartbeat = tokio::spawn(async move {
        heartbeat_ws.run_heartbeat_for_test(
            heartbeat_cancel,
            Duration::from_millis(80),
            Duration::from_millis(60),
            Duration::from_millis(5),
        ).await
    });
    let upload_ws = ws.clone();
    let upload = tokio::spawn(async move {
        while upload_ws.send(b"media").await.is_ok() {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    });
    let peer = tokio::spawn(async move {
        loop {
            let (opcode, _) = read_frame(&mut server).await;
            assert!(opcode == OP_PING || opcode == OP_BINARY);
        }
    });
    let result = tokio::time::timeout(Duration::from_secs(2), heartbeat).await;
    cancel.cancel();
    upload.abort();
    peer.abort();
    let error = result.expect("uploads must not suppress heartbeat").unwrap().unwrap_err();
    assert!(matches!(error, WsError::Other(message) if message.contains("pong deadline")));
    assert!(ws.is_closed());
}
