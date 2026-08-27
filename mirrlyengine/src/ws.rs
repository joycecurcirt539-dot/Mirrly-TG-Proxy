use crate::config::*;
use crate::crypto::xor_mask_in_place;
use crate::ldebug;
use base64::Engine;
use byteorder::{BigEndian, ByteOrder};
use rand::RngCore;
use rustls::{ClientConfig, RootCertStore};
use rustls_pki_types::ServerName;
use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;
use tokio_rustls::client::TlsStream;
use tokio_rustls::TlsConnector;

// ---------------------------------------------------------------------------
// WS opcodes
// ---------------------------------------------------------------------------

pub const OP_CONTINUATION: u8 = 0x0;
pub const OP_TEXT: u8 = 0x1;
pub const OP_BINARY: u8 = 0x2;
pub const OP_CLOSE: u8 = 0x8;
pub const OP_PING: u8 = 0x9;
pub const OP_PONG: u8 = 0xA;

pub const MAX_WS_OUTGOING_FRAME: usize = 32 * 1024;

// ---------------------------------------------------------------------------
// TLS config: Secure WebPKI Root CA Verification + session cache (100 sessions)
// ---------------------------------------------------------------------------

use once_cell::sync::Lazy;

static TLS_CONFIG: Lazy<Arc<ClientConfig>> = Lazy::new(|| {
    let mut root_store = RootCertStore::empty();
    root_store.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

    let mut cfg = ClientConfig::builder()
        .with_root_certificates(root_store)
        .with_no_client_auth();
    cfg.resumption = rustls::client::Resumption::in_memory_sessions(100);
    Arc::new(cfg)
});

// ---------------------------------------------------------------------------
// WsHandshakeError
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct WsHandshakeError {
    pub status_code: i32,
    pub status_line: String,
    pub headers: HashMap<String, String>,
    pub location: String,
}

impl WsHandshakeError {
    pub fn is_redirect(&self) -> bool {
        matches!(self.status_code, 301 | 302 | 303 | 307 | 308)
    }
}

#[derive(Debug)]
pub enum WsError {
    Io(std::io::Error),
    Handshake(WsHandshakeError),
    Timeout,
    Canceled,
    Other(String),
}

impl WsError {
    pub fn compact(&self) -> String {
        match self {
            WsError::Canceled => "canceled".to_string(),
            WsError::Timeout => "timeout".to_string(),
            WsError::Handshake(h) => format!("http {}", h.status_code),
            WsError::Io(e) => {
                if e.kind() == std::io::ErrorKind::TimedOut
                    || e.kind() == std::io::ErrorKind::WouldBlock
                {
                    "timeout".to_string()
                } else {
                    e.to_string()
                }
            }
            WsError::Other(s) => s.clone(),
        }
    }
    pub fn handshake_status(&self) -> Option<i32> {
        if let WsError::Handshake(h) = self {
            Some(h.status_code)
        } else {
            None
        }
    }
    pub fn handshake(&self) -> Option<&WsHandshakeError> {
        if let WsError::Handshake(h) = self {
            Some(h)
        } else {
            None
        }
    }
}

pub fn is_http_status_error(err: &WsError, code: i32) -> bool {
    err.handshake_status() == Some(code)
}

pub fn is_cooldown_error(err: &WsError) -> bool {
    if let Some(code) = err.handshake_status() {
        matches!(code, 429 | 500 | 502 | 503 | 504 | 520 | 521 | 522 | 523 | 524)
    } else {
        false
    }
}

impl From<std::io::Error> for WsError {
    fn from(e: std::io::Error) -> Self {
        WsError::Io(e)
    }
}

// ---------------------------------------------------------------------------
// RawWebSocket
// ---------------------------------------------------------------------------

pub struct RawWebSocket {
    reader: tokio::sync::Mutex<BufReader<tokio::io::ReadHalf<TlsStream<TcpStream>>>>,
    writer: tokio::sync::Mutex<tokio::io::WriteHalf<TlsStream<TcpStream>>>,
    pub closed: AtomicBool,
}

impl RawWebSocket {
    pub fn is_closed(&self) -> bool {
        self.closed.load(Ordering::Relaxed)
    }

    pub async fn send(&self, data: &[u8]) -> Result<(), WsError> {
        if self.is_closed() {
            return Err(WsError::Other("WebSocket closed".to_string()));
        }
        if data.len() <= MAX_FRAME_PAYLOAD as usize {
            let frame = build_frame(OP_BINARY, data, true);
            self.write_frame(&frame, WS_WRITE_TIMEOUT).await
        } else {
            // RFC 6455 Fragmented message if payload exceeds max frame limit
            let chunks: Vec<&[u8]> = data.chunks(MAX_FRAME_PAYLOAD as usize).collect();
            let total_chunks = chunks.len();
            let mut writer = self.writer.lock().await;
            for (i, chunk) in chunks.into_iter().enumerate() {
                let opcode = if i == 0 { OP_BINARY } else { OP_CONTINUATION };
                let fin = i == total_chunks - 1;
                let frame = build_frame_ext(opcode, chunk, true, fin);
                match tokio::time::timeout(WS_WRITE_TIMEOUT, writer.write_all(&frame)).await {
                    Ok(Ok(())) => {}
                    Ok(Err(e)) => {
                        self.closed.store(true, Ordering::Relaxed);
                        return Err(WsError::Io(e));
                    }
                    Err(_) => {
                        self.closed.store(true, Ordering::Relaxed);
                        return Err(WsError::Timeout);
                    }
                }
            }
            Ok(())
        }
    }

    pub async fn send_batch(&self, parts: &[Vec<u8>]) -> Result<(), WsError> {
        if self.is_closed() {
            return Err(WsError::Other("WebSocket closed".to_string()));
        }
        let mut writer = self.writer.lock().await;
        for part in parts {
            if part.len() <= MAX_FRAME_PAYLOAD as usize {
                let frame = build_frame(OP_BINARY, part, true);
                match tokio::time::timeout(WS_WRITE_TIMEOUT, writer.write_all(&frame)).await {
                    Ok(Ok(())) => {}
                    Ok(Err(e)) => {
                        self.closed.store(true, Ordering::Relaxed);
                        return Err(WsError::Io(e));
                    }
                    Err(_) => {
                        self.closed.store(true, Ordering::Relaxed);
                        return Err(WsError::Timeout);
                    }
                }
            } else {
                let chunks: Vec<&[u8]> = part.chunks(MAX_FRAME_PAYLOAD as usize).collect();
                let total_chunks = chunks.len();
                for (i, chunk) in chunks.into_iter().enumerate() {
                    let opcode = if i == 0 { OP_BINARY } else { OP_CONTINUATION };
                    let fin = i == total_chunks - 1;
                    let frame = build_frame_ext(opcode, chunk, true, fin);
                    match tokio::time::timeout(WS_WRITE_TIMEOUT, writer.write_all(&frame)).await {
                        Ok(Ok(())) => {}
                        Ok(Err(e)) => {
                            self.closed.store(true, Ordering::Relaxed);
                            return Err(WsError::Io(e));
                        }
                        Err(_) => {
                            self.closed.store(true, Ordering::Relaxed);
                            return Err(WsError::Timeout);
                        }
                    }
                }
            }
        }
        Ok(())
    }

    pub async fn send_ping(&self) -> Result<(), WsError> {
        if self.is_closed() {
            return Err(WsError::Other("WebSocket closed".to_string()));
        }
        let frame = build_frame(OP_PING, &[], true);
        self.write_frame(&frame, WS_CONTROL_TIMEOUT).await
    }

    async fn write_frame(&self, frame: &[u8], timeout: Duration) -> Result<(), WsError> {
        let mut writer = self.writer.lock().await;
        let res = if timeout > Duration::ZERO {
            tokio::time::timeout(timeout, writer.write_all(frame)).await
        } else {
            Ok(writer.write_all(frame).await)
        };
        match res {
            Ok(Ok(())) => Ok(()),
            Ok(Err(e)) => {
                self.closed.store(true, Ordering::Relaxed);
                Err(WsError::Io(e))
            }
            Err(_) => {
                self.closed.store(true, Ordering::Relaxed);
                Err(WsError::Timeout)
            }
        }
    }

    pub async fn recv(&self) -> Result<Vec<u8>, WsError> {
        let mut assembling_buf: Option<Vec<u8>> = None;
        while !self.is_closed() {
            let (fin, opcode, payload) = match self.read_frame().await {
                Ok(v) => v,
                Err(e) => {
                    self.closed.store(true, Ordering::Relaxed);
                    return Err(e);
                }
            };
            match opcode {
                OP_CLOSE => {
                    self.closed.store(true, Ordering::Relaxed);
                    let mut close_payload = payload;
                    if close_payload.len() > 2 {
                        close_payload.truncate(2);
                    }
                    let reply = build_frame(OP_CLOSE, &close_payload, true);
                    let _ = self.write_frame(&reply, WS_CONTROL_TIMEOUT).await;
                    return Err(WsError::Io(std::io::Error::new(
                        std::io::ErrorKind::UnexpectedEof,
                        "EOF",
                    )));
                }
                OP_PING => {
                    let pong = build_frame(OP_PONG, &payload, true);
                    let _ = self.write_frame(&pong, WS_CONTROL_TIMEOUT).await;
                    continue;
                }
                OP_PONG => continue,
                OP_TEXT | OP_BINARY => {
                    if fin {
                        return Ok(payload);
                    } else {
                        assembling_buf = Some(payload);
                    }
                }
                OP_CONTINUATION => {
                    if let Some(mut buf) = assembling_buf.take() {
                        if (buf.len() as u64) + (payload.len() as u64) > MAX_FRAME_PAYLOAD {
                            self.closed.store(true, Ordering::Relaxed);
                            return Err(WsError::Other(format!(
                                "reassembled frame too large: {} bytes",
                                buf.len() + payload.len()
                            )));
                        }
                        buf.extend_from_slice(&payload);
                        if fin {
                            return Ok(buf);
                        } else {
                            assembling_buf = Some(buf);
                        }
                    } else {
                        return Ok(payload);
                    }
                }
                _ => {}
            }
        }
        Err(WsError::Io(std::io::Error::new(
            std::io::ErrorKind::UnexpectedEof,
            "EOF",
        )))
    }

    pub async fn close(&self) {
        if self.closed.swap(true, Ordering::Relaxed) {
            return;
        }
        let frame = build_frame(OP_CLOSE, &[], true);
        let _ = self.write_frame(&frame, WS_CONTROL_TIMEOUT).await;
    }

    pub async fn recv_with_timeout(&self, dur: Duration) -> Result<Vec<u8>, WsError> {
        let mut assembling_buf: Option<Vec<u8>> = None;
        loop {
            if self.is_closed() {
                return Err(WsError::Io(std::io::Error::new(
                    std::io::ErrorKind::UnexpectedEof,
                    "EOF",
                )));
            }
            let frame = {
                let mut reader = self.reader.lock().await;
                match tokio::time::timeout(dur, read_frame_locked(&mut reader)).await {
                    Ok(Ok(v)) => v,
                    Ok(Err(e)) => {
                        self.closed.store(true, Ordering::Relaxed);
                        return Err(e);
                    }
                    Err(_) => return Err(WsError::Timeout),
                }
            };
            let (fin, opcode, payload) = frame;
            match opcode {
                OP_CLOSE => {
                    self.closed.store(true, Ordering::Relaxed);
                    let mut close_payload = payload;
                    if close_payload.len() > 2 {
                        close_payload.truncate(2);
                    }
                    let reply = build_frame(OP_CLOSE, &close_payload, true);
                    let _ = self.write_frame(&reply, WS_CONTROL_TIMEOUT).await;
                    return Err(WsError::Io(std::io::Error::new(
                        std::io::ErrorKind::UnexpectedEof,
                        "EOF",
                    )));
                }
                OP_PING => {
                    let pong = build_frame(OP_PONG, &payload, true);
                    let _ = self.write_frame(&pong, WS_CONTROL_TIMEOUT).await;
                    continue;
                }
                OP_PONG => continue,
                OP_TEXT | OP_BINARY => {
                    if fin {
                        return Ok(payload);
                    } else {
                        assembling_buf = Some(payload);
                    }
                }
                OP_CONTINUATION => {
                    if let Some(mut buf) = assembling_buf.take() {
                        if (buf.len() as u64) + (payload.len() as u64) > MAX_FRAME_PAYLOAD {
                            self.closed.store(true, Ordering::Relaxed);
                            return Err(WsError::Other(format!(
                                "reassembled frame too large: {} bytes",
                                buf.len() + payload.len()
                            )));
                        }
                        buf.extend_from_slice(&payload);
                        if fin {
                            return Ok(buf);
                        } else {
                            assembling_buf = Some(buf);
                        }
                    } else {
                        return Ok(payload);
                    }
                }
                _ => continue,
            }
        }
    }

    async fn read_frame(&self) -> Result<(bool, u8, Vec<u8>), WsError> {
        let mut reader = self.reader.lock().await;
        read_frame_locked(&mut reader).await
    }
}

pub const MAX_FRAME_PAYLOAD: u64 = 16 * 1024 * 1024;

async fn read_frame_locked(
    reader: &mut BufReader<tokio::io::ReadHalf<TlsStream<TcpStream>>>,
) -> Result<(bool, u8, Vec<u8>), WsError> {
    let mut hdr = [0u8; 2];
    reader.read_exact(&mut hdr).await?;

    let fin = (hdr[0] & 0x80) != 0;
    let opcode = hdr[0] & 0x0F;
    let mut length = (hdr[1] & 0x7F) as u64;

    if length == 126 {
        let mut buf = [0u8; 2];
        reader.read_exact(&mut buf).await?;
        length = BigEndian::read_u16(&buf) as u64;
    } else if length == 127 {
        let mut buf = [0u8; 8];
        reader.read_exact(&mut buf).await?;
        length = BigEndian::read_u64(&buf);
    }

    let has_mask = (hdr[1] & 0x80) != 0;
    let mut mask_key = [0u8; 4];
    if has_mask {
        reader.read_exact(&mut mask_key).await?;
    }

    if length > MAX_FRAME_PAYLOAD {
        return Err(WsError::Other(format!("frame too large: {} bytes (max {})", length, MAX_FRAME_PAYLOAD)));
    }
    let mut payload = vec![0u8; length as usize];
    if length > 0 {
        reader.read_exact(&mut payload).await?;
    }
    if has_mask {
        xor_mask_in_place(&mut payload, &mask_key);
    }
    Ok((fin, opcode, payload))
}

// ---------------------------------------------------------------------------
// Frame builder
// ---------------------------------------------------------------------------

pub fn build_frame_ext(opcode: u8, data: &[u8], mask: bool, fin: bool) -> Vec<u8> {
    let length = data.len();
    let fb = if fin { 0x80 | (opcode & 0x0F) } else { opcode & 0x0F };

    let mut header_size = 2;
    if mask {
        header_size += 4;
    }
    if length >= 126 && length < 65536 {
        header_size += 2;
    } else if length >= 65536 {
        header_size += 8;
    }

    let total_size = header_size + length;
    let mut result = vec![0u8; total_size];

    let mut pos = 0;
    result[pos] = fb;
    pos += 1;

    let mut mask_key = [0u8; 4];
    if mask {
        rand::thread_rng().fill_bytes(&mut mask_key);
    }

    if length < 126 {
        let mut lb = length as u8;
        if mask {
            lb |= 0x80;
        }
        result[pos] = lb;
        pos += 1;
    } else if length < 65536 {
        let mut lb = 126u8;
        if mask {
            lb |= 0x80;
        }
        result[pos] = lb;
        pos += 1;
        BigEndian::write_u16(&mut result[pos..], length as u16);
        pos += 2;
    } else {
        let mut lb = 127u8;
        if mask {
            lb |= 0x80;
        }
        result[pos] = lb;
        pos += 1;
        BigEndian::write_u64(&mut result[pos..], length as u64);
        pos += 8;
    }

    if mask {
        result[pos..pos + 4].copy_from_slice(&mask_key);
        pos += 4;
        result[pos..pos + length].copy_from_slice(data);
        xor_mask_in_place(&mut result[pos..pos + length], &mask_key);
    } else {
        result[pos..pos + length].copy_from_slice(data);
    }
    result
}

pub fn build_frame(opcode: u8, data: &[u8], mask: bool) -> Vec<u8> {
    build_frame_ext(opcode, data, mask, true)
}

// ---------------------------------------------------------------------------
// Connection helpers
// ---------------------------------------------------------------------------

fn set_sock_opts(stream: &TcpStream) {
    let nodelay = TCP_NODELAY.load(Ordering::Relaxed);
    let _ = stream.set_nodelay(nodelay);
    let sock = socket2::SockRef::from(stream);
    #[allow(unused_mut)]
    let mut ka = socket2::TcpKeepalive::new()
        .with_time(Duration::from_secs(30))
        .with_interval(Duration::from_secs(10));
    #[cfg(any(target_os = "android", unix))]
    {
        ka = ka.with_retries(3);
    }
    let _ = sock.set_tcp_keepalive(&ka);
}

pub fn ws_connect_timeout(timeout: f64) -> Duration {
    if timeout <= 0.0 {
        Duration::from_secs(5)
    } else {
        Duration::from_secs_f64(timeout)
    }
}

pub fn ws_handshake_timeout(total: Duration) -> Duration {
    if total <= Duration::ZERO {
        Duration::from_secs(3)
    } else if total > Duration::from_secs(3) {
        Duration::from_secs(3)
    } else {
        total
    }
}

pub fn compute_sec_websocket_accept(key: &str) -> String {
    const WS_GUID: &[u8] = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    let mut data = Vec::with_capacity(key.len() + WS_GUID.len());
    data.extend_from_slice(key.as_bytes());
    data.extend_from_slice(WS_GUID);
    let digest = ring::digest::digest(&ring::digest::SHA1_FOR_LEGACY_USE_ONLY, &data);
    base64::engine::general_purpose::STANDARD.encode(digest.as_ref())
}

fn server_name(domain: &str) -> ServerName<'static> {
    ServerName::try_from(domain.to_string())
        .unwrap_or_else(|_| ServerName::IpAddress("127.0.0.1".parse::<IpAddr>().unwrap().into()))
}

pub const HAPPY_EYEBALLS_DELAY: Duration = Duration::from_millis(200);

pub async fn happy_eyeballs_tcp_connect(
    addrs: &[SocketAddr],
    total_timeout: Duration,
) -> Result<(TcpStream, SocketAddr), WsError> {
    if addrs.is_empty() {
        return Err(WsError::Other("no candidate addresses provided".to_string()));
    }
    if addrs.len() == 1 {
        let addr = addrs[0];
        let stream = match tokio::time::timeout(total_timeout, TcpStream::connect(addr)).await {
            Ok(Ok(s)) => s,
            Ok(Err(e)) => return Err(WsError::Io(e)),
            Err(_) => return Err(WsError::Timeout),
        };
        set_sock_opts(&stream);
        return Ok((stream, addr));
    }

    let (tx, mut rx) = tokio::sync::mpsc::channel::<(TcpStream, SocketAddr)>(1);
    let cancel_token = tokio_util::sync::CancellationToken::new();
    let mut tasks: Vec<tokio::task::JoinHandle<()>> = Vec::with_capacity(addrs.len());

    let mut next_idx = 0;
    let num_addrs = addrs.len();

    let deadline = tokio::time::sleep(total_timeout);
    tokio::pin!(deadline);

    let mut stagger_timer = tokio::time::interval(HAPPY_EYEBALLS_DELAY);
    let mut remaining_active: usize = 0;

    let (err_tx, mut err_rx) = tokio::sync::mpsc::channel::<std::io::Error>(num_addrs);
    #[allow(unused_assignments)]
    let mut last_err = None;

    loop {
        tokio::select! {
            _ = &mut deadline => {
                cancel_token.cancel();
                for t in tasks {
                    t.abort();
                }
                return Err(WsError::Timeout);
            }
            res = rx.recv() => {
                if let Some((stream, winning_addr)) = res {
                    cancel_token.cancel();
                    for t in tasks {
                        t.abort();
                    }
                    set_sock_opts(&stream);
                    return Ok((stream, winning_addr));
                }
            }
            err = err_rx.recv() => {
                if let Some(e) = err {
                    last_err = Some(e);
                    remaining_active = remaining_active.saturating_sub(1);
                    // Fast failover: immediately launch next candidate on early connection failure
                    if next_idx < num_addrs {
                        let target_addr = addrs[next_idx];
                        next_idx += 1;
                        remaining_active += 1;

                        let tx = tx.clone();
                        let err_tx = err_tx.clone();
                        let cancel = cancel_token.clone();

                        tasks.push(tokio::spawn(async move {
                            tokio::select! {
                                _ = cancel.cancelled() => {}
                                res = TcpStream::connect(target_addr) => {
                                    match res {
                                        Ok(s) => {
                                            let _ = tx.send((s, target_addr)).await;
                                        }
                                        Err(e) => {
                                            let _ = err_tx.send(e).await;
                                        }
                                    }
                                }
                            }
                        }));
                    } else if remaining_active == 0 {
                        break;
                    }
                }
            }
            _ = stagger_timer.tick(), if next_idx < num_addrs => {
                let target_addr = addrs[next_idx];
                next_idx += 1;
                remaining_active += 1;

                let tx = tx.clone();
                let err_tx = err_tx.clone();
                let cancel = cancel_token.clone();

                tasks.push(tokio::spawn(async move {
                    tokio::select! {
                        _ = cancel.cancelled() => {}
                        res = TcpStream::connect(target_addr) => {
                            match res {
                                Ok(s) => {
                                    let _ = tx.send((s, target_addr)).await;
                                }
                                Err(e) => {
                                    let _ = err_tx.send(e).await;
                                }
                            }
                        }
                    }
                }));
            }
        }
    }

    cancel_token.cancel();
    for t in tasks {
        t.abort();
    }

    if let Some(e) = last_err {
        Err(WsError::Io(e))
    } else {
        Err(WsError::Other("all connection candidates failed".to_string()))
    }
}

pub async fn ws_handshake_over_stream(
    raw_conn: TcpStream,
    dial_ip: &str,
    domain: &str,
    path: &str,
    timeout: Duration,
) -> Result<RawWebSocket, WsError> {
    set_sock_opts(&raw_conn);

    let connector = TlsConnector::from(TLS_CONFIG.clone());
    let sni = server_name(domain);

    let handshake_timeout = ws_handshake_timeout(timeout);
    let tls_conn =
        match tokio::time::timeout(handshake_timeout, connector.connect(sni, raw_conn)).await {
            Ok(Ok(c)) => c,
            Ok(Err(e)) => {
                if e.kind() != std::io::ErrorKind::ConnectionReset {
                    ldebug!(" ws tls fail {} via {}: {}", domain, dial_ip, e);
                }
                return Err(WsError::Io(e));
            }
            Err(_) => {
                ldebug!(" ws tls fail {} via {}: timeout", domain, dial_ip);
                return Err(WsError::Timeout);
            }
        };

    let (read_half, mut write_half) = tokio::io::split(tls_conn);

    let mut ws_key_bytes = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut ws_key_bytes);
    let ws_key = base64::engine::general_purpose::STANDARD.encode(ws_key_bytes);

    let req = format!(
        "GET {} HTTP/1.1\r\n\
         Host: {}\r\n\
         Upgrade: websocket\r\n\
         Connection: Upgrade\r\n\
         Sec-WebSocket-Key: {}\r\n\
         Sec-WebSocket-Version: 13\r\n\
         Sec-WebSocket-Protocol: binary\r\n\r\n",
        path, domain, ws_key
    );

    match tokio::time::timeout(timeout, write_half.write_all(req.as_bytes())).await {
        Ok(Ok(())) => {}
        Ok(Err(e)) => return Err(WsError::Io(e)),
        Err(_) => return Err(WsError::Timeout),
    }

    let mut bufreader = BufReader::with_capacity(4096, read_half);

    let mut response_lines: Vec<String> = Vec::new();
    let read_result = tokio::time::timeout(timeout, async {
        loop {
            let line = read_line(&mut bufreader).await?;
            let line = line.trim_end_matches(['\r', '\n']).to_string();
            if line.is_empty() {
                break;
            }
            response_lines.push(line);
            if response_lines.len() > 100 {
                return Err(WsError::Other("too many HTTP headers".to_string()));
            }
        }
        Ok::<(), WsError>(())
    })
    .await;

    match read_result {
        Ok(Ok(())) => {}
        Ok(Err(e)) => return Err(e),
        Err(_) => return Err(WsError::Timeout),
    }

    if response_lines.is_empty() {
        return Err(WsError::Handshake(WsHandshakeError {
            status_code: 0,
            status_line: "empty response".to_string(),
            headers: HashMap::new(),
            location: String::new(),
        }));
    }

    let first_line = response_lines[0].clone();
    let parts: Vec<&str> = first_line.splitn(3, ' ').collect();
    let mut status_code = 0;
    if parts.len() >= 2 {
        status_code = parts[1].parse::<i32>().unwrap_or(0);
    }

    let mut headers = HashMap::new();
    for hl in &response_lines[1..] {
        if let Some(idx) = hl.find(':') {
            headers.insert(
                hl[..idx].trim().to_lowercase(),
                hl[idx + 1..].trim().to_string(),
            );
        }
    }

    if status_code == 101 {
        // RFC 6455: Upgrade header must be "websocket"
        let upgrade = headers.get("upgrade").map(|s| s.to_lowercase()).unwrap_or_default();
        if upgrade != "websocket" {
            return Err(WsError::Handshake(WsHandshakeError {
                status_code,
                status_line: "Invalid Upgrade Header".to_string(),
                headers,
                location: String::new(),
            }));
        }

        // RFC 6455 Section 4.2.2: Sec-WebSocket-Accept = Base64(SHA1(Key + GUID))
        let expected_accept = compute_sec_websocket_accept(&ws_key);
        let actual_accept = headers.get("sec-websocket-accept").cloned().unwrap_or_default();

        if actual_accept != expected_accept {
            ldebug!(" ws handshake invalid Sec-WebSocket-Accept: expected={}, got={}", expected_accept, actual_accept);
            return Err(WsError::Handshake(WsHandshakeError {
                status_code,
                status_line: "Invalid Sec-WebSocket-Accept Header".to_string(),
                headers,
                location: String::new(),
            }));
        }

        return Ok(RawWebSocket {
            reader: tokio::sync::Mutex::new(bufreader),
            writer: tokio::sync::Mutex::new(write_half),
            closed: AtomicBool::new(false),
        });
    }

    let location = headers.get("location").cloned().unwrap_or_default();
    Err(WsError::Handshake(WsHandshakeError {
        status_code,
        status_line: first_line,
        headers,
        location,
    }))
}

pub async fn ws_connect_happy_eyeballs(
    domain: &str,
    path: &str,
    addrs: &[SocketAddr],
    timeout: Duration,
) -> Result<(RawWebSocket, SocketAddr), WsError> {
    let (stream, winner_addr) = happy_eyeballs_tcp_connect(addrs, timeout).await?;
    let dial_ip = winner_addr.ip().to_string();
    let ws = ws_handshake_over_stream(stream, &dial_ip, domain, path, timeout).await?;
    Ok((ws, winner_addr))
}

pub async fn ws_connect_once(
    dial_addr: &str,
    domain: &str,
    path: &str,
    timeout: Duration,
) -> Result<RawWebSocket, WsError> {
    if dial_addr.is_empty() {
        return Err(WsError::Other("empty dial address".to_string()));
    }

    if let Ok(ip) = dial_addr.parse::<IpAddr>() {
        let sock_addr = SocketAddr::new(ip, 443);
        let (stream, _) = happy_eyeballs_tcp_connect(&[sock_addr], timeout).await?;
        return ws_handshake_over_stream(stream, dial_addr, domain, path, timeout).await;
    }

    let target_addr = if dial_addr.contains(':') {
        dial_addr.to_string()
    } else {
        format!("{}:443", dial_addr)
    };

    let raw_conn = match tokio::time::timeout(timeout, TcpStream::connect(&target_addr)).await {
        Ok(Ok(c)) => c,
        Ok(Err(e)) => return Err(WsError::Io(e)),
        Err(_) => return Err(WsError::Timeout),
    };
    ws_handshake_over_stream(raw_conn, dial_addr, domain, path, timeout).await
}

async fn read_line<R: AsyncReadExt + Unpin>(reader: &mut R) -> Result<String, WsError> {
    let mut buf = Vec::with_capacity(128);
    let mut byte = [0u8; 1];
    loop {
        let n = reader.read(&mut byte).await?;
        if n == 0 {
            return Err(WsError::Io(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "EOF",
            )));
        }
        buf.push(byte[0]);
        if byte[0] == b'\n' {
            break;
        }
        if buf.len() > 16384 {
            return Err(WsError::Other("header line too long".to_string()));
        }
    }
    Ok(String::from_utf8_lossy(&buf).to_string())
}

pub async fn ws_connect(
    ip: &str,
    domain: &str,
    path: &str,
    timeout: f64,
) -> Result<RawWebSocket, WsError> {
    let path = if path.is_empty() { "/apiws" } else { path };
    let attempt_timeout = ws_connect_timeout(timeout);

    let candidate_ips = if !ip.trim().is_empty() {
        if let Ok(parsed) = ip.trim().parse::<IpAddr>() {
            vec![parsed]
        } else {
            crate::cfproxy::resolve_dual_stack_ips(ip.trim()).await
        }
    } else {
        crate::cfproxy::resolve_dual_stack_ips(domain).await
    };

    let candidate_addrs: Vec<SocketAddr> = candidate_ips
        .into_iter()
        .map(|ip_addr| SocketAddr::new(ip_addr, 443))
        .collect();

    if candidate_addrs.is_empty() {
        return Err(WsError::Other("no candidate addresses found".to_string()));
    }

    let (ws, _) = ws_connect_happy_eyeballs(domain, path, &candidate_addrs, attempt_timeout).await?;
    Ok(ws)
}

pub async fn connect_one_ws(ip: &str, domains: &[String]) -> Option<RawWebSocket> {
    for d in domains {
        if let Ok(ws) = ws_connect(ip, d, "/apiws", WS_POOL_CONNECT_TIMEOUT).await {
            return Some(ws);
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_build_frame_unfragmented() {
        let payload = b"hello telegram ws";
        let frame = build_frame(OP_BINARY, payload, false);

        assert_eq!(frame[0], 0x82); // FIN=1 (0x80) | OP_BINARY (0x02)
        assert_eq!(frame[1], payload.len() as u8); // Unmasked
        assert_eq!(&frame[2..], payload);
    }

    #[test]
    fn test_build_frame_fragmented_rfc6455() {
        let chunk1 = b"fragment 1";
        let chunk2 = b"fragment 2";
        let chunk3 = b"fragment 3";

        // Frame 1: OP_BINARY, FIN=0
        let f1 = build_frame_ext(OP_BINARY, chunk1, false, false);
        assert_eq!(f1[0], 0x02); // FIN=0, opcode=2
        assert_eq!(f1[1], chunk1.len() as u8);
        assert_eq!(&f1[2..], chunk1);

        // Frame 2: OP_CONTINUATION, FIN=0
        let f2 = build_frame_ext(OP_CONTINUATION, chunk2, false, false);
        assert_eq!(f2[0], 0x00); // FIN=0, opcode=0
        assert_eq!(f2[1], chunk2.len() as u8);
        assert_eq!(&f2[2..], chunk2);

        // Frame 3: OP_CONTINUATION, FIN=1
        let f3 = build_frame_ext(OP_CONTINUATION, chunk3, false, true);
        assert_eq!(f3[0], 0x80); // FIN=1, opcode=0
        assert_eq!(f3[1], chunk3.len() as u8);
        assert_eq!(&f3[2..], chunk3);
    }

    #[test]
    fn test_build_frame_large_payload() {
        let payload = vec![0x42u8; 70000]; // > 65535 bytes -> 8-byte length
        let frame = build_frame(OP_BINARY, &payload, false);

        assert_eq!(frame[0], 0x82);
        assert_eq!(frame[1], 127);
        let len = BigEndian::read_u64(&frame[2..10]);
        assert_eq!(len, 70000);
        assert_eq!(&frame[10..], &payload[..]);
    }

    #[test]
    fn test_max_frame_payload_limit() {
        assert_eq!(MAX_FRAME_PAYLOAD, 16 * 1024 * 1024);
    }
}

