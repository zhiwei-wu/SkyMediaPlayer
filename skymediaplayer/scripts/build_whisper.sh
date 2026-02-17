#!/bin/bash
# ===================================================================
# SkyPlayer 依赖库编译 - whisper.cpp
# 编译 whisper.cpp 静态库，供 FFmpeg 静态链接（行业标准做法）
# 产物：静态库(.a) + af_whisper_vk_helper.o，由 build_ffmpeg.sh 链接到 libskyffmpeg.so
# ===================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

# ===== 参数 =====
BUILD_TYPE="${1:-release}"
WHISPER_SRC="${2:-}"
OUTPUT_DIR="${3:-}"

if [[ -z "${WHISPER_SRC}" ]] || [[ -z "${OUTPUT_DIR}" ]]; then
    log_error "用法: build_whisper.sh <release|debug> <whisper_src_dir> <output_dir>"
    exit 1
fi

if [[ ! -d "${WHISPER_SRC}" ]]; then
    log_error "whisper.cpp 源码目录不存在: ${WHISPER_SRC}"
    exit 1
fi

log_step "编译 whisper.cpp (${BUILD_TYPE})"
timer_start

# ===== 设置编译参数 =====
setup_build_flags "${BUILD_TYPE}"

# ===== 编译目录 =====
WHISPER_BUILD_DIR="${OUTPUT_DIR}/whisper_build/${BUILD_TYPE}"
WHISPER_INSTALL_DIR="${OUTPUT_DIR}/whisper_install/${BUILD_TYPE}"
clean_directory "${WHISPER_BUILD_DIR}"
clean_directory "${WHISPER_INSTALL_DIR}"

# ===== CMake 编译类型映射 =====
# debug 模式使用 RelWithDebInfo（-O2 -g）而非 Debug（-O0），
# 确保 whisper 推理性能不因编译优化级别而大幅下降
if [[ "${BUILD_TYPE}" == "debug" ]]; then
    CMAKE_BUILD_TYPE="RelWithDebInfo"
else
    CMAKE_BUILD_TYPE="Release"
fi

# ===== 检测 Vulkan C++ 头文件路径 =====
# Android NDK 的 Vulkan 头文件只有 C 头文件（vulkan.h），不包含 C++ 头文件（vulkan.hpp）
# ggml-vulkan.cpp 需要 vulkan.hpp，因此需要从宿主机的 Vulkan SDK 或 Homebrew 获取
VULKAN_INCLUDE_DIR=""
if command -v brew &>/dev/null; then
    BREW_VK_PREFIX="$(brew --prefix vulkan-headers 2>/dev/null || true)"
    if [[ -n "${BREW_VK_PREFIX}" ]] && [[ -f "${BREW_VK_PREFIX}/include/vulkan/vulkan.hpp" ]]; then
        VULKAN_INCLUDE_DIR="${BREW_VK_PREFIX}/include"
        log_info "Vulkan C++ 头文件: ${VULKAN_INCLUDE_DIR} (Homebrew)"
    fi
fi

if [[ -z "${VULKAN_INCLUDE_DIR}" ]]; then
    # 尝试 VulkanSDK 标准路径
    for sdk_dir in "$HOME/VulkanSDK"/*/macOS/include /usr/local/include; do
        if [[ -f "${sdk_dir}/vulkan/vulkan.hpp" ]]; then
            VULKAN_INCLUDE_DIR="${sdk_dir}"
            log_info "Vulkan C++ 头文件: ${VULKAN_INCLUDE_DIR}"
            break
        fi
    done
fi

if [[ -z "${VULKAN_INCLUDE_DIR}" ]]; then
    log_error "未找到 Vulkan C++ 头文件（vulkan.hpp）"
    log_error "请安装: brew install vulkan-headers"
    exit 1
fi

# ===== CMake 配置 =====
log_info "配置 whisper.cpp..."
cmake -S "${WHISPER_SRC}" -B "${WHISPER_BUILD_DIR}" \
    -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="${ANDROID_ABI}" \
    -DANDROID_PLATFORM="android-${ANDROID_API}" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE="${CMAKE_BUILD_TYPE}" \
    -DCMAKE_INSTALL_PREFIX="${WHISPER_INSTALL_DIR}" \
    -DBUILD_SHARED_LIBS=OFF \
    -DWHISPER_BUILD_EXAMPLES=OFF \
    -DWHISPER_BUILD_TESTS=OFF \
    -DWHISPER_BUILD_SERVER=OFF \
    -DGGML_OPENMP=OFF \
    -DGGML_VULKAN=ON \
    -DGGML_CPU_ARM_ARCH="armv8.2-a+dotprod+i8mm+fp16" \
    -DVulkan_INCLUDE_DIR="${VULKAN_INCLUDE_DIR}"

# ===== 编译 =====
log_info "编译 whisper.cpp..."
cmake --build "${WHISPER_BUILD_DIR}" -j$(sysctl -n hw.ncpu)

# ===== 安装 =====
log_info "安装 whisper.cpp..."
cmake --install "${WHISPER_BUILD_DIR}"

# ===== 查找静态库产物 =====
WHISPER_LIB_DIR="${WHISPER_INSTALL_DIR}/lib"
if [[ ! -d "${WHISPER_LIB_DIR}" ]]; then
    log_error "whisper.cpp 安装目录不存在: ${WHISPER_LIB_DIR}"
    exit 1
fi

log_info "whisper.cpp 静态库目录: ${WHISPER_LIB_DIR}"
ls -la "${WHISPER_LIB_DIR}/"

# ===== 编译 af_whisper_vk_helper.cpp =====
# 该文件位于 FFmpeg 源码中，提供 Vulkan GPU 安全测试和后端注册功能
# 编译为 .o 后合并到 libskywhisper.so，供 FFmpeg 的 af_whisper.c 通过 extern 引用
VK_HELPER_OBJ=""
if [[ -n "${FFMPEG_SRC_DIR:-}" ]] && [[ -f "${FFMPEG_SRC_DIR}/libavfilter/af_whisper_vk_helper.cpp" ]]; then
    VK_HELPER_SRC="${FFMPEG_SRC_DIR}/libavfilter/af_whisper_vk_helper.cpp"
    VK_HELPER_OBJ="${WHISPER_BUILD_DIR}/af_whisper_vk_helper.o"

    log_info "编译 af_whisper_vk_helper.cpp..."
    # -fvisibility=default: 覆盖 release 版的 -fvisibility=hidden，
    # 确保 whisper_vk_try_register 等函数在动态符号表中可见，
    # 供 libskyffmpeg.so 中的 af_whisper.c 通过动态链接调用
    ${CXX} ${BUILD_CXXFLAGS} -fvisibility=default \
        -I"${WHISPER_INSTALL_DIR}/include" \
        -I"${FFMPEG_SRC_DIR}" \
        --sysroot="${SYSROOT}" \
        -c "${VK_HELPER_SRC}" \
        -o "${VK_HELPER_OBJ}"
    log_info "  产物: ${VK_HELPER_OBJ}"
else
    log_warn "未找到 af_whisper_vk_helper.cpp（FFMPEG_SRC_DIR=${FFMPEG_SRC_DIR:-未设置}）"
    log_warn "libskywhisper.so 将不包含 Vulkan helper 函数"
fi

# ===== 校验静态库产物 =====
WHISPER_STATIC_LIBS_FOUND=0

for lib in libwhisper.a libggml.a libggml-base.a libggml-cpu.a; do
    if [[ -f "${WHISPER_LIB_DIR}/${lib}" ]]; then
        log_info "  静态库: ${lib} ✓"
        WHISPER_STATIC_LIBS_FOUND=$((WHISPER_STATIC_LIBS_FOUND + 1))
    else
        log_warn "  缺失: ${lib}"
    fi
done

# Vulkan 支持库
if [[ -f "${WHISPER_LIB_DIR}/libggml-vulkan.a" ]]; then
    log_info "  静态库: libggml-vulkan.a ✓ (Vulkan GPU 加速)"
    WHISPER_STATIC_LIBS_FOUND=$((WHISPER_STATIC_LIBS_FOUND + 1))
else
    log_warn "  缺失: libggml-vulkan.a（Vulkan 已启用但未生成静态库）"
fi

if [[ ${WHISPER_STATIC_LIBS_FOUND} -eq 0 ]]; then
    log_error "未找到任何 whisper.cpp 静态库"
    exit 1
fi

# ===== 保存路径供 build_ffmpeg.sh 静态链接使用 =====
# 头文件路径（FFmpeg configure 的 -I 参数）
echo "${WHISPER_INSTALL_DIR}/include" > "${OUTPUT_DIR}/whisper_include_${BUILD_TYPE}.path"
# 静态库目录（FFmpeg 合并 libskyffmpeg.so 时直接链接 .a 文件）
echo "${WHISPER_LIB_DIR}" > "${OUTPUT_DIR}/whisper_lib_${BUILD_TYPE}.path"
# vk_helper.o 路径（如果存在）
if [[ -n "${VK_HELPER_OBJ}" ]] && [[ -f "${VK_HELPER_OBJ}" ]]; then
    echo "${VK_HELPER_OBJ}" > "${OUTPUT_DIR}/whisper_vk_helper_${BUILD_TYPE}.path"
    log_info "  vk_helper.o: ${VK_HELPER_OBJ} ✓"
fi

timer_end "whisper.cpp (${BUILD_TYPE}) 编译"
log_info "whisper.cpp 产物（静态库，将链接到 libskyffmpeg.so）:"
log_info "  静态库目录: ${WHISPER_LIB_DIR}"
log_info "  头文件目录: ${WHISPER_INSTALL_DIR}/include"
