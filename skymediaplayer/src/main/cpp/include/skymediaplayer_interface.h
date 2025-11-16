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

#ifdef __cplusplus
};
#endif

#endif //MY_PLAYER_SKYMEDIAPLAYER_INTERFACE_H