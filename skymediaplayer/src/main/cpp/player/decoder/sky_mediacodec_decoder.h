#ifndef SKY_MEDIACODEC_DECODER_H
#define SKY_MEDIACODEC_DECODER_H

#ifdef __ANDROID__

#include "sky_hw_decoder.h"
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <android/native_window.h>
#include <atomic>
#include <string>
#include <vector>

/**
 * Android MediaCodec 硬件解码器实现
 *
 * 使用 NDK AMediaCodec API，支持两种输出模式：
 *   - Surface 模式：零拷贝直渲到 ANativeWindow
 *   - Buffer 模式：取出 NV12/YUV420P 帧数据到 CPU 内存
 *
 * 内部手动实现 AvcC/HvcC → Annex-B 格式转换，
 * 将 length-prefixed NAL 单元转换为 start-code-prefixed 格式以兼容 MediaCodec。
 *
 * 三级回退策略：
 *   1. 尝试 Surface 模式 configure
 *   2. 失败则尝试 Buffer 模式 configure
 *   3. 都失败则返回 false，上层回退到 FFmpeg 软解
 */
class SkyMediaCodecDecoder : public SkyHWDecoder {
public:
    SkyMediaCodecDecoder();
    ~SkyMediaCodecDecoder() override;

    bool configure(AVCodecParameters *codecpar, void *surface) override;
    bool start() override;
    int sendPacket(AVPacket *packet) override;
    int receiveFrame(AVFrame *frame) override;
    int dequeueFrame(AVFrame *frame) override;
    bool renderOutputBuffer() override;
    void flush() override;
    void stop() override;
    void release() override;
    DecoderMode getActiveMode() const override;
    bool isSurfaceMode() const override;
    const char* getName() const override;

private:
    static const char* codecIdToMime(AVCodecID codecId);

    AMediaFormat* buildMediaFormat(AVCodecParameters *codecpar);

    bool tryConfigureSurface(AVCodecParameters *codecpar, ANativeWindow *window);

    bool tryConfigureBuffer(AVCodecParameters *codecpar);

    int fillFrameFromBuffer(AVFrame *frame, uint8_t *bufferData, size_t bufferSize,
                            AMediaCodecBufferInfo *bufferInfo, int outputIndex);

    /**
     * 检测 extradata 是否为 AvcC/HvcC 格式（需要转换）
     */
    bool isAvccFormat(const uint8_t *extradata, int extradataSize) const;

    /**
     * 解析 AvcC 格式的 extradata，提取 SPS/PPS 并转换为 Annex-B 格式
     * 输出的 csd 数据包含 start code + SPS + start code + PPS
     */
    bool parseAvccExtradata(const uint8_t *extradata, int extradataSize,
                            std::vector<uint8_t> &spsAnnexB, std::vector<uint8_t> &ppsAnnexB);

    /**
     * 解析 HvcC 格式的 extradata，提取 VPS/SPS/PPS 并转换为 Annex-B 格式
     */
    bool parseHvccExtradata(const uint8_t *extradata, int extradataSize,
                            std::vector<uint8_t> &csdAnnexB);

    /**
     * 将 length-prefixed packet 数据转换为 Annex-B 格式（原地替换 start code）
     * 返回转换后的数据（可能与输入共享内存，也可能重新分配）
     */
    bool convertPacketToAnnexB(const uint8_t *srcData, int srcSize,
                               std::vector<uint8_t> &dstData);

private:
    AMediaCodec *codec_ = nullptr;
    AMediaFormat *inputFormat_ = nullptr;
    ANativeWindow *surface_ = nullptr;

    DecoderMode activeMode_ = DecoderMode::SOFTWARE;
    std::atomic<bool> isStarted_{false};
    std::atomic<bool> isConfigured_{false};
    std::atomic<bool> isEndOfStream_{false};

    int32_t width_ = 0;
    int32_t height_ = 0;
    int32_t colorFormat_ = 0;
    int32_t stride_ = 0;
    int32_t sliceHeight_ = 0;

    std::string mimeType_;
    AVCodecID codecId_ = AV_CODEC_ID_NONE;

    int nalLengthSize_ = 4;
    bool needsAnnexBConversion_ = false;

    // Surface 模式两步渲染：dequeueFrame 保存 outputIndex，renderOutputBuffer 渲染
    ssize_t pendingOutputIndex_ = -1;
    bool hasPendingFrame_ = false;
};

#endif // __ANDROID__

#endif // SKY_MEDIACODEC_DECODER_H
