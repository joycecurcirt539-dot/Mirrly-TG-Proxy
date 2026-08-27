@echo off
setlocal
title Mirrly TG Proxy - Worker Deployer

where powershell.exe >nul 2>&1
if %errorlevel% neq 0 (
    echo [X] Error: PowerShell not found in system.
    pause
    exit /b 1
)

set "MIRRLY_BAT_DIR=%~dp0"

if exist "%~dp0deploy.ps1" (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1"
    goto :finish
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; $cmd = Invoke-RestMethod -Uri 'https://raw.githubusercontent.com/joycecurcirt539-dot/Mirrly-TG-Proxy/main/tools/deploy-worker/deploy.ps1'; Invoke-Expression $cmd"

:finish
set "EXIT_CODE=%errorlevel%"
if %EXIT_CODE% neq 0 (
    echo.
    echo [!] Finished with exit code %EXIT_CODE%.
    echo.
    pause
)
exit /b %EXIT_CODE%