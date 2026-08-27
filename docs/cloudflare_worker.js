/**
 * Mirrly TG Proxy - Dedicated Cloudflare Worker for Telegram
 * Specifically optimized for Telegram MTProto & SOCKS5 VoIP calls
 * Protected with Telegram Destination & Port Allowlist (Anti-Open-Relay)
 */
import { connect } from 'cloudflare:sockets';

// Telegram IPv4 Subnets (AS44907, AS62041, AS59930, AS62014)
const TG_IPV4_SUBNETS = [
  { ip: "91.108.4.0", mask: 22 },
  { ip: "91.108.8.0", mask: 22 },
  { ip: "91.108.12.0", mask: 22 },
  { ip: "91.108.16.0", mask: 22 },
  { ip: "91.108.20.0", mask: 22 },
  { ip: "91.108.36.0", mask: 23 },
  { ip: "91.108.38.0", mask: 23 },
  { ip: "91.108.56.0", mask: 22 },
  { ip: "149.154.160.0", mask: 20 },
  { ip: "91.105.192.0", mask: 23 },
  { ip: "185.76.151.0", mask: 24 }
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
    d.endsWith(".cdn-telegram.org")
  );
}

function isTelegramDestination(host) {
  if (!host) return false;
  return isTelegramIp(host) || isTelegramDomain(host);
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    const upgradeHeader = request.headers.get('Upgrade');
    if (!upgradeHeader || upgradeHeader.toLowerCase() !== 'websocket') {
      return new Response(
        JSON.stringify({
          status: "online",
          service: "Mirrly TG Proxy Dedicated Worker",
          security: "Protected Telegram Relay (Allowlist Enforced)",
          compatible: ["Telegram MTProto", "Telegram SOCKS5", "Telegram VoIP Calls"],
          version: "1.1.8.1",
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

    const webSocketPair = new WebSocketPair();
    const [clientWs, serverWs] = Object.values(webSocketPair);
    serverWs.accept();

    try {
      const tcpSocket = connect({
        hostname: targetHost,
        port: targetPort
      });

      const tcpWriter = tcpSocket.writable.getWriter();
      const tcpReader = tcpSocket.readable.getReader();

      let writeQueue = Promise.resolve();
      let isClosed = false;

      const cleanup = () => {
        if (isClosed) return;
        isClosed = true;
        try { tcpWriter.close(); } catch (_) {}
        try { tcpSocket.close(); } catch (_) {}
      };

      serverWs.addEventListener('message', (event) => {
        if (isClosed) return;
        try {
          const raw = event.data;
          const data = typeof raw === 'string' ? new TextEncoder().encode(raw) : new Uint8Array(raw);
          writeQueue = writeQueue.then(async () => {
            if (isClosed) return;
            await tcpWriter.write(data);
          }).catch((_) => {
            if (!isClosed) {
              isClosed = true;
              try { serverWs.close(1011, "TCP Write Error"); } catch (_) {}
              cleanup();
            }
          });
        } catch (_) {
          if (!isClosed) {
            isClosed = true;
            try { serverWs.close(1011, "TCP Write Error"); } catch (_) {}
            cleanup();
          }
        }
      });

      serverWs.addEventListener('close', cleanup);
      serverWs.addEventListener('error', cleanup);

      (async () => {
        try {
          while (true) {
            const { value, done } = await tcpReader.read();
            if (done) break;
            if (value && serverWs.readyState === WebSocket.OPEN) {
              if (value.byteLength > 65536) {
                for (let offset = 0; offset < value.byteLength; offset += 65536) {
                  serverWs.send(value.subarray(offset, offset + 65536));
                }
              } else {
                serverWs.send(value);
              }
            }
          }
        } catch (_) {
        } finally {
          cleanup();
          try { serverWs.close(); } catch (_) {}
        }
      })();

    } catch (err) {
      serverWs.close(1011, "Connect failed: " + err.message);
    }

    return new Response(null, {
      status: 101,
      webSocket: clientWs
    });
  }
};
