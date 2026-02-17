#!/bin/bash
# ===================================================================
# SkyPlayer 依赖库编译 - OpenSSL
# 编译 OpenSSL 并合并为 libskyssl.so
# ===================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

# ===== 参数 =====
BUILD_TYPE="${1:-release}"
OPENSSL_SRC="${2:-}"
OUTPUT_DIR="${3:-}"

if [[ -z "${OPENSSL_SRC}" ]] || [[ -z "${OUTPUT_DIR}" ]]; then
    log_error "用法: build_openssl.sh <release|debug> <openssl_src_dir> <output_dir>"
    exit 1
fi

if [[ ! -d "${OPENSSL_SRC}" ]]; then
    log_error "OpenSSL 源码目录不存在: ${OPENSSL_SRC}"
    exit 1
fi

log_step "编译 OpenSSL (${BUILD_TYPE})"
timer_start

# ===== 设置编译参数 =====
setup_build_flags "${BUILD_TYPE}"

# ===== 编译目录 =====
OPENSSL_BUILD_DIR="${OUTPUT_DIR}/openssl_build/${BUILD_TYPE}"
OPENSSL_INSTALL_DIR="${OUTPUT_DIR}/openssl_install/${BUILD_TYPE}"
clean_directory "${OPENSSL_BUILD_DIR}"
clean_directory "${OPENSSL_INSTALL_DIR}"

# ===== 进入 OpenSSL 源码目录编译 =====
# OpenSSL 使用自己的构建系统，需要在源码目录中执行
# 为避免污染源码目录，先复制到编译目录
log_info "复制 OpenSSL 源码到编译目录..."
rsync -a --exclude='.git' "${OPENSSL_SRC}/" "${OPENSSL_BUILD_DIR}/"

cd "${OPENSSL_BUILD_DIR}"

# OpenSSL 的 Android 交叉编译配置
export ANDROID_NDK_HOME="${ANDROID_NDK_ROOT}"
export PATH="${NDK_TOOLCHAIN}/bin:${PATH}"

# OpenSSL configure 参数
local_cflags="${BUILD_CFLAGS}"
if [[ "${BUILD_TYPE}" == "debug" ]]; then
    OPENSSL_BUILD_FLAG="--debug"
else
    OPENSSL_BUILD_FLAG=""
fi

log_info "配置 OpenSSL..."
./Configure android-arm64 \
    ${OPENSSL_BUILD_FLAG} \
    -D__ANDROID_API__=${ANDROID_API} \
    --prefix="${OPENSSL_INSTALL_DIR}" \
    --openssldir="${OPENSSL_INSTALL_DIR}" \
    no-shared \
    no-tests \
    no-ui-console \
    no-comp \
    no-engine \
    no-dso \
    no-async \
    ${local_cflags:+-DCFLAGS="${local_cflags}"}

log_info "编译 OpenSSL..."
make -j$(sysctl -n hw.ncpu) build_libs

log_info "安装 OpenSSL..."
make install_dev

# ===== 验证静态库产物 =====
if [[ ! -f "${OPENSSL_INSTALL_DIR}/lib/libssl.a" ]] || [[ ! -f "${OPENSSL_INSTALL_DIR}/lib/libcrypto.a" ]]; then
    # 某些版本的 OpenSSL 安装到 lib64
    if [[ -f "${OPENSSL_INSTALL_DIR}/lib64/libssl.a" ]]; then
        OPENSSL_LIB_DIR="${OPENSSL_INSTALL_DIR}/lib64"
    else
        log_error "OpenSSL 静态库未生成"
        ls -la "${OPENSSL_INSTALL_DIR}/lib/" 2>/dev/null || true
        ls -la "${OPENSSL_INSTALL_DIR}/lib64/" 2>/dev/null || true
        exit 1
    fi
else
    OPENSSL_LIB_DIR="${OPENSSL_INSTALL_DIR}/lib"
fi

log_info "OpenSSL 静态库: ${OPENSSL_LIB_DIR}"

# ===== 合并为 libskyssl.so =====
FINAL_OUTPUT_DIR="${OUTPUT_DIR}/${BUILD_TYPE}"
mkdir -p "${FINAL_OUTPUT_DIR}"

merge_static_to_shared \
    "${FINAL_OUTPUT_DIR}/libskyssl.so" \
    "cc" \
    "${OPENSSL_LIB_DIR}/libssl.a" \
    "${OPENSSL_LIB_DIR}/libcrypto.a" \
    -- \
    -llog -landroid

# ===== 校验产物 =====
verify_shared_library "${FINAL_OUTPUT_DIR}/libskyssl.so" "OPENSSL_init_ssl SSL_new"

# ===== 保存头文件路径供 FFmpeg 使用 =====
echo "${OPENSSL_INSTALL_DIR}/include" > "${OUTPUT_DIR}/openssl_include_${BUILD_TYPE}.path"
echo "${FINAL_OUTPUT_DIR}" > "${OUTPUT_DIR}/openssl_lib_${BUILD_TYPE}.path"

timer_end "OpenSSL (${BUILD_TYPE}) 编译"
log_info "OpenSSL 产物: ${FINAL_OUTPUT_DIR}/libskyssl.so"
log_info "OpenSSL 头文件: ${OPENSSL_INSTALL_DIR}/include"
