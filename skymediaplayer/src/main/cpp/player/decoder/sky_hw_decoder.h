#ifndef SKY_HW_DECODER_H
#define SKY_HW_DECODER_H

#include <memory>
#include <string>
#include "sky_decoder_types.h"

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavutil/frame.h"
}

/**
 * 硬件解码器抽象基类
 *
 * 跨平台设计：
 *   - SkyMediaCodecDecoder (Android) — 使用 NDK AMediaCodec API
 *   - SkyVideoToolboxDecoder (iOS)   — 使用 VTDecompressionSession（未来）
 *
 * 生命周期：
 *   configure() → start() → [sendPacket() / receiveFrame()] → flush() → stop() → release()
 */
class SkyHWDecoder {
public:
    virtual ~SkyHWDecoder() = default;

    /**
     * 配置硬件解码器
     * @param codecpar 编解码器参数（从 FFmpeg AVStream 获取）
     * @param surface  平台原生窗口指针（Android: ANativeWindow*, iOS: nil）
     *                 Surface 模式下必须提供，Buffer 模式下传 nullptr
     * @return true 配置成功
     */
    virtual bool configure(AVCodecParameters *codecpar, void *surface) = 0;

    /**
     * 启动解码器
     * @return true 启动成功
     */
    virtual bool start() = 0;

    /**
     * 向解码器投喂压缩数据包
     * @param packet FFmpeg 数据包（包含压缩的视频数据）
     * @return 0 成功, 负值错误, EAGAIN 需要先取出帧
     */
    virtual int sendPacket(AVPacket *packet) = 0;

    /**
     * 从解码器获取解码后的帧
     * @param frame 输出帧（Buffer 模式下填充 NV12 数据，Surface 模式下帧已直接渲染）
     * @return 0 成功, 负值错误, EAGAIN 需要先投喂数据
     */
    virtual int receiveFrame(AVFrame *frame) = 0;

    /**
     * 从解码器取出帧但不渲染（仅 Surface 模式使用）
     * 取出帧的元数据（PTS、宽高），但不调用 releaseOutputBuffer 渲染到 Surface。
     * 调用方需要在同步等待后调用 renderOutputBuffer() 完成渲染。
     * Buffer 模式下行为与 receiveFrame 相同。
     * @param frame 输出帧（仅填充元数据，不含像素数据）
     * @return 0 成功, 负值错误, EAGAIN 需要先投喂数据
     */
    virtual int dequeueFrame(AVFrame *frame) = 0;

    /**
     * 将已取出的帧渲染到 Surface（仅 Surface 模式使用）
     * 必须在 dequeueFrame() 返回 0 之后调用。
     * Buffer 模式下此方法为空操作。
     * @return true 渲染成功, false 无待渲染帧或渲染失败
     */
    virtual bool renderOutputBuffer() = 0;

    /**
     * 刷新解码器（Seek 时调用）
     */
    virtual void flush() = 0;

    /**
     * 停止解码器
     */
    virtual void stop() = 0;

    /**
     * 释放所有资源
     */
    virtual void release() = 0;

    /**
     * 获取当前解码模式
     */
    virtual DecoderMode getActiveMode() const = 0;

    /**
     * 是否处于 Surface 直渲模式
     */
    virtual bool isSurfaceMode() const = 0;

    /**
     * 获取解码器名称（用于日志）
     */
    virtual const char* getName() const = 0;

    /**
     * 工厂方法：根据平台创建硬件解码器实例
     * @return 平台对应的硬件解码器，不支持时返回 nullptr
     */
    static std::unique_ptr<SkyHWDecoder> create();
};

#endif // SKY_HW_DECODER_H
