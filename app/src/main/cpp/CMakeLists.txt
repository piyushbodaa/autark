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
include(${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/patches/patches.cmake)
autark_patch_llama(${LLAMA_SRC} src/llama-model-loader.cpp llama-load-in-parallel.patch)

# llama.cpp's common library (chat templates, tokenizing, sampling) is linked into libautark
# rather than shipped as its own 4 MB libllama-common.so, so the linker keeps only what Autark
# uses, and exceptions thrown by the templates are caught within the same library.
foreach(var LLAMA_VERSION_BASE LLAMA_VERSION_MAJOR LLAMA_BUILD_NUMBER LLAMA_BUILD_COMMIT BUILD_COMPILER BUILD_TARGET)
    get_directory_property(${var} DIRECTORY ${LLAMA_SRC} DEFINITION ${var})  # set in llama.cpp's scope
endforeach()
set(BUILD_SHARED_LIBS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory(${LLAMA_SRC}/vendor/cpp-httplib cpp-httplib)  # llama.cpp adds it only with its common
add_subdirectory(${LLAMA_SRC}/common llama-common)

add_library(autark SHARED autark_jni.cpp engine.cpp)
target_link_libraries(autark llama llama-common android log)
# Export only the JNI entry points; everything else is internal and can be dropped if unused.
target_link_options(autark PRIVATE -Wl,--version-script=${CMAKE_CURRENT_LIST_DIR}/autark.map -Wl,--gc-sections)
set_target_properties(autark PROPERTIES LINK_DEPENDS ${CMAKE_CURRENT_LIST_DIR}/autark.map)

# The on-device tests (app/src/androidTest) run tools/engine-test and llama-bench through this
# library. Release builds leave it out (app/build.gradle.kts).
set(AUTARK_TOOLS ${CMAKE_CURRENT_LIST_DIR}/../../../../tools)
add_library(autark-tests SHARED
    ${AUTARK_TOOLS}/device-test/device_test.cpp
    ${AUTARK_TOOLS}/engine-test/main.cpp
    ${LLAMA_SRC}/tools/llama-bench/llama-bench.cpp
    engine.cpp)
set_source_files_properties(${AUTARK_TOOLS}/engine-test/main.cpp PROPERTIES COMPILE_DEFINITIONS main=engine_test_main)
target_include_directories(autark-tests PRIVATE ${CMAKE_CURRENT_LIST_DIR})
target_link_libraries(autark-tests llama llama-common android log)
