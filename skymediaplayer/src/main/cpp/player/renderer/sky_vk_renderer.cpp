/**
 * Vulkan 渲染器实现
 * 使用 Vulkan 1.1 API 实现视频帧渲染
 * 支持 YUV420P, NV12, NV21, RGBA 像素格式
 */

#include "sky_vk_renderer.h"
#include "logger.h"
#include <android/native_window.h>
#include "sky_vk_shaders.h"
#include <cstring>
#include <set>
#include <algorithm>
#include <array>
#include <cstdint>
#include <vector>
#include <string>

// ========== GLSL Shader 源码（参考，实际使用预编译的 SPIR-V 字节码） ==========
// 注意：权威着色器源码在 renderer/shaders/*.{vert,frag}（含 LUT，binding=3 + push_constant lutEnabled），
//       改完用 renderer/shaders/gen_spirv.sh 重新生成 sky_vk_shaders.h。以下字符串仅作历史参考、运行时未使用。
// 所有片段着色器输出 RGBA 通道顺序 vec4(r,g,b,a)

// 顶点着色器：全屏四边形
static const char* vertexShaderSource = R"(
#version 450

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;

layout(location = 0) out vec2 vTexCoord;

void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vTexCoord = aTexCoord;
}
)";

// 片段着色器：YUV420P (3 planes)
// BT.601 limited range (Y:16-235, UV:16-240) → full range RGB
static const char* fragmentShaderYUV420P = R"(
#version 450

layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D yTexture;
layout(binding = 1) uniform sampler2D uTexture;
layout(binding = 2) uniform sampler2D vTexture;

void main() {
    float y = (texture(yTexture, vTexCoord).r - 0.0627451) * 1.164384;
    float u = texture(uTexture, vTexCoord).r - 0.5;
    float v = texture(vTexture, vTexCoord).r - 0.5;
    
    float r = y + 1.596027 * v;
    float g = y - 0.391762 * u - 0.812968 * v;
    float b = y + 2.017232 * u;
    
    fragColor = vec4(clamp(r, 0.0, 1.0), clamp(g, 0.0, 1.0), clamp(b, 0.0, 1.0), 1.0);
}
)";

// 片段着色器：NV12 (Y plane + UV interleaved)
// BT.601 limited range → full range RGB
static const char* fragmentShaderNV12 = R"(
#version 450

layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D yTexture;
layout(binding = 1) uniform sampler2D uvTexture;

void main() {
    float y = (texture(yTexture, vTexCoord).r - 0.0627451) * 1.164384;
    vec2 uv = texture(uvTexture, vTexCoord).rg;
    float u = uv.r - 0.5;
    float v = uv.g - 0.5;
    
    float r = y + 1.596027 * v;
    float g = y - 0.391762 * u - 0.812968 * v;
    float b = y + 2.017232 * u;
    
    fragColor = vec4(clamp(r, 0.0, 1.0), clamp(g, 0.0, 1.0), clamp(b, 0.0, 1.0), 1.0);
}
)";

// 片段着色器：NV21 (Y plane + VU interleaved)
// BT.601 limited range → full range RGB
static const char* fragmentShaderNV21 = R"(
#version 450

layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D yTexture;
layout(binding = 1) uniform sampler2D vuTexture;

void main() {
    float y = (texture(yTexture, vTexCoord).r - 0.0627451) * 1.164384;
    vec2 vu = texture(vuTexture, vTexCoord).rg;
    float u = vu.g - 0.5;
    float v = vu.r - 0.5;
    
    float r = y + 1.596027 * v;
    float g = y - 0.391762 * u - 0.812968 * v;
    float b = y + 2.017232 * u;
    
    fragColor = vec4(clamp(r, 0.0, 1.0), clamp(g, 0.0, 1.0), clamp(b, 0.0, 1.0), 1.0);
}
)";

// 片段着色器：RGBA (direct output)
static const char* fragmentShaderRGBA = R"(
#version 450

layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 fragColor;

layout(binding = 0) uniform sampler2D rgbaTexture;

void main() {
    fragColor = texture(rgbaTexture, vTexCoord);
}
)";

// ========== 顶点数据：全屏四边形（Triangle Strip） ==========
// Vulkan NDC: Y 轴向下，纹理坐标 (0,0) 在左上角
static const float vertices[] = {
    // Position     // TexCoord
    -1.0f, -1.0f,   0.0f, 0.0f,  // 左下 → 纹理顶部
     1.0f, -1.0f,   1.0f, 0.0f,  // 右下 → 纹理顶部
    -1.0f,  1.0f,   0.0f, 1.0f,  // 左上 → 纹理底部
     1.0f,  1.0f,   1.0f, 1.0f   // 右上 → 纹理底部
};

// ========== SkyVkRenderer 实现 ==========

SkyVkRenderer::SkyVkRenderer() {
    ALOG_I(TAG, "SkyVkRenderer constructor");
}

SkyVkRenderer::~SkyVkRenderer() {
    terminate();
}

bool SkyVkRenderer::isValid() {
    return isInitialized_;
}

void SkyVkRenderer::terminate() {
    FUNC_TRACE();
    
    if (!isInitialized_) {
        return;
    }
    
    ALOG_I(TAG, "Terminating Vulkan renderer");
    
    // 等待设备空闲
    if (device_ != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device_);
    }
    
    // 清理同步对象
    for (size_t i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
        if (renderFinishedSemaphores_[i] != VK_NULL_HANDLE) {
            vkDestroySemaphore(device_, renderFinishedSemaphores_[i], nullptr);
        }
        if (imageAvailableSemaphores_[i] != VK_NULL_HANDLE) {
            vkDestroySemaphore(device_, imageAvailableSemaphores_[i], nullptr);
        }
        if (inFlightFences_[i] != VK_NULL_HANDLE) {
            vkDestroyFence(device_, inFlightFences_[i], nullptr);
        }
    }
    
    // 清理命令缓冲（销毁命令池会自动释放其分配的命令缓冲；
    // 置空避免 cleanupSwapchain() 再对已销毁的池调用 vkFreeCommandBuffers）
    if (commandPool_ != VK_NULL_HANDLE) {
        vkDestroyCommandPool(device_, commandPool_, nullptr);
        commandPool_ = VK_NULL_HANDLE;
    }
    commandBuffers_.clear();

    // 清理纹理资源
    cleanupTextureResources();

    // 清理常驻 LUT 纹理
    if (lutTexture_.sampler != VK_NULL_HANDLE) {
        vkDestroySampler(device_, lutTexture_.sampler, nullptr);
    }
    if (lutTexture_.imageView != VK_NULL_HANDLE) {
        vkDestroyImageView(device_, lutTexture_.imageView, nullptr);
    }
    if (lutTexture_.image != VK_NULL_HANDLE) {
        vkDestroyImage(device_, lutTexture_.image, nullptr);
    }
    if (lutTexture_.memory != VK_NULL_HANDLE) {
        vkFreeMemory(device_, lutTexture_.memory, nullptr);
    }
    lutTexture_ = {};

    // 清理帧缓冲（清空容器，避免后续 cleanupSwapchain() 重复销毁同一句柄导致 abort）
    for (auto framebuffer : framebuffers_) {
        if (framebuffer != VK_NULL_HANDLE) {
            vkDestroyFramebuffer(device_, framebuffer, nullptr);
        }
    }
    framebuffers_.clear();

    // 清理管线
    if (graphicsPipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device_, graphicsPipeline_, nullptr);
    }
    if (pipelineLayout_ != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
    }
    if (renderPass_ != VK_NULL_HANDLE) {
        vkDestroyRenderPass(device_, renderPass_, nullptr);
    }
    
    // 清理 Swapchain
    cleanupSwapchain();
    
    // 清理 descriptor pool
    if (descriptorPool_ != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(device_, descriptorPool_, nullptr);
    }
    if (descriptorSetLayout_ != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(device_, descriptorSetLayout_, nullptr);
    }
    
    // 清理 vertex buffer
    if (vertexBuffer_ != VK_NULL_HANDLE) {
        vkDestroyBuffer(device_, vertexBuffer_, nullptr);
    }
    if (vertexBufferMemory_ != VK_NULL_HANDLE) {
        vkFreeMemory(device_, vertexBufferMemory_, nullptr);
    }
    
    // 清理 staging buffer
    if (stagingBuffer_ != VK_NULL_HANDLE) {
        vkDestroyBuffer(device_, stagingBuffer_, nullptr);
    }
    if (stagingBufferMemory_ != VK_NULL_HANDLE) {
        vkFreeMemory(device_, stagingBufferMemory_, nullptr);
    }
    
    // 清理逻辑设备
    if (device_ != VK_NULL_HANDLE) {
        vkDestroyDevice(device_, nullptr);
    }
    
    // 清理 Surface
    if (surface_ != VK_NULL_HANDLE) {
        vkDestroySurfaceKHR(instance_, surface_, nullptr);
    }
    
    // 清理实例
    if (instance_ != VK_NULL_HANDLE) {
        vkDestroyInstance(instance_, nullptr);
    }
    
    isInitialized_ = false;
    ALOG_I(TAG, "Vulkan renderer terminated");
}

bool SkyVkRenderer::displayImage(EGLNativeWindowType window, AVFrame *frame) {
    if (!window || !frame) {
        ALOG_E(TAG, "Invalid parameters: window=%p, frame=%p", window, frame);
        return false;
    }
    
    // 首次初始化
    if (!isInitialized_) {
        ALOG_I(TAG, "Initializing Vulkan renderer");
        
        if (!createInstance()) {
            ALOG_E(TAG, "Failed to create Vulkan instance");
            return false;
        }
        
        // Surface 必须在 pickPhysicalDevice/createLogicalDevice 之前创建
        // 因为 findQueueFamilies 需要 surface_ 来查询 present 支持
        if (!createSurface(window)) {
            ALOG_E(TAG, "Failed to create surface");
            return false;
        }
        
        if (!pickPhysicalDevice()) {
            ALOG_E(TAG, "Failed to pick physical device");
            return false;
        }
        
        if (!createLogicalDevice()) {
            ALOG_E(TAG, "Failed to create logical device");
            return false;
        }
        
        if (!createSwapchain()) {
            ALOG_E(TAG, "Failed to create swapchain");
            return false;
        }
        
        if (!createImageViews()) {
            ALOG_E(TAG, "Failed to create image views");
            return false;
        }
        
        if (!createRenderPass()) {
            ALOG_E(TAG, "Failed to create render pass");
            return false;
        }
        
        if (!createCommandPool()) {
            ALOG_E(TAG, "Failed to create command pool");
            return false;
        }
        
        if (!createSyncObjects()) {
            ALOG_E(TAG, "Failed to create sync objects");
            return false;
        }
        
        if (!createCommandBuffers()) {
            ALOG_E(TAG, "Failed to create command buffers");
            return false;
        }
        
        if (!createVertexBuffer()) {
            ALOG_E(TAG, "Failed to create vertex buffer");
            return false;
        }
        
        isInitialized_ = true;
        currentWindow_ = window;
    }
    
    // 检查窗口是否变化（指针变化或尺寸变化，如屏幕旋转）
    int32_t windowWidth = ANativeWindow_getWidth(window);
    int32_t windowHeight = ANativeWindow_getHeight(window);
    bool windowChanged = (window != currentWindow_);
    bool windowResized = (windowWidth != static_cast<int32_t>(swapchainExtent_.width) ||
                          windowHeight != static_cast<int32_t>(swapchainExtent_.height));
    
    if (windowChanged || windowResized) {
        ALOG_I(TAG, "Window %s: %dx%d -> %dx%d",
               windowChanged ? "changed" : "resized",
               swapchainExtent_.width, swapchainExtent_.height,
               windowWidth, windowHeight);
        
        vkDeviceWaitIdle(device_);
        currentWindow_ = window;
        
        // 无论是 window 指针变化还是尺寸变化（如屏幕旋转），都需要重建 VkSurfaceKHR
        // 因为屏幕旋转后 surface 的 transform 属性会变化，旧的 VkSurfaceKHR 无法反映新的方向
        if (surface_ != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(instance_, surface_, nullptr);
            surface_ = VK_NULL_HANDLE;
        }
        
        if (!createSurface(window)) {
            ALOG_E(TAG, "Failed to recreate surface");
            return false;
        }
        
        if (!recreateSwapchain()) {
            ALOG_E(TAG, "Failed to recreate swapchain");
            return false;
        }
    }
    
    // 检查像素格式是否变化
    if (frame->format != currentPixelFormat_) {
        ALOG_I(TAG, "Pixel format changed: %d -> %d", currentPixelFormat_, frame->format);
        
        vkDeviceWaitIdle(device_);
        
        cleanupTextureResources();
        
        // 清理旧的 Pipeline（不同像素格式需要不同的 fragment shader）
        if (graphicsPipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, graphicsPipeline_, nullptr);
            graphicsPipeline_ = VK_NULL_HANDLE;
        }
        if (pipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
            pipelineLayout_ = VK_NULL_HANDLE;
        }
        
        currentPixelFormat_ = static_cast<AVPixelFormat>(frame->format);
        
        if (!createTextureResources(currentPixelFormat_)) {
            ALOG_E(TAG, "Failed to create texture resources");
            return false;
        }
        
        if (!createDescriptorSets()) {
            ALOG_E(TAG, "Failed to create descriptor sets");
            return false;
        }
        
        if (!createGraphicsPipeline()) {
            ALOG_E(TAG, "Failed to create graphics pipeline");
            return false;
        }
        
        if (!createFramebuffers()) {
            ALOG_E(TAG, "Failed to create framebuffers");
            return false;
        }
    }
    
    // 上传纹理数据
    PixelFormatType pixelFormatType = getPixelFormatType(static_cast<AVPixelFormat>(frame->format));
    
    switch (pixelFormatType) {
        case PixelFormatType::YUV420P: {
            // Y plane (R8_UNORM, 1 byte per pixel)
            uploadTextureData(yuvTextures_[0], frame->data[0],
                            frame->linesize[0] * frame->height,
                            frame->width, frame->height,
                            frame->linesize[0],
                            getVkFormat(pixelFormatType, 0));
            // U plane
            uploadTextureData(yuvTextures_[1], frame->data[1],
                            frame->linesize[1] * (frame->height / 2),
                            frame->width / 2, frame->height / 2,
                            frame->linesize[1],
                            getVkFormat(pixelFormatType, 1));
            // V plane
            uploadTextureData(yuvTextures_[2], frame->data[2],
                            frame->linesize[2] * (frame->height / 2),
                            frame->width / 2, frame->height / 2,
                            frame->linesize[2],
                            getVkFormat(pixelFormatType, 2));
            break;
        }
        case PixelFormatType::NV12: {
            // Y plane
            uploadTextureData(yuvTextures_[0], frame->data[0],
                            frame->linesize[0] * frame->height,
                            frame->width, frame->height,
                            frame->linesize[0],
                            getVkFormat(pixelFormatType, 0));
            // UV plane (R8G8_UNORM, 2 bytes per pixel)
            uploadTextureData(yuvTextures_[1], frame->data[1],
                            frame->linesize[1] * (frame->height / 2),
                            frame->width / 2, frame->height / 2,
                            frame->linesize[1] / 2,
                            getVkFormat(pixelFormatType, 1));
            break;
        }
        case PixelFormatType::NV21: {
            // Y plane
            uploadTextureData(yuvTextures_[0], frame->data[0],
                            frame->linesize[0] * frame->height,
                            frame->width, frame->height,
                            frame->linesize[0],
                            getVkFormat(pixelFormatType, 0));
            // VU plane (R8G8_UNORM, 2 bytes per pixel)
            uploadTextureData(yuvTextures_[1], frame->data[1],
                            frame->linesize[1] * (frame->height / 2),
                            frame->width / 2, frame->height / 2,
                            frame->linesize[1] / 2,
                            getVkFormat(pixelFormatType, 1));
            break;
        }
        case PixelFormatType::RGBA: {
            // RGBA (R8G8B8A8_UNORM, 4 bytes per pixel)
            uploadTextureData(yuvTextures_[0], frame->data[0],
                            frame->linesize[0] * frame->height,
                            frame->width, frame->height,
                            frame->linesize[0] / 4,
                            getVkFormat(pixelFormatType, 0));
            break;
        }
        default:
            ALOG_E(TAG, "Unsupported pixel format: %d", frame->format);
            return false;
    }
    
    // 纹理尺寸变化后需要更新 descriptor sets
    if (descriptorSetsNeedUpdate_) {
        descriptorSetsNeedUpdate_ = false;
        
        for (size_t i = 0; i < descriptorSets_.size(); i++) {
            // imageInfos 必须与 descriptorWrites 生命周期一致，避免悬空指针
            std::vector<VkDescriptorImageInfo> imageInfos(yuvTextures_.size());
            std::vector<VkWriteDescriptorSet> descriptorWrites(yuvTextures_.size());
            
            for (size_t j = 0; j < yuvTextures_.size(); j++) {
                imageInfos[j].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                imageInfos[j].imageView = yuvTextures_[j].imageView;
                imageInfos[j].sampler = yuvTextures_[j].sampler;
                
                descriptorWrites[j].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                descriptorWrites[j].dstSet = descriptorSets_[i];
                descriptorWrites[j].dstBinding = j;
                descriptorWrites[j].dstArrayElement = 0;
                descriptorWrites[j].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                descriptorWrites[j].descriptorCount = 1;
                descriptorWrites[j].pImageInfo = &imageInfos[j];
            }
            
            vkUpdateDescriptorSets(device_, static_cast<uint32_t>(descriptorWrites.size()),
                                  descriptorWrites.data(), 0, nullptr);
        }
    }
    
    // LUT/增强状态下发（变更时；LUT 等设备空闲后重传到常驻纹理，512x512 同尺寸不会重建，descriptor 仍有效）
    {
        std::lock_guard<std::mutex> lk(lutMtx_);
        if (lutPendingDirty_) {
            vkDeviceWaitIdle(device_);
            if (lutEnabled_ && lutPending_.size() == static_cast<size_t>(512 * 512 * 4)
                && lutTexture_.image != VK_NULL_HANDLE) {
                uploadTextureData(lutTexture_, lutPending_.data(), lutPending_.size(),
                                  512, 512, 512 * 4, VK_FORMAT_R8G8B8A8_UNORM);
            }
            pushValues_[0] = lutEnabled_ ? lutIntensity_ : 0.0f;
            lutPendingDirty_ = false;
        }
        if (enhanceDirty_) {
            pushValues_[1] = enhanceSharpness_;
            pushValues_[2] = enhanceDeband_;
            enhanceDirty_ = false;
        }
    }

    // 渲染一帧
    uint32_t imageIndex;
    VkResult result = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX,
                                           imageAvailableSemaphores_[currentFrameIndex_],
                                           VK_NULL_HANDLE, &imageIndex);
    
    if (result == VK_ERROR_OUT_OF_DATE_KHR) {
        if (!recreateSwapchain()) {
            ALOG_E(TAG, "Failed to recreate swapchain");
            return false;
        }
        return displayImage(window, frame);
    } else if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
        ALOG_E(TAG, "Failed to acquire swapchain image: %d", result);
        return false;
    }
    
    // 等待上一帧完成
    VkFence currentFence = inFlightFences_[currentFrameIndex_];
    vkWaitForFences(device_, 1, &currentFence, VK_TRUE, UINT64_MAX);
    vkResetFences(device_, 1, &currentFence);
    
    // 录制命令缓冲
    vkResetCommandBuffer(commandBuffers_[currentFrameIndex_], 0);
    recordCommandBuffer(commandBuffers_[currentFrameIndex_], imageIndex);
    
    // 提交命令
    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    
    VkSemaphore waitSemaphores[] = {imageAvailableSemaphores_[currentFrameIndex_]};
    VkPipelineStageFlags waitStages[] = {VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
    submitInfo.waitSemaphoreCount = 1;
    submitInfo.pWaitSemaphores = waitSemaphores;
    submitInfo.pWaitDstStageMask = waitStages;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffers_[currentFrameIndex_];
    
    VkSemaphore signalSemaphores[] = {renderFinishedSemaphores_[currentFrameIndex_]};
    submitInfo.signalSemaphoreCount = 1;
    submitInfo.pSignalSemaphores = signalSemaphores;
    
    result = vkQueueSubmit(graphicsQueue_, 1, &submitInfo, inFlightFences_[currentFrameIndex_]);
    if (result != VK_SUCCESS) {
        ALOG_E(TAG, "Failed to submit draw command buffer: %d", result);
        return false;
    }
    
    // 呈现
    VkPresentInfoKHR presentInfo{};
    presentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    presentInfo.waitSemaphoreCount = 1;
    presentInfo.pWaitSemaphores = signalSemaphores;
    
    VkSwapchainKHR swapchains[] = {swapchain_};
    presentInfo.swapchainCount = 1;
    presentInfo.pSwapchains = swapchains;
    presentInfo.pImageIndices = &imageIndex;
    
    result = vkQueuePresentKHR(presentQueue_, &presentInfo);
    
    // 使用 IDENTITY preTransform 时，驱动会持续报告 VK_SUBOPTIMAL_KHR（因为 preTransform != currentTransform），
    // 这是预期行为，不应触发重建，否则会陷入无限重建循环。
    // 只在 VK_ERROR_OUT_OF_DATE_KHR 或 framebufferResized_ 时才重建。
    if (result == VK_ERROR_OUT_OF_DATE_KHR || framebufferResized_) {
        framebufferResized_ = false;
        if (!recreateSwapchain()) {
            ALOG_E(TAG, "Failed to recreate swapchain after present");
            return false;
        }
    } else if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
        ALOG_E(TAG, "Failed to present swapchain image: %d", result);
        return false;
    }
    
    currentFrameIndex_ = (currentFrameIndex_ + 1) % MAX_FRAMES_IN_FLIGHT;
    
    return true;
}

// ========== Vulkan 初始化方法 ==========

bool SkyVkRenderer::createInstance() {
    FUNC_TRACE();
    
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "SkyPlayer";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "SkyPlayer Engine";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;
    
    const char* extensions[] = {
        "VK_KHR_surface",
        "VK_KHR_android_surface"
    };
    
    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;
    createInfo.enabledExtensionCount = 2;
    createInfo.ppEnabledExtensionNames = extensions;
    
    VkResult result = vkCreateInstance(&createInfo, nullptr, &instance_);
    if (result != VK_SUCCESS) {
        ALOG_E(TAG, "Failed to create Vulkan instance: %d", result);
        return false;
    }
    
    ALOG_I(TAG, "Vulkan instance created successfully");
    return true;
}

bool SkyVkRenderer::pickPhysicalDevice() {
    FUNC_TRACE();
    
    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(instance_, &deviceCount, nullptr);
    
    if (deviceCount == 0) {
        ALOG_E(TAG, "Failed to find GPUs with Vulkan support");
        return false;
    }
    
    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(instance_, &deviceCount, devices.data());
    
    for (const auto& device : devices) {
        if (isDeviceSuitable(device)) {
            physicalDevice_ = device;
            break;
        }
    }
    
    if (physicalDevice_ == VK_NULL_HANDLE) {
        ALOG_E(TAG, "Failed to find a suitable GPU");
        return false;
    }
    
    ALOG_I(TAG, "Physical device selected");
    return true;
}

bool SkyVkRenderer::createLogicalDevice() {
    FUNC_TRACE();
    
    QueueFamilyIndices indices = findQueueFamilies(physicalDevice_);
    
    std::vector<VkDeviceQueueCreateInfo> queueCreateInfos;
    std::set<uint32_t> uniqueQueueFamilies = {indices.graphicsFamily, indices.presentFamily};
    
    float queuePriority = 1.0f;
    for (uint32_t queueFamily : uniqueQueueFamilies) {
        VkDeviceQueueCreateInfo queueCreateInfo{};
        queueCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        queueCreateInfo.queueFamilyIndex = queueFamily;
        queueCreateInfo.queueCount = 1;
        queueCreateInfo.pQueuePriorities = &queuePriority;
        queueCreateInfos.push_back(queueCreateInfo);
    }
    
    VkPhysicalDeviceFeatures deviceFeatures{};
    
    const char* deviceExtensions[] = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME
    };
    
    VkDeviceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    createInfo.queueCreateInfoCount = static_cast<uint32_t>(queueCreateInfos.size());
    createInfo.pQueueCreateInfos = queueCreateInfos.data();
    createInfo.pEnabledFeatures = &deviceFeatures;
    createInfo.enabledExtensionCount = 1;
    createInfo.ppEnabledExtensionNames = deviceExtensions;
    
    VkResult result = vkCreateDevice(physicalDevice_, &createInfo, nullptr, &device_);
    if (result != VK_SUCCESS) {
        ALOG_E(TAG, "Failed to create logical device: %d", result);
        return false;
    }
    
    vkGetDeviceQueue(device_, indices.graphicsFamily, 0, &graphicsQueue_);
    vkGetDeviceQueue(device_, indices.presentFamily, 0, &presentQueue_);
    
    ALOG_I(TAG, "Logical device created");
    return true;
}

bool SkyVkRenderer::createSurface(EGLNativeWindowType window) {
    FUNC_TRACE();
    
    VkAndroidSurfaceCreateInfoKHR createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    createInfo.window = window;
    
    VkResult result = vkCreateAndroidSurfaceKHR(instance_, &createInfo, nullptr, &surface_);
    if (result != VK_SUCCESS) {
        ALOG_E(TAG, "Failed to create Android surface: %d", result);
        return false;
    }
    
    ALOG_I(TAG, "Surface created");
    return true;
}

bool SkyVkRenderer::createSwapchain() {
    FUNC_TRACE();
    
    QueueFamilyIndices indices = findQueueFamilies(physicalDevice_);
    
    VkSurfaceCapabilitiesKHR capabilities;
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice_, surface_, &capabilities);
    
    uint32_t formatCount;
    vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, nullptr);
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, formats.data());
    
    uint32_t presentModeCount;
    vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice_, surface_, &presentModeCount, nullptr);
    std::vector<VkPresentModeKHR> presentModes(presentModeCount);
    vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice_, surface_, &presentModeCount, presentModes.data());
    
    VkSurfaceFormatKHR surfaceFormat = chooseSwapSurfaceFormat(formats);
    VkPresentModeKHR presentMode = chooseSwapPresentMode(presentModes);
    VkExtent2D extent = chooseSwapExtent(capabilities);
    
    uint32_t imageCount = capabilities.minImageCount + 1;
    if (capabilities.maxImageCount > 0 && imageCount > capabilities.maxImageCount) {
        imageCount = capabilities.maxImageCount;
    }
    
    VkSwapchainCreateInfoKHR createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    createInfo.surface = surface_;
    createInfo.minImageCount = imageCount;
    createInfo.imageFormat = surfaceFormat.format;
    createInfo.imageColorSpace = surfaceFormat.colorSpace;
    createInfo.imageExtent = extent;
    createInfo.imageArrayLayers = 1;
    createInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    
    uint32_t queueFamilyIndicesArray[] = {indices.graphicsFamily, indices.presentFamily};
    if (indices.graphicsFamily != indices.presentFamily) {
        createInfo.imageSharingMode = VK_SHARING_MODE_CONCURRENT;
        createInfo.queueFamilyIndexCount = 2;
        createInfo.pQueueFamilyIndices = queueFamilyIndicesArray;
    } else {
        createInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    }
    
    // 使用 IDENTITY transform，让 Android 合成器处理屏幕旋转
    // 如果设置为 currentTransform，则需要应用自己在着色器中处理旋转
    if (capabilities.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) {
        createInfo.preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    } else {
        createInfo.preTransform = capabilities.currentTransform;
    }
    ALOG_I(TAG, "Surface transform: current=0x%x, using=0x%x",
           capabilities.currentTransform, createInfo.preTransform);
    
    // 选择设备支持的 compositeAlpha 模式
    VkCompositeAlphaFlagBitsKHR compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    VkCompositeAlphaFlagBitsKHR preferredAlphaModes[] = {
        VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
        VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
        VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
        VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
    };
    for (auto mode : preferredAlphaModes) {
        if (capabilities.supportedCompositeAlpha & mode) {
            compositeAlpha = mode;
            break;
        }
    }
    createInfo.compositeAlpha = compositeAlpha;
    createInfo.presentMode = presentMode;
    createInfo.clipped = VK_TRUE;
    createInfo.oldSwapchain = VK_NULL_HANDLE;
    
    VkResult result = vkCreateSwapchainKHR(device_, &createInfo, nullptr, &swapchain_);
    if (result != VK_SUCCESS) {
        ALOG_E(TAG, "Failed to create swapchain: %d", result);
        return false;
    }
    
    vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount, nullptr);
    swapchainImages_.resize(imageCount);
    vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount, swapchainImages_.data());
    
    swapchainImageFormat_ = surfaceFormat.format;
    swapchainExtent_ = extent;
    
    ALOG_I(TAG, "Swapchain created: %dx%d, format=%d", extent.width, extent.height, surfaceFormat.format);
    return true;
}

bool SkyVkRenderer::createImageViews() {
    FUNC_TRACE();
    
    swapchainImageViews_.resize(swapchainImages_.size());
    
    for (size_t i = 0; i < swapchainImages_.size(); i++) {
        VkImageViewCreateInfo createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        createInfo.image = swapchainImages_[i];
        createInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        createInfo.format = swapchainImageFormat_;
        createInfo.components.r = VK_COMPONENT_SWIZZLE_IDENTITY;
        createInfo.components.g = VK_COMPONENT_SWIZZLE_IDENTITY;
        createInfo.components.b = VK_COMPONENT_SWIZZLE_IDENTITY;
        createInfo.components.a = VK_COMPONENT_SWIZZLE_IDENTITY;
        createInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        createInfo.subresourceRange.baseMipLevel = 0;
        createInfo.subresourceRange.levelCount = 1;
        createInfo.subresourceRange.baseArrayLayer = 0;
        createInfo.subresourceRange.layerCount = 1;
        
        VkResult result = vkCreateImageView(device_, &createInfo, nullptr, &swapchainImageViews_[i]);
        if (result != VK_SUCCESS) {
            ALOG_E(TAG, "Failed to create image view: %d", result);
            return false;
        }
    }
    
    return true;
}

bool SkyVkRenderer::createRenderPass() {
    FUNC_TRACE();
    
    VkAttachmentDescription colorAttachment{};
    colorAttachment.format = swapchainImageFormat_;
    colorAttachment.samples = VK_SAMPLE_COUNT_1_BIT;
    colorAttachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    colorAttachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    colorAttachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    colorAttachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    colorAttachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    colorAttachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    
    VkAttachmentReference colorAttachmentRef{};
    colorAttachmentRef.attachment = 0;
    colorAttachmentRef.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    
    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &colorAttachmentRef;
    
    VkSubpassDependency dependency{};
    dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
    dependency.dstSubpass = 0;
    dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependency.srcAccessMask = 0;
    dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    
    VkRenderPassCreateInfo renderPassInfo{};
    renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    renderPassInfo.attachmentCount = 1;
    renderPassInfo.pAttachments = &colorAttachment;
    renderPassInfo.subpassCount = 1;
    renderPassInfo.pSubpasses = &subpass;
    renderPassInfo.dependencyCount = 1;
    renderPassInfo.pDependencies = &dependency;
    
    VkResult result = vkCreateRenderPass(device_, &renderPassInfo, nullptr, &renderPass_);
    if (result != VK_SUCCESS) {
        ALOG_E(TAG, "Failed to create render pass: %d", result);
        return false;
    }
    
    return true;
}

bool SkyVkRenderer::createGraphicsPipeline() {
    FUNC_TRACE();
    
    // 使用预编译的 SPIR-V 着色器字节码
    const uint32_t* fragmentSPIRVData = nullptr;
    size_t fragmentSPIRVSize = 0;
    
    switch (getPixelFormatType(currentPixelFormat_)) {
        case PixelFormatType::YUV420P:
            fragmentSPIRVData = fragmentShaderYUV420PSPIRV;
            fragmentSPIRVSize = fragmentShaderYUV420PSPIRVSize;
            break;
        case PixelFormatType::NV12:
            fragmentSPIRVData = fragmentShaderNV12SPIRV;
            fragmentSPIRVSize = fragmentShaderNV12SPIRVSize;
            break;
        case PixelFormatType::NV21:
            fragmentSPIRVData = fragmentShaderNV21SPIRV;
            fragmentSPIRVSize = fragmentShaderNV21SPIRVSize;
            break;
        case PixelFormatType::RGBA:
            fragmentSPIRVData = fragmentShaderRGBASPIRV;
            fragmentSPIRVSize = fragmentShaderRGBASPIRVSize;
            break;
        default:
            ALOG_E(TAG, "Unsupported pixel format for shader: %d", currentPixelFormat_);
            return false;
    }
    
    // 创建 shader modules
    VkShaderModule vertShaderModule = createShaderModule(vertexShaderSPIRV, vertexShaderSPIRVSize);
    VkShaderModule fragShaderModule = createShaderModule(fragmentSPIRVData, fragmentSPIRVSize);
    
    if (vertShaderModule == VK_NULL_HANDLE || fragShaderModule == VK_NULL_HANDLE) {
        ALOG_E(TAG, "Failed to create shader modules");
        if (vertShaderModule) vkDestroyShaderModule(device_, vertShaderModule, nullptr);
        if (fragShaderModule) vkDestroyShaderModule(device_, fragShaderModule, nullptr);
        return false;
    }
    
    // 顶点着色器阶段
    VkPipelineShaderStageCreateInfo vertShaderStageInfo{};
    vertShaderStageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    vertShaderStageInfo.stage = VK_SHADER_STAGE_VERTEX_BIT;
    vertShaderStageInfo.module = vertShaderModule;
    vertShaderStageInfo.pName = "main";
    
    // 片段着色器阶段
    VkPipelineShaderStageCreateInfo fragShaderStageInfo{};
    fragShaderStageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    fragShaderStageInfo.stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    fragShaderStageInfo.module = fragShaderModule;
    fragShaderStageInfo.pName = "main";
    
    VkPipelineShaderStageCreateInfo shaderStages[] = {vertShaderStageInfo, fragShaderStageInfo};
    
    // 顶点输入
    VkVertexInputBindingDescription bindingDescription{};
    bindingDescription.binding = 0;
    bindingDescription.stride = 4 * sizeof(float);
    bindingDescription.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;
    
    std::array<VkVertexInputAttributeDescription, 2> attributeDescriptions{};
    attributeDescriptions[0].binding = 0;
    attributeDescriptions[0].location = 0;
    attributeDescriptions[0].format = VK_FORMAT_R32G32_SFLOAT;
    attributeDescriptions[0].offset = 0;
    
    attributeDescriptions[1].binding = 0;
    attributeDescriptions[1].location = 1;
    attributeDescriptions[1].format = VK_FORMAT_R32G32_SFLOAT;
    attributeDescriptions[1].offset = 2 * sizeof(float);
    
    VkPipelineVertexInputStateCreateInfo vertexInputInfo{};
    vertexInputInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    vertexInputInfo.vertexBindingDescriptionCount = 1;
    vertexInputInfo.pVertexBindingDescriptions = &bindingDescription;
    vertexInputInfo.vertexAttributeDescriptionCount = static_cast<uint32_t>(attributeDescriptions.size());
    vertexInputInfo.pVertexAttributeDescriptions = attributeDescriptions.data();
    
    // 输入装配
    VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
    inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    inputAssembly.primitiveRestartEnable = VK_FALSE;
    
    // 视口和裁剪（使用动态状态，支持屏幕旋转时自动适配尺寸）
    VkPipelineViewportStateCreateInfo viewportState{};
    viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    viewportState.viewportCount = 1;
    viewportState.pViewports = nullptr;
    viewportState.scissorCount = 1;
    viewportState.pScissors = nullptr;
    
    // 光栅化
    VkPipelineRasterizationStateCreateInfo rasterizer{};
    rasterizer.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    rasterizer.depthClampEnable = VK_FALSE;
    rasterizer.rasterizerDiscardEnable = VK_FALSE;
    rasterizer.polygonMode = VK_POLYGON_MODE_FILL;
    rasterizer.lineWidth = 1.0f;
    rasterizer.cullMode = VK_CULL_MODE_NONE;
    rasterizer.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    rasterizer.depthBiasEnable = VK_FALSE;
    
    // 多重采样
    VkPipelineMultisampleStateCreateInfo multisampling{};
    multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    multisampling.sampleShadingEnable = VK_FALSE;
    multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    
    // 颜色混合
    VkPipelineColorBlendAttachmentState colorBlendAttachment{};
    colorBlendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                         VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    colorBlendAttachment.blendEnable = VK_FALSE;
    
    VkPipelineColorBlendStateCreateInfo colorBlending{};
    colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    colorBlending.logicOpEnable = VK_FALSE;
    colorBlending.attachmentCount = 1;
    colorBlending.pAttachments = &colorBlendAttachment;
    
    // 动态状态：viewport 和 scissor 在每帧录制命令时设置
    VkDynamicState dynamicStates[] = {
        VK_DYNAMIC_STATE_VIEWPORT,
        VK_DYNAMIC_STATE_SCISSOR
    };
    VkPipelineDynamicStateCreateInfo dynamicState{};
    dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
    dynamicState.dynamicStateCount = 2;
    dynamicState.pDynamicStates = dynamicStates;
    
    // Pipeline layout（含 LUT 开关/强度 push constant）
    VkPushConstantRange pushConstantRange{};
    pushConstantRange.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    pushConstantRange.offset = 0;
    pushConstantRange.size = 3 * sizeof(float);  // lutEnabled, sharpness, deband

    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &descriptorSetLayout_;
    pipelineLayoutInfo.pushConstantRangeCount = 1;
    pipelineLayoutInfo.pPushConstantRanges = &pushConstantRange;

    VkResult result = vkCreatePipelineLayout(device_, &pipelineLayoutInfo, nullptr, &pipelineLayout_);
    if (result != VK_SUCCESS) {
        ALOG_E(TAG, "Failed to create pipeline layout: %d", result);
        vkDestroyShaderModule(device_, vertShaderModule, nullptr);
        vkDestroyShaderModule(device_, fragShaderModule, nullptr);
        return false;
    }
    
    // Graphics pipeline
    VkGraphicsPipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pipelineInfo.stageCount = 2;
    pipelineInfo.pStages = shaderStages;
    pipelineInfo.pVertexInputState = &vertexInputInfo;
    pipelineInfo.pInputAssemblyState = &inputAssembly;
    pipelineInfo.pViewportState = &viewportState;
    pipelineInfo.pRasterizationState = &rasterizer;
    pipelineInfo.pMultisampleState = &multisampling;
    pipelineInfo.pColorBlendState = &colorBlending;
    pipelineInfo.pDynamicState = &dynamicState;
    pipelineInfo.layout = pipelineLayout_;
    pipelineInfo.renderPass = renderPass_;
    pipelineInfo.subpass = 0;
    
    result = vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &graphicsPipeline_);
    
    vkDestroyShaderModule(device_, vertShaderModule, nullptr);
    vkDestroyShaderModule(device_, fragShaderModule, nullptr);
    
    if (result != VK_SUCCESS) {
        ALOG_E(TAG, "Failed to create graphics pipeline: %d", result);
        return false;
    }
    
    ALOG_I(TAG, "Graphics pipeline created");
    return true;
}

bool SkyVkRenderer::createFramebuffers() {
    FUNC_TRACE();
    
    framebuffers_.resize(swapchainImageViews_.size());
    
    for (size_t i = 0; i < swapchainImageViews_.size(); i++) {
        VkImageView attachments[] = {swapchainImageViews_[i]};
        
        VkFramebufferCreateInfo framebufferInfo{};
        framebufferInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        framebufferInfo.renderPass = renderPass_;
        framebufferInfo.attachmentCount = 1;
        framebufferInfo.pAttachments = attachments;
        framebufferInfo.width = swapchainExtent_.width;
        framebufferInfo.height = swapchainExtent_.height;
        framebufferInfo.layers = 1;
        
        VkResult result = vkCreateFramebuffer(device_, &framebufferInfo, nullptr, &framebuffers_[i]);
        if (result != VK_SUCCESS) {
            ALOG_E(TAG, "Failed to create framebuffer: %d", result);
            return false;
        }
    }
    
    return true;
}

bool SkyVkRenderer::createCommandPool() {
    FUNC_TRACE();
    
    QueueFamilyIndices queueFamilyIndices = findQueueFamilies(physicalDevice_);
    
    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = queueFamilyIndices.graphicsFamily;
    
    VkResult result = vkCreateCommandPool(device_, &poolInfo, nullptr, &commandPool_);
    if (result != VK_SUCCESS) {
        ALOG_E(TAG, "Failed to create command pool: %d", result);
        return false;
    }
    
    return true;
}

bool SkyVkRenderer::createCommandBuffers() {
    FUNC_TRACE();
    
    commandBuffers_.resize(MAX_FRAMES_IN_FLIGHT);
    
    VkCommandBufferAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocInfo.commandPool = commandPool_;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = (uint32_t)commandBuffers_.size();
    
    VkResult result = vkAllocateCommandBuffers(device_, &allocInfo, commandBuffers_.data());
    if (result != VK_SUCCESS) {
        ALOG_E(TAG, "Failed to allocate command buffers: %d", result);
        return false;
    }
    
    return true;
}

bool SkyVkRenderer::createSyncObjects() {
    FUNC_TRACE();
    
    imageAvailableSemaphores_.resize(MAX_FRAMES_IN_FLIGHT);
    renderFinishedSemaphores_.resize(MAX_FRAMES_IN_FLIGHT);
    inFlightFences_.resize(MAX_FRAMES_IN_FLIGHT);
    
    VkSemaphoreCreateInfo semaphoreInfo{};
    semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    
    for (size_t i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
        if (vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &imageAvailableSemaphores_[i]) != VK_SUCCESS ||
            vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &renderFinishedSemaphores_[i]) != VK_SUCCESS ||
            vkCreateFence(device_, &fenceInfo, nullptr, &inFlightFences_[i]) != VK_SUCCESS) {
            ALOG_E(TAG, "Failed to create synchronization objects");
            return false;
        }
    }
    
    return true;
}

bool SkyVkRenderer::createTextureResources(AVPixelFormat format) {
    FUNC_TRACE();
    
    PixelFormatType pixelFormatType = getPixelFormatType(format);
    int numTextures = 0;
    
    switch (pixelFormatType) {
        case PixelFormatType::YUV420P:
            numTextures = 3;
            break;
        case PixelFormatType::NV12:
        case PixelFormatType::NV21:
            numTextures = 2;
            break;
        case PixelFormatType::RGBA:
            numTextures = 1;
            break;
        default:
            ALOG_E(TAG, "Unsupported pixel format: %d", format);
            return false;
    }
    
    yuvTextures_.resize(numTextures);
    
    // 创建纹理（尺寸将在上传时确定）
    for (int i = 0; i < numTextures; i++) {
        VkFormat vkFormat = getVkFormat(pixelFormatType, i);
        
        if (!createTextureImage(yuvTextures_[i], vkFormat, 1, 1)) {
            ALOG_E(TAG, "Failed to create texture image %d", i);
            return false;
        }
        
        if (!createTextureImageView(yuvTextures_[i], vkFormat)) {
            ALOG_E(TAG, "Failed to create texture image view %d", i);
            return false;
        }
        
        if (!createTextureSampler(yuvTextures_[i])) {
            ALOG_E(TAG, "Failed to create texture sampler %d", i);
            return false;
        }
    }
    
    // 常驻 LUT 纹理（与像素格式无关，512x512 RGBA）
    if (!ensureLutTexture()) {
        ALOG_E(TAG, "Failed to ensure LUT texture");
        return false;
    }

    ALOG_I(TAG, "Texture resources created: %d textures", numTextures);
    return true;
}

bool SkyVkRenderer::ensureLutTexture() {
    if (lutTexture_.image != VK_NULL_HANDLE) {
        return true;  // 已创建，跨像素格式复用
    }
    if (!createTextureImage(lutTexture_, VK_FORMAT_R8G8B8A8_UNORM, 512, 512)) {
        return false;
    }
    if (!createTextureImageView(lutTexture_, VK_FORMAT_R8G8B8A8_UNORM)) {
        return false;
    }
    if (!createTextureSampler(lutTexture_)) {
        return false;
    }
    // 上传一张全 0 占位，把 layout 切到 SHADER_READ_ONLY，保证 binding=3 可采样
    std::vector<uint8_t> zero(512 * 512 * 4, 0);
    uploadTextureData(lutTexture_, zero.data(), zero.size(), 512, 512, 512 * 4,
                      VK_FORMAT_R8G8B8A8_UNORM);
    // 若已有待上传的 LUT 数据，触发下一帧重传
    if (lutEnabled_ && lutPending_.size() == static_cast<size_t>(512 * 512 * 4)) {
        lutPendingDirty_ = true;
    }
    ALOG_I(TAG, "LUT texture (512x512) created");
    return true;
}

void SkyVkRenderer::setLut(const uint8_t* rgba, int len, float intensity) {
    std::lock_guard<std::mutex> lk(lutMtx_);
    if (rgba == nullptr || len <= 0) {
        lutEnabled_ = false;
        lutPendingDirty_ = true;
        return;
    }
    lutPending_.assign(rgba, rgba + len);
    lutIntensity_ = intensity;
    lutEnabled_ = true;
    lutPendingDirty_ = true;
}

void SkyVkRenderer::clearLut() {
    std::lock_guard<std::mutex> lk(lutMtx_);
    lutEnabled_ = false;
    lutPendingDirty_ = true;
}

void SkyVkRenderer::setEnhance(float sharpness, float deband) {
    std::lock_guard<std::mutex> lk(lutMtx_);
    enhanceSharpness_ = sharpness;
    enhanceDeband_ = deband;
    enhanceDirty_ = true;
}

bool SkyVkRenderer::createDescriptorSets() {
    FUNC_TRACE();

    // 确保 LUT 常驻纹理就绪（binding=3 必须有有效 image 可采样）
    if (!ensureLutTexture()) {
        ALOG_E(TAG, "ensureLutTexture failed");
        return false;
    }

    // 创建 descriptor set layout
    std::vector<VkDescriptorSetLayoutBinding> bindings;

    for (size_t i = 0; i < yuvTextures_.size(); i++) {
        VkDescriptorSetLayoutBinding binding{};
        binding.binding = i;
        binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        binding.descriptorCount = 1;
        binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        binding.pImmutableSamplers = nullptr;
        bindings.push_back(binding);
    }

    // LUT 采样器固定 binding=3
    {
        VkDescriptorSetLayoutBinding lutBinding{};
        lutBinding.binding = LUT_BINDING;
        lutBinding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        lutBinding.descriptorCount = 1;
        lutBinding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        lutBinding.pImmutableSamplers = nullptr;
        bindings.push_back(lutBinding);
    }

    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
    layoutInfo.pBindings = bindings.data();
    
    if (descriptorSetLayout_ != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(device_, descriptorSetLayout_, nullptr);
    }
    
    VkResult result = vkCreateDescriptorSetLayout(device_, &layoutInfo, nullptr, &descriptorSetLayout_);
    if (result != VK_SUCCESS) {
        ALOG_E(TAG, "Failed to create descriptor set layout: %d", result);
        return false;
    }
    
    // 创建 descriptor pool
    std::vector<VkDescriptorPoolSize> poolSizes;
    for (size_t i = 0; i < yuvTextures_.size(); i++) {
        VkDescriptorPoolSize poolSize{};
        poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        poolSize.descriptorCount = MAX_FRAMES_IN_FLIGHT;
        poolSizes.push_back(poolSize);
    }
    // LUT 采样器
    {
        VkDescriptorPoolSize lutPool{};
        lutPool.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        lutPool.descriptorCount = MAX_FRAMES_IN_FLIGHT;
        poolSizes.push_back(lutPool);
    }
    
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.poolSizeCount = static_cast<uint32_t>(poolSizes.size());
    poolInfo.pPoolSizes = poolSizes.data();
    poolInfo.maxSets = MAX_FRAMES_IN_FLIGHT;
    poolInfo.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    
    if (descriptorPool_ != VK_NULL_HANDLE) {
        vkResetDescriptorPool(device_, descriptorPool_, 0);
    } else {
        result = vkCreateDescriptorPool(device_, &poolInfo, nullptr, &descriptorPool_);
        if (result != VK_SUCCESS) {
            ALOG_E(TAG, "Failed to create descriptor pool: %d", result);
            return false;
        }
    }
    
    // 分配 descriptor sets
    descriptorSets_.resize(MAX_FRAMES_IN_FLIGHT);
    std::vector<VkDescriptorSetLayout> layouts(MAX_FRAMES_IN_FLIGHT, descriptorSetLayout_);
    
    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = descriptorPool_;
    allocInfo.descriptorSetCount = MAX_FRAMES_IN_FLIGHT;
    allocInfo.pSetLayouts = layouts.data();
    
    result = vkAllocateDescriptorSets(device_, &allocInfo, descriptorSets_.data());
    if (result != VK_SUCCESS) {
        ALOG_E(TAG, "Failed to allocate descriptor sets: %d", result);
        return false;
    }
    
    // 更新 descriptor sets（平面 + LUT binding 3）
    for (size_t i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
        size_t n = yuvTextures_.size();
        // imageInfos 必须与 descriptorWrites 生命周期一致，避免悬空指针
        std::vector<VkDescriptorImageInfo> imageInfos(n + 1);
        std::vector<VkWriteDescriptorSet> descriptorWrites(n + 1);

        for (size_t j = 0; j < n; j++) {
            imageInfos[j].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            imageInfos[j].imageView = yuvTextures_[j].imageView;
            imageInfos[j].sampler = yuvTextures_[j].sampler;

            descriptorWrites[j].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            descriptorWrites[j].dstSet = descriptorSets_[i];
            descriptorWrites[j].dstBinding = j;
            descriptorWrites[j].dstArrayElement = 0;
            descriptorWrites[j].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            descriptorWrites[j].descriptorCount = 1;
            descriptorWrites[j].pImageInfo = &imageInfos[j];
        }

        // LUT binding 3 -> 常驻 lutTexture_
        imageInfos[n].imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        imageInfos[n].imageView = lutTexture_.imageView;
        imageInfos[n].sampler = lutTexture_.sampler;
        descriptorWrites[n].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        descriptorWrites[n].dstSet = descriptorSets_[i];
        descriptorWrites[n].dstBinding = LUT_BINDING;
        descriptorWrites[n].dstArrayElement = 0;
        descriptorWrites[n].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        descriptorWrites[n].descriptorCount = 1;
        descriptorWrites[n].pImageInfo = &imageInfos[n];

        vkUpdateDescriptorSets(device_, static_cast<uint32_t>(descriptorWrites.size()),
                              descriptorWrites.data(), 0, nullptr);
    }
    
    ALOG_I(TAG, "Descriptor sets created");
    return true;
}

// ========== 纹理管理 ==========

bool SkyVkRenderer::createTextureImage(TextureImage& texture, VkFormat format,
                                      uint32_t width, uint32_t height) {
    FUNC_TRACE();
    
    // 创建 image
    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.extent.width = width;
    imageInfo.extent.height = height;
    imageInfo.extent.depth = 1;
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.format = format;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    
    VkResult result = vkCreateImage(device_, &imageInfo, nullptr, &texture.image);
    if (result != VK_SUCCESS) {
        ALOG_E(TAG, "Failed to create texture image: %d", result);
        return false;
    }
    
    // 分配内存
    VkMemoryRequirements memRequirements;
    vkGetImageMemoryRequirements(device_, texture.image, &memRequirements);
    
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memRequirements.size;
    allocInfo.memoryTypeIndex = findMemoryType(memRequirements.memoryTypeBits,
                                               VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    
    result = vkAllocateMemory(device_, &allocInfo, nullptr, &texture.memory);
    if (result != VK_SUCCESS) {
        ALOG_E(TAG, "Failed to allocate texture memory: %d", result);
        vkDestroyImage(device_, texture.image, nullptr);
        return false;
    }
    
    vkBindImageMemory(device_, texture.image, texture.memory, 0);
    
    texture.width = width;
    texture.height = height;
    
    return true;
}

bool SkyVkRenderer::createTextureImageView(TextureImage& texture, VkFormat format) {
    FUNC_TRACE();
    
    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = texture.image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = format;
    viewInfo.components.r = VK_COMPONENT_SWIZZLE_IDENTITY;
    viewInfo.components.g = VK_COMPONENT_SWIZZLE_IDENTITY;
    viewInfo.components.b = VK_COMPONENT_SWIZZLE_IDENTITY;
    viewInfo.components.a = VK_COMPONENT_SWIZZLE_IDENTITY;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.baseMipLevel = 0;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.baseArrayLayer = 0;
    viewInfo.subresourceRange.layerCount = 1;
    
    VkResult result = vkCreateImageView(device_, &viewInfo, nullptr, &texture.imageView);
    if (result != VK_SUCCESS) {
        ALOG_E(TAG, "Failed to create texture image view: %d", result);
        return false;
    }
    
    return true;
}

bool SkyVkRenderer::createTextureSampler(TextureImage& texture) {
    FUNC_TRACE();
    
    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.anisotropyEnable = VK_FALSE;
    samplerInfo.maxAnisotropy = 1.0f;
    samplerInfo.borderColor = VK_BORDER_COLOR_INT_OPAQUE_BLACK;
    samplerInfo.unnormalizedCoordinates = VK_FALSE;
    samplerInfo.compareEnable = VK_FALSE;
    samplerInfo.compareOp = VK_COMPARE_OP_ALWAYS;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
    samplerInfo.mipLodBias = 0.0f;
    samplerInfo.minLod = 0.0f;
    samplerInfo.maxLod = 0.0f;
    
    VkResult result = vkCreateSampler(device_, &samplerInfo, nullptr, &texture.sampler);
    if (result != VK_SUCCESS) {
        ALOG_E(TAG, "Failed to create texture sampler: %d", result);
        return false;
    }
    
    return true;
}

bool SkyVkRenderer::uploadTextureData(TextureImage& texture, const void* data,
                                     VkDeviceSize size, uint32_t width, uint32_t height,
                                     uint32_t rowPitch, VkFormat format) {
    // 检查纹理尺寸是否匹配，不匹配则重建
    if (texture.width != width || texture.height != height) {
        ALOG_I(TAG, "Texture size changed: %dx%d -> %dx%d, recreating",
               texture.width, texture.height, width, height);
        
        vkDeviceWaitIdle(device_);
        
        if (texture.sampler != VK_NULL_HANDLE) {
            vkDestroySampler(device_, texture.sampler, nullptr);
        }
        if (texture.imageView != VK_NULL_HANDLE) {
            vkDestroyImageView(device_, texture.imageView, nullptr);
        }
        if (texture.image != VK_NULL_HANDLE) {
            vkDestroyImage(device_, texture.image, nullptr);
        }
        if (texture.memory != VK_NULL_HANDLE) {
            vkFreeMemory(device_, texture.memory, nullptr);
        }
        texture = {};
        
        if (!createTextureImage(texture, format, width, height)) {
            ALOG_E(TAG, "Failed to recreate texture image");
            return false;
        }
        if (!createTextureImageView(texture, format)) {
            ALOG_E(TAG, "Failed to recreate texture image view");
            return false;
        }
        if (!createTextureSampler(texture)) {
            ALOG_E(TAG, "Failed to recreate texture sampler");
            return false;
        }
        
        descriptorSetsNeedUpdate_ = true;
    }
    
    // 创建或调整 staging buffer
    VkDeviceSize requiredSize = std::max(size, static_cast<VkDeviceSize>(4 * 1024 * 1024));
    if (stagingBuffer_ == VK_NULL_HANDLE || stagingBufferSize_ < size) {
        // 清理旧的 staging buffer
        if (stagingBuffer_ != VK_NULL_HANDLE) {
            vkDestroyBuffer(device_, stagingBuffer_, nullptr);
            stagingBuffer_ = VK_NULL_HANDLE;
        }
        if (stagingBufferMemory_ != VK_NULL_HANDLE) {
            vkFreeMemory(device_, stagingBufferMemory_, nullptr);
            stagingBufferMemory_ = VK_NULL_HANDLE;
        }
        
        VkBufferCreateInfo bufferInfo{};
        bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufferInfo.size = requiredSize;
        bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        
        VkResult result = vkCreateBuffer(device_, &bufferInfo, nullptr, &stagingBuffer_);
        if (result != VK_SUCCESS) {
            ALOG_E(TAG, "Failed to create staging buffer: %d", result);
            return false;
        }
        
        VkMemoryRequirements memRequirements;
        vkGetBufferMemoryRequirements(device_, stagingBuffer_, &memRequirements);
        
        VkMemoryAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize = memRequirements.size;
        allocInfo.memoryTypeIndex = findMemoryType(memRequirements.memoryTypeBits,
                                                   VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                                   VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        
        result = vkAllocateMemory(device_, &allocInfo, nullptr, &stagingBufferMemory_);
        if (result != VK_SUCCESS) {
            ALOG_E(TAG, "Failed to allocate staging buffer memory: %d", result);
            vkDestroyBuffer(device_, stagingBuffer_, nullptr);
            stagingBuffer_ = VK_NULL_HANDLE;
            return false;
        }
        
        vkBindBufferMemory(device_, stagingBuffer_, stagingBufferMemory_, 0);
        stagingBufferSize_ = requiredSize;
    }
    
    // 复制数据到 staging buffer
    void* mappedData;
    vkMapMemory(device_, stagingBufferMemory_, 0, size, 0, &mappedData);
    memcpy(mappedData, data, size);
    vkUnmapMemory(device_, stagingBufferMemory_);
    
    // 转换 image layout
    VkCommandBufferAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocInfo.commandPool = commandPool_;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;
    
    VkCommandBuffer commandBuffer;
    vkAllocateCommandBuffers(device_, &allocInfo, &commandBuffer);
    
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(commandBuffer, &beginInfo);
    
    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = texture.image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.baseMipLevel = 0;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.baseArrayLayer = 0;
    barrier.subresourceRange.layerCount = 1;
    barrier.srcAccessMask = 0;
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
    
    // 复制 buffer 到 image
    VkBufferImageCopy region{};
    region.bufferOffset = 0;
    region.bufferRowLength = rowPitch;
    region.bufferImageHeight = 0;
    region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.imageSubresource.mipLevel = 0;
    region.imageSubresource.baseArrayLayer = 0;
    region.imageSubresource.layerCount = 1;
    region.imageOffset = {0, 0, 0};
    region.imageExtent = {width, height, 1};
    
    vkCmdCopyBufferToImage(commandBuffer, stagingBuffer_, texture.image,
                          VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
    
    // 转换到 shader read only layout
    barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &barrier);
    
    vkEndCommandBuffer(commandBuffer);
    
    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer;
    
    vkQueueSubmit(graphicsQueue_, 1, &submitInfo, VK_NULL_HANDLE);
    vkQueueWaitIdle(graphicsQueue_);
    
    vkFreeCommandBuffers(device_, commandPool_, 1, &commandBuffer);
    
    return true;
}

// ========== 渲染相关 ==========

void SkyVkRenderer::recordCommandBuffer(VkCommandBuffer commandBuffer, uint32_t imageIndex) {
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    
    vkBeginCommandBuffer(commandBuffer, &beginInfo);
    
    VkRenderPassBeginInfo renderPassInfo{};
    renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    renderPassInfo.renderPass = renderPass_;
    renderPassInfo.framebuffer = framebuffers_[imageIndex];
    renderPassInfo.renderArea.offset = {0, 0};
    renderPassInfo.renderArea.extent = swapchainExtent_;
    
    VkClearValue clearColor = {{{0.0f, 0.0f, 0.0f, 1.0f}}};
    renderPassInfo.clearValueCount = 1;
    renderPassInfo.pClearValues = &clearColor;
    
    vkCmdBeginRenderPass(commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
    
    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline_);

    // LUT 开关/强度（push constant）
    vkCmdPushConstants(commandBuffer, pipelineLayout_, VK_SHADER_STAGE_FRAGMENT_BIT,
                       0, 3 * sizeof(float), pushValues_);

    // 设置动态 viewport 和 scissor（跟随 swapchain 尺寸）
    VkViewport viewport{};
    viewport.x = 0.0f;
    viewport.y = 0.0f;
    viewport.width = static_cast<float>(swapchainExtent_.width);
    viewport.height = static_cast<float>(swapchainExtent_.height);
    viewport.minDepth = 0.0f;
    viewport.maxDepth = 1.0f;
    vkCmdSetViewport(commandBuffer, 0, 1, &viewport);
    
    VkRect2D scissor{};
    scissor.offset = {0, 0};
    scissor.extent = swapchainExtent_;
    vkCmdSetScissor(commandBuffer, 0, 1, &scissor);
    
    // 绑定 descriptor set
    vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                           pipelineLayout_, 0, 1, &descriptorSets_[currentFrameIndex_], 0, nullptr);
    
    // 绑定持久化 vertex buffer
    VkBuffer vertexBuffers[] = {vertexBuffer_};
    VkDeviceSize offsets[] = {0};
    vkCmdBindVertexBuffers(commandBuffer, 0, 1, vertexBuffers, offsets);
    
    vkCmdDraw(commandBuffer, 4, 1, 0, 0);
    
    vkCmdEndRenderPass(commandBuffer);
    
    vkEndCommandBuffer(commandBuffer);
}

bool SkyVkRenderer::createVertexBuffer() {
    FUNC_TRACE();
    
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = sizeof(vertices);
    bufferInfo.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    
    VkResult result = vkCreateBuffer(device_, &bufferInfo, nullptr, &vertexBuffer_);
    if (result != VK_SUCCESS) {
        ALOG_E(TAG, "Failed to create vertex buffer: %d", result);
        return false;
    }
    
    VkMemoryRequirements memRequirements;
    vkGetBufferMemoryRequirements(device_, vertexBuffer_, &memRequirements);
    
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memRequirements.size;
    allocInfo.memoryTypeIndex = findMemoryType(memRequirements.memoryTypeBits,
                                               VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                               VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    
    result = vkAllocateMemory(device_, &allocInfo, nullptr, &vertexBufferMemory_);
    if (result != VK_SUCCESS) {
        ALOG_E(TAG, "Failed to allocate vertex buffer memory: %d", result);
        vkDestroyBuffer(device_, vertexBuffer_, nullptr);
        vertexBuffer_ = VK_NULL_HANDLE;
        return false;
    }
    
    vkBindBufferMemory(device_, vertexBuffer_, vertexBufferMemory_, 0);
    
    void* mappedData;
    vkMapMemory(device_, vertexBufferMemory_, 0, sizeof(vertices), 0, &mappedData);
    memcpy(mappedData, vertices, sizeof(vertices));
    vkUnmapMemory(device_, vertexBufferMemory_);
    
    ALOG_I(TAG, "Vertex buffer created");
    return true;
}

bool SkyVkRenderer::recreateSwapchain() {
    FUNC_TRACE();
    
    vkDeviceWaitIdle(device_);
    
    cleanupSwapchain();
    
    if (!createSwapchain()) {
        return false;
    }
    
    if (!createImageViews()) {
        return false;
    }
    
    if (!createFramebuffers()) {
        return false;
    }
    
    if (!createCommandBuffers()) {
        return false;
    }
    
    return true;
}

void SkyVkRenderer::cleanupSwapchain() {
    FUNC_TRACE();
    
    for (auto framebuffer : framebuffers_) {
        if (framebuffer != VK_NULL_HANDLE) {
            vkDestroyFramebuffer(device_, framebuffer, nullptr);
        }
    }
    framebuffers_.clear();
    
    if (commandPool_ != VK_NULL_HANDLE) {
        vkFreeCommandBuffers(device_, commandPool_, static_cast<uint32_t>(commandBuffers_.size()),
                            commandBuffers_.data());
    }
    commandBuffers_.clear();
    
    for (auto imageView : swapchainImageViews_) {
        if (imageView != VK_NULL_HANDLE) {
            vkDestroyImageView(device_, imageView, nullptr);
        }
    }
    swapchainImageViews_.clear();
    
    if (swapchain_ != VK_NULL_HANDLE) {
        vkDestroySwapchainKHR(device_, swapchain_, nullptr);
        swapchain_ = VK_NULL_HANDLE;
    }
}

void SkyVkRenderer::cleanupTextureResources() {
    FUNC_TRACE();
    
    for (auto& texture : yuvTextures_) {
        if (texture.sampler != VK_NULL_HANDLE) {
            vkDestroySampler(device_, texture.sampler, nullptr);
        }
        if (texture.imageView != VK_NULL_HANDLE) {
            vkDestroyImageView(device_, texture.imageView, nullptr);
        }
        if (texture.image != VK_NULL_HANDLE) {
            vkDestroyImage(device_, texture.image, nullptr);
        }
        if (texture.memory != VK_NULL_HANDLE) {
            vkFreeMemory(device_, texture.memory, nullptr);
        }
    }
    yuvTextures_.clear();
}

// ========== 辅助方法 ==========

uint32_t SkyVkRenderer::findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memProperties;
    vkGetPhysicalDeviceMemoryProperties(physicalDevice_, &memProperties);
    
    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) && (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    
    ALOG_E(TAG, "Failed to find suitable memory type");
    return UINT32_MAX;
}

bool SkyVkRenderer::hasStencilComponent(VkFormat format) {
    return format == VK_FORMAT_D32_SFLOAT_S8_UINT || format == VK_FORMAT_D24_UNORM_S8_UINT;
}

SkyVkRenderer::PixelFormatType SkyVkRenderer::getPixelFormatType(AVPixelFormat format) {
    switch (format) {
        case AV_PIX_FMT_YUV420P:
            return PixelFormatType::YUV420P;
        case AV_PIX_FMT_NV12:
            return PixelFormatType::NV12;
        case AV_PIX_FMT_NV21:
            return PixelFormatType::NV21;
        case AV_PIX_FMT_RGBA:
        case AV_PIX_FMT_BGRA:
            return PixelFormatType::RGBA;
        default:
            return PixelFormatType::UNKNOWN;
    }
}

VkFormat SkyVkRenderer::getVkFormat(PixelFormatType type, int planeIndex) {
    switch (type) {
        case PixelFormatType::YUV420P:
            return VK_FORMAT_R8_UNORM;
        case PixelFormatType::NV12:
            return planeIndex == 0 ? VK_FORMAT_R8_UNORM : VK_FORMAT_R8G8_UNORM;
        case PixelFormatType::NV21:
            return planeIndex == 0 ? VK_FORMAT_R8_UNORM : VK_FORMAT_R8G8_UNORM;
        case PixelFormatType::RGBA:
            return VK_FORMAT_R8G8B8A8_UNORM;
        default:
            return VK_FORMAT_R8_UNORM;
    }
}

SkyVkRenderer::QueueFamilyIndices SkyVkRenderer::findQueueFamilies(VkPhysicalDevice device) {
    QueueFamilyIndices indices;
    
    uint32_t queueFamilyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(device, &queueFamilyCount, nullptr);
    
    std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(device, &queueFamilyCount, queueFamilies.data());
    
    int i = 0;
    for (const auto& queueFamily : queueFamilies) {
        if (queueFamily.queueFlags & VK_QUEUE_GRAPHICS_BIT) {
            indices.graphicsFamily = i;
        }
        
        VkBool32 presentSupport = false;
        vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface_, &presentSupport);
        
        if (presentSupport) {
            indices.presentFamily = i;
        }
        
        if (indices.isComplete()) {
            break;
        }
        
        i++;
    }
    
    return indices;
}

bool SkyVkRenderer::checkDeviceExtensionSupport(VkPhysicalDevice device) {
    uint32_t extensionCount;
    vkEnumerateDeviceExtensionProperties(device, nullptr, &extensionCount, nullptr);
    
    std::vector<VkExtensionProperties> availableExtensions(extensionCount);
    vkEnumerateDeviceExtensionProperties(device, nullptr, &extensionCount, availableExtensions.data());
    
    std::set<std::string> requiredExtensions = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    
    for (const auto& extension : availableExtensions) {
        requiredExtensions.erase(extension.extensionName);
    }
    
    return requiredExtensions.empty();
}

bool SkyVkRenderer::isDeviceSuitable(VkPhysicalDevice device) {
    QueueFamilyIndices indices = findQueueFamilies(device);
    
    bool extensionsSupported = checkDeviceExtensionSupport(device);
    
    bool swapChainAdequate = false;
    if (extensionsSupported) {
        uint32_t formatCount;
        vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface_, &formatCount, nullptr);
        
        uint32_t presentModeCount;
        vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface_, &presentModeCount, nullptr);
        
        swapChainAdequate = formatCount != 0 && presentModeCount != 0;
    }
    
    return indices.isComplete() && extensionsSupported && swapChainAdequate;
}

VkSurfaceFormatKHR SkyVkRenderer::chooseSwapSurfaceFormat(const std::vector<VkSurfaceFormatKHR>& availableFormats) {
    // 优先选择 UNORM 格式（shader 输出 RGBA，Vulkan 会自动处理通道映射）
    // 按优先级：R8G8B8A8_UNORM > B8G8R8A8_UNORM > R8G8B8A8_SRGB > B8G8R8A8_SRGB
    const VkFormat preferredFormats[] = {
        VK_FORMAT_R8G8B8A8_UNORM,
        VK_FORMAT_B8G8R8A8_UNORM,
        VK_FORMAT_R8G8B8A8_SRGB,
        VK_FORMAT_B8G8R8A8_SRGB,
    };
    for (auto preferred : preferredFormats) {
        for (const auto& availableFormat : availableFormats) {
            if (availableFormat.format == preferred) {
                ALOG_I(TAG, "Selected swap format: %d", preferred);
                return availableFormat;
            }
        }
    }
    ALOG_W(TAG, "No preferred format found, using first available format: %d", availableFormats[0].format);
    return availableFormats[0];
}

VkPresentModeKHR SkyVkRenderer::chooseSwapPresentMode(const std::vector<VkPresentModeKHR>& availablePresentModes) {
    for (const auto& availablePresentMode : availablePresentModes) {
        if (availablePresentMode == VK_PRESENT_MODE_MAILBOX_KHR) {
            return availablePresentMode;
        }
    }
    return VK_PRESENT_MODE_FIFO_KHR;
}

VkExtent2D SkyVkRenderer::chooseSwapExtent(const VkSurfaceCapabilitiesKHR& capabilities) {
    // 使用 ANativeWindow 的实际尺寸，而非 capabilities.currentExtent
    // 因为使用 IDENTITY preTransform 时，capabilities.currentExtent 可能返回未旋转的尺寸
    VkExtent2D actualExtent;
    if (currentWindow_) {
        actualExtent.width = static_cast<uint32_t>(ANativeWindow_getWidth(currentWindow_));
        actualExtent.height = static_cast<uint32_t>(ANativeWindow_getHeight(currentWindow_));
    } else if (capabilities.currentExtent.width != UINT32_MAX) {
        return capabilities.currentExtent;
    } else {
        actualExtent = {800, 600};
    }
    
    actualExtent.width = std::max(capabilities.minImageExtent.width,
                                  std::min(capabilities.maxImageExtent.width, actualExtent.width));
    actualExtent.height = std::max(capabilities.minImageExtent.height,
                                   std::min(capabilities.maxImageExtent.height, actualExtent.height));
    
    return actualExtent;
}

VkShaderModule SkyVkRenderer::createShaderModule(const uint32_t* code, size_t codeSize) {
    VkShaderModuleCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    createInfo.codeSize = codeSize;
    createInfo.pCode = code;
    
    VkShaderModule shaderModule;
    VkResult result = vkCreateShaderModule(device_, &createInfo, nullptr, &shaderModule);
    
    if (result != VK_SUCCESS) {
        ALOG_E(TAG, "Failed to create shader module: %d", result);
        return VK_NULL_HANDLE;
    }
    
    return shaderModule;
}
