//
// skymediaplayer.cpp 提供给 ffplay.c 调用的接口，实现 c 代码调用 c++ 代码的封装
//

#ifndef MY_PLAYER_SKYMEDIAPLAYER_INTERFACE_H
#define MY_PLAYER_SKYMEDIAPLAYER_INTERFACE_H

#include <stdbool.h>
#include "ffplay.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 方法定义，c -> cpp 调用方向
 */
bool sky_display_image(void *player, AVFrame *frame);

bool sky_open_audio(void *player, SkyAudioSpec *desired, SkyAudioSpec *obtained);

void sky_pause_audio(void *player, bool pause);

void sky_flush_audio(void *player);

/**
 * 消息发送接口 - 从 ffplay.c 发送消息到 SkyPlayer
 * @param player SkyPlayer 实例指针
 * @param what 消息类型 (使用 SKY_MSG_* 常量)
 * @param arg1 参数1
 * @param arg2 参数2
 * @param obj 对象指针 (可选)
 * @return 成功返回 true，失败返回 false
 */
bool sky_post_message(void *player, int what, int arg1, int arg2, void *obj);

/**
 * 简化版消息发送接口 - 只发送消息类型
 * @param player SkyPlayer 实例指针
 * @param what 消息类型 (使用 SKY_MSG_* 常量)
 * @return 成功返回 true，失败返回 false
 */
bool sky_post_simple_message(void *player, int what);

/**
 * 发送带两个参数的消息
 * @param player SkyPlayer 实例指针
 * @param what 消息类型 (使用 SKY_MSG_* 常量)
 * @param arg1 参数1
 * @param arg2 参数2
 * @return 成功返回 true，失败返回 false
 */
bool sky_post_message_ii(void *player, int what, int arg1, int arg2);

/**
 * 发送 Whisper 字幕消息
 * @param player SkyPlayer 实例指针
 * @param text 字幕文本（UTF-8 编码）
 * @return 成功返回 true，失败返回 false
 */
bool sky_post_whisper_subtitle(void *player, const char *text);

/**
 * 发送带 PTS 时间戳的 Whisper 字幕消息（用于 PTS 同步方案）
 * @param player SkyPlayer 实例指针
 * @param text 字幕文本（UTF-8 编码）
 * @param start_time 字幕开始时间（秒）
 * @param end_time 字幕结束时间（秒）
 * @return 成功返回 true，失败返回 false
 */
bool sky_post_whisper_subtitle_with_pts(void *player, const char *text, double start_time, double end_time);

/**
 * 设置 Whisper 预缓冲模式
 * 预缓冲模式下：音频解码继续但不播放，视频暂停，音频帧只送入 Whisper
 * @param player SkyPlayer 实例指针
 * @param enabled 是否启用预缓冲模式
 * @return 成功返回 true，失败返回 false
 */
bool sky_set_whisper_prebuffer_mode(void *player, bool enabled);

/**
 * 发送 Whisper 预缓冲完成消息
 * 当第一条字幕生成后调用，通知 Java 层隐藏 loading UI
 * @param player SkyPlayer 实例指针
 * @param subtitle_count 已缓冲的字幕数量
 * @return 成功返回 true，失败返回 false
 */
bool sky_post_whisper_prebuffer_complete(void *player, int subtitle_count);

#ifdef __cplusplus
};
#endif

#endif //MY_PLAYER_SKYMEDIAPLAYER_INTERFACE_H