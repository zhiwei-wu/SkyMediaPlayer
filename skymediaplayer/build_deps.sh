#!/bin/bash
# ===================================================================
# SkyPlayer 依赖库一站式编译脚本
#
# 支持编译 FFmpeg、whisper.cpp、OpenSSL 的 release/debug 版本
# 编译产物自动安装到 jniLibs-release / jniLibs-debug 目录
#
# 用法:
#   ./build_deps.sh [选项]
#
# 选项:
#   --component=<ffmpeg|whisper|openssl|all>   编译组件（默认 all）
#   --build-type=<release|debug|all>           编译类型（默认 all）
#   --ffmpeg-extra="<额外 configure 参数>"     追加 FFmpeg configure 参数
#   --install                                  编译后安装到 jniLibs
#   --clean                                    清理编译产物
#   --verbose                                  显示详细日志
#   --dry-run                                  仅打印命令不执行
#
# 示例:
#   ./build_deps.sh --install                                    # 编译全部并安装
#   ./build_deps.sh --component=ffmpeg --build-type=debug        # 仅编译 FFmpeg debug
#   ./build_deps.sh --component=whisper --install                # 编译 whisper 并安装
#   ./build_deps.sh --ffmpeg-extra="--enable-decoder=av1"        # 追加 FFmpeg 参数
#   ./build_deps.sh --clean                                      # 清理编译产物
# ===================================================================

set -euo pipefail

# ===== 脚本路径 =====
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS_DIR="${SCRIPT_DIR}/scripts"

# ===== 加载公共函数 =====
source "${SCRIPTS_DIR}/common.sh"

# ===== 默认参数 =====
COMPONENT="all"
BUILD_TYPE="all"
FFMPEG_EXTRA=""
DO_INSTALL=false
DO_CLEAN=false
DRY_RUN=false
export VERBOSE=0

# ===== 解析命令行参数 =====
for arg in "$@"; do
    case "${arg}" in
        --component=*)
            COMPONENT="${arg#*=}"
            ;;
        --build-type=*)
            BUILD_TYPE="${arg#*=}"
            ;;
        --ffmpeg-extra=*)
            FFMPEG_EXTRA="${arg#*=}"
            ;;
        --install)
            DO_INSTALL=true
            ;;
        --clean)
            DO_CLEAN=true
            ;;
        --verbose)
            export VERBOSE=1
            ;;
        --dry-run)
            DRY_RUN=true
            ;;
        --help|-h)
            head -30 "$0" | tail -25
            exit 0
            ;;
        *)
            log_error "未知参数: ${arg}"
            log_error "使用 --help 查看帮助"
            exit 1
            ;;
    esac
done

# ===== 验证参数 =====
case "${COMPONENT}" in
    ffmpeg|whisper|openssl|all) ;;
    *) log_error "无效的组件: ${COMPONENT}，支持 ffmpeg|whisper|openssl|all"; exit 1 ;;
esac

case "${BUILD_TYPE}" in
    release|debug|all) ;;
    *) log_error "无效的编译类型: ${BUILD_TYPE}，支持 release|debug|all"; exit 1 ;;
esac

# ===== 加载配置 =====
load_deps_config "${SCRIPT_DIR}/deps.config"

# ===== 导出配置变量供子脚本使用 =====
export ANDROID_NDK_ROOT
export ANDROID_API
export ANDROID_ABI
export ANDROID_ARCH="${ANDROID_ARCH:-arm64}"
export ANDROID_CPU="${ANDROID_CPU:-armv8-a}"
export FFMPEG_SRC_DIR="${FFMPEG_SRC_DIR:-}"
export WHISPER_SRC_DIR="${WHISPER_SRC_DIR:-}"
export OPENSSL_SRC_DIR="${OPENSSL_SRC_DIR:-}"

# ===== 设置 NDK 工具链 =====
setup_ndk_toolchain

# ===== 导出 NDK 工具链变量供子脚本使用 =====
export CC CXX AR AS NM RANLIB STRIP OBJDUMP LD
export NDK_TOOLCHAIN SYSROOT

# ===== 编译产物输出目录 =====
OUTPUT_DIR="${SCRIPT_DIR}/build_output"

# ===== 清理 =====
if ${DO_CLEAN}; then
    log_step "清理编译产物"
    clean_directory "${OUTPUT_DIR}"
    log_info "清理完成"
    exit 0
fi

# ===== 确定要编译的类型列表 =====
BUILD_TYPES=()
if [[ "${BUILD_TYPE}" == "all" ]]; then
    BUILD_TYPES=("release" "debug")
else
    BUILD_TYPES=("${BUILD_TYPE}")
fi

# ===== 打印编译计划 =====
log_step "SkyPlayer 依赖库编译"
log_info "组件:     ${COMPONENT}"
log_info "编译类型: ${BUILD_TYPES[*]}"
log_info "安装:     ${DO_INSTALL}"
log_info "输出目录: ${OUTPUT_DIR}"
echo ""

if ${DRY_RUN}; then
    log_warn "Dry-run 模式，仅打印命令不执行"
    exit 0
fi

timer_start

# ===== 编译函数 =====
build_openssl() {
    local build_type="$1"
    log_step "编译 OpenSSL (${build_type})"
    bash "${SCRIPTS_DIR}/build_openssl.sh" "${build_type}" "${OPENSSL_SRC_DIR}" "${OUTPUT_DIR}"
}

build_whisper() {
    local build_type="$1"
    log_step "编译 whisper.cpp (${build_type})"
    bash "${SCRIPTS_DIR}/build_whisper.sh" "${build_type}" "${WHISPER_SRC_DIR}" "${OUTPUT_DIR}"
}

build_ffmpeg() {
    local build_type="$1"
    log_step "编译 FFmpeg (${build_type})"
    bash "${SCRIPTS_DIR}/build_ffmpeg.sh" "${build_type}" "${FFMPEG_SRC_DIR}" "${OUTPUT_DIR}" "${FFMPEG_EXTRA}"
}

# ===== 按组件和类型执行编译 =====
for bt in "${BUILD_TYPES[@]}"; do
    log_step "===== 开始 ${bt} 版本编译 ====="

    # 编译顺序：OpenSSL → whisper → FFmpeg（FFmpeg 依赖前两者）
    if [[ "${COMPONENT}" == "openssl" ]] || [[ "${COMPONENT}" == "all" ]]; then
        build_openssl "${bt}"
    fi

    if [[ "${COMPONENT}" == "whisper" ]] || [[ "${COMPONENT}" == "all" ]]; then
        build_whisper "${bt}"
    fi

    if [[ "${COMPONENT}" == "ffmpeg" ]] || [[ "${COMPONENT}" == "all" ]]; then
        build_ffmpeg "${bt}"
    fi

    log_info "${bt} 版本编译完成"
done

# ===== 安装到 jniLibs =====
if ${DO_INSTALL}; then
    log_step "安装编译产物到 jniLibs"

    # NDK 中的 libc++_shared.so 路径
    INSTALL_HOST_TAG=""
    case "$(uname -s)" in
        Darwin) INSTALL_HOST_TAG="darwin-x86_64" ;;
        Linux)  INSTALL_HOST_TAG="linux-x86_64" ;;
    esac
    LIBCXX_SHARED="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/${INSTALL_HOST_TAG}/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"

    for bt in "${BUILD_TYPES[@]}"; do
        JNILIBS_DIR="${SCRIPT_DIR}/src/main/jniLibs-${bt}/${ANDROID_ABI}"
        SOURCE_DIR="${OUTPUT_DIR}/${bt}"

        log_info "安装 ${bt} 产物到: ${JNILIBS_DIR}"
        mkdir -p "${JNILIBS_DIR}"

        # 复制编译产物
        if [[ -f "${SOURCE_DIR}/libskyffmpeg.so" ]]; then
            cp "${SOURCE_DIR}/libskyffmpeg.so" "${JNILIBS_DIR}/"
            log_info "  已安装: libskyffmpeg.so"
        fi

        if [[ -f "${SOURCE_DIR}/libskyssl.so" ]]; then
            cp "${SOURCE_DIR}/libskyssl.so" "${JNILIBS_DIR}/"
            log_info "  已安装: libskyssl.so"
        fi

        # whisper 已静态链接到 libskyffmpeg.so，不再需要独立的 libskywhisper.so

        # 复制 SDL3（从旧的 jniLibs 目录）
        OLD_JNILIBS="${SCRIPT_DIR}/src/main/jniLibs/${ANDROID_ABI}"
        if [[ -f "${OLD_JNILIBS}/libSDL3.so" ]] && [[ ! -f "${JNILIBS_DIR}/libSDL3.so" ]]; then
            cp "${OLD_JNILIBS}/libSDL3.so" "${JNILIBS_DIR}/"
            log_info "  已安装: libSDL3.so (从旧目录复制)"
        fi

        # 复制 libc++_shared.so
        if [[ -f "${LIBCXX_SHARED}" ]] && [[ ! -f "${JNILIBS_DIR}/libc++_shared.so" ]]; then
            cp "${LIBCXX_SHARED}" "${JNILIBS_DIR}/"
            log_info "  已安装: libc++_shared.so"
        fi

        # 列出安装结果
        log_info "  ${bt} 目录内容:"
        ls -lh "${JNILIBS_DIR}/" | grep -v "^total" | while read line; do
            log_info "    ${line}"
        done
    done

    # 同步 FFmpeg 头文件
    FFMPEG_HEADERS_SRC="${OUTPUT_DIR}/ffmpeg_headers"
    FFMPEG_HEADERS_DST="${SCRIPT_DIR}/src/main/cpp/ffmpeg/include"
    if [[ -d "${FFMPEG_HEADERS_SRC}" ]]; then
        log_info "同步 FFmpeg 头文件到: ${FFMPEG_HEADERS_DST}"

        # 需要保留的目录（不替换）
        PRESERVE_DIRS=("compat" "libpostproc")

        # 备份需要保留的目录
        for dir in "${PRESERVE_DIRS[@]}"; do
            if [[ -d "${FFMPEG_HEADERS_DST}/${dir}" ]]; then
                cp -r "${FFMPEG_HEADERS_DST}/${dir}" "/tmp/skyplayer_preserve_${dir}"
            fi
        done

        # 同步头文件
        rsync -a --delete \
            --exclude='.DS_Store' \
            "${FFMPEG_HEADERS_SRC}/" "${FFMPEG_HEADERS_DST}/"

        # 恢复保留的目录
        for dir in "${PRESERVE_DIRS[@]}"; do
            if [[ -d "/tmp/skyplayer_preserve_${dir}" ]]; then
                cp -r "/tmp/skyplayer_preserve_${dir}" "${FFMPEG_HEADERS_DST}/${dir}"
                rm -rf "/tmp/skyplayer_preserve_${dir}"
            fi
        done

        log_info "FFmpeg 头文件同步完成"
    fi

    log_info "安装完成"
fi

# ===== 编译总结 =====
timer_end "全部编译"

log_step "编译总结"
for bt in "${BUILD_TYPES[@]}"; do
    RESULT_DIR="${OUTPUT_DIR}/${bt}"
    if [[ -d "${RESULT_DIR}" ]]; then
        log_info "${bt} 产物:"
        ls -lh "${RESULT_DIR}/"*.so 2>/dev/null | while read line; do
            log_info "  ${line}"
        done
    fi
done

log_info "编译完成 ✅"
