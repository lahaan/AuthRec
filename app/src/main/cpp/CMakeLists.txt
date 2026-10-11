# Native code: the software video encoders for "Compress" in the recordings list.
#
# x265 (third_party/x265, GPL-2.0-or-later, release 4.2 from download.videolan.org) is built as
# a 10-bit static library: HEVC Main10 output, AArch64 Neon/SVE kernels picked at run time.
cmake_minimum_required(VERSION 3.22)
project(authrec_codecs C CXX)

# Always optimised: the owner runs the debug build, and an -O0 encoder is unusably slow.
set(CMAKE_BUILD_TYPE Release)
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")

set(HIGH_BIT_DEPTH ON CACHE BOOL "" FORCE)
set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(ENABLE_CLI OFF CACHE BOOL "" FORCE)
set(ENABLE_LIBNUMA OFF CACHE BOOL "" FORCE)
set(ENABLE_HDR10_PLUS OFF CACHE BOOL "" FORCE)
# x265 runs the compiler itself for its AArch64 assembly, without the NDK's target and sysroot
# (which CMake adds only to its own compile steps): the objects came out for the Mac. Its
# assembler flag list starts from ours.
set(ASM_FLAGS --target=${CMAKE_CXX_COMPILER_TARGET} --sysroot=${CMAKE_SYSROOT})
# Hidden symbols: the assembly addresses x265's tables directly (ADRP), which a shared library only
# allows for symbols nothing outside can replace; we only call x265 from inside our own library.
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
add_subdirectory(third_party/x265/source x265 EXCLUDE_FROM_ALL)

add_library(authrec_codecs SHARED x265_jni.cpp)
target_include_directories(authrec_codecs PRIVATE third_party/x265/source ${CMAKE_CURRENT_BINARY_DIR}/x265)
target_link_libraries(authrec_codecs PRIVATE x265-static log)
