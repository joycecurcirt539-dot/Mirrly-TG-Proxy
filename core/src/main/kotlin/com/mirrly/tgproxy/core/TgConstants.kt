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
 */
import { connect } from 'cloudflare:sockets';

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

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

    fun getWsDomains(dc: Int, isMedia: Boolean?): List<String> {
        val targetDc = if (dc == 203) 2 else if (dc in 1..5) dc else 2
        val embeddedFormatted = mutableListOf<String>()
        for (raw in dynamicEmbeddedDomains) {
            val domain = decodeCfDomain(raw)
            val kwsDomain = if (isMedia == true) "kws$targetDc-1.$domain" else "kws$targetDc.$domain"
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
        // 1. Direct DC IP mapping
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

        // 3. Subnet heuristic for standard Telegram DC subnets
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

        return null
    }
}
