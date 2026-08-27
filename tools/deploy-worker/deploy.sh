#!/usr/bin/env bash
# Mirrly TG Proxy — Автоматический деплой персонального Cloudflare Worker (Linux / macOS)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HISTORY_FILE="${SCRIPT_DIR}/mirrly_workers.txt"

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

function isTelegramHost(hostStr) {
  const cleanHost = hostStr.trim().toLowerCase();
  if (isTelegramIp(cleanHost)) return true;
  if (TG_EXACT_DOMAINS.has(cleanHost)) return true;
  if (cleanHost.endsWith('.telegram.org') || cleanHost.endsWith('.t.me') || cleanHost.endsWith('.telesco.pe')) return true;
  return false;
}

export default {
  async fetch(request, env, ctx) {
    const upgradeHeader = request.headers.get('Upgrade');
    if (!upgradeHeader || upgradeHeader.toLowerCase() !== 'websocket') {
      return new Response("Mirrly TG Proxy Relay Node Online\nStatus: 200 OK\nType: Cloudflare WebSocket MTProto/SOCKS5 Relay", {
        status: 200,
        headers: { "Content-Type": "text/plain; charset=utf-8" }
      });
    }

    const url = new URL(request.url);
    const targetHost = url.searchParams.get('ip') || url.searchParams.get('host') || '149.154.167.50';
    const targetPort = parseInt(url.searchParams.get('port') || '443', 10);

    if (!ALLOWED_PORTS.has(targetPort)) {
      return new Response(`Forbidden: Port ${targetPort} is not allowed for Telegram traffic.`, { status: 403 });
    }

    if (!isTelegramHost(targetHost)) {
      return new Response(`Forbidden: Destination ${targetHost} is not a verified Telegram network endpoint.`, { status: 403 });
    }

    const webSocketPair = new WebSocketPair();
    const [clientSocket, serverSocket] = Object.values(webSocketPair);
    serverSocket.accept();

    let tcpSocket;
    try {
      tcpSocket = connect({
        hostname: targetHost,
        port: targetPort
      });
    } catch (err) {
      serverSocket.close(1011, `TCP Connect Error: ${err.message}`);
      return new Response(null, { status: 101, webSocket: clientSocket });
    }

    const tcpWriter = tcpSocket.writable.getWriter();
    const tcpReader = tcpSocket.readable.getReader();

    serverSocket.addEventListener('message', async (event) => {
      try {
        let data = event.data;
        if (typeof data === 'string') {
          data = new TextEncoder().encode(data);
        }
        await tcpWriter.write(data);
      } catch (e) {
        serverSocket.close(1011, "Write to TCP Failed");
      }
    });

    serverSocket.addEventListener('close', () => {
      try { tcpWriter.close(); } catch (_) {}
      try { tcpSocket.close(); } catch (_) {}
    });

    serverSocket.addEventListener('error', () => {
      try { tcpWriter.close(); } catch (_) {}
      try { tcpSocket.close(); } catch (_) {}
    });

    ctx.waitUntil((async () => {
      try {
        while (true) {
          const { value, done } = await tcpReader.read();
          if (done) break;
          if (serverSocket.readyState === 1) {
            serverSocket.send(value);
          } else {
            break;
          }
        }
      } catch (_) {
      } finally {
        if (serverSocket.readyState === 1) {
          serverSocket.close(1000, "Normal Closure");
        }
        try { tcpSocket.close(); } catch (_) {}
      }
    })());

    return new Response(null, {
      status: 101,
      webSocket: clientSocket
    });
  }
};
EOF

    cat << EOF > "$TEMP_DIR/wrangler.toml"
name = "${WORKER_NAME}"
main = "worker.js"
compatibility_date = "2025-01-01"
EOF

    cd "$TEMP_DIR"

    echo -e "\033[1;33m  [1/2] Проверка авторизации Cloudflare...\033[0m"
    if ! npx -y wrangler@latest whoami 2>/dev/null | grep -q "You are logged in"; then
        echo -e "\033[1;33m  [!] Не авторизован. Открывается браузер для входа в Cloudflare...\033[0m"
        npx -y wrangler@latest login
    else
        echo -e "\033[1;32m  [OK] Авторизован в Cloudflare\033[0m"
    fi

    echo -e "\n\033[1;36m  [2/2] Публикую воркер в Cloudflare...\033[0m\n"
    local DEPLOY_LOG=$(npx -y wrangler@latest deploy 2>&1)
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
        echo -e "\n\033[1;31m  [X] Ошибка публикации воркера. Проверьте вывод выше.\033[0m\n"
    fi
}

test_workers_health() {
    show_banner
    echo -e "\033[1;36m  Проверка доступности воркеров из истории (${HISTORY_FILE}):\033[0m"
    echo -e "\033[0;37m  ──────────────────────────────────────────────────────────────────\033[0m"
    if [ ! -f "$HISTORY_FILE" ]; then
        echo -e "  (История пуста)"
        return
    fi
    while IFS= read -r line; do
        DOMAIN=$(echo "$line" | grep -oE 'Domain: [^ ]+' | awk '{print $2}')
        if [ -n "$DOMAIN" ]; then
            echo -n "  • $DOMAIN ... "
            HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 "https://${DOMAIN}/" || echo "000")
            if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "426" ] || [ "$HTTP_CODE" = "400" ]; then
                echo -e "\033[1;32m[ONLINE] HTTP ${HTTP_CODE}\033[0m"
            else
                echo -e "\033[1;31m[UNREACHABLE] HTTP ${HTTP_CODE}\033[0m"
            fi
        fi
    done < "$HISTORY_FILE"
    echo -e "\033[0;37m  ──────────────────────────────────────────────────────────────────\033[0m\n"
}

show_saved_workers() {
    show_banner
    echo -e "\033[1;36m  Ваши созданные воркеры (${HISTORY_FILE}):\033[0m"
    echo -e "\033[0;37m  ──────────────────────────────────────────────────────────────────\033[0m"
    if [ -f "$HISTORY_FILE" ]; then
        cat "$HISTORY_FILE"
    else
        echo -e "  (Список пуст)"
    fi
    echo -e "\033[0;37m  ──────────────────────────────────────────────────────────────────\033[0m\n"
}

while true; do
    show_banner
    echo -e "\033[1;33m  Выберите действие:\033[0m"
    echo -e "  \033[1;37m[1]\033[0m Создать новый воркер (авто-имя, 1 клик)"
    echo -e "  \033[1;37m[2]\033[0m Создать воркер с моим именем"
    echo -e "  \033[1;37m[3]\033[0m Проверить доступность воркеров (Health Check)"
    echo -e "  \033[1;37m[4]\033[0m Просмотреть список моих созданных воркеров"
    echo -e "  \033[1;37m[5]\033[0m Сменить аккаунт Cloudflare"
    echo -e "  \033[0;37m[0] Выход\033[0m\n"

    read -p "  Ваш выбор (0-5): " CHOICE
    case "$CHOICE" in
        1) deploy_worker ""; read -p "  Нажмите Enter для продолжения..." ;;
        2) read -p "  Введите желаемое имя: " CNAME; deploy_worker "$CNAME"; read -p "  Нажмите Enter для продолжения..." ;;
        3) test_workers_health; read -p "  Нажмите Enter для продолжения..." ;;
        4) show_saved_workers; read -p "  Нажмите Enter для продолжения..." ;;
        5) npx -y wrangler@latest logout; echo -e "\n  [OK] Сессия сброшена."; read -p "  Нажмите Enter..." ;;
        0) echo -e "\n  До свидания!\n"; exit 0 ;;
        *) echo -e "\n  [!] Неверный выбор." ; sleep 1 ;;
    esac
done
