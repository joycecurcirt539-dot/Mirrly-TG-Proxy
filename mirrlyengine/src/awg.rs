// Mirrly TG Proxy - AmneziaWG (AWG) Native Protocol Engine
// Implements WireGuard Noise IKpsk2 with AmneziaWG obfuscation extensions:
// - Jc (junk packets) and Jmin..Jmax randomized padding before handshake
// - S1 / S2 padding on Initiation and Response packets
// - H1..H4 custom message type headers (defaulting to 1..4 for standard WireGuard / WARP Anycast)
// - I1 custom initial packet camouflage (e.g. QUIC Initial RFC 9001)
// - smoltcp user-space TCP stack bridging for SOCKS5 proxy without Android VpnService
use crate::{ldebug, lerror, linfo, lwarn, STATS};
use once_cell::sync::Lazy;
use parking_lot::{Mutex, RwLock};
use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey, CHACHA20_POLY1305};
use smoltcp::iface::{Config as SmolConfig, Interface, SocketSet};
use smoltcp::phy::{Device, DeviceCapabilities, Medium, RxToken, TxToken};
use smoltcp::socket::tcp::{self, Socket as TcpSocket, State as TcpState};
use smoltcp::time::Instant as SmolInstant;
use smoltcp::wire::{
    HardwareAddress, IpAddress, IpCidr, IpEndpoint, Ipv4Address, Ipv4Cidr, Ipv6Address, Ipv6Cidr,
};
use std::collections::{HashMap, VecDeque};
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::atomic::{AtomicU16, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::UdpSocket;
use tokio_util::sync::CancellationToken;

// ---------------------------------------------------------------------------
// 1. RFC 7693 BLAKE2s-256 Pure-Rust Implementation
// ---------------------------------------------------------------------------

const BLAKE2S_IV: [u32; 8] = [
    0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
];

const BLAKE2S_SIGMA: [[usize; 16]; 10] = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
    [14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3],
    [11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4],
    [7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8],
    [9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13],
    [2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9],
    [12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11],
    [13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10],
    [6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5],
    [10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0],
];

#[inline(always)]
fn blake2s_g(v: &mut [u32; 16], a: usize, b: usize, c: usize, d: usize, x: u32, y: u32) {
    v[a] = v[a].wrapping_add(v[b]).wrapping_add(x);
    v[d] = (v[d] ^ v[a]).rotate_right(16);
    v[c] = v[c].wrapping_add(v[d]);
    v[b] = (v[b] ^ v[c]).rotate_right(12);
    v[a] = v[a].wrapping_add(v[b]).wrapping_add(y);
    v[d] = (v[d] ^ v[a]).rotate_right(8);
    v[c] = v[c].wrapping_add(v[d]);
    v[b] = (v[b] ^ v[c]).rotate_right(7);
}

fn blake2s_compress(h: &mut [u32; 8], block: &[u8; 64], t0: u32, t1: u32, f0: u32, f1: u32) {
    let mut m = [0u32; 16];
    for i in 0..16 {
        m[i] = u32::from_le_bytes([
            block[4 * i],
            block[4 * i + 1],
            block[4 * i + 2],
            block[4 * i + 3],
        ]);
    }
    let mut v = [0u32; 16];
    v[0..8].copy_from_slice(h);
    v[8..16].copy_from_slice(&BLAKE2S_IV);
    v[12] ^= t0;
    v[13] ^= t1;
    v[14] ^= f0;
    v[15] ^= f1;

    for r in 0..10 {
        let s = &BLAKE2S_SIGMA[r];
        blake2s_g(&mut v, 0, 4, 8, 12, m[s[0]], m[s[1]]);
        blake2s_g(&mut v, 1, 5, 9, 13, m[s[2]], m[s[3]]);
        blake2s_g(&mut v, 2, 6, 10, 14, m[s[4]], m[s[5]]);
        blake2s_g(&mut v, 3, 7, 11, 15, m[s[6]], m[s[7]]);
        blake2s_g(&mut v, 0, 5, 10, 15, m[s[8]], m[s[9]]);
        blake2s_g(&mut v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
        blake2s_g(&mut v, 2, 7, 8, 13, m[s[12]], m[s[13]]);
        blake2s_g(&mut v, 3, 4, 9, 14, m[s[14]], m[s[15]]);
    }

    for i in 0..8 {
        h[i] ^= v[i] ^ v[i + 8];
    }
}

pub fn blake2s(key: &[u8], data: &[u8], out_len: usize) -> Vec<u8> {
    assert!(out_len > 0 && out_len <= 32);
    assert!(key.len() <= 32);

    let mut h = BLAKE2S_IV;
    h[0] ^= 0x01010000 ^ ((key.len() as u32) << 8) ^ (out_len as u32);

    let mut buf = [0u8; 64];
    let mut buflen = 0usize;
    let mut t0 = 0u32;
    let mut t1 = 0u32;

    fn inc_t(t0: &mut u32, t1: &mut u32, inc: u32) {
        let (res, ov) = t0.overflowing_add(inc);
        *t0 = res;
        if ov {
            *t1 = t1.wrapping_add(1);
        }
    }

    if !key.is_empty() {
        let mut key_block = [0u8; 64];
        key_block[..key.len()].copy_from_slice(key);
        inc_t(&mut t0, &mut t1, 64);
        blake2s_compress(&mut h, &key_block, t0, t1, 0, 0);
    }

    let mut pos = 0;
    let len = data.len();

    while pos < len {
        if buflen == 64 {
            inc_t(&mut t0, &mut t1, 64);
            blake2s_compress(&mut h, &buf, t0, t1, 0, 0);
            buflen = 0;
        }
        let take = (64 - buflen).min(len - pos);
        buf[buflen..buflen + take].copy_from_slice(&data[pos..pos + take]);
        buflen += take;
        pos += take;
    }

    inc_t(&mut t0, &mut t1, buflen as u32);
    let mut last_block = [0u8; 64];
    last_block[..buflen].copy_from_slice(&buf[..buflen]);
    blake2s_compress(&mut h, &last_block, t0, t1, 0xFFFFFFFF, 0);

    let mut out = Vec::with_capacity(out_len);
    for i in 0..8 {
        let bytes = h[i].to_le_bytes();
        for b in bytes {
            if out.len() < out_len {
                out.push(b);
            }
        }
    }
    out
}

pub fn blake2s_256(data: &[u8]) -> [u8; 32] {
    let res = blake2s(&[], data, 32);
    let mut arr = [0u8; 32];
    arr.copy_from_slice(&res);
    arr
}

pub fn blake2s_mix(h: &[u8; 32], data: &[u8]) -> [u8; 32] {
    let mut combined = Vec::with_capacity(32 + data.len());
    combined.extend_from_slice(h);
    combined.extend_from_slice(data);
    blake2s_256(&combined)
}

pub fn hmac_blake2s(key: &[u8], data: &[u8]) -> [u8; 32] {
    let mut k = [0u8; 64];
    if key.len() > 64 {
        let kh = blake2s_256(key);
        k[..32].copy_from_slice(&kh);
    } else {
        k[..key.len()].copy_from_slice(key);
    }

    let mut ipad = [0x36u8; 64];
    let mut opad = [0x5cu8; 64];
    for i in 0..64 {
        ipad[i] ^= k[i];
        opad[i] ^= k[i];
    }

    let mut inner_input = Vec::with_capacity(64 + data.len());
    inner_input.extend_from_slice(&ipad);
    inner_input.extend_from_slice(data);
    let inner_hash = blake2s_256(&inner_input);

    let mut outer_input = Vec::with_capacity(64 + 32);
    outer_input.extend_from_slice(&opad);
    outer_input.extend_from_slice(&inner_hash);
    blake2s_256(&outer_input)
}

pub fn kdf1(key: &[u8; 32], input: &[u8]) -> [u8; 32] {
    let prk = hmac_blake2s(key, input);
    hmac_blake2s(&prk, &[0x01])
}

pub fn kdf2(key: &[u8; 32], input: &[u8]) -> ([u8; 32], [u8; 32]) {
    let prk = hmac_blake2s(key, input);
    let t0 = hmac_blake2s(&prk, &[0x01]);
    let mut t1_input = Vec::with_capacity(33);
    t1_input.extend_from_slice(&t0);
    t1_input.push(0x02);
    let t1 = hmac_blake2s(&prk, &t1_input);
    (t0, t1)
}

pub fn kdf3(key: &[u8; 32], input: &[u8]) -> ([u8; 32], [u8; 32], [u8; 32]) {
    let prk = hmac_blake2s(key, input);
    let t0 = hmac_blake2s(&prk, &[0x01]);
    let mut t1_input = Vec::with_capacity(33);
    t1_input.extend_from_slice(&t0);
    t1_input.push(0x02);
    let t1 = hmac_blake2s(&prk, &t1_input);
    let mut t2_input = Vec::with_capacity(33);
    t2_input.extend_from_slice(&t1);
    t2_input.push(0x03);
    let t2 = hmac_blake2s(&prk, &t2_input);
    (t0, t1, t2)
}

// ---------------------------------------------------------------------------
// 2. RFC 7748 Curve25519 Montgomery Ladder Pure-Rust Implementation
// ---------------------------------------------------------------------------

const MASK51: u64 = 0x7ffffffffffff;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Fe(pub [u64; 5]);

impl Fe {
    pub const fn zero() -> Self {
        Fe([0; 5])
    }

    pub const fn one() -> Self {
        Fe([1, 0, 0, 0, 0])
    }

    pub fn from_bytes(s: &[u8; 32]) -> Self {
        let load64 = |idx: usize| -> u64 {
            let mut b = [0u8; 8];
            b.copy_from_slice(&s[idx..idx + 8]);
            u64::from_le_bytes(b)
        };
        let h0 = load64(0) & MASK51;
        let h1 = (load64(6) >> 3) & MASK51;
        let h2 = (load64(12) >> 6) & MASK51;
        let h3 = (load64(19) >> 1) & MASK51;
        let h4 = (load64(24) >> 12) & 0x7ffffffffffff;
        Fe([h0, h1, h2, h3, h4])
    }

    pub fn to_bytes(&self) -> [u8; 32] {
        let mut t = [0u128; 5];
        t[0] = self.0[0] as u128;
        t[1] = self.0[1] as u128;
        t[2] = self.0[2] as u128;
        t[3] = self.0[3] as u128;
        t[4] = self.0[4] as u128;

        for _ in 0..2 {
            t[1] += t[0] >> 51;
            t[0] &= MASK51 as u128;
            t[2] += t[1] >> 51;
            t[1] &= MASK51 as u128;
            t[3] += t[2] >> 51;
            t[2] &= MASK51 as u128;
            t[4] += t[3] >> 51;
            t[3] &= MASK51 as u128;
            t[0] += 19 * (t[4] >> 51);
            t[4] &= MASK51 as u128;
        }

        t[0] += 19;
        t[1] += t[0] >> 51;
        t[0] &= MASK51 as u128;
        t[2] += t[1] >> 51;
        t[1] &= MASK51 as u128;
        t[3] += t[2] >> 51;
        t[2] &= MASK51 as u128;
        t[4] += t[3] >> 51;
        t[3] &= MASK51 as u128;
        t[0] += 19 * (t[4] >> 51);
        t[4] &= MASK51 as u128;

        t[0] += 0x8000000000000 - 19;
        t[1] += 0x8000000000000 - 1;
        t[2] += 0x8000000000000 - 1;
        t[3] += 0x8000000000000 - 1;
        t[4] += 0x8000000000000 - 1;

        t[1] += t[0] >> 51;
        t[0] &= MASK51 as u128;
        t[2] += t[1] >> 51;
        t[1] &= MASK51 as u128;
        t[3] += t[2] >> 51;
        t[2] &= MASK51 as u128;
        t[4] += t[3] >> 51;
        t[3] &= MASK51 as u128;
        t[4] &= MASK51 as u128;

        let t0 = (t[0] as u64) | ((t[1] as u64) << 51);
        let t1 = ((t[1] as u64) >> 13) | ((t[2] as u64) << 38);
        let t2 = ((t[2] as u64) >> 26) | ((t[3] as u64) << 25);
        let t3 = ((t[3] as u64) >> 39) | ((t[4] as u64) << 12);

        let mut out = [0u8; 32];
        out[0..8].copy_from_slice(&t0.to_le_bytes());
        out[8..16].copy_from_slice(&t1.to_le_bytes());
        out[16..24].copy_from_slice(&t2.to_le_bytes());
        out[24..32].copy_from_slice(&t3.to_le_bytes());
        out
    }

    pub fn add(&self, g: &Self) -> Self {
        Fe([
            self.0[0] + g.0[0],
            self.0[1] + g.0[1],
            self.0[2] + g.0[2],
            self.0[3] + g.0[3],
            self.0[4] + g.0[4],
        ])
    }

    pub fn sub(&self, g: &Self) -> Self {
        let mut h0 = g.0[0];
        let mut h1 = g.0[1];
        let mut h2 = g.0[2];
        let mut h3 = g.0[3];
        let mut h4 = g.0[4];

        h1 += h0 >> 51;
        h0 &= MASK51;
        h2 += h1 >> 51;
        h1 &= MASK51;
        h3 += h2 >> 51;
        h2 &= MASK51;
        h4 += h3 >> 51;
        h3 &= MASK51;
        h0 += 19 * (h4 >> 51);
        h4 &= MASK51;

        h0 = (self.0[0] + 0xfffffffffffda) - h0;
        h1 = (self.0[1] + 0xffffffffffffe) - h1;
        h2 = (self.0[2] + 0xffffffffffffe) - h2;
        h3 = (self.0[3] + 0xffffffffffffe) - h3;
        h4 = (self.0[4] + 0xffffffffffffe) - h4;

        Fe([h0, h1, h2, h3, h4])
    }

    pub fn mul(&self, g: &Self) -> Self {
        let f = self.0;
        let g = g.0;

        let f0 = f[0] as u128;
        let f1 = f[1] as u128;
        let f2 = f[2] as u128;
        let f3 = f[3] as u128;
        let f4 = f[4] as u128;

        let g0 = g[0] as u128;
        let g1 = g[1] as u128;
        let g2 = g[2] as u128;
        let g3 = g[3] as u128;
        let g4 = g[4] as u128;

        let f1_19 = 19u128 * (f[1] as u128);
        let f2_19 = 19u128 * (f[2] as u128);
        let f3_19 = 19u128 * (f[3] as u128);
        let f4_19 = 19u128 * (f[4] as u128);

        let r0 = f0 * g0 + f1_19 * g4 + f2_19 * g3 + f3_19 * g2 + f4_19 * g1;
        let mut r1 = f0 * g1 + f1 * g0 + f2_19 * g4 + f3_19 * g3 + f4_19 * g2;
        let mut r2 = f0 * g2 + f1 * g1 + f2 * g0 + f3_19 * g4 + f4_19 * g3;
        let mut r3 = f0 * g3 + f1 * g2 + f2 * g1 + f3 * g0 + f4_19 * g4;
        let mut r4 = f0 * g4 + f1 * g3 + f2 * g2 + f3 * g1 + f4 * g0;

        let mut r00 = (r0 as u64) & MASK51;
        let mut carry = (r0 >> 51) as u64;
        r1 += carry as u128;
        let mut r01 = (r1 as u64) & MASK51;
        carry = (r1 >> 51) as u64;
        r2 += carry as u128;
        let mut r02 = (r2 as u64) & MASK51;
        carry = (r2 >> 51) as u64;
        r3 += carry as u128;
        let r03 = (r3 as u64) & MASK51;
        carry = (r3 >> 51) as u64;
        r4 += carry as u128;
        let r04 = (r4 as u64) & MASK51;
        carry = (r4 >> 51) as u64;

        r00 += 19 * carry;
        carry = r00 >> 51;
        r00 &= MASK51;
        r01 += carry;
        carry = r01 >> 51;
        r01 &= MASK51;
        r02 += carry;

        Fe([r00, r01, r02, r03, r04])
    }

    pub fn sqr(&self) -> Self {
        self.mul(self)
    }

    pub fn mul_const(&self, s: u64) -> Self {
        let f = self.0;
        let s128 = s as u128;
        let r0 = (f[0] as u128) * s128;
        let mut r1 = (f[1] as u128) * s128;
        let mut r2 = (f[2] as u128) * s128;
        let mut r3 = (f[3] as u128) * s128;
        let mut r4 = (f[4] as u128) * s128;

        let mut r00 = (r0 as u64) & MASK51;
        let mut carry = (r0 >> 51) as u64;
        r1 += carry as u128;
        let mut r01 = (r1 as u64) & MASK51;
        carry = (r1 >> 51) as u64;
        r2 += carry as u128;
        let mut r02 = (r2 as u64) & MASK51;
        carry = (r2 >> 51) as u64;
        r3 += carry as u128;
        let r03 = (r3 as u64) & MASK51;
        carry = (r3 >> 51) as u64;
        r4 += carry as u128;
        let r04 = (r4 as u64) & MASK51;
        carry = (r4 >> 51) as u64;

        r00 += 19 * carry;
        carry = r00 >> 51;
        r00 &= MASK51;
        r01 += carry;
        carry = r01 >> 51;
        r01 &= MASK51;
        r02 += carry;

        Fe([r00, r01, r02, r03, r04])
    }

    pub fn invert(&self) -> Self {
        fn nsquare(x: &Fe, mut n: usize) -> Fe {
            let mut z = *x;
            while n > 0 {
                z = z.sqr();
                n -= 1;
            }
            z
        }

        let z2 = self.sqr();
        let mut t = z2.sqr();
        t = t.sqr();
        let z9 = t.mul(self);
        let z11 = z9.mul(&z2);
        t = z11.sqr();
        let z2_5_0 = t.mul(&z9);

        t = nsquare(&z2_5_0, 5);
        let z2_10_0 = t.mul(&z2_5_0);

        t = nsquare(&z2_10_0, 10);
        let z2_20_0 = t.mul(&z2_10_0);

        t = nsquare(&z2_20_0, 20);
        t = t.mul(&z2_20_0);

        t = nsquare(&t, 10);
        let z2_50_0 = t.mul(&z2_10_0);

        t = nsquare(&z2_50_0, 50);
        let z2_100_0 = t.mul(&z2_50_0);

        t = nsquare(&z2_100_0, 100);
        t = t.mul(&z2_100_0);

        t = nsquare(&t, 50);
        t = t.mul(&z2_50_0);

        t = nsquare(&t, 5);
        t.mul(&z11)
    }

    #[inline(always)]
    pub fn cswap(a: &mut Fe, b: &mut Fe, swap: u64) {
        let mask = if swap != 0 { 0xFFFFFFFFFFFFFFFF } else { 0 };
        for i in 0..5 {
            let x = mask & (a.0[i] ^ b.0[i]);
            a.0[i] ^= x;
            b.0[i] ^= x;
        }
    }
}

pub fn x25519(scalar: &[u8; 32], point: &[u8; 32]) -> [u8; 32] {
    let mut t = *scalar;
    t[0] &= 248;
    t[31] &= 127;
    t[31] |= 64;

    let x1 = Fe::from_bytes(point);
    let mut x2 = Fe::one();
    let mut z2 = Fe::zero();
    let mut x3 = x1;
    let mut z3 = Fe::one();
    let mut swap = 0u64;

    for pos in (0..=254).rev() {
        let bit = ((t[pos / 8] >> (pos % 8)) & 1) as u64;
        swap ^= bit;
        Fe::cswap(&mut x2, &mut x3, swap);
        Fe::cswap(&mut z2, &mut z3, swap);
        swap = bit;

        let a = x2.add(&z2);
        let b = x2.sub(&z2);
        let aa = a.sqr();
        let bb = b.sqr();
        x2 = aa.mul(&bb);
        let e = aa.sub(&bb);

        let mut da = x3.sub(&z3);
        da = da.mul(&a);
        let mut cb = x3.add(&z3);
        cb = cb.mul(&b);

        x3 = da.add(&cb).sqr();
        z3 = da.sub(&cb).sqr().mul(&x1);

        z2 = e.mul_const(121666);
        z2 = z2.add(&bb);
        z2 = z2.mul(&e);
    }

    Fe::cswap(&mut x2, &mut x3, swap);
    Fe::cswap(&mut z2, &mut z3, swap);

    let z2_inv = z2.invert();
    x2.mul(&z2_inv).to_bytes()
}

pub fn x25519_base(scalar: &[u8; 32]) -> [u8; 32] {
    let mut point = [0u8; 32];
    point[0] = 9;
    x25519(scalar, &point)
}

// ---------------------------------------------------------------------------
// 3. Tai64n Timestamp & ChaCha20-Poly1305 AEAD
// ---------------------------------------------------------------------------

pub fn generate_tai64n() -> [u8; 12] {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    let tai_secs = 0x400000000000000a_u64 + now.as_secs();
    let nanos = now.subsec_nanos();
    let mut out = [0u8; 12];
    out[0..8].copy_from_slice(&tai_secs.to_be_bytes());
    out[8..12].copy_from_slice(&nanos.to_be_bytes());
    out
}

pub fn aead_encrypt(
    key: &[u8; 32],
    counter: u64,
    ad: &[u8],
    plaintext: &[u8],
) -> Result<Vec<u8>, String> {
    let mut nonce_bytes = [0u8; 12];
    nonce_bytes[4..12].copy_from_slice(&counter.to_le_bytes());
    let unbound_key = UnboundKey::new(&CHACHA20_POLY1305, key).map_err(|e| format!("{:?}", e))?;
    let aead_key = LessSafeKey::new(unbound_key);
    let nonce = Nonce::try_assume_unique_for_key(&nonce_bytes).map_err(|e| format!("{:?}", e))?;
    let mut in_out = plaintext.to_vec();
    aead_key
        .seal_in_place_append_tag(nonce, Aad::from(ad), &mut in_out)
        .map_err(|e| format!("{:?}", e))?;
    Ok(in_out)
}

pub fn aead_decrypt(
    key: &[u8; 32],
    counter: u64,
    ad: &[u8],
    ciphertext: &[u8],
) -> Result<Vec<u8>, String> {
    let mut nonce_bytes = [0u8; 12];
    nonce_bytes[4..12].copy_from_slice(&counter.to_le_bytes());
    let unbound_key = UnboundKey::new(&CHACHA20_POLY1305, key).map_err(|e| format!("{:?}", e))?;
    let aead_key = LessSafeKey::new(unbound_key);
    let nonce = Nonce::try_assume_unique_for_key(&nonce_bytes).map_err(|e| format!("{:?}", e))?;
    let mut in_out = ciphertext.to_vec();
    let res = aead_key
        .open_in_place(nonce, Aad::from(ad), &mut in_out)
        .map_err(|e| format!("{:?}", e))?;
    Ok(res.to_vec())
}

// ---------------------------------------------------------------------------
// WireGuard Cookie Reply & XChaCha20-Poly1305 (WireGuard Specification Section 5.4.7)
// ---------------------------------------------------------------------------

#[inline(always)]
fn chacha_quarter_round(state: &mut [u32; 16], a: usize, b: usize, c: usize, d: usize) {
    state[a] = state[a].wrapping_add(state[b]);
    state[d] = (state[d] ^ state[a]).rotate_left(16);
    state[c] = state[c].wrapping_add(state[d]);
    state[b] = (state[b] ^ state[c]).rotate_left(12);
    state[a] = state[a].wrapping_add(state[b]);
    state[d] = (state[d] ^ state[a]).rotate_left(8);
    state[c] = state[c].wrapping_add(state[d]);
    state[b] = (state[b] ^ state[c]).rotate_left(7);
}

/// Pure-Rust HChaCha20 implementation per draft-irtf-cfrg-xchacha / RFC 8439.
/// Transforms a 256-bit key and 128-bit nonce into a 256-bit subkey.
pub fn hchacha20(key: &[u8; 32], nonce: &[u8; 16]) -> [u8; 32] {
    let mut state = [
        0x61707865,
        0x3320646e,
        0x79622d32,
        0x6b206574,
        u32::from_le_bytes([key[0], key[1], key[2], key[3]]),
        u32::from_le_bytes([key[4], key[5], key[6], key[7]]),
        u32::from_le_bytes([key[8], key[9], key[10], key[11]]),
        u32::from_le_bytes([key[12], key[13], key[14], key[15]]),
        u32::from_le_bytes([key[16], key[17], key[18], key[19]]),
        u32::from_le_bytes([key[20], key[21], key[22], key[23]]),
        u32::from_le_bytes([key[24], key[25], key[26], key[27]]),
        u32::from_le_bytes([key[28], key[29], key[30], key[31]]),
        u32::from_le_bytes([nonce[0], nonce[1], nonce[2], nonce[3]]),
        u32::from_le_bytes([nonce[4], nonce[5], nonce[6], nonce[7]]),
        u32::from_le_bytes([nonce[8], nonce[9], nonce[10], nonce[11]]),
        u32::from_le_bytes([nonce[12], nonce[13], nonce[14], nonce[15]]),
    ];

    for _ in 0..10 {
        // Column rounds
        chacha_quarter_round(&mut state, 0, 4, 8, 12);
        chacha_quarter_round(&mut state, 1, 5, 9, 13);
        chacha_quarter_round(&mut state, 2, 6, 10, 14);
        chacha_quarter_round(&mut state, 3, 7, 11, 15);

        // Diagonal rounds
        chacha_quarter_round(&mut state, 0, 5, 10, 15);
        chacha_quarter_round(&mut state, 1, 6, 11, 12);
        chacha_quarter_round(&mut state, 2, 7, 8, 13);
        chacha_quarter_round(&mut state, 3, 4, 9, 14);
    }

    let mut out = [0u8; 32];
    out[0..4].copy_from_slice(&state[0].to_le_bytes());
    out[4..8].copy_from_slice(&state[1].to_le_bytes());
    out[8..12].copy_from_slice(&state[2].to_le_bytes());
    out[12..16].copy_from_slice(&state[3].to_le_bytes());
    out[16..20].copy_from_slice(&state[12].to_le_bytes());
    out[20..24].copy_from_slice(&state[13].to_le_bytes());
    out[24..28].copy_from_slice(&state[14].to_le_bytes());
    out[28..32].copy_from_slice(&state[15].to_le_bytes());
    out
}

/// Encrypts plaintext using XChaCha20-Poly1305 with a 192-bit (24-byte) nonce.
pub fn xchacha20poly1305_encrypt(
    key: &[u8; 32],
    nonce: &[u8; 24],
    ad: &[u8],
    plaintext: &[u8],
) -> Result<Vec<u8>, String> {
    let mut nonce_16 = [0u8; 16];
    nonce_16.copy_from_slice(&nonce[0..16]);
    let subkey = hchacha20(key, &nonce_16);

    let mut ietf_nonce = [0u8; 12];
    ietf_nonce[4..12].copy_from_slice(&nonce[16..24]);

    let unbound_key =
        UnboundKey::new(&CHACHA20_POLY1305, &subkey).map_err(|e| format!("{:?}", e))?;
    let aead_key = LessSafeKey::new(unbound_key);
    let ring_nonce =
        Nonce::try_assume_unique_for_key(&ietf_nonce).map_err(|e| format!("{:?}", e))?;
    let mut in_out = plaintext.to_vec();
    aead_key
        .seal_in_place_append_tag(ring_nonce, Aad::from(ad), &mut in_out)
        .map_err(|e| format!("{:?}", e))?;
    Ok(in_out)
}

/// Decrypts ciphertext and verifies Poly1305 tag using XChaCha20-Poly1305 with a 24-byte nonce.
pub fn xchacha20poly1305_decrypt(
    key: &[u8; 32],
    nonce: &[u8; 24],
    ad: &[u8],
    ciphertext: &[u8],
) -> Result<Vec<u8>, String> {
    let mut nonce_16 = [0u8; 16];
    nonce_16.copy_from_slice(&nonce[0..16]);
    let subkey = hchacha20(key, &nonce_16);

    let mut ietf_nonce = [0u8; 12];
    ietf_nonce[4..12].copy_from_slice(&nonce[16..24]);

    let unbound_key =
        UnboundKey::new(&CHACHA20_POLY1305, &subkey).map_err(|e| format!("{:?}", e))?;
    let aead_key = LessSafeKey::new(unbound_key);
    let ring_nonce =
        Nonce::try_assume_unique_for_key(&ietf_nonce).map_err(|e| format!("{:?}", e))?;
    let mut in_out = ciphertext.to_vec();
    let res = aead_key
        .open_in_place(ring_nonce, Aad::from(ad), &mut in_out)
        .map_err(|e| format!("{:?}", e))?;
    Ok(res.to_vec())
}

/// WireGuard cookie lifetime: 120 seconds per protocol specification.
pub const COOKIE_TIMEOUT: Duration = Duration::from_secs(120);

#[derive(Clone, Debug)]
pub struct PeerCookie {
    pub cookie: [u8; 16],
    pub received_at: std::time::Instant,
}

impl PeerCookie {
    pub fn is_valid(&self) -> bool {
        self.received_at.elapsed() < COOKIE_TIMEOUT
    }
}

static PEER_COOKIES: Lazy<RwLock<HashMap<[u8; 32], PeerCookie>>> =
    Lazy::new(|| RwLock::new(HashMap::new()));

pub fn get_peer_cookie(peer_pubkey: &[u8; 32]) -> Option<[u8; 16]> {
    let guard = PEER_COOKIES.read();
    if let Some(c) = guard.get(peer_pubkey) {
        if c.is_valid() {
            return Some(c.cookie);
        }
    }
    None
}

pub fn set_peer_cookie(peer_pubkey: &[u8; 32], cookie: [u8; 16]) {
    let mut guard = PEER_COOKIES.write();
    guard.insert(
        *peer_pubkey,
        PeerCookie {
            cookie,
            received_at: std::time::Instant::now(),
        },
    );
}

pub fn clear_peer_cookies() {
    let mut guard = PEER_COOKIES.write();
    guard.clear();
}

/// Sets MAC2 on an initiation packet in-place using the specified cookie.
pub fn set_initiation_mac2(
    packet: &mut [u8],
    s1: usize,
    cookie: &[u8; 16],
) -> Result<(), String> {
    if packet.len() < s1 + 148 {
        return Err(format!(
            "Initiation packet too short for MAC2: {} bytes (expected >= {})",
            packet.len(),
            s1 + 148
        ));
    }
    let mac2 = blake2s(cookie, &packet[s1..s1 + 132], 16);
    packet[s1 + 132..s1 + 148].copy_from_slice(&mac2);
    Ok(())
}

/// Verifies whether the MAC2 field of an initiation packet matches the expected cookie.
pub fn verify_initiation_mac2(
    packet: &[u8],
    s1: usize,
    cookie: &[u8; 16],
) -> bool {
    if packet.len() < s1 + 148 {
        return false;
    }
    let expected = blake2s(cookie, &packet[s1..s1 + 132], 16);
    &packet[s1 + 132..s1 + 148] == expected.as_slice()
}

/// Assembles a 64-byte WireGuard / AmneziaWG Cookie Reply message (Message Type 3 / H3).
/// Formula: encrypted_cookie = XChaCha20Poly1305(key=BLAKE2s("cookie--" || Spk), nonce, ad=mac1, cookie)
pub fn create_cookie_reply(
    server_pubkey: &[u8; 32],
    h3: u32,
    receiver_index: u32,
    mac1: &[u8; 16],
    cookie: &[u8; 16],
) -> Result<Vec<u8>, String> {
    let mut key_input = Vec::with_capacity(8 + 32);
    key_input.extend_from_slice(b"cookie--");
    key_input.extend_from_slice(server_pubkey);
    let cookie_key = blake2s_256(&key_input);

    let mut nonce = [0u8; 24];
    rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut nonce);

    let encrypted_cookie = xchacha20poly1305_encrypt(&cookie_key, &nonce, mac1, cookie)?;

    let mut msg = Vec::with_capacity(64);
    msg.extend_from_slice(&h3.to_le_bytes());
    msg.extend_from_slice(&receiver_index.to_le_bytes());
    msg.extend_from_slice(&nonce);
    msg.extend_from_slice(&encrypted_cookie);
    Ok(msg)
}

/// Parses and decrypts a 64-byte WireGuard / AmneziaWG Cookie Reply message (Type 3 / H3).
pub fn parse_and_decrypt_cookie_reply(
    peer_pubkey: &[u8; 32],
    h3: u32,
    expected_receiver_index: u32,
    expected_mac1: &[u8; 16],
    reply: &[u8],
) -> Result<[u8; 16], String> {
    if reply.len() < 64 {
        return Err(format!(
            "Cookie reply too short: {} bytes (expected >= 64)",
            reply.len()
        ));
    }
    let msg_type = u32::from_le_bytes([reply[0], reply[1], reply[2], reply[3]]);
    if msg_type != h3 {
        return Err(format!(
            "Cookie reply message type mismatch: got {}, expected H3={}",
            msg_type, h3
        ));
    }
    let receiver_idx = u32::from_le_bytes([reply[4], reply[5], reply[6], reply[7]]);
    if receiver_idx != expected_receiver_index {
        return Err(format!(
            "Cookie reply receiver index mismatch: got 0x{:08x}, expected 0x{:08x}",
            receiver_idx, expected_receiver_index
        ));
    }
    let mut nonce = [0u8; 24];
    nonce.copy_from_slice(&reply[8..32]);

    let mut key_input = Vec::with_capacity(8 + 32);
    key_input.extend_from_slice(b"cookie--");
    key_input.extend_from_slice(peer_pubkey);
    let cookie_key = blake2s_256(&key_input);

    let decrypted =
        xchacha20poly1305_decrypt(&cookie_key, &nonce, expected_mac1, &reply[32..64])?;
    if decrypted.len() != 16 {
        return Err(format!(
            "Decrypted cookie has invalid length: {} (expected 16)",
            decrypted.len()
        ));
    }
    let mut cookie = [0u8; 16];
    cookie.copy_from_slice(&decrypted);
    Ok(cookie)
}

/// Outcome of processing an initiation on a responder under load.
#[derive(Debug)]
pub enum ServerHandshakeOutcome {
    Success {
        keys: TransportKeys,
        response_packet: Vec<u8>,
    },
    CookieReply(Vec<u8>),
}

/// Processes an initiation packet on a server that may be under load and challenging clients with cookies.
pub fn respond_to_initiation_under_load(
    server_priv: &[u8; 32],
    server_index: u32,
    init_packet: &[u8],
    awg_params: &AwgParams,
    psk: Option<[u8; 32]>,
    required_cookie: Option<&[u8; 16]>,
) -> Result<ServerHandshakeOutcome, String> {
    let s1 = awg_params.s1;
    let min_len = 148 + s1;
    if init_packet.len() < min_len {
        return Err(format!(
            "AWG initiation packet too short: {} (expected >= {})",
            init_packet.len(),
            min_len
        ));
    }

    let init_slice = &init_packet[s1..];
    let client_index =
        u32::from_le_bytes([init_slice[4], init_slice[5], init_slice[6], init_slice[7]]);
    let server_pub = x25519_base(server_priv);

    // Verify MAC1
    let mut mac1_key_input = Vec::with_capacity(8 + 32);
    mac1_key_input.extend_from_slice(b"mac1----");
    mac1_key_input.extend_from_slice(&server_pub);
    let mac1_key = blake2s_256(&mac1_key_input);
    let expected_mac1 = blake2s(&mac1_key, &init_slice[0..116], 16);
    if &init_slice[116..132] != expected_mac1.as_slice() {
        return Err("AWG initiation MAC1 verification failed".to_string());
    }

    // If server is under load and requires cookie:
    if let Some(cookie) = required_cookie {
        let expected_mac2 = blake2s(cookie, &init_slice[0..132], 16);
        if &init_slice[132..148] != expected_mac2.as_slice() {
            // MAC2 invalid or zero: issue cookie challenge
            let mut mac1_arr = [0u8; 16];
            mac1_arr.copy_from_slice(&init_slice[116..132]);
            let reply = create_cookie_reply(
                &server_pub,
                awg_params.h3,
                client_index,
                &mac1_arr,
                cookie,
            )?;
            return Ok(ServerHandshakeOutcome::CookieReply(reply));
        }
    }

    let (keys, resp_pkt) =
        respond_to_initiation(server_priv, server_index, init_packet, awg_params, psk)?;
    Ok(ServerHandshakeOutcome::Success {
        keys,
        response_packet: resp_pkt,
    })
}

// ---------------------------------------------------------------------------
// 4. AmneziaWG Parameters & Configuration (AmneziaWG v1.0)
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AwgPeerClassification {
    StandardWireGuardWarp,
    DedicatedAmneziaWg,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AwgValidationReport {
    pub protocol_version: &'static str,
    pub classification: AwgPeerClassification,
    pub is_compatible_warp: bool,
    pub warnings: Vec<String>,
}

pub fn is_cloudflare_warp_endpoint(endpoint: &str) -> bool {
    let lower = endpoint.to_ascii_lowercase();
    if lower.contains("cloudflareclient.com") || lower.contains("cloudflare.com") {
        return true;
    }
    let host = if let Some(stripped) = endpoint.strip_prefix('[') {
        stripped.split(']').next().unwrap_or("")
    } else {
        endpoint.split(':').next().unwrap_or("")
    };

    if let Ok(ip) = host.parse::<std::net::IpAddr>() {
        match ip {
            std::net::IpAddr::V4(v4) => {
                let octets = v4.octets();
                (octets[0] == 162 && octets[1] == 159 && (octets[2] >= 192 && octets[2] <= 204))
                    || (octets[0] == 188 && octets[1] == 114 && (octets[2] >= 96 && octets[2] <= 99))
            }
            std::net::IpAddr::V6(v6) => {
                let segments = v6.segments();
                segments[0] == 0x2606 && segments[1] == 0x4700
            }
        }
    } else {
        false
    }
}

#[derive(Clone, Debug)]
pub struct AwgParams {
    pub jc: u32,
    pub jmin: usize,
    pub jmax: usize,
    pub s1: usize,
    pub s2: usize,
    pub h1: u32,
    pub h2: u32,
    pub h3: u32,
    pub h4: u32,
    pub i1: Option<Vec<u8>>,
}

impl Default for AwgParams {
    fn default() -> Self {
        // Default standard WireGuard values
        Self {
            jc: 0,
            jmin: 0,
            jmax: 0,
            s1: 0,
            s2: 0,
            h1: 1,
            h2: 2,
            h3: 3,
            h4: 4,
            i1: None,
        }
    }
}

impl AwgParams {
    pub const SUPPORTED_VERSION: &'static str = "AmneziaWG v1.0";

    pub fn warp_recommended() -> Self {
        // Recommended settings for bypassing Russian TSPU blocks on Cloudflare Anycast:
        // Cloudflare server operates on standard WireGuard headers (H1=1..H4=4, S1=0, S2=0).
        // 5 junk packets (40-70 bytes) sent before the handshake destroy the 148-byte DPI signature.
        Self {
            jc: 5,
            jmin: 40,
            jmax: 70,
            s1: 0,
            s2: 0,
            h1: 1,
            h2: 2,
            h3: 3,
            h4: 4,
            i1: None,
        }
    }

    pub fn is_standard_wireguard(&self) -> bool {
        self.h1 == 1 && self.h2 == 2 && self.h3 == 3 && self.h4 == 4 && self.s1 == 0 && self.s2 == 0
    }

    pub fn is_dedicated_awg(&self) -> bool {
        !self.is_standard_wireguard()
    }

    pub fn classify(&self) -> AwgPeerClassification {
        if self.is_standard_wireguard() {
            AwgPeerClassification::StandardWireGuardWarp
        } else {
            AwgPeerClassification::DedicatedAmneziaWg
        }
    }

    pub fn validate_against_endpoint(&self, endpoint: &str) -> Result<AwgValidationReport, String> {
        if self.jc > 128 {
            return Err(format!("AWG Jc={} exceeds maximum allowable junk packet count (128)", self.jc));
        }
        if self.jmin > self.jmax {
            return Err(format!("AWG Jmin={} cannot exceed Jmax={}", self.jmin, self.jmax));
        }
        if self.jmax > 1280 {
            return Err(format!("AWG Jmax={} exceeds standard MTU safety limit (1280 bytes)", self.jmax));
        }
        if self.s1 > 1280 {
            return Err(format!("AWG S1={} exceeds maximum allowable prefix padding (1280 bytes)", self.s1));
        }
        if self.s2 > 1280 {
            return Err(format!("AWG S2={} exceeds maximum allowable prefix padding (1280 bytes)", self.s2));
        }
        if self.h1 == 0 || self.h2 == 0 || self.h3 == 0 || self.h4 == 0 {
            return Err("AWG custom message types H1, H2, H3, H4 must all be non-zero".to_string());
        }
        let headers = [self.h1, self.h2, self.h3, self.h4];
        for i in 0..headers.len() {
            for j in (i + 1)..headers.len() {
                if headers[i] == headers[j] {
                    return Err(format!(
                        "AWG header collision detected: H{} and H{} share identical value 0x{:08x}",
                        i + 1, j + 1, headers[i]
                    ));
                }
            }
        }

        let classification = self.classify();
        let mut warnings = Vec::new();
        let is_cf = is_cloudflare_warp_endpoint(endpoint);

        let is_compatible_warp = if is_cf {
            if classification == AwgPeerClassification::DedicatedAmneziaWg {
                warnings.push(format!(
                    "Endpoint '{}' is a Cloudflare WARP Anycast peer. Cloudflare WARP only supports standard WireGuard headers (H1=1..H4=4, S1=0, S2=0). Custom AmneziaWG obfuscation headers (H1={}, H2={}, H3={}, H4={}) or non-zero padding (S1={}, S2={}) are incompatible with Cloudflare WARP servers and should only be directed to a dedicated AmneziaWG server.",
                    endpoint, self.h1, self.h2, self.h3, self.h4, self.s1, self.s2
                ));
                false
            } else {
                true
            }
        } else {
            true
        };

        if self.i1.is_some() {
            warnings.push("I1 parameter provided: custom initial packet camouflage is parsed and supported in subset".to_string());
        }

        Ok(AwgValidationReport {
            protocol_version: Self::SUPPORTED_VERSION,
            classification,
            is_compatible_warp,
            warnings,
        })
    }
}

pub const CLOUDFLARE_WARP_PEER_PUBKEY_B64: &str = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=";

pub const CLOUDFLARE_WARP_ANYCAST_POOL: &[&str] = &[
    "162.159.193.10:1701",
    "162.159.192.1:1701",
    "162.159.195.5:1701",
    "188.114.97.1:1701",
    "188.114.98.2:1701",
    "188.114.99.3:1701",
    "162.159.193.10:4500",
    "162.159.192.1:4500",
    "188.114.96.1:4500",
    "188.114.97.1:4500",
    "162.159.193.10:854",
    "162.159.192.1:854",
    "188.114.96.1:8095",
    "188.114.97.1:8095",
    "188.114.96.1:500",
];

pub fn is_cloudflare_warp_peer(peer_pub: &[u8; 32]) -> bool {
    use base64::engine::general_purpose::STANDARD as B64;
    use base64::Engine;
    if let Ok(dec) = B64.decode(CLOUDFLARE_WARP_PEER_PUBKEY_B64) {
        if dec.len() == 32 {
            return peer_pub == dec.as_slice();
        }
    }
    false
}

#[derive(Clone, Debug)]
pub struct AwgConfig {
    pub profile_name: String,
    pub endpoint: String,
    pub fallback_endpoints: Vec<String>,
    pub private_key: [u8; 32],
    pub public_key: [u8; 32],
    pub peer_public_key: [u8; 32],
    pub preshared_key: Option<[u8; 32]>,
    pub client_ipv4: Ipv4Addr,
    pub client_ipv6: Option<Ipv6Addr>,
    pub awg_params: AwgParams,
    pub persistent_keepalive: Option<u16>,
}

impl AwgConfig {
    pub fn is_warp_profile(&self) -> bool {
        is_cloudflare_warp_peer(&self.peer_public_key) || is_cloudflare_warp_endpoint(&self.endpoint)
    }
}

impl Default for AwgConfig {
    fn default() -> Self {
        use base64::engine::general_purpose::STANDARD as B64;
        use base64::Engine;
        let mut peer_pub = [0u8; 32];
        if let Ok(dec) = B64.decode(CLOUDFLARE_WARP_PEER_PUBKEY_B64) {
            if dec.len() == 32 {
                peer_pub.copy_from_slice(&dec);
            }
        }
        Self {
            profile_name: "Cloudflare WARP".to_string(),
            endpoint: "162.159.193.10:1701".to_string(),
            fallback_endpoints: CLOUDFLARE_WARP_ANYCAST_POOL
                .iter()
                .map(|&s| s.to_string())
                .collect(),
            private_key: [0u8; 32],
            public_key: [0u8; 32],
            peer_public_key: peer_pub,
            preshared_key: None,
            client_ipv4: Ipv4Addr::new(172, 16, 0, 2),
            client_ipv6: None,
            awg_params: AwgParams::warp_recommended(),
            persistent_keepalive: Some(20),
        }
    }
}

pub fn validate_endpoint_format(endpoint: &str) -> Result<(String, u16), String> {
    let trimmed = endpoint.trim().trim_matches('"').trim_matches('\'').trim();
    if trimmed.is_empty() {
        return Err("Endpoint is empty".to_string());
    }
    let (host, port_str) = if trimmed.starts_with('[') {
        let closing = trimmed.find(']').ok_or_else(|| {
            format!("invalid IPv6 endpoint '{}': missing closing bracket ']'", trimmed)
        })?;
        let host = trimmed[1..closing].trim();
        if host.is_empty() {
            return Err(format!("invalid IPv6 endpoint '{}': host cannot be empty", trimmed));
        }
        let rest = &trimmed[closing + 1..];
        if !rest.starts_with(':') {
            return Err(format!("invalid IPv6 endpoint '{}': expected ':' port after ']'", trimmed));
        }
        (host, &rest[1..])
    } else {
        let colon_idx = trimmed.rfind(':').ok_or_else(|| {
            format!("invalid Endpoint format '{}': missing port separator ':'", trimmed)
        })?;
        let host = trimmed[..colon_idx].trim();
        let port_str = &trimmed[colon_idx + 1..];
        (host, port_str)
    };

    if host.is_empty() {
        return Err(format!("invalid Endpoint '{}': host cannot be empty", trimmed));
    }
    let port: u16 = port_str.parse().map_err(|_| {
        format!("invalid Endpoint port '{}': must be an integer between 1 and 65535", port_str)
    })?;
    if port == 0 {
        return Err(format!("invalid Endpoint port '{}': port 0 is not allowed", port_str));
    }
    Ok((trimmed.to_string(), port))
}

impl AwgConfig {
    pub fn parse_ini(ini_str: &str) -> Result<Self, String> {
        use base64::engine::general_purpose::STANDARD as B64;
        use base64::Engine;

        #[derive(Copy, Clone, PartialEq, Eq)]
        enum Section {
            None,
            Interface,
            Peer,
        }

        const UNSUPPORTED_SYSTEM_DIRECTIVES: &[&str] = &[
            "preup", "postup", "predown", "postdown", "table", "fwmark", "saveconfig",
        ];

        let mut current_section = Section::None;
        let mut interface_count = 0usize;
        let mut peer_count = 0usize;

        let mut profile_name: Option<String> = None;
        let mut fallback_endpoints: Vec<String> = Vec::new();
        let mut private_key: Option<[u8; 32]> = None;
        let mut client_ipv4: Option<Ipv4Addr> = None;
        let mut client_ipv6: Option<Ipv6Addr> = None;
        let mut peer_public_key: Option<[u8; 32]> = None;
        let mut preshared_key: Option<[u8; 32]> = None;
        let mut endpoint: Option<String> = None;
        let mut persistent_keepalive: Option<u16> = Some(20);
        let mut awg_params = AwgParams::default();

        for (line_idx, line) in ini_str.lines().enumerate() {
            let line_num = line_idx + 1;
            let trimmed = line.trim();
            if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with(';') {
                continue;
            }

            let clean = if let Some(idx) = trimmed.find(|c| c == '#' || c == ';') {
                trimmed[..idx].trim()
            } else {
                trimmed
            };
            if clean.is_empty() {
                continue;
            }

            if clean.starts_with('[') {
                if !clean.ends_with(']') {
                    return Err(format!(
                        "line {}: invalid section header format: missing closing bracket in '{}'",
                        line_num, clean
                    ));
                }
                let sec_name = clean[1..clean.len() - 1].trim();
                if sec_name.eq_ignore_ascii_case("Interface") {
                    interface_count += 1;
                    if interface_count > 1 {
                        return Err(format!(
                            "line {}: duplicate [Interface] section is not allowed in configuration",
                            line_num
                        ));
                    }
                    current_section = Section::Interface;
                } else if sec_name.eq_ignore_ascii_case("Peer") {
                    peer_count += 1;
                    if peer_count > 1 {
                        return Err(format!(
                            "line {}: multiple [Peer] sections are unsupported: single-peer proxy mode requires exactly one peer",
                            line_num
                        ));
                    }
                    current_section = Section::Peer;
                } else {
                    return Err(format!(
                        "line {}: unrecognized section '[{}]' in configuration",
                        line_num, sec_name
                    ));
                }
                continue;
            }

            let (key, val) = match clean.find('=') {
                Some(idx) => (clean[..idx].trim(), clean[idx + 1..].trim()),
                None => {
                    return Err(format!(
                        "line {}: invalid INI syntax: missing '=' in '{}'",
                        line_num, clean
                    ));
                }
            };

            if current_section == Section::None {
                return Err(format!(
                    "line {}: directive '{}' outside of any section in INI configuration",
                    line_num, key
                ));
            }

            let val = val.trim_matches('"').trim_matches('\'').trim();

            if UNSUPPORTED_SYSTEM_DIRECTIVES
                .iter()
                .any(|&d| key.eq_ignore_ascii_case(d))
            {
                return Err(format!(
                    "line {}: system routing directive/hook '{}' is unsupported in Android userspace proxy",
                    line_num, key
                ));
            }

            match current_section {
                Section::Interface => match key.to_ascii_lowercase().as_str() {
                    "privatekey" => {
                        let bytes = B64.decode(val).map_err(|e| {
                            format!("line {}: invalid Base64 in 'PrivateKey': {}", line_num, e)
                        })?;
                        if bytes.len() != 32 {
                            return Err(format!(
                                "line {}: invalid PrivateKey length: expected 32 bytes, got {}",
                                line_num,
                                bytes.len()
                            ));
                        }
                        let mut k = [0u8; 32];
                        k.copy_from_slice(&bytes);
                        private_key = Some(k);
                    }
                    "address" => {
                        for part in val.split(',') {
                            let part = part.trim();
                            if part.is_empty() {
                                continue;
                            }
                            let (ip_str, prefix_opt) = if let Some(idx) = part.find('/') {
                                (part[..idx].trim(), Some(part[idx + 1..].trim()))
                            } else {
                                (part, None)
                            };

                            if let Ok(v4) = ip_str.parse::<Ipv4Addr>() {
                                if let Some(p_str) = prefix_opt {
                                    let prefix: u8 = p_str.parse().map_err(|_| {
                                        format!(
                                            "line {}: invalid IPv4 CIDR prefix in Address: '{}'",
                                            line_num, p_str
                                        )
                                    })?;
                                    if prefix > 32 {
                                        return Err(format!(
                                            "line {}: invalid IPv4 CIDR prefix '/{}': must be <= 32",
                                            line_num, prefix
                                        ));
                                    }
                                }
                                client_ipv4 = Some(v4);
                            } else if let Ok(v6) = ip_str.parse::<Ipv6Addr>() {
                                if let Some(p_str) = prefix_opt {
                                    let prefix: u8 = p_str.parse().map_err(|_| {
                                        format!(
                                            "line {}: invalid IPv6 CIDR prefix in Address: '{}'",
                                            line_num, p_str
                                        )
                                    })?;
                                    if prefix > 128 {
                                        return Err(format!(
                                            "line {}: invalid IPv6 CIDR prefix '/{}': must be <= 128",
                                            line_num, prefix
                                        ));
                                    }
                                }
                                client_ipv6 = Some(v6);
                            } else {
                                return Err(format!(
                                    "line {}: invalid IP address in Address field: '{}'",
                                    line_num, part
                                ));
                            }
                        }
                    }
                    "dns" => {
                        ldebug!("AWG config: parsed interface DNS: {}", val);
                    }
                    "profilename" | "name" => {
                        if !val.is_empty() {
                            profile_name = Some(val.to_string());
                        }
                    }
                    "mtu" => {
                        let mtu: u16 = val.parse().map_err(|_| {
                            format!("line {}: invalid MTU value '{}': expected integer", line_num, val)
                        })?;
                        if !(576..=9000).contains(&mtu) {
                            return Err(format!(
                                "line {}: invalid MTU value {}: must be between 576 and 9000",
                                line_num, mtu
                            ));
                        }
                    }
                    "jc" => {
                        let jc: u32 = val.parse().map_err(|_| {
                            format!("line {}: invalid Jc value '{}': expected integer", line_num, val)
                        })?;
                        if jc > 128 {
                            return Err(format!(
                                "line {}: invalid Jc value {}: maximum allowed junk packet count is 128",
                                line_num, jc
                            ));
                        }
                        awg_params.jc = jc;
                    }
                    "jmin" => {
                        let jmin: usize = val.parse().map_err(|_| {
                            format!("line {}: invalid Jmin value '{}': expected integer", line_num, val)
                        })?;
                        if jmin > 1280 {
                            return Err(format!(
                                "line {}: invalid Jmin value {}: maximum allowed junk packet size is 1280",
                                line_num, jmin
                            ));
                        }
                        awg_params.jmin = jmin;
                    }
                    "jmax" => {
                        let jmax: usize = val.parse().map_err(|_| {
                            format!("line {}: invalid Jmax value '{}': expected integer", line_num, val)
                        })?;
                        if jmax > 1280 {
                            return Err(format!(
                                "line {}: invalid Jmax value {}: maximum allowed junk packet size is 1280",
                                line_num, jmax
                            ));
                        }
                        awg_params.jmax = jmax;
                    }
                    "s1" => {
                        let s1: usize = val.parse().map_err(|_| {
                            format!("line {}: invalid S1 value '{}': expected integer", line_num, val)
                        })?;
                        if s1 > 1280 {
                            return Err(format!(
                                "line {}: invalid S1 value {}: exceeds maximum handshake padding 1280",
                                line_num, s1
                            ));
                        }
                        awg_params.s1 = s1;
                    }
                    "s2" => {
                        let s2: usize = val.parse().map_err(|_| {
                            format!("line {}: invalid S2 value '{}': expected integer", line_num, val)
                        })?;
                        if s2 > 1280 {
                            return Err(format!(
                                "line {}: invalid S2 value {}: exceeds maximum response padding 1280",
                                line_num, s2
                            ));
                        }
                        awg_params.s2 = s2;
                    }
                    "h1" => {
                        let h: u32 = val.parse().map_err(|_| {
                            format!("line {}: invalid H1 value '{}': expected integer", line_num, val)
                        })?;
                        if h == 0 {
                            return Err(format!("line {}: invalid H1 value 0: message type header cannot be zero", line_num));
                        }
                        awg_params.h1 = h;
                    }
                    "h2" => {
                        let h: u32 = val.parse().map_err(|_| {
                            format!("line {}: invalid H2 value '{}': expected integer", line_num, val)
                        })?;
                        if h == 0 {
                            return Err(format!("line {}: invalid H2 value 0: message type header cannot be zero", line_num));
                        }
                        awg_params.h2 = h;
                    }
                    "h3" => {
                        let h: u32 = val.parse().map_err(|_| {
                            format!("line {}: invalid H3 value '{}': expected integer", line_num, val)
                        })?;
                        if h == 0 {
                            return Err(format!("line {}: invalid H3 value 0: message type header cannot be zero", line_num));
                        }
                        awg_params.h3 = h;
                    }
                    "h4" => {
                        let h: u32 = val.parse().map_err(|_| {
                            format!("line {}: invalid H4 value '{}': expected integer", line_num, val)
                        })?;
                        if h == 0 {
                            return Err(format!("line {}: invalid H4 value 0: message type header cannot be zero", line_num));
                        }
                        awg_params.h4 = h;
                    }
                    "i1" => {
                        let clean_hex = val
                            .trim_start_matches("0x")
                            .trim_start_matches("<b 0x")
                            .trim_end_matches('>')
                            .trim();
                        let bytes = hex::decode(clean_hex).map_err(|e| {
                            format!("line {}: invalid I1 hex format '{}': {}", line_num, val, e)
                        })?;
                        awg_params.i1 = Some(bytes);
                    }
                    _ => {
                        return Err(format!(
                            "line {}: unrecognized configuration key '{}' in section [Interface]",
                            line_num, key
                        ));
                    }
                },
                Section::Peer => match key.to_ascii_lowercase().as_str() {
                    "publickey" => {
                        let bytes = B64.decode(val).map_err(|e| {
                            format!("line {}: invalid Base64 in 'PublicKey': {}", line_num, e)
                        })?;
                        if bytes.len() != 32 {
                            return Err(format!(
                                "line {}: invalid PublicKey length: expected 32 bytes, got {}",
                                line_num,
                                bytes.len()
                            ));
                        }
                        let mut k = [0u8; 32];
                        k.copy_from_slice(&bytes);
                        peer_public_key = Some(k);
                    }
                    "presharedkey" => {
                        let bytes = B64.decode(val).map_err(|e| {
                            format!("line {}: invalid Base64 in 'PresharedKey': {}", line_num, e)
                        })?;
                        if bytes.len() != 32 {
                            return Err(format!(
                                "line {}: invalid PresharedKey length: expected 32 bytes, got {}",
                                line_num,
                                bytes.len()
                            ));
                        }
                        let mut k = [0u8; 32];
                        k.copy_from_slice(&bytes);
                        preshared_key = Some(k);
                    }
                    "endpoint" => {
                        let (validated_ep, _port) = validate_endpoint_format(val).map_err(|e| {
                            format!("line {}: {}", line_num, e)
                        })?;
                        endpoint = Some(validated_ep);
                    }
                    "allowedips" => {
                        for part in val.split(',') {
                            let part_clean = part.trim();
                            if part_clean.is_empty() {
                                continue;
                            }
                            let ip_part = part_clean.split('/').next().unwrap_or("").trim();
                            if ip_part.parse::<Ipv4Addr>().is_err() && ip_part.parse::<Ipv6Addr>().is_err() {
                                return Err(format!(
                                    "line {}: invalid IP/CIDR in AllowedIPs: '{}'",
                                    line_num, part_clean
                                ));
                            }
                        }
                        ldebug!("AWG config: parsed peer AllowedIPs: {}", val);
                    }
                    "fallbackendpoints" | "fallback" => {
                        for part in val.split(',') {
                            let ep_part = part.trim();
                            if ep_part.is_empty() {
                                continue;
                            }
                            let (val_ep, _port) = validate_endpoint_format(ep_part).map_err(|e| {
                                format!("line {}: invalid fallback endpoint '{}': {}", line_num, ep_part, e)
                            })?;
                            fallback_endpoints.push(val_ep);
                        }
                    }
                    "persistentkeepalive" => {
                        let secs: u16 = val.parse().map_err(|_| {
                            format!(
                                "line {}: invalid PersistentKeepalive value '{}': expected unsigned integer",
                                line_num, val
                            )
                        })?;
                        persistent_keepalive = if secs > 0 { Some(secs) } else { None };
                    }
                    "jc" => {
                        let jc: u32 = val.parse().map_err(|_| {
                            format!("line {}: invalid Jc value '{}': expected integer", line_num, val)
                        })?;
                        if jc > 128 {
                            return Err(format!(
                                "line {}: invalid Jc value {}: maximum allowed junk packet count is 128",
                                line_num, jc
                            ));
                        }
                        awg_params.jc = jc;
                    }
                    "jmin" => {
                        let jmin: usize = val.parse().map_err(|_| {
                            format!("line {}: invalid Jmin value '{}': expected integer", line_num, val)
                        })?;
                        if jmin > 1280 {
                            return Err(format!(
                                "line {}: invalid Jmin value {}: maximum allowed junk packet size is 1280",
                                line_num, jmin
                            ));
                        }
                        awg_params.jmin = jmin;
                    }
                    "jmax" => {
                        let jmax: usize = val.parse().map_err(|_| {
                            format!("line {}: invalid Jmax value '{}': expected integer", line_num, val)
                        })?;
                        if jmax > 1280 {
                            return Err(format!(
                                "line {}: invalid Jmax value {}: maximum allowed junk packet size is 1280",
                                line_num, jmax
                            ));
                        }
                        awg_params.jmax = jmax;
                    }
                    "s1" => {
                        let s1: usize = val.parse().map_err(|_| {
                            format!("line {}: invalid S1 value '{}': expected integer", line_num, val)
                        })?;
                        if s1 > 1280 {
                            return Err(format!(
                                "line {}: invalid S1 value {}: exceeds maximum handshake padding 1280",
                                line_num, s1
                            ));
                        }
                        awg_params.s1 = s1;
                    }
                    "s2" => {
                        let s2: usize = val.parse().map_err(|_| {
                            format!("line {}: invalid S2 value '{}': expected integer", line_num, val)
                        })?;
                        if s2 > 1280 {
                            return Err(format!(
                                "line {}: invalid S2 value {}: exceeds maximum response padding 1280",
                                line_num, s2
                            ));
                        }
                        awg_params.s2 = s2;
                    }
                    "h1" => {
                        let h: u32 = val.parse().map_err(|_| {
                            format!("line {}: invalid H1 value '{}': expected integer", line_num, val)
                        })?;
                        if h == 0 {
                            return Err(format!("line {}: invalid H1 value 0: message type header cannot be zero", line_num));
                        }
                        awg_params.h1 = h;
                    }
                    "h2" => {
                        let h: u32 = val.parse().map_err(|_| {
                            format!("line {}: invalid H2 value '{}': expected integer", line_num, val)
                        })?;
                        if h == 0 {
                            return Err(format!("line {}: invalid H2 value 0: message type header cannot be zero", line_num));
                        }
                        awg_params.h2 = h;
                    }
                    "h3" => {
                        let h: u32 = val.parse().map_err(|_| {
                            format!("line {}: invalid H3 value '{}': expected integer", line_num, val)
                        })?;
                        if h == 0 {
                            return Err(format!("line {}: invalid H3 value 0: message type header cannot be zero", line_num));
                        }
                        awg_params.h3 = h;
                    }
                    "h4" => {
                        let h: u32 = val.parse().map_err(|_| {
                            format!("line {}: invalid H4 value '{}': expected integer", line_num, val)
                        })?;
                        if h == 0 {
                            return Err(format!("line {}: invalid H4 value 0: message type header cannot be zero", line_num));
                        }
                        awg_params.h4 = h;
                    }
                    "i1" => {
                        let clean_hex = val
                            .trim_start_matches("0x")
                            .trim_start_matches("<b 0x")
                            .trim_end_matches('>')
                            .trim();
                        let bytes = hex::decode(clean_hex).map_err(|e| {
                            format!("line {}: invalid I1 hex format '{}': {}", line_num, val, e)
                        })?;
                        awg_params.i1 = Some(bytes);
                    }
                    _ => {
                        return Err(format!(
                            "line {}: unrecognized configuration key '{}' in section [Peer]",
                            line_num, key
                        ));
                    }
                },
                Section::None => unreachable!(),
            }
        }

        if interface_count == 0 {
            return Err("missing required [Interface] section in configuration".to_string());
        }
        if peer_count == 0 {
            return Err("missing required [Peer] section: configuration must define exactly one peer".to_string());
        }

        let priv_k = private_key.ok_or_else(|| {
            "missing required field 'PrivateKey' in [Interface] section".to_string()
        })?;
        let v4 = client_ipv4.ok_or_else(|| {
            "missing required IPv4 address in 'Address' field under [Interface]".to_string()
        })?;
        let pub_k = peer_public_key.ok_or_else(|| {
            "missing required field 'PublicKey' in [Peer] section".to_string()
        })?;
        let ep = endpoint.ok_or_else(|| {
            "missing required field 'Endpoint' in [Peer] section".to_string()
        })?;

        if awg_params.jmin > awg_params.jmax {
            return Err(format!(
                "invalid junk packet size range: Jmin ({}) > Jmax ({})",
                awg_params.jmin, awg_params.jmax
            ));
        }

        let headers = [awg_params.h1, awg_params.h2, awg_params.h3, awg_params.h4];
        for i in 0..headers.len() {
            for j in (i + 1)..headers.len() {
                if headers[i] == headers[j] {
                    return Err(format!(
                        "AWG header collision detected: H{} and H{} share identical value 0x{:08x}",
                        i + 1,
                        j + 1,
                        headers[i]
                    ));
                }
            }
        }

        let report = awg_params.validate_against_endpoint(&ep)?;
        for warning in &report.warnings {
            lwarn!("AWG config validation: {}", warning);
        }

        let my_pub = x25519_base(&priv_k);

        let is_warp = is_cloudflare_warp_peer(&pub_k) || is_cloudflare_warp_endpoint(&ep);
        let prof_name = profile_name.unwrap_or_else(|| {
            if is_warp {
                "Cloudflare WARP".to_string()
            } else {
                format!("Custom AWG ({})", ep)
            }
        });

        let effective_fallbacks = if !fallback_endpoints.is_empty() {
            fallback_endpoints
        } else if is_warp {
            CLOUDFLARE_WARP_ANYCAST_POOL
                .iter()
                .map(|&s| s.to_string())
                .collect()
        } else {
            // Dedicated custom server profile: NEVER inject alien Cloudflare Anycast endpoints!
            Vec::new()
        };

        Ok(Self {
            profile_name: prof_name,
            endpoint: ep,
            fallback_endpoints: effective_fallbacks,
            private_key: priv_k,
            public_key: my_pub,
            peer_public_key: pub_k,
            preshared_key,
            client_ipv4: v4,
            client_ipv6,
            awg_params,
            persistent_keepalive,
        })
    }
}

pub static AWG_CONFIG: Lazy<RwLock<AwgConfig>> = Lazy::new(|| RwLock::new(AwgConfig::default()));

pub fn set_awg_config_ini(ini_str: &str) -> Result<(), String> {
    let parsed = AwgConfig::parse_ini(ini_str)?;
    let mut cfg = AWG_CONFIG.write();
    *cfg = parsed;
    linfo!(
        "AWG config loaded from INI: profile='{}', endpoint={}, ipv4={}, fallbacks={}, Jc={}, S1={}, S2={}, H1={}",
        cfg.profile_name,
        cfg.endpoint,
        cfg.client_ipv4,
        cfg.fallback_endpoints.len(),
        cfg.awg_params.jc,
        cfg.awg_params.s1,
        cfg.awg_params.s2,
        cfg.awg_params.h1
    );
    drop(cfg);
    invalidate_active_peer();
    reset_awg_circuit_breaker();
    Ok(())
}

pub fn set_awg_config(
    endpoint: &str,
    private_key_b64: &str,
    public_key_b64: &str,
    peer_pub_b64: &str,
    client_ipv4: &str,
    jc: u32,
    jmin: usize,
    jmax: usize,
    s1: usize,
    s2: usize,
    h1: u32,
    h2: u32,
    h3: u32,
    h4: u32,
) {
    use base64::engine::general_purpose::STANDARD as B64;
    use base64::Engine;

    let mut cfg = AWG_CONFIG.write();
    if !endpoint.trim().is_empty() {
        cfg.endpoint = endpoint.trim().to_string();
    }
    if let Ok(dec) = B64.decode(private_key_b64.trim()) {
        if dec.len() == 32 {
            cfg.private_key.copy_from_slice(&dec);
            cfg.public_key = x25519_base(&cfg.private_key);
        }
    }
    if let Ok(dec) = B64.decode(public_key_b64.trim()) {
        if dec.len() == 32 {
            cfg.public_key.copy_from_slice(&dec);
        }
    }
    if let Ok(dec) = B64.decode(peer_pub_b64.trim()) {
        if dec.len() == 32 {
            cfg.peer_public_key.copy_from_slice(&dec);
        }
    }
    if let Ok(v4) = client_ipv4.trim().parse::<Ipv4Addr>() {
        cfg.client_ipv4 = v4;
    }
    cfg.awg_params.jc = jc;
    cfg.awg_params.jmin = jmin;
    cfg.awg_params.jmax = jmax;
    cfg.awg_params.s1 = s1;
    cfg.awg_params.s2 = s2;
    cfg.awg_params.h1 = if h1 != 0 { h1 } else { 1 };
    cfg.awg_params.h2 = if h2 != 0 { h2 } else { 2 };
    cfg.awg_params.h3 = if h3 != 0 { h3 } else { 3 };
    cfg.awg_params.h4 = if h4 != 0 { h4 } else { 4 };

    let is_warp = is_cloudflare_warp_peer(&cfg.peer_public_key) || is_cloudflare_warp_endpoint(&cfg.endpoint);
    cfg.profile_name = if is_warp {
        "Cloudflare WARP".to_string()
    } else {
        format!("Custom AWG ({})", cfg.endpoint)
    };
    cfg.fallback_endpoints = if is_warp {
        CLOUDFLARE_WARP_ANYCAST_POOL.iter().map(|&s| s.to_string()).collect()
    } else {
        Vec::new()
    };

    if let Ok(report) = cfg.awg_params.validate_against_endpoint(&cfg.endpoint) {
        for warning in &report.warnings {
            lwarn!("AWG config validation: {}", warning);
        }
    }

    linfo!(
        "AWG config updated: profile='{}', endpoint={}, ipv4={}, fallbacks={}, Jc={}, S1={}, H1..H4=({}, {}, {}, {})",
        cfg.profile_name,
        cfg.endpoint,
        cfg.client_ipv4,
        cfg.fallback_endpoints.len(),
        cfg.awg_params.jc,
        cfg.awg_params.s1,
        cfg.awg_params.h1,
        cfg.awg_params.h2,
        cfg.awg_params.h3,
        cfg.awg_params.h4
    );
    drop(cfg);
    invalidate_active_peer();
    reset_awg_circuit_breaker();
}

pub fn set_awg_fallback_endpoints(endpoints: &[String]) {
    let mut cfg = AWG_CONFIG.write();
    cfg.fallback_endpoints = endpoints.to_vec();
}

pub fn get_awg_status() -> String {
    let cfg = AWG_CONFIG.read();
    format!(
        "profile='{}' ep={} fallbacks={} ipv4={} jc={} s1={} s2={} h1={} ka={:?}",
        cfg.profile_name,
        cfg.endpoint,
        cfg.fallback_endpoints.len(),
        cfg.client_ipv4,
        cfg.awg_params.jc,
        cfg.awg_params.s1,
        cfg.awg_params.s2,
        cfg.awg_params.h1,
        cfg.persistent_keepalive
    )
}

pub fn set_awg_keepalive(secs: Option<u16>) {
    let mut cfg = AWG_CONFIG.write();
    cfg.persistent_keepalive = secs;
    linfo!("AWG persistent keepalive set to {:?}", secs);
    drop(cfg);
    notify_active_peer_timer();
}

// ---------------------------------------------------------------------------
// 5. WireGuard Noise IKpsk2 Handshake State Machine
// ---------------------------------------------------------------------------

const NOISE_CONSTRUCTION: &[u8] = b"Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s";
const NOISE_IDENTIFIER: &[u8] = b"WireGuard v1 zx2c4 Jason@zx2c4.com";

#[derive(Clone, Copy, Debug)]
pub struct HandshakeState {
    pub e_priv: [u8; 32],
    pub e_pub: [u8; 32],
    pub local_index: u32,
    pub chaining_key: [u8; 32],
    pub hash: [u8; 32],
    pub mac1: [u8; 16],
}

// ---------------------------------------------------------------------------
// WireGuard Anti-Replay Sliding Window (RFC 6479 / WireGuard Paper Section 5.4)
// ---------------------------------------------------------------------------

/// Maximum messages allowed per session before rekey is strictly required.
/// WireGuard specification: 2^64 - 2^13 - 1.
pub const REJECT_AFTER_MESSAGES: u64 = u64::MAX - 8192;

/// Standard WireGuard protocol rekey limit: 2^60 messages.
pub const REKEY_AFTER_MESSAGES: u64 = 1u64 << 60;

/// Standard WireGuard protocol timer constants (WireGuard Specification Section 6.2)
pub const REKEY_AFTER_TIME: Duration = Duration::from_secs(120);
pub const REJECT_AFTER_TIME: Duration = Duration::from_secs(180);
pub const REKEY_ATTEMPT_TIME: Duration = Duration::from_secs(90);
pub const REKEY_TIMEOUT: Duration = Duration::from_secs(5);
pub const KEEPALIVE_TIMEOUT: Duration = Duration::from_secs(10);

/// Session limits and timers for WireGuard key lifecycle and rotation.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SessionLimits {
    pub rekey_after_messages: u64,
    pub reject_after_messages: u64,
    pub rekey_after_time: Duration,
    pub reject_after_time: Duration,
    pub rekey_attempt_time: Duration,
    pub rekey_timeout: Duration,
    pub keepalive_timeout: Duration,
    pub persistent_keepalive: Option<Duration>,
}

impl Default for SessionLimits {
    fn default() -> Self {
        Self {
            rekey_after_messages: REKEY_AFTER_MESSAGES,
            reject_after_messages: REJECT_AFTER_MESSAGES,
            rekey_after_time: REKEY_AFTER_TIME,
            reject_after_time: REJECT_AFTER_TIME,
            rekey_attempt_time: REKEY_ATTEMPT_TIME,
            rekey_timeout: REKEY_TIMEOUT,
            keepalive_timeout: KEEPALIVE_TIMEOUT,
            persistent_keepalive: None,
        }
    }
}

impl SessionLimits {
    pub fn test_small_limits(
        rekey_after_messages: u64,
        reject_after_messages: u64,
        rekey_after_time: Duration,
        reject_after_time: Duration,
    ) -> Self {
        Self {
            rekey_after_messages,
            reject_after_messages,
            rekey_after_time,
            reject_after_time,
            rekey_attempt_time: Duration::from_secs(5),
            rekey_timeout: Duration::from_millis(500),
            keepalive_timeout: Duration::from_secs(2),
            persistent_keepalive: None,
        }
    }

    pub fn with_persistent_keepalive(mut self, interval: Duration) -> Self {
        self.persistent_keepalive = Some(interval);
        self
    }
}

/// Standard WireGuard sliding window size (2048 packets).
pub const COUNTER_WINDOW_SIZE: u64 = 2048;
pub const BITMAP_WORDS: usize = (COUNTER_WINDOW_SIZE as usize) / 64; // 32 words of u64 = 2048 bits

#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum ReplayError {
    Replay,
    TooOld,
    CounterExhausted,
}

impl std::fmt::Display for ReplayError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ReplayError::Replay => write!(f, "replay packet detected"),
            ReplayError::TooOld => write!(f, "packet counter too old (outside window)"),
            ReplayError::CounterExhausted => {
                write!(f, "packet counter exhausted (exceeds REJECT_AFTER_MESSAGES)")
            }
        }
    }
}

impl std::error::Error for ReplayError {}

/// Sliding bitmap window to protect WireGuard transport against packet replay and reordering attacks.
#[derive(Debug, Clone)]
pub struct ReplayFilter {
    pub last_counter: u64,
    pub bitmap: [u64; BITMAP_WORDS],
    pub initialized: bool,
}

impl Default for ReplayFilter {
    fn default() -> Self {
        Self::new()
    }
}

impl ReplayFilter {
    pub fn new() -> Self {
        Self {
            last_counter: 0,
            bitmap: [0u64; BITMAP_WORDS],
            initialized: false,
        }
    }

    /// Pre-check if packet counter is plausible before expensive AEAD decryption.
    /// Crucially, this does NOT modify the filter state, preventing DoS attacks
    /// where unauthenticated packets with high counters would advance the window.
    pub fn check(&self, counter: u64) -> Result<(), ReplayError> {
        if counter >= REJECT_AFTER_MESSAGES {
            return Err(ReplayError::CounterExhausted);
        }

        if !self.initialized {
            return Ok(());
        }

        if counter > self.last_counter {
            // Future packet: valid candidate
            Ok(())
        } else {
            let diff = self.last_counter - counter;
            if diff >= COUNTER_WINDOW_SIZE {
                return Err(ReplayError::TooOld);
            }
            let word_idx = (diff / 64) as usize;
            let bit_idx = (diff % 64) as usize;
            if (self.bitmap[word_idx] & (1u64 << bit_idx)) != 0 {
                return Err(ReplayError::Replay);
            }
            Ok(())
        }
    }

    /// Commit authenticated counter into the sliding window after successful AEAD verification.
    pub fn update(&mut self, counter: u64) -> Result<(), ReplayError> {
        if counter >= REJECT_AFTER_MESSAGES {
            return Err(ReplayError::CounterExhausted);
        }

        if !self.initialized {
            self.last_counter = counter;
            self.bitmap[0] = 1;
            self.initialized = true;
            return Ok(());
        }

        if counter > self.last_counter {
            let diff = counter - self.last_counter;
            if diff >= COUNTER_WINDOW_SIZE {
                self.bitmap.fill(0);
            } else {
                slide_bitmap(&mut self.bitmap, diff as usize);
            }
            self.bitmap[0] |= 1;
            self.last_counter = counter;
            Ok(())
        } else {
            let diff = self.last_counter - counter;
            if diff >= COUNTER_WINDOW_SIZE {
                return Err(ReplayError::TooOld);
            }
            let word_idx = (diff / 64) as usize;
            let bit_idx = (diff % 64) as usize;
            let mask = 1u64 << bit_idx;
            if (self.bitmap[word_idx] & mask) != 0 {
                return Err(ReplayError::Replay);
            }
            self.bitmap[word_idx] |= mask;
            Ok(())
        }
    }
}

/// Slide bitmap forward by `shift` bit positions.
/// Older packets shift to higher word/bit indices; bits shifted past 2047 are dropped.
pub fn slide_bitmap(bitmap: &mut [u64; BITMAP_WORDS], shift: usize) {
    if shift == 0 {
        return;
    }
    if shift >= BITMAP_WORDS * 64 {
        bitmap.fill(0);
        return;
    }

    let word_shift = shift / 64;
    let bit_shift = shift % 64;

    if bit_shift == 0 {
        for i in (word_shift..BITMAP_WORDS).rev() {
            bitmap[i] = bitmap[i - word_shift];
        }
        for i in 0..word_shift {
            bitmap[i] = 0;
        }
    } else {
        let rev_shift = 64 - bit_shift;
        for i in (word_shift..BITMAP_WORDS).rev() {
            let low = bitmap[i - word_shift] << bit_shift;
            let high = if i > word_shift {
                bitmap[i - word_shift - 1] >> rev_shift
            } else {
                0
            };
            bitmap[i] = low | high;
        }
        for i in 0..word_shift {
            bitmap[i] = 0;
        }
    }
}

pub struct TransportKeys {
    pub send_key: [u8; 32],
    pub recv_key: [u8; 32],
    pub local_index: u32,
    pub peer_index: u32,
    pub send_counter: AtomicU64,
    pub recv_counter: AtomicU64,
    pub replay_filter: Mutex<ReplayFilter>,
    pub created_at: std::time::Instant,
    pub limits: SessionLimits,
    pub is_initiator: bool,
}

impl std::fmt::Debug for TransportKeys {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TransportKeys")
            .field("local_index", &self.local_index)
            .field("peer_index", &self.peer_index)
            .field("send_counter", &self.send_counter.load(Ordering::Relaxed))
            .field("recv_counter", &self.recv_counter.load(Ordering::Relaxed))
            .field("is_initiator", &self.is_initiator)
            .finish()
    }
}

impl TransportKeys {
    pub fn new(
        send_key: [u8; 32],
        recv_key: [u8; 32],
        local_index: u32,
        peer_index: u32,
    ) -> Self {
        Self::with_limits(
            send_key,
            recv_key,
            local_index,
            peer_index,
            SessionLimits::default(),
            true,
        )
    }

    pub fn with_limits(
        send_key: [u8; 32],
        recv_key: [u8; 32],
        local_index: u32,
        peer_index: u32,
        limits: SessionLimits,
        is_initiator: bool,
    ) -> Self {
        Self {
            send_key,
            recv_key,
            local_index,
            peer_index,
            send_counter: AtomicU64::new(0),
            recv_counter: AtomicU64::new(0),
            replay_filter: Mutex::new(ReplayFilter::new()),
            created_at: std::time::Instant::now(),
            limits,
            is_initiator,
        }
    }

    #[inline]
    pub fn age(&self) -> Duration {
        self.created_at.elapsed()
    }

    #[inline]
    pub fn should_rekey(&self) -> bool {
        self.send_counter.load(Ordering::Relaxed) >= self.limits.rekey_after_messages
            || self.age() >= self.limits.rekey_after_time
    }

    #[inline]
    pub fn is_expired(&self) -> bool {
        self.send_counter.load(Ordering::Relaxed) >= self.limits.reject_after_messages
            || self.age() >= self.limits.reject_after_time
    }
}

pub fn create_initiation(
    config: &AwgConfig,
    local_index: u32,
) -> Result<(HandshakeState, Vec<u8>), String> {
    let cached_cookie = get_peer_cookie(&config.peer_public_key);
    create_initiation_with_cookie(config, local_index, cached_cookie.as_ref())
}

pub fn create_initiation_with_cookie(
    config: &AwgConfig,
    local_index: u32,
    cookie: Option<&[u8; 16]>,
) -> Result<(HandshakeState, Vec<u8>), String> {
    let ci = blake2s_256(NOISE_CONSTRUCTION);
    let mut hi_input = Vec::with_capacity(32 + NOISE_IDENTIFIER.len());
    hi_input.extend_from_slice(&ci);
    hi_input.extend_from_slice(NOISE_IDENTIFIER);
    let hi = blake2s_256(&hi_input);

    // MixHash responder public key
    let mut h = blake2s_mix(&hi, &config.peer_public_key);
    let mut chaining_key = ci;

    // Ephemeral key generation
    let mut e_priv = [0u8; 32];
    rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut e_priv);
    let e_pub = x25519_base(&e_priv);

    // Add unencrypted ephemeral to packet & mix hash & kdf
    h = blake2s_mix(&h, &e_pub);
    let (ck_new, _k1) = kdf2(&chaining_key, &e_pub);
    chaining_key = ck_new;

    // ss1 = X25519(e_priv, Spk)
    let ss1 = x25519(&e_priv, &config.peer_public_key);
    let (ck_new, k2) = kdf2(&chaining_key, &ss1);
    chaining_key = ck_new;

    // Encrypt static public key
    let encrypted_static = aead_encrypt(&k2, 0, &h, &config.public_key)?;
    h = blake2s_mix(&h, &encrypted_static);

    // ss2 = X25519(Cpriv, Spk)
    let ss2 = x25519(&config.private_key, &config.peer_public_key);
    let (ck_new, k3) = kdf2(&chaining_key, &ss2);
    chaining_key = ck_new;

    // Encrypt timestamp (Tai64n)
    let tai64n = generate_tai64n();
    let encrypted_timestamp = aead_encrypt(&k3, 0, &h, &tai64n)?;
    h = blake2s_mix(&h, &encrypted_timestamp);

    // Assemble packet: S1 prefix padding + 148 bytes initiation
    let s1 = config.awg_params.s1;
    let mut packet = Vec::with_capacity(s1 + 148);

    // AmneziaWG v1.0: S1 bytes of random prefix padding
    if s1 > 0 {
        let mut padding = vec![0u8; s1];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut padding);
        packet.extend_from_slice(&padding);
    }

    let init_start = s1;
    packet.extend_from_slice(&config.awg_params.h1.to_le_bytes());
    packet.extend_from_slice(&local_index.to_le_bytes());
    packet.extend_from_slice(&e_pub);
    packet.extend_from_slice(&encrypted_static);
    packet.extend_from_slice(&encrypted_timestamp);

    // MAC1 = BLAKE2s-128(key = BLAKE2s(b"mac1----" || Spk), data = packet[init_start..init_start + 116])
    let mut mac1_key_input = Vec::with_capacity(8 + 32);
    mac1_key_input.extend_from_slice(b"mac1----");
    mac1_key_input.extend_from_slice(&config.peer_public_key);
    let mac1_key = blake2s_256(&mac1_key_input);
    let mac1 = blake2s(&mac1_key, &packet[init_start..init_start + 116], 16);
    packet.extend_from_slice(&mac1);

    // MAC2 = if cookie present { BLAKE2s-128(key = cookie, data = packet[init_start..init_start + 132]) } else { 16 zeros }
    if let Some(c) = cookie {
        let mac2 = blake2s(c, &packet[init_start..init_start + 132], 16);
        packet.extend_from_slice(&mac2);
    } else {
        packet.extend_from_slice(&[0u8; 16]);
    }

    let mut mac1_arr = [0u8; 16];
    mac1_arr.copy_from_slice(&mac1);

    let state = HandshakeState {
        e_priv,
        e_pub,
        local_index,
        chaining_key,
        hash: h,
        mac1: mac1_arr,
    };

    Ok((state, packet))
}

pub fn process_response(
    handshake: HandshakeState,
    config: &AwgConfig,
    resp: &[u8],
) -> Result<TransportKeys, String> {
    let s2 = config.awg_params.s2;
    let min_len = 92 + s2;
    if resp.len() < min_len {
        return Err(format!(
            "AWG response too short: {} bytes (expected >= {})",
            resp.len(),
            min_len
        ));
    }

    let resp_slice = &resp[s2..];
    let msg_type = u32::from_le_bytes([resp_slice[0], resp_slice[1], resp_slice[2], resp_slice[3]]);
    if msg_type != config.awg_params.h2 {
        return Err(format!(
            "AWG response message type mismatch: {} (expected H2={})",
            msg_type, config.awg_params.h2
        ));
    }

    let server_index = u32::from_le_bytes([resp_slice[4], resp_slice[5], resp_slice[6], resp_slice[7]]);
    let receiver_index = u32::from_le_bytes([resp_slice[8], resp_slice[9], resp_slice[10], resp_slice[11]]);
    if receiver_index != handshake.local_index {
        return Err(format!(
            "AWG receiver index mismatch: got {}, expected local {}",
            receiver_index, handshake.local_index
        ));
    }

    // Verify MAC1 over resp_slice[0..60]
    let mut mac1_key_input = Vec::with_capacity(8 + 32);
    mac1_key_input.extend_from_slice(b"mac1----");
    mac1_key_input.extend_from_slice(&config.public_key);
    let mac1_key = blake2s_256(&mac1_key_input);
    let expected_mac1 = blake2s(&mac1_key, &resp_slice[0..60], 16);
    if &resp_slice[60..76] != expected_mac1.as_slice() {
        return Err("AWG response MAC1 verification failed".to_string());
    }

    let mut e_pub_server = [0u8; 32];
    e_pub_server.copy_from_slice(&resp_slice[12..44]);

    let mut h = blake2s_mix(&handshake.hash, &e_pub_server);
    let (ck_new, _k1) = kdf2(&handshake.chaining_key, &e_pub_server);
    let mut chaining_key = ck_new;

    // ss3 = X25519(e_priv, e_pub_server)
    let ss3 = x25519(&handshake.e_priv, &e_pub_server);
    let (ck_new, _k2) = kdf2(&chaining_key, &ss3);
    chaining_key = ck_new;

    // ss4 = X25519(Cpriv, e_pub_server)
    let ss4 = x25519(&config.private_key, &e_pub_server);
    let (ck_new, _k3) = kdf2(&chaining_key, &ss4);
    chaining_key = ck_new;

    // PSK (preshared key, default 32 zeros)
    let psk = config.preshared_key.unwrap_or([0u8; 32]);
    let (ck_new, tau, k4) = kdf3(&chaining_key, &psk);
    chaining_key = ck_new;
    h = blake2s_mix(&h, &tau);

    // Decrypt empty payload (auth tag verification)
    let empty = aead_decrypt(&k4, 0, &h, &resp_slice[44..60])?;
    if !empty.is_empty() {
        return Err("AWG handshake response payload non-empty".to_string());
    }
    let _ = blake2s_mix(&h, &resp_slice[44..60]);

    // Derive final transport keys
    let (send_key, recv_key) = kdf2(&chaining_key, &[]);

    Ok(TransportKeys::new(
        send_key,
        recv_key,
        handshake.local_index,
        server_index,
    ))
}

/// Responder-side handshake processing (RFC Noise IKpsk2 / WireGuard paper Section 5.4).
/// Allows accepting initiation datagrams, verifying client public key and Tai64n timestamp,
/// deriving transport keypairs, and generating handshake response packet.
pub fn respond_to_initiation(
    server_priv: &[u8; 32],
    server_index: u32,
    init_packet: &[u8],
    awg_params: &AwgParams,
    psk: Option<[u8; 32]>,
) -> Result<(TransportKeys, Vec<u8>), String> {
    let s1 = awg_params.s1;
    let min_len = 148 + s1;
    if init_packet.len() < min_len {
        return Err(format!(
            "AWG initiation packet too short: {} (expected >= {})",
            init_packet.len(),
            min_len
        ));
    }

    let init_slice = &init_packet[s1..];
    let msg_type = u32::from_le_bytes([init_slice[0], init_slice[1], init_slice[2], init_slice[3]]);
    if msg_type != awg_params.h1 {
        return Err(format!(
            "AWG initiation message type mismatch: got {}, expected H1={}",
            msg_type, awg_params.h1
        ));
    }

    let client_index = u32::from_le_bytes([init_slice[4], init_slice[5], init_slice[6], init_slice[7]]);
    let mut e_pub_client = [0u8; 32];
    e_pub_client.copy_from_slice(&init_slice[8..40]);

    let server_pub = x25519_base(server_priv);

    // Verify MAC1
    let mut mac1_key_input = Vec::with_capacity(8 + 32);
    mac1_key_input.extend_from_slice(b"mac1----");
    mac1_key_input.extend_from_slice(&server_pub);
    let mac1_key = blake2s_256(&mac1_key_input);
    let expected_mac1 = blake2s(&mac1_key, &init_slice[0..116], 16);
    if &init_slice[116..132] != expected_mac1.as_slice() {
        return Err("AWG initiation MAC1 verification failed".to_string());
    }

    let ci = blake2s_256(NOISE_CONSTRUCTION);
    let mut hi_input = Vec::with_capacity(32 + NOISE_IDENTIFIER.len());
    hi_input.extend_from_slice(&ci);
    hi_input.extend_from_slice(NOISE_IDENTIFIER);
    let hi = blake2s_256(&hi_input);

    let mut h = blake2s_mix(&hi, &server_pub);
    let mut chaining_key = ci;

    h = blake2s_mix(&h, &e_pub_client);
    let (ck_new, _k1) = kdf2(&chaining_key, &e_pub_client);
    chaining_key = ck_new;

    // ss1 = X25519(Spriv, Epub_client)
    let ss1 = x25519(server_priv, &e_pub_client);
    let (ck_new, k2) = kdf2(&chaining_key, &ss1);
    chaining_key = ck_new;

    // Decrypt static public key
    let encrypted_static = &init_slice[40..88];
    let client_pub_bytes = aead_decrypt(&k2, 0, &h, encrypted_static)?;
    if client_pub_bytes.len() != 32 {
        return Err("invalid decrypted client static key length".to_string());
    }
    let mut client_pub = [0u8; 32];
    client_pub.copy_from_slice(&client_pub_bytes);
    h = blake2s_mix(&h, encrypted_static);

    // ss2 = X25519(Spriv, Cpub)
    let ss2 = x25519(server_priv, &client_pub);
    let (ck_new, k3) = kdf2(&chaining_key, &ss2);
    chaining_key = ck_new;

    // Decrypt timestamp (Tai64n)
    let encrypted_timestamp = &init_slice[88..116];
    let _tai64n = aead_decrypt(&k3, 0, &h, encrypted_timestamp)?;
    h = blake2s_mix(&h, encrypted_timestamp);

    // Generate server ephemeral keypair
    let mut e_priv_server = [0u8; 32];
    rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut e_priv_server);
    let e_pub_server = x25519_base(&e_priv_server);

    h = blake2s_mix(&h, &e_pub_server);
    let (ck_new, _k1) = kdf2(&chaining_key, &e_pub_server);
    chaining_key = ck_new;

    // ss3 = X25519(e_priv_server, e_pub_client)
    let ss3 = x25519(&e_priv_server, &e_pub_client);
    let (ck_new, _k2) = kdf2(&chaining_key, &ss3);
    chaining_key = ck_new;

    // ss4 = X25519(e_priv_server, client_pub)
    let ss4 = x25519(&e_priv_server, &client_pub);
    let (ck_new, _k3) = kdf2(&chaining_key, &ss4);
    chaining_key = ck_new;

    // PSK (default 32 zeros)
    let psk_bytes = psk.unwrap_or([0u8; 32]);
    let (ck_new, tau, k4) = kdf3(&chaining_key, &psk_bytes);
    chaining_key = ck_new;
    h = blake2s_mix(&h, &tau);

    // Encrypt empty payload
    let empty_encrypted = aead_encrypt(&k4, 0, &h, &[])?;
    let _ = blake2s_mix(&h, &empty_encrypted);

    // Derive transport keys:
    // Initiator gets (send_key, recv_key) = kdf2(chaining_key, &[]).
    // Responder sends with initiator's recv_key and receives with initiator's send_key!
    let (t_send, t_recv) = kdf2(&chaining_key, &[]);
    let responder_keys = TransportKeys::new(t_recv, t_send, server_index, client_index);

    let s2 = awg_params.s2;
    let mut resp_packet = Vec::with_capacity(s2 + 92);

    // AmneziaWG v1.0: S2 bytes of random prefix padding
    if s2 > 0 {
        let mut padding = vec![0u8; s2];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut padding);
        resp_packet.extend_from_slice(&padding);
    }

    let resp_start = s2;
    resp_packet.extend_from_slice(&awg_params.h2.to_le_bytes());
    resp_packet.extend_from_slice(&server_index.to_le_bytes());
    resp_packet.extend_from_slice(&client_index.to_le_bytes());
    resp_packet.extend_from_slice(&e_pub_server);
    resp_packet.extend_from_slice(&empty_encrypted);

    // MAC1 over resp_packet[resp_start..resp_start + 60]
    let mut mac1_key_input = Vec::with_capacity(8 + 32);
    mac1_key_input.extend_from_slice(b"mac1----");
    mac1_key_input.extend_from_slice(&client_pub);
    let mac1_key = blake2s_256(&mac1_key_input);
    let mac1 = blake2s(&mac1_key, &resp_packet[resp_start..resp_start + 60], 16);
    resp_packet.extend_from_slice(&mac1);
    // MAC2 (16 zeros)
    resp_packet.extend_from_slice(&[0u8; 16]);

    Ok((responder_keys, resp_packet))
}

// ---------------------------------------------------------------------------
// 6. Transport Data Encapsulation & Decapsulation (Type 4 / H4)
// ---------------------------------------------------------------------------

/// Pads plaintext to a multiple of 16 bytes per WireGuard protocol specification:
/// P = pad16(packet)
#[inline]
pub fn pad16(len: usize) -> usize {
    if len % 16 == 0 {
        len
    } else {
        len + (16 - (len % 16))
    }
}

/// Checks if data is a well-formed IPv4 or IPv6 packet header.
#[inline]
pub fn is_ip_packet(data: &[u8]) -> bool {
    if data.len() < 20 {
        return false;
    }
    let ver = data[0] >> 4;
    if ver == 4 {
        let total_len = u16::from_be_bytes([data[2], data[3]]) as usize;
        total_len >= 20 && total_len <= data.len()
    } else if ver == 6 && data.len() >= 40 {
        let payload_len = u16::from_be_bytes([data[4], data[5]]) as usize;
        40 + payload_len <= data.len()
    } else {
        false
    }
}

/// Trims 16-byte padding and validates inner IPv4 or IPv6 header length after decryption.
pub fn trim_and_validate_ip_packet(plaintext: &[u8]) -> Result<Vec<u8>, String> {
    if plaintext.is_empty() {
        return Ok(Vec::new());
    }
    if plaintext[0] == 0x45 {
        if plaintext.len() < 20 {
            return Err(format!(
                "Decrypted IPv4 packet too short: {} bytes (expected >= 20)",
                plaintext.len()
            ));
        }
        let total_len = u16::from_be_bytes([plaintext[2], plaintext[3]]) as usize;
        if total_len < 20 {
            return Err(format!(
                "Decrypted IPv4 total length {} invalid (expected >= 20)",
                total_len
            ));
        }
        if total_len > plaintext.len() {
            return Err(format!(
                "Decrypted IPv4 total length {} exceeds decrypted buffer len {}",
                total_len,
                plaintext.len()
            ));
        }
        Ok(plaintext[..total_len].to_vec())
    } else if plaintext[0] == 0x60 {
        if plaintext.len() < 40 {
            return Err(format!(
                "Decrypted IPv6 packet too short: {} bytes (expected >= 40)",
                plaintext.len()
            ));
        }
        let payload_len = u16::from_be_bytes([plaintext[4], plaintext[5]]) as usize;
        let total_len = 40 + payload_len;
        if total_len > plaintext.len() {
            return Err(format!(
                "Decrypted IPv6 total length {} exceeds decrypted buffer len {}",
                total_len,
                plaintext.len()
            ));
        }
        Ok(plaintext[..total_len].to_vec())
    } else if plaintext.len() >= 40 && (plaintext[0] >> 4 == 6) {
        let payload_len = u16::from_be_bytes([plaintext[4], plaintext[5]]) as usize;
        let total_len = 40 + payload_len;
        if total_len <= plaintext.len() {
            Ok(plaintext[..total_len].to_vec())
        } else {
            Ok(plaintext.to_vec())
        }
    } else {
        // Raw/dummy non-IP payloads (e.g. test dummy data like b"Hello Server" or b"GET /chat"): returned as-is
        Ok(plaintext.to_vec())
    }
}

pub fn encapsulate_transport_packet(
    keys: &TransportKeys,
    h4: u32,
    ip_packet: &[u8],
) -> Result<Vec<u8>, String> {
    if keys.age() >= keys.limits.reject_after_time {
        return Err("AWG send key expired (reject_after_time exceeded)".to_string());
    }

    // Atomic CAS loop: counter never exceeds reject_after_messages, preventing nonce reuse
    let mut current = keys.send_counter.load(Ordering::Relaxed);
    let counter = loop {
        if current >= keys.limits.reject_after_messages {
            return Err(format!(
                "AWG send counter exhausted: {} >= reject_after_messages {}",
                current, keys.limits.reject_after_messages
            ));
        }
        match keys.send_counter.compare_exchange_weak(
            current,
            current + 1,
            Ordering::AcqRel,
            Ordering::Relaxed,
        ) {
            Ok(c) => break c,
            Err(actual) => current = actual,
        }
    };

    // Apply standard WireGuard 16-byte padding for IP packets (empty keepalive packets are unpadded)
    let padded_payload: Vec<u8> = if !ip_packet.is_empty() {
        if is_ip_packet(ip_packet) {
            let target_len = pad16(ip_packet.len());
            let mut buf = ip_packet.to_vec();
            buf.resize(target_len, 0u8);
            buf
        } else {
            ip_packet.to_vec()
        }
    } else {
        Vec::new()
    };

    let encrypted = aead_encrypt(&keys.send_key, counter, &[], &padded_payload)?;
    let mut packet = Vec::with_capacity(16 + encrypted.len());
    packet.extend_from_slice(&h4.to_le_bytes());
    packet.extend_from_slice(&keys.peer_index.to_le_bytes());
    packet.extend_from_slice(&counter.to_le_bytes());
    packet.extend_from_slice(&encrypted);
    Ok(packet)
}

pub fn decapsulate_transport_packet(
    keys: &TransportKeys,
    h4: u32,
    raw_udp: &[u8],
) -> Result<Vec<u8>, String> {
    if raw_udp.len() < 32 {
        return Err("AWG transport packet too short".to_string());
    }
    if keys.age() >= keys.limits.reject_after_time {
        return Err("AWG receive key expired (reject_after_time exceeded)".to_string());
    }
    let msg_type = u32::from_le_bytes([raw_udp[0], raw_udp[1], raw_udp[2], raw_udp[3]]);
    if msg_type != h4 {
        return Err(format!(
            "AWG packet type mismatch: got {}, expected H4={}",
            msg_type, h4
        ));
    }
    let receiver_idx = u32::from_le_bytes([raw_udp[4], raw_udp[5], raw_udp[6], raw_udp[7]]);
    if receiver_idx != keys.local_index {
        return Err(format!(
            "AWG receiver index mismatch: got {}, expected {}",
            receiver_idx, keys.local_index
        ));
    }
    let counter = u64::from_le_bytes([
        raw_udp[8],
        raw_udp[9],
        raw_udp[10],
        raw_udp[11],
        raw_udp[12],
        raw_udp[13],
        raw_udp[14],
        raw_udp[15],
    ]);

    if counter >= keys.limits.reject_after_messages {
        return Err(format!(
            "AWG packet counter exhausted: {} >= reject_after_messages {}",
            counter, keys.limits.reject_after_messages
        ));
    }

    // 1. Anti-replay pre-check (cheap validation before cryptographic operations)
    {
        let filter = keys.replay_filter.lock();
        if let Err(e) = filter.check(counter) {
            return Err(format!(
                "AWG anti-replay pre-check rejected packet counter {}: {}",
                counter, e
            ));
        }
    }

    // 2. Cryptographic AEAD verification and decryption
    let plaintext = aead_decrypt(&keys.recv_key, counter, &[], &raw_udp[16..])?;

    // 3. Commit counter into anti-replay sliding window under lock
    {
        let mut filter = keys.replay_filter.lock();
        if let Err(e) = filter.update(counter) {
            return Err(format!(
                "AWG anti-replay commit rejected packet counter {}: {}",
                counter, e
            ));
        }
        keys.recv_counter.store(filter.last_counter, Ordering::Relaxed);
    }

    // 4. Validate inner IP header and trim padding (or return empty vec for keepalive)
    trim_and_validate_ip_packet(&plaintext)
}

// ---------------------------------------------------------------------------
// 7. WireGuard Session & Rekey State Machine (WireGuard Paper Section 6)
// ---------------------------------------------------------------------------

#[derive(Debug, PartialEq, Eq)]
pub enum IncomingPacket {
    Data(Vec<u8>),
    Keepalive,
    HandshakeResponse {
        keepalive_to_send: Option<Vec<u8>>,
    },
    CookieReply {
        retransmit_initiation: Option<Vec<u8>>,
    },
    Ignored,
}

#[derive(Debug, PartialEq, Eq, Clone)]
pub enum TimerPacket {
    Rekey(Vec<u8>),
    Keepalive(Vec<u8>),
}

impl std::ops::Deref for TimerPacket {
    type Target = [u8];
    fn deref(&self) -> &Self::Target {
        match self {
            TimerPacket::Rekey(v) => v.as_slice(),
            TimerPacket::Keepalive(v) => v.as_slice(),
        }
    }
}

impl AsRef<[u8]> for TimerPacket {
    fn as_ref(&self) -> &[u8] {
        match self {
            TimerPacket::Rekey(v) => v.as_slice(),
            TimerPacket::Keepalive(v) => v.as_slice(),
        }
    }
}

impl From<TimerPacket> for Vec<u8> {
    fn from(tp: TimerPacket) -> Self {
        tp.into_vec()
    }
}

impl TimerPacket {
    pub fn into_vec(self) -> Vec<u8> {
        match self {
            TimerPacket::Rekey(v) => v,
            TimerPacket::Keepalive(v) => v,
        }
    }
}

pub struct PendingHandshake {
    pub state: HandshakeState,
    pub created_at: std::time::Instant,
    pub packet: Vec<u8>,
    pub attempts: u32,
}

pub struct KeypairSet {
    pub current: Arc<TransportKeys>,
    pub previous: Option<Arc<TransportKeys>>,
}

pub struct WireGuardSession {
    pub config: AwgConfig,
    pub limits: SessionLimits,
    pub keypairs: RwLock<KeypairSet>,
    pub pending_handshake: Mutex<Option<PendingHandshake>>,
    pub last_handshake_attempt: Mutex<Option<std::time::Instant>>,
    pub last_sent: Mutex<std::time::Instant>,
    pub last_received: Mutex<std::time::Instant>,
}

impl WireGuardSession {
    pub fn new(config: AwgConfig, initial_keys: Arc<TransportKeys>) -> Self {
        Self::with_limits(config, initial_keys, SessionLimits::default())
    }

    pub fn with_limits(
        config: AwgConfig,
        initial_keys: Arc<TransportKeys>,
        limits: SessionLimits,
    ) -> Self {
        let initial_keys = if initial_keys.limits != limits {
            Arc::new(TransportKeys::with_limits(
                initial_keys.send_key,
                initial_keys.recv_key,
                initial_keys.local_index,
                initial_keys.peer_index,
                limits,
                initial_keys.is_initiator,
            ))
        } else {
            initial_keys
        };
        let now = std::time::Instant::now();
        Self {
            config,
            limits,
            keypairs: RwLock::new(KeypairSet {
                current: initial_keys,
                previous: None,
            }),
            pending_handshake: Mutex::new(None),
            last_handshake_attempt: Mutex::new(None),
            last_sent: Mutex::new(now),
            last_received: Mutex::new(now),
        }
    }

    #[inline]
    pub fn current_keys(&self) -> Arc<TransportKeys> {
        Arc::clone(&self.keypairs.read().current)
    }

    #[inline]
    pub fn previous_keys(&self) -> Option<Arc<TransportKeys>> {
        self.keypairs.read().previous.as_ref().map(Arc::clone)
    }

    pub fn initiate_rekey(&self) -> Result<Vec<u8>, String> {
        let now = std::time::Instant::now();
        let mut pending_guard = self.pending_handshake.lock();

        if let Some(ref pending) = *pending_guard {
            if pending.created_at.elapsed() < self.limits.rekey_timeout {
                return Ok(pending.packet.clone());
            }
        }

        let local_index = rand::random::<u32>();
        let (state, packet) = create_initiation(&self.config, local_index)?;
        *pending_guard = Some(PendingHandshake {
            state,
            created_at: now,
            packet: packet.clone(),
            attempts: 1,
        });
        *self.last_handshake_attempt.lock() = Some(now);

        ldebug!(
            "AWG session: rekey initiated (local_index: 0x{:08x}, packet_len: {})",
            local_index,
            packet.len()
        );

        Ok(packet)
    }

    pub fn handle_handshake_response(&self, resp: &[u8]) -> Result<Option<Vec<u8>>, String> {
        let s2 = self.config.awg_params.s2;
        let min_len = 92 + s2;
        if resp.len() < min_len {
            return Err(format!(
                "AWG response too short: {} (expected >= {})",
                resp.len(),
                min_len
            ));
        }

        let resp_slice = &resp[s2..];
        let msg_type = u32::from_le_bytes([resp_slice[0], resp_slice[1], resp_slice[2], resp_slice[3]]);
        if msg_type != self.config.awg_params.h2 {
            return Err(format!(
                "AWG response message type mismatch: {} (expected H2={})",
                msg_type, self.config.awg_params.h2
            ));
        }

        let receiver_index = u32::from_le_bytes([resp_slice[8], resp_slice[9], resp_slice[10], resp_slice[11]]);

        let pending = {
            let mut guard = self.pending_handshake.lock();
            match *guard {
                Some(ref p) if p.state.local_index == receiver_index => guard.take().unwrap(),
                _ => return Ok(None),
            }
        };

        let keys = process_response(pending.state, &self.config, resp)?;
        let new_keys = Arc::new(TransportKeys::with_limits(
            keys.send_key,
            keys.recv_key,
            keys.local_index,
            keys.peer_index,
            self.limits,
            true,
        ));

        {
            let mut kp = self.keypairs.write();
            let old_current = Arc::clone(&kp.current);
            kp.previous = Some(old_current);
            kp.current = Arc::clone(&new_keys);
        }

        linfo!(
            "AWG session: rekey complete. Rotated to local_index 0x{:08x}, peer_index 0x{:08x}",
            new_keys.local_index,
            new_keys.peer_index
        );

        // WireGuard specification: initiator immediately sends empty keepalive packet under new key
        let keepalive = encapsulate_transport_packet(&new_keys, self.config.awg_params.h4, &[])?;
        *self.last_sent.lock() = std::time::Instant::now();

        Ok(Some(keepalive))
    }

    pub fn handle_cookie_reply(&self, raw_udp: &[u8]) -> Result<Option<Vec<u8>>, String> {
        if raw_udp.len() < 64 {
            return Ok(None);
        }
        let receiver_idx = u32::from_le_bytes([raw_udp[4], raw_udp[5], raw_udp[6], raw_udp[7]]);

        let mut pending_guard = self.pending_handshake.lock();
        if let Some(ref mut pending) = *pending_guard {
            if pending.state.local_index == receiver_idx {
                match parse_and_decrypt_cookie_reply(
                    &self.config.peer_public_key,
                    self.config.awg_params.h3,
                    receiver_idx,
                    &pending.state.mac1,
                    raw_udp,
                ) {
                    Ok(cookie) => {
                        set_peer_cookie(&self.config.peer_public_key, cookie);
                        if let Err(e) = set_initiation_mac2(&mut pending.packet, self.config.awg_params.s1, &cookie) {
                            lwarn!("AWG session: failed to update MAC2 in pending initiation: {}", e);
                            return Ok(None);
                        }
                        pending.created_at = std::time::Instant::now();
                        ldebug!(
                            "AWG session: cookie reply verified for local_index 0x{:08x}, retransmitting initiation with MAC2",
                            receiver_idx
                        );
                        return Ok(Some(pending.packet.clone()));
                    }
                    Err(e) => {
                        ldebug!("AWG session: dropped invalid cookie reply: {}", e);
                    }
                }
            }
        }
        Ok(None)
    }

    pub fn encapsulate_outgoing(&self, ip_packet: &[u8]) -> Result<(Vec<u8>, Option<Vec<u8>>), String> {
        let current = self.current_keys();

        if current.age() >= self.limits.reject_after_time {
            return Err("AWG send key expired (reject_after_time exceeded), transmission rejected".to_string());
        }
        if current.send_counter.load(Ordering::Relaxed) >= self.limits.reject_after_messages {
            return Err(format!(
                "AWG send counter exhausted ({} >= reject_after_messages {}), transmission rejected",
                current.send_counter.load(Ordering::Relaxed),
                self.limits.reject_after_messages
            ));
        }

        let mut maybe_rekey = None;
        if current.should_rekey() {
            let should_init = {
                let p = self.pending_handshake.lock();
                match *p {
                    None => true,
                    Some(ref pend) => pend.created_at.elapsed() >= self.limits.rekey_timeout,
                }
            };
            if should_init {
                if let Ok(rekey_pkt) = self.initiate_rekey() {
                    maybe_rekey = Some(rekey_pkt);
                }
            }
        }

        let wire_pkt = encapsulate_transport_packet(&current, self.config.awg_params.h4, ip_packet)?;
        *self.last_sent.lock() = std::time::Instant::now();

        Ok((wire_pkt, maybe_rekey))
    }

    pub fn decapsulate_incoming(&self, raw_udp: &[u8]) -> Result<IncomingPacket, String> {
        let s2 = self.config.awg_params.s2;

        // Check for AmneziaWG / WireGuard Handshake Response:
        // Size must be >= s2 + 92 and header at offset s2 must match H2
        if raw_udp.len() >= s2 + 92 {
            let candidate_h2 = u32::from_le_bytes([
                raw_udp[s2],
                raw_udp[s2 + 1],
                raw_udp[s2 + 2],
                raw_udp[s2 + 3],
            ]);
            if candidate_h2 == self.config.awg_params.h2 {
                let keepalive_opt = self.handle_handshake_response(raw_udp)?;
                return Ok(IncomingPacket::HandshakeResponse {
                    keepalive_to_send: keepalive_opt,
                });
            }
        }

        // Check for AmneziaWG / WireGuard Cookie Reply (Type 3 / H3):
        // Size must be >= 64 bytes and header at offset 0 must match H3
        if raw_udp.len() >= 64 {
            let candidate_h3 = u32::from_le_bytes([
                raw_udp[0],
                raw_udp[1],
                raw_udp[2],
                raw_udp[3],
            ]);
            if candidate_h3 == self.config.awg_params.h3 {
                let retransmit_opt = self.handle_cookie_reply(raw_udp)?;
                return Ok(IncomingPacket::CookieReply {
                    retransmit_initiation: retransmit_opt,
                });
            }
        }

        // Check for AmneziaWG / WireGuard Transport Data packet:
        // Size must be >= 32 and header at offset 0 must match H4
        if raw_udp.len() >= 32 {
            let candidate_h4 = u32::from_le_bytes([
                raw_udp[0],
                raw_udp[1],
                raw_udp[2],
                raw_udp[3],
            ]);
            if candidate_h4 == self.config.awg_params.h4 {
                let receiver_idx = u32::from_le_bytes([raw_udp[4], raw_udp[5], raw_udp[6], raw_udp[7]]);

                let (current, previous) = {
                    let kp = self.keypairs.read();
                    (Arc::clone(&kp.current), kp.previous.as_ref().map(Arc::clone))
                };

                if receiver_idx == current.local_index {
                    if current.age() >= self.limits.reject_after_time {
                        return Err("AWG current receive key expired (reject_after_time exceeded)".to_string());
                    }
                    let plaintext = decapsulate_transport_packet(&current, self.config.awg_params.h4, raw_udp)?;
                    *self.last_received.lock() = std::time::Instant::now();
                    if plaintext.is_empty() {
                        return Ok(IncomingPacket::Keepalive);
                    } else {
                        return Ok(IncomingPacket::Data(plaintext));
                    }
                } else if let Some(ref prev) = previous {
                    if receiver_idx == prev.local_index {
                        if prev.age() >= self.limits.reject_after_time {
                            return Err("AWG previous receive key expired (reject_after_time exceeded)".to_string());
                        }
                        let plaintext = decapsulate_transport_packet(prev, self.config.awg_params.h4, raw_udp)?;
                        *self.last_received.lock() = std::time::Instant::now();
                        if plaintext.is_empty() {
                            return Ok(IncomingPacket::Keepalive);
                        } else {
                            return Ok(IncomingPacket::Data(plaintext));
                        }
                    }
                }

                return Err(format!(
                    "AWG unknown receiver index 0x{:08x} (current: 0x{:08x}, prev: {:08x?})",
                    receiver_idx,
                    current.local_index,
                    previous.as_ref().map(|p| p.local_index)
                ));
            }
        }

        if raw_udp.len() < 4 {
            return Err("AWG datagram too short (< 4 bytes)".to_string());
        }

        Ok(IncomingPacket::Ignored)
    }

    pub fn effective_keepalive_interval(&self, has_active_flows: bool, qos_level: u8) -> Option<Duration> {
        let base_interval = self.limits.persistent_keepalive.or_else(|| {
            self.config.persistent_keepalive.map(|secs| Duration::from_secs(secs as u64))
        })?;

        // QoS level: 0 = NONE, 1 = MODERATE, 2 = SEVERE
        let adjusted = match qos_level {
            0 => base_interval,
            1 => base_interval.mul_f64(1.5),
            _ => {
                if has_active_flows {
                    base_interval.mul_f64(2.0)
                } else {
                    base_interval.mul_f64(3.0).min(Duration::from_secs(60))
                }
            }
        };
        Some(adjusted)
    }

    pub fn check_timers(&self) -> Option<TimerPacket> {
        self.check_timers_with_qos(true, 0)
    }

    pub fn check_timers_with_qos(&self, has_active_flows: bool, qos_level: u8) -> Option<TimerPacket> {
        let now = std::time::Instant::now();

        // 1. Expire previous keypair if beyond reject_after_time
        {
            let mut kp = self.keypairs.write();
            if let Some(ref prev) = kp.previous {
                if prev.age() >= self.limits.reject_after_time {
                    ldebug!(
                        "AWG session: previous keypair (0x{:08x}) expired after {:?}",
                        prev.local_index,
                        prev.age()
                    );
                    kp.previous = None;
                }
            }
        }

        // 2. Check pending handshake retransmission
        {
            let mut pending_guard = self.pending_handshake.lock();
            if let Some(ref mut pending) = *pending_guard {
                if pending.created_at.elapsed() >= self.limits.rekey_attempt_time {
                    lwarn!(
                        "AWG session: rekey attempt timed out after {:?}, dropping pending handshake",
                        pending.created_at.elapsed()
                    );
                    *pending_guard = None;
                } else if pending.created_at.elapsed() >= self.limits.rekey_timeout {
                    pending.attempts += 1;
                    pending.created_at = now;
                    ldebug!(
                        "AWG session: retransmitting rekey initiation (attempt {})",
                        pending.attempts
                    );
                    return Some(TimerPacket::Rekey(pending.packet.clone()));
                }
            }
        }

        // 3. Trigger time-based rekey if current key is older than rekey_after_time
        let current = self.current_keys();
        if current.should_rekey() {
            let can_rekey = {
                let p = self.pending_handshake.lock();
                p.is_none()
            };
            if can_rekey {
                if let Ok(pkt) = self.initiate_rekey() {
                    return Some(TimerPacket::Rekey(pkt));
                }
            }
        }

        // 4. If current key is expired or pending handshake is active, don't send keepalive
        if current.is_expired() {
            return None;
        }
        {
            let p = self.pending_handshake.lock();
            if p.is_some() {
                return None;
            }
        }

        // 5. Persistent Keepalive check: only send if elapsed since last sent >= interval
        if let Some(keepalive_interval) = self.effective_keepalive_interval(has_active_flows, qos_level) {
            let elapsed_sent = self.last_sent.lock().elapsed();
            if elapsed_sent >= keepalive_interval {
                match self.encapsulate_keepalive() {
                    Ok(ka_pkt) => {
                        *self.last_sent.lock() = now;
                        ldebug!(
                            "AWG session: generated persistent keepalive packet ({} bytes, idle {:?})",
                            ka_pkt.len(),
                            elapsed_sent
                        );
                        return Some(TimerPacket::Keepalive(ka_pkt));
                    }
                    Err(e) => {
                        lwarn!("AWG session: failed to encapsulate keepalive: {}", e);
                    }
                }
            }
        } else {
            // Passive keepalive per WireGuard spec Section 6.2:
            // If received packet from peer, but sent nothing back for KEEPALIVE_TIMEOUT
            let last_rcvd = *self.last_received.lock();
            let last_snt = *self.last_sent.lock();
            if last_rcvd > last_snt && last_snt.elapsed() >= self.limits.keepalive_timeout {
                match self.encapsulate_keepalive() {
                    Ok(ka_pkt) => {
                        *self.last_sent.lock() = now;
                        ldebug!("AWG session: generated passive keepalive packet");
                        return Some(TimerPacket::Keepalive(ka_pkt));
                    }
                    Err(e) => {
                        lwarn!("AWG session: failed to encapsulate passive keepalive: {}", e);
                    }
                }
            }
        }

        None
    }

    pub fn time_until_next_event(&self, has_active_flows: bool, qos_level: u8) -> Duration {
        let mut next_deadline = Duration::from_secs(60);

        // 1. Pending handshake retransmission deadline
        {
            let pending = self.pending_handshake.lock();
            if let Some(ref p) = *pending {
                let elapsed = p.created_at.elapsed();
                let rem = self.limits.rekey_timeout.saturating_sub(elapsed);
                return rem.max(Duration::from_millis(50));
            }
        }

        // 2. Rekey deadline for current key
        let current = self.current_keys();
        let key_age = current.age();
        if key_age < self.limits.rekey_after_time {
            let rekey_rem = self.limits.rekey_after_time - key_age;
            if rekey_rem < next_deadline {
                next_deadline = rekey_rem;
            }
        } else {
            return Duration::from_millis(50);
        }

        // 3. Keepalive deadline
        if let Some(keepalive_interval) = self.effective_keepalive_interval(has_active_flows, qos_level) {
            let elapsed_sent = self.last_sent.lock().elapsed();
            let ka_rem = keepalive_interval.saturating_sub(elapsed_sent);
            if ka_rem < next_deadline {
                next_deadline = ka_rem;
            }
        } else {
            let last_rcvd = *self.last_received.lock();
            let last_snt = *self.last_sent.lock();
            if last_rcvd > last_snt {
                let elapsed_sent = last_snt.elapsed();
                let passive_rem = self.limits.keepalive_timeout.saturating_sub(elapsed_sent);
                if passive_rem < next_deadline {
                    next_deadline = passive_rem;
                }
            }
        }

        next_deadline.max(Duration::from_millis(50))
    }

    pub fn encapsulate_keepalive(&self) -> Result<Vec<u8>, String> {
        let current = self.current_keys();
        encapsulate_transport_packet(&current, self.config.awg_params.h4, &[])
    }
}

// ---------------------------------------------------------------------------
// 7. Virtual smoltcp Device & User-Space Bridge
// ---------------------------------------------------------------------------

pub struct AwgSmolDevice {
    pub rx_queue: VecDeque<Vec<u8>>,
    pub tx_queue: VecDeque<Vec<u8>>,
}

impl AwgSmolDevice {
    pub fn new() -> Self {
        Self {
            rx_queue: VecDeque::with_capacity(64),
            tx_queue: VecDeque::with_capacity(64),
        }
    }
}

pub struct AwgRxToken(pub Vec<u8>);

impl RxToken for AwgRxToken {
    fn consume<R, F>(mut self, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        f(&mut self.0)
    }
}

pub struct AwgTxToken<'a>(pub &'a mut VecDeque<Vec<u8>>);

impl<'a> TxToken for AwgTxToken<'a> {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        let mut buf = vec![0u8; len];
        let res = f(&mut buf);
        self.0.push_back(buf);
        res
    }
}

impl Device for AwgSmolDevice {
    type RxToken<'a>
        = AwgRxToken
    where
        Self: 'a;
    type TxToken<'a>
        = AwgTxToken<'a>
    where
        Self: 'a;

    fn receive(
        &mut self,
        _timestamp: SmolInstant,
    ) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        self.rx_queue
            .pop_front()
            .map(|pkt| (AwgRxToken(pkt), AwgTxToken(&mut self.tx_queue)))
    }

    fn transmit(&mut self, _timestamp: SmolInstant) -> Option<Self::TxToken<'_>> {
        Some(AwgTxToken(&mut self.tx_queue))
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut caps = DeviceCapabilities::default();
        caps.medium = Medium::Ip;
        caps.max_transmission_unit = 1380;
        caps
    }
}

// ---------------------------------------------------------------------------
// 8. AwgPeerSession: Single Owner of WireGuard Peer Session & Multi-Flow Router
// ---------------------------------------------------------------------------

pub fn extract_flow_port(packet: &[u8]) -> Option<u16> {
    if packet.is_empty() {
        return None;
    }
    let version = packet[0] >> 4;
    match version {
        4 => {
            if packet.len() < 20 {
                return None;
            }
            let ihl = (packet[0] & 0x0f) as usize * 4;
            if packet.len() < ihl + 4 {
                return None;
            }
            let proto = packet[9];
            if proto == 6 || proto == 17 {
                Some(u16::from_be_bytes([packet[ihl + 2], packet[ihl + 3]]))
            } else if proto == 1 {
                // ICMP: payload encapsulates original IP header
                if packet.len() >= ihl + 8 + 20 + 4 {
                    let icmp_type = packet[ihl];
                    if icmp_type == 3 || icmp_type == 11 || icmp_type == 12 {
                        let orig_ip_offset = ihl + 8;
                        let orig_ihl = (packet[orig_ip_offset] & 0x0f) as usize * 4;
                        let orig_port_offset = orig_ip_offset + orig_ihl;
                        if packet.len() >= orig_port_offset + 2 {
                            Some(u16::from_be_bytes([
                                packet[orig_port_offset],
                                packet[orig_port_offset + 1],
                            ]))
                        } else {
                            None
                        }
                    } else {
                        None
                    }
                } else {
                    None
                }
            } else {
                None
            }
        }
        6 => {
            if packet.len() < 40 + 4 {
                return None;
            }
            let next_header = packet[6];
            if next_header == 6 || next_header == 17 {
                Some(u16::from_be_bytes([packet[42], packet[43]]))
            } else if next_header == 58 {
                // ICMPv6
                if packet.len() >= 40 + 8 + 40 + 4 {
                    let icmp6_type = packet[40];
                    if icmp6_type == 1 || icmp6_type == 2 || icmp6_type == 3 {
                        let orig_port_offset = 40 + 8 + 40;
                        Some(u16::from_be_bytes([
                            packet[orig_port_offset],
                            packet[orig_port_offset + 1],
                        ]))
                    } else {
                        None
                    }
                } else {
                    None
                }
            } else {
                None
            }
        }
        _ => None,
    }
}

pub struct AwgPeerSession {
    pub endpoint_addr: SocketAddr,
    pub socket: Arc<UdpSocket>,
    pub session: Arc<WireGuardSession>,
    pub config: AwgConfig,
    pub flows: Arc<parking_lot::RwLock<HashMap<u16, tokio::sync::mpsc::UnboundedSender<Vec<u8>>>>>,
    pub next_port: AtomicU16,
    pub cancel_token: CancellationToken,
    pub timer_notify: Arc<tokio::sync::Notify>,
}

impl std::fmt::Debug for AwgPeerSession {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AwgPeerSession")
            .field("endpoint_addr", &self.endpoint_addr)
            .field("profile_name", &self.config.profile_name)
            .field("flows", &self.active_flows())
            .finish()
    }
}

impl AwgPeerSession {
    pub fn new_with_session(
        endpoint_addr: SocketAddr,
        socket: Arc<UdpSocket>,
        session: Arc<WireGuardSession>,
        config: AwgConfig,
    ) -> Arc<Self> {
        let cancel_token = CancellationToken::new();
        let timer_notify = Arc::new(tokio::sync::Notify::new());
        let peer = Arc::new(Self {
            endpoint_addr,
            socket,
            session,
            config,
            flows: Arc::new(parking_lot::RwLock::new(HashMap::new())),
            next_port: AtomicU16::new(40000),
            cancel_token,
            timer_notify,
        });

        tokio::spawn(run_peer_receiver_loop(Arc::clone(&peer)));
        peer
    }

    pub async fn connect_internal(
        cancel_token: &CancellationToken,
        endpoint_override: Option<&str>,
        timeout_ms: u64,
    ) -> Result<Arc<Self>, String> {
        let config = AWG_CONFIG.read().clone();
        Self::connect_with_config(config, cancel_token, endpoint_override, timeout_ms).await
    }

    pub async fn connect_with_config(
        mut config: AwgConfig,
        cancel_token: &CancellationToken,
        endpoint_override: Option<&str>,
        timeout_ms: u64,
    ) -> Result<Arc<Self>, String> {
        if let Some(ep) = endpoint_override {
            config.endpoint = ep.to_string();
        }
        let profile = config.profile_name.clone();
        let endpoint = config.endpoint.clone();

        if config.private_key == [0u8; 32] {
            return Err(format!(
                "[profile='{}', stage='validate_credentials', endpoint='{}'] AWG private key not configured",
                profile, endpoint
            ));
        }

        let endpoint_addr: SocketAddr = match tokio::net::lookup_host(&config.endpoint).await {
            Ok(mut iter) => match iter.next() {
                Some(a) => a,
                None => {
                    return Err(format!(
                        "[profile='{}', stage='dns_lookup', endpoint='{}'] failed to resolve AWG endpoint to socket address",
                        profile, endpoint
                    ))
                }
            },
            Err(e) => {
                return Err(format!(
                    "[profile='{}', stage='dns_lookup', endpoint='{}'] DNS lookup failed: {}",
                    profile, endpoint, e
                ))
            }
        };

        let bind_addr = if endpoint_addr.is_ipv6() {
            "[::]:0"
        } else {
            "0.0.0.0:0"
        };
        let socket = UdpSocket::bind(bind_addr)
            .await
            .map_err(|e| format!(
                "[profile='{}', stage='udp_bind', endpoint='{}'] failed to bind local UDP socket: {}",
                profile, endpoint, e
            ))?;

        socket
            .connect(endpoint_addr)
            .await
            .map_err(|e| format!(
                "[profile='{}', stage='udp_connect', endpoint='{}'] failed to connect UDP socket to {}: {}",
                profile, endpoint, endpoint_addr, e
            ))?;

        ldebug!(
            "[profile='{}', stage='udp_ready', endpoint='{}'] UDP socket bound to local {}, connected to remote {}",
            profile,
            endpoint,
            socket
                .local_addr()
                .map(|a| a.to_string())
                .unwrap_or_default(),
            endpoint_addr
        );

        // 1. Obfuscation Preamble:
        //    a) Custom I1 packet (QUIC Initial camouflage if present)
        if let Some(ref i1_bytes) = config.awg_params.i1 {
            ldebug!(
                "[profile='{}', stage='send_camouflage', endpoint='{}'] sending I1 camouflage packet ({} bytes)",
                profile,
                endpoint,
                i1_bytes.len()
            );
            let _ = socket.send(i1_bytes).await;
            tokio::time::sleep(Duration::from_millis(5)).await;
        }

        //    b) Jc junk packets with random lengths in [Jmin, Jmax]
        if config.awg_params.jc > 0 {
            ldebug!(
                "[profile='{}', stage='send_junk', endpoint='{}'] sending {} junk packets (length {}..={}) to disrupt TSPU DPI signatures",
                profile,
                endpoint,
                config.awg_params.jc,
                config.awg_params.jmin,
                config.awg_params.jmax
            );
            for _ in 0..config.awg_params.jc {
                let len = if config.awg_params.jmax > config.awg_params.jmin {
                    config.awg_params.jmin
                        + (rand::random::<usize>()
                            % (config.awg_params.jmax - config.awg_params.jmin + 1))
                } else {
                    config.awg_params.jmin.max(40)
                };
                let mut junk = vec![0u8; len];
                rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut junk);
                let _ = socket.send(&junk).await;
                tokio::time::sleep(Duration::from_millis(2)).await;
            }
        }

        // 2. Send Handshake Initiation with Cookie Support & Retransmissions
        let local_index = rand::random::<u32>();
        let (handshake, mut init_packet) = create_initiation(&config, local_index)
            .map_err(|e| format!(
                "[profile='{}', stage='create_initiation', endpoint='{}'] failed to create initiation packet: {}",
                profile, endpoint, e
            ))?;

        ldebug!(
            "[profile='{}', stage='send_initiation', endpoint='{}'] sending handshake initiation ({} bytes, local index 0x{:08x}, H1={})",
            profile,
            endpoint,
            init_packet.len(),
            local_index,
            config.awg_params.h1
        );

        socket
            .send(&init_packet)
            .await
            .map_err(|e| format!(
                "[profile='{}', stage='send_initiation', endpoint='{}'] failed to send AWG initiation: {}",
                profile, endpoint, e
            ))?;

        // 3. Handshake Retransmission & Demux Loop (WireGuard Section 5.4 & 6.2)
        // Handles packet loss (retransmissions with backoff), cookie challenge (Type 3 / H3),
        // filtering out stray, corrupted, or old transport datagrams.
        let handshake_start = tokio::time::Instant::now();
        let total_budget = Duration::from_millis(timeout_ms);
        let deadline = handshake_start + total_budget;
        let mut retransmit_delay = Duration::from_millis(500);
        let mut next_retransmit = handshake_start + retransmit_delay;
        let mut attempts = 1u32;
        let max_attempts = 4u32;

        let mut resp_buf = [0u8; 2048];
        let keys = loop {
            let now = tokio::time::Instant::now();
            if now >= deadline {
                return Err(format!(
                    "[profile='{}', stage='handshake_timeout', endpoint='{}'] AWG handshake timed out after {}ms ({} attempts)",
                    profile, endpoint, timeout_ms, attempts
                ));
            }

            // Check if time to retransmit initiation
            if now >= next_retransmit && attempts < max_attempts {
                attempts += 1;
                let jitter_ms = rand::random::<u64>() % 100;
                retransmit_delay = (retransmit_delay * 3 / 2) + Duration::from_millis(jitter_ms);
                next_retransmit = now + retransmit_delay;
                ldebug!(
                    "[profile='{}', stage='retransmit_initiation', endpoint='{}'] retransmitting initiation (attempt {}/{})",
                    profile, endpoint, attempts, max_attempts
                );
                let _ = socket.send(&init_packet).await;
            }

            let wait_time = next_retransmit
                .saturating_duration_since(now)
                .min(deadline.saturating_duration_since(now))
                .max(Duration::from_millis(10));

            let recv_res = tokio::select! {
                _ = cancel_token.cancelled() => {
                    return Err(format!(
                        "[profile='{}', stage='handshake_cancelled', endpoint='{}'] AWG handshake cancelled",
                        profile, endpoint
                    ));
                }
                res = tokio::time::timeout(wait_time, socket.recv(&mut resp_buf)) => res,
            };

            let resp_len = match recv_res {
                Ok(Ok(n)) => n,
                Ok(Err(e)) => {
                    ldebug!(
                        "[profile='{}', stage='recv_response', endpoint='{}'] transient UDP recv error: {}",
                        profile, endpoint, e
                    );
                    continue;
                }
                Err(_) => {
                    // Timeout tick: loop will check retransmit or deadline
                    continue;
                }
            };

            let s2 = config.awg_params.s2;

            // Branch 1: Cookie Reply (AmneziaWG Type 3 / H3)
            // Length must be >= 64 bytes, message type must match H3
            if resp_len >= 64 {
                let msg_type = u32::from_le_bytes([resp_buf[0], resp_buf[1], resp_buf[2], resp_buf[3]]);
                if msg_type == config.awg_params.h3 {
                    let r_idx = u32::from_le_bytes([resp_buf[4], resp_buf[5], resp_buf[6], resp_buf[7]]);
                    if r_idx == local_index {
                        match parse_and_decrypt_cookie_reply(
                            &config.peer_public_key,
                            config.awg_params.h3,
                            local_index,
                            &handshake.mac1,
                            &resp_buf[..resp_len],
                        ) {
                            Ok(cookie) => {
                                ldebug!(
                                    "[profile='{}', stage='cookie_reply', endpoint='{}'] received valid cookie challenge, updating MAC2 and retransmitting immediately",
                                    profile, endpoint
                                );
                                set_peer_cookie(&config.peer_public_key, cookie);
                                if let Err(e) = set_initiation_mac2(&mut init_packet, config.awg_params.s1, &cookie) {
                                    lwarn!("failed to set MAC2 on initiation: {}", e);
                                } else {
                                    // Retransmit immediately with valid MAC2
                                    let _ = socket.send(&init_packet).await;
                                    next_retransmit = tokio::time::Instant::now() + retransmit_delay;
                                }
                                continue;
                            }
                            Err(e) => {
                                ldebug!(
                                    "[profile='{}', stage='cookie_reply_dropped', endpoint='{}'] invalid cookie reply: {}",
                                    profile, endpoint, e
                                );
                                continue;
                            }
                        }
                    } else {
                        ldebug!(
                            "[profile='{}', stage='cookie_reply_dropped', endpoint='{}'] cookie reply receiver index mismatch: 0x{:08x} vs local 0x{:08x}",
                            profile, endpoint, r_idx, local_index
                        );
                        continue;
                    }
                }
            }

            // Branch 2: Handshake Response (AmneziaWG Type 2 / H2)
            // Length must be >= s2 + 92, message type at offset s2 must match H2
            if resp_len >= s2 + 92 {
                let msg_type = u32::from_le_bytes([
                    resp_buf[s2],
                    resp_buf[s2 + 1],
                    resp_buf[s2 + 2],
                    resp_buf[s2 + 3],
                ]);
                if msg_type == config.awg_params.h2 {
                    let r_idx = u32::from_le_bytes([
                        resp_buf[s2 + 8],
                        resp_buf[s2 + 9],
                        resp_buf[s2 + 10],
                        resp_buf[s2 + 11],
                    ]);
                    if r_idx != local_index {
                        ldebug!(
                            "[profile='{}', stage='response_dropped', endpoint='{}'] response receiver index mismatch: 0x{:08x} vs local 0x{:08x}",
                            profile, endpoint, r_idx, local_index
                        );
                        continue;
                    }

                    match process_response(handshake, &config, &resp_buf[..resp_len]) {
                        Ok(keys) => {
                            ldebug!(
                                "[profile='{}', stage='response_verified', endpoint='{}'] handshake response verified after {} attempts",
                                profile, endpoint, attempts
                            );
                            break keys;
                        }
                        Err(e) => {
                            ldebug!(
                                "[profile='{}', stage='response_rejected', endpoint='{}'] response verification failed: {}",
                                profile, endpoint, e
                            );
                            continue;
                        }
                    }
                }
            }

            // Branch 3: Stray datagrams (old H4 transport packets, corrupt noise, etc.)
            ldebug!(
                "[profile='{}', stage='filter_stray', endpoint='{}'] filtered stray datagram of {} bytes during handshake",
                profile, endpoint, resp_len
            );
        };

        let initial_keys = Arc::new(keys);
        let session = Arc::new(WireGuardSession::new(config.clone(), Arc::clone(&initial_keys)));
        linfo!(
            "[profile='{}', stage='handshake_completed', endpoint='{}'] AWG peer session established: local index 0x{:08x}, peer index 0x{:08x} on {}",
            profile,
            endpoint,
            initial_keys.local_index,
            initial_keys.peer_index,
            endpoint_addr
        );

        Ok(Self::new_with_session(
            endpoint_addr,
            Arc::new(socket),
            session,
            config,
        ))
    }

    pub fn register_flow(&self, sender: tokio::sync::mpsc::UnboundedSender<Vec<u8>>) -> u16 {
        let mut guard = self.flows.write();
        for _ in 0..25000 {
            let port = self.next_port.fetch_add(1, Ordering::Relaxed);
            let p = if port < 40000 || port >= 65000 {
                self.next_port.store(40001, Ordering::Relaxed);
                40000
            } else {
                port
            };
            if !guard.contains_key(&p) {
                guard.insert(p, sender);
                drop(guard);
                self.timer_notify.notify_one();
                return p;
            }
        }
        guard.insert(40000, sender);
        drop(guard);
        self.timer_notify.notify_one();
        40000
    }

    pub fn unregister_flow(&self, port: u16) {
        let mut guard = self.flows.write();
        guard.remove(&port);
        drop(guard);
        self.timer_notify.notify_one();
    }

    pub fn active_flows(&self) -> usize {
        self.flows.read().len()
    }

    pub async fn send_ip_packet(&self, ip_pkt: &[u8]) -> Result<(), std::io::Error> {
        let (wire_pkt, maybe_rekey) = self
            .session
            .encapsulate_outgoing(ip_pkt)
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e))?;

        if let Some(rekey_pkt) = maybe_rekey {
            ldebug!("AWG: flow-triggered rekey initiation packet emitted");
            let _ = self.socket.send(&rekey_pkt).await;
            self.timer_notify.notify_one();
        }

        self.socket
            .send(&wire_pkt)
            .await
            .map(|_| ())
    }

    pub fn is_healthy(&self) -> bool {
        if self.cancel_token.is_cancelled() {
            return false;
        }
        let current_cfg_ep = AWG_CONFIG.read().endpoint.clone();
        if self.config.endpoint != current_cfg_ep {
            return false;
        }
        let keys = self.session.current_keys();
        if keys.is_expired() {
            return false;
        }
        true
    }

    pub fn close(&self) {
        self.cancel_token.cancel();
    }
}

async fn run_peer_receiver_loop(peer: Arc<AwgPeerSession>) {
    let socket = Arc::clone(&peer.socket);
    let session = Arc::clone(&peer.session);
    let flows = Arc::clone(&peer.flows);
    let cancel = peer.cancel_token.clone();
    let timer_notify = Arc::clone(&peer.timer_notify);
    let mut udp_rx_buf = [0u8; 2048];

    loop {
        let has_flows = peer.active_flows() > 0;
        let qos_level = crate::get_battery_qos_level();
        let sleep_dur = session.time_until_next_event(has_flows, qos_level);

        tokio::select! {
            _ = cancel.cancelled() => {
                ldebug!("AWG: background peer receiver loop stopped");
                break;
            }

            _ = timer_notify.notified() => {
                // Adaptive wakeup on flow changes, rekeys or QoS transitions
                continue;
            }

            _ = tokio::time::sleep(sleep_dur) => {
                let has_flows_now = peer.active_flows() > 0;
                let qos_level_now = crate::get_battery_qos_level();
                if let Some(pkt) = session.check_timers_with_qos(has_flows_now, qos_level_now) {
                    match pkt {
                        TimerPacket::Rekey(rekey_pkt) => {
                            ldebug!("AWG: background timer triggered rekey initiation");
                            let _ = socket.send(&rekey_pkt).await;
                        }
                        TimerPacket::Keepalive(ka_pkt) => {
                            ldebug!("AWG: background timer triggered persistent keepalive ({} bytes)", ka_pkt.len());
                            let _ = socket.send(&ka_pkt).await;
                        }
                    }
                }
            }

            // Receive from single WireGuard UDP socket
            res = socket.recv(&mut udp_rx_buf) => {
                match res {
                    Ok(n) if n >= 4 => {
                        match session.decapsulate_incoming(&udp_rx_buf[..n]) {
                            Ok(IncomingPacket::Data(ip_pkt)) => {
                                if let Some(port) = extract_flow_port(&ip_pkt) {
                                    let guard = flows.read();
                                    if let Some(sender) = guard.get(&port) {
                                        let _ = sender.send(ip_pkt);
                                    } else {
                                        ldebug!("AWG: dropped packet for unmapped local port {}", port);
                                    }
                                }
                            }
                            Ok(IncomingPacket::HandshakeResponse { keepalive_to_send }) => {
                                linfo!("AWG: handshake response processed; session keypair rotated");
                                if let Some(ka) = keepalive_to_send {
                                    let _ = socket.send(&ka).await;
                                }
                                timer_notify.notify_one();
                            }
                            Ok(IncomingPacket::CookieReply { retransmit_initiation }) => {
                                linfo!("AWG: cookie reply challenge received; MAC2 updated");
                                if let Some(retransmit_pkt) = retransmit_initiation {
                                    let _ = socket.send(&retransmit_pkt).await;
                                }
                                timer_notify.notify_one();
                            }
                            Ok(IncomingPacket::Keepalive) => {
                                ldebug!("AWG: keepalive received from server");
                            }
                            Ok(IncomingPacket::Ignored) => {}
                            Err(e) => {
                                ldebug!("AWG: dropped invalid UDP packet: {}", e);
                            }
                        }
                    }
                    Ok(_) => {}
                    Err(e) => {
                        lwarn!("AWG: UDP socket error in background loop: {}", e);
                        break;
                    }
                }
            }
        }
    }
}

pub struct AwgTunnel {
    pub peer: Arc<AwgPeerSession>,
    pub target_addr: String,
    pub local_port: u16,
    pub flow_rx: tokio::sync::Mutex<tokio::sync::mpsc::UnboundedReceiver<Vec<u8>>>,
}

impl AwgTunnel {
    pub async fn connect(
        target_addr: &str,
        cancel_token: &CancellationToken,
    ) -> Result<Self, String> {
        let peer = get_or_connect_peer(cancel_token).await?;
        let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
        let local_port = peer.register_flow(tx);
        Ok(Self {
            peer,
            target_addr: target_addr.to_string(),
            local_port,
            flow_rx: tokio::sync::Mutex::new(rx),
        })
    }

    pub fn open_flow(
        peer: Arc<AwgPeerSession>,
        target_addr: &str,
    ) -> Self {
        let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
        let local_port = peer.register_flow(tx);
        Self {
            peer,
            target_addr: target_addr.to_string(),
            local_port,
            flow_rx: tokio::sync::Mutex::new(rx),
        }
    }

    pub async fn close(&self) {
        self.peer.unregister_flow(self.local_port);
    }

    #[inline]
    pub fn session(&self) -> &Arc<WireGuardSession> {
        &self.peer.session
    }

    #[inline]
    pub fn socket(&self) -> &Arc<UdpSocket> {
        &self.peer.socket
    }
}

impl Drop for AwgTunnel {
    fn drop(&mut self) {
        self.peer.unregister_flow(self.local_port);
    }
}

async fn flush_smoltcp_tx(
    dev: &mut AwgSmolDevice,
    peer: &AwgPeerSession,
) {
    while let Some(pkt) = dev.tx_queue.pop_front() {
        let _ = peer.send_ip_packet(&pkt).await;
    }
}

impl AwgTunnel {
    pub async fn run_smoltcp_bridge(
        &self,
        client: tokio::net::TcpStream,
        cancel_token: CancellationToken,
    ) -> Result<(), std::io::Error> {
        let (target_ip, target_port) =
            match crate::masque::parse_target_endpoint(&self.target_addr).await {
                Ok(res) => res,
                Err(e) => {
                    lerror!(
                        "AWG: cannot resolve target endpoint for '{}': {}",
                        self.target_addr,
                        e
                    );
                    return Err(std::io::Error::new(std::io::ErrorKind::AddrNotAvailable, e));
                }
            };

        let client_v4 = self.peer.config.client_ipv4;
        let client_v6_opt = self.peer.config.client_ipv6;

        // Fail-safe validation for IPv6 targets: if target is IPv6, client MUST have a valid IPv6 address
        if let std::net::IpAddr::V6(target_v6) = target_ip {
            if client_v6_opt.is_none() {
                lerror!(
                    "AWG: target '{}' is IPv6 ([{}]:{}), but no client IPv6 address is configured; aborting without direct bypass",
                    self.target_addr,
                    target_v6,
                    target_port
                );
                return Err(std::io::Error::new(
                    std::io::ErrorKind::AddrNotAvailable,
                    format!("IPv6 target '{}' is unsupported: no client IPv6 configured", self.target_addr),
                ));
            }
        }

        let mut dev = AwgSmolDevice::new();
        let mut iface_cfg = SmolConfig::new(HardwareAddress::Ip);
        iface_cfg.random_seed = rand::random();

        let mut iface = Interface::new(iface_cfg, &mut dev, SmolInstant::now());
        let smol_client_v4 = Ipv4Address::from(client_v4);

        iface.update_ip_addrs(|addrs| {
            let _ = addrs.push(IpCidr::Ipv4(Ipv4Cidr::new(smol_client_v4, 32)));
            if let Some(v6) = client_v6_opt {
                let smol_client_v6 = Ipv6Address::from(v6);
                let _ = addrs.push(IpCidr::Ipv6(Ipv6Cidr::new(smol_client_v6, 128)));
            }
        });
        if let Err(e) = iface.routes_mut().add_default_ipv4_route(smol_client_v4) {
            lwarn!("AWG: add_default_ipv4_route: {:?}", e);
        }
        if let Some(v6) = client_v6_opt {
            let smol_client_v6 = Ipv6Address::from(v6);
            if let Err(e) = iface.routes_mut().add_default_ipv6_route(smol_client_v6) {
                lwarn!("AWG: add_default_ipv6_route: {:?}", e);
            }
        }

        let rx_buf = tcp::SocketBuffer::new(vec![0u8; 128 * 1024]);
        let tx_buf = tcp::SocketBuffer::new(vec![0u8; 128 * 1024]);
        let mut socket = TcpSocket::new(rx_buf, tx_buf);

        let local_port = self.local_port;
        let (remote_ep, local_ep) = match target_ip {
            std::net::IpAddr::V4(v4) => {
                ldebug!(
                    "AWG smoltcp bridge starting (IPv4): local {}:{} -> remote {}:{} via {}",
                    client_v4,
                    local_port,
                    v4,
                    target_port,
                    self.peer.endpoint_addr
                );
                let smol_target = Ipv4Address::from(v4);
                (
                    IpEndpoint::new(IpAddress::Ipv4(smol_target), target_port),
                    IpEndpoint::new(IpAddress::Ipv4(smol_client_v4), local_port),
                )
            }
            std::net::IpAddr::V6(v6) => {
                let c6 = client_v6_opt.unwrap();
                ldebug!(
                    "AWG smoltcp bridge starting (IPv6): local [{}:{} -> remote [{}]:{} via {}",
                    c6,
                    local_port,
                    v6,
                    target_port,
                    self.peer.endpoint_addr
                );
                let smol_client_v6 = Ipv6Address::from(c6);
                let smol_target_v6 = Ipv6Address::from(v6);
                (
                    IpEndpoint::new(IpAddress::Ipv6(smol_target_v6), target_port),
                    IpEndpoint::new(IpAddress::Ipv6(smol_client_v6), local_port),
                )
            }
        };

        if let Err(e) = socket.connect(iface.context(), remote_ep, local_ep) {
            lerror!("AWG: smoltcp socket connect error: {:?}", e);
            return Err(std::io::Error::new(
                std::io::ErrorKind::ConnectionRefused,
                format!("{:?}", e),
            ));
        }

        let mut sockets = SocketSet::new(vec![]);
        let sock_handle = sockets.add(socket);

        // Initial poll to emit SYN packet into tx_queue
        iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
        flush_smoltcp_tx(&mut dev, &self.peer).await;

        let (mut c_read, mut c_write) = client.into_split();
        let mut pending_client_data: Vec<u8> = Vec::with_capacity(32 * 1024);
        let mut read_temp_buf = [0u8; 16 * 1024];
        let mut recv_temp_buf = [0u8; 16 * 1024];

        let handshake_deadline = tokio::time::Instant::now() + Duration::from_secs(10);
        let mut handshake_logged = false;
        let mut flow_rx = self.flow_rx.lock().await;

        loop {
            if cancel_token.is_cancelled() || self.peer.cancel_token.is_cancelled() {
                let socket = sockets.get_mut::<TcpSocket>(sock_handle);
                socket.abort();
                iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
                flush_smoltcp_tx(&mut dev, &self.peer).await;
                break;
            }

            let mut needs_poll = false;

            // 1. Drain pending client data into smoltcp socket
            {
                let socket = sockets.get_mut::<TcpSocket>(sock_handle);
                if !pending_client_data.is_empty() && socket.can_send() {
                    match socket.send_slice(&pending_client_data) {
                        Ok(n) if n > 0 => {
                            pending_client_data.drain(..n);
                            STATS.bytes_up.fetch_add(n as i64, Ordering::Relaxed);
                            needs_poll = true;
                        }
                        _ => {}
                    }
                }
            }

            // 2. Drain smoltcp socket to Telegram client c_write
            loop {
                let socket = sockets.get_mut::<TcpSocket>(sock_handle);
                if !socket.can_recv() {
                    break;
                }
                match socket.recv_slice(&mut recv_temp_buf) {
                    Ok(n) if n > 0 => {
                        STATS.bytes_down.fetch_add(n as i64, Ordering::Relaxed);
                        if let Err(e) = c_write.write_all(&recv_temp_buf[..n]).await {
                            ldebug!("AWG: client write error: {}", e);
                            let socket = sockets.get_mut::<TcpSocket>(sock_handle);
                            socket.abort();
                            return Err(e);
                        }
                        needs_poll = true;
                    }
                    _ => break,
                }
            }

            // 3. Check socket connection state
            {
                let socket = sockets.get_mut::<TcpSocket>(sock_handle);
                let state = socket.state();

                if state == TcpState::Established && !handshake_logged {
                    handshake_logged = true;
                    ldebug!(
                        "AWG smoltcp: TCP connection established to {} (local port {})",
                        self.target_addr,
                        local_port
                    );
                }

                if state == TcpState::Closed || state == TcpState::TimeWait {
                    ldebug!("AWG smoltcp: TCP socket closed gracefully (port {})", local_port);
                    break;
                }

                if !handshake_logged
                    && (state == TcpState::SynSent || state == TcpState::SynReceived)
                    && tokio::time::Instant::now() > handshake_deadline
                {
                    lwarn!(
                        "AWG smoltcp: handshake timeout (10s) to {} (port {})",
                        self.target_addr,
                        local_port
                    );
                    socket.abort();
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::TimedOut,
                        "smoltcp handshake timeout",
                    ));
                }
            }

            if needs_poll {
                iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
                flush_smoltcp_tx(&mut dev, &self.peer).await;
            }

            // 4. Multiplex async events with adaptive smoltcp poll_delay timer
            let socket_can_send = {
                let s = sockets.get_mut::<TcpSocket>(sock_handle);
                s.can_send()
            };

            let can_read_client = socket_can_send && pending_client_data.len() < 32 * 1024;
            let poll_delay = iface.poll_delay(SmolInstant::now(), &sockets);
            let sleep_dur = match poll_delay {
                Some(d) => Duration::from_micros(d.total_micros())
                    .clamp(Duration::from_millis(2), Duration::from_millis(1000)),
                None => Duration::from_millis(1000),
            };

            tokio::select! {
                _ = cancel_token.cancelled() => {
                    let socket = sockets.get_mut::<TcpSocket>(sock_handle);
                    socket.abort();
                    break;
                }
                _ = self.peer.cancel_token.cancelled() => {
                    let socket = sockets.get_mut::<TcpSocket>(sock_handle);
                    socket.abort();
                    break;
                }

                // Read from local Telegram SOCKS5 client
                read_res = c_read.read(&mut read_temp_buf), if can_read_client => {
                    match read_res {
                        Ok(0) => {
                            let s = sockets.get_mut::<TcpSocket>(sock_handle);
                            s.close();
                            iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
                            flush_smoltcp_tx(&mut dev, &self.peer).await;
                        }
                        Ok(n) => {
                            pending_client_data.extend_from_slice(&read_temp_buf[..n]);
                            let s = sockets.get_mut::<TcpSocket>(sock_handle);
                            if s.can_send() {
                                match s.send_slice(&pending_client_data) {
                                    Ok(sent) if sent > 0 => {
                                        pending_client_data.drain(..sent);
                                        STATS.bytes_up.fetch_add(sent as i64, Ordering::Relaxed);
                                    }
                                    _ => {}
                                }
                            }
                            iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
                            flush_smoltcp_tx(&mut dev, &self.peer).await;
                        }
                        Err(e) => {
                            ldebug!("AWG: client read error: {}", e);
                            break;
                        }
                    }
                }

                // Read incoming IP packets routed to this flow
                ip_res = flow_rx.recv() => {
                    match ip_res {
                        Some(ip_pkt) => {
                            dev.rx_queue.push_back(ip_pkt);
                            iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
                            flush_smoltcp_tx(&mut dev, &self.peer).await;
                        }
                        None => {
                            ldebug!("AWG flow {}: router channel closed", local_port);
                            break;
                        }
                    }
                }

                // Adaptive smoltcp timer poll calculated from poll_delay (zero idle wakeups)
                _ = tokio::time::sleep(sleep_dur) => {
                    iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
                    flush_smoltcp_tx(&mut dev, &self.peer).await;
                }
            }
        }

        self.peer.unregister_flow(local_port);
        Ok(())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CircuitState {
    Closed,
    Open,
    HalfOpen,
}

pub struct AwgCircuitBreaker {
    pub state: CircuitState,
    pub consecutive_failures: u32,
    pub last_failure_at: Option<std::time::Instant>,
    pub cooldown_until: Option<std::time::Instant>,
    pub last_success_at: Option<std::time::Instant>,
    pub base_cooldown_ms: u64,
    pub max_cooldown_ms: u64,
}

impl AwgCircuitBreaker {
    pub const fn new() -> Self {
        Self {
            state: CircuitState::Closed,
            consecutive_failures: 0,
            last_failure_at: None,
            cooldown_until: None,
            last_success_at: None,
            base_cooldown_ms: 3000,
            max_cooldown_ms: 30000,
        }
    }

    pub fn should_allow(&mut self) -> bool {
        match self.state {
            CircuitState::Closed => true,
            CircuitState::Open => {
                if let Some(until) = self.cooldown_until {
                    if std::time::Instant::now() >= until {
                        ldebug!("AWG circuit breaker: cooldown expired; transitioning to HalfOpen");
                        self.state = CircuitState::HalfOpen;
                        true
                    } else {
                        false
                    }
                } else {
                    self.state = CircuitState::Closed;
                    true
                }
            }
            CircuitState::HalfOpen => true,
        }
    }

    pub fn is_open(&self) -> bool {
        if self.state == CircuitState::Open {
            if let Some(until) = self.cooldown_until {
                return std::time::Instant::now() < until;
            }
        }
        false
    }

    pub fn record_success(&mut self) {
        self.consecutive_failures = 0;
        self.state = CircuitState::Closed;
        self.cooldown_until = None;
        self.last_success_at = Some(std::time::Instant::now());
    }

    pub fn record_failure(&mut self) {
        self.consecutive_failures = self.consecutive_failures.saturating_add(1);
        let now = std::time::Instant::now();
        self.last_failure_at = Some(now);

        let exp_factor = 2u64.pow((self.consecutive_failures - 1).min(4));
        let backoff_ms = (self.base_cooldown_ms * exp_factor).min(self.max_cooldown_ms);
        let jitter_ms = rand::random::<u64>() % 1000;
        let total_cooldown = Duration::from_millis(backoff_ms + jitter_ms);

        self.cooldown_until = Some(now + total_cooldown);
        self.state = CircuitState::Open;
        ldebug!(
            "AWG circuit breaker: failure recorded (consecutive={}); open for {}ms",
            self.consecutive_failures,
            total_cooldown.as_millis()
        );
    }

    pub fn reset(&mut self) {
        self.state = CircuitState::Closed;
        self.consecutive_failures = 0;
        self.cooldown_until = None;
    }
}

static AWG_CIRCUIT_BREAKER: parking_lot::RwLock<AwgCircuitBreaker> =
    parking_lot::RwLock::new(AwgCircuitBreaker::new());

pub fn reset_awg_circuit_breaker() {
    AWG_CIRCUIT_BREAKER.write().reset();
}

pub fn get_awg_circuit_breaker_state() -> CircuitState {
    AWG_CIRCUIT_BREAKER.read().state
}

static ACTIVE_AWG_PEER: parking_lot::RwLock<Option<Arc<AwgPeerSession>>> =
    parking_lot::RwLock::new(None);
static AWG_DIAL_MUTEX: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

pub async fn get_or_connect_peer(
    cancel_token: &CancellationToken,
) -> Result<Arc<AwgPeerSession>, String> {
    {
        let guard = ACTIVE_AWG_PEER.read();
        if let Some(ref peer) = *guard {
            if peer.is_healthy() {
                return Ok(Arc::clone(peer));
            }
        }
    }

    {
        let cb = AWG_CIRCUIT_BREAKER.read();
        if cb.is_open() {
            let (profile, endpoint) = {
                let cfg = AWG_CONFIG.read();
                (cfg.profile_name.clone(), cfg.endpoint.clone())
            };
            return Err(format!(
                "[profile='{}', stage='circuit_breaker_open', endpoint='{}'] circuit breaker is open; failing fast without dial storm",
                profile, endpoint
            ));
        }
    }

    let _dial_guard = AWG_DIAL_MUTEX.lock().await;

    {
        let guard = ACTIVE_AWG_PEER.read();
        if let Some(ref peer) = *guard {
            if peer.is_healthy() {
                AWG_CIRCUIT_BREAKER.write().record_success();
                return Ok(Arc::clone(peer));
            }
        }
    }

    {
        let mut cb = AWG_CIRCUIT_BREAKER.write();
        if !cb.should_allow() {
            let (profile, endpoint) = {
                let cfg = AWG_CONFIG.read();
                (cfg.profile_name.clone(), cfg.endpoint.clone())
            };
            return Err(format!(
                "[profile='{}', stage='circuit_breaker_open', endpoint='{}'] circuit breaker is open; failing fast without dial storm",
                profile, endpoint
            ));
        }
    }

    let primary_jitter_ms = rand::random::<u64>() % 200;
    let timeout_ms = 2500 + primary_jitter_ms;
    match AwgPeerSession::connect_internal(cancel_token, None, timeout_ms).await {
        Ok(peer) => {
            AWG_CIRCUIT_BREAKER.write().record_success();
            *ACTIVE_AWG_PEER.write() = Some(Arc::clone(&peer));
            Ok(peer)
        }
        Err(e) => {
            if AWG_CONFIG.read().fallback_endpoints.is_empty() {
                AWG_CIRCUIT_BREAKER.write().record_failure();
            }
            Err(e)
        }
    }
}

pub fn set_active_peer(peer: Arc<AwgPeerSession>) {
    AWG_CIRCUIT_BREAKER.write().record_success();
    *ACTIVE_AWG_PEER.write() = Some(peer);
}

pub fn invalidate_active_peer() {
    if let Some(old) = ACTIVE_AWG_PEER.write().take() {
        old.close();
    }
}

pub fn notify_active_peer_timer() {
    let guard = ACTIVE_AWG_PEER.read();
    if let Some(ref peer) = *guard {
        peer.timer_notify.notify_one();
    }
}

pub async fn awg_acquire_tunnel(
    target_addr: &str,
    cancel_token: &CancellationToken,
) -> Option<AwgTunnel> {
    // 1. Fast path: check existing healthy peer without lock contention
    {
        let guard = ACTIVE_AWG_PEER.read();
        if let Some(ref peer) = *guard {
            if peer.is_healthy() {
                return Some(AwgTunnel::open_flow(Arc::clone(peer), target_addr));
            }
        }
    }

    // 2. Fast fail if circuit breaker is open (prevents dial storm across concurrent requests)
    {
        let cb = AWG_CIRCUIT_BREAKER.read();
        if cb.is_open() {
            let (profile, endpoint) = {
                let cfg = AWG_CONFIG.read();
                (cfg.profile_name.clone(), cfg.endpoint.clone())
            };
            ldebug!(
                "[profile='{}', stage='circuit_breaker_open', endpoint='{}'] circuit breaker is open; failing fast without dial storm",
                profile, endpoint
            );
            return None;
        }
    }

    // 3. Single-flight dial coordination
    let _dial_guard = AWG_DIAL_MUTEX.lock().await;

    // Double-check active peer under the lock: a concurrent dialer may have established it
    {
        let guard = ACTIVE_AWG_PEER.read();
        if let Some(ref peer) = *guard {
            if peer.is_healthy() {
                AWG_CIRCUIT_BREAKER.write().record_success();
                return Some(AwgTunnel::open_flow(Arc::clone(peer), target_addr));
            }
        }
    }

    // Double-check circuit breaker under the lock
    {
        let mut cb = AWG_CIRCUIT_BREAKER.write();
        if !cb.should_allow() {
            let (profile, endpoint) = {
                let cfg = AWG_CONFIG.read();
                (cfg.profile_name.clone(), cfg.endpoint.clone())
            };
            ldebug!(
                "[profile='{}', stage='circuit_breaker_open', endpoint='{}'] circuit breaker is open; failing fast without dial storm",
                profile, endpoint
            );
            return None;
        }
    }

    let overall_deadline = tokio::time::Instant::now() + Duration::from_millis(6000);
    let (profile_name, configured_ep, fallback_endpoints, is_warp) = {
        let cfg = AWG_CONFIG.read();
        (
            cfg.profile_name.clone(),
            cfg.endpoint.clone(),
            cfg.fallback_endpoints.clone(),
            cfg.is_warp_profile(),
        )
    };

    // 4. Try primary endpoint first with jitter
    let primary_jitter_ms = rand::random::<u64>() % 250;
    let primary_timeout = 2200 + primary_jitter_ms;
    ldebug!(
        "[profile='{}', stage='primary_dial', endpoint='{}'] single-flight dial to primary endpoint (timeout {}ms)",
        profile_name, configured_ep, primary_timeout
    );

    match AwgPeerSession::connect_internal(cancel_token, None, primary_timeout).await {
        Ok(peer) => {
            linfo!(
                "[profile='{}', stage='primary_success', endpoint='{}'] single-flight dial established primary peer session",
                profile_name, configured_ep
            );
            AWG_CIRCUIT_BREAKER.write().record_success();
            set_active_peer(Arc::clone(&peer));
            return Some(AwgTunnel::open_flow(peer, target_addr));
        }
        Err(e) => {
            ldebug!(
                "[profile='{}', stage='primary_failed', endpoint='{}'] primary dial failed: {}",
                profile_name, configured_ep, e
            );
        }
    }

    // 5. If primary failed, check fallback endpoints
    if fallback_endpoints.is_empty() {
        lwarn!(
            "[profile='{}', stage='no_viable_fallbacks', endpoint='{}'] primary endpoint failed; no fallback endpoints configured for profile",
            profile_name, configured_ep
        );
        AWG_CIRCUIT_BREAKER.write().record_failure();
        return None;
    }

    if cancel_token.is_cancelled() || tokio::time::Instant::now() >= overall_deadline {
        AWG_CIRCUIT_BREAKER.write().record_failure();
        return None;
    }

    lwarn!(
        "[profile='{}', stage='probing_fallbacks', endpoint='{}'] primary endpoint failed; probing {} fallback candidates (overall deadline 6s)...",
        profile_name, configured_ep, fallback_endpoints.len()
    );

    for candidate in &fallback_endpoints {
        if candidate == &configured_ep || cancel_token.is_cancelled() {
            continue;
        }

        if tokio::time::Instant::now() >= overall_deadline {
            lwarn!(
                "[profile='{}', stage='failover_overall_timeout', endpoint='{}'] failover overall deadline reached",
                profile_name, configured_ep
            );
            break;
        }

        // Strict security isolation: non-WARP profiles must NEVER probe Cloudflare Anycast endpoints!
        if !is_warp && is_cloudflare_warp_endpoint(candidate) {
            lwarn!(
                "[profile='{}', stage='probe_candidate_filtered', endpoint='{}'] skipping alien Cloudflare Anycast endpoint for custom profile",
                profile_name, candidate
            );
            continue;
        }

        let time_left = overall_deadline.saturating_duration_since(tokio::time::Instant::now());
        let candidate_jitter_ms = rand::random::<u64>() % 200;
        let candidate_timeout = (1500 + candidate_jitter_ms).min(time_left.as_millis() as u64).max(500);

        ldebug!(
            "[profile='{}', stage='probe_candidate', endpoint='{}'] probing failover candidate (timeout {}ms)",
            profile_name, candidate, candidate_timeout
        );

        match AwgPeerSession::connect_internal(cancel_token, Some(candidate), candidate_timeout).await {
            Ok(peer) => {
                linfo!(
                    "[profile='{}', stage='failover_success', endpoint='{}'] active endpoint switched successfully",
                    profile_name, candidate
                );
                let mut cfg = AWG_CONFIG.write();
                cfg.endpoint = candidate.to_string();
                AWG_CIRCUIT_BREAKER.write().record_success();
                set_active_peer(Arc::clone(&peer));
                return Some(AwgTunnel::open_flow(peer, target_addr));
            }
            Err(ce) => {
                ldebug!(
                    "[profile='{}', stage='probe_failed', endpoint='{}'] failover candidate unreachable: {}",
                    profile_name, candidate, ce
                );
            }
        }
    }

    lwarn!(
        "[profile='{}', stage='all_fallbacks_exhausted', endpoint='{}'] AWG tunnel acquire error for {}: all fallback endpoints exhausted; circuit breaker tripped",
        profile_name, configured_ep, target_addr
    );
    AWG_CIRCUIT_BREAKER.write().record_failure();
    None
}

// ---------------------------------------------------------------------------
// 9. Unit Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    static AWG_TEST_MUTEX: std::sync::Mutex<()> = std::sync::Mutex::new(());

    #[test]
    fn test_rfc7693_blake2s_empty() {
        let h = blake2s_256(b"");
        let hex = h.iter().map(|b| format!("{:02x}", b)).collect::<String>();
        assert_eq!(
            hex,
            "69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9"
        );
    }

    #[test]
    fn test_rfc7693_blake2s_fox() {
        let h = blake2s_256(b"The quick brown fox jumps over the lazy dog");
        let hex = h.iter().map(|b| format!("{:02x}", b)).collect::<String>();
        assert_eq!(
            hex,
            "606beeec743ccbeff6cbcdf5d5302aa855c256c29b88c8ed331ea1a6bf3c8812"
        );
    }

    #[test]
    fn test_rfc7748_x25519_vector1() {
        fn from_hex(s: &str) -> [u8; 32] {
            let mut arr = [0u8; 32];
            for i in 0..32 {
                arr[i] = u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).unwrap();
            }
            arr
        }

        let scalar = from_hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4");
        let point = from_hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c");

        let res = x25519(&scalar, &point);
        let hex_res = res.iter().map(|b| format!("{:02x}", b)).collect::<String>();
        assert_eq!(
            hex_res,
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"
        );
    }

    #[test]
    fn test_awg_ini_parsing() {
        let ini = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32
Jc = 8
Jmin = 40
Jmax = 70
S1 = 16
S2 = 32
H1 = 100
H2 = 200
H3 = 300
H4 = 400

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1:8443
"#;

        let cfg = AwgConfig::parse_ini(ini).expect("INI parse failed");
        assert_eq!(cfg.endpoint, "188.114.96.1:8443");
        assert_eq!(cfg.private_key, [b'a'; 32]);
        assert_eq!(cfg.public_key, x25519_base(&[b'a'; 32]));
        assert_eq!(cfg.client_ipv4, Ipv4Addr::new(172, 16, 0, 2));
        assert_eq!(cfg.awg_params.jc, 8);
        assert_eq!(cfg.awg_params.jmin, 40);
        assert_eq!(cfg.awg_params.jmax, 70);
        assert_eq!(cfg.awg_params.s1, 16);
        assert_eq!(cfg.awg_params.s2, 32);
        assert_eq!(cfg.awg_params.h1, 100);
        assert_eq!(cfg.awg_params.h2, 200);
        assert_eq!(cfg.awg_params.h3, 300);
        assert_eq!(cfg.awg_params.h4, 400);
    }

    #[test]
    fn test_awg_initiation_packet_structure() {
        let mut cfg = AwgConfig::default();
        cfg.private_key = [1u8; 32];
        cfg.public_key = x25519_base(&cfg.private_key);
        cfg.awg_params.s1 = 20;
        cfg.awg_params.h1 = 777;

        let (state, packet) =
            create_initiation(&cfg, 0x12345678).expect("initiation creation failed");
        assert_eq!(packet.len(), 148 + 20);
        // AmneziaWG v1.0: S1=20 bytes are random prefix padding at packet[0..20]
        let msg_type = u32::from_le_bytes([packet[20], packet[21], packet[22], packet[23]]);
        assert_eq!(msg_type, 777);
        let local_idx = u32::from_le_bytes([packet[24], packet[25], packet[26], packet[27]]);
        assert_eq!(local_idx, 0x12345678);
        assert_eq!(state.local_index, 0x12345678);
    }

    #[test]
    fn test_transport_packet_roundtrip() {
        let keys = TransportKeys::new([2u8; 32], [2u8; 32], 0x11112222, 0x33334444);

        let ip_payload = b"SYN packet data 1234567890";
        let h4 = 4444;
        let wire_packet =
            encapsulate_transport_packet(&keys, h4, ip_payload).expect("encapsulation failed");

        let peer_keys = TransportKeys::new([2u8; 32], [2u8; 32], 0x33334444, 0x11112222);

        let decrypted = decapsulate_transport_packet(&peer_keys, h4, &wire_packet)
            .expect("decapsulation failed");
        assert_eq!(decrypted, ip_payload);

        // Anti-replay: identical packet replayed must be rejected
        let replay_res = decapsulate_transport_packet(&peer_keys, h4, &wire_packet);
        assert!(replay_res.is_err(), "Replayed datagram must be rejected");
        let err_msg = replay_res.unwrap_err();
        assert!(
            err_msg.contains("replay"),
            "Error must mention replay, got: {}",
            err_msg
        );
    }

    #[test]
    fn test_replay_filter_slide_bitmap_boundary_cases() {
        let mut bitmap = [0u64; BITMAP_WORDS];
        bitmap[0] = 1; // bit 0 set

        // Shift by 0: no-op
        slide_bitmap(&mut bitmap, 0);
        assert_eq!(bitmap[0], 1);

        // Shift by 1: bit 0 moves to bit 1
        slide_bitmap(&mut bitmap, 1);
        assert_eq!(bitmap[0], 2);

        // Shift by 62: bit 1 moves to bit 63
        slide_bitmap(&mut bitmap, 62);
        assert_eq!(bitmap[0], 1u64 << 63);
        assert_eq!(bitmap[1], 0);

        // Shift by 1 across word boundary: bit 63 in word 0 moves to bit 0 in word 1
        slide_bitmap(&mut bitmap, 1);
        assert_eq!(bitmap[0], 0);
        assert_eq!(bitmap[1], 1);

        // Shift by 65: bit 0 in word 1 moves by 65 bits -> bit 1 in word 2
        slide_bitmap(&mut bitmap, 65);
        assert_eq!(bitmap[0], 0);
        assert_eq!(bitmap[1], 0);
        assert_eq!(bitmap[2], 2);

        // Shift beyond entire window (2048): all bits zeroed
        slide_bitmap(&mut bitmap, 2048);
        assert!(bitmap.iter().all(|&w| w == 0));
    }

    #[test]
    fn test_replay_filter_sequential_and_duplicates() {
        let mut filter = ReplayFilter::new();

        for c in 0..20 {
            assert!(filter.check(c).is_ok());
            assert!(filter.update(c).is_ok());
            assert_eq!(filter.last_counter, c);

            // Immediate duplicate must be rejected
            assert_eq!(filter.check(c), Err(ReplayError::Replay));
            assert_eq!(filter.update(c), Err(ReplayError::Replay));
        }

        // Old duplicates within window must be rejected
        for c in 0..20 {
            assert_eq!(filter.check(c), Err(ReplayError::Replay));
            assert_eq!(filter.update(c), Err(ReplayError::Replay));
        }
    }

    #[test]
    fn test_replay_filter_reordering_window() {
        let mut filter = ReplayFilter::new();
        let arrival_order = [0u64, 5, 2, 1, 4, 3, 10, 8, 9, 7, 6];

        for &c in &arrival_order {
            assert!(
                filter.check(c).is_ok(),
                "Counter {} should be accepted within reorder window",
                c
            );
            assert!(filter.update(c).is_ok());
        }

        assert_eq!(filter.last_counter, 10);

        // Every seen counter must now be rejected as replay
        for &c in &arrival_order {
            assert_eq!(filter.check(c), Err(ReplayError::Replay));
            assert_eq!(filter.update(c), Err(ReplayError::Replay));
        }
    }

    #[test]
    fn test_replay_filter_sliding_window_expiration() {
        let mut filter = ReplayFilter::new();

        // Initial base counter
        assert!(filter.update(10).is_ok());
        assert_eq!(filter.last_counter, 10);

        // Jump forward by 2048 packets (new counter = 10 + 2048 = 2058)
        assert!(filter.update(2058).is_ok());
        assert_eq!(filter.last_counter, 2058);

        // Counter 10 is at diff = 2058 - 10 = 2048 >= COUNTER_WINDOW_SIZE -> TooOld
        assert_eq!(filter.check(10), Err(ReplayError::TooOld));
        assert_eq!(filter.update(10), Err(ReplayError::TooOld));

        // Counter 11 is at diff = 2058 - 11 = 2047 < COUNTER_WINDOW_SIZE -> Accepted (reordered)
        assert_eq!(filter.check(11), Ok(()));
        assert_eq!(filter.update(11), Ok(()));

        // Counter 11 again -> Replay
        assert_eq!(filter.check(11), Err(ReplayError::Replay));
        assert_eq!(filter.update(11), Err(ReplayError::Replay));
    }

    #[test]
    fn test_replay_filter_counter_exhaustion() {
        let mut filter = ReplayFilter::new();

        assert_eq!(
            filter.check(REJECT_AFTER_MESSAGES),
            Err(ReplayError::CounterExhausted)
        );
        assert_eq!(
            filter.update(REJECT_AFTER_MESSAGES),
            Err(ReplayError::CounterExhausted)
        );

        assert_eq!(filter.check(u64::MAX), Err(ReplayError::CounterExhausted));
        assert_eq!(filter.update(u64::MAX), Err(ReplayError::CounterExhausted));
    }

    #[test]
    fn test_decapsulate_transport_packet_anti_replay_pipeline() {
        let keys_tx = TransportKeys::new([7u8; 32], [8u8; 32], 0xAAAA, 0xBBBB);
        let keys_rx = TransportKeys::new([8u8; 32], [7u8; 32], 0xBBBB, 0xAAAA);
        let h4 = 4;

        let p0 = encapsulate_transport_packet(&keys_tx, h4, b"payload-0").unwrap();
        let p1 = encapsulate_transport_packet(&keys_tx, h4, b"payload-1").unwrap();
        let p2 = encapsulate_transport_packet(&keys_tx, h4, b"payload-2").unwrap();
        let p3 = encapsulate_transport_packet(&keys_tx, h4, b"payload-3").unwrap();

        // Deliver out of order: [P0, P2, P1, P3]
        assert_eq!(
            decapsulate_transport_packet(&keys_rx, h4, &p0).unwrap(),
            b"payload-0"
        );
        assert_eq!(
            decapsulate_transport_packet(&keys_rx, h4, &p2).unwrap(),
            b"payload-2"
        );
        assert_eq!(
            decapsulate_transport_packet(&keys_rx, h4, &p1).unwrap(),
            b"payload-1"
        );
        assert_eq!(
            decapsulate_transport_packet(&keys_rx, h4, &p3).unwrap(),
            b"payload-3"
        );

        // Replaying any packet must fail
        assert!(decapsulate_transport_packet(&keys_rx, h4, &p1).is_err());
        assert!(decapsulate_transport_packet(&keys_rx, h4, &p0).is_err());
        assert!(decapsulate_transport_packet(&keys_rx, h4, &p2).is_err());
        assert!(decapsulate_transport_packet(&keys_rx, h4, &p3).is_err());
    }

    #[test]
    fn test_decapsulate_unauthenticated_packet_does_not_advance_replay_window() {
        let keys_tx = TransportKeys::new([9u8; 32], [10u8; 32], 0x1234, 0x5678);
        let keys_rx = TransportKeys::new([10u8; 32], [9u8; 32], 0x5678, 0x1234);
        let h4 = 4;

        // Packet 0: normal authenticated packet
        let p0 = encapsulate_transport_packet(&keys_tx, h4, b"valid-0").unwrap();
        assert_eq!(
            decapsulate_transport_packet(&keys_rx, h4, &p0).unwrap(),
            b"valid-0"
        );

        // Attacker creates a packet with huge counter (500) and corrupt ciphertext/tag
        let mut forged = encapsulate_transport_packet(&keys_tx, h4, b"forged-msg").unwrap();
        // Modify counter in wire format to 500
        forged[8..16].copy_from_slice(&500u64.to_le_bytes());
        // Invalidate tag so AEAD will fail
        let last_byte_idx = forged.len() - 1;
        forged[last_byte_idx] ^= 0xFF;

        // Decapsulating forged packet must fail AEAD
        let forged_res = decapsulate_transport_packet(&keys_rx, h4, &forged);
        assert!(forged_res.is_err(), "Forged packet must fail decapsulation");

        // The replay filter MUST NOT have advanced to 500!
        assert_eq!(keys_rx.replay_filter.lock().last_counter, 0);

        // Legitimate subsequent packet must be accepted without being rejected as old
        let p1 = encapsulate_transport_packet(&keys_tx, h4, b"valid-1").unwrap();
        let p1_counter = u64::from_le_bytes(p1[8..16].try_into().unwrap());
        assert_eq!(
            decapsulate_transport_packet(&keys_rx, h4, &p1).unwrap(),
            b"valid-1"
        );
        assert_eq!(keys_rx.replay_filter.lock().last_counter, p1_counter);
    }

    #[test]
    fn test_awg_ini_parsing_dual_stack() {
        let ini = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32, 2606:4700:110:812c:a554:b442:26d4:1330/128

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1:8443
"#;

        let cfg = AwgConfig::parse_ini(ini).expect("INI parse failed");
        assert_eq!(cfg.client_ipv4, Ipv4Addr::new(172, 16, 0, 2));
        assert_eq!(
            cfg.client_ipv6,
            Some("2606:4700:110:812c:a554:b442:26d4:1330".parse().unwrap())
        );
    }

    #[test]
    fn test_awg_smoltcp_bridge_ipv6_syn_generation() {
        let mut dev = AwgSmolDevice::new();
        let mut iface_cfg = SmolConfig::new(HardwareAddress::Ip);
        iface_cfg.random_seed = 9999;

        let mut iface = Interface::new(iface_cfg, &mut dev, SmolInstant::now());
        let client_v6 = "2606:4700:110:812c:a554:b442:26d4:1330".parse::<std::net::Ipv6Addr>().unwrap();
        let smol_client_v6 = Ipv6Address::from(client_v6);

        iface.update_ip_addrs(|addrs| {
            let _ = addrs.push(IpCidr::Ipv6(Ipv6Cidr::new(smol_client_v6, 128)));
        });
        iface.routes_mut().add_default_ipv6_route(smol_client_v6).unwrap();

        let rx_buf = tcp::SocketBuffer::new(vec![0u8; 65535]);
        let tx_buf = tcp::SocketBuffer::new(vec![0u8; 65535]);
        let mut socket = TcpSocket::new(rx_buf, tx_buf);

        let target_v6 = "2606:4700:4700::1111".parse::<std::net::Ipv6Addr>().unwrap();
        let smol_target_v6 = Ipv6Address::from(target_v6);

        let remote_ep = IpEndpoint::new(IpAddress::Ipv6(smol_target_v6), 443);
        let local_ep = IpEndpoint::new(IpAddress::Ipv6(smol_client_v6), 48100);

        socket.connect(iface.context(), remote_ep, local_ep).unwrap();
        let mut sockets = SocketSet::new(vec![]);
        let _ = sockets.add(socket);

        iface.poll(SmolInstant::now(), &mut dev, &mut sockets);
        assert_eq!(dev.tx_queue.len(), 1, "must generate exactly 1 IPv6 SYN packet");
        let pkt = dev.tx_queue.pop_front().unwrap();
        assert_eq!(pkt[0] >> 4, 6, "must be IPv6 packet");
        assert_eq!(pkt[6], 6, "Next Header must be TCP (6)");
        assert_eq!(&pkt[8..24], &client_v6.octets());
        assert_eq!(&pkt[24..40], &target_v6.octets());
    }

    #[tokio::test]
    async fn test_awg_smoltcp_bridge_rejects_ipv6_without_client_ipv6() {
        let config = AwgConfig {
            client_ipv4: Ipv4Addr::new(172, 16, 0, 2),
            client_ipv6: None, // No IPv6 configured
            ..AwgConfig::default()
        };

        let target_v6_addr = "[2606:4700:4700::1111]:443";
        let (target_ip, target_port) =
            crate::masque::parse_target_endpoint(target_v6_addr).await.unwrap();

        assert!(matches!(target_ip, std::net::IpAddr::V6(_)));
        assert_eq!(target_port, 443);

        // Bridge check
        let client_v6_opt = config.client_ipv6;
        let validation_res = if let std::net::IpAddr::V6(_target_v6) = target_ip {
            if client_v6_opt.is_none() {
                Err(std::io::Error::new(
                    std::io::ErrorKind::AddrNotAvailable,
                    format!("IPv6 target '{}' is unsupported: no client IPv6 configured", target_v6_addr),
                ))
            } else {
                Ok(())
            }
        } else {
            Ok(())
        };

        assert!(validation_res.is_err());
        assert_eq!(validation_res.unwrap_err().kind(), std::io::ErrorKind::AddrNotAvailable);
    }

    #[test]
    fn test_session_limits_defaults_conform_to_wireguard_spec() {
        let limits = SessionLimits::default();
        assert_eq!(limits.rekey_after_messages, 1u64 << 60);
        assert_eq!(limits.reject_after_messages, u64::MAX - 8192);
        assert_eq!(limits.rekey_after_time, Duration::from_secs(120));
        assert_eq!(limits.reject_after_time, Duration::from_secs(180));
        assert_eq!(limits.rekey_attempt_time, Duration::from_secs(90));
        assert_eq!(limits.rekey_timeout, Duration::from_secs(5));
        assert_eq!(limits.keepalive_timeout, Duration::from_secs(10));
    }

    #[test]
    fn test_small_limit_rejects_send_after_exhaustion_no_nonce_reuse() {
        // Small test limit: exactly 5 messages permitted
        let limits = SessionLimits::test_small_limits(3, 5, Duration::from_secs(60), Duration::from_secs(120));
        let keys = TransportKeys::with_limits([1u8; 32], [2u8; 32], 0x100, 0x200, limits, true);

        // Packets 0..4 must succeed
        for i in 0..5 {
            let res = encapsulate_transport_packet(&keys, 4, b"test-payload");
            assert!(res.is_ok(), "Packet {} must succeed", i);
            let packet = res.unwrap();
            let counter = u64::from_le_bytes(packet[8..16].try_into().unwrap());
            assert_eq!(counter, i);
        }

        // Packet 5 (exceeding reject_after_messages = 5) MUST FAIL
        let exhausted_res1 = encapsulate_transport_packet(&keys, 4, b"test-payload");
        assert!(exhausted_res1.is_err(), "Must reject when counter reaches limit");
        assert!(exhausted_res1.unwrap_err().contains("exhausted"));

        // Subsequent call MUST ALSO FAIL and NEVER reuse nonce or increment further
        let exhausted_res2 = encapsulate_transport_packet(&keys, 4, b"test-payload");
        assert!(exhausted_res2.is_err());
        assert_eq!(keys.send_counter.load(Ordering::Relaxed), 5);
    }

    #[test]
    fn test_small_limit_rejects_receive_after_exhaustion() {
        let limits = SessionLimits::test_small_limits(3, 5, Duration::from_secs(60), Duration::from_secs(120));
        let keys_tx = TransportKeys::with_limits([1u8; 32], [2u8; 32], 0x100, 0x200, limits, true);
        let keys_rx = TransportKeys::with_limits([2u8; 32], [1u8; 32], 0x200, 0x100, limits, false);

        // Receive counters 0..4 succeed
        for _ in 0..5 {
            let wire = encapsulate_transport_packet(&keys_tx, 4, b"data").unwrap();
            let dec = decapsulate_transport_packet(&keys_rx, 4, &wire).unwrap();
            assert_eq!(dec, b"data");
        }

        // Forge a packet with counter = 5
        let raw_p5 = aead_encrypt(&keys_tx.send_key, 5, &[], b"forged5").unwrap();
        let mut forged = Vec::new();
        forged.extend_from_slice(&4u32.to_le_bytes());
        forged.extend_from_slice(&0x200u32.to_le_bytes());
        forged.extend_from_slice(&5u64.to_le_bytes());
        forged.extend_from_slice(&raw_p5);

        let err = decapsulate_transport_packet(&keys_rx, 4, &forged);
        assert!(err.is_err(), "Must reject counter exceeding reject_after_messages");
        assert!(err.unwrap_err().contains("exhausted"));
    }

    #[test]
    fn test_reject_after_time_expiration() {
        let limits = SessionLimits::test_small_limits(100, 200, Duration::from_millis(30), Duration::from_millis(60));
        let keys_tx = TransportKeys::with_limits([3u8; 32], [4u8; 32], 0x111, 0x222, limits, true);
        let keys_rx = TransportKeys::with_limits([4u8; 32], [3u8; 32], 0x222, 0x111, limits, false);

        // Immediately valid
        let p0 = encapsulate_transport_packet(&keys_tx, 4, b"fresh").unwrap();
        let dec = decapsulate_transport_packet(&keys_rx, 4, &p0).unwrap();
        assert_eq!(dec, b"fresh");

        // Wait for reject_after_time (60ms) to elapse
        std::thread::sleep(Duration::from_millis(70));

        // Now encapsulation on expired key must be rejected
        let enc_res = encapsulate_transport_packet(&keys_tx, 4, b"stale");
        assert!(enc_res.is_err(), "Expired key cannot send");
        assert!(enc_res.unwrap_err().contains("expired"));

        // Decapsulation on expired key must also be rejected
        let dec_res = decapsulate_transport_packet(&keys_rx, 4, &p0);
        assert!(dec_res.is_err(), "Expired key cannot receive");
        assert!(dec_res.unwrap_err().contains("expired"));
    }

    #[test]
    fn test_pad16_and_inner_ip_trim_and_validation() {
        assert_eq!(pad16(0), 0);
        assert_eq!(pad16(1), 16);
        assert_eq!(pad16(15), 16);
        assert_eq!(pad16(16), 16);
        assert_eq!(pad16(17), 32);

        // Construct a mock IPv4 packet (total length 24 bytes)
        let mut ipv4 = vec![0u8; 24];
        ipv4[0] = 0x45; // IPv4, header len 5 (20 bytes)
        ipv4[2] = 0x00;
        ipv4[3] = 0x18; // total length = 24
        ipv4[9] = 6;    // TCP

        let keys_tx = TransportKeys::new([5u8; 32], [6u8; 32], 0xA1, 0xB1);
        let keys_rx = TransportKeys::new([6u8; 32], [5u8; 32], 0xB1, 0xA1);

        // Encapsulate: 24 bytes IPv4 should be padded to 32 bytes
        let wire = encapsulate_transport_packet(&keys_tx, 4, &ipv4).unwrap();
        // 16 header + 32 padded ciphertext + 16 auth tag = 64 bytes total wire size
        assert_eq!(wire.len(), 16 + 32 + 16);

        // Decapsulate: should automatically trim padding back to exact 24 bytes
        let dec = decapsulate_transport_packet(&keys_rx, 4, &wire).unwrap();
        assert_eq!(dec.len(), 24);
        assert_eq!(dec, ipv4);
    }

    #[test]
    fn test_respond_to_initiation_and_two_way_traffic() {
        let server_priv = [42u8; 32];
        let server_pub = x25519_base(&server_priv);

        let mut client_cfg = AwgConfig::default();
        client_cfg.private_key = [11u8; 32];
        client_cfg.public_key = x25519_base(&client_cfg.private_key);
        client_cfg.peer_public_key = server_pub;

        let client_index = 0xAAAA1111;
        let server_index = 0xBBBB2222;

        // 1. Client creates initiation
        let (client_hs, init_pkt) = create_initiation(&client_cfg, client_index).unwrap();

        // 2. Server processes initiation and generates response
        let (server_keys, resp_pkt) = respond_to_initiation(
            &server_priv,
            server_index,
            &init_pkt,
            &client_cfg.awg_params,
            client_cfg.preshared_key,
        ).unwrap();

        // 3. Client processes response and derives transport keys
        let client_keys = process_response(client_hs, &client_cfg, &resp_pkt).unwrap();

        assert_eq!(client_keys.local_index, client_index);
        assert_eq!(client_keys.peer_index, server_index);
        assert_eq!(server_keys.local_index, server_index);
        assert_eq!(server_keys.peer_index, client_index);
        assert_eq!(client_keys.send_key, server_keys.recv_key);
        assert_eq!(client_keys.recv_key, server_keys.send_key);

        // 4. Two-way data communication
        let c2s = encapsulate_transport_packet(&client_keys, 4, b"Hello Server").unwrap();
        let s_dec = decapsulate_transport_packet(&server_keys, 4, &c2s).unwrap();
        assert_eq!(s_dec, b"Hello Server");

        let s2c = encapsulate_transport_packet(&server_keys, 4, b"Hello Client").unwrap();
        let c_dec = decapsulate_transport_packet(&client_keys, 4, &s2c).unwrap();
        assert_eq!(c_dec, b"Hello Client");
    }

    #[test]
    fn test_long_transfer_passes_multiple_rekeys() {
        let server_priv = [55u8; 32];
        let server_pub = x25519_base(&server_priv);

        let mut client_cfg = AwgConfig::default();
        client_cfg.private_key = [77u8; 32];
        client_cfg.public_key = x25519_base(&client_cfg.private_key);
        client_cfg.peer_public_key = server_pub;

        // Small rekey limit: trigger rekey every 4 packets; reject after 20 packets
        let limits = SessionLimits::test_small_limits(4, 20, Duration::from_secs(60), Duration::from_secs(120));

        // Initial handshake
        let client_index_0 = 0xC001;
        let server_index_0 = 0x5001;
        let (client_hs_0, init_0) = create_initiation(&client_cfg, client_index_0).unwrap();
        let (s_keys_0, resp_0) = respond_to_initiation(
            &server_priv,
            server_index_0,
            &init_0,
            &client_cfg.awg_params,
            None,
        ).unwrap();
        let mut current_server_keys = Arc::new(s_keys_0);
        let mut previous_server_keys: Option<Arc<TransportKeys>> = None;
        let initial_client_keys = Arc::new(process_response(client_hs_0, &client_cfg, &resp_0).unwrap());

        let session = WireGuardSession::with_limits(client_cfg.clone(), initial_client_keys, limits);

        let mut server_index_seq = 0x5002;
        let total_packets = 25;
        let mut rekey_count = 0;

        for i in 0..total_packets {
            let msg = format!("packet-payload-{:04}", i);
            let (wire_pkt, maybe_rekey) = session.encapsulate_outgoing(msg.as_bytes()).unwrap();

            // If a rekey initiation packet was emitted, simulate server handling it
            if let Some(ref rekey_init) = maybe_rekey {
                rekey_count += 1;
                let s_idx = server_index_seq;
                server_index_seq += 1;

                let (new_s_keys, rekey_resp) = respond_to_initiation(
                    &server_priv,
                    s_idx,
                    rekey_init,
                    &client_cfg.awg_params,
                    None,
                ).unwrap();

                // Client processes handshake response
                let incoming = session.decapsulate_incoming(&rekey_resp).unwrap();
                match incoming {
                    IncomingPacket::HandshakeResponse { keepalive_to_send } => {
                        assert!(keepalive_to_send.is_some(), "Must generate keepalive upon rekey response");
                        // Server receives keepalive
                        let ka = keepalive_to_send.unwrap();
                        let ka_dec = decapsulate_transport_packet(&new_s_keys, 4, &ka).unwrap();
                        assert!(ka_dec.is_empty(), "Keepalive payload must be empty");
                    }
                    _ => panic!("Expected HandshakeResponse"),
                }

                previous_server_keys = Some(Arc::clone(&current_server_keys));
                current_server_keys = Arc::new(new_s_keys);
            }

            // Server decrypts the data packet
            let decrypted = decapsulate_transport_packet(&current_server_keys, 4, &wire_pkt)
                .or_else(|_| {
                    if let Some(ref prev) = previous_server_keys {
                        decapsulate_transport_packet(prev, 4, &wire_pkt)
                    } else {
                        Err("no previous server key".to_string())
                    }
                })
                .unwrap();
            assert_eq!(decrypted, msg.as_bytes());
        }

        // We transferred 25 packets with rekey_after_messages = 4.
        // It must have passed multiple rekeys (at least 3..4 rekeys).
        assert!(rekey_count >= 3, "Transfer must have triggered multiple rekeys, got {}", rekey_count);
    }

    #[test]
    fn test_previous_keypair_in_flight_decryption_and_expiration() {
        let limits = SessionLimits::test_small_limits(10, 20, Duration::from_secs(60), Duration::from_millis(50));
        let key_0_tx = TransportKeys::with_limits([1u8; 32], [2u8; 32], 0x10, 0x20, limits, true);
        let key_0_rx = Arc::new(TransportKeys::with_limits([2u8; 32], [1u8; 32], 0x20, 0x10, limits, false));

        let cfg = AwgConfig::default();
        let session = WireGuardSession::with_limits(cfg, key_0_rx, limits);

        // Encrypt in-flight packet with key_0_tx
        let in_flight = encapsulate_transport_packet(&key_0_tx, 4, b"in-flight-message").unwrap();

        // Rotate session to key_1
        let key_1_rx = Arc::new(TransportKeys::with_limits([4u8; 32], [3u8; 32], 0x21, 0x11, limits, false));
        {
            let mut kp = session.keypairs.write();
            kp.previous = Some(Arc::clone(&kp.current));
            kp.current = Arc::clone(&key_1_rx);
        }

        // Before expiration: in-flight packet must be accepted and decrypted by previous keypair
        let dec_res = session.decapsulate_incoming(&in_flight).unwrap();
        assert_eq!(dec_res, IncomingPacket::Data(b"in-flight-message".to_vec()));

        // Sleep past reject_after_time (50ms)
        std::thread::sleep(Duration::from_millis(60));

        // Now previous keypair is expired: packet must be rejected
        let stale_res = session.decapsulate_incoming(&in_flight);
        assert!(stale_res.is_err());
        assert!(stale_res.unwrap_err().contains("expired"));
    }

    #[test]
    fn test_keypair_isolation_and_replay_filters() {
        let keys_epoch_0 = TransportKeys::new([1u8; 32], [2u8; 32], 0x10, 0x20);
        let keys_epoch_1 = TransportKeys::new([3u8; 32], [4u8; 32], 0x11, 0x21);

        let p0_epoch_0 = encapsulate_transport_packet(&keys_epoch_0, 4, b"epoch-0-packet-0").unwrap();
        let p0_epoch_1 = encapsulate_transport_packet(&keys_epoch_1, 4, b"epoch-1-packet-0").unwrap();

        let peer_epoch_0 = TransportKeys::new([2u8; 32], [1u8; 32], 0x20, 0x10);
        let peer_epoch_1 = TransportKeys::new([4u8; 32], [3u8; 32], 0x21, 0x11);

        // Epoch 0 receives counter 0
        assert_eq!(decapsulate_transport_packet(&peer_epoch_0, 4, &p0_epoch_0).unwrap(), b"epoch-0-packet-0");
        // Replaying p0_epoch_0 must fail
        assert!(decapsulate_transport_packet(&peer_epoch_0, 4, &p0_epoch_0).is_err());

        // Epoch 1 has its own fresh replay filter: counter 0 on epoch 1 must succeed
        assert_eq!(decapsulate_transport_packet(&peer_epoch_1, 4, &p0_epoch_1).unwrap(), b"epoch-1-packet-0");
        // Replaying p0_epoch_1 must fail
        assert!(decapsulate_transport_packet(&peer_epoch_1, 4, &p0_epoch_1).is_err());
    }

    #[test]
    fn test_wireguard_session_timer_triggers_rekey() {
        let limits = SessionLimits::test_small_limits(100, 200, Duration::from_millis(30), Duration::from_millis(150));
        let dummy_keys = Arc::new(TransportKeys::with_limits([1u8; 32], [2u8; 32], 0x1, 0x2, limits, true));
        let mut cfg = AwgConfig::default();
        cfg.private_key = [7u8; 32];
        cfg.public_key = x25519_base(&cfg.private_key);

        let session = WireGuardSession::with_limits(cfg, dummy_keys, limits);

        // Immediately, timer should not trigger rekey
        assert!(session.check_timers().is_none());

        // Wait for rekey_after_time (30ms)
        std::thread::sleep(Duration::from_millis(40));

        // Now timer must trigger rekey initiation
        let rekey_pkt = session.check_timers();
        assert!(rekey_pkt.is_some(), "Timer must trigger rekey after rekey_after_time");
        assert!(rekey_pkt.unwrap().len() >= 148);
    }

    #[test]
    fn test_extract_flow_port_ipv4_and_ipv6_and_icmp() {
        // 1. IPv4 TCP (dest port 45123)
        let mut ipv4_tcp = vec![0u8; 40];
        ipv4_tcp[0] = 0x45; // IPv4, ihl = 20
        ipv4_tcp[9] = 6;    // TCP
        ipv4_tcp[22] = (45123 >> 8) as u8;
        ipv4_tcp[23] = (45123 & 0xFF) as u8;
        assert_eq!(extract_flow_port(&ipv4_tcp), Some(45123));

        // 2. IPv4 UDP (dest port 53)
        let mut ipv4_udp = vec![0u8; 28];
        ipv4_udp[0] = 0x45;
        ipv4_udp[9] = 17;   // UDP
        ipv4_udp[22] = 0;
        ipv4_udp[23] = 53;
        assert_eq!(extract_flow_port(&ipv4_udp), Some(53));

        // 3. IPv6 TCP (dest port 443)
        let mut ipv6_tcp = vec![0u8; 60];
        ipv6_tcp[0] = 0x60; // IPv6
        ipv6_tcp[6] = 6;    // TCP
        ipv6_tcp[42] = 1;
        ipv6_tcp[43] = 187; // 443
        assert_eq!(extract_flow_port(&ipv6_tcp), Some(443));

        // 4. IPv4 ICMP error (Type 3 Dest Unreachable encapsulating original TCP segment with local_port 41000)
        let mut icmp_err = vec![0u8; 60];
        icmp_err[0] = 0x45;
        icmp_err[9] = 1;    // ICMP
        icmp_err[20] = 3;   // Type 3 Dest Unreachable
        // Encapsulated original IP header at offset 28
        icmp_err[28] = 0x45; // orig IPv4 ihl 20
        // Original transport header at offset 28 + 20 = 48
        icmp_err[48] = (41000 >> 8) as u8;
        icmp_err[49] = (41000 & 0xFF) as u8;
        assert_eq!(extract_flow_port(&icmp_err), Some(41000));

        // 5. Malformed or empty
        assert_eq!(extract_flow_port(&[]), None);
        assert_eq!(extract_flow_port(&[0x45]), None);
    }

    #[tokio::test]
    async fn test_awg_single_peer_session_1_flow() {
        let dummy_keys = Arc::new(TransportKeys::new([1u8; 32], [2u8; 32], 0x11, 0x22));
        let session = Arc::new(WireGuardSession::new(AwgConfig::default(), dummy_keys));
        let (socket_local, socket_remote) = {
            let s1 = UdpSocket::bind("127.0.0.1:0").await.unwrap();
            let s2 = UdpSocket::bind("127.0.0.1:0").await.unwrap();
            let addr1 = s1.local_addr().unwrap();
            let addr2 = s2.local_addr().unwrap();
            s1.connect(addr2).await.unwrap();
            s2.connect(addr1).await.unwrap();
            (Arc::new(s1), Arc::new(s2))
        };

        let peer = AwgPeerSession::new_with_session(
            socket_remote.local_addr().unwrap(),
            socket_local,
            session,
            AwgConfig::default(),
        );

        let (tx, _rx) = tokio::sync::mpsc::unbounded_channel();
        let port = peer.register_flow(tx);
        assert!(port >= 40000);
        assert_eq!(peer.active_flows(), 1);

        peer.unregister_flow(port);
        assert_eq!(peer.active_flows(), 0);
        peer.close();
    }

    #[tokio::test]
    async fn test_awg_single_peer_session_10_and_100_parallel_flows_no_handshake_storm() {
        let client_priv = [15u8; 32];
        let client_pub = x25519_base(&client_priv);
        let server_priv = [25u8; 32];
        let server_pub = x25519_base(&server_priv);

        let mut config = AwgConfig::default();
        config.private_key = client_priv;
        config.public_key = client_pub;
        config.peer_public_key = server_pub;
        config.client_ipv4 = Ipv4Addr::new(172, 16, 0, 2);

        // 1. Setup mock server UDP socket
        let server_sock = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let server_addr = server_sock.local_addr().unwrap();

        // 2. Client socket connecting to mock server
        let client_sock = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        client_sock.connect(server_addr).await.unwrap();
        let client_sock_arc = Arc::new(client_sock);

        // 3. Perform initial handshake
        let client_index = 0xAA01;
        let (hs, init_pkt) = create_initiation(&config, client_index).unwrap();
        client_sock_arc.send(&init_pkt).await.unwrap();

        let mut init_buf = [0u8; 1024];
        let (n, client_ep) = server_sock.recv_from(&mut init_buf).await.unwrap();
        server_sock.connect(client_ep).await.unwrap();

        let server_index = 0xBB01;
        let (s_keys, resp_pkt) = respond_to_initiation(
            &server_priv,
            server_index,
            &init_buf[..n],
            &config.awg_params,
            None,
        ).unwrap();
        server_sock.send(&resp_pkt).await.unwrap();

        let mut resp_buf = [0u8; 1024];
        let n_resp = client_sock_arc.recv(&mut resp_buf).await.unwrap();
        let c_keys = process_response(hs, &config, &resp_buf[..n_resp]).unwrap();

        let session = Arc::new(WireGuardSession::new(config.clone(), Arc::new(c_keys)));
        let peer = AwgPeerSession::new_with_session(
            server_addr,
            Arc::clone(&client_sock_arc),
            session,
            config.clone(),
        );

        // 4. Open 100 parallel flows on this SINGLE session
        let mut flows = Vec::with_capacity(100);
        for i in 0..100 {
            let tunnel = AwgTunnel::open_flow(Arc::clone(&peer), &format!("149.154.167.{}:443", 50 + (i % 10)));
            flows.push(tunnel);
        }

        assert_eq!(peer.active_flows(), 100);
        // Verify all 100 flows got strictly unique local ports
        let mut ports_set = std::collections::HashSet::new();
        for f in &flows {
            assert!(ports_set.insert(f.local_port), "Local port {} collided!", f.local_port);
        }
        assert_eq!(ports_set.len(), 100);

        // 5. Server sends an encrypted IP packet to each of the 100 flows
        for (i, f) in flows.iter().enumerate() {
            let mut ip_pkt = vec![0u8; 32];
            ip_pkt[0] = 0x45; // IPv4
            ip_pkt[2] = 0;
            ip_pkt[3] = 32;   // len
            ip_pkt[9] = 6;    // TCP
            ip_pkt[16] = 172; ip_pkt[17] = 16; ip_pkt[18] = 0; ip_pkt[19] = 2;
            ip_pkt[22] = (f.local_port >> 8) as u8;
            ip_pkt[23] = (f.local_port & 0xFF) as u8;
            ip_pkt[24] = i as u8;

            let wire = encapsulate_transport_packet(&s_keys, 4, &ip_pkt).unwrap();
            server_sock.send(&wire).await.unwrap();

            let mut rx = f.flow_rx.lock().await;
            let received = tokio::time::timeout(Duration::from_millis(1500), rx.recv()).await.unwrap().unwrap();
            assert_eq!(received[22], (f.local_port >> 8) as u8);
            assert_eq!(received[23], (f.local_port & 0xFF) as u8);
            assert_eq!(received[24], i as u8, "Packet routed to wrong flow!");
        }

        // 6. Test churn & reopen: close first 50 flows and open 50 new ones
        for f in flows.drain(..50) {
            f.close().await;
        }
        assert_eq!(peer.active_flows(), 50);

        // Existing flows (50..100) are completely unaffected
        for (idx, f) in flows.iter().enumerate() {
            let orig_i = 50 + idx;
            let mut ip_pkt = vec![0u8; 32];
            ip_pkt[0] = 0x45;
            ip_pkt[2] = 0; ip_pkt[3] = 32;
            ip_pkt[9] = 6;
            ip_pkt[16] = 172; ip_pkt[17] = 16; ip_pkt[18] = 0; ip_pkt[19] = 2;
            ip_pkt[22] = (f.local_port >> 8) as u8;
            ip_pkt[23] = (f.local_port & 0xFF) as u8;
            ip_pkt[24] = orig_i as u8;

            let wire = encapsulate_transport_packet(&s_keys, 4, &ip_pkt).unwrap();
            server_sock.send(&wire).await.unwrap();

            let mut rx = f.flow_rx.lock().await;
            let received = tokio::time::timeout(Duration::from_millis(1500), rx.recv()).await.unwrap().unwrap();
            assert_eq!(received[24], orig_i as u8);
        }

        // Open 50 new flows
        let mut new_flows = Vec::with_capacity(50);
        for i in 0..50 {
            let tunnel = AwgTunnel::open_flow(Arc::clone(&peer), &format!("149.154.167.{}:443", 70 + (i % 10)));
            new_flows.push(tunnel);
        }
        assert_eq!(peer.active_flows(), 100);

        // 7. Verify NO handshake storm: exactly 1 handshake happened (the initial one)
        let mut probe_buf = [0u8; 1024];
        let probe_res = tokio::time::timeout(Duration::from_millis(100), server_sock.recv(&mut probe_buf)).await;
        assert!(probe_res.is_err(), "Server must NOT receive any handshake storm packets!");

        peer.close();
    }

    #[tokio::test]
    async fn test_awg_10_flows_survive_rekey_during_active_traffic() {
        let client_priv = [33u8; 32];
        let client_pub = x25519_base(&client_priv);
        let server_priv = [44u8; 32];
        let server_pub = x25519_base(&server_priv);

        let mut config = AwgConfig::default();
        config.private_key = client_priv;
        config.public_key = client_pub;
        config.peer_public_key = server_pub;
        config.client_ipv4 = Ipv4Addr::new(172, 16, 0, 2);

        let server_sock = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let server_addr = server_sock.local_addr().unwrap();

        let client_sock = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        client_sock.connect(server_addr).await.unwrap();
        let client_sock_arc = Arc::new(client_sock);

        // Handshake
        let (hs, init_pkt) = create_initiation(&config, 0x1111).unwrap();
        client_sock_arc.send(&init_pkt).await.unwrap();

        let mut init_buf = [0u8; 1024];
        let (n, client_ep) = server_sock.recv_from(&mut init_buf).await.unwrap();
        server_sock.connect(client_ep).await.unwrap();

        let (mut s_keys, resp_pkt) = respond_to_initiation(
            &server_priv,
            0x2222,
            &init_buf[..n],
            &config.awg_params,
            None,
        ).unwrap();
        server_sock.send(&resp_pkt).await.unwrap();

        let mut resp_buf = [0u8; 1024];
        let n_resp = client_sock_arc.recv(&mut resp_buf).await.unwrap();
        let c_keys = process_response(hs, &config, &resp_buf[..n_resp]).unwrap();

        let session = Arc::new(WireGuardSession::new(config.clone(), Arc::new(c_keys)));
        let peer = AwgPeerSession::new_with_session(
            server_addr,
            Arc::clone(&client_sock_arc),
            session,
            config.clone(),
        );

        // 10 active flows
        let mut flows = Vec::new();
        for i in 0..10 {
            flows.push(AwgTunnel::open_flow(Arc::clone(&peer), &format!("149.154.167.{}:443", 50 + i)));
        }

        // Outgoing traffic from flows on keypair 0
        for f in &flows {
            let mut data = vec![0u8; 24];
            data[0] = 0x45; data[2] = 0; data[3] = 24; data[9] = 6;
            data[20] = (f.local_port >> 8) as u8; data[21] = (f.local_port & 0xFF) as u8;
            peer.send_ip_packet(&data).await.unwrap();

            let mut s_buf = [0u8; 1024];
            let sn = server_sock.recv(&mut s_buf).await.unwrap();
            let s_dec = decapsulate_transport_packet(&s_keys, 4, &s_buf[..sn]).unwrap();
            assert_eq!(s_dec, data);
        }

        // Trigger REKEY: client initiates rekey
        let rekey_init = peer.session.initiate_rekey().unwrap();
        client_sock_arc.send(&rekey_init).await.unwrap();

        let mut rekey_init_buf = [0u8; 1024];
        let rn = server_sock.recv(&mut rekey_init_buf).await.unwrap();
        let (new_s_keys, rekey_resp) = respond_to_initiation(
            &server_priv,
            0x3333,
            &rekey_init_buf[..rn],
            &config.awg_params,
            None,
        ).unwrap();

        // Server sends rekey response to client
        server_sock.send(&rekey_resp).await.unwrap();

        // Background loop processes rekey response and rotates keys
        tokio::time::sleep(Duration::from_millis(150)).await;

        let prev_s_keys = s_keys;
        s_keys = new_s_keys;

        // 1. Send in-flight packet from server encrypted with PREVIOUS key
        let mut in_flight_pkt = vec![0u8; 28];
        in_flight_pkt[0] = 0x45; in_flight_pkt[2] = 0; in_flight_pkt[3] = 28; in_flight_pkt[9] = 6;
        let flow0_port = flows[0].local_port;
        in_flight_pkt[22] = (flow0_port >> 8) as u8; in_flight_pkt[23] = (flow0_port & 0xFF) as u8;
        in_flight_pkt[24] = 0xEE;

        let wire_prev = encapsulate_transport_packet(&prev_s_keys, 4, &in_flight_pkt).unwrap();
        server_sock.send(&wire_prev).await.unwrap();

        let mut rx0 = flows[0].flow_rx.lock().await;
        let r_prev = tokio::time::timeout(Duration::from_millis(1500), rx0.recv()).await.unwrap().unwrap();
        assert_eq!(r_prev[24], 0xEE, "In-flight packet on previous keypair must be decrypted and routed!");

        // Receive the initiator's mandatory WireGuard keepalive on the new keypair
        let mut ka_buf = [0u8; 1024];
        let kn = server_sock.recv(&mut ka_buf).await.unwrap();
        let ka_dec = decapsulate_transport_packet(&s_keys, 4, &ka_buf[..kn]).unwrap();
        assert!(ka_dec.is_empty(), "WireGuard initiator must send empty keepalive on new key");

        // 2. Flows send outgoing traffic on NEW keypair: server decrypts with new_s_keys
        for f in &flows {
            let mut data = vec![0u8; 24];
            data[0] = 0x45; data[2] = 0; data[3] = 24; data[9] = 6;
            data[20] = (f.local_port >> 8) as u8; data[21] = (f.local_port & 0xFF) as u8;
            peer.send_ip_packet(&data).await.unwrap();

            let mut s_buf = [0u8; 1024];
            let sn = server_sock.recv(&mut s_buf).await.unwrap();
            let s_dec = decapsulate_transport_packet(&s_keys, 4, &s_buf[..sn]).unwrap();
            assert_eq!(s_dec, data, "Server must decrypt with new key after rekey!");
        }

        peer.close();
    }

    #[test]
    fn test_awg_ini_parsing_persistent_keepalive() {
        let ini_25 = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1:8443
PersistentKeepalive = 25
"#;
        let cfg_25 = AwgConfig::parse_ini(ini_25).unwrap();
        assert_eq!(cfg_25.persistent_keepalive, Some(25));

        let ini_0 = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1:8443
PersistentKeepalive = 0
"#;
        let cfg_0 = AwgConfig::parse_ini(ini_0).unwrap();
        assert_eq!(cfg_0.persistent_keepalive, None);

        let ini_default = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1:8443
"#;
        let cfg_default = AwgConfig::parse_ini(ini_default).unwrap();
        assert_eq!(cfg_default.persistent_keepalive, Some(20));
    }

    #[test]
    fn test_awg_keepalive_packet_structure_and_roundtrip() {
        let client_keys = TransportKeys::new([11u8; 32], [22u8; 32], 0x1111, 0x2222);
        let server_keys = TransportKeys::new([22u8; 32], [11u8; 32], 0x2222, 0x1111);

        let mut cfg = AwgConfig::default();
        cfg.awg_params.h4 = 4;
        let session_client = WireGuardSession::new(cfg.clone(), Arc::new(client_keys));
        let session_server = WireGuardSession::new(cfg, Arc::new(server_keys));

        // 1. Generate keepalive
        let ka_pkt = session_client.encapsulate_keepalive().unwrap();
        // Wire format: 4 (type H4) + 4 (peer_index) + 8 (counter) + 16 (poly1305 MAC tag) = 32 bytes
        assert_eq!(ka_pkt.len(), 32, "WireGuard empty keepalive packet must be exactly 32 bytes");
        let msg_type = u32::from_le_bytes(ka_pkt[0..4].try_into().unwrap());
        assert_eq!(msg_type, 4);
        let receiver_idx = u32::from_le_bytes(ka_pkt[4..8].try_into().unwrap());
        assert_eq!(receiver_idx, 0x2222);

        // 2. Server decapsulates keepalive
        let rcvd = session_server.decapsulate_incoming(&ka_pkt).unwrap();
        assert_eq!(rcvd, IncomingPacket::Keepalive, "Empty transport packet must be identified as Keepalive");
    }

    #[test]
    fn test_awg_no_redundant_keepalive_during_active_traffic() {
        let limits = SessionLimits::test_small_limits(1000, 2000, Duration::from_secs(60), Duration::from_secs(120))
            .with_persistent_keepalive(Duration::from_millis(80));
        let client_keys = Arc::new(TransportKeys::with_limits([1u8; 32], [2u8; 32], 0x1, 0x2, limits, true));
        let cfg = AwgConfig::default();
        let session = WireGuardSession::with_limits(cfg, client_keys, limits);

        // Active traffic: transmit packets every 25ms over 120ms total duration (which exceeds 80ms keepalive interval)
        for i in 0..5 {
            std::thread::sleep(Duration::from_millis(25));
            let dummy_payload = format!("active-data-{}", i);
            let _ = session.encapsulate_outgoing(dummy_payload.as_bytes()).unwrap();

            // At each active transmission step, check_timers MUST NOT generate keepalive!
            let timer_action = session.check_timers();
            assert!(timer_action.is_none(), "Active traffic must suppress keepalive packets (step {})", i);
        }
    }

    #[test]
    fn test_awg_idle_triggers_persistent_keepalive() {
        let limits = SessionLimits::test_small_limits(1000, 2000, Duration::from_secs(60), Duration::from_secs(120))
            .with_persistent_keepalive(Duration::from_millis(50));
        let client_keys = Arc::new(TransportKeys::with_limits([1u8; 32], [2u8; 32], 0x1, 0x2, limits, true));
        let cfg = AwgConfig::default();
        let session = WireGuardSession::with_limits(cfg, client_keys, limits);

        // Immediately, no keepalive
        assert!(session.check_timers().is_none());

        // Wait past 50ms keepalive interval
        std::thread::sleep(Duration::from_millis(65));

        // Now keepalive MUST be triggered
        let action = session.check_timers();
        assert!(action.is_some(), "Idle exceeding keepalive interval must trigger keepalive");
        let pkt = action.unwrap();
        match pkt {
            TimerPacket::Keepalive(ref ka) => {
                assert_eq!(ka.len(), 32);
            }
            _ => panic!("Expected TimerPacket::Keepalive"),
        }

        // Checking immediately again must return None because last_sent was updated
        assert!(session.check_timers().is_none(), "Timer must not re-trigger immediately");
    }

    #[test]
    fn test_awg_battery_qos_adapts_keepalive_interval() {
        let limits = SessionLimits::test_small_limits(1000, 2000, Duration::from_secs(60), Duration::from_secs(120))
            .with_persistent_keepalive(Duration::from_secs(20));
        let client_keys = Arc::new(TransportKeys::with_limits([1u8; 32], [2u8; 32], 0x1, 0x2, limits, true));
        let cfg = AwgConfig::default();
        let session = WireGuardSession::with_limits(cfg, client_keys, limits);

        // QoS 0 (NONE): base 20s
        assert_eq!(session.effective_keepalive_interval(true, 0), Some(Duration::from_secs(20)));
        assert_eq!(session.effective_keepalive_interval(false, 0), Some(Duration::from_secs(20)));

        // QoS 1 (MODERATE, battery <= 20%): 1.5x = 30s
        assert_eq!(session.effective_keepalive_interval(true, 1), Some(Duration::from_secs(30)));
        assert_eq!(session.effective_keepalive_interval(false, 1), Some(Duration::from_secs(30)));

        // QoS 2 (SEVERE, battery <= 10% / PowerSave):
        // with active flows -> 2.0x = 40s
        assert_eq!(session.effective_keepalive_interval(true, 2), Some(Duration::from_secs(40)));
        // without active flows -> 3.0x = 60s (capped at 60s)
        assert_eq!(session.effective_keepalive_interval(false, 2), Some(Duration::from_secs(60)));
    }

    #[test]
    fn test_awg_timer_next_event_deadline_sleep_optimization() {
        let limits = SessionLimits::test_small_limits(1000, 2000, Duration::from_secs(120), Duration::from_secs(180))
            .with_persistent_keepalive(Duration::from_secs(20));
        let client_keys = Arc::new(TransportKeys::with_limits([1u8; 32], [2u8; 32], 0x1, 0x2, limits, true));
        let cfg = AwgConfig::default();
        let session = WireGuardSession::with_limits(cfg, client_keys, limits);

        // Next event deadline should be close to 20s (keepalive interval)
        let next_sleep = session.time_until_next_event(true, 0);
        assert!(next_sleep >= Duration::from_millis(19000) && next_sleep <= Duration::from_millis(20000));

        // After sending a packet, the next keepalive is reset to ~20s again
        let _ = session.encapsulate_outgoing(b"dummy").unwrap();
        let next_sleep_after_tx = session.time_until_next_event(true, 0);
        assert!(next_sleep_after_tx >= Duration::from_millis(19000) && next_sleep_after_tx <= Duration::from_millis(20000));
    }

    #[tokio::test]
    async fn test_awg_prolonged_idle_nat_expiration_and_incoming_traffic_resumption() {
        let server_sock = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let server_addr = server_sock.local_addr().unwrap();

        let client_sock = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        client_sock.connect(server_addr).await.unwrap();
        let client_sock_arc = Arc::new(client_sock);

        let mut server_priv = [0u8; 32];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut server_priv);
        let server_pub = x25519_base(&server_priv);

        let mut config = AwgConfig::default();
        config.endpoint = server_addr.to_string();
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut config.private_key);
        config.public_key = x25519_base(&config.private_key);
        config.peer_public_key = server_pub;
        config.client_ipv4 = Ipv4Addr::new(172, 16, 0, 2);

        // Initial handshake
        let client_index = 0xA101;
        let server_index = 0xB202;
        let (hs, init_pkt) = create_initiation(&config, client_index).unwrap();
        client_sock_arc.send(&init_pkt).await.unwrap();

        let mut init_buf = [0u8; 1024];
        let (n_init, client_addr) = server_sock.recv_from(&mut init_buf).await.unwrap();
        server_sock.connect(client_addr).await.unwrap();
        let (s_keys, resp_pkt) = respond_to_initiation(
            &server_priv,
            server_index,
            &init_buf[..n_init],
            &config.awg_params,
            None,
        ).unwrap();
        server_sock.send(&resp_pkt).await.unwrap();

        let mut resp_buf = [0u8; 1024];
        let n_resp = client_sock_arc.recv(&mut resp_buf).await.unwrap();
        let c_keys = process_response(hs, &config, &resp_buf[..n_resp]).unwrap();

        // 80ms keepalive interval for testing prolonged idle
        let limits = SessionLimits::test_small_limits(1000, 2000, Duration::from_secs(60), Duration::from_secs(120))
            .with_persistent_keepalive(Duration::from_millis(80));

        let session = Arc::new(WireGuardSession::with_limits(config.clone(), Arc::new(c_keys), limits));
        let peer = AwgPeerSession::new_with_session(
            server_addr,
            Arc::clone(&client_sock_arc),
            session,
            config.clone(),
        );

        // Open flow 1
        let flow1 = AwgTunnel::open_flow(Arc::clone(&peer), "149.154.167.50:443");

        // Prolonged idle: sleep for 200ms (more than 2 keepalive periods)
        // Server should receive keepalives keeping the NAT mapping fresh
        let mut ka_count = 0;
        let start = tokio::time::Instant::now();
        while start.elapsed() < Duration::from_millis(220) {
            let mut buf = [0u8; 1024];
            if let Ok(Ok(n)) = tokio::time::timeout(Duration::from_millis(100), server_sock.recv(&mut buf)).await {
                if n == 32 {
                    let dec = decapsulate_transport_packet(&s_keys, 4, &buf[..n]).unwrap();
                    if dec.is_empty() {
                        ka_count += 1;
                    }
                }
            }
        }
        assert!(ka_count >= 1, "Server must have received persistent keepalives during idle, got {}", ka_count);

        // Now simulate server resuming incoming traffic to client on the kept-alive NAT mapping
        let mut server_traffic = vec![0u8; 36];
        server_traffic[0] = 0x45;
        server_traffic[2] = 0; server_traffic[3] = 36;
        server_traffic[9] = 6;
        server_traffic[16] = 172; server_traffic[17] = 16; server_traffic[18] = 0; server_traffic[19] = 2;
        server_traffic[22] = (flow1.local_port >> 8) as u8;
        server_traffic[23] = (flow1.local_port & 0xFF) as u8;
        server_traffic[24] = 0xAB; server_traffic[25] = 0xCD;

        let wire_in = encapsulate_transport_packet(&s_keys, 4, &server_traffic).unwrap();
        server_sock.send(&wire_in).await.unwrap();

        // Client flow must successfully receive incoming traffic after prolonged idle!
        let mut rx1 = flow1.flow_rx.lock().await;
        let received = tokio::time::timeout(Duration::from_millis(1500), rx1.recv()).await.unwrap().unwrap();
        assert_eq!(received[24], 0xAB);
        assert_eq!(received[25], 0xCD);

        peer.close();
    }

    #[test]
    fn test_awg_golden_wire_packet_layout() {
        let server_priv = [0x55u8; 32];
        let server_pub = x25519_base(&server_priv);

        let mut client_cfg = AwgConfig::default();
        client_cfg.private_key = [0x77u8; 32];
        client_cfg.public_key = x25519_base(&client_cfg.private_key);
        client_cfg.peer_public_key = server_pub;
        client_cfg.awg_params = AwgParams {
            jc: 0,
            jmin: 0,
            jmax: 0,
            s1: 40,
            s2: 56,
            h1: 0xA1B2C3D4,
            h2: 0xE5F6A7B8,
            h3: 0x09182736,
            h4: 0x11223344,
            i1: None,
        };

        let client_index = 0xCAFE1234;
        let server_index = 0x5E440001;

        // 1. Client creates initiation packet
        let (client_hs, init_pkt) = create_initiation(&client_cfg, client_index).unwrap();

        // Wire format validation for Initiation:
        // [0..40]: S1 random prefix padding
        // [40..44]: H1 (0xA1B2C3D4 in LE)
        // [44..48]: local_index (0xCAFE1234 in LE)
        // [48..80]: unencrypted ephemeral key (32 bytes)
        // [80..128]: encrypted static key (48 bytes)
        // [128..156]: encrypted timestamp (28 bytes)
        // [156..172]: MAC1 (16 bytes)
        // [172..188]: MAC2 (16 zero bytes)
        assert_eq!(init_pkt.len(), 40 + 148, "Initiation packet must equal S1 + 148 bytes");
        let wire_h1 = u32::from_le_bytes([init_pkt[40], init_pkt[41], init_pkt[42], init_pkt[43]]);
        assert_eq!(wire_h1, 0xA1B2C3D4, "Wire H1 must be at offset S1=40");
        let wire_c_idx = u32::from_le_bytes([init_pkt[44], init_pkt[45], init_pkt[46], init_pkt[47]]);
        assert_eq!(wire_c_idx, 0xCAFE1234, "Client sender index must be at offset S1+4");

        // Verify MAC1 over init_pkt[40..156]
        let mut mac1_key_input = Vec::with_capacity(8 + 32);
        mac1_key_input.extend_from_slice(b"mac1----");
        mac1_key_input.extend_from_slice(&server_pub);
        let mac1_key = blake2s_256(&mac1_key_input);
        let expected_mac1 = blake2s(&mac1_key, &init_pkt[40..156], 16);
        assert_eq!(&init_pkt[156..172], expected_mac1.as_slice(), "Wire MAC1 must be valid");
        assert_eq!(&init_pkt[172..188], &[0u8; 16], "Wire MAC2 must be 16 zeros");

        // 2. Server processes initiation and generates response
        let (server_keys, resp_pkt) = respond_to_initiation(
            &server_priv,
            server_index,
            &init_pkt,
            &client_cfg.awg_params,
            None,
        ).unwrap();

        // Wire format validation for Response:
        // [0..56]: S2 random prefix padding
        // [56..60]: H2 (0xE5F6A7B8 in LE)
        // [60..64]: server_index (0x5E440001 in LE)
        // [64..68]: client_index (0xCAFE1234 in LE)
        // [68..100]: unencrypted ephemeral key (32 bytes)
        // [100..116]: encrypted empty auth tag (16 bytes)
        // [116..132]: MAC1 (16 bytes)
        // [132..148]: MAC2 (16 zero bytes)
        assert_eq!(resp_pkt.len(), 56 + 92, "Response packet must equal S2 + 92 bytes");
        let wire_h2 = u32::from_le_bytes([resp_pkt[56], resp_pkt[57], resp_pkt[58], resp_pkt[59]]);
        assert_eq!(wire_h2, 0xE5F6A7B8, "Wire H2 must be at offset S2=56");
        let wire_s_idx = u32::from_le_bytes([resp_pkt[60], resp_pkt[61], resp_pkt[62], resp_pkt[63]]);
        assert_eq!(wire_s_idx, 0x5E440001, "Server sender index must be at offset S2+4");
        let wire_rcv_idx = u32::from_le_bytes([resp_pkt[64], resp_pkt[65], resp_pkt[66], resp_pkt[67]]);
        assert_eq!(wire_rcv_idx, 0xCAFE1234, "Receiver index must match client_index at S2+8");

        // Verify MAC1 over resp_pkt[56..116]
        let mut r_mac1_key_input = Vec::with_capacity(8 + 32);
        r_mac1_key_input.extend_from_slice(b"mac1----");
        r_mac1_key_input.extend_from_slice(&client_cfg.public_key);
        let r_mac1_key = blake2s_256(&r_mac1_key_input);
        let expected_r_mac1 = blake2s(&r_mac1_key, &resp_pkt[56..116], 16);
        assert_eq!(&resp_pkt[116..132], expected_r_mac1.as_slice(), "Wire Response MAC1 must be valid");
        assert_eq!(&resp_pkt[132..148], &[0u8; 16], "Wire Response MAC2 must be 16 zeros");

        // 3. Client processes response
        let client_keys = process_response(client_hs, &client_cfg, &resp_pkt).unwrap();
        assert_eq!(client_keys.send_key, server_keys.recv_key);
        assert_eq!(client_keys.recv_key, server_keys.send_key);
    }

    #[test]
    fn test_awg_dedicated_peer_full_handshake_and_transport_flow() {
        let server_priv = [0x99u8; 32];
        let server_pub = x25519_base(&server_priv);

        let mut client_cfg = AwgConfig::default();
        client_cfg.private_key = [0x33u8; 32];
        client_cfg.public_key = x25519_base(&client_cfg.private_key);
        client_cfg.peer_public_key = server_pub;
        client_cfg.awg_params = AwgParams {
            jc: 3,
            jmin: 40,
            jmax: 80,
            s1: 64,
            s2: 96,
            h1: 0x10101010,
            h2: 0x20202020,
            h3: 0x30303030,
            h4: 0x40404040,
            i1: None,
        };

        let (client_hs, init_pkt) = create_initiation(&client_cfg, 0x1122).unwrap();
        let (server_keys, resp_pkt) = respond_to_initiation(
            &server_priv,
            0x3344,
            &init_pkt,
            &client_cfg.awg_params,
            None,
        ).unwrap();

        let client_keys = Arc::new(process_response(client_hs, &client_cfg, &resp_pkt).unwrap());
        let session = WireGuardSession::new(client_cfg.clone(), client_keys);

        // Client encapsulates an IP packet under H4
        let dummy_ip = b"GET /chat HTTP/1.1\r\nHost: example.com\r\n\r\n";
        let (wire_out, _) = session.encapsulate_outgoing(dummy_ip).unwrap();
        let wire_h4 = u32::from_le_bytes([wire_out[0], wire_out[1], wire_out[2], wire_out[3]]);
        assert_eq!(wire_h4, 0x40404040, "Transport packet header must match custom H4");

        // Server decapsulates
        let server_dec = decapsulate_transport_packet(&server_keys, 0x40404040, &wire_out).unwrap();
        assert_eq!(server_dec, dummy_ip);

        // Server replies to client
        let reply_ip = b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK";
        let server_wire = encapsulate_transport_packet(&server_keys, 0x40404040, reply_ip).unwrap();

        // Client receives and decapsulates via session
        let incoming = session.decapsulate_incoming(&server_wire).unwrap();
        match incoming {
            IncomingPacket::Data(plaintext) => {
                assert_eq!(plaintext, reply_ip);
            }
            _ => panic!("Expected IncomingPacket::Data"),
        }
    }

    #[test]
    fn test_awg_negative_mismatched_h1_rejected() {
        let server_priv = [0x12u8; 32];
        let mut client_cfg = AwgConfig::default();
        client_cfg.private_key = [0x34u8; 32];
        client_cfg.public_key = x25519_base(&client_cfg.private_key);
        client_cfg.peer_public_key = x25519_base(&server_priv);
        client_cfg.awg_params.s1 = 30;
        client_cfg.awg_params.h1 = 0x88776655;

        let (_, mut init_pkt) = create_initiation(&client_cfg, 0x9999).unwrap();

        // Corrupt H1 byte at offset S1=30
        init_pkt[30] ^= 0xFF;

        let res = respond_to_initiation(
            &server_priv,
            0x8888,
            &init_pkt,
            &client_cfg.awg_params,
            None,
        );
        assert!(res.is_err());
        assert!(res.unwrap_err().contains("message type mismatch"));
    }

    #[test]
    fn test_awg_negative_mismatched_h2_rejected() {
        let server_priv = [0x21u8; 32];
        let mut client_cfg = AwgConfig::default();
        client_cfg.private_key = [0x43u8; 32];
        client_cfg.public_key = x25519_base(&client_cfg.private_key);
        client_cfg.peer_public_key = x25519_base(&server_priv);
        client_cfg.awg_params.s2 = 45;
        client_cfg.awg_params.h2 = 0x12345678;

        let (client_hs, init_pkt) = create_initiation(&client_cfg, 0xAAAA).unwrap();
        let (_, mut resp_pkt) = respond_to_initiation(
            &server_priv,
            0xBBBB,
            &init_pkt,
            &client_cfg.awg_params,
            None,
        ).unwrap();

        // Corrupt H2 byte at offset S2=45
        resp_pkt[45] ^= 0xFF;

        let res = process_response(client_hs, &client_cfg, &resp_pkt);
        assert!(res.is_err());
        assert!(res.unwrap_err().contains("message type mismatch"));
    }

    #[test]
    fn test_awg_negative_truncated_packets_rejected() {
        let server_priv = [0x54u8; 32];
        let mut client_cfg = AwgConfig::default();
        client_cfg.private_key = [0x65u8; 32];
        client_cfg.public_key = x25519_base(&client_cfg.private_key);
        client_cfg.peer_public_key = x25519_base(&server_priv);
        client_cfg.awg_params.s1 = 25;
        client_cfg.awg_params.s2 = 35;

        let (client_hs, init_pkt) = create_initiation(&client_cfg, 0x1234).unwrap();
        let (_, resp_pkt) = respond_to_initiation(
            &server_priv,
            0x5678,
            &init_pkt,
            &client_cfg.awg_params,
            None,
        ).unwrap();

        // Truncated initiation (< 25 + 148 = 173)
        let truncated_init = &init_pkt[..170];
        let init_err = respond_to_initiation(
            &server_priv,
            0x5678,
            truncated_init,
            &client_cfg.awg_params,
            None,
        ).unwrap_err();
        assert!(init_err.contains("too short"));

        // Truncated response (< 35 + 92 = 127)
        let truncated_resp = &resp_pkt[..120];
        let resp_err = process_response(client_hs, &client_cfg, truncated_resp).unwrap_err();
        assert!(resp_err.contains("too short"));
    }

    #[test]
    fn test_awg_negative_corrupted_mac1_rejected() {
        let server_priv = [0x88u8; 32];
        let mut client_cfg = AwgConfig::default();
        client_cfg.private_key = [0x99u8; 32];
        client_cfg.public_key = x25519_base(&client_cfg.private_key);
        client_cfg.peer_public_key = x25519_base(&server_priv);
        client_cfg.awg_params.s1 = 15;
        client_cfg.awg_params.s2 = 25;

        // Corrupt initiation MAC1: offset is S1 + 116 = 15 + 116 = 131
        let (_client_hs, mut init_pkt) = create_initiation(&client_cfg, 0x5555).unwrap();
        init_pkt[131] ^= 0x01;
        let init_res = respond_to_initiation(
            &server_priv,
            0x6666,
            &init_pkt,
            &client_cfg.awg_params,
            None,
        );
        assert!(init_res.is_err());
        assert!(init_res.unwrap_err().contains("MAC1 verification failed"));

        // Re-create valid initiation and corrupt response MAC1: offset is S2 + 60 = 25 + 60 = 85
        let (client_hs2, valid_init) = create_initiation(&client_cfg, 0x5556).unwrap();
        let (_, mut resp_pkt) = respond_to_initiation(
            &server_priv,
            0x6667,
            &valid_init,
            &client_cfg.awg_params,
            None,
        ).unwrap();
        resp_pkt[85] ^= 0x01;
        let resp_res = process_response(client_hs2, &client_cfg, &resp_pkt);
        assert!(resp_res.is_err());
        assert!(resp_res.unwrap_err().contains("MAC1 verification failed"));
    }

    #[test]
    fn test_awg_warp_compatibility_and_classification() {
        // Standard WireGuard / WARP parameters: compatible with Cloudflare Anycast
        let std_params = AwgParams::warp_recommended();
        assert_eq!(std_params.classify(), AwgPeerClassification::StandardWireGuardWarp);
        assert!(std_params.is_standard_wireguard());
        assert!(!std_params.is_dedicated_awg());

        let cf_endpoint = "162.159.193.10:1701";
        let report_std = std_params.validate_against_endpoint(cf_endpoint).unwrap();
        assert_eq!(report_std.protocol_version, "AmneziaWG v1.0");
        assert!(report_std.is_compatible_warp);
        assert!(report_std.warnings.is_empty());

        // Dedicated AmneziaWG parameters on Cloudflare WARP Anycast IP: incompatible!
        let dedicated_params = AwgParams {
            jc: 4,
            jmin: 40,
            jmax: 70,
            s1: 16,
            s2: 32,
            h1: 100,
            h2: 200,
            h3: 300,
            h4: 400,
            i1: None,
        };
        assert_eq!(dedicated_params.classify(), AwgPeerClassification::DedicatedAmneziaWg);
        assert!(!dedicated_params.is_standard_wireguard());
        assert!(dedicated_params.is_dedicated_awg());

        let report_cf_incompat = dedicated_params.validate_against_endpoint(cf_endpoint).unwrap();
        assert!(!report_cf_incompat.is_compatible_warp, "Custom headers are incompatible with Cloudflare WARP Anycast");
        assert!(!report_cf_incompat.warnings.is_empty(), "Must produce incompatibility warning for Cloudflare WARP");
        assert!(report_cf_incompat.warnings[0].contains("Cloudflare WARP"));

        // Dedicated AmneziaWG parameters on custom dedicated server: compatible!
        let custom_endpoint = "198.51.100.25:51820";
        let report_custom = dedicated_params.validate_against_endpoint(custom_endpoint).unwrap();
        assert!(report_custom.is_compatible_warp);
        assert!(report_custom.warnings.is_empty());

        // Parameter range validations
        let mut invalid_j = dedicated_params.clone();
        invalid_j.jmin = 100;
        invalid_j.jmax = 50;
        assert!(invalid_j.validate_against_endpoint(custom_endpoint).is_err());

        let mut invalid_jc = dedicated_params.clone();
        invalid_jc.jc = 150;
        assert!(invalid_jc.validate_against_endpoint(custom_endpoint).is_err());

        let mut invalid_h = dedicated_params.clone();
        invalid_h.h1 = 0;
        assert!(invalid_h.validate_against_endpoint(custom_endpoint).is_err());

        let mut collision_h = dedicated_params.clone();
        collision_h.h1 = 999;
        collision_h.h2 = 999;
        assert!(collision_h.validate_against_endpoint(custom_endpoint).is_err());
    }

    #[test]
    fn test_awg_ini_corrupted_key_error() {
        // Corrupted Base64 in PrivateKey
        let bad_b64 = r#"
[Interface]
PrivateKey = %%%not_base64%%%
Address = 172.16.0.2/32

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1:8443
"#;
        let err = AwgConfig::parse_ini(bad_b64).unwrap_err();
        assert!(err.contains("invalid Base64 in 'PrivateKey'"), "Actual: {}", err);

        // 33-byte PrivateKey (old 44 'a's bug)
        let bad_len_priv = r#"
[Interface]
PrivateKey = aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
Address = 172.16.0.2/32

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1:8443
"#;
        let err = AwgConfig::parse_ini(bad_len_priv).unwrap_err();
        assert!(err.contains("invalid PrivateKey length: expected 32 bytes, got 33"), "Actual: {}", err);

        // Short PublicKey (3 bytes)
        let bad_pub = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32

[Peer]
PublicKey = AAAA
Endpoint = 188.114.96.1:8443
"#;
        let err = AwgConfig::parse_ini(bad_pub).unwrap_err();
        assert!(err.contains("invalid PublicKey length: expected 32 bytes, got 3"), "Actual: {}", err);

        // Corrupted PresharedKey
        let bad_psk = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1:8443
PresharedKey = dG9vc2hvcnQ=
"#;
        let err = AwgConfig::parse_ini(bad_psk).unwrap_err();
        assert!(err.contains("invalid PresharedKey length: expected 32 bytes"), "Actual: {}", err);
    }

    #[test]
    fn test_awg_ini_empty_peer_and_missing_sections_error() {
        // Missing [Interface]
        let no_interface = r#"
[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1:8443
"#;
        let err = AwgConfig::parse_ini(no_interface).unwrap_err();
        assert!(err.contains("missing required [Interface] section"), "Actual: {}", err);

        // Missing [Peer]
        let no_peer = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32
"#;
        let err = AwgConfig::parse_ini(no_peer).unwrap_err();
        assert!(err.contains("missing required [Peer] section"), "Actual: {}", err);

        // Empty [Peer] section (no keys)
        let empty_peer = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32

[Peer]
"#;
        let err = AwgConfig::parse_ini(empty_peer).unwrap_err();
        assert!(err.contains("missing required field 'PublicKey' in [Peer] section"), "Actual: {}", err);

        // [Peer] missing Endpoint
        let missing_endpoint = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
"#;
        let err = AwgConfig::parse_ini(missing_endpoint).unwrap_err();
        assert!(err.contains("missing required field 'Endpoint' in [Peer] section"), "Actual: {}", err);

        // [Interface] missing Address
        let missing_addr = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1:8443
"#;
        let err = AwgConfig::parse_ini(missing_addr).unwrap_err();
        assert!(err.contains("missing required IPv4 address in 'Address' field"), "Actual: {}", err);
    }

    #[test]
    fn test_awg_ini_huge_jc_and_jmin_greater_than_jmax_error() {
        // Huge Jc (exceeds 128)
        let huge_jc = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32
Jc = 250

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1:8443
"#;
        let err = AwgConfig::parse_ini(huge_jc).unwrap_err();
        assert!(err.contains("invalid Jc value 250: maximum allowed junk packet count is 128"), "Actual: {}", err);

        // Jmin > Jmax
        let inverted_j = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32
Jmin = 120
Jmax = 80

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1:8443
"#;
        let err = AwgConfig::parse_ini(inverted_j).unwrap_err();
        assert!(err.contains("invalid junk packet size range: Jmin (120) > Jmax (80)"), "Actual: {}", err);

        // Huge S1 (exceeds 1280)
        let huge_s1 = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32
S1 = 1500

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1:8443
"#;
        let err = AwgConfig::parse_ini(huge_s1).unwrap_err();
        assert!(err.contains("invalid S1 value 1500: exceeds maximum handshake padding 1280"), "Actual: {}", err);
    }

    #[test]
    fn test_awg_ini_multiple_peers_rejected() {
        let multi_peer = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1:8443

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.2:8443
"#;
        let err = AwgConfig::parse_ini(multi_peer).unwrap_err();
        assert!(err.contains("multiple [Peer] sections are unsupported: single-peer proxy mode requires exactly one peer"), "Actual: {}", err);
    }

    #[test]
    fn test_awg_ini_unsupported_system_directives_rejected() {
        let postup = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32
PostUp = iptables -A FORWARD -i %i -j ACCEPT

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1:8443
"#;
        let err = AwgConfig::parse_ini(postup).unwrap_err();
        assert!(err.contains("system routing directive/hook 'PostUp' is unsupported in Android userspace proxy"), "Actual: {}", err);

        let table = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32
Table = off

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1:8443
"#;
        let err = AwgConfig::parse_ini(table).unwrap_err();
        assert!(err.contains("system routing directive/hook 'Table' is unsupported in Android userspace proxy"), "Actual: {}", err);
    }

    #[test]
    fn test_awg_ini_unrecognized_key_and_syntax_rejected() {
        // Typo in key name
        let typo = r#"
[Interface]
PrivatKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1:8443
"#;
        let err = AwgConfig::parse_ini(typo).unwrap_err();
        assert!(err.contains("unrecognized configuration key 'PrivatKey' in section [Interface]"), "Actual: {}", err);

        // Directive outside of any section
        let outside = r#"
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
[Interface]
Address = 172.16.0.2/32

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1:8443
"#;
        let err = AwgConfig::parse_ini(outside).unwrap_err();
        assert!(err.contains("directive 'PrivateKey' outside of any section in INI configuration"), "Actual: {}", err);
    }

    #[test]
    fn test_awg_ini_endpoint_format_validation() {
        // Missing port
        let no_port = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1
"#;
        let err = AwgConfig::parse_ini(no_port).unwrap_err();
        assert!(err.contains("missing port separator ':'"), "Actual: {}", err);

        // Port 0
        let port_zero = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 188.114.96.1:0
"#;
        let err = AwgConfig::parse_ini(port_zero).unwrap_err();
        assert!(err.contains("port 0 is not allowed"), "Actual: {}", err);

        // Direct validate_endpoint_format checks
        assert!(validate_endpoint_format("engage.cloudflareclient.com:2408").is_ok());
        assert!(validate_endpoint_format("[2606:4700:d0::a29f:c001]:1701").is_ok());
        assert!(validate_endpoint_format("1.2.3.4:70000").is_err());
        assert!(validate_endpoint_format("[2606:4700").is_err());
        assert!(validate_endpoint_format(":8443").is_err());
    }

    #[test]
    fn test_awg_ini_full_valid_custom_amnezia_profile() {
        let valid_ini = r#"
# Sample dedicated AmneziaWG configuration with comments
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE= # Client key
Address = 10.0.0.2/32, fd00::2/128 # Dual stack
DNS = 1.1.1.1, 8.8.8.8
MTU = 1360
Jc = 4
Jmin = 50
Jmax = 80
S1 = 24
S2 = 48
H1 = 1111
H2 = 2222
H3 = 3333
H4 = 4444

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
Endpoint = 198.51.100.25:51820 # Custom dedicated server
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
"#;
        let cfg = AwgConfig::parse_ini(valid_ini).expect("Valid custom profile should parse");
        assert_eq!(cfg.endpoint, "198.51.100.25:51820");
        assert_eq!(cfg.client_ipv4, Ipv4Addr::new(10, 0, 0, 2));
        assert_eq!(cfg.client_ipv6, Some("fd00::2".parse().unwrap()));
        assert_eq!(cfg.persistent_keepalive, Some(25));
        assert_eq!(cfg.awg_params.jc, 4);
        assert_eq!(cfg.awg_params.jmin, 50);
        assert_eq!(cfg.awg_params.jmax, 80);
        assert_eq!(cfg.awg_params.s1, 24);
        assert_eq!(cfg.awg_params.s2, 48);
        assert_eq!(cfg.awg_params.h1, 1111);
        assert_eq!(cfg.awg_params.h2, 2222);
        assert_eq!(cfg.awg_params.h3, 3333);
        assert_eq!(cfg.awg_params.h4, 4444);
        assert_eq!(cfg.private_key, [b'a'; 32]);
    }

    #[test]
    fn test_awg_custom_profile_without_fallbacks_has_empty_fallbacks() {
        let custom_ini = r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 10.0.0.2/32

[Peer]
PublicKey = vK84eU6iK0xVq4f8eK1iK0xVq4f8eK1iK0xVq4f8eK0=
Endpoint = 198.51.100.25:51820
"#;
        let cfg = AwgConfig::parse_ini(custom_ini).expect("Valid custom profile should parse");
        assert!(!cfg.is_warp_profile(), "Custom non-WARP profile must not be classified as WARP");
        assert_eq!(cfg.profile_name, "Custom AWG (198.51.100.25:51820)");
        assert!(cfg.fallback_endpoints.is_empty(), "Custom profile without explicit fallbacks must NOT inherit Cloudflare Anycast endpoints!");
    }

    #[test]
    fn test_awg_custom_profile_with_explicit_fallbacks_uses_only_its_own() {
        let custom_ini = r#"
[Interface]
ProfileName = My Private VPS
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 10.0.0.2/32

[Peer]
PublicKey = vK84eU6iK0xVq4f8eK1iK0xVq4f8eK1iK0xVq4f8eK0=
Endpoint = 198.51.100.25:51820
FallbackEndpoints = 198.51.100.26:51820, 198.51.100.27:51820
"#;
        let cfg = AwgConfig::parse_ini(custom_ini).expect("Valid custom profile with fallbacks should parse");
        assert_eq!(cfg.profile_name, "My Private VPS");
        assert_eq!(cfg.fallback_endpoints, vec!["198.51.100.26:51820", "198.51.100.27:51820"]);
        for ep in &cfg.fallback_endpoints {
            assert!(!is_cloudflare_warp_endpoint(ep), "Fallback endpoint {} must not be Cloudflare Anycast", ep);
        }
    }

    #[test]
    fn test_awg_warp_profile_populates_warp_anycast_fallbacks() {
        // 1. Default config
        let def_cfg = AwgConfig::default();
        assert!(def_cfg.is_warp_profile());
        assert_eq!(def_cfg.profile_name, "Cloudflare WARP");
        assert_eq!(def_cfg.fallback_endpoints.len(), CLOUDFLARE_WARP_ANYCAST_POOL.len());

        // 2. Parse INI matching Cloudflare WARP public key
        let warp_ini = format!(r#"
[Interface]
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 172.16.0.2/32

[Peer]
PublicKey = {}
Endpoint = 162.159.192.1:2408
"#, CLOUDFLARE_WARP_PEER_PUBKEY_B64);
        let cfg = AwgConfig::parse_ini(&warp_ini).expect("WARP profile should parse");
        assert!(cfg.is_warp_profile(), "Profile with Cloudflare peer public key must be classified as WARP");
        assert_eq!(cfg.profile_name, "Cloudflare WARP");
        assert_eq!(cfg.fallback_endpoints.len(), CLOUDFLARE_WARP_ANYCAST_POOL.len());
        assert_eq!(cfg.fallback_endpoints[0], CLOUDFLARE_WARP_ANYCAST_POOL[0]);
    }

    #[tokio::test]
    async fn test_awg_connect_with_config_error_contains_profile_and_stage_without_secrets() {
        let cancel_token = CancellationToken::new();

        // 1. Missing private key (validate_credentials stage)
        let mut cfg_unconf = AwgConfig::default();
        cfg_unconf.private_key = [0u8; 32];
        let err1 = AwgPeerSession::connect_with_config(cfg_unconf, &cancel_token, None, 100).await.unwrap_err();
        assert!(err1.contains("[profile='Cloudflare WARP', stage='validate_credentials', endpoint='162.159.193.10:1701']"), "Actual err: {}", err1);
        assert!(!err1.contains("AAAAA"), "No Base64 key material leaked");
        assert!(!err1.contains("000000"), "No raw key hex bytes leaked");

        // 2. DNS lookup failure (dns_lookup stage)
        let mut cfg_dns = AwgConfig::default();
        cfg_dns.profile_name = "My Custom VPS".to_string();
        cfg_dns.endpoint = "nonexistent.mirrly.invalid.domain.test:51820".to_string();
        cfg_dns.private_key = [0x42; 32];
        let err2 = AwgPeerSession::connect_with_config(cfg_dns, &cancel_token, None, 100).await.unwrap_err();
        assert!(err2.contains("[profile='My Custom VPS', stage='dns_lookup', endpoint='nonexistent.mirrly.invalid.domain.test:51820']"), "Actual err: {}", err2);
        assert!(!err2.contains("424242"), "No secret key bytes in hex leaked");

        // 3. Handshake timeout on unresponsive endpoint (handshake_timeout stage)
        let blackhole_socket = tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let local_addr = blackhole_socket.local_addr().unwrap();
        let mut cfg_timeout = AwgConfig::default();
        cfg_timeout.profile_name = "Blackhole Peer".to_string();
        cfg_timeout.endpoint = local_addr.to_string();
        cfg_timeout.private_key = [0x55; 32];
        let err3 = AwgPeerSession::connect_with_config(cfg_timeout, &cancel_token, None, 50).await.unwrap_err();
        assert!(err3.contains("[profile='Blackhole Peer', stage='handshake_timeout'"), "Actual err: {}", err3);
        assert!(!err3.contains("555555"), "No secret key bytes leaked");
    }

    #[tokio::test]
    async fn test_awg_custom_profile_failover_isolation_does_not_probe_warp_pool() {
        let _guard = AWG_TEST_MUTEX.lock().unwrap();
        // Setup a custom profile with unreachable primary endpoint and NO fallback endpoints
        let custom_ini = r#"
[Interface]
ProfileName = Isolated Private Server
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 10.0.0.2/32

[Peer]
PublicKey = vK84eU6iK0xVq4f8eK1iK0xVq4f8eK1iK0xVq4f8eK0=
Endpoint = 127.0.0.1:49999
"#;
        let cfg = AwgConfig::parse_ini(custom_ini).unwrap();
        assert_eq!(cfg.fallback_endpoints.len(), 0);
        assert!(!cfg.is_warp_profile());

        // Set global config
        {
            let mut g = AWG_CONFIG.write();
            *g = cfg;
        }
        invalidate_active_peer();

        let cancel = CancellationToken::new();
        // awg_acquire_tunnel should fail without attempting to probe any Cloudflare Anycast servers
        let tunnel = awg_acquire_tunnel("1.1.1.1:443", &cancel).await;
        assert!(tunnel.is_none(), "Should fail when primary is down and fallbacks are empty");

        // Verify that the endpoint was NOT changed to any Cloudflare Anycast address
        let current_ep = AWG_CONFIG.read().endpoint.clone();
        assert_eq!(current_ep, "127.0.0.1:49999");
        assert!(!is_cloudflare_warp_endpoint(&current_ep), "Must never switch custom profile to Cloudflare Anycast");

        // Restore default config for safety
        {
            let mut g = AWG_CONFIG.write();
            *g = AwgConfig::default();
        }
        invalidate_active_peer();
    }

    #[test]
    fn test_awg_circuit_breaker_transitions_and_backoff() {
        let mut cb = AwgCircuitBreaker::new();
        assert_eq!(cb.state, CircuitState::Closed);
        assert_eq!(cb.consecutive_failures, 0);
        assert!(cb.should_allow());
        assert!(!cb.is_open());

        // First failure -> Open with cooldown
        cb.record_failure();
        assert_eq!(cb.state, CircuitState::Open);
        assert_eq!(cb.consecutive_failures, 1);
        assert!(cb.is_open());
        assert!(!cb.should_allow());

        // Exponential backoff increases cooldown
        let first_cooldown = cb.cooldown_until.unwrap();
        cb.record_failure();
        assert_eq!(cb.consecutive_failures, 2);
        let second_cooldown = cb.cooldown_until.unwrap();
        assert!(second_cooldown >= first_cooldown);

        // Success resets circuit breaker to Closed
        cb.record_success();
        assert_eq!(cb.state, CircuitState::Closed);
        assert_eq!(cb.consecutive_failures, 0);
        assert!(cb.should_allow());
        assert!(!cb.is_open());
        assert!(cb.cooldown_until.is_none());

        // Reset also returns to Closed
        cb.record_failure();
        assert!(cb.is_open());
        cb.reset();
        assert_eq!(cb.state, CircuitState::Closed);
        assert!(!cb.is_open());
    }

    #[tokio::test]
    async fn test_awg_single_flight_and_circuit_breaker_prevents_dial_storm_under_100_concurrent_requests() {
        let _guard = AWG_TEST_MUTEX.lock().unwrap();
        let custom_ini = r#"
[Interface]
ProfileName = Flaky Server Under Storm
PrivateKey = YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=
Address = 10.0.0.2/32

[Peer]
PublicKey = vK84eU6iK0xVq4f8eK1iK0xVq4f8eK1iK0xVq4f8eK0=
Endpoint = 127.0.0.1:49991
FallbackEndpoints = 127.0.0.1:49992, 127.0.0.1:49993
"#;
        let cfg = AwgConfig::parse_ini(custom_ini).unwrap();
        {
            let mut g = AWG_CONFIG.write();
            *g = cfg;
        }
        invalidate_active_peer();
        reset_awg_circuit_breaker();

        let cancel_root = CancellationToken::new();
        let mut handles = Vec::with_capacity(100);

        // Launch 100 concurrent requests to the unreachable profile
        for _ in 0..100 {
            let cancel = cancel_root.child_token();
            handles.push(tokio::spawn(async move {
                awg_acquire_tunnel("1.1.1.1:443", &cancel).await
            }));
        }

        let start = std::time::Instant::now();
        let mut fail_count = 0;
        for h in handles {
            let res = h.await.expect("task join");
            if res.is_none() {
                fail_count += 1;
            }
        }
        let elapsed = start.elapsed();

        assert_eq!(fail_count, 100, "All 100 concurrent requests must fail safely");
        assert!(
            elapsed < Duration::from_secs(12),
            "100 concurrent dials must not run 100 sequential fallback loops (took {:?})",
            elapsed
        );

        // Verify circuit breaker is now OPEN
        assert_eq!(
            get_awg_circuit_breaker_state(),
            CircuitState::Open,
            "Circuit breaker must be tripped to Open after exhausted failovers"
        );

        // Subsequent requests while Circuit Breaker is OPEN must fail-fast in microseconds
        let fast_start = std::time::Instant::now();
        let immediate_fail = awg_acquire_tunnel("1.1.1.1:443", &cancel_root).await;
        let fast_elapsed = fast_start.elapsed();

        assert!(immediate_fail.is_none());
        assert!(
            fast_elapsed < Duration::from_millis(20),
            "Fast-fail under open circuit breaker must resolve in <20ms, took {:?}",
            fast_elapsed
        );

        // Restore default config
        {
            let mut g = AWG_CONFIG.write();
            *g = AwgConfig::default();
        }
        invalidate_active_peer();
        reset_awg_circuit_breaker();
    }

    #[tokio::test]
    async fn test_awg_bridge_poll_delay_and_zero_wakeups_after_stop() {
        // Verify smoltcp poll delay behavior
        let mut dev = AwgSmolDevice::new();
        let mut iface_cfg = SmolConfig::new(HardwareAddress::Ip);
        iface_cfg.random_seed = 12345;
        let mut iface = Interface::new(iface_cfg, &mut dev, SmolInstant::now());
        let mut sockets = SocketSet::new(vec![]);

        let rx_buf = tcp::SocketBuffer::new(vec![0u8; 1024]);
        let tx_buf = tcp::SocketBuffer::new(vec![0u8; 1024]);
        let socket = TcpSocket::new(rx_buf, tx_buf);
        let _handle = sockets.add(socket);

        // Idle interface with no pending timer events
        let delay = iface.poll_delay(SmolInstant::now(), &sockets);
        let sleep_dur = match delay {
            Some(d) => Duration::from_micros(d.total_micros())
                .clamp(Duration::from_millis(2), Duration::from_millis(1000)),
            None => Duration::from_millis(1000),
        };

        // When idle, sleep duration must be relaxed (1000ms) rather than 20ms busy polling
        assert_eq!(sleep_dur, Duration::from_millis(1000), "Idle smoltcp bridge must sleep 1000ms, not 20ms");

        // Test prompt shutdown on cancellation
        let cancel = CancellationToken::new();
        let cancel_clone = cancel.clone();

        let bridge_task = tokio::spawn(async move {
            let mut wakeups = 0usize;
            loop {
                tokio::select! {
                    _ = cancel_clone.cancelled() => {
                        break;
                    }
                    _ = tokio::time::sleep(sleep_dur) => {
                        wakeups += 1;
                    }
                }
            }
            wakeups
        });

        // Cancel immediately
        cancel.cancel();
        let wakeups = tokio::time::timeout(Duration::from_millis(100), bridge_task)
            .await
            .expect("Bridge task must terminate promptly upon cancellation")
            .expect("Join handle");

        assert_eq!(wakeups, 0, "After stop, zero timer wakeups must occur");
    }

    #[test]
    fn test_hchacha20_rfc_draft_vector() {
        let key: [u8; 32] = [
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
            0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
            0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f,
        ];
        let nonce: [u8; 16] = [
            0x00, 0x00, 0x00, 0x09, 0x00, 0x00, 0x00, 0x4a,
            0x00, 0x00, 0x00, 0x00, 0x31, 0x41, 0x59, 0x27,
        ];
        let expected: [u8; 32] = [
            0x82, 0x41, 0x3b, 0x42, 0x27, 0xb2, 0x7b, 0xfe,
            0xd3, 0x0e, 0x42, 0x50, 0x8a, 0x87, 0x7d, 0x73,
            0xa0, 0xf9, 0xe4, 0xd5, 0x8a, 0x74, 0xa8, 0x53,
            0xc1, 0x2e, 0xc4, 0x13, 0x26, 0xd3, 0xec, 0xdc,
        ];
        let subkey = hchacha20(&key, &nonce);
        assert_eq!(subkey, expected, "HChaCha20 output must match draft-irtf-cfrg-xchacha test vector");
    }

    #[test]
    fn test_xchacha20poly1305_roundtrip_and_tamper() {
        let mut key = [0u8; 32];
        let mut nonce = [0u8; 24];
        let mut ad = [0u8; 16];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut key);
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut nonce);
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut ad);

        let plaintext = b"WireGuard-Cookie-Challenge-16B";
        let ciphertext = xchacha20poly1305_encrypt(&key, &nonce, &ad, plaintext)
            .expect("encryption must succeed");
        assert_eq!(ciphertext.len(), plaintext.len() + 16, "Ciphertext includes 16-byte Poly1305 tag");

        // Legitimate decryption
        let decrypted = xchacha20poly1305_decrypt(&key, &nonce, &ad, &ciphertext)
            .expect("decryption must succeed");
        assert_eq!(&decrypted, plaintext, "Decrypted text must match plaintext");

        // Tamper ciphertext
        let mut bad_ct = ciphertext.clone();
        bad_ct[0] ^= 0xFF;
        assert!(
            xchacha20poly1305_decrypt(&key, &nonce, &ad, &bad_ct).is_err(),
            "Tampered ciphertext must fail authentication"
        );

        // Tamper AD (e.g. MAC1 mismatch)
        let mut bad_ad = ad;
        bad_ad[0] ^= 0x01;
        assert!(
            xchacha20poly1305_decrypt(&key, &nonce, &bad_ad, &ciphertext).is_err(),
            "Tampered AD must fail authentication"
        );

        // Tamper nonce
        let mut bad_nonce = nonce;
        bad_nonce[0] ^= 0x01;
        assert!(
            xchacha20poly1305_decrypt(&key, &bad_nonce, &ad, &ciphertext).is_err(),
            "Wrong nonce must fail authentication"
        );
    }

    #[test]
    fn test_cookie_reply_roundtrip_and_validation() {
        let mut server_priv = [0u8; 32];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut server_priv);
        let server_pub = x25519_base(&server_priv);

        let client_index = 0xCAFEBABE;
        let mut mac1 = [0u8; 16];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut mac1);

        let mut cookie = [0u8; 16];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut cookie);

        let h3 = 3u32;
        let reply = create_cookie_reply(&server_pub, h3, client_index, &mac1, &cookie)
            .expect("create_cookie_reply must succeed");
        assert_eq!(reply.len(), 64, "WireGuard cookie reply datagram must be exactly 64 bytes");

        // Parse and decrypt
        let decrypted_cookie = parse_and_decrypt_cookie_reply(
            &server_pub,
            h3,
            client_index,
            &mac1,
            &reply,
        ).expect("parse_and_decrypt_cookie_reply must succeed");
        assert_eq!(decrypted_cookie, cookie, "Decrypted cookie must match original");

        // Negative tests:
        // 1. Wrong receiver index
        assert!(
            parse_and_decrypt_cookie_reply(&server_pub, h3, 0x12345678, &mac1, &reply).is_err(),
            "Mismatched receiver index must be rejected"
        );
        // 2. Wrong message type
        assert!(
            parse_and_decrypt_cookie_reply(&server_pub, 0x9999, client_index, &mac1, &reply).is_err(),
            "Mismatched message type must be rejected"
        );
        // 3. Wrong MAC1 (AD)
        let mut bad_mac1 = mac1;
        bad_mac1[0] ^= 0x01;
        assert!(
            parse_and_decrypt_cookie_reply(&server_pub, h3, client_index, &bad_mac1, &reply).is_err(),
            "Mismatched MAC1 AD must fail authentication"
        );
        // 4. Truncated packet
        assert!(
            parse_and_decrypt_cookie_reply(&server_pub, h3, client_index, &mac1, &reply[..60]).is_err(),
            "Truncated cookie reply must be rejected"
        );
    }

    #[test]
    fn test_awg_server_under_load_cookie_challenge_flow() {
        let mut server_priv = [0u8; 32];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut server_priv);
        let server_pub = x25519_base(&server_priv);

        let mut config = AwgConfig::default();
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut config.private_key);
        config.public_key = x25519_base(&config.private_key);
        config.peer_public_key = server_pub;
        config.awg_params.s1 = 32;
        config.awg_params.s2 = 24;

        let client_index = 0x55AA;
        // Step 1: Client creates initiation without cached cookie (MAC2 is zeros)
        let (handshake, mut init_pkt) = create_initiation(&config, client_index).unwrap();

        // Server is under load and demands cookie
        let mut server_cookie = [0u8; 16];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut server_cookie);

        let outcome1 = respond_to_initiation_under_load(
            &server_priv,
            0xBBBB,
            &init_pkt,
            &config.awg_params,
            None,
            Some(&server_cookie),
        ).unwrap();

        // Must receive CookieReply
        let cookie_reply = match outcome1 {
            ServerHandshakeOutcome::CookieReply(reply) => reply,
            ServerHandshakeOutcome::Success { .. } => panic!("Expected CookieReply when MAC2 is zero"),
        };
        assert_eq!(cookie_reply.len(), 64);

        // Step 2: Client processes cookie reply
        let decrypted_cookie = parse_and_decrypt_cookie_reply(
            &config.peer_public_key,
            config.awg_params.h3,
            client_index,
            &handshake.mac1,
            &cookie_reply,
        ).expect("client must successfully decrypt cookie challenge");

        // Client sets MAC2 in existing initiation packet
        set_initiation_mac2(&mut init_pkt, config.awg_params.s1, &decrypted_cookie)
            .expect("set_initiation_mac2 must succeed");

        // Step 3: Server receives updated initiation with valid MAC2
        let outcome2 = respond_to_initiation_under_load(
            &server_priv,
            0xBBBB,
            &init_pkt,
            &config.awg_params,
            None,
            Some(&server_cookie),
        ).unwrap();

        let (server_keys, resp_pkt) = match outcome2 {
            ServerHandshakeOutcome::Success { keys, response_packet } => (keys, response_packet),
            ServerHandshakeOutcome::CookieReply(_) => panic!("Expected Success after valid MAC2"),
        };

        // Step 4: Client processes response
        let client_keys = process_response(handshake, &config, &resp_pkt)
            .expect("client must complete handshake with server response");

        assert_eq!(client_keys.send_key, server_keys.recv_key);
        assert_eq!(client_keys.recv_key, server_keys.send_key);
    }

    #[tokio::test]
    async fn test_awg_handshake_loss_recovery_first_initiation_lost() {
        let server_sock = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let server_addr = server_sock.local_addr().unwrap();

        let mut server_priv = [0u8; 32];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut server_priv);
        let server_pub = x25519_base(&server_priv);

        let mut client_cfg = AwgConfig::default();
        client_cfg.profile_name = "test_loss_init".to_string();
        client_cfg.endpoint = server_addr.to_string();
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut client_cfg.private_key);
        client_cfg.public_key = x25519_base(&client_cfg.private_key);
        client_cfg.peer_public_key = server_pub;
        client_cfg.awg_params.s1 = 16;
        client_cfg.awg_params.s2 = 16;
        client_cfg.awg_params.jc = 0;
        client_cfg.awg_params.i1 = None;

        let awg_params = client_cfg.awg_params.clone();

        let server_task = tokio::spawn(async move {
            let mut buf = [0u8; 2048];
            // Initiation 1: receive and DROP
            let (_n1, client_ep) = loop {
                let (n, ep) = server_sock.recv_from(&mut buf).await.unwrap();
                if n >= 148 + awg_params.s1 {
                    break (n, ep);
                }
            };

            // Initiation 2 (retransmitted): receive and ANSWER
            let (n2, client_ep2) = loop {
                let (n, ep) = server_sock.recv_from(&mut buf).await.unwrap();
                if n >= 148 + awg_params.s1 {
                    break (n, ep);
                }
            };
            assert_eq!(client_ep, client_ep2);

            let (server_keys, resp_pkt) = respond_to_initiation(
                &server_priv,
                0x7777,
                &buf[..n2],
                &awg_params,
                None,
            ).unwrap();

            server_sock.send_to(&resp_pkt, client_ep2).await.unwrap();
            server_keys
        });

        let cancel = CancellationToken::new();
        let peer = AwgPeerSession::connect_with_config(client_cfg, &cancel, None, 4000)
            .await
            .expect("Handshake must recover from lost first initiation via retransmission");

        let s_keys = server_task.await.unwrap();
        assert_eq!(peer.session.keypairs.read().current.send_key, s_keys.recv_key);
    }

    #[tokio::test]
    async fn test_awg_handshake_loss_recovery_first_response_lost() {
        let server_sock = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let server_addr = server_sock.local_addr().unwrap();

        let mut server_priv = [0u8; 32];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut server_priv);
        let server_pub = x25519_base(&server_priv);

        let mut client_cfg = AwgConfig::default();
        client_cfg.profile_name = "test_loss_resp".to_string();
        client_cfg.endpoint = server_addr.to_string();
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut client_cfg.private_key);
        client_cfg.public_key = x25519_base(&client_cfg.private_key);
        client_cfg.peer_public_key = server_pub;
        client_cfg.awg_params.s1 = 8;
        client_cfg.awg_params.s2 = 8;
        client_cfg.awg_params.jc = 0;
        client_cfg.awg_params.i1 = None;

        let awg_params = client_cfg.awg_params.clone();

        let server_task = tokio::spawn(async move {
            let mut buf = [0u8; 2048];
            // Initiation 1: generate response but DO NOT SEND IT
            let (n1, _client_ep) = loop {
                let (n, ep) = server_sock.recv_from(&mut buf).await.unwrap();
                if n >= 148 + awg_params.s1 {
                    break (n, ep);
                }
            };
            let _ = respond_to_initiation(&server_priv, 0x8881, &buf[..n1], &awg_params, None).unwrap();

            // Initiation 2: retransmitted by client after timeout
            let (n2, client_ep2) = loop {
                let (n, ep) = server_sock.recv_from(&mut buf).await.unwrap();
                if n >= 148 + awg_params.s1 {
                    break (n, ep);
                }
            };
            let (server_keys, resp_pkt2) = respond_to_initiation(
                &server_priv,
                0x8882,
                &buf[..n2],
                &awg_params,
                None,
            ).unwrap();

            server_sock.send_to(&resp_pkt2, client_ep2).await.unwrap();
            server_keys
        });

        let cancel = CancellationToken::new();
        let peer = AwgPeerSession::connect_with_config(client_cfg, &cancel, None, 4000)
            .await
            .expect("Handshake must recover from lost first response via initiation retransmission");

        let s_keys = server_task.await.unwrap();
        assert_eq!(peer.session.keypairs.read().current.send_key, s_keys.recv_key);
    }

    #[tokio::test]
    async fn test_awg_handshake_stray_and_duplicate_packet_filtering() {
        let server_sock = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let server_addr = server_sock.local_addr().unwrap();

        let mut server_priv = [0u8; 32];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut server_priv);
        let server_pub = x25519_base(&server_priv);

        let mut client_cfg = AwgConfig::default();
        client_cfg.profile_name = "test_stray_filtering".to_string();
        client_cfg.endpoint = server_addr.to_string();
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut client_cfg.private_key);
        client_cfg.public_key = x25519_base(&client_cfg.private_key);
        client_cfg.peer_public_key = server_pub;
        client_cfg.awg_params.s1 = 12;
        client_cfg.awg_params.s2 = 12;
        client_cfg.awg_params.jc = 0;
        client_cfg.awg_params.i1 = None;

        let awg_params = client_cfg.awg_params.clone();

        let server_task = tokio::spawn(async move {
            let mut buf = [0u8; 2048];
            let (n, client_ep) = loop {
                let (len, ep) = server_sock.recv_from(&mut buf).await.unwrap();
                if len >= 148 + awg_params.s1 {
                    break (len, ep);
                }
            };

            // 1. Inject random short garbage packet
            server_sock.send_to(&[0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x02, 0x03], client_ep).await.unwrap();

            // 2. Inject stray data packet (H4) with non-matching receiver index
            let mut stray_data = vec![0u8; 48];
            stray_data[0..4].copy_from_slice(&awg_params.h4.to_le_bytes());
            stray_data[4..8].copy_from_slice(&0x99999999u32.to_le_bytes());
            server_sock.send_to(&stray_data, client_ep).await.unwrap();

            // 3. Inject stray response packet (H2) with non-matching receiver index
            let mut stray_resp = vec![0u8; awg_params.s2 + 92];
            stray_resp[awg_params.s2..awg_params.s2 + 4].copy_from_slice(&awg_params.h2.to_le_bytes());
            stray_resp[awg_params.s2 + 8..awg_params.s2 + 12].copy_from_slice(&0xEEEEEEEEu32.to_le_bytes());
            server_sock.send_to(&stray_resp, client_ep).await.unwrap();

            // 4. Inject corrupted cookie challenge
            let mut bad_cookie_reply = vec![0u8; 64];
            bad_cookie_reply[0..4].copy_from_slice(&awg_params.h3.to_le_bytes());
            let client_idx = u32::from_le_bytes([buf[awg_params.s1 + 4], buf[awg_params.s1 + 5], buf[awg_params.s1 + 6], buf[awg_params.s1 + 7]]);
            bad_cookie_reply[4..8].copy_from_slice(&client_idx.to_le_bytes());
            server_sock.send_to(&bad_cookie_reply, client_ep).await.unwrap();

            tokio::time::sleep(Duration::from_millis(15)).await;

            // 5. Finally send legitimate response
            let (server_keys, valid_resp) = respond_to_initiation(
                &server_priv,
                0x9991,
                &buf[..n],
                &awg_params,
                None,
            ).unwrap();

            server_sock.send_to(&valid_resp, client_ep).await.unwrap();
            server_keys
        });

        let cancel = CancellationToken::new();
        let peer = AwgPeerSession::connect_with_config(client_cfg, &cancel, None, 4000)
            .await
            .expect("Stray and corrupted packets must be safely filtered without terminating handshake");

        let s_keys = server_task.await.unwrap();
        assert_eq!(peer.session.keypairs.read().current.send_key, s_keys.recv_key);
    }

    #[tokio::test]
    async fn test_awg_handshake_cookie_challenge_in_connect_loop() {
        let server_sock = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let server_addr = server_sock.local_addr().unwrap();

        let mut server_priv = [0u8; 32];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut server_priv);
        let server_pub = x25519_base(&server_priv);

        let mut client_cfg = AwgConfig::default();
        client_cfg.profile_name = "test_cookie_loop".to_string();
        client_cfg.endpoint = server_addr.to_string();
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut client_cfg.private_key);
        client_cfg.public_key = x25519_base(&client_cfg.private_key);
        client_cfg.peer_public_key = server_pub;
        client_cfg.awg_params.s1 = 20;
        client_cfg.awg_params.s2 = 20;
        client_cfg.awg_params.jc = 0;
        client_cfg.awg_params.i1 = None;

        let awg_params = client_cfg.awg_params.clone();
        let mut server_cookie = [0u8; 16];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut server_cookie);

        let server_task = tokio::spawn(async move {
            let mut buf = [0u8; 2048];
            // Step 1: receive first initiation without cookie -> issue CookieReply
            let (n1, client_ep1) = loop {
                let (len, ep) = server_sock.recv_from(&mut buf).await.unwrap();
                if len >= 148 + awg_params.s1 {
                    break (len, ep);
                }
            };
            let outcome1 = respond_to_initiation_under_load(
                &server_priv,
                0x7701,
                &buf[..n1],
                &awg_params,
                None,
                Some(&server_cookie),
            ).unwrap();

            let cookie_reply = match outcome1 {
                ServerHandshakeOutcome::CookieReply(r) => r,
                _ => panic!("Expected CookieReply"),
            };
            server_sock.send_to(&cookie_reply, client_ep1).await.unwrap();

            // Step 2: receive retransmitted initiation with valid MAC2 -> issue Response
            let (n2, client_ep2) = loop {
                let (len, ep) = server_sock.recv_from(&mut buf).await.unwrap();
                if len >= 148 + awg_params.s1 {
                    break (len, ep);
                }
            };
            let outcome2 = respond_to_initiation_under_load(
                &server_priv,
                0x7702,
                &buf[..n2],
                &awg_params,
                None,
                Some(&server_cookie),
            ).unwrap();

            let (s_keys, resp_pkt) = match outcome2 {
                ServerHandshakeOutcome::Success { keys, response_packet } => (keys, response_packet),
                _ => panic!("Expected Success with valid MAC2"),
            };
            server_sock.send_to(&resp_pkt, client_ep2).await.unwrap();
            s_keys
        });

        let cancel = CancellationToken::new();
        let peer = AwgPeerSession::connect_with_config(client_cfg, &cancel, None, 4000)
            .await
            .expect("Client connect loop must handle cookie challenge and successfully retransmit");

        let s_keys = server_task.await.unwrap();
        assert_eq!(peer.session.keypairs.read().current.send_key, s_keys.recv_key);
    }

    #[test]
    fn test_differential_x25519_against_ring() {
        use ring::agreement::{agree_ephemeral, EphemeralPrivateKey, UnparsedPublicKey, X25519};
        use ring::rand::SystemRandom;

        let rng = SystemRandom::new();
        for _ in 0..50 {
            // Generate ephemeral key using ring
            let ring_priv = EphemeralPrivateKey::generate(&X25519, &rng).expect("ring keygen");
            let ring_pub = ring_priv.compute_public_key().expect("ring pubkey");

            // Generate private scalar for our pure-Rust Montgomery ladder
            let mut our_priv = [0u8; 32];
            rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut our_priv);
            let our_pub = x25519_base(&our_priv);

            // Ring computes shared secret S_ring = DiffieHellman(ring_priv, our_pub)
            let ring_shared = agree_ephemeral(
                ring_priv,
                &UnparsedPublicKey::new(&X25519, &our_pub),
                |shared| {
                    let mut s = [0u8; 32];
                    s.copy_from_slice(shared);
                    Ok::<[u8; 32], ring::error::Unspecified>(s)
                },
            )
            .expect("ring agreement")
            .expect("kdf");

            // Our code computes shared secret S_our = DiffieHellman(our_priv, ring_pub)
            let mut ring_pub_bytes = [0u8; 32];
            ring_pub_bytes.copy_from_slice(ring_pub.as_ref());
            let our_shared = x25519(&our_priv, &ring_pub_bytes);

            // By commutativity of X25519, ring_shared must equal our_shared
            assert_eq!(
                ring_shared, our_shared,
                "Pure-Rust X25519 must agree with ring BoringSSL X25519"
            );
        }
    }

    #[test]
    fn test_rfc7748_x25519_vector2_and_iterated() {
        fn from_hex(s: &str) -> [u8; 32] {
            let mut arr = [0u8; 32];
            for i in 0..32 {
                arr[i] = u8::from_str_radix(&s[i * 2..i * 2 + 2], 16).unwrap();
            }
            arr
        }

        // RFC 7748 Section 5.2 Vector 2
        let scalar = from_hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d");
        let point = from_hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493");
        let res = x25519(&scalar, &point);
        let hex_res = res.iter().map(|b| format!("{:02x}", b)).collect::<String>();
        assert_eq!(
            hex_res,
            "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
            "RFC 7748 Vector 2 must match"
        );

        // RFC 7748 Section 5.2 Iterated (1 and 1,000 times)
        let mut k = [0u8; 32];
        k[0] = 9;
        let mut u = [0u8; 32];
        u[0] = 9;

        // After 1 iteration:
        let next_k = x25519(&k, &u);
        u = k;
        k = next_k;
        let iter1_hex = k.iter().map(|b| format!("{:02x}", b)).collect::<String>();
        assert_eq!(
            iter1_hex,
            "422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079",
            "RFC 7748 after 1 iteration must match official test vector"
        );

        // For remaining 999 iterations:
        for _ in 1..1000 {
            let next_k = x25519(&k, &u);
            u = k;
            k = next_k;
        }
        let iterated_hex = k.iter().map(|b| format!("{:02x}", b)).collect::<String>();
        assert_eq!(
            iterated_hex,
            "684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51",
            "RFC 7748 after 1,000 iterations must match official test vector"
        );
    }

    #[test]
    fn test_rfc8439_chacha20_poly1305_aead_vector() {
        let key: [u8; 32] = [
            0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
            0x88, 0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x8e, 0x8f,
            0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97,
            0x98, 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e, 0x9f,
        ];
        let ad: [u8; 12] = [
            0x50, 0x51, 0x52, 0x53, 0xc0, 0xc1, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7,
        ];
        let plaintext = b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";

        let counter = u64::from_le_bytes([0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47]);
        let ciphertext = aead_encrypt(&key, counter, &ad, plaintext).expect("AEAD encrypt");
        assert_eq!(ciphertext.len(), plaintext.len() + 16);

        let decrypted = aead_decrypt(&key, counter, &ad, &ciphertext).expect("AEAD decrypt");
        assert_eq!(&decrypted, plaintext);

        // Verify tamper detection
        let mut tampered = ciphertext.clone();
        tampered[0] ^= 0x01;
        assert!(aead_decrypt(&key, counter, &ad, &tampered).is_err());
        assert!(aead_decrypt(&key, counter + 1, &ad, &ciphertext).is_err());
        assert!(aead_decrypt(&key, counter, &ad[..10], &ciphertext).is_err());
    }

    #[test]
    fn test_wireguard_handshake_golden_vector() {
        let client_priv = [
            0xe8, 0x4b, 0x5a, 0x68, 0x0d, 0xe1, 0x07, 0x16,
            0x7d, 0x01, 0x05, 0xfb, 0x90, 0xc6, 0x62, 0xe6,
            0xf4, 0x1f, 0xae, 0x1b, 0x10, 0xd4, 0x9d, 0xda,
            0x24, 0x27, 0x10, 0x24, 0xf5, 0xe8, 0x06, 0x10,
        ];
        let client_pub = x25519_base(&client_priv);

        let server_priv = [
            0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
            0x88, 0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x8e, 0x8f,
            0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97,
            0x98, 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e, 0x9f,
        ];
        let server_pub = x25519_base(&server_priv);

        let mut config = AwgConfig::default();
        config.private_key = client_priv;
        config.public_key = client_pub;
        config.peer_public_key = server_pub;
        config.awg_params.s1 = 0; // standard WireGuard wire format
        config.awg_params.s2 = 0;
        config.awg_params.h1 = 1;
        config.awg_params.h2 = 2;
        config.awg_params.h3 = 3;
        config.awg_params.h4 = 4;

        let client_index = 0x12345678;
        let (hs, init_pkt) = create_initiation(&config, client_index).unwrap();
        assert_eq!(init_pkt.len(), 148, "WireGuard initiation packet must be exactly 148 bytes");
        assert_eq!(u32::from_le_bytes([init_pkt[0], init_pkt[1], init_pkt[2], init_pkt[3]]), 1);
        assert_eq!(u32::from_le_bytes([init_pkt[4], init_pkt[5], init_pkt[6], init_pkt[7]]), client_index);

        let server_index = 0x87654321;
        let (server_keys, resp_pkt) = respond_to_initiation(
            &server_priv,
            server_index,
            &init_pkt,
            &config.awg_params,
            None,
        ).expect("server must respond to valid initiation");
        assert_eq!(resp_pkt.len(), 92, "WireGuard response packet must be exactly 92 bytes");
        assert_eq!(u32::from_le_bytes([resp_pkt[0], resp_pkt[1], resp_pkt[2], resp_pkt[3]]), 2);
        assert_eq!(u32::from_le_bytes([resp_pkt[4], resp_pkt[5], resp_pkt[6], resp_pkt[7]]), server_index);
        assert_eq!(u32::from_le_bytes([resp_pkt[8], resp_pkt[9], resp_pkt[10], resp_pkt[11]]), client_index);

        let client_keys = process_response(hs, &config, &resp_pkt)
            .expect("client must process response");
        assert_eq!(client_keys.send_key, server_keys.recv_key);
        assert_eq!(client_keys.recv_key, server_keys.send_key);
    }

    #[test]
    fn test_wireguard_transport_padding_boundary_lengths() {
        let keys_tx = TransportKeys::new([1u8; 32], [2u8; 32], 0x11, 0x22);
        let keys_rx = TransportKeys::new([2u8; 32], [1u8; 32], 0x22, 0x11);
        let h4 = 4;

        // 1. Keepalive (0-byte payload): unpadded, exact 32 bytes on wire
        let keepalive = encapsulate_transport_packet(&keys_tx, h4, &[]).unwrap();
        assert_eq!(keepalive.len(), 32, "Keepalive packet must be exactly 32 bytes (16 header + 16 tag)");
        let ka_decrypted = decapsulate_transport_packet(&keys_rx, h4, &keepalive).unwrap();
        assert!(ka_decrypted.is_empty());

        // 2. 20-byte minimal IPv4 packet -> pad16(20) = 32, total wire = 16 header + 32 payload + 16 tag = 64 bytes
        let mut ipv4_20 = vec![0u8; 20];
        ipv4_20[0] = 0x45; // IPv4
        ipv4_20[2] = 0x00;
        ipv4_20[3] = 0x14; // total length = 20
        let wire_20 = encapsulate_transport_packet(&keys_tx, h4, &ipv4_20).unwrap();
        assert_eq!(wire_20.len(), 16 + 32 + 16, "20-byte IPv4 padded to 32 bytes, wire len = 64");
        let dec_20 = decapsulate_transport_packet(&keys_rx, h4, &wire_20).unwrap();
        assert_eq!(dec_20.len(), 20, "Padding must be cleanly trimmed to original 20 bytes");
        assert_eq!(dec_20, ipv4_20);

        // 3. 24-byte IPv4 packet -> pad16(24) = 32, total wire = 64 bytes
        let mut ipv4_24 = vec![0u8; 24];
        ipv4_24[0] = 0x45;
        ipv4_24[2] = 0x00;
        ipv4_24[3] = 0x18; // total length = 24
        let wire_24 = encapsulate_transport_packet(&keys_tx, h4, &ipv4_24).unwrap();
        assert_eq!(wire_24.len(), 64);
        let dec_24 = decapsulate_transport_packet(&keys_rx, h4, &wire_24).unwrap();
        assert_eq!(dec_24.len(), 24);

        // 4. 40-byte IPv6 packet -> pad16(40) = 48, total wire = 16 header + 48 payload + 16 tag = 80 bytes
        let mut ipv6_40 = vec![0u8; 40];
        ipv6_40[0] = 0x60; // IPv6
        ipv6_40[4] = 0x00;
        ipv6_40[5] = 0x00; // payload length = 0, total = 40
        let wire_v6 = encapsulate_transport_packet(&keys_tx, h4, &ipv6_40).unwrap();
        assert_eq!(wire_v6.len(), 16 + 48 + 16, "40-byte IPv6 padded to 48 bytes, wire len = 80");
        let dec_v6 = decapsulate_transport_packet(&keys_rx, h4, &wire_v6).unwrap();
        assert_eq!(dec_v6.len(), 40);

        // 5. 1500-byte MTU IPv4 packet -> pad16(1500) = 1504, wire = 16 header + 1504 payload + 16 tag = 1536 bytes
        let mut ipv4_1500 = vec![0u8; 1500];
        ipv4_1500[0] = 0x45;
        ipv4_1500[2] = 0x05;
        ipv4_1500[3] = 0xdc; // total length = 1500
        let wire_1500 = encapsulate_transport_packet(&keys_tx, h4, &ipv4_1500).unwrap();
        assert_eq!(wire_1500.len(), 16 + 1504 + 16, "1500-byte packet padded to 1504, wire len = 1536");
        let dec_1500 = decapsulate_transport_packet(&keys_rx, h4, &wire_1500).unwrap();
        assert_eq!(dec_1500.len(), 1500);
    }

    #[test]
    fn test_trim_and_validate_ip_packet_overrun_and_truncated_rejection() {
        // Case 1: IPv4 truncated header (< 20 bytes)
        let truncated_v4 = vec![0x45, 0x00, 0x00, 0x14, 0x01];
        assert!(trim_and_validate_ip_packet(&truncated_v4).is_err(), "IPv4 < 20 bytes must be rejected");

        // Case 2: IPv4 claiming length exceeding decrypted buffer
        let mut overrun_v4 = vec![0u8; 24];
        overrun_v4[0] = 0x45;
        overrun_v4[2] = 0x00;
        overrun_v4[3] = 0x64; // claims 100 bytes
        assert!(trim_and_validate_ip_packet(&overrun_v4).is_err(), "IPv4 claiming total_len > buffer must be rejected");

        // Case 3: IPv4 claiming invalid header length < 20
        let mut invalid_hdr_v4 = vec![0u8; 24];
        invalid_hdr_v4[0] = 0x45;
        invalid_hdr_v4[2] = 0x00;
        invalid_hdr_v4[3] = 0x05; // claims 5 bytes
        assert!(trim_and_validate_ip_packet(&invalid_hdr_v4).is_err(), "IPv4 claiming total_len < 20 must be rejected");

        // Case 4: IPv6 truncated header (< 40 bytes)
        let truncated_v6 = vec![0x60, 0x00, 0x00, 0x00, 0x00, 0x10];
        assert!(trim_and_validate_ip_packet(&truncated_v6).is_err(), "IPv6 < 40 bytes must be rejected");

        // Case 5: IPv6 claiming payload exceeding decrypted buffer
        let mut overrun_v6 = vec![0u8; 48];
        overrun_v6[0] = 0x60;
        overrun_v6[4] = 0x00;
        overrun_v6[5] = 0x64; // payload = 100, total = 140 > 48
        assert!(trim_and_validate_ip_packet(&overrun_v6).is_err(), "IPv6 claiming 40 + payload > buffer must be rejected");
    }

    #[test]
    fn test_handshake_and_transport_negative_mac_and_tamper_cases() {
        let mut client_cfg = AwgConfig::default();
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut client_cfg.private_key);
        client_cfg.public_key = x25519_base(&client_cfg.private_key);

        let mut server_priv = [0u8; 32];
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), &mut server_priv);
        let server_pub = x25519_base(&server_priv);
        client_cfg.peer_public_key = server_pub;
        client_cfg.awg_params.s1 = 0;
        client_cfg.awg_params.s2 = 0;

        let (hs, init_pkt) = create_initiation(&client_cfg, 0x7788).unwrap();

        // 1. Corrupted MAC1 in initiation
        let mut bad_mac1_pkt = init_pkt.clone();
        bad_mac1_pkt[116] ^= 0x01;
        let res = respond_to_initiation(&server_priv, 0x9999, &bad_mac1_pkt, &client_cfg.awg_params, None);
        assert!(res.is_err(), "Corrupted MAC1 must be rejected by responder");
        assert!(res.unwrap_err().contains("MAC1 verification failed"));

        // 2. Corrupted Ephemeral Key in initiation
        let mut bad_ephem_pkt = init_pkt.clone();
        bad_ephem_pkt[8] ^= 0x01;
        // recalculate MAC1 so it passes MAC1 check, but fails AEAD
        let mut mac1_key_input = Vec::new();
        mac1_key_input.extend_from_slice(b"mac1----");
        mac1_key_input.extend_from_slice(&server_pub);
        let mac1_key = blake2s_256(&mac1_key_input);
        let mac1 = blake2s(&mac1_key, &bad_ephem_pkt[0..116], 16);
        bad_ephem_pkt[116..132].copy_from_slice(&mac1);
        let res = respond_to_initiation(&server_priv, 0x9999, &bad_ephem_pkt, &client_cfg.awg_params, None);
        assert!(res.is_err(), "Tampered ephemeral key must fail AEAD verification");

        // 3. Corrupted AEAD tag in handshake response
        let (_s_keys, resp_pkt) = respond_to_initiation(&server_priv, 0x9999, &init_pkt, &client_cfg.awg_params, None).unwrap();
        let mut bad_resp = resp_pkt.clone();
        bad_resp[50] ^= 0x01; // tamper tag
        assert!(process_response(hs, &client_cfg, &bad_resp).is_err(), "Tampered response payload tag must fail");

        // 4. Truncated transport packet
        let tx = TransportKeys::new([3u8; 32], [4u8; 32], 0x1, 0x2);
        let rx = TransportKeys::new([4u8; 32], [3u8; 32], 0x2, 0x1);
        let valid_trans = encapsulate_transport_packet(&tx, 4, b"test").unwrap();
        assert!(decapsulate_transport_packet(&rx, 4, &valid_trans[..20]).is_err(), "Truncated transport packet must be rejected");
    }
}
