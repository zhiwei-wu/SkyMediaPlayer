#ifndef MY_PLAYER_SKYAUDIO_H
#define MY_PLAYER_SKYAUDIO_H

#include <thread>
#include <mutex>
#include <vector>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

#include "logger.h"
#include "SDL3/SDL_audio.h"
#include "ffplay.h"

#define OPENSLES_BUFFERS 4 /* 减少缓冲区数量以降低延迟 */
#define OPENSLES_BUFLEN  10 /* ms */

#define CHECK_OPENSL_ERROR(ret__, ...) \
    do { \
    	if ((ret__) != SL_RESULT_SUCCESS) \
    	{ \
    		ALOGE(__VA_ARGS__); \
    		goto fail; \
    	} \
    } while (0)

class SkyAudioOut {
public:
    virtual void free() {}
    virtual bool openAudio(const SkyAudioSpec *desired, SkyAudioSpec *obtained) {
        return 0;
    }
    virtual void pauseAudio(int pauseOn) {}
    virtual void flushAudio() {}
    virtual void setVolume(float left, float right) {}
    virtual void closeAudio() {}

    virtual double getLatencySeconds() {
        return 0.0;
    }
    virtual void setDefaultLatencySeconds(double latency) {}

public:
    std::mutex mtx;
    double minimalLatencySeconds;
};

class SkySLESAudioOut : public SkyAudioOut {
public:
    bool prepareAudio();

    bool openAudio(const SkyAudioSpec *desired, SkyAudioSpec *obtained) override;

    void pauseAudio(int pauseOn) override;

    void flushAudio() override;

    void audioOutputThread();

    void closeAudio() override;

    // 静态回调函数声明
    static void bufferQueueCallback(SLAndroidSimpleBufferQueueItf bufferQueueItf, void *context);

private:
    std::unique_ptr<std::condition_variable> wakeup_cond_;
    std::unique_ptr<std::mutex> wakeup_mutex_;
    std::thread audio_thread_;             // 线程对象
    std::atomic<bool> stop_thread_flag_{false}; // 控制线程退出的标志

    SkyAudioSpec spec_;
    SLDataFormat_PCM format_pcm_;
    int bytes_per_frame_ = 0;
    int milli_per_buffer_ = 0;
    int frames_per_buffer_ = 0;
    int bytes_per_buffer_ = 0;

    SLObjectItf slObject_ = nullptr;
    SLEngineItf slEngine_ = nullptr;
    SLObjectItf slOutputMixObject_ = nullptr;
    SLObjectItf slPlayerObject_ = nullptr;
    SLAndroidSimpleBufferQueueItf slBufferQueueItf_ = nullptr;
    SLVolumeItf slVolumeItf_ = nullptr;
    SLPlayItf slPlayItf_ = nullptr;

    std::atomic<bool> need_set_volume{false};
    std::atomic<float> left_volume{1.0f};
    std::atomic<float> right_volume{1.0f};

    std::atomic<bool> abort_request_{false};
    std::atomic<bool> pause_on_{false};
    std::atomic<bool> need_flush_{false};
    std::atomic<bool> is_running_{false};

    std::vector<uint8_t> buffer_;
    size_t buffer_capacity_ = 0;
};

#endif //MY_PLAYER_SKYAUDIO_H
