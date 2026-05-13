---
description: 扫描当前修改并提交中文 git commit
---

扫描以下工作区修改，生成一条简洁的中文 git commit message 并提交：

**暂存区变更：**
!`git status --short 2>&1`

**变更摘要：**
!`git diff --stat 2>&1`

**最近提交风格参考：**
!`git log --oneline -5 2>&1`

要求：
1. 分析以上变更内容，用中文撰写一条简洁的 commit message（一行概括标题，空行后可跟详细说明）
2. commit message 遵循仓库历史风格（参考最近 5 条 commit 格式）
3. 先 `git add` 所有新增和修改文件（不添加未跟踪的无关文件）
4. 执行 `git commit -m "<message>"`
5. 不推送，仅在本地提交
6. 排除 `openspec/changes/archive/` 等临时目录
7. 如有 .gitignore 中已忽略的文件变更，不纳入提交
