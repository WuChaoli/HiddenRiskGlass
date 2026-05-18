# AI 代码定位脚手架 — 设计文档

日期：2026-05-18
状态：待实施

## 问题

当开发者描述任务修改时，AI 需要花费大量时间：
1. **找不到文件** — 不知道某功能对应哪个 Kotlin 文件，需多次 grep/glob 搜索
2. **看不懂调用链** — 找到文件后需逐个阅读才能理解 A→B→C 关系
3. **单文件过大** — 核心 Activity 动辄数千行，AI 一次读不完或占用过多 token

## 设计目标

让 AI 在 **3 次工具调用内** 定位到目标代码位置并理解其上下文。

## 方案

### 核心思路：模块级 README.md = 代码地图 + 业务真相源

每个功能模块目录下一个 `README.md`，是该模块的**唯一真相源**，包含三部分：
1. 业务逻辑 — 这个模块做什么、行为规则
2. 文件索引 — 每个文件负责什么、关键函数入口
3. 调用链 & 依赖 — 数据如何流转

AI 启动时只读 CLAUDE.md（总索引），根据任务描述定位到具体模块 README.md，按图索骥找到代码。

### README.md 模板

```markdown
# <模块名>/ — <一句话职责>

## 业务概述
<从 docs/ 迁入的核心业务逻辑，描述模块做什么、行为规则>

## 文件索引
| 文件 | 职责 | 关键入口 |
|------|------|----------|
| XxxActivity.kt | ... | onCreate(), startXxx() |
| XxxManager.kt | ... | init(), process() |

## 核心调用链
<关键流程的调用链，帮助 AI 理解数据流转>

## 依赖关系
- 依赖: <我依赖哪些模块>
- 被依赖: <哪些模块依赖我>
```

### CLAUDE.md 角色

精简为**总索引**，列出所有模块 README.md 和跨模块文档的指针：

```markdown
## 模块代码地图

| 模块 | README |
|------|--------|
| 隐患识别/推理 | app/src/.../hiddenrisk/README.md |
| 相机/帧流 | app/src/.../camera/README.md |
| ... | ... |

## 跨模块文档

| 文档 | 路径 |
|------|------|
| 架构总览 | docs/公共能力/架构总览.md |
| ... | ... |
```

### docs/ 处理

- **跨模块内容**（架构总览、旅程图、页面导航分层）→ 保留在 docs/
- **单模块业务逻辑**（如 docs/功能模块/隐患识别.md）→ 迁入对应模块 README.md
- 原 docs/ 文件 → 改为指向 README.md 的链接

### LSP 调用链搜索

Claude Code 内置 LSP 工具（`goToDefinition`、`findReferences`、`incomingCalls`），安装 `kotlin-language-server` 后可启用。用作 README.md 定位之后的补充手段。

## 范围

| 优先级 | 模块 | README 路径 |
|--------|------|-------------|
| P0 | hiddenrisk/ | app/src/main/java/com/rokid/glass/hiddenrisk/README.md |
| P0 | CLAUDE.md 更新 | 根目录 |
| P1 | camera/ | app/src/main/java/com/rokid/glass/camera/README.md |
| P1 | input/ | app/src/main/java/com/rokid/glass/input/README.md |
| P1 | workflow/ | app/src/main/java/com/rokid/glass/workflow/README.md |
| P1 | docs/ 旧文件迁移 | docs/功能模块/ |
| P2 | component/ | app/src/main/java/com/rokid/glass/component/README.md |
| P2 | config/ | app/src/main/java/com/rokid/glass/config/README.md |
| P2 | Kotlin LSP 配置 | .claude/settings.local.json |

## 验收标准

- AI 收到任务后，首次代码定位 ≤ 3 次工具调用
- 每个模块 README.md 包含：业务概述、文件索引（含关键函数）、调用链、依赖关系
- CLAUDE.md 的模块索引表覆盖所有主要模块
- docs/ 中已迁出文档改为指向 README.md 的链接

## 非目标

- 不重构代码结构（不拆文件、不改包结构）
- 不引入新的工具链依赖（LSP 为可选项）
- 不改变 docs/ 中跨模块文档的位置
