// Mirrly TG Proxy - Native L3 Android VPN Tunnel
// Copyright (C) 2026 R1Xern (Mirrly Dev)
// GNU GPL v3+ <https://www.gnu.org/licenses/>

use once_cell::sync::Lazy;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use tokio_util::sync::CancellationToken;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VpnUplinkMode {
    WarpAwg = 0,
    WarpMasque = 1,
}

impl VpnUplinkMode {
    pub fn from_i32(val: i32) -> Self {
        match val {
            1 => VpnUplinkMode::WarpMasque,
            _ => VpnUplinkMode::WarpAwg,
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            VpnUplinkMode::WarpAwg => "WARP AmneziaWG",
            VpnUplinkMode::WarpMasque => "WARP MASQUE",
        }
    }
}

pub static VPN_UPLINK_MODE: AtomicI32 = AtomicI32::new(0);
pub static IS_VPN_ESTABLISHED: AtomicBool = AtomicBool::new(false);
pub static VPN_RECONNECT_NOTIFY: Lazy<tokio::sync::Notify> = Lazy::new(|| tokio::sync::Notify::new());

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
    crate::linfo!("VPN: active uplink mode switched to {} ({})", mode, VpnUplinkMode::from_i32(mode).as_str());
}

#[cfg(unix)]
#[repr(C)]
struct PollFd {
    fd: std::os::raw::c_int,
    events: std::os::raw::c_short,
    revents: std::os::raw::c_short,
}

#[cfg(unix)]
const POLLIN: std::os::raw::c_short = 0x0001;
#[cfg(unix)]
const POLLERR: std::os::raw::c_short = 0x0008;
#[cfg(unix)]
const POLLHUP: std::os::raw::c_short = 0x0010;
#[cfg(unix)]
const POLLNVAL: std::os::raw::c_short = 0x0020;

#[cfg(unix)]
extern "C" {
    fn poll(fds: *mut PollFd, nfds: std::os::raw::c_ulong, timeout: std::os::raw::c_int) -> std::os::raw::c_int;
}

#[cfg(unix)]
const VIRTUAL_DNS_IPV4: [u8; 4] = [192, 0, 2, 3];

#[cfg(unix)]
struct DnsQueryInfo {
    src_ip: std::net::Ipv4Addr,
    src_port: u16,
    dst_ip: std::net::Ipv4Addr,
    dst_port: u16,
    query: Vec<u8>,
}

#[cfg(unix)]
fn parse_dns_query(pkt: &[u8]) -> Option<DnsQueryInfo> {
    if pkt.len() < 28 {
        return None;
    }
    // Check IPv4 version (0x4)
    if (pkt[0] >> 4) != 4 {
        return None;
    }
    let ihl = ((pkt[0] & 0x0F) as usize) * 4;
    if pkt.len() < ihl + 8 {
        return None;
    }
    // Protocol 17 = UDP
    if pkt[9] != 17 {
        return None;
    }
    let src_ip = std::net::Ipv4Addr::new(pkt[12], pkt[13], pkt[14], pkt[15]);
    let dst_ip = std::net::Ipv4Addr::new(pkt[16], pkt[17], pkt[18], pkt[19]);
    let src_port = u16::from_be_bytes([pkt[ihl], pkt[ihl + 1]]);
    let dst_port = u16::from_be_bytes([pkt[ihl + 2], pkt[ihl + 3]]);

    if dst_port == 53 || dst_ip.octets() == VIRTUAL_DNS_IPV4 {
        let udp_len = u16::from_be_bytes([pkt[ihl + 4], pkt[ihl + 5]]) as usize;
        if udp_len >= 8 && pkt.len() >= ihl + udp_len {
            let query = pkt[ihl + 8..ihl + udp_len].to_vec();
            return Some(DnsQueryInfo {
                src_ip,
                src_port,
                dst_ip,
                dst_port,
                query,
            });
        }
    }
    None
}

#[cfg(unix)]
fn build_dns_response_packet(
    client_ip: std::net::Ipv4Addr,
    client_port: u16,
    dns_server_ip: std::net::Ipv4Addr,
    dns_server_port: u16,
    dns_resp_payload: &[u8],
) -> Vec<u8> {
    let total_len = 20 + 8 + dns_resp_payload.len();
    let mut pkt = vec![0u8; total_len];

    // IPv4 Header
    pkt[0] = 0x45; // Version 4, IHL 5 (20 bytes)
    pkt[1] = 0x00; // DSCP / ECN
    pkt[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
    pkt[4..6].copy_from_slice(&0x1234u16.to_be_bytes()); // ID
    pkt[6..8].copy_from_slice(&0x4000u16.to_be_bytes()); // Don't fragment
    pkt[8] = 64;   // TTL
    pkt[9] = 17;   // Protocol UDP
    pkt[10..12].copy_from_slice(&[0, 0]); // Checksum placeholder
    pkt[12..16].copy_from_slice(&dns_server_ip.octets());
    pkt[16..20].copy_from_slice(&client_ip.octets());

    // IPv4 Header Checksum
    let mut sum: u32 = 0;
    for i in (0..20).step_by(2) {
        sum += u16::from_be_bytes([pkt[i], pkt[i + 1]]) as u32;
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    let ip_csum = !(sum as u16);
    pkt[10..12].copy_from_slice(&ip_csum.to_be_bytes());

    // UDP Header
    let udp_len = (8 + dns_resp_payload.len()) as u16;
    pkt[20..22].copy_from_slice(&dns_server_port.to_be_bytes());
    pkt[22..24].copy_from_slice(&client_port.to_be_bytes());
    pkt[24..26].copy_from_slice(&udp_len.to_be_bytes());
    pkt[26..28].copy_from_slice(&[0, 0]); // Checksum placeholder

    // UDP Payload
    pkt[28..].copy_from_slice(dns_resp_payload);

    // UDP Checksum with IPv4 Pseudo-header (RFC 768 strict calculation)
    let mut udp_sum: u32 = 0;
    udp_sum += u16::from_be_bytes([pkt[12], pkt[13]]) as u32;
    udp_sum += u16::from_be_bytes([pkt[14], pkt[15]]) as u32;
    udp_sum += u16::from_be_bytes([pkt[16], pkt[17]]) as u32;
    udp_sum += u16::from_be_bytes([pkt[18], pkt[19]]) as u32;
    udp_sum += 17u32; // Protocol UDP
    udp_sum += udp_len as u32;
    for i in (20..total_len).step_by(2) {
        if i + 1 < total_len {
            udp_sum += u16::from_be_bytes([pkt[i], pkt[i + 1]]) as u32;
        } else {
            udp_sum += (pkt[i] as u32) << 8;
        }
    }
    while (udp_sum >> 16) != 0 {
        udp_sum = (udp_sum & 0xFFFF) + (udp_sum >> 16);
    }
    let mut udp_csum = !(udp_sum as u16);
    if udp_csum == 0 {
        udp_csum = 0xFFFF; // RFC 768: 0 means send 0xFFFF
    }
    pkt[26..28].copy_from_slice(&udp_csum.to_be_bytes());

    pkt
}

#[cfg(unix)]
fn build_tcp_rst_packet_v4(incoming_ip_pkt: &[u8], ihl: usize) -> Option<Vec<u8>> {
    if incoming_ip_pkt.len() < ihl + 20 {
        return None;
    }
    let src_ip = std::net::Ipv4Addr::new(
        incoming_ip_pkt[12], incoming_ip_pkt[13], incoming_ip_pkt[14], incoming_ip_pkt[15]
    );
    let dst_ip = std::net::Ipv4Addr::new(
        incoming_ip_pkt[16], incoming_ip_pkt[17], incoming_ip_pkt[18], incoming_ip_pkt[19]
    );
    let src_port = u16::from_be_bytes([incoming_ip_pkt[ihl], incoming_ip_pkt[ihl + 1]]);
    let dst_port = u16::from_be_bytes([incoming_ip_pkt[ihl + 2], incoming_ip_pkt[ihl + 3]]);
    let client_seq = u32::from_be_bytes([
        incoming_ip_pkt[ihl + 4], incoming_ip_pkt[ihl + 5], incoming_ip_pkt[ihl + 6], incoming_ip_pkt[ihl + 7]
    ]);
    let client_ack = u32::from_be_bytes([
        incoming_ip_pkt[ihl + 8], incoming_ip_pkt[ihl + 9], incoming_ip_pkt[ihl + 10], incoming_ip_pkt[ihl + 11]
    ]);
    let client_flags = incoming_ip_pkt[ihl + 13];

    let has_ack = (client_flags & 0x10) != 0;
    let has_syn = (client_flags & 0x02) != 0;

    let (rst_seq, rst_ack, rst_flags) = if has_ack {
        (client_ack, 0u32, 0x04u8) // RST
    } else {
        let seg_len = if has_syn { 1 } else { 0 };
        (0u32, client_seq.wrapping_add(seg_len), 0x14u8) // RST | ACK
    };

    let total_len = 20 + 20; // 20 IP + 20 TCP
    let mut pkt = vec![0u8; total_len];

    // IP Header
    pkt[0] = 0x45;
    pkt[1] = 0x00;
    pkt[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
    pkt[4..6].copy_from_slice(&0x5678u16.to_be_bytes());
    pkt[6..8].copy_from_slice(&0x4000u16.to_be_bytes()); // DF
    pkt[8] = 64; // TTL
    pkt[9] = 6;  // Protocol TCP
    pkt[10..12].copy_from_slice(&[0, 0]);
    pkt[12..16].copy_from_slice(&dst_ip.octets()); // Server becomes Src
    pkt[16..20].copy_from_slice(&src_ip.octets()); // Client becomes Dst

    // IPv4 Header Checksum
    let mut sum: u32 = 0;
    for i in (0..20).step_by(2) {
        sum += u16::from_be_bytes([pkt[i], pkt[i + 1]]) as u32;
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    let ip_csum = !(sum as u16);
    pkt[10..12].copy_from_slice(&ip_csum.to_be_bytes());

    // TCP Header
    pkt[20..22].copy_from_slice(&dst_port.to_be_bytes()); // Server port becomes Src
    pkt[22..24].copy_from_slice(&src_port.to_be_bytes()); // Client port becomes Dst
    pkt[24..28].copy_from_slice(&rst_seq.to_be_bytes());
    pkt[28..32].copy_from_slice(&rst_ack.to_be_bytes());
    pkt[32] = 0x50; // Data offset 5 (20 bytes)
    pkt[33] = rst_flags;
    pkt[34..36].copy_from_slice(&0u16.to_be_bytes()); // Window 0
    pkt[36..38].copy_from_slice(&[0, 0]); // Checksum placeholder
    pkt[38..40].copy_from_slice(&0u16.to_be_bytes()); // Urgent pointer 0

    // TCP Checksum with Pseudo-header
    let mut tcp_sum: u32 = 0;
    tcp_sum += u16::from_be_bytes([pkt[12], pkt[13]]) as u32;
    tcp_sum += u16::from_be_bytes([pkt[14], pkt[15]]) as u32;
    tcp_sum += u16::from_be_bytes([pkt[16], pkt[17]]) as u32;
    tcp_sum += u16::from_be_bytes([pkt[18], pkt[19]]) as u32;
    tcp_sum += 6u32; // Protocol TCP
    tcp_sum += 20u32; // TCP length
    for i in (20..40).step_by(2) {
        tcp_sum += u16::from_be_bytes([pkt[i], pkt[i + 1]]) as u32;
    }
    while (tcp_sum >> 16) != 0 {
        tcp_sum = (tcp_sum & 0xFFFF) + (tcp_sum >> 16);
    }
    let tcp_csum = !(tcp_sum as u16);
    pkt[36..38].copy_from_slice(&tcp_csum.to_be_bytes());

    Some(pkt)
}

#[cfg(unix)]
fn build_tcp_rst_packet_v6(incoming_ip_pkt: &[u8]) -> Option<Vec<u8>> {
    if incoming_ip_pkt.len() < 40 + 20 {
        return None;
    }
    let src_port = u16::from_be_bytes([incoming_ip_pkt[40], incoming_ip_pkt[41]]);
    let dst_port = u16::from_be_bytes([incoming_ip_pkt[42], incoming_ip_pkt[43]]);
    let client_seq = u32::from_be_bytes([
        incoming_ip_pkt[44], incoming_ip_pkt[45], incoming_ip_pkt[46], incoming_ip_pkt[47]
    ]);
    let client_ack = u32::from_be_bytes([
        incoming_ip_pkt[48], incoming_ip_pkt[49], incoming_ip_pkt[50], incoming_ip_pkt[51]
    ]);
    let client_flags = incoming_ip_pkt[53];

    let has_ack = (client_flags & 0x10) != 0;
    let has_syn = (client_flags & 0x02) != 0;

    let (rst_seq, rst_ack, rst_flags) = if has_ack {
        (client_ack, 0u32, 0x04u8)
    } else {
        let seg_len = if has_syn { 1 } else { 0 };
        (0u32, client_seq.wrapping_add(seg_len), 0x14u8)
    };

    let total_len = 40 + 20; // 40 IPv6 + 20 TCP
    let mut pkt = vec![0u8; total_len];

    // IPv6 Header
    pkt[0] = 0x60; // Version 6
    pkt[4..6].copy_from_slice(&20u16.to_be_bytes()); // Payload length (20 bytes TCP)
    pkt[6] = 6;  // Next header TCP
    pkt[7] = 64; // Hop limit
    pkt[8..24].copy_from_slice(&incoming_ip_pkt[24..40]); // Server IP becomes Src
    pkt[24..40].copy_from_slice(&incoming_ip_pkt[8..24]); // Client IP becomes Dst

    // TCP Header
    pkt[40..42].copy_from_slice(&dst_port.to_be_bytes());
    pkt[42..44].copy_from_slice(&src_port.to_be_bytes());
    pkt[44..48].copy_from_slice(&rst_seq.to_be_bytes());
    pkt[48..52].copy_from_slice(&rst_ack.to_be_bytes());
    pkt[52] = 0x50; // 20 bytes
    pkt[53] = rst_flags;
    pkt[54..56].copy_from_slice(&0u16.to_be_bytes());
    pkt[56..58].copy_from_slice(&[0, 0]); // Checksum placeholder
    pkt[58..60].copy_from_slice(&0u16.to_be_bytes());

    // TCP Checksum with IPv6 Pseudo-header
    let mut sum: u32 = 0;
    for i in (8..40).step_by(2) {
        sum += u16::from_be_bytes([pkt[i], pkt[i + 1]]) as u32;
    }
    sum += 20u32; // TCP payload length
    sum += 6u32;  // Next header TCP
    for i in (40..60).step_by(2) {
        sum += u16::from_be_bytes([pkt[i], pkt[i + 1]]) as u32;
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    let tcp_csum = !(sum as u16);
    pkt[56..58].copy_from_slice(&tcp_csum.to_be_bytes());

    Some(pkt)
}

#[cfg(unix)]
fn handle_dot_or_blocked_tcp(pkt: &[u8]) -> Option<Vec<u8>> {
    if pkt.is_empty() {
        return None;
    }
    let version = pkt[0] >> 4;
    if version == 4 {
        if pkt.len() < 40 {
            return None;
        }
        let ihl = ((pkt[0] & 0x0F) as usize) * 4;
        if pkt.len() < ihl + 20 || pkt[9] != 6 {
            return None;
        }
        let dst_port = u16::from_be_bytes([pkt[ihl + 2], pkt[ihl + 3]]);
        let dst_ip = std::net::Ipv4Addr::new(pkt[16], pkt[17], pkt[18], pkt[19]);

        // Android Private DNS probes port 853 (DoT) or TCP DNS to virtual DNS
        if dst_port == 853 || (dst_ip.octets() == VIRTUAL_DNS_IPV4 && dst_port == 53) {
            return build_tcp_rst_packet_v4(pkt, ihl);
        }
    } else if version == 6 {
        if pkt.len() < 60 || pkt[6] != 6 {
            return None;
        }
        let dst_port = u16::from_be_bytes([pkt[42], pkt[43]]);
        if dst_port == 853 {
            return build_tcp_rst_packet_v6(pkt);
        }
    }
    None
}

#[cfg(unix)]
const SAFE_MSS_V4: u16 = 1220;
#[cfg(unix)]
const SAFE_MSS_V6: u16 = 1200;

#[cfg(unix)]
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

#[cfg(unix)]
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

#[cfg(unix)]
fn clamp_tcp_mss(pkt: &mut [u8]) {
    if pkt.is_empty() {
        return;
    }
    let version = pkt[0] >> 4;
    if version == 4 {
        if pkt.len() < 40 {
            return;
        }
        let ihl = ((pkt[0] & 0x0F) as usize) * 4;
        if pkt.len() < ihl + 20 || pkt[9] != 6 {
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
    let mut buf = [0u8; 65535];
    let mut pfd = PollFd {
        fd: raw_fd,
        events: POLLIN,
        revents: 0,
    };

    while !cancel_reader.is_cancelled() {
        pfd.revents = 0;
        let ret = unsafe { poll(&mut pfd as *mut PollFd, 1, 150) };
        if ret < 0 {
            let err = std::io::Error::last_os_error();
            if err.raw_os_error() == Some(4) || err.kind() == std::io::ErrorKind::Interrupted {
                continue;
            }
            if !cancel_reader.is_cancelled() {
                crate::lwarn!("VPN [{}]: TUN poll error: {}", tag, err);
            }
            break;
        }
        if ret == 0 {
            // 150ms timeout: re-evaluate cancel_reader.is_cancelled()
            continue;
        }

        if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0 {
            if !cancel_reader.is_cancelled() {
                crate::ldebug!("VPN [{}]: TUN poll revents error/hup/nval: {:#x}", tag, pfd.revents);
            }
            break;
        }

        if (pfd.revents & POLLIN) != 0 {
            match read_file.read(&mut buf) {
                Ok(0) => {
                    crate::linfo!("VPN [{}]: TUN EOF received, stopping reader", tag);
                    break;
                }
                Ok(n) => {
                    let mut pkt = buf[..n].to_vec();

                    // 1. Android Private DNS DoT (port 853) / Virtual DNS TCP: Reject with immediate TCP RST
                    if let Some(rst_pkt) = handle_dot_or_blocked_tcp(&pkt) {
                        let _ = dns_inbound_tx.blocking_send(rst_pkt);
                        continue;
                    }

                    // 2. Cloudflare Virtual DNS Interception (192.0.2.3:53 or port 53 UDP)
                    if let Some(info) = parse_dns_query(&pkt) {
                        let sink = dns_inbound_tx.clone();
                        tokio::spawn(async move {
                            match crate::dns::resolve_doh_wireformat(&info.query).await {
                                Ok(resp_bytes) => {
                                    let resp_pkt = build_dns_response_packet(
                                        info.src_ip,
                                        info.src_port,
                                        info.dst_ip,
                                        info.dst_port,
                                        &resp_bytes,
                                    );
                                    let _ = sink.send(resp_pkt).await;
                                }
                                Err(e) => {
                                    crate::ldebug!("VPN [DNS]: DoH wireformat resolve error: {}", e);
                                }
                            }
                        });
                    } else {
                        // 3. MSS Clamping on all outbound TCP SYN packets to eliminate cellular fragmentation
                        clamp_tcp_mss(&mut pkt);
                        if outbound_tx.blocking_send(pkt).is_err() {
                            break;
                        }
                    }
                }
                Err(e) => {
                    if e.kind() == std::io::ErrorKind::Interrupted {
                        continue;
                    }
                    if !cancel_reader.is_cancelled() {
                        crate::lwarn!("VPN [{}]: TUN read error: {}", tag, e);
                    }
                    break;
                }
            }
        }
    }

    // Explicitly drop read_file here, closing the native duped FD cleanly!
    drop(read_file);
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
        "VPN: initializing native L3 TUN engine (tun_fd={}, uplink={})",
        tun_fd,
        mode.as_str()
    );

    extern "C" {
        fn dup(fd: std::os::raw::c_int) -> std::os::raw::c_int;
    }

    let native_tun_fd = unsafe { dup(tun_fd) };
    if native_tun_fd < 0 {
        return Err(format!("failed to dup tun_fd: {}", std::io::Error::last_os_error()));
    }

    IS_VPN_ESTABLISHED.store(false, Ordering::SeqCst);

    let read_file = unsafe { File::from_raw_fd(native_tun_fd) };
    let write_file = read_file
        .try_clone()
        .map_err(|e| format!("failed to clone tun_fd: {}", e))?;

    let res = match mode {
        VpnUplinkMode::WarpAwg => run_awg_vpn(read_file, write_file, cancel_token).await,
        VpnUplinkMode::WarpMasque => run_masque_vpn(read_file, write_file, cancel_token).await,
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
    let peer = crate::awg::get_or_connect_peer(&cancel_token)
        .await
        .map_err(|e| format!("AWG connect failed: {}", e))?;

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

    // 1. Inbound channel: decrypted IP packets from WireGuard + virtual DNS responses -> written to TUN interface
    let (inbound_tx, mut inbound_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(2048);
    peer.set_tun_sink(Some(inbound_tx.clone()));

    let cancel_writer = cancel_token.clone();
    let writer_handle = tokio::task::spawn_blocking(move || {
        use std::io::Write;
        while !cancel_writer.is_cancelled() {
            match inbound_rx.blocking_recv() {
                Some(pkt) => {
                    crate::STATS.add_vpn_bytes_down(pkt.len() as i64);
                    if let Err(e) = write_file.write_all(&pkt) {
                        if !cancel_writer.is_cancelled() {
                            crate::lwarn!("VPN [AWG]: TUN write error: {}", e);
                        }
                        break;
                    }
                }
                None => break,
            }
        }
        drop(write_file);
    });

    // 2. Outbound forwarder: raw IP packets read from TUN -> encapsulated & sent via protected UDP
    let (outbound_tx, mut outbound_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(2048);
    let cancel_fwd = cancel_token.clone();
    let peer_holder = Arc::new(parking_lot::RwLock::new(Arc::clone(&peer)));
    let peer_holder_fwd = Arc::clone(&peer_holder);
    let fwd_handle = tokio::spawn(async move {
        while !cancel_fwd.is_cancelled() {
            tokio::select! {
                _ = cancel_fwd.cancelled() => break,
                Some(pkt) = outbound_rx.recv() => {
                    crate::STATS.add_vpn_bytes_up(pkt.len() as i64);
                    let current_peer = peer_holder_fwd.read().clone();
                    if let Err(e) = current_peer.send_ip_packet(&pkt).await {
                        crate::ldebug!("VPN: send_ip_packet error: {}", e);
                    }
                }
            }
        }
    });

    // 3. Outbound reader: cancellable poll-based read from TUN in dedicated OS thread with virtual DNS interception
    let cancel_reader = cancel_token.clone();
    let dns_inbound_tx = inbound_tx.clone();
    let reader_handle = tokio::task::spawn_blocking(move || {
        read_tun_loop(read_file, outbound_tx, dns_inbound_tx, cancel_reader, "AWG");
    });

    // 4. Watchdog & failover supervisor loop: periodically monitor peer health & reconnect on network shift
    let mut watchdog_interval = tokio::time::interval(std::time::Duration::from_secs(2));
    loop {
        tokio::select! {
            _ = cancel_token.cancelled() => break,
            _ = crate::vpn::VPN_RECONNECT_NOTIFY.notified() => {
                crate::linfo!("VPN [AWG]: received explicit VPN socket reconnect notification (network handover / reset)");
                let current_peer = peer_holder.read().clone();
                crate::awg::invalidate_active_peer();
                match crate::awg::get_or_connect_peer(&cancel_token).await {
                    Ok(new_peer) => {
                        current_peer.set_tun_sink(None);
                        new_peer.set_tun_sink(Some(inbound_tx.clone()));
                        *peer_holder.write() = Arc::clone(&new_peer);
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
                    crate::lwarn!(
                        "VPN [AWG]: active peer {} is cancelled or unhealthy (handshake/NAT loss); acquiring Anycast fallback...",
                        current_peer.endpoint_addr
                    );
                    crate::awg::invalidate_active_peer();
                    match crate::awg::get_or_connect_peer(&cancel_token).await {
                        Ok(new_peer) => {
                            current_peer.set_tun_sink(None);
                            new_peer.set_tun_sink(Some(inbound_tx.clone()));
                            *peer_holder.write() = Arc::clone(&new_peer);
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

    let final_peer = peer_holder.read().clone();
    final_peer.set_tun_sink(None);
    fwd_handle.abort();
    let _ = writer_handle.await;
    let _ = reader_handle.await;

    Ok(())
}

#[cfg(unix)]
async fn run_masque_vpn(
    read_file: std::fs::File,
    mut write_file: std::fs::File,
    cancel_token: CancellationToken,
) -> Result<(), String> {

    crate::linfo!("VPN [MASQUE]: acquiring Cloudflare WARP MASQUE tunnel...");
    let tunnel = match crate::masque::masque_acquire_tunnel("", &cancel_token).await {
        Some(t) => t,
        None => {
            return Err("failed to acquire Cloudflare WARP MASQUE tunnel (check credentials/Anycast)".to_string());
        }
    };

    IS_VPN_ESTABLISHED.store(true, Ordering::SeqCst);

    crate::linfo!(
        "VPN [MASQUE]: connected to Cloudflare Anycast {}, quarter_stream_id={}",
        tunnel.endpoint_addr,
        tunnel.quarter_stream_id
    );

    let tunnel_arc = Arc::new(tunnel);

    let (inbound_tx, mut inbound_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(2048);

    // 1. Inbound writer: packets from inbound_tx (MASQUE datagrams + virtual DNS responses) -> write to TUN
    let cancel_writer = cancel_token.clone();
    let writer_handle = tokio::task::spawn_blocking(move || {
        use std::io::Write;
        while !cancel_writer.is_cancelled() {
            match inbound_rx.blocking_recv() {
                Some(pkt) => {
                    crate::STATS.add_vpn_bytes_down(pkt.len() as i64);
                    if let Err(e) = write_file.write_all(&pkt) {
                        if !cancel_writer.is_cancelled() {
                            crate::lwarn!("VPN [MASQUE]: TUN write error: {}", e);
                        }
                        break;
                    }
                }
                None => break,
            }
        }
        drop(write_file);
    });

    // 1b. Inbound datagram receiver from Cloudflare HTTP/3
    let tunnel_in = Arc::clone(&tunnel_arc);
    let cancel_in = cancel_token.clone();
    let in_tx_fwd = inbound_tx.clone();
    let in_handle = tokio::spawn(async move {
        let mut dgram_rx = tunnel_in.dgram_rx.lock().await;
        while !cancel_in.is_cancelled() {
            tokio::select! {
                _ = cancel_in.cancelled() => break,
                res = dgram_rx.recv() => {
                    match res {
                        Some(ip_pkt) => {
                            if in_tx_fwd.send(ip_pkt.to_vec()).await.is_err() {
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

    // 3. Reader from TUN fd with virtual DNS interception
    let cancel_read = cancel_token.clone();
    let dns_inbound_tx = inbound_tx.clone();
    let read_handle = tokio::task::spawn_blocking(move || {
        read_tun_loop(read_file, out_tx, dns_inbound_tx, cancel_read, "MASQUE");
    });

    cancel_token.cancelled().await;
    in_handle.abort();
    out_handle.abort();
    let _ = writer_handle.await;
    let _ = read_handle.await;
    tunnel_arc.close().await;
    IS_VPN_ESTABLISHED.store(false, Ordering::SeqCst);
    Ok(())
}

#[cfg(not(unix))]
pub async fn run_vpn(
    _tun_fd: std::os::raw::c_int,
    _cancel_token: CancellationToken,
) -> Result<(), String> {
    Err("Native VPN requires Unix/Android platform".to_string())
}
