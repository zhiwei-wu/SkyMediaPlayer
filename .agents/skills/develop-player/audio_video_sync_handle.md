# SkyPlayer 音画同步逻辑

## 概述

SkyPlayer 采用**主从时钟架构**实现音画同步，默认以音频时钟为主，视频同步到音频。

## 主时钟选择策略

**文件位置**: `skymediaplayer/src/main/cpp/ffplay/ffplay.c`（第 1252-1268 行）

### 三种时钟类型

```c
enum {
    AV_SYNC_AUDIO_MASTER,    // 音频时钟为主（默认）
    AV_SYNC_VIDEO_MASTER,    // 视频时钟为主
    AV_SYNC_EXTERNAL_CLOCK,  // 外部时钟为主
};
```

### 时钟选择逻辑

```c
static int get_master_sync_type(VideoState *is) {
    if (is->av_sync_type == AV_SYNC_VIDEO_MASTER) {
        if (is->video_st)
            return AV_SYNC_VIDEO_MASTER;
        else
            return AV_SYNC_AUDIO_MASTER;  // 降级到音频
    } else if (is->av_sync_type == AV_SYNC_AUDIO_MASTER) {
        if (is->audio_st)
            return AV_SYNC_AUDIO_MASTER;
        else
            return AV_SYNC_EXTERNAL_CLOCK;  // 降级到外部时钟
    } else {
        return AV_SYNC_EXTERNAL_CLOCK;
    }
}
```

**设计思想**：
- 音频时钟为主是最优选择，因为人耳对音频不连续比视觉更敏感
- 音频采样率固定，时钟更稳定
- 视频帧率可变，容易调整

### 获取主时钟

```c
static double get_master_clock(VideoState *is) {
    double val;

    switch (get_master_sync_type(is)) {
        case AV_SYNC_VIDEO_MASTER:
            val = get_clock(&is->vidclk);
            break;
        case AV_SYNC_AUDIO_MASTER:
            val = get_clock(&is->audclk);
            break;
        default:
            val = get_clock(&is->extclk);
            break;
    }
    return val;
}
```

## 时钟结构体

**文件位置**: `ffplay.h`（约第 60-70 行）

```c
typedef struct Clock {
    double pts;           // 当前时钟时间
    double pts_drift;     // 时钟漂移（pts - 系统时间）
    double last_updated;  // 最后更新时间
    double speed;         // 时钟速度（用于变速播放）
    int serial;           // 序列号（用于 Seek 同步）
    int paused;           // 暂停标志
    int *queue_serial;    // 队列序列号指针
} Clock;
```

### 时钟计算公式

```c
static double get_clock(Clock *c) {
    if (*c->queue_serial != c->serial)
        return NAN;
    if (c->paused) {
        return c->pts;
    } else {
        double time = av_gettime_relative() / 1000000.0;
        // 核心公式：pts_drift + 当前时间 - (当前时间 - 最后更新时间) * (1 - 速度)
        return c->pts_drift + time - (time - c->last_updated) * (1.0 - c->speed);
    }
}
```

## 音视频同步算法

**文件位置**: `ffplay.c`（第 1388-1414 行）

### compute_target_delay 函数

```c
static double compute_target_delay(double delay, VideoState *is)
{
    double sync_threshold, diff = 0;

    /* 视频作为从时钟时，需要修正延迟 */
    if (get_master_sync_type(is) != AV_SYNC_VIDEO_MASTER) {
        /* 计算视频时钟与主时钟的差值 */
        diff = get_clock(&is->vidclk) - get_master_clock(is);

        /* 计算同步阈值：基于帧时长动态调整 */
        sync_threshold = FFMAX(AV_SYNC_THRESHOLD_MIN, 
                              FFMIN(AV_SYNC_THRESHOLD_MAX, delay));
        
        if (!isnan(diff) && fabs(diff) < is->max_frame_duration) {
            if (diff <= -sync_threshold) {
                /* 视频落后：减少延迟（加速显示） */
                delay = FFMAX(0, delay + diff);
            }
            else if (diff >= sync_threshold && delay > AV_SYNC_FRAMEDUP_THRESHOLD) {
                /* 视频超前且帧时长足够：增加延迟（重复帧） */
                delay = delay + diff;
            }
            else if (diff >= sync_threshold) {
                /* 视频超前但帧时长短：加倍延迟 */
                delay = 2 * delay;
            }
        }
    }

    return delay;
}
```

### 关键参数

**文件位置**: `ffplay.h`（第 39-56 行）

```c
#define AV_SYNC_THRESHOLD_MIN 0.04          // 最小同步阈值（40ms）
#define AV_SYNC_THRESHOLD_MAX 0.1           // 最大同步阈值（100ms）
#define AV_SYNC_FRAMEDUP_THRESHOLD 0.1      // 帧重复阈值（100ms）
#define AV_NOSYNC_THRESHOLD 10.0            // 失去同步阈值（10秒）
#define SAMPLE_CORRECTION_PERCENT_MAX 10    // 音频采样补偿最大 ±10%
#define AUDIO_DIFF_AVG_NB 20                // 音频差值平均样本数
```

### 算法逻辑

1. 计算视频时钟与主时钟的差值 `diff`
2. 根据当前帧时长动态计算同步阈值
3. 根据差值调整显示延迟：
   - **视频落后**（diff < -threshold）：减少延迟，快速显示
   - **视频超前**（diff > threshold）：增加延迟，重复显示
   - **帧时长短**时加倍延迟以避免频繁调整

## 视频帧显示时机控制

**文件位置**: `ffplay.c`（第 1439-1545 行）

### video_refresh 函数

```c
static void video_refresh(void *opaque, double *remaining_time)
{
    VideoState *is = opaque;
    
    if (is->video_st) {
        retry:
        if (frame_queue_nb_remaining(&is->pictq) == 0) {
            // 没有可显示的帧
        } else {
            Frame *vp, *lastvp;
            lastvp = frame_queue_peek_last(&is->pictq);
            vp = frame_queue_peek(&is->pictq);
            
            // 1. 序列号不匹配，跳过（Seek 后的旧帧）
            if (vp->serial != is->videoq.serial) {
                frame_queue_next(&is->pictq);
                goto retry;
            }
            
            // 2. 计算显示延迟
            last_duration = vp_duration(is, lastvp, vp);
            delay = compute_target_delay(last_duration, is);
            
            time = av_gettime_relative() / 1000000.0;
            
            // 3. 时间未到，等待
            if (time < is->frame_timer + delay) {
                *remaining_time = FFMIN(is->frame_timer + delay - time, *remaining_time);
                goto display;
            }
            
            // 4. 更新帧计时器
            is->frame_timer += delay;
            if (delay > 0 && time - is->frame_timer > AV_SYNC_THRESHOLD_MAX)
                is->frame_timer = time;  // 防止计时器漂移过大
            
            // 5. 更新视频时钟
            SDL_LockMutex(is->pictq.mutex);
            if (!isnan(vp->pts))
                update_video_pts(is, vp->pts, vp->serial);
            SDL_UnlockMutex(is->pictq.mutex);
            
            // 6. 检查是否需要丢弃帧
            if (frame_queue_nb_remaining(&is->pictq) > 1) {
                Frame *nextvp = frame_queue_peek_next(&is->pictq);
                duration = vp_duration(is, vp, nextvp);
                if (!is->step && (framedrop > 0 || 
                    (framedrop && get_master_sync_type(is) != AV_SYNC_VIDEO_MASTER)) && 
                    time > is->frame_timer + duration) {
                    is->frame_drops_late++;
                    frame_queue_next(&is->pictq);
                    goto retry;
                }
            }
            
            // 7. 显示帧
            frame_queue_next(&is->pictq);
            is->force_refresh = 1;
        }
    display:
        if (!display_disable && is->force_refresh && 
            is->show_mode == SHOW_MODE_VIDEO && is->pictq.rindex_shown)
            video_display(is);
    }
}
```

## 音频采样数补偿机制

**文件位置**: `ffplay.c`（第 2845-2880 行）

### synchronize_audio 函数

```c
static int synchronize_audio(VideoState *is, int nb_samples)
{
    int wanted_nb_samples = nb_samples;

    /* 音频作为从时钟时，调整采样数 */
    if (get_master_sync_type(is) != AV_SYNC_AUDIO_MASTER) {
        double diff, avg_diff;
        int min_nb_samples, max_nb_samples;

        /* 计算音频时钟与主时钟的差值 */
        diff = get_clock(&is->audclk) - get_master_clock(is);

        if (!isnan(diff) && fabs(diff) < AV_NOSYNC_THRESHOLD) {
            /* 使用指数移动平均平滑差值 */
            is->audio_diff_cum = diff + is->audio_diff_avg_coef * is->audio_diff_cum;
            
            if (is->audio_diff_avg_count < AUDIO_DIFF_AVG_NB) {
                /* 积累足够的样本 */
                is->audio_diff_avg_count++;
            } else {
                /* 计算平均差值 */
                avg_diff = is->audio_diff_cum * (1.0 - is->audio_diff_avg_coef);

                if (fabs(avg_diff) >= is->audio_diff_threshold) {
                    /* 调整采样数：diff * 采样率 */
                    wanted_nb_samples = nb_samples + (int)(diff * is->audio_src.freq);
                    
                    /* 限制调整范围在 ±10% */
                    min_nb_samples = nb_samples * (100 - SAMPLE_CORRECTION_PERCENT_MAX) / 100;
                    max_nb_samples = nb_samples * (100 + SAMPLE_CORRECTION_PERCENT_MAX) / 100;
                    wanted_nb_samples = av_clip(wanted_nb_samples, min_nb_samples, max_nb_samples);
                }
            }
        } else {
            /* 差值过大，重置统计 */
            is->audio_diff_avg_count = 0;
            is->audio_diff_cum = 0;
        }
    }

    return wanted_nb_samples;
}
```

**补偿策略**：
1. 计算音频时钟与主时钟的差值
2. 使用指数移动平均平滑噪声
3. 根据差值调整采样数：`wanted_nb_samples = nb_samples + diff * 采样率`
4. 限制调整范围在 ±10% 以避免音质失真

## 帧丢弃策略

SkyPlayer 实现了**两级帧丢弃机制**：

### 1. 早期丢弃（Early Drop）

**文件位置**: `ffplay.c`（第 1652-1666 行）

在解码后立即检查，丢弃已经延迟的帧：

```c
if (framedrop > 0 || (framedrop && get_master_sync_type(is) != AV_SYNC_VIDEO_MASTER)) {
    if (frame->pts != AV_NOPTS_VALUE) {
        double diff = dpts - get_master_clock(is);
        if (!isnan(diff) && fabs(diff) < AV_NOSYNC_THRESHOLD &&
            diff - is->frame_last_filter_delay < 0 &&
            is->viddec.pkt_serial == is->vidclk.serial &&
            is->videoq.nb_packets) {
            /* 解码后立即丢弃延迟帧 */
            is->frame_drops_early++;
            av_frame_unref(frame);
            got_picture = 0;
        }
    }
}
```

**触发条件**：
- 帧时间戳 < 主时钟（延迟）
- 同一序列（serial 匹配）
- 队列中有足够数据包

### 2. 延迟丢弃（Late Drop）

**文件位置**: `ffplay.c`（第 1496-1503 行）

在显示前检查，丢弃来不及显示的帧：

```c
if (frame_queue_nb_remaining(&is->pictq) > 1) {
    Frame *nextvp = frame_queue_peek_next(&is->pictq);
    duration = vp_duration(is, vp, nextvp);
    if (!is->step && (framedrop > 0 || 
        (framedrop && get_master_sync_type(is) != AV_SYNC_VIDEO_MASTER)) && 
        time > is->frame_timer + duration) {
        /* 显示前丢弃延迟帧 */
        is->frame_drops_late++;
        frame_queue_next(&is->pictq);
        goto retry;
    }
}
```

**触发条件**：
- 当前时间 > 帧应该显示的时间 + 帧时长
- 队列中有多余帧

**对比**：
- **早期丢弃**：节省解码资源，但可能影响帧率稳定性
- **延迟丢弃**：保证帧率稳定，但浪费解码资源

## 关键函数调用链

### 时钟管理

```
init_clock() → set_clock() → get_clock()
    ↓
set_clock_at() (设置时钟点)
    ↓
set_clock_speed() (调整时钟速度)
    ↓
sync_clock_to_slave() (从时钟同步到主时钟)
```

### 主同步流程

```
refresh_loop_wait_event() [主循环]
    ↓
video_refresh() [视频刷新]
    ↓
compute_target_delay() [计算目标延迟]
    ↓
get_master_clock() → get_master_sync_type() [获取主时钟]
    ↓
vp_duration() [计算帧时长]
    ↓
update_video_pts() → set_clock() [更新视频时钟]
```

### 音频同步流程

```
audio_decode_frame()
    ↓
synchronize_audio() [音频采样数补偿]
    ↓
get_master_clock() [获取主时钟]
    ↓
wanted_nb_samples = nb_samples + diff * freq [调整采样数]
```

### 帧丢弃流程

```
decoder_decode_frame() [解码线程]
    ↓
early_drop_check() [早期丢弃检查]
    ↓
queue_picture() [入队]
    ↓
video_refresh() [显示线程]
    ↓
late_drop_check() [延迟丢弃检查]
```

## 同步参数调优

### 直播流优化

```c
// 减小同步阈值，更快响应
#define AV_SYNC_THRESHOLD_MIN 0.02  // 20ms
#define AV_SYNC_THRESHOLD_MAX 0.05  // 50ms

// 增加丢帧激进度
framedrop = 2;  // 更激进的丢帧
```

### 点播优化

```c
// 使用默认阈值
#define AV_SYNC_THRESHOLD_MIN 0.04  // 40ms
#define AV_SYNC_THRESHOLD_MAX 0.1   // 100ms

// 保守丢帧
framedrop = 1;
```

### 变速播放

```c
// 调整时钟速度
set_clock_speed(&is->vidclk, speed);
set_clock_speed(&is->audclk, speed);
set_clock_speed(&is->extclk, speed);

// 音频重采样时调整采样率
swr_set_compensation(is->swr_ctx, ...);
```

## 扩展开发指南

### 添加新的同步模式

1. 在 `av_sync_type` 枚举中添加新类型
2. 在 `get_master_sync_type()` 中添加处理逻辑
3. 在 `get_master_clock()` 中添加时钟获取逻辑

### 优化同步算法

1. 调整 `AV_SYNC_THRESHOLD_*` 参数
2. 修改 `compute_target_delay()` 中的延迟计算逻辑
3. 调整 `synchronize_audio()` 中的采样数补偿策略

### 调试同步问题

1. 监控 `is->frame_drops_early` 和 `is->frame_drops_late`
2. 打印 `diff = get_clock(&is->vidclk) - get_master_clock(is)`
3. 检查 `is->audio_clock` 和 `is->video_clock` 的更新频率
