package com.mirrly.tgproxy.core

object TgConstants {
    const val HANDSHAKE_LEN = 64
    const val SKIP_LEN = 8
    const val PREKEY_LEN = 32
    const val IV_LEN = 16
    const val PROTO_TAG_POS = 56
    const val DC_IDX_POS = 60

    const val DEFAULT_SOCKS5_DEV_WORKER = "mirrly-tg-proxy-worker.brawny-singer.workers.dev"

    const val CLOUDFLARE_WORKER_JS_CODE = """/**
 * Mirrly TG Proxy - Dedicated Cloudflare Worker for Telegram
 * Specifically optimized for Telegram MTProto & SOCKS5 VoIP calls
 * Protected with Telegram Destination & Port Allowlist (Anti-Open-Relay)
 */
import { connect } from 'cloudflare:sockets';

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

// Bounded flow control constants (MOB-010, MOB-011)
const MAX_WS_MESSAGE_BYTES = 256 * 1024;      // 256 KiB max incoming single WS message
const MAX_PENDING_WRITE_BYTES = 4 * 1024 * 1024; // 4 MiB high watermark for uplink write buffer
const UPLINK_LOW_WATERMARK = 1024 * 1024;      // 1 MiB low watermark
const DOWNLINK_HIGH_WATERMARK = 512 * 1024;   // 512 KiB high watermark for downlink WS buffer
const DOWNLINK_LOW_WATERMARK = 128 * 1024;    // 128 KiB low watermark
const MAX_DOWNLINK_CHUNK = 32 * 1024;         // 32 KiB max chunk per WS frame
const TCP_WRITE_TIMEOUT_MS = 10000;           // 10s write timeout before closing stalled socket
const SLOW_READER_SOAK_TIMEOUT_MS = 30000;    // 30s slow-reader soak timeout

/**
 * Sequential FIFO Writer with Bounded Watermark, Timeout, and Blob/Binary compatibility (MOB-010, MOB-011).
 * Resolves Blobs to ArrayBuffer asynchronously while preserving strict FIFO byte order.
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
  let slowReaderSoakStart = null;
  try {
    while (true) {
      if (isClosedCheck()) break;

      if (typeof serverWs.bufferedAmount === 'number' && serverWs.bufferedAmount > DOWNLINK_HIGH_WATERMARK) {
        if (slowReaderSoakStart === null) {
          slowReaderSoakStart = Date.now();
        } else if (Date.now() - slowReaderSoakStart > SLOW_READER_SOAK_TIMEOUT_MS) {
          try { serverWs.close(1008, "Downlink slow reader timeout"); } catch (_) {}
          break;
        }

        while (typeof serverWs.bufferedAmount === 'number' &&
               serverWs.bufferedAmount > DOWNLINK_LOW_WATERMARK &&
               !isClosedCheck()) {
          await new Promise((resolve) => setTimeout(resolve, 25));
          if (Date.now() - slowReaderSoakStart > SLOW_READER_SOAK_TIMEOUT_MS) {
            try { serverWs.close(1008, "Downlink slow reader timeout"); } catch (_) {}
            return;
          }
        }
      } else {
        slowReaderSoakStart = null;
      }

      const { value, done } = await tcpReader.read();
      if (done) {
        if (!isClosedCheck()) {
          onCleanup(1000, "Upstream closed");
        }
        break;
      }
      if (value && serverWs.readyState === WebSocket.OPEN) {
        if (value.byteLength > MAX_DOWNLINK_CHUNK) {
          for (let offset = 0; offset < value.byteLength; offset += MAX_DOWNLINK_CHUNK) {
            if (isClosedCheck()) break;
            const chunk = value.subarray(offset, offset + MAX_DOWNLINK_CHUNK);
            serverWs.send(chunk);
            if (typeof serverWs.bufferedAmount === 'number' && serverWs.bufferedAmount > DOWNLINK_HIGH_WATERMARK) {
              while (typeof serverWs.bufferedAmount === 'number' &&
                     serverWs.bufferedAmount > DOWNLINK_LOW_WATERMARK &&
                     !isClosedCheck()) {
                await new Promise((resolve) => setTimeout(resolve, 25));
              }
            }
          }
        } else {
          serverWs.send(value);
        }
      }
    }
  } catch (_) {
    onCleanup(1011, "Downlink read error");
  } finally {
    onCleanup(1000, "Downlink complete");
  }
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    const upgradeHeader = request.headers.get('Upgrade');
    if (!upgradeHeader || upgradeHeader.toLowerCase() !== 'websocket') {
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

          const cfUrl = 'https://api.cloudflareclient.com/v0a4471' + targetPath + url.search;

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

      return new Response(
        JSON.stringify({
          status: "online",
          service: "Mirrly TG Proxy Dedicated Worker",
          security: "Protected Telegram Relay (Allowlist Enforced)",
          compatible: ["Telegram MTProto", "Telegram SOCKS5", "Telegram VoIP Calls"],
          version: "2.0.0",
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

    let targetHost = url.searchParams.get('host');
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

    // Explicitly set binaryType before accept (MOB-011)
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

      const cleanup = (code = 1000, reason = "Normal Closure") => {
        if (isClosed) return;
        isClosed = true;
        writer.stop();
        try { tcpWriter.close(); } catch (_) {}
        try { tcpSocket.close(); } catch (_) {}
        try {
          if (serverWs.readyState === 1 || serverWs.readyState === 0) {
            serverWs.close(code, reason);
          }
        } catch (_) {}
      };

      const writer = createBoundedSequentialWriter(tcpWriter, serverWs, cleanup);

      serverWs.addEventListener('message', (event) => {
        if (isClosed) return;
        try {
          writer.enqueue(event.data);
        } catch (_) {
          cleanup(1011, "TCP Write Error");
        }
      });

      serverWs.addEventListener('close', () => cleanup(1000, "Client closed"));
      serverWs.addEventListener('error', () => cleanup(1011, "WebSocket error"));

      tcpSocket.closed.then(() => {
        cleanup(1000, "Upstream closed");
      }).catch(() => {
        cleanup(1011, "Upstream TCP error");
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
};"""

    val PROTO_TAG_ABRIDGED = byteArrayOf(0xef.toByte(), 0xef.toByte(), 0xef.toByte(), 0xef.toByte())
    val PROTO_TAG_INTERMEDIATE = byteArrayOf(0xee.toByte(), 0xee.toByte(), 0xee.toByte(), 0xee.toByte())
    val PROTO_TAG_SECURE = byteArrayOf(0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte(), 0xdd.toByte())

    val PROTO_ABRIDGED_INT = 0xEFEFEFEFU.toInt()
    val PROTO_INTERMEDIATE_INT = 0xEEEEEEEEU.toInt()
    val PROTO_PADDED_INTERMEDIATE_INT = 0xDDDDDDDDU.toInt()

    val RESERVED_FIRST_BYTES = setOf(0xEF.toByte())
    val RESERVED_CONTINUE = byteArrayOf(0, 0, 0, 0)

    val DC_DEFAULT_IPS = mapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.51",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "91.108.56.130",
        203 to "91.105.192.100"
    )

    val DC_TEST_IPS = mapOf(
        1 to "149.154.175.10",
        2 to "149.154.167.40",
        3 to "149.154.175.117"
    )

    val DC_DEFAULT_IPV6 = mapOf(
        1 to "2001:b28:f23d:f001::a",
        2 to "2001:67c:4e8:f002::a",
        3 to "2001:b28:f23d:f003::a",
        4 to "2001:67c:4e8:f004::a",
        5 to "2001:b28:f23f:f005::a"
    )

    val DC_MEDIA_IPV6 = mapOf(
        1 to "2001:b28:f23d:f001::b",
        2 to "2001:67c:4e8:f002::b",
        3 to "2001:b28:f23d:f003::b",
        4 to "2001:67c:4e8:f004::b",
        5 to "2001:b28:f23f:f005::b"
    )

    const val NAT64_WELL_KNOWN_PREFIX = "64:ff9b::"

    fun findDcIpv6(dc: Int, isMedia: Boolean = false): String? {
        val targetDc = if (dc == 203) 2 else dc
        return if (isMedia) {
            DC_MEDIA_IPV6[targetDc] ?: DC_DEFAULT_IPV6[targetDc]
        } else {
            DC_DEFAULT_IPV6[targetDc]
        }
    }

    /**
     * Синтезирует IPv6 адрес из IPv4 по RFC 6052 (Well-Known Prefix 64:ff9b::/96).
     */
    fun synthesizeNat64(ipv4: String, prefix: String = NAT64_WELL_KNOWN_PREFIX): String? {
        val parts = ipv4.trim().split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 }) return null
        val cleanPrefix = prefix.trimEnd(':')
        val hex1 = String.format("%02x%02x", octets[0], octets[1])
        val hex2 = String.format("%02x%02x", octets[2], octets[3])
        return "$cleanPrefix::$hex1:$hex2"
    }

    /**
     * Проверяет, является ли IPv6 адрес синтезированным адресом NAT64 (RFC 6052 WKP 64:ff9b::/96).
     */
    fun isNat64Address(ipv6: String): Boolean {
        val lower = ipv6.trim().lowercase()
        if (lower.startsWith("64:ff9b::") || lower.startsWith("64:ff9b:") || lower.startsWith("0064:ff9b:")) {
            return try {
                val inet = java.net.InetAddress.getByName(lower)
                val bytes = inet.address
                bytes.size == 16 &&
                    bytes[0] == 0.toByte() && bytes[1] == 0x64.toByte() &&
                    bytes[2] == 0xFF.toByte() && bytes[3] == 0x9B.toByte() &&
                    (4..11).all { bytes[it] == 0.toByte() }
            } catch (_: Exception) {
                false
            }
        }
        return false
    }

    /**
     * Извлекает исходный IPv4 адрес из синтезированного адреса NAT64 RFC 6052.
     */
    fun extractIpv4FromNat64(ipv6: String, prefix: String = NAT64_WELL_KNOWN_PREFIX): String? {
        val lower = ipv6.trim().lowercase()
        if (!lower.startsWith("64:ff9b:") && !lower.startsWith("0064:ff9b:")) return null
        return try {
            val inet = java.net.InetAddress.getByName(lower)
            val bytes = inet.address
            if (bytes.size != 16) return null
            if (bytes[0] != 0.toByte() || bytes[1] != 0x64.toByte() ||
                bytes[2] != 0xFF.toByte() || bytes[3] != 0x9B.toByte() ||
                !(4..11).all { bytes[it] == 0.toByte() }) {
                return null
            }
            val b12 = bytes[12].toInt() and 0xFF
            val b13 = bytes[13].toInt() and 0xFF
            val b14 = bytes[14].toInt() and 0xFF
            val b15 = bytes[15].toInt() and 0xFF
            "$b12.$b13.$b14.$b15"
        } catch (_: Exception) {
            null
        }
    }

    val NAMED_GATEWAYS = mapOf(
        1 to "pluto.web.telegram.org",
        2 to "venus.web.telegram.org",
        3 to "aurora.web.telegram.org",
        4 to "vesta.web.telegram.org",
        5 to "flora.web.telegram.org"
    )

    val DEFAULT_EMBEDDED_DOMAINS = listOf(
        "virkgj.com",
        "vmmzovy.com",
        "mkuosckvso.com",
        "zaewayzmplad.com",
        "twdmbzcm.com",
        "awzwsldi.com",
        "clngqrflngqin.com",
        "tjacxbqtj.com",
        "bxaxtxmrw.com",
        "dmohrsgmohcrwb.com",
        "vwbmtmoi.com",
        "khgrre.com",
        "ulihssf.com",
        "tmhqsdqmfpmk.com",
        "xwuwoqbm.com",
        "orgcnunpj.com",
        "zhkuldz.com",
        "zypoljnslxa.com",
        "efabnxaowuzs.com",
        "zaftuzsftqdq.com"
    )

    const val WS_PATH = "/apiws"
    const val WS_PATH_TEST = "/apiws_test"

    private val dynamicEmbeddedDomains = java.util.concurrent.CopyOnWriteArrayList(DEFAULT_EMBEDDED_DOMAINS)

    fun promoteDomain(domain: String) {
        if (dynamicEmbeddedDomains.remove(domain)) {
            dynamicEmbeddedDomains.add(0, domain)
        }
    }

    fun decodeCfDomain(s: String): String {
        val trimmed = s.trim()
        if (!trimmed.endsWith(".com")) return trimmed
        val suffix = ".co.uk"
        val p = trimmed.dropLast(4)
        var n = 0
        for (c in p) {
            if (c in 'a'..'z' || c in 'A'..'Z') n++
        }
        val sb = StringBuilder()
        for (c in p) {
            when (c) {
                in 'a'..'z' -> {
                    val v = (((c - 'a') - n % 26 + 26) % 26 + 'a'.code).toChar()
                    sb.append(v)
                }
                in 'A'..'Z' -> {
                    val v = (((c - 'A') - n % 26 + 26) % 26 + 'A'.code).toChar()
                    sb.append(v)
                }
                else -> sb.append(c)
            }
        }
        sb.append(suffix)
        return sb.toString()
    }

    fun getWsDomains(dc: Int, isMedia: Boolean? = null): List<String> {
        val targetDc = if (dc == 203) 2 else if (dc in 1..5) dc else 2
        val embeddedFormatted = mutableListOf<String>()
        for (raw in dynamicEmbeddedDomains) {
            val domain = decodeCfDomain(raw)
            val kwsDomain = "kws$targetDc.$domain"
            embeddedFormatted.add(kwsDomain)
            embeddedFormatted.add(domain)
        }
        return embeddedFormatted
    }

    /**
     * Identifies Telegram Datacenter (DC 1..5) from IP or hostname.
     * Returns Pair(dcId, isMedia) or null if target is not a recognized DC.
     */
    fun findDcByTarget(host: String): Pair<Int, Boolean>? {
        val lower = host.trim().lowercase()
        // 1. Direct DC IP mapping (IPv4 & IPv6)
        when (lower) {
            "149.154.175.50", "149.154.175.10" -> return Pair(1, false)
            "149.154.175.51", "149.154.175.52" -> return Pair(1, true)
            "149.154.167.51", "149.154.167.50", "149.154.167.40" -> return Pair(2, false)
            "149.154.167.52", "149.154.167.53" -> return Pair(2, true)
            "149.154.175.100", "149.154.175.117" -> return Pair(3, false)
            "149.154.175.101" -> return Pair(3, true)
            "149.154.167.91", "149.154.167.92" -> return Pair(4, false)
            "149.154.167.93" -> return Pair(4, true)
            "91.108.56.130", "91.108.56.165", "91.108.4.130" -> return Pair(5, false)
            "91.108.56.131", "91.108.56.166" -> return Pair(5, true)
            "91.105.192.100" -> return Pair(203, false)
            // Telegram IPv6 DC addresses
            "2001:b28:f23d:f001::a" -> return Pair(1, false)
            "2001:b28:f23d:f001::b" -> return Pair(1, true)
            "2001:67c:4e8:f002::a" -> return Pair(2, false)
            "2001:67c:4e8:f002::b" -> return Pair(2, true)
            "2001:b28:f23d:f003::a" -> return Pair(3, false)
            "2001:b28:f23d:f003::b" -> return Pair(3, true)
            "2001:67c:4e8:f004::a" -> return Pair(4, false)
            "2001:67c:4e8:f004::b" -> return Pair(4, true)
            "2001:b28:f23f:f005::a" -> return Pair(5, false)
            "2001:b28:f23f:f005::b" -> return Pair(5, true)
        }

        // NAT64 synthesized address unwrap: check if it wraps a known DC IPv4
        if (isNat64Address(lower)) {
            val extractedV4 = extractIpv4FromNat64(lower)
            if (extractedV4 != null) {
                val dcResult = findDcByTarget(extractedV4)
                if (dcResult != null) return dcResult
            }
        }

        // 2. Named gateways / domains
        if (lower.contains("pluto")) return Pair(1, false)
        if (lower.contains("venus")) return Pair(2, false)
        if (lower.contains("aurora")) return Pair(3, false)
        if (lower.contains("vesta")) return Pair(4, false)
        if (lower.contains("flora")) return Pair(5, false)

        val kwsMatch = Regex("kws([1-5])(-1)?\\.web\\.telegram\\.org").find(lower)
        if (kwsMatch != null) {
            val dc = kwsMatch.groupValues[1].toIntOrNull() ?: 2
            val isMedia = kwsMatch.groupValues[2].isNotEmpty()
            return Pair(dc, isMedia)
        }

        // 3. Subnet heuristic for standard Telegram DC subnets (IPv4 & IPv6)
        if (lower.startsWith("149.154.175.")) {
            val last = lower.substringAfterLast('.').toIntOrNull() ?: 50
            return if (last >= 100) Pair(3, false) else Pair(1, false)
        }
        if (lower.startsWith("149.154.167.")) {
            val last = lower.substringAfterLast('.').toIntOrNull() ?: 51
            return if (last >= 90) Pair(4, false) else Pair(2, false)
        }
        if (lower.startsWith("91.108.56.") || lower.startsWith("91.108.4.")) {
            return Pair(5, false)
        }
        if (lower.startsWith("91.105.192.")) {
            return Pair(203, false)
        }
        if (lower.startsWith("2001:b28:f23d:f001:")) return Pair(1, lower.endsWith("::b"))
        if (lower.startsWith("2001:67c:4e8:f002:")) return Pair(2, lower.endsWith("::b"))
        if (lower.startsWith("2001:b28:f23d:f003:")) return Pair(3, lower.endsWith("::b"))
        if (lower.startsWith("2001:67c:4e8:f004:")) return Pair(4, lower.endsWith("::b"))
        if (lower.startsWith("2001:b28:f23f:f005:")) return Pair(5, lower.endsWith("::b"))

        return null
    }
}
