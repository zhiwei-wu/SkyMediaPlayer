#include "sky_hw_decoder.h"
#include "logger.h"

#ifdef __ANDROID__
#include "sky_mediacodec_decoder.h"
#endif

static const char *TAG = "SkyHWDecoder";

std::unique_ptr<SkyHWDecoder> SkyHWDecoder::create() {
#ifdef __ANDROID__
    ALOG_I(TAG, "Creating MediaCodec hardware decoder");
    return std::make_unique<SkyMediaCodecDecoder>();
#elif defined(__APPLE__)
    // iOS VideoToolbox 解码器（未来实现）
    ALOG_W(TAG, "VideoToolbox decoder not yet implemented");
    return nullptr;
#else
    ALOG_W(TAG, "No hardware decoder available for this platform");
    return nullptr;
#endif
}
