// Mirrly TG Proxy - Native L3 Android VPN Tunnel
// Copyright (C) 2026 R1Xern (Mirrly Dev)
// GNU GPL v3+ <https://www.gnu.org/licenses/>

use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::Arc;
use tokio_util::sync::CancellationToken;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VpnUplinkMode {
    WarpAwg = 0,
    WarpMasque = 1,
    Opera = 2,
    Proton = 3,
    Vless = 4,
}

impl VpnUplinkMode {
    pub fn from_i32(val: i32) -> Self {
        match val {
            1 => VpnUplinkMode::WarpMasque,
            2 => VpnUplinkMode::Opera,
            3 => VpnUplinkMode::Proton,
            4 => VpnUplinkMode::Vless,
            _ => VpnUplinkMode::WarpAwg,
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            VpnUplinkMode::WarpAwg => "WARP AmneziaWG",
            VpnUplinkMode::WarpMasque => "WARP MASQUE",
            VpnUplinkMode::Opera => "Opera VPN",
            VpnUplinkMode::Proton => "Proton VPN",
            VpnUplinkMode::Vless => "VLESS Reality",
        }
    }
}

pub static VPN_UPLINK_MODE: AtomicI32 = AtomicI32::new(0);

pub fn get_vpn_uplink_mode() -> VpnUplinkMode {
    VpnUplinkMode::from_i32(VPN_UPLINK_MODE.load(Ordering::Relaxed))
}

pub fn set_vpn_uplink_mode(mode: i32) {
    VPN_UPLINK_MODE.store(mode, Ordering::Relaxed);
    crate::linfo!("VPN: active uplink mode switched to {} ({})", mode, VpnUplinkMode::from_i32(mode).as_str());
}

#[cfg(unix)]
pub async fn run_vpn(
    tun_fd: std::os::raw::c_int,
    cancel_token: CancellationToken,
) -> Result<(), String> {
    use std::fs::File;
    use std::os::unix::io::FromRawFd;

    let mode = get_vpn_uplink_mode();
    crate::linfo!(
        "VPN: initializing native L3 TUN engine (tun_fd={}, uplink={})",
        tun_fd,
        mode.as_str()
    );

    let read_file = unsafe { File::from_raw_fd(tun_fd) };
    let write_file = read_file
        .try_clone()
        .map_err(|e| format!("failed to clone tun_fd: {}", e))?;

    let res = match mode {
        VpnUplinkMode::WarpAwg => run_awg_vpn(read_file, write_file, cancel_token).await,
        VpnUplinkMode::WarpMasque => run_masque_vpn(read_file, write_file, cancel_token).await,
        VpnUplinkMode::Proton => run_proton_vpn(read_file, write_file, cancel_token).await,
        VpnUplinkMode::Opera => run_proxy_vpn(read_file, write_file, VpnUplinkMode::Opera, cancel_token).await,
        VpnUplinkMode::Vless => run_proxy_vpn(read_file, write_file, VpnUplinkMode::Vless, cancel_token).await,
    };

    crate::linfo!("VPN: native L3 TUN engine stopped (result={:?})", res.as_ref().map(|_| "clean"));
    res
}

#[cfg(unix)]
async fn run_awg_vpn(
    read_file: std::fs::File,
    write_file: std::fs::File,
    cancel_token: CancellationToken,
) -> Result<(), String> {
    let peer = crate::awg::get_or_connect_peer(&cancel_token)
        .await
        .map_err(|e| format!("AWG connect failed: {}", e))?;

    crate::linfo!(
        "VPN [AWG]: connected to WireGuard/AWG Anycast peer {}, starting L3 pump",
        peer.endpoint_addr
    );

    run_peer_l3_pump(read_file, write_file, peer, cancel_token).await
}

#[cfg(unix)]
async fn run_proton_vpn(
    read_file: std::fs::File,
    write_file: std::fs::File,
    cancel_token: CancellationToken,
) -> Result<(), String> {
    use base64::engine::general_purpose::STANDARD as B64;
    use base64::Engine;

    let proton_cfg = crate::config::PROTON_VPN.read().clone();
    crate::linfo!(
        "VPN [Proton]: connecting to node {} ({}:{})",
        proton_cfg.node_name,
        proton_cfg.server_ip,
        proton_cfg.server_port
    );

    let mut priv_bytes = [0u8; 32];
    if let Ok(dec) = B64.decode(&proton_cfg.private_key) {
        if dec.len() == 32 {
            priv_bytes.copy_from_slice(&dec);
        }
    }
    if priv_bytes == [0u8; 32] {
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut priv_bytes);
    }

    let pub_bytes = crate::awg::x25519_base(&priv_bytes);

    let mut peer_pub = [0u8; 32];
    if let Ok(dec) = B64.decode(&proton_cfg.peer_public_key) {
        if dec.len() == 32 {
            peer_pub.copy_from_slice(&dec);
        }
    }
    if peer_pub == [0u8; 32] {
        return Err("Proton VPN: server public key not configured or invalid".to_string());
    }

    let client_ipv4: std::net::Ipv4Addr = proton_cfg
        .client_ip
        .parse()
        .unwrap_or_else(|_| std::net::Ipv4Addr::new(10, 2, 0, 2));

    let endpoint_str = format!("{}:{}", proton_cfg.server_ip, proton_cfg.server_port);

    let awg_config = crate::awg::AwgConfig {
        profile_name: format!("Proton VPN ({})", proton_cfg.node_name),
        endpoint: endpoint_str,
        fallback_endpoints: Vec::new(),
        private_key: priv_bytes,
        public_key: pub_bytes,
        peer_public_key: peer_pub,
        preshared_key: None,
        client_ipv4,
        client_ipv6: None,
        awg_params: crate::awg::AwgParams::default(), // Standard WireGuard
        persistent_keepalive: Some(25),
    };

    let peer = crate::awg::AwgPeerSession::connect_with_config(
        awg_config,
        &cancel_token,
        None,
        6000,
    )
    .await
    .map_err(|e| format!("Proton VPN connect failed: {}", e))?;

    crate::STATS.connections_proton.fetch_add(1, Ordering::Relaxed);
    crate::linfo!(
        "VPN [Proton]: handshake complete with {}, starting L3 pump",
        peer.endpoint_addr
    );

    run_peer_l3_pump(read_file, write_file, peer, cancel_token).await
}

#[cfg(unix)]
async fn run_peer_l3_pump(
    read_file: std::fs::File,
    mut write_file: std::fs::File,
    peer: Arc<crate::awg::AwgPeerSession>,
    cancel_token: CancellationToken,
) -> Result<(), String> {
    use std::io::{Read, Write};
    use std::os::unix::io::IntoRawFd;

    // 1. Inbound channel: decrypted IP packets from WireGuard -> written to TUN interface
    let (inbound_tx, mut inbound_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(2048);
    peer.set_tun_sink(Some(inbound_tx));

    let cancel_writer = cancel_token.clone();
    let writer_handle = tokio::task::spawn_blocking(move || {
        while !cancel_writer.is_cancelled() {
            match inbound_rx.blocking_recv() {
                Some(pkt) => {
                    crate::STATS.add_vpn_bytes_down(pkt.len() as i64);
                    if let Err(e) = write_file.write_all(&pkt) {
                        if !cancel_writer.is_cancelled() {
                            crate::lwarn!("VPN: TUN write error: {}", e);
                        }
                        break;
                    }
                }
                None => break,
            }
        }
    });

    // 2. Outbound forwarder: raw IP packets read from TUN -> encapsulated & sent via protected UDP
    let (outbound_tx, mut outbound_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(2048);
    let cancel_fwd = cancel_token.clone();
    let peer_fwd = Arc::clone(&peer);
    let fwd_handle = tokio::spawn(async move {
        while !cancel_fwd.is_cancelled() {
            tokio::select! {
                _ = cancel_fwd.cancelled() => break,
                Some(pkt) = outbound_rx.recv() => {
                    crate::STATS.add_vpn_bytes_up(pkt.len() as i64);
                    if let Err(e) = peer_fwd.send_ip_packet(&pkt).await {
                        crate::ldebug!("VPN: send_ip_packet error: {}", e);
                    }
                }
            }
        }
    });

    // 3. Outbound reader: blocking read from TUN file descriptor in dedicated OS thread
    let cancel_reader = cancel_token.clone();
    let mut read_f = read_file;
    let reader_handle = tokio::task::spawn_blocking(move || {
        let mut buf = [0u8; 65535];
        while !cancel_reader.is_cancelled() {
            match read_f.read(&mut buf) {
                Ok(0) => break, // EOF / TUN shutdown
                Ok(n) => {
                    let pkt = buf[..n].to_vec();
                    if outbound_tx.blocking_send(pkt).is_err() {
                        break;
                    }
                }
                Err(e) => {
                    if !cancel_reader.is_cancelled() {
                        crate::lwarn!("VPN: TUN read error: {}", e);
                    }
                    break;
                }
            }
        }
        let _ = read_f.into_raw_fd();
    });

    // Wait until stop signal
    cancel_token.cancelled().await;

    peer.set_tun_sink(None);
    let _ = fwd_handle.abort();
    let _ = writer_handle.abort();
    let _ = reader_handle.abort();

    Ok(())
}

#[cfg(unix)]
async fn run_masque_vpn(
    read_file: std::fs::File,
    mut write_file: std::fs::File,
    cancel_token: CancellationToken,
) -> Result<(), String> {
    use std::io::{Read, Write};
    use std::os::unix::io::IntoRawFd;

    crate::linfo!("VPN [MASQUE]: acquiring Cloudflare WARP MASQUE tunnel...");
    let tunnel = match crate::masque::masque_acquire_tunnel("", &cancel_token).await {
        Some(t) => t,
        None => {
            return Err("failed to acquire Cloudflare WARP MASQUE tunnel (check credentials/Anycast)".to_string());
        }
    };

    crate::linfo!(
        "VPN [MASQUE]: connected to Cloudflare Anycast {}, quarter_stream_id={}",
        tunnel.endpoint_addr,
        tunnel.quarter_stream_id
    );

    let tunnel_arc = Arc::new(tunnel);

    // 1. Inbound loop: HTTP/3 datagrams from Cloudflare -> write raw IP packets to TUN
    let tunnel_in = Arc::clone(&tunnel_arc);
    let cancel_in = cancel_token.clone();
    let in_handle = tokio::spawn(async move {
        let mut dgram_rx = tunnel_in.dgram_rx.lock().await;
        while !cancel_in.is_cancelled() {
            tokio::select! {
                _ = cancel_in.cancelled() => break,
                res = dgram_rx.recv() => {
                    match res {
                        Some(ip_pkt) => {
                            crate::STATS.add_vpn_bytes_down(ip_pkt.len() as i64);
                            if let Err(e) = write_file.write_all(&ip_pkt) {
                                if !cancel_in.is_cancelled() {
                                    crate::lwarn!("VPN [MASQUE]: TUN write error: {}", e);
                                }
                                break;
                            }
                        }
                        None => break,
                    }
                }
            }
        }
    });

    // 2. Outbound loop: raw IP packets from TUN -> HTTP/3 datagrams to Cloudflare
    let (out_tx, mut out_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(2048);
    let tunnel_out = Arc::clone(&tunnel_arc);
    let cancel_out = cancel_token.clone();
    let out_handle = tokio::spawn(async move {
        while !cancel_out.is_cancelled() {
            tokio::select! {
                _ = cancel_out.cancelled() => break,
                Some(pkt) = out_rx.recv() => {
                    crate::STATS.add_vpn_bytes_up(pkt.len() as i64);
                    let dgram = crate::masque::encode_h3_datagram(tunnel_out.quarter_stream_id, 0, &pkt);
                    let bytes_dgram = bytes::Bytes::from(dgram);
                    let _ = tunnel_out.connection.send_datagram_wait(bytes_dgram).await;
                }
            }
        }
    });

    // 3. Reader from TUN fd
    let cancel_read = cancel_token.clone();
    let mut read_f = read_file;
    let read_handle = tokio::task::spawn_blocking(move || {
        let mut buf = [0u8; 65535];
        while !cancel_read.is_cancelled() {
            match read_f.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    let pkt = buf[..n].to_vec();
                    if out_tx.blocking_send(pkt).is_err() {
                        break;
                    }
                }
                Err(e) => {
                    if !cancel_read.is_cancelled() {
                        crate::lwarn!("VPN [MASQUE]: TUN read error: {}", e);
                    }
                    break;
                }
            }
        }
        let _ = read_f.into_raw_fd();
    });

    cancel_token.cancelled().await;
    let _ = in_handle.abort();
    let _ = out_handle.abort();
    let _ = read_handle.abort();
    tunnel_arc.close().await;
    Ok(())
}

#[cfg(unix)]
async fn run_proxy_vpn(
    read_file: std::fs::File,
    mut write_file: std::fs::File,
    mode: VpnUplinkMode,
    cancel_token: CancellationToken,
) -> Result<(), String> {
    use std::io::{Read, Write};
    use std::os::unix::io::IntoRawFd;

    crate::linfo!("VPN [Proxy]: starting L3 proxy bridge for {}", mode.as_str());

    let (inbound_tx, mut inbound_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(2048);
    let (outbound_tx, mut outbound_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(2048);

    // 1. Writer to TUN
    let cancel_writer = cancel_token.clone();
    let writer_handle = tokio::task::spawn_blocking(move || {
        while !cancel_writer.is_cancelled() {
            match inbound_rx.blocking_recv() {
                Some(pkt) => {
                    crate::STATS.add_vpn_bytes_down(pkt.len() as i64);
                    if let Err(e) = write_file.write_all(&pkt) {
                        if !cancel_writer.is_cancelled() {
                            crate::lwarn!("VPN [Proxy]: TUN write error: {}", e);
                        }
                        break;
                    }
                }
                None => break,
            }
        }
    });

    // 2. Reader from TUN
    let cancel_reader = cancel_token.clone();
    let mut read_f = read_file;
    let reader_handle = tokio::task::spawn_blocking(move || {
        let mut buf = [0u8; 65535];
        while !cancel_reader.is_cancelled() {
            match read_f.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    let pkt = buf[..n].to_vec();
                    if outbound_tx.blocking_send(pkt).is_err() {
                        break;
                    }
                }
                Err(e) => {
                    if !cancel_reader.is_cancelled() {
                        crate::lwarn!("VPN [Proxy]: TUN read error: {}", e);
                    }
                    break;
                }
            }
        }
        let _ = read_f.into_raw_fd();
    });

    // 3. Dispatcher loop: processes packets from TUN
    let cancel_dispatch = cancel_token.clone();
    let in_tx = inbound_tx.clone();
    let dispatch_handle = tokio::spawn(async move {
        while !cancel_dispatch.is_cancelled() {
            tokio::select! {
                _ = cancel_dispatch.cancelled() => break,
                Some(pkt) = outbound_rx.recv() => {
                    crate::STATS.add_vpn_bytes_up(pkt.len() as i64);

                    // Check if IPv4 packet
                    if pkt.len() >= 20 && (pkt[0] >> 4) == 4 {
                        let proto = pkt[9];
                        let src_ip = std::net::Ipv4Addr::new(pkt[12], pkt[13], pkt[14], pkt[15]);
                        let dst_ip = std::net::Ipv4Addr::new(pkt[16], pkt[17], pkt[18], pkt[19]);

                        // UDP packet handling
                        if proto == 17 && pkt.len() >= 28 {
                            let src_port = u16::from_be_bytes([pkt[20], pkt[21]]);
                            let dst_port = u16::from_be_bytes([pkt[22], pkt[23]]);
                            let udp_len = u16::from_be_bytes([pkt[24], pkt[25]]) as usize;

                            // DNS query interception on port 53 (RFC 8484 DoH)
                            if dst_port == 53 && udp_len >= 8 && pkt.len() >= 20 + udp_len {
                                let dns_query = pkt[28..20 + udp_len].to_vec();
                                let in_tx_dns = in_tx.clone();
                                tokio::spawn(async move {
                                    if let Ok(dns_resp) = crate::dns::resolve_doh_wireformat(&dns_query).await {
                                        let resp_pkt = build_ipv4_udp_packet(
                                            dst_ip,
                                            src_ip,
                                            dst_port,
                                            src_port,
                                            &dns_resp,
                                        );
                                        let _ = in_tx_dns.send(resp_pkt).await;
                                    }
                                });
                                continue;
                            }
                        }
                    }
                }
            }
        }
    });

    cancel_token.cancelled().await;
    let _ = writer_handle.abort();
    let _ = reader_handle.abort();
    let _ = dispatch_handle.abort();
    Ok(())
}

/// Computes standard Internet Checksum (RFC 1071).
pub fn ipv4_checksum(header: &[u8]) -> u16 {
    let mut sum = 0u32;
    for i in (0..header.len()).step_by(2) {
        if i + 1 < header.len() {
            sum += u16::from_be_bytes([header[i], header[i + 1]]) as u32;
        } else {
            sum += (header[i] as u32) << 8;
        }
    }
    while (sum >> 16) > 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !sum as u16
}

/// Constructs a raw IPv4 UDP packet.
pub fn build_ipv4_udp_packet(
    src_ip: std::net::Ipv4Addr,
    dst_ip: std::net::Ipv4Addr,
    src_port: u16,
    dst_port: u16,
    payload: &[u8],
) -> Vec<u8> {
    let total_len = 20 + 8 + payload.len();
    let mut pkt = vec![0u8; total_len];
    // IPv4 header
    pkt[0] = 0x45; // Version 4, IHL 5 (20 bytes)
    pkt[1] = 0x00; // DSCP / ECN
    pkt[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
    pkt[4..6].copy_from_slice(&rand::random::<u16>().to_be_bytes()); // ID
    pkt[6..8].copy_from_slice(&[0x40, 0x00]); // Don't Fragment
    pkt[8] = 64; // TTL
    pkt[9] = 17; // Protocol UDP
    pkt[12..16].copy_from_slice(&src_ip.octets());
    pkt[16..20].copy_from_slice(&dst_ip.octets());
    let ip_csum = ipv4_checksum(&pkt[0..20]);
    pkt[10..12].copy_from_slice(&ip_csum.to_be_bytes());

    // UDP header
    let udp_len = (8 + payload.len()) as u16;
    pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
    pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
    pkt[24..26].copy_from_slice(&udp_len.to_be_bytes());
    pkt[26..28].copy_from_slice(&[0, 0]); // Checksum optional for IPv4 UDP
    pkt[28..].copy_from_slice(payload);
    pkt
}

#[cfg(not(unix))]
pub async fn run_vpn(
    _tun_fd: std::os::raw::c_int,
    _cancel_token: CancellationToken,
) -> Result<(), String> {
    Err("Native VPN requires Unix/Android platform".to_string())
}
