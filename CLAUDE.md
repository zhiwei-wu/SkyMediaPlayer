# SkyPlayer — 项目说明

本项目的 rules 与 skills 统一维护在 `.agents/` 目录，由 Claude Code / Codex / Aone Copilot 多个 agent 共享，单一来源、避免重复。

## 项目规则（始终生效）

@.agents/rules/project-structure.md
@.agents/rules/features.md
@.agents/rules/code-style.md
@.agents/rules/FFmpeg_Compile.md
@.agents/rules/git-workflow.md

## 技能（Skills）

技能位于 `.agents/skills/`，已通过 `.claude/skills` 软链供 Claude Code 发现：

- `develop-player` — SkyPlayer 播放器开发指南（架构、音视频解码、渲染、同步等）
- `upgrade-ffmpeg` — 将新编译的 FFmpeg 库升级到项目中

> 说明：根目录 `AGENTS.md` 由 `.agents/rules/*.md` 拼接生成（供 Codex），改动规则后运行 `.agents/bin/sync-agents-md.sh` 重新生成。
