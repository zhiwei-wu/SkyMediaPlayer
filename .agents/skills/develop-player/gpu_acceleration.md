# Whisper GPU 加速支持

## 概述

Whisper 滤镜支持 Vulkan GPU 加速，可显著提升语音识别速度（5-10x 实时速度）。由于部分 Android 设备的 Vulkan 驱动存在兼容性问题，实现了安全的 GPU 初始化机制。

## 核心文件

| 文件 | 位置 | 作用 |
|------|------|------|
| `af_whisper.c` | FFmpeg `libavfilter/` | Whisper 滤镜主文件 |
| `af_whisper_vk_helper.cpp` | FFmpeg `libavfilter/` | Vulkan 后端 C++ 包装 |

## GPU 初始化机制

### 问题背景

某些 Android 设备（如 Adreno 730）在 Vulkan GPU 初始化时会发生 SIGSEGV 崩溃，这种崩溃无法在 C 代码中捕获。

### 解决方案：fork() 安全测试

使用 `fork()` 创建子进程测试 GPU 初始化，如果子进程崩溃，父进程检测到并自动回退到 CPU。

```
┌─────────────────────────────────────────────────────────────┐
│                    用户请求 GPU 加速                         │
└─────────────────────────┬───────────────────────────────────┘
                          ▼
┌─────────────────────────────────────────────────────────────┐
│  whisper_vk_check_support()                                 │
│  检查 Vulkan 版本 (需要 1.2+)                                │
└─────────────────────────┬───────────────────────────────────┘
                          ▼
              ┌───────────────────────┐
              │  Vulkan 1.2+ 支持?    │
              └───────────┬───────────┘
                    ┌─────┴─────┐
                    ▼           ▼
                   是          否 → 直接使用 CPU
                    │
                    ▼
┌─────────────────────────────────────────────────────────────┐
│  whisper_vk_test_gpu_safety()                               │
│  fork() 创建子进程测试 GPU 初始化                            │
└─────────────────────────┬───────────────────────────────────┘
                          ▼
              ┌───────────────────────┐
              │     子进程测试        │
              │  whisper_init(GPU)    │
              └───────────┬───────────┘
                    ┌─────┴─────┐
                    ▼           ▼
                  成功        崩溃(SIGSEGV)
                    │           │
                    ▼           ▼
              exit(0)      被信号杀死
                    │           │
                    └─────┬─────┘
                          ▼
┌─────────────────────────────────────────────────────────────┐
│  父进程检测子进程状态                                        │
│  WIFEXITED(status) → 正常退出                               │
│  WIFSIGNALED(status) → 被信号杀死（崩溃）                    │
└─────────────────────────┬───────────────────────────────────┘
                          ▼
              ┌───────────────────────┐
              │   GPU 测试通过?       │
              └───────────┬───────────┘
                    ┌─────┴─────┐
                    ▼           ▼
                   是          否
                    │           │
                    ▼           ▼
              使用 GPU      回退到 CPU
```

## af_whisper_vk_helper.cpp 关键函数

### whisper_vk_check_support()

检查 Vulkan API 版本支持。

```cpp
/**
 * 返回值:
 * 0 = 不支持 Vulkan
 * 1 = 仅支持 Vulkan 1.1（不满足要求）
 * 2 = 支持 Vulkan 1.2+（满足要求）
 */
int whisper_vk_check_support(void);
```

### whisper_vk_test_gpu_safety()

使用 fork() 安全测试 GPU 初始化。

```cpp
/**
 * 在子进程中测试 GPU 初始化是否会崩溃
 * @param model_path Whisper 模型文件路径
 * @return 1 = GPU 安全可用, 0 = GPU 不可用或会崩溃
 */
int whisper_vk_test_gpu_safety(const char* model_path);
```

### whisper_vk_get_last_error()

获取最后一次 Vulkan 错误信息。

```cpp
const char* whisper_vk_get_last_error(void);
```

## af_whisper.c 初始化逻辑

```c
// Forward declarations
extern int whisper_vk_check_support(void);
extern int whisper_vk_test_gpu_safety(const char* model_path);
extern const char* whisper_vk_get_last_error(void);

// 初始化逻辑
if (wctx->use_gpu) {
    // 使用 fork() 安全测试 GPU
    int gpu_safe = whisper_vk_test_gpu_safety(wctx->model_path);
    
    if (gpu_safe) {
        params.use_gpu = true;
        wctx->ctx_wsp = whisper_init_from_file_with_params(...);
        if (wctx->ctx_wsp != NULL) {
            gpu_init_success = true;
        }
    } else {
        const char* error_msg = whisper_vk_get_last_error();
        av_log(ctx, AV_LOG_WARNING, "GPU safety test failed: %s\n", error_msg);
    }
}

// 回退到 CPU
if (!gpu_init_success) {
    params.use_gpu = false;
    wctx->ctx_wsp = whisper_init_from_file_with_params(...);
}
```

## 编译脚本修改

在 `build_skyplayer_ffmpeg.sh` 中添加编译 C++ 包装文件：

```bash
# 保存 FFmpeg 源码绝对路径
FFMPEG_SRC_PATH=$(pwd)

cd $OUTPUT_DIR

# 编译 Vulkan helper C++ 文件
echo "Compiling af_whisper_vk_helper.cpp..."
CXX=$TOOLCHAIN/bin/${TARGET}${API_LEVEL}-clang++
$CXX -c -fPIC -std=c++17 \
    -I$WHISPER_DIR/include \
    -I$FFMPEG_SRC_PATH \
    $FFMPEG_SRC_PATH/libavfilter/af_whisper_vk_helper.cpp \
    -o af_whisper_vk_helper.o

# 链接时包含 af_whisper_vk_helper.o
$CXX -shared -o libskyffmpeg.so \
    af_whisper_vk_helper.o \
    -Wl,--whole-archive \
    ...
```

## 关键技术点

| 技术点 | 说明 |
|--------|------|
| **fork() 隔离** | 子进程崩溃不影响主进程 |
| **共享内存 (mmap)** | 子进程将结果传递给父进程 |
| **信号处理 (sigaction)** | 子进程捕获 SIGSEGV/SIGBUS/SIGABRT |
| **setjmp/longjmp** | 从信号处理器恢复执行 |
| **waitpid + WIFSIGNALED** | 父进程检测子进程是否被信号杀死 |
| **Vulkan 版本检查** | 提前过滤不支持 Vulkan 1.2 的设备 |

## 日志调试

```bash
adb logcat | grep -E "Whisper-VK-Helper|Whisper-Filter"
```

**成功场景**：
```
Whisper-VK-Helper: Checking Vulkan support...
Whisper-VK-Helper: Vulkan API version: 1.3.xxx
Whisper-VK-Helper: Vulkan 1.2+ support confirmed
Whisper-VK-Helper: Testing GPU initialization safety with fork()...
Whisper-VK-Helper: [Child] Testing GPU initialization...
Whisper-VK-Helper: [Child] GPU initialization successful!
Whisper-VK-Helper: [Parent] Child exited with code 0, shared_result=1
Whisper-VK-Helper: GPU initialization test PASSED
Whisper-Filter: GPU safety test passed, initializing with GPU...
Whisper-Filter: GPU initialization successful
```

**失败回退场景**：
```
Whisper-VK-Helper: [Child] Testing GPU initialization...
Whisper-VK-Helper: [Parent] Child killed by signal 11
Whisper-VK-Helper: GPU initialization test FAILED
Whisper-Filter: GPU safety test failed: GPU initialization crashed with signal 11
Whisper-Filter: Initializing with CPU backend...
```

## 设备兼容性

| GPU | Vulkan 版本 | 状态 |
|-----|-------------|------|
| Adreno 730 | 1.3 | ⚠️ 可能崩溃，需要 fork 测试 |
| Mali-G76 | 1.1 | ❌ 不支持（版本过低） |
| Mali-G78+ | 1.2+ | ✅ 应该支持 |
| Adreno 650+ | 1.2+ | ✅ 应该支持 |

## 注意事项

1. **Vulkan 1.2 要求**：ggml-vulkan 需要 Vulkan 1.2+，低版本设备自动回退 CPU
2. **fork() 开销**：每次初始化会 fork 子进程测试，有一定性能开销
3. **模型加载两次**：测试成功后主进程还需再次加载模型
4. **静态链接限制**：`ggml_backend_load_all()` 在静态链接时不可用，后端在编译时静态注册
