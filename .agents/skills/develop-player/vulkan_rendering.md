# SkyPlayer Vulkan 视频渲染

## 概述

SkyPlayer 支持 Vulkan 1.1 作为视频渲染后端，与 OpenGL ES 2.0 并列提供硬件加速渲染能力。Vulkan 渲染器通过 SPIR-V 预编译着色器实现 YUV→RGB 色彩空间转换，支持多种像素格式，并针对不同 Android GPU 厂商（Qualcomm Adreno、ARM Mali 等）做了兼容性适配。

## 核心文件

| 文件 | 位置 | 作用 |
|------|------|------|
| `sky_vk_renderer.h` | `skymediaplayer/src/main/cpp/player/` | Vulkan 渲染器头文件，类定义和接口声明 |
| `sky_vk_renderer.cpp` | `skymediaplayer/src/main/cpp/player/` | Vulkan 渲染器实现，包含完整渲染管线 |
| `sky_vk_shaders.h` | `skymediaplayer/src/main/cpp/player/` | SPIR-V 预编译着色器字节码 |
| `skyrenderer.cpp` | `skymediaplayer/src/main/cpp/player/` | 渲染器管理层，负责 OpenGL/Vulkan 切换 |

## 渲染器架构

```
┌─────────────────────────────────────────────────────────────┐
│  skyrenderer.cpp (渲染器管理层)                              │
│  sky_display_image() → 选择 OpenGL 或 Vulkan 渲染器         │
└─────────────────────────┬───────────────────────────────────┘
                          │
          ┌───────────────┴───────────────┐
          ▼                               ▼
┌──────────────────────┐    ┌──────────────────────────┐
│  SkyEGL2Renderer     │    │  SkyVkRenderer           │
│  (OpenGL ES 2.0)     │    │  (Vulkan 1.1)            │
│                      │    │                          │
│  - EGL 上下文管理     │    │  - Vulkan Instance       │
│  - GLSL 着色器       │    │  - Swapchain 管理        │
│  - glTexImage2D      │    │  - SPIR-V 着色器         │
│  - eglSwapBuffers    │    │  - Staging Buffer 上传   │
└──────────────────────┘    └──────────────────────────┘
```

## Vulkan 渲染管线

### 初始化流程

```
displayImage() [首次调用]
    ↓
createInstance()          → VkInstance（应用信息、Vulkan 1.1）
    ↓
createSurface()          → VkSurfaceKHR（ANativeWindow）
    ↓
pickPhysicalDevice()     → VkPhysicalDevice（GPU 选择）
    ↓
createLogicalDevice()    → VkDevice + VkQueue
    ↓
createSwapchain()        → VkSwapchainKHR（格式协商）
    ↓
createImageViews()       → VkImageView[]
    ↓
createRenderPass()       → VkRenderPass
    ↓
createCommandPool()      → VkCommandPool
    ↓
createSyncObjects()      → VkSemaphore + VkFence
    ↓
createCommandBuffers()   → VkCommandBuffer[]
    ↓
createVertexBuffer()     → 全屏四边形顶点数据
    ↓
createTextureResources() → YUV 纹理 + Sampler
    ↓
createDescriptorSets()   → 绑定纹理到着色器
    ↓
createGraphicsPipeline() → VkPipeline（选择对应 SPIR-V 着色器）
    ↓
createFramebuffers()     → VkFramebuffer[]
```

### 每帧渲染流程

```
displayImage(window, frame)
    ↓
检查像素格式变化 → 重建纹理和管线
    ↓
uploadTextureData()      → Staging Buffer → vkCmdCopyBufferToImage
    ↓                       (处理 linesize padding 对齐)
更新 Descriptor Sets     → 绑定新纹理到着色器
    ↓
vkAcquireNextImageKHR()  → 获取 swapchain image
    ↓
recordCommandBuffer()    → 录制渲染命令
    ↓
vkQueueSubmit()          → 提交 GPU 执行
    ↓
vkQueuePresentKHR()      → 呈现到屏幕
```

## 支持的像素格式

| 像素格式 | 纹理数量 | 纹理格式 | 着色器 |
|----------|----------|----------|--------|
| YUV420P | 3（Y, U, V） | R8_UNORM × 3 | fragmentShaderYUV420PSPIRV |
| NV12 | 2（Y, UV） | R8_UNORM + R8G8_UNORM | fragmentShaderNV12SPIRV |
| NV21 | 2（Y, VU） | R8_UNORM + R8G8_UNORM | fragmentShaderNV21SPIRV |
| RGBA | 1 | R8G8B8A8_UNORM | fragmentShaderRGBASPIRV |

## YUV→RGB 色彩空间转换

### BT.601 Limited Range 公式

FFmpeg 解码输出的 YUV 数据通常是 **limited range**（Y: 16-235, UV: 16-240），着色器中需要先做 limited→full range 缩放，再做 YUV→RGB 矩阵转换：

```glsl
// BT.601 limited range → full range RGB
void main() {
    // 1. Y 通道 limited range 缩放：Y=16 → 0.0（纯黑），Y=235 → 1.0（纯白）
    float y = (texture(yTexture, vTexCoord).r - 0.0627451) * 1.164384;
    float u = texture(uTexture, vTexCoord).r - 0.5;
    float v = texture(vTexture, vTexCoord).r - 0.5;

    // 2. BT.601 limited range 系数
    float r = y + 1.596027 * v;
    float g = y - 0.391762 * u - 0.812968 * v;
    float b = y + 2.017232 * u;

    // 3. clamp 防止溢出
    fragColor = vec4(clamp(r, 0.0, 1.0), clamp(g, 0.0, 1.0), clamp(b, 0.0, 1.0), 1.0);
}
```

**常量说明**：
| 常量 | 计算方式 | 说明 |
|------|----------|------|
| 0.0627451 | 16.0 / 255.0 | Y 通道偏移量 |
| 1.164384 | 255.0 / 219.0 | Y 通道缩放因子 |
| 1.596027 | 1.402 × 1.164384 × (255/224) | V→R 系数 |
| 0.391762 | 0.34414 × 1.164384 × (255/224) | U→G 系数 |
| 0.812968 | 0.71414 × 1.164384 × (255/224) | V→G 系数 |
| 2.017232 | 1.772 × 1.164384 × (255/224) | U→B 系数 |

### 与 OpenGL 渲染器的对比

| 特性 | OpenGL ES 2.0 | Vulkan 1.1 |
|------|---------------|------------|
| 着色器语言 | GLSL ES（运行时编译） | SPIR-V（预编译字节码） |
| YUV→RGB 方式 | 矩阵 uniform | 着色器内联常量 |
| 色彩范围 | BT.601 limited range | BT.601 limited range |
| 纹理上传 | glTexImage2D | Staging Buffer + vkCmdCopyBufferToImage |
| 缓冲区交换 | eglSwapBuffers | vkQueuePresentKHR |

## SPIR-V 着色器管理

### 着色器编译

GLSL 源码位于 `sky_vk_renderer.cpp` 顶部（作为备份参考），预编译的 SPIR-V 字节码存储在 `sky_vk_shaders.h` 中。

**编译命令**（使用 `glslc`）：

```bash
# 编译顶点着色器
glslc -fshader-stage=vertex vert.glsl -o vert.spv

# 编译片段着色器
glslc -fshader-stage=fragment frag_yuv420p.glsl -o frag_yuv420p.spv
glslc -fshader-stage=fragment frag_nv12.glsl -o frag_nv12.spv
glslc -fshader-stage=fragment frag_nv21.glsl -o frag_nv21.spv
glslc -fshader-stage=fragment frag_rgba.glsl -o frag_rgba.spv
```

**转换为 C 数组**：

```python
import struct

def spv_to_c_array(spv_file, array_name):
    with open(spv_file, 'rb') as f:
        data = f.read()
    words = struct.unpack(f'<{len(data)//4}I', data)
    lines = [f'static const uint32_t {array_name}[] = {{']
    for i in range(0, len(words), 7):
        chunk = words[i:i+7]
        lines.append('    ' + ','.join(f'0x{w:08x}' for w in chunk) + ',')
    lines.append('};')
    lines.append(f'static const size_t {array_name}Size = sizeof({array_name});')
    return '\n'.join(lines)
```

### 着色器选择逻辑

```cpp
// createGraphicsPipeline() 中根据像素格式选择片段着色器
const uint32_t* fragmentShaderCode;
size_t fragmentShaderSize;

switch (currentPixelFormat_) {
    case PixelFormatType::YUV420P:
        fragmentShaderCode = fragmentShaderYUV420PSPIRV;
        fragmentShaderSize = fragmentShaderYUV420PSPIRVSize;
        break;
    case PixelFormatType::NV12:
        fragmentShaderCode = fragmentShaderNV12SPIRV;
        fragmentShaderSize = fragmentShaderNV12SPIRVSize;
        break;
    case PixelFormatType::NV21:
        fragmentShaderCode = fragmentShaderNV21SPIRV;
        fragmentShaderSize = fragmentShaderNV21SPIRVSize;
        break;
    case PixelFormatType::RGBA:
        fragmentShaderCode = fragmentShaderRGBASPIRV;
        fragmentShaderSize = fragmentShaderRGBASPIRVSize;
        break;
}
```

## Swapchain 格式兼容性

### 问题背景

不同 Android GPU 厂商支持的 Vulkan swapchain 格式不同：
- **Qualcomm Adreno**：通常只提供 `R8G8B8A8_UNORM`（format=37）
- **ARM Mali**：通常提供 `B8G8R8A8_UNORM`（format=44）和 `B8G8R8A8_SRGB`
- **Samsung Xclipse**：两种格式都可能支持

### 格式选择策略

```cpp
VkSurfaceFormatKHR chooseSwapSurfaceFormat(
    const std::vector<VkSurfaceFormatKHR>& availableFormats) {
    // 优先级：R8G8B8A8_UNORM > B8G8R8A8_UNORM > R8G8B8A8_SRGB > B8G8R8A8_SRGB
    const VkFormat preferredFormats[] = {
        VK_FORMAT_R8G8B8A8_UNORM,   // Adreno 常见
        VK_FORMAT_B8G8R8A8_UNORM,   // Mali 常见
        VK_FORMAT_R8G8B8A8_SRGB,
        VK_FORMAT_B8G8R8A8_SRGB,
    };
    for (auto preferred : preferredFormats) {
        for (const auto& available : availableFormats) {
            if (available.format == preferred) {
                return available;
            }
        }
    }
    return availableFormats[0];
}
```

**选择 UNORM 而非 SRGB 的原因**：着色器输出的 YUV→RGB 转换结果本身就是 sRGB 空间的值（视频内容编码标准），使用 UNORM 格式可以避免 GPU 对输出值做额外的 sRGB gamma 编码，确保暗部（黑色）不会被提亮。

## 纹理上传与 linesize 处理

### FFmpeg linesize 对齐

FFmpeg 解码的 AVFrame 中，`linesize` 通常大于实际宽度（为了内存对齐），例如 1920 宽度的视频 `linesize[0]` 可能是 1920 或 1984。

```cpp
// uploadTextureData 中通过 bufferRowLength 处理 padding
VkBufferImageCopy region{};
region.bufferOffset = 0;
region.bufferRowLength = rowPitch;  // 使用 linesize 而非 width
region.bufferImageHeight = 0;
region.imageExtent = {width, height, 1};
```

### 纹理上传调用

```cpp
// YUV420P：3 个平面分别上传
uploadTextureData(yuvTextures_[0], frame->data[0],
    frame->linesize[0] * frame->height,
    frame->width, frame->height,
    frame->linesize[0], VK_FORMAT_R8_UNORM);

uploadTextureData(yuvTextures_[1], frame->data[1],
    frame->linesize[1] * frame->height / 2,
    frame->width / 2, frame->height / 2,
    frame->linesize[1], VK_FORMAT_R8_UNORM);

uploadTextureData(yuvTextures_[2], frame->data[2],
    frame->linesize[2] * frame->height / 2,
    frame->width / 2, frame->height / 2,
    frame->linesize[2], VK_FORMAT_R8_UNORM);
```

## Descriptor Sets 管理

### 关键注意事项

更新 `VkDescriptorImageInfo` 时，必须确保每个 descriptor write 指向**独立的** `VkDescriptorImageInfo` 实例，避免悬空指针导致所有纹理绑定到同一个 plane。

```cpp
// 正确写法：使用 vector 存储独立的 imageInfo
std::vector<VkDescriptorImageInfo> imageInfos(yuvTextures_.size());
std::vector<VkWriteDescriptorSet> descriptorWrites(yuvTextures_.size());

for (size_t j = 0; j < yuvTextures_.size(); j++) {
    imageInfos[j].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    imageInfos[j].imageView = yuvTextures_[j].imageView;
    imageInfos[j].sampler = yuvTextures_[j].sampler;

    descriptorWrites[j].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    descriptorWrites[j].dstSet = descriptorSets_[i];
    descriptorWrites[j].dstBinding = j;
    descriptorWrites[j].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    descriptorWrites[j].descriptorCount = 1;
    descriptorWrites[j].pImageInfo = &imageInfos[j];  // 指向独立实例
}

vkUpdateDescriptorSets(device_, descriptorWrites.size(),
                       descriptorWrites.data(), 0, nullptr);
```

**错误写法**（会导致画面灰白/偏色）：
```cpp
// 错误：循环内的局部变量 imageInfo 每次迭代被覆盖
// 所有 pImageInfo 指向同一个栈地址，最终全部绑定到最后一个纹理
for (size_t j = 0; j < yuvTextures_.size(); j++) {
    VkDescriptorImageInfo imageInfo{};  // ❌ 栈上局部变量
    imageInfo.imageView = yuvTextures_[j].imageView;
    writes[j].pImageInfo = &imageInfo;  // ❌ 悬空指针
}
```

## 关键技术点

| 技术点 | 说明 |
|--------|------|
| **Vulkan 1.1** | 最低 API 版本要求，兼容大多数 Android 设备 |
| **SPIR-V 预编译** | 避免运行时编译开销，着色器以 uint32_t 数组嵌入 |
| **Staging Buffer** | Host Visible 内存 → Device Local 内存的纹理上传 |
| **bufferRowLength** | 处理 FFmpeg linesize 对齐 padding |
| **UNORM 格式** | 避免 sRGB gamma 二次编码导致暗部提亮 |
| **BT.601 Limited Range** | 匹配 FFmpeg 解码输出的 YUV 值域 |
| **Descriptor Set 独立 ImageInfo** | 避免悬空指针导致多 plane 绑定到同一纹理 |
| **Swapchain 格式协商** | 兼容 Adreno（RGBA）和 Mali（BGRA）设备 |
| **compositeAlpha 适配** | 按优先级选择设备支持的合成模式 |
| **MAILBOX 呈现模式** | 优先使用低延迟呈现，回退到 FIFO |

## 日志调试

```bash
adb logcat | grep -E "SkyVkRenderer|sky_trace"
```

**正常初始化日志**：
```
SkyVkRenderer: Initializing Vulkan renderer
SkyVkRenderer: Vulkan instance created successfully
SkyVkRenderer: Surface created
SkyVkRenderer: Physical device selected
SkyVkRenderer: Logical device created
SkyVkRenderer: Selected swap format: 37
SkyVkRenderer: Swapchain created: 1080x607, format=37
SkyVkRenderer: Vertex buffer created
SkyVkRenderer: Pixel format changed: -1 -> 0
SkyVkRenderer: Texture resources created: 3 textures
SkyVkRenderer: Descriptor sets created
SkyVkRenderer: Graphics pipeline created
SkyVkRenderer: Texture size changed: 1x1 -> 1920x1080, recreating
```

**Gralloc 错误（可忽略）**：
```
qdgralloc: GetSize: Unrecognized pixel format: 0x38
Gralloc4: isSupported(1, 1, 56, 1, ...) failed with 5
```
这是 Qualcomm Adreno 驱动在探测 `B8G8R8A8` 格式时的副作用，不影响实际渲染。Swapchain 会成功使用 `R8G8B8A8_UNORM`（format=37）。

## 设备兼容性

| GPU 厂商 | 常见 Swapchain 格式 | 状态 |
|----------|---------------------|------|
| Qualcomm Adreno | R8G8B8A8_UNORM (37) | ✅ 已验证 |
| ARM Mali | B8G8R8A8_UNORM (44) | ✅ 已适配 |
| Samsung Xclipse | 两种均可能 | ✅ 已适配 |

## 常见问题排查

| 现象 | 原因 | 解决方案 |
|------|------|----------|
| 画面灰白色覆盖 | Descriptor Set 悬空指针，Y/U/V 全绑定到 V plane | 使用 vector 存储独立的 VkDescriptorImageInfo |
| 画面偏红 | 同上，Y 通道读到 V 的色度数据 | 同上 |
| 黑色变灰色 | YUV→RGB 使用 full range 公式，但数据是 limited range | 使用 BT.601 limited range 公式 |
| 画面整体偏亮 | Swapchain 使用 SRGB 格式导致 gamma 二次编码 | 优先选择 UNORM 格式 |
| Gralloc 报错 | Adreno 驱动探测不支持的格式 | 可忽略，不影响渲染 |
| 画面撕裂/花屏 | linesize 未正确处理 | 使用 bufferRowLength 传入 linesize |

## 屏幕旋转处理（preTransform）

### 问题背景

Android 设备屏幕旋转时，`VkSurfaceCapabilitiesKHR::currentTransform` 会变化（如竖屏 `IDENTITY`=0x1，横屏 `ROTATE_90`=0x2）。Vulkan 规范要求 swapchain 的 `preTransform` 匹配 `currentTransform`，否则 `vkQueuePresentKHR` 会返回 `VK_SUBOPTIMAL_KHR`。

### 设计决策：使用 IDENTITY preTransform

SkyPlayer 选择始终使用 `VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR` 作为 `preTransform`，**让 Android 合成器（SurfaceFlinger）处理旋转**，而不是在 Vulkan 渲染管线中手动旋转画面。

**原因**：
- 手动处理旋转需要修改投影矩阵/顶点坐标，增加复杂度
- 视频播放器不需要极致的合成性能，合成器旋转的开销可以接受
- 简化代码维护

### 关键修改点（共 6 处）

#### 1. createSwapchain：preTransform 设为 IDENTITY

```cpp
// createSwapchain() 中
if (capabilities.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) {
    createInfo.preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
} else {
    createInfo.preTransform = capabilities.currentTransform;
}
```

#### 2. chooseSwapExtent：使用 ANativeWindow 实际尺寸

使用 `IDENTITY` preTransform 时，`capabilities.currentExtent` 返回的是**未旋转的物理尺寸**（如横屏时返回 1080×1920 而非 1920×1080），与 `ANativeWindow_getWidth/Height` 返回的**已旋转尺寸**不一致。必须使用 `ANativeWindow` 的实际尺寸：

```cpp
VkExtent2D chooseSwapExtent(const VkSurfaceCapabilitiesKHR& capabilities) {
    VkExtent2D actualExtent;
    if (currentWindow_) {
        // 使用 ANativeWindow 的实际尺寸（已经过 Android 旋转处理）
        actualExtent.width = static_cast<uint32_t>(ANativeWindow_getWidth(currentWindow_));
        actualExtent.height = static_cast<uint32_t>(ANativeWindow_getHeight(currentWindow_));
    } else if (capabilities.currentExtent.width != UINT32_MAX) {
        return capabilities.currentExtent;
    } else {
        actualExtent = {800, 600};
    }
    // clamp 到 min/max 范围
    actualExtent.width = std::clamp(actualExtent.width,
        capabilities.minImageExtent.width, capabilities.maxImageExtent.width);
    actualExtent.height = std::clamp(actualExtent.height,
        capabilities.minImageExtent.height, capabilities.maxImageExtent.height);
    return actualExtent;
}
```

#### 3. displayImage：检测 window 尺寸变化

屏幕旋转时 `ANativeWindow` 指针不变但尺寸变化，需要检测并重建 `VkSurfaceKHR` + swapchain：

```cpp
int32_t windowWidth = ANativeWindow_getWidth(window);
int32_t windowHeight = ANativeWindow_getHeight(window);
bool windowChanged = (window != currentWindow_);
bool windowResized = (windowWidth != static_cast<int32_t>(swapchainExtent_.width) ||
                      windowHeight != static_cast<int32_t>(swapchainExtent_.height));

if (windowChanged || windowResized) {
    // 无论是 window 指针变化还是尺寸变化（如屏幕旋转），都需要重建 VkSurfaceKHR
    if (surface_ != VK_NULL_HANDLE) {
        vkDestroySurfaceKHR(instance_, surface_, nullptr);
        surface_ = VK_NULL_HANDLE;
    }
    // 重新创建 surface 和 swapchain...
}
```

#### 4. Pipeline：viewport/scissor 使用动态状态

旋转后 swapchain 尺寸变化，如果 viewport/scissor 是静态的（写死在 pipeline 中），画面会拉伸变形。改为动态状态：

```cpp
// createGraphicsPipeline() 中
std::vector<VkDynamicState> dynamicStates = {
    VK_DYNAMIC_STATE_VIEWPORT,
    VK_DYNAMIC_STATE_SCISSOR
};

// recordCommandBuffer() 中每帧设置
VkViewport viewport{};
viewport.width = static_cast<float>(swapchainExtent_.width);
viewport.height = static_cast<float>(swapchainExtent_.height);
vkCmdSetViewport(commandBuffer, 0, 1, &viewport);

VkRect2D scissor{};
scissor.extent = swapchainExtent_;
vkCmdSetScissor(commandBuffer, 0, 1, &scissor);
```

#### 5. vkAcquireNextImageKHR：SUBOPTIMAL 不重建

```cpp
if (result == VK_ERROR_OUT_OF_DATE_KHR) {
    recreateSwapchain();
    return true;
} else if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
    return false;
}
// VK_SUBOPTIMAL_KHR 继续正常渲染
```

#### 6. vkQueuePresentKHR：忽略 SUBOPTIMAL

这是最关键的一处。使用 `IDENTITY` preTransform 时，驱动会**持续**报告 `VK_SUBOPTIMAL_KHR`（因为 `preTransform != currentTransform`），这是预期行为。如果在此处触发重建，会陷入**无限重建循环**（每帧重建一次 swapchain）：

```cpp
// vkQueuePresentKHR 返回值处理
if (result == VK_ERROR_OUT_OF_DATE_KHR || framebufferResized_) {
    framebufferResized_ = false;
    recreateSwapchain();
} else if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
    // 真正的错误
    return false;
}
// VK_SUBOPTIMAL_KHR 是 IDENTITY transform 下的预期行为，忽略
```

### 踩坑总结

| 坑 | 现象 | 根因 | 解决 |
|----|------|------|------|
| **每帧重建 swapchain** | 旋转后日志疯狂刷 `recreateSwapchain` | `vkQueuePresentKHR` 返回 `VK_SUBOPTIMAL_KHR` 触发重建 | 忽略 `VK_SUBOPTIMAL_KHR` |
| **旋转后尺寸不对** | 横屏画面被压缩到竖屏区域 | `capabilities.currentExtent` 返回未旋转尺寸 | 使用 `ANativeWindow_getWidth/Height` |
| **旋转后画面拉伸** | 画面比例变形 | viewport/scissor 是静态的，写死了旧尺寸 | 改为 `VK_DYNAMIC_STATE_VIEWPORT/SCISSOR` |
| **旋转后画面方向不变** | 画面内容没有跟随旋转 | 使用 `currentTransform` 时需要手动旋转顶点 | 使用 `IDENTITY` 让合成器处理 |
| **旋转后 surface 失效** | 渲染失败或崩溃 | 尺寸变化但没重建 `VkSurfaceKHR` | 检测尺寸变化时也重建 surface |

## 扩展开发指南

### 添加新的像素格式

1. 在 `PixelFormatType` 枚举中添加新类型
2. 在 `getPixelFormatType()` 中添加 AVPixelFormat 映射
3. 在 `getVkFormat()` 中定义各 plane 的 VkFormat
4. 编写 GLSL 片段着色器，使用 `glslc` 编译为 SPIR-V
5. 将 SPIR-V 字节码添加到 `sky_vk_shaders.h`
6. 在 `createGraphicsPipeline()` 中添加着色器选择分支
7. 在 `displayImage()` 中添加纹理上传逻辑

### 修改 YUV→RGB 转换公式

1. 修改 `sky_vk_renderer.cpp` 顶部的 GLSL 源码（备份参考）
2. 创建临时 `.glsl` 文件
3. 使用 `glslc` 编译为 `.spv`
4. 使用 Python 脚本转换为 C 数组
5. 替换 `sky_vk_shaders.h` 中的字节码
6. 确保 GLSL 源码和 SPIR-V 字节码保持一致
