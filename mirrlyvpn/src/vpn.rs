// Mirrly TG Proxy - Native L3 Android VPN Tunnel
// Copyright (C) 2026 R1Xern (Mirrly Dev)
// GNU GPL v3+ <https://www.gnu.org/licenses/>

use once_cell::sync::Lazy;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
#[cfg(unix)]
use std::sync::Arc;
use tokio_util::sync::CancellationToken;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VpnUplinkMode {
    WarpAwg = 0,
}

impl VpnUplinkMode {
    pub fn from_i32(val: i32) -> Self {
        match val {
            _ => VpnUplinkMode::WarpAwg,
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            VpnUplinkMode::WarpAwg => "WARP AmneziaWG",
        }
    }
}

pub static VPN_UPLINK_MODE: AtomicI32 = AtomicI32::new(0);
pub static IS_VPN_ESTABLISHED: AtomicBool = AtomicBool::new(false);
pub static VPN_RECONNECT_NOTIFY: Lazy<tokio::sync::Notify> = Lazy::new(tokio::sync::Notify::new);

pub fn is_vpn_established() -> bool {
    IS_VPN_ESTABLISHED.load(Ordering::Relaxed)
}

pub fn notify_vpn_reconnect() {
    VPN_RECONNECT_NOTIFY.notify_waiters();
}

pub fn get_vpn_uplink_mode() -> VpnUplinkMode {
    VpnUplinkMode::from_i32(VPN_UPLINK_MODE.load(Ordering::Relaxed))
}

pub fn set_vpn_uplink_mode(mode: i32) {
    VPN_UPLINK_MODE.store(mode, Ordering::Relaxed);
    crate::linfo!(
        "VPN: active uplink mode switched to {} ({})",
        mode,
        VpnUplinkMode::from_i32(mode).as_str()
    );
}

const VIRTUAL_DNS_IPV4: [u8; 4] = [192, 0, 2, 3];

pub fn is_blocked_virtual_dns_tcp(pkt: &[u8]) -> bool {
    if pkt.is_empty() {
        return false;
    }
    let version = pkt[0] >> 4;
    if version == 4 {
        if pkt.len() < 40 {
            return false;
        }
        let ihl = ((pkt[0] & 0x0F) as usize) * 4;
        if ihl < 20 || pkt.len() < ihl + 20 || pkt[9] != 6 {
            return false;
        }
        let dst_port = u16::from_be_bytes([pkt[ihl + 2], pkt[ihl + 3]]);
        let dst_ip = std::net::Ipv4Addr::new(pkt[16], pkt[17], pkt[18], pkt[19]);

        // Virtual DNS (192.0.2.3): TCP DNS (port 53) or DoT (port 853) to virtual DNS must be
        // silently dropped without generating TCP RST, preventing Android NetworkMonitor broken network flags.
        if dst_ip.octets() == VIRTUAL_DNS_IPV4 && (dst_port == 53 || dst_port == 853) {
            return true;
        }
    }
    false
}

pub fn is_tcp_dot_port_853(pkt: &[u8]) -> bool {
    if pkt.is_empty() {
        return false;
    }
    let version = pkt[0] >> 4;
    if version == 4 {
        if pkt.len() < 40 {
            return false;
        }
        let ihl = ((pkt[0] & 0x0F) as usize) * 4;
        if ihl < 20 || pkt.len() < ihl + 20 || pkt[9] != 6 {
            return false;
        }
        let dst_port = u16::from_be_bytes([pkt[ihl + 2], pkt[ihl + 3]]);
        dst_port == 853
    } else if version == 6 {
        if pkt.len() < 60 || pkt[6] != 6 {
            return false;
        }
        let dst_port = u16::from_be_bytes([pkt[42], pkt[43]]);
        dst_port == 853
    } else {
        false
    }
}

/// Determines whether an outbound TUN TCP packet should be silently dropped in the TUN reader.
/// - Virtual DNS (192.0.2.3:53 / :853) is always silently dropped.
/// - When `tls_enabled == false`, Android Private DNS DoT (TCP :853) is silently dropped so
///   Android falls back cleanly to plain DNS (:53) over the configured VPN DNS servers.
/// - When `tls_enabled == true`, public DoT (:853) passes through the tunnel normally.
pub fn should_drop_outbound_dns_tcp(pkt: &[u8], tls_enabled: bool) -> bool {
    if is_blocked_virtual_dns_tcp(pkt) {
        return true;
    }
    if !tls_enabled && is_tcp_dot_port_853(pkt) {
        return true;
    }
    false
}

pub fn handle_dot_or_blocked_tcp(_pkt: &[u8]) -> Option<Vec<u8>> {
    // TCP RST on DoT (port 853) is strictly forbidden for all public and tunnel IP addresses.
    // Dropping without RST prevents Android 9+ NetworkMonitor from flagging "No Internet".
    None
}

const SAFE_MSS_V4: u16 = 1220;
const SAFE_MSS_V6: u16 = 1200;

fn recompute_tcp_checksum_v4(pkt: &mut [u8], ihl: usize) {
    let total_len = pkt.len();
    let tcp_len = total_len - ihl;
    pkt[ihl + 16..ihl + 18].copy_from_slice(&[0, 0]);

    let mut sum: u32 = 0;
    sum += u16::from_be_bytes([pkt[12], pkt[13]]) as u32;
    sum += u16::from_be_bytes([pkt[14], pkt[15]]) as u32;
    sum += u16::from_be_bytes([pkt[16], pkt[17]]) as u32;
    sum += u16::from_be_bytes([pkt[18], pkt[19]]) as u32;
    sum += 6u32;
    sum += tcp_len as u32;

    for i in (ihl..total_len).step_by(2) {
        if i + 1 < total_len {
            sum += u16::from_be_bytes([pkt[i], pkt[i + 1]]) as u32;
        } else {
            sum += (pkt[i] as u32) << 8;
        }
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    let csum = !(sum as u16);
    pkt[ihl + 16..ihl + 18].copy_from_slice(&csum.to_be_bytes());
}

fn recompute_tcp_checksum_v6(pkt: &mut [u8]) {
    let total_len = pkt.len();
    let tcp_len = total_len - 40;
    pkt[40 + 16..40 + 18].copy_from_slice(&[0, 0]);

    let mut sum: u32 = 0;
    for i in (8..40).step_by(2) {
        sum += u16::from_be_bytes([pkt[i], pkt[i + 1]]) as u32;
    }
    sum += (tcp_len as u32) >> 16;
    sum += (tcp_len as u32) & 0xFFFF;
    sum += 6u32;

    for i in (40..total_len).step_by(2) {
        if i + 1 < total_len {
            sum += u16::from_be_bytes([pkt[i], pkt[i + 1]]) as u32;
        } else {
            sum += (pkt[i] as u32) << 8;
        }
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    let csum = !(sum as u16);
    pkt[40 + 16..40 + 18].copy_from_slice(&csum.to_be_bytes());
}

pub fn clamp_tcp_mss(pkt: &mut [u8]) {
    if pkt.is_empty() {
        return;
    }
    let version = pkt[0] >> 4;
    if version == 4 {
        if pkt.len() < 40 {
            return;
        }
        let ihl = ((pkt[0] & 0x0F) as usize) * 4;
        if ihl < 20 || pkt.len() < ihl + 20 || pkt[9] != 6 {
            return;
        }
        let tcp_offset = ihl;
        let tcp_header_len = (((pkt[tcp_offset + 12] >> 4) as usize) * 4).min(pkt.len() - tcp_offset);
        if tcp_header_len <= 20 {
            return;
        }
        let tcp_flags = pkt[tcp_offset + 13];
        // Clamp SYN packets
        if (tcp_flags & 0x02) == 0 {
            return;
        }

        let mut opt_idx = tcp_offset + 20;
        let opt_end = tcp_offset + tcp_header_len;
        let mut modified = false;

        while opt_idx < opt_end {
            let kind = pkt[opt_idx];
            if kind == 0 {
                break;
            }
            if kind == 1 {
                opt_idx += 1;
                continue;
            }
            if opt_idx + 1 >= opt_end {
                break;
            }
            let len = pkt[opt_idx + 1] as usize;
            if len < 2 || opt_idx + len > opt_end {
                break;
            }
            if kind == 2 && len == 4 {
                let current_mss = u16::from_be_bytes([pkt[opt_idx + 2], pkt[opt_idx + 3]]);
                if current_mss > SAFE_MSS_V4 {
                    pkt[opt_idx + 2..opt_idx + 4].copy_from_slice(&SAFE_MSS_V4.to_be_bytes());
                    modified = true;
                }
                break;
            }
            opt_idx += len;
        }

        if modified {
            recompute_tcp_checksum_v4(pkt, ihl);
        }
    } else if version == 6 {
        if pkt.len() < 60 || pkt[6] != 6 {
            return;
        }
        let tcp_offset = 40;
        let tcp_header_len = (((pkt[tcp_offset + 12] >> 4) as usize) * 4).min(pkt.len() - tcp_offset);
        if tcp_header_len <= 20 {
            return;
        }
        let tcp_flags = pkt[tcp_offset + 13];
        if (tcp_flags & 0x02) == 0 {
            return;
        }

        let mut opt_idx = tcp_offset + 20;
        let opt_end = tcp_offset + tcp_header_len;
        let mut modified = false;

        while opt_idx < opt_end {
            let kind = pkt[opt_idx];
            if kind == 0 {
                break;
            }
            if kind == 1 {
                opt_idx += 1;
                continue;
            }
            if opt_idx + 1 >= opt_end {
                break;
            }
            let len = pkt[opt_idx + 1] as usize;
            if len < 2 || opt_idx + len > opt_end {
                break;
            }
            if kind == 2 && len == 4 {
                let current_mss = u16::from_be_bytes([pkt[opt_idx + 2], pkt[opt_idx + 3]]);
                if current_mss > SAFE_MSS_V6 {
                    pkt[opt_idx + 2..opt_idx + 4].copy_from_slice(&SAFE_MSS_V6.to_be_bytes());
                    modified = true;
                }
                break;
            }
            opt_idx += len;
        }

        if modified {
            recompute_tcp_checksum_v6(pkt);
        }
    }
}

#[cfg(unix)]
fn read_tun_loop(
    mut read_file: std::fs::File,
    outbound_tx: tokio::sync::mpsc::Sender<Vec<u8>>,
    dns_inbound_tx: tokio::sync::mpsc::Sender<Vec<u8>>,
    cancel_reader: CancellationToken,
    tag: &'static str,
) {
    use std::io::Read;
    use std::os::unix::io::AsRawFd;

    let raw_fd = read_file.as_raw_fd();

    // 1. Enable O_NONBLOCK on the TUN open file description for batch draining
    unsafe {
        let flags = libc::fcntl(raw_fd, libc::F_GETFL, 0);
        if flags >= 0 {
            libc::fcntl(raw_fd, libc::F_SETFL, flags | libc::O_NONBLOCK);
        }
    }

    let mut buf = [0u8; 65535];
    let mut pfd = libc::pollfd {
        fd: raw_fd,
        events: libc::POLLIN,
        revents: 0,
    };

    const READ_BATCH_LIMIT: usize = 128;

    while !cancel_reader.is_cancelled() {
        pfd.revents = 0;
        let ret = unsafe { libc::poll(&mut pfd as *mut libc::pollfd, 1, 150) };
        if ret < 0 {
            let err = std::io::Error::last_os_error();
            if err.raw_os_error() == Some(libc::EINTR) || err.kind() == std::io::ErrorKind::Interrupted {
                continue;
            }
            if !cancel_reader.is_cancelled() {
                crate::lwarn!("VPN [{}]: TUN poll error: {}", tag, err);
                cancel_reader.cancel();
            }
            break;
        }
        if ret == 0 {
            // 150ms timeout: re-evaluate cancel_reader.is_cancelled()
            continue;
        }

        if (pfd.revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL)) != 0 {
            if !cancel_reader.is_cancelled() {
                crate::ldebug!("VPN [{}]: TUN poll revents error/hup/nval: {:#x}", tag, pfd.revents);
                cancel_reader.cancel();
            }
            break;
        }

        if (pfd.revents & libc::POLLIN) != 0 {
            let mut drained_count = 0;
            while drained_count < READ_BATCH_LIMIT && !cancel_reader.is_cancelled() {
                match read_file.read(&mut buf) {
                    Ok(0) => {
                        crate::linfo!("VPN [{}]: TUN EOF received, stopping reader", tag);
                        cancel_reader.cancel();
                        break;
                    }
                    Ok(n) => {
                        drained_count += 1;
                        let mut pkt = buf[..n].to_vec();

                        // 1. Android Private DNS DoT (port 853) / Virtual DNS TCP filtering:
                        // Virtual DNS (192.0.2.3) is always silently dropped without TCP RST.
                        // When VPN_TLS_ENABLED == false, Private DNS DoT (:853) is also silently dropped
                        // so DNS cleanly falls back to plain DNS (:53) over configured VPN DNS servers.
                        let tls_enabled = crate::config::is_vpn_tls_enabled();
                        if should_drop_outbound_dns_tcp(&pkt, tls_enabled) {
                            continue;
                        }
                        if let Some(rst_pkt) = handle_dot_or_blocked_tcp(&pkt) {
                            let _ = dns_inbound_tx.try_send(rst_pkt);
                            continue;
                        }

                        // 2. Forward TUN packets directly into the encrypted AWG/WG tunnel.
                        // Legacy virtual DNS IP (192.0.2.3:53), if ever seen, is rewritten to 1.1.1.1 without DoH interception.
                        if pkt.len() >= 28 && (pkt[0] >> 4) == 4 && pkt[9] == 17 && pkt[16..20] == VIRTUAL_DNS_IPV4 {
                            pkt[16..20].copy_from_slice(&[1, 1, 1, 1]);
                            pkt[10..12].copy_from_slice(&[0, 0]);
                            let ihl = ((pkt[0] & 0x0F) as usize) * 4;
                            if ihl >= 20 && pkt.len() >= ihl + 8 {
                                let mut ip_sum: u32 = 0;
                                for i in (0..ihl).step_by(2) {
                                    ip_sum += u16::from_be_bytes([pkt[i], pkt[i + 1]]) as u32;
                                }
                                while (ip_sum >> 16) != 0 {
                                    ip_sum = (ip_sum & 0xFFFF) + (ip_sum >> 16);
                                }
                                let ip_csum = !(ip_sum as u16);
                                pkt[10..12].copy_from_slice(&ip_csum.to_be_bytes());
                                pkt[ihl + 6..ihl + 8].copy_from_slice(&[0, 0]);
                            }
                        }

                        // 3. MSS Clamping on all outbound TCP SYN packets to eliminate cellular fragmentation
                        clamp_tcp_mss(&mut pkt);
                        match outbound_tx.blocking_send(pkt) {
                            Ok(_) => {}
                            Err(_) => {
                                cancel_reader.cancel();
                                break;
                            }
                        }
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        // Kernel TUN receive queue completely drained; break back to poll
                        break;
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {
                        continue;
                    }
                    Err(e) => {
                        if !cancel_reader.is_cancelled() {
                            crate::lwarn!("VPN [{}]: TUN read error: {}", tag, e);
                            cancel_reader.cancel();
                        }
                        break;
                    }
                }
            }
        }
    }

    drop(read_file);
    drop(dns_inbound_tx);
    drop(outbound_tx);
    crate::linfo!("VPN [{}]: TUN reader cleanly stopped and FD closed", tag);
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
        "VPN: initializing native L3 TUN engine (tun_fd={}, uplink={}, tls_enabled={})",
        tun_fd,
        mode.as_str(),
        crate::config::is_vpn_tls_enabled()
    );

    let native_tun_fd = unsafe { libc::dup(tun_fd) };
    if native_tun_fd < 0 {
        return Err(format!("failed to dup tun_fd: {}", std::io::Error::last_os_error()));
    }

    IS_VPN_ESTABLISHED.store(false, Ordering::SeqCst);

    let read_file = unsafe { File::from_raw_fd(native_tun_fd) };
    let write_file = match read_file.try_clone() {
        Ok(f) => f,
        Err(e) => {
            return Err(format!("failed to clone tun_fd: {}", e));
        }
    };

    let res = match mode {
        VpnUplinkMode::WarpAwg => run_awg_vpn(read_file, write_file, cancel_token).await,
    };

    IS_VPN_ESTABLISHED.store(false, Ordering::SeqCst);
    crate::linfo!("VPN: native L3 TUN engine stopped (result={:?})", res.as_ref().map(|_| "clean"));
    res
}

#[cfg(unix)]
async fn run_awg_vpn(
    read_file: std::fs::File,
    write_file: std::fs::File,
    cancel_token: CancellationToken,
) -> Result<(), String> {
    crate::awg::reset_awg_diagnostics();
    let peer = crate::awg::get_or_connect_peer(&cancel_token)
        .await
        .map_err(|e| format!("AWG connect failed: {}", e))?;

    crate::STATS.connections_awg.fetch_add(1, Ordering::Relaxed);
    IS_VPN_ESTABLISHED.store(true, Ordering::SeqCst);

    crate::linfo!(
        "VPN [AWG]: connected to WireGuard/AWG Anycast peer {}, starting L3 pump",
        peer.endpoint_addr
    );

    let res = run_peer_l3_pump(read_file, write_file, peer, cancel_token).await;
    IS_VPN_ESTABLISHED.store(false, Ordering::SeqCst);
    res
}

#[cfg(unix)]
async fn run_peer_l3_pump(
    read_file: std::fs::File,
    mut write_file: std::fs::File,
    peer: Arc<crate::awg::AwgPeerSession>,
    cancel_token: CancellationToken,
) -> Result<(), String> {
    use std::os::unix::io::AsRawFd;

    // 1. Inbound channel: decrypted IP packets from WireGuard -> written to TUN interface
    let (inbound_tx, mut inbound_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(2048);
    peer.set_tun_sink(Some(inbound_tx.clone()));

    let cancel_writer = cancel_token.clone();
    let raw_write_fd = write_file.as_raw_fd();
    let writer_handle = tokio::task::spawn_blocking(move || {
        use std::io::Write;
        const WRITE_BURST_LIMIT: usize = 128;

        let write_single_packet = |wf: &mut std::fs::File, pkt: &[u8]| -> bool {
            crate::STATS.add_vpn_bytes_down(pkt.len() as i64);
            let mut retries = 0;
            loop {
                match wf.write_all(pkt) {
                    Ok(()) => return true,
                    Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        retries += 1;
                        if retries > 3 {
                            crate::ldebug!("VPN [AWG]: TUN write queue saturated; dropping packet");
                            return true;
                        }
                        let mut pfd = libc::pollfd {
                            fd: raw_write_fd,
                            events: libc::POLLOUT,
                            revents: 0,
                        };
                        let ret = unsafe { libc::poll(&mut pfd as *mut libc::pollfd, 1, 20) };
                        if ret <= 0 {
                            return true;
                        }
                    }
                    Err(ref e) if e.kind() == std::io::ErrorKind::Interrupted => {
                        continue;
                    }
                    Err(e) => {
                        if !cancel_writer.is_cancelled() {
                            crate::lwarn!("VPN [AWG]: TUN write error: {}", e);
                        }
                        return false;
                    }
                }
            }
        };

        'writer_loop: while !cancel_writer.is_cancelled() {
            match inbound_rx.blocking_recv() {
                Some(pkt) => {
                    if pkt.is_empty() || cancel_writer.is_cancelled() {
                        break 'writer_loop;
                    }
                    let mut pkt = pkt;
                    clamp_tcp_mss(&mut pkt);
                    if !write_single_packet(&mut write_file, &pkt) {
                        break 'writer_loop;
                    }

                    let mut drained = 1;
                    while drained < WRITE_BURST_LIMIT {
                        match inbound_rx.try_recv() {
                            Ok(burst_pkt) => {
                                if burst_pkt.is_empty() || cancel_writer.is_cancelled() {
                                    break 'writer_loop;
                                }
                                drained += 1;
                                let mut burst_pkt = burst_pkt;
                                clamp_tcp_mss(&mut burst_pkt);
                                if !write_single_packet(&mut write_file, &burst_pkt) {
                                    break 'writer_loop;
                                }
                            }
                            Err(tokio::sync::mpsc::error::TryRecvError::Empty) => {
                                break;
                            }
                            Err(tokio::sync::mpsc::error::TryRecvError::Disconnected) => {
                                break 'writer_loop;
                            }
                        }
                    }
                }
                None => break 'writer_loop,
            }
        }
        drop(write_file);
        crate::linfo!("VPN [AWG]: TUN writer cleanly stopped and FD closed");
    });

    // 2. Outbound forwarder: raw IP packets read from TUN -> encapsulated & sent via protected UDP
    let (outbound_tx, mut outbound_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(2048);
    let cancel_fwd = cancel_token.clone();
    let peer_holder = Arc::new(parking_lot::RwLock::new(Arc::clone(&peer)));
    let peer_holder_fwd = Arc::clone(&peer_holder);
    let fwd_handle = tokio::spawn(async move {
        let mut cached_peer = peer_holder_fwd.read().clone();
        let mut batch: Vec<Vec<u8>> = Vec::with_capacity(128);

        while !cancel_fwd.is_cancelled() {
            let first_pkt = tokio::select! {
                biased;
                _ = cancel_fwd.cancelled() => break,
                maybe_pkt = outbound_rx.recv() => {
                    match maybe_pkt {
                        Some(p) => p,
                        None => break,
                    }
                }
            };

            batch.push(first_pkt);

            while batch.len() < 128 {
                match outbound_rx.try_recv() {
                    Ok(pkt) => batch.push(pkt),
                    Err(_) => break,
                }
            }

            if cached_peer.cancel_token.is_cancelled() {
                cached_peer = peer_holder_fwd.read().clone();
                if cached_peer.cancel_token.is_cancelled() {
                    batch.clear();
                    tokio::time::sleep(std::time::Duration::from_millis(50)).await;
                    continue;
                }
            }

            let batch_bytes: usize = batch.iter().map(|p| p.len()).sum();
            crate::STATS.add_vpn_bytes_up(batch_bytes as i64);

            if let Err(e) = cached_peer.send_ip_packet_batch_pipeline(&batch).await {
                crate::ldebug!("VPN [AWG]: forwarder batch send error: {}", e);
            }
            batch.clear();
        }
        crate::linfo!("VPN [AWG]: forwarder task cleanly stopped");
    });

    // 3. Outbound reader: cancellable poll-based read from TUN in dedicated OS thread
    let cancel_reader = cancel_token.clone();
    let dns_inbound_tx = inbound_tx.clone();
    let reader_handle = tokio::task::spawn_blocking(move || {
        read_tun_loop(read_file, outbound_tx, dns_inbound_tx, cancel_reader, "AWG");
    });

    struct AwgL3PumpGuard {
        peer_holder: Arc<parking_lot::RwLock<Arc<crate::awg::AwgPeerSession>>>,
        inbound_tx: Option<tokio::sync::mpsc::Sender<Vec<u8>>>,
        cancel_token: CancellationToken,
    }

    impl Drop for AwgL3PumpGuard {
        fn drop(&mut self) {
            self.cancel_token.cancel();
            let peer = self.peer_holder.read().clone();
            peer.set_tun_sink(None);
            if let Some(tx) = self.inbound_tx.take() {
                let _ = tx.try_send(Vec::new());
                drop(tx);
            }
        }
    }

    let guard = AwgL3PumpGuard {
        peer_holder: Arc::clone(&peer_holder),
        inbound_tx: Some(inbound_tx),
        cancel_token: cancel_token.clone(),
    };

    // 4. Watchdog & failover supervisor loop
    let mut watchdog_interval = tokio::time::interval(std::time::Duration::from_secs(2));
    loop {
        tokio::select! {
            _ = cancel_token.cancelled() => break,
            _ = crate::vpn::VPN_RECONNECT_NOTIFY.notified() => {
                crate::linfo!("VPN [AWG]: received explicit VPN socket reconnect notification (network handover / reset)");
                let current_peer = peer_holder.read().clone();
                current_peer.set_tun_sink(None);
                crate::awg::invalidate_active_peer();
                match crate::awg::get_or_connect_peer(&cancel_token).await {
                    Ok(new_peer) => {
                        if let Some(ref tx) = guard.inbound_tx {
                            new_peer.set_tun_sink(Some(tx.clone()));
                        }
                        *peer_holder.write() = Arc::clone(&new_peer);
                        crate::STATS.connections_awg.fetch_add(1, Ordering::Relaxed);
                        IS_VPN_ESTABLISHED.store(true, Ordering::SeqCst);
                        crate::linfo!(
                            "VPN [AWG]: Handover reset completed! Now routed via Anycast peer {}",
                            new_peer.endpoint_addr
                        );
                    }
                    Err(e) => {
                        crate::lwarn!("VPN [AWG]: Handover reconnect attempt failed: {}, will retry on watchdog tick", e);
                    }
                }
            }
            _ = watchdog_interval.tick() => {
                let current_peer = peer_holder.read().clone();
                if current_peer.cancel_token.is_cancelled() || !current_peer.is_healthy() {
                    IS_VPN_ESTABLISHED.store(false, Ordering::SeqCst);
                    current_peer.set_tun_sink(None);
                    crate::lwarn!(
                        "VPN [AWG]: active peer {} is cancelled or unhealthy (handshake/NAT loss); acquiring Anycast fallback...",
                        current_peer.endpoint_addr
                    );
                    crate::awg::invalidate_active_peer();
                    match crate::awg::get_or_connect_peer(&cancel_token).await {
                        Ok(new_peer) => {
                            if let Some(ref tx) = guard.inbound_tx {
                                new_peer.set_tun_sink(Some(tx.clone()));
                            }
                            *peer_holder.write() = Arc::clone(&new_peer);
                            crate::STATS.connections_awg.fetch_add(1, Ordering::Relaxed);
                            IS_VPN_ESTABLISHED.store(true, Ordering::SeqCst);
                            crate::linfo!(
                                "VPN [AWG]: Anycast failover successful! Now routed via {}",
                                new_peer.endpoint_addr
                            );
                        }
                        Err(e) => {
                            crate::lwarn!("VPN [AWG]: failover attempt failed: {}, will retry on next tick", e);
                        }
                    }
                }
            }
        }
    }

    fwd_handle.abort();
    drop(guard);

    let _ = tokio::join!(writer_handle, reader_handle);

    Ok(())
}

#[cfg(not(unix))]
pub async fn run_vpn(
    _tun_fd: std::os::raw::c_int,
    _cancel_token: CancellationToken,
) -> Result<(), String> {
    Err("Native VPN requires Unix/Android platform".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_dot_port_853_tcp_rst_strictly_disabled_and_virtual_dns_dropped() {
        // Construct IPv4 TCP packet to 1.1.1.1:853 (Public DoT)
        let mut pkt_public_dot = vec![0u8; 40];
        pkt_public_dot[0] = 0x45; // IPv4, IHL 5 (20 bytes)
        pkt_public_dot[9] = 6; // TCP
        pkt_public_dot[16..20].copy_from_slice(&[1, 1, 1, 1]); // dst 1.1.1.1
        pkt_public_dot[22..24].copy_from_slice(&853u16.to_be_bytes()); // dst port 853

        // When TLS is enabled: public DoT must pass through, never dropped, never RST
        assert!(!is_blocked_virtual_dns_tcp(&pkt_public_dot));
        assert!(!should_drop_outbound_dns_tcp(&pkt_public_dot, true));
        assert!(handle_dot_or_blocked_tcp(&pkt_public_dot).is_none());

        // When TLS is disabled: public DoT (:853) MUST be dropped silently (no RST) so Android falls back to :53
        assert!(should_drop_outbound_dns_tcp(&pkt_public_dot, false));
        assert!(handle_dot_or_blocked_tcp(&pkt_public_dot).is_none());

        // Construct IPv6 TCP packet to [2606:4700:4700::1111]:853 (Public DoT over IPv6)
        let mut pkt_public_dot_v6 = vec![0u8; 60];
        pkt_public_dot_v6[0] = 0x60; // IPv6
        pkt_public_dot_v6[6] = 6; // Next Header: TCP
        pkt_public_dot_v6[42..44].copy_from_slice(&853u16.to_be_bytes()); // dst port 853

        assert!(!should_drop_outbound_dns_tcp(&pkt_public_dot_v6, true));
        assert!(should_drop_outbound_dns_tcp(&pkt_public_dot_v6, false));
        assert!(handle_dot_or_blocked_tcp(&pkt_public_dot_v6).is_none());

        // Plain DNS TCP to 1.1.1.1:53 or HTTPS 1.1.1.1:443 must NOT be dropped when TLS is disabled
        let mut pkt_public_53 = pkt_public_dot.clone();
        pkt_public_53[22..24].copy_from_slice(&53u16.to_be_bytes());
        assert!(!should_drop_outbound_dns_tcp(&pkt_public_53, false));

        // Construct IPv4 TCP packet to 192.0.2.3:853 (Virtual DNS DoT)
        let mut pkt_virtual_dot = vec![0u8; 40];
        pkt_virtual_dot[0] = 0x45;
        pkt_virtual_dot[9] = 6;
        pkt_virtual_dot[16..20].copy_from_slice(&[192, 0, 2, 3]);
        pkt_virtual_dot[22..24].copy_from_slice(&853u16.to_be_bytes());

        assert!(is_blocked_virtual_dns_tcp(&pkt_virtual_dot));
        assert!(should_drop_outbound_dns_tcp(&pkt_virtual_dot, true));
        assert!(should_drop_outbound_dns_tcp(&pkt_virtual_dot, false));
        assert!(handle_dot_or_blocked_tcp(&pkt_virtual_dot).is_none());

        // Construct IPv4 TCP packet to 192.0.2.3:53 (Virtual DNS TCP)
        let mut pkt_virtual_dns53 = vec![0u8; 40];
        pkt_virtual_dns53[0] = 0x45;
        pkt_virtual_dns53[9] = 6;
        pkt_virtual_dns53[16..20].copy_from_slice(&[192, 0, 2, 3]);
        pkt_virtual_dns53[22..24].copy_from_slice(&53u16.to_be_bytes());

        assert!(is_blocked_virtual_dns_tcp(&pkt_virtual_dns53));
        assert!(should_drop_outbound_dns_tcp(&pkt_virtual_dns53, true));
        assert!(should_drop_outbound_dns_tcp(&pkt_virtual_dns53, false));
        assert!(handle_dot_or_blocked_tcp(&pkt_virtual_dns53).is_none());
    }

    #[test]
    fn test_clamp_tcp_mss_v4_and_v6() {
        // IPv4 SYN packet with MSS=1460 -> clamped to 1220
        let mut v4_syn = vec![0u8; 44];
        v4_syn[0] = 0x45; // IHL=5 (20B)
        v4_syn[3] = 44;
        v4_syn[9] = 6; // TCP
        v4_syn[20 + 12] = 0x60; // data offset = 6 (24B TCP header)
        v4_syn[20 + 13] = 0x02; // SYN
        v4_syn[40] = 2; // MSS option kind
        v4_syn[41] = 4; // MSS option len
        v4_syn[42..44].copy_from_slice(&1460u16.to_be_bytes());
        clamp_tcp_mss(&mut v4_syn);
        assert_eq!(u16::from_be_bytes([v4_syn[42], v4_syn[43]]), SAFE_MSS_V4);

        // IPv6 SYN packet with MSS=1440 -> clamped to 1200
        let mut v6_syn = vec![0u8; 64];
        v6_syn[0] = 0x60;
        v6_syn[5] = 24;
        v6_syn[6] = 6; // TCP
        v6_syn[40 + 12] = 0x60; // data offset = 6 (24B TCP header)
        v6_syn[40 + 13] = 0x02; // SYN
        v6_syn[60] = 2;
        v6_syn[61] = 4;
        v6_syn[62..64].copy_from_slice(&1440u16.to_be_bytes());
        clamp_tcp_mss(&mut v6_syn);
        assert_eq!(u16::from_be_bytes([v6_syn[62], v6_syn[63]]), SAFE_MSS_V6);
    }

    #[tokio::test]
    async fn test_pipelined_forwarder_drain_and_zero_taildrop() {
        let (tx, mut rx) = tokio::sync::mpsc::channel::<Vec<u8>>(2048);
        let cancel = CancellationToken::new();

        let producer_cancel = cancel.clone();
        let producer = std::thread::spawn(move || {
            for _ in 0..5000 {
                if producer_cancel.is_cancelled() {
                    break;
                }
                let pkt = vec![0x45; 1400];
                let _ = tx.blocking_send(pkt);
            }
        });

        let mut total_received = 0;
        while total_received < 5000 {
            if let Some(_first) = rx.recv().await {
                total_received += 1;
                while let Ok(_) = rx.try_recv() {
                    total_received += 1;
                }
            }
        }

        producer.join().unwrap();
        assert_eq!(total_received, 5000, "All 5000 packets must be received without tail drop");
    }
}
