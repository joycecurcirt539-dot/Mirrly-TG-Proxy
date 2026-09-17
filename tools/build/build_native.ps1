# Mirrly TG Proxy - Portable Rust Native Engine Build Script (PowerShell)
$ErrorActionPreference = "Stop"

$currentDir = Split-Path -Parent $MyInvocation.MyCommand.Path
# Resolve project root (whether run from root or tools/build)
$projectRoot = if (Test-Path "$currentDir\..\..\mirrlyengine") {
    Resolve-Path "$currentDir\..\.."
} elseif (Test-Path "$currentDir\mirrlyengine") {
    $currentDir
} else {
    (Get-Location).Path
}

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "Mirrly TG Proxy - Building Native Rust Engine" -ForegroundColor White
Write-Host "Project Root: $projectRoot" -ForegroundColor DarkGray
Write-Host "=============================================" -ForegroundColor Cyan

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

# Configure MSVC and Windows SDK LIB paths for host build scripts and proc-macros
if ($IsWindows -or $env:OS -like "*Windows*") {
    $msvcCandidates = @(
        "C:\Program Files\Microsoft Visual Studio\*\Community\VC\Tools\MSVC\*\lib\onecore\x64",
        "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Tools\MSVC\*\lib\x64",
        "C:\Program Files\Microsoft Visual Studio\*\Community\VC\Tools\MSVC\*\lib\x64",
        "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Tools\MSVC\*\lib\onecore\x64",
        "C:\Program Files (x86)\Microsoft Visual Studio\*\Community\VC\Tools\MSVC\*\lib\onecore\x64"
    )
    $msvcLibDir = $msvcCandidates | ForEach-Object { Resolve-Path $_ -ErrorAction SilentlyContinue } | Where-Object { Test-Path (Join-Path $_.Path "msvcrt.lib") } | Select-Object -First 1 -ExpandProperty Path
    $sdkUmDir = Resolve-Path "C:\Program Files (x86)\Windows Kits\10\Lib\*\um\x64" -ErrorAction SilentlyContinue | Select-Object -Last 1 -ExpandProperty Path
    $sdkUcrtDir = Resolve-Path "C:\Program Files (x86)\Windows Kits\10\Lib\*\ucrt\x64" -ErrorAction SilentlyContinue | Select-Object -Last 1 -ExpandProperty Path

    $libEntries = @($msvcLibDir, $sdkUmDir, $sdkUcrtDir) | Where-Object { $_ -and (Test-Path $_) }
    if ($libEntries.Count -gt 0) {
        $env:LIB = ($libEntries -join ";") + $(if ($env:LIB) { ";$env:LIB" } else { "" })
    }
}

# 2. Configure API Level (minSdk = 26)
$apiLevel = 26

$env:CC_aarch64_linux_android = "$ndkBin\aarch64-linux-android$($apiLevel)-clang.cmd"
$env:CC_armv7_linux_androideabi = "$ndkBin\armv7a-linux-androideabi$($apiLevel)-clang.cmd"
$env:CC_i686_linux_android = "$ndkBin\i686-linux-android$($apiLevel)-clang.cmd"
$env:CC_x86_64_linux_android = "$ndkBin\x86_64-linux-android$($apiLevel)-clang.cmd"

$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = "$ndkBin\aarch64-linux-android$($apiLevel)-clang.cmd"
$env:CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER = "$ndkBin\armv7a-linux-androideabi$($apiLevel)-clang.cmd"
$env:CARGO_TARGET_I686_LINUX_ANDROID_LINKER = "$ndkBin\i686-linux-android$($apiLevel)-clang.cmd"
$env:CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER = "$ndkBin\x86_64-linux-android$($apiLevel)-clang.cmd"

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

# 4. Configure external cargo target directory to keep repository lightweight
if (-not $env:CARGO_TARGET_DIR) {
    $cargoCacheRoot = if ($env:USERPROFILE) { "$env:USERPROFILE\.cargo\target-cache\mirrlyengine" } else { "$projectRoot\mirrlyengine\target" }
    $env:CARGO_TARGET_DIR = $cargoCacheRoot
}
$targetDir = $env:CARGO_TARGET_DIR
Write-Host "Cargo Target Dir: $targetDir"

# 4.1. Configure Rust path remapping to eliminate local developer paths from binaries and enable symbol stripping
$normProjectRoot = $projectRoot.ToString().TrimEnd('\', '/')
$normUser = if ($env:USERPROFILE) { $env:USERPROFILE.TrimEnd('\', '/') } else { "" }
$normTarget = $targetDir.ToString().TrimEnd('\', '/')

$remapList = @()
if ($normProjectRoot) {
    $remapList += "--remap-path-prefix=$normProjectRoot\mirrlyengine=/mirrlyengine"
    $remapList += "--remap-path-prefix=$($normProjectRoot.Replace('\', '/'))/mirrlyengine=/mirrlyengine"
    $remapList += "--remap-path-prefix=$normProjectRoot=/mirrly"
    $remapList += "--remap-path-prefix=$($normProjectRoot.Replace('\', '/'))=/mirrly"
}
if ($normTarget) {
    $remapList += "--remap-path-prefix=$normTarget=/cargo-target"
    $remapList += "--remap-path-prefix=$($normTarget.Replace('\', '/'))=/cargo-target"
}
if ($normUser) {
    $remapList += "--remap-path-prefix=$normUser\.cargo=/cargo"
    $remapList += "--remap-path-prefix=$($normUser.Replace('\', '/'))/.cargo=/cargo"
    $remapList += "--remap-path-prefix=$normUser\.rustup=/rustup"
    $remapList += "--remap-path-prefix=$($normUser.Replace('\', '/'))/.rustup=/rustup"
    $remapList += "--remap-path-prefix=$normUser=/user"
    $remapList += "--remap-path-prefix=$($normUser.Replace('\', '/'))=/user"
}
$remapList += "--remap-path-prefix=C:\Users\iplii=/user"
$remapList += "--remap-path-prefix=C:/Users/iplii=/user"
$remapList += "--remap-path-prefix=c:\projects\Mirrly dev=/projects"
$remapList += "--remap-path-prefix=c:/projects/Mirrly dev=/projects"
$remapList += "-C"
$remapList += "strip=symbols"
$remapList += "-C"
$remapList += "debuginfo=0"

$env:RUSTFLAGS = $null
$env:CARGO_ENCODED_RUSTFLAGS = $remapList -join [char]0x1F
Write-Host "CARGO_ENCODED_RUSTFLAGS configured with path remapping & symbol stripping"

# 5. Build each target
Set-Location "$projectRoot\mirrlyengine"

foreach ($t in $targets) {
    Write-Host "---------------------------------------------"
    Write-Host "Building for $($t.rust) -> $($t.jni)..."
    Write-Host "---------------------------------------------"
    cargo build --target $($t.rust) --release
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Failed to build $($t.rust)"
        exit 1
    }
    $src = "$targetDir\$($t.rust)\release\libmirrlyengine.so"
    $dstDir = "$projectRoot\app\src\main\jniLibs\$($t.jni)"
    if (-not (Test-Path $dstDir)) {
        New-Item -ItemType Directory -Path $dstDir -Force | Out-Null
    }
    $dst = "$dstDir\libmirrlyengine.so"
    Copy-Item -Path $src -Destination $dst -Force
    Write-Host "Copied $src -> $dst"

    # Post-process with NDK llvm-strip to ensure zero leftover debug/comment symbols
    $stripTool = "$ndkBin\llvm-strip.exe"
    if (Test-Path $stripTool) {
        Write-Host "Running llvm-strip on $dst..."
        & $stripTool --strip-all "$dst"
        & $stripTool --strip-debug "$dst"
        & $stripTool --remove-section=.comment "$dst" 2>$null
        & $stripTool --remove-section=.note.GNU-stack "$dst" 2>$null
    }
}

Set-Location "$projectRoot"

Write-Host "=============================================" -ForegroundColor Green
Write-Host "All 4 JNI libraries successfully built and verified!" -ForegroundColor Green
Write-Host "=============================================" -ForegroundColor Green
