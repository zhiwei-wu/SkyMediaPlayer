# FFmpeg 编译产物参考

## 编译产物目录结构

FFmpeg 编译产物位于 `/Users/uc/code/zhiwei-wu/FFmpeg/android/arm64-v8a/`：

```
android/arm64-v8a/
├── libskyffmpeg.so          # 编译好的动态库 (48MB)
├── config.h                 # FFmpeg 编译配置文件
├── include/                 # FFmpeg 头文件目录
│   ├── libavcodec/         # 编解码器头文件 (24个)
│   ├── libavformat/        # 封装格式头文件 (4个)
│   ├── libavutil/          # 工具库头文件 (92个)
│   ├── libavfilter/        # 滤镜库头文件 (5个)
│   ├── libavdevice/        # 设备库头文件 (3个)
│   ├── libswresample/      # 音频重采样头文件 (3个)
│   ├── libswscale/         # 视频缩放头文件 (3个)
│   └── libffmpeg/          # FFmpeg 配置头文件 (1个)
└── lib/                     # 静态库目录
    ├── libavcodec.a
    ├── libavformat.a
    ├── libavutil.a
    ├── libavfilter.a
    ├── libavdevice.a
    ├── libswresample.a
    └── libswscale.a
```

## SkyPlayer 项目目标目录

- **jniLibs**: `skymediaplayer/src/main/jniLibs/arm64-v8a/`
- **头文件**: `skymediaplayer/src/main/cpp/ffmpeg/include/`


## 替换命令

```bash
# 替换 libskyffmpeg.so
cp /Users/uc/code/zhiwei-wu/FFmpeg/android/arm64-v8a/libskyffmpeg.so \
   /Users/uc/code/SkyPlayer/skymediaplayer/src/main/jniLibs/arm64-v8a/libskyffmpeg.so

# 替换 config.h
cp /Users/uc/code/zhiwei-wu/FFmpeg/android/arm64-v8a/config.h \
   /Users/uc/code/SkyPlayer/skymediaplayer/src/main/cpp/ffmpeg/include/config.h
```
