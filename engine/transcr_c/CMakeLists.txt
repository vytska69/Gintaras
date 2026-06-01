# Builds libtranscr (clean reimplementation) for any ABI, incl. arm64-v8a.
cmake_minimum_required(VERSION 3.18)
project(transcr C)

set(CMAKE_C_STANDARD 11)
add_compile_options(-O2 -Wall)

set(TRANSCR_SRCS
    transcr.c
    transcr_core.c
    transcr_tables.c
    function_words.c
    g2p_phoneme_table.c)

add_library(transcr SHARED ${TRANSCR_SRCS})
target_link_libraries(transcr m)

enable_testing()
add_executable(test_transcr test_transcr.c transcr.c)
add_test(NAME leaf_functions COMMAND test_transcr)

add_executable(test_core test_core.c transcr_core.c transcr_tables.c function_words.c g2p_phoneme_table.c)
add_test(NAME core_functions COMMAND test_core)
