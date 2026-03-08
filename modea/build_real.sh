#!/usr/bin/env bash
# build_real.sh — cross-compile SHIELD Mode-A daemon and BPF object for arm64
#
# Run from WSL (AMD64 Linux host) targeting Android arm64-v8a.
# Uses the NDK's aarch64-linux-android cross-compiler.
# No external library dependencies (libbpf removed — self-contained ELF loader).
#
# Prerequisites (inside WSL):
#   export ANDROID_NDK_HOME=~/android-ndk-r25c   # Linux NDK — NOT the Windows one
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ="${SCRIPT_DIR}"

NDK="${ANDROID_NDK_HOME:-${HOME}/android-ndk-r25c}"
if [[ ! -d "${NDK}/toolchains/llvm/prebuilt/linux-x86_64" ]]; then
    echo "ERROR: Linux NDK not found at ${NDK}"
    echo "  Download and extract the Linux NDK (not the Windows one):"
    echo "    cd ~ && wget https://dl.google.com/android/repository/android-ndk-r25c-linux.zip"
    echo "    unzip android-ndk-r25c-linux.zip"
    echo "    export ANDROID_NDK_HOME=~/android-ndk-r25c"
    exit 1
fi

PREBUILT="${NDK}/toolchains/llvm/prebuilt/linux-x86_64"
CC="${PREBUILT}/bin/aarch64-linux-android30-clang"
CXX="${PREBUILT}/bin/aarch64-linux-android30-clang++"
SYSROOT="${PREBUILT}/sysroot"
API=30

OUT="${PROJ}/out"
mkdir -p "${OUT}"

# Kernel annotation macros — must be defined before any kernel UAPI headers
KFLAGS="-D__user= -D__kernel= -D__force= -D__iomem= -D__must_check= -D__cold= -D__wsum=__u32"

echo "=== [1/2] Compiling eBPF program (target: BPF VM — host arch irrelevant) ==="
cd "${PROJ}"

# clang -target bpf compiles to BPF bytecode regardless of host architecture.
# This produces architecture-independent BPF ELF that runs on both arm64 and x86 devices.
clang -target bpf -O2 -g \
    -D__BPF__ \
    -D__TARGET_ARCH_arm64 \
    ${KFLAGS} \
    -I"include" \
    -c "ebpf/shield_bpf.c" \
    -o "${OUT}/shield_bpf.o"
echo "  → ${OUT}/shield_bpf.o"

echo "=== [2/2] Cross-compiling daemon (host: x86_64 → target: aarch64-linux-android) ==="

# Only NDK sysroot headers for the daemon (no /usr/include leakage → arm64 binary)
"${CXX}" \
    --sysroot="${SYSROOT}" \
    -target "aarch64-linux-android${API}" \
    -O2 -std=c++17 \
    ${KFLAGS} \
    -I"include" \
    -I"daemon" \
    -Wno-error -Wno-macro-redefined \
    "daemon/daemon_main.cpp" \
    "daemon/modea_daemon.cpp" \
    "daemon/bpf_loader.cpp" \
    -llog \
    -static-libstdc++ \
    -o "${OUT}/shield_modea_daemon"

echo "  → ${OUT}/shield_modea_daemon"

echo "=== Verifying arm64 binary ==="
file "${OUT}/shield_modea_daemon" 2>/dev/null || true   # shows "ELF 64-bit LSB … aarch64"

echo ""
echo "=== Copying binaries to APK assets (for deployBinaries()) ==="
ASSETS_DIR="${PROJ}/app/src/main/assets"
mkdir -p "${ASSETS_DIR}"
cp "${OUT}/shield_bpf.o"        "${ASSETS_DIR}/shield_bpf.o"
cp "${OUT}/shield_modea_daemon" "${ASSETS_DIR}/shield_modea_daemon"
echo "  → ${ASSETS_DIR}/shield_modea_daemon"
echo "  → ${ASSETS_DIR}/shield_bpf.o"
echo "  Rebuild the APK after this step so assets are bundled."

echo "=== Pushing to device via adb ==="
ADB=""
if command -v adb &>/dev/null; then
    ADB="adb"
else
    for u in "/mnt/c/Users/${USER}" /mnt/c/Users/*/; do
        p="${u%/}/AppData/Local/Android/Sdk/platform-tools/adb.exe"
        if [ -f "${p}" ]; then
            ADB="${p}"
            break
        fi
    done
fi

if [ -n "${ADB}" ]; then
    # adb.exe requires Windows-style paths when running from WSL
    if [[ "${ADB}" == *.exe ]]; then
        BPF_WIN="$(wslpath -w "${OUT}/shield_bpf.o")"
        DAE_WIN="$(wslpath -w "${OUT}/shield_modea_daemon")"
        "${ADB}" push "${BPF_WIN}"  /data/local/tmp/shield_bpf.o
        "${ADB}" push "${DAE_WIN}"  /data/local/tmp/shield_modea_daemon
    else
        "${ADB}" push "${OUT}/shield_bpf.o"        /data/local/tmp/shield_bpf.o
        "${ADB}" push "${OUT}/shield_modea_daemon" /data/local/tmp/shield_modea_daemon
    fi
    "${ADB}" shell chmod 755 /data/local/tmp/shield_modea_daemon
    echo "✓ Pushed arm64 binaries to device"
else
    echo "adb not found — push manually:"
    echo "  adb push out/shield_bpf.o        /data/local/tmp/shield_bpf.o"
    echo "  adb push out/shield_modea_daemon /data/local/tmp/shield_modea_daemon"
    echo "  adb shell chmod 755 /data/local/tmp/shield_modea_daemon"
fi

