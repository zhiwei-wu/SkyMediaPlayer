#include <pthread.h>
#include <sched.h>
#include <thread>
#include <chrono>

#include "skyaudio.h"

static const char *TAG = "SkySLGESAudioOut";

bool SkySLESAudioOut::prepareAudio() {
    wakeup_cond_ = std::make_unique<std::condition_variable>();
    if (!wakeup_cond_) {
        ALOG_E(TAG, "SDL_CreateCondition failed");
        return false;
    }

    wakeup_mutex_ = std::make_unique<std::mutex>();
    if (!wakeup_mutex_) {
        ALOG_E(TAG, "SDL_CreateMutex failed");
        return false;
    }

    // 1. 创建 OpenSL ES 引擎对象
    SLresult result;
    result = slCreateEngine(&slObject_, 0, nullptr, 0, nullptr, nullptr);
    if (result != SL_RESULT_SUCCESS) {
        ALOG_E(TAG, "Failed to create OpenSL ES engine object");
        return false;
    }

    // 2. 实例化引擎
    result = (*slObject_)->Realize(slObject_, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) {
        ALOG_E(TAG, "Failed to realize OpenSL ES engine object");
        return false;
    }

    // 3. 获取引擎接口
    result = (*slObject_)->GetInterface(slObject_, SL_IID_ENGINE, &slEngine_);
    if (result != SL_RESULT_SUCCESS) {
        ALOG_E(TAG, "Failed to get engine interface");
        return false;
    }

    // 4. 创建输出混音器对象
    const SLInterfaceID engineIIDs[] = {SL_IID_ENVIRONMENTALREVERB};
    const SLboolean engineIIDsRequired[] = {SL_BOOLEAN_FALSE};

    result = (*slEngine_)->CreateOutputMix(slEngine_, &slOutputMixObject_,
                                           0, engineIIDs, engineIIDsRequired);
    if (result != SL_RESULT_SUCCESS) {
        ALOG_E(TAG, "Failed to create output mix object");
        return false;
    }

    // 5. 实例化输出混音器
    result = (*slOutputMixObject_)->Realize(slOutputMixObject_, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) {
        ALOG_E(TAG, "Failed to realize output mix object");
        return false;
    }

    return true;
}

bool SkySLESAudioOut::openAudio(const SkyAudioSpec *desired, SkyAudioSpec *obtained) {
    if (!prepareAudio()) {
        ALOG_E(TAG, "prepareAudio failed");
        return false;
    }

    SLDataLocator_AndroidSimpleBufferQueue loc_bufq =
            {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, OPENSLES_BUFFERS};

    format_pcm_.formatType = SL_DATAFORMAT_PCM;
    format_pcm_.numChannels = desired->sdl_audioSpec.channels;
    format_pcm_.samplesPerSec = desired->sdl_audioSpec.freq * 1000; // milli Hz
    format_pcm_.bitsPerSample = SL_PCMSAMPLEFORMAT_FIXED_16;
    format_pcm_.containerSize = SL_PCMSAMPLEFORMAT_FIXED_16;
    switch (desired->sdl_audioSpec.channels) {
        case 2:
            format_pcm_.channelMask = SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT;
            break;
        case 1:
            format_pcm_.channelMask = SL_SPEAKER_FRONT_CENTER;
            break;
        default:
            ALOG_E(TAG, "Unsupported number of channels:%d", desired->sdl_audioSpec.channels);
            closeAudio();
            return false;
    }
    format_pcm_.endianness = SL_BYTEORDER_LITTLEENDIAN;
    SLDataSource audioSource = {
            &loc_bufq, &format_pcm_
    };

    SLDataLocator_OutputMix locOutmix = {SL_DATALOCATOR_OUTPUTMIX, slOutputMixObject_};
    SLDataSink audioSink = {&locOutmix, nullptr};

    const SLInterfaceID ids[] = {SL_IID_BUFFERQUEUE, SL_IID_VOLUME, SL_IID_PLAY};
    const SLboolean req[] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};

    SLObjectItf playerObject = nullptr;
    SLresult result = (*slEngine_)->CreateAudioPlayer(
            slEngine_,
            &playerObject,
            &audioSource,
            &audioSink,
            sizeof(ids) / sizeof(ids[0]),
            ids,
            req);
    if (result != SL_RESULT_SUCCESS) {
        ALOG_E(TAG, "Failed to create audio player");
        closeAudio();
        return false;
    }
    slPlayerObject_ = playerObject;

    result = (*playerObject)->Realize(playerObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) {
        ALOG_E(TAG, "Failed to realize audio player");
        closeAudio();
        return false;
    }

    // 获取接口
    result = (*playerObject)->GetInterface(playerObject, SL_IID_PLAY, &slPlayItf_);
    if (result != SL_RESULT_SUCCESS) {
        ALOG_E(TAG, "Failed to get SL_IID_PLAY interface");
        closeAudio();
        return false;
    }

    result = (*playerObject)->GetInterface(playerObject, SL_IID_VOLUME, &slVolumeItf_);
    if (result != SL_RESULT_SUCCESS) {
        ALOG_E(TAG, "Failed to get SL_IID_VOLUME interface");
        closeAudio();
        return false;
    }

    result = (*playerObject)->GetInterface(playerObject, SL_IID_ANDROIDSIMPLEBUFFERQUEUE,
                                           &slBufferQueueItf_);
    if (result != SL_RESULT_SUCCESS) {
        ALOG_E(TAG, "Failed to get SL_IID_ANDROIDSIMPLEBUFFERQUEUE interface");
        closeAudio();
        return false;
    }

    result = (*slBufferQueueItf_)->RegisterCallback(slBufferQueueItf_, bufferQueueCallback, this);
    if (result != SL_RESULT_SUCCESS) {
        ALOG_E(TAG, "Failed to get RegisterCallback");
        closeAudio();
        return false;
    }

    bytes_per_frame_ = format_pcm_.numChannels * format_pcm_.bitsPerSample / 8;
    milli_per_buffer_ = OPENSLES_BUFLEN;
    // samplesPerSec 在上面转化成了ms，这里是微秒
    frames_per_buffer_ = milli_per_buffer_ * format_pcm_.samplesPerSec / 1000000;
    bytes_per_buffer_ = bytes_per_frame_ * frames_per_buffer_;
    buffer_capacity_ = OPENSLES_BUFFERS * bytes_per_buffer_;
    ALOG_I(TAG, "OpenSL-ES: bytes_per_frame  = %d bytes\n", bytes_per_frame_);
    ALOG_I(TAG, "OpenSL-ES: milli_per_buffer = %d ms\n", milli_per_buffer_);
    ALOG_I(TAG, "OpenSL-ES: frame_per_buffer = %d frames\n", frames_per_buffer_);
    ALOG_I(TAG, "OpenSL-ES: bytes_per_buffer = %d bytes\n", bytes_per_buffer_);
    ALOG_I(TAG, "OpenSL-ES: buffer_capacity  = %zu bytes\n", buffer_capacity_);
    buffer_.resize(buffer_capacity_, 0);

    for (int i = 0; i < OPENSLES_BUFFERS; ++i) {
        result = (*slBufferQueueItf_)->Enqueue(slBufferQueueItf_,
                                               buffer_.data() + i * bytes_per_buffer_,
                                               bytes_per_buffer_);
        if (result != SL_RESULT_SUCCESS) {
            ALOG_E(TAG, "Failed to enqueue buffer");
            closeAudio();
            return false;
        }
    }

    pause_on_.store(true, std::memory_order_relaxed);
    abort_request_.store(false, std::memory_order_relaxed);

    // 创建读取buffer线程，与 bufferQueueCallback 配合
    stop_thread_flag_.store(false, std::memory_order_relaxed);
    audio_thread_ = std::thread([this]() {
        this->audioOutputThread();
    });

    if (obtained) {
        *obtained = *desired;  // 返回更新后的 spec_，包含正确的 size
        obtained->size = buffer_capacity_;
    }
    spec_ = *desired;

    ALOG_I(TAG, "OpenSL-ES: Audio spec configured - callback: %p, userdata: %p, freq: %d, channels: %d, format: %d, silence: %d",
           spec_.callback, spec_.userdata, spec_.sdl_audioSpec.freq, spec_.sdl_audioSpec.channels,
           spec_.sdl_audioSpec.format, spec_.silence);

    ALOG_I(TAG, "OpenSL-ES: Opened audio device success");
    return true;
}

void
SkySLESAudioOut::bufferQueueCallback(SLAndroidSimpleBufferQueueItf bufferQueueItf, void *context) {
    auto *audioOutput = static_cast<SkySLESAudioOut *>(context);

    if (audioOutput && audioOutput->wakeup_cond_) {
        // 唤醒等待的线程
        audioOutput->wakeup_cond_->notify_one();
    } else {
        ALOG_W(TAG, "[BUFFER_CALLBACK] Invalid context or condition variable - audioOutput: %p", audioOutput);
    }
}

void SkySLESAudioOut::audioOutputThread() {
    // 设置线程优先级
    struct sched_param param{};
    param.sched_priority = sched_get_priority_max(SCHED_FIFO);  // 设置为最高优先级
    pthread_setschedparam(pthread_self(), SCHED_FIFO, &param);

    ALOG_I(TAG, "[AUDIO_THREAD] Audio output thread started");

    // 在线程开始时检查关键接口是否为空，不满足则直接退出线程
    if (!slBufferQueueItf_ || !slPlayItf_) {
        ALOG_E(TAG, "Critical OpenSL ES interfaces are null, exiting audio thread");
        is_running_.store(false, std::memory_order_relaxed);
        return;
    }

    ALOG_I(TAG, "[AUDIO_THREAD] OpenSL ES interfaces initialized successfully");
    ALOG_I(TAG, "[AUDIO_THREAD] Audio callback: %p, userdata: %p", spec_.callback, spec_.userdata);
    ALOG_I(TAG, "[AUDIO_THREAD] Buffer config - bytes_per_buffer: %d, buffer_count: %d", bytes_per_buffer_, OPENSLES_BUFFERS);

    // 初始化下一个缓冲区索引（局部变量）
    int next_buffer_index = 0;

    if (!abort_request_.load(std::memory_order_relaxed)
        && !pause_on_.load(std::memory_order_relaxed)) {
        (*slPlayItf_)->SetPlayState(slPlayItf_, SL_PLAYSTATE_PLAYING);
    }

    is_running_.store(true, std::memory_order_relaxed);

    // 线程没有被停止
    while (!stop_thread_flag_.load(std::memory_order_relaxed)
        && !abort_request_.load(std::memory_order_relaxed)) {

        SLAndroidSimpleBufferQueueState slState = {0};
        SLresult slRet = (*slBufferQueueItf_)->GetState(slBufferQueueItf_, &slState);
        if (SL_RESULT_SUCCESS != slRet) {
            ALOG_E(TAG, "%s: slBufferQueueItf_->GetState() failed", __func__ );
            break;
        }

        std::unique_lock<std::mutex> lock(*wakeup_mutex_);

        // 处理刷新请求 - 优先处理，在其他逻辑之前
        if (need_flush_.load(std::memory_order_relaxed)) {
            ALOG_I(TAG, "[AUDIO_THREAD] Flushing audio buffers");

            // 获取当前播放状态
            SLuint32 currentState;
            (*slPlayItf_)->GetPlayState(slPlayItf_, &currentState);
            ALOG_I(TAG, "[AUDIO_THREAD] Current play state before flush: %u", currentState);

            // 只清空缓冲区，不改变播放状态，避免触发 AudioTrack 重建
            (*slBufferQueueItf_)->Clear(slBufferQueueItf_);
            ALOG_I(TAG, "[AUDIO_THREAD] Buffer queue cleared without stopping playback");

            // 重置缓冲区索引
            next_buffer_index = 0;
            ALOG_I(TAG, "[AUDIO_THREAD] Reset buffer index to 0");

            need_flush_.store(false, std::memory_order_relaxed);

            // 确保播放状态保持活跃（如果之前是播放状态）
            if (currentState == SL_PLAYSTATE_PLAYING && !pause_on_.load(std::memory_order_relaxed)) {
                // 播放状态已经是 PLAYING，无需重新设置
                ALOG_I(TAG, "[AUDIO_THREAD] Playback state maintained as PLAYING");
            } else if (!pause_on_.load(std::memory_order_relaxed)) {
                // 只有在必要时才设置播放状态
                (*slPlayItf_)->SetPlayState(slPlayItf_, SL_PLAYSTATE_PLAYING);
                ALOG_I(TAG, "[AUDIO_THREAD] Set play state to PLAYING after flush");
            }

            ALOG_I(TAG, "[AUDIO_THREAD] Flush completed, will immediately fill new audio data");

            // flush 后立即尝试填充一些缓冲区，确保音频流能够重新启动
            continue;
        }

        // 处理暂停逻辑
        if (pause_on_.load(std::memory_order_relaxed)) {
            ALOG_I(TAG, "[AUDIO_THREAD] Audio paused, setting play state to PAUSED");
            (*slPlayItf_)->SetPlayState(slPlayItf_, SL_PLAYSTATE_PAUSED);

            // 在暂停状态下持续等待，直到明确被唤醒（取消暂停或停止）
            // 不使用超时，避免自动恢复播放导致频繁的 pause/resume 循环
            while (pause_on_.load(std::memory_order_relaxed) &&
                   !stop_thread_flag_.load(std::memory_order_relaxed) &&
                   !abort_request_.load(std::memory_order_relaxed)) {

                wakeup_cond_->wait(lock, [this] {
                    return !pause_on_.load(std::memory_order_relaxed) ||
                           stop_thread_flag_.load(std::memory_order_relaxed) ||
                           abort_request_.load(std::memory_order_relaxed);
                });
            }

            // 只有在明确取消暂停时才恢复播放
            if (!pause_on_.load(std::memory_order_relaxed) &&
                !stop_thread_flag_.load(std::memory_order_relaxed) &&
                !abort_request_.load(std::memory_order_relaxed)) {
                ALOG_I(TAG, "[AUDIO_THREAD] Audio resumed, setting play state to PLAYING");
                (*slPlayItf_)->SetPlayState(slPlayItf_, SL_PLAYSTATE_PLAYING);
            }
            continue;
        }

        // 处理音量设置
        if (need_set_volume.load(std::memory_order_relaxed)) {
            float left = left_volume.load(std::memory_order_relaxed);
            float right = right_volume.load(std::memory_order_relaxed);

            ALOG_D(TAG, "[AUDIO_THREAD] Setting volume - left: %.2f, right: %.2f", left, right);

            // OpenSL ES 音量范围是毫贝尔 (mB)，0 是最大音量，负值降低音量
            SLmillibel leftVol = left <= 0.01f ? SL_MILLIBEL_MIN :
                                (SLmillibel)(2000.0f * log10f(left));
            SLmillibel rightVol = right <= 0.01f ? SL_MILLIBEL_MIN :
                                 (SLmillibel)(2000.0f * log10f(right));

            if (slVolumeItf_) {
                (*slVolumeItf_)->SetVolumeLevel(slVolumeItf_, (leftVol + rightVol) / 2);
            }

            need_set_volume.store(false, std::memory_order_relaxed);
        }

        if (slState.count < OPENSLES_BUFFERS) {
            // 使用局部变量管理缓冲区索引
            uint8_t* current_buffer = buffer_.data() + next_buffer_index * bytes_per_buffer_;

            // 调用音频回调函数获取音频数据
            if (spec_.callback && spec_.userdata) {
                // 检查是否有 flush 请求，如果有则跳过本次回调
                if (need_flush_.load(std::memory_order_relaxed)) {
                    ALOG_I(TAG, "[AUDIO_THREAD] Flush requested, skipping callback and will process flush");
                    continue;  // 跳过回调，回到循环开始处理 flush
                }

                // 先用静音填充缓冲区
                memset(current_buffer, spec_.silence, bytes_per_buffer_);

                // 调用回调函数获取音频数据
                spec_.callback(spec_.userdata, current_buffer, bytes_per_buffer_);

                // 将缓冲区入队到 OpenSL ES
                slRet = (*slBufferQueueItf_)->Enqueue(slBufferQueueItf_,
                                                     current_buffer,
                                                     bytes_per_buffer_);
                if (slRet == SL_RESULT_SUCCESS) {
                    // 更新下一个缓冲区索引
                    next_buffer_index = (next_buffer_index + 1) % OPENSLES_BUFFERS;
                } else {
                    ALOG_E(TAG, "Failed to enqueue audio buffer, error: %d", slRet);
                }
            } else {
                // 没有回调函数，填充静音
                memset(current_buffer, spec_.silence, bytes_per_buffer_);
                slRet = (*slBufferQueueItf_)->Enqueue(slBufferQueueItf_,
                                                     current_buffer,
                                                     bytes_per_buffer_);
                if (slRet == SL_RESULT_SUCCESS) {
                    next_buffer_index = (next_buffer_index + 1) % OPENSLES_BUFFERS;
                }
            }
        } else {
            // 所有缓冲区都在使用中，等待回调唤醒
            // 简单等待，让 OpenSL ES 缓冲区回调能够唤醒线程
            wakeup_cond_->wait_for(lock, std::chrono::milliseconds(1000));
        }
    }

    // 线程退出前的清理
    ALOG_I(TAG, "[AUDIO_THREAD] Audio thread stopping, cleaning up...");
    (*slPlayItf_)->SetPlayState(slPlayItf_, SL_PLAYSTATE_STOPPED);
    (*slBufferQueueItf_)->Clear(slBufferQueueItf_);

    is_running_.store(false, std::memory_order_relaxed);
    ALOG_I(TAG, "Audio output thread exited");
}

void SkySLESAudioOut::closeAudio() {
    ALOG_I(TAG, "Closing audio device");
    SkyAudioOut::closeAudio();

    // 请求线程停止
    stop_thread_flag_.store(true, std::memory_order_relaxed);

    // 唤醒线程以便其检查停止标志
    if (wakeup_cond_) {
        wakeup_cond_->notify_all();
    }

    // 如果线程是 joinable 的，则等待其结束
    if (audio_thread_.joinable()) {
        audio_thread_.join(); // 或 detach，但推荐 join 以确保同步
    }
}

void SkySLESAudioOut::pauseAudio(int pauseOn) {
    // 设置暂停状态
    pause_on_.store(pauseOn != 0, std::memory_order_relaxed);

    // 唤醒音频线程，让它检查新的暂停状态
    if (wakeup_cond_) {
        wakeup_cond_->notify_one();
    }
}

void SkySLESAudioOut::flushAudio() {
    ALOG_I(TAG, "Flushing audio buffers");

    // 检查音频线程状态
    ALOG_I(TAG, "Audio thread running: %s, stop flag: %s",
           is_running_.load(std::memory_order_relaxed) ? "true" : "false",
           stop_thread_flag_.load(std::memory_order_relaxed) ? "true" : "false");

    // 设置刷新标志
    need_flush_.store(true, std::memory_order_relaxed);

    // 唤醒音频线程，让它处理刷新请求
    if (wakeup_cond_) {
        wakeup_cond_->notify_one();
    }
}