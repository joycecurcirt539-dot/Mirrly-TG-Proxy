#!/usr/bin/env bash
# Mirrly TG Proxy — Автоматический деплой персонального Cloudflare Worker (Linux / macOS)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HISTORY_FILE="${SCRIPT_DIR}/my_workers.txt"

show_banner() {
    clear
    echo -e "\n\033[0;36m  ╔══════════════════════════════════════════════════════════════════╗\033[0m"
    echo -e "\033[1;37m  ║                Mirrly TG Proxy — Worker Deployer                 ║\033[0m"
    echo -e "\033[0;36m  ║   Автоматическое создание и деплой узлов Cloudflare (MTProto)    ║\033[0m"
    echo -e "\033[0;36m  ╚══════════════════════════════════════════════════════════════════╝\033[0m\n"
}

if ! command -v node >/dev/null 2>&1; then
    show_banner
    echo -e "\033[0;31m  [X] Node.js не установлен в системе!\033[0m"
    echo -e "\033[1;33m  Установите Node.js (v18+): https://nodejs.org/\033[0m\n"
    exit 1
fi

deploy_worker() {
    local WORKER_NAME="$1"
    if [ -z "$WORKER_NAME" ]; then
        local SUFFIX=$(head /dev/urandom | tr -dc a-z0-9 | head -c 6)
        WORKER_NAME="mtg-relay-${SUFFIX}"
    fi

    echo -e "\n\033[0;37m  [*] Имя воркера: \033[1;36m${WORKER_NAME}\033[0m"

    local TEMP_DIR=$(mktemp -d /tmp/mirrly-deploy.XXXXXX)
    trap 'rm -rf "$TEMP_DIR"' EXIT

    cat << 'EOF' > "$TEMP_DIR/worker.js"
import { connect } from 'cloudflare:sockets';

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

const TG_IPV6_PREFIXES = [
  "2001:b28:f23d:",
  "2001:b28:f23f:",
  "2001:67c:4e8:"
];

const ALLOWED_PORTS = new Set([80, 443, 5222, 8443, 8888, 8080]);

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
  for (const prefix of TG_IPV6_PREFIXES) {
    if (cleanIp.startsWith(prefix)) return true;
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
          version: "1.1.6.1",
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
EOF

    cat << EOF > "$TEMP_DIR/wrangler.toml"
name = "$WORKER_NAME"
main = "worker.js"
compatibility_date = "2024-09-23"
EOF

    cd "$TEMP_DIR"

    echo -e "  \033[1;33m[1/2] Проверка авторизации Cloudflare (OAuth)...\033[0m"
    npx -y wrangler@latest login

    echo -e "\n  \033[1;36m[2/2] Публикую воркер в Cloudflare...\033[0m\n"
    npx -y wrangler@latest deploy

    local DOMAIN=""
    local LOG_DIR="${HOME}/.config/.wrangler/logs"
    if [ -d "$LOG_DIR" ]; then
        local LATEST_LOG=$(ls -t "$LOG_DIR"/*.log 2>/dev/null | head -n 1)
        if [ -n "$LATEST_LOG" ]; then
            DOMAIN=$(grep -oE 'https://[a-zA-Z0-9\.-]+\.workers\.dev' "$LATEST_LOG" | tail -n 1 | sed 's|https://||')
        fi
    fi

    echo -e "\n\033[1;32m  ╔══════════════════════════════════════════════════════════════════╗\033[0m"
    echo -e "\033[1;32m  ║                 Воркер успешно задеплоен!                        ║\033[0m"
    echo -e "\033[1;32m  ╚══════════════════════════════════════════════════════════════════╝\033[0m\n"

    if [ -n "$DOMAIN" ]; then
        local DEEP_LINK="mirrly://worker?name=${WORKER_NAME}&domain=${DOMAIN}"
        echo -e "  \033[0;37mДомен воркера: \033[1;37m${DOMAIN}\033[0m"
        echo -e "  \033[0;37mApp Ссылка:    \033[0;36m${DEEP_LINK}\033[0m\n"

        if command -v xclip >/dev/null 2>&1; then
            echo -n "$DOMAIN" | xclip -selection clipboard
            echo -e "  \033[1;32m[OK] Домен скопирован в буфер обмена!\033[0m"
        elif command -v pbcopy >/dev/null 2>&1; then
            echo -n "$DOMAIN" | pbcopy
            echo -e "  \033[1;32m[OK] Домен скопирован в буфер обмена!\033[0m"
        fi

        local DATE_STR=$(date "+%Y-%m-%d %H:%M:%S")
        echo "${DATE_STR} | Name: ${WORKER_NAME} | Domain: ${DOMAIN} | Link: ${DEEP_LINK}" >> "$HISTORY_FILE"
        echo -e "  \033[0;37m[OK] Запись сохранена в файл:\033[0m ${HISTORY_FILE}"
    fi
}

show_saved_workers() {
    show_banner
    echo -e "  \033[1;36mВаши созданные воркеры (${HISTORY_FILE}):\033[0m"
    echo -e "  ──────────────────────────────────────────────────────────────────"
    if [ -f "$HISTORY_FILE" ]; then
        cat "$HISTORY_FILE" | while read -r line; do
            echo -e "  • \033[1;37m$line\033[0m"
        done
    else
        echo -e "  \033[0;37m(Вы еще не создавали воркеры)\033[0m"
    fi
    echo -e "  ──────────────────────────────────────────────────────────────────\n"
}

switch_account() {
    echo -e "\n\033[1;33m  [*] Сброс текущей авторизации Cloudflare...\033[0m"
    npx -y wrangler@latest logout || true
    echo -e "  \033[1;32m[OK] Сессия Cloudflare сброшена.\033[0m\n"
}

while true; do
    show_banner
    echo -e "  \033[1;33mВыберите действие:\033[0m"
    echo -e "  \033[1;37m[1]\033[0m Создать новый воркер (авто-имя, 1 клик)"
    echo -e "  \033[1;37m[2]\033[0m Создать воркер с моим именем"
    echo -e "  \033[1;37m[3]\033[0m Сменить аккаунт Cloudflare (войти под другой почтой)"
    echo -e "  \033[1;37m[4]\033[0m Просмотреть список моих созданных воркеров"
    echo -e "  \033[0;37m[0]\033[0m Выход\n"

    read -p "  Ваш выбор (0-4): " CHOICE

    case "$CHOICE" in
        1)
            deploy_worker ""
            echo ""
            read -p "  Нажмите Enter, чтобы вернуться в меню..."
            ;;
        2)
            read -p "  Введите имя воркера: " CUSTOM_NAME
            deploy_worker "$CUSTOM_NAME"
            echo ""
            read -p "  Нажмите Enter, чтобы вернуться в меню..."
            ;;
        3)
            switch_account
            echo ""
            read -p "  Нажмите Enter, чтобы вернуться в меню..."
            ;;
        4)
            show_saved_workers
            read -p "  Нажмите Enter, чтобы вернуться в меню..."
            ;;
        0)
            echo -e "\n\033[0;36m  До свидания!\033[0m\n"
            exit 0
            ;;
        *)
            deploy_worker ""
            echo ""
            read -p "  Нажмите Enter, чтобы вернуться в меню..."
            ;;
    esac
done
