cmake_minimum_required(VERSION 3.22.1)

project(flit_local_runtime VERSION 1.0.0 LANGUAGES C CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "The local runtime is only built for Android")
endif()

if(NOT DEFINED LLAMA_CPP_DIR OR NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Pass -DLLAMA_CPP_DIR=/absolute/path/to/llama.cpp")
endif()

if(NOT ANDROID_ABI STREQUAL "arm64-v8a" AND NOT ANDROID_ABI STREQUAL "x86_64")
    message(FATAL_ERROR "Only arm64-v8a and x86_64 are published by FLIT")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

# Keep the runtime self-contained. KleidiAI fetches a second source archive while configuring,
# so it is intentionally left off for the first release.
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_CPP_DIR}" llama-cpp)

add_library(flit_local_llama SHARED src/main/cpp/flit_local_llama.cpp)
target_include_directories(flit_local_llama PRIVATE
    "${LLAMA_CPP_DIR}/include"
    "${LLAMA_CPP_DIR}/ggml/include")
target_link_libraries(flit_local_llama PRIVATE llama log android)
target_link_options(flit_local_llama PRIVATE "-Wl,-z,max-page-size=16384")

install(TARGETS flit_local_llama LIBRARY DESTINATION lib)
