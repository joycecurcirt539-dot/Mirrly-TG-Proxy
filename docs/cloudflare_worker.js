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

async function handleVlessWebSocket(clientWs, serverWs, expectedUuid, earlyDataHeader) {
  serverWs.accept();

  let tcpSocket = null;
  let tcpWriter = null;
  let tcpReader = null;
  let isClosed = false;
  let writeQueue = Promise.resolve();

  const cleanup = () => {
    if (isClosed) return;
    isClosed = true;
    try { if (tcpWriter) tcpWriter.close(); } catch (_) {}
    try { if (tcpSocket) tcpSocket.close(); } catch (_) {}
    try { serverWs.close(); } catch (_) {}
  };

  serverWs.addEventListener('close', cleanup);
  serverWs.addEventListener('error', cleanup);

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
    } catch (err) {
      serverWs.close(1011, "Connect failed: " + err.message);
      return;
    }

    // Send VLESS response header: [version 0, addon length 0]
    serverWs.send(new Uint8Array([0, 0]));

    // If there's initial payload in the first message, write it to TCP
    if (parsed.rawPayload && parsed.rawPayload.byteLength > 0) {
      writeQueue = writeQueue.then(async () => {
        if (isClosed || !tcpWriter) return;
        await tcpWriter.write(parsed.rawPayload);
      });
    }

    // Start reading from TCP and piping to WebSocket
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
      }
    })();
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

      const raw = event.data;
      const data = typeof raw === 'string' ? new TextEncoder().encode(raw) : new Uint8Array(raw);

      if (isFirstMessage) {
        isFirstMessage = false;
        await processFirstMessage(data);
        return;
      }

      // Subsequent messages are raw TCP payload
      writeQueue = writeQueue.then(async () => {
        if (isClosed || !tcpWriter) return;
        await tcpWriter.write(data);
      }).catch((_) => {
        cleanup();
      });

    } catch (_) {
      cleanup();
    }
  });

  const respHeaders = new Headers();
  if (earlyDataHeader) {
    respHeaders.set('Sec-WebSocket-Protocol', earlyDataHeader);
  }

  return new Response(null, {
    status: 101,
    webSocket: clientWs,
    headers: respHeaders
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

      return new Response(
        JSON.stringify({
          status: "online",
          service: "Mirrly TG Proxy Dedicated Worker",
          security: "Protected Telegram Relay (Allowlist Enforced)",
          protocols: [
            "VLESS over WebSocket (TLS 1.3)",
            "TCP over WebSocket (Legacy)",
            "Telegram MTProto",
            "Telegram SOCKS5",
            "Telegram VoIP Calls"
          ],
          version: "1.2.0-beta",
          edge_colo: request.cf?.colo || "Global Anycast",
          vless: {
            port: 443,
            transport: "ws",
            tls: true,
            uuid: configuredUuid
          },
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

    // WebSocket Handling
    const hasExplicitTarget = url.searchParams.has('target') || url.searchParams.has('host') || url.searchParams.has('ip');

    // VLESS over WebSocket Mode (when no query parameter target is passed)
    if (!hasExplicitTarget) {
      const earlyDataHeader = request.headers.get('sec-websocket-protocol');
      const webSocketPair = new WebSocketPair();
      const [clientWs, serverWs] = Object.values(webSocketPair);
      return await handleVlessWebSocket(clientWs, serverWs, configuredUuid, earlyDataHeader);
    }

    // Legacy Direct TCP over WebSocket Mode
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
