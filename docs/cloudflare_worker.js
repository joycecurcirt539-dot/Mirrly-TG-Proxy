/**
 * Mirrly TG Proxy - Cloudflare Worker TCP/SOCKS5 Tunnel
 * 
 * Supports:
 * - WSS endpoint: /socks5?target=<host>:<port>
 * - Direct TCP forwarding to Telegram DCs and Telegram VoIP Call Reflectors using Cloudflare Sockets API (cloudflare:sockets)
 */

import { connect } from 'cloudflare:sockets';

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    // 1. Check WebSocket Upgrade Header
    const upgradeHeader = request.headers.get('Upgrade');
    if (!upgradeHeader || upgradeHeader.toLowerCase() !== 'websocket') {
      return new Response(
        JSON.stringify({
          status: "active",
          service: "Mirrly TG Proxy Cloudflare Worker",
          version: "1.0.9",
          time: new Date().toISOString()
        }),
        {
          headers: { "content-type": "application/json;charset=UTF-8" }
        }
      );
    }

    // 2. Parse target host & port (e.g. /socks5?target=149.154.167.51:443)
    const targetParam = url.searchParams.get('target');
    if (!targetParam) {
      return new Response("Missing target parameter. Expected ?target=host:port", { status: 400 });
    }

    const lastColon = targetParam.lastIndexOf(':');
    if (lastColon === -1) {
      return new Response("Invalid target format. Expected host:port", { status: 400 });
    }

    const targetHost = targetParam.substring(0, lastColon);
    const targetPort = parseInt(targetParam.substring(lastColon + 1), 10);

    if (isNaN(targetPort) || targetPort <= 0 || targetPort > 65535) {
      return new Response("Invalid target port", { status: 400 });
    }

    // 3. Establish WebSocket pair
    const webSocketPair = new WebSocketPair();
    const [clientWs, serverWs] = Object.values(webSocketPair);

    serverWs.accept();

    // 4. Connect to remote TCP target via Cloudflare Sockets API
    try {
      const tcpSocket = connect({
        hostname: targetHost,
        port: targetPort
      });

      const tcpWriter = tcpSocket.writable.getWriter();
      const tcpReader = tcpSocket.readable.getReader();

      // Pipe Server WebSocket -> Remote TCP
      serverWs.addEventListener('message', async (event) => {
        try {
          const data = typeof event.data === 'string' ? new TextEncoder().encode(event.data) : new Uint8Array(event.data);
          await tcpWriter.write(data);
        } catch (e) {
          serverWs.close(1011, "TCP Write Error");
        }
      });

      serverWs.addEventListener('close', () => {
        try { tcpWriter.close(); } catch (_) {}
      });

      serverWs.addEventListener('error', () => {
        try { tcpWriter.close(); } catch (_) {}
      });

      // Pipe Remote TCP -> Server WebSocket
      (async () => {
        try {
          while (true) {
            const { value, done } = await tcpReader.read();
            if (done) break;
            if (value && serverWs.readyState === WebSocket.OPEN) {
              serverWs.send(value);
            }
          }
        } catch (e) {
        } finally {
          try { serverWs.close(); } catch (_) {}
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
