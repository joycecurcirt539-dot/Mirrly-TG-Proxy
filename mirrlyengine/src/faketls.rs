use rand::RngCore;
use std::io;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

pub const TLS_RECORD_HEADER_LEN: usize = 5;
pub const MAX_TLS_RECORD_PAYLOAD: usize = 16384;

pub const CONTENT_TYPE_CHANGE_CIPHER_SPEC: u8 = 0x14;
pub const CONTENT_TYPE_ALERT: u8 = 0x15;
pub const CONTENT_TYPE_HANDSHAKE: u8 = 0x16;
pub const CONTENT_TYPE_APPLICATION_DATA: u8 = 0x17;

/// Проверяет, является ли начальный буфер заголовком TLS Handshake (ClientHello).
pub fn is_tls_handshake(buf: &[u8]) -> bool {
    if buf.len() < 3 {
        return false;
    }
    buf[0] == CONTENT_TYPE_HANDSHAKE && buf[1] == 0x03 && (buf[2] >= 0x01 && buf[2] <= 0x04)
}

/// Формирует стандартную последовательность ответа Fake-TLS:
/// 1. ServerHello (TLS 1.3 с эхом session_id клиента, supported_versions и key_share)
/// 2. ChangeCipherSpec (0x14 0x03 0x03 0x00 0x01 0x01)
/// 3. ApplicationData (эмуляция Finished/EncryptedExtensions)
pub fn build_fake_tls_server_hello(session_id: &[u8; 32]) -> Vec<u8> {
    let mut rng = rand::thread_rng();

    // 32 байта случайных чисел для Server Random
    let mut server_random = [0u8; 32];
    rng.fill_bytes(&mut server_random);

    // 32 байта случайного публичного ключа x25519 для key_share
    let mut key_share_pub = [0u8; 32];
    rng.fill_bytes(&mut key_share_pub);

    let mut resp = Vec::with_capacity(256);

    // ── 1. TLS Record Header для ServerHello ───────────────────────────────
    // Record Header: 0x16 (Handshake), 0x03, 0x03 (TLS 1.2 legacy), 2 байта длины (122 = 0x00, 0x7a)
    resp.extend_from_slice(&[0x16, 0x03, 0x03, 0x00, 0x7a]);

    // Handshake Type: 0x02 (ServerHello), 3 байта длины (118 = 0x00, 0x00, 0x76)
    resp.extend_from_slice(&[0x02, 0x00, 0x00, 0x76]);

    // Legacy Version: 0x03, 0x03
    resp.extend_from_slice(&[0x03, 0x03]);

    // Server Random (32 байта)
    resp.extend_from_slice(&server_random);

    // Session ID length (32) + Session ID (эхо от клиента)
    resp.push(0x20);
    resp.extend_from_slice(session_id);

    // Cipher Suite: TLS_AES_128_GCM_SHA256 (0x13, 0x01)
    resp.extend_from_slice(&[0x13, 0x01]);

    // Compression Method: null (0x00)
    resp.push(0x00);

    // Extensions Length: 46 байт (0x00, 0x2e)
    resp.extend_from_slice(&[0x00, 0x2e]);

    // Extension 1: supported_versions (type 43 = 0x00, 0x2b, len = 2, TLS 1.3 = 0x03, 0x04)
    resp.extend_from_slice(&[0x00, 0x2b, 0x00, 0x02, 0x03, 0x04]);

    // Extension 2: key_share (type 51 = 0x00, 0x33, len = 36, group x25519 = 0x00, 0x1d, key_len = 32)
    resp.extend_from_slice(&[0x00, 0x33, 0x00, 0x24, 0x00, 0x1d, 0x00, 0x20]);
    resp.extend_from_slice(&key_share_pub);

    // ── 2. ChangeCipherSpec Record ─────────────────────────────────────────
    // 0x14, 0x03, 0x03, 0x00, 0x01, 0x01
    resp.extend_from_slice(&[0x14, 0x03, 0x03, 0x00, 0x01, 0x01]);

    // ── 3. Dummy ApplicationData (Encrypted Handshake / Finished) ──────────
    // 0x17, 0x03, 0x03, 0x00, 0x35 (53 байта фиктивных зашифрованных данных)
    let mut dummy_app_data = [0u8; 53];
    rng.fill_bytes(&mut dummy_app_data);
    resp.extend_from_slice(&[0x17, 0x03, 0x03, 0x00, 0x35]);
    resp.extend_from_slice(&dummy_app_data);

    resp
}

/// Выполняет рукопожатие Fake-TLS: дочитывает ClientHello, извлекает session_id и отправляет ServerHello.
pub async fn handle_fake_tls_handshake<S: AsyncReadExt + AsyncWriteExt + Unpin>(
    stream: &mut S,
    initial_5: &[u8],
) -> io::Result<()> {
    if initial_5.len() < TLS_RECORD_HEADER_LEN {
        return Err(io::Error::new(
            io::ErrorKind::UnexpectedEof,
            "Invalid TLS header prefix",
        ));
    }

    let record_len = u16::from_be_bytes([initial_5[3], initial_5[4]]) as usize;
    let mut client_hello_body = vec![0u8; record_len];
    stream.read_exact(&mut client_hello_body).await?;

    let mut session_id = [0u8; 32];
    if client_hello_body.len() >= 71 && client_hello_body[38] == 32 {
        session_id.copy_from_slice(&client_hello_body[39..71]);
    } else {
        rand::thread_rng().fill_bytes(&mut session_id);
    }

    let response = build_fake_tls_server_hello(&session_id);
    stream.write_all(&response).await?;
    stream.flush().await?;

    Ok(())
}

/// Читает следующую запись TLS Application Data (0x17), пропуская промежуточные ChangeCipherSpec (0x14).
pub async fn read_tls_app_data<R: AsyncReadExt + Unpin>(reader: &mut R) -> io::Result<Vec<u8>> {
    loop {
        let mut hdr = [0u8; TLS_RECORD_HEADER_LEN];
        match reader.read_exact(&mut hdr).await {
            Ok(_) => {}
            Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(Vec::new()),
            Err(e) => return Err(e),
        }

        let content_type = hdr[0];
        let record_len = u16::from_be_bytes([hdr[3], hdr[4]]) as usize;

        if record_len > MAX_TLS_RECORD_PAYLOAD + 2048 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("TLS record too large: {}", record_len),
            ));
        }

        let mut payload = vec![0u8; record_len];
        reader.read_exact(&mut payload).await?;

        if content_type == CONTENT_TYPE_APPLICATION_DATA {
            return Ok(payload);
        } else if content_type == CONTENT_TYPE_CHANGE_CIPHER_SPEC
            || content_type == CONTENT_TYPE_HANDSHAKE
        {
            // Пропускаем клиентский ChangeCipherSpec или Finished
            continue;
        } else if content_type == CONTENT_TYPE_ALERT {
            return Ok(Vec::new());
        }
    }
}

/// Записывает данные в сокет клиента, оборачивая их в TLS Application Data записи (0x17 0x03 0x03).
pub async fn write_tls_app_data<W: AsyncWriteExt + Unpin>(
    writer: &mut W,
    data: &[u8],
) -> io::Result<()> {
    for chunk in data.chunks(MAX_TLS_RECORD_PAYLOAD) {
        let len = chunk.len() as u16;
        let hdr = [
            CONTENT_TYPE_APPLICATION_DATA,
            0x03,
            0x03,
            (len >> 8) as u8,
            (len & 0xff) as u8,
        ];
        writer.write_all(&hdr).await?;
        writer.write_all(chunk).await?;
    }
    writer.flush().await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_is_tls_handshake() {
        assert!(is_tls_handshake(&[0x16, 0x03, 0x01, 0x02, 0x00]));
        assert!(is_tls_handshake(&[0x16, 0x03, 0x03, 0x01, 0x00]));
        assert!(!is_tls_handshake(&[0xef, 0xef, 0xef, 0xef]));
        assert!(!is_tls_handshake(&[0x17, 0x03, 0x03]));
    }

    #[test]
    fn test_build_fake_tls_server_hello() {
        let session_id = [0x42u8; 32];
        let resp = build_fake_tls_server_hello(&session_id);

        // ServerHello (127) + CCS (6) + AppData (58) = 191 bytes
        assert_eq!(resp.len(), 191);

        // Check ServerHello header
        assert_eq!(&resp[0..3], &[0x16, 0x03, 0x03]);
        assert_eq!(u16::from_be_bytes([resp[3], resp[4]]), 122);

        // Check Session ID in ServerHello at offset 44
        assert_eq!(&resp[44..76], &session_id);

        // Check ChangeCipherSpec at offset 127
        assert_eq!(&resp[127..133], &[0x14, 0x03, 0x03, 0x00, 0x01, 0x01]);

        // Check ApplicationData at offset 133
        assert_eq!(&resp[133..136], &[0x17, 0x03, 0x03]);
    }

    #[tokio::test]
    async fn test_fake_tls_handshake_and_framing() {
        let (mut client_sock, mut server_sock) = tokio::io::duplex(4096);

        // 1. Build a mock ClientHello
        let mut client_hello_body = Vec::new();
        client_hello_body.push(0x01); // HandshakeType: ClientHello
        client_hello_body.extend_from_slice(&[0x00, 0x00, 0x4b]); // Length: 75 bytes body
        client_hello_body.extend_from_slice(&[0x03, 0x03]); // Version
        client_hello_body.extend_from_slice(&[0xaa; 32]); // Client Random
        client_hello_body.push(32); // Session ID len = 32
        let expected_session_id = [0x55u8; 32];
        client_hello_body.extend_from_slice(&expected_session_id); // Session ID
        client_hello_body.extend_from_slice(&[0x00, 0x02, 0x13, 0x01]); // 1 cipher suite
        client_hello_body.extend_from_slice(&[0x01, 0x00]); // 1 compression method
        client_hello_body.extend_from_slice(&[0x00, 0x00]); // 0 extensions

        let body_len = client_hello_body.len() as u16;
        let mut client_hello =
            vec![0x16, 0x03, 0x01, (body_len >> 8) as u8, (body_len & 0xff) as u8];
        client_hello.extend_from_slice(&client_hello_body);

        let initial_5 = client_hello[..5].to_vec();

        let client_task = tokio::spawn(async move {
            // Client sends remaining ClientHello body (beyond the first 5 bytes)
            client_sock.write_all(&client_hello[5..]).await.unwrap();
            client_sock.flush().await.unwrap();

            // Client receives Server response (191 bytes)
            let mut srv_resp = vec![0u8; 191];
            client_sock.read_exact(&mut srv_resp).await.unwrap();
            assert_eq!(&srv_resp[0..3], &[0x16, 0x03, 0x03]);
            assert_eq!(&srv_resp[44..76], &expected_session_id);

            // Client sends ApplicationData
            let test_payload = b"Hello MTProto via FakeTLS";
            write_tls_app_data(&mut client_sock, test_payload).await.unwrap();

            // Client receives ApplicationData response
            let response = read_tls_app_data(&mut client_sock).await.unwrap();
            assert_eq!(&response[..], b"MTProto Response from DC");
        });

        // Server handles handshake
        handle_fake_tls_handshake(&mut server_sock, &initial_5).await.unwrap();

        // Server reads client's ApplicationData
        let received = read_tls_app_data(&mut server_sock).await.unwrap();
        assert_eq!(&received[..], b"Hello MTProto via FakeTLS");

        // Server replies with ApplicationData
        write_tls_app_data(&mut server_sock, b"MTProto Response from DC").await.unwrap();

        client_task.await.unwrap();
    }
}
