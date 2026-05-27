# 阶段开发保存 - 多仓库关联提交

本文档提供阶段性开发保存的操作指南，支持关联多个仓库的修改进行统一 commit。

## 关联仓库列表

| 仓库名称 | 路径 | 说明 |
|---------|------|------|
| SkyPlayer | `/Users/uc/code/SkyPlayer` | 主项目，播放器核心 |
| FFmpeg | `/Users/uc/code/zhiwei-wu/FFmpeg` | FFmpeg 编译和定制 |
| openssl | `/Users/uc/code/zhiwei-wu/openssl` | OpenSSL 加密库 |
| whisper.cpp | `/Users/uc/code/zhiwei-wu/whisper.cpp` | Whisper AI 语音识别 |

## 使用场景

当你完成一个功能开发阶段，需要保存所有相关仓库的修改时，使用此 skill 进行统一提交。

**适用场景**：
- 完成 FFmpeg 编译并升级到 SkyPlayer
- 完成 Whisper 集成并更新播放器
- 完成 OpenSSL 配置并同步到项目
- 任何涉及多仓库协作的功能开发

## 执行流程

### 1. 检查各仓库状态

首先检查所有关联仓库的修改状态：

```bash
# 检查 SkyPlayer 状态
cd /Users/uc/code/SkyPlayer && git status

# 检查 FFmpeg 状态
cd /Users/uc/code/zhiwei-wu/FFmpeg && git status

# 检查 openssl 状态
cd /Users/uc/code/zhiwei-wu/openssl && git status

# 检查 whisper.cpp 状态
cd /Users/uc/code/zhiwei-wu/whisper.cpp && git status
```

### 2. 生成 Commit Message

**优先级规则**：
1. **用户提供的 commit message**：如果用户明确给出 commit message，直接使用
2. **自动生成**：如果用户未提供，根据当前开发的功能自动生成

**自动生成格式**：
```
[功能模块] 简短描述

详细说明：
- 修改点1
- 修改点2
- ...

关联仓库：
- SkyPlayer: 具体修改
- FFmpeg: 具体修改（如有）
- openssl: 具体修改（如有）
- whisper.cpp: 具体修改（如有）
```

**示例**：
```
[AI字幕] 集成 Whisper GPU 加速支持

详细说明：
- 添加 Vulkan GPU 后端支持
- 实现 GPU/CPU 自动回退机制
- 优化模型加载性能

关联仓库：
- SkyPlayer: 更新 JNI 接口和 Kotlin 层
- whisper.cpp: 启用 Vulkan 编译选项
```

### 3. 执行关联提交

对每个有修改的仓库执行提交：

```bash
# 提交 SkyPlayer（主仓库，必须提交）
cd /Users/uc/code/SkyPlayer
git add -A
git commit -m "commit message"

# 提交 FFmpeg（如有修改）
cd /Users/uc/code/zhiwei-wu/FFmpeg
git add -A
git commit -m "commit message"

# 提交 openssl（如有修改）
cd /Users/uc/code/zhiwei-wu/openssl
git add -A
git commit -m "commit message"

# 提交 whisper.cpp（如有修改）
cd /Users/uc/code/zhiwei-wu/whisper.cpp
git add -A
git commit -m "commit message"
```

### 4. 验证提交结果

```bash
# 查看各仓库最新提交
echo "=== SkyPlayer ===" && cd /Users/uc/code/SkyPlayer && git log -1 --oneline
echo "=== FFmpeg ===" && cd /Users/uc/code/zhiwei-wu/FFmpeg && git log -1 --oneline
echo "=== openssl ===" && cd /Users/uc/code/zhiwei-wu/openssl && git log -1 --oneline
echo "=== whisper.cpp ===" && cd /Users/uc/code/zhiwei-wu/whisper.cpp && git log -1 --oneline
```

## 快速命令

### 一键检查所有仓库状态

```bash
for repo in "/Users/uc/code/SkyPlayer" "/Users/uc/code/zhiwei-wu/FFmpeg" "/Users/uc/code/zhiwei-wu/openssl" "/Users/uc/code/zhiwei-wu/whisper.cpp"; do
  echo "=== $(basename $repo) ===" && cd "$repo" && git status -s
done
```

### 一键查看所有仓库 diff

```bash
for repo in "/Users/uc/code/SkyPlayer" "/Users/uc/code/zhiwei-wu/FFmpeg" "/Users/uc/code/zhiwei-wu/openssl" "/Users/uc/code/zhiwei-wu/whisper.cpp"; do
  echo "=== $(basename $repo) ===" && cd "$repo" && git diff --stat
done
```

## 注意事项

1. **提交顺序**：建议先提交依赖库（FFmpeg、openssl、whisper.cpp），再提交主项目（SkyPlayer）
2. **Commit 关联**：各仓库使用相同或相关的 commit message，便于追溯
3. **仅提交有修改的仓库**：检查 `git status` 后，只对有修改的仓库执行提交
4. **分支一致性**：确保各仓库在正确的开发分支上

## 回滚操作

如需回滚某个仓库的提交：

```bash
cd <仓库路径>
git reset --soft HEAD~1  # 保留修改，撤销提交
# 或
git reset --hard HEAD~1  # 完全撤销提交和修改
```
