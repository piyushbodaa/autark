cmake_minimum_required(VERSION 3.22.1)
project(autark LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Arm phones get KleidiAI's hand-tuned matrix kernels and OpenMP threads.
# x86_64 is only here so the app runs on the emulator.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON)
    set(GGML_OPENMP ON)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(GGML_CPU_KLEIDIAI OFF)
    set(GGML_OPENMP OFF)
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
add_subdirectory(${LLAMA_SRC} llama)

add_library(autark SHARED autark_jni.cpp)
target_link_libraries(autark llama llama-common android log)
