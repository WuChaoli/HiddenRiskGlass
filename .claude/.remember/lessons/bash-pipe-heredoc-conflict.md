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

# Bash: pipe 与 heredoc 不能混用

## 规则
在 bash 中，`cmd | python3 << 'HEREDOC'` 会导致 stdin 冲突——pipe 的输出和 heredoc 的输入都试图占据 stdin，
实际结果不可预测。需要将数据通过命令行参数传递：`python3 - "${ARRAY[@]}" << 'HEREDOC'`，
Python 端从 `sys.argv[1:]` 读取。

## 原因
bash 中 pipe 和 heredoc 是两种不同的 stdin 重定向方式，同时使用时优先级行为不确定，
这会导致数据丢失或脚本挂死。当你用 `set -euo pipefail` 时，这类问题会直接导致脚本静默退出。

## 适用范围
全局（所有 bash 脚本）

## 来源
会话：实现 summarize skill 的 filter-session.sh 时踩坑
