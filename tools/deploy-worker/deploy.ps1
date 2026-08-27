<#
.SYNOPSIS
    Mirrly TG Proxy — Автоматический деплой персонального Cloudflare Worker.
    Самодостаточный скрипт: JS-код воркера и генератор QR-кода вшиты внутрь.
    Поддерживает отмену операции на клавишу Escape на любом шаге.
#>

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Файл истории воркеров сохраняется рядом со скриптом
$BatDir = $env:MIRRLY_BAT_DIR
if (-not $BatDir -or -not (Test-Path $BatDir)) {
    if ($PSScriptRoot -and (Test-Path $PSScriptRoot)) {
        $BatDir = $PSScriptRoot
    } else {
        $BatDir = [Environment]::GetFolderPath("MyDocuments")
    }
}
$HistoryFile = Join-Path $BatDir "mirrly_workers.txt"
$script:CachedCfAccount = $null

# ── Вспомогательные функции ввода с поддержкой Escape ────────────────────────

function Read-LineOrEscape([string]$PromptText, [string]$Hint = "(Esc — отмена)") {
    Write-Host $PromptText -NoNewline -ForegroundColor Yellow
    if ($Hint) {
        Write-Host (' ' + $Hint + ': ') -NoNewline -ForegroundColor DarkGray
    } else {
        Write-Host ': ' -NoNewline -ForegroundColor Yellow
    }

    try {
        $inputStr = New-Object System.Text.StringBuilder
        while ($true) {
            $keyInfo = [Console]::ReadKey($true)
            if ($keyInfo.Key -eq [System.ConsoleKey]::Escape) {
                Write-Host "`n  [!] Действие отменено (нажат Esc)." -ForegroundColor DarkYellow
                return $null
            }
            if ($keyInfo.Key -eq [System.ConsoleKey]::Enter) {
                Write-Host ""
                return $inputStr.ToString()
            }
            if ($keyInfo.Key -eq [System.ConsoleKey]::Backspace) {
                if ($inputStr.Length -gt 0) {
                    $null = $inputStr.Remove($inputStr.Length - 1, 1)
                    Write-Host "`b `b" -NoNewline
                }
            }
            elseif (-not [char]::IsControl($keyInfo.KeyChar)) {
                $null = $inputStr.Append($keyInfo.KeyChar)
                Write-Host $keyInfo.KeyChar -NoNewline
            }
        }
    } catch {
        return (Read-Host)
    }
}

function Confirm-ActionOrEscape([string]$PromptText) {
    Write-Host $PromptText -ForegroundColor Yellow
    Write-Host "  Нажмите [Enter] для продолжения или [Esc] для отмены... " -NoNewline -ForegroundColor DarkGray
    try {
        while ($true) {
            $keyInfo = [Console]::ReadKey($true)
            if ($keyInfo.Key -eq [System.ConsoleKey]::Escape) {
                Write-Host "`n  [!] Операция отменена пользователем.`n" -ForegroundColor DarkYellow
                return $false
            }
            if ($keyInfo.Key -eq [System.ConsoleKey]::Enter -or $keyInfo.Key -eq [System.ConsoleKey]::Spacebar) {
                Write-Host "`n"
                return $true
            }
        }
    } catch {
        return $true
    }
}

function Wait-ReturnToMenu {
    Write-Host "`n  Нажмите любую клавишу (или Esc) для возврата в меню..." -ForegroundColor DarkGray
    try {
        $null = [Console]::ReadKey($true)
    } catch {
        $null = Read-Host
    }
}

# ── Функция вывода шапки и профиля Cloudflare ────────────────────────────────

function Show-Banner {
    Clear-Host
    Write-Host ""
    Write-Host "  ╔══════════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
    Write-Host "  ║                Mirrly TG Proxy — Worker Deployer                 ║" -ForegroundColor White
    Write-Host "  ║   Автоматическое создание и деплой узлов Cloudflare (MTProto)    ║" -ForegroundColor DarkCyan
    Write-Host "  ╚══════════════════════════════════════════════════════════════════╝" -ForegroundColor Cyan
    
    if ($script:CachedCfAccount -and $script:CachedCfAccount -ne "Не авторизован") {
        Write-Host "  [Аккаунт: " -NoNewline -ForegroundColor DarkGray
        Write-Host "$($script:CachedCfAccount)" -NoNewline -ForegroundColor Green
        Write-Host "]" -ForegroundColor DarkGray
    } else {
        Write-Host "  [Статус: " -NoNewline -ForegroundColor DarkGray
        Write-Host "Не авторизован (вход при деплое)" -NoNewline -ForegroundColor Yellow
        Write-Host "]" -ForegroundColor DarkGray
    }
    Write-Host ""
}

function Refresh-CloudflareAccount {
    try {
        $whoami = npx -y wrangler@latest whoami 2>&1
        $isAuthed = $whoami | Select-String -Pattern "You are logged in" -Quiet
        if ($isAuthed) {
            $accountLine = ($whoami | Select-String "Account Name|Account ID|Email" | Select-Object -First 1)
            if ($accountLine) {
                $script:CachedCfAccount = $accountLine.ToString().Trim()
            } else {
                $script:CachedCfAccount = "Авторизован"
            }
        } else {
            $script:CachedCfAccount = "Не авторизован"
        }
    } catch {
        $script:CachedCfAccount = "Не авторизован"
    }
}

# ── Проверка и автоустановка Node.js ─────────────────────────────────────────

function Check-NodeJs {
    try {
        $ver = (node --version 2>$null)
        if ($ver) { return $true }
    } catch {}

    $commonNodePaths = @(
        "$env:ProgramFiles\nodejs",
        "${env:ProgramFiles(x86)}\nodejs",
        "$env:LOCALAPPDATA\Programs\nodejs"
    )
    foreach ($p in $commonNodePaths) {
        if ($p -and (Test-Path "$p\node.exe")) {
            $env:Path = "$p;$env:Path"
            try {
                $ver = (node --version 2>$null)
                if ($ver) { return $true }
            } catch {}
        }
    }

    Show-Banner
    Write-Host "  [X] Node.js не найден в системе!" -ForegroundColor Red
    Write-Host ""
    Write-Host "  Для сборки и деплоя Cloudflare Worker требуется среда Node.js (v18+)." -ForegroundColor Yellow
    Write-Host ""

    $hasWinget = (Get-Command winget -ErrorAction SilentlyContinue) -ne $null
    if ($hasWinget) {
        Write-Host "  [1] Установить Node.js автоматически в 1 клик (через winget)" -ForegroundColor Green
        Write-Host "  [2] Открыть сайт nodejs.org для ручной загрузки" -ForegroundColor White
        Write-Host "  [0] Выход" -ForegroundColor DarkGray
        Write-Host ""
        $c = Read-LineOrEscape "  Выберите вариант (1/2/0)" ""
        if ($c -eq "1") {
            Write-Host "`n  [*] Запуск автоматической установки Node.js LTS через winget..." -ForegroundColor Cyan
            winget install OpenJS.NodeJS.LTS --silent --accept-package-agreements --accept-source-agreements
            
            # Мгновенно обновляем PATH из реестра
            $machinePath = [System.Environment]::GetEnvironmentVariable("Path", "Machine")
            $userPath = [System.Environment]::GetEnvironmentVariable("Path", "User")
            $env:Path = "$machinePath;$userPath"

            foreach ($p in $commonNodePaths) {
                if ($p -and (Test-Path "$p\node.exe")) {
                    $env:Path = "$p;$env:Path"
                }
            }
            
            try {
                $verAfter = (node --version 2>$null)
                if ($verAfter) {
                    Write-Host "`n  [OK] Node.js ($verAfter) успешно установлен и обнаружен!" -ForegroundColor Green
                    Write-Host "  [OK] Продолжаем работу без перезапуска...`n" -ForegroundColor Green
                    Start-Sleep -Seconds 2
                    return $true
                }
            } catch {}
            
            Write-Host "`n  [!] Установка завершена, но процессам требуется обновление окружения." -ForegroundColor Yellow
            Write-Host "  Перезапустите скрипт для продолжения.`n" -ForegroundColor DarkGray
            Read-Host "  Нажмите Enter для выхода..."
            exit 0
        } elseif ($c -eq "2") {
            Start-Process "https://nodejs.org/"
            exit 1
        } else {
            exit 0
        }
    } else {
        $open = Read-Host "  Открыть официальный сайт nodejs.org для скачивания? (Y/n)"
        if ($open -ne "n" -and $open -ne "N") {
            Start-Process "https://nodejs.org/"
        }
        Write-Host "`n  После установки Node.js перезапустите этот скрипт.`n" -ForegroundColor DarkGray
        Read-Host "  Нажмите Enter для выхода..."
        exit 1
    }
}

# ── Встроенный JS-генератор QR-кода ──────────────────────────────────────────

function Show-QrCode([string]$Text) {
    if (-not $Text) { return }
    
    $qrScript = @'
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
    const utf8 = Buffer.from(text, 'utf8');
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
    let out = '\n';
    for (let r = 0; r < tot; r += 2) {
        let line = '  ';
        for (let c = 0; c < tot; c++) {
            const topW = (grid[r][c] === 0), botW = (r + 1 < tot) ? (grid[r + 1][c] === 0) : true;
            line += (topW && botW) ? '█' : (topW ? '▀' : (botW ? '▄' : ' '));
        }
        out += line + '\n';
    }
    return out;
}
const text = process.argv[1];
if (text) console.log(generateQR(text));
'@

    $tmpJs = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "mirrly_qr_" + [System.Guid]::NewGuid().ToString("N") + ".js")
    try {
        [System.IO.File]::WriteAllText($tmpJs, $qrScript, [System.Text.Encoding]::UTF8)
        Write-Host "  Наведите камеру смартфона или сканер в Mirrly TG Proxy:" -ForegroundColor Yellow
        $qrOut = node $tmpJs $Text
        Write-Host $qrOut -ForegroundColor White
    } catch {} finally {
        Remove-Item $tmpJs -Force -ErrorAction SilentlyContinue
    }
}

# ── Нормализация имени воркера ───────────────────────────────────────────────

function Normalize-WorkerName([string]$InputName) {
    if (-not $InputName) { return "" }
    $s = $InputName.Trim().ToLower()

    # Транслитерация кириллицы в латиницу
    $cyrMap = @{
        'а'='a'; 'б'='b'; 'в'='v'; 'г'='g'; 'д'='d'; 'е'='e'; 'ё'='yo'; 'ж'='zh';
        'з'='z'; 'и'='i'; 'й'='y'; 'к'='k'; 'л'='l'; 'м'='m'; 'н'='n'; 'о'='o';
        'п'='p'; 'р'='r'; 'с'='s'; 'т'='t'; 'у'='u'; 'ф'='f'; 'х'='h'; 'ц'='ts';
        'ч'='ch'; 'ш'='sh'; 'щ'='sch'; 'ъ'=''; 'ы'='y'; 'ь'=''; 'э'='e'; 'ю'='yu'; 'я'='ya'
    }
    foreach ($k in $cyrMap.Keys) {
        $s = $s.Replace($k, $cyrMap[$k])
    }

    # Замена всех спецсимволов, знаков препинания и пробелов на дефис
    $clean = [System.Text.RegularExpressions.Regex]::Replace($s, "[^a-z0-9-]", "-")
    $clean = [System.Text.RegularExpressions.Regex]::Replace($clean, "-+", "-")
    $clean = $clean.Trim('-')

    # Ограничение длины имени воркера Cloudflare (макс. 60 символов)
    if ($clean.Length -gt 60) {
        $clean = $clean.Substring(0, 60).TrimEnd('-')
    }

    if (-not $clean) {
        $chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $bytes = New-Object byte[] 6
        $rng.GetBytes($bytes)
        $clean = "mtg-relay-" + (-join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] }))
    }
    return $clean
}

# ── Встроенный JS-код воркера ────────────────────────────────────────────────

$WorkerJsCode = @'
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
    for (const sub of TG_IPV4_SUBNETS) {
      const netLong = ipToLong(sub.ip);
      const maskLong = (~((1 << (32 - sub.mask)) - 1)) >>> 0;
      if ((targetLong & maskLong) === (netLong & maskLong)) return true;
    }
    return false;
  }

  // IPv6 check
  if (cleanIp.includes(':')) {
    for (const prefix of TG_IPV6_PREFIXES) {
      if (cleanIp.startsWith(prefix)) return true;
    }
    return false;
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
'@

# ── Функция создания и деплоя воркера ────────────────────────────────────────

function Deploy-Worker([string]$WorkerName) {
    if (-not $WorkerName) {
        $chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $bytes = New-Object byte[] 6
        $rng.GetBytes($bytes)
        $WorkerName = "mtg-relay-" + (-join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] }))
    } else {
        $WorkerName = Normalize-WorkerName $WorkerName
    }

    Write-Host "`n  [*] Имя воркера: " -NoNewline -ForegroundColor Gray
    Write-Host "$WorkerName" -ForegroundColor Cyan

    if (-not (Confirm-ActionOrEscape "  Готовность к публикации узла в Cloudflare:")) {
        return $null
    }

    $tempDir = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "mirrly-deploy-" + [System.Guid]::NewGuid().ToString("N"))
    $null = New-Item -ItemType Directory -Path $tempDir -Force

    $workerJsPath     = Join-Path $tempDir "worker.js"
    $wranglerTomlPath = Join-Path $tempDir "wrangler.toml"

    Set-Content -Path $workerJsPath -Value $WorkerJsCode -Encoding UTF8

    $tomlContent = @"
name = "$WorkerName"
main = "worker.js"
compatibility_date = "2025-01-01"
"@
    Set-Content -Path $wranglerTomlPath -Value $tomlContent -Encoding UTF8

    Push-Location $tempDir
    $deploySuccess = $false
    $domain = ""

    try {
        Write-Host "  [1/2] Проверка авторизации Cloudflare..." -ForegroundColor Yellow
        $whoami = npx -y wrangler@latest whoami 2>&1
        $isAuthed = $whoami | Select-String -Pattern "You are logged in" -Quiet
        if (-not $isAuthed) {
            Write-Host "  [!] Не авторизован. Открывается браузер для входа в Cloudflare..." -ForegroundColor Yellow
            npx -y wrangler@latest login
            Refresh-CloudflareAccount
        } else {
            $accountLine = ($whoami | Select-String "Account Name|Account ID" | Select-Object -First 1)
            if ($accountLine) {
                $script:CachedCfAccount = $accountLine.ToString().Trim()
                Write-Host "  [OK] $($script:CachedCfAccount)" -ForegroundColor Green
            } else {
                Write-Host "  [OK] Авторизован в Cloudflare" -ForegroundColor Green
            }
        }

        Write-Host "`n  [2/2] Публикую воркер в Cloudflare...`n" -ForegroundColor Cyan

        $deployOutput = npx -y wrangler@latest deploy 2>&1
        $deploySuccess = ($LASTEXITCODE -eq 0)

        $deployOutput | ForEach-Object { Write-Host "  $_" }

        if ($deploySuccess) {
            $urlMatch = $deployOutput | Select-String -Pattern 'https://([a-zA-Z0-9.-]+.workers.dev)' | Select-Object -First 1
            if ($urlMatch) {
                $domain = $urlMatch.Matches[0].Groups[1].Value
            }
        }

    } catch {
        Write-Host "`n  [X] Ошибка во время деплоя: $_" -ForegroundColor Red
    } finally {
        Pop-Location
        Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }

    if (-not $deploySuccess) {
        Write-Host "`n  [X] Деплой не завершился успешно. Проверьте сообщения выше.`n" -ForegroundColor Red
        return $null
    }

    Write-Host ""
    Write-Host "  ╔══════════════════════════════════════════════════════════════════╗" -ForegroundColor Green
    Write-Host "  ║                 Воркер успешно задеплоен!                        ║" -ForegroundColor Green
    Write-Host "  ╚══════════════════════════════════════════════════════════════════╝" -ForegroundColor Green
    Write-Host ""

    if ($domain) {
        $deepLink = "mirrly://worker?name=$([Uri]::EscapeDataString($WorkerName))&domain=$domain"

        Write-Host "  Домен воркера: " -NoNewline -ForegroundColor Gray
        Write-Host "$domain" -ForegroundColor White
        Write-Host "  App Ссылка:    " -NoNewline -ForegroundColor Gray
        Write-Host "$deepLink" -ForegroundColor DarkCyan
        Write-Host ""

        # Выводим QR-код прямо в окно консоли
        Show-QrCode $deepLink

        # Копируем в буфер
        try {
            Set-Clipboard $domain
            Write-Host "  [OK] Домен автоматически скопирован в буфер обмена!" -ForegroundColor Green
        } catch {}

        # Сохраняем в файл истории
        $dateStr = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
        $logLine = "$dateStr | Name: $WorkerName | Domain: $domain | Link: $deepLink"
        Add-Content -Path $HistoryFile -Value $logLine -Encoding UTF8 -ErrorAction SilentlyContinue
        Write-Host "  [OK] Запись сохранена в: $HistoryFile" -ForegroundColor DarkGray
    } else {
        Write-Host "  Воркер '$WorkerName' опубликован!" -ForegroundColor Green
        Write-Host "  (Скопируйте публичный URL из Cloudflare Dashboard)`n" -ForegroundColor Yellow
    }

    return $domain
}

# ── Проверка доступности (Health Check / Ping) ───────────────────────────────

function Test-WorkersHealth {
    Show-Banner
    Write-Host "  Проверка доступности воркеров из истории ($HistoryFile):" -ForegroundColor Cyan
    Write-Host "  ──────────────────────────────────────────────────────────────────" -ForegroundColor DarkGray

    if (-not (Test-Path $HistoryFile)) {
        Write-Host "  (История пуста, нет созданных узлов для проверки)" -ForegroundColor DarkGray
        Write-Host "  ──────────────────────────────────────────────────────────────────`n" -ForegroundColor DarkGray
        return
    }

    $lines = Get-Content $HistoryFile -Encoding UTF8 | Where-Object { $_ -match "Domain:\s*([a-zA-Z0-9.-]+\.workers\.dev)" }
    if (-not $lines -or $lines.Count -eq 0) {
        Write-Host "  (Не найдено доменов воркеров в истории)" -ForegroundColor DarkGray
        Write-Host "  ──────────────────────────────────────────────────────────────────`n" -ForegroundColor DarkGray
        return
    }

    $uniqueDomains = @{}
    foreach ($line in $lines) {
        $match = [System.Text.RegularExpressions.Regex]::Match($line, "Domain:\s*([a-zA-Z0-9.-]+)")
        if ($match.Success) {
            $uniqueDomains[$match.Groups[1].Value] = $true
        }
    }

    $onlineCount = 0
    $totalCount = $uniqueDomains.Keys.Count

    foreach ($domain in $uniqueDomains.Keys) {
        Write-Host "  • $domain ... " -NoNewline -ForegroundColor White
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $req = [System.Net.HttpWebRequest]::Create("https://$domain/")
            $req.Timeout = 6000
            $req.Headers.Add("Upgrade", "websocket")
            $req.UserAgent = "Mirrly-HealthCheck/1.0"
            $resp = $req.GetResponse()
            $sw.Stop()
            $code = [int]$resp.StatusCode
            $resp.Close()
            Write-Host "[ONLINE] WSS / HTTP Ready (${sw.ElapsedMilliseconds} ms)" -ForegroundColor Green
            $onlineCount++
        } catch [System.Net.WebException] {
            $sw.Stop()
            if ($_.Response) {
                $code = [int]$_.Response.StatusCode
                if ($code -eq 426 -or $code -eq 400 -or $code -eq 200 -or $code -eq 101) {
                    Write-Host "[ONLINE] WSS Relay Active (Code: $code, ${sw.ElapsedMilliseconds} ms)" -ForegroundColor Green
                    $onlineCount++
                } elseif ($code -eq 403) {
                    Write-Host "[ONLINE/PROTECTED] Relay Active (Code: 403, ${sw.ElapsedMilliseconds} ms)" -ForegroundColor Green
                    $onlineCount++
                } else {
                    Write-Host "[BLOCKED / ERROR: $code] (${sw.ElapsedMilliseconds} ms)" -ForegroundColor Yellow
                }
            } else {
                Write-Host "[UNREACHABLE] Таймаут / узел недоступен" -ForegroundColor Red
            }
        } catch {
            Write-Host "[ERROR] $_" -ForegroundColor Red
        }
    }

    Write-Host "  ──────────────────────────────────────────────────────────────────" -ForegroundColor DarkGray
    Write-Host "  Итог проверки: " -NoNewline -ForegroundColor DarkGray
    if ($onlineCount -eq $totalCount) {
        Write-Host "$onlineCount из $totalCount узлов активны и готовы к работе." -ForegroundColor Green
    } else {
        Write-Host "$onlineCount из $totalCount узлов доступны." -ForegroundColor Yellow
    }
    Write-Host ""
}

# ── Удаление воркера из Cloudflare ───────────────────────────────────────────

function Remove-CloudflareWorker {
    Show-Banner
    Write-Host "  Удаление воркера из Cloudflare:" -ForegroundColor Yellow
    Write-Host "  ──────────────────────────────────────────────────────────────────" -ForegroundColor DarkGray

    $wName = Read-LineOrEscape "  Введите имя воркера для удаления (например: mtg-relay-abc123)"
    if ([string]::IsNullOrWhiteSpace($wName)) { return }
    $wName = Normalize-WorkerName $wName

    if (-not (Confirm-ActionOrEscape "  Вы действительно хотите удалить '$wName' из Cloudflare?")) {
        return
    }

    Write-Host "`n  [*] Отправка запроса на удаление '$wName' в Cloudflare..." -ForegroundColor Cyan
    try {
        $delOutput = npx -y wrangler@latest delete $wName --force 2>&1
        $delOutput | ForEach-Object { Write-Host "  $_" }
        Write-Host "`n  [OK] Операция завершена." -ForegroundColor Green
    } catch {
        Write-Host "  [X] Ошибка удаления: $_" -ForegroundColor Red
    }
}

# ── Функция смены аккаунта Cloudflare ─────────────────────────────────────────

function Switch-CloudflareAccount {
    Write-Host "`n  [*] Сброс текущей авторизации Cloudflare..." -ForegroundColor Yellow
    try {
        npx -y wrangler@latest logout
        $script:CachedCfAccount = "Не авторизован"
        Write-Host "  [OK] Сессия Cloudflare сброшена." -ForegroundColor Green
        Write-Host "  При следующем деплое откроется окно для входа в новый аккаунт.`n" -ForegroundColor DarkGray
    } catch {
        Write-Host "  [!] Ошибка сброса: $_" -ForegroundColor Red
    }
}

# ── Функция просмотра сохраненных воркеров ───────────────────────────────────

function Show-SavedWorkers {
    Show-Banner
    Write-Host "  Ваши созданные воркеры ($HistoryFile):" -ForegroundColor Cyan
    Write-Host "  ──────────────────────────────────────────────────────────────────" -ForegroundColor DarkGray

    if (Test-Path $HistoryFile) {
        $rawLines = Get-Content $HistoryFile -Encoding UTF8 | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        if ($rawLines.Count -gt 0) {
            $idx = 1
            foreach ($line in $rawLines) {
                Write-Host "  [$idx] $line" -ForegroundColor White
                $idx++
            }
            Write-Host "  ──────────────────────────────────────────────────────────────────" -ForegroundColor DarkGray
            Write-Host "  [1-$($rawLines.Count)] Показать QR-код для воркера" -ForegroundColor Yellow
            Write-Host "  [N]   Открыть историю в Блокноте (Notepad)" -ForegroundColor Cyan
            Write-Host "  [Esc] Назад в главное меню" -ForegroundColor DarkGray
            Write-Host ""
            $sel = Read-LineOrEscape "  Выберите действие" ""
            if ($sel -match '^\d+$') {
                $num = [int]$sel
                if ($num -ge 1 -and $num -le $rawLines.Count) {
                    $selectedLine = $rawLines[$num - 1]
                    $matchLink = [System.Text.RegularExpressions.Regex]::Match($selectedLine, "Link:\s*(mirrly://[^\s|]+)")
                    $linkToQr = if ($matchLink.Success) { $matchLink.Groups[1].Value } else {
                        $matchDom = [System.Text.RegularExpressions.Regex]::Match($selectedLine, "Domain:\s*([^\s|]+)")
                        if ($matchDom.Success) { "mirrly://worker?domain=" + $matchDom.Groups[1].Value } else { $null }
                    }
                    if ($linkToQr) {
                        Write-Host "`n  QR-код для узла:" -ForegroundColor Green
                        Show-QrCode $linkToQr
                        Wait-ReturnToMenu
                    }
                }
            } elseif ($sel -eq "N" -or $sel -eq "n") {
                Start-Process "notepad.exe" $HistoryFile
            }
            return
        } else {
            Write-Host "  (Список пуст)" -ForegroundColor DarkGray
        }
    } else {
        Write-Host "  (Вы еще не создавали воркеры)" -ForegroundColor DarkGray
    }
    Write-Host "  ──────────────────────────────────────────────────────────────────`n" -ForegroundColor DarkGray
}

# ── Главный интерактивный цикл ───────────────────────────────────────────────

Check-NodeJs

:mainLoop while ($true) {
    Show-Banner
    Write-Host "  Выберите действие (или нажмите Esc для выхода):" -ForegroundColor Yellow
    Write-Host "  [1] Создать новый воркер (авто-имя, 1 клик)" -ForegroundColor White
    Write-Host "  [2] Создать воркер с моим именем" -ForegroundColor White
    Write-Host "  [3] Проверить доступность и пинг воркеров (Health Check)" -ForegroundColor White
    Write-Host "  [4] Просмотреть список и QR-коды моих воркеров" -ForegroundColor White
    Write-Host "  [5] Открыть файл истории в Блокноте" -ForegroundColor White
    Write-Host "  [6] Удалить воркер из Cloudflare" -ForegroundColor White
    Write-Host "  [7] Сменить аккаунт Cloudflare (войти под другой почтой)" -ForegroundColor White
    Write-Host "  [0] Выход (Esc)" -ForegroundColor DarkGray
    Write-Host ""

    $choice = Read-LineOrEscape "  Ваш выбор (0-7)" ""

    if ($null -eq $choice) {
        Write-Host "`n  До свидания!`n" -ForegroundColor Cyan
        break mainLoop
    }

    switch ($choice.Trim()) {
        "1" {
            $domain = Deploy-Worker ""
            Wait-ReturnToMenu
        }
        "2" {
            $rawInput = Read-LineOrEscape "  Введите желаемое имя воркера (например: My Proxy Worker!)"
            if (-not [string]::IsNullOrWhiteSpace($rawInput)) {
                $customName = Normalize-WorkerName $rawInput
                if ($customName -ne $rawInput.Trim().ToLower()) {
                    Write-Host "  [*] Имя нормализовано для Cloudflare: " -NoNewline -ForegroundColor Gray
                    Write-Host "$customName" -ForegroundColor Yellow
                }
                $domain = Deploy-Worker $customName
            }
            Wait-ReturnToMenu
        }
        "3" {
            Test-WorkersHealth
            Wait-ReturnToMenu
        }
        "4" {
            Show-SavedWorkers
            Wait-ReturnToMenu
        }
        "5" {
            if (-not (Test-Path $HistoryFile)) {
                New-Item -ItemType File -Path $HistoryFile -Force | Out-Null
            }
            Start-Process "notepad.exe" $HistoryFile
        }
        "6" {
            Remove-CloudflareWorker
            Wait-ReturnToMenu
        }
        "7" {
            Switch-CloudflareAccount
            Wait-ReturnToMenu
        }
        "0" {
            Write-Host "`n  До свидания!`n" -ForegroundColor Cyan
            break mainLoop
        }
        default {
            Write-Host "  [!] Некорректный выбор. Повторите ввод." -ForegroundColor DarkYellow
            Start-Sleep -Milliseconds 800
        }
    }
}
