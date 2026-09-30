# APK 发布流程

本流程对应 demo 应用 `:app`，用于 GitHub Release 直接下载。AAR 是播放器开发库，不能作为 APK 安装。
构建阶段只产出 **未签名 APK**；仓库不读取签名凭据、不生成密钥、不自动发布。

## 环境与支持范围

- 完整 JDK 17（需要 `javac`，只有 JRE 不够）。
- 使用仓库 Gradle Wrapper：Gradle 8.10.2 / AGP 8.8.0。
- Android SDK Platform 35、Build Tools 35.0.0、NDK 27.0.12077973、CMake 3.22.1。
- `ANDROID_HOME` 指向 SDK；或在未跟踪的 `local.properties` 中配置 `sdk.dir`。
- APK 只支持 **arm64-v8a、Android 11 / API 30 及以上**。

用 Android Studio 的 SDK Manager 安装依赖，或在已接受 SDK 许可的环境运行：

```bash
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' \
  'platform-tools' 'ndk;27.0.12077973' 'cmake;3.22.1'
```

源码模式直接使用已提交的 release 预编译依赖；通常无需重编 FFmpeg、OpenSSL 或 Whisper。
不要用旧 JitPack AAR 构建“当前 main 的 APK”。

## 1. 确认提交与版本，构建未签名包

在拟发布的、已审查提交上运行。下面 `1.6.1-preparation / 2` 是命令示例，不代表已批准的发布版本。
`skyVersionName` 应与拟创建的标签一致；`skyVersionCode` 必须大于所有此前分发 APK 的值。
已检查的 v1.1.0 两个 APK 均为 `versionCode=1 / versionName=1.0`。

```bash
java -version
javac -version
git rev-parse HEAD
./gradlew :app:prepareReleaseApk :app:lintRelease \
  -PskyDependencyMode=project -PskyAutoTestEnabled=false \
  -PskyVersionName=1.6.1-preparation -PskyVersionCode=2
```

release 构建缺少显式版本、版本号无效、依赖模式不是 project 或自动测试启动未禁用时会失败。
debug 使用 `.debug` 应用 ID 后缀，与正式安装并存。单独构建 APK 的底层命令是 `:app:assembleRelease`，也须传入上述参数。

| 产物 | 路径 |
| --- | --- |
| 原始未签名 APK | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| 供签名前处理的未签名 APK | `app/build/release-apk/SkyMediaPlayer-1.6.1-preparation-arm64-v8a-unsigned.apk` |
| 暂存版本元数据 | `app/build/release-apk/output-metadata.json` |
| 暂存 R8 映射 | `app/build/release-apk/mapping.txt` |
| Lint 报告 | `app/build/reports/lint-results-release.html` |

暂存目录每次同步清除旧产物。元数据中的原始 APK 文件名指向原始产物目录；暂存 APK 会重命名。
映射文件应与最终 APK、源码提交一并内部归档，便于排查混淆后的崩溃。

## 2. 维护者在自己的可信环境签名

正式发布版本仍须维护者确认；本次仅构建 `1.6.1-preparation / 2` 验收包。签名使用维护者选定的本机密钥。
私钥、密钥库和密码不发送到聊天，不提交到仓库，也不写入命令行密码参数。
以下变量仅在维护者本机设置：`RELEASE_KEYSTORE` 是维护者选定的密钥库路径，`RELEASE_KEY_ALIAS` 是已有别名。
`apksigner` 交互提示输入密码。构建任务不创建密钥，也不使用 debug 签名冒充正式签名。

```bash
BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
VERSION=1.6.1-preparation
UNSIGNED="app/build/release-apk/SkyMediaPlayer-$VERSION-arm64-v8a-unsigned.apk"
ALIGNED="app/build/release-apk/SkyMediaPlayer-$VERSION-arm64-v8a-aligned.apk"
SIGNED="app/build/release-apk/SkyMediaPlayer-$VERSION-arm64-v8a.apk"

# 必须先对齐，再签名；签名后不要修改 ZIP 内容。
"$BUILD_TOOLS/zipalign" -f -v 4 "$UNSIGNED" "$ALIGNED"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$RELEASE_KEYSTORE" --ks-key-alias "$RELEASE_KEY_ALIAS" \
  --out "$SIGNED" "$ALIGNED"
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$SIGNED"
"$BUILD_TOOLS/zipalign" -c -v 4 "$SIGNED"
"$BUILD_TOOLS/aapt" dump badging "$SIGNED"
```

原 v1.1.0 的 `app-release.apk` 和 `app-debug.apk` 均通过 v2 签名验证，具有同一证书。
其 **公开证书 SHA-256** 为：

```text
1b95ab1b21c974cc9a24d3fa217b1e00e901569cd6e846d5a74640fee4521b4f
```

同签名覆盖升级要求证书与历史包一致、应用 ID 相同且版本递增。
本次维护者明确选择了新签名身份，其公开证书 SHA-256 为
`f165252cbaec492e1828cda7053188453f23d9508fb7031a67ce450139a53be4`。
它不能直接覆盖安装旧签名版；卸载重装可能丢失应用数据，应由用户自行决定。
新签名只用于本次准备包，未批准正式发布。
文件名叫 release 并不代表已签名；未签名包不能作为可安装正式包上传。

## 3. 真机验收并手动发布

1. 在 arm64 真机验证安装；签名不匹配时暂停，不自动卸载或清除数据。相同新签名准备包可用 `adb install -r "$SIGNED"` 更新。
2. 检查启动、媒体权限、本地播放、HTTP/HTTPS、HLS、播放/暂停/seek、软硬解码、OpenGL/Vulkan 切换、画质滤镜。
3. 首次离线启动等待内置模型准备完成，再检查 AI 字幕（默认 CPU；GPU 单独验收）。
4. 核对 APK 的版本、ABI、`debuggable=false`、证书指纹、大小；保存 SHA-256：

   ```bash
   (cd app/build/release-apk && shasum -a 256 "SkyMediaPlayer-$VERSION-arm64-v8a.apk" > SHA256SUMS)
   ```

5. 在 GitHub 确认源提交，手动创建相同版本的标签和 Release，上传 **已签名 APK** 与 `SHA256SUMS`。
   发布说明写明 Android 11+ / arm64、离线英文 AI 字幕模型及兼容性限制。不要上传 unsigned/aligned APK。
6. 实际下载 Release 资产，复核校验和及安装。随后用真实下载链接回复 #2 / #3，再决定是否关闭 issue。

## 包体优化与边界

- release 开启 R8 和资源压缩；JNI consumer rules 保留原生代码按名称查找的播放器类、方法和字段。
- release APK 压缩 `.so`，减少直接下载大小；系统安装时解压，安装空间和安装耗时有所增加。
- 保留约 74.1 MiB 的 `ggml-tiny.en.bin`，维持离线 AI 字幕。R8 不会压缩模型权重，也不会裁剪原生 FFmpeg 功能。
- release 使用 `jniLibs-release`，AGP 在打包时剥离调试符号；保留功能配置；SDL3、FFmpeg、OpenSSL 按原源码和功能配置重新链接，导出符号集合与原库一致。
- 要进一步大幅缩包，需要明确决定模型量化或按需下载、FFmpeg 裁剪等功能变化，另行验证后推进。
- release 的 SDL3、FFmpeg、OpenSSL 和播放器 ELF 已按 16 KiB 重新链接。
  NDK 27 的 `libc++_shared.so` 仍存在 GNU_RELRO 末端对齐缺口，不能称完整 16 KiB 支持。
  已有真机使用 4 KiB 页；`zipalign` 和 ELF 静态检查不能替代 16 KiB 真机验收。

官方依据：[命令行构建](https://developer.android.com/build/building-cmdline)、
[APK 签名与验证](https://developer.android.com/tools/apksigner)、
[AGP 8.8 环境要求](https://developer.android.com/build/releases/agp-8-8-0-release-notes)。
