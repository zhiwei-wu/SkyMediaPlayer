---
name: upgrade-ffmpeg
version: 0.3.0
description: 将新编译的 FFmpeg 库升级到 SkyPlayer 项目中
---

# 执行流程

## 1. 确认 FFmpeg 编译产物目录存在
参考 ffmpeg_build_reference.md

## 2. 替换 libskyffmpeg.so 动态库
将 `android/arm64-v8a/libskyffmpeg.so` 复制到 `jniLibs/arm64-v8a/` 目录

## 2.1 添加 C++ 标准库（如果需要）
如果新版 FFmpeg 链接了 C++ 库（如 Whisper.cpp），需要将 NDK 中的 `libc++_shared.so` 复制到 `jniLibs/arm64-v8a/` 目录：

```bash
NDK_ROOT=~/Library/Android/sdk/ndk/27.0.12077973
cp "$NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" \
   skymediaplayer/src/main/jniLibs/arm64-v8a/
```

**错误症状**：如果运行时出现 `dlopen failed: library "libc++_shared.so" not found` 错误，说明需要添加此库。

## 3. 对比并更新 FFmpeg 头文件
将 ffmpeg 编译产物目录下 `include/` 目录下的依赖更换到 SkyPlayer 项目 `skymediaplayer/src/main/cpp/ffmpeg/include/`

**注意事项**：
- 不能直接全部拷贝替换，需要对比差异
- 评估能替换的则直接替换，不能替换的以专业知识去修改
- 如果无法确认，需要跟用户确认再进行修改

**需要保留的目录（不替换）**：
- `compat/` - 兼容性头文件
- `libpostproc/` - 后处理库（新版可能不包含）

## 4. 复制 FFmpeg 内部头文件
以下头文件不在 FFmpeg 安装的 `include/` 目录中，但被 `cmdutils.c` 等文件引用，需要从 FFmpeg 源码中复制：

| 文件 | 源位置 | 目标位置 |
|------|--------|----------|
| `getenv_utf8.h` | FFmpeg 源码 `libavutil/` | `ffmpeg/include/libavutil/` |
| `libm.h` | FFmpeg 源码 `libavutil/` | `ffmpeg/include/libavutil/` |
| `fopen_utf8.h` | FFmpeg 源码 `fftools/` | `ffplay/` |

## 5. 替换 config.h 配置文件
将 ffmpeg 编译产物目录的 `config.h` 文件替换到 `ffmpeg/include/` 目录下

## 6. 验证升级结果
- 运行 `./gradlew :skymediaplayer:assembleDebug` 确保编译通过
- 列出新增/修改的配置（如 `CONFIG_WHISPER`、`CONFIG_NETWORK` 等）

---

# 相关文档

| 文档 | 说明 |
|------|------|
| [ffmpeg_build_reference.md](ffmpeg_build_reference.md) | FFmpeg 编译产物目录结构和替换命令 |
| [gpu_acceleration.md](../develop-player/gpu_acceleration.md) | Whisper GPU 加速支持（位于 develop-player） |
