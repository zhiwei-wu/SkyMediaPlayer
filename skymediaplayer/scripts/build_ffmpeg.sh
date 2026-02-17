#!/bin/bash
# ===================================================================
# SkyPlayer 依赖库编译 - FFmpeg
# 编译 FFmpeg 子库（静态合并）+ whisper 静态链接，生成 libskyffmpeg.so
# whisper.cpp 采用行业标准做法：静态链接到 FFmpeg，而非独立动态库
# ===================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

# ===== 参数 =====
BUILD_TYPE="${1:-release}"
FFMPEG_SRC="${2:-}"
OUTPUT_DIR="${3:-}"
FFMPEG_EXTRA_CONFIGURE="${4:-}"

if [[ -z "${FFMPEG_SRC}" ]] || [[ -z "${OUTPUT_DIR}" ]]; then
    log_error "用法: build_ffmpeg.sh <release|debug> <ffmpeg_src_dir> <output_dir> [extra_configure]"
    exit 1
fi

if [[ ! -d "${FFMPEG_SRC}" ]]; then
    log_error "FFmpeg 源码目录不存在: ${FFMPEG_SRC}"
    exit 1
fi

log_step "编译 FFmpeg (${BUILD_TYPE})"
timer_start

# ===== 设置编译参数 =====
setup_build_flags "${BUILD_TYPE}"

# ===== 编译目录 =====
FFMPEG_BUILD_DIR="${OUTPUT_DIR}/ffmpeg_build/${BUILD_TYPE}"
FFMPEG_INSTALL_DIR="${OUTPUT_DIR}/ffmpeg_install/${BUILD_TYPE}"
clean_directory "${FFMPEG_BUILD_DIR}"
clean_directory "${FFMPEG_INSTALL_DIR}"

# ===== 读取 OpenSSL 和 whisper 的路径 =====
OPENSSL_INCLUDE_DIR=""
OPENSSL_LIB_DIR=""
WHISPER_INCLUDE_DIR=""
WHISPER_STATIC_LIB_DIR=""
WHISPER_VK_HELPER_OBJ=""

# 从编译产物路径文件中读取
if [[ -f "${OUTPUT_DIR}/openssl_include_${BUILD_TYPE}.path" ]]; then
    OPENSSL_INCLUDE_DIR=$(cat "${OUTPUT_DIR}/openssl_include_${BUILD_TYPE}.path")
    OPENSSL_LIB_DIR=$(cat "${OUTPUT_DIR}/openssl_lib_${BUILD_TYPE}.path")
    log_info "OpenSSL 头文件: ${OPENSSL_INCLUDE_DIR}"
    log_info "OpenSSL 库目录: ${OPENSSL_LIB_DIR}"
else
    log_warn "未找到 OpenSSL 编译产物路径，尝试使用 deps.config 中的默认路径"
    if [[ -n "${OPENSSL_SRC_DIR:-}" ]]; then
        OPENSSL_INCLUDE_DIR="${OPENSSL_SRC_DIR}/build/${ANDROID_ABI}/include"
        OPENSSL_LIB_DIR="${OPENSSL_SRC_DIR}/build/${ANDROID_ABI}/lib"
    fi
fi

# whisper 静态库路径（将在合并 libskyffmpeg.so 时静态链接）
if [[ -f "${OUTPUT_DIR}/whisper_include_${BUILD_TYPE}.path" ]]; then
    WHISPER_INCLUDE_DIR=$(cat "${OUTPUT_DIR}/whisper_include_${BUILD_TYPE}.path")
    WHISPER_STATIC_LIB_DIR=$(cat "${OUTPUT_DIR}/whisper_lib_${BUILD_TYPE}.path")
    log_info "whisper 头文件: ${WHISPER_INCLUDE_DIR}"
    log_info "whisper 静态库: ${WHISPER_STATIC_LIB_DIR}"
else
    log_warn "未找到 whisper 编译产物路径，尝试使用 deps.config 中的默认路径"
    if [[ -n "${WHISPER_SRC_DIR:-}" ]]; then
        WHISPER_INCLUDE_DIR="${WHISPER_SRC_DIR}/build/android/${ANDROID_ABI}/install/include"
        WHISPER_STATIC_LIB_DIR="${WHISPER_SRC_DIR}/build/android/${ANDROID_ABI}/install/lib"
    fi
fi

# vk_helper.o 路径（Vulkan GPU 后端桥接）
if [[ -f "${OUTPUT_DIR}/whisper_vk_helper_${BUILD_TYPE}.path" ]]; then
    WHISPER_VK_HELPER_OBJ=$(cat "${OUTPUT_DIR}/whisper_vk_helper_${BUILD_TYPE}.path")
    log_info "whisper vk_helper: ${WHISPER_VK_HELPER_OBJ}"
fi

# ===== 读取 ffmpeg.config 中的用户配置参数 =====
FFMPEG_USER_CONFIG=$(load_ffmpeg_config "${SCRIPT_DIR}/ffmpeg.config")
log_info "FFmpeg 用户配置参数: ${FFMPEG_USER_CONFIG}"

if [[ -n "${FFMPEG_EXTRA_CONFIGURE}" ]]; then
    log_info "FFmpeg 额外追加参数: ${FFMPEG_EXTRA_CONFIGURE}"
fi

# ===== NDK cpufeatures 路径 =====
CPU_FEATURES_DIR="${ANDROID_NDK_ROOT}/sources/android/cpufeatures"

# ===== 构建 extra-cflags 和 extra-ldflags =====
EXTRA_CFLAGS="${BUILD_CFLAGS}"
EXTRA_CFLAGS="${EXTRA_CFLAGS} -I${CPU_FEATURES_DIR}"
EXTRA_CXXFLAGS="${BUILD_CXXFLAGS}"
EXTRA_CXXFLAGS="${EXTRA_CXXFLAGS} -I${CPU_FEATURES_DIR}"
EXTRA_LDFLAGS="${BUILD_LDFLAGS} -L${CPU_FEATURES_DIR}"

# 添加 OpenSSL 路径
if [[ -n "${OPENSSL_INCLUDE_DIR}" ]]; then
    EXTRA_CFLAGS="${EXTRA_CFLAGS} -I${OPENSSL_INCLUDE_DIR}"
    EXTRA_CXXFLAGS="${EXTRA_CXXFLAGS} -I${OPENSSL_INCLUDE_DIR}"
    EXTRA_LDFLAGS="${EXTRA_LDFLAGS} -L${OPENSSL_LIB_DIR} -lskyssl"
fi

# 添加 whisper 头文件路径（whisper 静态链接，configure 阶段只需头文件）
if [[ -n "${WHISPER_INCLUDE_DIR}" ]]; then
    EXTRA_CFLAGS="${EXTRA_CFLAGS} -I${WHISPER_INCLUDE_DIR}"
    EXTRA_CXXFLAGS="${EXTRA_CXXFLAGS} -I${WHISPER_INCLUDE_DIR}"
fi

# 添加 Bsymbolic 优化
EXTRA_LDFLAGS="${EXTRA_LDFLAGS} -Wl,-Bsymbolic"

# C++ 标准库
EXTRA_LDFLAGS="${EXTRA_LDFLAGS} -lc++ -lm"

# ===== 进入 FFmpeg 源码目录配置 =====
cd "${FFMPEG_SRC}"

# 清理之前的编译
make clean 2>/dev/null || true

# ===== 创建自定义 pkgconfig 目录 =====
# whisper.pc 原始文件引用的是静态库名（-lggml -lwhisper），但我们合并为 libskywhisper.so
# 需要生成修正版的 .pc 文件，让 FFmpeg configure 的链接测试能通过
CUSTOM_PC_DIR="${FFMPEG_BUILD_DIR}/pkgconfig"
mkdir -p "${CUSTOM_PC_DIR}"

# FFmpeg configure 的 check_pkg_config 会编译链接测试程序来验证依赖
# 交叉编译时无法链接 ARM64 的 .so 文件，所以 .pc 文件必须指向静态库（.a）
# whisper 静态库最终会被直接合并到 libskyffmpeg.so 中
OPENSSL_STATIC_LIB_DIR="${OUTPUT_DIR}/openssl_install/${BUILD_TYPE}/lib"

# 生成修正版 whisper.pc（指向静态库，用于 configure 链接测试）
if [[ -n "${WHISPER_INCLUDE_DIR}" ]] && [[ -d "${WHISPER_STATIC_LIB_DIR}" ]]; then
    cat > "${CUSTOM_PC_DIR}/whisper.pc" << WHISPERPC
prefix=${OUTPUT_DIR}/whisper_install/${BUILD_TYPE}
exec_prefix=\${prefix}
libdir=${WHISPER_STATIC_LIB_DIR}
includedir=${WHISPER_INCLUDE_DIR}

Name: whisper
Description: Port of OpenAI's Whisper model in C/C++
Version: 1.8.3
Libs: -L\${libdir} -lwhisper -lggml -lggml-base -lggml-cpu -lggml-vulkan -lvulkan -lstdc++
Cflags: -I\${includedir}
WHISPERPC
    log_info "生成修正版 whisper.pc → ${CUSTOM_PC_DIR}/whisper.pc"
fi

# 生成修正版 openssl .pc 文件（指向静态库，用于 configure 链接测试）
if [[ -n "${OPENSSL_INCLUDE_DIR}" ]] && [[ -d "${OPENSSL_STATIC_LIB_DIR}" ]]; then
    cat > "${CUSTOM_PC_DIR}/openssl.pc" << OPENSSLPC
prefix=${OUTPUT_DIR}/openssl_install/${BUILD_TYPE}
exec_prefix=\${prefix}
libdir=${OPENSSL_STATIC_LIB_DIR}
includedir=${OPENSSL_INCLUDE_DIR}

Name: OpenSSL
Description: Secure Sockets Layer and cryptography libraries
Version: 3.6.0
Requires: libssl libcrypto
OPENSSLPC

    cat > "${CUSTOM_PC_DIR}/libssl.pc" << SSLPC
prefix=${OUTPUT_DIR}/openssl_install/${BUILD_TYPE}
exec_prefix=\${prefix}
libdir=${OPENSSL_STATIC_LIB_DIR}
includedir=${OPENSSL_INCLUDE_DIR}

Name: OpenSSL-libssl
Description: Secure Sockets Layer and cryptography libraries
Version: 3.6.0
Requires.private: libcrypto
Libs: -L\${libdir} -lssl
Cflags: -I\${includedir}
SSLPC

    cat > "${CUSTOM_PC_DIR}/libcrypto.pc" << CRYPTOPC
prefix=${OUTPUT_DIR}/openssl_install/${BUILD_TYPE}
exec_prefix=\${prefix}
libdir=${OPENSSL_STATIC_LIB_DIR}
includedir=${OPENSSL_INCLUDE_DIR}

Name: OpenSSL-libcrypto
Description: OpenSSL cryptography library
Version: 3.6.0
Libs: -L\${libdir} -lcrypto
Cflags: -I\${includedir}
CRYPTOPC
    log_info "生成修正版 openssl .pc 文件 → ${CUSTOM_PC_DIR}/"
fi

# 构建 PKG_CONFIG_PATH，仅使用自定义 pkgconfig 目录
CUSTOM_PKG_CONFIG_PATH="${CUSTOM_PC_DIR}"

PKG_CONFIG_SCRIPT="${FFMPEG_BUILD_DIR}/pkg-config-android.sh"
cat > "${PKG_CONFIG_SCRIPT}" << PKGEOF
#!/bin/bash
# pkg-config wrapper for Android cross-compilation
# 使用编译产物中的 .pc 文件，避免引用宿主机的库路径
export PKG_CONFIG_PATH="${CUSTOM_PKG_CONFIG_PATH}"
export PKG_CONFIG_LIBDIR="${CUSTOM_PKG_CONFIG_PATH}"
export PKG_CONFIG_SYSROOT_DIR=""
exec pkg-config "\$@"
PKGEOF
chmod +x "${PKG_CONFIG_SCRIPT}"

# ===== 清除宿主机环境变量 =====
# macOS Homebrew 可能设置了 LDFLAGS/CFLAGS/CPPFLAGS 指向宿主机库路径
# 这会导致 FFmpeg configure 的链接测试引用宿主机的 .a 文件而非 Android 交叉编译版本
unset LDFLAGS CFLAGS CPPFLAGS CXXFLAGS LIBRARY_PATH C_INCLUDE_PATH CPLUS_INCLUDE_PATH 2>/dev/null || true
log_info "已清除宿主机环境变量（LDFLAGS/CFLAGS 等），避免干扰交叉编译"

# ===== FFmpeg configure =====
log_info "配置 FFmpeg..."

# 工具链参数（自动注入，用户不需要关心）
FFMPEG_TOOLCHAIN_CONFIG="
    --target-os=android
    --cc=${CC}
    --cxx=${CXX}
    --nm=${NM}
    --strip=${STRIP}
    --enable-cross-compile
    --arch=${ANDROID_ARCH:-arm64}
    --cpu=${ANDROID_CPU:-armv8-a}
    --sysroot=${SYSROOT}
    --prefix=${FFMPEG_INSTALL_DIR}
    --cross-prefix=
    --pkg-config=${PKG_CONFIG_SCRIPT}
    --extra-cflags='${EXTRA_CFLAGS}'
    --extra-cxxflags='${EXTRA_CXXFLAGS}'
    --extra-ldflags='${EXTRA_LDFLAGS}'
"

# 组装最终 configure 命令
# 优先级: 工具链参数 → ffmpeg.config → 命令行追加
FINAL_CONFIGURE="${FFMPEG_TOOLCHAIN_CONFIG} ${FFMPEG_USER_CONFIG} ${FFMPEG_EXTRA_CONFIGURE}"

log_info "执行 FFmpeg configure..."
log_debug "完整参数: ${FINAL_CONFIGURE}"

# 使用 eval 执行，因为参数中包含引号
eval ./configure ${FINAL_CONFIGURE}

# ===== 编译 =====
log_info "编译 FFmpeg..."
make -j$(sysctl -n hw.ncpu)

# ===== 安装 =====
log_info "安装 FFmpeg..."
make install

# ===== 验证静态库产物 =====
FFMPEG_LIB_DIR="${FFMPEG_INSTALL_DIR}/lib"
FFMPEG_STATIC_LIBS=()

for lib in libavcodec.a libavformat.a libavutil.a libswresample.a libswscale.a libavfilter.a libavdevice.a; do
    if [[ -f "${FFMPEG_LIB_DIR}/${lib}" ]]; then
        FFMPEG_STATIC_LIBS+=("${FFMPEG_LIB_DIR}/${lib}")
        log_info "  包含: ${lib}"
    else
        log_warn "  缺失: ${lib}（可能未启用对应模块）"
    fi
done

if [[ ${#FFMPEG_STATIC_LIBS[@]} -eq 0 ]]; then
    log_error "未找到任何 FFmpeg 静态库"
    exit 1
fi

# ===== 收集 whisper 静态库（静态链接到 libskyffmpeg.so）=====
WHISPER_STATIC_LIBS=()
if [[ -n "${WHISPER_STATIC_LIB_DIR}" ]] && [[ -d "${WHISPER_STATIC_LIB_DIR}" ]]; then
    for lib in libwhisper.a libggml.a libggml-base.a libggml-cpu.a libggml-vulkan.a; do
        if [[ -f "${WHISPER_STATIC_LIB_DIR}/${lib}" ]]; then
            WHISPER_STATIC_LIBS+=("${WHISPER_STATIC_LIB_DIR}/${lib}")
            log_info "  whisper 静态链接: ${lib}"
        fi
    done
fi

# ===== 合并为 libskyffmpeg.so =====
FINAL_OUTPUT_DIR="${OUTPUT_DIR}/${BUILD_TYPE}"
mkdir -p "${FINAL_OUTPUT_DIR}"

# FFmpeg 需要链接 skyssl 动态库以及 Android 系统库
# whisper 静态库直接合并到 libskyffmpeg.so（行业标准做法）
# -lmediandk: MediaCodec 硬件解码（AMediaFormat_delete 等符号）
# -lvulkan: whisper ggml-vulkan 后端需要
FFMPEG_EXTRA_LINK_FLAGS="-llog -landroid -lm -lz -lmediandk -lvulkan"
if [[ -n "${OPENSSL_LIB_DIR}" ]]; then
    FFMPEG_EXTRA_LINK_FLAGS="${FFMPEG_EXTRA_LINK_FLAGS} -L${OPENSSL_LIB_DIR} -lskyssl"
fi

# 如果有 vk_helper.o，追加到链接参数
if [[ -n "${WHISPER_VK_HELPER_OBJ}" ]] && [[ -f "${WHISPER_VK_HELPER_OBJ}" ]]; then
    FFMPEG_EXTRA_LINK_FLAGS="${WHISPER_VK_HELPER_OBJ} ${FFMPEG_EXTRA_LINK_FLAGS}"
    log_info "  静态链接: af_whisper_vk_helper.o"
fi

# FFmpeg 静态库 + whisper 静态库一起合并为 libskyffmpeg.so
ALL_STATIC_LIBS=("${FFMPEG_STATIC_LIBS[@]}" "${WHISPER_STATIC_LIBS[@]}")

merge_static_to_shared \
    "${FINAL_OUTPUT_DIR}/libskyffmpeg.so" \
    "cxx" \
    "${ALL_STATIC_LIBS[@]}" \
    -- \
    ${FFMPEG_EXTRA_LINK_FLAGS}

# ===== 校验产物 =====
verify_shared_library "${FINAL_OUTPUT_DIR}/libskyffmpeg.so" "avcodec_open2 avformat_open_input"

# ===== 复制头文件和 config.h =====
FFMPEG_HEADERS_DIR="${OUTPUT_DIR}/ffmpeg_headers"
mkdir -p "${FFMPEG_HEADERS_DIR}"

# 复制安装的头文件
if [[ -d "${FFMPEG_INSTALL_DIR}/include" ]]; then
    rsync -a "${FFMPEG_INSTALL_DIR}/include/" "${FFMPEG_HEADERS_DIR}/"
    log_info "FFmpeg 头文件已复制到: ${FFMPEG_HEADERS_DIR}"
fi

# 复制 config.h
if [[ -f "${FFMPEG_SRC}/config.h" ]]; then
    cp "${FFMPEG_SRC}/config.h" "${FFMPEG_HEADERS_DIR}/config.h"
    log_info "config.h 已复制"
fi

# ===== 复制 FFmpeg 内部头文件（cmdutils 等需要）=====
# 这些头文件不在 install/include 中，但被 SkyPlayer 的 ffplay 代码引用
FFMPEG_INTERNAL_HEADERS=(
    "libavutil/getenv_utf8.h"
    "libavutil/libm.h"
)

for header in "${FFMPEG_INTERNAL_HEADERS[@]}"; do
    if [[ -f "${FFMPEG_SRC}/${header}" ]]; then
        mkdir -p "${FFMPEG_HEADERS_DIR}/$(dirname ${header})"
        cp "${FFMPEG_SRC}/${header}" "${FFMPEG_HEADERS_DIR}/${header}"
        log_info "  内部头文件: ${header}"
    fi
done

timer_end "FFmpeg (${BUILD_TYPE}) 编译"
log_info "FFmpeg 产物: ${FINAL_OUTPUT_DIR}/libskyffmpeg.so"
log_info "FFmpeg 头文件: ${FFMPEG_HEADERS_DIR}"
