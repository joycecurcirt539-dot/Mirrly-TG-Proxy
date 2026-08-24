# Mirrly TG Proxy - Full Project Cleanup Script (PowerShell)
param(
    [switch]$DeepCargoClean
)

$ErrorActionPreference = "SilentlyContinue"

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "Mirrly TG Proxy - Cleaning Build & Cache Artifacts" -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
Set-Location "$scriptDir"

$dirsToRemove = @(
    ".gradle",
    "build",
    "app/build",
    "core/build",
    ".cxx",
    "app/.cxx",
    ".externalNativeBuild",
    ".kotlin",
    ".kotlin-rsp",
    "captures",
    ".verify_check",
    "mirrlyengine/target"
)

foreach ($dir in $dirsToRemove) {
    if (Test-Path "$scriptDir\$dir") {
        Write-Host "Removing: $dir ..." -ForegroundColor Yellow
        Remove-Item -Recurse -Force "$scriptDir\$dir" -ErrorAction SilentlyContinue | Out-Null
    }
}

if ($DeepCargoClean) {
    $cargoCacheRoot = "$env:USERPROFILE\.cargo\target-cache\mirrlyengine"
    if (Test-Path $cargoCacheRoot) {
        Write-Host "Removing external Cargo cache: $cargoCacheRoot ..." -ForegroundColor Yellow
        Remove-Item -Recurse -Force $cargoCacheRoot -ErrorAction SilentlyContinue | Out-Null
    }
}

Write-Host "`nCalculating current workspace size..." -ForegroundColor Cyan

Get-ChildItem -Force | ForEach-Object {
    $item = $_
    $size = if ($item.PSIsContainer) {
        (Get-ChildItem $item.FullName -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
    } else { $item.Length }
    [PSCustomObject]@{
        Name   = $item.Name
        SizeMB = [math]::Round($size / 1MB, 2)
    }
} | Sort-Object -Property SizeMB -Descending | Format-Table -AutoSize

Write-Host "=============================================" -ForegroundColor Green
Write-Host "Workspace cleanup completed successfully!" -ForegroundColor Green
Write-Host "=============================================" -ForegroundColor Green
