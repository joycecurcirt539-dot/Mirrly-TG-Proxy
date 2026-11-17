use mirrlyengine::config::{
    get_socket_buffer_status, max_socket_buffer, DEFAULT_MAX_SOCKET_BUFFER,
    HIGH_THROUGHPUT_MAX_SOCKET_BUFFER, MIN_SOCKET_BUFFER, RECV_BUF, REQUESTED_RECV_BUF,
    REQUESTED_SEND_BUF, SEND_BUF,
};
use mirrlyengine::ws::set_sock_opts;
use mirrlyengine::{
    GetLastOsSocketBufferSizes, SetMaxSocketBufferCeiling, SetSocketBufferSizes,
};
use std::sync::atomic::Ordering;
use std::sync::{Arc, Barrier};
use std::thread;
use tokio::net::{TcpListener, TcpStream};

static SERIAL_LOCK: parking_lot::Mutex<()> = parking_lot::Mutex::new(());

struct BufferRestoreGuard {
    ceiling: i32,
    req_recv: i32,
    req_send: i32,
    recv: i32,
    send: i32,
}

impl BufferRestoreGuard {
    fn new() -> Self {
        Self {
            ceiling: max_socket_buffer(),
            req_recv: REQUESTED_RECV_BUF.load(Ordering::Relaxed),
            req_send: REQUESTED_SEND_BUF.load(Ordering::Relaxed),
            recv: RECV_BUF.load(Ordering::Relaxed),
            send: SEND_BUF.load(Ordering::Relaxed),
        }
    }
}

impl Drop for BufferRestoreGuard {
    fn drop(&mut self) {
        SetMaxSocketBufferCeiling(self.ceiling);
        REQUESTED_RECV_BUF.store(self.req_recv, Ordering::Relaxed);
        REQUESTED_SEND_BUF.store(self.req_send, Ordering::Relaxed);
        RECV_BUF.store(self.recv, Ordering::Relaxed);
        SEND_BUF.store(self.send, Ordering::Relaxed);
    }
}

#[test]
fn test_default_ceiling_clamps_to_2mb() {
    let _lock = SERIAL_LOCK.lock();
    let _restore = BufferRestoreGuard::new();

    // Reset ceiling to default 2MB
    SetMaxSocketBufferCeiling(DEFAULT_MAX_SOCKET_BUFFER);
    assert_eq!(max_socket_buffer(), DEFAULT_MAX_SOCKET_BUFFER);
    assert_eq!(max_socket_buffer(), 2 * 1024 * 1024);

    // Request 4MB recv and 10MB send -> must clamp both to 2MB ceiling
    SetSocketBufferSizes(4 * 1024 * 1024, 10 * 1024 * 1024);
    let status = get_socket_buffer_status();
    assert_eq!(status.configured_recv_bytes, 4 * 1024 * 1024);
    assert_eq!(status.configured_send_bytes, 10 * 1024 * 1024);
    assert_eq!(status.clamped_recv_bytes, 2 * 1024 * 1024);
    assert_eq!(status.clamped_send_bytes, 2 * 1024 * 1024);
    assert!(!status.autotune_baseline);

    // Request i32::MAX -> must clamp to 2MB
    SetSocketBufferSizes(i32::MAX, i32::MAX);
    let status = get_socket_buffer_status();
    assert_eq!(status.clamped_recv_bytes, 2 * 1024 * 1024);
    assert_eq!(status.clamped_send_bytes, 2 * 1024 * 1024);
}

#[test]
fn test_dynamic_ceiling_expands_to_8mb() {
    let _lock = SERIAL_LOCK.lock();
    let _restore = BufferRestoreGuard::new();

    // Expand ceiling to 8MB
    SetMaxSocketBufferCeiling(8 * 1024 * 1024);
    assert_eq!(max_socket_buffer(), 8 * 1024 * 1024);
    assert_eq!(max_socket_buffer(), HIGH_THROUGHPUT_MAX_SOCKET_BUFFER);

    // Now request 4MB and 8MB -> both should be permitted up to 8MB
    SetSocketBufferSizes(4 * 1024 * 1024, 8 * 1024 * 1024);
    let status = get_socket_buffer_status();
    assert_eq!(status.configured_recv_bytes, 4 * 1024 * 1024);
    assert_eq!(status.configured_send_bytes, 8 * 1024 * 1024);
    assert_eq!(status.clamped_recv_bytes, 4 * 1024 * 1024);
    assert_eq!(status.clamped_send_bytes, 8 * 1024 * 1024);

    // Request 16MB and 32MB -> clamped to the 8MB expanded ceiling
    SetSocketBufferSizes(16 * 1024 * 1024, 32 * 1024 * 1024);
    let status = get_socket_buffer_status();
    assert_eq!(status.clamped_recv_bytes, 8 * 1024 * 1024);
    assert_eq!(status.clamped_send_bytes, 8 * 1024 * 1024);
}

#[test]
fn test_minimum_socket_buffer_clamping_32kb() {
    let _lock = SERIAL_LOCK.lock();
    let _restore = BufferRestoreGuard::new();

    SetMaxSocketBufferCeiling(DEFAULT_MAX_SOCKET_BUFFER);

    // Request 1 byte and 1024 bytes -> both must clamp UP to MIN_SOCKET_BUFFER (32KB)
    SetSocketBufferSizes(1, 1024);
    let status = get_socket_buffer_status();
    assert_eq!(status.configured_recv_bytes, 1);
    assert_eq!(status.configured_send_bytes, 1024);
    assert_eq!(status.clamped_recv_bytes, MIN_SOCKET_BUFFER);
    assert_eq!(status.clamped_send_bytes, MIN_SOCKET_BUFFER);
    assert_eq!(status.clamped_recv_bytes, 32 * 1024);
    assert_eq!(status.clamped_send_bytes, 32 * 1024);

    // Request 31KB -> clamped to 32KB
    SetSocketBufferSizes(31 * 1024, 31 * 1024);
    let status = get_socket_buffer_status();
    assert_eq!(status.clamped_recv_bytes, 32 * 1024);
    assert_eq!(status.clamped_send_bytes, 32 * 1024);

    // Exact 32KB -> permitted
    SetSocketBufferSizes(32 * 1024, 32 * 1024);
    let status = get_socket_buffer_status();
    assert_eq!(status.clamped_recv_bytes, 32 * 1024);
    assert_eq!(status.clamped_send_bytes, 32 * 1024);
}

#[test]
fn test_zero_autotune_and_negative_inputs() {
    let _lock = SERIAL_LOCK.lock();
    let _restore = BufferRestoreGuard::new();

    // 0 must enable autotune baseline
    SetSocketBufferSizes(0, 0);
    let status = get_socket_buffer_status();
    assert_eq!(status.configured_recv_bytes, 0);
    assert_eq!(status.configured_send_bytes, 0);
    assert_eq!(status.clamped_recv_bytes, 0);
    assert_eq!(status.clamped_send_bytes, 0);
    assert!(status.autotune_baseline);

    // Negative inputs must be sanitized to 0
    SetSocketBufferSizes(-1000, -500);
    let status = get_socket_buffer_status();
    assert_eq!(status.configured_recv_bytes, 0);
    assert_eq!(status.configured_send_bytes, 0);
    assert_eq!(status.clamped_recv_bytes, 0);
    assert_eq!(status.clamped_send_bytes, 0);
    assert!(status.autotune_baseline);

    // Asymmetric: recv=0 (autotune), send=64KB
    SetSocketBufferSizes(0, 64 * 1024);
    let status = get_socket_buffer_status();
    assert_eq!(status.clamped_recv_bytes, 0);
    assert_eq!(status.clamped_send_bytes, 64 * 1024);
    assert!(!status.autotune_baseline);
}

#[test]
fn test_ceiling_boundary_clamping() {
    let _lock = SERIAL_LOCK.lock();
    let _restore = BufferRestoreGuard::new();

    // Upper bound on ceiling itself: cannot exceed HIGH_THROUGHPUT_MAX_SOCKET_BUFFER (8MB)
    SetMaxSocketBufferCeiling(100 * 1024 * 1024);
    assert_eq!(max_socket_buffer(), 8 * 1024 * 1024);

    // Lower bound on ceiling itself: cannot go below MIN_SOCKET_BUFFER (32KB)
    SetMaxSocketBufferCeiling(1024);
    assert_eq!(max_socket_buffer(), 32 * 1024);

    SetMaxSocketBufferCeiling(-500);
    assert_eq!(max_socket_buffer(), 32 * 1024);

    // Ceiling contraction: ceiling is 64KB -> buffers capped at 64KB
    SetMaxSocketBufferCeiling(64 * 1024);
    assert_eq!(max_socket_buffer(), 64 * 1024);

    SetSocketBufferSizes(1024 * 1024, 2 * 1024 * 1024);
    let status = get_socket_buffer_status();
    assert_eq!(status.clamped_recv_bytes, 64 * 1024);
    assert_eq!(status.clamped_send_bytes, 64 * 1024);
}

#[test]
fn test_concurrent_mutations_race_stress() {
    let _lock = SERIAL_LOCK.lock();
    let _restore = BufferRestoreGuard::new();

    const NUM_THREADS: usize = 12;
    const ITERS_PER_THREAD: usize = 2000;

    let barrier = Arc::new(Barrier::new(NUM_THREADS));
    let mut handles = Vec::new();

    for tid in 0..NUM_THREADS {
        let b = barrier.clone();
        let handle = thread::spawn(move || {
            b.wait();
            for i in 0..ITERS_PER_THREAD {
                match (tid + i) % 4 {
                    0 => {
                        let c = match i % 5 {
                            0 => -100,
                            1 => 16 * 1024,
                            2 => 64 * 1024,
                            3 => 2 * 1024 * 1024,
                            _ => 16 * 1024 * 1024,
                        };
                        SetMaxSocketBufferCeiling(c);
                        let ceil = max_socket_buffer();
                        assert!(ceil >= MIN_SOCKET_BUFFER);
                        assert!(ceil <= HIGH_THROUGHPUT_MAX_SOCKET_BUFFER);
                    }
                    1 => {
                        let r = ((i * 1024) % (12 * 1024 * 1024)) as i32;
                        let s = (((i + 1) * 2048) % (12 * 1024 * 1024)) as i32;
                        SetSocketBufferSizes(r, s);
                    }
                    2 => {
                        let st = get_socket_buffer_status();
                        // Invariants: either 0 (autotune) or >= 32KB and <= 8MB
                        if st.clamped_recv_bytes != 0 {
                            assert!(st.clamped_recv_bytes >= MIN_SOCKET_BUFFER);
                            assert!(st.clamped_recv_bytes <= HIGH_THROUGHPUT_MAX_SOCKET_BUFFER);
                        }
                        if st.clamped_send_bytes != 0 {
                            assert!(st.clamped_send_bytes >= MIN_SOCKET_BUFFER);
                            assert!(st.clamped_send_bytes <= HIGH_THROUGHPUT_MAX_SOCKET_BUFFER);
                        }
                    }
                    _ => {
                        let mut r = 0;
                        let mut s = 0;
                        let code = unsafe {
                            GetLastOsSocketBufferSizes(&mut r as *mut i32, &mut s as *mut i32)
                        };
                        assert_eq!(code, 0);
                        assert!(r >= 0);
                        assert!(s >= 0);
                    }
                }
            }
        });
        handles.push(handle);
    }

    for h in handles {
        h.join().expect("thread join failed under stress");
    }
}

#[tokio::test(flavor = "current_thread")]
async fn test_real_socket_os_buffer_application_with_dynamic_ceiling() {
    let _lock = SERIAL_LOCK.lock();
    let _restore = BufferRestoreGuard::new();

    let listener = TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind loopback listener");
    let address = listener.local_addr().expect("loopback address");

    // Expand ceiling to 8MB and set buffer to 4MB
    SetMaxSocketBufferCeiling(8 * 1024 * 1024);
    SetSocketBufferSizes(4 * 1024 * 1024, 4 * 1024 * 1024);

    let status = get_socket_buffer_status();
    assert_eq!(status.clamped_recv_bytes, 4 * 1024 * 1024);
    assert_eq!(status.clamped_send_bytes, 4 * 1024 * 1024);

    let socket = TcpStream::connect(address)
        .await
        .expect("connect stream");
    let (_peer, _) = listener.accept().await.expect("accept stream");

    set_sock_opts(&socket);

    let sock_ref = socket2::SockRef::from(&socket);
    let os_recv = sock_ref.recv_buffer_size().expect("SO_RCVBUF") as i32;
    let os_send = sock_ref.send_buffer_size().expect("SO_SNDBUF") as i32;

    assert!(os_recv > 0);
    assert!(os_send > 0);

    let mut ffi_recv = 0;
    let mut ffi_send = 0;
    let res = unsafe {
        GetLastOsSocketBufferSizes(&mut ffi_recv as *mut i32, &mut ffi_send as *mut i32)
    };
    assert_eq!(res, 0);
    assert_eq!(ffi_recv, os_recv);
    assert_eq!(ffi_send, os_send);
}
