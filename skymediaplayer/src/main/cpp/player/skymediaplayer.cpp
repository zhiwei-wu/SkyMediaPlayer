extern "C" {
#include "libavutil/pixdesc.h"
#include "libavutil/log.h"
}

#include "logger.h"
#include "skymediaplayer.h"
#include "ffplay.h"
#include "skymediaplayer_interface.h"

extern bool postEventToJava(SkyPlayer* player, int what, int arg1, int arg2, jobject obj);

namespace {
    std::once_flag ffmpegLogInitFlag;
}

SkyPlayer* createSkyPlayer() {
    FUNC_TRACE()

    // 日志输出
    // std::call_once(ffmpegLogInitFlag, setupFfmpegLogCallback);

    auto* player = new SkyPlayer();
    player->getSkyVideoOutHandler().setSkyRenderer(std::make_unique<SkyEGL2Renderer>());
    return player;
}

void setSkyPlayerWeakJavaPlayer(SkyPlayer *player, void *weakJavaPlayer) {
    FUNC_TRACE()
    if (nullptr == player) {
        ALOG_E(TAG, "player == null");
        return;
    }

    player->setWeakJavaPlayerPtr(weakJavaPlayer);
}

bool sky_display_image(void *player, AVFrame *frame) {
    if (nullptr == player) {
        ALOG_E(TAG, "sky_display_image() player == null");
        return false;
    }

    auto* skyPlayer = reinterpret_cast<SkyPlayer*>(player);

    bool ret = skyPlayer->getSkyVideoOutHandler().displayImage(frame);
    if (!ret) {
        ALOG_E(TAG, "=== displayImage FAILED === for frame %dx%d format=%s",
               frame->width, frame->height,
               av_get_pix_fmt_name(static_cast<AVPixelFormat>(frame->format)));
    }
    if (ret && !skyPlayer->firstVideoFrameRendered) {
        skyPlayer->firstVideoFrameRendered = true;
        skyPlayer->postMessage(SKY_MSG_VIDEO_RENDERING_START);
    }
    return ret;
}

bool sky_open_audio(void *player, SkyAudioSpec *desired, SkyAudioSpec *obtained) {
    if (nullptr == player) {
        ALOG_E(TAG, "sky_open_audio() player == null");
        return false;
    }

    if (nullptr == desired) {
        ALOG_E(TAG, "sky_open_audio() desired == null");
        return false;
    }

    auto* skyPlayer = reinterpret_cast<SkyPlayer*>(player);
    auto& skyAudioOutHandler = skyPlayer->getSkyAudioOutHandler();

    ALOG_I(TAG, "sky_open_audio() attempting to open: %d channels, %d Hz",
           desired->sdl_audioSpec.channels, desired->sdl_audioSpec.freq);

    if (skyAudioOutHandler.openAudio(AudioOutType::OPENSL_ES, desired, obtained)) {
        ALOG_I(TAG, "sky_open_audio() openAudio success: %d channels, %d Hz",
               obtained ? obtained->sdl_audioSpec.channels : desired->sdl_audioSpec.channels,
               obtained ? obtained->sdl_audioSpec.freq : desired->sdl_audioSpec.freq);
        return true;
    }

    ALOG_W(TAG, "sky_open_audio() openAudio failed for %d channels, %d Hz",
           desired->sdl_audioSpec.channels, desired->sdl_audioSpec.freq);
    return false;
}

void sky_pause_audio(void *player, bool pause) {
    if (nullptr == player) {
        ALOG_E(TAG, "sky_pause_audio() player == null");
        return;
    }

    auto* skyPlayer = reinterpret_cast<SkyPlayer*>(player);
    auto& skyAudioOutHandler = skyPlayer->getSkyAudioOutHandler();

    skyAudioOutHandler.pauseAudio(pause);

    ALOG_I(TAG, "sky_pause_audio() %s", pause ? "paused" : "resumed");
}

void sky_flush_audio(void *player) {
    if (nullptr == player) {
        ALOG_E(TAG, "sky_flush_audio() player == null");
        return;
    }

    auto* skyPlayer = reinterpret_cast<SkyPlayer*>(player);
    auto& skyAudioOutHandler = skyPlayer->getSkyAudioOutHandler();

    skyAudioOutHandler.flushAudio();

    ALOG_I(TAG, "sky_flush_audio() audio buffers flushed");
}

// ============================================================================
// Message Sending Interface Implementation
// ============================================================================

bool sky_post_message(void *player, int what, int arg1, int arg2, void *obj) {
    if (nullptr == player) {
        ALOG_E(TAG, "sky_post_message() player == null");
        return false;
    }

    auto* skyPlayer = reinterpret_cast<SkyPlayer*>(player);

    ALOG_D(TAG, "sky_post_message() what=%d, arg1=%d, arg2=%d, obj=%p",
           what, arg1, arg2, obj);

    return skyPlayer->postMessage(what, arg1, arg2, obj);
}

bool sky_post_simple_message(void *player, int what) {
    if (nullptr == player) {
        ALOG_E(TAG, "sky_post_simple_message() player == null");
        return false;
    }

    auto* skyPlayer = reinterpret_cast<SkyPlayer*>(player);

    ALOG_D(TAG, "sky_post_simple_message() what=%d", what);

    return skyPlayer->postMessage(what);
}

bool sky_post_message_ii(void *player, int what, int arg1, int arg2) {
    if (nullptr == player) {
        ALOG_E(TAG, "sky_post_message_ii() player == null");
        return false;
    }

    auto* skyPlayer = reinterpret_cast<SkyPlayer*>(player);

    ALOG_D(TAG, "sky_post_message_ii() what=%d, arg1=%d, arg2=%d",
           what, arg1, arg2);

    return skyPlayer->postMessage(what, arg1, arg2);
}

// ============================================================================
// SkyVideoOutHandler Implementation
// ============================================================================

void SkyVideoOutHandler::setWindow(EGLNativeWindowType window) {
    FUNC_TRACE()
    if (window_ == window) {
        ALOG_W(TAG, "duplicate set window");
        return;
    }

    std::lock_guard<std::mutex> lock(mtx);

    // release old resources
    if (nullptr != window_) {
        releaseResources();
    }

    if (nullptr != window) {
        ANativeWindow_acquire(window);
        window_ = window;

        // 重新创建渲染器，确保Surface重建后能正常渲染
        if (!renderer_) {
            ALOG_I(TAG, "Creating new renderer for window");
            renderer_ = std::make_unique<SkyEGL2Renderer>();
        }

        ALOG_I(TAG, "Window set successfully, renderer ready: %s", renderer_ ? "true" : "false");
    } else {
        ALOG_W(TAG, "setWindow called with null window");
    }
}

void SkyVideoOutHandler::releaseResources() {
    // 注意：这里不需要加锁，因为调用方已经加锁了

    ALOG_I(TAG, "SkyVideoOutHandler releasing resources");

    // 只释放与当前Surface相关的渲染资源，但保留渲染器实例
    if (renderer_) {
        ALOG_I(TAG, "Terminating renderer for current surface");
        renderer_->terminate();
        // 注意：不要reset渲染器，保留实例以便重用
    }

    // 释放 window 资源
    if (window_) {
        ANativeWindow_release(window_);
        window_ = nullptr;
        ALOG_I(TAG, "Released ANativeWindow");
    }

    ALOG_I(TAG, "SkyVideoOutHandler resources released (renderer preserved)");
}

bool SkyVideoOutHandler::displayImage(AVFrame *frame) {
    std::lock_guard<std::mutex> lock(mtx);

    // 检查渲染器是否存在
    if (!renderer_) {
        ALOG_E(TAG, "displayImage() but renderer_ == null");
        return false;
    }

    // 检查窗口是否存在
    if (!window_) {
        ALOG_E(TAG, "displayImage() but window_ == null");
        return false;
    }

    // 尝试渲染
    bool result = renderer_->displayImage(window_, frame);
    if (!result) {
        ALOG_W(TAG, "displayImage() failed for frame %dx%d format=%s",
               frame->width, frame->height,
               av_get_pix_fmt_name(static_cast<AVPixelFormat>(frame->format)));
    }

    return result;
}

// ============================================================================
// SkyAudioOutHandler Implementation
// ============================================================================

std::unique_ptr<SkyAudioOut> SkyAudioOutHandler::createAudioOutInstance(AudioOutType type) {
    std::unique_ptr<SkyAudioOut> audioOut;
    switch (type) {
        case AudioOutType::ANDROID_AUDIO_TRACK:
            audioOut = nullptr;
            break;
        case AudioOutType::OPENSL_ES:
            audioOut = std::make_unique<SkySLESAudioOut>();
            break;
        default:
            ALOG_E(TAG, "unknown audio out type");
            audioOut = nullptr;
            break;
    }

    return audioOut;
}

bool SkyAudioOutHandler::openAudio(const AudioOutType audioOutType, SkyAudioSpec *desired,
                                   SkyAudioSpec *obtained) {
    if (skyAudioOut_) {
        skyAudioOut_->closeAudio();
        ALOG_E(TAG, "openAudio() skyAudioOut_ != null");
        skyAudioOut_ = nullptr;
    }

    skyAudioOut_ = createAudioOutInstance(audioOutType);
    if (nullptr == skyAudioOut_) {
        ALOG_E(TAG, "createAudioOutInstance failed");
        return false;
    }

    return skyAudioOut_->openAudio(desired, obtained);
}

void SkyAudioOutHandler::pauseAudio(bool pause) {
    std::lock_guard<std::mutex> lock(mtx);
    if (skyAudioOut_) {
        skyAudioOut_->pauseAudio(pause ? 1 : 0);
    }
}

void SkyAudioOutHandler::flushAudio() {
    std::lock_guard<std::mutex> lock(mtx);
    if (skyAudioOut_) {
        skyAudioOut_->flushAudio();
    }
}

void SkyAudioOutHandler::cleanup() {
    std::lock_guard<std::mutex> lock(mtx);
    ALOG_I(TAG, "SkyAudioOutHandler cleanup starting");
    if (skyAudioOut_) {
        skyAudioOut_->closeAudio();
        skyAudioOut_.reset();
    }
    ALOG_I(TAG, "SkyAudioOutHandler cleanup completed");
}

// ============================================================================
// SkyPlayer Implementation
// ============================================================================

SkyPlayer::SkyPlayer()
    : data_source_(nullptr)
    , is(nullptr)
    , weakJavaPlayer(nullptr)
    , playerState(STATE_IDLE)
    , isDestroyed_(false) {
}

SkyPlayer::~SkyPlayer() {
    cleanup();
}

void SkyPlayer::cleanup() {
    if (isDestroyed_.exchange(true)) {
        return; // 防止重复清理
    }

    ALOG_I(TAG, "SkyPlayer cleanup starting");

    // 1. 停止消息队列
    messageQueue_.abort();
    messageQueue_.destroy();

    // 2. 停止播放并清理 ffplay 资源
    if (is) {
        stream_close(is);
        is = nullptr;
    }

    // 3. 释放视频输出资源
    skyVideoOutHandler_.releaseResources();

    // 4. 释放音频输出资源
    skyAudioOutHandler_.cleanup();

    // 5. 释放数据源
    if (data_source_) {
        delete[] data_source_;
        data_source_ = nullptr;
    }

    ALOG_I(TAG, "SkyPlayer cleanup completed");
}

const char* SkyPlayer::getDataSource() const {
    // const方法中不使用mutex，因为只是简单的指针读取
    return data_source_;
}

// data_source 访问方法实现
void SkyPlayer::setDataSource(const char* path) {
    std::lock_guard<std::mutex> lock(mtx);

    // 释放之前的内存
    if (data_source_) {
        delete[] data_source_;
        data_source_ = nullptr;
    }

    if (path) {
        // 分配新内存并复制字符串
        size_t len = strlen(path) + 1;
        data_source_ = new char[len];
        strcpy(data_source_, path);
        setPlayerState(STATE_INITIALIZED);
    }
}

void SkyPlayer::start() {
    std::lock_guard<std::mutex> lock(mtx);
    if (is && (playerState == STATE_PREPARED || playerState == STATE_PAUSED)) {
        // 调用ffplay.c的控制函数
        toggle_pause(is);
        setPlayerState(STATE_STARTED);
        onPlaybackStateChanged(STATE_STARTED);
    }
}

void SkyPlayer::pause() {
    std::lock_guard<std::mutex> lock(mtx);
    if (is && playerState == STATE_STARTED) {
        toggle_pause(is);
        setPlayerState(STATE_PAUSED);
        onPlaybackStateChanged(STATE_PAUSED);
    }
}

void SkyPlayer::seekTo(int64_t pos) {
    std::lock_guard<std::mutex> lock(mtx);
    if (is && (playerState == STATE_STARTED || playerState == STATE_PAUSED)) {
        // 调用ffplay.c的seek函数
        stream_seek(is, pos * 1000, 0, 0);
    }
}

void SkyPlayer::prepareAsync() {
    std::lock_guard<std::mutex> lock(mtx);
    if (playerState == STATE_INITIALIZED && data_source_) {
        setPlayerState(STATE_ASYNC_PREPARING);

        // 在VideoState中设置skyPlayer指针
        is = stream_open(data_source_, nullptr);
        if (is) {
            is->skyPlayer = this;  // 关键：建立C到C++的连接
            setPlayerState(STATE_PREPARED);

            // 启动消息队列
            messageQueue_.start(std::bind(&SkyPlayer::handleMessage, this, std::placeholders::_1));
            ALOG_I(TAG, "prepareAsync() success, message queue started");

            onPlaybackStateChanged(STATE_PREPARED);
        } else {
            setPlayerState(STATE_ERROR);
            ALOG_E(TAG, "prepareAsync() error");
        }
    }
}

bool SkyPlayer::postMessage(const SkyMessage& message) {
    return messageQueue_.put(message);
}

bool SkyPlayer::postMessage(int what, int arg1, int arg2, void* obj) {
    SkyMessage message(what, arg1, arg2, obj);
    return messageQueue_.put(message);
}

void SkyPlayer::setPlayerState(PlayerState state) {
    ALOG_I(TAG, "setPlayerState() from=%d -> %d", playerState, state);
    bool stateChanged = playerState != state;
    playerState = state;
    if (stateChanged) {
        onPlaybackStateChanged(state);
    }
}

// 向Java层发送事件的便捷方法实现
bool SkyPlayer::postEventToJava(int what, int arg1, int arg2, void* obj) {
    return ::postEventToJava(this, what, arg1, arg2, static_cast<jobject>(obj));
}

void SkyPlayer::handleMessage(const SkyMessage& message) {

    switch (message.what) {
        case SKY_MSG_PREPARED:
            ALOG_I(TAG, "handleMessage() SKY_MSG_PREPARED");
            {
                std::lock_guard<std::mutex> lock(mtx);
                setPlayerState(STATE_PREPARED);
            }
            postMediaEventToJava(MEDIA_EVENT_TYPE::MEDIA_PREPARED);
            break;

        case SKY_MSG_COMPLETED:
            ALOG_I(TAG, "handleMessage() SKY_MSG_COMPLETED");
            {
                std::lock_guard<std::mutex> lock(mtx);
                restart = true;
                setPlayerState(STATE_COMPLETED);
            }
            postMediaEventToJava(MEDIA_EVENT_TYPE::MEDIA_PLAYBACK_COMPLETE);
            break;

        case SKY_MSG_SEEK_COMPLETE:
            ALOG_I(TAG, "handleMessage() SKY_MSG_SEEK_COMPLETE");
            {
                std::lock_guard<std::mutex> lock(mtx);
                is->seek_req = 0;
            }
            postMediaEventToJava(MEDIA_EVENT_TYPE::MEDIA_SEEK_COMPLETE);
            break;
        case SKY_MSG_REQ_START:
            ALOG_I(TAG, "handleMessage() SKY_MSG_REQ_START");
            break;

        case SKY_MSG_VIDEO_SIZE_CHANGED:
            ALOG_I(TAG, "handleMessage() SKY_MSG_VIDEO_SIZE_CHANGED");
            postMediaEventToJava(MEDIA_EVENT_TYPE::MEDIA_SET_VIDEO_SIZE, message.arg1, message.arg2);
            break;

        case SKY_MSG_SAR_CHANGED:
            ALOG_I(TAG, "handleMessage() SKY_MSG_SAR_CHANGED");
            postMediaEventToJava(MEDIA_EVENT_TYPE::MEDIA_SET_VIDEO_SAR, message.arg1, message.arg2);
            break;

        case SKY_MSG_VIDEO_RENDERING_START:
            ALOG_I(TAG, "handleMessage() SKY_MSG_VIDEO_DECODED_START");
            postMediaEventToJava(MEDIA_EVENT_TYPE::MEDIA_INFO, static_cast<int>(MEDIA_INFO_TYPE::MEDIA_INFO_VIDEO_SEEK_RENDERING_START));
            break;

        case SKY_MSG_VIDEO_DECODED_START:
            ALOG_I(TAG, "handleMessage() SKY_MSG_VIDEO_DECODED_START");
            postMediaEventToJava(MEDIA_EVENT_TYPE::MEDIA_INFO, static_cast<int>(MEDIA_INFO_TYPE::MEDIA_INFO_VIDEO_DECODED_START));
            break;

        case SKY_MSG_OPEN_INPUT:
            ALOG_I(TAG, "handleMessage() SKY_MSG_OPEN_INPUT");
            postMediaEventToJava(MEDIA_EVENT_TYPE::MEDIA_INFO, static_cast<int>(MEDIA_INFO_TYPE::MEDIA_INFO_OPEN_INPUT));
            break;

        case SKY_MSG_FIND_STREAM_INFO:
            ALOG_I(TAG, "handleMessage() SKY_MSG_FIND_STREAM_INFO");
            postMediaEventToJava(MEDIA_EVENT_TYPE::MEDIA_INFO, static_cast<int>(MEDIA_INFO_TYPE::MEDIA_INFO_FIND_STREAM_INFO));
            break;

        case SKY_MSG_COMPONENT_OPEN:
            ALOG_I(TAG, "handleMessage() SKY_MSG_COMPONENT_OPEN");
            postMediaEventToJava(MEDIA_EVENT_TYPE::MEDIA_INFO, static_cast<int>(MEDIA_INFO_TYPE::MEDIA_INFO_COMPONENT_OPEN));
            break;

        case SKY_MSG_COMPONENT_OPEN_ERR:
            ALOG_I(TAG, "handleMessage() SKY_MSG_COMPONENT_OPEN_ERR, arg1=%d, arg2=%d", message.arg1, message.arg2);
            postMediaEventToJava(MEDIA_EVENT_TYPE::MEDIA_ERROR, static_cast<int>(MEDIA_INFO_TYPE::MEDIA_INFO_COMPONENT_OPEN_ERR));
            break;

        case SKY_MSG_ERROR:
            ALOG_E(TAG, "handleMessage() SKY_MSG_ERROR arg1=%d, arg2=%d", message.arg1, message.arg2);
            {
                std::lock_guard<std::mutex> lock(mtx);
                setPlayerState(STATE_ERROR);
            }
            postMediaEventToJava(MEDIA_EVENT_TYPE::MEDIA_ERROR, message.arg1, message.arg2);
            break;

        default:
            ALOG_W(TAG, "handleMessage() unknown message what=%d", message.what);
            break;
    }
}

void SkyPlayer::onPlaybackStateChanged(int state) {
    postMediaEventToJava(MEDIA_EVENT_TYPE::MEDIA_INFO, state);
}

bool SkyPlayer::isPlaying() {
    std::lock_guard<std::mutex> lock(mtx);
    return playerState == STATE_PREPARED|| playerState == STATE_STARTED;
}

int64_t SkyPlayer::getCurrentPosition() {
    std::lock_guard<std::mutex> lock(mtx);
    if (!is || !is->ic) {
        return 0;
    }

    // 获取当前播放时钟位置（秒）
    double pos_seconds = get_current_position(is);
    if (isnan(pos_seconds) || pos_seconds < 0) {
        return 0;
    }

    // 转换为毫秒
    return (int64_t)(pos_seconds * 1000);
}

int64_t SkyPlayer::getDuration() {
    std::lock_guard<std::mutex> lock(mtx);
    if (!is) {
        return 0;
    }

    // 获取媒体文件总时长（微秒）
    int64_t duration_us = get_media_duration(is);
    if (duration_us == AV_NOPTS_VALUE || duration_us <= 0) {
        return 0;
    }

    // 转换为毫秒
    return duration_us / 1000;
}

void SkyPlayer::stop() {

}

namespace {

constexpr int mapFfmpegLogLevelToAndroid(int ffmpegLevel) noexcept {
    if (ffmpegLevel <= AV_LOG_ERROR) {
        return ANDROID_LOG_ERROR;
    }
    if (ffmpegLevel == AV_LOG_WARNING) {
        return ANDROID_LOG_WARN;
    }
    if (ffmpegLevel == AV_LOG_INFO) {
        return ANDROID_LOG_INFO;
    }
    if (ffmpegLevel >= AV_LOG_VERBOSE) {
        return ANDROID_LOG_DEBUG;
    }
    return ANDROID_LOG_INFO;
}

void androidAvLogCallback(void* ptr, int level, const char* fmt, va_list vl) noexcept {
    const int androidLogLevel = mapFfmpegLogLevelToAndroid(level);
    __android_log_vprint(androidLogLevel, "FFmpeg", fmt, vl);
}

}

void setupFfmpegLogCallback() noexcept {
    av_log_set_level(AV_LOG_QUIET);
    av_log_set_callback(androidAvLogCallback);
}