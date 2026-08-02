cmake_minimum_required(VERSION 3.22.1)

project("mirrly_sec")

add_library(
    mirrly_sec
    SHARED
    native_sec.cpp
)

find_library(
    log-lib
    log
)

target_compile_options(
    mirrly_sec
    PRIVATE
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -O3
)

target_link_options(
    mirrly_sec
    PRIVATE
    -Wl,--gc-sections
    -Wl,--strip-debug
    -Wl,--exclude-libs,ALL
)

target_link_libraries(
    mirrly_sec
    ${log-lib}
)
