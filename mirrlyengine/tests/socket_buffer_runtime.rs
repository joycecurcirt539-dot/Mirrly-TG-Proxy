use mirrlyengine::config::{
    get_socket_buffer_status, RECV_BUF, REQUESTED_RECV_BUF, REQUESTED_SEND_BUF, SEND_BUF,
};
use mirrlyengine::ws::set_sock_opts;
use mirrlyengine::{GetLastOsSocketBufferSizes, SetSocketBufferSizes};
use std::sync::atomic::Ordering;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

#[tokio::test(flavor = "current_thread")]
async fn socket_buffer_change_applies_only_to_new_real_sockets() {
    struct BufferConfigRestore {
        requested_recv: i32,
        requested_send: i32,
        recv: i32,
        send: i32,
    }
    impl Drop for BufferConfigRestore {
        fn drop(&mut self) {
            REQUESTED_RECV_BUF.store(self.requested_recv, Ordering::Relaxed);
            REQUESTED_SEND_BUF.store(self.requested_send, Ordering::Relaxed);
            RECV_BUF.store(self.recv, Ordering::Relaxed);
            SEND_BUF.store(self.send, Ordering::Relaxed);
        }
    }
    let _restore = BufferConfigRestore {
        requested_recv: REQUESTED_RECV_BUF.load(Ordering::Relaxed),
        requested_send: REQUESTED_SEND_BUF.load(Ordering::Relaxed),
        recv: RECV_BUF.load(Ordering::Relaxed),
        send: SEND_BUF.load(Ordering::Relaxed),
    };

    let listener = TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind loopback listener");
    let address = listener.local_addr().expect("loopback address");

    SetSocketBufferSizes(16 * 1024, 4 * 1024 * 1024);
    let clamped = get_socket_buffer_status();
    assert_eq!(clamped.configured_recv_bytes, 16 * 1024);
    assert_eq!(clamped.configured_send_bytes, 4 * 1024 * 1024);
    assert_eq!(clamped.clamped_recv_bytes, 32 * 1024);
    assert_eq!(clamped.clamped_send_bytes, 2 * 1024 * 1024);
    assert_eq!(
        unsafe { GetLastOsSocketBufferSizes(std::ptr::null_mut(), std::ptr::null_mut()) },
        -1
    );

    SetSocketBufferSizes(32 * 1024, 32 * 1024);
    let mut old_socket = TcpStream::connect(address)
        .await
        .expect("connect old socket");
    let (mut old_peer, _) = listener.accept().await.expect("accept old socket");
    set_sock_opts(&old_socket);
    let old_sizes = socket_buffer_sizes(&old_socket);

    SetSocketBufferSizes(2 * 1024 * 1024, 2 * 1024 * 1024);

    // Updating the atomic policy must not mutate or restart an established flow.
    assert_eq!(old_sizes, socket_buffer_sizes(&old_socket));
    old_socket
        .write_all(b"old-flow-stays-alive")
        .await
        .expect("write old flow");
    let mut old_payload = [0_u8; 20];
    old_peer
        .read_exact(&mut old_payload)
        .await
        .expect("read old flow");
    assert_eq!(&old_payload, b"old-flow-stays-alive");

    let new_socket = TcpStream::connect(address)
        .await
        .expect("connect new socket");
    let (_new_peer, _) = listener.accept().await.expect("accept new socket");
    set_sock_opts(&new_socket);
    let new_sizes = socket_buffer_sizes(&new_socket);

    assert!(
        new_sizes.0 > old_sizes.0 || new_sizes.1 > old_sizes.1,
        "new real socket must observe a larger OS buffer: old={old_sizes:?}, new={new_sizes:?}"
    );
    let mut ffi_recv = 0;
    let mut ffi_send = 0;
    let ffi_code =
        unsafe { GetLastOsSocketBufferSizes(&mut ffi_recv as *mut i32, &mut ffi_send as *mut i32) };
    assert_eq!(ffi_code, 0);
    assert_eq!((ffi_recv, ffi_send), new_sizes);
    assert_eq!(
        get_socket_buffer_status().clamped_recv_bytes,
        2 * 1024 * 1024
    );
}

fn socket_buffer_sizes(stream: &TcpStream) -> (i32, i32) {
    let socket = socket2::SockRef::from(stream);
    (
        socket.recv_buffer_size().expect("SO_RCVBUF") as i32,
        socket.send_buffer_size().expect("SO_SNDBUF") as i32,
    )
}
