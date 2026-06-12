# Summarize Skill 设计文档

## 概述

创建一个 Claude Code 技能，用于从会话记录中总结经验，将问题、经验、工作记录归档到结构化记忆体系中。

## 触发方式

- **自动**：SessionEnd hook 触发，当前 Claude 窗口直接从上下文总结
- **手动**：`/summarize [--since 7d|--session <uuid>|--last N] [--global]`

## 文件结构

```
~/.claude/skills/summarize/
├── SKILL.md                       # Skill 定义 + 完整 prompt 指引
└── scripts/
    └── filter-session.sh          # JSONL 过滤脚本

~/.claude/hooks/
└── session-end-summarize.sh       # SessionEnd hook

<project>/.claude/.remember/       # 项目级记忆（默认）
├── problems/
│   ├── MEMORY.md                  # 问题索引
│   └── <slug>.md
├── lessons/
│   ├── MEMORY.md                  # 经验索引
│   └── <slug>.md
└── works/
    ├── MEMORY.md                  # 工作索引
    └── YYYY-MM-DD.md

~/.claude/.remember/               # 用户级通用记忆（--global）
├── problems/
├── lessons/
└── works/
```

## 记忆分类定义

| 分类 | 定义 | 状态 |
|------|------|------|
| **problems** | 当前遇到的问题，尚未解决 | open → resolved |
| **lessons** | 已解决且可复用的经验/方法论/架构决策 | 永久保留 |
| **works** | 会话工作索引，用于回溯定位 | 按日期追加 |

## Lesson 与 Skill 的边界

| 维度 | Lesson | Skill |
|------|--------|-------|
| **本质** | 可复用的知识点/规则 | 可执行的工作流 |
| **触发** | 被动引用（遇到相关场景时检索） | 主动触发（命令/hook/关键词） |
| **产出** | 为决策提供信息 | 产出具体结果（代码、文件、报告） |
| **结构** | 标题 + 规则 + 原因 | 触发条件 + 步骤 + 工具 + 输出格式 |

### 升级判断标准

一个 lesson 满足以下 **2-3 条**时，建议标记为 skill 候选：

1. **重复性**：同一流程执行过 3 次以上，每次都需要回忆步骤
2. **步骤化**：流程有明确的 2+ 个步骤，不是单条命令
3. **易错性**：步骤中容易遗漏参数、顺序、或边界条件
4. **可自动化**：步骤可以被 Claude 通过工具调用执行

### 升级流程

- 总结时 AI 识别满足条件的 lesson，在其 frontmatter 中设置 `skill_candidate: true`
- 用户后续统一检查所有标记为候选的 lesson，决定是否创建 skill
- 创建 skill 后，将 lesson 的 `type` 改为 `skill`，并添加 `skill_ref: <skill名>` 指向对应 skill

## SKILL.md 核心工作流

### 自动模式（SessionEnd）

1. 基于当前对话上下文总结
2. 识别：待解决问题、可复用经验、本会话做了什么
3. 分别写入对应目录，更新 MEMORY.md 索引
4. 提取关联的 git commit/diff 信息一并写入

### 手动模式（/summarize）

1. 解析参数确定扫描范围
2. 调用 `scripts/filter-session.sh` 过滤 JSONL
3. 将过滤后文本交给当前 Claude 或子 agent 总结
4. 同自动模式的写入逻辑

### 关键约束

- 默认写入项目级目录，`--global` 写入用户级
- 写入前去重：同一天同一主题不重复创建
- 增量更新索引文件

## filter-session.sh 设计

### 功能
从原始 JSONL 中提取 user + assistant 对话，去除 hook/attachment/system 噪声。

### 用法
```bash
filter-session.sh --project <name> --since 7d
filter-session.sh --project <name> --session <uuid>
filter-session.sh --project <name> --last 5
```

### 处理逻辑
- 只保留 `type: "user"` 和 `type: "assistant"` 行
- 跳过 `<ide_opened_file>`、`<local-command-caveat>` 等元信息
- Tool call 做摘要标记而非完整输出
- 保留 timestamp 和 sessionId

## 记忆文件格式

### problems/<slug>.md
```markdown
---
status: open | resolved
severity: high | medium | low
project: <name>
date: YYYY-MM-DD
session: <uuid>
commits: [hash, ...]
tags: [...]
---

# <标题>

## 现象
<问题表现>

## 当前状态
<处理进展>

## 相关变更
- `<hash>` <msg>
  ```diff
  <关键 diff>
  ```

## 相关文件
- <文件路径>
```

### lessons/<slug>.md
```markdown
---
type: convention | fix | insight | workflow | skill
skill_candidate: false          # AI 判断是否建议升级为 skill
skill_ref: ""                   # 升级为 skill 后填写 skill 名称
project: <name>
date: YYYY-MM-DD
session: <uuid>
commits: [hash, ...]
tags: [...]
related_problems: [slug, ...]
---

# <标题>

## 规则
<经验内容>

## 原因
<为什么>

## 相关变更
- `<hash>` <msg>
  ```diff
  <关键 diff>
  ```

## 适用范围
<项目级 | 全局>

## 来源
<会话 | CLAUDE.md | 架构不变量>
```

### works/YYYY-MM-DD.md
```markdown
# YYYY-MM-DD

## <项目名>
- **Session <uuid>**: <一句话摘要>
  - Commits: `<hash>`, ...
  - 涉及: <文件列表>
  - 产出: problem `<slug>`, lesson `<slug>`
```

### MEMORY.md（索引）
```markdown
# Problems Index
- [<标题>](problems/<slug>.md) — <status>, <severity>, <date>

# Lessons Index
- [<标题>](lessons/<slug>.md) — <type>, <date>

# Works Index
- [YYYY-MM-DD](works/YYYY-MM-DD.md) — <项目名>: <摘要>
```

## 数据源

会话 JSONL 位于 `~/.claude/projects/<项目名>/<UUID>.jsonl`：
- 每个会话一个 JSONL 文件，平均约 1MB
- 包含 user、assistant、system、attachment、file-history-snapshot 等类型
- `user` 消息：`message.content` 为文本数组
- `assistant` 消息：`message.content` 为文本数组，含 tool call

## 完成标准

1. SKILL.md 编写完成并通过 skill 校验
2. filter-session.sh 脚本可正常运行
3. SessionEnd hook 配置完成
4. 手动 + 自动两种模式均可正常工作
5. 记忆文件格式符合设计
