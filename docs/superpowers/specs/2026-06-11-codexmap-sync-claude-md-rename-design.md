# codexmap-sync: README.md → CLAUDE.md 重命名设计

## 动机

Claude Code 启动时会递归向上遍历目录树加载 `CLAUDE.md`。当前项目三层文档体系中 L3 使用 `README.md`，Claude 从模块子目录启动时无法自动获取模块上下文。将模块文档统一命名为 `CLAUDE.md` 后：

- 从根目录启动 → 加载根 `CLAUDE.md`（L1），模块 `CLAUDE.md` 不会被加载（不会向下遍历）
- 从模块子目录启动 → 加载模块 `CLAUDE.md` + 根 `CLAUDE.md` + 全局 `CLAUDE.md`

## 变更范围

### Skill 文件

`~/.claude/skills/codexmap-sync/SKILL.md`：

- 描述行：`模块README.md (L3硬盘)` → `模块CLAUDE.md (L3硬盘)`
- L3 边界定义：`模块 README.md + 源码` → `模块 CLAUDE.md + 源码`
- 步骤 2/3 中所有 `README.md` 引用 → `CLAUDE.md`
- 初始化步骤中 `find` 搜索目标 → `CLAUDE.md`
- 常见场景示例中所有 `README.md` → `CLAUDE.md`
- 新增：模块 CLAUDE.md 行数约束（≤80 行）

### 项目文件

**文件重命名**（11 个文件）：

```
app/src/main/java/com/rokid/glass/
  camera/README.md       → CLAUDE.md
  component/README.md    → CLAUDE.md
  config/README.md       → CLAUDE.md
  data/README.md         → CLAUDE.md
  input/README.md        → CLAUDE.md
  network/README.md      → CLAUDE.md
  updater/README.md      → CLAUDE.md
  utils/README.md        → CLAUDE.md
  workflow/README.md     → CLAUDE.md
app/src/main/jni/
  README.md              → CLAUDE.md
scripts/android/
  README.md              → CLAUDE.md
```

**不动的文件**：

- `hiddenrisk/README.md` — 独立仓库，不修改
- `jni/ncnn/**/README.md` — NCNN 第三方库文档，保持原名

**引用链接更新**：

| 文件 | 变更类型 | 处数 |
|------|----------|------|
| 根 CLAUDE.md | 模块索引表 README → CLAUDE.md（8行）+ 文案更新（3处） | ~11 |
| docs/CODEMAPS.md | 依赖矩阵（10行）+ 任务速查（~10处） | ~20 |
| AGENTS.md | scripts/android/README.md → CLAUDE.md | 1 |

## 行数约束

| 约束级别 | 行数 | 适用 |
|----------|------|------|
| L1 硬约束 | ≤250 行 | 根 CLAUDE.md（当前 100 行，充足） |
| L3 硬约束 | ≤80 行 | 模块 CLAUDE.md（从子目录启动时自动加载） |

当前模块行数检查（9 个模块 + 1 JNI + 1 scripts）：

| 文件 | 当前行数 | 状态 |
|------|----------|------|
| camera | 46 | ✅ |
| component | 26 | ✅ |
| config | 49 | ✅ |
| data | 27 | ✅ |
| input | 62 | ✅ |
| network | 15 | ✅ |
| updater | 32 | ✅ |
| utils | 27 | ✅ |
| workflow | 39 | ✅ |
| jni | **104** | ❌ 需精简 24 行 |
| scripts/android | **116** | ❌ 需精简 36 行 |

## 实施步骤

1. 更新 `~/.claude/skills/codexmap-sync/SKILL.md`
2. 重命名 9 个模块 README.md → CLAUDE.md（git mv）
3. 重命名 jni/README.md → CLAUDE.md
4. 重命名 scripts/android/README.md → CLAUDE.md
5. 精简 jni/CLAUDE.md 到 ≤80 行
6. 精简 scripts/android/CLAUDE.md 到 ≤80 行
7. 更新根 CLAUDE.md 中所有引用链接
8. 更新 docs/CODEMAPS.md 中所有引用链接
9. 更新 AGENTS.md 中引用链接
10. 验证：确认所有链接路径指向正确文件
