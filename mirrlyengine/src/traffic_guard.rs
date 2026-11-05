use byteorder::{ByteOrder, LittleEndian};
use once_cell::sync::Lazy;
use parking_lot::RwLock;
use sha2::{Digest, Sha256};
use std::ffi::CString;
use std::fs::File;
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom};
use std::os::raw::{c_char, c_int};
use std::sync::atomic::{AtomicBool, Ordering};

const MASK_A: [u8; 16] = [
    0xA5, 0x5A, 0x3C, 0xC3, 0x96, 0x69, 0x0F, 0xF0,
    0x55, 0xAA, 0x33, 0xCC, 0x0F, 0xF0, 0x5A, 0xA5,
];
const MASK_B: [u8; 16] = [
    0x3C, 0xC3, 0x5A, 0xA5, 0x96, 0x69, 0xF0, 0x0F,
    0xAA, 0x55, 0xCC, 0x33, 0xF0, 0x0F, 0xA5, 0x5A,
];

const ENC_A: [u8; 16] = [
    0xA5, 0x68, 0x55, 0x08, 0xE6, 0x7B, 0xD0, 0xC7,
    0x34, 0x4D, 0xE0, 0x5E, 0x76, 0xAC, 0x99, 0xF0,
];
const ENC_B: [u8; 16] = [
    0x3E, 0x80, 0x63, 0x01, 0x24, 0x72, 0xCF, 0xBA,
    0xDF, 0xF1, 0x05, 0x18, 0x3C, 0xD2, 0x80, 0x4C,
];

const PKG_LEN: usize = 18;
const PKG_MASK: [u8; PKG_LEN] = [
    0x5A, 0xC3, 0x96, 0x0F, 0xAA, 0x33, 0x55, 0xF0,
    0x69, 0x3C, 0xA5, 0x5A, 0xC3, 0x96, 0x0F, 0xAA,
    0x33, 0x55,
];
const PKG_ENC: [u8; PKG_LEN] = [
    b'c' ^ 0x5A, b'o' ^ 0xC3, b'm' ^ 0x96, b'.' ^ 0x0F,
    b'm' ^ 0xAA, b'i' ^ 0x33, b'r' ^ 0x55, b'r' ^ 0xF0,
    b'l' ^ 0x69, b'y' ^ 0x3C, b'.' ^ 0xA5, b't' ^ 0x5A,
    b'g' ^ 0xC3, b'p' ^ 0x96, b'r' ^ 0x0F, b'o' ^ 0xAA,
    b'x' ^ 0x33, b'y' ^ 0x55,
];

const APK_SIG_BLOCK_MAGIC: &[u8; 16] = b"APK Sig Block 42";
const SCHEME_V2_ID: u32 = 0x7109871a;
const SCHEME_V3_ID: u32 = 0xf05368c0;

#[derive(Debug, Clone)]
pub struct SecurityPosture {
    pub is_authentic: bool,
    pub is_debug: bool,
    pub is_checked: bool,
    pub actual_fingerprint: String,
    pub official_fingerprint: String,
    pub detected_source: String,
}

static POSTURE: Lazy<RwLock<SecurityPosture>> = Lazy::new(|| {
    let mut official = [0u8; 32];
    for i in 0..16 {
        official[i] = ENC_A[i] ^ MASK_A[i];
        official[i + 16] = ENC_B[i] ^ MASK_B[i];
    }
    let official_hex = format_fingerprint_colon(&official);

    RwLock::new(SecurityPosture {
        is_authentic: false,
        is_debug: false,
        is_checked: false,
        actual_fingerprint: String::new(),
        official_fingerprint: official_hex,
        detected_source: "UNCHECKED".to_string(),
    })
});

static TRAFFIC_AUTHORIZED: AtomicBool = AtomicBool::new(false);
static SILENT_POISONING: AtomicBool = AtomicBool::new(true);
static AUDIT_DONE: AtomicBool = AtomicBool::new(false);

#[inline]
fn constant_time_compare_32(a: &[u8; 32], b: &[u8; 32]) -> bool {
    let mut diff = 0u8;
    for i in 0..32 {
        diff |= a[i] ^ b[i];
    }
    diff == 0
}

#[inline]
fn constant_time_compare_slices(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for i in 0..a.len() {
        diff |= a[i] ^ b[i];
    }
    diff == 0
}

#[inline]
pub fn format_fingerprint_colon(bytes: &[u8]) -> String {
    bytes
        .iter()
        .map(|b| format!("{:02X}", b))
        .collect::<Vec<String>>()
        .join(":")
}

#[inline]
pub fn clean_fingerprint(input: &str) -> String {
    input
        .chars()
        .filter(|c| c.is_ascii_hexdigit())
        .collect::<String>()
        .to_uppercase()
}

pub fn get_official_sha256_bytes() -> [u8; 32] {
    let mut official = [0u8; 32];
    for i in 0..16 {
        official[i] = ENC_A[i] ^ MASK_A[i];
        official[i + 16] = ENC_B[i] ^ MASK_B[i];
    }
    official
}

pub fn get_expected_package_bytes() -> [u8; PKG_LEN] {
    let mut pkg = [0u8; PKG_LEN];
    for i in 0..PKG_LEN {
        pkg[i] = PKG_ENC[i] ^ PKG_MASK[i];
    }
    pkg
}

fn verify_process_cmdline() -> bool {
    let Ok(mut f) = File::open("/proc/self/cmdline") else {
        #[cfg(target_os = "android")]
        return false;
        #[cfg(not(target_os = "android"))]
        return true;
    };

    let mut buf = [0u8; 128];
    let Ok(n) = f.read(&mut buf) else {
        return false;
    };
    if n < PKG_LEN {
        return false;
    }

    let expected = get_expected_package_bytes();

    let mut end = 0;
    while end < n && buf[end] != 0 && buf[end] != b':' {
        end += 1;
    }

    if end != PKG_LEN {
        return false;
    }

    constant_time_compare_slices(&buf[0..PKG_LEN], &expected)
}

fn find_apk_path_from_maps() -> Option<String> {
    let maps_file = File::open("/proc/self/maps").ok()?;
    let reader = BufReader::new(maps_file);

    for line in reader.lines().map_while(Result::ok) {
        if line.contains("base.apk") || (line.contains("com.mirrly.tgproxy") && line.ends_with(".apk")) {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if let Some(path) = parts.last() {
                if path.ends_with(".apk") && std::path::Path::new(path).exists() {
                    return Some(path.to_string());
                }
            }
        }
    }
    None
}

fn parse_apk_signing_certificate(apk_path: &str) -> Option<Vec<u8>> {
    let mut file = File::open(apk_path).ok()?;
    let file_len = file.metadata().ok()?.len();
    if file_len < 32 {
        return None;
    }

    let search_len = std::cmp::min(file_len as usize, 65535 + 22);
    let mut buf = vec![0u8; search_len];
    file.seek(SeekFrom::End(-(search_len as i64))).ok()?;
    file.read_exact(&mut buf).ok()?;

    let mut eocd_offset_in_buf = None;
    for i in (0..=buf.len() - 22).rev() {
        if &buf[i..i + 4] == b"PK\x05\x06" {
            eocd_offset_in_buf = Some(i);
            break;
        }
    }

    let eocd_pos = eocd_offset_in_buf?;
    if eocd_pos + 20 > buf.len() {
        return None;
    }

    let cd_offset = LittleEndian::read_u32(&buf[eocd_pos + 16..eocd_pos + 20]) as u64;
    if cd_offset < 24 || cd_offset > file_len {
        return None;
    }

    let footer_start = cd_offset.checked_sub(24)?;
    file.seek(SeekFrom::Start(footer_start)).ok()?;
    let mut footer = [0u8; 24];
    file.read_exact(&mut footer).ok()?;

    let block_size = LittleEndian::read_u64(&footer[0..8]);
    if &footer[8..24] != APK_SIG_BLOCK_MAGIC {
        return None;
    }

    if block_size < 32 || block_size > cd_offset {
        return None;
    }

    let block_start = cd_offset.checked_sub(block_size + 8)?;
    file.seek(SeekFrom::Start(block_start)).ok()?;

    let mut block_header = [0u8; 8];
    file.read_exact(&mut block_header).ok()?;
    let header_size = LittleEndian::read_u64(&block_header);
    if header_size != block_size {
        return None;
    }

    let payload_len = (block_size - 24) as usize;
    if payload_len > 4 * 1024 * 1024 {
        return None;
    }

    let mut payload = vec![0u8; payload_len];
    file.read_exact(&mut payload).ok()?;

    let mut offset = 0;
    while offset + 12 <= payload.len() {
        let pair_len = LittleEndian::read_u64(&payload[offset..offset + 8]) as usize;
        let id = LittleEndian::read_u32(&payload[offset + 8..offset + 12]);
        let pair_data_start = offset + 12;
        let pair_data_end = offset + 8 + pair_len;

        if pair_data_end > payload.len() {
            break;
        }

        if id == SCHEME_V2_ID || id == SCHEME_V3_ID {
            let data = &payload[pair_data_start..pair_data_end];
            if let Some(cert) = extract_cert_from_scheme_block(data) {
                return Some(cert);
            }
        }

        offset = pair_data_end;
    }

    None
}

fn extract_cert_from_scheme_block(data: &[u8]) -> Option<Vec<u8>> {
    if data.len() < 8 {
        return None;
    }
    let signers_len = LittleEndian::read_u32(&data[0..4]) as usize;
    if signers_len + 4 > data.len() || signers_len < 4 {
        return None;
    }
    let signer_len = LittleEndian::read_u32(&data[4..8]) as usize;
    if 8 + signer_len > data.len() {
        return None;
    }
    let signer = &data[8..8 + signer_len];
    if signer.len() < 4 {
        return None;
    }
    let signed_data_len = LittleEndian::read_u32(&signer[0..4]) as usize;
    if 4 + signed_data_len > signer.len() {
        return None;
    }
    let signed_data = &signer[4..4 + signed_data_len];

    let mut pos = 0;
    if pos + 4 > signed_data.len() {
        return None;
    }
    let digests_len = LittleEndian::read_u32(&signed_data[pos..pos + 4]) as usize;
    pos += 4 + digests_len;

    if pos + 4 > signed_data.len() {
        return None;
    }
    let certs_len = LittleEndian::read_u32(&signed_data[pos..pos + 4]) as usize;
    pos += 4;
    if pos + certs_len > signed_data.len() || certs_len < 4 {
        return None;
    }

    let cert_len = LittleEndian::read_u32(&signed_data[pos..pos + 4]) as usize;
    pos += 4;
    if pos + cert_len > signed_data.len() {
        return None;
    }

    Some(signed_data[pos..pos + cert_len].to_vec())
}

pub fn run_autonomous_audit() -> bool {
    let official_bytes = get_official_sha256_bytes();
    let official_hex = format_fingerprint_colon(&official_bytes);

    let is_cmdline_valid = verify_process_cmdline();
    if !is_cmdline_valid {
        let posture = SecurityPosture {
            is_authentic: false,
            is_debug: false,
            is_checked: true,
            actual_fingerprint: "CLONED_PROCESS".to_string(),
            official_fingerprint: official_hex,
            detected_source: "PROC_CMDLINE_MISMATCH".to_string(),
        };
        TRAFFIC_AUTHORIZED.store(false, Ordering::SeqCst);
        SILENT_POISONING.store(true, Ordering::SeqCst);
        AUDIT_DONE.store(true, Ordering::SeqCst);
        *POSTURE.write() = posture;
        return false;
    }

    let mut native_cert_sha: Option<[u8; 32]> = None;
    let detected_source = "NATIVE_MAPS_APK";

    if let Some(apk_path) = find_apk_path_from_maps() {
        if !apk_path.contains("com.mirrly.tgproxy") || apk_path.contains("com.mirrly.tgproxy.mod") {
            let posture = SecurityPosture {
                is_authentic: false,
                is_debug: false,
                is_checked: true,
                actual_fingerprint: "CLONED_STORAGE_PATH".to_string(),
                official_fingerprint: official_hex,
                detected_source: "PATH_CLONE_DETECTED".to_string(),
            };
            TRAFFIC_AUTHORIZED.store(false, Ordering::SeqCst);
            SILENT_POISONING.store(true, Ordering::SeqCst);
            AUDIT_DONE.store(true, Ordering::SeqCst);
            *POSTURE.write() = posture;
            return false;
        }

        if let Some(cert_der) = parse_apk_signing_certificate(&apk_path) {
            let digest = Sha256::digest(&cert_der);
            let mut arr = [0u8; 32];
            arr.copy_from_slice(&digest);
            native_cert_sha = Some(arr);
        }
    }

    let (is_auth, actual_hex, source_tag) = if let Some(native_sha) = native_cert_sha {
        let is_match = constant_time_compare_32(&native_sha, &official_bytes);
        (is_match, format_fingerprint_colon(&native_sha), detected_source.to_string())
    } else {
        #[cfg(target_os = "android")]
        {
            (false, "UNREADABLE_APK".to_string(), "FAILED_APK_READ".to_string())
        }
        #[cfg(not(target_os = "android"))]
        {
            (true, official_hex.clone(), "HOST_ENV".to_string())
        }
    };

    let posture = SecurityPosture {
        is_authentic: is_auth,
        is_debug: false,
        is_checked: true,
        actual_fingerprint: actual_hex,
        official_fingerprint: official_hex,
        detected_source: source_tag,
    };

    TRAFFIC_AUTHORIZED.store(is_auth, Ordering::SeqCst);
    SILENT_POISONING.store(!is_auth, Ordering::SeqCst);
    AUDIT_DONE.store(true, Ordering::SeqCst);
    *POSTURE.write() = posture;

    is_auth
}

#[inline]
pub fn ensure_traffic_guard_audited() -> bool {
    if AUDIT_DONE.load(Ordering::Acquire) {
        TRAFFIC_AUTHORIZED.load(Ordering::Acquire)
    } else {
        run_autonomous_audit()
    }
}

pub fn is_traffic_authorized() -> bool {
    ensure_traffic_guard_audited()
}

pub fn should_poison_traffic() -> bool {
    !ensure_traffic_guard_audited()
}

pub fn get_security_posture() -> SecurityPosture {
    ensure_traffic_guard_audited();
    POSTURE.read().clone()
}

#[no_mangle]
pub extern "C" fn GetEngineSecurityState() -> c_int {
    let is_auth = ensure_traffic_guard_audited();
    if is_auth {
        1
    } else {
        0
    }
}

#[no_mangle]
pub extern "C" fn GetSecurityPostureReportJson() -> *mut c_char {
    let p = get_security_posture();

    let json = serde_json::json!({
        "is_authentic": p.is_authentic,
        "is_debug": p.is_debug,
        "actual_fingerprint": p.actual_fingerprint,
        "official_fingerprint": p.official_fingerprint,
        "detected_source": p.detected_source,
        "title": "Остерегайтесь подделок и сторонних репаков!",
        "description": "Официальные версии Mirrly TG Proxy выходят исключительно в канале @WhyOKyHb и на GitHub. В модифицированных сторонними лицами сборках сетевой шлюз блокируется встроенной криптографической защитой для предотвращения перехвата трафика.",
        "official_channel_url": "https://t.me/WhyOKyHb",
        "official_github_url": "https://github.com/joycecurcirt539-dot/Mirrly-TG-Proxy/releases"
    });

    CString::new(json.to_string())
        .unwrap_or_default()
        .into_raw()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_official_key_xor_derivation() {
        let official = get_official_sha256_bytes();
        let hex = format_fingerprint_colon(&official);
        assert_eq!(
            hex,
            "00:32:69:CB:70:12:DF:37:61:E7:D3:92:79:5C:C3:55:02:43:39:A4:B2:1B:3F:B5:75:A4:C9:2B:CC:DD:25:16"
        );
    }

    #[test]
    fn test_package_name_xor_derivation() {
        let pkg_bytes = get_expected_package_bytes();
        assert_eq!(&pkg_bytes, b"com.mirrly.tgproxy");
    }

    #[test]
    fn test_clean_fingerprint() {
        assert_eq!(clean_fingerprint("00:32:69:CB"), "003269CB");
        assert_eq!(clean_fingerprint("00 32-69:cb"), "003269CB");
    }
}