#ifndef MY_PLAYER_SKYMEDIAPLAYER_H
#define MY_PLAYER_SKYMEDIAPLAYER_H

#include <memory>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <jni.h>
#include "skyrenderer.h"
#include "skyaudio.h"
#include "sky_msg_queue.h"
#include "sky_renderer_types.h"
#include "sky_decoder_types.h"
#include "sky_hw_decoder.h"

#define TAG "SkyPlayer"

// 专门用来保存需要从C++调用到Java的方法ID的类
class SkyMediaPlayerMethod {
public:
    SkyMediaPlayerMethod() : javaClass(nullptr), postEventFromNative(nullptr) {}

    ~SkyMediaPlayerMethod() {
        // 析构函数中不释放JNI资源，因为需要在有JNIEnv的地方释放
    }

    // 初始化方法ID缓存
    bool initialize(JNIEnv* env, const char* className) {
        // 查找Java类
        jclass localClass = env->FindClass(className);
        if (!localClass) {
            return false;
        }

        // 创建全局引用
        javaClass = static_cast<jclass>(env->NewGlobalRef(localClass));
        env->DeleteLocalRef(localClass);

        if (!javaClass) {
            return false;
        }

        // 获取postEventFromNative方法ID
        postEventFromNative = env->GetStaticMethodID(javaClass, "postEventFromNative",
            "(Limt/zw/skymediaplayer/player/SkyMediaPlayer;IIILjava/lang/Object;)V");

        return postEventFromNative != nullptr;
    }

    // 清理资源
    void cleanup(JNIEnv* env) {
        if (javaClass) {
            env->DeleteGlobalRef(javaClass);
            javaClass = nullptr;
        }
        postEventFromNative = nullptr;
    }

    // 检查是否已初始化
    bool isInitialized() const {
        return javaClass != nullptr && postEventFromNative != nullptr;
    }

    // 获取Java类
    jclass getJavaClass() const {
        return javaClass;
    }

    // 获取postEventFromNative方法ID
    jmethodID getPostEventFromNative() const {
        return postEventFromNative;
    }

private:
    jclass javaClass;                    // Java类的全局引用
    jmethodID postEventFromNative;       // postEventFromNative方法ID
};

class SkyVideoOutHandler {
public:
    SkyVideoOutHandler() : window_(nullptr), rendererBackend_(RendererBackend::OPENGL_ES) {}

    void setSkyRenderer(std::unique_ptr<SkyRenderer> renderer) {
        renderer_ = std::move(renderer);
    }

    void setRendererBackend(RendererBackend backend) {
        if (rendererBackend_ != backend) {
            rendererBackend_ = backend;
            // 重置已有渲染器，下次 setWindow 时将根据新后端重新创建
            if (renderer_) {
                renderer_->terminate();
                renderer_.reset();
            }
        }
    }

    void setWindow(EGLNativeWindowType window);

    bool displayImage(AVFrame *frame);

    void releaseResources();

    /**
     * 获取当前 ANativeWindow 指针（用于 Surface 直渲模式）
     * @return ANativeWindow 指针，可能为 nullptr
     */
    EGLNativeWindowType getWindow() const { return window_; }

public:
    std::mutex mtx;

private:
    EGLNativeWindowType window_;
    RendererBackend rendererBackend_;
    std::unique_ptr<SkyRenderer> renderer_;
};

enum class AudioOutType {
    ANDROID_AUDIO_TRACK,
    OPENSL_ES
};

class SkyAudioOutHandler {
public:
    // 添加构造函数，确保成员变量正确初始化
    SkyAudioOutHandler() = default;

    static std::unique_ptr<SkyAudioOut> createAudioOutInstance(AudioOutType type);
    bool openAudio(const AudioOutType audioOutType, SkyAudioSpec *desired, SkyAudioSpec *obtained);

    // 添加暂停音频方法
    void pauseAudio(bool pause);

    // 添加刷新音频方法
    void flushAudio();

    // 添加清理方法
    void cleanup();

public:
    std::mutex mtx;

private:
    std::unique_ptr<SkyAudioOut> skyAudioOut_;
};

// ============================================================================
// Media Event Type Definitions for Java Layer Communication
// ============================================================================
enum class MEDIA_EVENT_TYPE {
    MEDIA_NOP               = 0,        // interface test message
    MEDIA_PREPARED          = 1,
    MEDIA_PLAYBACK_COMPLETE = 2,
    MEDIA_BUFFERING_UPDATE  = 3,        // arg1 = percentage, arg2 = cached duration
    MEDIA_SEEK_COMPLETE     = 4,
    MEDIA_SET_VIDEO_SIZE    = 5,        // arg1 = width, arg2 = height
    MEDIA_GET_IMG_STATE     = 6,        // arg1 = timestamp, arg2 = result code, obj = file name
    MEDIA_TIMED_TEXT        = 99,       // not supported yet
    MEDIA_ERROR             = 100,      // arg1, arg2
    MEDIA_INFO              = 200,      // arg1, arg2
    MEDIA_SET_VIDEO_SAR     = 10001,    // arg1 = sar.num, arg2 = sar.den
};

enum class MEDIA_INFO_TYPE {
    // 0xx
    MEDIA_INFO_UNKNOWN = 1,
    MEDIA_INFO_STARTED_AS_NEXT = 2,
    MEDIA_INFO_VIDEO_RENDERING_START = 3,

    // 7xx
    MEDIA_INFO_VIDEO_TRACK_LAGGING = 700,
    MEDIA_INFO_BUFFERING_START = 701,
    MEDIA_INFO_BUFFERING_END = 702,
    MEDIA_INFO_NETWORK_BANDWIDTH = 703,

    // 8xx
    MEDIA_INFO_BAD_INTERLEAVING = 800,
    MEDIA_INFO_NOT_SEEKABLE = 801,
    MEDIA_INFO_METADATA_UPDATE = 802,

    //9xx
    MEDIA_INFO_TIMED_TEXT_ERROR = 900,

    //100xx
    MEDIA_INFO_VIDEO_ROTATION_CHANGED = 10001,
    MEDIA_INFO_AUDIO_RENDERING_START  = 10002,
    MEDIA_INFO_AUDIO_DECODED_START    = 10003,
    MEDIA_INFO_VIDEO_DECODED_START    = 10004,
    MEDIA_INFO_OPEN_INPUT             = 10005,
    MEDIA_INFO_FIND_STREAM_INFO       = 10006,
    MEDIA_INFO_COMPONENT_OPEN         = 10007,
    MEDIA_INFO_COMPONENT_OPEN_ERR     = 10008,
    MEDIA_INFO_VIDEO_SEEK_RENDERING_START = 10009,
    MEDIA_INFO_AUDIO_SEEK_RENDERING_START = 100010,

    MEDIA_INFO_MEDIA_ACCURATE_SEEK_COMPLETE = 10100
};

/**
 * 硬件解码管理器
 * 管理硬件解码器的生命周期，提供给 ffplay.c 调用的 C 接口
 */
class SkyDecoderHandler {
public:
    SkyDecoderHandler() = default;

    /**
     * 设置用户期望的解码模式（必须在 prepareAsync 之前调用）
     */
    void setDecoderMode(DecoderMode mode);

    /**
     * 获取用户设置的解码模式
     */
    DecoderMode getDecoderMode() const { return requestedMode_; }

    /**
     * 获取实际生效的解码模式（经过回退策略后的结果）
     */
    DecoderMode getActiveDecoderMode() const;

    /**
     * 初始化硬件解码器（由 ffplay.c 的 stream_component_open 调用）
     * 实现三级回退策略：HW_SURFACE → HW_BUFFER → SOFTWARE
     * @param codecpar 编解码器参数
     * @param surface  ANativeWindow 指针（可为 nullptr）
     * @return true 硬解初始化成功，false 需要回退到软解
     */
    bool initHWDecoder(AVCodecParameters *codecpar, void *surface);

    /**
     * 向硬件解码器投喂数据包
     * @return 0 成功, AVERROR(EAGAIN) 需要先取帧, 其他负值表示错误
     */
    int sendPacket(AVPacket *packet);

    /**
     * 从硬件解码器获取解码帧
     * @return 0 成功, AVERROR(EAGAIN) 需要先投喂, AVERROR_EOF 结束
     */
    int receiveFrame(AVFrame *frame);

    /**
     * 从硬件解码器取出帧但不渲染（仅 Surface 模式）
     * @return 0 成功, AVERROR(EAGAIN) 需要先投喂, AVERROR_EOF 结束
     */
    int dequeueFrame(AVFrame *frame);

    /**
     * 将已取出的帧渲染到 Surface（仅 Surface 模式）
     * @return true 渲染成功
     */
    bool renderOutputBuffer();

    /**
     * 刷新硬件解码器（Seek 时调用）
     */
    void flush();

    /**
     * 释放硬件解码器资源
     */
    void release();

    /**
     * 硬件解码器是否已激活
     */
    bool isHWDecoderActive() const;

    /**
     * 是否处于 Surface 直渲模式
     */
    bool isSurfaceMode() const;

public:
    std::mutex mtx;

private:
    DecoderMode requestedMode_ = DecoderMode::AUTO;
    std::unique_ptr<SkyHWDecoder> hwDecoder_;
};

class SkyPlayer {
public:
    SkyPlayer();
    ~SkyPlayer();

    void cleanup();

    void setWeakJavaPlayerPtr(void* ptr) {
        weakJavaPlayer = ptr;
    }
    void* getWeakJavaPlayerPtr() {
        return weakJavaPlayer;
    }

    SkyMediaPlayerMethod& getMethodManager() {
        return methodManager_;
    }

    SkyVideoOutHandler& getSkyVideoOutHandler() {
        return skyVideoOutHandler_;
    }

    SkyAudioOutHandler& getSkyAudioOutHandler() {
        return skyAudioOutHandler_;
    }

    SkyDecoderHandler& getSkyDecoderHandler() {
        return skyDecoderHandler_;
    }

    // 播放控制方法
    void start();
    void pause();
    void seekTo(int64_t pos);
    void stop();

    // 状态查询方法
    bool isPlaying();
    int64_t getCurrentPosition();
    int64_t getDuration();

    // 设置动态音频滤镜
    int setAudioFilter(const char* filters);

    // 设置渲染后端（必须在 prepareAsync 之前调用）
    void setRendererBackend(RendererBackend backend);
    RendererBackend getRendererBackend() const { return rendererBackend_; }

    // 设置解码模式（必须在 prepareAsync 之前调用）
    void setDecoderMode(DecoderMode mode);
    DecoderMode getDecoderMode() const { return decoderMode_; }

    // JNI 相关方法
    void setDataSource(const char* path);
    const char *getDataSource() const;
    void prepareAsync();

    // 状态控制回调
    void onPlaybackStateChanged(int state);

    // 消息队列相关方法
    SkyMessageQueue& getMessageQueue() {
        return messageQueue_;
    }

    // 发送消息到队列
    bool postMessage(const SkyMessage& message);
    bool postMessage(int what, int arg1 = 0, int arg2 = 0, void* obj = nullptr);

    // 向Java层发送事件的便捷方法
    bool postEventToJava(int what, int arg1 = 0, int arg2 = 0, void* obj = nullptr);
    bool postMediaEventToJava(MEDIA_EVENT_TYPE eventType, int arg1 = 0, int arg2 = 0, void* obj = nullptr) {
        return postEventToJava(static_cast<int>(eventType), arg1, arg2, obj);
    }

public:
    std::mutex mtx;

    // c++ 调用 ffplay.c 传递
    VideoState* is;

    bool restart = false;
    bool autoStartOnPrepare = true;
    bool firstVideoFrameRendered = false;

    AudioOutType audioOutType = AudioOutType::OPENSL_ES;

    enum PlayerState {
        STATE_IDLE = 0,
        STATE_INITIALIZED,
        STATE_ASYNC_PREPARING,
        STATE_PREPARED,
        STATE_STARTED,
        STATE_PAUSED,
        STATE_COMPLETED,
        STATE_STOPPED,
        STATE_ERROR,
        STATE_END
    };

private:
    char *data_source_;

    SkyVideoOutHandler skyVideoOutHandler_;
    SkyAudioOutHandler skyAudioOutHandler_;
    SkyDecoderHandler skyDecoderHandler_;

    void *weakJavaPlayer;
    SkyMediaPlayerMethod methodManager_;

    PlayerState playerState = STATE_IDLE;

    SkyMessageQueue messageQueue_;
    void handleMessage(const SkyMessage& message);

    void setPlayerState(PlayerState state);
    const char* getPlayerStateString(PlayerState state);

    // 渲染后端配置
    RendererBackend rendererBackend_ = RendererBackend::OPENGL_ES;

    // 解码模式配置
    DecoderMode decoderMode_ = DecoderMode::AUTO;

    std::atomic<bool> isDestroyed_{false};
};

SkyPlayer* createSkyPlayer();
void setSkyPlayerWeakJavaPlayer(SkyPlayer *player, void *weakJavaPlayer);

void setupFfmpegLogCallback() noexcept;

#endif //MY_PLAYER_SKYMEDIAPLAYER_H