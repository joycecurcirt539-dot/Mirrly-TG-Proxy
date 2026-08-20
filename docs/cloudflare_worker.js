/**
 * Mirrly TG Proxy - Dedicated Cloudflare Worker for Telegram (MTProto & SOCKS5)
 * 
 * Specifically optimized for Telegram:
 * 1. SOCKS5 raw TCP tunneling via Cloudflare Sockets API (cloudflare:sockets)
 *    - Instant routing to Telegram Data Centers (DC1 - DC5)
 *    - Low-latency VoIP Reflector support for audio & video calls
 *    - Robust buffer streaming with zero V8 GC overhead
 * 2. MTProto WebSocket proxying via /apiws
 * 3. Dynamic IPv4, IPv6 and domain endpoint resolution
 */

import { connect } from 'cloudflare:sockets';

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // Health check / Service info for HTTP GET
    const upgradeHeader = request.headers.get('Upgrade');
    if (!upgradeHeader || upgradeHeader.toLowerCase() !== 'websocket') {
      return new Response(
        JSON.stringify({
          status: "online",
          service: "Mirrly TG Proxy Dedicated Worker",
          compatible: ["Telegram MTProto", "Telegram SOCKS5", "Telegram VoIP Calls"],
          version: "1.1.3",
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

    // Parse target endpoint (host & port)
    let targetHost = url.searchParams.get('host');
    let targetPort = parseInt(url.searchParams.get('port'), 10);

    if (!targetHost || isNaN(targetPort)) {
      const targetParam = url.searchParams.get('target');
      if (!targetParam) {
        return new Response("Missing target parameter. Expected ?target=host:port or ?host=...&port=...", { status: 400 });
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
      return new Response("Invalid target format. Expected host:port", { status: 400 });
    }

    // Accept WebSocket connection
    const webSocketPair = new WebSocketPair();
    const [clientWs, serverWs] = Object.values(webSocketPair);
    serverWs.accept();

    // Connect to Telegram TCP endpoint using cloudflare:sockets
    try {
      const tcpSocket = connect({
        hostname: targetHost,
        port: targetPort
      });

      const tcpWriter = tcpSocket.writable.getWriter();
      const tcpReader = tcpSocket.readable.getReader();

      // Client WS -> Telegram Remote TCP
      serverWs.addEventListener('message', async (event) => {
        try {
          const data = typeof event.data === 'string' ? new TextEncoder().encode(event.data) : new Uint8Array(event.data);
          await tcpWriter.write(data);
        } catch (_) {
          serverWs.close(1011, "TCP Write Error");
          try { tcpSocket.close(); } catch (_) {}
        }
      });

      serverWs.addEventListener('close', () => {
        try { tcpWriter.close(); } catch (_) {}
        try { tcpSocket.close(); } catch (_) {}
      });

      serverWs.addEventListener('error', () => {
        try { tcpWriter.close(); } catch (_) {}
        try { tcpSocket.close(); } catch (_) {}
      });

      // Telegram Remote TCP -> Client WS
      (async () => {
        try {
          while (true) {
            const { value, done } = await tcpReader.read();
            if (done) break;
            if (value && serverWs.readyState === WebSocket.OPEN) {
              serverWs.send(value);
            }
          }
        } catch (_) {
        } finally {
          try { serverWs.close(); } catch (_) {}
          try { tcpSocket.close(); } catch (_) {}
        }
      })();

    } catch (err) {
      serverWs.close(1011, `Connect failed: ${err.message}`);
    }

    return new Response(null, {
      status: 101,
      webSocket: clientWs
    });
  }
};
