#!/usr/bin/env bash
# build_bpf.sh — compile the SHIELD Mode-A eBPF program
#
# Usage
# -----
#   ./build_bpf.sh [--arch arm64|x86_64] [--out <dir>]
#
# Prerequisites
# -------------
#   • clang 12+  (Ubuntu: apt install clang  |  macOS: brew install llvm)
#   • libbpf headers at  ../vendor/libbpf/src  OR  /usr/include/bpf
#   • Linux kernel UAPI headers (typically from  linux-libc-dev)
#
# The script produces:
#   <out>/shield_bpf.o       — BPF ELF object for arm64-v8a
#   <out>/shield_bpf.x86.o  — BPF ELF object for x86_64 (emulator)
#
# Both objects are intended to be pushed to the device as:
#   adb push shield_bpf.o /data/local/tmp/shield_bpf.o

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
BPF_SRC="${REPO_ROOT}/ebpf/shield_bpf.c"
INCLUDE_DIR="${REPO_ROOT}/include"
OUT_DIR="${SCRIPT_DIR}/bpf_out"

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
ARCH="arm64"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --arch)   ARCH="$2";    shift 2 ;;
        --out)    OUT_DIR="$2"; shift 2 ;;
        *)        echo "Unknown argument: $1"; exit 1 ;;
    esac
done

mkdir -p "${OUT_DIR}"

# ---------------------------------------------------------------------------
# Locate libbpf headers
# ---------------------------------------------------------------------------
LIBBPF_VENDOR="${REPO_ROOT}/vendor/libbpf/src"
if [[ -d "${LIBBPF_VENDOR}" ]]; then
    LIBBPF_INCLUDE="-I${LIBBPF_VENDOR} -I${REPO_ROOT}/vendor/libbpf/include"
    echo "[build_bpf] Using vendored libbpf headers: ${LIBBPF_VENDOR}"
elif [[ -d "/usr/include/bpf" ]]; then
    LIBBPF_INCLUDE="-I/usr/include"
    echo "[build_bpf] Using system libbpf headers: /usr/include/bpf"
else
    echo "[ERROR] libbpf headers not found."
    echo "  Option A: git submodule update --init modea/vendor/libbpf"
    echo "  Option B: apt install libbpf-dev"
    exit 1
fi

# Kernel UAPI headers
UAPI_INCLUDE=""
for dir in /usr/include /usr/include/linux /usr/src/linux-headers-$(uname -r)/include; do
    if [[ -f "${dir}/linux/bpf.h" ]] || [[ -f "${dir}/bpf.h" ]]; then
        UAPI_INCLUDE="-I${dir}"
        break
    fi
done

# ---------------------------------------------------------------------------
# Compile for arm64 (primary Android target)
# ---------------------------------------------------------------------------
ARM64_OUT="${OUT_DIR}/shield_bpf.o"
echo "[build_bpf] Compiling for bpf (arm64 target context)…"
clang \
    -target bpf \
    -O2 \
    -g \
    -D__BPF__ \
    -D__TARGET_ARCH_arm64 \
    ${UAPI_INCLUDE} \
    ${LIBBPF_INCLUDE} \
    -I"${INCLUDE_DIR}" \
    -Wall \
    -Wno-unused-value \
    -Wno-pointer-sign \
    -Wno-compare-distinct-pointer-types \
    -c "${BPF_SRC}" \
    -o "${ARM64_OUT}"

echo "[build_bpf] ✓ ${ARM64_OUT}"

# ---------------------------------------------------------------------------
# Compile for x86_64 (Android emulator / CI)
# ---------------------------------------------------------------------------
X86_OUT="${OUT_DIR}/shield_bpf.x86.o"
echo "[build_bpf] Compiling for bpf (x86_64 target context)…"
clang \
    -target bpf \
    -O2 \
    -g \
    -D__BPF__ \
    -D__TARGET_ARCH_x86 \
    ${UAPI_INCLUDE} \
    ${LIBBPF_INCLUDE} \
    -I"${INCLUDE_DIR}" \
    -Wall \
    -Wno-unused-value \
    -Wno-pointer-sign \
    -Wno-compare-distinct-pointer-types \
    -c "${BPF_SRC}" \
    -o "${X86_OUT}"

echo "[build_bpf] ✓ ${X86_OUT}"

# ---------------------------------------------------------------------------
# Verify with bpftool (optional — skip if not installed)
# ---------------------------------------------------------------------------
if command -v bpftool &>/dev/null; then
    echo "[build_bpf] Verifying BPF programs with bpftool:"
    bpftool prog load "${ARM64_OUT}" /dev/null 2>&1 || true
    echo "[build_bpf] bpftool check complete"
else
    echo "[build_bpf] bpftool not found — skipping verification (install: apt install linux-tools-common)"
fi

echo ""
echo "[build_bpf] Build complete."
echo "  ARM64 → ${ARM64_OUT}"
echo "  x86   → ${X86_OUT}"
echo ""
echo "Deploy to device:"
echo "  adb push ${ARM64_OUT} /data/local/tmp/shield_bpf.o"
