# CMakeLists.txt — SHIELD main app native build
#
# Builds the JNI shared library that bridges ModeAService (Java) to the
# Unix-socket / event-parsing layer (C++).
#
# The daemon executable (shield_modea_daemon) and BPF object (shield_bpf.o)
# are compiled separately by modea/build_real.sh and placed in
# modea/app/src/main/assets/, which is included as an additional assets
# source directory in app/build.gradle.kts.

cmake_minimum_required(VERSION 3.22)
project(shield_native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD   11)

# ---------------------------------------------------------------------------
# modea_jni — JNI bridge loaded by ModeAJni.java
# ---------------------------------------------------------------------------
add_library(modea_jni SHARED
    ../modea/jni/modea_jni.cpp
)

target_include_directories(modea_jni PRIVATE
    ../modea/include
)

target_link_libraries(modea_jni
    log
)

target_compile_options(modea_jni PRIVATE
    -Wall -Wextra -O2 -fstack-protector-strong
    -fvisibility=hidden   # hide all symbols except JNI exports
)
