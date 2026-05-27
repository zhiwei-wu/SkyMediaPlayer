# SkyPlayer 自动化自测指南

## 概述

SkyPlayer 内置了自动化测试页面，用于在开发新功能后快速验证播放器核心功能的正确性。通过编译选项控制，启用后 app 启动时自动进入测试页面，执行全部测试用例并展示结果。

## 启用方式

### 1. 修改编译选项

在 `gradle.properties` 中设置：

```properties
# 设置为 true 启用自动化测试模式
skyAutoTestEnabled=true
```

### 2. 编译安装启动

```bash
# 编译并安装到设备
./gradlew :app:installDebug

# 启动 app（自动进入测试页面）
adb shell am start -n imt.skymediaplayer.demo/.MainActivity
```

### 3. 恢复正常模式

测试完成后，将编译选项改回 `false`：

```properties
skyAutoTestEnabled=false
```

## 工作原理

### 编译选项控制

- `gradle.properties` 中的 `skyAutoTestEnabled` 会生成 `BuildConfig.AUTO_TEST_ENABLED` 编译常量
- `MainActivity.onCreate()` 检查该常量，为 `true` 时自动跳转到 `AutoTestActivity`
- 测试页面支持手动点击"开始测试"或通过 `auto_start=true` intent extra 自动开始

### 测试页面

`AutoTestActivity` 位于 `app/src/main/java/imt/skymediaplayer/demo/AutoTestActivity.kt`

页面结构：
- **顶部**：标题栏 + 测试摘要（通过/失败/跳过计数）
- **进度条**：整体测试进度
- **当前测试状态**：正在执行的测试用例名称
- **视频预览区**：实时显示当前测试的视频画面和配置信息
- **测试结果列表**：每个测试用例的状态和详细结果
- **底部操作栏**：开始测试 / 跳过到主页

## 测试矩阵

### 解码 × 渲染组合测试

测试所有解码模式和渲染后端的组合，验证播放功能正常：

| 解码模式 | 渲染后端 | 视频源 |
|---------|---------|--------|
| 软解 (FFmpeg) | OpenGL ES | 在线 HTTPS / HLS |
| 软解 (FFmpeg) | Vulkan | 在线 HTTPS / HLS |
| 硬解直渲 (Surface) | OpenGL ES | 在线 HTTPS / HLS |
| 硬解直渲 (Surface) | Vulkan | 在线 HTTPS / HLS |
| 硬解 Buffer | OpenGL ES | 在线 HTTPS / HLS |
| 硬解 Buffer | Vulkan | 在线 HTTPS / HLS |
| 自动回退 | OpenGL ES | 在线 HTTPS / HLS |
| 自动回退 | Vulkan | 在线 HTTPS / HLS |

每个组合测试流程：
1. 配置解码模式和渲染后端
2. 设置视频源并开始播放
3. 等待播放启动（超时 15s）
4. 播放 5s 后验证：`isPlaying()` 为 true 且 `getCurrentPosition() > 0`
5. 记录结果（通过/失败 + 详细信息）

### 在线视频测试

测试用的在线视频源：
- **HTTPS**: `https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4`
- **HLS (m3u8)**: `https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8`

### AI 字幕测试

使用英文语音视频测试 Whisper AI 字幕功能：
- **视频源**: `https://media.w3.org/2010/05/sintel/trailer.mp4` (Sintel 预告片)
- **配置**: 软解 + OpenGL ES（最稳定组合）
- **语言**: 英文 (`en`)

测试流程：
1. 播放视频并等待播放启动
2. 检查 Whisper 模型是否就绪（未就绪则跳过）
3. 暂停播放，启用 Whisper（`setWhisperEnabled(true, modelPath, "en", 5)`）
4. 等待预缓冲完成回调（超时 30s）
5. 恢复播放，等待 5s 检查是否收到字幕回调
6. 验证结果：收到字幕文本或预缓冲字幕数 > 0 即为通过

## 测试结果判定

每个测试用例的结果状态：
- ✅ **通过 (PASSED)**: 播放正常，各项指标符合预期
- ❌ **失败 (FAILED)**: 播放异常、超时或发生错误
- ⏭️ **跳过 (SKIPPED)**: 前置条件不满足（如 Whisper 模型未就绪）

## AI Copilot 自测流程

当用户要求"自行验证"时，按以下步骤执行：

### 步骤 1：启用测试模式

```bash
# 修改 gradle.properties
sed -i '' 's/skyAutoTestEnabled=false/skyAutoTestEnabled=true/' gradle.properties
```

### 步骤 2：编译安装

```bash
./gradlew :app:installDebug
```

### 步骤 3：启动测试

```bash
adb shell am start -n imt.skymediaplayer.demo/.MainActivity
```

### 步骤 4：等待测试完成

通过 logcat 监控测试进度：

```bash
adb logcat -s AutoTestActivity | cat
```

关键日志：
- `Starting test suite with N test cases` - 测试开始
- `Test result [PASSED]: xxx` - 单个测试通过
- `Test result [FAILED]: xxx` - 单个测试失败
- `Test suite complete: X passed, Y failed, Z skipped` - 测试完成

### 步骤 5：恢复正常模式

```bash
sed -i '' 's/skyAutoTestEnabled=true/skyAutoTestEnabled=false/' gradle.properties
```

## 相关文件

| 文件 | 说明 |
|------|------|
| `app/src/main/java/imt/skymediaplayer/demo/AutoTestActivity.kt` | 自动化测试 Activity |
| `app/src/main/res/layout/activity_auto_test.xml` | 测试页面布局 |
| `app/build.gradle.kts` | 编译选项 `AUTO_TEST_ENABLED` |
| `gradle.properties` | 开关 `skyAutoTestEnabled` |
| `app/src/main/AndroidManifest.xml` | Activity 注册 |
| `app/src/main/java/imt/skymediaplayer/demo/MainActivity.kt` | 自动跳转逻辑 |
