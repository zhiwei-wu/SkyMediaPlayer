# AI 驱动的自主开发闭环：从"人工测试员"到"需求驱动"的转变

## 背景

在 SkyPlayer 的开发过程中，AI 编程助手已经深度参与了代码编写、架构设计、问题排查等工作。然而，在之前的协作模式中，存在一个明显的效率瓶颈：

**每次 AI 修改代码后，都需要开发者手动操作设备来验证结果。**

开发者实际上充当了 AI 的"测试工程师"——点击播放按钮、观察画面、反馈结果。这种模式下，一个问题的修复往往需要 3~5 轮人工介入，严重拖慢了开发节奏。

> 架构对比图见：[ai_auto_dev.drawio](ai_auto_dev.drawio)（第 1 页：AI 自主开发闭环）

![AI 自主开发闭环](AI_self-verification_process.png)

**实际效果截图：**

![AI 自动化验证实际效果](AI_Automation_screenshot.jpg)

## 核心思路：让 AI 拥有"眼睛"和"手"

要实现 AI 自主闭环开发，关键是补齐两个能力：

1. **"手"**：AI 能自动编译、安装、启动应用并触发测试
2. **"眼睛"**：AI 能自动收集测试结果（logcat 日志），判断功能是否正常

### 解决方案：自动化测试框架

我们在示例应用中新增了 `AutoTestActivity`，它是一个全自动的播放器功能验证页面：

- **应用启动后自动进入测试页面**，无需手动导航
- **自动遍历所有测试用例**，覆盖解码模式、渲染后端、视频源的完整组合
- **自动判定测试结果**，通过 logcat 输出标准化的 PASSED/FAILED 日志
- **AI 通过 ADB 收集日志**，即可判断功能是否正常

> 架构图见：[ai_auto_dev.drawio](ai_auto_dev.drawio)（第 3 页：自动化测试架构）

![自动化测试架构](AI_automated_testing_framework.png)

### 测试矩阵

| 维度 | 选项 | 说明 |
|------|------|------|
| 解码模式 | 硬解直渲 / 硬解Buffer / 软解 / 自动 | 4 种解码策略 |
| 渲染后端 | OpenGL ES / Vulkan | 2 种 GPU 渲染方式 |
| 视频源 | HTTPS MP4 / HLS m3u8 | 2 种网络协议 |
| AI 字幕 | Whisper 实时识别 | 端侧 AI 推理 |

**总计 17 个测试用例**（4×2×2 = 16 + 1 AI 字幕），全自动执行，无需任何人工操作。

### HLS 智能验证策略

HLS 流的特殊性在于起播慢（需下载 m3u8 索引 + 首个 TS 分片），因此验证策略做了针对性优化：

| 策略 | 普通视频 | HLS 流 |
|------|---------|--------|
| 准备超时 | 20 秒 | 45 秒 |
| 播放验证等待 | 5 秒 | 8 秒 |
| 验证条件 | isPlaying + position > 0 | isPlaying 即通过（duration/position 可能暂不可用） |
| 失败重试 | 无 | 额外等待 5 秒后重试 |

## 实战案例：HLS 视频画面卡住问题的自主修复

这是一个完整的 AI 自主闭环修复案例。开发者只说了一句话，AI 就自主完成了 **4 轮"编译→安装→测试→日志分析→代码修改"的迭代闭环**，最终定位根因并修复问题。

> 多轮排查流程图见：[ai_auto_dev.drawio](ai_auto_dev.drawio)（第 4 页：HLS 多轮自主排查）

![HLS 问题修复流程](HLS_playback_repair.png)

### 触发：一句话驱动全自主修复

开发者的完整输入只有这一段话：

> "不对，HLS 这个播放的确是有问题的，问题就是我上面描述的：画面卡住没有继续播放，但音频输出正常；如果进行 seek，只会播放一小段，然后画面又卡住不继续刷新渲染了。
> 请根据我的描述去排查问题，自行验证，直至问题修复。"

从这一刻起，**开发者没有再做任何操作**。AI 接管了全部工作，开始了 4 轮自主排查迭代。

### Agent Skill：AI 自主开发的技术基础

AI 之所以能完全自主地完成这个修复任务，依赖于 **Agent Skill** 技术体系。Skill 是预先注册的结构化知识文档，让 AI 在面对特定开发场景时，能够自动加载对应的架构知识、代码目录、开发规范，而不需要开发者反复解释项目背景。

本次修复过程中，AI 自动加载了 `develop-player` Skill，从中获取了：

| Skill 提供的知识 | 具体内容 |
|-----------------|---------|
| 播放器架构 | 四层架构（Java → JNI → Native → FFmpeg），理解问题应该在哪一层排查 |
| 视频解码流程 | `stream_component_open` → `decoder_decode_frame` → `video_thread` 的调用链 |
| 渲染流程 | `video_refresh` → `sky_video_image_display` 的帧显示逻辑 |
| 音画同步机制 | master clock、frame_timer、delay 计算，理解"音频正常但画面卡住"意味着什么 |
| 解码模式定义 | HW_SURFACE(0) / HW_BUFFER(1) / SOFTWARE(2) / AUTO(3) 四种模式 |
| 代码目录 | 精确知道要修改 `ffplay.c` 而不是其他文件 |

**没有 Skill，AI 需要大量搜索和试错来理解项目；有了 Skill，AI 直接切入核心逻辑。**

### 第一轮：搜索代码库 + 添加诊断日志

**目标**：理解"画面卡住但音频正常"的技术含义，在关键路径上添加诊断日志。

AI 借助 Skill 中的架构知识，快速锁定了排查方向：
- 音频正常 → 解封装、音频解码、音频输出链路没问题
- 画面卡住 → 问题在视频解码或视频渲染环节

AI 使用 `codebase_search` 和 `read_file` 工具搜索代码库，定位到 `ffplay.c` 中的 5 个关键位置，并添加了诊断日志：

```c
// 1. video_refresh: 帧队列是否为空
"[VDISP] pictq EMPTY count=%d videoq.nb_packets=%d"

// 2. sky_video_image_display: 渲染帧的 pts 值
"[VDISP] rendered pts=%.3f rindex=%d size=%d"

// 3. decoder_decode_frame: 解码返回值
"[VDEC] avcodec_receive_frame ret=%d"

// 4. decoder_decode_frame: serial 不匹配
"[VDEC] serial mismatch queue_serial=%d pkt_serial=%d"

// 5. video_thread: 无帧产出
"[VDEC] get_video_frame returned 0 (no frame)"
```

然后执行第一轮自动化验证：

```bash
./gradlew :app:installDebug          # 编译安装
adb shell am start -n .../.MainActivity  # 启动应用（自动进入测试页面）
sleep 50                              # 等待自动测试完成
adb logcat -s AutoTestActivity        # 收集测试结果
```

**第一轮结果**：HTTPS MP4 视频全部 PASSED，HLS 视频全部 FAILED。确认问题仅出现在 HLS 流上。

### 第二轮：分析 HLS 日志，缩小范围

**目标**：从 logcat 中提取 HLS 播放时的诊断日志，定位具体卡住的环节。

AI 使用 `launch-process` 执行 `adb logcat` 并用 `grep` 过滤关键日志：

```bash
adb logcat -d -s SkyPlayer | grep -E "VDEC|VDISP|pictq|rendered" | head -50
```

发现了两种不同的失败模式：

**HW_SURFACE 模式（硬解直渲）——解码线程卡死：**
```
[VDISP] pictq EMPTY count=301 videoq.nb_packets=26
```
视频包队列有 26 个包等待解码，但帧队列始终为空。解码线程卡住了，没有帧产出。

**HW_BUFFER 模式（硬解 Buffer）——时间戳丢失：**
```
[VDISP] rendered pts=nan rindex=3 size=1
```
帧虽然入队了，但 `pts` 全是 NaN。时间戳丢失导致音画同步失效，`video_refresh` 中的 delay 计算异常。

**关键发现**：两种硬解模式都有问题，但软解模式正常。问题指向 **MediaCodec 硬件解码器**。

### 第三轮：定位根因 + 编写修复代码

**目标**：确认根因是 MediaCodec 与 HLS 不兼容，编写修复代码。

AI 分析了 HLS 流的技术特性：
- HLS 由多个 TS 分片组成，播放过程中频繁切换分片
- 每次切换时，编解码器参数可能变化、时间戳基准可能重置
- MediaCodec 的内部状态在分片切换时可能异常

这解释了两种硬解模式的不同表现：
- **HW_SURFACE**：MediaCodec 在分片切换后解码线程卡死，无法产出帧
- **HW_BUFFER**：MediaCodec 产出帧但丢失了时间戳信息

**修复方案**：在 `stream_component_open` 中检测 HLS 流，强制使用 FFmpeg 软解：

```c
// HLS 流检测：URL 包含 .m3u8 或格式名包含 hls/applehttp
int is_hls = 0;
if (is->filename && strstr(is->filename, ".m3u8")) {
    is_hls = 1;
} else if (ic->iformat && ic->iformat->name &&
           (strstr(ic->iformat->name, "hls") ||
            strstr(ic->iformat->name, "applehttp"))) {
    is_hls = 1;
}

// HLS 流强制使用软解，避免硬件解码器兼容性问题
if (is_hls && decoder_mode != SKY_DECODER_MODE_SOFTWARE) {
    decoder_mode = SKY_DECODER_MODE_SOFTWARE;
}
```

### 第四轮：编译 → 安装 → 全量验证

**目标**：验证修复代码，确保 HLS 播放正常且不影响其他功能。

AI 再次执行完整的编译安装和自动测试流程：

```
Test result [PASSED]: 软解 + OpenGL ES + 在线HTTPS    ✅
Test result [PASSED]: 软解 + OpenGL ES + 在线HLS      ✅
Test result [PASSED]: 软解 + Vulkan + 在线HTTPS       ✅
Test result [PASSED]: 软解 + Vulkan + 在线HLS         ✅
Test result [PASSED]: 硬解直渲 + OpenGL ES + 在线HTTPS ✅
Test result [PASSED]: 硬解直渲 + OpenGL ES + 在线HLS   ✅ (自动回退软解)
Test result [PASSED]: 硬解直渲 + Vulkan + 在线HTTPS    ✅
Test result [PASSED]: 硬解直渲 + Vulkan + 在线HLS      ✅ (自动回退软解)
...
Test result [PASSED]: AI 字幕 (Whisper 英文识别)       ✅
```

**全部 17 个用例 PASSED**。HLS 播放恢复正常，HTTPS 视频不受影响，AI 字幕功能正常。

### 4 轮迭代总览

| 轮次 | AI 的动作 | 关键产出 |
|------|----------|---------|
| **第 1 轮** | 搜索代码库 → 添加 5 处诊断日志 → 编译安装 → 自动测试 | 确认问题仅出现在 HLS 流 |
| **第 2 轮** | 收集 logcat 日志 → 分析两种硬解模式的失败表现 | 发现 HW_SURFACE 帧队列为空、HW_BUFFER pts 为 NaN |
| **第 3 轮** | 定位根因（MediaCodec 与 HLS 不兼容）→ 编写修复代码 | HLS 检测 + 软解回退逻辑 |
| **第 4 轮** | 编译安装 → 全量自动测试 → 17 个用例全部 PASSED | 问题修复完成 ✅ |

**全程零人工干预**，从开发者说出"请根据我的描述去排查问题，自行验证，直至问题修复"到最终修复完成，AI 独立完成了 4 轮迭代。

### 关键数据

| 指标 | 传统模式（估算） | AI 自主闭环 |
|------|----------------|------------|
| 人工介入次数 | 5~8 次 | **1 次**（仅提出问题） |
| 自主迭代轮次 | — | **4 轮**（搜索→日志→修复→验证） |
| 修复周期 | 2~4 小时 | **约 30 分钟** |
| 验证覆盖度 | 手动测试 1~2 种配置 | **自动覆盖 17 种配置** |

## 工作模式的转变

### 之前：开发者 = AI 的测试工程师

```
开发者提需求 → AI 写代码 → 开发者手动测试 → 开发者反馈结果 → AI 改代码 → 开发者再测 → ...
```

每一轮迭代都需要开发者：
1. 等待编译完成
2. 手动安装到设备
3. 打开应用，导航到播放页面
4. 点击播放，观察画面
5. 将观察结果描述给 AI

### 现在：开发者 = 需求提出者

```
开发者提出需求/问题 → AI 自主完成全部工作 → 开发者确认结果
```

AI 自主完成的工作包括：
1. **搜索代码库**，理解现有架构
2. **编写/修改代码**，实现功能或修复问题
3. **执行编译构建**（`./gradlew installDebug`）
4. **安装到设备**（`adb install`）
5. **触发自动测试**（`AutoTestActivity` 自动运行）
6. **收集测试日志**（`adb logcat`）
7. **分析结果**，判断是否通过
8. **如果失败，自动迭代**修复

开发者只需要做一件事：**描述需求或问题**。

## 技术实现要点

### 自动化测试框架（AutoTestActivity）

**核心设计**：
- 应用启动时自动进入测试页面（`MainActivity` 作为启动 Activity，自动跳转）
- 测试用例以矩阵方式组织，自动遍历所有组合
- 每个用例独立运行：释放旧播放器 → 配置参数 → 播放 → 验证 → 记录结果
- 通过 `Log.i(TAG, "Test result [PASSED/FAILED]: ...")` 输出标准化日志

**关键代码路径**：
- `app/src/main/java/imt/skymediaplayer/demo/AutoTestActivity.kt`
- `app/src/main/res/layout/activity_auto_test.xml`

### HLS 软解回退

**核心设计**：
- 在 FFmpeg 的 `stream_component_open` 中检测 HLS 流
- 检测方式：URL 包含 `.m3u8` 或 `AVInputFormat.name` 包含 `hls`/`applehttp`
- 检测到 HLS 流时，强制将解码模式设为 `SKY_DECODER_MODE_SOFTWARE`
- 对用户透明：上层仍可设置任意解码模式，底层自动回退

**关键代码路径**：
- `skymediaplayer/src/main/cpp/ffplay/ffplay.c`（`stream_component_open` 函数）

### AI Agent 的工具链与 Skill 体系

AI 自主闭环依赖两大技术支撑：**工具链**（执行能力）和 **Agent Skill**（领域知识）。

#### 工具链：AI 的"手"和"眼睛"

| 工具 | 用途 |
|------|------|
| `codebase_search` | 语义搜索代码库，定位相关模块 |
| `read_file` | 读取源码，理解实现细节 |
| `file_replace` | 精确修改代码 |
| `launch-process` | 执行 shell 命令（编译、安装、收集日志） |
| `read-process` | 读取命令输出（logcat 日志分析） |

#### Agent Skill：AI 的"领域专家大脑"

Skill 是预先注册的结构化知识文档，让 AI 在面对特定开发场景时自动加载对应的架构知识和开发规范，无需开发者反复解释项目背景。

| Skill | 提供的能力 |
|-------|----------|
| `develop-player` | 播放器架构、解码/渲染/同步流程、代码目录映射，让 AI 直接切入核心逻辑 |
| `upgrade-ffmpeg` | FFmpeg 编译配置和升级流程，让 AI 自主完成底层库升级 |
| `stage-commit` | 多仓库关联提交规范，让 AI 正确管理跨仓库的代码变更 |

**Skill 的价值**：没有 Skill，AI 需要大量搜索和试错来理解项目；有了 Skill，AI 像一个熟悉项目的老员工一样，直接知道"问题在哪里、该改哪个文件、怎么改"。

## 总结

通过引入自动化测试框架，我们实现了 AI 开发的关键闭环：

1. **AI 拥有了"手"**：通过 ADB 命令自动编译、安装、启动应用
2. **AI 拥有了"眼睛"**：通过 logcat 日志自动判断功能是否正常
3. **开发者角色转变**：从"AI 的测试工程师"变为"需求提出者"
4. **效率提升**：人工介入从 5~8 次降至 1 次，验证覆盖度从 1~2 种提升到 17 种

这不仅是一个技术改进，更是一种 **AI 辅助开发工作模式的升级**——让 AI 真正具备了端到端的自主开发能力。
