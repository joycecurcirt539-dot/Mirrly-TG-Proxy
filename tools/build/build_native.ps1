param(
    [string]$SingleTarget = ""
)
# Mirrly TG Proxy - Native Rust Engine Build Script (NDK + Stale Cache Pruning)
$ErrorActionPreference = "Stop"

$currentDir = Split-Path -Parent $MyInvocation.MyCommand.Path
# Resolve project root (whether run from root or tools/build)
$projectRoot = if (Test-Path "$currentDir\..\..\mirrlyvpn") {
    (Resolve-Path "$currentDir\..\..").Path
} elseif (Test-Path "$currentDir\mirrlyvpn") {
    $currentDir
} else {
    (Get-Location).Path
}

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "Mirrly TG Proxy - Native Rust Engines (NDK)" -ForegroundColor White
Write-Host "Project Root: $projectRoot" -ForegroundColor DarkGray
Write-Host "=============================================" -ForegroundColor Cyan

# 1. Locate Android NDK
$ndkDir = $null
$candidatePaths = @(
    $env:ANDROID_NDK_HOME,
    $env:ANDROID_NDK_ROOT,
    $env:NDK_HOME
)

# Check local.properties for sdk.dir / ndk.dir
$localProps = "$projectRoot\local.properties"
if (Test-Path $localProps) {
    Get-Content $localProps | ForEach-Object {
        if ($_ -match "^\s*sdk\.dir\s*=\s*(.+)$") {
            $sdkFromProp = $matches[1].Trim().Replace('\\', '\').Replace('\:', ':')
            if (Test-Path "$sdkFromProp\ndk") {
                $candidatePaths += Get-ChildItem -Path "$sdkFromProp\ndk\*" -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
            }
        }
        if ($_ -match "^\s*ndk\.dir\s*=\s*(.+)$") {
            $candidatePaths += $matches[1].Trim().Replace('\\', '\').Replace('\:', ':')
        }
    }
}

if ($env:ANDROID_HOME) {
    $candidatePaths += Get-ChildItem -Path "$env:ANDROID_HOME\ndk\*" -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
}
if ($env:ANDROID_SDK_ROOT) {
    $candidatePaths += Get-ChildItem -Path "$env:ANDROID_SDK_ROOT\ndk\*" -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
}
if ($env:LOCALAPPDATA) {
    $candidatePaths += Get-ChildItem -Path "$env:LOCALAPPDATA\Android\Sdk\ndk\*" -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
}

$validNdkDirs = $candidatePaths | Where-Object { $_ -and (Test-Path "$_\toolchains\llvm\prebuilt") } | Sort-Object -Descending
if ($validNdkDirs -and $validNdkDirs.Count -gt 0) {
    $ndkDir = $validNdkDirs[0]
}

if (-not $ndkDir) {
    Write-Error "Android NDK not found. Please set ANDROID_NDK_HOME or configure sdk.dir in local.properties."
    exit 1
}

$env:ANDROID_NDK_HOME = $ndkDir
$env:NDK_HOME = $ndkDir
Write-Host "Using Android NDK: $ndkDir" -ForegroundColor Green

$isWin = $IsWindows -or $env:OS -like "*Windows*"
$ndkBinDir = Get-ChildItem -Path "$ndkDir\toolchains\llvm\prebuilt\*\bin" -Directory -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $ndkBinDir -or -not (Test-Path $ndkBinDir)) {
    Write-Error "Android NDK LLVM bin directory not found under $ndkDir"
    exit 1
}

# 2. Configure MSVC and Windows SDK LIB paths for host build scripts and proc-macros
if ($isWin) {
    $msvcCandidates = @(
        "C:\Program Files\Microsoft Visual Studio\*\Community\VC\Tools\MSVC\*\lib\onecore\x64",
        "C:\Program Files\Microsoft Visual Studio\*\Community\VC\Tools\MSVC\*\lib\x64",
        "C:\Program Files (x86)\Microsoft Visual Studio\*\*\VC\Tools\MSVC\*\lib\x64",
        "C:\Program Files (x86)\Microsoft Visual Studio\*\*\VC\Tools\MSVC\*\lib\onecore\x64"
    )
    $msvcLibDir = $msvcCandidates | ForEach-Object { Resolve-Path $_ -ErrorAction SilentlyContinue } | Where-Object { Test-Path (Join-Path $_.Path "msvcrt.lib") } | Select-Object -First 1 -ExpandProperty Path
    $sdkUmDir = Resolve-Path "C:\Program Files (x86)\Windows Kits\10\Lib\*\um\x64" -ErrorAction SilentlyContinue | Select-Object -Last 1 -ExpandProperty Path
    $sdkUcrtDir = Resolve-Path "C:\Program Files (x86)\Windows Kits\10\Lib\*\ucrt\x64" -ErrorAction SilentlyContinue | Select-Object -Last 1 -ExpandProperty Path

    $libEntries = @($msvcLibDir, $sdkUmDir, $sdkUcrtDir) | Where-Object { $_ -and (Test-Path $_) }
    if ($libEntries.Count -gt 0) {
        $env:LIB = ($libEntries -join ";") + $(if ($env:LIB) { ";$env:LIB" } else { "" })
    }
}

# 3. Auto-enable sccache if installed & preserve .cargo/config.toml rustflags
if (-not $env:RUSTC_WRAPPER) {
    $sccacheCmd = Get-Command sccache -ErrorAction SilentlyContinue
    if ($sccacheCmd) {
        $env:RUSTC_WRAPPER = $sccacheCmd.Source
        Write-Host "Using sccache: $($sccacheCmd.Source)" -ForegroundColor Green
    }
}
Remove-Item Env:RUSTFLAGS -ErrorAction SilentlyContinue
Remove-Item Env:CARGO_ENCODED_RUSTFLAGS -ErrorAction SilentlyContinue

# 4. Ensure Rust targets are installed
$installedTargets = @(rustup target list --installed)
$requiredTargets = @(
    @{ rust = "aarch64-linux-android";   abi = "arm64-v8a";   clangPrefix = "aarch64-linux-android";    envLower = "aarch64_linux_android";   envUpper = "AARCH64_LINUX_ANDROID" },
    @{ rust = "armv7-linux-androideabi"; abi = "armeabi-v7a"; clangPrefix = "armv7a-linux-androideabi"; envLower = "armv7_linux_androideabi"; envUpper = "ARMV7_LINUX_ANDROIDEABI" },
    @{ rust = "i686-linux-android";      abi = "x86";         clangPrefix = "i686-linux-android";       envLower = "i686_linux_android";      envUpper = "I686_LINUX_ANDROID" },
    @{ rust = "x86_64-linux-android";    abi = "x86_64";      clangPrefix = "x86_64-linux-android";     envLower = "x86_64_linux_android";    envUpper = "X86_64_LINUX_ANDROID" }
)

foreach ($t in $requiredTargets) {
    if ($installedTargets -notcontains $t.rust) {
        Write-Host "Installing rustup target $($t.rust)..." -ForegroundColor Yellow
        rustup target add $t.rust | Out-Null
    }
}

# Helper: prune stale hashed Cargo artifacts inside a single profile dir (target/<triple>/release or target/release)
function Prune-CargoProfileDir {
    param(
        [string]$ProfileDir,
        [System.Collections.Generic.HashSet[string]]$ActivePaths,
        [string]$RootCrateName = "mirrlyengine"
    )

    if (-not (Test-Path $ProfileDir)) { return }
    $normProfileDir = [System.IO.Path]::GetFullPath($ProfileDir).TrimEnd('\', '/')
    $depsDir = Join-Path $normProfileDir "deps"
    $buildDir = Join-Path $normProfileDir "build"
    $fpDir = Join-Path $normProfileDir ".fingerprint"
    $incDir = Join-Path $normProfileDir "incremental"

    if (Test-Path $incDir) {
        Remove-Item -Path $incDir -Recurse -Force -ErrorAction SilentlyContinue
    }

    $activeDepsFiles = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
    $activeBuildDirs = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
    $activeHashes = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)

    $sep = [System.IO.Path]::DirectorySeparatorChar
    $depsPrefix = "$depsDir$sep"
    $buildPrefix = "$buildDir$sep"
    foreach ($p in $ActivePaths) {
        if ($p.StartsWith($depsPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            [void]$activeDepsFiles.Add($p)
            $name = [System.IO.Path]::GetFileName($p)
            if ($name -match '-([0-9a-f]{16})(?:\.|$)') {
                [void]$activeHashes.Add($matches[1])
            }
        } elseif ($p.StartsWith($buildPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            $rel = $p.Substring($buildPrefix.Length)
            $topDir = ($rel -split '[\\/]', 2)[0]
            if ($topDir) {
                [void]$activeBuildDirs.Add($topDir)
                if ($topDir -match '-([0-9a-f]{16})$') {
                    [void]$activeHashes.Add($matches[1])
                }
            }
        }
    }

    if ($activeDepsFiles.Count -eq 0 -and $activeHashes.Count -eq 0) {
        return
    }

    $removedCount = 0
    if (Test-Path $depsDir) {
        foreach ($file in (Get-ChildItem -Path $depsDir -File -ErrorAction SilentlyContinue)) {
            $full = [System.IO.Path]::GetFullPath($file.FullName)
            $keep = $activeDepsFiles.Contains($full) -or
                $file.Name.StartsWith("$RootCrateName.", [System.StringComparison]::OrdinalIgnoreCase) -or
                $file.Name.StartsWith("lib$RootCrateName.", [System.StringComparison]::OrdinalIgnoreCase)
            if (-not $keep -and $file.Name -match '-([0-9a-f]{16})(?:\.|$)') {
                $keep = $activeHashes.Contains($matches[1])
            }
            if (-not $keep) {
                Remove-Item -Path $full -Force -ErrorAction SilentlyContinue
                $removedCount++
            }
        }
    }

    if (Test-Path $buildDir) {
        foreach ($dir in (Get-ChildItem -Path $buildDir -Directory -ErrorAction SilentlyContinue)) {
            if (-not $activeBuildDirs.Contains($dir.Name)) {
                Remove-Item -Path $dir.FullName -Recurse -Force -ErrorAction SilentlyContinue
                $removedCount++
            }
        }
    }

    if (Test-Path $fpDir) {
        $latestRootFp = Get-ChildItem -Path $fpDir -Directory -Filter "$RootCrateName-*" -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTimeUtc -Descending |
            Select-Object -First 1 -ExpandProperty Name
        foreach ($dir in (Get-ChildItem -Path $fpDir -Directory -ErrorAction SilentlyContinue)) {
            $keep = ($dir.Name -eq $latestRootFp)
            if (-not $keep -and $dir.Name -match '-([0-9a-f]{16})$') {
                $keep = $activeHashes.Contains($matches[1])
            }
            if (-not $keep) {
                Remove-Item -Path $dir.FullName -Recurse -Force -ErrorAction SilentlyContinue
                $removedCount++
            }
        }
    }

    if ($removedCount -gt 0) {
        $label = $normProfileDir.Replace("$projectRoot$sep", "")
        Write-Host "  Pruned $removedCount stale cache entries in $label" -ForegroundColor DarkGray
    }
}

# 5. Select targets and build via Cargo + NDK LLVM toolchain
$outJniLibs = "$projectRoot\app\src\main\jniLibs"
$apiLevel = 26
$effectiveSingleTarget = if ($SingleTarget) { $SingleTarget } elseif ($env:MIRRLY_SINGLE_ABI) { $env:MIRRLY_SINGLE_ABI } else { "" }

$targetsToBuild = @()
if ($effectiveSingleTarget) {
    $matched = @($requiredTargets | Where-Object { $_.rust -eq $effectiveSingleTarget -or $_.abi -eq $effectiveSingleTarget })
    if ($matched.Count -eq 0) {
        Write-Error "Unknown target/ABI '$effectiveSingleTarget'. Expected one of: arm64-v8a, armeabi-v7a, x86, x86_64."
        exit 1
    }
    $targetsToBuild = $matched
    Write-Host "Target filtered to: $($matched[0].abi)" -ForegroundColor Yellow
} else {
    $targetsToBuild = $requiredTargets
}

$crates = @(
    @{ name = "mirrlyvpn"; dir = (Join-Path $projectRoot "mirrlyvpn"); soName = "libmirrlyvpn.so" }
)

foreach ($crate in $crates) {
    $manifestPath = Join-Path $crate.dir "Cargo.toml"
    if (-not (Test-Path $manifestPath)) {
        Write-Error "Crate Cargo.toml not found: $manifestPath"
        exit 1
    }
}

$clangExt = if ($isWin) { ".cmd" } else { "" }
$exeExt = if ($isWin) { ".exe" } else { "" }
$arTool = Join-Path $ndkBinDir "llvm-ar$exeExt"
$ranlibTool = Join-Path $ndkBinDir "llvm-ranlib$exeExt"

foreach ($t in $targetsToBuild) {
    $ccTool = Join-Path $ndkBinDir "$($t.clangPrefix)$apiLevel-clang$clangExt"
    $cxxTool = Join-Path $ndkBinDir "$($t.clangPrefix)$apiLevel-clang++$clangExt"
    if (-not (Test-Path $ccTool)) {
        Write-Error "NDK compiler not found: $ccTool"
        exit 1
    }

    Set-Item -Path "Env:CC_$($t.envLower)" -Value $ccTool
    Set-Item -Path "Env:CXX_$($t.envLower)" -Value $cxxTool
    Set-Item -Path "Env:AR_$($t.envLower)" -Value $arTool
    Set-Item -Path "Env:RANLIB_$($t.envLower)" -Value $ranlibTool
    Set-Item -Path "Env:CARGO_TARGET_$($t.envUpper)_LINKER" -Value $ccTool
    Set-Item -Path "Env:CARGO_TARGET_$($t.envUpper)_AR" -Value $arTool
}

try {
    foreach ($crate in $crates) {
        Set-Location $crate.dir
        $targetRoot = Join-Path $crate.dir "target"
        $allActivePaths = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)

        foreach ($t in $targetsToBuild) {
            Write-Host "Building $($crate.name) [$($t.abi) / $($t.rust)] (API $apiLevel)..." -ForegroundColor Cyan
            $jsonLines = & cargo build --release --lib --target $t.rust --message-format=json-render-diagnostics
            if ($LASTEXITCODE -ne 0) {
                Write-Error "cargo build failed for $($crate.name) ($($t.rust)) with exit code $LASTEXITCODE"
                exit 1
            }

            $targetActivePaths = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
            $builtSo = $null
            foreach ($line in $jsonLines) {
                if (-not $line -or -not $line.StartsWith("{")) { continue }
                $msg = $line | ConvertFrom-Json -ErrorAction SilentlyContinue
                if (-not $msg) { continue }
                if ($msg.reason -eq "compiler-artifact") {
                    foreach ($f in $msg.filenames) {
                        $full = [System.IO.Path]::GetFullPath($f)
                        [void]$targetActivePaths.Add($full)
                        [void]$allActivePaths.Add($full)
                        if ($msg.target.name -eq $crate.name -and $full.EndsWith($crate.soName, [System.StringComparison]::OrdinalIgnoreCase)) {
                            $builtSo = $full
                        }
                    }
                } elseif ($msg.reason -eq "build-script-executed" -and $msg.out_dir) {
                    $full = [System.IO.Path]::GetFullPath($msg.out_dir)
                    [void]$targetActivePaths.Add($full)
                    [void]$allActivePaths.Add($full)
                }
            }

            if (-not $builtSo) {
                $builtSo = "$targetRoot\$($t.rust)\release\$($crate.soName)"
            }
            if (-not (Test-Path $builtSo)) {
                Write-Error "Built shared library not found: $builtSo"
                exit 1
            }

            $abiOutDir = Join-Path $outJniLibs $t.abi
            if (-not (Test-Path $abiOutDir)) {
                New-Item -ItemType Directory -Path $abiOutDir -Force | Out-Null
            }
            $destSo = Join-Path $abiOutDir $crate.soName
            Copy-Item -Path $builtSo -Destination $destSo -Force

            Prune-CargoProfileDir -ProfileDir "$targetRoot\$($t.rust)\release" -ActivePaths $targetActivePaths -RootCrateName $crate.name
        }

        Prune-CargoProfileDir -ProfileDir "$targetRoot\release" -ActivePaths $allActivePaths -RootCrateName $crate.name
    }
} finally {
    Set-Location "$projectRoot"
}

# 6. Post-processing with llvm-strip (single pass per library)
$stripTool = Join-Path $ndkBinDir "llvm-strip$exeExt"
if (-not (Test-Path $stripTool)) {
    $stripTool = Get-ChildItem -Path "$ndkDir\toolchains\llvm\prebuilt\*\bin\llvm-strip*" -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
}

if ($stripTool -and (Test-Path $stripTool)) {
    Write-Host "Running llvm-strip on output libraries..." -ForegroundColor DarkGray
    foreach ($t in $targetsToBuild) {
        foreach ($crate in $crates) {
            $soPath = Join-Path (Join-Path $outJniLibs $t.abi) $crate.soName
            if (Test-Path $soPath) {
                & $stripTool --strip-all --remove-section=.comment --remove-section=.note.GNU-stack "$soPath" 2>$null
                $size = (Get-Item $soPath).Length
                Write-Host "  -> $($t.abi)/$($crate.soName) ($size bytes)" -ForegroundColor Green
            }
        }
    }
}

Write-Host "=============================================" -ForegroundColor Green
Write-Host "Native build complete: all JNI libs updated in app/src/main/jniLibs/" -ForegroundColor Green
Write-Host "=============================================" -ForegroundColor Green
