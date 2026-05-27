# SkyPlayer 开发参考详情

本文档提供各开发场景的详细说明和代码目录索引。

## 开发场景详情

### 1. 播放器整体架构
→ 参考 [player_structure.md](player_structure.md)

**适用场景**：
- 新功能开发前的架构理解
- 添加新的 JNI 方法
- 修改消息队列机制
- 扩展播放器状态管理

### 2. 视频解码与渲染
→ 参考 [video_decode_play.md](video_decode_play.md)

**适用场景**：
- 添加新的视频编解码器支持
- 优化视频渲染性能
- 添加新的像素格式渲染器
- 修改视频滤镜处理

### 3. 音频解码与输出
→ 参考 [audio_decode_play.md](audio_decode_play.md)

**适用场景**：
- 优化音频延迟
- 添加音频效果处理
- 修改音频重采样逻辑
- 处理音频焦点策略

### 4. 播控 UI 开发
→ 参考 [media_controller.md](media_controller.md)

**适用场景**：
- 自定义播放控制器
- 添加新的缩放模式
- 优化 Surface 生命周期管理
- 集成新的 UI 组件

### 5. 音画同步优化
→ 参考 [audio_video_sync_handle.md](audio_video_sync_handle.md)

**适用场景**：
- 优化直播流同步
- 调整同步阈值参数
- 修改帧丢弃策略
- 处理变速播放同步

### 6. AI 字幕功能
→ 参考 [ai_subtitle_func.md](ai_subtitle_func.md)

**适用场景**：
- 集成新的语音识别模型
- 优化字幕延迟
- 添加多语言支持
- 自定义字幕显示样式

### 7. GPU 加速开发
→ 参考 [gpu_acceleration.md](gpu_acceleration.md)

**适用场景**：
- 优化 GPU 推理性能
- 处理 Vulkan 驱动兼容性
- 实现 GPU/CPU 自动回退
- 调试 GPU 初始化问题

### 8. Vulkan 视频渲染
→ 参考 [vulkan_rendering.md](vulkan_rendering.md)

**适用场景**：
- Vulkan 渲染管线开发和调试
- 添加新的像素格式支持
- 修改 YUV→RGB 色彩空间转换公式
- 处理 Swapchain 格式兼容性问题
- SPIR-V 着色器编译和管理
- Descriptor Sets 纹理绑定问题排查
- 不同 GPU 厂商（Adreno/Mali）适配

### 9. 阶段开发保存（多仓库关联提交）
→ 参考 [stage_commit.md](stage_commit.md)

**适用场景**：
- 完成 FFmpeg 编译并升级到 SkyPlayer
- 完成 Whisper 集成并更新播放器
- 完成 OpenSSL 配置并同步到项目
- 任何涉及多仓库协作的功能开发

**关联仓库**：
- SkyPlayer（主项目）：`/Users/uc/code/SkyPlayer`
- FFmpeg：`/Users/uc/code/zhiwei-wu/FFmpeg`
- openssl：`/Users/uc/code/zhiwei-wu/openssl`
- whisper.cpp：`/Users/uc/code/zhiwei-wu/whisper.cpp`

**Commit 规则**：
- 优先使用用户提供的 commit message
- 若未提供，根据当前开发的功能自动生成

## 核心代码目录

| 层级 | 目录 | 说明 |
|------|------|------|
| Java/Kotlin | `skymediaplayer/src/main/java/imt/zw/skymediaplayer/` | 播放器接口和 UI 组件 |
| JNI | `skymediaplayer/src/main/cpp/skymediaplayer_jni.cpp` | JNI 桥接层 |
| Native | `skymediaplayer/src/main/cpp/player/` | 播放器核心、渲染器、音频输出 |
| FFmpeg | `skymediaplayer/src/main/cpp/ffplay/ffplay.c` | FFplay 核心引擎 |

## 开发流程建议

1. **理解架构**：先阅读 `player_structure.md` 了解整体架构
2. **定位模块**：根据开发需求找到对应的参考文档
3. **阅读代码**：结合参考文档中的代码路径阅读源码
4. **实现功能**：遵循现有代码风格和架构设计
5. **测试验证**：确保修改不影响现有功能

## 构建验证

```bash
# 编译播放器库
./gradlew :skymediaplayer:assembleDebug

# 编译示例应用
./gradlew :app:assembleDebug
```
