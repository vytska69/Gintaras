# Builds libtranscr (clean reimplementation) for any ABI, incl. arm64-v8a.
# Used both by the host test build and (later) the Android NDK build feeding
# app/src/main/jniLibs/<abi>/libtranscr.so.
cmake_minimum_required(VERSION 3.18)
project(transcr C)

set(CMAKE_C_STANDARD 11)
add_compile_options(-O2 -Wall)

add_library(transcr SHARED transcr.c)
target_link_libraries(transcr m)

# Host test executable (not shipped).
add_executable(test_transcr test_transcr.c transcr.c)
target_link_libraries(test_transcr m)
enable_testing()
add_test(NAME leaf_functions COMMAND test_transcr)
