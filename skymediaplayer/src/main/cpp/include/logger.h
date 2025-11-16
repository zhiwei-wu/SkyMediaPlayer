#ifndef MY_PLAYER_LOGGER_H
#define MY_PLAYER_LOGGER_H

#ifdef __cplusplus
extern "C" {
#endif

#include "../../../../../../../Library/Android/sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/include/android/log.h"

#define ALOG_I(TAG, ...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define ALOG_D(TAG, ...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define ALOG_W(TAG, ...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define ALOG_E(TAG, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define VLOG_I(TAG, ...) __android_log_vprint(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define VLOG_D(TAG, ...) __android_log_vprint(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define VLOG_W(TAG, ...) __android_log_vprint(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define VLOG_E(TAG, ...) __android_log_vprint(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define FUNC_TRACE() ALOG_I("sky_trace", "Func:%s, line:%d", __func__, __LINE__);

#ifdef __cplusplus
};
#endif

#endif //MY_PLAYER_LOGGER_H
