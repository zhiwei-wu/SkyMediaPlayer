#!/bin/bash
# ===================================================================
# SkyPlayer 依赖库编译 - 公共函数
# 提供 NDK 工具链设置、日志、编译参数模板等公共能力
# ===================================================================

set -euo pipefail

# ===== 颜色定义 =====
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# ===== 日志函数 =====
log_info() {
    echo -e "${GREEN}[INFO]${NC} $*"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $*"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $*"
}

log_step() {
    echo -e "${CYAN}[STEP]${NC} ========== $* =========="
}

log_debug() {
    if [[ "${VERBOSE:-0}" == "1" ]]; then
        echo -e "${BLUE}[DEBUG]${NC} $*"
    fi
}

# ===== 错误处理 =====
on_error() {
    local exit_code=$?
    local line_number=$1
    log_error "脚本在第 ${line_number} 行出错，退出码: ${exit_code}"
    exit ${exit_code}
}

trap 'on_error ${LINENO}' ERR

# ===== NDK 工具链设置 =====
# 调用前需确保 ANDROID_NDK_ROOT, ANDROID_API, ANDROID_ABI 已设置
setup_ndk_toolchain() {
    if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
        log_error "ANDROID_NDK_ROOT 未设置"
        exit 1
    fi

    if [[ ! -d "${ANDROID_NDK_ROOT}" ]]; then
        log_error "NDK 目录不存在: ${ANDROID_NDK_ROOT}"
        exit 1
    fi

    local host_tag
    case "$(uname -s)" in
        Darwin) host_tag="darwin-x86_64" ;;
        Linux)  host_tag="linux-x86_64" ;;
        *)      log_error "不支持的操作系统: $(uname -s)"; exit 1 ;;
    esac

    # NDK 工具链路径
    NDK_TOOLCHAIN="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/${host_tag}"
    SYSROOT="${NDK_TOOLCHAIN}/sysroot"

    # 根据 ABI 设置目标三元组
    local target_triple
    case "${ANDROID_ABI}" in
        arm64-v8a)  target_triple="aarch64-linux-android" ;;
        armeabi-v7a) target_triple="armv7a-linux-androideabi" ;;
        x86_64)     target_triple="x86_64-linux-android" ;;
        x86)        target_triple="i686-linux-android" ;;
        *)          log_error "不支持的 ABI: ${ANDROID_ABI}"; exit 1 ;;
    esac

    # 编译器和工具
    export CC="${NDK_TOOLCHAIN}/bin/${target_triple}${ANDROID_API}-clang"
    export CXX="${NDK_TOOLCHAIN}/bin/${target_triple}${ANDROID_API}-clang++"
    export AR="${NDK_TOOLCHAIN}/bin/llvm-ar"
    export AS="${NDK_TOOLCHAIN}/bin/llvm-as"
    export NM="${NDK_TOOLCHAIN}/bin/llvm-nm"
    export RANLIB="${NDK_TOOLCHAIN}/bin/llvm-ranlib"
    export STRIP="${NDK_TOOLCHAIN}/bin/llvm-strip"
    export OBJDUMP="${NDK_TOOLCHAIN}/bin/llvm-objdump"
    export LD="${NDK_TOOLCHAIN}/bin/ld.lld"

    # 验证编译器存在
    if [[ ! -f "${CC}" ]]; then
        log_error "编译器不存在: ${CC}"
        exit 1
    fi

    log_info "NDK 工具链已配置:"
    log_info "  NDK: ${ANDROID_NDK_ROOT}"
    log_info "  CC:  ${CC}"
    log_info "  ABI: ${ANDROID_ABI}, API: ${ANDROID_API}"
}

# ===== 编译参数模板 =====
# 根据 build_type 设置 CFLAGS / LDFLAGS
setup_build_flags() {
    local build_type="${1:-release}"

    # 公共参数
    COMMON_CFLAGS="-fPIC"
    COMMON_LDFLAGS=""

    case "${build_type}" in
        release)
            BUILD_CFLAGS="${COMMON_CFLAGS} -O2 -DNDEBUG"
            BUILD_CXXFLAGS="${COMMON_CFLAGS} -O2 -DNDEBUG -std=c++17"
            BUILD_LDFLAGS="${COMMON_LDFLAGS}"
            DO_STRIP=true
            ;;
        debug)
            BUILD_CFLAGS="${COMMON_CFLAGS} -O2 -g -DDEBUG -fno-omit-frame-pointer"
            BUILD_CXXFLAGS="${COMMON_CFLAGS} -O2 -g -DDEBUG -fno-omit-frame-pointer -std=c++17"
            BUILD_LDFLAGS="${COMMON_LDFLAGS}"
            DO_STRIP=false
            ;;
        *)
            log_error "未知的编译类型: ${build_type}，支持 release 或 debug"
            exit 1
            ;;
    esac

    export BUILD_CFLAGS BUILD_CXXFLAGS BUILD_LDFLAGS DO_STRIP
    log_info "编译参数已配置 (${build_type}):"
    log_debug "  CFLAGS:   ${BUILD_CFLAGS}"
    log_debug "  CXXFLAGS: ${BUILD_CXXFLAGS}"
    log_debug "  STRIP:    ${DO_STRIP}"
}

# ===== 合并静态库为动态库 =====
# 将多个 .a 文件合并为一个 .so 文件
# 用法: merge_static_to_shared <output.so> <linker: cc|cxx> <static_libs...> [-- <extra_ldflags...>]
merge_static_to_shared() {
    local output_so="$1"
    local linker_type="$2"
    shift 2

    local static_libs=()
    local extra_ldflags=()
    local parsing_libs=true

    for arg in "$@"; do
        if [[ "$arg" == "--" ]]; then
            parsing_libs=false
            continue
        fi
        if $parsing_libs; then
            static_libs+=("$arg")
        else
            extra_ldflags+=("$arg")
        fi
    done

    # 选择链接器
    local linker
    case "${linker_type}" in
        cc)  linker="${CC}" ;;
        cxx) linker="${CXX}" ;;
        *)   log_error "未知的链接器类型: ${linker_type}"; exit 1 ;;
    esac

    # 验证静态库存在
    for lib in "${static_libs[@]}"; do
        if [[ ! -f "$lib" ]]; then
            log_error "静态库不存在: $lib"
            exit 1
        fi
    done

    log_info "合并静态库为动态库: $(basename ${output_so})"
    log_debug "  静态库: ${static_libs[*]}"
    log_debug "  额外链接参数: ${extra_ldflags[*]:-无}"

    local output_dir
    output_dir=$(dirname "${output_so}")
    mkdir -p "${output_dir}"

    ${linker} -shared -o "${output_so}" \
        -Wl,--whole-archive "${static_libs[@]}" -Wl,--no-whole-archive \
        ${BUILD_LDFLAGS} \
        "${extra_ldflags[@]}" \
        --sysroot="${SYSROOT}"

    # Strip
    if ${DO_STRIP}; then
        log_info "Strip: $(basename ${output_so})"
        ${STRIP} --strip-debug "${output_so}"
    fi

    # 输出文件大小
    local file_size
    file_size=$(du -h "${output_so}" | cut -f1)
    log_info "产物: ${output_so} (${file_size})"
}

# ===== 产物校验 =====
# 检查 so 文件是否有效
verify_shared_library() {
    local so_file="$1"
    local expected_symbols="${2:-}"

    if [[ ! -f "${so_file}" ]]; then
        log_error "so 文件不存在: ${so_file}"
        return 1
    fi

    # 检查文件类型
    local file_type
    file_type=$(file "${so_file}")
    if [[ ! "${file_type}" == *"ELF"* ]] || [[ ! "${file_type}" == *"shared object"* ]]; then
        log_error "文件不是有效的 ELF 共享库: ${so_file}"
        log_error "文件类型: ${file_type}"
        return 1
    fi

    # 检查目标架构
    if [[ "${ANDROID_ABI}" == "arm64-v8a" ]] && [[ ! "${file_type}" == *"aarch64"* ]]; then
        log_error "so 文件架构不匹配，期望 aarch64: ${so_file}"
        return 1
    fi

    # 检查期望的符号
    if [[ -n "${expected_symbols}" ]]; then
        local missing=false
        for symbol in ${expected_symbols}; do
            if ! ${NM} -D "${so_file}" 2>/dev/null | grep -q "${symbol}"; then
                log_warn "缺少期望的符号: ${symbol}"
                missing=true
            fi
        done
        if ${missing}; then
            log_warn "部分期望符号缺失，请检查"
        fi
    fi

    log_info "校验通过: $(basename ${so_file})"
    return 0
}

# ===== 目录清理 =====
clean_directory() {
    local dir="$1"
    if [[ -d "${dir}" ]]; then
        log_info "清理目录: ${dir}"
        rm -rf "${dir}"
    fi
    mkdir -p "${dir}"
}

# ===== 耗时统计 =====
timer_start() {
    TIMER_START=$(date +%s)
}

timer_end() {
    local label="${1:-操作}"
    local end_time
    end_time=$(date +%s)
    local elapsed=$((end_time - TIMER_START))
    local minutes=$((elapsed / 60))
    local seconds=$((elapsed % 60))
    log_info "${label} 耗时: ${minutes}分${seconds}秒"
}

# ===== 加载配置文件 =====
load_deps_config() {
    local config_file="${1:-}"
    if [[ -z "${config_file}" ]]; then
        # 默认查找 skymediaplayer/deps.config
        local script_dir
        script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
        config_file="${script_dir}/../deps.config"
    fi

    if [[ ! -f "${config_file}" ]]; then
        log_error "配置文件不存在: ${config_file}"
        log_error "请复制 deps.config.template 为 deps.config 并修改为本地实际路径"
        exit 1
    fi

    log_info "加载配置: ${config_file}"
    source "${config_file}"

    # 校验必要配置
    local required_vars=("ANDROID_NDK_ROOT" "ANDROID_API" "ANDROID_ABI")
    for var in "${required_vars[@]}"; do
        if [[ -z "${!var:-}" ]]; then
            log_error "配置缺失: ${var}"
            exit 1
        fi
    done
}

# ===== 加载 FFmpeg configure 参数 =====
load_ffmpeg_config() {
    local config_file="${1:-}"
    if [[ -z "${config_file}" ]]; then
        local script_dir
        script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
        config_file="${script_dir}/ffmpeg.config"
    fi

    if [[ ! -f "${config_file}" ]]; then
        log_error "FFmpeg 配置文件不存在: ${config_file}"
        exit 1
    fi

    # 读取非空、非注释行，拼接为一行
    grep -v '^\s*#' "${config_file}" | grep -v '^\s*$' | tr '\n' ' '
}

log_debug "common.sh 已加载"
