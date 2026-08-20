use crate::cfproxy::*;
use crate::config::*;
use crate::ws::*;
use crate::{ldebug, linfo, lwarn};
use std::net::{Ipv4Addr, Ipv6Addr};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio_util::sync::CancellationToken;

pub async fn run_socks5_server(
    host: String,
    port: u16,
    cancel_root: CancellationToken,
    listener: TcpListener,
) -> std::io::Result<()> {
    linfo!("━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    linfo!("  SOCKS5 Proxy запущен");
    linfo!("  Адрес: {}:{}", host, port);

    loop {
        tokio::select! {
            _ = cancel_root.cancelled() => break,
            accept = listener.accept() => {
                match accept {
                    Ok((conn, _)) => {
                        let cancel = cancel_root.child_token();
                        tokio::spawn(async move {
                            handle_socks5_client(conn, cancel).await;
                        });
                    }
                    Err(_) => continue,
                }
            }
        }
    }

    Ok(())
}

async fn handle_socks5_client(mut client: TcpStream, cancel: CancellationToken) {
    STATS.connections_total.fetch_add(1, Ordering::Relaxed);
    STATS.connections_active.fetch_add(1, Ordering::Relaxed);
    struct ActiveGuard;
    impl Drop for ActiveGuard {
        fn drop(&mut self) {
            if STATS.connections_active.load(Ordering::Relaxed) > 0 {
                STATS.connections_active.fetch_sub(1, Ordering::Relaxed);
            }
        }
    }
    let _guard = ActiveGuard;

    let _ = client.set_nodelay(true);

    // 1. Handshake: Auth methods
    let mut header = [0u8; 2];
    if tokio::time::timeout(Duration::from_secs(5), client.read_exact(&mut header)).await.is_err() {
        return;
    }
    if header[0] != 0x05 {
        return;
    }
    let num_methods = header[1] as usize;
    let mut methods = vec![0u8; num_methods];
    if client.read_exact(&mut methods).await.is_err() {
        return;
    }
    if client.write_all(&[0x05, 0x00]).await.is_err() {
        return;
    }

    // 2. Request details
    let mut req_hdr = [0u8; 4];
    if client.read_exact(&mut req_hdr).await.is_err() {
        return;
    }
    if req_hdr[0] != 0x05 || req_hdr[1] != 0x01 { // CMD 0x01 = CONNECT
        let _ = client.write_all(&[0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await;
        return;
    }

    let target_host = match req_hdr[3] {
        0x01 => { // IPv4
            let mut ip = [0u8; 4];
            if client.read_exact(&mut ip).await.is_err() {
                return;
            }
            Ipv4Addr::from(ip).to_string()
        }
        0x03 => { // Domain
            let mut len = [0u8; 1];
            if client.read_exact(&mut len).await.is_err() {
                return;
            }
            let mut domain = vec![0u8; len[0] as usize];
            if client.read_exact(&mut domain).await.is_err() {
                return;
            }
            String::from_utf8_lossy(&domain).to_string()
        }
        0x04 => { // IPv6
            let mut ip = [0u8; 16];
            if client.read_exact(&mut ip).await.is_err() {
                return;
            }
            Ipv6Addr::from(ip).to_string()
        }
        _ => {
            let _ = client.write_all(&[0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await;
            return;
        }
    };

    let mut port_buf = [0u8; 2];
    if client.read_exact(&mut port_buf).await.is_err() {
        return;
    }
    let target_port = u16::from_be_bytes(port_buf);
    let target_addr = format!("{}:{}", target_host, target_port);

    ldebug!("SOCKS5 connect request to {}", target_addr);

    // 3. Connect via Cloudflare CDN
    let ws_opt = socks5_acquire_cf_ws(&target_addr, &cancel).await;

    let ws = match ws_opt {
        Some(w) => w,
        None => {
            lwarn!("SOCKS5: CF connect failed for target {}", target_addr);
            let _ = client.write_all(&[0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await;
            return;
        }
    };

    // SOCKS5 success response
    if client.write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await.is_err() {
        ws.close().await;
        return;
    }

    STATS.connections_cfproxy.fetch_add(1, Ordering::Relaxed);
    STATS.connections_ws.fetch_add(1, Ordering::Relaxed);

    // 4. Bi-directional bridge
    bridge_socks5_ws(client, ws, cancel).await;
}

async fn socks5_acquire_cf_ws(target_addr: &str, cancel_token: &CancellationToken) -> Option<RawWebSocket> {
    let (enabled, domains, user_domain) = {
        let cfg = CFPROXY.read();
        (
            CFPROXY_ENABLED.load(Ordering::Relaxed),
            cfg.domains.clone(),
            cfg.user_domain.clone(),
        )
    };

    if !enabled {
        return None;
    }

    let path = format!("/tcp?target={}", target_addr);

    // If user has custom domain configured, prioritize it
    if !user_domain.is_empty() {
        let (ws, _, _) = cf_connect_domain(&user_domain, &path, 5.0).await;
        if let Some(w) = ws {
            return Some(w);
        }
    }

    if domains.is_empty() {
        return None;
    }

    // Try balanced domains
    let ordered = crate::balancer::BALANCER.read().get_domains_for_dc(2);
    for bd in ordered.iter().take(4) {
        if cancel_token.is_cancelled() {
            return None;
        }
        let (ws, _, err) = cf_connect_domain(bd, &path, 4.0).await;
        if let Some(w) = ws {
            return Some(w);
        }
        if let Some(e) = err {
            if is_http_status_error(&e, 429) {
                mark_cfproxy_429_cooldown(bd, &e);
            }
        }
    }

    None
}

async fn bridge_socks5_ws(client: TcpStream, ws: RawWebSocket, cancel_token: CancellationToken) {
    let ws = Arc::new(ws);
    let (mut c_read, mut c_write) = client.into_split();
    let cancel = Arc::new(tokio::sync::Notify::new());

    // Up: Client -> WS
    let ws_up = ws.clone();
    let cancel_up = cancel.clone();
    let cancel_token_up = cancel_token.clone();
    let up = tokio::spawn(async move {
        let mut buf = vec![0u8; 65536];
        loop {
            let res = tokio::select! {
                _ = cancel_token_up.cancelled() => break,
                _ = cancel_up.notified() => break,
                r = c_read.read(&mut buf) => r,
            };
            match res {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    STATS.bytes_up.fetch_add(n as i64, Ordering::Relaxed);
                    if ws_up.send(&buf[..n]).await.is_err() {
                        break;
                    }
                }
            }
        }
        cancel_up.notify_waiters();
    });

    // Down: WS -> Client
    let ws_down = ws.clone();
    let cancel_down = cancel.clone();
    let cancel_token_down = cancel_token.clone();
    let down = tokio::spawn(async move {
        loop {
            let res = tokio::select! {
                _ = cancel_token_down.cancelled() => break,
                _ = cancel_down.notified() => break,
                r = ws_down.recv() => r,
            };
            match res {
                Ok(data) => {
                    let n = data.len();
                    STATS.bytes_down.fetch_add(n as i64, Ordering::Relaxed);
                    if c_write.write_all(&data).await.is_err() {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
        cancel_down.notify_waiters();
    });

    let _ = tokio::join!(up, down);
    cancel.notify_waiters();
    ws.close().await;
}
