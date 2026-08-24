@echo off
chcp 65001 >nul
title Mirrly TG Proxy — Автодеплой Cloudflare Worker
cd /d "%~dp0"

:: Проверка наличия PowerShell
where powershell >nul 2>&1
if %errorlevel% neq 0 (
    echo.
    echo  [X] Ошибка: PowerShell не найден в системе.
    echo.
    pause
    exit /b 1
)

:: Запуск интерактивного скрипта PowerShell с обходом ExecutionPolicy
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1" %*

if %errorlevel% neq 0 (
    echo.
    echo  [!] Скрипт завершил работу с кодом %errorlevel%.
    echo.
    pause
)
