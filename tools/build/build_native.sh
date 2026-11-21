#!/usr/bin/env bash
set -euo pipefail

CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Resolve project root
if [ -d "$CURRENT_DIR/../../mirrlyvpn" ]; then
    PROJECT_ROOT="$(cd "$CURRENT_DIR/../.." && pwd)"
elif [ -d "$CURRENT_DIR/mirrlyvpn" ]; then
    PROJECT_ROOT="$CURRENT_DIR"
else
    PROJECT_ROOT="$(pwd)"
fi

echo "============================================="
echo "Mirrly TG Proxy - Native Rust Engines (NDK)"
echo "Project Root: $PROJECT_ROOT"
echo "============================================="

# 1. Locate Android NDK
NDK_DIR=""
if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    NDK_DIR="$ANDROID_NDK_HOME"
elif [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -d "$ANDROID_NDK_ROOT" ]; then
    NDK_DIR="$ANDROID_NDK_ROOT"
elif [ -n "${NDK_HOME:-}" ] && [ -d "$NDK_HOME" ]; then
    NDK_DIR="$NDK_HOME"
elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    NDK_DIR="$(ls -d "$ANDROID_HOME/ndk/"* 2>/dev/null | sort -V | tail -n 1)"
elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/ndk" ]; then
    NDK_DIR="$(ls -d "$ANDROID_SDK_ROOT/ndk/"* 2>/dev/null | sort -V | tail -n 1)"
fi

if [ -z "$NDK_DIR" ] && [ -f "$PROJECT_ROOT/local.properties" ]; then
    SDK_DIR_LINE="$(grep -E '^\s*sdk\.dir\s*=' "$PROJECT_ROOT/local.properties" | cut -d'=' -f2- | tr -d ' \r\n' || true)"
    if [ -n "$SDK_DIR_LINE" ] && [ -d "$SDK_DIR_LINE/ndk" ]; then
        NDK_DIR="$(ls -d "$SDK_DIR_LINE/ndk/"* 2>/dev/null | sort -V | tail -n 1)"
    fi
fi

if [ -z "$NDK_DIR" ] || [ ! -d "$NDK_DIR" ]; then
    echo "ERROR: Android NDK not found. Please set ANDROID_NDK_HOME." >&2
    exit 1
fi

export ANDROID_NDK_HOME="$NDK_DIR"
export NDK_HOME="$NDK_DIR"
echo "Using Android NDK: $NDK_DIR"

HOST_TAG="$(uname | tr '[:upper:]' '[:lower:]')-x86_64"
NDK_BIN_DIR="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG/bin"
if [ ! -d "$NDK_BIN_DIR" ]; then
    NDK_BIN_DIR="$(find "$NDK_DIR/toolchains/llvm/prebuilt" -maxdepth 2 -type d -name "bin" 2>/dev/null | head -n 1 || true)"
fi
if [ -z "$NDK_BIN_DIR" ] || [ ! -d "$NDK_BIN_DIR" ]; then
    echo "ERROR: Android NDK LLVM bin directory not found under $NDK_DIR" >&2
    exit 1
fi

# 2. Auto-enable sccache if installed & preserve .cargo/config.toml rustflags
if [ -z "${RUSTC_WRAPPER:-}" ] && command -v sccache &>/dev/null; then
    export RUSTC_WRAPPER="$(command -v sccache)"
    echo "Using sccache: $RUSTC_WRAPPER"
fi
unset RUSTFLAGS CARGO_ENCODED_RUSTFLAGS

# 3. Ensure Rust targets are installed
INSTALLED_TARGETS="$(rustup target list --installed)"
TARGETS=(
    "aarch64-linux-android"
    "armv7-linux-androideabi"
    "i686-linux-android"
    "x86_64-linux-android"
)

for target in "${TARGETS[@]}"; do
    if ! grep -qx "$target" <<<"$INSTALLED_TARGETS"; then
        echo "Installing rustup target $target..."
        rustup target add "$target" || true
    fi
done

prune_cargo_profile_dir() {
    local profile_dir="$1"
    local root_crate="$2"
    shift 2
    [ -d "$profile_dir" ] || return 0
    rm -rf "$profile_dir/incremental"

    if ! command -v python3 &>/dev/null; then
        return 0
    fi

    python3 - "$profile_dir" "$root_crate" "$@" <<'PYEOF'
import json, os, re, shutil, sys

profile_dir = os.path.realpath(sys.argv[1])
root_crate = sys.argv[2]
json_files = sys.argv[3:]

active_deps = set()
active_build = set()
active_hashes = set()
hash_re = re.compile(r'-([0-9a-f]{16})(?:\.|$)')

deps_prefix = os.path.join(profile_dir, "deps") + os.sep
build_prefix = os.path.join(profile_dir, "build") + os.sep

def record(path_str):
    if not path_str:
        return
    p = os.path.realpath(path_str)
    if p.startswith(deps_prefix):
        active_deps.add(p)
        m = hash_re.search(os.path.basename(p))
        if m:
            active_hashes.add(m.group(1))
    elif p.startswith(build_prefix):
        rel = p[len(build_prefix):]
        top = rel.split(os.sep, 1)[0]
        if top:
            active_build.add(top)
            m = re.search(r'-([0-9a-f]{16})$', top)
            if m:
                active_hashes.add(m.group(1))

for jf in json_files:
    with open(jf, "r", encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                msg = json.loads(line)
            except Exception:
                continue
            reason = msg.get("reason")
            if reason == "compiler-artifact":
                for fn in msg.get("filenames") or []:
                    record(fn)
            elif reason == "build-script-executed":
                record(msg.get("out_dir"))

if not active_deps and not active_hashes:
    sys.exit(0)

removed = 0
deps_dir = os.path.join(profile_dir, "deps")
if os.path.isdir(deps_dir):
    for entry in os.scandir(deps_dir):
        if not entry.is_file():
            continue
        p = os.path.realpath(entry.path)
        keep = p in active_deps or entry.name.startswith((f"{root_crate}.", f"lib{root_crate}."))
        if not keep:
            m = hash_re.search(entry.name)
            if m and m.group(1) in active_hashes:
                keep = True
        if not keep:
            os.remove(p)
            removed += 1

build_dir = os.path.join(profile_dir, "build")
if os.path.isdir(build_dir):
    for entry in os.scandir(build_dir):
        if entry.is_dir() and entry.name not in active_build:
            shutil.rmtree(entry.path, ignore_errors=True)
            removed += 1

fp_dir = os.path.join(profile_dir, ".fingerprint")
if os.path.isdir(fp_dir):
    root_fps = [
        (entry.stat().st_mtime, entry.name)
        for entry in os.scandir(fp_dir)
        if entry.is_dir() and entry.name.startswith(f"{root_crate}-")
    ]
    latest_root = max(root_fps)[1] if root_fps else None
    for entry in os.scandir(fp_dir):
        if not entry.is_dir():
            continue
        keep = (entry.name == latest_root)
        if not keep:
            m = re.search(r'-([0-9a-f]{16})$', entry.name)
            if m and m.group(1) in active_hashes:
                keep = True
        if not keep:
            shutil.rmtree(entry.path, ignore_errors=True)
            removed += 1

if removed > 0:
    print(f"  Pruned {removed} stale cache entries in {profile_dir}")
PYEOF
}

# 4. Build ABIs via Cargo + NDK LLVM toolchain
OUT_JNI_LIBS="$PROJECT_ROOT/app/src/main/jniLibs"
API_LEVEL=26
SINGLE_ABI="${1:-${MIRRLY_SINGLE_ABI:-}}"

if [ -n "$SINGLE_ABI" ]; then
    case "$SINGLE_ABI" in
        arm64-v8a|aarch64-linux-android) ABIS_TO_BUILD=("arm64-v8a") ;;
        armeabi-v7a|armv7-linux-androideabi) ABIS_TO_BUILD=("armeabi-v7a") ;;
        x86|i686-linux-android) ABIS_TO_BUILD=("x86") ;;
        x86_64|x86_64-linux-android) ABIS_TO_BUILD=("x86_64") ;;
        *)
            echo "ERROR: Unknown target/ABI '$SINGLE_ABI'." >&2
            exit 1
            ;;
    esac
    echo "Target filtered to: ${ABIS_TO_BUILD[0]}"
else
    ABIS_TO_BUILD=("arm64-v8a" "armeabi-v7a" "x86" "x86_64")
fi

CRATES=("mirrlyvpn")
for crate in "${CRATES[@]}"; do
    if [ ! -f "$PROJECT_ROOT/$crate/Cargo.toml" ]; then
        echo "ERROR: Crate Cargo.toml not found: $PROJECT_ROOT/$crate/Cargo.toml" >&2
        exit 1
    fi
done

resolve_abi_vars() {
    local abi="$1"
    case "$abi" in
        arm64-v8a)
            RUST_TRIPLE="aarch64-linux-android"
            CLANG_PREFIX="aarch64-linux-android"
            ENV_LOWER="aarch64_linux_android"
            ENV_UPPER="AARCH64_LINUX_ANDROID"
            ;;
        armeabi-v7a)
            RUST_TRIPLE="armv7-linux-androideabi"
            CLANG_PREFIX="armv7a-linux-androideabi"
            ENV_LOWER="armv7_linux_androideabi"
            ENV_UPPER="ARMV7_LINUX_ANDROIDEABI"
            ;;
        x86)
            RUST_TRIPLE="i686-linux-android"
            CLANG_PREFIX="i686-linux-android"
            ENV_LOWER="i686_linux_android"
            ENV_UPPER="I686_LINUX_ANDROID"
            ;;
        x86_64)
            RUST_TRIPLE="x86_64-linux-android"
            CLANG_PREFIX="x86_64-linux-android"
            ENV_LOWER="x86_64_linux_android"
            ENV_UPPER="X86_64_LINUX_ANDROID"
            ;;
    esac
}

TMP_JSON_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_JSON_DIR"' EXIT

AR_TOOL="$NDK_BIN_DIR/llvm-ar"
RANLIB_TOOL="$NDK_BIN_DIR/llvm-ranlib"

for abi in "${ABIS_TO_BUILD[@]}"; do
    resolve_abi_vars "$abi"
    CC_TOOL="$NDK_BIN_DIR/${CLANG_PREFIX}${API_LEVEL}-clang"
    CXX_TOOL="$NDK_BIN_DIR/${CLANG_PREFIX}${API_LEVEL}-clang++"
    if [ ! -x "$CC_TOOL" ]; then
        echo "ERROR: NDK compiler not found: $CC_TOOL" >&2
        exit 1
    fi

    export "CC_${ENV_LOWER}=$CC_TOOL"
    export "CXX_${ENV_LOWER}=$CXX_TOOL"
    export "AR_${ENV_LOWER}=$AR_TOOL"
    export "RANLIB_${ENV_LOWER}=$RANLIB_TOOL"
    export "CARGO_TARGET_${ENV_UPPER}_LINKER=$CC_TOOL"
    export "CARGO_TARGET_${ENV_UPPER}_AR=$AR_TOOL"
done

for crate in "${CRATES[@]}"; do
    CRATE_DIR="$PROJECT_ROOT/$crate"
    SO_NAME="lib${crate}.so"
    TARGET_ROOT="$CRATE_DIR/target"
    ALL_JSON_LOGS=()

    cd "$CRATE_DIR"

    for abi in "${ABIS_TO_BUILD[@]}"; do
        resolve_abi_vars "$abi"

        echo "Building $crate [$abi / $RUST_TRIPLE] (API $API_LEVEL)..."
        JSON_LOG="$TMP_JSON_DIR/${crate}_${abi}.json"
        cargo build --release --lib --target "$RUST_TRIPLE" --message-format=json-render-diagnostics > "$JSON_LOG"
        ALL_JSON_LOGS+=("$JSON_LOG")

        BUILT_SO="$TARGET_ROOT/$RUST_TRIPLE/release/$SO_NAME"
        if [ ! -f "$BUILT_SO" ]; then
            echo "ERROR: Built shared library not found: $BUILT_SO" >&2
            exit 1
        fi

        mkdir -p "$OUT_JNI_LIBS/$abi"
        cp -f "$BUILT_SO" "$OUT_JNI_LIBS/$abi/$SO_NAME"

        prune_cargo_profile_dir "$TARGET_ROOT/$RUST_TRIPLE/release" "$crate" "$JSON_LOG"
    done

    prune_cargo_profile_dir "$TARGET_ROOT/release" "$crate" "${ALL_JSON_LOGS[@]}"
done

# 5. Post-processing with llvm-strip (single pass per library)
STRIP_TOOL="$NDK_BIN_DIR/llvm-strip"
if [ ! -x "$STRIP_TOOL" ]; then
    STRIP_TOOL="$(find "$NDK_DIR/toolchains/llvm/prebuilt" -type f -name "llvm-strip" 2>/dev/null | head -n 1 || true)"
fi

if [ -n "$STRIP_TOOL" ] && [ -x "$STRIP_TOOL" ]; then
    echo "Running llvm-strip on output libraries..."
    for abi in "${ABIS_TO_BUILD[@]}"; do
        for crate in "${CRATES[@]}"; do
            SO_NAME="lib${crate}.so"
            SO_PATH="$OUT_JNI_LIBS/$abi/$SO_NAME"
            if [ -f "$SO_PATH" ]; then
                "$STRIP_TOOL" --strip-all --remove-section=.comment --remove-section=.note.GNU-stack "$SO_PATH" 2>/dev/null || true
                echo "  -> $abi/$SO_NAME ($(wc -c < "$SO_PATH") bytes)"
            fi
        done
    done
fi

cd "$PROJECT_ROOT"

echo "============================================="
echo "Native build complete: all JNI libs updated in app/src/main/jniLibs/"
echo "============================================="
