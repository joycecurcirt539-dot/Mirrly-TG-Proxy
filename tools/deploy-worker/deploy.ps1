<#
.SYNOPSIS
    Mirrly TG Proxy - Cloudflare Worker Deploy Script
    Создает и деплоит воркер с рандомным именем на аккаунт Cloudflare.
    Автоматически открывает браузер для OAuth-авторизации.

.USAGE
    .\deploy.ps1              # Деплой с рандомным именем
    .\deploy.ps1 -Name "my-worker"  # Деплой с конкретным именем
#>

param(
    [string]$Name = ""
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$WorkerJs = Join-Path $ScriptDir "worker.js"

# ── Проверки ─────────────────────────────────────────────────────────────────

if (-not (Test-Path $WorkerJs)) {
    Write-Host "`n  [X] Файл worker.js не найден: $WorkerJs" -ForegroundColor Red
    exit 1
}

# Проверяем Node.js
$nodeVersion = $null
try { $nodeVersion = (node --version 2>$null) } catch {}
if (-not $nodeVersion) {
    Write-Host "`n  [X] Node.js не установлен." -ForegroundColor Red
    Write-Host "      Скачай: https://nodejs.org/`n" -ForegroundColor Yellow
    exit 1
}
Write-Host "`n  [OK] Node.js $nodeVersion" -ForegroundColor DarkGray

# ── Генерация рандомного имени ────────────────────────────────────────────────

if ([string]::IsNullOrWhiteSpace($Name)) {
    $chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 8
    $rng.GetBytes($bytes)
    $suffix = -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
    $Name = "mtg-relay-$suffix"
}

Write-Host "  [*] Имя воркера: " -NoNewline -ForegroundColor Cyan
Write-Host $Name -ForegroundColor White

# ── Создаем временный wrangler.toml ───────────────────────────────────────────

$wranglerToml = Join-Path $ScriptDir "wrangler.toml"
$compatDate = (Get-Date).ToString("yyyy-MM-dd")

$tomlContent = @"
name = "$Name"
main = "worker.js"
compatibility_date = "$compatDate"
"@

Set-Content -Path $wranglerToml -Value $tomlContent -Encoding UTF8
Write-Host "  [OK] wrangler.toml создан" -ForegroundColor DarkGray

# ── OAuth авторизация (открывает браузер) ─────────────────────────────────────

Write-Host "`n  ┌─────────────────────────────────────────────────────┐" -ForegroundColor DarkCyan
Write-Host "  │  Сейчас откроется браузер для входа в Cloudflare.   │" -ForegroundColor DarkCyan
Write-Host "  │  Войди в нужный аккаунт и разреши доступ.           │" -ForegroundColor DarkCyan
Write-Host "  └─────────────────────────────────────────────────────┘`n" -ForegroundColor DarkCyan

try {
    Push-Location $ScriptDir
    npx wrangler login
    if ($LASTEXITCODE -ne 0) {
        Write-Host "`n  [X] Авторизация не удалась (код $LASTEXITCODE)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Авторизация успешна`n" -ForegroundColor Green
} catch {
    Write-Host "`n  [X] Ошибка авторизации: $_" -ForegroundColor Red
    exit 1
}

# ── Деплой воркера ────────────────────────────────────────────────────────────

Write-Host "  [*] Деплою воркер..." -ForegroundColor Cyan

try {
    $deployOutput = npx wrangler deploy 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        Write-Host "`n  [X] Деплой провалился:`n$deployOutput" -ForegroundColor Red
        exit 1
    }
    Write-Host $deployOutput -ForegroundColor DarkGray
} catch {
    Write-Host "`n  [X] Ошибка деплоя: $_" -ForegroundColor Red
    exit 1
} finally {
    Pop-Location
}

# ── Извлекаем URL воркера ─────────────────────────────────────────────────────

$workerUrl = ""
if ($deployOutput -match '(https://[^\s]+\.workers\.dev)') {
    $workerUrl = $Matches[1]
}

# ── Удаляем временный wrangler.toml ───────────────────────────────────────────

Remove-Item $wranglerToml -Force -ErrorAction SilentlyContinue

# ── Результат ─────────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "  ╔═══════════════════════════════════════════════════════╗" -ForegroundColor Green
Write-Host "  ║              Воркер успешно задеплоен                 ║" -ForegroundColor Green
Write-Host "  ╚═══════════════════════════════════════════════════════╝" -ForegroundColor Green
Write-Host ""

if ($workerUrl) {
    # Домен без https://
    $domain = $workerUrl -replace '^https://',''
    Write-Host "  URL:    $workerUrl" -ForegroundColor White
    Write-Host "  Домен:  $domain" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  Этот домен нужно добавить в Mirrly TG Proxy:" -ForegroundColor Yellow
    Write-Host "  Менеджер воркеров -> + Добавить -> Вставить домен`n" -ForegroundColor Yellow

    # Копируем домен в буфер обмена
    try {
        Set-Clipboard -Value $domain
        Write-Host "  [OK] Домен скопирован в буфер обмена`n" -ForegroundColor Green
    } catch {}
} else {
    Write-Host "  Имя воркера: $Name" -ForegroundColor White
    Write-Host "  Домен будет: $Name.<твой-поддомен>.workers.dev" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  Открой Cloudflare Dashboard чтобы посмотреть полный URL`n" -ForegroundColor Yellow
}
