# HRAM — warstwa natywna (arena na pliku rzadkim + mmap + LZ4)
cmake_minimum_required(VERSION 3.22.1)
project(hram LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(hram SHARED
        hram_core.cpp
        hram_jni.cpp
        lz4/lz4.c)

target_include_directories(hram PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# LZ4 wymaga -O3 dla sensownej prędkości; nie wyłączamy optymalizacji.
target_compile_options(hram PRIVATE
        -O3
        -Wall
        -Wextra
        -Wno-unused-parameter)

find_library(log-lib log)
target_link_libraries(hram ${log-lib} android)
