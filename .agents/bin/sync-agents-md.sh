#!/usr/bin/env bash
# 由 .agents/rules/*.md 拼接生成根目录 AGENTS.md（供 Codex 等不支持 import 的 agent）。
# 改动 .agents/rules/ 后重跑本脚本。请勿手动编辑 AGENTS.md。
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_root"

out="AGENTS.md"

{
  echo "<!-- 本文件由 .agents/bin/sync-agents-md.sh 从 .agents/rules/*.md 生成，请勿手动编辑。 -->"
  echo "<!-- 修改规则请编辑 .agents/rules/ 下对应文件后重跑该脚本。 -->"
  echo
  echo "# SkyPlayer — 项目规则（始终生效）"
  echo
  echo "> 技能（skills）位于 \`.agents/skills/\`，Codex 会原生扫描该目录，无需在此声明。"
  echo
  for f in .agents/rules/*.md; do
    # 去除文件开头的 YAML frontmatter（--- ... ---）后输出正文
    awk 'NR==1 && $0=="---" {fm=1; next} fm==1 && $0=="---" {fm=0; next} fm!=1 {print}' "$f"
    echo
  done
} > "$out"

echo "已生成 ${out}（来源：.agents/rules/*.md）"
