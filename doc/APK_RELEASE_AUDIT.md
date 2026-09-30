# APK 准备审查（2026-09-30）

基线：[main / 64364c42a3d0564a26466232476f951575831472](https://github.com/zhiwei-wu/SkyMediaPlayer/commit/64364c42a3d0564a26466232476f951575831472)。
本次使用独立准备分支，不修改原有 checkout。`1.6.1-preparation / code 2` 是验收版本，正式版本未决定；未发布 Release、PR 或 issue 回复。

## 构建与签名

- JDK 17、Gradle Wrapper 8.10.2、AGP 8.8.0、SDK/Build Tools 35、NDK 27.0.12077973、CMake 3.22.1。
- 显式声明 AAR 发布变体，清理不存在的 xffmpeg 模块及 properties 残留。
- release 校验显式版本、源码依赖模式、关闭自动测试启动；构建任务不读取签名凭据，暂存 unsigned APK、版本元数据和 R8 映射。
- R8 / 资源压缩及 release 原生库压缩开启；JNI 类、native 方法、字段和回调名称保留。离线 Whisper 模型保留，SHA-1 `c78c86eb1a8faa21b369bcd33207cc90d64ae9df`。
- debug 使用已有 debug 签名与 `.debug` 应用 ID，和原应用并存；不生成 debug 密钥，不卸载或清除原应用数据。
- 维护者在本机自行创建并输入新密钥口令。新证书 SHA-256：`f165252cbaec492e1828cda7053188453f23d9508fb7031a67ce450139a53be4`。
- 历史 v1.1.0 APK 证书 SHA-256：`1b95ab1b21c974cc9a24d3fa217b1e00e901569cd6e846d5a74640fee4521b4f`。新身份不能直接覆盖旧签名安装；未验证旧签名覆盖升级。

## 真机发现与修复

| 触发条件 | 根因 | 修复 |
| --- | --- | --- |
| MediaCodec buffer 输出 NV12/NV21 有行 padding | GLES2 上传按图像宽度读连续内存，忽略 linesize | 逐行打包 Y / UV 到紧密缓冲；无 padding 时直接引用，避免 GLES2 不支持的 UNPACK_ROW_LENGTH |
| 暂停后后台恢复出现空 Surface | 新 Surface 没有已上传图像，native 引用不平衡 | Surface 重建请求现有帧重绘，并平衡 ANativeWindow 引用 |
| 非法 NAL 长度 | uint32 长度转有符号值可能溢出并绕过边界 | size_t 剩余长度比较；拒绝截断、零长度与超大长度，清除部分输出 |
| seek 后硬解时钟无效 | 有效 MediaCodec PTS 被未赋值的 best_effort_timestamp 覆盖，serial 切换漏 flush | 保留 PTS，packet serial 变化刷新解码器；debug 每秒记录 A/V 时钟 |
| EOF 后切源主线程挂在关闭音频 | 音频回调阻塞空 frame queue，关闭之前未唤醒；硬解 EOS 处理也错误 | 先 abort / signal 播放队列再关闭音频；向 MediaCodec 发送 EOS 并排空延迟帧 |
| SAF 选择视频后需要全盘权限或报 -13 | 已授权 content URI 被转为原始路径，/proc/self/fd 重开也受 scoped storage 限制 | 保留只读描述符，native 自定义 AVIO 使用 pread / 独立偏移 seek；释放播放器后关闭，不请求全盘权限 |

SAF 自定义 AVIO 支持可 seek 的普通文件描述符；管道等不支持的提供方返回明确错误，不静默绕过权限。Whisper 独立读取使用各自 AVIO 偏移，不与播放互相改变文件位置。

## 原生依赖与 16 KiB 范围

从本机已有源码按原功能配置重编 SDL3 3.2.2、FFmpeg、OpenSSL；未下载或执行未知脚本、未安装新依赖。三个库的动态导出符号集合分别为 1232、9146、10799，与原 release 库一致；FFmpeg 公共头文件与原版逐字节一致。
链接器使用 max-page-size / common-page-size 16384。release 的 SDL3、FFmpeg、OpenSSL、播放器 LOAD / GNU_RELRO 已修复。
NDK 27 的 libc++_shared LOAD 是 16 KiB，但 GNU_RELRO 末端模 16384 为 12288，仍有缺口；需要后续验证新版 NDK runtime。不得称完整 16 KiB 支持。
debug 继续使用原 debug 预编译依赖；系统兼容警告反映其旧对齐状态。最终 release 必须重新验收重编 FFmpeg / OpenSSL。

## 已完成验证与范围

设备为已授权真实 Pixel 10，Android 16 / API 36，arm64-v8a，页面大小 4096；不是模拟器，不代表 16 KiB 实机测试。

- NAL 边界与逐行打包测试通过 ASan / UBSan。
- 实机 debug：854×480 padding 样片连续画面正常，640×360 无 padding 样片画面正常。
- 实机 debug：播放、暂停、seek 后持续画面、暂停后后台恢复通过。seek 恢复约一秒后 A/V 时钟差稳定约 19–23 ms；本地暂停恢复约 26–46 ms。这是播放器时钟测量，未用声学采集验证主观口型同步。
- 实机 debug：SAF 单文件授权的本地样片播放通过，不开启所有文件访问；后台返回仍有画面并能继续播放。
- 实机 debug：有效 HTTPS Sintel 媒体已起播并有持续正常画面；Mac 验证源为可读 MP4、证书链有效。没有关闭 TLS 校验或绕过安全。403 外部源单独记作源访问失败，不据此判定播放器失败。
- 初始 release / AAR 构建成功、lint 0 errors / 58 warnings；最终修复包的 release / lint / 签名和实机结果在最终验收后补充。

## 后续发布边界

全部 debug 核心通过后仅做一次最终 release 构建与本机交互签名，验签并以同一 APK 实机回归。维护者随后明确授权先 push 独立准备分支，再发布预发布版；这不代表未解决项已通过。不推 main、不 force、不提交 APK 或凭据。
正式版本、16 KiB runtime、HLS、其他解码/渲染模式、滤镜及 AI 字幕仍需各自验收；本次主要验证默认硬解 buffer + GLES2 的播放故障修复。发布及 issue 回复另行由维护者决定，参见 [APK 流程](APK_RELEASE.md)。

## 当前交接：HTTPS 黑帧尚未定性

维护者要求暂停深入调试、先提交并发布 `v1.6.1-rc.1` 预发布版。公开发布文案与内部诊断分开；此记录保留实际未解决项。

- Pixel 10 / Android 16 / arm64-v8a / 4096-byte pages。默认硬解 buffer + GLES2。
- W3 Sintel 源 `https://media.w3.org/2010/05/sintel/trailer.mp4`。原文件媒体 20.000s 非黑，YAVG 53.838、YMIN 4、YMAX 245；与本地同源帧 MD5 一致。附近短黑场不能解释为已确定根因。
- 一次 HTTPS 截图约媒体 20s 全黑；当时 codec2 与 A/V 时钟仍活动，没有明确 EGL/GL 错误，也没有逐帧 present 证据。文件名中的 30s 是墙钟等待命名，不能当媒体 PTS。根因未定位。
- 后续固定媒体约 20.09s，本地和 HTTPS 均显示正常街道人物。临时探针观察 Y 范围 16..236、GL 中心亮度约 29、GL error 0；HTTPS 时钟差约 27ms。本次成功不能排除间歇性黑帧。
- 另一轮 HTTPS 起播 `-110` 没有有效解码帧，是起播超时，与上述播放中黑帧分开。Mac 同源 TLS/curl 成功，另一有效 HTTPS 视频也显示正常；不能据此声称所有网络问题已解决。
- 临时 FrameProbe/glReadPixels 已移除；清理后 assembleDebug 成功，未再扩大设备测试。
- 已通过：padding/no-padding 代表样片、SAF 本地、HTTP、实际 seek/flush 后持续画面、暂停与后台恢复、EOS/completion 后切源；不是完整功能/主观音画同步验收。
- 最小下一步：固定非黑时段连续采集同 PTS 的解码亮度、GL 输出及 Surface present/截图，区分解码、上传与呈现；不要用起播超时截图推断渲染根因。

证据保留在维护者本机工作目录 `/Users/uc/Documents/Codex/2026-09-30/task`：`debug-final-https-30s.png`（旧黑帧）、`debug-https-fixed-20s.png`、`debug-local-20s-probe.png`、`reference-20s.png`、`debug-https-20s-probe.log`、`debug-probe-removed-build.log`。图片与日志未提交以避免混入设备信息。
