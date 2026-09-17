use mirrlyengine::{
    bridge::BridgeActivity,
    socks5::{bounded_write, bridge_socks5_stream},
};
use std::time::Duration;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt, DuplexStream},
    net::{TcpListener, TcpStream},
};
use tokio_util::sync::CancellationToken;

async fn bridge(
    capacity: usize,
    cancel: CancellationToken,
) -> (TcpStream, DuplexStream, tokio::task::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let client = TcpStream::connect(listener.local_addr().unwrap())
        .await
        .unwrap();
    let (server, _) = listener.accept().await.unwrap();
    let (upstream, peer) = tokio::io::duplex(capacity);
    let task = tokio::spawn(bridge_socks5_stream(
        server,
        upstream,
        vec![],
        None,
        false,
        cancel,
    ));
    (client, peer, task)
}
async fn finish(task: tokio::task::JoinHandle<()>) {
    tokio::time::timeout(Duration::from_secs(2), task)
        .await
        .unwrap()
        .unwrap();
}

#[tokio::test]
async fn client_half_close_drains_complete_response() {
    let (mut client, mut peer, task) = bridge(64, CancellationToken::new()).await;
    client.write_all(b"request").await.unwrap();
    client.shutdown().await.unwrap();
    let mut request = vec![];
    peer.read_to_end(&mut request).await.unwrap();
    assert_eq!(request, b"request");
    let response = vec![42; 256 * 1024];
    let expected = response.clone();
    let sender = tokio::spawn(async move {
        peer.write_all(&response).await.unwrap();
        peer.shutdown().await.unwrap();
    });
    let mut received = vec![];
    tokio::time::timeout(Duration::from_secs(3), client.read_to_end(&mut received))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(received, expected);
    sender.await.unwrap();
    finish(task).await;
}

#[tokio::test]
async fn peer_half_close_preserves_remaining_upload() {
    let (mut client, mut peer, task) = bridge(64, CancellationToken::new()).await;
    peer.write_all(b"response").await.unwrap();
    peer.shutdown().await.unwrap();
    let mut response = vec![];
    client.read_to_end(&mut response).await.unwrap();
    assert_eq!(response, b"response");
    let payload = vec![17; 128 * 1024];
    let expected = payload.clone();
    let sender = tokio::spawn(async move {
        client.write_all(&payload).await.unwrap();
        client.shutdown().await.unwrap();
    });
    let mut received = vec![];
    tokio::time::timeout(Duration::from_secs(3), peer.read_to_end(&mut received))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(received, expected);
    sender.await.unwrap();
    finish(task).await;
}

#[tokio::test]
async fn upload_only() {
    let (mut client, mut peer, task) = bridge(64, CancellationToken::new()).await;
    client.write_all(b"upload").await.unwrap();
    client.shutdown().await.unwrap();
    let mut received = vec![];
    peer.read_to_end(&mut received).await.unwrap();
    assert_eq!(received, b"upload");
    peer.shutdown().await.unwrap();
    finish(task).await;
}
#[tokio::test]
async fn download_only() {
    let (mut client, mut peer, task) = bridge(64, CancellationToken::new()).await;
    peer.write_all(b"download").await.unwrap();
    peer.shutdown().await.unwrap();
    let mut received = vec![];
    client.read_to_end(&mut received).await.unwrap();
    assert_eq!(received, b"download");
    client.shutdown().await.unwrap();
    finish(task).await;
}
#[tokio::test]
async fn stop_during_blocked_write() {
    let cancel = CancellationToken::new();
    let (mut client, _peer, task) = bridge(1, cancel.clone()).await;
    client.write_all(&vec![8; 32 * 1024]).await.unwrap();
    tokio::time::sleep(Duration::from_millis(30)).await;
    cancel.cancel();
    finish(task).await;
}
#[tokio::test]
async fn dropping_bridge_owns_both_directions() {
    let (mut client, mut peer, task) = bridge(64, CancellationToken::new()).await;
    tokio::task::yield_now().await;
    task.abort();
    assert!(task.await.unwrap_err().is_cancelled());
    for read in [&mut peer as &mut (dyn tokio::io::AsyncRead + Unpin)] {
        let mut bytes = vec![];
        tokio::time::timeout(Duration::from_secs(1), read.read_to_end(&mut bytes))
            .await
            .unwrap()
            .unwrap();
    }
    let mut bytes = vec![];
    tokio::time::timeout(Duration::from_secs(1), client.read_to_end(&mut bytes))
        .await
        .unwrap()
        .unwrap();
}
#[tokio::test]
async fn cancellation_before_wait_is_retained() {
    let cancel = CancellationToken::new();
    cancel.cancel();
    let activity = BridgeActivity::new();
    assert_eq!(
        activity
            .wait(std::future::pending::<()>(), &cancel)
            .await
            .unwrap_err()
            .kind(),
        std::io::ErrorKind::Interrupted
    );
    let (mut writer, mut reader) = tokio::io::duplex(64);
    assert_eq!(
        bounded_write(&mut writer, b"unsent", &cancel, Duration::from_secs(1))
            .await
            .unwrap_err()
            .kind(),
        std::io::ErrorKind::Interrupted
    );
    writer.shutdown().await.unwrap();
    let mut bytes = vec![];
    reader.read_to_end(&mut bytes).await.unwrap();
    assert!(bytes.is_empty());
}
#[tokio::test(start_paused = true)]
async fn partial_framed_read_survives_idle_checks() {
    let (mut writer, mut reader) = tokio::io::duplex(64);
    let activity = BridgeActivity::new();
    let task = tokio::spawn(async move {
        activity
            .wait(
                mirrlyengine::faketls::read_tls_app_data(&mut reader),
                &CancellationToken::new(),
            )
            .await
            .unwrap()
            .unwrap()
    });
    writer.write_all(&[0x17, 3]).await.unwrap();
    tokio::task::yield_now().await;
    tokio::time::advance(Duration::from_secs(5)).await;
    writer.write_all(&[3, 0, 3, 1, 2, 3]).await.unwrap();
    assert_eq!(task.await.unwrap(), [1, 2, 3]);
}
#[tokio::test(start_paused = true)]
async fn ws_drain_deadline_tracks_data_progress() {
    let activity = BridgeActivity::new();
    activity.upload_eof();
    let observed = activity.clone();
    let task = tokio::spawn(async move {
        observed
            .wait(std::future::pending::<()>(), &CancellationToken::new())
            .await
    });
    tokio::task::yield_now().await;
    tokio::time::advance(Duration::from_secs(25)).await;
    activity.touch();
    tokio::task::yield_now().await;
    tokio::time::advance(Duration::from_secs(25)).await;
    tokio::task::yield_now().await;
    assert!(!task.is_finished());
    tokio::time::advance(Duration::from_secs(6)).await;
    assert_eq!(
        task.await.unwrap().unwrap_err().kind(),
        std::io::ErrorKind::TimedOut
    );
}
#[tokio::test]
async fn blocked_write_reports_committed_prefix_without_replay() {
    let (mut writer, mut reader) = tokio::io::duplex(3);
    let error = bounded_write(
        &mut writer,
        b"abcdef",
        &CancellationToken::new(),
        Duration::from_millis(20),
    )
    .await
    .unwrap_err();
    assert_eq!(error.kind(), std::io::ErrorKind::TimedOut);
    assert!(error.to_string().contains("3/6"));
    drop(writer);
    let mut bytes = vec![];
    reader.read_to_end(&mut bytes).await.unwrap();
    assert_eq!(bytes, b"abc");
}

#[cfg(feature = "heartbeat-test-utils")]
#[tokio::test]
async fn websocket_half_close_drains_before_peer_close() {
    use mirrlyengine::{socks5::bridge_socks5_ws, ws::RawWebSocket};
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let mut client = TcpStream::connect(listener.local_addr().unwrap())
        .await
        .unwrap();
    let (local, _) = listener.accept().await.unwrap();
    let remote = TcpStream::connect(listener.local_addr().unwrap())
        .await
        .unwrap();
    let (mut peer, _) = listener.accept().await.unwrap();
    let task = tokio::spawn(bridge_socks5_ws(
        local,
        RawWebSocket::from_plain_stream_for_heartbeat_test(remote),
        false,
        CancellationToken::new(),
    ));
    client.shutdown().await.unwrap();
    peer.write_all(&[0x82, 3, 1, 2, 3, 0x88, 0]).await.unwrap();
    let mut received = vec![];
    tokio::time::timeout(Duration::from_secs(2), client.read_to_end(&mut received))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(received, [1, 2, 3]);
    finish(task).await;
}

#[cfg(feature = "heartbeat-test-utils")]
#[tokio::test]
async fn fragmented_websocket_frame_survives_idle_ticks() {
    use mirrlyengine::ws::RawWebSocket;
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let remote = TcpStream::connect(listener.local_addr().unwrap())
        .await
        .unwrap();
    let (mut peer, _) = listener.accept().await.unwrap();
    let task = tokio::spawn(async move {
        let ws = RawWebSocket::from_plain_stream_for_heartbeat_test(remote);
        BridgeActivity::new()
            .wait(ws.recv(), &CancellationToken::new())
            .await
            .unwrap()
            .unwrap()
    });
    peer.write_all(&[0x02, 3, 1]).await.unwrap();
    tokio::time::sleep(Duration::from_millis(1100)).await;
    peer.write_all(&[2, 3, 0x80]).await.unwrap();
    tokio::time::sleep(Duration::from_millis(1100)).await;
    peer.write_all(&[2, 4, 5]).await.unwrap();
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(1), task)
            .await
            .unwrap()
            .unwrap(),
        [1, 2, 3, 4, 5]
    );
}

#[tokio::test]
async fn truncated_faketls_header_is_not_graceful_eof() {
    let (mut writer, mut reader) = tokio::io::duplex(64);
    writer.write_all(&[0x17, 3]).await.unwrap();
    writer.shutdown().await.unwrap();
    assert_eq!(
        mirrlyengine::faketls::read_tls_app_data(&mut reader)
            .await
            .unwrap_err()
            .kind(),
        std::io::ErrorKind::UnexpectedEof
    );
}

#[tokio::test]
async fn cancellation_between_write_iterations_is_retained() {
    struct Writer {
        bytes: Vec<u8>,
        cancel: CancellationToken,
    }
    impl tokio::io::AsyncWrite for Writer {
        fn poll_write(
            mut self: std::pin::Pin<&mut Self>,
            _: &mut std::task::Context<'_>,
            data: &[u8],
        ) -> std::task::Poll<std::io::Result<usize>> {
            let n = data.len().min(3);
            self.bytes.extend_from_slice(&data[..n]);
            self.cancel.cancel();
            std::task::Poll::Ready(Ok(n))
        }
        fn poll_flush(
            self: std::pin::Pin<&mut Self>,
            _: &mut std::task::Context<'_>,
        ) -> std::task::Poll<std::io::Result<()>> {
            std::task::Poll::Ready(Ok(()))
        }
        fn poll_shutdown(
            self: std::pin::Pin<&mut Self>,
            _: &mut std::task::Context<'_>,
        ) -> std::task::Poll<std::io::Result<()>> {
            std::task::Poll::Ready(Ok(()))
        }
    }
    let cancel = CancellationToken::new();
    let mut writer = Writer {
        bytes: vec![],
        cancel: cancel.clone(),
    };
    let error = bounded_write(&mut writer, b"abcdef", &cancel, Duration::from_secs(1))
        .await
        .unwrap_err();
    assert_eq!(error.kind(), std::io::ErrorKind::Interrupted);
    assert_eq!(writer.bytes, b"abc");
}

#[tokio::test(start_paused = true)]
async fn ten_minutes_idle_with_pong_does_not_timeout() {
    let last_pong = std::sync::Arc::new(parking_lot::Mutex::new(tokio::time::Instant::now()));
    let last_pong_cb = last_pong.clone();
    let activity = BridgeActivity::with_transport_health(move || {
        (last_pong_cb.lock().elapsed(), false)
    });
    let task = tokio::spawn(async move {
        activity
            .wait(std::future::pending::<()>(), &CancellationToken::new())
            .await
    });
    // Simulate 12 minutes (720 seconds) of idle application data with PONGs arriving every 30 seconds
    for _ in 0..24 {
        tokio::time::advance(Duration::from_secs(30)).await;
        *last_pong.lock() = tokio::time::Instant::now();
        tokio::task::yield_now().await;
    }
    // 12 minutes elapsed with healthy pongs: bridge MUST NOT be closed or timed out
    assert!(!task.is_finished(), "Flow must remain alive over 10+ minutes of idle with healthy pongs");
}

#[tokio::test(start_paused = true)]
async fn blackhole_triggers_closure_on_pong_deadline_faster_than_legacy_timeout() {
    let is_closed = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let is_closed_cb = is_closed.clone();
    let activity = BridgeActivity::with_transport_health(move || {
        (Duration::from_secs(0), is_closed_cb.load(std::sync::atomic::Ordering::Relaxed))
    });
    let task = tokio::spawn(async move {
        activity
            .wait(std::future::pending::<()>(), &CancellationToken::new())
            .await
    });
    tokio::task::yield_now().await;
    tokio::time::advance(Duration::from_secs(20)).await;
    assert!(!task.is_finished());

    // Blackhole occurs: heartbeat detect pong deadline failure and marks transport closed (< 30s)
    is_closed.store(true, std::sync::atomic::Ordering::Relaxed);
    tokio::time::advance(Duration::from_secs(2)).await;
    tokio::task::yield_now().await;

    assert!(task.is_finished(), "Blackhole must terminate the bridge immediately upon pong deadline failure");
    assert_eq!(
        task.await.unwrap().unwrap_err().kind(),
        std::io::ErrorKind::ConnectionReset
    );
}

#[tokio::test(start_paused = true)]
async fn profile_aware_absolute_idle_timeout_retires_abandoned_flow() {
    let activity = BridgeActivity::new();
    let task = tokio::spawn(async move {
        activity
            .wait(std::future::pending::<()>(), &CancellationToken::new())
            .await
    });
    tokio::task::yield_now().await;
    // Advance past legacy 120s (e.g. 300s): bridge MUST NOT be killed
    tokio::time::advance(Duration::from_secs(300)).await;
    tokio::task::yield_now().await;
    assert!(!task.is_finished(), "Legacy 120-second data-idle kill must not fire");

    // Advance beyond profile-aware absolute timeout (31 minutes): completely abandoned session is retired
    tokio::time::advance(Duration::from_secs(31 * 60)).await;
    tokio::task::yield_now().await;
    assert!(task.is_finished(), "Abandoned session must be retired after absolute ceiling");
    assert_eq!(
        task.await.unwrap().unwrap_err().kind(),
        std::io::ErrorKind::TimedOut
    );
}

