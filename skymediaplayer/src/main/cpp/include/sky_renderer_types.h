#ifndef SKY_RENDERER_TYPES_H
#define SKY_RENDERER_TYPES_H

/**
 * 渲染后端类型枚举
 * 用于在运行时选择不同的图形 API 进行视频渲染
 * 枚举值与 Java/Kotlin 层保持一致
 */
enum class RendererBackend {
    OPENGL_ES = 0,   // OpenGL ES 2.0 (当前默认)
    VULKAN    = 1,   // Vulkan 1.0+ (待实现)
    METAL     = 2,   // Metal (iOS/macOS, 预留)
    AUTO      = 3    // 自动选择最优后端
};

#endif // SKY_RENDERER_TYPES_H
