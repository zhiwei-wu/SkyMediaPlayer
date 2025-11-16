//
// 将需要对 ffplay.c 进行 增加/修改 的代码尽量放这里
//

#include <stdbool.h>
#include <stdarg.h>
#include <android/log.h>

#include "ffplay.h"
#include "sky_ffplay.h"
#include "logger.h"

#include "libavutil/log.h"

static bool g_ffmpeg_global_init = false;

// FFmpeg 日志回调函数 - 将 av_log 输出重定向到 Android logcat
static void android_av_log_callback(void* ptr, int level, const char* fmt, va_list vl) {
    // 将 FFmpeg 日志级别映射到 Android 日志级别
    int android_log_level;
    switch (level) {
        case AV_LOG_PANIC:
        case AV_LOG_FATAL:
        case AV_LOG_ERROR:
            android_log_level = ANDROID_LOG_ERROR;
            break;
        case AV_LOG_WARNING:
            android_log_level = ANDROID_LOG_WARN;
            break;
        case AV_LOG_INFO:
            android_log_level = ANDROID_LOG_INFO;
            break;
        case AV_LOG_VERBOSE:
        case AV_LOG_DEBUG:
        case AV_LOG_TRACE:
            android_log_level = ANDROID_LOG_DEBUG;
            break;
        default:
            android_log_level = ANDROID_LOG_INFO;
            break;
    }

    // 格式化日志消息并输出到 Android logcat
    __android_log_vprint(android_log_level, "FFmpeg", fmt, vl);
}

void ffp_global_init() {
    if (g_ffmpeg_global_init) {
        return;
    }
    // 配置 FFmpeg 日志系统
//    av_log_set_level(AV_LOG_QUIET);  // 完全禁用 FFmpeg 日志输出
//    av_log_set_callback(android_av_log_callback);  // 设置自定义日志回调
//
    g_ffmpeg_global_init = true;
}