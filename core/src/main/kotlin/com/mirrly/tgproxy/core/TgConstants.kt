package com.mirrly.tgproxy.core

object TgConstants {
    const val HANDSHAKE_LEN = 64
    const val SKIP_LEN = 8
    const val PREKEY_LEN = 32
    const val IV_LEN = 16
    const val PROTO_TAG_POS = 56
    const val DC_IDX_POS = 60

    const val CLOUDFLARE_WORKER_JS_CODE = """/**
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
        if (lower.startsWith("91.108.")) {
            return Pair(5, false)
        }
        if (lower.startsWith("91.105.192.")) {
            return Pair(203, false)
        }
        if (lower.startsWith("185.76.151.")) {
            return Pair(2, false)
        }
        if (lower.startsWith("2001:b28:f23d:f001:")) return Pair(1, lower.endsWith("::b"))
        if (lower.startsWith("2001:67c:4e8:f002:")) return Pair(2, lower.endsWith("::b"))
        if (lower.startsWith("2001:b28:f23d:f003:")) return Pair(3, lower.endsWith("::b"))
        if (lower.startsWith("2001:67c:4e8:f004:")) return Pair(4, lower.endsWith("::b"))
        if (lower.startsWith("2001:b28:f23f:f005:")) return Pair(5, lower.endsWith("::b"))
        if (lower.startsWith("2001:b28:f23e:")) return Pair(4, false)
        if (lower.startsWith("2a0a:f28")) return Pair(5, false)

        return null
    }
}
