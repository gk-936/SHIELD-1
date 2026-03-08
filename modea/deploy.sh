#!/usr/bin/env bash
# deploy.sh — Build and deploy SHIELD Mode-A native binaries to an Android device.
#
# Run this on a LINUX machine (WSL2, Ubuntu, macOS) — NOT on Windows directly.
# Windows users: wsl bash deploy.sh
#
# Prerequisites
# -------------
#   apt install clang llvm linux-libc-dev libbpf-dev   # for BPF compile
#   Android NDK r25+ in $ANDROID_NDK_HOME
#   adb connected to rooted device

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

EBPF_SRC="ebpf/shield_bpf.c"
INCLUDE_DIR="include"
DAEMON_DIR="daemon"
VENDOR_DIR="vendor/libbpf"
OUT_DIR="out"
OUT_LIBBPF_OBJS="${OUT_DIR}/libbpf_objs"

# NDK resolution order:
#   1. ANDROID_NDK_HOME env var (explicit, highest priority)
#   2. WSL: Windows SDK under /mnt/c/Users/<current Windows user>/AppData/Local/Android/Sdk/ndk
#   3. WSL: all Windows user profiles under /mnt/c/Users/*/AppData/Local/Android/Sdk/ndk
#   4. Native Linux: ~/Android/Sdk/ndk
#   5. Relative to this script: ../../sdk/ndk

find_latest_ndk() {
    local base="$1"
    [[ -d "${base}" ]] || return 1
    local latest
    latest="$(ls -d "${base}"/*/ 2>/dev/null | sort -V | tail -1)"
    [[ -n "${latest}" ]] || return 1
    echo "${latest%/}"
}

NDK=""
if [[ -n "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_NDK_HOME}" ]]; then
    NDK="${ANDROID_NDK_HOME}"
else
    # WSL: derive Windows username from /mnt/c/Users
    WIN_USER=""
    if [[ -d "/mnt/c/Users" ]]; then
        # Try the username that matches the current WSL user first, then any profile
        for candidate in "/mnt/c/Users/${USER}" /mnt/c/Users/*/; do
            candidate="${candidate%/}"
            NDK_BASE="${candidate}/AppData/Local/Android/Sdk/ndk"
            NDK="$(find_latest_ndk "${NDK_BASE}")" && break
            NDK=""
        done
    fi

    # Native Linux fallback
    if [[ -z "${NDK}" ]]; then
        NDK="$(find_latest_ndk "${HOME}/Android/Sdk/ndk")" || true
    fi

    # Relative-to-script fallback
    if [[ -z "${NDK}" ]]; then
        NDK="$(find_latest_ndk "${SCRIPT_DIR}/../../sdk/ndk")" || true
    fi

    if [[ -z "${NDK}" ]]; then
        echo "ERROR: Android NDK not found."
        echo "  On WSL, set ANDROID_NDK_HOME to the Windows NDK path, e.g.:"
        echo "    export ANDROID_NDK_HOME=\"/mnt/c/Users/\${USER}/AppData/Local/Android/Sdk/ndk/25.1.8937393\""
        exit 1
    fi
fi

echo "Using NDK: ${NDK}"

# NDK sub-paths: detect linux-x86_64 (native Linux NDK) vs windows-x86_64 (Windows NDK via WSL)
if [[ -d "${NDK}/toolchains/llvm/prebuilt/linux-x86_64" ]]; then
    PREBUILT="${NDK}/toolchains/llvm/prebuilt/linux-x86_64"
elif [[ -d "${NDK}/toolchains/llvm/prebuilt/windows-x86_64" ]]; then
    echo ""
    echo "WARNING: Found Windows NDK at: ${NDK}"
    echo "  Windows .exe binaries cannot run natively in WSL."
    echo "  Install a Linux NDK inside WSL instead:"
    echo ""
    echo "    cd ~"
    echo "    wget https://dl.google.com/android/repository/android-ndk-r25c-linux.zip"
    echo "    unzip android-ndk-r25c-linux.zip"
    echo "    export ANDROID_NDK_HOME=~/android-ndk-r25c"
    echo "    bash ${SCRIPT_DIR}/deploy.sh"
    echo ""
    exit 1
else
    echo "ERROR: NDK toolchain prebuilt directory not found under ${NDK}/toolchains/llvm/prebuilt/"
    exit 1
fi

TOOLCHAIN_BIN="${PREBUILT}/bin"
NDK_SYSROOT="${PREBUILT}/sysroot"
API=30

cd "${SCRIPT_DIR}"

mkdir -p "${OUT_DIR}" "${OUT_LIBBPF_OBJS}"

# ---------------------------------------------------------------------------
echo "=== [1/3] Compiling eBPF program ==="
# ---------------------------------------------------------------------------

LIBBPF_INC_SRC=""
LIBBPF_INC_HDR=""
if [[ -d "${VENDOR_DIR}/src" ]]; then
    LIBBPF_INC_SRC="${VENDOR_DIR}/src"
    LIBBPF_INC_HDR="${VENDOR_DIR}/include"
elif [[ -d "/usr/include/bpf" ]]; then
    LIBBPF_INC_SRC="/usr/include"
    LIBBPF_INC_HDR="/usr/include"
else
    echo "ERROR: libbpf headers not found.  Run: apt install libbpf-dev"
    exit 1
fi

clang -target bpf -O2 -g \
    -D__BPF__ \
    -I"${INCLUDE_DIR}" \
    -I"${LIBBPF_INC_SRC}" \
    -I"${LIBBPF_INC_HDR}" \
    -c "${EBPF_SRC}" \
    -o "${OUT_DIR}/shield_bpf.o"

echo "  → ${OUT_DIR}/shield_bpf.o"

# ---------------------------------------------------------------------------
echo "=== [2/3] Cross-compiling daemon (arm64-v8a, API ${API}) ==="
# ---------------------------------------------------------------------------

CC="${TOOLCHAIN_BIN}/aarch64-linux-android${API}-clang"
CXX="${TOOLCHAIN_BIN}/aarch64-linux-android${API}-clang++"
AR="${TOOLCHAIN_BIN}/llvm-ar"

if [[ ! -f "${CXX}" ]]; then
    echo "ERROR: NDK compiler not found: ${CXX}"
    echo "  Set ANDROID_NDK_HOME to your NDK r25+ installation."
    exit 1
fi

LIBBPF_SRC="${VENDOR_DIR}/src"
LIBBPF_INCLUDE="${VENDOR_DIR}/include"
LIBBPF_UAPI="${VENDOR_DIR}/include/uapi"

for src in bpf.c btf.c libbpf.c libbpf_errno.c netlink.c nlattr.c \
           str_error.c ringbuf.c hashmap.c elf.c zip.c; do
    if [[ -f "${LIBBPF_SRC}/${src}" ]]; then
        "${CC}" \
            --sysroot="${NDK_SYSROOT}" \
            -target "aarch64-linux-android${API}" \
            -O2 -D_GNU_SOURCE -DUSE_INTERNAL_RINGBUF_H \
            -I"${LIBBPF_SRC}" \
            -I"${LIBBPF_INCLUDE}" \
            -I"${LIBBPF_UAPI}" \
            -c "${LIBBPF_SRC}/${src}" \
            -o "${OUT_LIBBPF_OBJS}/${src%.c}.o"
    fi
done

"${AR}" rcs "${OUT_DIR}/libbpf.a" "${OUT_LIBBPF_OBJS}"/*.o

"${CXX}" \
    --sysroot="${NDK_SYSROOT}" \
    -target "aarch64-linux-android${API}" \
    -O2 -std=c++17 \
    -I"${INCLUDE_DIR}" \
    -I"${DAEMON_DIR}" \
    -I"${LIBBPF_SRC}" \
    -I"${LIBBPF_INCLUDE}" \
    -I"${LIBBPF_UAPI}" \
    "${DAEMON_DIR}/daemon_main.cpp" \
    "${DAEMON_DIR}/modea_daemon.cpp" \
    "${DAEMON_DIR}/bpf_loader.cpp" \
    "${OUT_DIR}/libbpf.a" \
    -llog -lz \
    -static-libstdc++ \
    -o "${OUT_DIR}/shield_modea_daemon"

echo "  → ${OUT_DIR}/shield_modea_daemon"

# ---------------------------------------------------------------------------
echo "=== [3/3] Pushing to device via adb ==="
# ---------------------------------------------------------------------------

# Find adb: prefer PATH, then Windows SDK via WSL /mnt/c mount
ADB=""
if command -v adb &>/dev/null; then
    ADB="adb"
else
    for win_user in "/mnt/c/Users/${USER}" /mnt/c/Users/*/; do
        candidate="${win_user%/}/AppData/Local/Android/Sdk/platform-tools/adb.exe"
        if [[ -f "${candidate}" ]]; then
            # Copy to /tmp with no spaces so exec works
            cp "${candidate}" /tmp/adb_win.exe
            chmod +x /tmp/adb_win.exe
            ADB="/tmp/adb_win.exe"
            break
        fi
    done
fi

if [[ -z "${ADB}" ]]; then
    echo "ERROR: adb not found. Push manually:"
    echo "  adb push out/shield_bpf.o        /data/local/tmp/shield_bpf.o"
    echo "  adb push out/shield_modea_daemon /data/local/tmp/shield_modea_daemon"
    echo "  adb shell chmod 755 /data/local/tmp/shield_modea_daemon"
    exit 1
fi

"${ADB}" push "${OUT_DIR}/shield_bpf.o"        /data/local/tmp/shield_bpf.o
"${ADB}" push "${OUT_DIR}/shield_modea_daemon" /data/local/tmp/shield_modea_daemon
"${ADB}" shell chmod 755 /data/local/tmp/shield_modea_daemon

echo ""
echo "✓ Deployed. Start SHIELD Mode-A from the app — real eBPF events will now flow."
echo ""
echo "  Monitor with:"
echo "    adb logcat -s SHIELD_MODE_A:V SHIELD_MODE_A_DETECTION:V SHIELD_BPF_LOADER:V"
