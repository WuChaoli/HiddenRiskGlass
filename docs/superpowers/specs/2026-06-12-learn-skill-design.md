# /learn Skill 设计文档

## 背景与目标

当前 `/summarize` 技能的核心问题是**与 `agentmemory` 职责重叠**。`agentmemory` 已经承担了记忆持久化（user/feedback/project/reference），而 `/summarize` 继续归档 problems/works/ 等记录类内容，造成重复劳动。

`/learn` 技能将焦点从「总结归档」升级为「经验学习」：

- 砍掉 `problems/` 和 `works/` 两类记录性输出
- 只保留 `lessons/` 作为核心产物
- 在识别到可执行工作流时，**即时询问用户**是否升级为 skill
- 自动触发时机从 PostCompact 改为 **PreCompact**——在上下文压缩前利用完整上下文提炼

```
会话上下文 / 历史 JSONL
        ↓
     /learn
        ↓
   ┌────┴────┐
 lessons   skills（经用户确认后创建）
（经验）    （可执行工作流）
```

## 架构与文件结构

```text
~/.claude/skills/learn/
├── SKILL.md                       # Skill 定义 + 完整 prompt
└── scripts/
    └── learn-from-history.sh      # 手动模式：从 JSONL 扫描历史会话

~/.claude/hooks/
└── precompact-learn.sh            # PreCompact 自动触发入口

<project>/.claude/.remember/
└── lessons/
    ├── MEMORY.md                  # lesson 索引
    └── <slug>.md                  # 单个 lesson 文件

~/.claude/.remember/               # --global 时
└── lessons/
    ├── MEMORY.md
    └── <slug>.md
```

旧 `problems/`、`works/` 保留为历史归档，新 `/learn` 不再写入它们。
`~/.claude/skills/summarize/` 及其 hook 已删除，仅保留 `.remember/` 中的历史条目。

`agentmemory` 继续负责通用记忆，与 `/learn` 的职责边界：

| 工具 | 负责 | 不负责 |
|------|------|--------|
| `agentmemory` | user/feedback/project/reference 记忆 | 从会话提炼可复用 lesson |
| `/learn` | 从会话提炼 lesson 和 skill 候选 | 通用用户画像、项目状态跟踪 |

## Lesson 文件格式

```yaml
---
type: convention | fix | insight | workflow | anti-pattern
level: concrete | tactical | strategic
domain: android | bash | claude-code | architecture | ...
project: glassdemo
date: 2026-06-12
session: <uuid>
commits: [hash, ...]
tags: [...]
related_lessons: [slug, ...]
skill_ref: ""                    # 升级后填充 skill 路径
---

# <标题>

## 触发场景
<这条 lesson 在什么情况下会被用到——具体的问题信号或上下文>

## 模式（怎么做）
<可复用的规则、步骤、决策>

## 反模式（不要怎么做）
<常见但错误的做法，避免踩坑>

## 为什么有效
<背后的原理、动机、约束>

## 边界条件
<什么时候不适用，或需要权衡>

## 证据
<相关 commit、代码片段、日志、文档链接>

## 适用范围
<项目级 | 全局>

## 来源
<会话 | CLAUDE.md | 代码审查 | 架构不变量>
```

### 字段说明

| 字段 | 说明 |
|------|------|
| `type` | lesson 类型：约定、修复、洞察、工作流、反模式 |
| `level` | 抽象层次：具体技巧 / 战术方法 / 战略决策 |
| `domain` | 所属领域，便于检索 |
| `skill_ref` | 升级后关联的 skill 路径 |
| `related_lessons` | 相关 lesson slug 列表 |

旧 lesson 文件保留原格式，新 lesson 使用新格式，不批量迁移。

## Skill 升级即时询问

当一条 lesson 满足以下 **3 条中的 2 条** 时，/learn 认为它具备 skill 升级条件：

1. 模式包含 ≥2 个明确步骤，顺序容易搞错或遗漏
2. 同一流程在不同会话中重复出现 ≥2 次
3. 步骤可被 Claude 通过工具调用执行

不升级的信号：
- 纯知识点、架构原则、单条规则
- 依赖人工判断或外部审批
- 只在极特殊场景下出现一次

### 询问流程

/learn 不写入 `skill_candidate` 标记，而是直接询问用户：

> 发现 `<lesson 标题>` 具备 skill 升级条件：
> - 包含明确步骤
> - 可重复执行
> - 可被 Claude 通过工具调用完成
>
> 是否将其升级为 `~/.claude/skills/<slug>/SKILL.md`？

- 用户同意：生成 SKILL.md 骨架，并在 lesson 中填充 `skill_ref`
- 用户拒绝：记录 `skill_declined: true` 到 frontmatter，避免重复询问

## 触发方式

### 自动：PreCompact Hook

在上下文被压缩**之前**触发，此时 Claude 仍拥有完整会话上下文。

流程：

1. 系统检测到上下文接近限制，执行 `precompact-learn.sh`
2. Hook 向 Claude 注入提示：「上下文即将压缩，请使用 /learn 从当前完整上下文中提炼 lesson」
3. Claude 回顾本会话，识别可复用经验
4. 检查 skill 升级条件并询问用户
5. 写入 lesson 文件并更新索引
6. 输出简短归档报告

### 手动：/learn 命令

```text
/learn                    # 直接总结当前会话
/learn --since 7d         # 最近 7 天历史会话
/learn --last 5           # 最近 5 个历史会话
/learn --session <uuid>   # 指定历史会话
/learn --global           # 写入 ~/.claude/.remember/（默认项目级）
```

流程：

1. 解析参数
2. 无历史参数时：直接基于当前上下文提炼
3. 有历史参数时：调用 `learn-from-history.sh` 从 JSONL 读取并逐会话提炼
4. 跨会话聚合重复主题
5. 检查 skill 升级条件并询问用户
6. 写入 lesson 文件并更新 `lessons/MEMORY.md` 索引
7. 输出报告

## 关键约束

- **默认项目级**：不加 `--global` 写入 `<project>/.claude/.remember/lessons/`
- **去重**：同一天同一主题不重复创建；检查已有 slug
- **增量**：索引文件只追加不重写
- **不编造**：只从对话和实际代码变更中提取
- **不重复记忆**：不提取 user/feedback/project/reference 等 agentmemory 已负责的内容
- **slug 命名**：英文短横线，简洁描述性（如 `ncnn-oom-at-960`、`jni-call-convention`）

## 错误处理

- 无 lesson 可提炼：明确报告「本次会话未产生可归档 lesson」，不创建空文件
- JSONL 读取失败：报告失败原因，但不阻塞其他会话的提炼
- 用户拒绝 skill 升级：记录 `skill_declined: true`，避免重复询问
- 索引与文件不一致：以文件系统为准，必要时重建索引

## 旧数据策略

- `~/.claude/skills/summarize/` 及其 hook 已删除，不再维护；新实现创建 `~/.claude/skills/learn/`
- `problems/`、`works/`：保留为历史归档，新 /learn 不再写入
- 现有 lessons/：保留原格式，新 lesson 使用新格式，不批量迁移

## 测试策略

**单元级验证：**
- 手动运行 `/learn`，确认 lesson 文件按新格式生成
- 运行 `/learn --last 3`，确认 JSONL 过滤和跨会话聚合正常
- 验证 `--global` 写入路径正确

**集成级验证：**
- 触发 PreCompact，确认 hook 在压缩前注入提示
- 验证 skill 升级询问流程：用户同意/拒绝都能正确处理
- 确认不去重创建同主题 lesson

**回归验证：**
- 旧 summarize 文件移除后，不影响现有 `.remember/lessons/` 索引
- agentmemory 正常运行，/learn 不与其冲突

## 待决策事项

1. `precompact-learn.sh` 不需要携带压缩摘要 JSON。PreCompact 时上下文尚未压缩，Claude 可直接访问完整上下文。
2. `learn-from-history.sh` 应复制 `filter-session.sh` 的核心逻辑，避免新 skill 依赖旧的 summarize skill。
3. `/learn --global` 默认仍扫描当前项目的历史会话，仅改变写入路径为 `~/.claude/.remember/lessons/`。跨项目扫描不在本次范围内。

---

**设计日期**: 2026-06-12
**关联旧设计**: `docs/superpowers/specs/2026-06-12-summarize-skill-design.md`
