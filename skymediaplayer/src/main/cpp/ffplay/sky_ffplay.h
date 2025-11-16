//
// skymediaplayer.cpp 通过这个头文件访问ffplay.c，中间层
//

#ifndef MY_PLAYER_SKY_FFPLAY_H
#define MY_PLAYER_SKY_FFPLAY_H

#include "ffplay.h"

#ifdef __cplusplus
extern "C" {
#endif

void ffp_global_init();

#ifdef __cplusplus
};
#endif

#endif //MY_PLAYER_SKY_FFPLAY_H
