/**
 * Vulkan 渲染器实现
 * 支持将 YUV/RGB 像素格式的视频帧渲染到 ANativeWindow
 */
#ifndef SKYMEDIAPLAYER_PLAYER_SKY_VK_RENDERER_H
#define SKYMEDIAPLAYER_PLAYER_SKY_VK_RENDERER_H

#include "skyrenderer.h"
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <memory>
#include <vector>
#include <mutex>
#include <cstdint>
#include <unordered_map>

class SkyVkRenderer : public SkyRenderer {
public:
    SkyVkRenderer();
    ~SkyVkRenderer() override;

    // SkyRenderer 接口实现
    bool displayImage(EGLNativeWindowType window, AVFrame *frame) override;
    bool isValid() override;
    void terminate() override;

    void setLut(const uint8_t* rgba, int len, float intensity) override;
    void clearLut() override;

private:
    // Vulkan 核心对象
    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue graphicsQueue_ = VK_NULL_HANDLE;
    VkQueue presentQueue_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    
    // Swapchain 相关
    std::vector<VkImage> swapchainImages_;
    std::vector<VkImageView> swapchainImageViews_;
    VkFormat swapchainImageFormat_;
    VkExtent2D swapchainExtent_;
    
    // 渲染管线
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline graphicsPipeline_ = VK_NULL_HANDLE;
    
    // 帧缓冲和命令缓冲
    std::vector<VkFramebuffer> framebuffers_;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> commandBuffers_;
    
    // 同步对象
    std::vector<VkSemaphore> imageAvailableSemaphores_;
    std::vector<VkSemaphore> renderFinishedSemaphores_;
    std::vector<VkFence> inFlightFences_;
    
    // 纹理资源
    struct TextureImage {
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkImageView imageView = VK_NULL_HANDLE;
        VkSampler sampler = VK_NULL_HANDLE;
        uint32_t width = 0;
        uint32_t height = 0;
    };
    
    std::vector<TextureImage> yuvTextures_;  // YUV420P: 3, NV12/NV21: 2, RGBA: 1
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    std::vector<VkDescriptorSet> descriptorSets_;
    
    // 纹理上传
    VkBuffer stagingBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory stagingBufferMemory_ = VK_NULL_HANDLE;
    VkDeviceSize stagingBufferSize_ = 0;
    
    // 持久化 vertex buffer
    VkBuffer vertexBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory vertexBufferMemory_ = VK_NULL_HANDLE;
    
    // descriptor sets 更新标记
    bool descriptorSetsNeedUpdate_ = false;

    // LUT（GPUImage 512x512 lookup）—— 常驻纹理绑 binding=3，开关/强度走 push constant
    static constexpr int LUT_BINDING = 3;
    TextureImage lutTexture_;
    std::mutex   lutMtx_;
    std::vector<uint8_t> lutPending_;   // 512*512*4，待上传
    bool  lutPendingDirty_ = false;
    bool  lutEnabled_ = false;
    float lutIntensity_ = 1.0f;
    float lutPushValue_ = 0.0f;         // = enabled ? intensity : 0，每帧推送
    
    // 状态追踪
    EGLNativeWindowType currentWindow_ = nullptr;
    AVPixelFormat currentPixelFormat_ = AV_PIX_FMT_NONE;
    int currentFrameIndex_ = 0;
    bool isInitialized_ = false;
    bool framebufferResized_ = false;
    
    // 像素格式支持
    enum class PixelFormatType {
        YUV420P,
        NV12,
        NV21,
        RGBA,
        UNKNOWN
    };
    
    // 初始化方法
    bool createInstance();
    bool pickPhysicalDevice();
    bool createLogicalDevice();
    bool createSurface(EGLNativeWindowType window);
    bool createSwapchain();
    bool createImageViews();
    bool createRenderPass();
    bool createGraphicsPipeline();
    bool createFramebuffers();
    bool createCommandPool();
    bool createCommandBuffers();
    bool createSyncObjects();
    bool createTextureResources(AVPixelFormat format);
    bool createDescriptorSets();
    
    // 纹理管理
    bool createTextureImage(TextureImage& texture, VkFormat format, 
                           uint32_t width, uint32_t height);
    bool createTextureImageView(TextureImage& texture, VkFormat format);
    bool createTextureSampler(TextureImage& texture);
    bool uploadTextureData(TextureImage& texture, const void* data,
                          VkDeviceSize size, uint32_t width, uint32_t height,
                          uint32_t rowPitch, VkFormat format);
    bool createVertexBuffer();
    bool ensureLutTexture();   // 创建常驻 512x512 LUT 纹理（首次）

    // 渲染相关
    void recordCommandBuffer(VkCommandBuffer commandBuffer, uint32_t imageIndex);
    bool recreateSwapchain();
    void cleanupSwapchain();
    void cleanupTextureResources();
    
    // 辅助方法
    uint32_t findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties);
    bool hasStencilComponent(VkFormat format);
    PixelFormatType getPixelFormatType(AVPixelFormat format);
    VkFormat getVkFormat(PixelFormatType type, int planeIndex);
    
    // Queue Family 索引
    struct QueueFamilyIndices {
        uint32_t graphicsFamily = UINT32_MAX;
        uint32_t presentFamily = UINT32_MAX;
        bool isComplete() const { return graphicsFamily != UINT32_MAX && presentFamily != UINT32_MAX; }
    };
    
    QueueFamilyIndices findQueueFamilies(VkPhysicalDevice device);
    bool checkDeviceExtensionSupport(VkPhysicalDevice device);
    bool isDeviceSuitable(VkPhysicalDevice device);
    VkSurfaceFormatKHR chooseSwapSurfaceFormat(const std::vector<VkSurfaceFormatKHR>& availableFormats);
    VkPresentModeKHR chooseSwapPresentMode(const std::vector<VkPresentModeKHR>& availablePresentModes);
    VkExtent2D chooseSwapExtent(const VkSurfaceCapabilitiesKHR& capabilities);
    
    // Shader 模块创建
    VkShaderModule createShaderModule(const uint32_t* code, size_t codeSize);
    
    // 常量
    static constexpr int MAX_FRAMES_IN_FLIGHT = 2;
    static constexpr const char* TAG = "SkyVkRenderer";
};

#endif // SKYMEDIAPLAYER_PLAYER_SKY_VK_RENDERER_H
