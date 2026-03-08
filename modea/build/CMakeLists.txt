# CMakeLists.txt — SHIELD Mode-A native build
#
# Targets
# -------
#   shield_modea_daemon   — root daemon executable (arm64-v8a)
#   modea_jni             — shared library loaded by ModeAJni.java
#
# Prerequisites
# -------------
#   • Android NDK r25+  (set via ANDROID_NDK_HOME or cmake -DANDROID_NDK=...)
#   • clang / lld from NDK toolchain
#
# No external library dependencies (libbpf, libelf, zlib) required.
# The daemon uses a self-contained ELF loader with direct bpf() syscalls.
#
# Typical invocation (from modea/build/):
#   cmake .. \
#     -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=arm64-v8a \
#     -DANDROID_PLATFORM=android-30 \
#     -DANDROID_STL=c++_static \
#   cmake --build . --target shield_modea_daemon modea_jni

cmake_minimum_required(VERSION 3.22)
project(shield_mode_a C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD   11)

# ---------------------------------------------------------------------------
# Shared include directories
# ---------------------------------------------------------------------------
set(MODEA_INCLUDES
    ${CMAKE_SOURCE_DIR}/../include
    ${CMAKE_SOURCE_DIR}/../daemon
)

# ---------------------------------------------------------------------------
# Target 1: shield_modea_daemon (executable, runs as root on device)
# ---------------------------------------------------------------------------
add_executable(shield_modea_daemon
    ${CMAKE_SOURCE_DIR}/../daemon/daemon_main.cpp
    ${CMAKE_SOURCE_DIR}/../daemon/modea_daemon.cpp
    ${CMAKE_SOURCE_DIR}/../daemon/bpf_loader.cpp
)

target_include_directories(shield_modea_daemon PRIVATE
    ${MODEA_INCLUDES}
)

target_link_libraries(shield_modea_daemon
    log               # Android logcat
)

target_compile_options(shield_modea_daemon PRIVATE
    -Wall -Wextra -O2 -fstack-protector-strong
)

# Deploy the daemon to the device after build (requires adb in PATH)
add_custom_command(TARGET shield_modea_daemon POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E echo
        "Daemon built: $<TARGET_FILE:shield_modea_daemon>"
    COMMENT "shield_modea_daemon ready for adb push"
)

# ---------------------------------------------------------------------------
# Target 2: modea_jni (shared library loaded by ModeAJni.java)
# ---------------------------------------------------------------------------
add_library(modea_jni SHARED
    ${CMAKE_SOURCE_DIR}/../jni/modea_jni.cpp
)

target_include_directories(modea_jni PRIVATE
    ${MODEA_INCLUDES}
)

target_link_libraries(modea_jni
    log
)

target_compile_options(modea_jni PRIVATE
    -Wall -Wextra -O2 -fstack-protector-strong
    -fvisibility=hidden          # hide all symbols except JNI exports
)
