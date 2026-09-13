/*
 * Mirrly TG Proxy - High Performance Native MTProto & WebSocket Engine
 * Copyright (C) 2026 R1Xern (Mirrly Dev)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

use crate::{ldebug, linfo, lwarn};
use hmac::{Hmac, Mac};
use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey, AES_128_GCM, AES_256_GCM, CHACHA20_POLY1305};
use sha2::{Digest, Sha256, Sha384, Sha512};
use std::pin::Pin;
use std::task::{Context, Poll};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf};
use tokio::net::TcpStream;

type HmacSha256 = Hmac<Sha256>;
type HmacSha384 = Hmac<Sha384>;
type HmacSha512 = Hmac<Sha512>;

// ---------------------------------------------------------------------------
// TLS 1.3 Reality Stream & Record Layer Implementation
// ---------------------------------------------------------------------------

pub struct RealityStream {
    stream: TcpStream,
    read_key: Vec<u8>,
    read_iv: [u8; 12],
    read_seq: u64,
    write_key: Vec<u8>,
    write_iv: [u8; 12],
    write_seq: u64,
    cipher_suite: u16,

    pub read_buf: Vec<u8>,
    read_buf_pos: usize,
    raw_in: Vec<u8>,

    raw_out: Vec<u8>,
    raw_out_pos: usize,
}

impl RealityStream {
    pub fn new(
        stream: TcpStream,
        read_key: Vec<u8>,
        read_iv: [u8; 12],
        write_key: Vec<u8>,
        write_iv: [u8; 12],
        cipher_suite: u16,
    ) -> Self {
        Self {
            stream,
            read_key,
            read_iv,
            read_seq: 0,
            write_key,
            write_iv,
            write_seq: 0,
            cipher_suite,
            read_buf: Vec::new(),
            read_buf_pos: 0,
            raw_in: Vec::new(),
            raw_out: Vec::new(),
            raw_out_pos: 0,
        }
    }

    pub fn into_inner(self) -> TcpStream {
        self.stream
    }

    pub fn encrypt_app_record(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, String> {
        let mut in_out = Vec::with_capacity(plaintext.len() + 1 + 16);
        in_out.extend_from_slice(plaintext);
        in_out.push(0x17); // Inner content type: Application Data (RFC 8446 Section 5.2)

        let mut nonce_bytes = self.write_iv;
        let seq_be = self.write_seq.to_be_bytes();
        for i in 0..8 {
            nonce_bytes[4 + i] ^= seq_be[i];
        }
        self.write_seq += 1;

        let ct_len = (in_out.len() + 16) as u16;
        let header = [0x17, 0x03, 0x03, (ct_len >> 8) as u8, ct_len as u8];

        aead_seal(
            self.cipher_suite,
            &self.write_key,
            &nonce_bytes,
            &header,
            &mut in_out,
        )?;

        let mut record = Vec::with_capacity(5 + in_out.len());
        record.extend_from_slice(&header);
        record.extend_from_slice(&in_out);
        Ok(record)
    }

    pub fn decrypt_app_record(&mut self, record: &[u8]) -> Result<Option<Vec<u8>>, String> {
        if record.len() < 5 + 16 {
            return Err("TLS record too short".to_string());
        }
        let header = &record[..5];
        let content_type = header[0];

        if content_type == 0x15 {
            // TLS Alert (connection termination)
            ldebug!("REALITY: received TLS alert record in application phase");
            return Ok(None);
        }
        if content_type == 0x14 {
            // Dummy ChangeCipherSpec record: ignore
            return Ok(Some(Vec::new()));
        }
        if content_type != 0x17 {
            return Err(format!(
                "Unexpected TLS record content type: 0x{:02x} (expected 0x17)",
                content_type
            ));
        }

        let mut ciphertext = record[5..].to_vec();

        let mut nonce_bytes = self.read_iv;
        let seq_be = self.read_seq.to_be_bytes();
        for i in 0..8 {
            nonce_bytes[4 + i] ^= seq_be[i];
        }
        self.read_seq += 1;

        aead_open(
            self.cipher_suite,
            &self.read_key,
            &nonce_bytes,
            header,
            &mut ciphertext,
        )?;

        let mut end = ciphertext.len();
        while end > 0 && ciphertext[end - 1] == 0 {
            end -= 1;
        }
        if end == 0 {
            return Err("TLS 1.3 record has no inner content type".to_string());
        }
        let inner_type = ciphertext[end - 1];
        let payload = ciphertext[..end - 1].to_vec();

        if inner_type == 0x17 {
            Ok(Some(payload))
        } else if inner_type == 0x15 {
            ldebug!("REALITY: received TLS inner alert in app record: {:?}", payload);
            Ok(None)
        } else if inner_type == 0x18 {
            // NewSessionTicket post-handshake message: skip
            ldebug!("REALITY: received NewSessionTicket, skipping");
            Ok(Some(Vec::new()))
        } else {
            ldebug!("REALITY: skipping non-data inner type 0x{:02x}", inner_type);
            Ok(Some(Vec::new()))
        }
    }
}

impl AsyncRead for RealityStream {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        let this = self.get_mut();

        // 1. Consume buffered decrypted plaintext if available
        if this.read_buf_pos < this.read_buf.len() {
            let to_copy = (this.read_buf.len() - this.read_buf_pos).min(buf.remaining());
            buf.put_slice(&this.read_buf[this.read_buf_pos..this.read_buf_pos + to_copy]);
            this.read_buf_pos += to_copy;
            if this.read_buf_pos >= this.read_buf.len() {
                this.read_buf.clear();
                this.read_buf_pos = 0;
            }
            return Poll::Ready(Ok(()));
        }

        // 2. Read encrypted TLS records from raw TCP socket
        loop {
            if this.raw_in.len() >= 5 {
                let rec_len = u16::from_be_bytes([this.raw_in[3], this.raw_in[4]]) as usize;
                let total_rec_len = 5 + rec_len;
                if this.raw_in.len() >= total_rec_len {
                    let record_bytes = this.raw_in[..total_rec_len].to_vec();
                    this.raw_in.drain(..total_rec_len);

                    match this.decrypt_app_record(&record_bytes) {
                        Ok(Some(plaintext)) => {
                            if !plaintext.is_empty() {
                                let to_copy = plaintext.len().min(buf.remaining());
                                buf.put_slice(&plaintext[..to_copy]);
                                if to_copy < plaintext.len() {
                                    this.read_buf = plaintext[to_copy..].to_vec();
                                    this.read_buf_pos = 0;
                                }
                                return Poll::Ready(Ok(()));
                            }
                            continue;
                        }
                        Ok(None) => {
                            // TLS alert / close_notify: EOF
                            return Poll::Ready(Ok(()));
                        }
                        Err(e) => {
                            return Poll::Ready(Err(std::io::Error::new(
                                std::io::ErrorKind::InvalidData,
                                e,
                            )));
                        }
                    }
                }
            }

            let mut chunk = [0u8; 8192];
            let mut read_chunk = ReadBuf::new(&mut chunk);
            match Pin::new(&mut this.stream).poll_read(cx, &mut read_chunk) {
                Poll::Ready(Ok(())) => {
                    let n = read_chunk.filled().len();
                    if n == 0 {
                        return Poll::Ready(Ok(()));
                    }
                    this.raw_in.extend_from_slice(read_chunk.filled());
                }
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Pending => return Poll::Pending,
            }
        }
    }
}

impl AsyncWrite for RealityStream {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<std::io::Result<usize>> {
        let this = self.get_mut();

        // 1. Drain pending raw_out data first
        while this.raw_out_pos < this.raw_out.len() {
            let data = &this.raw_out[this.raw_out_pos..];
            match Pin::new(&mut this.stream).poll_write(cx, data) {
                Poll::Ready(Ok(0)) => {
                    return Poll::Ready(Err(std::io::Error::new(
                        std::io::ErrorKind::WriteZero,
                        "TCP stream closed during write",
                    )));
                }
                Poll::Ready(Ok(n)) => {
                    this.raw_out_pos += n;
                    if this.raw_out_pos >= this.raw_out.len() {
                        this.raw_out.clear();
                        this.raw_out_pos = 0;
                    }
                }
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Pending => return Poll::Pending,
            }
        }

        if buf.is_empty() {
            return Poll::Ready(Ok(0));
        }

        // 2. Encrypt at most 16384 bytes into TLS 1.3 Application Record
        let chunk_len = buf.len().min(16384);
        let chunk = &buf[..chunk_len];

        let record = match this.encrypt_app_record(chunk) {
            Ok(r) => r,
            Err(e) => {
                return Poll::Ready(Err(std::io::Error::new(
                    std::io::ErrorKind::Other,
                    e,
                )));
            }
        };

        // 3. Write record into TCP socket
        match Pin::new(&mut this.stream).poll_write(cx, &record) {
            Poll::Ready(Ok(n)) => {
                if n < record.len() {
                    this.raw_out = record[n..].to_vec();
                    this.raw_out_pos = 0;
                }
                Poll::Ready(Ok(chunk_len))
            }
            Poll::Pending => {
                this.raw_out = record;
                this.raw_out_pos = 0;
                Poll::Pending
            }
            Poll::Ready(Err(e)) => Poll::Ready(Err(e)),
        }
    }

    fn poll_flush(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<std::io::Result<()>> {
        let this = self.get_mut();
        while this.raw_out_pos < this.raw_out.len() {
            let data = &this.raw_out[this.raw_out_pos..];
            match Pin::new(&mut this.stream).poll_write(cx, data) {
                Poll::Ready(Ok(0)) => {
                    return Poll::Ready(Err(std::io::Error::new(
                        std::io::ErrorKind::WriteZero,
                        "TCP stream closed during flush",
                    )));
                }
                Poll::Ready(Ok(n)) => {
                    this.raw_out_pos += n;
                    if this.raw_out_pos >= this.raw_out.len() {
                        this.raw_out.clear();
                        this.raw_out_pos = 0;
                    }
                }
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Pending => return Poll::Pending,
            }
        }
        Pin::new(&mut this.stream).poll_flush(cx)
    }

    fn poll_shutdown(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<std::io::Result<()>> {
        let this = self.get_mut();
        while this.raw_out_pos < this.raw_out.len() {
            let data = &this.raw_out[this.raw_out_pos..];
            match Pin::new(&mut this.stream).poll_write(cx, data) {
                Poll::Ready(Ok(0)) => break,
                Poll::Ready(Ok(n)) => {
                    this.raw_out_pos += n;
                    if this.raw_out_pos >= this.raw_out.len() {
                        this.raw_out.clear();
                        this.raw_out_pos = 0;
                    }
                }
                Poll::Ready(Err(e)) => return Poll::Ready(Err(e)),
                Poll::Pending => return Poll::Pending,
            }
        }
        Pin::new(&mut this.stream).poll_shutdown(cx)
    }
}

// ---------------------------------------------------------------------------
// REALITY Connect Client Handshake Implementation
// ---------------------------------------------------------------------------

/// Reality handshake client implementation (RFC 8446 / Xray Reality specification)
/// Performs a TLS 1.3 ClientHello camouflage handshake with the Reality server,
/// injecting authenticated Session ID tag, completes the entire TLS 1.3 handshake,
/// verifies peer authentication against AuthKey, verifies Finished, and returns
/// a cryptographically protected `RealityStream` for the record layer.
pub async fn reality_connect_ext(
    mut stream: TcpStream,
    sni: &str,
    public_key_str: &str,
    short_id_str: &str,
    fingerprint: &str,
    timeout: Duration,
) -> Result<RealityStream, String> {
    let clean_pk = public_key_str.trim();
    if clean_pk.is_empty() {
        return Err("REALITY: Server public key is required for Reality mode".to_string());
    }

    ldebug!(
        "REALITY: starting TLS 1.3 client handshake with sni={}, pbk={}, sid={}, fp={}",
        sni,
        clean_pk,
        short_id_str,
        fingerprint
    );

    let (_, cap) = crate::ws::classify_fingerprint(fingerprint);
    match cap {
        crate::ws::FingerprintCapability::Supported => {}
        crate::ws::FingerprintCapability::Mapped => {
            ldebug!("REALITY: TLS fingerprint '{}' mapped to Chrome profile", fingerprint);
        }
        crate::ws::FingerprintCapability::UnsupportedFallback => {
            lwarn!(
                "REALITY: Unsupported TLS fingerprint '{}'; falling back to Chrome profile without altering security",
                fingerprint
            );
        }
    }

    // 1. Decode server static public key (32 bytes X25519)
    let pbk_bytes = match decode_reality_key(clean_pk) {
        Ok(b) if b.len() == 32 => {
            let mut arr = [0u8; 32];
            arr.copy_from_slice(&b);
            arr
        }
        _ => {
            return Err(format!(
                "Invalid Reality public key length or format: {}",
                clean_pk
            ));
        }
    };

    // 2. Decode short_id (0 to 8 bytes)
    let sid_bytes = decode_reality_short_id(short_id_str.trim());

    // 3. Generate client ephemeral X25519 private key & public key
    let mut client_priv = [0u8; 32];
    rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut client_priv);
    let client_pub = crate::awg::x25519_base(&client_priv);

    // 4. Compute static ECDH shared secret: X25519(client_priv, server_static_pbk)
    let shared_secret = crate::awg::x25519(&client_priv, &pbk_bytes);

    // 5. Generate Client Random (32 bytes):
    //    client_random[..20] is the HKDF salt.
    //    client_random[20..32] is the 12-byte AES-256-GCM Nonce.
    let mut client_random = [0u8; 32];
    rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut client_random);

    // 6. Derive AuthKey (32 bytes) via HKDF-SHA256:
    //    salt = client_random[..20], IKM = shared_secret, info = b"REALITY"
    let auth_key = hkdf_sha256(&client_random[..20], &shared_secret, b"REALITY");

    // 7. Prepare Session ID plaintext payload (16 bytes):
    //    [0..3]: Client version (1.8.23)
    //    [3]: 0 (reserved)
    //    [4..8]: Big-endian Unix timestamp (u32)
    //    [8..16]: short_id (up to 8 bytes, zero-padded)
    let mut plaintext = [0u8; 16];
    plaintext[0] = 1;
    plaintext[1] = 8;
    plaintext[2] = 23;
    plaintext[3] = 0;
    let now_secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as u32;
    plaintext[4..8].copy_from_slice(&now_secs.to_be_bytes());
    if !sid_bytes.is_empty() {
        let copy_len = sid_bytes.len().min(8);
        plaintext[8..8 + copy_len].copy_from_slice(&sid_bytes[..copy_len]);
    }

    // 8. Build ClientHello handshake body with session ID set to 32 zeros (AAD)
    let (mut handshake_msg, session_id_offset) = build_reality_client_hello_msg_ext(
        sni,
        &client_random,
        &[0u8; 32],
        &client_pub,
        fingerprint,
    );

    // 9. Encrypt plaintext using AES-256-GCM:
    //    Key: auth_key (32 bytes)
    //    Nonce: client_random[20..32] (12 bytes)
    //    AAD: handshake_msg (the raw ClientHello message with 32 zeros in Session ID)
    let unbound_key = UnboundKey::new(&AES_256_GCM, &auth_key)
        .map_err(|_| "REALITY: Failed to initialize AES-256-GCM key".to_string())?;
    let aead_key = LessSafeKey::new(unbound_key);
    let nonce = Nonce::try_assume_unique_for_key(&client_random[20..32])
        .map_err(|_| "REALITY: Invalid 12-byte nonce for AES-256-GCM".to_string())?;

    let mut sealed_session_id = plaintext.to_vec();
    aead_key
        .seal_in_place_append_tag(nonce, Aad::from(&handshake_msg), &mut sealed_session_id)
        .map_err(|_| "REALITY: Failed to seal authentication tag into Session ID".to_string())?;

    if sealed_session_id.len() != 32 {
        return Err("REALITY: Sealed session ID length is not 32 bytes".to_string());
    }

    // Overwrite the 32 zeros in handshake_msg with the encrypted Session ID
    handshake_msg[session_id_offset..session_id_offset + 32].copy_from_slice(&sealed_session_id);

    // 10. Wrap handshake_msg into a TLS 1.3 Record:
    //     Content Type: 0x16 (Handshake), Legacy Record Version: 0x03, 0x01
    let mut tls_record = Vec::with_capacity(5 + handshake_msg.len());
    tls_record.extend_from_slice(&[0x16, 0x03, 0x01]);
    let rec_len = handshake_msg.len() as u16;
    tls_record.extend_from_slice(&rec_len.to_be_bytes());
    tls_record.extend_from_slice(&handshake_msg);

    // 11. Transmit ClientHello over TCP stream (fragmented into 2 TCP packets to blind DPI SNI inspection)
    let split_point = 64.min(tls_record.len().saturating_sub(1));
    let write_res = tokio::time::timeout(timeout, async {
        stream.write_all(&tls_record[..split_point]).await?;
        stream.flush().await?;
        tokio::time::sleep(Duration::from_millis(3)).await;
        stream.write_all(&tls_record[split_point..]).await?;
        stream.flush().await
    })
    .await;

    match write_res {
        Ok(Ok(())) => {}
        Ok(Err(e)) => return Err(format!("REALITY: Failed to write ClientHello: {}", e)),
        Err(_) => return Err("REALITY: Timeout writing ClientHello".to_string()),
    }

    // Initialize transcript with ClientHello handshake message (without 5-byte TLS record header)
    let mut transcript = Vec::new();
    transcript.extend_from_slice(&handshake_msg);

    // 12. Read ServerHello record header (5 bytes)
    let mut resp_header = [0u8; 5];
    match tokio::time::timeout(timeout, stream.read_exact(&mut resp_header)).await {
        Ok(Ok(_)) => {}
        Ok(Err(e)) => return Err(format!("REALITY: Failed to read ServerHello header: {}", e)),
        Err(_) => return Err("REALITY: Timeout waiting for ServerHello".to_string()),
    }

    // 13. Verify TLS record content type
    if resp_header[0] == 0x15 {
        let mut alert_body = [0u8; 2];
        let _ = stream.read_exact(&mut alert_body).await;
        return Err(format!(
            "REALITY: Server rejected handshake with TLS Alert (level={}, description={})",
            alert_body[0], alert_body[1]
        ));
    }

    if resp_header[0] != 0x16 {
        return Err(format!(
            "REALITY: Unexpected response content type 0x{:02x} (expected 0x16 Handshake)",
            resp_header[0]
        ));
    }

    let record_len = u16::from_be_bytes([resp_header[3], resp_header[4]]) as usize;
    if record_len > 16384 || record_len < 44 {
        return Err(format!(
            "REALITY: ServerHello record size invalid: {} (min 44)",
            record_len
        ));
    }

    let mut record_body = vec![0u8; record_len];
    match tokio::time::timeout(timeout, stream.read_exact(&mut record_body)).await {
        Ok(Ok(_)) => {}
        Ok(Err(e)) => return Err(format!("REALITY: Failed to read ServerHello body: {}", e)),
        Err(_) => return Err("REALITY: Timeout reading ServerHello body".to_string()),
    }

    // 14. Verify ServerHello handshake message structure & parse TLS 1.3 parameters
    if record_body.is_empty() || record_body[0] != 0x02 {
        return Err("REALITY: Expected ServerHello (type 0x02)".to_string());
    }

    verify_server_hello(&record_body)?;
    let (cipher_suite, _server_random, server_ephemeral_pub) = parse_server_hello(&record_body)?;

    // Add ServerHello handshake message to transcript
    transcript.extend_from_slice(&record_body);

    // 15. Check for and consume optional TLS 1.3 dummy ChangeCipherSpec record [0x14, 0x03, 0x03, 0x00, 0x01, 0x01]
    let mut peek_buf = [0u8; 6];
    if let Ok(Ok(6)) =
        tokio::time::timeout(Duration::from_millis(60), stream.peek(&mut peek_buf)).await
    {
        if peek_buf == [0x14, 0x03, 0x03, 0x00, 0x01, 0x01] {
            let mut discard = [0u8; 6];
            let _ = stream.read_exact(&mut discard).await;
            ldebug!("REALITY: Consumed TLS 1.3 dummy ChangeCipherSpec record");
        }
    }

    // 16. Compute DHE shared secret: X25519(client_priv, server_ephemeral_pub)
    let dhe_secret = crate::awg::x25519(&client_priv, &server_ephemeral_pub);

    // 17. RFC 8446 TLS 1.3 Key Schedule Derivations
    let (key_len, hash_len) = cipher_suite_params(cipher_suite)?;
    let early_secret = hkdf_extract(cipher_suite, &vec![0u8; hash_len], &vec![0u8; hash_len]);
    let empty_hash = transcript_hash(cipher_suite, b"");
    let derived_early = derive_secret(cipher_suite, &early_secret, "derived", &empty_hash)?;
    let handshake_secret = hkdf_extract(cipher_suite, &derived_early, &dhe_secret);

    let th_sh = transcript_hash(cipher_suite, &transcript);
    let client_hs_traffic_secret =
        derive_secret(cipher_suite, &handshake_secret, "c hs traffic", &th_sh)?;
    let server_hs_traffic_secret =
        derive_secret(cipher_suite, &handshake_secret, "s hs traffic", &th_sh)?;

    let client_hs_key =
        hkdf_expand_label(cipher_suite, &client_hs_traffic_secret, "key", b"", key_len)?;
    let client_hs_iv =
        hkdf_expand_label(cipher_suite, &client_hs_traffic_secret, "iv", b"", 12)?;
    let server_hs_key =
        hkdf_expand_label(cipher_suite, &server_hs_traffic_secret, "key", b"", key_len)?;
    let server_hs_iv =
        hkdf_expand_label(cipher_suite, &server_hs_traffic_secret, "iv", b"", 12)?;

    let server_finished_key =
        hkdf_expand_label(cipher_suite, &server_hs_traffic_secret, "finished", b"", hash_len)?;
    let client_finished_key =
        hkdf_expand_label(cipher_suite, &client_hs_traffic_secret, "finished", b"", hash_len)?;

    let mut client_hs_iv_arr = [0u8; 12];
    client_hs_iv_arr.copy_from_slice(&client_hs_iv);
    let mut server_hs_iv_arr = [0u8; 12];
    server_hs_iv_arr.copy_from_slice(&server_hs_iv);

    // 18. Read and Decrypt Server Handshake Messages:
    //     EncryptedExtensions (0x08), Certificate (0x0b), CertificateVerify (0x0f), Finished (0x14)
    let mut hs_buf = Vec::new();
    let mut server_hs_seq = 0u64;
    let mut encrypted_extensions_seen = false;
    let mut certificate_seen = false;
    let mut certificate_verify_seen = false;
    let mut finished_seen = false;

    while !finished_seen {
        while hs_buf.len() >= 4 {
            let msg_type = hs_buf[0];
            let msg_len =
                ((hs_buf[1] as usize) << 16) | ((hs_buf[2] as usize) << 8) | (hs_buf[3] as usize);
            if hs_buf.len() < 4 + msg_len {
                break;
            }
            let msg = &hs_buf[..4 + msg_len];

            match msg_type {
                0x08 => {
                    // EncryptedExtensions
                    transcript.extend_from_slice(msg);
                    encrypted_extensions_seen = true;
                }
                0x0b => {
                    // Certificate
                    if !encrypted_extensions_seen {
                        return Err(
                            "REALITY: Received Certificate before EncryptedExtensions".to_string()
                        );
                    }
                    verify_reality_certificate(&msg[4..], &auth_key)?;
                    transcript.extend_from_slice(msg);
                    certificate_seen = true;
                }
                0x0f => {
                    // CertificateVerify
                    if !certificate_seen {
                        return Err(
                            "REALITY: Received CertificateVerify before Certificate".to_string()
                        );
                    }
                    transcript.extend_from_slice(msg);
                    certificate_verify_seen = true;
                }
                0x14 => {
                    // Finished
                    if !certificate_verify_seen {
                        return Err(
                            "REALITY: Received Finished before CertificateVerify".to_string()
                        );
                    }
                    let th = transcript_hash(cipher_suite, &transcript);
                    let expected_verify =
                        compute_finished_verify_data(cipher_suite, &server_finished_key, &th)?;
                    let verify_data = &msg[4..];
                    if verify_data != expected_verify.as_slice() {
                        return Err("REALITY: Server Finished verify_data mismatch".to_string());
                    }
                    transcript.extend_from_slice(msg);
                    finished_seen = true;
                }
                _ => {
                    return Err(format!(
                        "REALITY: Unexpected server handshake message type: 0x{:02x}",
                        msg_type
                    ));
                }
            }

            hs_buf.drain(..4 + msg_len);
            if finished_seen {
                break;
            }
        }

        if finished_seen {
            break;
        }

        let record_payload = read_and_decrypt_hs_record(
            &mut stream,
            cipher_suite,
            &server_hs_key,
            &server_hs_iv_arr,
            &mut server_hs_seq,
            timeout,
        )
        .await?;
        hs_buf.extend_from_slice(&record_payload);
    }

    // 19. Send Client Finished message
    let th_client = transcript_hash(cipher_suite, &transcript);
    let client_verify_data =
        compute_finished_verify_data(cipher_suite, &client_finished_key, &th_client)?;

    let mut client_finished_msg = Vec::with_capacity(4 + client_verify_data.len());
    client_finished_msg.push(0x14); // Finished
    let clen = client_verify_data.len() as u32;
    client_finished_msg.push((clen >> 16) as u8);
    client_finished_msg.push((clen >> 8) as u8);
    client_finished_msg.push(clen as u8);
    client_finished_msg.extend_from_slice(&client_verify_data);

    // Send dummy ChangeCipherSpec record per RFC 8446 Section D.4
    let _ = stream.write_all(&[0x14, 0x03, 0x03, 0x00, 0x01, 0x01]).await;

    // Encrypt Client Finished in TLS record
    let mut cf_in_out = client_finished_msg.clone();
    cf_in_out.push(0x16); // Inner content type: Handshake
    let cf_nonce = client_hs_iv_arr; // sequence 0
    let cf_ct_len = (cf_in_out.len() + 16) as u16;
    let cf_header = [0x17, 0x03, 0x03, (cf_ct_len >> 8) as u8, cf_ct_len as u8];
    aead_seal(cipher_suite, &client_hs_key, &cf_nonce, &cf_header, &mut cf_in_out)?;

    let mut cf_record = Vec::with_capacity(5 + cf_in_out.len());
    cf_record.extend_from_slice(&cf_header);
    cf_record.extend_from_slice(&cf_in_out);

    let send_cf_res = tokio::time::timeout(timeout, stream.write_all(&cf_record)).await;
    match send_cf_res {
        Ok(Ok(())) => {}
        Ok(Err(e)) => return Err(format!("REALITY: Failed to write Client Finished: {}", e)),
        Err(_) => return Err("REALITY: Timeout writing Client Finished".to_string()),
    }
    let _ = stream.flush().await;

    transcript.extend_from_slice(&client_finished_msg);

    // 20. Derive Application Traffic Secrets
    let derived_hs = derive_secret(cipher_suite, &handshake_secret, "derived", &empty_hash)?;
    let master_secret = hkdf_extract(cipher_suite, &derived_hs, &vec![0u8; hash_len]);
    let th_app = transcript_hash(cipher_suite, &transcript);
    let client_app_secret =
        derive_secret(cipher_suite, &master_secret, "c ap traffic", &th_app)?;
    let server_app_secret =
        derive_secret(cipher_suite, &master_secret, "s ap traffic", &th_app)?;

    let client_app_key =
        hkdf_expand_label(cipher_suite, &client_app_secret, "key", b"", key_len)?;
    let client_app_iv =
        hkdf_expand_label(cipher_suite, &client_app_secret, "iv", b"", 12)?;
    let server_app_key =
        hkdf_expand_label(cipher_suite, &server_app_secret, "key", b"", key_len)?;
    let server_app_iv =
        hkdf_expand_label(cipher_suite, &server_app_secret, "iv", b"", 12)?;

    let mut client_app_iv_arr = [0u8; 12];
    client_app_iv_arr.copy_from_slice(&client_app_iv);
    let mut server_app_iv_arr = [0u8; 12];
    server_app_iv_arr.copy_from_slice(&server_app_iv);

    linfo!(
        "REALITY: TLS 1.3 handshake completed, peer certificate authenticated with AuthKey, and stream established with sni={}",
        sni
    );

    let mut real_stream = RealityStream::new(
        stream,
        server_app_key,
        server_app_iv_arr,
        client_app_key,
        client_app_iv_arr,
        cipher_suite,
    );

    if !hs_buf.is_empty() {
        real_stream.read_buf = hs_buf;
    }

    Ok(real_stream)
}

pub async fn reality_connect(
    stream: TcpStream,
    sni: &str,
    public_key_str: &str,
    short_id_str: &str,
    timeout: Duration,
) -> Result<RealityStream, String> {
    reality_connect_ext(stream, sni, public_key_str, short_id_str, "chrome", timeout).await
}

// ---------------------------------------------------------------------------
// ServerHello Parsing & Safe Bounds Checking (Fixes B02)
// ---------------------------------------------------------------------------

pub fn verify_server_hello(body: &[u8]) -> Result<(), String> {
    if body.len() < 44 {
        return Err(format!("ServerHello too short: {} bytes (min 44)", body.len()));
    }
    let (header, rest) = body
        .split_at_checked(4)
        .ok_or_else(|| "ServerHello truncated header".to_string())?;
    if header[0] != 0x02 {
        return Err(format!(
            "ServerHello unexpected handshake type: 0x{:02x} (expected 0x02)",
            header[0]
        ));
    }
    let hs_len = ((header[1] as usize) << 16) | ((header[2] as usize) << 8) | (header[3] as usize);
    if hs_len < 40 {
        return Err(format!(
            "ServerHello handshake length too small: {} (min 40)",
            hs_len
        ));
    }
    let (payload, _trailing) = rest
        .split_at_checked(hs_len)
        .ok_or_else(|| format!(
            "ServerHello body length mismatch: declared {}, available {}",
            hs_len,
            rest.len()
        ))?;

    // legacy_version (2 bytes): must be 0x03, 0x03
    let (version, rem) = payload
        .split_at_checked(2)
        .ok_or_else(|| "ServerHello truncated legacy_version".to_string())?;
    if version != [0x03, 0x03] {
        return Err(format!("ServerHello invalid legacy_version: {:?}", version));
    }

    // random (32 bytes)
    let (_random, rem) = rem
        .split_at_checked(32)
        .ok_or_else(|| "ServerHello truncated random".to_string())?;

    // legacy_session_id_echo (1 byte len + sid_len bytes)
    let (sid_len_slice, rem) = rem
        .split_at_checked(1)
        .ok_or_else(|| "ServerHello truncated session ID length".to_string())?;
    let sid_len = sid_len_slice[0] as usize;
    if sid_len > 32 {
        return Err(format!(
            "ServerHello invalid session ID length: {} (max 32)",
            sid_len
        ));
    }
    let (_sid, rem) = rem
        .split_at_checked(sid_len)
        .ok_or_else(|| "ServerHello truncated session ID payload".to_string())?;

    // cipher_suite (2 bytes)
    let (_cs, rem) = rem
        .split_at_checked(2)
        .ok_or_else(|| "ServerHello truncated cipher suite".to_string())?;

    // legacy_compression_method (1 byte): must be 0x00
    let (comp, rem) = rem
        .split_at_checked(1)
        .ok_or_else(|| "ServerHello truncated compression method".to_string())?;
    if comp[0] != 0x00 {
        return Err(format!(
            "ServerHello invalid compression method: 0x{:02x}",
            comp[0]
        ));
    }

    // extensions (2 bytes total length + extensions payload)
    let (ext_len_slice, rem) = rem
        .split_at_checked(2)
        .ok_or_else(|| "ServerHello truncated extensions length".to_string())?;
    let ext_total_len = u16::from_be_bytes([ext_len_slice[0], ext_len_slice[1]]) as usize;
    let (extensions, rem) = rem
        .split_at_checked(ext_total_len)
        .ok_or_else(|| format!(
            "ServerHello extensions block truncated: declared {}, available {}",
            ext_total_len,
            rem.len()
        ))?;

    if !rem.is_empty() {
        return Err(format!(
            "ServerHello unexpected trailing bytes after extensions: {} bytes",
            rem.len()
        ));
    }

    // Validate nested extension entries safely
    let mut ext_rem = extensions;
    while !ext_rem.is_empty() {
        let (ext_hdr, next) = ext_rem
            .split_at_checked(4)
            .ok_or_else(|| "ServerHello extension entry header truncated".to_string())?;
        let ext_len = u16::from_be_bytes([ext_hdr[2], ext_hdr[3]]) as usize;
        let (_ext_data, next) = next
            .split_at_checked(ext_len)
            .ok_or_else(|| "ServerHello extension entry data truncated".to_string())?;
        ext_rem = next;
    }

    Ok(())
}

pub fn parse_server_hello(record_body: &[u8]) -> Result<(u16, [u8; 32], [u8; 32]), String> {
    // 1. Strict validation with checked slices
    verify_server_hello(record_body)?;

    // 2. Safe field extraction
    let (_header, rest) = record_body
        .split_at_checked(4)
        .ok_or_else(|| "ServerHello truncated header".to_string())?;
    let (_version, rest) = rest
        .split_at_checked(2)
        .ok_or_else(|| "ServerHello truncated version".to_string())?;
    let (random_slice, rest) = rest
        .split_at_checked(32)
        .ok_or_else(|| "ServerHello truncated random".to_string())?;
    let mut server_random = [0u8; 32];
    server_random.copy_from_slice(random_slice);

    let (sid_len_slice, rest) = rest
        .split_at_checked(1)
        .ok_or_else(|| "ServerHello truncated session ID length".to_string())?;
    let sid_len = sid_len_slice[0] as usize;
    let (_sid, rest) = rest
        .split_at_checked(sid_len)
        .ok_or_else(|| "ServerHello truncated session ID".to_string())?;

    let (cs_slice, rest) = rest
        .split_at_checked(2)
        .ok_or_else(|| "ServerHello truncated cipher suite".to_string())?;
    let cipher_suite = u16::from_be_bytes([cs_slice[0], cs_slice[1]]);

    let (_comp, rest) = rest
        .split_at_checked(1)
        .ok_or_else(|| "ServerHello truncated compression".to_string())?;
    let (ext_len_slice, rest) = rest
        .split_at_checked(2)
        .ok_or_else(|| "ServerHello truncated extensions length".to_string())?;
    let ext_total_len = u16::from_be_bytes([ext_len_slice[0], ext_len_slice[1]]) as usize;
    let (extensions, _) = rest
        .split_at_checked(ext_total_len)
        .ok_or_else(|| "ServerHello extensions truncated".to_string())?;

    let mut ext_rem = extensions;
    let mut has_tls13_version = false;
    let mut server_key_share: Option<[u8; 32]> = None;

    while !ext_rem.is_empty() {
        let (ext_hdr, next) = ext_rem
            .split_at_checked(4)
            .ok_or_else(|| "ServerHello extension header truncated".to_string())?;
        let ext_type = u16::from_be_bytes([ext_hdr[0], ext_hdr[1]]);
        let ext_len = u16::from_be_bytes([ext_hdr[2], ext_hdr[3]]) as usize;
        let (ext_data, next) = next
            .split_at_checked(ext_len)
            .ok_or_else(|| "ServerHello extension data truncated".to_string())?;
        ext_rem = next;

        match ext_type {
            0x002b => {
                // supported_versions: 0x0304 (TLS 1.3)
                if ext_data.len() >= 2 && ext_data[0] == 0x03 && ext_data[1] == 0x04 {
                    has_tls13_version = true;
                }
            }
            0x0033 => {
                // key_share: group (2 bytes), kex_len (2 bytes), key_exchange (kex_len bytes)
                if let Some((group_bytes, key_rem)) = ext_data.split_at_checked(2) {
                    let group = u16::from_be_bytes([group_bytes[0], group_bytes[1]]);
                    if let Some((kex_len_bytes, key_bytes)) = key_rem.split_at_checked(2) {
                        let kex_len =
                            u16::from_be_bytes([kex_len_bytes[0], kex_len_bytes[1]]) as usize;
                        if group == 0x001d && kex_len == 32 && key_bytes.len() >= 32 {
                            let mut pk = [0u8; 32];
                            pk.copy_from_slice(&key_bytes[..32]);
                            server_key_share = Some(pk);
                        }
                    }
                }
            }
            _ => {}
        }
    }

    if !has_tls13_version {
        return Err("REALITY: ServerHello does not negotiate TLS 1.3".to_string());
    }

    let key_share = server_key_share
        .ok_or_else(|| "REALITY: ServerHello missing X25519 key_share extension".to_string())?;

    Ok((cipher_suite, server_random, key_share))
}

// ---------------------------------------------------------------------------
// Encrypted Handshake Record Reader
// ---------------------------------------------------------------------------

async fn read_and_decrypt_hs_record(
    stream: &mut TcpStream,
    cipher_suite: u16,
    server_hs_key: &[u8],
    server_hs_iv: &[u8; 12],
    server_hs_seq: &mut u64,
    timeout: Duration,
) -> Result<Vec<u8>, String> {
    loop {
        let mut header = [0u8; 5];
        match tokio::time::timeout(timeout, stream.read_exact(&mut header)).await {
            Ok(Ok(_)) => {}
            Ok(Err(e)) => return Err(format!("REALITY: Failed to read handshake record header: {}", e)),
            Err(_) => return Err("REALITY: Timeout reading handshake record header".to_string()),
        }

        if header[0] == 0x14 {
            // Dummy ChangeCipherSpec record: read body and skip
            let rec_len = u16::from_be_bytes([header[3], header[4]]) as usize;
            let mut discard = vec![0u8; rec_len];
            let _ = stream.read_exact(&mut discard).await;
            continue;
        }

        if header[0] == 0x15 {
            let mut alert = [0u8; 2];
            let _ = stream.read_exact(&mut alert).await;
            return Err(format!(
                "REALITY: Server sent TLS alert: level={}, desc={}",
                alert[0], alert[1]
            ));
        }

        if header[0] != 0x17 {
            return Err(format!(
                "REALITY: Expected encrypted handshake record (0x17), got 0x{:02x}",
                header[0]
            ));
        }

        let rec_len = u16::from_be_bytes([header[3], header[4]]) as usize;
        if rec_len > 16384 || rec_len < 16 {
            return Err(format!("REALITY: Invalid handshake record length: {}", rec_len));
        }

        let mut ciphertext = vec![0u8; rec_len];
        match tokio::time::timeout(timeout, stream.read_exact(&mut ciphertext)).await {
            Ok(Ok(_)) => {}
            Ok(Err(e)) => return Err(format!("REALITY: Failed to read handshake record body: {}", e)),
            Err(_) => return Err("REALITY: Timeout reading handshake record body".to_string()),
        }

        let mut nonce = *server_hs_iv;
        let seq_be = server_hs_seq.to_be_bytes();
        for i in 0..8 {
            nonce[4 + i] ^= seq_be[i];
        }
        *server_hs_seq += 1;

        aead_open(cipher_suite, server_hs_key, &nonce, &header, &mut ciphertext)?;

        let mut end = ciphertext.len();
        while end > 0 && ciphertext[end - 1] == 0 {
            end -= 1;
        }
        if end == 0 {
            return Err("REALITY: Handshake record missing inner content type".to_string());
        }
        let inner_type = ciphertext[end - 1];
        let payload = ciphertext[..end - 1].to_vec();

        if inner_type == 0x16 {
            return Ok(payload);
        } else if inner_type == 0x15 {
            return Err(format!("REALITY: Server sent inner TLS Alert: {:?}", payload));
        } else {
            return Err(format!(
                "REALITY: Unexpected inner content type during handshake: 0x{:02x}",
                inner_type
            ));
        }
    }
}

// ---------------------------------------------------------------------------
// REALITY Peer Certificate Verification (HMAC-SHA512 against AuthKey)
// ---------------------------------------------------------------------------

pub fn verify_reality_certificate(cert_body: &[u8], auth_key: &[u8; 32]) -> Result<(), String> {
    let (context_len_slice, rem) = cert_body
        .split_at_checked(1)
        .ok_or_else(|| "Certificate message empty".to_string())?;
    let context_len = context_len_slice[0] as usize;
    let (_ctx, rem) = rem
        .split_at_checked(context_len)
        .ok_or_else(|| "Certificate message truncated at context".to_string())?;
    let (list_len_slice, rem) = rem
        .split_at_checked(3)
        .ok_or_else(|| "Certificate message truncated at list len".to_string())?;
    let list_len = ((list_len_slice[0] as usize) << 16)
        | ((list_len_slice[1] as usize) << 8)
        | (list_len_slice[2] as usize);
    let (cert_list, _rem) = rem
        .split_at_checked(list_len)
        .ok_or_else(|| "Certificate list truncated".to_string())?;
    if list_len < 3 {
        return Err("Certificate list empty".to_string());
    }

    let (cert_data_len_slice, rem) = cert_list
        .split_at_checked(3)
        .ok_or_else(|| "CertificateEntry truncated at cert len".to_string())?;
    let cert_data_len = ((cert_data_len_slice[0] as usize) << 16)
        | ((cert_data_len_slice[1] as usize) << 8)
        | (cert_data_len_slice[2] as usize);
    let (cert_der, _rem) = rem
        .split_at_checked(cert_data_len)
        .ok_or_else(|| "CertificateEntry data truncated".to_string())?;

    let (ed_pub, ed_sig) = extract_ed25519_cert_pubkey_and_signature(cert_der)?;

    let mut mac = HmacSha512::new_from_slice(auth_key)
        .map_err(|_| "HMAC-SHA512 initialization failed".to_string())?;
    mac.update(&ed_pub);
    let expected = mac.finalize().into_bytes();

    if expected.as_slice() != &ed_sig {
        return Err(
            "REALITY: Server authentication failed: peer certificate signature does not match REALITY AuthKey (decoy or unauthenticated server)"
                .to_string(),
        );
    }

    ldebug!("REALITY: Peer certificate successfully verified against AuthKey HMAC-SHA512");
    Ok(())
}

pub fn parse_der_element<'a>(input: &'a [u8]) -> Result<(u8, &'a [u8], &'a [u8]), String> {
    let (tag_slice, rem) = input
        .split_at_checked(1)
        .ok_or_else(|| "DER: empty input".to_string())?;
    let tag = tag_slice[0];
    let (len_lead_slice, rem) = rem
        .split_at_checked(1)
        .ok_or_else(|| "DER: truncated header".to_string())?;
    let len_lead = len_lead_slice[0];

    let (len, rem) = if len_lead & 0x80 == 0 {
        (len_lead as usize, rem)
    } else {
        let num_octets = (len_lead & 0x7f) as usize;
        if num_octets == 0 || num_octets > 4 {
            return Err("DER: invalid length octets".to_string());
        }
        let (len_bytes, rem) = rem
            .split_at_checked(num_octets)
            .ok_or_else(|| "DER: truncated length octets".to_string())?;
        let mut l = 0usize;
        for &b in len_bytes {
            l = (l << 8) | (b as usize);
        }
        (l, rem)
    };

    let (value, rest) = rem
        .split_at_checked(len)
        .ok_or_else(|| "DER: content truncated".to_string())?;
    Ok((tag, value, rest))
}

pub fn extract_ed25519_cert_pubkey_and_signature(der: &[u8]) -> Result<([u8; 32], [u8; 64]), String> {
    let (tag, cert_body, _) = parse_der_element(der)?;
    if tag != 0x30 {
        return Err("Certificate DER must start with SEQUENCE (0x30)".to_string());
    }

    let (tag1, tbs_body, rest1) = parse_der_element(cert_body)?;
    if tag1 != 0x30 {
        return Err("TBSCertificate must be SEQUENCE (0x30)".to_string());
    }
    let (_tag2, _sig_alg_body, rest2) = parse_der_element(rest1)?;
    let (tag3, sig_value, _) = parse_der_element(rest2)?;
    if tag3 != 0x03 {
        return Err("SignatureValue must be BIT STRING (0x03)".to_string());
    }
    if sig_value.len() != 65 || sig_value[0] != 0 {
        return Err("SignatureValue is not 64-byte Ed25519 signature".to_string());
    }
    let mut signature = [0u8; 64];
    signature.copy_from_slice(&sig_value[1..65]);

    let mut tbs_rem = tbs_body;
    let mut spki_body: Option<&[u8]> = None;
    let mut seq_count = 0;

    while !tbs_rem.is_empty() {
        let (elem_tag, elem_val, elem_rem) = parse_der_element(tbs_rem)?;
        tbs_rem = elem_rem;
        if elem_tag == 0x30 {
            seq_count += 1;
            // Standard X.509 TBS structure:
            // 1 = signature, 2 = issuer, 3 = validity, 4 = subject, 5 = subjectPublicKeyInfo
            if seq_count == 5 {
                spki_body = Some(elem_val);
                break;
            }
        }
    }

    let spki = spki_body.ok_or_else(|| "SubjectPublicKeyInfo not found in TBSCertificate".to_string())?;
    let (_alg_tag, _alg_val, spki_rem) = parse_der_element(spki)?;
    let (spk_tag, spk_val, _) = parse_der_element(spki_rem)?;
    if spk_tag != 0x03 {
        return Err("SubjectPublicKey must be BIT STRING (0x03)".to_string());
    }
    if spk_val.len() != 33 || spk_val[0] != 0 {
        return Err("SubjectPublicKey is not 32-byte Ed25519 public key".to_string());
    }
    let mut pubkey = [0u8; 32];
    pubkey.copy_from_slice(&spk_val[1..33]);

    Ok((pubkey, signature))
}

// ---------------------------------------------------------------------------
// AEAD Encryption & Decryption Helpers
// ---------------------------------------------------------------------------

fn get_aead_algorithm(cipher_suite: u16) -> Result<&'static ring::aead::Algorithm, String> {
    match cipher_suite {
        0x1301 => Ok(&AES_128_GCM),
        0x1302 => Ok(&AES_256_GCM),
        0x1303 => Ok(&CHACHA20_POLY1305),
        _ => Err(format!("Unsupported TLS 1.3 cipher suite: 0x{:04x}", cipher_suite)),
    }
}

pub fn aead_seal(
    cipher_suite: u16,
    key_bytes: &[u8],
    nonce_bytes: &[u8; 12],
    aad_bytes: &[u8],
    in_out: &mut Vec<u8>,
) -> Result<(), String> {
    let alg = get_aead_algorithm(cipher_suite)?;
    let unbound = UnboundKey::new(alg, key_bytes)
        .map_err(|_| "AEAD: Invalid key length".to_string())?;
    let key = LessSafeKey::new(unbound);
    let nonce = Nonce::try_assume_unique_for_key(nonce_bytes)
        .map_err(|_| "AEAD: Invalid nonce length".to_string())?;
    key.seal_in_place_append_tag(nonce, Aad::from(aad_bytes), in_out)
        .map_err(|_| "AEAD: Encryption failed".to_string())?;
    Ok(())
}

pub fn aead_open(
    cipher_suite: u16,
    key_bytes: &[u8],
    nonce_bytes: &[u8; 12],
    aad_bytes: &[u8],
    in_out: &mut Vec<u8>,
) -> Result<(), String> {
    let alg = get_aead_algorithm(cipher_suite)?;
    let unbound = UnboundKey::new(alg, key_bytes)
        .map_err(|_| "AEAD: Invalid key length".to_string())?;
    let key = LessSafeKey::new(unbound);
    let nonce = Nonce::try_assume_unique_for_key(nonce_bytes)
        .map_err(|_| "AEAD: Invalid nonce length".to_string())?;
    let decrypted = key
        .open_in_place(nonce, Aad::from(aad_bytes), in_out)
        .map_err(|_| "AEAD: Decryption failed (MAC check failed)".to_string())?;
    let dlen = decrypted.len();
    in_out.truncate(dlen);
    Ok(())
}

// ---------------------------------------------------------------------------
// RFC 8446 TLS 1.3 HKDF Key Schedule Implementation
// ---------------------------------------------------------------------------

pub fn cipher_suite_params(cipher_suite: u16) -> Result<(usize, usize), String> {
    match cipher_suite {
        0x1301 => Ok((16, 32)), // AES_128_GCM, SHA-256
        0x1302 => Ok((32, 48)), // AES_256_GCM, SHA-384
        0x1303 => Ok((32, 32)), // CHACHA20_POLY1305, SHA-256
        _ => Err(format!("Unsupported TLS 1.3 cipher suite: 0x{:04x}", cipher_suite)),
    }
}

pub fn hkdf_extract(cipher_suite: u16, salt: &[u8], ikm: &[u8]) -> Vec<u8> {
    let is_sha384 = cipher_suite == 0x1302;
    if is_sha384 {
        let mut mac = HmacSha384::new_from_slice(salt).expect("HMAC-SHA384 takes any salt length");
        mac.update(ikm);
        mac.finalize().into_bytes().to_vec()
    } else {
        let mut mac = HmacSha256::new_from_slice(salt).expect("HMAC-SHA256 takes any salt length");
        mac.update(ikm);
        mac.finalize().into_bytes().to_vec()
    }
}

pub fn hkdf_expand(
    cipher_suite: u16,
    prk: &[u8],
    info: &[u8],
    length: usize,
) -> Result<Vec<u8>, String> {
    let is_sha384 = cipher_suite == 0x1302;
    let hash_len = if is_sha384 { 48 } else { 32 };
    let n = (length + hash_len - 1) / hash_len;
    if n > 255 {
        return Err("HKDF-Expand: length too long".to_string());
    }

    let mut okm = Vec::with_capacity(n * hash_len);
    let mut t_prev = Vec::new();

    for i in 1..=n {
        let mut hmac_input = Vec::with_capacity(t_prev.len() + info.len() + 1);
        hmac_input.extend_from_slice(&t_prev);
        hmac_input.extend_from_slice(info);
        hmac_input.push(i as u8);

        let t_i = if is_sha384 {
            let mut mac = HmacSha384::new_from_slice(prk)
                .map_err(|_| "HMAC init failed".to_string())?;
            mac.update(&hmac_input);
            mac.finalize().into_bytes().to_vec()
        } else {
            let mut mac = HmacSha256::new_from_slice(prk)
                .map_err(|_| "HMAC init failed".to_string())?;
            mac.update(&hmac_input);
            mac.finalize().into_bytes().to_vec()
        };

        okm.extend_from_slice(&t_i);
        t_prev = t_i;
    }

    okm.truncate(length);
    Ok(okm)
}

pub fn hkdf_expand_label(
    cipher_suite: u16,
    secret: &[u8],
    label: &str,
    context: &[u8],
    length: usize,
) -> Result<Vec<u8>, String> {
    let full_label = format!("tls13 {}", label);
    let label_bytes = full_label.as_bytes();
    if label_bytes.len() > 255 || context.len() > 255 {
        return Err("HKDF-Expand-Label: label or context too long".to_string());
    }

    let mut hkdf_label = Vec::with_capacity(2 + 1 + label_bytes.len() + 1 + context.len());
    hkdf_label.extend_from_slice(&(length as u16).to_be_bytes());
    hkdf_label.push(label_bytes.len() as u8);
    hkdf_label.extend_from_slice(label_bytes);
    hkdf_label.push(context.len() as u8);
    hkdf_label.extend_from_slice(context);

    hkdf_expand(cipher_suite, secret, &hkdf_label, length)
}

pub fn derive_secret(
    cipher_suite: u16,
    secret: &[u8],
    label: &str,
    transcript_hash: &[u8],
) -> Result<Vec<u8>, String> {
    let (_, hash_len) = cipher_suite_params(cipher_suite)?;
    hkdf_expand_label(cipher_suite, secret, label, transcript_hash, hash_len)
}

pub fn transcript_hash(cipher_suite: u16, transcript: &[u8]) -> Vec<u8> {
    if cipher_suite == 0x1302 {
        let mut h = Sha384::new();
        h.update(transcript);
        h.finalize().to_vec()
    } else {
        let mut h = Sha256::new();
        h.update(transcript);
        h.finalize().to_vec()
    }
}

pub fn compute_finished_verify_data(
    cipher_suite: u16,
    finished_key: &[u8],
    transcript_hash: &[u8],
) -> Result<Vec<u8>, String> {
    let is_sha384 = cipher_suite == 0x1302;
    if is_sha384 {
        let mut mac = HmacSha384::new_from_slice(finished_key)
            .map_err(|_| "HMAC-SHA384 init failed".to_string())?;
        mac.update(transcript_hash);
        Ok(mac.finalize().into_bytes().to_vec())
    } else {
        let mut mac = HmacSha256::new_from_slice(finished_key)
            .map_err(|_| "HMAC-SHA256 init failed".to_string())?;
        mac.update(transcript_hash);
        Ok(mac.finalize().into_bytes().to_vec())
    }
}

// ---------------------------------------------------------------------------
// Key Decoding & Utilities
// ---------------------------------------------------------------------------

pub fn decode_reality_key(s: &str) -> Result<Vec<u8>, String> {
    use base64::Engine;
    if s.len() == 64 {
        if let Ok(h) = hex::decode(s) {
            return Ok(h);
        }
    }
    if let Ok(b) = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(s) {
        return Ok(b);
    }
    if let Ok(b) = base64::engine::general_purpose::URL_SAFE.decode(s) {
        return Ok(b);
    }
    if let Ok(b) = base64::engine::general_purpose::STANDARD.decode(s) {
        return Ok(b);
    }
    Err("Failed to decode base64/hex key".to_string())
}

pub fn decode_reality_short_id(s: &str) -> Vec<u8> {
    if s.is_empty() {
        return Vec::new();
    }
    if let Ok(h) = hex::decode(s) {
        return h;
    }
    s.as_bytes().to_vec()
}

pub fn hkdf_sha256(salt: &[u8], ikm: &[u8], info: &[u8]) -> [u8; 32] {
    let mut extract_mac =
        HmacSha256::new_from_slice(salt).expect("HMAC-SHA256 takes any key length");
    extract_mac.update(ikm);
    let prk = extract_mac.finalize().into_bytes();

    let mut expand_mac = HmacSha256::new_from_slice(&prk).expect("HMAC-SHA256 takes PRK");
    expand_mac.update(info);
    expand_mac.update(&[0x01]);
    let okm = expand_mac.finalize().into_bytes();

    let mut out = [0u8; 32];
    out.copy_from_slice(&okm);
    out
}

pub const GREASE_VALUES: [u16; 16] = [
    0x0a0a, 0x1a1a, 0x2a2a, 0x3a3a, 0x4a4a, 0x5a5a, 0x6a6a, 0x7a7a, 0x8a8a, 0x9a9a, 0xaaaa, 0xbaba,
    0xcaca, 0xdada, 0xeaea, 0xfafa,
];

pub fn distinct_grease(count: usize) -> Vec<u16> {
    use rand::seq::SliceRandom;
    let mut pool = GREASE_VALUES.to_vec();
    pool.shuffle(&mut rand::thread_rng());
    pool.truncate(count);
    pool
}

pub fn build_reality_client_hello_msg_ext(
    sni: &str,
    client_random: &[u8; 32],
    session_id: &[u8; 32],
    key_share_pub: &[u8; 32],
    fingerprint: &str,
) -> (Vec<u8>, usize) {
    let mut handshake_body = Vec::with_capacity(512);

    // Legacy Version: TLS 1.2 (0x03, 0x03)
    handshake_body.extend_from_slice(&[0x03, 0x03]);

    // Client Random (32 bytes)
    handshake_body.extend_from_slice(client_random);

    // Session ID length (32) + Session ID (32 bytes)
    handshake_body.push(32);
    handshake_body.extend_from_slice(session_id);

    let is_firefox = fingerprint.trim().eq_ignore_ascii_case("firefox");

    if is_firefox {
        // Firefox cipher suites (15 suites = 30 bytes)
        let ff_ciphers: &[u8] = &[
            0x00, 0x1e, // len: 30
            0x13, 0x01, // TLS_AES_128_GCM_SHA256
            0x13, 0x03, // TLS_CHACHA20_POLY1305_SHA256
            0x13, 0x02, // TLS_AES_256_GCM_SHA384
            0xc0, 0x2b, // TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
            0xc0, 0x2f, // TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
            0xcc, 0xa9, // TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256
            0xcc, 0xa8, // TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
            0xc0, 0x2c, // TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
            0xc0, 0x30, // TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
            0xc0, 0x13, // TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA
            0xc0, 0x14, // TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA
            0x00, 0x9c, // TLS_RSA_WITH_AES_128_GCM_SHA256
            0x00, 0x9d, // TLS_RSA_WITH_AES_256_GCM_SHA384
            0x00, 0x2f, // TLS_RSA_WITH_AES_128_CBC_SHA
            0x00, 0x35, // TLS_RSA_WITH_AES_256_CBC_SHA
        ];
        handshake_body.extend_from_slice(ff_ciphers);
    } else {
        // Chrome / Safari with RFC 8701 GREASE
        let grease = distinct_grease(5);
        let grease_cipher = grease[0];
        let mut ciphers = Vec::with_capacity(34);
        ciphers.extend_from_slice(&[0x00, 0x20]); // len = 32 bytes (16 suites)
        ciphers.extend_from_slice(&grease_cipher.to_be_bytes()); // GREASE cipher suite
        ciphers.extend_from_slice(&[
            0x13, 0x01, // TLS_AES_128_GCM_SHA256
            0x13, 0x02, // TLS_AES_256_GCM_SHA384
            0x13, 0x03, // TLS_CHACHA20_POLY1305_SHA256
            0xc0, 0x2b, // TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
            0xc0, 0x2f, // TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
            0xc0, 0x2c, // TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
            0xc0, 0x30, // TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
            0xcc, 0xa9, // TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256
            0xcc, 0xa8, // TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
            0xc0, 0x13, // TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA
            0xc0, 0x14, // TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA
            0x00, 0x9c, // TLS_RSA_WITH_AES_128_GCM_SHA256
            0x00, 0x9d, // TLS_RSA_WITH_AES_256_GCM_SHA384
            0x00, 0x2f, // TLS_RSA_WITH_AES_128_CBC_SHA
            0x00, 0x35, // TLS_RSA_WITH_AES_256_CBC_SHA
        ]);
        handshake_body.extend_from_slice(&ciphers);
    }

    // Legacy compression methods: 1 byte length (1), 1 byte null (0x00)
    handshake_body.extend_from_slice(&[0x01, 0x00]);

    // Extensions buffer
    let mut extensions = Vec::with_capacity(256);

    if is_firefox {
        // Firefox Extensions Ordering (no GREASE)
        if !sni.is_empty() {
            let sni_bytes = sni.as_bytes();
            let sni_len = sni_bytes.len() as u16;
            let list_len = sni_len + 3;
            let ext_len = list_len + 2;
            extensions.extend_from_slice(&[0x00, 0x00]);
            extensions.extend_from_slice(&ext_len.to_be_bytes());
            extensions.extend_from_slice(&list_len.to_be_bytes());
            extensions.push(0x00);
            extensions.extend_from_slice(&sni_len.to_be_bytes());
            extensions.extend_from_slice(sni_bytes);
        }
        // EMS (0x0017)
        extensions.extend_from_slice(&[0x00, 0x17, 0x00, 0x00]);
        // Renegotiation (0xff01)
        extensions.extend_from_slice(&[0xff, 0x01, 0x00, 0x01, 0x00]);
        // Supported groups: x25519, secp256r1, secp384r1
        extensions.extend_from_slice(&[
            0x00, 0x0a, 0x00, 0x08, 0x00, 0x06, 0x00, 0x1d, 0x00, 0x17, 0x00, 0x18,
        ]);
        // EC point formats
        extensions.extend_from_slice(&[0x00, 0x0b, 0x00, 0x02, 0x01, 0x00]);
        // Session ticket
        extensions.extend_from_slice(&[0x00, 0x23, 0x00, 0x00]);
        // ALPN: h2, http/1.1
        let alpn: &[u8] = &[
            0x00, 0x10, 0x00, 0x0e, 0x00, 0x0c, 0x02, b'h', b'2', 0x08, b'h', b't', b't', b'p',
            b'/', b'1', b'.', b'1',
        ];
        extensions.extend_from_slice(alpn);
        // Status request
        extensions.extend_from_slice(&[0x00, 0x05, 0x00, 0x05, 0x01, 0x00, 0x00, 0x00, 0x00]);
        // Key share: x25519
        let key_share_total_len: u16 = 2 + 2 + 2 + 32; // 38
        extensions.extend_from_slice(&[0x00, 0x33]);
        extensions.extend_from_slice(&key_share_total_len.to_be_bytes());
        extensions.extend_from_slice(&(36u16).to_be_bytes());
        extensions.extend_from_slice(&[0x00, 0x1d]);
        extensions.extend_from_slice(&(32u16).to_be_bytes());
        extensions.extend_from_slice(key_share_pub);
        // Supported versions: TLS 1.3, TLS 1.2
        extensions.extend_from_slice(&[0x00, 0x2b, 0x00, 0x05, 0x04, 0x03, 0x04, 0x03, 0x03]);
        // Signature algorithms
        let sig_algs: &[u8] = &[
            0x00, 0x0d, 0x00, 0x12, 0x00, 0x10, 0x04, 0x03, 0x08, 0x04, 0x04, 0x01, 0x05, 0x03,
            0x08, 0x05, 0x05, 0x01, 0x08, 0x06, 0x06, 0x01,
        ];
        extensions.extend_from_slice(sig_algs);
        // Record size limit
        extensions.extend_from_slice(&[0x00, 0x1c, 0x00, 0x02, 0x40, 0x01]);
    } else {
        // Chrome Extensions Ordering (with RFC 8701 GREASE)
        let grease = distinct_grease(5);
        let grease_group = grease[1];
        let grease_ext = grease[2];
        let grease_ver = grease[3];
        let grease_ext_end = grease[4];

        // 1. GREASE extension (type grease_ext, len 0)
        extensions.extend_from_slice(&grease_ext.to_be_bytes());
        extensions.extend_from_slice(&[0x00, 0x00]);

        // 2. SNI
        if !sni.is_empty() {
            let sni_bytes = sni.as_bytes();
            let sni_len = sni_bytes.len() as u16;
            let list_len = sni_len + 3;
            let ext_len = list_len + 2;
            extensions.extend_from_slice(&[0x00, 0x00]);
            extensions.extend_from_slice(&ext_len.to_be_bytes());
            extensions.extend_from_slice(&list_len.to_be_bytes());
            extensions.push(0x00);
            extensions.extend_from_slice(&sni_len.to_be_bytes());
            extensions.extend_from_slice(sni_bytes);
        }

        // 3. Extended Master Secret
        extensions.extend_from_slice(&[0x00, 0x17, 0x00, 0x00]);

        // 4. Renegotiation Info
        extensions.extend_from_slice(&[0xff, 0x01, 0x00, 0x01, 0x00]);

        // 5. Supported Groups (with GREASE group)
        extensions.extend_from_slice(&[0x00, 0x0a, 0x00, 0x0a, 0x00, 0x08]);
        extensions.extend_from_slice(&grease_group.to_be_bytes());
        extensions.extend_from_slice(&[0x00, 0x1d, 0x00, 0x17, 0x00, 0x18]);

        // 6. EC Point Formats
        extensions.extend_from_slice(&[0x00, 0x0b, 0x00, 0x02, 0x01, 0x00]);

        // 7. Session Ticket
        extensions.extend_from_slice(&[0x00, 0x23, 0x00, 0x00]);

        // 8. ALPN: h2, http/1.1
        let alpn: &[u8] = &[
            0x00, 0x10, 0x00, 0x0e, 0x00, 0x0c, 0x02, b'h', b'2', 0x08, b'h', b't', b't', b'p',
            b'/', b'1', b'.', b'1',
        ];
        extensions.extend_from_slice(alpn);

        // 9. Status Request (OCSP)
        extensions.extend_from_slice(&[0x00, 0x05, 0x00, 0x05, 0x01, 0x00, 0x00, 0x00, 0x00]);

        // 10. Signature Algorithms
        let sig_algs: &[u8] = &[
            0x00, 0x0d, 0x00, 0x12, 0x00, 0x10, 0x04, 0x03, 0x08, 0x04, 0x04, 0x01, 0x05, 0x03,
            0x08, 0x05, 0x05, 0x01, 0x08, 0x06, 0x06, 0x01,
        ];
        extensions.extend_from_slice(sig_algs);

        // 11. Supported Versions (with GREASE version)
        extensions.extend_from_slice(&[0x00, 0x2b, 0x00, 0x07, 0x06]);
        extensions.extend_from_slice(&grease_ver.to_be_bytes());
        extensions.extend_from_slice(&[0x03, 0x04, 0x03, 0x03]);

        // 12. Key Share (with GREASE key_share + x25519)
        let key_share_total_len: u16 = 2 + 2 + (2 + 2 + 1) + (2 + 2 + 32); // 43
        let key_share_list_len: u16 = (2 + 2 + 1) + (2 + 2 + 32); // 41
        extensions.extend_from_slice(&[0x00, 0x33]);
        extensions.extend_from_slice(&key_share_total_len.to_be_bytes());
        extensions.extend_from_slice(&key_share_list_len.to_be_bytes());
        extensions.extend_from_slice(&grease_group.to_be_bytes());
        extensions.extend_from_slice(&[0x00, 0x01, 0x00]);
        extensions.extend_from_slice(&[0x00, 0x1d]);
        extensions.extend_from_slice(&(32u16).to_be_bytes());
        extensions.extend_from_slice(key_share_pub);

        // 13. PSK Key Exchange Modes
        extensions.extend_from_slice(&[0x00, 0x2d, 0x00, 0x02, 0x01, 0x01]);

        // 14. Record Size Limit
        extensions.extend_from_slice(&[0x00, 0x1c, 0x00, 0x02, 0x40, 0x01]);

        // 15. GREASE end extension
        extensions.extend_from_slice(&grease_ext_end.to_be_bytes());
        extensions.extend_from_slice(&[0x00, 0x00]);
    }

    let ext_total_len = extensions.len() as u16;
    handshake_body.extend_from_slice(&ext_total_len.to_be_bytes());
    handshake_body.extend_from_slice(&extensions);

    let mut handshake_msg = Vec::with_capacity(4 + handshake_body.len());
    handshake_msg.push(0x01); // ClientHello
    let hs_len = handshake_body.len() as u32;
    handshake_msg.push((hs_len >> 16) as u8);
    handshake_msg.push((hs_len >> 8) as u8);
    handshake_msg.push(hs_len as u8);
    handshake_msg.extend_from_slice(&handshake_body);

    (handshake_msg, 39)
}

pub fn build_reality_client_hello_msg(
    sni: &str,
    client_random: &[u8; 32],
    session_id: &[u8; 32],
    key_share_pub: &[u8; 32],
) -> (Vec<u8>, usize) {
    build_reality_client_hello_msg_ext(sni, client_random, session_id, key_share_pub, "chrome")
}

// ---------------------------------------------------------------------------
// Unit Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_decode_reality_keys() {
        let hex_key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        let dec_hex = decode_reality_key(hex_key).expect("Valid hex key");
        assert_eq!(dec_hex.len(), 32);
        assert_eq!(dec_hex[0], 0x01);
        assert_eq!(dec_hex[31], 0xef);

        use base64::Engine;
        let b64_std = base64::engine::general_purpose::STANDARD.encode(&dec_hex);
        let dec_b64 = decode_reality_key(&b64_std).expect("Valid standard base64");
        assert_eq!(dec_b64, dec_hex);

        let b64_url = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&dec_hex);
        let dec_url = decode_reality_key(&b64_url).expect("Valid url-safe base64");
        assert_eq!(dec_url, dec_hex);
    }

    #[test]
    fn test_decode_reality_short_id() {
        let sid_hex = "0123456789abcdef";
        let dec = decode_reality_short_id(sid_hex);
        assert_eq!(dec, vec![0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef]);

        let sid_empty = "";
        let dec_empty = decode_reality_short_id(sid_empty);
        assert!(dec_empty.is_empty());
    }

    #[test]
    fn test_hkdf_sha256_reproducibility() {
        let salt = [0x01u8; 20];
        let ikm = [0x02u8; 32];
        let info = b"REALITY";
        let key1 = hkdf_sha256(&salt, &ikm, info);
        let key2 = hkdf_sha256(&salt, &ikm, info);
        assert_eq!(key1, key2);
        assert_ne!(key1, [0u8; 32]);
    }

    #[test]
    fn test_rfc8446_key_schedule_test_vectors() {
        // Test vectors from RFC 8446 Appendix C.1 (TLS_AES_128_GCM_SHA256)
        let early_secret = hkdf_extract(0x1301, &[0u8; 32], &[0u8; 32]);
        let empty_hash = transcript_hash(0x1301, b"");
        let derived = derive_secret(0x1301, &early_secret, "derived", &empty_hash).unwrap();
        let expected_derived =
            hex::decode("6f2615a108c702c5678f54fc9dbab69716c076189c48250cebeac3576c3611ba").unwrap();
        assert_eq!(derived, expected_derived);
    }

    #[test]
    fn test_verify_server_hello_parsing() {
        let mut sh_body = Vec::new();
        sh_body.push(0x02); // ServerHello
        let mut payload = Vec::new();
        payload.extend_from_slice(&[0x03, 0x03]); // version
        payload.extend_from_slice(&[0xaa; 32]); // server random
        payload.push(0); // session id len 0
        payload.extend_from_slice(&[0x13, 0x01]); // cipher suite
        payload.push(0x00); // compression
        payload.extend_from_slice(&[0x00, 0x00]); // extensions len 0
        let hs_len = payload.len() as u32;
        sh_body.push((hs_len >> 16) as u8);
        sh_body.push((hs_len >> 8) as u8);
        sh_body.push(hs_len as u8);
        sh_body.extend_from_slice(&payload);

        assert!(verify_server_hello(&sh_body).is_ok());
        assert!(verify_server_hello(&[0x02, 0x00]).is_err());
    }

    #[test]
    fn test_verify_server_hello_safe_boundary_lengths_0_to_43() {
        // Boundary lengths 0 to 43 MUST gracefully return Err without panic
        for len in 0..=43 {
            let zero_buf = vec![0x00u8; len];
            assert!(
                verify_server_hello(&zero_buf).is_err(),
                "verify_server_hello 0x00 len {} must fail",
                len
            );
            assert!(
                parse_server_hello(&zero_buf).is_err(),
                "parse_server_hello 0x00 len {} must fail",
                len
            );

            let sh_type_buf = vec![0x02u8; len];
            assert!(
                verify_server_hello(&sh_type_buf).is_err(),
                "verify_server_hello 0x02 len {} must fail",
                len
            );
            assert!(
                parse_server_hello(&sh_type_buf).is_err(),
                "parse_server_hello 0x02 len {} must fail",
                len
            );

            let mut rand_buf = vec![0u8; len];
            for i in 0..len {
                rand_buf[i] = ((i * 37 + 19) % 256) as u8;
            }
            assert!(
                verify_server_hello(&rand_buf).is_err(),
                "verify_server_hello rand len {} must fail",
                len
            );
            assert!(
                parse_server_hello(&rand_buf).is_err(),
                "parse_server_hello rand len {} must fail",
                len
            );
        }
    }

    #[test]
    fn test_verify_server_hello_truncated_session_id() {
        // 1. Valid header up to session ID byte, but sid_len = 32 and buffer ends immediately or truncated
        let mut base = Vec::new();
        base.push(0x02);
        base.extend_from_slice(&[0x00, 0x00, 0x50]); // declared 80 bytes
        base.extend_from_slice(&[0x03, 0x03]); // version
        base.extend_from_slice(&[0x11; 32]); // random
        base.push(32); // sid_len = 32

        // Truncate at various points inside session ID (0..32 bytes available)
        for sid_avail in 0..32 {
            let mut buf = base.clone();
            buf.extend_from_slice(&vec![0x42u8; sid_avail]);
            assert!(verify_server_hello(&buf).is_err());
            assert!(parse_server_hello(&buf).is_err());
        }

        // 2. sid_len > 32 (invalid session ID length)
        for bad_sid_len in [33u8, 64, 128, 255] {
            let mut buf = base.clone();
            buf[38] = bad_sid_len;
            buf.extend_from_slice(&vec![0x42u8; bad_sid_len as usize + 20]);
            assert!(verify_server_hello(&buf).is_err());
            assert!(parse_server_hello(&buf).is_err());
        }
    }

    #[test]
    fn test_verify_server_hello_invalid_nested_lengths() {
        let build_valid_sh = || -> Vec<u8> {
            let mut sh = Vec::new();
            sh.push(0x02); // ServerHello
            let mut payload = Vec::new();
            payload.extend_from_slice(&[0x03, 0x03]); // version
            payload.extend_from_slice(&[0x77; 32]); // random
            payload.push(0); // sid_len = 0
            payload.extend_from_slice(&[0x13, 0x01]); // TLS_AES_128_GCM_SHA256
            payload.push(0x00); // compression = 0

            // Extensions: supported_versions (0x002b) + key_share (0x0033)
            let mut extensions = Vec::new();
            // supported_versions
            extensions.extend_from_slice(&[0x00, 0x2b, 0x00, 0x02, 0x03, 0x04]);
            // key_share
            extensions.extend_from_slice(&[0x00, 0x33, 0x00, 0x24]); // len = 36
            extensions.extend_from_slice(&[0x00, 0x1d, 0x00, 0x20]); // group x25519, len 32
            extensions.extend_from_slice(&[0x88; 32]); // key share

            payload.extend_from_slice(&(extensions.len() as u16).to_be_bytes());
            payload.extend_from_slice(&extensions);

            let hs_len = payload.len() as u32;
            sh.push((hs_len >> 16) as u8);
            sh.push((hs_len >> 8) as u8);
            sh.push(hs_len as u8);
            sh.extend_from_slice(&payload);
            sh
        };

        let valid_sh = build_valid_sh();
        assert!(verify_server_hello(&valid_sh).is_ok());
        assert!(parse_server_hello(&valid_sh).is_ok());

        // 1. Handshake declared length mismatch (too large)
        let mut malformed_hs_len = valid_sh.clone();
        malformed_hs_len[3] = 0xff; // declare huge length
        assert!(verify_server_hello(&malformed_hs_len).is_err());
        assert!(parse_server_hello(&malformed_hs_len).is_err());

        // 2. Handshake declared length too small (< 40)
        let mut malformed_small_hs = valid_sh.clone();
        malformed_small_hs[3] = 10;
        assert!(verify_server_hello(&malformed_small_hs).is_err());

        // 3. Invalid legacy version (e.g. 0x0301 instead of 0x0303)
        let mut malformed_ver = valid_sh.clone();
        malformed_ver[5] = 0x01;
        assert!(verify_server_hello(&malformed_ver).is_err());

        // 4. Invalid compression method (!= 0x00)
        let mut malformed_comp = valid_sh.clone();
        malformed_comp[41] = 0x01;
        assert!(verify_server_hello(&malformed_comp).is_err());

        // 5. Extensions block declared length too large
        let mut malformed_ext_len = valid_sh.clone();
        malformed_ext_len[42] = 0x05; // declare huge extension length
        assert!(verify_server_hello(&malformed_ext_len).is_err());

        // 6. Truncated individual extension
        let mut truncated_ext = valid_sh.clone();
        truncated_ext.truncate(valid_sh.len() - 10);
        assert!(verify_server_hello(&truncated_ext).is_err());

        // 7. Trailing unexpected bytes after extensions
        let mut trailing = valid_sh.clone();
        trailing.push(0x99);
        let new_len = (trailing.len() - 4) as u32;
        trailing[1] = (new_len >> 16) as u8;
        trailing[2] = (new_len >> 8) as u8;
        trailing[3] = new_len as u8;
        assert!(verify_server_hello(&trailing).is_err());
    }

    #[test]
    fn test_server_hello_parser_fuzzing() {
        // Pseudo-fuzzing: 20,000 iterations of adversarial mutations without any panic
        let mut base_sh = Vec::new();
        base_sh.push(0x02);
        let mut payload = Vec::new();
        payload.extend_from_slice(&[0x03, 0x03]);
        payload.extend_from_slice(&[0x55; 32]);
        payload.push(32); // sid_len = 32
        payload.extend_from_slice(&[0x66; 32]); // session id
        payload.extend_from_slice(&[0x13, 0x01]); // cipher suite
        payload.push(0x00); // compression

        let mut extensions = Vec::new();
        extensions.extend_from_slice(&[0x00, 0x2b, 0x00, 0x02, 0x03, 0x04]);
        extensions.extend_from_slice(&[0x00, 0x33, 0x00, 0x24]);
        extensions.extend_from_slice(&[0x00, 0x1d, 0x00, 0x20]);
        extensions.extend_from_slice(&[0x77; 32]);

        payload.extend_from_slice(&(extensions.len() as u16).to_be_bytes());
        payload.extend_from_slice(&extensions);

        let hs_len = payload.len() as u32;
        base_sh.push((hs_len >> 16) as u8);
        base_sh.push((hs_len >> 8) as u8);
        base_sh.push(hs_len as u8);
        base_sh.extend_from_slice(&payload);

        assert!(verify_server_hello(&base_sh).is_ok());
        assert!(parse_server_hello(&base_sh).is_ok());

        // Simple deterministic PRNG for reproducible fuzzing
        let mut seed = 0xdeadbeef_u64;
        let mut prng = || -> u64 {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            seed
        };

        for _ in 0..20_000 {
            let mut test_buf = base_sh.clone();
            let mode = (prng() % 5) as usize;

            match mode {
                0 => {
                    // Truncation at arbitrary offset
                    let trunc_len = (prng() as usize) % (test_buf.len() + 1);
                    test_buf.truncate(trunc_len);
                }
                1 => {
                    // Bit/byte flipping at random offsets
                    let flips = ((prng() % 8) + 1) as usize;
                    for _ in 0..flips {
                        if !test_buf.is_empty() {
                            let idx = (prng() as usize) % test_buf.len();
                            test_buf[idx] ^= (prng() % 256) as u8;
                        }
                    }
                }
                2 => {
                    // Boundary length injection into header / sid_len / ext_len
                    let edge_vals: [u8; 12] = [0, 1, 2, 38, 39, 43, 44, 127, 128, 254, 255, 16];
                    let val = edge_vals[(prng() as usize) % edge_vals.len()];
                    if !test_buf.is_empty() {
                        let idx = (prng() as usize) % test_buf.len();
                        test_buf[idx] = val;
                    }
                }
                3 => {
                    // Completely random buffer of arbitrary length
                    let len = (prng() as usize) % 512;
                    test_buf = (0..len).map(|_| (prng() % 256) as u8).collect();
                }
                4 => {
                    // Insertion of garbage bytes
                    let pos = (prng() as usize) % (test_buf.len() + 1);
                    let insert_len = ((prng() % 16) + 1) as usize;
                    let garbage: Vec<u8> = (0..insert_len).map(|_| (prng() % 256) as u8).collect();
                    test_buf.splice(pos..pos, garbage);
                }
                _ => unreachable!(),
            }

            // Neither verify_server_hello nor parse_server_hello should ever panic
            let _ = verify_server_hello(&test_buf);
            let _ = parse_server_hello(&test_buf);
        }
    }

    #[test]
    fn test_distinct_grease() {
        let grease = distinct_grease(5);
        assert_eq!(grease.len(), 5);
        for &val in &grease {
            assert!(GREASE_VALUES.contains(&val));
        }
        let mut sorted = grease.clone();
        sorted.sort();
        sorted.dedup();
        assert_eq!(sorted.len(), 5);
    }

    #[test]
    fn test_build_reality_client_hello_chrome_vs_firefox() {
        let client_random = [0x42u8; 32];
        let session_id = [0u8; 32];
        let key_share_pub = [0x55u8; 32];

        // 1. Chrome build: has GREASE and ALPN
        let (ch_chrome, sid_off_chrome) = build_reality_client_hello_msg_ext(
            "cloudflare.com",
            &client_random,
            &session_id,
            &key_share_pub,
            "chrome",
        );
        assert_eq!(sid_off_chrome, 39);
        assert_eq!(ch_chrome[0], 0x01); // Handshake Type: ClientHello
        assert!(ch_chrome.windows(2).any(|w| w == b"h2"));
        assert!(ch_chrome.windows(8).any(|w| w == b"http/1.1"));

        // 2. Firefox build: no GREASE, Firefox cipher order, ALPN
        let (ch_ff, sid_off_ff) = build_reality_client_hello_msg_ext(
            "cloudflare.com",
            &client_random,
            &session_id,
            &key_share_pub,
            "firefox",
        );
        assert_eq!(sid_off_ff, 39);
        assert_eq!(ch_ff[0], 0x01);
        assert!(ch_ff.windows(2).any(|w| w == b"h2"));
        assert!(ch_ff.windows(8).any(|w| w == b"http/1.1"));
        let ff_ciphers = [0x13, 0x01, 0x13, 0x03, 0x13, 0x02];
        assert!(ch_ff.windows(ff_ciphers.len()).any(|w| w == ff_ciphers));

        // 3. Mapped profile (edge) and unsupported fallback: safe Chrome profile with GREASE
        let (ch_edge, sid_off_edge) = build_reality_client_hello_msg_ext(
            "cloudflare.com",
            &client_random,
            &session_id,
            &key_share_pub,
            "edge",
        );
        assert_eq!(sid_off_edge, 39);
        assert_eq!(ch_edge[0], 0x01);
        assert!(ch_edge.windows(2).any(|w| w == b"h2"));

        let (ch_unknown, sid_off_unknown) = build_reality_client_hello_msg_ext(
            "cloudflare.com",
            &client_random,
            &session_id,
            &key_share_pub,
            "custom_parrot_agent",
        );
        assert_eq!(sid_off_unknown, 39);
        assert_eq!(ch_unknown[0], 0x01);
        assert!(ch_unknown.windows(2).any(|w| w == b"h2"));
    }

    #[test]
    fn test_reality_peer_cert_verification_success_and_failure() {
        let auth_key = [0x42u8; 32];
        let ed_pub = [0x55u8; 32];

        // Compute valid signature: HMAC-SHA512(auth_key, ed_pub)
        let mut mac = HmacSha512::new_from_slice(&auth_key).unwrap();
        mac.update(&ed_pub);
        let valid_sig = mac.finalize().into_bytes();

        // Build synthetic DER certificate with this public key and signature
        let mut der = Vec::new();
        der.push(0x30); // Certificate SEQUENCE
        let mut cert_content = Vec::new();

        // 1. TBSCertificate (SEQUENCE)
        let mut tbs_content = Vec::new();
        // 5 SEQUENCES inside: signature, issuer, validity, subject, SPKI
        for _ in 0..4 {
            tbs_content.extend_from_slice(&[0x30, 0x02, 0x05, 0x00]); // 4 dummy sequences
        }
        // SPKI sequence: algorithm sequence + subjectPublicKey BIT STRING
        let mut spki_content = Vec::new();
        spki_content.extend_from_slice(&[0x30, 0x02, 0x05, 0x00]); // dummy algorithm
        spki_content.push(0x03); // BIT STRING
        spki_content.push(33); // len
        spki_content.push(0); // 0 unused bits
        spki_content.extend_from_slice(&ed_pub);

        let mut spki_seq = Vec::new();
        spki_seq.push(0x30);
        spki_seq.push(spki_content.len() as u8);
        spki_seq.extend_from_slice(&spki_content);
        tbs_content.extend_from_slice(&spki_seq);

        let mut tbs_seq = Vec::new();
        tbs_seq.push(0x30);
        tbs_seq.push(tbs_content.len() as u8);
        tbs_seq.extend_from_slice(&tbs_content);
        cert_content.extend_from_slice(&tbs_seq);

        // 2. SignatureAlgorithm (SEQUENCE)
        cert_content.extend_from_slice(&[0x30, 0x02, 0x05, 0x00]);

        // 3. SignatureValue (BIT STRING: 65 bytes)
        cert_content.push(0x03);
        cert_content.push(65);
        cert_content.push(0); // 0 unused bits
        cert_content.extend_from_slice(&valid_sig);

        if cert_content.len() < 128 {
            der.push(cert_content.len() as u8);
        } else if cert_content.len() < 256 {
            der.push(0x81);
            der.push(cert_content.len() as u8);
        } else {
            der.push(0x82);
            der.push((cert_content.len() >> 8) as u8);
            der.push((cert_content.len() & 0xff) as u8);
        }
        der.extend_from_slice(&cert_content);

        // Build Certificate handshake message body
        let mut cert_msg_body = Vec::new();
        cert_msg_body.push(0); // context len 0
        let list_len = 3 + der.len();
        cert_msg_body.extend_from_slice(&[0x00, (list_len >> 8) as u8, list_len as u8]);
        let dlen = der.len();
        cert_msg_body.extend_from_slice(&[0x00, (dlen >> 8) as u8, dlen as u8]);
        cert_msg_body.extend_from_slice(&der);

        // Positive test: valid HMAC-SHA512 signature must verify successfully
        let verify_res = verify_reality_certificate(&cert_msg_body, &auth_key);
        assert!(verify_res.is_ok(), "Verification failed: {:?}", verify_res);

        // Negative test: invalid auth_key must fail
        let wrong_key = [0x99u8; 32];
        assert!(verify_reality_certificate(&cert_msg_body, &wrong_key).is_err());
    }

    #[test]
    fn test_reality_stream_record_roundtrip() {
        let key = vec![0x11u8; 16]; // AES-128-GCM
        let iv = [0x22u8; 12];
        let cipher_suite = 0x1301;

        // Dummy TcpStream cannot be connected in offline unit tests, so we test encrypt/decrypt methods
        let mut enc_in_out = b"Hello VLESS Reality World!".to_vec();
        enc_in_out.push(0x17);
        let ct_len = (enc_in_out.len() + 16) as u16;
        let header = [0x17, 0x03, 0x03, (ct_len >> 8) as u8, ct_len as u8];

        aead_seal(cipher_suite, &key, &iv, &header, &mut enc_in_out).unwrap();
        assert_eq!(enc_in_out.len(), b"Hello VLESS Reality World!".len() + 1 + 16);

        // Decrypt
        aead_open(cipher_suite, &key, &iv, &header, &mut enc_in_out).unwrap();
        assert_eq!(enc_in_out.last(), Some(&0x17));
        assert_eq!(&enc_in_out[..enc_in_out.len() - 1], b"Hello VLESS Reality World!");
    }
}
