# Summarize Skill 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建 summarize 技能，从会话记录中自动/手动总结经验，归档问题(problems)、经验(lessons)、工作记录(works)到结构化记忆体系。

**Architecture:** Skill + 过滤脚本 + SessionEnd Hook。SKILL.md 定义两种模式（自动/手动）的工作流，filter-session.sh 从 JSONL 中提取有效对话，Hook 在会话结束时触发自动总结。记忆按项目级/用户级两层存储，每层含 problems/lessons/works 三个分类。

**Tech Stack:** Bash (filter-session.sh), YAML frontmatter + Markdown (记忆文件), JSONL 解析 (jq/python3)

---

## 文件结构

```
~/.claude/skills/summarize/
├── SKILL.md                       # 新建：Skill 定义 + 完整 prompt
└── scripts/
    └── filter-session.sh          # 新建：JSONL 过滤脚本

~/.claude/hooks/
└── session-end-summarize.sh       # 新建：SessionEnd 触发入口

<project>/.claude/.remember/       # 新建：项目级记忆
├── problems/
│   └── MEMORY.md
├── lessons/
│   └── MEMORY.md
└── works/
    └── MEMORY.md
```

---

### Task 1: 创建目录结构

**Files:**
- Create: `~/.claude/skills/summarize/scripts/` (目录)
- Create: `<project>/.claude/.remember/problems/` (目录)
- Create: `<project>/.claude/.remember/lessons/` (目录)
- Create: `<project>/.claude/.remember/works/` (目录)

- [ ] **Step 1: 创建 skill 目录和脚本目录**

```bash
mkdir -p ~/.claude/skills/summarize/scripts
```

- [ ] **Step 2: 创建项目级记忆目录结构**

```bash
mkdir -p .claude/.remember/problems
mkdir -p .claude/.remember/lessons
mkdir -p .claude/.remember/works
```

- [ ] **Step 3: 初始化三个 MEMORY.md 索引文件**

```bash
cat > .claude/.remember/problems/MEMORY.md << 'EOF'
# Problems Index

> 待解决的问题列表。问题解决后更新 status 为 resolved。

EOF

cat > .claude/.remember/lessons/MEMORY.md << 'EOF'
# Lessons Index

> 已解决且可复用的经验。`skill_candidate: true` 表示建议升级为 skill。

EOF

cat > .claude/.remember/works/MEMORY.md << 'EOF'
# Works Index

> 会话工作索引，按日期排列，用于回溯定位。

EOF
```

- [ ] **Step 4: 验证目录结构**

```bash
ls -R .claude/.remember/
ls -R ~/.claude/skills/summarize/
```

- [ ] **Step 5: Commit**

```bash
git add .claude/.remember/
git commit -m "chore: 初始化 .remember/ 记忆目录结构

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: 编写 filter-session.sh

**Files:**
- Create: `~/.claude/skills/summarize/scripts/filter-session.sh`

- [ ] **Step 1: 创建过滤脚本**

```bash
cat > ~/.claude/skills/summarize/scripts/filter-session.sh << 'SCRIPT_EOF'
#!/usr/bin/env bash
# filter-session.sh — 从 JSONL 会话记录中提取 user + assistant 对话
#
# 用法:
#   filter-session.sh --project <name> --since 7d
#   filter-session.sh --project <name> --session <uuid>
#   filter-session.sh --project <name> --last 5
#
# 输出: 纯文本，按会话分组，每条消息带角色标签和时间戳

set -euo pipefail

PROJECT_DIR="${HOME}/.claude/projects"
PROJECT=""
SINCE=""
SESSION=""
LAST=""

usage() {
    echo "用法: $0 --project <name> [--since <nd>|--session <uuid>|--last <n>]"
    echo "  --project  项目名（projects/ 下的目录名）"
    echo "  --since    最近 N 天的会话（如 7d）"
    echo "  --session  指定会话 UUID"
    echo "  --last     最近 N 个会话"
    exit 1
}

# 解析参数
while [[ $# -gt 0 ]]; do
    case "$1" in
        --project) PROJECT="$2"; shift 2;;
        --since)   SINCE="$2"; shift 2;;
        --session) SESSION="$2"; shift 2;;
        --last)    LAST="$2"; shift 2;;
        *)         usage;;
    esac
done

[[ -z "$PROJECT" ]] && usage

# 查找项目目录（模糊匹配）
PROJECT_PATH=""
for d in "$PROJECT_DIR"/*/; do
    dirname=$(basename "$d")
    if echo "$dirname" | grep -qi "$PROJECT"; then
        PROJECT_PATH="$d"
        break
    fi
done

if [[ -z "$PROJECT_PATH" ]]; then
    echo "错误: 找不到项目目录匹配 '$PROJECT'" >&2
    echo "可用项目:" >&2
    for d in "$PROJECT_DIR"/*/; do
        echo "  $(basename "$d")" >&2
    done
    exit 1
fi

# 选择会话文件
if [[ -n "$SESSION" ]]; then
    FILES=("${PROJECT_PATH}${SESSION}.jsonl")
elif [[ -n "$LAST" ]]; then
    mapfile -t FILES < <(ls -t "${PROJECT_PATH}"*.jsonl 2>/dev/null | head -n "$LAST")
elif [[ -n "$SINCE" ]]; then
    DAYS="${SINCE%d}"
    mapfile -t FILES < <(find "$PROJECT_PATH" -name "*.jsonl" -mtime "-${DAYS}" -print0 2>/dev/null | xargs -0 ls -t 2>/dev/null)
else
    echo "错误: 需要 --since、--session 或 --last" >&2
    exit 1
fi

if [[ ${#FILES[@]} -eq 0 ]]; then
    echo "没有找到匹配的会话文件" >&2
    exit 0
fi

# 核心过滤逻辑
for f in "${FILES[@]}"; do
    [[ ! -f "$f" ]] && continue
    
    session_id=$(basename "$f" .jsonl)
    
    # 读取第一行获取时间戳
    first_ts=$(python3 -c "
import json, sys
try:
    line = sys.stdin.readline()
    d = json.loads(line)
    ts = d.get('timestamp', '')
    print(ts[:16].replace('T', ' '))
except: pass
" < "$f" 2>/dev/null)
    
    echo ""
    echo "=== SESSION: ${session_id} (${first_ts:-unknown}) ==="
    echo ""
    
    # 提取 user + assistant 消息
    python3 << PYEOF
import json, sys

with open("$f", "r", encoding="utf-8") as fh:
    for line in fh:
        line = line.strip()
        if not line:
            continue
        try:
            d = json.loads(line)
        except json.JSONDecodeError:
            continue
        
        msg_type = d.get("type", "")
        if msg_type not in ("user", "assistant"):
            continue
        
        message = d.get("message", {})
        content = message.get("content", "")
        
        # 提取纯文本内容
        text_parts = []
        if isinstance(content, str):
            text = content
        elif isinstance(content, list):
            for item in content:
                if isinstance(item, dict):
                    if item.get("type") == "text":
                        text_parts.append(item.get("text", ""))
                    elif item.get("type") == "tool_use":
                        name = item.get("name", "unknown_tool")
                        tool_input = item.get("input", {})
                        # 简短摘要
                        summary = f"[调用 {name}]"
                        if "file_path" in tool_input:
                            fp = tool_input["file_path"]
                            summary = f"[调用 {name}: {fp}]"
                        elif "pattern" in tool_input:
                            summary = f"[调用 {name}: {tool_input['pattern']}]"
                        text_parts.append(summary)
                    elif item.get("type") == "tool_result":
                        text_parts.append("[工具结果]")
                elif isinstance(item, str):
                    text_parts.append(item)
            text = " ".join(text_parts)
        else:
            continue
        
        # 跳过纯元信息和命令回显
        if not text.strip():
            continue
        if "<local-command-caveat>" in text:
            continue
        if "<ide_opened_file>" in text:
            continue
        if "<command-name>" in text and "<command-args>" in text:
            # 只显示命令名，不显示完整回显
            import re
            cmd_match = re.search(r'<command-name>(.*?)</command-name>', text)
            if cmd_match:
                print(f"[{msg_type.upper()}] /{cmd_match.group(1)}")
            continue
        
        # 清理 XML 标签
        text = re.sub(r'<[^>]+>', '', text)
        text = text.strip()
        if not text:
            continue
        
        role = "User" if msg_type == "user" else "Assistant"
        print(f"[{role}] {text}")

import re
PYEOF
    
    echo ""
    echo "---"
done
SCRIPT_EOF
```

- [ ] **Step 2: 设置可执行权限**

```bash
chmod +x ~/.claude/skills/summarize/scripts/filter-session.sh
```

- [ ] **Step 3: 测试脚本（基本语法检查）**

```bash
bash -n ~/.claude/skills/summarize/scripts/filter-session.sh
```

Expected: 无输出（语法正确）

- [ ] **Step 4: 测试脚本（功能测试）**

```bash
# 测试 --last 1 参数
bash ~/.claude/skills/summarize/scripts/filter-session.sh --project glassdemo --last 1
```

Expected: 输出最近一个会话的 user+assistant 对话摘要

---

### Task 3: 编写 SKILL.md

**Files:**
- Create: `~/.claude/skills/summarize/SKILL.md`

- [ ] **Step 1: 创建 SKILL.md 文件**

使用以下内容创建文件 `~/.claude/skills/summarize/SKILL.md`：

```markdown
---
name: summarize
description: >
  会话总结与经验归档。从当前会话或历史 JSONL 中提取问题、经验、工作记录，
  归档到 .remember/ 结构化记忆体系。支持自动（SessionEnd hook）和手动（/summarize）两种触发。
  触发场景：会话结束时自动触发、用户说"总结/总结经验/回溯/记录问题"时。
---

# summarize — 会话总结与经验归档技能

## 核心理念

每次开发会话都会产生知识——遇到的问题、解决的方案、做过的事情。如果不归档，这些知识就丢失在历史记录中。本技能将它们提取并结构化存储，让 Claude Code 在后续会话中能检索到。

```
会话 JSONL / 当前上下文
        ↓
   summarize 技能
        ↓
  ┌─────┼─────┐
problems  lessons  works
(待解决)  (已解决)  (回溯索引)
```

## 触发场景

- **自动**：SessionEnd hook 触发时
- **手动**：用户输入 `/summarize`、`/总结`，或提到"总结会话/总结经验/回溯记录"

## 记忆分类定义

| 分类 | 定义 | 状态流转 |
|------|------|----------|
| **problems** | 当前遇到的问题，尚未解决 | open → resolved |
| **lessons** | 已解决且可复用的经验/方法论/架构决策 | 永久保留，可升级为 skill |
| **works** | 会话工作索引，用于回溯定位 | 按日期追加 |

### Lesson vs Skill 边界

| 维度 | Lesson | Skill |
|------|--------|-------|
| **本质** | 可复用的知识点/规则 | 可执行的工作流 |
| **触发** | 被动引用 | 主动触发（命令/hook） |
| **产出** | 为决策提供信息 | 产出具体结果 |

一个 lesson 满足以下 **2-3 条**时，标记 `skill_candidate: true`：
1. 同一流程执行过 3 次以上
2. 流程有明确的 2+ 个步骤
3. 步骤中容易遗漏参数、顺序、边界条件
4. 步骤可以被 Claude 通过工具调用执行

## 工作流

### 模式 1：自动（SessionEnd hook 触发）

当前会话仍在上下文窗口中，直接基于记忆总结：

1. **扫描对话**：回顾本会话中的关键节点
   - 用户提出的问题是否都解决了？
   - 有哪些架构决策、踩坑经验、新的工作流？
   - 产生了哪些 commit？

2. **分类提取**：
   - 未解决的问题 → `problems/<slug>.md`（status: open）
   - 已解决的可复用经验 → `lessons/<slug>.md`
   - 本会话做了什么 → `works/YYYY-MM-DD.md`

3. **检查 skill 候选**：
   - 对每个 lesson，根据升级判断标准评估
   - 符合条件的设置 `skill_candidate: true`

4. **提取 commit 信息**：
   - 调用 `git log --oneline --since="<会话开始时间>"` 获取本会话产生的 commit
   - 对关键 commit 提取 `git diff` 摘要

5. **去重写入**：
   - 检查已有记录，同一天同一主题不重复创建
   - 增量更新 `MEMORY.md` 索引

6. **简短报告**：在写入完成后，用 2-3 句话报告本次归档了什么

### 模式 2：手动（/summarize 命令）

用户指定范围扫描历史会话：

1. **解析参数**：
   - `--since 7d`：最近 N 天
   - `--session <uuid>`：指定会话
   - `--last N`：最近 N 个会话
   - `--global`：写入用户级目录（否则项目级）

2. **调用过滤脚本**：
   ```bash
   bash ~/.claude/skills/summarize/scripts/filter-session.sh \
     --project <当前项目名> --since 7d
   ```

3. **逐会话总结**：
   - 读取过滤后的输出
   - 对每个会话按模式 1 的流程提取
   - 如果会话数量多（>5），派发子 agent 并行处理

4. **汇总报告**：
   - 跨会话发现重复出现的问题/模式
   - 输出总结报告

### 关键约束

- **默认项目级**：不加参数写入 `<project>/.claude/.remember/`
- **--global**：写入 `~/.claude/.remember/`
- **去重**：同一天同一主题不重复创建
- **增量**：索引文件只追加不重写
- **不编造**：不基于推理补充内容，只从对话中提取

## 记忆文件格式

### problems/<slug>.md

slug 命名：英文短横线，如 `ncnn-oom-at-960`

```markdown
---
status: open
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
```

### lessons/<slug>.md

```markdown
---
type: convention | fix | insight | workflow | skill
skill_candidate: false
skill_ref: ""
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

### 当前会话（自动模式）
直接从上下文中获取，无需外部数据源。

### 历史会话（手动模式）
- 位置：`~/.claude/projects/<项目名>/<UUID>.jsonl`
- 格式：每行一个 JSON 对象
- 关键字段：`type`（user/assistant/system/attachment）、`message.content`、`timestamp`、`sessionId`
- 过滤脚本：`~/.claude/skills/summarize/scripts/filter-session.sh`

## 示例

### 会话结束时自动触发

User: (会话结束，SessionEnd hook 触发)

Claude:
```
总结本会话：
- problem: NCNN 960 输入 OOM（待解决）
- lesson: JNI 调用统一通过 HiddenRiskNcnn.java（convention，skill_candidate: false）
- lesson: NCNN 模型导出流程（workflow，skill_candidate: true，步骤多且易出错）
- works: 已记录到 works/2026-06-12.md
```

### 手动扫描

User: /summarize --since 3d

Claude: (调用 filter-session.sh 扫描最近 3 天会话，逐条总结)
```

- [ ] **Step 2: 验证 SKILL.md 可以正常加载**

```bash
# 检查 skill 是否能被识别
ls -la ~/.claude/skills/summarize/SKILL.md
```

---

### Task 4: 编写 SessionEnd Hook

**Files:**
- Create: `~/.claude/hooks/session-end-summarize.sh`

- [ ] **Step 1: 创建 Hook 脚本**

```bash
cat > ~/.claude/hooks/session-end-summarize.sh << 'HOOK_EOF'
#!/usr/bin/env bash
# SessionEnd hook — 在会话结束时触发 summarize 技能的自动总结
# 
# 此 hook 通过 stdin 接收 JSON 格式的会话上下文
# 主要做两件事：
# 1. 记录会话结束信号
# 2. 提示 Claude 执行 summarize（通过 stdout 输出 additionalContext）

set -euo pipefail

# 读取 hook 输入
INPUT=$(cat)

# 提取关键信息
SESSION_ID=$(echo "$INPUT" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('session_id',''))" 2>/dev/null || echo "")
CWD=$(echo "$INPUT" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('cwd',''))" 2>/dev/null || echo "")

# 检查是否在 git 仓库中（决定是否执行项目级总结）
if [[ -n "$CWD" ]] && git -C "$CWD" rev-parse --git-dir >/dev/null 2>&1; then
    PROJECT_DIR="$CWD"
else
    PROJECT_DIR=""
fi

# 获取本次会话产生的 commits
COMMITS=""
if [[ -n "$PROJECT_DIR" ]]; then
    COMMITS=$(git -C "$PROJECT_DIR" log --oneline --since="2 hours ago" 2>/dev/null | head -5 || echo "")
fi

# 输出 additional context，触发 Claude 执行总结
cat << OUTPUT
{
  "hookSpecificOutput": {
    "hookEventName": "SessionEnd",
    "additionalContext": "=== SESSION END ===
会话即将结束。请执行 summarize 技能（自动模式）：

1. 回顾本会话中的关键节点
2. 将未解决的问题记录到 .claude/.remember/problems/
3. 将可复用的经验记录到 .claude/.remember/lessons/
4. 将会话工作摘要记录到 .claude/.remember/works/YYYY-MM-DD.md
5. 输出简短归档报告

本次会话产生的 commits:
${COMMITS:-无}",
    "systemMessage": "会话结束。请基于上下文执行 summarize 自动总结，将问题、经验、工作记录归档到 .remember/ 目录。"
  }
}
OUTPUT
HOOK_EOF
```

- [ ] **Step 2: 设置可执行权限**

```bash
chmod +x ~/.claude/hooks/session-end-summarize.sh
```

- [ ] **Step 3: 验证 Hook 脚本语法**

```bash
bash -n ~/.claude/hooks/session-end-summarize.sh
```

Expected: 无输出

---

### Task 5: 配置 settings.json Hook

**Files:**
- Modify: `~/.claude/settings.json`

- [ ] **Step 1: 在 settings.json 中添加 SessionEnd hook 配置**

需要说明：如果 Claude Code 不支持 `SessionEnd` hook 事件，则改用 `Stop` 事件。

在 `~/.claude/settings.json` 的 `"hooks"` 字段中添加：

```json
"SessionEnd": [
  {
    "matcher": "",
    "hooks": [
      {
        "type": "command",
        "command": "bash",
        "args": [
          "C:\\Users\\wuchaoli\\.claude\\hooks\\session-end-summarize.sh"
        ]
      }
    ]
  }
]
```

- [ ] **Step 1: 读取当前 settings.json**

使用 Read 工具读取 `~/.claude/settings.json`。

- [ ] **Step 2: 编辑添加 SessionEnd hook 配置**

在 `"hooks"` 对象中添加 `"SessionEnd"` 条目。如果 `SessionEnd` 不被支持，则改用 `"Stop"` 并在 matcher 中不做过滤。

- [ ] **Step 3: 验证 JSON 格式**

```bash
python3 -c "import json; json.load(open('$HOME/.claude/settings.json')); print('JSON valid')"
```

Expected: `JSON valid`

---

### Task 6: 端到端测试

- [ ] **Step 1: 测试 filter-session.sh 脚本**

```bash
# 测试最近 1 个会话
bash ~/.claude/skills/summarize/scripts/filter-session.sh --project glassdemo --last 1
```

验证：输出包含 `=== SESSION:` 头和 `[User]/[Assistant]` 标签。

- [ ] **Step 2: 测试手动模式（当前会话总结）**

在 Claude Code 中输入：`/summarize`

预期：Claude 基于当前上下文总结，输出 problems/lessons/works 并写入 `.remember/`。

- [ ] **Step 3: 验证写入内容**

```bash
cat .claude/.remember/problems/MEMORY.md
cat .claude/.remember/lessons/MEMORY.md
cat .claude/.remember/works/MEMORY.md
```

验证：索引文件中有新增条目。

- [ ] **Step 4: 测试手动模式（扫描历史）**

在 Claude Code 中输入：`/summarize --last 3`

预期：Claude 调用 filter-session.sh，扫描最近 3 个会话，提取并总结。

---

### Task 7: 提交内存结构到 git

- [ ] **Step 1: 提交初始 .remember 结构**

确认 `.claude/.remember/` 已被 Task 1 的 commit 包含。如有变更需要追加提交。

---

## Spec 覆盖检查

| Spec 需求 | 对应 Task |
|-----------|----------|
| Skill 文件结构（SKILL.md + scripts） | Task 1, 2, 3 |
| filter-session.sh 过滤脚本 | Task 2 |
| SessionEnd Hook | Task 4, 5 |
| 记忆分类（problems/lessons/works） | Task 1, 3 |
| 文件格式（frontmatter + markdown） | Task 3（SKILL.md 中定义） |
| Lesson-Skill 升级边界 | Task 3（SKILL.md 中定义） |
| 自动模式（SessionEnd） | Task 3（SKILL.md 中定义） |
| 手动模式（/summarize） | Task 3（SKILL.md 中定义） |
| commit/diff 信息记录 | Task 3（SKILL.md 中定义） |
| 默认项目级，--global 用户级 | Task 3（SKILL.md 中定义） |
| 去重 + 增量更新 | Task 3（SKILL.md 中定义） |
