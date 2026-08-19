use aes::cipher::{KeyIvInit, StreamCipher};
use sha2::{Digest, Sha256};

pub type Aes256Ctr128BE = ctr::Ctr128BE<aes::Aes256>;

pub const PROTOCOL_ABRIDGED: u32 = 0xefefefef;
pub const PROTOCOL_INTERMEDIATE: u32 = 0xeeeeeeee;
pub const PROTOCOL_PADDED_INTERMEDIATE: u32 = 0xdddddddd;

#[derive(Clone, Debug, PartialEq)]
pub enum MtprotoProtocol {
    Abridged,
    Intermediate,
    PaddedIntermediate,
    FakeTls,
    Unknown(u32),
}

pub struct CryptoPair {
    pub dec: Aes256Ctr128BE,
    pub enc: Aes256Ctr128BE,
}

pub struct HandshakeResult {
    pub dc_id: i16,
    pub protocol: MtprotoProtocol,
    pub is_fake_tls: bool,
    pub raw_header: Vec<u8>,
    pub client_crypto: Option<CryptoPair>,
}

pub fn parse_secret_bytes(secret: &str) -> (Vec<u8>, Option<String>) {
    let secret = secret.trim();
    if secret.is_empty() {
        return (vec![0u8; 16], None);
    }

    if secret.starts_with("ee") || secret.starts_with("EE") {
        // Fake-TLS: ee + 32-hex-secret + hex-encoded-domain
        if let Ok(bytes) = hex::decode(secret) {
            if bytes.len() >= 17 {
                let raw_secret = bytes[1..17].to_vec();
                let domain = if bytes.len() > 17 {
                    String::from_utf8(bytes[17..].to_vec()).ok()
                } else {
                    None
                };
                return (raw_secret, domain);
            }
        }
    } else if secret.starts_with("dd") || secret.starts_with("DD") {
        // Padded: dd + 32-hex-secret
        if let Ok(bytes) = hex::decode(secret) {
            if bytes.len() == 17 {
                return (bytes[1..17].to_vec(), None);
            }
        }
    }

    // Standard 32 hex chars
    if let Ok(bytes) = hex::decode(secret) {
        if bytes.len() == 16 {
            return (bytes, None);
        }
    }

    // Raw string fallback
    let mut raw = vec![0u8; 16];
    let src = secret.as_bytes();
    let copy_len = src.len().min(16);
    raw[..copy_len].copy_from_slice(&src[..copy_len]);
    (raw, None)
}

pub fn format_secret_with_prefix(secret: &str) -> String {
    let s = secret.trim();
    if s.is_empty() {
        return String::new();
    }
    if s.starts_with("dd") || s.starts_with("DD") || s.starts_with("ee") || s.starts_with("EE") {
        return s.to_string();
    }
    // Default prefix for MTProto intermediate padded
    if s.len() == 32 && hex::decode(s).is_ok() {
        format!("dd{}", s)
    } else {
        s.to_string()
    }
}

pub fn parse_handshake_header(buf: &[u8], secret: &str) -> Option<HandshakeResult> {
    if buf.len() < 64 {
        return None;
    }

    // Check Fake-TLS handshake (0x16 0x03 0x01 = TLS ClientHello)
    if buf[0] == 0x16 && buf[1] == 0x03 && buf[2] == 0x01 {
        return Some(HandshakeResult {
            dc_id: 2,
            protocol: MtprotoProtocol::FakeTls,
            is_fake_tls: true,
            raw_header: buf[..64].to_vec(),
            client_crypto: None,
        });
    }

    let (secret_bytes, _) = parse_secret_bytes(secret);

    // Decryption key: key = sha256(buf[8..40] + secret)
    let mut hasher = Sha256::new();
    hasher.update(&buf[8..40]);
    hasher.update(&secret_bytes);
    let dec_key = hasher.finalize();
    let dec_iv = &buf[40..56];

    let mut client_dec = match Aes256Ctr128BE::new_from_slices(&dec_key, dec_iv) {
        Ok(c) => c,
        Err(_) => return None,
    };

    // Encryption key from reversed 48 bytes (buf[8..56]) per Telegram specification
    let mut rev_48 = [0u8; 48];
    rev_48.copy_from_slice(&buf[8..56]);
    rev_48.reverse();

    let mut hasher_rev = Sha256::new();
    hasher_rev.update(&rev_48[0..32]);
    hasher_rev.update(&secret_bytes);
    let enc_key = hasher_rev.finalize();
    let enc_iv = &rev_48[32..48];

    let client_enc = match Aes256Ctr128BE::new_from_slices(&enc_key, enc_iv) {
        Ok(c) => c,
        Err(_) => return None,
    };

    let mut decrypted = [0u8; 64];
    decrypted.copy_from_slice(&buf[..64]);
    client_dec.apply_keystream(&mut decrypted);

    let tag = u32::from_le_bytes([decrypted[56], decrypted[57], decrypted[58], decrypted[59]]);
    let dc_id = i16::from_le_bytes([decrypted[60], decrypted[61]]);

    let protocol = match tag {
        PROTOCOL_ABRIDGED => MtprotoProtocol::Abridged,
        PROTOCOL_INTERMEDIATE => MtprotoProtocol::Intermediate,
        PROTOCOL_PADDED_INTERMEDIATE => MtprotoProtocol::PaddedIntermediate,
        _ => MtprotoProtocol::Unknown(tag),
    };

    Some(HandshakeResult {
        dc_id: if dc_id == 0 { 2 } else { dc_id },
        protocol,
        is_fake_tls: false,
        raw_header: buf[..64].to_vec(),
        client_crypto: Some(CryptoPair {
            dec: client_dec,
            enc: client_enc,
        }),
    })
}

pub fn generate_relay_init(protocol: &MtprotoProtocol, dc_idx: i16) -> ([u8; 64], CryptoPair) {
    use rand::RngCore;
    let mut rng = rand::thread_rng();
    let mut rnd = [0u8; 64];

    loop {
        rng.fill_bytes(&mut rnd);
        let first = rnd[0];
        if first == 0xef {
            continue;
        }
        let start4 = &rnd[0..4];
        if start4 == b"HEAD"
            || start4 == b"POST"
            || start4 == b"GET "
            || start4 == &[0xee, 0xee, 0xee, 0xee]
            || start4 == &[0xdd, 0xdd, 0xdd, 0xdd]
            || start4 == &[0x16, 0x03, 0x01, 0x02]
        {
            continue;
        }
        if &rnd[4..8] == &[0, 0, 0, 0] {
            continue;
        }
        break;
    }

    let enc_key = &rnd[8..40];
    let enc_iv = &rnd[40..56];
    let mut upstream_enc =
        Aes256Ctr128BE::new_from_slices(enc_key, enc_iv).expect("valid key/iv length");

    let mut rev_48 = [0u8; 48];
    rev_48.copy_from_slice(&rnd[8..56]);
    rev_48.reverse();
    let dec_key = &rev_48[0..32];
    let dec_iv = &rev_48[32..48];

    let upstream_dec =
        Aes256Ctr128BE::new_from_slices(dec_key, dec_iv).expect("valid key/iv length");

    let proto_tag: [u8; 4] = match protocol {
        MtprotoProtocol::PaddedIntermediate => PROTOCOL_PADDED_INTERMEDIATE.to_le_bytes(),
        MtprotoProtocol::Intermediate => PROTOCOL_INTERMEDIATE.to_le_bytes(),
        MtprotoProtocol::Abridged => PROTOCOL_ABRIDGED.to_le_bytes(),
        _ => PROTOCOL_PADDED_INTERMEDIATE.to_le_bytes(),
    };

    let dc_bytes = dc_idx.to_le_bytes();
    let mut random_tail = [0u8; 2];
    rng.fill_bytes(&mut random_tail);

    let mut tail_plain = [0u8; 8];
    tail_plain[0..4].copy_from_slice(&proto_tag);
    tail_plain[4..6].copy_from_slice(&dc_bytes);
    tail_plain[6..8].copy_from_slice(&random_tail);

    let mut encrypted_full = rnd;
    upstream_enc.apply_keystream(&mut encrypted_full);

    let mut relay_init = rnd;
    for i in 0..8 {
        let keystream_tail_byte = encrypted_full[56 + i] ^ rnd[56 + i];
        relay_init[56 + i] = tail_plain[i] ^ keystream_tail_byte;
    }

    (
        relay_init,
        CryptoPair {
            enc: upstream_enc,
            dec: upstream_dec,
        },
    )
}

/// Splits MTProto stream into discrete packets for WebSocket /apiws framing.
pub struct MsgSplitter {
    protocol: MtprotoProtocol,
    plain_buf: Vec<u8>,
}

impl MsgSplitter {
    pub fn new(protocol: MtprotoProtocol) -> Self {
        Self {
            protocol,
            plain_buf: Vec::with_capacity(16384),
        }
    }

    /// Feeds incoming client payload (after client_dec), extracts complete MTProto packets,
    /// encrypts each packet with upstream_enc, and returns a list of individual WebSocket frames.
    pub fn process_chunk(
        &mut self,
        mut chunk: Vec<u8>,
        client_dec: &mut Option<Aes256Ctr128BE>,
        upstream_enc: &mut Option<Aes256Ctr128BE>,
    ) -> Vec<Vec<u8>> {
        if let Some(dec) = client_dec {
            dec.apply_keystream(&mut chunk);
        }
        self.plain_buf.extend_from_slice(&chunk);

        let mut packets = Vec::new();

        loop {
            let buf_len = self.plain_buf.len();
            if buf_len == 0 {
                break;
            }

            let packet_len = match self.protocol {
                MtprotoProtocol::Intermediate | MtprotoProtocol::PaddedIntermediate | MtprotoProtocol::FakeTls => {
                    if buf_len < 4 {
                        break;
                    }
                    let raw_len = u32::from_le_bytes([
                        self.plain_buf[0],
                        self.plain_buf[1],
                        self.plain_buf[2],
                        self.plain_buf[3],
                    ]);
                    let payload_len = (raw_len & 0x7FFFFFFF) as usize;
                    if payload_len == 0 || payload_len > 16 * 1024 * 1024 {
                        // Pass through entire buffer if unknown length
                        buf_len
                    } else {
                        4 + payload_len
                    }
                }
                MtprotoProtocol::Abridged => {
                    let first = self.plain_buf[0];
                    if first == 0x7F || first == 0xFF {
                        if buf_len < 4 {
                            break;
                        }
                        let b1 = self.plain_buf[1] as usize;
                        let b2 = self.plain_buf[2] as usize;
                        let b3 = self.plain_buf[3] as usize;
                        let payload_len = (b1 | (b2 << 8) | (b3 << 16)) * 4;
                        if payload_len == 0 || payload_len > 16 * 1024 * 1024 {
                            buf_len
                        } else {
                            4 + payload_len
                        }
                    } else {
                        let payload_len = ((first & 0x7F) as usize) * 4;
                        if payload_len == 0 {
                            buf_len
                        } else {
                            1 + payload_len
                        }
                    }
                }
                _ => buf_len,
            };

            if buf_len < packet_len {
                // Incomplete packet in buffer; wait for more data from client
                break;
            }

            let mut packet: Vec<u8> = self.plain_buf.drain(..packet_len).collect();
            if let Some(enc) = upstream_enc {
                enc.apply_keystream(&mut packet);
            }
            packets.push(packet);
        }

        packets
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_generate_relay_init_dc_decryption() {
        let dc_id = 4i16;
        let protocol = MtprotoProtocol::PaddedIntermediate;
        let (relay_init, _crypto) = generate_relay_init(&protocol, dc_id);

        let dc_dec_key = &relay_init[8..40];
        let dc_dec_iv = &relay_init[40..56];
        let mut dc_dec = Aes256Ctr128BE::new_from_slices(dc_dec_key, dc_dec_iv).unwrap();

        let mut decrypted = relay_init;
        dc_dec.apply_keystream(&mut decrypted);
        let tag = u32::from_le_bytes([decrypted[56], decrypted[57], decrypted[58], decrypted[59]]);
        let dc = i16::from_le_bytes([decrypted[60], decrypted[61]]);
        assert_eq!(tag, PROTOCOL_PADDED_INTERMEDIATE);
        assert_eq!(dc, dc_id);
    }

    #[test]
    fn test_msg_splitter_packet_framing() {
        let mut splitter = MsgSplitter::new(MtprotoProtocol::PaddedIntermediate);
        let mut client_dec = None;
        let mut upstream_enc = None;

        // Packet 1: 4 bytes len (payload=8) + 8 bytes payload = 12 bytes
        let mut p1 = vec![8, 0, 0, 0];
        p1.extend_from_slice(b"12345678");

        // Packet 2: 4 bytes len (payload=4) + 4 bytes payload = 8 bytes
        let mut p2 = vec![4, 0, 0, 0];
        p2.extend_from_slice(b"abcd");

        // Send half of p1
        let frames1 = splitter.process_chunk(p1[..6].to_vec(), &mut client_dec, &mut upstream_enc);
        assert_eq!(frames1.len(), 0);

        // Send rest of p1 + full p2
        let mut combined = p1[6..].to_vec();
        combined.extend_from_slice(&p2);
        let frames2 = splitter.process_chunk(combined, &mut client_dec, &mut upstream_enc);
        assert_eq!(frames2.len(), 2);
        assert_eq!(frames2[0], p1);
        assert_eq!(frames2[1], p2);
    }
}
