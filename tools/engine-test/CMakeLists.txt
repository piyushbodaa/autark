# Builds Autark's engine for the desktop, to test it with real models without a phone:
#
#   cmake -S tools/engine-test -B build/engine-test -G Ninja -DCMAKE_BUILD_TYPE=Release
#   cmake --build build/engine-test
#   build/engine-test/engine-test path/to/model.gguf

cmake_minimum_required(VERSION 3.22.1)
project(engine-test LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(BUILD_SHARED_LIBS OFF)
set(LLAMA_BUILD_COMMON ON)
set(LLAMA_BUILD_TESTS OFF)
set(LLAMA_BUILD_TOOLS OFF)
set(LLAMA_BUILD_EXAMPLES OFF)
set(LLAMA_BUILD_SERVER OFF)
set(LLAMA_BUILD_APP OFF)
set(LLAMA_OPENSSL OFF)

set(AUTARK_ROOT ${CMAKE_CURRENT_LIST_DIR}/../..)
add_subdirectory(${AUTARK_ROOT}/third_party/llama.cpp llama)
include(${AUTARK_ROOT}/third_party/patches/patches.cmake)
autark_patch_llama(${AUTARK_ROOT}/third_party/llama.cpp src/llama-model-loader.cpp llama-load-in-parallel.patch)

add_executable(engine-test main.cpp ${AUTARK_ROOT}/app/src/main/cpp/engine.cpp)
target_include_directories(engine-test PRIVATE ${AUTARK_ROOT}/app/src/main/cpp)
target_link_libraries(engine-test PRIVATE llama llama-common)

# Replies to a list of questions, for comparing sampling settings by reading them:
#   build/engine-test/engine-ask path/to/model.gguf tools/engine-test/questions.txt --seeds 2
add_executable(engine-ask ask.cpp ${AUTARK_ROOT}/app/src/main/cpp/engine.cpp)
target_include_directories(engine-ask PRIVATE ${AUTARK_ROOT}/app/src/main/cpp)
target_link_libraries(engine-ask PRIVATE llama llama-common)
