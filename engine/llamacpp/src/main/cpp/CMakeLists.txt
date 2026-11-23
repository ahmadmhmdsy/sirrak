cmake_minimum_required(VERSION 3.22.1)
project(sirrak_llm CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# Debug builds of llama.cpp are unusably slow on an emulator; always compile optimised code.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# llama.cpp defaults to shared libraries; link one static JNI library instead so the app ships a
# single libsirrak_llm.so per ABI.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_STATIC ON CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# NDK r27 / AGP CMake passes -static-libstdc++ but never links a libc++; append the C++ runtime
# explicitly so the final JNI library has no undefined std:: symbols.
string(APPEND CMAKE_CXX_STANDARD_LIBRARIES " -lc++_static -lc++abi")

# Vendored at D:/sirrak/third_party/llama.cpp (pinned source, MIT). From this file:
# engine/llamacpp/src/main/cpp -> ../../../../../third_party/llama.cpp
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/llama.cpp)

set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
# M9: vision. libmtmd is the vendored multimodal front-end (mmproj projectors); its root CMake hook
# only fires with LLAMA_BUILD_TOOLS off, which is exactly how this build is configured.
set(LLAMA_BUILD_MTMD     ON CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE          OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

add_library(sirrak_llm SHARED llama_jni.cpp)
target_link_libraries(sirrak_llm PRIVATE llama mtmd android log)
# The JNI library carries no debug info to the device: strip at link time. Without this the
# release APK packages a ~53 MB .so per ABI, and AGP cannot strip it after the fact on Windows.
target_link_options(sirrak_llm PRIVATE -s)
