#ifdef __ANDROID__

#include "sky_mediacodec_decoder.h"
#include "logger.h"
#include <cstring>
#include <unistd.h>

extern "C" {
#include "libavutil/pixfmt.h"
}

// Annex-B start code: 00 00 00 01
static const uint8_t ANNEX_B_START_CODE[] = {0x00, 0x00, 0x00, 0x01};

static const char *TAG = "SkyMediaCodecDecoder";

// MediaCodec 超时时间（微秒）
static constexpr int64_t DEQUEUE_TIMEOUT_US = 10000;  // 10ms

// Android MediaCodec COLOR_Format 常量（来自 MediaCodecInfo.CodecCapabilities）
static constexpr int32_t MC_COLOR_FormatYUV420Planar        = 19;   // I420: Y + U + V 三平面
static constexpr int32_t MC_COLOR_FormatYUV420PackedPlanar  = 20;   // 同 19，packed 变体
static constexpr int32_t MC_COLOR_FormatYUV420SemiPlanar    = 21;   // NV12: Y + UV 交错
static constexpr int32_t MC_COLOR_FormatYUV420PackedSemiPlanar = 39; // 同 21，packed 变体
static constexpr int32_t MC_COLOR_FormatNV21                = 23;   // NV21: Y + VU 交错
static constexpr int32_t MC_COLOR_FormatYUV420Flexible      = 0x7F420888; // 灵活格式，需动态判断

SkyMediaCodecDecoder::SkyMediaCodecDecoder() {
    ALOG_I(TAG, "SkyMediaCodecDecoder created");
}

SkyMediaCodecDecoder::~SkyMediaCodecDecoder() {
    release();
}

const char* SkyMediaCodecDecoder::codecIdToMime(AVCodecID codecId) {
    switch (codecId) {
        case AV_CODEC_ID_H264:
            return "video/avc";
        case AV_CODEC_ID_HEVC:
            return "video/hevc";
        case AV_CODEC_ID_VP8:
            return "video/x-vnd.on2.vp8";
        case AV_CODEC_ID_VP9:
            return "video/x-vnd.on2.vp9";
        case AV_CODEC_ID_AV1:
            return "video/av01";
        case AV_CODEC_ID_MPEG4:
            return "video/mp4v-es";
        case AV_CODEC_ID_MPEG2VIDEO:
            return "video/mpeg2";
        default:
            return nullptr;
    }
}

bool SkyMediaCodecDecoder::isAvccFormat(const uint8_t *extradata, int extradataSize) const {
    if (!extradata || extradataSize < 7) {
        return false;
    }
    // AvcC 格式: byte[0] = configurationVersion = 1
    // Annex-B 格式: 以 00 00 00 01 或 00 00 01 开头
    if ((extradata[0] == 0x00 && extradata[1] == 0x00 && extradata[2] == 0x00 && extradata[3] == 0x01) ||
        (extradata[0] == 0x00 && extradata[1] == 0x00 && extradata[2] == 0x01)) {
        return false;
    }
    // AvcC: version == 1
    if (extradata[0] == 1) {
        return true;
    }
    return false;
}

bool SkyMediaCodecDecoder::parseAvccExtradata(const uint8_t *extradata, int extradataSize,
                                               std::vector<uint8_t> &spsAnnexB,
                                               std::vector<uint8_t> &ppsAnnexB) {
    if (!extradata || extradataSize < 7) {
        ALOG_E(TAG, "AvcC extradata too short: %d", extradataSize);
        return false;
    }

    // AvcC 结构:
    // byte[0]: configurationVersion = 1
    // byte[1]: AVCProfileIndication
    // byte[2]: profile_compatibility
    // byte[3]: AVCLevelIndication
    // byte[4]: (lengthSizeMinusOne & 0x03) | 0xFC  -> NAL length size = (byte[4] & 0x03) + 1
    // byte[5]: (numOfSPS & 0x1F) | 0xE0
    // 然后是 SPS 列表: 每个 SPS 前有 2 字节大端长度
    // 然后是 numOfPPS (1 byte)
    // 然后是 PPS 列表: 每个 PPS 前有 2 字节大端长度

    nalLengthSize_ = (extradata[4] & 0x03) + 1;
    ALOG_I(TAG, "AvcC: NAL length size = %d", nalLengthSize_);

    int offset = 5;
    int numSps = extradata[offset] & 0x1F;
    offset++;

    ALOG_I(TAG, "AvcC: numSPS = %d", numSps);

    // 解析 SPS
    for (int i = 0; i < numSps; i++) {
        if (offset + 2 > extradataSize) {
            ALOG_E(TAG, "AvcC: SPS length overflow at offset %d", offset);
            return false;
        }
        int spsLen = (extradata[offset] << 8) | extradata[offset + 1];
        offset += 2;

        if (offset + spsLen > extradataSize) {
            ALOG_E(TAG, "AvcC: SPS data overflow, len=%d, remaining=%d", spsLen, extradataSize - offset);
            return false;
        }

        // start code + SPS data
        spsAnnexB.insert(spsAnnexB.end(), ANNEX_B_START_CODE, ANNEX_B_START_CODE + 4);
        spsAnnexB.insert(spsAnnexB.end(), extradata + offset, extradata + offset + spsLen);
        ALOG_I(TAG, "AvcC: SPS[%d] len=%d, nalType=0x%02X", i, spsLen, extradata[offset] & 0x1F);
        offset += spsLen;
    }

    // 解析 PPS
    if (offset >= extradataSize) {
        ALOG_E(TAG, "AvcC: no PPS data at offset %d", offset);
        return false;
    }
    int numPps = extradata[offset];
    offset++;

    ALOG_I(TAG, "AvcC: numPPS = %d", numPps);

    for (int i = 0; i < numPps; i++) {
        if (offset + 2 > extradataSize) {
            ALOG_E(TAG, "AvcC: PPS length overflow at offset %d", offset);
            return false;
        }
        int ppsLen = (extradata[offset] << 8) | extradata[offset + 1];
        offset += 2;

        if (offset + ppsLen > extradataSize) {
            ALOG_E(TAG, "AvcC: PPS data overflow, len=%d, remaining=%d", ppsLen, extradataSize - offset);
            return false;
        }

        // start code + PPS data
        ppsAnnexB.insert(ppsAnnexB.end(), ANNEX_B_START_CODE, ANNEX_B_START_CODE + 4);
        ppsAnnexB.insert(ppsAnnexB.end(), extradata + offset, extradata + offset + ppsLen);
        ALOG_I(TAG, "AvcC: PPS[%d] len=%d, nalType=0x%02X", i, ppsLen, extradata[offset] & 0x1F);
        offset += ppsLen;
    }

    return !spsAnnexB.empty() && !ppsAnnexB.empty();
}

bool SkyMediaCodecDecoder::parseHvccExtradata(const uint8_t *extradata, int extradataSize,
                                               std::vector<uint8_t> &csdAnnexB) {
    if (!extradata || extradataSize < 23) {
        ALOG_E(TAG, "HvcC extradata too short: %d", extradataSize);
        return false;
    }

    // HvcC 结构:
    // byte[0]: configurationVersion = 1
    // byte[21]: (lengthSizeMinusOne & 0x03) | ...  -> NAL length size = (byte[21] & 0x03) + 1
    // byte[22]: numOfArrays
    // 然后是 NAL 数组列表，每个数组:
    //   byte[0]: (array_completeness << 7) | (reserved << 6) | nalUnitType
    //   byte[1..2]: numNalus (大端)
    //   然后是 numNalus 个 NAL 单元，每个:
    //     byte[0..1]: nalUnitLength (大端)
    //     byte[2..]: NAL 数据

    nalLengthSize_ = (extradata[21] & 0x03) + 1;
    int numArrays = extradata[22];
    int offset = 23;

    ALOG_I(TAG, "HvcC: NAL length size = %d, numArrays = %d", nalLengthSize_, numArrays);

    for (int arrayIdx = 0; arrayIdx < numArrays; arrayIdx++) {
        if (offset + 3 > extradataSize) {
            ALOG_E(TAG, "HvcC: array header overflow at offset %d", offset);
            return false;
        }

        uint8_t nalType = extradata[offset] & 0x3F;
        int numNalus = (extradata[offset + 1] << 8) | extradata[offset + 2];
        offset += 3;

        ALOG_I(TAG, "HvcC: array[%d] nalType=%d, numNalus=%d", arrayIdx, nalType, numNalus);

        for (int naluIdx = 0; naluIdx < numNalus; naluIdx++) {
            if (offset + 2 > extradataSize) {
                ALOG_E(TAG, "HvcC: NALU length overflow at offset %d", offset);
                return false;
            }
            int naluLen = (extradata[offset] << 8) | extradata[offset + 1];
            offset += 2;

            if (offset + naluLen > extradataSize) {
                ALOG_E(TAG, "HvcC: NALU data overflow, len=%d, remaining=%d",
                       naluLen, extradataSize - offset);
                return false;
            }

            // start code + NAL data
            csdAnnexB.insert(csdAnnexB.end(), ANNEX_B_START_CODE, ANNEX_B_START_CODE + 4);
            csdAnnexB.insert(csdAnnexB.end(), extradata + offset, extradata + offset + naluLen);
            offset += naluLen;
        }
    }

    return !csdAnnexB.empty();
}

bool SkyMediaCodecDecoder::convertPacketToAnnexB(const uint8_t *srcData, int srcSize,
                                                  std::vector<uint8_t> &dstData) {
    dstData.clear();
    // 预分配：最坏情况下大小不变（4字节 length → 4字节 start code）
    dstData.reserve(srcSize);

    int offset = 0;
    while (offset + nalLengthSize_ <= srcSize) {
        // 读取 NAL 长度（大端）
        uint32_t nalLength = 0;
        for (int i = 0; i < nalLengthSize_; i++) {
            nalLength = (nalLength << 8) | srcData[offset + i];
        }
        offset += nalLengthSize_;

        if (nalLength == 0 || offset + (int)nalLength > srcSize) {
            ALOG_W(TAG, "Invalid NAL length %u at offset %d, srcSize=%d",
                   nalLength, offset, srcSize);
            break;
        }

        // 写入 4 字节 Annex-B start code + NAL 数据
        dstData.insert(dstData.end(), ANNEX_B_START_CODE, ANNEX_B_START_CODE + 4);
        dstData.insert(dstData.end(), srcData + offset, srcData + offset + nalLength);
        offset += nalLength;
    }

    return !dstData.empty();
}

AMediaFormat* SkyMediaCodecDecoder::buildMediaFormat(AVCodecParameters *codecpar) {
    const char *mime = codecIdToMime(codecpar->codec_id);
    if (!mime) {
        ALOG_E(TAG, "Unsupported codec ID: %d", codecpar->codec_id);
        return nullptr;
    }

    mimeType_ = mime;
    codecId_ = codecpar->codec_id;

    AMediaFormat *format = AMediaFormat_new();
    if (!format) {
        ALOG_E(TAG, "Failed to create AMediaFormat");
        return nullptr;
    }

    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, mime);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, codecpar->width);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, codecpar->height);

    // 解析 extradata 并设置 CSD（Codec Specific Data）
    needsAnnexBConversion_ = false;
    if (codecpar->extradata && codecpar->extradata_size > 0) {
        if (codecpar->codec_id == AV_CODEC_ID_H264 &&
            isAvccFormat(codecpar->extradata, codecpar->extradata_size)) {
            // AvcC 格式 → 解析提取 SPS/PPS 并转为 Annex-B
            std::vector<uint8_t> spsAnnexB, ppsAnnexB;
            if (parseAvccExtradata(codecpar->extradata, codecpar->extradata_size,
                                   spsAnnexB, ppsAnnexB)) {
                AMediaFormat_setBuffer(format, "csd-0", spsAnnexB.data(), spsAnnexB.size());
                AMediaFormat_setBuffer(format, "csd-1", ppsAnnexB.data(), ppsAnnexB.size());
                needsAnnexBConversion_ = true;
                ALOG_I(TAG, "Set csd-0 (SPS Annex-B): size=%zu, csd-1 (PPS Annex-B): size=%zu",
                       spsAnnexB.size(), ppsAnnexB.size());
            } else {
                ALOG_W(TAG, "Failed to parse AvcC extradata, using raw as csd-0");
                AMediaFormat_setBuffer(format, "csd-0", codecpar->extradata,
                                       codecpar->extradata_size);
            }
        } else if (codecpar->codec_id == AV_CODEC_ID_HEVC &&
                   isAvccFormat(codecpar->extradata, codecpar->extradata_size)) {
            // HvcC 格式 → 解析提取 VPS/SPS/PPS 并转为 Annex-B
            std::vector<uint8_t> csdAnnexB;
            if (parseHvccExtradata(codecpar->extradata, codecpar->extradata_size, csdAnnexB)) {
                AMediaFormat_setBuffer(format, "csd-0", csdAnnexB.data(), csdAnnexB.size());
                needsAnnexBConversion_ = true;
                ALOG_I(TAG, "Set csd-0 (HEVC Annex-B): size=%zu", csdAnnexB.size());
            } else {
                ALOG_W(TAG, "Failed to parse HvcC extradata, using raw as csd-0");
                AMediaFormat_setBuffer(format, "csd-0", codecpar->extradata,
                                       codecpar->extradata_size);
            }
        } else {
            // 已经是 Annex-B 格式或其他编解码器，直接设置
            AMediaFormat_setBuffer(format, "csd-0", codecpar->extradata,
                                   codecpar->extradata_size);
            ALOG_I(TAG, "Set csd-0 (raw/Annex-B): size=%d", codecpar->extradata_size);
        }
    }

    // 设置最大输入大小
    if (codecpar->width > 0 && codecpar->height > 0) {
        int32_t maxInputSize = codecpar->width * codecpar->height;
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, maxInputSize);
    }

    width_ = codecpar->width;
    height_ = codecpar->height;

    ALOG_I(TAG, "Built MediaFormat: mime=%s, width=%d, height=%d, annexb_convert=%s",
           mime, codecpar->width, codecpar->height,
           needsAnnexBConversion_ ? "yes" : "no");

    return format;
}

bool SkyMediaCodecDecoder::tryConfigureSurface(AVCodecParameters *codecpar, ANativeWindow *window) {
    ALOG_I(TAG, "Trying Surface mode configuration");

    AMediaFormat *format = buildMediaFormat(codecpar);
    if (!format) {
        return false;
    }

    AMediaCodec *codec = AMediaCodec_createDecoderByType(mimeType_.c_str());
    if (!codec) {
        ALOG_E(TAG, "Failed to create MediaCodec decoder for %s", mimeType_.c_str());
        AMediaFormat_delete(format);
        return false;
    }

    media_status_t status = AMediaCodec_configure(codec, format, window, nullptr, 0);
    if (status != AMEDIA_OK) {
        ALOG_W(TAG, "Surface mode configure failed: %d, will try Buffer mode", status);
        AMediaCodec_delete(codec);
        AMediaFormat_delete(format);
        return false;
    }

    // Surface 模式配置成功
    codec_ = codec;
    inputFormat_ = format;
    surface_ = window;
    activeMode_ = DecoderMode::HW_SURFACE;
    isConfigured_ = true;

    ALOG_I(TAG, "Surface mode configured successfully");
    return true;
}

bool SkyMediaCodecDecoder::tryConfigureBuffer(AVCodecParameters *codecpar) {
    ALOG_I(TAG, "Trying Buffer mode configuration");

    AMediaFormat *format = buildMediaFormat(codecpar);
    if (!format) {
        return false;
    }

    AMediaCodec *codec = AMediaCodec_createDecoderByType(mimeType_.c_str());
    if (!codec) {
        ALOG_E(TAG, "Failed to create MediaCodec decoder for %s", mimeType_.c_str());
        AMediaFormat_delete(format);
        return false;
    }

    // Buffer 模式：surface 参数传 nullptr
    media_status_t status = AMediaCodec_configure(codec, format, nullptr, nullptr, 0);
    if (status != AMEDIA_OK) {
        ALOG_E(TAG, "Buffer mode configure failed: %d", status);
        AMediaCodec_delete(codec);
        AMediaFormat_delete(format);
        return false;
    }

    // Buffer 模式配置成功
    codec_ = codec;
    inputFormat_ = format;
    surface_ = nullptr;
    activeMode_ = DecoderMode::HW_BUFFER;
    isConfigured_ = true;

    ALOG_I(TAG, "Buffer mode configured successfully");
    return true;
}

bool SkyMediaCodecDecoder::configure(AVCodecParameters *codecpar, void *surface) {
    if (!codecpar) {
        ALOG_E(TAG, "configure() codecpar is null");
        return false;
    }

    // 检查是否支持该编解码器
    const char *mime = codecIdToMime(codecpar->codec_id);
    if (!mime) {
        ALOG_W(TAG, "Codec ID %d not supported by MediaCodec", codecpar->codec_id);
        return false;
    }

    // 释放之前的资源
    release();

    auto *window = static_cast<ANativeWindow *>(surface);

    // 三级回退策略：
    // 1. 如果提供了 Surface，先尝试 Surface 模式
    if (window) {
        if (tryConfigureSurface(codecpar, window)) {
            return true;
        }
        ALOG_W(TAG, "Surface mode failed, falling back to Buffer mode");
    }

    // 2. 尝试 Buffer 模式
    if (tryConfigureBuffer(codecpar)) {
        return true;
    }

    // 3. 都失败，返回 false，上层回退到 FFmpeg 软解
    ALOG_E(TAG, "All hardware decode modes failed for codec %s", mime);
    return false;
}

bool SkyMediaCodecDecoder::start() {
    if (!isConfigured_ || !codec_) {
        ALOG_E(TAG, "start() called but decoder not configured");
        return false;
    }

    media_status_t status = AMediaCodec_start(codec_);
    if (status != AMEDIA_OK) {
        ALOG_E(TAG, "AMediaCodec_start failed: %d", status);
        return false;
    }

    isStarted_ = true;
    isEndOfStream_ = false;
    ALOG_I(TAG, "MediaCodec started, mode=%s",
           activeMode_ == DecoderMode::HW_SURFACE ? "SURFACE" : "BUFFER");
    return true;
}

int SkyMediaCodecDecoder::sendPacket(AVPacket *packet) {
    if (!isStarted_ || !codec_) {
        return AVERROR(EINVAL);
    }

    bool isEos = (packet == nullptr || packet->size == 0);

    if (isEos) {
        // EOS 不需要经过 bitstream filter
        ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec_, DEQUEUE_TIMEOUT_US);
        if (inputIndex < 0) {
            if (inputIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
                return AVERROR(EAGAIN);
            }
            ALOG_E(TAG, "dequeueInputBuffer failed (EOS): %zd", inputIndex);
            return AVERROR_EXTERNAL;
        }
        AMediaCodec_queueInputBuffer(codec_, inputIndex, 0, 0, 0,
                                      AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
        isEndOfStream_ = true;
        ALOG_I(TAG, "Sent EOS to MediaCodec");
        return 0;
    }

    // 将 AvcC/HvcC 格式的 packet 转换为 Annex-B 格式
    const uint8_t *sendData = packet->data;
    int sendSize = packet->size;
    std::vector<uint8_t> annexBBuffer;

    if (needsAnnexBConversion_) {
        if (!convertPacketToAnnexB(packet->data, packet->size, annexBBuffer)) {
            ALOG_E(TAG, "Failed to convert packet to Annex-B, size=%d", packet->size);
            return AVERROR_EXTERNAL;
        }
        sendData = annexBBuffer.data();
        sendSize = static_cast<int>(annexBBuffer.size());
    }

    // 获取输入 buffer 索引
    ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec_, DEQUEUE_TIMEOUT_US);
    if (inputIndex < 0) {
        if (inputIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
            return AVERROR(EAGAIN);
        }
        ALOG_E(TAG, "dequeueInputBuffer failed: %zd", inputIndex);
        return AVERROR_EXTERNAL;
    }

    // 获取输入 buffer
    size_t inputBufferSize = 0;
    uint8_t *inputBuffer = AMediaCodec_getInputBuffer(codec_, inputIndex, &inputBufferSize);
    if (!inputBuffer) {
        ALOG_E(TAG, "getInputBuffer returned null");
        return AVERROR_EXTERNAL;
    }

    // 检查 buffer 大小是否足够
    size_t copySize = sendSize;
    if (copySize > inputBufferSize) {
        ALOG_W(TAG, "Packet size %zu exceeds input buffer size %zu, truncating",
               copySize, inputBufferSize);
        copySize = inputBufferSize;
    }

    // 复制 Annex-B 数据到输入 buffer
    memcpy(inputBuffer, sendData, copySize);

    // PTS（保持原始 time_base 单位，由上层处理）
    int64_t presentationTimeUs = 0;
    if (packet->pts != AV_NOPTS_VALUE) {
        presentationTimeUs = packet->pts;
    }

    // 提交输入 buffer
    media_status_t status = AMediaCodec_queueInputBuffer(
            codec_, inputIndex, 0, copySize, presentationTimeUs, 0);

    if (status != AMEDIA_OK) {
        ALOG_E(TAG, "queueInputBuffer failed: %d", status);
        return AVERROR_EXTERNAL;
    }

    return 0;
}

/**
 * 将 MediaCodec colorFormat 映射到 FFmpeg AVPixelFormat
 * 支持 Android MediaCodec 常见的 YUV420 输出格式
 */
static AVPixelFormat mapColorFormatToPixFmt(int32_t colorFormat) {
    switch (colorFormat) {
        case MC_COLOR_FormatYUV420Planar:
        case MC_COLOR_FormatYUV420PackedPlanar:
            return AV_PIX_FMT_YUV420P;

        case MC_COLOR_FormatYUV420SemiPlanar:
        case MC_COLOR_FormatYUV420PackedSemiPlanar:
        case MC_COLOR_FormatYUV420Flexible:
            return AV_PIX_FMT_NV12;

        case MC_COLOR_FormatNV21:
            return AV_PIX_FMT_NV21;

        default:
            ALOG_W("SkyMediaCodecDecoder",
                   "Unknown colorFormat %d (0x%X), defaulting to NV12", colorFormat, colorFormat);
            return AV_PIX_FMT_NV12;
    }
}

static const char* pixFmtName(AVPixelFormat fmt) {
    switch (fmt) {
        case AV_PIX_FMT_YUV420P: return "YUV420P";
        case AV_PIX_FMT_NV12:    return "NV12";
        case AV_PIX_FMT_NV21:    return "NV21";
        default:                  return "UNKNOWN";
    }
}

int SkyMediaCodecDecoder::fillFrameFromBuffer(AVFrame *frame, uint8_t *bufferData,
                                               size_t bufferSize,
                                               AMediaCodecBufferInfo *bufferInfo,
                                               int outputIndex) {
    // 获取输出格式以确定颜色格式和 stride
    AMediaFormat *outputFormat = AMediaCodec_getOutputFormat(codec_);
    if (outputFormat) {
        AMediaFormat_getInt32(outputFormat, AMEDIAFORMAT_KEY_WIDTH, &width_);
        AMediaFormat_getInt32(outputFormat, AMEDIAFORMAT_KEY_HEIGHT, &height_);
        AMediaFormat_getInt32(outputFormat, AMEDIAFORMAT_KEY_COLOR_FORMAT, &colorFormat_);
        AMediaFormat_getInt32(outputFormat, "stride", &stride_);
        AMediaFormat_getInt32(outputFormat, "slice-height", &sliceHeight_);
        AMediaFormat_delete(outputFormat);
    }

    if (stride_ <= 0) {
        stride_ = width_;
    }
    if (sliceHeight_ <= 0) {
        sliceHeight_ = height_;
    }

    // 根据 MediaCodec 输出的 colorFormat 动态映射到 AVPixelFormat
    AVPixelFormat pixFmt = mapColorFormatToPixFmt(colorFormat_);

    {
        static int formatLogCounter = 0;
        if (formatLogCounter++ % 300 == 0) {
            ALOG_I(TAG, "fillFrameFromBuffer: colorFormat=%d (0x%X) → %s, %dx%d, stride=%d, sliceHeight=%d",
                   colorFormat_, colorFormat_, pixFmtName(pixFmt),
                   width_, height_, stride_, sliceHeight_);
        }
    }

    // 分配 AVFrame
    frame->format = pixFmt;
    frame->width = width_;
    frame->height = height_;

    int ret = av_frame_get_buffer(frame, 0);
    if (ret < 0) {
        ALOG_E(TAG, "av_frame_get_buffer failed: %d", ret);
        return ret;
    }

    uint8_t *srcY = bufferData + bufferInfo->offset;
    int uvHeight = (height_ + 1) / 2;

    // 复制 Y 平面（所有 YUV420 格式共用）
    for (int row = 0; row < height_; row++) {
        memcpy(frame->data[0] + row * frame->linesize[0],
               srcY + row * stride_,
               width_);
    }

    if (pixFmt == AV_PIX_FMT_YUV420P) {
        // YUV420P (I420): Y + U + V 三个独立平面
        // MediaCodec 布局: Y[stride * sliceHeight] + U[stride/2 * sliceHeight/2] + V[stride/2 * sliceHeight/2]
        int uvStride = stride_ / 2;
        int uvSliceHeight = sliceHeight_ / 2;
        uint8_t *srcU = srcY + stride_ * sliceHeight_;
        uint8_t *srcV = srcU + uvStride * uvSliceHeight;
        int uvWidth = (width_ + 1) / 2;

        for (int row = 0; row < uvHeight; row++) {
            memcpy(frame->data[1] + row * frame->linesize[1],
                   srcU + row * uvStride,
                   uvWidth);
            memcpy(frame->data[2] + row * frame->linesize[2],
                   srcV + row * uvStride,
                   uvWidth);
        }
    } else {
        // NV12 / NV21: Y + UV(或VU) 交错平面
        // MediaCodec 布局: Y[stride * sliceHeight] + UV[stride * sliceHeight/2]
        uint8_t *srcUV = srcY + stride_ * sliceHeight_;

        for (int row = 0; row < uvHeight; row++) {
            memcpy(frame->data[1] + row * frame->linesize[1],
                   srcUV + row * stride_,
                   width_);
        }
    }

    // 设置 PTS
    frame->pts = bufferInfo->presentationTimeUs;

    return 0;
}

int SkyMediaCodecDecoder::receiveFrame(AVFrame *frame) {
    if (!isStarted_ || !codec_) {
        return AVERROR(EINVAL);
    }

    AMediaCodecBufferInfo bufferInfo;
    ssize_t outputIndex = AMediaCodec_dequeueOutputBuffer(codec_, &bufferInfo, DEQUEUE_TIMEOUT_US);

    if (outputIndex >= 0) {
        // 检查是否是 EOS
        if (bufferInfo.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
            AMediaCodec_releaseOutputBuffer(codec_, outputIndex, false);
            ALOG_I(TAG, "Received EOS from MediaCodec");
            return AVERROR_EOF;
        }

        if (isSurfaceMode()) {
            // Surface 模式：直接渲染到 Surface，不需要取出帧数据
            // render=true 表示将此 buffer 渲染到 Surface
            AMediaCodec_releaseOutputBuffer(codec_, outputIndex, true);

            // 设置帧的基本信息（供上层使用 PTS 等）
            frame->width = width_;
            frame->height = height_;
            frame->format = AV_PIX_FMT_NV12;  // 标记格式，虽然数据已直接渲染
            frame->pts = bufferInfo.presentationTimeUs;

            return 0;
        } else {
            // Buffer 模式：从输出 buffer 取出帧数据
            size_t outputBufferSize = 0;
            uint8_t *outputBuffer = AMediaCodec_getOutputBuffer(codec_, outputIndex,
                                                                 &outputBufferSize);
            if (!outputBuffer) {
                ALOG_E(TAG, "getOutputBuffer returned null");
                AMediaCodec_releaseOutputBuffer(codec_, outputIndex, false);
                return AVERROR_EXTERNAL;
            }

            int ret = fillFrameFromBuffer(frame, outputBuffer, outputBufferSize,
                                           &bufferInfo, outputIndex);

            // 释放输出 buffer（Buffer 模式不渲染到 Surface）
            AMediaCodec_releaseOutputBuffer(codec_, outputIndex, false);

            return ret;
        }
    } else if (outputIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
        return AVERROR(EAGAIN);
    } else if (outputIndex == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
        ALOG_I(TAG, "Output buffers changed");
        return AVERROR(EAGAIN);
    } else if (outputIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
        AMediaFormat *newFormat = AMediaCodec_getOutputFormat(codec_);
        if (newFormat) {
            AMediaFormat_getInt32(newFormat, AMEDIAFORMAT_KEY_WIDTH, &width_);
            AMediaFormat_getInt32(newFormat, AMEDIAFORMAT_KEY_HEIGHT, &height_);
            AMediaFormat_getInt32(newFormat, AMEDIAFORMAT_KEY_COLOR_FORMAT, &colorFormat_);
            AMediaFormat_getInt32(newFormat, "stride", &stride_);
            AMediaFormat_getInt32(newFormat, "slice-height", &sliceHeight_);
            ALOG_D(TAG, "Output format changed: %dx%d, color=%d, stride=%d, sliceHeight=%d",
                   width_, height_, colorFormat_, stride_, sliceHeight_);
            AMediaFormat_delete(newFormat);
        }
        return AVERROR(EAGAIN);
    } else {
        ALOG_E(TAG, "dequeueOutputBuffer unexpected result: %zd", outputIndex);
        return AVERROR_EXTERNAL;
    }
}

int SkyMediaCodecDecoder::dequeueFrame(AVFrame *frame) {
    if (!isStarted_ || !codec_) {
        return AVERROR(EINVAL);
    }

    // 如果已有未渲染的 pending 帧，先释放它（不渲染）
    if (hasPendingFrame_) {
        ALOG_W(TAG, "dequeueFrame: releasing previous unreleased pending frame (index=%zd)",
               pendingOutputIndex_);
        AMediaCodec_releaseOutputBuffer(codec_, pendingOutputIndex_, false);
        hasPendingFrame_ = false;
        pendingOutputIndex_ = -1;
    }

    AMediaCodecBufferInfo bufferInfo;
    ssize_t outputIndex = AMediaCodec_dequeueOutputBuffer(codec_, &bufferInfo, DEQUEUE_TIMEOUT_US);

    if (outputIndex >= 0) {
        // 检查是否是 EOS
        if (bufferInfo.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
            AMediaCodec_releaseOutputBuffer(codec_, outputIndex, false);
            ALOG_I(TAG, "dequeueFrame: Received EOS from MediaCodec");
            return AVERROR_EOF;
        }

        if (isSurfaceMode()) {
            // Surface 模式：只取元数据，不渲染，保存 outputIndex 等待 renderOutputBuffer()
            frame->width = width_;
            frame->height = height_;
            frame->format = AV_PIX_FMT_NV12;
            frame->pts = bufferInfo.presentationTimeUs;

            pendingOutputIndex_ = outputIndex;
            hasPendingFrame_ = true;

            return 0;
        } else {
            // Buffer 模式：行为与 receiveFrame 相同
            size_t outputBufferSize = 0;
            uint8_t *outputBuffer = AMediaCodec_getOutputBuffer(codec_, outputIndex,
                                                                 &outputBufferSize);
            if (!outputBuffer) {
                ALOG_E(TAG, "dequeueFrame: getOutputBuffer returned null");
                AMediaCodec_releaseOutputBuffer(codec_, outputIndex, false);
                return AVERROR_EXTERNAL;
            }

            int ret = fillFrameFromBuffer(frame, outputBuffer, outputBufferSize,
                                           &bufferInfo, outputIndex);

            AMediaCodec_releaseOutputBuffer(codec_, outputIndex, false);
            return ret;
        }
    } else if (outputIndex == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
        return AVERROR(EAGAIN);
    } else if (outputIndex == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
        ALOG_I(TAG, "dequeueFrame: Output buffers changed");
        return AVERROR(EAGAIN);
    } else if (outputIndex == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
        AMediaFormat *newFormat = AMediaCodec_getOutputFormat(codec_);
        if (newFormat) {
            AMediaFormat_getInt32(newFormat, AMEDIAFORMAT_KEY_WIDTH, &width_);
            AMediaFormat_getInt32(newFormat, AMEDIAFORMAT_KEY_HEIGHT, &height_);
            AMediaFormat_getInt32(newFormat, AMEDIAFORMAT_KEY_COLOR_FORMAT, &colorFormat_);
            AMediaFormat_getInt32(newFormat, "stride", &stride_);
            AMediaFormat_getInt32(newFormat, "slice-height", &sliceHeight_);
            ALOG_D(TAG, "dequeueFrame: Output format changed: %dx%d, color=%d, stride=%d, sliceHeight=%d",
                   width_, height_, colorFormat_, stride_, sliceHeight_);
            AMediaFormat_delete(newFormat);
        }
        return AVERROR(EAGAIN);
    } else {
        ALOG_E(TAG, "dequeueFrame: dequeueOutputBuffer unexpected result: %zd", outputIndex);
        return AVERROR_EXTERNAL;
    }
}

bool SkyMediaCodecDecoder::renderOutputBuffer() {
    if (!hasPendingFrame_ || !codec_) {
        return false;
    }

    // 将 pending 帧渲染到 Surface
    AMediaCodec_releaseOutputBuffer(codec_, pendingOutputIndex_, true);

    hasPendingFrame_ = false;
    pendingOutputIndex_ = -1;
    return true;
}

void SkyMediaCodecDecoder::flush() {
    if (!isStarted_ || !codec_) {
        return;
    }

    // flush 前释放未渲染的 pending 帧
    if (hasPendingFrame_) {
        AMediaCodec_releaseOutputBuffer(codec_, pendingOutputIndex_, false);
        hasPendingFrame_ = false;
        pendingOutputIndex_ = -1;
    }

    media_status_t status = AMediaCodec_flush(codec_);
    if (status != AMEDIA_OK) {
        ALOG_E(TAG, "AMediaCodec_flush failed: %d", status);
    } else {
        isEndOfStream_ = false;
        ALOG_I(TAG, "MediaCodec flushed");
    }
}

void SkyMediaCodecDecoder::stop() {
    if (!isStarted_ || !codec_) {
        return;
    }

    media_status_t status = AMediaCodec_stop(codec_);
    if (status != AMEDIA_OK) {
        ALOG_E(TAG, "AMediaCodec_stop failed: %d", status);
    }

    isStarted_ = false;
    ALOG_I(TAG, "MediaCodec stopped");
}

void SkyMediaCodecDecoder::release() {
    // release 前释放未渲染的 pending 帧
    if (hasPendingFrame_ && codec_) {
        AMediaCodec_releaseOutputBuffer(codec_, pendingOutputIndex_, false);
        hasPendingFrame_ = false;
        pendingOutputIndex_ = -1;
    }

    if (codec_) {
        if (isStarted_) {
            stop();
        }
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
        ALOG_I(TAG, "MediaCodec released");
    }

    if (inputFormat_) {
        AMediaFormat_delete(inputFormat_);
        inputFormat_ = nullptr;
    }

    // 注意：不释放 surface_，它由外部管理（SkyVideoOutHandler）
    surface_ = nullptr;
    isConfigured_ = false;
    isStarted_ = false;
    isEndOfStream_ = false;
    activeMode_ = DecoderMode::SOFTWARE;
    codecId_ = AV_CODEC_ID_NONE;
    nalLengthSize_ = 4;
    needsAnnexBConversion_ = false;
    hasPendingFrame_ = false;
    pendingOutputIndex_ = -1;
}

DecoderMode SkyMediaCodecDecoder::getActiveMode() const {
    return activeMode_;
}

bool SkyMediaCodecDecoder::isSurfaceMode() const {
    return activeMode_ == DecoderMode::HW_SURFACE;
}

const char* SkyMediaCodecDecoder::getName() const {
    return "MediaCodecDecoder";
}

#endif // __ANDROID__
