use crate::cfproxy::CfManager;
use crate::config::ConfigManager;
use crate::dc::find_dc_by_target;
use crate::stats::EngineStats;
use crate::ws::{connect_tls_ws, WsPool, WsStream};
use rustls::ClientConfig;
use std::io;
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::watch;
use tokio_rustls::client::TlsStream;

#[allow(dead_code)]
pub struct Socks5Server {
    config: Arc<ConfigManager>,
    stats: Arc<EngineStats>,
    cf_manager: Arc<CfManager>,
    ws_pool: Arc<WsPool>,
    tls_config: Arc<ClientConfig>,
}

impl Socks5Server {
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
                        Err(_) => {
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

    async fn handle_client(self: Arc<Self>, mut client: TcpStream, _peer: SocketAddr) {
        let _ = client.set_nodelay(true);
        self.stats.inc_conns();

        if let Err(e) = self.process_socks5(&mut client).await {
            if self.config.get().verbose {
                eprintln!("[mirrlyengine-socks5] Client error: {:?}", e);
            }
        }

        self.stats.dec_conns();
    }

    async fn process_socks5(&self, client: &mut TcpStream) -> io::Result<()> {
        // 1. Negotiation (Version Identifier/Method Selection)
        let mut ver_methods = [0u8; 2];
        client.read_exact(&mut ver_methods).await?;
        self.stats.add_rx(2);

        if ver_methods[0] != 0x05 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "Unsupported SOCKS version",
            ));
        }

        let nmethods = ver_methods[1] as usize;
        let mut methods = vec![0u8; nmethods];
        client.read_exact(&mut methods).await?;
        self.stats.add_rx(nmethods as u64);

        if !methods.contains(&0x00) {
            client.write_all(&[0x05, 0xFF]).await?;
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "No acceptable SOCKS5 auth methods",
            ));
        }

        client.write_all(&[0x05, 0x00]).await?;
        client.flush().await?;
        self.stats.add_tx(2);

        // 2. Request (CMD, RSV, ATYP, DST.ADDR, DST.PORT)
        let mut head = [0u8; 4];
        client.read_exact(&mut head).await?;
        self.stats.add_rx(4);

        if head[0] != 0x05 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "Invalid SOCKS5 request version",
            ));
        }

        let cmd = head[1];
        if cmd != 0x01 {
            client.write_all(&[0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Unsupported SOCKS5 command (only CONNECT is supported)",
            ));
        }

        let atyp = head[3];
        let target_host = match atyp {
            0x01 => {
                let mut ip_bytes = [0u8; 4];
                client.read_exact(&mut ip_bytes).await?;
                self.stats.add_rx(4);
                let ip = Ipv4Addr::from(ip_bytes);
                ip.to_string()
            }
            0x03 => {
                let mut len = [0u8; 1];
                client.read_exact(&mut len).await?;
                self.stats.add_rx(1);
                let domain_len = len[0] as usize;
                let mut domain_buf = vec![0u8; domain_len];
                client.read_exact(&mut domain_buf).await?;
                self.stats.add_rx(domain_len as u64);
                String::from_utf8(domain_buf).map_err(|e| {
                    io::Error::new(io::ErrorKind::InvalidData, format!("Invalid domain UTF-8: {}", e))
                })?
            }
            0x04 => {
                let mut ip_bytes = [0u8; 16];
                client.read_exact(&mut ip_bytes).await?;
                self.stats.add_rx(16);
                let ip = Ipv6Addr::from(ip_bytes);
                ip.to_string()
            }
            _ => {
                client.write_all(&[0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "Unsupported SOCKS5 address type",
                ));
            }
        };

        let mut port_bytes = [0u8; 2];
        client.read_exact(&mut port_bytes).await?;
        self.stats.add_rx(2);
        let target_port = u16::from_be_bytes(port_bytes);

        let target_spec = if target_host.contains(':') && !target_host.starts_with('[') {
            format!("[{}]:{}", target_host, target_port)
        } else {
            format!("{}:{}", target_host, target_port)
        };

        let cfg = self.config.get();
        let use_cf = cfg.cf_enabled;
        let cf_priority = cfg.cf_priority;
        let mut connected_via_cf = false;

        if use_cf {
            // Check if this target is a known Telegram DC and we have a pooled socket
            if let Some((dc_id, is_media)) = find_dc_by_target(&target_host) {
                if let Some(ws_stream) = self.ws_pool.get(dc_id, is_media).await {
                    let reply = [0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
                    client.write_all(&reply).await?;
                    client.flush().await?;
                    self.stats.add_tx(reply.len() as u64);

                    return self.relay_ws_client(client, ws_stream).await;
                }
            }

            let user_domain = cfg.cf_user_domain.clone();
            let mut domains_to_try = Vec::new();
            if !user_domain.is_empty() {
                domains_to_try.push(user_domain);
            } else {
                domains_to_try.push("mirrly-tg-proxy-worker.brawny-singer.workers.dev".to_string());
            }

            for domain in domains_to_try {
                if let Some(cf_ip) = self.cf_manager.resolve_target(&domain).await {
                    let worker_path = format!(
                        "/tcp?target={}&host={}&port={}",
                        target_spec, target_host, target_port
                    );
                    if let Ok(ws_stream) = connect_tls_ws(
                        &domain,
                        &domain,
                        cf_ip,
                        &worker_path,
                        self.tls_config.clone(),
                    )
                    .await
                    {
                        let reply = [0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
                        client.write_all(&reply).await?;
                        client.flush().await?;
                        self.stats.add_tx(reply.len() as u64);

                        connected_via_cf = true;
                        self.relay_ws_client(client, ws_stream).await?;
                        break;
                    }
                }
            }

            if !connected_via_cf && cf_priority {
                let reply = [0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
                let _ = client.write_all(&reply).await;
                return Err(io::Error::new(
                    io::ErrorKind::ConnectionRefused,
                    "Cloudflare Worker SOCKS5 tunnel unavailable and direct connection forbidden",
                ));
            }
        }

        if !connected_via_cf {
            match TcpStream::connect(&target_spec).await {
                Ok(mut upstream) => {
                    let _ = upstream.set_nodelay(true);
                    let reply = [0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
                    client.write_all(&reply).await?;
                    client.flush().await?;
                    self.stats.add_tx(reply.len() as u64);

                    self.relay_tcp_bidirectional(client, &mut upstream).await?;
                }
                Err(e) => {
                    let reply = [0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
                    let _ = client.write_all(&reply).await;
                    return Err(e);
                }
            }
        }

        Ok(())
    }

    async fn relay_tcp_bidirectional(
        &self,
        client: &mut TcpStream,
        upstream: &mut TcpStream,
    ) -> io::Result<()> {
        let (mut cr, mut cw) = client.split();
        let (mut ur, mut uw) = upstream.split();

        let stats_rx = self.stats.clone();
        let stats_tx = self.stats.clone();

        let client_to_up = async {
            let mut buf = [0u8; 16384];
            loop {
                let n = cr.read(&mut buf).await?;
                if n == 0 {
                    break;
                }
                stats_tx.add_rx(n as u64);
                uw.write_all(&buf[..n]).await?;
                uw.flush().await?;
                stats_tx.add_tx(n as u64);
            }
            uw.shutdown().await
        };

        let up_to_client = async {
            let mut buf = [0u8; 16384];
            loop {
                let n = ur.read(&mut buf).await?;
                if n == 0 {
                    break;
                }
                stats_rx.add_rx(n as u64);
                cw.write_all(&buf[..n]).await?;
                cw.flush().await?;
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
    ) -> io::Result<()> {
        let (mut cr, mut cw) = client.split();
        let (mut ws_reader, mut ws_writer) = ws.into_split();

        let stats_c2w = self.stats.clone();
        let stats_w2c = self.stats.clone();

        let c2w = async {
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
                        if ws_writer.write_binary_frame(&buf[..n]).await.is_err() {
                            break;
                        }
                        stats_c2w.add_tx(n as u64);
                    }
                }
            }
            io::Result::Ok(())
        };

        let w2c = async {
            loop {
                let frame = match ws_reader.read_binary_frame().await {
                    Ok(data) => data,
                    Err(_) => break,
                };
                if frame.is_empty() {
                    break;
                }
                stats_w2c.add_rx(frame.len() as u64);
                if cw.write_all(&frame).await.is_err() || cw.flush().await.is_err() {
                    break;
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
