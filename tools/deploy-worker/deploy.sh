#!/usr/bin/env bash
# Mirrly TG Proxy — Автоматический деплой персонального Cloudflare Worker (Linux / macOS)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HISTORY_FILE="${SCRIPT_DIR}/mirrly_workers.txt"
WRANGLER_VERSION="3.114.1"

invoke_wrangler() {
    if command -v wrangler >/dev/null 2>&1; then
        wrangler "$@"
    else
        npx -y --prefer-offline "wrangler@${WRANGLER_VERSION}" "$@"
    fi
}

format_cloudflare_error() {
    local LOG="$1"
    echo -e "\n\033[1;33m  ╔══════════════════════════════════════════════════════════════════╗\033[0m"
    echo -e "\033[1;33m  ║                     Диагностика ошибки                           ║\033[0m"
    echo -e "\033[1;33m  ╚══════════════════════════════════════════════════════════════════╝\033[0m"

    if echo "$LOG" | grep -qiE "10021|10000|limit reached|rate limit|100,?000 requests|exceeded"; then
        echo -e "  \033[1;33m[!] Причина: Исчерпан суточный лимит бесплатного тарифа (100 000 req/day).\033[0m"
        echo -e "  \033[1;36m[?] Рекомендация: Выберите пункт [6] для смены аккаунта Cloudflare или дождитесь сброса в 00:00 UTC.\033[0m\n"
    elif echo "$LOG" | grep -qiE "Token has expired|Invalid access token|10001|Authentication error"; then
        echo -e "  \033[1;33m[!] Причина: Истек токен авторизации Cloudflare.\033[0m"
        echo -e "  \033[1;36m[?] Рекомендация: Выберите пункт [6] «Сменить аккаунт Cloudflare» для повторного входа.\033[0m\n"
    elif echo "$LOG" | grep -qiE "Account Suspended|Account is blocked|10008|10014|Forbidden|Billing"; then
        echo -e "  \033[1;33m[!] Причина: Аккаунт Cloudflare заблокирован или ограничен.\033[0m"
        echo -e "  \033[1;36m[?] Рекомендация: Проверьте статус на dash.cloudflare.com или смените аккаунт (пункт [6]).\033[0m\n"
    elif echo "$LOG" | grep -qiE "already exists|subdomain.*taken|10013"; then
        echo -e "  \033[1;33m[!] Причина: Воркер с таким именем уже существует.\033[0m"
        echo -e "  \033[1;36m[?] Рекомендация: Выберите пункт [1] (авто-имя) или укажите другое имя в пункте [2].\033[0m\n"
    elif echo "$LOG" | grep -qiE "ETIMEDOUT|ENOTFOUND|ECONNRESET|fetch failed|Could not resolve host"; then
        echo -e "  \033[1;33m[!] Причина: Ошибка сетевого соединения с api.cloudflare.com.\033[0m"
        echo -e "  \033[1;36m[?] Рекомендация: Проверьте интернет или VPN и повторите попытку.\033[0m\n"
    else
        echo -e "  \033[1;33m[!] Непредвиденная ошибка Cloudflare API.\033[0m"
        echo -e "  \033[1;36m[?] Проверьте логи выше или попробуйте другое имя воркера.\033[0m\n"
    fi
}

show_banner() {
    clear
    echo -e "\n\033[0;36m  ╔══════════════════════════════════════════════════════════════════╗\033[0m"
    echo -e "\033[1;37m  ║                Mirrly TG Proxy — Worker Deployer                 ║\033[0m"
    echo -e "\033[0;36m  ║  Деплой SOCKS5-релея Telegram в Cloudflare Workers             ║\033[0m"
    echo -e "\033[0;36m  ╚══════════════════════════════════════════════════════════════════╝\033[0m\n"
}

if ! command -v node >/dev/null 2>&1; then
    show_banner
    echo -e "\033[0;31m  [X] Node.js не установлен в системе!\033[0m"
    echo -e "\033[1;33m  Установите Node.js (v18+): https://nodejs.org/\033[0m\n"
    exit 1
fi

show_qr_code() {
    local TEXT="$1"
    if [ -z "$TEXT" ]; then return; fi
    node -e '
function generateQR(text) {
    const PAD0 = 0xEC, PAD1 = 0x11;
    const EXP = new Uint8Array(256), LOG = new Uint8Array(256);
    for (let i = 0, x = 1; i < 255; i++) { EXP[i] = x; LOG[x] = i; x = (x << 1) ^ (x >= 128 ? 0x11D : 0); }
    EXP[255] = EXP[0];
    function glog(n) { return LOG[n]; }
    function gexp(n) { while (n < 0) n += 255; while (n >= 256) n -= 255; return EXP[n]; }
    function polyMul(p1, p2) {
        const res = new Uint8Array(p1.length + p2.length - 1);
        for (let i = 0; i < p1.length; i++) for (let j = 0; j < p2.length; j++) res[i + j] ^= gexp(glog(p1[i]) + glog(p2[j]));
        return res;
    }
    function getGenPoly(n) {
        let g = new Uint8Array([1]);
        for (let i = 0; i < n; i++) g = polyMul(g, new Uint8Array([1, gexp(i)]));
        return g;
    }
    function calcEc(data, ecWords) {
        const gen = getGenPoly(ecWords), msg = new Uint8Array(data.length + ecWords);
        msg.set(data, 0);
        for (let i = 0; i < data.length; i++) {
            const coef = msg[i];
            if (coef !== 0) for (let j = 0; j < gen.length; j++) msg[i + j] ^= gexp(glog(gen[j]) + glog(coef));
        }
        return msg.slice(data.length);
    }
    const ECC = [
        null,
        [[26,7,1,19,0,0],[26,10,1,16,0,0],[26,13,1,13,0,0],[26,17,1,9,0,0]],
        [[44,10,1,34,0,0],[44,16,1,28,0,0],[44,22,1,22,0,0],[44,28,1,16,0,0]],
        [[70,15,1,55,0,0],[70,26,1,44,0,0],[70,18,2,17,0,0],[70,22,2,13,0,0]],
        [[100,20,1,80,0,0],[100,18,2,32,0,0],[100,26,2,24,0,0],[100,16,4,9,0,0]],
        [[134,26,1,108,0,0],[134,24,2,43,0,0],[134,18,2,15,2,16],[134,22,2,11,2,12]],
        [[172,18,2,68,0,0],[172,16,4,27,0,0],[172,24,4,19,0,0],[172,28,4,15,0,0]],
        [[196,20,2,78,0,0],[196,18,4,31,0,0],[196,18,2,14,4,15],[196,26,4,13,1,14]],
        [[242,24,2,97,0,0],[242,22,2,38,2,39],[242,22,4,18,2,19],[242,26,4,14,2,15]],
        [[292,30,2,116,0,0],[292,22,3,36,2,37],[292,20,4,16,4,17],[292,24,4,12,4,13]],
        [[346,18,2,68,2,69],[346,26,4,43,1,44],[346,24,6,19,2,20],[346,28,6,15,2,16]],
        [[404,20,4,81,0,0],[404,30,1,50,4,51],[404,28,4,22,4,23],[404,24,3,12,8,13]],
        [[466,24,2,92,2,93],[466,22,6,36,2,37],[466,26,4,20,6,21],[466,28,7,14,4,15]],
        [[532,26,4,107,0,0],[532,22,8,37,1,38],[532,24,8,20,4,21],[532,22,12,11,4,12]],
        [[581,30,3,115,1,116],[581,24,4,40,5,41],[581,20,11,16,5,17],[581,24,11,12,5,13]]
    ];
    const ALIGN = [[],[],[6,18],[6,22],[6,26],[6,30],[6,34],[6,22,38],[6,24,42],[6,26,46],[6,28,50],[6,30,54],[6,32,58],[6,34,62],[6,26,46,66]];
    const FMT = [
        [0x5412,0x5125,0x5E7C,0x5B4B,0x45F9,0x40CE,0x4F97,0x4AA0],
        [0x77C4,0x72F3,0x7DAA,0x789D,0x662F,0x6318,0x6C41,0x6976]
    ];
    const utf8 = Buffer.from(text, "utf8");
    let ver = 1, cap = 0;
    for (let v = 1; v < ECC.length; v++) {
        const inf = ECC[v][1];
        const c = inf[2]*inf[3] + inf[4]*inf[5];
        if (c * 8 >= 4 + (v<=9?8:16) + (utf8.length * 8)) { ver = v; cap = c; break; }
    }
    const bits = [];
    function put(v, l) { for (let i = l - 1; i >= 0; i--) bits.push((v >> i) & 1); }
    put(0b0100, 4);
    put(utf8.length, ver <= 9 ? 8 : 16);
    for (let i = 0; i < utf8.length; i++) put(utf8[i], 8);
    const capBits = cap * 8;
    put(0, Math.min(4, capBits - bits.length));
    while (bits.length % 8 !== 0) bits.push(0);
    let p = PAD0;
    while (bits.length < capBits) { put(p, 8); p = (p === PAD0) ? PAD1 : PAD0; }
    const dataBytes = new Uint8Array(bits.length / 8);
    for (let i = 0; i < dataBytes.length; i++) {
        let b = 0; for (let j = 0; j < 8; j++) b = (b << 1) | bits[i * 8 + j];
        dataBytes[i] = b;
    }
    const inf = ECC[ver][1];
    const ecWords = inf[1], g1B = inf[2], g1D = inf[3], g2B = inf[4], g2D = inf[5];
    const totB = g1B + g2B, bData = [], bEc = [];
    let off = 0;
    for (let b = 0; b < totB; b++) {
        const sz = (b < g1B) ? g1D : g2D;
        const d = dataBytes.slice(off, off + sz);
        off += sz; bData.push(d); bEc.push(calcEc(d, ecWords));
    }
    const stream = new Uint8Array(inf[0]);
    let ptr = 0, maxD = Math.max(g1D, g2D);
    for (let i = 0; i < maxD; i++) for (let b = 0; b < totB; b++) if (i < bData[b].length) stream[ptr++] = bData[b][i];
    for (let i = 0; i < ecWords; i++) for (let b = 0; b < totB; b++) stream[ptr++] = bEc[b][i];
    const size = ver * 4 + 17;
    const mat = Array.from({ length: size }, () => new Int8Array(size).fill(-1));
    function setM(r, c, v) { if (r >= 0 && r < size && c >= 0 && c < size) mat[r][c] = v ? 1 : 0; }
    function drawF(row, col) {
        for (let r = -1; r <= 7; r++) for (let c = -1; c <= 7; c++) {
            const rr = row + r, cc = col + c;
            if (rr < 0 || rr >= size || cc < 0 || cc >= size) continue;
            if (r === -1 || r === 7 || c === -1 || c === 7) setM(rr, cc, 0);
            else if (r === 0 || r === 6 || c === 0 || c === 6 || (r >= 2 && r <= 4 && c >= 2 && c <= 4)) setM(rr, cc, 1);
            else setM(rr, cc, 0);
        }
    }
    drawF(0, 0); drawF(0, size - 7); drawF(size - 7, 0);
    for (let i = 8; i < size - 8; i++) {
        if (mat[6][i] === -1) mat[6][i] = (i % 2 === 0) ? 1 : 0;
        if (mat[i][6] === -1) mat[i][6] = (i % 2 === 0) ? 1 : 0;
    }
    const al = ALIGN[ver];
    for (let i = 0; i < al.length; i++) for (let j = 0; j < al.length; j++) {
        const r = al[i], c = al[j];
        if (mat[r][c] !== -1) continue;
        for (let dr = -2; dr <= 2; dr++) for (let dc = -2; dc <= 2; dc++) {
            setM(r + dr, c + dc, (Math.max(Math.abs(dr), Math.abs(dc)) === 2 || (dr === 0 && dc === 0)) ? 1 : 0);
        }
    }
    setM(4 * ver + 9, 8, 1);
    for (let i = 0; i < 9; i++) { if (mat[8][i] === -1) mat[8][i] = -2; if (mat[i][8] === -1) mat[i][8] = -2; }
    for (let i = size - 8; i < size; i++) { if (mat[8][i] === -1) mat[8][i] = -2; if (mat[i][8] === -1) mat[i][8] = -2; }
    let bIdx = 0, dir = -1;
    for (let col = size - 1; col > 0; col -= 2) {
        if (col === 6) col--;
        const rows = (dir === -1) ? Array.from({ length: size }, (_, i) => size - 1 - i) : Array.from({ length: size }, (_, i) => i);
        for (const row of rows) for (const c of [col, col - 1]) {
            if (mat[row][c] === -1 || mat[row][c] === -2) {
                let bit = 0;
                if (bIdx < stream.length * 8) {
                    bit = (stream[Math.floor(bIdx / 8)] >> (7 - (bIdx % 8))) & 1;
                    bIdx++;
                }
                mat[row][c] = bit ^ (((row + c) % 2 === 0) ? 1 : 0);
            }
        }
        dir = -dir;
    }
    const fBits = [];
    for (let i = 0; i < 15; i++) fBits.push((FMT[1][0] >> i) & 1);
    const tl = [[8,0],[8,1],[8,2],[8,3],[8,4],[8,5],[8,7],[8,8],[7,8],[5,8],[4,8],[3,8],[2,8],[1,8],[0,8]];
    for (let i = 0; i < 15; i++) mat[tl[i][0]][tl[i][1]] = fBits[i];
    for (let i = 0; i < 8; i++) mat[8][size - 1 - i] = fBits[i];
    for (let i = 0; i < 7; i++) mat[size - 7 + i][8] = fBits[8 + i];

    const q = 2, tot = size + q * 2;
    const grid = Array.from({ length: tot }, () => new Uint8Array(tot).fill(0));
    for (let r = 0; r < size; r++) for (let c = 0; c < size; c++) grid[r + q][c + q] = mat[r][c];
    let out = "\n";
    for (let r = 0; r < tot; r += 2) {
        let line = "  ";
        for (let c = 0; c < tot; c++) {
            const topW = (grid[r][c] === 0), botW = (r + 1 < tot) ? (grid[r + 1][c] === 0) : true;
            line += (topW && botW) ? "█" : (topW ? "▀" : (botW ? "▄" : " "));
        }
        out += line + "\n";
    }
    return out;
}
console.log(generateQR(process.argv[1]));
' "$TEXT"
}

normalize_name() {
    local raw="$1"
    echo "$raw" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9-]/-/g' | sed 's/--*/-/g' | sed 's/^-//;s/-$//'
}

deploy_worker() {
    local WORKER_NAME="$1"
    if [ -z "$WORKER_NAME" ]; then
        local SUFFIX=$(head /dev/urandom | tr -dc a-z0-9 | head -c 6)
        WORKER_NAME="mtg-relay-${SUFFIX}"
    else
        WORKER_NAME=$(normalize_name "$WORKER_NAME")
        if [ -z "$WORKER_NAME" ]; then WORKER_NAME="mtg-relay-worker"; fi
    fi

    echo -e "\n\033[0;37m  [*] Имя воркера: \033[1;36m${WORKER_NAME}\033[0m"

    local TEMP_DIR=$(mktemp -d /tmp/mirrly-deploy.XXXXXX)
    trap 'rm -rf "$TEMP_DIR"' EXIT

    cat << 'EOF' > "$TEMP_DIR/worker.js"
/**
 * Mirrly TG Proxy - Dedicated Cloudflare Worker for Telegram
 * Specifically optimized for Telegram MTProto & SOCKS5 VoIP calls
 * Protected with Telegram Destination & Port Allowlist (Anti-Open-Relay)
 */
import { connect } from 'cloudflare:sockets';

// Telegram IPv4 Subnets (AS44907, AS62041, AS59930, AS62014, AS211157)
const TG_NETWORKS = [
  ['91.108.0.0', 16],       // AS44907
  ['149.154.160.0', 20],    // AS62041
  ['91.105.192.0', 23],     // AS59930
  ['185.76.151.0', 24],     // AS62014
  ['95.161.64.0', 20]       // AS211157
];

// Telegram IPv6 Subnets
const TG_IPV6_PREFIXES = [
  '2001:b28:f23c:', '2001:b28:f23d:', '2001:b28:f23e:', '2001:b28:f23f:',
  '2001:67c:4e8:',
  '2a0a:f280:', '2a0a:f281:', '2a0a:f282:', '2a0a:f283:',
  '2a0a:f284:', '2a0a:f285:', '2a0a:f286:', '2a0a:f287:'
];

// Telegram Ports (MTProto, SOCKS5, Web, VoIP, CDN)
// Supported Pool: 443 (default primary), 80, 5222, 8080, 8443, 8888, 25
const TG_PORTS = new Set([443, 80, 5222, 8080, 8443, 8888, 25]);

// Telegram Official Domains
const TG_DOMAINS = new Set([
  'telegram.org',
  't.me',
  'telesco.pe',
  'telegram.dog',
  'telegra.ph',
  'cdn-telegram.org',
  'telegram-cdn.org'
]);

const CONNECT_TIMEOUT_MS = 15000;
const MIN_CONNECT_TIMEOUT_MS = 10000;
const MAX_CONNECT_TIMEOUT_MS = 25000;
const MAX_PENDING_WRITE_BYTES = 32 * 1024 * 1024; // 32 MiB Upper Backpressure Limit
const MAX_PENDING_CHUNKS = 1024;
const DOWNLINK_WINDOW_BYTES = 4 * 1024 * 1024; // 4 MiB Wi-Fi default
const MIN_DOWNLINK_WINDOW_BYTES = 1024 * 1024;  // 1 MiB minimum
const MAX_DOWNLINK_WINDOW_BYTES = 8 * 1024 * 1024; // 8 MiB maximum
const DOWNLINK_CHUNK_BYTES = 128 * 1024;
const MIN_DOWNLINK_CHUNK_BYTES = 32 * 1024;
const MAX_DOWNLINK_CHUNK_BYTES = 256 * 1024;

// TCP open timeout for /apiws lazy connect (ms).
// Must be shorter than CONNECT_TIMEOUT_MS to allow fast failure signalling.
const APIWS_TCP_OPEN_TIMEOUT_MS = 12000;

function ipv4ToNumber(ip) {
  const parts = ip.split('.');
  if (parts.length !== 4 || parts.some(part => !/^\d{1,3}$/.test(part) || Number(part) > 255)) return null;
  return parts.reduce((value, part) => ((value << 8) | Number(part)) >>> 0, 0);
}

function extractNat64Ipv4(host) {
  const value = host.trim().toLowerCase();
  if (!value.startsWith('64:ff9b::') && !value.startsWith('0064:ff9b::')) return null;
  const suffix = value.replace(/^(?:00)?64:ff9b::/, '');
  if (/^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(suffix)) {
    return suffix;
  }
  const hexParts = suffix.split(':');
  if (hexParts.length === 2 && /^[0-9a-f]{1,4}$/.test(hexParts[0]) && /^[0-9a-f]{1,4}$/.test(hexParts[1])) {
    const high = parseInt(hexParts[0], 16);
    const low = parseInt(hexParts[1], 16);
    const b0 = (high >>> 8) & 0xff;
    const b1 = high & 0xff;
    const b2 = (low >>> 8) & 0xff;
    const b3 = low & 0xff;
    return b0 + '.' + b1 + '.' + b2 + '.' + b3;
  }
  return null;
}

function isTelegramHost(host) {
  const value = host.trim().toLowerCase().replace(/\.$/, '');
  const nat64v4 = extractNat64Ipv4(value);
  const targetIp = nat64v4 || value;
  const ip = ipv4ToNumber(targetIp);
  if (ip !== null) {
    return TG_NETWORKS.some(([network, bits]) => {
      const base = ipv4ToNumber(network);
      const mask = (0xffffffff << (32 - bits)) >>> 0;
      return (ip & mask) === (base & mask);
    });
  }
  if (value.includes(':')) return TG_IPV6_PREFIXES.some(prefix => value.startsWith(prefix));
  return TG_DOMAINS.has(value) || [
    '.telegram.org', '.t.me', '.telesco.pe', '.telegram.dog',
    '.telegra.ph', '.cdn-telegram.org', '.telegram-cdn.org'
  ].some(suffix => value.endsWith(suffix));
}

function parseTarget(value) {
  if (!value) return null;
  let host, portText;
  if (value.startsWith('[')) {
    const end = value.indexOf(']');
    if (end < 0 || value[end + 1] !== ':') return null;
    host = value.slice(1, end);
    portText = value.slice(end + 2);
  } else {
    const colon = value.lastIndexOf(':');
    if (colon < 1 || value.indexOf(':') !== colon) {
      if (colon === -1 && value.length > 0) {
        return { host: value, port: 443 };
      }
      return null;
    }
    host = value.slice(0, colon);
    portText = value.slice(colon + 1);
  }
  if (!/^\d{1,5}$/.test(portText)) return null;
  const port = Number(portText);
  return port > 0 && port <= 65535 ? { host, port } : null;
}

function error(message, status) {
  return new Response(message, { status, headers: { 'content-type': 'text/plain; charset=utf-8' } });
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // Diagnostics / Port Probe endpoint for testing port latency & filtering
    if (url.pathname === '/probe' || url.pathname === '/diag/port-probe') {
      const hostParam = url.searchParams.get('host') || '149.154.167.50'; // Telegram DC2 default
      const portParam = Number(url.searchParams.get('port') || '443');
      if (!TG_PORTS.has(portParam) || !isTelegramHost(hostParam)) {
        return new Response(JSON.stringify({ ok: false, error: 'Forbidden destination or port' }), {
          status: 403,
          headers: { 'content-type': 'application/json; charset=utf-8' }
        });
      }
      const started = Date.now();
      let probeSocket;
      try {
        probeSocket = connect({ hostname: extractNat64Ipv4(hostParam) || hostParam, port: portParam });
        let timer;
        await Promise.race([
          probeSocket.opened,
          new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('timeout')), 3500); })
        ]);
        clearTimeout(timer);
        const elapsed = Date.now() - started;
        try { await probeSocket.close(); } catch (_) {}
        return new Response(JSON.stringify({
          ok: true,
          port: portParam,
          host: hostParam,
          rtt_ms: elapsed,
          colo: request.cf?.colo || 'Anycast'
        }), {
          status: 200,
          headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' }
        });
      } catch (err) {
        try { if (probeSocket) await probeSocket.close(); } catch (_) {}
        return new Response(JSON.stringify({
          ok: false,
          port: portParam,
          host: hostParam,
          error: err?.message || 'probe_failed'
        }), {
          status: 502,
          headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' }
        });
      }
    }

    const upgrade = request.headers.get('Upgrade');
    if (!upgrade || upgrade.toLowerCase() !== 'websocket') {
      return new Response(
        JSON.stringify({
          status: "online",
          service: "Mirrly TG Proxy Dedicated Worker",
          security: "Protected Telegram Relay (Allowlist Enforced)",
          compatible: ["Telegram MTProto", "Telegram SOCKS5", "Telegram VoIP Calls"],
          supported_ports: Array.from(TG_PORTS),
          version: "1.2.0",
          edge_colo: request.cf?.colo || "Global Anycast",
          timestamp: new Date().toISOString()
        }, null, 2),
        {
          headers: {
            "content-type": "application/json; charset=utf-8",
            "cache-control": "no-store, no-cache, must-revalidate"
          }
        }
      );
    }

    if (url.pathname !== '/tcp' && url.pathname !== '/tcp-v2') {
      if (url.pathname !== '/' && url.pathname !== '/apiws') {
        return error('Not found', 404);
      }
    }

    let target = parseTarget(url.searchParams.get('target')) || parseTarget(url.searchParams.get('dst'));
    if (!target) {
      let dstParam = url.searchParams.get('dst') || url.searchParams.get('target');
      if (dstParam) {
        dstParam = dstParam.trim();
        if (dstParam.startsWith('[')) {
          target = parseTarget(dstParam);
          if (!target) {
            const end = dstParam.indexOf(']');
            if (end > 0) {
              target = { host: dstParam.slice(1, end), port: 443 };
            }
          }
        } else if (dstParam.includes(':') && dstParam.indexOf(':') === dstParam.lastIndexOf(':')) {
          target = parseTarget(dstParam);
        } else {
          target = { host: dstParam, port: 443 };
        }
      }
    }
    if (!target) {
      const hostParam = url.searchParams.get('host');
      const portParam = Number(url.searchParams.get('port') || '443');
      if (hostParam && Number.isSafeInteger(portParam) && portParam > 0 && portParam <= 65535) {
        target = { host: hostParam, port: portParam };
      }
    }

    // Support explicit ?port= override for non-443 Telegram DC ports (5222, 8443, etc.)
    // Applied after target host is resolved, so host-only dst= still works.
    if (target) {
      const explicitPort = url.searchParams.get('port');
      if (explicitPort && /^\d{1,5}$/.test(explicitPort)) {
        const p = Number(explicitPort);
        if (p > 0 && p <= 65535) target = { host: target.host, port: p };
      }
    }

    if (!target) return error('Invalid target', 400);
    if (!TG_PORTS.has(target.port) || !isTelegramHost(target.host)) return error('Telegram destination not allowed', 403);

    const flow = url.pathname === '/tcp-v2' && url.searchParams.get('flow') === '1';

    const rawWindow = Number(url.searchParams.get('window'));
    const downlinkWindow = flow
      ? Math.min(Math.max(Number.isFinite(rawWindow) && rawWindow > 0 ? rawWindow : DOWNLINK_WINDOW_BYTES, MIN_DOWNLINK_WINDOW_BYTES), MAX_DOWNLINK_WINDOW_BYTES)
      : Infinity;

    const rawChunk = Number(url.searchParams.get('chunk'));
    const downlinkChunk = Math.min(Math.max(Number.isFinite(rawChunk) && rawChunk > 0 ? rawChunk : DOWNLINK_CHUNK_BYTES, MIN_DOWNLINK_CHUNK_BYTES), MAX_DOWNLINK_CHUNK_BYTES);

    const rawTimeout = Number(url.searchParams.get('timeout'));
    const connectTimeout = Math.min(Math.max(Number.isFinite(rawTimeout) && rawTimeout > 0 ? rawTimeout : CONNECT_TIMEOUT_MS, MIN_CONNECT_TIMEOUT_MS), MAX_CONNECT_TIMEOUT_MS);

    const connectHost = extractNat64Ipv4(target.host) || target.host;

    // 1. Primary Monolithic Transport: /apiws (and / alias)
    //
    // FIX: startTCP() is now async and the write queue awaits tcpOpenPromise
    // before the first writer.write(). This eliminates the race condition where
    // the 64-byte MTProto SOCKS5 init packet was written to the socket writer
    // before the underlying TCP connection to the Telegram DC was confirmed open.
    // On slow mobile networks (LTE/CGNAT) this caused silent data loss and a
    // hung SOCKS5 session with no error signalling to Telegram.
    if (url.pathname === '/apiws' || url.pathname === '/') {
      const [client, server] = Object.values(new WebSocketPair());
      server.binaryType = 'arraybuffer';
      server.accept();

      let tcp = null;
      let writer = null;
      let reader = null;
      let closed = false;
      // Resolves (void) when TCP is open, rejects if open fails/times out.
      let tcpOpenPromise = null;
      let pendingBytes = 0;
      let writes = Promise.resolve();

      const cleanup = (code = 1000, reason = 'Closed') => {
        if (closed) return;
        closed = true;
        try { void tcp?.close().catch(() => {}); } catch (_) {}
        // Release reader lock so the downlink loop can exit cleanly
        try { reader?.releaseLock(); } catch (_) {}
        if (server.readyState === WebSocket.OPEN || server.readyState === WebSocket.CLOSING) {
          try { server.close(code, reason); } catch (_) {}
        }
      };

      // startTCP is async: returns after initiating connect and setting up
      // tcpOpenPromise. The downlink relay loop and the write queue both await
      // tcpOpenPromise to ensure data is only sent after the TCP handshake.
      async function startTCP() {
        if (tcp) return; // Already initialised — idempotent guard
        try {
          tcp = connect({ hostname: connectHost, port: target.port }, { secureTransport: 'off', allowHalfOpen: true });
          writer = tcp.writable.getWriter();
          reader = tcp.readable.getReader();

          // Build a shared promise that both write queue and downlink loop await.
          // The timeout prevents an infinite hang if the DC is unreachable.
          tcpOpenPromise = Promise.race([
            tcp.opened,
            new Promise((_, reject) =>
              setTimeout(() => reject(new Error('tcp_open_timeout')), APIWS_TCP_OPEN_TIMEOUT_MS)
            )
          ]);

          // Non-blocking: handle unexpected TCP close after open
          tcp.closed?.catch(() => cleanup(1011, 'tcp_closed_unexpectedly'));

          // Launch downlink relay: TCP → WebSocket
          ctx.waitUntil((async () => {
            try {
              // Wait for TCP open before reading — avoids reading from a
              // not-yet-connected socket which would immediately return done=true.
              await tcpOpenPromise;
              while (!closed) {
                const { value, done } = await reader.read();
                if (done || closed) break;
                if (!value?.byteLength) continue;
                if (server.readyState !== WebSocket.OPEN) break;

                // Chunk large buffers (> 64 KiB) to match mobile socket buffers and avoid TCP burst loss
                if (value.byteLength > 65536) {
                  for (let offset = 0; offset < value.byteLength && !closed; offset += 65536) {
                    if (server.readyState !== WebSocket.OPEN) break;
                    server.send(value.subarray(offset, Math.min(offset + 65536, value.byteLength)));
                  }
                } else {
                  server.send(value);
                }
              }
            } catch (_) {
              // tcp_open_timeout or read error — cleanup triggered below
            } finally {
              try { reader.releaseLock(); } catch (_) {}
              cleanup(1000, 'tcp_eof');
            }
          })());

        } catch (e) {
          cleanup(1011, 'tcp_connect_failed');
          throw e; // Propagate to the write queue .catch() handler
        }
      }


      server.addEventListener('message', event => {
        if (closed) return;
        const data = event.data instanceof ArrayBuffer
          ? new Uint8Array(event.data)
          : ArrayBuffer.isView(event.data)
            ? new Uint8Array(event.data.buffer, event.data.byteOffset, event.data.byteLength)
            : typeof event.data === 'string'
              ? new TextEncoder().encode(event.data)
              : null;
        if (!data || !data.byteLength) return;

        if (pendingBytes + data.byteLength > MAX_PENDING_WRITE_BYTES) {
          cleanup(1009, 'tcp_backlog_limit');
          return;
        }

        pendingBytes += data.byteLength;
        writes = writes.then(async () => {
          if (closed) return;
          // Ensure TCP socket is created (idempotent)
          await startTCP();
          // KEY FIX: await TCP open confirmation before any write.
          // Without this, the first 64-byte MTProto SOCKS5 init frame arrives
          // at writer.write() while TCP handshake is still in progress.
          // cloudflare:sockets buffers it internally, but on congested paths
          // the handshake can time out and the buffered write is silently dropped.
          if (tcpOpenPromise) await tcpOpenPromise;
          if (!closed && writer) await writer.write(data);
        }).catch(() => {
          cleanup(1011, 'ws_to_tcp_failed');
        }).finally(() => {
          pendingBytes -= data.byteLength;
        });
      });

      server.addEventListener('close', () => cleanup());
      server.addEventListener('error', () => cleanup(1011, 'WebSocket error'));

      return new Response(null, { status: 101, webSocket: client });
    }

    // 2. Fallback Transports: /tcp-v2 (flow-controlled) & /tcp (legacy)
    let tcp;
    try {
      tcp = connect({ hostname: connectHost, port: target.port }, { secureTransport: "off", allowHalfOpen: true });
      let timer;
      try {
        await Promise.race([
          tcp.opened,
          new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('connect timeout')), connectTimeout); })
        ]);
      } finally {
        clearTimeout(timer);
      }
    } catch (_) {
      try { if (tcp) await tcp.close(); } catch (_) {}
      return error('Telegram connection failed', 502);
    }

    const [client, server] = Object.values(new WebSocketPair());
    server.binaryType = 'arraybuffer';
    server.accept();
    const writer = tcp.writable.getWriter();
    const reader = tcp.readable.getReader();
    let closed = false;
    let pendingBytes = 0;
    let pendingChunks = 0;
    let sentBytes = 0;
    let ackedBytes = 0;
    let resume;
    const wake = () => { resume?.(); resume = undefined; };

    const cleanup = (code = 1000, reason = 'Closed') => {
      if (closed) return;
      closed = true;
      wake();
      try { void tcp.close().catch(() => {}); } catch (_) {}
      if (server.readyState === WebSocket.OPEN || server.readyState === WebSocket.CLOSING) {
        try { server.close(code, reason); } catch (_) {}
      }
    };

    // Sequential Promise Queue with Backpressure Enforcement (MAX_PENDING_WRITE_BYTES = 32 MiB)
    let writes = Promise.resolve();
    server.addEventListener('message', event => {
      if (closed) return;
      if (flow && typeof event.data === 'string') {
        const match = /^ack:(\d+)$/.exec(event.data);
        const ack = match ? Number(match[1]) : NaN;
        if (!Number.isSafeInteger(ack)) return cleanup(1002, 'Invalid ack');
        ackedBytes = Math.min(Math.max(ack, ackedBytes), sentBytes);
        wake();
        return;
      }
      const data = event.data instanceof ArrayBuffer
        ? new Uint8Array(event.data)
        : ArrayBuffer.isView(event.data)
          ? new Uint8Array(event.data.buffer, event.data.byteOffset, event.data.byteLength)
          : typeof event.data === 'string'
            ? new TextEncoder().encode(event.data)
            : null;
      if (!data) return cleanup(1003, 'Binary messages required');

      if (pendingChunks >= MAX_PENDING_CHUNKS) {
        cleanup(1008, 'write_queue_overflow');
        return;
      }

      if (pendingBytes + data.byteLength > MAX_PENDING_WRITE_BYTES) {
        cleanup(1009, 'tcp_backlog_limit');
        return;
      }

      pendingChunks++;
      pendingBytes += data.byteLength;
      writes = writes.then(async () => {
        if (!closed) await writer.write(data);
      }).catch(() => {
        cleanup(1011, 'ws_to_tcp_failed');
      }).finally(() => {
        pendingChunks--;
        pendingBytes -= data.byteLength;
      });
    });
    server.addEventListener('close', () => cleanup());
    server.addEventListener('error', () => cleanup(1011, 'WebSocket error'));

    if (url.pathname === '/tcp-v2') server.send(new Uint8Array([0x56, flow ? 0x03 : 0x02, 0x00, 0x00]));

    ctx.waitUntil((async () => {
      try {
        if (!flow) {
          while (!closed) {
            const { value, done } = await reader.read();
            if (done) break;
            if (server.readyState !== WebSocket.OPEN) return;
            if (value.byteLength > 65536) {
              for (let offset = 0; offset < value.byteLength && !closed; offset += 65536) {
                if (server.readyState !== WebSocket.OPEN) return;
                server.send(value.subarray(offset, Math.min(offset + 65536, value.byteLength)));
              }
            } else {
              server.send(value);
            }
          }
        } else {
          while (!closed) {
            while (flow && sentBytes - ackedBytes >= downlinkWindow && !closed) {
              await new Promise(resolve => { resume = resolve; });
            }
            if (closed) break;
            const { value, done } = await reader.read();
            if (done) break;
            for (let offset = 0; offset < value.byteLength && !closed;) {
              while (flow && sentBytes - ackedBytes >= downlinkWindow && !closed) {
                await new Promise(resolve => { resume = resolve; });
              }
              if (server.readyState !== WebSocket.OPEN) return;
              const size = Math.min(value.byteLength - offset, downlinkChunk,
                flow ? downlinkWindow - (sentBytes - ackedBytes) : Infinity);
              server.send(value.subarray(offset, offset + size));
              offset += size;
              if (flow) sentBytes += size;
            }
          }
        }
      } catch (_) {
        cleanup(1011, 'TCP relay failed');
      } finally {
        cleanup();
      }
    })());

    return new Response(null, { status: 101, webSocket: client });
  }
};
EOF

    cat << EOF > "$TEMP_DIR/wrangler.toml"
name = "${WORKER_NAME}"
main = "worker.js"
compatibility_date = "2026-09-29"
EOF

    cd "$TEMP_DIR"

    echo -e "\033[1;33m  [1/2] Проверка авторизации Cloudflare...\033[0m"
    if ! invoke_wrangler whoami 2>/dev/null | grep -q "You are logged in"; then
        echo -e "\033[1;33m  [!] Не авторизован. Открывается браузер для входа в Cloudflare...\033[0m"
        invoke_wrangler login
    else
        echo -e "\033[1;32m  [OK] Авторизован в Cloudflare\033[0m"
    fi

    echo -e "\n\033[1;36m  [2/2] Публикую воркер в Cloudflare...\033[0m\n"
    local DEPLOY_LOG=$(invoke_wrangler deploy 2>&1)
    echo "$DEPLOY_LOG"

    cd "$SCRIPT_DIR"

    local DOMAIN=$(echo "$DEPLOY_LOG" | grep -oE 'https://[a-zA-Z0-9.-]+\.workers\.dev' | head -n 1 | sed 's#https://##')

    if [ -n "$DOMAIN" ]; then
        local DEEP_LINK="mirrly://worker?name=${WORKER_NAME}&domain=${DOMAIN}"
        echo -e "\n\033[1;32m  ╔══════════════════════════════════════════════════════════════════╗\033[0m"
        echo -e "\033[1;32m  ║                 Воркер успешно задеплоен!                        ║\033[0m"
        echo -e "\033[1;32m  ╚══════════════════════════════════════════════════════════════════╝\033[0m\n"
        echo -e "  \033[0;37mДомен воркера: \033[1;37m${DOMAIN}\033[0m"
        echo -e "  \033[0;37mApp Ссылка:    \033[1;36m${DEEP_LINK}\033[0m\n"
        
        echo -e "\033[1;33m  Наведите камеру смартфона или сканер в Mirrly TG Proxy:\033[0m"
        show_qr_code "$DEEP_LINK"

        local DATE_STR=$(date "+%Y-%m-%d %H:%M:%S")
        echo "${DATE_STR} | Name: ${WORKER_NAME} | Domain: ${DOMAIN} | Link: ${DEEP_LINK}" >> "$HISTORY_FILE"
        echo -e "\n\033[0;37m  [OK] Запись сохранена в: ${HISTORY_FILE}\033[0m"
    else
        format_cloudflare_error "$DEPLOY_LOG"
        echo -e "\n\033[1;31m  [X] Ошибка публикации воркера.\033[0m\n"
    fi
}

test_workers_health() {
    show_banner
    echo -e "\033[1;36m  Проверка доступности воркеров из истории (${HISTORY_FILE}):\033[0m"
    echo -e "\033[0;37m  ──────────────────────────────────────────────────────────────────\033[0m"
    if [ ! -f "$HISTORY_FILE" ]; then
        echo -e "  (История пуста)"
        echo -e "\033[0;37m  ──────────────────────────────────────────────────────────────────\033[0m\n"
        return
    fi
    local TOTAL=0
    local ONLINE=0
    while IFS= read -r line; do
        DOMAIN=$(echo "$line" | grep -oE 'Domain: [^ ]+' | awk '{print $2}')
        if [ -n "$DOMAIN" ]; then
            TOTAL=$((TOTAL + 1))
            echo -n "  • $DOMAIN ... "
            START_TIME=$(date +%s%N)
            HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -H "Upgrade: websocket" --connect-timeout 6 "https://${DOMAIN}/" || echo "000")
            END_TIME=$(date +%s%N)
            RTT_MS=$(( (END_TIME - START_TIME) / 1000000 ))
            if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "426" ] || [ "$HTTP_CODE" = "400" ] || [ "$HTTP_CODE" = "101" ]; then
                echo -e "\033[1;32m[ONLINE] WSS Relay Ready (${HTTP_CODE}, ${RTT_MS} ms)\033[0m"
                ONLINE=$((ONLINE + 1))
            elif [ "$HTTP_CODE" = "403" ]; then
                echo -e "\033[1;32m[ONLINE/PROTECTED] Relay Ready (403, ${RTT_MS} ms)\033[0m"
                ONLINE=$((ONLINE + 1))
            else
                echo -e "\033[1;31m[UNREACHABLE] HTTP ${HTTP_CODE} (${RTT_MS} ms)\033[0m"
            fi
        fi
    done < "$HISTORY_FILE"
    echo -e "\033[0;37m  ──────────────────────────────────────────────────────────────────\033[0m"
    echo -e "  Итог: \033[1;32m${ONLINE}\033[0m из \033[1;37m${TOTAL}\033[0m узлов активны.\n"
}

show_saved_workers() {
    show_banner
    echo -e "\033[1;36m  Ваши созданные воркеры (${HISTORY_FILE}):\033[0m"
    echo -e "\033[0;37m  ──────────────────────────────────────────────────────────────────\033[0m"
    if [ -f "$HISTORY_FILE" ]; then
        nl -ba -w2 -s'. ' "$HISTORY_FILE"
        echo -e "\033[0;37m  ──────────────────────────────────────────────────────────────────\033[0m"
        echo -e "  \033[1;33m[1-N]\033[0m Показать QR-код для воркера | \033[0;37m[Enter] Назад в меню\033[0m"
        read -r -p "  Ваш выбор: " CHOICE
        if [[ "$CHOICE" =~ ^[0-9]+$ ]]; then
            LINE=$(sed -n "${CHOICE}p" "$HISTORY_FILE")
            LINK=$(echo "$LINE" | grep -oE 'Link: mirrly://[^\ ]+' | sed 's/Link: //')
            if [ -n "$LINK" ]; then
                echo -e "\n\033[1;32m  QR-код для выбранного узла:\033[0m"
                show_qr_code "$LINK"
                read -r -p "  Нажмите Enter для возврата..." _
            fi
        fi
    else
        echo -e "  (Список пуст)"
        echo -e "\033[0;37m  ──────────────────────────────────────────────────────────────────\033[0m\n"
    fi
}

remove_worker() {
    show_banner
    echo -e "\033[1;33m  Удаление воркера из Cloudflare:\033[0m"
    echo -e "\033[0;37m  ──────────────────────────────────────────────────────────────────\033[0m"
    read -r -p "  Введите имя воркера для удаления: " WNAME
    if [ -z "$WNAME" ]; then return; fi
    WNAME=$(normalize_name "$WNAME")
    read -r -p "  Вы уверены, что хотите удалить '$WNAME'? (y/N): " CONFIRM
    if [[ "$CONFIRM" =~ ^[yY]$ ]]; then
        echo -e "\n\033[1;36m  [*] Отправка запроса на удаление '$WNAME'...\033[0m"
        invoke_wrangler delete "$WNAME" --force
    fi
}

switch_account() {
    echo -e "\n\033[1;33m  [*] Сброс текущей авторизации Cloudflare...\033[0m"
    invoke_wrangler logout
    echo -e "\033[1;32m  [OK] Сессия Cloudflare сброшена.\033[0m\n"
}

while true; do
    show_banner
    echo -e "\033[1;33m  Выберите действие:\033[0m"
    echo -e "  \033[1;37m[1]\033[0m Создать новый воркер (авто-имя, 1 клик)"
    echo -e "  \033[1;37m[2]\033[0m Создать воркер с моим именем"
    echo -e "  \033[1;37m[3]\033[0m Проверить доступность и пинг воркеров (Health Check)"
    echo -e "  \033[1;37m[4]\033[0m Просмотреть список и QR-коды моих воркеров"
    echo -e "  \033[1;37m[5]\033[0m Удалить воркер из Cloudflare"
    echo -e "  \033[1;37m[6]\033[0m Сменить аккаунт Cloudflare"
    echo -e "  \033[0;37m[0] Выход\033[0m\n"
    read -r -p "  Ваш выбор (0-6): " ACTION

    case "$ACTION" in
        1)
            deploy_worker ""
            read -r -p "  Нажмите Enter для возврата в меню..." _
            ;;
        2)
            read -r -p "  Введите желаемое имя воркера: " CUSTOM_NAME
            if [ -n "$CUSTOM_NAME" ]; then
                deploy_worker "$CUSTOM_NAME"
            fi
            read -r -p "  Нажмите Enter для возврата в меню..." _
            ;;
        3)
            test_workers_health
            read -r -p "  Нажмите Enter для возврата в меню..." _
            ;;
        4)
            show_saved_workers
            ;;
        5)
            remove_worker
            read -r -p "  Нажмите Enter для возврата в меню..." _
            ;;
        6)
            switch_account
            read -r -p "  Нажмите Enter для возврата в меню..." _
            ;;
        0)
            echo -e "\n\033[1;36m  До свидания!\033[0m\n"
            exit 0
            ;;
        *)
            echo -e "\033[1;33m  [!] Некорректный выбор. Повторите ввод.\033[0m"
            sleep 1
            ;;
    esac
done
