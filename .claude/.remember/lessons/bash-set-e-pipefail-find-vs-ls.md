---
type: fix
skill_candidate: false
skill_ref: ""
project: glassdemo
date: 2026-06-12
session: summarize-skill-session
commits: []
tags: [bash, scripting, shell]
related_problems: []
---

# Bash: set -euo pipefail 下避免用 ls *.ext 统计文件

## 规则
在 `set -euo pipefail` 生效的脚本中，`ls /path/*.jsonl 2>/dev/null | wc -l` 在 glob 无匹配时会
因为 `ls` 返回非零 exit code（结合 `pipefail`）导致脚本直接退出。
替代方案：使用 `find /path -maxdepth 1 -name "*.jsonl" 2>/dev/null | wc -l`，`find` 在无结果时返回 0。

## 原因
`pipefail` 将管道的整体返回值设为最后一个非零退出码的命令。`ls` 在 glob 不匹配时返回 2，
即使 `2>/dev/null` 抑制了 stderr，`ls` 的退出码仍会通过 `pipefail` 传递。

## 适用范围
全局（所有使用 `set -euo pipefail` 的 bash 脚本）

## 来源
会话：实现 summarize skill 的 filter-session.sh 时，模糊匹配到了一个空的旧项目目录
