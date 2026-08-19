use crate::doh::DohResolver;
use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use parking_lot::Mutex as SyncMutex;
use rand::Rng;
use rustls::pki_types::ServerName;
use rustls::ClientConfig;
use std::collections::{HashMap, VecDeque};
use std::io;
use std::net::SocketAddr;
use std::sync::atomic::AtomicBool;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::{mpsc, Mutex as TokioMutex};
use tokio_rustls::client::TlsStream;
use tokio_rustls::TlsConnector;

/// Safety cap: reject WS frames larger than 16 MiB to prevent OOM.
const MAX_WS_FRAME_PAYLOAD: u64 = 16 * 1024 * 1024;
const MAX_POOL_AGE: Duration = Duration::from_secs(60);

// ── Internal raw frame ────────────────────────────────────────────────────────

struct WsFrame {
    opcode: u8,
    data: Vec<u8>,
}

async fn read_exact_with_early_data<R: AsyncRead + Unpin>(
    reader: &mut R,
    early_data: &mut Vec<u8>,
    early_offset: &mut usize,
    buf: &mut [u8],
) -> io::Result<()> {
    let mut needed = buf.len();
    let mut buf_offset = 0;

    let available_early = early_data.len().saturating_sub(*early_offset);
    if available_early > 0 {
        let to_take = needed.min(available_early);
        buf[..to_take].copy_from_slice(&early_data[*early_offset..*early_offset + to_take]);
        *early_offset += to_take;
        needed -= to_take;
        buf_offset += to_take;
    }

    if needed > 0 {
        reader.read_exact(&mut buf[buf_offset..]).await?;
    }

    Ok(())
}

async fn read_one_ws_frame_buffered<R: AsyncRead + Unpin>(
    reader: &mut R,
    early_data: &mut Vec<u8>,
    early_offset: &mut usize,
) -> io::Result<WsFrame> {
    let mut head = [0u8; 2];
    read_exact_with_early_data(reader, early_data, early_offset, &mut head).await?;

    let opcode = head[0] & 0x0F;
    let is_masked = (head[1] & 0x80) != 0;
    let mut payload_len = (head[1] & 0x7F) as u64;

    if payload_len == 126 {
        let mut ext = [0u8; 2];
        read_exact_with_early_data(reader, early_data, early_offset, &mut ext).await?;
        payload_len = u16::from_be_bytes(ext) as u64;
    } else if payload_len == 127 {
        let mut ext = [0u8; 8];
        read_exact_with_early_data(reader, early_data, early_offset, &mut ext).await?;
        payload_len = u64::from_be_bytes(ext);
    }

    if payload_len > MAX_WS_FRAME_PAYLOAD {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("WebSocket frame too large: {} bytes", payload_len),
        ));
    }

    let mask_key = if is_masked {
        let mut mask = [0u8; 4];
        read_exact_with_early_data(reader, early_data, early_offset, &mut mask).await?;
        Some(mask)
    } else {
        None
    };

    let mut data = vec![0u8; payload_len as usize];
    read_exact_with_early_data(reader, early_data, early_offset, &mut data).await?;

    if let Some(mask) = mask_key {
        for (i, b) in data.iter_mut().enumerate() {
            *b ^= mask[i % 4];
        }
    }

    Ok(WsFrame { opcode, data })
}

/// Sends a masked binary WebSocket frame (RFC 6455 §5.3) with optimized single write.
async fn write_ws_binary_frame<W: AsyncWrite + Unpin>(
    writer: &mut W,
    payload: &[u8],
) -> io::Result<()> {
    let len = payload.len();
    let header_capacity = 14 + len;
    let mut frame = Vec::with_capacity(header_capacity);
    frame.push(0x82); // FIN + Binary opcode (2)

    if len <= 125 {
        frame.push(0x80 | len as u8);
    } else if len <= 65535 {
        frame.push(0x80 | 126);
        frame.extend_from_slice(&(len as u16).to_be_bytes());
    } else {
        frame.push(0x80 | 127);
        frame.extend_from_slice(&(len as u64).to_be_bytes());
    }

    let mask_key: [u8; 4] = rand::random();
    frame.extend_from_slice(&mask_key);

    let offset = frame.len();
    frame.extend_from_slice(payload);
    for (i, b) in frame[offset..].iter_mut().enumerate() {
        *b ^= mask_key[i % 4];
    }

    writer.write_all(&frame).await?;
    writer.flush().await
}

/// Sends a properly masked Pong frame echoing the Ping payload (RFC 6455 §5.5.3).
async fn write_ws_pong_frame<W: AsyncWrite + Unpin>(
    writer: &mut W,
    ping_payload: &[u8],
) -> io::Result<()> {
    let payload = if ping_payload.len() > 125 {
        &ping_payload[..125]
    } else {
        ping_payload
    };
    let mask_key: [u8; 4] = rand::random();
    let mut frame = Vec::with_capacity(6 + payload.len());
    frame.push(0x8A); // FIN + Pong opcode (0xA)
    frame.push(0x80 | payload.len() as u8); // MASK bit + length (≤ 125)
    frame.extend_from_slice(&mask_key);

    let offset = frame.len();
    frame.extend_from_slice(payload);
    for (i, b) in frame[offset..].iter_mut().enumerate() {
        *b ^= mask_key[i % 4];
    }

    writer.write_all(&frame).await?;
    writer.flush().await
}

/// Sends a masked Ping frame (RFC 6455 §5.5.2) to keep Cloudflare Edge / Telegram /apiws alive.
async fn write_ws_ping_frame<W: AsyncWrite + Unpin>(
    writer: &mut W,
    payload: &[u8],
) -> io::Result<()> {
    let payload = if payload.len() > 125 {
        &payload[..125]
    } else {
        payload
    };
    let mask_key: [u8; 4] = rand::random();
    let mut frame = Vec::with_capacity(6 + payload.len());
    frame.push(0x89); // FIN + Ping opcode (0x9)
    frame.push(0x80 | payload.len() as u8); // MASK bit + length (≤ 125)
    frame.extend_from_slice(&mask_key);

    let offset = frame.len();
    frame.extend_from_slice(payload);
    for (i, b) in frame[offset..].iter_mut().enumerate() {
        *b ^= mask_key[i % 4];
    }

    writer.write_all(&frame).await?;
    writer.flush().await
}

// ── WsStream (unsplit) ────────────────────────────────────────────────────────

pub struct WsStream<S> {
    stream: S,
    early_data: Vec<u8>,
    early_offset: usize,
}

impl<S: AsyncRead + AsyncWrite + Unpin> WsStream<S> {
    pub fn new(stream: S) -> Self {
        Self {
            stream,
            early_data: Vec::new(),
            early_offset: 0,
        }
    }

    pub fn new_with_early_data(stream: S, early_data: Vec<u8>) -> Self {
        Self {
            stream,
            early_data,
            early_offset: 0,
        }
    }

    pub async fn write_binary_frame(&mut self, payload: &[u8]) -> io::Result<()> {
        write_ws_binary_frame(&mut self.stream, payload).await
    }

    pub async fn read_binary_frame(&mut self) -> io::Result<Vec<u8>> {
        loop {
            let frame = read_one_ws_frame_buffered(
                &mut self.stream,
                &mut self.early_data,
                &mut self.early_offset,
            )
            .await?;
            match frame.opcode {
                0x00 | 0x02 => return Ok(frame.data),
                0x01 => {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        format!(
                            "Unexpected WebSocket text frame: {}",
                            String::from_utf8_lossy(&frame.data)
                                .chars()
                                .take(64)
                                .collect::<String>()
                        ),
                    ));
                }
                0x08 => {
                    return Err(io::Error::new(
                        io::ErrorKind::ConnectionAborted,
                        "WebSocket closed by peer",
                    ));
                }
                0x09 => {
                    let _ = write_ws_pong_frame(&mut self.stream, &frame.data).await;
                }
                _ => {}
            }
        }
    }

    pub fn into_split(
        self,
    ) -> (
        WsReader<tokio::io::ReadHalf<S>>,
        WsWriter<tokio::io::WriteHalf<S>>,
    ) {
        let (pong_tx, pong_rx) = mpsc::channel::<Vec<u8>>(32);
        let (r, w) = tokio::io::split(self.stream);
        (
            WsReader {
                reader: r,
                early_data: self.early_data,
                early_offset: self.early_offset,
                pong_tx,
            },
            WsWriter {
                writer: w,
                pong_rx,
            },
        )
    }

    pub fn into_inner(self) -> S {
        self.stream
    }
}

// ── WsReader (split half) ─────────────────────────────────────────────────────

pub struct WsReader<R> {
    reader: R,
    early_data: Vec<u8>,
    early_offset: usize,
    pong_tx: mpsc::Sender<Vec<u8>>,
}

impl<R: AsyncRead + Unpin> WsReader<R> {
    pub async fn read_binary_frame(&mut self) -> io::Result<Vec<u8>> {
        loop {
            let frame = read_one_ws_frame_buffered(
                &mut self.reader,
                &mut self.early_data,
                &mut self.early_offset,
            )
            .await?;
            match frame.opcode {
                0x00 | 0x02 => return Ok(frame.data),
                0x01 => {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        format!(
                            "Unexpected WebSocket text frame: {}",
                            String::from_utf8_lossy(&frame.data)
                                .chars()
                                .take(64)
                                .collect::<String>()
                        ),
                    ));
                }
                0x08 => {
                    return Err(io::Error::new(
                        io::ErrorKind::ConnectionAborted,
                        "WebSocket closed by peer",
                    ));
                }
                0x09 => {
                    let _ = self.pong_tx.send(frame.data).await;
                }
                _ => {}
            }
        }
    }
}

// ── WsWriter (split half) ─────────────────────────────────────────────────────

pub struct WsWriter<W> {
    writer: W,
    pong_rx: mpsc::Receiver<Vec<u8>>,
}

impl<W: AsyncWrite + Unpin> WsWriter<W> {
    pub async fn recv_pong(&mut self) -> Option<Vec<u8>> {
        self.pong_rx.recv().await
    }

    pub async fn send_pong(&mut self, ping_payload: &[u8]) -> io::Result<()> {
        write_ws_pong_frame(&mut self.writer, ping_payload).await
    }

    pub async fn send_ping(&mut self, payload: &[u8]) -> io::Result<()> {
        write_ws_ping_frame(&mut self.writer, payload).await
    }

    pub async fn write_binary_frame(&mut self, payload: &[u8]) -> io::Result<()> {
        while let Ok(pong_data) = self.pong_rx.try_recv() {
            write_ws_pong_frame(&mut self.writer, &pong_data).await?;
        }
        write_ws_binary_frame(&mut self.writer, payload).await
    }
}

// ── TLS connection helpers ────────────────────────────────────────────────────

pub async fn connect_raw_tls(
    host: &str,
    target_addr: SocketAddr,
    tls_config: Arc<ClientConfig>,
) -> io::Result<TlsStream<TcpStream>> {
    let tcp = tokio::time::timeout(Duration::from_millis(2500), TcpStream::connect(target_addr))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "TCP connect timed out"))??;
    let _ = tcp.set_nodelay(true);

    let server_name = ServerName::try_from(host.to_string())
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e.to_string()))?;

    let connector = TlsConnector::from(tls_config);
    tokio::time::timeout(Duration::from_millis(2500), connector.connect(server_name, tcp))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "TLS handshake timed out"))?
}

pub async fn upgrade_tls_to_ws(
    mut tls: TlsStream<TcpStream>,
    host_header: &str,
    path: &str,
) -> io::Result<WsStream<TlsStream<TcpStream>>> {
    tokio::time::timeout(Duration::from_millis(2500), async {
        let mut key_bytes = [0u8; 16];
        rand::thread_rng().fill(&mut key_bytes);
        let sec_ws_key = BASE64.encode(key_bytes);

        let request = format!(
            "GET {} HTTP/1.1\r\nHost: {}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {}\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: binary\r\nUser-Agent: Mozilla/5.0 tg-ws-proxy-android\r\n\r\n",
            path, host_header, sec_ws_key
        );

        tls.write_all(request.as_bytes()).await?;
        tls.flush().await?;

        let mut response_buf = Vec::with_capacity(1024);
        let mut chunk = [0u8; 512];
        loop {
            let n = tls.read(&mut chunk).await?;
            if n == 0 {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "Connection closed during WebSocket upgrade",
                ));
            }
            response_buf.extend_from_slice(&chunk[..n]);
            if response_buf.len() > 8192 {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "WebSocket upgrade response too large",
                ));
            }
            if response_buf.windows(4).any(|w| w == b"\r\n\r\n") {
                break;
            }
        }

        let header_end = match response_buf.windows(4).position(|w| w == b"\r\n\r\n") {
            Some(pos) => pos,
            None => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "Incomplete WebSocket upgrade response header",
                ));
            }
        };

        let resp_str = String::from_utf8_lossy(&response_buf[..header_end]);
        if !resp_str.starts_with("HTTP/1.1 101") {
            return Err(io::Error::new(
                io::ErrorKind::ConnectionRefused,
                format!(
                    "Invalid WebSocket upgrade response: {}",
                    resp_str.chars().take(256).collect::<String>()
                ),
            ));
        }

        let early_data = response_buf[header_end + 4..].to_vec();
        Ok(WsStream::new_with_early_data(tls, early_data))
    })
    .await
    .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "WebSocket upgrade timed out"))?
}

pub async fn connect_tls_ws(
    sni_host: &str,
    host_header: &str,
    target_addr: SocketAddr,
    path: &str,
    tls_config: Arc<ClientConfig>,
) -> io::Result<WsStream<TlsStream<TcpStream>>> {
    let tls = connect_raw_tls(sni_host, target_addr, tls_config).await?;
    upgrade_tls_to_ws(tls, host_header, path).await
}

/// Concurrently races candidate domains using Happy Eyeballs (150ms stagger)
/// for ultra-fast, zero-delay connection to Telegram WebSockets.
pub async fn race_connect_candidate_domains(
    domains: Vec<String>,
    ws_path: &str,
    cf_manager: Arc<crate::cfproxy::CfManager>,
    tls_config: Arc<ClientConfig>,
) -> io::Result<(String, WsStream<TlsStream<TcpStream>>)> {
    if domains.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::NotFound,
            "No candidate domains available",
        ));
    }

    let (tx, mut rx) = tokio::sync::mpsc::channel(domains.len());
    let mut handles = Vec::new();
    let stagger = Duration::from_millis(150);
    let ws_path = ws_path.to_string();

    let runner = async {
        for domain in domains {
            let tx = tx.clone();
            let cf = cf_manager.clone();
            let tls = tls_config.clone();
            let d = domain.clone();
            let p = ws_path.clone();

            let handle = tokio::spawn(async move {
                let target_path = if cf.is_worker_or_user_domain(&d) {
                    p.clone()
                } else {
                    "/apiws".to_string()
                };
                if let Some(ip) = cf.resolve_target(&d).await {
                    match connect_tls_ws(&d, &d, ip, &target_path, tls).await {
                        Ok(ws) => {
                            let _ = tx.send((d, ws)).await;
                        }
                        Err(e) => {
                            crate::logging::log_info(
                                "mirrlyengine",
                                &format!("WS connect to {} ({}) path {} failed: {:?}", d, ip, target_path, e),
                            );
                        }
                    }
                }
            });
            handles.push(handle);
            tokio::time::sleep(stagger).await;
        }
    };

    tokio::select! {
        _ = runner => {},
        Some((winner_domain, winner_ws)) = rx.recv() => {
            for h in handles {
                h.abort();
            }
            return Ok((winner_domain, winner_ws));
        }
    }

    tokio::select! {
        Some((winner_domain, winner_ws)) = rx.recv() => {
            for h in handles {
                h.abort();
            }
            Ok((winner_domain, winner_ws))
        }
        _ = tokio::time::sleep(Duration::from_millis(3000)) => {
            for h in handles {
                h.abort();
            }
            Err(io::Error::new(
                io::ErrorKind::TimedOut,
                "All candidate domain connections failed or timed out",
            ))
        }
    }
}

// ── Pre-warmed WebSocket Pool (Fully established WsStream) ────────────────────

pub struct PooledWs {
    pub ws: WsStream<TlsStream<TcpStream>>,
    pub created_at: Instant,
}

#[derive(Hash, Eq, PartialEq, Clone, Copy, Debug)]
pub struct PoolKey {
    pub dc_id: i16,
    pub is_media: bool,
}

pub struct WsPool {
    pool: TokioMutex<HashMap<PoolKey, VecDeque<PooledWs>>>,
    refilling: SyncMutex<HashMap<PoolKey, bool>>,
    tls_config: Arc<ClientConfig>,
    doh: Arc<DohResolver>,
    is_active: AtomicBool,
}

impl WsPool {
    pub fn new(tls_config: Arc<ClientConfig>, doh: Arc<DohResolver>) -> Arc<Self> {
        Arc::new(Self {
            pool: TokioMutex::new(HashMap::new()),
            refilling: SyncMutex::new(HashMap::new()),
            tls_config,
            doh,
            is_active: AtomicBool::new(true),
        })
    }

    /// Retrieves an already established, active WebSocket connection for this DC.
    /// Zero latency (< 1ms). Drops expired or dead sockets.
    pub async fn get(&self, dc_id: i16, is_media: bool) -> Option<WsStream<TlsStream<TcpStream>>> {
        let key = PoolKey {
            dc_id: if dc_id.abs() == 203 { 2 } else { dc_id.abs() },
            is_media,
        };

        let mut p = self.pool.lock().await;
        let queue = p.get_mut(&key)?;

        while let Some(item) = queue.pop_front() {
            if item.created_at.elapsed() <= MAX_POOL_AGE {
                return Some(item.ws);
            }
        }

        None
    }

    /// Inserts a newly upgraded WebSocket connection into the pool.
    pub async fn put(&self, dc_id: i16, is_media: bool, ws: WsStream<TlsStream<TcpStream>>, max_size: usize) {
        let key = PoolKey {
            dc_id: if dc_id.abs() == 203 { 2 } else { dc_id.abs() },
            is_media,
        };

        let mut p = self.pool.lock().await;
        let queue = p.entry(key).or_insert_with(VecDeque::new);

        // Evict expired entries
        queue.retain(|item| item.created_at.elapsed() <= MAX_POOL_AGE);

        if queue.len() < max_size {
            queue.push_back(PooledWs {
                ws,
                created_at: Instant::now(),
            });
        }
    }

    /// Establishes and pre-warms a WebSocket connection to the native Telegram /apiws endpoint
    /// or custom Cloudflare Worker endpoint.
    pub async fn prewarm_target(
        &self,
        dc_id: i16,
        is_media: bool,
        domain: &str,
        path: &str,
        max_size: usize,
    ) -> bool {
        let target_path = if domain.contains("workers.dev") {
            path
        } else {
            "/apiws"
        };
        let target_addr = match self.doh.resolve_socket_addr(domain, 443).await {
            Some(addr) => addr,
            None => return false,
        };

        let host_header = domain;
        let ws_res = connect_tls_ws(
            domain,
            host_header,
            target_addr,
            target_path,
            self.tls_config.clone(),
        )
        .await;

        match ws_res {
            Ok(ws) => {
                self.put(dc_id, is_media, ws, max_size).await;
                true
            }
            Err(_) => false,
        }
    }

    /// Returns the number of active, ready sockets across all DCs in the pool.
    pub async fn available_sockets(&self) -> usize {
        let mut p = self.pool.lock().await;
        let mut count = 0;
        for queue in p.values_mut() {
            queue.retain(|item| item.created_at.elapsed() <= MAX_POOL_AGE);
            count += queue.len();
        }
        count
    }

    pub async fn clear(&self) {
        let mut p = self.pool.lock().await;
        p.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_ws_split_ping_pong_immediate_reply() {
        let (server_sock, client_sock) = tokio::io::duplex(4096);
        let mut server = server_sock;

        let ws = WsStream::new(client_sock);
        let (mut reader, mut writer) = ws.into_split();

        let ping_payload = b"cloudflare-keepalive";

        let mut ping_frame = vec![0x89, ping_payload.len() as u8];
        ping_frame.extend_from_slice(ping_payload);

        let srv_task = tokio::spawn(async move {
            server.write_all(&ping_frame).await.unwrap();
            server.flush().await.unwrap();

            let mut head = [0u8; 2];
            server.read_exact(&mut head).await.unwrap();
            assert_eq!(head[0], 0x8A);
            let is_masked = (head[1] & 0x80) != 0;
            assert!(is_masked);
            let len = (head[1] & 0x7F) as usize;
            assert_eq!(len, ping_payload.len());

            let mut mask = [0u8; 4];
            server.read_exact(&mut mask).await.unwrap();
            let mut data = vec![0u8; len];
            server.read_exact(&mut data).await.unwrap();
            for (i, b) in data.iter_mut().enumerate() {
                *b ^= mask[i % 4];
            }
            assert_eq!(&data, ping_payload);
        });

        let r_task = tokio::spawn(async move {
            let _ = reader.read_binary_frame().await;
        });

        let pong = tokio::time::timeout(Duration::from_millis(500), writer.recv_pong())
            .await
            .expect("pong received in time")
            .expect("pong data present");

        assert_eq!(&pong, ping_payload);
        writer.send_pong(&pong).await.unwrap();

        srv_task.await.unwrap();
        r_task.abort();
    }

    #[tokio::test]
    async fn test_ws_early_data_buffering() {
        let (_server_sock, client_sock) = tokio::io::duplex(4096);
        let early_frame = vec![0x82, 0x05, b'h', b'e', b'l', b'l', b'o'];
        let ws = WsStream::new_with_early_data(client_sock, early_frame);
        let (mut reader, _writer) = ws.into_split();

        let received = reader.read_binary_frame().await.unwrap();
        assert_eq!(&received, b"hello");
    }
}
