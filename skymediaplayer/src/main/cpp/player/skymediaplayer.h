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
    // 添加构造函数，正确初始化成员变量
    SkyVideoOutHandler() : window_(nullptr) {}

    void setSkyRenderer(std::unique_ptr<SkyRenderer> renderer) {
        renderer_ = std::move(renderer);
    }

    void setWindow(EGLNativeWindowType window);

    bool displayImage(AVFrame *frame);

    void releaseResources();

public:
    std::mutex mtx;

private:
    EGLNativeWindowType window_;
    std::unique_ptr<SkyRenderer> renderer_;
    std::unique_ptr<SkyVideoOutHandler> skyVideoOutHandler_;
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

class SkyPlayer {
public:
    // 添加构造函数和析构函数声明
    SkyPlayer();
    ~SkyPlayer();

    // 添加清理方法
    void cleanup();

    void setWeakJavaPlayerPtr(void* ptr) {
        weakJavaPlayer = ptr;
    }
    void* getWeakJavaPlayerPtr() {
        return weakJavaPlayer;
    }

    // 获取方法管理器
    SkyMediaPlayerMethod& getMethodManager() {
        return methodManager_;
    }

    SkyVideoOutHandler& getSkyVideoOutHandler() {
        return skyVideoOutHandler_;
    }

    SkyAudioOutHandler& getSkyAudioOutHandler() {
        return skyAudioOutHandler_;
    }

    // 添加播放控制方法
    void start();
    void pause();
    void seekTo(int64_t pos);
    void stop();

    // 添加状态查询方法
    bool isPlaying();
    int64_t getCurrentPosition();
    int64_t getDuration();

    // 添加JNI相关方法
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
    // 使用 MEDIA_EVENT_TYPE 枚举发送事件到Java层的便捷方法
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

    // 添加状态管理
    enum PlayerState {
        STATE_IDLE = 0,
        STATE_INITIALIZED,// 1
        STATE_ASYNC_PREPARING,//2
        STATE_PREPARED,//3
        STATE_STARTED,//4
        STATE_PAUSED,//5
        STATE_COMPLETED,//6
        STATE_STOPPED,//7
        STATE_ERROR,//8
        STATE_END //9
    };

private:
    char *data_source_;

    SkyVideoOutHandler skyVideoOutHandler_;
    SkyAudioOutHandler skyAudioOutHandler_;

    // 保存 java 层JxMediaPlayer对象，native->java 调用
    void *weakJavaPlayer;
    // JNI方法管理器
    SkyMediaPlayerMethod methodManager_;

    PlayerState playerState = STATE_IDLE;

    // 消息队列
    SkyMessageQueue messageQueue_;
    // 消息处理回调
    void handleMessage(const SkyMessage& message);

    // 添加设置播放器状态的方法
    void setPlayerState(PlayerState state);

    // 添加销毁标志
    std::atomic<bool> isDestroyed_{false};
};

SkyPlayer* createSkyPlayer();
void setSkyPlayerWeakJavaPlayer(SkyPlayer *player, void *weakJavaPlayer);

void setupFfmpegLogCallback() noexcept;

#endif //MY_PLAYER_SKYMEDIAPLAYER_H