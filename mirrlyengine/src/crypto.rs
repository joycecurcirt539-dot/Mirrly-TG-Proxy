use aes::cipher::{KeyIvInit, StreamCipher};
use byteorder::{BigEndian, ByteOrder, LittleEndian};

type Aes256Ctr = ctr::Ctr64BE<aes::Aes256>;

// ---------------------------------------------------------------------------
// TrackedStream — Go cipher.Stream emulation with Clone() support
// ---------------------------------------------------------------------------

pub struct TrackedStream {
    key: Vec<u8>,
    iv: Vec<u8>,
    processed: u64,
    stream: Aes256Ctr,
}

impl TrackedStream {
    pub fn new(key: &[u8], iv: &[u8]) -> TrackedStream {
        let stream = Aes256Ctr::new(key.into(), iv.into());
        TrackedStream {
            key: key.to_vec(),
            iv: iv.to_vec(),
            processed: 0,
            stream,
        }
    }

    // XOR in place
    pub fn xor(&mut self, data: &mut [u8]) {
        self.stream.apply_keystream(data);
        self.processed += data.len() as u64;
    }

    pub fn clone_state(&self) -> TrackedStream {
        let mut clone_stream = Aes256Ctr::new(self.key.as_slice().into(), self.iv.as_slice().into());
        let mut dummy = [0u8; 16384];
        let mut rem = self.processed;
        while rem > 0 {
            let n = if rem > 16384 { 16384 } else { rem as usize };
            clone_stream.apply_keystream(&mut dummy[..n]);
            rem -= n as u64;
        }
        TrackedStream {
            key: self.key.clone(),
            iv: self.iv.clone(),
            processed: self.processed,
            stream: clone_stream,
        }
    }
}

pub fn new_aes_ctr(key: &[u8], iv: &[u8]) -> TrackedStream {
    TrackedStream::new(key, iv)
}

// ---------------------------------------------------------------------------
// MTProto Splitter
// ---------------------------------------------------------------------------

pub const PROTO_ABRIDGED: i32 = 0;
pub const PROTO_INTERMEDIATE: i32 = 1;
pub const PROTO_PADDED_INTERMEDIATE: i32 = 2;
pub const MAX_MTPROTO_PACKET_LEN: i64 = 16 * 1024 * 1024; // 16 MB Full Buffer Limit

pub fn proto_tag_to_type(proto: u32) -> i32 {
    match proto {
        0xEEEEEEEE => PROTO_INTERMEDIATE,
        0xDDDDDDDD => PROTO_PADDED_INTERMEDIATE,
        _ => PROTO_ABRIDGED,
    }
}

pub struct MsgSplitter {
    proto_type: i32,
    plain_buf: Vec<u8>,
    disabled: bool,
}

impl MsgSplitter {
    pub fn new(proto: u32) -> MsgSplitter {
        MsgSplitter {
            proto_type: proto_tag_to_type(proto),
            plain_buf: Vec::new(),
            disabled: false,
        }
    }

    /// Processes incoming encrypted chunk from client socket:
    /// 1. Decrypts chunk with client decryptor (in-place).
    /// 2. Buffers plaintext and parses MTProto discrete packet frames.
    /// 3. Re-encrypts each complete discrete packet with upstream encryptor.
    /// 4. Returns vector of encrypted discrete frames ready for WebSocket transmission.
    pub fn process(
        &mut self,
        chunk: &[u8],
        clt_dec: &mut TrackedStream,
        tg_enc: &mut TrackedStream,
    ) -> Vec<Vec<u8>> {
        if chunk.is_empty() {
            return Vec::new();
        }

        let mut decrypted = chunk.to_vec();
        clt_dec.xor(&mut decrypted);

        if self.disabled {
            tg_enc.xor(&mut decrypted);
            return vec![decrypted];
        }

        self.plain_buf.extend_from_slice(&decrypted);

        let mut parts: Vec<Vec<u8>> = Vec::new();
        while !self.plain_buf.is_empty() {
            let pkt_len = self.next_packet_len();
            if pkt_len < 0 {
                // Need more bytes to complete current packet frame
                break;
            }
            if pkt_len == 0 {
                // Unknown/invalid format: disable splitter and pass through remaining data
                self.disabled = true;
                let mut remaining = std::mem::take(&mut self.plain_buf);
                tg_enc.xor(&mut remaining);
                parts.push(remaining);
                break;
            }
            let pkt_len = pkt_len as usize;
            if self.plain_buf.len() < pkt_len {
                break;
            }
            let mut packet: Vec<u8> = self.plain_buf.drain(..pkt_len).collect();
            tg_enc.xor(&mut packet);
            parts.push(packet);
        }

        parts
    }

    /// Flushes any pending un-framed plaintext buffer (e.g. at end of stream).
    pub fn flush(&mut self, tg_enc: &mut TrackedStream) -> Vec<Vec<u8>> {
        if self.plain_buf.is_empty() {
            return Vec::new();
        }
        let mut tail = std::mem::take(&mut self.plain_buf);
        tg_enc.xor(&mut tail);
        vec![tail]
    }

    fn next_packet_len(&self) -> i64 {
        if self.plain_buf.is_empty() {
            return -1;
        }
        match self.proto_type {
            PROTO_ABRIDGED => {
                let first = self.plain_buf[0];
                let header_len;
                let payload_len;
                if first == 0x7F || first == 0xFF {
                    if self.plain_buf.len() < 4 {
                        return -1;
                    }
                    payload_len = ((self.plain_buf[1] as i64)
                        | ((self.plain_buf[2] as i64) << 8)
                        | ((self.plain_buf[3] as i64) << 16))
                        * 4;
                    header_len = 4;
                } else {
                    payload_len = ((first & 0x7F) as i64) * 4;
                    header_len = 1;
                }
                if payload_len <= 0 || payload_len > MAX_MTPROTO_PACKET_LEN {
                    return 0;
                }
                let pkt_len = header_len + payload_len;
                if (self.plain_buf.len() as i64) < pkt_len {
                    return -1;
                }
                pkt_len
            }
            PROTO_INTERMEDIATE | PROTO_PADDED_INTERMEDIATE => {
                if self.plain_buf.len() < 4 {
                    return -1;
                }
                let payload_len =
                    (LittleEndian::read_u32(&self.plain_buf[..4]) & 0x7FFFFFFF) as i64;
                if payload_len <= 0 || payload_len > MAX_MTPROTO_PACKET_LEN {
                    return 0;
                }
                let pkt_len = 4 + payload_len;
                if (self.plain_buf.len() as i64) < pkt_len {
                    return -1;
                }
                pkt_len
            }
            _ => 0,
        }
    }
}

// ---------------------------------------------------------------------------
// XOR mask (websocket frame masking)
// ---------------------------------------------------------------------------

pub fn xor_mask_in_place(data: &mut [u8], mask: &[u8]) {
    let n = data.len();
    if n == 0 {
        return;
    }
    let mask8: u64 = (mask[0] as u64)
        | ((mask[1] as u64) << 8)
        | ((mask[2] as u64) << 16)
        | ((mask[3] as u64) << 24)
        | ((mask[0] as u64) << 32)
        | ((mask[1] as u64) << 40)
        | ((mask[2] as u64) << 48)
        | ((mask[3] as u64) << 56);

    let mut i = 0;
    while i + 8 <= n {
        let v = LittleEndian::read_u64(&data[i..]);
        LittleEndian::write_u64(&mut data[i..], v ^ mask8);
        i += 8;
    }
    while i < n {
        data[i] ^= mask[i & 3];
        i += 1;
    }
}

#[allow(dead_code)]
pub fn write_be_u16(buf: &mut [u8], v: u16) {
    BigEndian::write_u16(buf, v);
}
#[allow(dead_code)]
pub fn write_be_u64(buf: &mut [u8], v: u64) {
    BigEndian::write_u64(buf, v);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_msg_splitter_abridged_reassembly() {
        let key_clt = [0x11u8; 32];
        let iv_clt = [0x22u8; 16];
        let key_tg = [0x33u8; 32];
        let iv_tg = [0x44u8; 16];

        let mut clt_client_side = new_aes_ctr(&key_clt, &iv_clt);
        let mut clt_proxy_side = new_aes_ctr(&key_clt, &iv_clt);

        let mut tg_proxy_side = new_aes_ctr(&key_tg, &iv_tg);
        let mut tg_server_side = new_aes_ctr(&key_tg, &iv_tg);

        // Create Packet 1 (Abridged short: 10 words = 40 bytes payload + 1 byte header)
        let mut pkt1 = vec![10u8];
        pkt1.extend_from_slice(&[0xAA; 40]);

        // Create Packet 2 (Abridged long: 300 words = 1200 bytes payload + 4 bytes header)
        let words = 300u32;
        let mut pkt2 = vec![
            0x7Fu8,
            (words & 0xFF) as u8,
            ((words >> 8) & 0xFF) as u8,
            ((words >> 16) & 0xFF) as u8,
        ];
        pkt2.extend_from_slice(&[0xBB; 1200]);

        // Merge and encrypt from client side
        let mut stream = Vec::new();
        stream.extend_from_slice(&pkt1);
        stream.extend_from_slice(&pkt2);
        clt_client_side.xor(&mut stream);

        // Feed to MsgSplitter in fragmented chunks (e.g. 17 bytes each)
        let mut splitter = MsgSplitter::new(0xEFEFEFEF);
        let mut received_frames = Vec::new();
        for chunk in stream.chunks(17) {
            let frames = splitter.process(chunk, &mut clt_proxy_side, &mut tg_proxy_side);
            received_frames.extend(frames);
        }

        assert_eq!(received_frames.len(), 2);
        assert_eq!(received_frames[0].len(), pkt1.len());
        assert_eq!(received_frames[1].len(), pkt2.len());

        // Decrypt frames on upstream server side
        let mut rec1 = received_frames[0].clone();
        tg_server_side.xor(&mut rec1);
        assert_eq!(rec1, pkt1);

        let mut rec2 = received_frames[1].clone();
        tg_server_side.xor(&mut rec2);
        assert_eq!(rec2, pkt2);
    }

    #[test]
    fn test_msg_splitter_intermediate_large_media_chunk() {
        let key_clt = [0x55u8; 32];
        let iv_clt = [0x66u8; 16];
        let key_tg = [0x77u8; 32];
        let iv_tg = [0x88u8; 16];

        let mut clt_client_side = new_aes_ctr(&key_clt, &iv_clt);
        let mut clt_proxy_side = new_aes_ctr(&key_clt, &iv_clt);

        let mut tg_proxy_side = new_aes_ctr(&key_tg, &iv_tg);
        let mut tg_server_side = new_aes_ctr(&key_tg, &iv_tg);

        // 128 KB media chunk in MTProto Intermediate format
        let payload_len = 128 * 1024;
        let mut pkt = vec![0u8; 4 + payload_len];
        LittleEndian::write_u32(&mut pkt[0..4], payload_len as u32);
        for i in 4..pkt.len() {
            pkt[i] = (i % 251) as u8;
        }

        let mut client_cipher = pkt.clone();
        clt_client_side.xor(&mut client_cipher);

        let mut splitter = MsgSplitter::new(0xEEEEEEEE);
        let mut received_frames = Vec::new();

        // Feed in 8KB TCP fragments
        for chunk in client_cipher.chunks(8192) {
            let frames = splitter.process(chunk, &mut clt_proxy_side, &mut tg_proxy_side);
            received_frames.extend(frames);
        }

        assert_eq!(received_frames.len(), 1);
        assert_eq!(received_frames[0].len(), pkt.len());

        let mut rec = received_frames[0].clone();
        tg_server_side.xor(&mut rec);
        assert_eq!(rec, pkt);
    }
}
