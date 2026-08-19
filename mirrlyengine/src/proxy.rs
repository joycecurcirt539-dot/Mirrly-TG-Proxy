use crate::cfproxy::CfManager;
use crate::config::ConfigManager;
use crate::crypto::{
    generate_relay_init, parse_handshake_header, Aes256Ctr128BE, MsgSplitter, MtprotoProtocol,
};
use crate::dc::{get_ws_path, resolve_dc_addr};
use crate::faketls::{
    handle_fake_tls_handshake, is_tls_handshake, read_tls_app_data, write_tls_app_data,
};
use crate::logging::{log_error, log_info, log_warn};
use crate::stats::EngineStats;
use crate::ws::{connect_tls_ws, WsPool, WsStream};
use aes::cipher::StreamCipher;
use rustls::ClientConfig;
use std::io;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::watch;
use tokio_rustls::client::TlsStream;

#[allow(dead_code)]
pub struct ProxyServer {
    config: Arc<ConfigManager>,
    stats: Arc<EngineStats>,
    cf_manager: Arc<CfManager>,
    ws_pool: Arc<WsPool>,
    tls_config: Arc<ClientConfig>,
}

pub fn create_listener(bind_addr: SocketAddr) -> io::Result<TcpListener> {
    use socket2::{Domain, Protocol, Socket, Type};
    let domain = if bind_addr.is_ipv6() {
        Domain::IPV6
    } else {
        Domain::IPV4
    };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    let _ = socket.set_reuse_address(true);
    #[cfg(all(unix, not(target_os = "solaris"), not(target_os = "illumos")))]
    {
        let _ = socket.set_reuse_port(true);
    }
    socket.set_nonblocking(true)?;
    socket.bind(&bind_addr.into())?;
    socket.listen(1024)?;
    let std_listener: std::net::TcpListener = socket.into();
    TcpListener::from_std(std_listener)
}

impl ProxyServer {
    pub fn new(
        config: Arc<ConfigManager>,
        stats: Arc<EngineStats>,
        cf_manager: Arc<CfManager>,
        ws_pool: Arc<WsPool>,
        tls_config: Arc<ClientConfig>,
    ) -> Self {
        Self {
            config,
            stats,
            cf_manager,
            ws_pool,
            tls_config,
        }
    }

    pub async fn run(
        self: Arc<Self>,
        listener: TcpListener,
        mut shutdown_rx: watch::Receiver<bool>,
    ) -> io::Result<()> {
        loop {
            tokio::select! {
                res = listener.accept() => {
                    match res {
                        Ok((client_sock, peer_addr)) => {
                            let server = self.clone();
                            tokio::spawn(async move {
                                server.handle_client(client_sock, peer_addr).await;
                            });
                        }
                        Err(_e) => {
                            tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;
                        }
                    }
                }
                _ = shutdown_rx.changed() => {
                    if *shutdown_rx.borrow() {
                        break;
                    }
                }
            }
        }

        Ok(())
    }

    async fn handle_client(self: Arc<Self>, mut client_stream: TcpStream, peer: SocketAddr) {
        let _ = client_stream.set_nodelay(true);
        self.stats.inc_conns();

        log_info(
            "mirrlyengine",
            &format!("Incoming MTProto client connected from {}", peer),
        );

        let res = self.process_connection(&mut client_stream).await;
        if let Err(e) = res {
            log_error(
                "mirrlyengine",
                &format!("MTProto client error for {}: {:?}", peer, e),
            );
        } else {
            log_info(
                "mirrlyengine",
                &format!("MTProto client connection closed normally for {}", peer),
            );
        }

        self.stats.dec_conns();
    }

    async fn process_connection(&self, client_stream: &mut TcpStream) -> io::Result<()> {
        // Read first 5 bytes to inspect protocol (Fake-TLS Handshake vs raw MTProto)
        let mut prefix = [0u8; 5];
        client_stream.read_exact(&mut prefix).await?;
        self.stats.add_rx(5);

        let is_fake_tls = is_tls_handshake(&prefix);
        let mut header = [0u8; 64];
        let mut initial_extra_payload = Vec::new();

        if is_fake_tls {
            log_info("mirrlyengine", "Fake-TLS Handshake detected from client");
            handle_fake_tls_handshake(client_stream, &prefix).await?;

            let app_data = read_tls_app_data(client_stream).await?;
            if app_data.len() < 64 {
                log_error("mirrlyengine", "Fake-TLS payload < 64 bytes");
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "Fake-TLS: payload less than 64 bytes MTProto header",
                ));
            }
            self.stats.add_rx(app_data.len() as u64);
            header.copy_from_slice(&app_data[..64]);
            if app_data.len() > 64 {
                initial_extra_payload = app_data[64..].to_vec();
            }
        } else {
            header[..5].copy_from_slice(&prefix);
            client_stream.read_exact(&mut header[5..]).await?;
            self.stats.add_rx(59);
        }

        let cfg = self.config.get();
        let handshake = parse_handshake_header(&header, &cfg.secret);
        if handshake.is_none() {
            log_warn(
                "mirrlyengine",
                &format!(
                    "Handshake parse returned None with configured secret len={}. Using direct fallback.",
                    cfg.secret.len()
                ),
            );
        }

        let dc_id = handshake.as_ref().map(|h| h.dc_id).unwrap_or(2);
        let is_media = dc_id < 0;
        let abs_dc = if dc_id.abs() == 203 { 2 } else { dc_id.abs() };
        let protocol = handshake
            .as_ref()
            .map(|h| h.protocol.clone())
            .unwrap_or(MtprotoProtocol::PaddedIntermediate);
        let target_dc_addr = resolve_dc_addr(abs_dc, &cfg.dc_ips);

        log_info(
            "mirrlyengine",
            &format!(
                "Handshake parsed: protocol={:?}, dc_id={}, is_media={}, target_dc={}, is_fake_tls={}",
                protocol, abs_dc, is_media, target_dc_addr, is_fake_tls
            ),
        );

        let (relay_init, upstream_crypto) = generate_relay_init(&protocol, dc_id);

        let (client_dec, client_enc) = if let Some(h) = handshake {
            if let Some(c) = h.client_crypto {
                (Some(c.dec), Some(c.enc))
            } else {
                (None, None)
            }
        } else {
            (None, None)
        };

        let upstream_enc = Some(upstream_crypto.enc);
        let upstream_dec = Some(upstream_crypto.dec);

        let use_cf = cfg.cf_enabled;
        let cf_priority = cfg.cf_priority;

        if use_cf {
            // ── 1. Проверяем готовый WebSocket из WsPool (Zero-Latency Hit: ~0ms) ────────
            if let Some(mut pooled_ws) = self.ws_pool.get(abs_dc, is_media).await {
                log_info(
                    "mirrlyengine",
                    &format!(
                        "Instant WsPool Hit for DC{} (is_media={})! Sending relay_init...",
                        abs_dc, is_media
                    ),
                );

                if pooled_ws.write_binary_frame(&relay_init).await.is_ok() {
                    self.stats.add_tx(64);
                    return self
                        .relay_ws_client(
                            client_stream,
                            pooled_ws,
                            protocol,
                            client_dec,
                            client_enc,
                            upstream_enc,
                            upstream_dec,
                            is_fake_tls,
                            initial_extra_payload,
                        )
                        .await;
                }
            }

            // ── 2. Быстрая параллельная гонка (Happy Eyeballs, 150ms) по доверенным CDN/Telegram доменам ───
            let candidate_domains = self.cf_manager.get_candidate_domains(abs_dc, is_media);
            let ws_path = format!("/tcp?target={}", target_dc_addr);

            if let Ok((winner_domain, mut ws_stream)) = crate::ws::race_connect_candidate_domains(
                candidate_domains,
                &ws_path,
                self.cf_manager.clone(),
                self.tls_config.clone(),
            )
            .await
            {
                self.cf_manager.promote_domain(&winner_domain);
                log_info(
                    "mirrlyengine",
                    &format!(
                        "Connected 1-hop Native WS to {}! Sending relay_init...",
                        winner_domain
                    ),
                );

                if ws_stream.write_binary_frame(&relay_init).await.is_ok() {
                    self.stats.add_tx(64);
                    return self
                        .relay_ws_client(
                            client_stream,
                            ws_stream,
                            protocol,
                            client_dec,
                            client_enc,
                            upstream_enc,
                            upstream_dec,
                            is_fake_tls,
                            initial_extra_payload,
                        )
                        .await;
                }
            }
        }

        // ── 3. Прямой TCP-фолбек на IP дата-центра Telegram ──────────────────────────
        let mut dc_stream = TcpStream::connect(target_dc_addr).await?;
        let _ = dc_stream.set_nodelay(true);

        dc_stream.write_all(&relay_init).await?;
        dc_stream.flush().await?;
        self.stats.add_tx(64);

        self.relay_tcp_bidirectional(
            client_stream,
            &mut dc_stream,
            client_dec,
            client_enc,
            upstream_enc,
            upstream_dec,
            is_fake_tls,
            initial_extra_payload,
        )
        .await
    }

    async fn relay_tcp_bidirectional(
        &self,
        client: &mut TcpStream,
        upstream: &mut TcpStream,
        mut client_dec: Option<Aes256Ctr128BE>,
        mut client_enc: Option<Aes256Ctr128BE>,
        mut upstream_enc: Option<Aes256Ctr128BE>,
        mut upstream_dec: Option<Aes256Ctr128BE>,
        is_fake_tls: bool,
        initial_extra: Vec<u8>,
    ) -> io::Result<()> {
        let (mut cr, mut cw) = client.split();
        let (mut ur, mut uw) = upstream.split();

        let stats_rx = self.stats.clone();
        let stats_tx = self.stats.clone();

        let client_to_up = async {
            if !initial_extra.is_empty() {
                let mut extra = initial_extra;
                if let (Some(c_dec), Some(u_enc)) = (&mut client_dec, &mut upstream_enc) {
                    c_dec.apply_keystream(&mut extra);
                    u_enc.apply_keystream(&mut extra);
                }
                if uw.write_all(&extra).await.is_err() || uw.flush().await.is_err() {
                    return io::Result::Ok(());
                }
                stats_tx.add_tx(extra.len() as u64);
            }

            if is_fake_tls {
                loop {
                    let mut frame = match read_tls_app_data(&mut cr).await {
                        Ok(f) => f,
                        Err(_) => break,
                    };
                    if frame.is_empty() {
                        break;
                    }
                    stats_tx.add_rx(frame.len() as u64);
                    if let (Some(c_dec), Some(u_enc)) = (&mut client_dec, &mut upstream_enc) {
                        c_dec.apply_keystream(&mut frame);
                        u_enc.apply_keystream(&mut frame);
                    }
                    if uw.write_all(&frame).await.is_err() || uw.flush().await.is_err() {
                        break;
                    }
                    stats_tx.add_tx(frame.len() as u64);
                }
            } else {
                let mut buf = [0u8; 16384];
                loop {
                    let n = match cr.read(&mut buf).await {
                        Ok(n) if n > 0 => n,
                        _ => break,
                    };
                    stats_tx.add_rx(n as u64);
                    if let (Some(c_dec), Some(u_enc)) = (&mut client_dec, &mut upstream_enc) {
                        c_dec.apply_keystream(&mut buf[..n]);
                        u_enc.apply_keystream(&mut buf[..n]);
                    }
                    if uw.write_all(&buf[..n]).await.is_err() || uw.flush().await.is_err() {
                        break;
                    }
                    stats_tx.add_tx(n as u64);
                }
            }
            uw.shutdown().await
        };

        let up_to_client = async {
            let mut buf = [0u8; 16384];
            loop {
                let n = match ur.read(&mut buf).await {
                    Ok(n) if n > 0 => n,
                    _ => break,
                };
                stats_rx.add_rx(n as u64);
                if let (Some(u_dec), Some(c_enc)) = (&mut upstream_dec, &mut client_enc) {
                    u_dec.apply_keystream(&mut buf[..n]);
                    c_enc.apply_keystream(&mut buf[..n]);
                }
                if is_fake_tls {
                    if write_tls_app_data(&mut cw, &buf[..n]).await.is_err() {
                        break;
                    }
                } else {
                    if cw.write_all(&buf[..n]).await.is_err() || cw.flush().await.is_err() {
                        break;
                    }
                }
                stats_rx.add_tx(n as u64);
            }
            cw.shutdown().await
        };

        tokio::select! {
            r1 = client_to_up => r1,
            r2 = up_to_client => r2,
        }
    }

    async fn relay_ws_client(
        &self,
        client: &mut TcpStream,
        ws: WsStream<TlsStream<TcpStream>>,
        protocol: MtprotoProtocol,
        mut client_dec: Option<Aes256Ctr128BE>,
        mut client_enc: Option<Aes256Ctr128BE>,
        mut upstream_enc: Option<Aes256Ctr128BE>,
        mut upstream_dec: Option<Aes256Ctr128BE>,
        is_fake_tls: bool,
        initial_extra: Vec<u8>,
    ) -> io::Result<()> {
        let (mut cr, mut cw) = client.split();
        let (mut ws_reader, mut ws_writer) = ws.into_split();

        let stats_c2w = self.stats.clone();
        let stats_w2c = self.stats.clone();

        let mut splitter = MsgSplitter::new(protocol);

        // WebSocket Ping keep-alive interval (30s).
        // Cloudflare Edge kills idle WS after ~100s; Telegram MTProto pings are ~300s apart.
        // Without this, CF drops the connection during quiet periods between messages.
        let ping_interval = tokio::time::Duration::from_secs(30);

        let c2w = async {
            if !initial_extra.is_empty() {
                let frames = splitter.process_chunk(initial_extra, &mut client_dec, &mut upstream_enc);
                for pkt in frames {
                    stats_c2w.add_tx(pkt.len() as u64);
                    if ws_writer.write_binary_frame(&pkt).await.is_err() {
                        return io::Result::Ok(());
                    }
                }
            }

            let mut ping_timer = tokio::time::interval(ping_interval);
            ping_timer.tick().await; // consume the immediate first tick

            if is_fake_tls {
                loop {
                    tokio::select! {
                        Some(pong_data) = ws_writer.recv_pong() => {
                            if ws_writer.send_pong(&pong_data).await.is_err() {
                                break;
                            }
                        }
                        res = read_tls_app_data(&mut cr) => {
                            let frame = match res {
                                Ok(f) if !f.is_empty() => f,
                                _ => break,
                            };
                            stats_c2w.add_rx(frame.len() as u64);
                            let packets = splitter.process_chunk(frame, &mut client_dec, &mut upstream_enc);
                            for pkt in packets {
                                stats_c2w.add_tx(pkt.len() as u64);
                                if ws_writer.write_binary_frame(&pkt).await.is_err() {
                                    return io::Result::Ok(());
                                }
                            }
                        }
                        _ = ping_timer.tick() => {
                            if ws_writer.send_ping(b"ka").await.is_err() {
                                break;
                            }
                        }
                    }
                }
            } else {
                let mut buf = [0u8; 16384];
                loop {
                    tokio::select! {
                        Some(pong_data) = ws_writer.recv_pong() => {
                            if ws_writer.send_pong(&pong_data).await.is_err() {
                                break;
                            }
                        }
                        res = cr.read(&mut buf) => {
                            let n = match res {
                                Ok(n) if n > 0 => n,
                                _ => break,
                            };
                            stats_c2w.add_rx(n as u64);
                            let chunk = buf[..n].to_vec();
                            let packets = splitter.process_chunk(chunk, &mut client_dec, &mut upstream_enc);
                            for pkt in packets {
                                stats_c2w.add_tx(pkt.len() as u64);
                                if ws_writer.write_binary_frame(&pkt).await.is_err() {
                                    return io::Result::Ok(());
                                }
                            }
                        }
                        _ = ping_timer.tick() => {
                            if ws_writer.send_ping(b"ka").await.is_err() {
                                break;
                            }
                        }
                    }
                }
            }
            io::Result::Ok(())
        };

        let w2c = async {
            loop {
                let mut frame = match ws_reader.read_binary_frame().await {
                    Ok(data) => data,
                    Err(_) => break,
                };
                if frame.is_empty() {
                    break;
                }
                stats_w2c.add_rx(frame.len() as u64);
                if let (Some(u_dec), Some(c_enc)) = (&mut upstream_dec, &mut client_enc) {
                    u_dec.apply_keystream(&mut frame);
                    c_enc.apply_keystream(&mut frame);
                }
                if is_fake_tls {
                    if write_tls_app_data(&mut cw, &frame).await.is_err() {
                        break;
                    }
                } else {
                    if cw.write_all(&frame).await.is_err() || cw.flush().await.is_err() {
                        break;
                    }
                }
                stats_w2c.add_tx(frame.len() as u64);
            }
            io::Result::Ok(())
        };

        tokio::select! {
            r1 = c2w => r1,
            r2 = w2c => r2,
        }
    }
}
