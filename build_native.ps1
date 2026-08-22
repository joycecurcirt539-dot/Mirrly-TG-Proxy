# Mirrly TG Proxy - Portable Rust Native Engine Build Script (PowerShell)
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $scriptDir) { $scriptDir = (Get-Location).Path }

Write-Host "============================================="
Write-Host "Mirrly TG Proxy - Building Native Rust Engine"
Write-Host "============================================="

# 1. Locate Android NDK
$ndkDir = $null
$candidatePaths = @(
    $env:ANDROID_NDK_HOME,
    $env:ANDROID_NDK_ROOT,
    $env:NDK_HOME
)

if ($env:ANDROID_HOME) {
    $candidatePaths += Get-ChildItem -Path "$env:ANDROID_HOME\ndk\*" -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
}
if ($env:ANDROID_SDK_ROOT) {
    $candidatePaths += Get-ChildItem -Path "$env:ANDROID_SDK_ROOT\ndk\*" -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
}
if ($env:LOCALAPPDATA) {
    $candidatePaths += Get-ChildItem -Path "$env:LOCALAPPDATA\Android\Sdk\ndk\*" -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
}

# Sort descending to pick latest NDK version
$validNdkDirs = $candidatePaths | Where-Object { $_ -and (Test-Path "$_\toolchains\llvm\prebuilt\windows-x86_64\bin") } | Sort-Object -Descending

if ($validNdkDirs -and $validNdkDirs.Count -gt 0) {
    $ndkDir = $validNdkDirs[0]
}

if (-not $ndkDir) {
    Write-Error "Android NDK not found. Please set ANDROID_NDK_HOME environment variable or install NDK via Android Studio SDK Manager."
    exit 1
}

$ndkBin = "$ndkDir\toolchains\llvm\prebuilt\windows-x86_64\bin"
Write-Host "Using Android NDK: $ndkDir"
Write-Host "Toolchain bin:    $ndkBin"

# Add NDK bin to PATH
$env:PATH = "$ndkBin;$env:PATH"

# 2. Configure API Level (minSdk = 26)
$apiLevel = 26

$env:CC_aarch64_linux_android = "$ndkBin\aarch64-linux-android$($apiLevel)-clang.cmd"
$env:CC_armv7_linux_androideabi = "$ndkBin\armv7a-linux-androideabi$($apiLevel)-clang.cmd"
$env:CC_i686_linux_android = "$ndkBin\i686-linux-android$($apiLevel)-clang.cmd"
$env:CC_x86_64_linux_android = "$ndkBin\x86_64-linux-android$($apiLevel)-clang.cmd"

$env:AR_aarch64_linux_android = "$ndkBin\llvm-ar.exe"
$env:AR_armv7_linux_androideabi = "$ndkBin\llvm-ar.exe"
$env:AR_i686_linux_android = "$ndkBin\llvm-ar.exe"
$env:AR_x86_64_linux_android = "$ndkBin\llvm-ar.exe"

$targets = @(
    @{ rust = "aarch64-linux-android"; jni = "arm64-v8a" },
    @{ rust = "armv7-linux-androideabi"; jni = "armeabi-v7a" },
    @{ rust = "i686-linux-android"; jni = "x86" },
    @{ rust = "x86_64-linux-android"; jni = "x86_64" }
)

# 3. Ensure Rust targets are installed
$installedTargets = @(rustup target list --installed)
foreach ($t in $targets) {
    if ($installedTargets -notcontains $t.rust) {
        Write-Host "Installing target $($t.rust)..."
        rustup target add $t.rust | Out-Null
    }
}

# 4. Build each target
Set-Location "$scriptDir\mirrlyengine"

foreach ($t in $targets) {
    Write-Host "---------------------------------------------"
    Write-Host "Building for $($t.rust) -> $($t.jni)..."
    Write-Host "---------------------------------------------"
    cargo build --target $($t.rust) --release
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Failed to build $($t.rust)"
        exit 1
    }
    $src = "$scriptDir\mirrlyengine\target\$($t.rust)\release\libmirrlyengine.so"
    $dstDir = "$scriptDir\app\src\main\jniLibs\$($t.jni)"
    if (-not (Test-Path $dstDir)) {
        New-Item -ItemType Directory -Path $dstDir -Force | Out-Null
    }
    $dst = "$dstDir\libmirrlyengine.so"
    Copy-Item -Path $src -Destination $dst -Force
    Write-Host "Copied $src -> $dst"
}

Set-Location "$scriptDir"

Write-Host "============================================="
Write-Host "All 4 JNI libraries successfully built and verified!"
Write-Host "============================================="
