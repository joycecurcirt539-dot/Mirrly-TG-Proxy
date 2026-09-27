/**
 * Mirrly TG Proxy - Dedicated Cloudflare Worker for Telegram
 * Dual Mode: VLESS over WebSocket & TCP over WebSocket (TLS 1.3 Anycast)
 * Specifically optimized for Telegram MTProto & SOCKS5 VoIP calls
 * Protected with Telegram Destination & Port Allowlist (Anti-Open-Relay)
 */
import { connect } from 'cloudflare:sockets';

// Default UUID for VLESS over WebSocket
const DEFAULT_UUID = 'd342d11e-d424-4583-b36e-524ab1f0afa4';

// Telegram IPv4 Subnets (AS44907, AS62041, AS59930, AS62014)
const TG_IPV4_SUBNETS = [
  { ip: "91.108.0.0", mask: 16 },    // Telegram AS44907 (полный диапазон 91.108.0.0 - 91.108.255.255)
  { ip: "149.154.160.0", mask: 20 }, // Telegram AS62041 (149.154.160.0 - 149.154.175.255)
  { ip: "91.105.192.0", mask: 23 },  // Telegram AS59930 (91.105.192.0 - 91.105.193.255)
  { ip: "185.76.151.0", mask: 24 }   // Telegram AS62014 (185.76.151.0 - 185.76.151.255)
];

// Telegram IPv6 Subnets
const TG_IPV6_PREFIXES = [
  "2001:b28:f23d:",
  "2001:b28:f23f:",
  "2001:67c:4e8:"
];

// Telegram Ports (MTProto, SOCKS5, Web, VoIP, CDN)
const ALLOWED_PORTS = new Set([80, 443, 5222, 8443, 8888, 8080]);

// Telegram Official Domains
const TG_EXACT_DOMAINS = new Set([
  "telegram.org",
  "t.me",
  "telesco.pe",
  "telegram.dog",
  "telegra.ph",
  "cdn-telegram.org"
]);

function ipToLong(ip) {
  const parts = ip.split('.');
  if (parts.length !== 4) return null;
  let res = 0;
  for (let i = 0; i < 4; i++) {
    const octet = parseInt(parts[i], 10);
    if (isNaN(octet) || octet < 0 || octet > 255) return null;
    res = ((res << 8) + octet) >>> 0;
  }
  return res;
}

function isTelegramIp(ipStr) {
  const cleanIp = ipStr.trim().toLowerCase();
  
  // IPv4 check
  if (/^(\d{1,3}\.){3}\d{1,3}$/.test(cleanIp)) {
    const targetLong = ipToLong(cleanIp);
    if (targetLong === null) return false;
    for (const net of TG_IPV4_SUBNETS) {
      const netLong = ipToLong(net.ip);
      const maskLong = (0xFFFFFFFF << (32 - net.mask)) >>> 0;
      if ((targetLong & maskLong) === (netLong & maskLong)) {
        return true;
      }
    }
    return false;
  }

  // IPv6 check
  for (const prefix of TG_IPV6_PREFIXES) {
    if (cleanIp.startsWith(prefix)) {
      return true;
    }
  }

  return false;
}

function isTelegramDomain(domain) {
  const d = domain.trim().toLowerCase();
  if (TG_EXACT_DOMAINS.has(d)) return true;
  return (
    d.endsWith(".telegram.org") ||
    d.endsWith(".t.me") ||
    d.endsWith(".telesco.pe") ||
    d.endsWith(".telegram.dog") ||
    d.endsWith(".telegra.ph") ||
    d.endsWith(".cdn-telegram.org") ||
    d.endsWith(".telegram-cdn.org")
  );
}

function isTelegramDestination(host) {
  if (!host) return false;
  return isTelegramIp(host) || isTelegramDomain(host);
}

function formatUuid(bytes) {
  const hex = [];
  for (let i = 0; i < bytes.length; i++) {
    hex.push((bytes[i] < 16 ? '0' : '') + bytes[i].toString(16));
  }
  return [
    hex.slice(0, 4).join(''),
    hex.slice(4, 6).join(''),
    hex.slice(6, 8).join(''),
    hex.slice(8, 10).join(''),
    hex.slice(10, 16).join('')
  ].join('-');
}

function decodeBase64Url(str) {
  if (!str) return null;
  let b64 = str.replace(/-/g, '+').replace(/_/g, '/');
  while (b64.length % 4 !== 0) {
    b64 += '=';
  }
  try {
    const binStr = atob(b64);
    const bytes = new Uint8Array(binStr.length);
    for (let i = 0; i < binStr.length; i++) {
      bytes[i] = binStr.charCodeAt(i);
    }
    return bytes;
  } catch (_) {
    return null;
  }
}

function parseVlessHeader(input, expectedUuid) {
  const bytes = input instanceof Uint8Array ? input : new Uint8Array(input);
  if (bytes.byteLength < 24) {
    return { hasError: true, message: "VLESS header too short" };
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const version = view.getUint8(0);
  if (version !== 0) {
    return { hasError: true, message: `Unsupported VLESS version: ${version}` };
  }

  const clientUuid = formatUuid(new Uint8Array(bytes.buffer, bytes.byteOffset + 1, 16));
  if (expectedUuid && clientUuid.toLowerCase() !== expectedUuid.toLowerCase()) {
    return { hasError: true, message: "Invalid VLESS UUID" };
  }

  const addonLen = view.getUint8(17);
  let cursor = 18 + addonLen;
  if (bytes.byteLength < cursor + 4) {
    return { hasError: true, message: "Malformed VLESS header" };
  }

  const command = view.getUint8(cursor); // 1 = TCP, 2 = UDP
  cursor += 1;
  const port = view.getUint16(cursor, false); // Big-Endian
  cursor += 2;
  const addrType = view.getUint8(cursor);
  cursor += 1;

  let hostname = '';
  if (addrType === 1) {
    // IPv4
    if (bytes.byteLength < cursor + 4) return { hasError: true, message: "Truncated IPv4" };
    const ip = new Uint8Array(bytes.buffer, bytes.byteOffset + cursor, 4);
    hostname = ip.join('.');
    cursor += 4;
  } else if (addrType === 2) {
    // Domain
    if (bytes.byteLength < cursor + 1) return { hasError: true, message: "Truncated domain length" };
    const len = view.getUint8(cursor);
    cursor += 1;
    if (bytes.byteLength < cursor + len) return { hasError: true, message: "Truncated domain" };
    hostname = new TextDecoder().decode(new Uint8Array(bytes.buffer, bytes.byteOffset + cursor, len));
    cursor += len;
  } else if (addrType === 3) {
    // IPv6
    if (bytes.byteLength < cursor + 16) return { hasError: true, message: "Truncated IPv6" };
    const parts = [];
    for (let i = 0; i < 8; i++) {
      parts.push(view.getUint16(cursor + i * 2, false).toString(16));
    }
    hostname = parts.join(':');
    cursor += 16;
  } else {
    return { hasError: true, message: `Unknown address type: ${addrType}` };
  }

  const rawPayload = new Uint8Array(bytes.buffer, bytes.byteOffset + cursor, bytes.byteLength - cursor);
  return {
    hasError: false,
    version,
    clientUuid,
    command,
    port,
    hostname,
    rawPayload
  };
}

// Flow Control & Buffer Bounds (MOB-010)
const MAX_WS_MESSAGE_BYTES = 256 * 1024;      // 256 KiB max incoming single WS message
const MAX_PENDING_WRITE_BYTES = 4 * 1024 * 1024; // 4 MiB high watermark for uplink write buffer
const UPLINK_LOW_WATERMARK = 1024 * 1024;      // 1 MiB low watermark
const DOWNLINK_HIGH_WATERMARK = 512 * 1024;   // 512 KiB high watermark for downlink WS buffer
const DOWNLINK_LOW_WATERMARK = 128 * 1024;    // 128 KiB low watermark
const MAX_DOWNLINK_CHUNK = 32 * 1024;         // 32 KiB max chunk per WS frame
const TCP_WRITE_TIMEOUT_MS = 10000;           // 10s write timeout before closing stalled socket
const SLOW_READER_SOAK_TIMEOUT_MS = 30000;    // 30s slow-reader soak timeout

/**
 * Sequential FIFO Writer with Bounded Watermark and Write Timeout.
 * Prevents heap growth on slow uplinks and guarantees strict byte order.
 */
function createBoundedSequentialWriter(tcpWriter, serverWs, onCleanup) {
  let pendingWriteBytes = 0;
  let isWriting = false;
  let isStopped = false;
  const writeQueue = [];

  const pump = async () => {
    if (isWriting || isStopped) return;
    isWriting = true;
    while (writeQueue.length > 0 && !isStopped) {
      const item = writeQueue.shift();
      let chunk;
      try {
        if (typeof Blob !== 'undefined' && item.raw instanceof Blob) {
          const buf = await item.raw.arrayBuffer();
          chunk = new Uint8Array(buf);
        } else if (item.raw instanceof ArrayBuffer) {
          chunk = new Uint8Array(item.raw);
        } else if (ArrayBuffer.isView(item.raw)) {
          chunk = new Uint8Array(item.raw.buffer, item.raw.byteOffset, item.raw.byteLength);
        } else {
          chunk = new Uint8Array(item.raw);
        }
      } catch (err) {
        if (!isStopped) {
          isStopped = true;
          try { serverWs.close(1011, "Payload decode failure"); } catch (_) {}
          onCleanup(1011, "Payload decode failure");
        }
        return;
      }

      let timer;
      try {
        const writePromise = tcpWriter.write(chunk);
        const timeoutPromise = new Promise((_, reject) => {
          timer = setTimeout(() => reject(new Error("TCP write timeout")), TCP_WRITE_TIMEOUT_MS);
        });
        await Promise.race([writePromise, timeoutPromise]);
      } catch (err) {
        if (!isStopped) {
          isStopped = true;
          try { serverWs.close(1011, "TCP write failure"); } catch (_) {}
          onCleanup(1011, "TCP write failure");
        }
        return;
      } finally {
        if (timer) clearTimeout(timer);
        pendingWriteBytes = Math.max(0, pendingWriteBytes - item.byteLength);
      }
    }
    isWriting = false;
  };

  return {
    enqueue(data) {
      if (isStopped) return false;

      let raw = data;
      let byteLength = 0;
      if (typeof Blob !== 'undefined' && raw instanceof Blob) {
        byteLength = raw.size;
      } else if (typeof raw === 'string') {
        raw = new TextEncoder().encode(raw);
        byteLength = raw.byteLength;
      } else if (raw instanceof ArrayBuffer) {
        byteLength = raw.byteLength;
      } else if (ArrayBuffer.isView(raw)) {
        byteLength = raw.byteLength;
      } else if (raw && typeof raw.byteLength === 'number') {
        byteLength = raw.byteLength;
      }

      if (byteLength > MAX_WS_MESSAGE_BYTES) {
        isStopped = true;
        try { serverWs.close(1009, "Message exceeds max size (256 KB)"); } catch (_) {}
        onCleanup(1009, "Message exceeds max size (256 KB)");
        return false;
      }
      if (pendingWriteBytes + byteLength > MAX_PENDING_WRITE_BYTES ||
          pendingWriteBytes + data.byteLength > MAX_PENDING_WRITE_BYTES) {
        isStopped = true;
        try { serverWs.close(1009, "Uplink write buffer overflow (4 MB)"); } catch (_) {}
        onCleanup(1009, "Uplink write buffer overflow (4 MB)");
        return false;
      }
      pendingWriteBytes += byteLength;
      writeQueue.push({ raw, byteLength });
      pump();
      return true;
    },
    stop() {
      isStopped = true;
      writeQueue.length = 0;
      pendingWriteBytes = 0;
    },
    getPendingBytes() {
      return pendingWriteBytes;
    }
  };
}

async function pumpTcpToWebSocket(tcpReader, serverWs, isClosedCheck, onCleanup) {
  try {
    while (true) {
      if (isClosedCheck() || serverWs.readyState !== WebSocket.OPEN) break;

      // Backpressure: pause TCP reading if WebSocket client is slow
      if (typeof serverWs.bufferedAmount === 'number' && serverWs.bufferedAmount > DOWNLINK_HIGH_WATERMARK) {
        const pauseStart = Date.now();
        while (serverWs.bufferedAmount > DOWNLINK_LOW_WATERMARK) {
          if (isClosedCheck() || serverWs.readyState !== WebSocket.OPEN) break;
          if (Date.now() - pauseStart > SLOW_READER_SOAK_TIMEOUT_MS) {
            try { serverWs.close(1008, "Downlink slow reader timeout"); } catch (_) {}
            onCleanup();
            return;
          }
          await new Promise(r => setTimeout(r, 25));
        }
      }

      const { value, done } = await tcpReader.read();
      if (done) break;

      if (value && value.byteLength > 0 && serverWs.readyState === WebSocket.OPEN) {
        if (value.byteLength > MAX_DOWNLINK_CHUNK) {
          for (let offset = 0; offset < value.byteLength; offset += MAX_DOWNLINK_CHUNK) {
            if (isClosedCheck() || serverWs.readyState !== WebSocket.OPEN) break;
            if (typeof serverWs.bufferedAmount === 'number' && serverWs.bufferedAmount > DOWNLINK_HIGH_WATERMARK) {
              const chunkPause = Date.now();
              while (serverWs.bufferedAmount > DOWNLINK_LOW_WATERMARK) {
                if (isClosedCheck() || serverWs.readyState !== WebSocket.OPEN) break;
                if (Date.now() - chunkPause > SLOW_READER_SOAK_TIMEOUT_MS) {
                  try { serverWs.close(1008, "Downlink slow reader timeout"); } catch (_) {}
                  onCleanup();
                  return;
                }
                await new Promise(r => setTimeout(r, 25));
              }
            }
            const chunk = value.subarray(offset, Math.min(offset + MAX_DOWNLINK_CHUNK, value.byteLength));
            serverWs.send(chunk);
          }
        } else {
          serverWs.send(value);
        }
      }
    }
  } catch (_) {
  } finally {
    onCleanup();
    try { serverWs.close(1000, "Upstream closed"); } catch (_) {}
  }
}

async function handleVlessWebSocket(clientWs, serverWs, expectedUuid, earlyDataHeader) {
  serverWs.binaryType = "arraybuffer";
  serverWs.accept();

  let tcpSocket = null;
  let tcpWriter = null;
  let tcpReader = null;
  let writer = null;
  let isClosed = false;

  const cleanup = (code = 1000, reason = "Normal Closure") => {
    if (isClosed) return;
    isClosed = true;
    if (writer) writer.stop();
    try { if (tcpWriter) tcpWriter.close(); } catch (_) {}
    try { if (tcpSocket) tcpSocket.close(); } catch (_) {}
    try {
      if (serverWs.readyState === 1 || serverWs.readyState === 0) {
        serverWs.close(code, reason);
      }
    } catch (_) {}
  };

  serverWs.addEventListener('close', () => cleanup(1000, "Client closed"));
  serverWs.addEventListener('error', () => cleanup(1011, "WebSocket error"));

  const processFirstMessage = async (data) => {
    const parsed = parseVlessHeader(data, expectedUuid);
    if (parsed.hasError) {
      serverWs.close(1008, parsed.message);
      return;
    }

    const targetHost = parsed.hostname;
    const targetPort = parsed.port;

    // Security check
    if (!ALLOWED_PORTS.has(targetPort)) {
      serverWs.close(1008, "Forbidden: Port not allowed");
      return;
    }
    if (!isTelegramDestination(targetHost)) {
      serverWs.close(1008, "Forbidden: Destination host not allowed");
      return;
    }

    try {
      tcpSocket = connect({
        hostname: targetHost,
        port: targetPort
      });
      tcpWriter = tcpSocket.writable.getWriter();
      tcpReader = tcpSocket.readable.getReader();
      writer = createBoundedSequentialWriter(tcpWriter, serverWs, cleanup);

      tcpSocket.closed.then(() => {
        cleanup(1000, "Upstream closed");
      }).catch(() => {
        cleanup(1011, "Upstream TCP error");
      });
    } catch (err) {
      serverWs.close(1011, "Connect failed: " + err.message);
      return;
    }

    // Send VLESS response header: [version 0, addon length 0]
    serverWs.send(new Uint8Array([0, 0]));

    // If there's initial payload in the first message, write it to TCP
    if (parsed.rawPayload && parsed.rawPayload.byteLength > 0) {
      writer.enqueue(parsed.rawPayload);
    }

    // Start reading from TCP and piping to WebSocket with backpressure
    pumpTcpToWebSocket(tcpReader, serverWs, () => isClosed, cleanup);
  };

  // Check and extract 0-RTT early data from Sec-WebSocket-Protocol header
  let earlyData = null;
  if (earlyDataHeader) {
    const protocols = earlyDataHeader.split(',').map(p => p.trim());
    for (const proto of protocols) {
      if (proto && proto.toLowerCase() !== 'binary') {
        const decoded = decodeBase64Url(proto);
        if (decoded && decoded.byteLength > 0) {
          earlyData = decoded;
          break;
        }
      }
    }
  }

  let isFirstMessage = !earlyData;
  const initPromise = earlyData ? processFirstMessage(earlyData) : Promise.resolve();

  serverWs.addEventListener('message', async (event) => {
    if (isClosed) return;
    try {
      await initPromise;
      if (isClosed) return;

      if (isFirstMessage) {
        isFirstMessage = false;
        let raw = event.data;
        let data;
        if (typeof Blob !== 'undefined' && raw instanceof Blob) {
          const buf = await raw.arrayBuffer();
          data = new Uint8Array(buf);
        } else if (typeof raw === 'string') {
          data = new TextEncoder().encode(raw);
        } else if (raw instanceof ArrayBuffer) {
          data = new Uint8Array(raw);
        } else if (ArrayBuffer.isView(raw)) {
          data = new Uint8Array(raw.buffer, raw.byteOffset, raw.byteLength);
        } else {
          data = new Uint8Array(raw);
        }
        await processFirstMessage(data);
      } else {
        writer.enqueue(event.data);
      }
    } catch (_) {
      cleanup(1011, "Message processing error");
    }
  });

  return new Response(null, {
    status: 101,
    webSocket: clientWs
  });
}

function handleStatusPage(request, url, configuredUuid) {
  const isJson = url.searchParams.has('json') || (request.headers.get('accept') || '').includes('application/json');
  const colo = (request.cf && request.cf.colo) || 'Anycast';
  const country = (request.cf && request.cf.country) || 'WW';
  const asn = (request.cf && request.cf.asn) || 13335;
  const ip = request.headers.get('cf-connecting-ip') || '127.0.0.1';

  if (isJson) {
    return new Response(JSON.stringify({
      status: "online",
      service: "Mirrly TG Proxy Dedicated Worker",
      node: {
        edge_colo: colo,
        country: country,
        asn: asn,
        tls: "TLS 1.3 Anycast",
        http_version: (request.cf && request.cf.httpProtocol) || "HTTP/3"
      },
      protocols: [
        { name: "Telegram MTProto Relay", status: "operational", port: 443, transport: "WSS" },
        { name: "Telegram SOCKS5 Relay", status: "operational", port: 10808, transport: "TCP-over-WS v2" },
        { name: "VLESS over WebSocket", status: "operational", port: 443, transport: "WSS" }
      ],
      security: {
        anti_open_relay: "active",
        allowed_destinations: ["AS44907", "AS62041", "AS59930", "AS62014"],
        allowed_ports: [80, 443, 5222, 8443, 8888, 8080]
      },
      version: "2.0.0",
      timestamp: new Date().toISOString()
    }, null, 2), {
      headers: {
        "content-type": "application/json; charset=utf-8",
        "cache-control": "no-store, no-cache, must-revalidate",
        "access-control-allow-origin": "*"
      }
    });
  }

  const html = `<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>Mirrly Anycast Gateway &bull; Telegram Relay</title>
<style>
  :root {
    --bg: #07090e;
    --card-bg: rgba(14, 20, 32, 0.72);
    --border: rgba(38, 52, 78, 0.55);
    --border-glow: rgba(0, 245, 212, 0.35);
    --text: #f1f5f9;
    --text-muted: #94a3b8;
    --accent-cyan: #00f5d4;
    --accent-purple: #a855f7;
    --accent-emerald: #10b981;
    --accent-orange: #f59e0b;
    --font-sans: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    --font-mono: "JetBrains Mono", "SF Mono", Consolas, "Courier New", monospace;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    background-color: var(--bg);
    color: var(--text);
    font-family: var(--font-sans);
    min-height: 100vh;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 24px 16px;
    background-image: 
      radial-gradient(circle at 50% 0%, rgba(0, 245, 212, 0.08) 0%, transparent 60%),
      radial-gradient(circle at 100% 100%, rgba(168, 85, 247, 0.06) 0%, transparent 50%),
      linear-gradient(rgba(255, 255, 255, 0.015) 1px, transparent 1px),
      linear-gradient(90deg, rgba(255, 255, 255, 0.015) 1px, transparent 1px);
    background-size: 100% 100%, 100% 100%, 32px 32px, 32px 32px;
    position: relative;
    overflow-x: hidden;
  }
  .container {
    width: 100%;
    max-width: 480px;
    display: flex;
    flex-direction: column;
    gap: 16px;
    z-index: 2;
  }
  .glass-card {
    background: var(--card-bg);
    border: 1px solid var(--border);
    backdrop-filter: blur(20px);
    -webkit-backdrop-filter: blur(20px);
    border-radius: 20px;
    padding: 20px 22px;
    box-shadow: 0 10px 30px rgba(0, 0, 0, 0.45);
    transition: transform 0.2s ease, border-color 0.2s ease;
  }
  .glass-card:hover {
    border-color: var(--border-glow);
  }
  .header-card {
    text-align: center;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 12px;
    position: relative;
    padding-top: 26px;
  }
  .logo-wrap {
    width: 60px;
    height: 60px;
    border-radius: 50%;
    background: radial-gradient(circle, rgba(0, 245, 212, 0.2) 0%, rgba(168, 85, 247, 0.08) 70%);
    border: 1px solid rgba(0, 245, 212, 0.4);
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    user-select: none;
    transition: transform 0.25s cubic-bezier(0.34, 1.56, 0.64, 1);
    box-shadow: 0 0 20px rgba(0, 245, 212, 0.2);
  }
  .logo-wrap:hover {
    transform: scale(1.08) rotate(4deg);
    border-color: var(--accent-cyan);
    box-shadow: 0 0 30px rgba(0, 245, 212, 0.4);
  }
  .logo-wrap:active {
    transform: scale(0.94);
  }
  .logo-svg {
    width: 30px;
    height: 30px;
    fill: none;
    stroke: var(--accent-cyan);
    stroke-width: 2;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  h1 {
    font-size: 19px;
    font-weight: 800;
    letter-spacing: 1.2px;
    text-transform: uppercase;
    color: #ffffff;
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .subtitle {
    font-size: 12.5px;
    color: var(--text-muted);
    line-height: 1.4;
  }
  .badge-row {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 8px;
    margin-top: 4px;
  }
  .badge {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 4px 10px;
    border-radius: 999px;
    font-size: 11px;
    font-weight: 600;
    background: rgba(255, 255, 255, 0.04);
    border: 1px solid rgba(255, 255, 255, 0.08);
  }
  .badge-emerald {
    color: var(--accent-emerald);
    border-color: rgba(16, 185, 129, 0.35);
    background: rgba(16, 185, 129, 0.1);
  }
  .badge-cyan {
    color: var(--accent-cyan);
    border-color: rgba(0, 245, 212, 0.35);
    background: rgba(0, 245, 212, 0.1);
  }
  .badge-purple {
    color: var(--accent-purple);
    border-color: rgba(168, 85, 247, 0.35);
    background: rgba(168, 85, 247, 0.1);
  }
  .status-dot {
    width: 6.5px;
    height: 6.5px;
    border-radius: 50%;
    background-color: var(--accent-emerald);
    box-shadow: 0 0 8px var(--accent-emerald);
    animation: pulse 2s infinite;
  }
  @keyframes pulse {
    0%, 100% { opacity: 1; transform: scale(1); }
    50% { opacity: 0.4; transform: scale(0.85); }
  }
  .section-title {
    font-size: 11px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 1px;
    color: var(--text-muted);
    margin-bottom: 12px;
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .proto-list {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .proto-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 10px 12px;
    background: rgba(255, 255, 255, 0.025);
    border: 1px solid rgba(255, 255, 255, 0.05);
    border-radius: 12px;
    transition: background 0.15s ease;
  }
  .proto-item:hover {
    background: rgba(255, 255, 255, 0.05);
  }
  .proto-info {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }
  .proto-name {
    font-size: 13px;
    font-weight: 600;
    color: #fff;
  }
  .proto-desc {
    font-size: 11px;
    color: var(--text-muted);
  }
  .proto-tag {
    font-size: 10.5px;
    font-weight: 700;
    padding: 3px 8px;
    border-radius: 6px;
    text-transform: uppercase;
  }
  .actions {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 10px;
  }
  .btn {
    appearance: none;
    border: none;
    outline: none;
    background: rgba(255, 255, 255, 0.05);
    border: 1px solid rgba(255, 255, 255, 0.12);
    color: #fff;
    padding: 11px 14px;
    border-radius: 12px;
    font-size: 12.5px;
    font-weight: 600;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
    transition: all 0.2s ease;
    text-decoration: none;
    font-family: inherit;
  }
  .btn:hover {
    background: rgba(255, 255, 255, 0.09);
    border-color: rgba(255, 255, 255, 0.25);
    transform: translateY(-1px);
  }
  .btn:active {
    transform: translateY(0);
  }
  .btn-primary {
    background: rgba(0, 245, 212, 0.12);
    border-color: rgba(0, 245, 212, 0.4);
    color: var(--accent-cyan);
  }
  .btn-primary:hover {
    background: rgba(0, 245, 212, 0.2);
    border-color: var(--accent-cyan);
    box-shadow: 0 0 16px rgba(0, 245, 212, 0.2);
  }
  .btn-terminal {
    background: rgba(168, 85, 247, 0.1);
    border-color: rgba(168, 85, 247, 0.35);
    color: #d8b4fe;
  }
  .btn-terminal:hover {
    background: rgba(168, 85, 247, 0.18);
    border-color: var(--accent-purple);
    box-shadow: 0 0 16px rgba(168, 85, 247, 0.2);
  }
  .latency-box {
    margin-top: 10px;
    padding: 8px 12px;
    background: rgba(0, 0, 0, 0.3);
    border-radius: 10px;
    font-family: var(--font-mono);
    font-size: 11.5px;
    color: var(--accent-cyan);
    text-align: center;
    display: none;
  }
  .footer {
    text-align: center;
    font-size: 11px;
    color: #64748b;
    margin-top: 8px;
    line-height: 1.6;
  }
  .footer a {
    color: #94a3b8;
    text-decoration: none;
  }
  .footer a:hover {
    color: var(--accent-cyan);
  }

  /* Cyber Terminal Easter Egg Overlay */
  #termOverlay {
    position: fixed;
    top: 0;
    left: 0;
    width: 100vw;
    height: 100vh;
    background: rgba(5, 8, 17, 0.94);
    backdrop-filter: blur(16px);
    -webkit-backdrop-filter: blur(16px);
    z-index: 9999;
    display: none;
    align-items: center;
    justify-content: center;
    padding: 16px;
  }
  #matrixCanvas {
    position: absolute;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    opacity: 0.18;
    pointer-events: none;
  }
  .term-window {
    width: 100%;
    max-width: 680px;
    height: 480px;
    background: rgba(8, 12, 22, 0.96);
    border: 1px solid rgba(0, 245, 212, 0.4);
    border-radius: 14px;
    box-shadow: 0 0 40px rgba(0, 245, 212, 0.15), 0 20px 50px rgba(0,0,0,0.8);
    display: flex;
    flex-direction: column;
    position: relative;
    z-index: 10;
    overflow: hidden;
    font-family: var(--font-mono);
  }
  .term-header {
    background: rgba(18, 26, 44, 0.8);
    border-bottom: 1px solid rgba(0, 245, 212, 0.25);
    padding: 10px 14px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    user-select: none;
  }
  .term-title {
    font-size: 11.5px;
    color: var(--accent-cyan);
    font-weight: 700;
    letter-spacing: 0.8px;
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .term-close {
    background: transparent;
    border: 1px solid rgba(255, 255, 255, 0.2);
    color: var(--text-muted);
    font-size: 11px;
    padding: 3px 8px;
    border-radius: 6px;
    cursor: pointer;
    font-family: var(--font-mono);
    transition: all 0.15s ease;
  }
  .term-close:hover {
    background: rgba(239, 68, 68, 0.2);
    border-color: #ef4444;
    color: #ef4444;
  }
  .term-body {
    flex: 1;
    padding: 14px;
    overflow-y: auto;
    font-size: 12px;
    line-height: 1.5;
    color: #38bdf8;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .term-line {
    word-break: break-word;
    white-space: pre-wrap;
  }
  .term-accent { color: var(--accent-cyan); font-weight: bold; }
  .term-purple { color: #c084fc; }
  .term-green { color: #34d399; }
  .term-yellow { color: #fbbf24; }
  .term-input-row {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 14px;
    background: rgba(12, 17, 30, 0.95);
    border-top: 1px solid rgba(255, 255, 255, 0.08);
  }
  .term-prompt {
    font-size: 12px;
    color: var(--accent-emerald);
    font-weight: bold;
    white-space: nowrap;
  }
  .term-input {
    flex: 1;
    background: transparent;
    border: none;
    outline: none;
    color: #fff;
    font-family: var(--font-mono);
    font-size: 12.5px;
  }
</style>
</head>
<body>

<div class="container">
  <!-- Header Card -->
  <div class="glass-card header-card">
    <div class="logo-wrap" id="logoBtn" title="Mirrly Security Core (3 клика для консоли)">
      <svg class="logo-svg" viewBox="0 0 24 24">
        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
        <path d="M9 12l2 2 4-4"/>
      </svg>
    </div>
    <h1>MIRRLY ANYCAST GATEWAY</h1>
    <p class="subtitle">Выделенный узел туннелирования трафика Telegram через Cloudflare Edge с защитой от блокировок</p>
    
    <div class="badge-row">
      <span class="badge badge-emerald">
        <span class="status-dot"></span>
        OPERATIONAL
      </span>
      <span class="badge badge-cyan">EDGE COLO: ${colo}</span>
      <span class="badge badge-purple">GEO: ${country}</span>
      <span class="badge">TLS 1.3 / HTTP3</span>
    </div>
  </div>

  <!-- Protocol Status Card -->
  <div class="glass-card">
    <div class="section-title">
      <span>Состояние транспортных реле</span>
      <span style="color: var(--accent-emerald); font-weight: 700;">3 / 3 АКТИВНЫ</span>
    </div>

    <div class="proto-list">
      <div class="proto-item">
        <div class="proto-info">
          <div class="proto-name">Telegram MTProto Relay</div>
          <div class="proto-desc">Flowseal Anycast WSS туннель на порт 443 (чаты, каналы, медиа)</div>
        </div>
        <span class="proto-tag badge-emerald">ONLINE</span>
      </div>

      <div class="proto-item">
        <div class="proto-info">
          <div class="proto-name">Telegram SOCKS5 Relay</div>
          <div class="proto-desc">Двунаправленный TCP релей v2 на порт 10808 (аудио/видеозвонки)</div>
        </div>
        <span class="proto-tag badge-purple">READY</span>
      </div>

      <div class="proto-item">
        <div class="proto-info">
          <div class="proto-name">AS44907 Allowlist Filter</div>
          <div class="proto-desc">Аппаратная фильтрация направлений (защита от сканирования и спама)</div>
        </div>
        <span class="proto-tag badge-cyan">ENFORCED</span>
      </div>
    </div>
  </div>

  <!-- Action Buttons -->
  <div class="glass-card">
    <div class="section-title">
      <span>Диагностика и инструменты</span>
      <span style="font-size: 10px; color: var(--text-muted);">v2.0.0</span>
    </div>
    <div class="actions">
      <button class="btn btn-primary" id="pingBtn">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>
        </svg>
        Тест задержки
      </button>

      <button class="btn btn-terminal" id="openTermBtn">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="4 17 10 11 4 5"/>
          <line x1="12" y1="19" x2="20" y2="19"/>
        </svg>
        &gt;_ Терминал
      </button>
    </div>
    <div class="latency-box" id="latencyBox">Замеряется отклик...</div>
  </div>

  <div class="footer">
    <div>Mirrly TG Proxy &bull; Cloudflare Anycast CDN Architecture</div>
    <div>Тройной клик по щиту или клавиша &tilde; активируют системную консоль</div>
  </div>
</div>

<!-- Easter Egg: Cyber Terminal Modal -->
<div id="termOverlay">
  <canvas id="matrixCanvas"></canvas>
  <div class="term-window">
    <div class="term-header">
      <div class="term-title">
        <span style="color: var(--accent-emerald);">&bull;</span>
        MIRRLY RELAY TERMINAL // ANYCAST SECURE CORE
      </div>
      <button class="term-close" id="closeTermBtn">[Закрыть ESC]</button>
    </div>
    <div class="term-body" id="termBody"></div>
    <div class="term-input-row">
      <span class="term-prompt">guest@${colo.toLowerCase()}:~$</span>
      <input type="text" class="term-input" id="termInput" autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" placeholder="Введите команду (help, ping, status)...">
    </div>
  </div>
</div>

<script>
(function() {
  const colo = "${colo}";
  const country = "${country}";
  const pingBtn = document.getElementById('pingBtn');
  const latencyBox = document.getElementById('latencyBox');
  const logoBtn = document.getElementById('logoBtn');
  const openTermBtn = document.getElementById('openTermBtn');
  const closeTermBtn = document.getElementById('closeTermBtn');
  const termOverlay = document.getElementById('termOverlay');
  const termBody = document.getElementById('termBody');
  const termInput = document.getElementById('termInput');
  const matrixCanvas = document.getElementById('matrixCanvas');
  const ctx = matrixCanvas.getContext('2d');

  // Latency test
  pingBtn.addEventListener('click', async function() {
    latencyBox.style.display = 'block';
    latencyBox.style.color = 'var(--accent-cyan)';
    latencyBox.textContent = 'Отправка пакета на Anycast Edge (' + colo + ')...';
    const start = performance.now();
    try {
      const resp = await fetch('/?json=1', { cache: 'no-store' });
      if (resp.ok) {
        const rtt = Math.round(performance.now() - start);
        let quality = 'Отличный отклик';
        if (rtt > 120) quality = 'Удовлетворительный';
        if (rtt > 250) quality = 'Повышенная задержка';
        latencyBox.textContent = 'RTT: ' + rtt + ' ms &bull; POP: ' + colo + ' &bull; ' + quality;
      } else {
        latencyBox.textContent = 'Ошибка ответа: HTTP ' + resp.status;
        latencyBox.style.color = '#ef4444';
      }
    } catch (e) {
      latencyBox.textContent = 'Сетевой тайм-аут или обрыв соединения';
      latencyBox.style.color = '#ef4444';
    }
  });

  // Easter egg: Triple click detection
  let clickCount = 0;
  let clickTimer = null;
  logoBtn.addEventListener('click', function() {
    clickCount++;
    clearTimeout(clickTimer);
    if (clickCount >= 3) {
      clickCount = 0;
      openTerminal();
    } else {
      clickTimer = setTimeout(() => { clickCount = 0; }, 600);
    }
  });

  openTermBtn.addEventListener('click', openTerminal);
  closeTermBtn.addEventListener('click', closeTerminal);

  window.addEventListener('keydown', function(e) {
    if (e.code === 'Backquote' || e.key === '~') {
      e.preventDefault();
      if (termOverlay.style.display === 'flex') closeTerminal();
      else openTerminal();
    } else if (e.key === 'Escape' && termOverlay.style.display === 'flex') {
      closeTerminal();
    }
  });

  // Matrix Digital Rain
  let matrixInterval = null;
  function startMatrix() {
    matrixCanvas.width = window.innerWidth;
    matrixCanvas.height = window.innerHeight;
    const chars = '0123456789ABCDEFabcdefMIRRLYtgproxy#$&*+=-';
    const fontSize = 14;
    const columns = Math.floor(matrixCanvas.width / fontSize);
    const drops = [];
    for (let i = 0; i < columns; i++) drops[i] = Math.random() * -50;

    function draw() {
      ctx.fillStyle = 'rgba(5, 8, 17, 0.08)';
      ctx.fillRect(0, 0, matrixCanvas.width, matrixCanvas.height);
      ctx.fillStyle = '#00f5d4';
      ctx.font = fontSize + 'px monospace';
      for (let i = 0; i < drops.length; i++) {
        const text = chars[Math.floor(Math.random() * chars.length)];
        ctx.fillText(text, i * fontSize, drops[i] * fontSize);
        if (drops[i] * fontSize > matrixCanvas.height && Math.random() > 0.975) {
          drops[i] = 0;
        }
        drops[i]++;
      }
    }
    clearInterval(matrixInterval);
    matrixInterval = setInterval(draw, 33);
  }

  function stopMatrix() {
    clearInterval(matrixInterval);
  }

  let isBooted = false;
  function printLine(text, cssClass = '') {
    const div = document.createElement('div');
    div.className = 'term-line ' + cssClass;
    div.innerHTML = text;
    termBody.appendChild(div);
    termBody.scrollTop = termBody.scrollHeight;
  }

  function openTerminal() {
    termOverlay.style.display = 'flex';
    startMatrix();
    termInput.focus();
    if (!isBooted) {
      isBooted = true;
      const bootLines = [
        '<span class="term-accent">========================================================</span>',
        '<span class="term-accent">[MIRRLY TG PROXY // ANYCAST EDGE TERMINAL v2.0.0]</span>',
        '<span class="term-accent">========================================================</span>',
        '<span class="term-green">[+] Cloudflare PoP: ' + colo + ' (' + country + ')</span>',
        '<span class="term-green">[+] Memory-Relay Core: ACTIVE (Zero-Log)</span>',
        '<span class="term-green">[+] Routes: MTProto (443), SOCKS5 (10808), VLESS (443)</span>',
        '<span class="term-yellow">[SEC] Telegram Allowlist: AS44907, AS62041, AS59930, AS62014</span>',
        '<span class="term-purple">[EASTER_EGG] "Свобода слова и свободный доступ к информации не имеют границ."</span>',
        '<span class="term-purple">[EASTER_EGG] Mirrly TG Proxy — разработано с гордостью (Mirrly Dev).</span>',
        '--------------------------------------------------------',
        'Введите <span class="term-accent">help</span> для списка команд или <span class="term-accent">exit</span> для выхода.'
      ];
      bootLines.forEach((l, idx) => {
        setTimeout(() => printLine(l), idx * 70);
      });
    }
  }

  function closeTerminal() {
    termOverlay.style.display = 'none';
    stopMatrix();
  }

  termInput.addEventListener('keydown', async function(e) {
    if (e.key === 'Enter') {
      const cmd = termInput.value.trim();
      termInput.value = '';
      if (!cmd) return;

      printLine('<span class="term-prompt">guest@' + colo.toLowerCase() + ':~$</span> ' + escapeHtml(cmd));
      const parts = cmd.toLowerCase().split(' ');
      const action = parts[0];

      switch(action) {
        case 'help':
          printLine('Доступные команды:');
          printLine('  <span class="term-accent">help</span>    - Показать эту справку');
          printLine('  <span class="term-accent">ping</span>    - Измерить задержку до узла (' + colo + ')');
          printLine('  <span class="term-accent">status</span>  - Вывести статус реле и сетевой стек');
          printLine('  <span class="term-accent">dc</span>      - Список дата-центров Telegram');
          printLine('  <span class="term-accent">about</span>   - О проекте Mirrly TG Proxy');
          printLine('  <span class="term-accent">clear</span>   - Очистить экран');
          printLine('  <span class="term-accent">exit</span>    - Закрыть консоль');
          break;

        case 'ping':
          printLine('Отправка ICMP/HTTP probe на Anycast Edge...');
          const t0 = performance.now();
          try {
            await fetch('/?json=1', { cache: 'no-store' });
            const r = Math.round(performance.now() - t0);
            printLine('<span class="term-green">Ответ от ' + colo + ': время=' + r + ' мс, статус=OK</span>');
          } catch(err) {
            printLine('<span class="term-yellow">Тайм-аут пакета</span>');
          }
          break;

        case 'status':
          printLine('Узел Cloudflare Edge: <span class="term-accent">' + colo + ' / ' + country + '</span>');
          printLine('Транспорт MTProto:   <span class="term-green">OPERATIONAL (TLS 1.3 Anycast)</span>');
          printLine('Транспорт SOCKS5:    <span class="term-green">OPERATIONAL (TCP Relay v2)</span>');
          printLine('Защита Anti-Abuse:   <span class="term-green">ENFORCED (AS44907, AS62041)</span>');
          printLine('Логирование:         <span class="term-accent">ОТСУТСТВУЕТ (Zero-Log RAM Relay)</span>');
          break;

        case 'dc':
          printLine('Официальные дата-центры Telegram:');
          printLine('  DC1: 149.154.175.50  (Miami, USA)');
          printLine('  DC2: 149.154.167.51  (Amsterdam, Europe)');
          printLine('  DC3: 149.154.175.100 (Miami, USA)');
          printLine('  DC4: 149.154.167.91  (Amsterdam, Europe)');
          printLine('  DC5: 91.108.56.130   (Singapore, Asia)');
          break;

        case 'about':
          printLine('<span class="term-accent">Mirrly TG Proxy</span> — нативный высокоскоростной клиент-прокси для Android.');
          printLine('Использует бессерверные Cloudflare Workers и протоколы MTProto / SOCKS5 для обхода блокировок.');
          printLine('Автор: R1Xern (Mirrly Dev). 100% открытый исходный код.');
          break;

        case 'clear':
          termBody.innerHTML = '';
          break;

        case 'exit':
          closeTerminal();
          break;

        default:
          printLine('Команда не найдена: ' + escapeHtml(action) + '. Введите <span class="term-accent">help</span> для списка.', 'term-yellow');
      }
    }
  });

  function escapeHtml(str) {
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
})();
</script>
</body>
</html>`;

  return new Response(html, {
    status: 200,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store, no-cache, must-revalidate"
    }
  });
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const configuredUuid = (env && env.UUID) || DEFAULT_UUID;

    const upgradeHeader = request.headers.get('Upgrade');
    if (!upgradeHeader || upgradeHeader.toLowerCase() !== 'websocket') {
      // Plain HTTP handler
      if (url.pathname.toLowerCase().includes(configuredUuid.toLowerCase()) || url.pathname === '/sub') {
        const vlessLink = `vless://${configuredUuid}@${url.hostname}:443?encryption=none&security=tls&sni=${url.hostname}&type=ws&host=${url.hostname}&path=%2F#Mirrly-TG-Proxy`;
        return new Response(vlessLink + "\n", {
          headers: {
            "content-type": "text/plain; charset=utf-8",
            "cache-control": "no-store, no-cache, must-revalidate"
          }
        });
      }

      // WARP Client API Reverse Proxy (Bypasses ISP / TSPU SNI blocks on api.cloudflareclient.com)
      if (
        url.pathname === '/warp-reg' ||
        url.pathname.startsWith('/warp-reg/') ||
        url.pathname === '/warp-api' ||
        url.pathname.startsWith('/warp-api/')
      ) {
        // Handle CORS Preflight
        if (request.method === 'OPTIONS') {
          return new Response(null, {
            status: 204,
            headers: {
              "Access-Control-Allow-Origin": "*",
              "Access-Control-Allow-Methods": "GET, POST, PUT, PATCH, DELETE, OPTIONS",
              "Access-Control-Allow-Headers": "*",
              "Access-Control-Max-Age": "86400"
            }
          });
        }

        try {
          let targetPath = '/reg';
          if (url.pathname.startsWith('/warp-api')) {
            targetPath = url.pathname.substring('/warp-api'.length);
            if (!targetPath || targetPath === '/') {
              targetPath = '/reg';
            }
          } else if (url.pathname.startsWith('/warp-reg')) {
            targetPath = url.pathname.substring('/warp-reg'.length);
            if (!targetPath || targetPath === '/') {
              targetPath = '/reg';
            }
          }

          if (!targetPath.startsWith('/')) {
            targetPath = '/' + targetPath;
          }

          targetPath = targetPath.replace(/^\/v0a\d+/, '');
          if (!targetPath || targetPath === '/') {
            targetPath = '/reg';
          }

          const cfUrl = `https://api.cloudflareclient.com/v0a4471${targetPath}${url.search}`;

          const cfHeaders = {
            "Content-Type": request.headers.get("Content-Type") || "application/json; charset=UTF-8",
            "Accept": request.headers.get("Accept") || "application/json",
            "User-Agent": request.headers.get("User-Agent") || "WARP for Android",
            "CF-Client-Version": request.headers.get("CF-Client-Version") || "a-6.35-4471"
          };

          const auth = request.headers.get("Authorization");
          if (auth) {
            cfHeaders["Authorization"] = auth;
          }

          const fetchOptions = {
            method: request.method,
            headers: cfHeaders
          };

          if (request.method !== 'GET' && request.method !== 'HEAD') {
            const reqBody = await request.text();
            if (reqBody && reqBody.length > 0) {
              fetchOptions.body = reqBody;
            }
          }

          // Direct request across internal Cloudflare edge network
          const cfResp = await fetch(cfUrl, fetchOptions);
          const data = await cfResp.text();

          return new Response(data, {
            status: cfResp.status,
            statusText: cfResp.statusText,
            headers: {
              "Content-Type": cfResp.headers.get("Content-Type") || "application/json; charset=utf-8",
              "Cache-Control": "no-store, no-cache, must-revalidate",
              "Access-Control-Allow-Origin": "*",
              "Access-Control-Allow-Methods": "GET, POST, PUT, PATCH, DELETE, OPTIONS",
              "Access-Control-Allow-Headers": "*"
            }
          });
        } catch (err) {
          return new Response(JSON.stringify({ error: err.message }), {
            status: 502,
            headers: {
              "Content-Type": "application/json; charset=utf-8",
              "Access-Control-Allow-Origin": "*"
            }
          });
        }
      }

      // Default Web status page with Easter Egg
      return handleStatusPage(request, url, configuredUuid);
    }

    // WebSocket Handling
    const hasExplicitTarget = url.searchParams.has('target') || url.searchParams.has('host') || url.searchParams.has('ip');

    // VLESS over WebSocket Mode (when no query parameter target is passed)
    if (!hasExplicitTarget) {
      const earlyDataHeader = request.headers.get('sec-websocket-protocol');
      const webSocketPair = new WebSocketPair();
      const [clientWs, serverWs] = Object.values(webSocketPair);
      return await handleVlessWebSocket(clientWs, serverWs, configuredUuid, earlyDataHeader);
    }

    // Direct TCP over WebSocket Mode (Telegram MTProto / SOCKS5 Relay)
    let targetHost = url.searchParams.get('host') || url.searchParams.get('ip');
    let targetPort = parseInt(url.searchParams.get('port'), 10);

    if (!targetHost || isNaN(targetPort)) {
      const targetParam = url.searchParams.get('target');
      if (!targetParam) {
        return new Response("Missing target parameter", { status: 400 });
      }

      if (targetParam.startsWith('[')) {
        const closeBracket = targetParam.indexOf(']');
        if (closeBracket !== -1) {
          targetHost = targetParam.substring(1, closeBracket);
          const afterBracket = targetParam.substring(closeBracket + 1);
          if (afterBracket.startsWith(':')) {
            targetPort = parseInt(afterBracket.substring(1), 10);
          }
        }
      } else {
        const lastColon = targetParam.lastIndexOf(':');
        if (lastColon !== -1 && targetParam.indexOf(':') === lastColon) {
          targetHost = targetParam.substring(0, lastColon);
          targetPort = parseInt(targetParam.substring(lastColon + 1), 10);
        } else {
          targetHost = targetParam;
        }
      }
    }

    if (isNaN(targetPort) || targetPort <= 0 || targetPort > 65535) {
      targetPort = 443;
    }

    if (!targetHost) {
      return new Response("Invalid target format", { status: 400 });
    }

    // Security Verification: Destination & Port Allowlist
    if (!ALLOWED_PORTS.has(targetPort)) {
      return new Response("Forbidden: Port not allowed", { status: 403 });
    }

    if (!isTelegramDestination(targetHost)) {
      return new Response("Forbidden: Destination host not allowed", { status: 403 });
    }

    let tcpSocket;
    try {
      tcpSocket = connect({
        hostname: targetHost,
        port: targetPort
      });
      let openTimer;
      try {
        await Promise.race([
          tcpSocket.opened,
          new Promise((_, reject) => {
            openTimer = setTimeout(() => reject(new Error("TCP connect timeout")), 2200);
          })
        ]);
      } finally {
        if (openTimer !== undefined) clearTimeout(openTimer);
      }
    } catch (err) {
      try { tcpSocket?.close(); } catch (_) {}
      return new Response("Upstream TCP connect failed", { status: 502 });
    }

    // Do not complete the WebSocket upgrade until Cloudflare has confirmed the
    // upstream TCP connection. This makes SOCKS5 REP=success truthful end-to-end.
    const isTcpV2 = url.pathname === '/tcp-v2' || url.pathname.startsWith('/tcp-v2');
    const webSocketPair = new WebSocketPair();
    const [clientWs, serverWs] = Object.values(webSocketPair);
    serverWs.binaryType = "arraybuffer";
    serverWs.accept();

    if (isTcpV2) {
      // 4-byte versioned control ACK: [0x56 ('V'), 0x02, 0x00 (OK), 0x00 (reserved)]
      try {
        serverWs.send(new Uint8Array([0x56, 0x02, 0x00, 0x00]));
      } catch (_) {
        try { serverWs.close(1011, "Failed to emit relay-ready control ACK"); } catch (_) {}
        try { tcpSocket.close(); } catch (_) {}
        return new Response(null, { status: 101, webSocket: clientWs });
      }
    }

    try {
      const tcpWriter = tcpSocket.writable.getWriter();
      const tcpReader = tcpSocket.readable.getReader();
      let isClosed = false;

      const cleanup = () => {
        if (isClosed) return;
        isClosed = true;
        writer.stop();
        try { tcpWriter.close(); } catch (_) {}
        try { tcpSocket.close(); } catch (_) {}
      };

      const writer = createBoundedSequentialWriter(tcpWriter, serverWs, cleanup);

      serverWs.addEventListener('message', (event) => {
        if (isClosed) return;
        try {
          const raw = event.data;
          const data = typeof raw === 'string' ? new TextEncoder().encode(raw) : new Uint8Array(raw);
          writer.enqueue(data);
        } catch (_) {
          if (!isClosed) {
            try { serverWs.close(1011, "TCP Write Error"); } catch (_) {}
            cleanup();
          }
        }
      });

      serverWs.addEventListener('close', cleanup);
      serverWs.addEventListener('error', cleanup);

      tcpSocket.closed.then(() => {
        if (!isClosed) {
          try { serverWs.close(1000, "Upstream closed"); } catch (_) {}
          cleanup();
        }
      }).catch(() => {
        if (!isClosed) {
          try { serverWs.close(1011, "Upstream TCP error"); } catch (_) {}
          cleanup();
        }
      });

      pumpTcpToWebSocket(tcpReader, serverWs, () => isClosed, cleanup);

    } catch (err) {
      serverWs.close(1011, "Connect failed: " + err.message);
    }

    return new Response(null, {
      status: 101,
      webSocket: clientWs
    });
  }
};
