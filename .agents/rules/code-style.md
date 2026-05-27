---
alwaysApply: true
---

# SkyPlayer 代码风格规范

本项目遵循以下代码风格规范和约定：

## Kotlin 代码风格

### 命名约定
- **类名**: 使用 PascalCase，如 `SkyMediaPlayer`, `AudioFocusManager`
- **函数名**: 使用 camelCase，如 `setDataSource()`, `prepareAsync()`
- **常量**: 使用 UPPER_SNAKE_CASE，如 `MEDIA_PREPARED`, `MEDIA_ERROR`
- **变量**: 使用 camelCase，如 `mediaPlayer`, `surfaceHolder`

### 接口设计
- 播放器接口使用 `I` 前缀，如 `IMediaPlayer`
- 监听器接口使用 `On...Listener` 命名，如 `OnPreparedListener`, `OnErrorListener`

### 代码组织
- 每个类一个文件
- 相关类放在同一包下
- 包结构按功能划分：`player/`, `audio/`, `widget/`, `utils/`

## C/C++ 代码风格

### 命名约定
- **文件名**: 使用小写加下划线，如 `skymediaplayer.cpp`, `sky_msg_queue.cpp`
- **类名**: 使用 PascalCase，如 `SkyMediaPlayer`, `SkyRenderer`
- **函数名**: 使用小写加下划线，如 `sky_display_image()`, `sky_open_audio()`
- **宏定义**: 使用 UPPER_SNAKE_CASE，如 `SKY_MSG_PREPARED`
- **结构体**: 使用 PascalCase 或带 `Sky` 前缀，如 `SkyAudioSpec`

### 头文件规范
- 使用 `#pragma once` 或 include guards
- 公共头文件放在 `include/` 目录
- 私有头文件与源文件放在一起

### JNI 规范
- JNI 函数使用 `_` 前缀，如 `_native_setup`, `_setDataSource`
- 使用弱全局引用管理 Java 对象
- 使用 TLS 管理 JNIEnv
- 自动 attach/detach 线程

### 内存管理
- 使用 RAII 机制管理资源
- 智能指针优先于裸指针
- 正确处理 JNI 引用（Local/Global/Weak）

## 注释规范

### Kotlin/Java
```kotlin
/**
 * 播放器接口定义
 * 兼容 Android MediaPlayer API
 */
interface IMediaPlayer {
    /**
     * 设置数据源
     * @param path 文件路径或网络 URL
     */
    fun setDataSource(path: String)
}
```

### C/C++
```cpp
/**
 * 显示视频帧
 * @param player 播放器实例
 * @param frame 解码后的视频帧
 * @return 成功返回 true
 */
bool sky_display_image(void *player, AVFrame *frame);
```

## 异常处理

### Kotlin 层
- 使用 try-catch 捕获异常
- 通过 OnErrorListener 回调错误信息
- 资源释放使用 try-finally 或 use 扩展

### Native 层
- 函数返回错误码或布尔值
- 通过消息队列传递错误事件
- 使用 RAII 确保资源释放

## 线程安全

### 多线程设计
- 使用互斥锁保护共享资源
- JNI 层使用 TLS 管理 JNIEnv
- 消息队列实现异步事件处理

### 线程模型
- 读取线程：读取数据包
- 视频解码线程：解码视频帧
- 音频解码线程：解码音频帧
- 渲染线程：音画同步和渲染

## 性能考虑

### 视频渲染
- 使用 OpenGL ES 2.0 硬件加速
- 支持 5 种像素格式优化渲染
- Shader 实现 YUV → RGB 转换

### 音频输出
- 使用 OpenSL ES 实现低延迟（< 20ms）
- 多缓冲区设计保证流畅播放
- 实时线程优先级

### 内存优化
- 避免频繁内存分配
- 使用对象池复用资源
- 及时释放不再使用的资源

## 代码审查要点

1. **接口兼容性**: 确保与 Android MediaPlayer API 兼容
2. **线程安全**: 检查多线程访问的正确性
3. **资源管理**: 确保资源正确释放
4. **错误处理**: 检查错误路径的处理
5. **性能影响**: 评估对播放性能的影响
