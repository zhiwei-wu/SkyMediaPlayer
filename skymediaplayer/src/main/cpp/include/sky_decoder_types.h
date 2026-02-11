#ifndef SKY_DECODER_TYPES_H
#define SKY_DECODER_TYPES_H

/**
 * 视频解码模式
 * 用于在运行时选择不同的解码策略
 * 枚举值与 Java/Kotlin 层保持一致
 *
 * 跨平台设计：
 *   Android: HW_SURFACE/HW_BUFFER 使用 MediaCodec (NDK AMediaCodec API)
 *   iOS:     HW_SURFACE/HW_BUFFER 使用 VideoToolbox (未来实现)
 */

/* C 兼容的解码模式常量（供 ffplay.c 等 C 代码使用） */
#define SKY_DECODER_MODE_HW_SURFACE  0
#define SKY_DECODER_MODE_HW_BUFFER   1
#define SKY_DECODER_MODE_SOFTWARE    2
#define SKY_DECODER_MODE_AUTO        3

#ifdef __cplusplus

enum class DecoderMode {
    HW_SURFACE = SKY_DECODER_MODE_HW_SURFACE,   // 硬解 + Surface 直渲（零拷贝，性能最优）
    HW_BUFFER  = SKY_DECODER_MODE_HW_BUFFER,    // 硬解 + Buffer 输出（取出 NV12 帧，可后处理）
    SOFTWARE   = SKY_DECODER_MODE_SOFTWARE,      // FFmpeg 纯软解（兼容性最好，CPU 开销大）
    AUTO       = SKY_DECODER_MODE_AUTO           // 自动选择：HW_SURFACE → HW_BUFFER → SOFTWARE 三级回退
};

#endif // __cplusplus

#endif // SKY_DECODER_TYPES_H
