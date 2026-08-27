@echo off
chcp 65001 >nul
title Mirrly TG Proxy — Worker Deployer

:: ══════════════════════════════════════════════════════════════════════════
::  Mirrly TG Proxy — Самодостаточный деплой Cloudflare Worker
::  Скачайте этот файл и запустите двойным кликом. Внешние файлы не нужны.
::  Требования: Windows 10/11 + Node.js 18+ (https://nodejs.org)
:: ══════════════════════════════════════════════════════════════════════════

where powershell >nul 2>&1
if %errorlevel% neq 0 (
    echo.
    echo  [X] Ошибка: PowerShell не найден в системе.
    echo.
    pause
    exit /b 1
)

:: Передаем директорию батника в окружение
set "MIRRLY_BAT_DIR=%~dp0"

:: Читаем встроенный PS1-блок из этого .bat и запускаем его
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$batFile = '%~f0';" ^
    "$lines = [System.IO.File]::ReadAllLines($batFile, [System.Text.Encoding]::UTF8);" ^
    "$s = ($lines | Select-String -Pattern '^::PS1_BEGIN$' | Select-Object -First 1).LineNumber;" ^
    "$e = ($lines | Select-String -Pattern '^::PS1_END$'   | Select-Object -First 1).LineNumber;" ^
    "if (-not $s -or -not $e) { Write-Host '[X] Ошибка: PS1-блок не найден.' -ForegroundColor Red; exit 1 };" ^
    "$code = $lines[$s..($e - 2)] -join [System.Environment]::NewLine;" ^
    "$tmp = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), 'mirrly_' + [System.Guid]::NewGuid().ToString('N') + '.ps1');" ^
    "[System.IO.File]::WriteAllText($tmp, $code, [System.Text.Encoding]::UTF8);" ^
    "try { & powershell -NoProfile -ExecutionPolicy Bypass -File $tmp } finally { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }"

if %errorlevel% neq 0 (
    echo.
    echo  [!] Скрипт завершил работу с ошибкой.
    echo.
    pause
)
exit /b

::PS1_BEGIN
<#
.SYNOPSIS
    Mirrly TG Proxy — Автоматический деплой персонального Cloudflare Worker.
    Самодостаточный скрипт: JS-код воркера вшит внутрь, внешние файлы не нужны.
#>

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Файл истории воркеров сохраняется рядом с .bat файлом
$BatDir = $env:MIRRLY_BAT_DIR
if (-not $BatDir -or -not (Test-Path $BatDir)) {
    $BatDir = [Environment]::GetFolderPath("MyDocuments")
}
$HistoryFile = Join-Path $BatDir "mirrly_workers.txt"

# ── Функция вывода шапки ─────────────────────────────────────────────────────

function Show-Banner {
    Clear-Host
    Write-Host ""
    Write-Host "  ╔══════════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
    Write-Host "  ║                Mirrly TG Proxy — Worker Deployer                 ║" -ForegroundColor White
    Write-Host "  ║   Автоматическое создание и деплой узлов Cloudflare (MTProto)    ║" -ForegroundColor DarkCyan
    Write-Host "  ╚══════════════════════════════════════════════════════════════════╝" -ForegroundColor Cyan
    Write-Host ""
}

# ── Проверка Node.js ─────────────────────────────────────────────────────────

function Check-NodeJs {
    try {
        $ver = (node --version 2>$null)
        if ($ver) { return $true }
    } catch {}

    Show-Banner
    Write-Host "  [X] Node.js не найден в системе!" -ForegroundColor Red
    Write-Host ""
    Write-Host "  Для работы Cloudflare Wrangler требуется Node.js (версия 18+)." -ForegroundColor Yellow
    Write-Host ""
    $open = Read-Host "  Открыть официальный сайт nodejs.org для скачивания? (Y/n)"
    if ($open -ne "n" -and $open -ne "N") {
        Start-Process "https://nodejs.org/"
    }
    Write-Host "`n  После установки Node.js перезапустите этот скрипт.`n" -ForegroundColor DarkGray
    Read-Host "  Нажмите Enter для выхода..."
    exit 1
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
'@

# ── Функция создания и деплоя воркера ────────────────────────────────────────

function Deploy-Worker([string]$WorkerName) {
    if (-not $WorkerName) {
        $chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $bytes = New-Object byte[] 6
        $rng.GetBytes($bytes)
        $WorkerName = "mtg-relay-" + (-join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] }))
    }

    Write-Host "`n  [*] Имя воркера: " -NoNewline -ForegroundColor Gray
    Write-Host "$WorkerName" -ForegroundColor Cyan

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
        # Проверяем авторизацию — открываем браузер только если не залогинены
        Write-Host "  [1/2] Проверка авторизации Cloudflare..." -ForegroundColor Yellow
        $whoami = npx -y wrangler@latest whoami 2>&1
        $isAuthed = $whoami | Select-String -Pattern "You are logged in" -Quiet
        if (-not $isAuthed) {
            Write-Host "  [!] Не авторизован. Открывается браузер для входа в Cloudflare..." -ForegroundColor Yellow
            npx -y wrangler@latest login
        } else {
            $accountLine = $whoami | Select-String "Account Name"
            Write-Host "  [OK] Авторизован: $($accountLine.Line.Trim())" -ForegroundColor Green
        }

        Write-Host "`n  [2/2] Публикую воркер в Cloudflare...`n" -ForegroundColor Cyan

        # Перехватываем stdout для извлечения URL воркера
        $deployOutput = npx -y wrangler@latest deploy 2>&1
        $deploySuccess = ($LASTEXITCODE -eq 0)

        # Выводим лог деплоя
        $deployOutput | ForEach-Object { Write-Host "  $_" }

        # Извлекаем домен из вывода wrangler
        if ($deploySuccess) {
            $urlMatch = $deployOutput | Select-String -Pattern 'https://([a-zA-Z0-9.-]+.workers.dev)' |
                Select-Object -First 1
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

# ── Функция смены аккаунта Cloudflare ─────────────────────────────────────────

function Switch-CloudflareAccount {
    Write-Host "`n  [*] Сброс текущей авторизации Cloudflare..." -ForegroundColor Yellow
    try {
        npx -y wrangler@latest logout
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
        $lines = Get-Content $HistoryFile -Encoding UTF8
        if ($lines.Count -gt 0) {
            foreach ($line in $lines) {
                Write-Host "  • $line" -ForegroundColor White
            }
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
    Write-Host "  Выберите действие:" -ForegroundColor Yellow
    Write-Host "  [1] Создать новый воркер (авто-имя, 1 клик)" -ForegroundColor White
    Write-Host "  [2] Создать воркер с моим именем" -ForegroundColor White
    Write-Host "  [3] Сменить аккаунт Cloudflare (войти под другой почтой)" -ForegroundColor White
    Write-Host "  [4] Просмотреть список моих созданных воркеров" -ForegroundColor White
    Write-Host "  [0] Выход" -ForegroundColor DarkGray
    Write-Host ""

    $choice = Read-Host "  Ваш выбор (0-4)"

    switch ($choice) {
        "1" {
            $domain = Deploy-Worker ""
            Write-Host ""
            Read-Host "  Нажмите Enter, чтобы вернуться в меню..."
        }
        "2" {
            $customName = Read-Host "  Введите желаемое имя воркера (например: my-tg-worker)"
            if (-not [string]::IsNullOrWhiteSpace($customName)) {
                $domain = Deploy-Worker $customName.Trim()
            }
            Write-Host ""
            Read-Host "  Нажмите Enter, чтобы вернуться в меню..."
        }
        "3" {
            Switch-CloudflareAccount
            Write-Host ""
            Read-Host "  Нажмите Enter, чтобы вернуться в меню..."
        }
        "4" {
            Show-SavedWorkers
            Read-Host "  Нажмите Enter, чтобы вернуться в меню..."
        }
        "0" {
            Write-Host "`n  До свидания!`n" -ForegroundColor Cyan
            break mainLoop
        }
        default {
            $domain = Deploy-Worker ""
            Write-Host ""
            Read-Host "  Нажмите Enter, чтобы вернуться в меню..."
        }
    }
}
::PS1_END
