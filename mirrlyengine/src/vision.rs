use rand::Rng;

pub const VISION_FLOW: &str = "xtls-rprx-vision";

pub const CMD_PADDING_CONTINUE: u8 = 0x00;
pub const CMD_PADDING_END: u8 = 0x01;
pub const CMD_PADDING_DIRECT: u8 = 0x02;

pub const TLS_APPLICATION_DATA_START: &[u8] = &[0x17, 0x03, 0x03];
pub const TLS_CLIENT_HELLO_START: &[u8] = &[0x16, 0x03];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct VisionContext {
    pub uuid: [u8; 16],
}

/// Encodes VLESS Addons protobuf message for flow xtls-rprx-vision.
/// Protobuf message definition:
///   message Addons {
///     string Flow = 1; // wire type 2 (length-delimited) -> tag 0x0a
///     bytes Seed = 2;  // wire type 2 (length-delimited) -> tag 0x12
///   }
pub fn encode_vless_addons(flow: &str) -> Vec<u8> {
    let trimmed = flow.trim();
    if trimmed.eq_ignore_ascii_case(VISION_FLOW) || trimmed.starts_with(VISION_FLOW) {
        let flow_bytes = VISION_FLOW.as_bytes();
        let mut pb = Vec::with_capacity(2 + flow_bytes.len());
        // Field 1 tag: (1 << 3) | 2 = 0x0a
        pb.push(0x0a);
        pb.push(flow_bytes.len() as u8);
        pb.extend_from_slice(flow_bytes);
        pb
    } else {
        Vec::new()
    }
}

pub fn is_tls_client_hello(buf: &[u8]) -> bool {
    if buf.len() < 6 {
        return false;
    }
    buf[0] == 0x16 && buf[1] == 0x03 && buf[5] == 0x01
}

pub fn is_tls_application_data(buf: &[u8]) -> bool {
    if buf.len() < 3 {
        return false;
    }
    buf.starts_with(TLS_APPLICATION_DATA_START)
}

pub fn is_complete_tls_record(buf: &[u8]) -> bool {
    let mut offset = 0;
    while offset + 5 <= buf.len() {
        let record_type = buf[offset];
        if record_type != 0x14 && record_type != 0x15 && record_type != 0x16 && record_type != 0x17
        {
            return false;
        }
        let record_len = ((buf[offset + 3] as usize) << 8) | (buf[offset + 4] as usize);
        offset += 5 + record_len;
    }
    offset == buf.len()
}

/// Maximum payload chunk size for a single Vision frame (16 KiB).
pub const MAX_VISION_PAYLOAD_CHUNK: usize = 16384;

/// Maximum allowed single Vision frame length (header + content + padding).
pub const MAX_VISION_FRAME_LEN: usize = 65536 + 1024;

/// Maximum memory buffer limit for VisionUnpadder stream reassembly (256 KiB).
pub const MAX_UNPAD_BUFFER_LIMIT: usize = 256 * 1024;

/// Packs a single Vision frame (content length must fit in u16, ideally <= MAX_VISION_PAYLOAD_CHUNK).
/// Frame wire layout:
///   [Optional 16-byte UUID (first frame only)]
///   [1-byte command (0x00=continue, 0x01=end, 0x02=direct)]
///   [2-byte content length (big-endian)]
///   [2-byte padding length (big-endian)]
///   [content bytes]
///   [padding bytes]
pub fn pack_single_vision_frame(
    payload: &[u8],
    command: u8,
    uuid: Option<&[u8; 16]>,
    long_padding: bool,
) -> Vec<u8> {
    let content_len = payload.len();
    assert!(
        content_len <= u16::MAX as usize,
        "Vision single frame payload length {} exceeds u16::MAX",
        content_len
    );
    let mut rng = rand::thread_rng();

    let padding_len = if long_padding && content_len < 900 {
        let random_add: usize = rng.gen_range(0..500);
        let target = 900 + random_add;
        if target > content_len {
            target - content_len
        } else {
            0
        }
    } else {
        rng.gen_range(0..256)
    };

    let prefix_len = if uuid.is_some() { 16 } else { 0 };
    // Ensure total frame doesn't exceed 16384 bytes if content fits
    let max_padding = 16384usize.saturating_sub(prefix_len + 5 + content_len);
    let padding_len = padding_len.min(max_padding);

    let mut frame = Vec::with_capacity(prefix_len + 5 + content_len + padding_len);

    if let Some(id) = uuid {
        frame.extend_from_slice(id);
    }

    frame.push(command);
    frame.extend_from_slice(&(content_len as u16).to_be_bytes());
    frame.extend_from_slice(&(padding_len as u16).to_be_bytes());
    frame.extend_from_slice(payload);

    if padding_len > 0 {
        let start = frame.len();
        frame.resize(start + padding_len, 0);
        rng.fill(&mut frame[start..]);
    }

    frame
}

/// Packs arbitrary length payload into valid Vision frame(s).
/// If payload.len() <= MAX_VISION_PAYLOAD_CHUNK (16 KiB), a single frame is generated.
/// If payload.len() > MAX_VISION_PAYLOAD_CHUNK, the payload is chunked into frames of at most
/// MAX_VISION_PAYLOAD_CHUNK bytes each.
/// The UUID prefix (if provided) is attached only to the first frame.
/// Intermediate chunk frames use CMD_PADDING_CONTINUE so unpadders do not switch to direct
/// mode prematurely. The final chunk frame retains the caller's target command (e.g. CMD_PADDING_END
/// or CMD_PADDING_DIRECT).
pub fn pack_vision_frame(
    payload: &[u8],
    command: u8,
    uuid: Option<&[u8; 16]>,
    long_padding: bool,
) -> Vec<u8> {
    if payload.len() <= MAX_VISION_PAYLOAD_CHUNK {
        return pack_single_vision_frame(payload, command, uuid, long_padding);
    }

    let num_chunks = (payload.len() + MAX_VISION_PAYLOAD_CHUNK - 1) / MAX_VISION_PAYLOAD_CHUNK;
    let mut result = Vec::with_capacity(payload.len() + num_chunks * 32);

    for (idx, chunk) in payload.chunks(MAX_VISION_PAYLOAD_CHUNK).enumerate() {
        let is_first = idx == 0;
        let is_last = idx == num_chunks - 1;

        let chunk_uuid = if is_first { uuid } else { None };
        let chunk_cmd = if is_last {
            command
        } else {
            CMD_PADDING_CONTINUE
        };
        let chunk_long_padding = if is_first { long_padding } else { false };

        let frame = pack_single_vision_frame(chunk, chunk_cmd, chunk_uuid, chunk_long_padding);
        result.extend_from_slice(&frame);
    }

    result
}

/// Inner TLS / payload classification and state machine for XTLS Vision uplink stream.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VisionStreamState {
    /// Initial inspection: buffering bytes until enough data (at least 5 bytes, or conclusive 1-3 bytes)
    /// is available to determine if the stream is TLS or non-TLS.
    Inspecting,
    /// Non-TLS payload detected (MTProto, HTTP, SOCKS5, etc.).
    /// Initial frame is emitted with CMD_PADDING_END and transitions immediately to Direct mode.
    NonTls,
    /// Inner TLS detected: currently in handshake phase (ClientHello, ChangeCipherSpec, etc.).
    /// Handshake records are packed with CMD_PADDING_CONTINUE and padding.
    TlsHandshake,
    /// Direct mode achieved: either after CMD_PADDING_DIRECT on first inner TLS ApplicationData record,
    /// or after CMD_PADDING_END for non-TLS payload. All subsequent payload passes through raw.
    Direct,
}

/// Checks whether a byte buffer conclusively cannot be the start of a TLS record.
/// A valid TLS record must begin with ContentType in 0x14..=0x17 and ProtocolVersion 0x03, 0x00..=0x04.
pub fn is_definitely_non_tls(buf: &[u8]) -> bool {
    if buf.is_empty() {
        return false;
    }
    let content_type = buf[0];
    if content_type != 0x14 && content_type != 0x15 && content_type != 0x16 && content_type != 0x17 {
        return true;
    }
    if buf.len() >= 2 && buf[1] != 0x03 {
        return true;
    }
    if buf.len() >= 3 && buf[2] > 0x04 {
        return true;
    }
    false
}

/// Checks whether a buffer starts with a valid TLS record header (at least 5 bytes).
/// Returns Some((content_type, record_payload_len)) if valid TLS header, or None.
pub fn parse_tls_record_header(buf: &[u8]) -> Option<(u8, usize)> {
    if buf.len() < 5 {
        return None;
    }
    let content_type = buf[0];
    if content_type != 0x14 && content_type != 0x15 && content_type != 0x16 && content_type != 0x17 {
        return None;
    }
    if buf[1] != 0x03 || buf[2] > 0x04 {
        return None;
    }
    let record_len = ((buf[3] as usize) << 8) | (buf[4] as usize);
    Some((content_type, record_len))
}

/// Uplink Vision framer managing the state machine for client -> server traffic.
pub struct VisionFramer {
    uuid: [u8; 16],
    is_first_frame: bool,
    state: VisionStreamState,
    tx_buf: Vec<u8>,
}

impl VisionFramer {
    pub fn new(uuid: [u8; 16]) -> Self {
        Self {
            uuid,
            is_first_frame: true,
            state: VisionStreamState::Inspecting,
            tx_buf: Vec::with_capacity(4096),
        }
    }

    pub fn is_direct(&self) -> bool {
        self.state == VisionStreamState::Direct
    }

    pub fn state(&self) -> VisionStreamState {
        self.state
    }

    /// Feeds payload into the Vision state machine, producing framed bytes to write to the upstream connection.
    /// In Direct mode, returns payload directly (plus any drained buffer).
    pub fn frame(&mut self, payload: &[u8]) -> Vec<u8> {
        if self.state == VisionStreamState::Direct {
            if self.tx_buf.is_empty() {
                return payload.to_vec();
            } else {
                let mut out = std::mem::take(&mut self.tx_buf);
                out.extend_from_slice(payload);
                return out;
            }
        }

        // Guard against memory exhaustion / DOS on unparseable streams
        if self.tx_buf.len() + payload.len() > MAX_UNPAD_BUFFER_LIMIT {
            // Buffer overflow: flush as non-TLS direct and clear
            self.tx_buf.extend_from_slice(payload);
            let uuid_opt = if self.is_first_frame {
                self.is_first_frame = false;
                Some(&self.uuid)
            } else {
                None
            };
            let frame = pack_vision_frame(&self.tx_buf, CMD_PADDING_END, uuid_opt, false);
            self.tx_buf.clear();
            self.state = VisionStreamState::Direct;
            return frame;
        }

        self.tx_buf.extend_from_slice(payload);
        let mut output = Vec::new();

        // 1. Inspecting phase: determine TLS vs Non-TLS
        if self.state == VisionStreamState::Inspecting {
            if is_definitely_non_tls(&self.tx_buf) {
                self.state = VisionStreamState::NonTls;
            } else if self.tx_buf.len() >= 5 {
                if let Some((_, _)) = parse_tls_record_header(&self.tx_buf) {
                    self.state = VisionStreamState::TlsHandshake;
                } else {
                    self.state = VisionStreamState::NonTls;
                }
            } else {
                // Not enough bytes to confirm TLS yet, wait for more data
                return output;
            }
        }

        // 2. Non-TLS handling (e.g. MTProto, HTTP, SOCKS5 data)
        // XTLS Vision specification: send initial payload in frame with CMD_PADDING_END, then switch to Direct
        if self.state == VisionStreamState::NonTls {
            let uuid_opt = if self.is_first_frame {
                self.is_first_frame = false;
                Some(&self.uuid)
            } else {
                None
            };
            let frame = pack_vision_frame(&self.tx_buf, CMD_PADDING_END, uuid_opt, false);
            output.extend_from_slice(&frame);
            self.tx_buf.clear();
            self.state = VisionStreamState::Direct;
            return output;
        }

        // 3. TLS Handshake & Transition handling
        if self.state == VisionStreamState::TlsHandshake {
            loop {
                if self.tx_buf.len() < 5 {
                    break;
                }

                let (content_type, record_len) = match parse_tls_record_header(&self.tx_buf) {
                    Some(hdr) => hdr,
                    None => {
                        // Desynchronization or non-TLS record in TLS stream: fallback to Direct transition
                        let uuid_opt = if self.is_first_frame {
                            self.is_first_frame = false;
                            Some(&self.uuid)
                        } else {
                            None
                        };
                        let frame = pack_vision_frame(&self.tx_buf, CMD_PADDING_END, uuid_opt, false);
                        output.extend_from_slice(&frame);
                        self.tx_buf.clear();
                        self.state = VisionStreamState::Direct;
                        return output;
                    }
                };

                let total_record_len = 5 + record_len;

                // Wait for the full TLS record to accumulate in buffer
                if self.tx_buf.len() < total_record_len {
                    break;
                }

                let record = &self.tx_buf[..total_record_len];

                if content_type == 0x17 {
                    // ApplicationData record: Handshake is complete!
                    // Trigger XTLS Vision Direct transition with CMD_PADDING_DIRECT.
                    let uuid_opt = if self.is_first_frame {
                        self.is_first_frame = false;
                        Some(&self.uuid)
                    } else {
                        None
                    };
                    let frame = pack_vision_frame(record, CMD_PADDING_DIRECT, uuid_opt, true);
                    output.extend_from_slice(&frame);

                    self.tx_buf.drain(..total_record_len);
                    self.state = VisionStreamState::Direct;

                    // CRITICAL: Any remaining bytes in tx_buf are direct application data
                    // and MUST NOT be framed again! Append them directly as raw bytes.
                    if !self.tx_buf.is_empty() {
                        output.extend_from_slice(&self.tx_buf);
                        self.tx_buf.clear();
                    }
                    break;
                } else {
                    // Handshake (0x16), ChangeCipherSpec (0x14), or Alert (0x15)
                    let uuid_opt = if self.is_first_frame {
                        self.is_first_frame = false;
                        Some(&self.uuid)
                    } else {
                        None
                    };
                    let long_padding = content_type == 0x16; // Long padding on ClientHello
                    let frame = pack_vision_frame(record, CMD_PADDING_CONTINUE, uuid_opt, long_padding);
                    output.extend_from_slice(&frame);

                    self.tx_buf.drain(..total_record_len);
                }
            }
        }

        output
    }

    /// Flushes any pending buffered bytes before connection close.
    pub fn finish(&mut self) -> Vec<u8> {
        if self.tx_buf.is_empty() {
            return Vec::new();
        }

        if self.state == VisionStreamState::Direct {
            return std::mem::take(&mut self.tx_buf);
        }

        let uuid_opt = if self.is_first_frame {
            self.is_first_frame = false;
            Some(&self.uuid)
        } else {
            None
        };
        let frame = pack_vision_frame(&self.tx_buf, CMD_PADDING_END, uuid_opt, false);
        self.tx_buf.clear();
        self.state = VisionStreamState::Direct;
        frame
    }
}

/// Policy defining whether the first frame of a Vision stream must, may, or must not have a 16-byte UUID prefix.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VisionUuidPolicy {
    /// Auto-detect: frame may contain a 16-byte UUID prefix, or may omit it (standard server downlink).
    /// If the incoming buffer matches the UUID prefix, it holds until the full 16 bytes arrive.
    /// If the incoming buffer diverges from the UUID prefix, it proceeds as a frame without UUID
    /// provided the header contains a valid Vision command.
    Auto,
    /// Downlink frame does not contain a UUID prefix (standard VLESS Vision server -> client stream).
    NoUuid,
    /// Frame strictly requires a 16-byte UUID prefix.
    RequireUuid,
}

#[inline]
pub fn is_valid_vision_command(command: u8) -> bool {
    command == CMD_PADDING_CONTINUE || command == CMD_PADDING_END || command == CMD_PADDING_DIRECT
}

/// Downlink Vision unpadder managing stream reassembly and unpadding for server -> client traffic.
pub struct VisionUnpadder {
    uuid: [u8; 16],
    policy: VisionUuidPolicy,
    is_first_frame: bool,
    is_direct: bool,
    rx_buf: Vec<u8>,
}

impl VisionUnpadder {
    pub fn new(uuid: [u8; 16]) -> Self {
        Self::with_policy(uuid, VisionUuidPolicy::Auto)
    }

    pub fn with_policy(uuid: [u8; 16], policy: VisionUuidPolicy) -> Self {
        Self {
            uuid,
            policy,
            is_first_frame: true,
            is_direct: false,
            rx_buf: Vec::with_capacity(4096),
        }
    }

    pub fn new_no_uuid() -> Self {
        Self::with_policy([0u8; 16], VisionUuidPolicy::NoUuid)
    }

    pub fn new_require_uuid(uuid: [u8; 16]) -> Self {
        Self::with_policy(uuid, VisionUuidPolicy::RequireUuid)
    }

    pub fn policy(&self) -> VisionUuidPolicy {
        self.policy
    }

    pub fn set_policy(&mut self, policy: VisionUuidPolicy) {
        self.policy = policy;
    }

    pub fn is_direct(&self) -> bool {
        self.is_direct
    }

    pub fn unpad(&mut self, data: &[u8]) -> Vec<u8> {
        if self.is_direct {
            if self.rx_buf.is_empty() {
                return data.to_vec();
            } else {
                let mut out = std::mem::take(&mut self.rx_buf);
                out.extend_from_slice(data);
                return out;
            }
        }

        if self.rx_buf.len() + data.len() > MAX_UNPAD_BUFFER_LIMIT {
            self.rx_buf.clear();
            if data.len() > MAX_UNPAD_BUFFER_LIMIT {
                return Vec::new();
            }
        }

        self.rx_buf.extend_from_slice(data);
        let mut output = Vec::new();

        loop {
            if self.rx_buf.len() > MAX_UNPAD_BUFFER_LIMIT {
                self.rx_buf.clear();
                break;
            }

            if self.is_direct {
                if !self.rx_buf.is_empty() {
                    output.extend_from_slice(&self.rx_buf);
                    self.rx_buf.clear();
                }
                break;
            }

            if self.is_first_frame {
                match self.policy {
                    VisionUuidPolicy::RequireUuid => {
                        if self.rx_buf.len() < 16 {
                            if self.uuid.starts_with(&self.rx_buf) {
                                break;
                            } else {
                                self.rx_buf.clear();
                                break;
                            }
                        }
                        if self.rx_buf.starts_with(&self.uuid) {
                            self.rx_buf.drain(..16);
                            self.is_first_frame = false;
                        } else {
                            self.rx_buf.clear();
                            break;
                        }
                    }
                    VisionUuidPolicy::NoUuid => {
                        if self.rx_buf.len() < 5 {
                            break;
                        }
                        self.is_first_frame = false;
                    }
                    VisionUuidPolicy::Auto => {
                        if self.rx_buf.len() < 16 {
                            if self.uuid.starts_with(&self.rx_buf) {
                                // Buffer matches prefix of UUID: hold until full 16 bytes arrive
                                break;
                            }
                            // Buffer diverges from UUID prefix: only permissible as a frame without UUID
                            if self.rx_buf.len() < 5 {
                                break;
                            }
                            if is_valid_vision_command(self.rx_buf[0]) {
                                self.is_first_frame = false;
                            } else {
                                break;
                            }
                        } else {
                            // Buffer has at least 16 bytes
                            if self.rx_buf.starts_with(&self.uuid) {
                                self.rx_buf.drain(..16);
                                self.is_first_frame = false;
                            } else if is_valid_vision_command(self.rx_buf[0]) {
                                self.is_first_frame = false;
                            } else {
                                break;
                            }
                        }
                    }
                }
            }

            // Need at least 5 bytes for Vision frame header
            if self.rx_buf.len() < 5 {
                break;
            }

            let command = self.rx_buf[0];
            if !is_valid_vision_command(command) {
                break;
            }

            let content_len = u16::from_be_bytes([self.rx_buf[1], self.rx_buf[2]]) as usize;
            let padding_len = u16::from_be_bytes([self.rx_buf[3], self.rx_buf[4]]) as usize;
            let total_frame_len = 5 + content_len + padding_len;

            if total_frame_len > MAX_VISION_FRAME_LEN {
                // Desynchronization or invalid frame length
                self.rx_buf.clear();
                break;
            }

            if self.rx_buf.len() < total_frame_len {
                // Incomplete frame in buffer, wait for more data from stream
                break;
            }

            // Extract content
            if content_len > 0 {
                output.extend_from_slice(&self.rx_buf[5..5 + content_len]);
            }

            // Consume frame from buffer
            self.rx_buf.drain(..total_frame_len);

            if command == CMD_PADDING_END || command == CMD_PADDING_DIRECT {
                self.is_direct = true;
                if !self.rx_buf.is_empty() {
                    output.extend_from_slice(&self.rx_buf);
                    self.rx_buf.clear();
                }
                break;
            }
        }

        output
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_encode_vless_addons() {
        let addons = encode_vless_addons("xtls-rprx-vision");
        assert_eq!(addons.len(), 18);
        assert_eq!(addons[0], 0x0a); // Field 1 tag
        assert_eq!(addons[1], 16); // String length
        assert_eq!(&addons[2..], b"xtls-rprx-vision");

        let addons_none = encode_vless_addons("none");
        assert!(addons_none.is_empty());

        let addons_empty = encode_vless_addons("");
        assert!(addons_empty.is_empty());
    }

    #[test]
    fn test_tls_detection() {
        let client_hello = [0x16, 0x03, 0x01, 0x00, 0x50, 0x01, 0x00];
        assert!(is_tls_client_hello(&client_hello));

        let app_data = [0x17, 0x03, 0x03, 0x00, 0x20];
        assert!(is_tls_application_data(&app_data));

        let mtproto = [0xef, 0x00, 0x00, 0x00];
        assert!(!is_tls_client_hello(&mtproto));
        assert!(!is_tls_application_data(&mtproto));
    }

    #[test]
    fn test_pack_and_unpack_single_frame() {
        let uuid = [0x42; 16];
        let original_data = b"Hello VLESS Vision!";

        let packed = pack_vision_frame(original_data, CMD_PADDING_END, Some(&uuid), false);
        assert!(packed.len() >= 16 + 5 + original_data.len());
        assert_eq!(&packed[..16], &uuid);
        assert_eq!(packed[16], CMD_PADDING_END);

        let mut unpadder = VisionUnpadder::new(uuid);
        let unpadded = unpadder.unpad(&packed);

        assert_eq!(unpadded, original_data);
        assert!(unpadder.is_direct());
    }

    #[test]
    fn test_pack_and_unpack_streaming_chunks() {
        let uuid = [0x77; 16];
        let original_data = b"Multi-fragment streaming test over TCP socket";

        let packed = pack_vision_frame(original_data, CMD_PADDING_DIRECT, Some(&uuid), true);

        let mut unpadder = VisionUnpadder::new(uuid);
        let mut recovered = Vec::new();

        // Feed data 7 bytes at a time
        for chunk in packed.chunks(7) {
            let part = unpadder.unpad(chunk);
            recovered.extend_from_slice(&part);
        }

        assert_eq!(recovered, original_data);
        assert!(unpadder.is_direct());
    }

    #[test]
    fn test_framer_non_tls_flow() {
        let uuid = [0x99; 16];
        let mut framer = VisionFramer::new(uuid);
        let mtproto_msg = b"telegram mtproto payload";

        let frame1 = framer.frame(mtproto_msg);
        assert!(framer.is_direct());
        assert_eq!(&frame1[..16], &uuid);
        assert_eq!(frame1[16], CMD_PADDING_END);

        // Subsequent write in direct mode should return raw payload
        let subsequent = b"next direct packet";
        let frame2 = framer.frame(subsequent);
        assert_eq!(frame2, subsequent);
    }

    #[test]
    fn test_framer_tls_flow() {
        let uuid = [0xaa; 16];
        let mut framer = VisionFramer::new(uuid);

        // 1. ClientHello (5 bytes header + 5 bytes payload = 10 bytes)
        let ch = [0x16, 0x03, 0x01, 0x00, 0x05, 0x01, 0x00, 0x00, 0x00, 0x00];
        let frame1 = framer.frame(&ch);
        assert!(!framer.is_direct());
        assert_eq!(&frame1[..16], &uuid);
        assert_eq!(frame1[16], CMD_PADDING_CONTINUE);

        // 2. Application Data
        let app_data = [0x17, 0x03, 0x03, 0x00, 0x05, 0x01, 0x02, 0x03, 0x04, 0x05];
        let frame2 = framer.frame(&app_data);
        assert!(framer.is_direct());
        // Second frame does NOT have UUID prefix
        assert_eq!(frame2[0], CMD_PADDING_DIRECT);

        // 3. Raw direct after direct transition
        let raw_data = b"raw direct data";
        let frame3 = framer.frame(raw_data);
        assert_eq!(frame3, raw_data);
    }

    #[test]
    fn test_all_chunk_splits_with_uuid() {
        let uuid = [0x55; 16];
        let original_data = b"Testing Vision split chunks with UUID: 1, 4, 5, 8, 15, 16 bytes!";
        let packed = pack_vision_frame(original_data, CMD_PADDING_DIRECT, Some(&uuid), true);

        // Required split sizes from acceptance criteria: 1, 4, 5, 8, 15, 16
        for &chunk_size in &[1, 4, 5, 8, 15, 16] {
            let mut unpadder = VisionUnpadder::new(uuid);
            let mut recovered = Vec::new();

            for chunk in packed.chunks(chunk_size) {
                let part = unpadder.unpad(chunk);
                recovered.extend_from_slice(&part);
            }

            assert_eq!(
                recovered, original_data,
                "Failed to recover original data for chunk size with UUID: {}",
                chunk_size
            );
            assert!(unpadder.is_direct());
        }
    }

    #[test]
    fn test_all_chunk_splits_without_uuid() {
        let uuid = [0x66; 16];
        let original_data = b"Testing Vision split chunks WITHOUT UUID: 1, 4, 5, 8, 15, 16 bytes!";
        let packed = pack_vision_frame(original_data, CMD_PADDING_END, None, true);

        // Required split sizes from acceptance criteria: 1, 4, 5, 8, 15, 16
        for &chunk_size in &[1, 4, 5, 8, 15, 16] {
            let mut unpadder = VisionUnpadder::new(uuid);
            let mut recovered = Vec::new();

            for chunk in packed.chunks(chunk_size) {
                let part = unpadder.unpad(chunk);
                recovered.extend_from_slice(&part);
            }

            assert_eq!(
                recovered, original_data,
                "Failed to recover original data for chunk size without UUID: {}",
                chunk_size
            );
            assert!(unpadder.is_direct());
        }
    }

    #[test]
    fn test_chunk_5_specifically_does_not_misinterpret_uuid_prefix() {
        let uuid = [0x33; 16];
        let payload = b"Chunk-5 UUID holding verification payload";
        let packed = pack_vision_frame(payload, CMD_PADDING_END, Some(&uuid), false);

        let mut unpadder = VisionUnpadder::new(uuid);

        // Feed first 5 bytes (UUID fragment)
        let part1 = unpadder.unpad(&packed[..5]);
        assert!(part1.is_empty(), "First 5 bytes of UUID must not produce output");
        assert!(!unpadder.is_direct(), "Must not transition to direct mode prematurely");

        // Feed next 5 bytes (bytes 5..10 of UUID)
        let part2 = unpadder.unpad(&packed[5..10]);
        assert!(part2.is_empty(), "Second 5 bytes of UUID must not produce output");

        // Feed next 5 bytes (bytes 10..15 of UUID)
        let part3 = unpadder.unpad(&packed[10..15]);
        assert!(part3.is_empty(), "Third 5 bytes of UUID must not produce output");

        // Feed remainder of frame
        let part4 = unpadder.unpad(&packed[15..]);
        assert_eq!(part4, payload);
        assert!(unpadder.is_direct());
    }

    #[test]
    fn test_policy_no_uuid_unpadder() {
        let original_data = b"Testing strict NoUuid policy downlink";
        let packed = pack_vision_frame(original_data, CMD_PADDING_END, None, false);

        for &chunk_size in &[1, 4, 5, 8, 15, 16] {
            let mut unpadder = VisionUnpadder::new_no_uuid();
            let mut recovered = Vec::new();

            for chunk in packed.chunks(chunk_size) {
                let part = unpadder.unpad(chunk);
                recovered.extend_from_slice(&part);
            }

            assert_eq!(recovered, original_data, "NoUuid policy failed for chunk size {}", chunk_size);
            assert!(unpadder.is_direct());
        }
    }

    #[test]
    fn test_policy_require_uuid_rejects_frame_without_uuid() {
        let uuid = [0x88; 16];
        let original_data = b"Frame without UUID sent to strict unpadder";
        let packed_no_uuid = pack_vision_frame(original_data, CMD_PADDING_END, None, false);

        let mut unpadder = VisionUnpadder::new_require_uuid(uuid);
        let recovered = unpadder.unpad(&packed_no_uuid);
        assert!(recovered.is_empty(), "Strict unpadder must reject frames without UUID");
        assert!(!unpadder.is_direct());
    }

    #[test]
    fn test_payload_sizes_round_trip() {
        let uuid = [0x5a; 16];
        let test_sizes = [0, 1, 16383, 16384, 65535, 65536, 100000];

        for &size in &test_sizes {
            let payload: Vec<u8> = (0..size).map(|i| (i % 251) as u8).collect();

            // 1. Unfragmented round trip with UUID and CMD_PADDING_END
            let packed = pack_vision_frame(&payload, CMD_PADDING_END, Some(&uuid), false);
            let mut unpadder = VisionUnpadder::new(uuid);
            let recovered = unpadder.unpad(&packed);

            assert_eq!(
                recovered, payload,
                "Payload mismatch on direct unpad for size {}",
                size
            );
            assert!(
                unpadder.is_direct(),
                "Expected direct mode after CMD_PADDING_END for size {}",
                size
            );

            // 2. Fragmented streaming round trip (e.g. chunks of 4096 or 7)
            let mut streaming_unpadder = VisionUnpadder::new(uuid);
            let mut streamed_recovered = Vec::new();
            let stream_chunk_size = if size <= 100 { 7 } else { 4096 };

            for chunk in packed.chunks(stream_chunk_size) {
                let part = streaming_unpadder.unpad(chunk);
                streamed_recovered.extend_from_slice(&part);
            }

            assert_eq!(
                streamed_recovered, payload,
                "Payload mismatch on streaming unpad for size {}",
                size
            );
            assert!(streaming_unpadder.is_direct());
        }
    }

    #[test]
    fn test_large_payload_chunking_conforms_to_vision_max_frame() {
        let uuid = [0x7b; 16];
        let payload = vec![0x3c; 65536];

        let packed = pack_vision_frame(&payload, CMD_PADDING_DIRECT, Some(&uuid), false);

        // Verify that packed output consists of 4 frames, each <= MAX_VISION_FRAME_LEN,
        // and each payload chunk <= MAX_VISION_PAYLOAD_CHUNK
        let mut offset = 0;
        let mut frame_count = 0;
        let mut total_payload_extracted = 0;

        while offset < packed.len() {
            let has_uuid = frame_count == 0;
            if has_uuid {
                assert_eq!(&packed[offset..offset + 16], &uuid);
                offset += 16;
            }

            let command = packed[offset];
            let content_len = u16::from_be_bytes([packed[offset + 1], packed[offset + 2]]) as usize;
            let padding_len = u16::from_be_bytes([packed[offset + 3], packed[offset + 4]]) as usize;
            let frame_total = 5 + content_len + padding_len;

            assert!(
                content_len <= MAX_VISION_PAYLOAD_CHUNK,
                "Frame {} content_len {} exceeds MAX_VISION_PAYLOAD_CHUNK {}",
                frame_count,
                content_len,
                MAX_VISION_PAYLOAD_CHUNK
            );
            assert!(
                frame_total <= MAX_VISION_FRAME_LEN,
                "Frame {} total {} exceeds MAX_VISION_FRAME_LEN {}",
                frame_count,
                frame_total,
                MAX_VISION_FRAME_LEN
            );

            if frame_count < 3 {
                assert_eq!(
                    command, CMD_PADDING_CONTINUE,
                    "Intermediate frame {} should have CMD_PADDING_CONTINUE",
                    frame_count
                );
            } else {
                assert_eq!(
                    command, CMD_PADDING_DIRECT,
                    "Final frame {} should have target command CMD_PADDING_DIRECT",
                    frame_count
                );
            }

            total_payload_extracted += content_len;
            offset += frame_total;
            frame_count += 1;
        }

        assert_eq!(frame_count, 4, "65536-byte payload should produce exactly 4 chunks");
        assert_eq!(total_payload_extracted, 65536);
        assert_eq!(offset, packed.len());
    }

    #[test]
    fn test_unpadder_buffer_limit_protection() {
        let uuid = [0x11; 16];
        let mut unpadder = VisionUnpadder::new(uuid);

        // Send oversized incomplete stream (exceeding MAX_UNPAD_BUFFER_LIMIT)
        let junk = vec![0xee; MAX_UNPAD_BUFFER_LIMIT + 1024];
        let out = unpadder.unpad(&junk);
        assert!(out.is_empty());
        assert!(!unpadder.is_direct());

        // Stream must recover and parse a subsequent valid frame
        let valid_payload = b"recovery after buffer overflow";
        let valid_frame = pack_vision_frame(valid_payload, CMD_PADDING_END, Some(&uuid), false);
        let recovered = unpadder.unpad(&valid_frame);
        assert_eq!(recovered, valid_payload);
        assert!(unpadder.is_direct());
    }

    #[test]
    fn test_framer_with_65536_payload() {
        let uuid = [0x22; 16];
        let mut framer = VisionFramer::new(uuid);
        let payload = vec![0x7a; 65536];

        let framed = framer.frame(&payload);
        assert!(framer.is_direct());

        let mut unpadder = VisionUnpadder::new(uuid);
        let unpadded = unpadder.unpad(&framed);
        assert_eq!(unpadded, payload);
        assert!(unpadder.is_direct());

        // Subsequent payload in direct mode passes through raw
        let raw = vec![0x12, 0x34, 0x56];
        let direct_out = framer.frame(&raw);
        assert_eq!(direct_out, raw);
    }

    #[test]
    fn test_framer_fragmented_tls_client_hello() {
        let uuid = [0x33; 16];
        let mut framer = VisionFramer::new(uuid);

        // Build a 65-byte TLS ClientHello record (5 bytes header + 60 bytes payload)
        let mut ch = vec![0x16, 0x03, 0x01, 0x00, 60];
        ch.extend(std::iter::repeat(0xab).take(60));

        // Feed fragmented into: 1 byte, 2 bytes, 15 bytes, remainder
        let out1 = framer.frame(&ch[..1]);
        assert!(out1.is_empty(), "Fragmented 1-byte header must be buffered");
        assert!(!framer.is_direct());
        assert_eq!(framer.state(), VisionStreamState::Inspecting);

        let out2 = framer.frame(&ch[1..3]);
        assert!(out2.is_empty(), "Fragmented 3-byte header must be buffered");
        assert_eq!(framer.state(), VisionStreamState::Inspecting);

        let out3 = framer.frame(&ch[3..18]);
        assert!(out3.is_empty(), "Partial record must be buffered");
        assert_eq!(framer.state(), VisionStreamState::TlsHandshake);

        let out4 = framer.frame(&ch[18..]);
        assert!(!out4.is_empty(), "Complete ClientHello must emit Vision frame");
        assert_eq!(&out4[..16], &uuid);
        assert_eq!(out4[16], CMD_PADDING_CONTINUE);

        // Verify unpadder recovers exact original ClientHello
        let mut unpadder = VisionUnpadder::new(uuid);
        let recovered = unpadder.unpad(&out4);
        assert_eq!(recovered, ch);
        assert!(!unpadder.is_direct());
    }

    #[test]
    fn test_framer_direct_transition_with_concatenated_tail() {
        let uuid = [0x44; 16];
        let mut framer = VisionFramer::new(uuid);

        // 1. ClientHello
        let ch = vec![0x16, 0x03, 0x01, 0x00, 0x05, 0x01, 0x00, 0x00, 0x00, 0x00];
        let frame1 = framer.frame(&ch);
        assert!(!frame1.is_empty());
        assert!(!framer.is_direct());

        // 2. ApplicationData (5 bytes header + 10 bytes payload = 15 bytes)
        // CONCATENATED with 20 bytes of post-transition raw direct data
        let mut app_data_with_tail = vec![0x17, 0x03, 0x03, 0x00, 10];
        let app_payload = b"0123456789";
        app_data_with_tail.extend_from_slice(app_payload);

        let tail_raw = b"post-transition-tail";
        app_data_with_tail.extend_from_slice(tail_raw);

        let frame2 = framer.frame(&app_data_with_tail);
        assert!(framer.is_direct(), "Must transition to direct on ApplicationData");

        // Verify that frame2 begins with CMD_PADDING_DIRECT frame, followed by raw tail
        let mut unpadder = VisionUnpadder::new(uuid);
        let _ = unpadder.unpad(&frame1);
        let unpadded2 = unpadder.unpad(&frame2);

        assert!(unpadder.is_direct(), "Unpadder must transition to direct mode");
        // Recovered content must contain the exact application data record PLUS the raw tail
        let mut expected = vec![0x17, 0x03, 0x03, 0x00, 10];
        expected.extend_from_slice(app_payload);
        expected.extend_from_slice(tail_raw);

        assert_eq!(unpadded2, expected, "No bytes lost and no double framing at transition boundary");
    }

    #[test]
    fn test_framer_multiple_tls_records_in_single_chunk() {
        let uuid = [0x55; 16];
        let mut framer = VisionFramer::new(uuid);

        // Handshake ClientHello
        let ch = vec![0x16, 0x03, 0x01, 0x00, 0x05, 0x01, 0x00, 0x00, 0x00, 0x00];
        let _ = framer.frame(&ch);

        // Combined chunk with ChangeCipherSpec (6 bytes) + ApplicationData (10 bytes)
        let mut combined = vec![0x14, 0x03, 0x03, 0x00, 0x01, 0x01]; // CCS
        combined.extend_from_slice(&[0x17, 0x03, 0x03, 0x00, 0x05, 1, 2, 3, 4, 5]); // AppData

        let out = framer.frame(&combined);
        assert!(framer.is_direct());

        let mut unpadder = VisionUnpadder::new(uuid);
        let _ = unpadder.unpad(&pack_vision_frame(&ch, CMD_PADDING_CONTINUE, Some(&uuid), false));
        let recovered = unpadder.unpad(&out);

        assert_eq!(recovered, combined);
        assert!(unpadder.is_direct());
    }

    #[test]
    fn test_framer_server_first_interop() {
        let uuid = [0x66; 16];

        // Server sends banner first (e.g. initial downlink before client sends)
        let server_banner = b"SSH-2.0-OpenSSH_8.9p1";
        let server_frame = pack_vision_frame(server_banner, CMD_PADDING_END, None, false);

        let mut downlink_unpadder = VisionUnpadder::new(uuid);
        let recovered_banner = downlink_unpadder.unpad(&server_frame);
        assert_eq!(recovered_banner, server_banner);
        assert!(downlink_unpadder.is_direct(), "Downlink switches to direct on server first frame");

        // Client then sends request
        let mut uplink_framer = VisionFramer::new(uuid);
        let client_req = b"SSH-2.0-Client_1.0";
        let client_frame = uplink_framer.frame(client_req);
        assert!(uplink_framer.is_direct(), "Uplink switches to direct on non-TLS client frame");

        let mut server_unpadder = VisionUnpadder::new(uuid);
        let recovered_req = server_unpadder.unpad(&client_frame);
        assert_eq!(recovered_req, client_req);
        assert!(server_unpadder.is_direct());
    }

    #[test]
    fn test_framer_long_transfer_direct() {
        let uuid = [0x77; 16];
        let mut framer = VisionFramer::new(uuid);

        // Transition with non-TLS payload
        let _ = framer.frame(b"initial non-tls request");
        assert!(framer.is_direct());

        // Transmit 256 KiB in direct mode
        let large_stream: Vec<u8> = (0..256 * 1024).map(|i| (i % 256) as u8).collect();
        let direct_out = framer.frame(&large_stream);

        assert_eq!(direct_out.len(), large_stream.len());
        assert_eq!(direct_out, large_stream);
    }

    #[test]
    fn test_framer_finish_flushes_pending() {
        let uuid = [0x88; 16];
        let mut framer = VisionFramer::new(uuid);

        // Feed only 2 bytes that might be TLS (starts with 0x16)
        let _ = framer.frame(&[0x16, 0x03]);
        assert!(!framer.is_direct());

        // Stream closes (EOF)
        let tail = framer.finish();
        assert!(!tail.is_empty(), "finish must flush pending bytes as frame");
        assert!(framer.is_direct());

        let mut unpadder = VisionUnpadder::new(uuid);
        let recovered = unpadder.unpad(&tail);
        assert_eq!(recovered, &[0x16, 0x03]);
    }
}
