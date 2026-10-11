# Native code: the software video encoders for "Compress" in the recordings list.
#
# - x265 (third_party/x265, GPL-2.0-or-later, release 4.2): 10-bit static library, HEVC Main10,
#   AArch64 Neon/SVE kernels picked at run time.
# - x264 (third_party/x264, GPL-2.0-or-later, "stable" 2025-06-08): 8-bit H.264 High, the format
#   everything plays; its own configure/make, run here as an external project.
# - SVT-AV1 (third_party/svt-av1, BSD-3-Clause-Clear + AOM patent licence, v4.2.0): AV1.
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

# SVT-AV1: the encoder library only, static, without link-time optimisation (not worth the
# toolchain risk here).
set(BUILD_APPS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SVT_AV1_LTO OFF CACHE BOOL "" FORCE)
# With clang on Linux-like targets it replaces the archiver with a bare "llvm-ar", which the
# Android build can't run; answering its lookups with OFF keeps the NDK's own tools.
set(LLVM_LD_EXE OFF CACHE STRING "" FORCE)
set(LLVM_AR_EXE OFF CACHE STRING "" FORCE)
set(LLVM_RANLIB_EXE OFF CACHE STRING "" FORCE)
add_subdirectory(third_party/svt-av1 svt-av1 EXCLUDE_FROM_ALL)
# Its Threads::Threads became -lpthread, which Android doesn't have (threads are in libc).
set_property(TARGET SvtAv1Enc PROPERTY INTERFACE_LINK_LIBRARIES m dl)

# x264: configure + make with the NDK's clang (its assembly goes through the same compiler, so
# the target and sysroot reach it too).
include(ExternalProject)
set(X264_PREFIX ${CMAKE_CURRENT_BINARY_DIR}/x264-install)
set(X264_FLAGS "--target=${CMAKE_C_COMPILER_TARGET} --sysroot=${CMAKE_SYSROOT} -fPIC -fvisibility=hidden")
ExternalProject_Add(x264_build
    SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/x264
    BINARY_DIR ${CMAKE_CURRENT_BINARY_DIR}/x264-build
    INSTALL_DIR ${X264_PREFIX}
    CONFIGURE_COMMAND ${CMAKE_COMMAND} -E env CC=${CMAKE_C_COMPILER} AR=${CMAKE_AR} RANLIB=${CMAKE_RANLIB} STRIP=${CMAKE_STRIP}
        <SOURCE_DIR>/configure --host=aarch64-linux-android --prefix=<INSTALL_DIR>
        --enable-static --enable-pic --disable-cli --disable-opencl --bit-depth=8 --chroma-format=420
        --disable-avs --disable-swscale --disable-lavf --disable-ffms --disable-gpac --disable-lsmash
        --extra-cflags=${X264_FLAGS} --extra-asflags=${X264_FLAGS} --extra-ldflags=--target=${CMAKE_C_COMPILER_TARGET}
    BUILD_COMMAND make -j8
    INSTALL_COMMAND make install-lib-static
    BUILD_BYPRODUCTS ${X264_PREFIX}/lib/libx264.a)
add_library(x264 STATIC IMPORTED)
set_target_properties(x264 PROPERTIES IMPORTED_LOCATION ${X264_PREFIX}/lib/libx264.a)
add_dependencies(x264 x264_build)

add_library(authrec_codecs SHARED x265_jni.cpp x264_jni.cpp svtav1_jni.cpp)
target_include_directories(authrec_codecs PRIVATE third_party/x265/source ${CMAKE_CURRENT_BINARY_DIR}/x265
    ${X264_PREFIX}/include third_party/svt-av1/Source/API)
target_link_libraries(authrec_codecs PRIVATE x265-static x264 SvtAv1Enc log)
