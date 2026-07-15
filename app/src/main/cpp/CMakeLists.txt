cmake_minimum_required(VERSION 3.22.1)

project("flit_terminal")

add_library(termux SHARED
        termux_pty.cpp)

target_link_libraries(termux
        log)
