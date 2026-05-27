# SkyPlayer 音频解码播放流程

## 概述

SkyPlayer 的音频解码播放采用**独立线程 + OpenSL ES 低延迟输出**架构，实现高质量音频播放。

## 核心调用链

```
stream_component_open() 
  → audio_open()
    → sky_open_audio()
      → SkySLESAudioOut::openAudio()
  → decoder_start(&is->auddec, audio_thread, ...)
    → audio_thread() [独立线程]
      → decoder_decode_frame()
      → frame_queue_put(&is->sampq)
      
audioOutputThread() [输出线程]
  → spec_.callback() 
    → sdl_audio_callback()
      → audio_decode_frame()
        → swr_convert() [重采样]
  → Enqueue() [入队到 OpenSL ES]
```

## 音频解码线程

**文件位置**: `skymediaplayer/src/main/cpp/ffplay/ffplay.c`（第 2433-2550 行）

### audio_thread 工作流程

```c
static int audio_thread(void *arg) {
    VideoState *is = arg;
    AVFrame *frame = av_frame_alloc();
    
    do {
        // 1. 检测 Whisper 滤镜
        if (contains_whisper_filter(current_afilters)) {
            use_async_whisper = 1;
            if (!is->whisper_tid) {
                start_whisper_thread(is);
            }
        }
        
        // 2. 解码音频帧
        got_frame = decoder_decode_frame(&is->auddec, frame, NULL);
        if (got_frame < 0) goto the_end;
        
        if (got_frame) {
            // 3. 如果使用异步 Whisper，将帧送入队列
            if (use_async_whisper && is->whisper_tid) {
                feed_whisper_frame(is, frame);
                
                // 预缓冲模式下只送入 Whisper，不播放
                if (is->whisper_prebuffer_mode) {
                    av_frame_unref(frame);
                    continue;
                }
            }
            
            // 4. 检测帧参数变化，重新配置滤镜图
            if (cmp_audio_fmts(is->audio_filter_src.fmt, ...) ||
                av_channel_layout_compare(...) ||
                is->audio_filter_src.freq != frame->sample_rate) {
                configure_audio_filters(is, afilters, 1);
            }
            
            // 5. 将帧送入滤镜图
            av_buffersrc_add_frame(is->in_audio_filter, frame);
            
            // 6. 从滤镜图获取处理后的帧
            while ((ret = av_buffersink_get_frame_flags(is->out_audio_filter, 
                                                        frame, 0)) >= 0) {
                // 7. 放入采样队列
                af = frame_queue_peek_writable(&is->sampq);
                af->pts = (frame->pts == AV_NOPTS_VALUE) ? NAN : 
                          frame->pts * av_q2d(tb);
                af->pos = frame->pkt_pos;
                af->serial = is->auddec.pkt_serial;
                af->duration = av_q2d((AVRational){frame->nb_samples, 
                                                   frame->sample_rate});
                av_frame_move_ref(af->frame, frame);
                frame_queue_push(&is->sampq);
            }
        }
    } while (ret >= 0 || ret == AVERROR(EAGAIN) || ret == AVERROR_EOF);
}
```

## 音频重采样处理

**文件位置**: `ffplay.c`（第 2888-2998 行）

### audio_decode_frame 函数

```c
static int audio_decode_frame(VideoState *is) {
    int data_size, resampled_data_size;
    av_unused double audio_clock0;
    int wanted_nb_samples;
    Frame *af;

    do {
        // 1. 从采样队列获取帧
        af = frame_queue_peek_readable(&is->sampq);
        if (!af) return -1;
        frame_queue_next(&is->sampq);
    } while (af->serial != is->audioq.serial);

    // 2. 计算数据大小
    data_size = av_samples_get_buffer_size(NULL, af->frame->ch_layout.nb_channels,
                                           af->frame->nb_samples,
                                           af->frame->format, 1);

    // 3. 音视频同步：调整采样数
    wanted_nb_samples = synchronize_audio(is, af->frame->nb_samples);

    // 4. 检查是否需要重采样
    if (af->frame->format        != is->audio_src.fmt            ||
        av_channel_layout_compare(&af->frame->ch_layout, &is->audio_src.ch_layout) ||
        af->frame->sample_rate   != is->audio_src.freq           ||
        (wanted_nb_samples       != af->frame->nb_samples && !is->swr_ctx)) {
        
        // 5. 配置重采样器
        swr_alloc_set_opts2(&is->swr_ctx,
                           &is->audio_tgt.ch_layout, is->audio_tgt.fmt, is->audio_tgt.freq,
                           &af->frame->ch_layout, af->frame->format, af->frame->sample_rate,
                           0, NULL);
        swr_init(is->swr_ctx);
        
        // 更新源参数
        av_channel_layout_copy(&is->audio_src.ch_layout, &af->frame->ch_layout);
        is->audio_src.freq = af->frame->sample_rate;
        is->audio_src.fmt = af->frame->format;
    }

    if (is->swr_ctx) {
        // 6. 执行重采样
        const uint8_t **in = (const uint8_t **)af->frame->extended_data;
        uint8_t **out = &is->audio_buf1;
        int out_count = (int64_t)wanted_nb_samples * is->audio_tgt.freq / 
                        af->frame->sample_rate + 256;
        int out_size = av_samples_get_buffer_size(NULL, is->audio_tgt.ch_layout.nb_channels,
                                                  out_count, is->audio_tgt.fmt, 0);
        
        // 采样数补偿
        if (wanted_nb_samples != af->frame->nb_samples) {
            swr_set_compensation(is->swr_ctx,
                (wanted_nb_samples - af->frame->nb_samples) * is->audio_tgt.freq / 
                af->frame->sample_rate,
                wanted_nb_samples * is->audio_tgt.freq / af->frame->sample_rate);
        }
        
        // 执行转换
        len2 = swr_convert(is->swr_ctx, out, out_count, in, af->frame->nb_samples);
        
        is->audio_buf = is->audio_buf1;
        resampled_data_size = len2 * is->audio_tgt.ch_layout.nb_channels * 
                              av_get_bytes_per_sample(is->audio_tgt.fmt);
    } else {
        is->audio_buf = af->frame->data[0];
        resampled_data_size = data_size;
    }

    // 7. 更新音频时钟
    if (!isnan(af->pts))
        is->audio_clock = af->pts + (double) af->frame->nb_samples / af->frame->sample_rate;
    else
        is->audio_clock = NAN;
    
    return resampled_data_size;
}
```

## OpenSL ES 音频输出

**文件位置**: `skymediaplayer/src/main/cpp/player/skyaudio.h/cpp`

### 架构设计

```
SkyAudioOut (抽象基类)
    ↓
SkySLESAudioOut (OpenSL ES 实现)
    ├── prepareAudio()     // 创建引擎和混音器
    ├── openAudio()        // 创建播放器
    ├── audioOutputThread() // 音频输出线程
    └── bufferQueueCallback() // 缓冲区回调
```

### 关键配置

```cpp
#define OPENSLES_BUFFERS 4   // 4个缓冲区
#define OPENSLES_BUFLEN 10   // 每个缓冲区10ms
```

### prepareAudio 流程

```cpp
bool SkySLESAudioOut::prepareAudio() {
    // 1. 创建 OpenSL ES 引擎
    slCreateEngine(&slEngineObject_, 0, nullptr, 0, nullptr, nullptr);
    (*slEngineObject_)->Realize(slEngineObject_, SL_BOOLEAN_FALSE);
    (*slEngineObject_)->GetInterface(slEngineObject_, SL_IID_ENGINE, &slEngineItf_);
    
    // 2. 创建输出混音器
    (*slEngineItf_)->CreateOutputMix(slEngineItf_, &slOutputMixObject_, 0, nullptr, nullptr);
    (*slOutputMixObject_)->Realize(slOutputMixObject_, SL_BOOLEAN_FALSE);
    
    return true;
}
```

### openAudio 流程

```cpp
bool SkySLESAudioOut::openAudio(const SkyAudioSpec& spec) {
    spec_ = spec;
    
    // 1. 配置 PCM 格式
    SLDataFormat_PCM format_pcm = {
        SL_DATAFORMAT_PCM,
        spec.channels,                    // 声道数
        spec.freq * 1000,                 // 采样率（毫赫兹）
        SL_PCMSAMPLEFORMAT_FIXED_16,      // 16位
        SL_PCMSAMPLEFORMAT_FIXED_16,
        getChannelMask(spec.channels),    // 声道掩码
        SL_BYTEORDER_LITTLEENDIAN
    };
    
    // 2. 配置数据源（缓冲区队列）
    SLDataLocator_AndroidSimpleBufferQueue loc_bufq = {
        SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE,
        OPENSLES_BUFFERS
    };
    SLDataSource audioSrc = {&loc_bufq, &format_pcm};
    
    // 3. 配置数据接收器（输出混音器）
    SLDataLocator_OutputMix loc_outmix = {SL_DATALOCATOR_OUTPUTMIX, slOutputMixObject_};
    SLDataSink audioSnk = {&loc_outmix, nullptr};
    
    // 4. 创建音频播放器
    const SLInterfaceID ids[] = {SL_IID_BUFFERQUEUE, SL_IID_VOLUME, SL_IID_PLAY};
    const SLboolean req[] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};
    (*slEngineItf_)->CreateAudioPlayer(slEngineItf_, &slPlayerObject_,
                                       &audioSrc, &audioSnk, 3, ids, req);
    (*slPlayerObject_)->Realize(slPlayerObject_, SL_BOOLEAN_FALSE);
    
    // 5. 获取接口
    (*slPlayerObject_)->GetInterface(slPlayerObject_, SL_IID_BUFFERQUEUE, &slBufferQueueItf_);
    (*slPlayerObject_)->GetInterface(slPlayerObject_, SL_IID_VOLUME, &slVolumeItf_);
    (*slPlayerObject_)->GetInterface(slPlayerObject_, SL_IID_PLAY, &slPlayItf_);
    
    // 6. 注册缓冲区回调
    (*slBufferQueueItf_)->RegisterCallback(slBufferQueueItf_, bufferQueueCallback, this);
    
    // 7. 计算缓冲区大小
    bytes_per_buffer_ = spec.freq * spec.channels * sizeof(int16_t) * OPENSLES_BUFLEN / 1000;
    
    // 8. 启动输出线程
    audio_thread_ = std::thread(&SkySLESAudioOut::audioOutputThread, this);
    
    return true;
}
```

### audioOutputThread 流程

```cpp
void SkySLESAudioOut::audioOutputThread() {
    // 设置实时线程优先级
    struct sched_param param;
    param.sched_priority = sched_get_priority_max(SCHED_FIFO);
    pthread_setschedparam(pthread_self(), SCHED_FIFO, &param);
    
    while (!abort_) {
        // 1. 检查缓冲区状态
        SLBufferQueueState slState;
        (*slBufferQueueItf_)->GetState(slBufferQueueItf_, &slState);
        
        // 2. 如果缓冲区已满，等待回调
        if (slState.count >= OPENSLES_BUFFERS) {
            std::unique_lock<std::mutex> lock(mutex_);
            cond_.wait(lock);
            continue;
        }
        
        // 3. 获取当前缓冲区
        uint8_t* buffer = buffers_[next_buffer_index_];
        
        // 4. 调用回调函数获取音频数据
        spec_.callback(spec_.userdata, buffer, bytes_per_buffer_);
        
        // 5. 入队到 OpenSL ES
        (*slBufferQueueItf_)->Enqueue(slBufferQueueItf_, buffer, bytes_per_buffer_);
        
        // 6. 移动到下一个缓冲区
        next_buffer_index_ = (next_buffer_index_ + 1) % OPENSLES_BUFFERS;
    }
}
```

### bufferQueueCallback 回调

```cpp
void SkySLESAudioOut::bufferQueueCallback(SLAndroidSimpleBufferQueueItf bq, void *context) {
    auto* audio = static_cast<SkySLESAudioOut*>(context);
    // 唤醒输出线程
    audio->cond_.notify_one();
}
```

## 音频缓冲区管理

### 三级缓冲架构

1. **FFplay 层缓冲**：
   - `is->audio_buf`：重采样后的音频数据
   - `is->audio_buf_index` / `is->audio_buf_size`：读写索引
   - `is->audio_buf1`：重采样输出缓冲区

2. **OpenSL ES 层缓冲**：
   - 4 个固定大小缓冲区（每区 10ms）
   - 总容量：`OPENSLES_BUFFERS * bytes_per_buffer_`
   - 循环使用

3. **硬件缓冲**：
   - OpenSL ES 内部缓冲（约 50ms 延迟）

### sdl_audio_callback 函数

**文件位置**: `ffplay.c`（第 2998-3080 行）

```c
static void sdl_audio_callback(void *opaque, Uint8 *stream, int len) {
    VideoState *is = opaque;
    int audio_size, len1;

    while (len > 0) {
        if (is->audio_buf_index >= is->audio_buf_size) {
            // 1. 获取解码/重采样数据
            audio_size = audio_decode_frame(is);
            if (audio_size < 0) {
                // 静音填充
                is->audio_buf = NULL;
                is->audio_buf_size = SDL_AUDIO_MIN_BUFFER_SIZE / 
                                     is->audio_tgt.frame_size * is->audio_tgt.frame_size;
            } else {
                is->audio_buf_size = audio_size;
            }
            is->audio_buf_index = 0;
        }
        
        // 2. 计算可拷贝的数据量
        len1 = is->audio_buf_size - is->audio_buf_index;
        if (len1 > len) len1 = len;
        
        // 3. 拷贝数据到输出缓冲区
        if (!is->muted && is->audio_buf) {
            memcpy(stream, (uint8_t *)is->audio_buf + is->audio_buf_index, len1);
        } else {
            memset(stream, 0, len1);
        }
        
        len -= len1;
        stream += len1;
        is->audio_buf_index += len1;
    }
    
    // 4. 更新音频写入缓冲区大小（用于时钟补偿）
    is->audio_write_buf_size = is->audio_buf_size - is->audio_buf_index;
}
```

## 音频焦点管理

**文件位置**: `skymediaplayer/src/main/java/imt/zw/skymediaplayer/audio/AudioFocusManager.kt`

### 焦点请求流程

```kotlin
class AudioFocusManager(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestAudioFocusApi26()
        } else {
            requestAudioFocusLegacy()
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestAudioFocusApi26(): Boolean {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
        
        return audioManager.requestAudioFocus(focusRequest) == 
               AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
}
```

### 焦点状态处理

```kotlin
private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
    when (focusChange) {
        AudioManager.AUDIOFOCUS_GAIN -> {
            // 恢复播放
            onAudioFocusGain()
        }
        AudioManager.AUDIOFOCUS_LOSS -> {
            // 永久丢失焦点，停止播放
            onAudioFocusLoss()
        }
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
            // 临时丢失焦点，暂停播放
            onAudioFocusLossTransient()
        }
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
            // 可以降低音量继续播放
            onAudioFocusLossTransientCanDuck()
        }
    }
}
```

## 音频时钟补偿

音频时钟需要补偿硬件延迟：

```c
// 计算音频时钟（考虑硬件延迟）
double audio_clock = is->audio_clock;
double audio_latency = (2 * is->audio_hw_buf_size + is->audio_write_buf_size) / 
                       (double)is->audio_tgt.bytes_per_sec + 0.050;  // +50ms 硬件延迟
double pts = audio_clock - audio_latency;
```

## 关键函数调用链

```
初始化阶段：
stream_component_open()
  → audio_open()
    → sky_open_audio()
      → SkySLESAudioOut::openAudio()
        → prepareAudio()
        → audio_thread_ = std::thread(audioOutputThread)
  → decoder_start()
    → audio_thread() [解码线程]

播放阶段：
audio_thread() [解码线程]
  → decoder_decode_frame()
  → frame_queue_put(&is->sampq)

audioOutputThread() [输出线程]
  → bufferQueueCallback() [OpenSL ES 回调]
  → spec_.callback() 
    → sdl_audio_callback()
      → audio_decode_frame()
        → frame_queue_peek_readable(&is->sampq)
        → swr_convert() [重采样]
      → memcpy(stream, audio_buf, len)
  → Enqueue() [入队到 OpenSL ES]

控制阶段：
pause() → SkySLESAudioOut::pauseAudio()
flush() → SkySLESAudioOut::flushAudio()
setVolume() → SkySLESAudioOut::setVolume()
```

## 性能优化要点

1. **低延迟设计**：
   - OpenSL ES 缓冲区仅 40ms（4×10ms）
   - 实时线程优先级（SCHED_FIFO）
   - 硬件加速渲染

2. **音画同步**：
   - 音频时钟补偿硬件延迟（+50ms）
   - 动态采样数调整
   - 序列号检测（Seek 同步）

3. **内存管理**：
   - 循环缓冲区复用
   - 按需分配重采样缓冲区
   - RAII 资源管理

4. **线程安全**：
   - 互斥锁保护共享资源
   - 原子变量控制状态
   - 条件变量唤醒机制

## 扩展开发指南

### 添加音频效果

1. 在 `configure_audio_filters()` 中添加滤镜
2. 使用 FFmpeg 的 `avfilter` API 创建滤镜图
3. 常用滤镜：`volume`、`equalizer`、`aecho` 等

### 优化音频延迟

1. 减少 `OPENSLES_BUFFERS` 数量（可能增加卡顿风险）
2. 减少 `OPENSLES_BUFLEN` 时长
3. 使用 AAudio（Android 8.0+）替代 OpenSL ES
