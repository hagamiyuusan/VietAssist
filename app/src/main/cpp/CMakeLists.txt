cmake_minimum_required(VERSION 3.22.1)

project("lighton" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp (kèm libmtmd cho mô hình đọc ảnh) được tải theo bản phát hành cố định.
# Không cần git; CMake tự tải mã nguồn và kiểm tra SHA256.
include(FetchContent)

# OpenCL loader chỉ dùng khi liên kết; Android cung cấp driver GPU lúc chạy.
FetchContent_Declare(opencl_headers
        URL https://github.com/KhronosGroup/OpenCL-Headers/archive/refs/tags/v2026.05.29.tar.gz
        URL_HASH SHA256=d9e6c48357de5002da11ce45de600e0c3ffe6ab4f628a3b9fe2b38603161658a)
FetchContent_Declare(opencl_loader
        URL https://github.com/KhronosGroup/OpenCL-ICD-Loader/archive/refs/tags/v2026.05.29.tar.gz
        URL_HASH SHA256=48fd0c5181db7cd046f4f731d5955694892e10998d49d09ee0d997e7e04fd939)
FetchContent_Populate(opencl_headers)
set(OPENCL_ICD_LOADER_HEADERS_DIR "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_OPENCL_LAYERS OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(opencl_loader)
set_target_properties(OpenCL PROPERTIES VERSION "" SOVERSION ""
        LIBRARY_OUTPUT_DIRECTORY "${opencl_loader_BINARY_DIR}/link")
set(OpenCL_INCLUDE_DIR "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
set(OpenCL_LIBRARY "${opencl_loader_BINARY_DIR}/link/libOpenCL.so" CACHE FILEPATH "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)

set(BUILD_SHARED_LIBS      ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD       ON  CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL          OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO             OFF CACHE BOOL "" FORCE)

# Đóng gói mọi biến thể CPU ARM (armv8.0 → armv9 có SME); lúc chạy, ggml
# tự chọn biến thể nhanh nhất mà chip của điện thoại hỗ trợ.
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL        ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS  ON  CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE         OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_OPENMP ON  CACHE BOOL "" FORCE)
else()
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
endif()

FetchContent_Declare(
        llama_cpp
        URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/b11645.tar.gz
        URL_HASH SHA256=c8ef0a70d634e0b3b7c2ee92ace11422e964bec3d36652e45b7c3c0cffb74cd0
)
FetchContent_MakeAvailable(llama_cpp)

add_library(lighton SHARED lighton_jni.cpp)

target_link_libraries(lighton
        mtmd
        llama
        ggml
        android
        log)

# FindOpenCL dùng đường dẫn thư viện, cần bảo đảm loader liên kết đã được tạo.
add_dependencies(ggml-opencl OpenCL)
