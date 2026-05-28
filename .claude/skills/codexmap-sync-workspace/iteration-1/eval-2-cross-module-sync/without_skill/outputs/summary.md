# 文档同步分析：VoiceControlManager 交叉模块依赖

## 变更概述

- **新增**: `app/src/main/java/com/rokid/glass/hiddenrisk/VoiceControlManager.kt`（hiddenrisk 模块）
- **修改**: `app/src/main/java/com/rokid/glass/input/VoiceController.kt`（input 模块，新增对 VoiceControlManager 的调用）

## 核心架构影响

### 依赖方向变化

**变更前**（单向依赖）：
```
hiddenrisk → input
```
hiddenrisk 依赖 input（通过 UnifiedInputSession 注册触控/语音/头部手势动作）。

**变更后**（双向依赖）：
```
hiddenrisk ↔ input
```
- hiddenrisk → input：仍通过 UnifiedInput 依赖输入层
- input → hiddenrisk：VoiceController 调用 VoiceControlManager

这形成了模块间的**循环依赖**，是一个需要关注的架构信号。

---

## 需要更新的文档（按优先级排序）

### 1. `app/src/main/java/com/rokid/glass/hiddenrisk/README.md`

**变更原因**：VoiceControlManager.kt 是新增文件，模块文件索引缺少该条目；依赖关系声明已过时。

**具体更新**：

| 更新点 | 当前状态 | 应更新为 |
|--------|----------|----------|
| 文件索引 | 无 VoiceControlManager.kt | 在「基础/系统」或新建「语音控制」分类下添加该文件索引，含职责描述和关键入口 |
| 依赖关系 — 被依赖 | `无 — 本模块是业务顶层，其他模块不依赖此包` | 更新为 `input/ — VoiceController 调用 VoiceControlManager 进行语音控制管理` |

**建议分类**：`VoiceControlManager.kt` 的职责是管理语音控制状态/逻辑，若专为巡检页面服务，可放在「基础/系统」分类；若功能较独立，可新增「语音控制」分类。

---

### 2. `app/src/main/java/com/rokid/glass/input/README.md`

**变更原因**：VoiceController.kt 当前不在文件索引中；模块依赖声明缺少对 hiddenrisk 的依赖。

**具体更新**：

| 更新点 | 当前状态 | 应更新为 |
|--------|----------|----------|
| 文件索引 | 无 VoiceController.kt | 添加文件索引条目，说明其负责语音控制器的实现 |
| 依赖关系 — 依赖 | `Android Sensor API、Rokid Glass SDK` | 新增 `hiddenrisk/ — VoiceControlManager 提供语音控制管理能力` |
| 核心调用链 — 语音分发 | 仅提及 VoiceRecognition → UnifiedInputSession | 补充 VoiceController → VoiceControlManager 的调用链路 |

---

### 3. `CLAUDE.md`

**变更原因**：Kotlin 包结构表和统一输入层的描述未反映语音控制新能力。

**具体更新**：

| 更新点 | 当前状态 | 应更新为 |
|--------|----------|----------|
| Kotlin 包结构表 — `hiddenrisk/` | `NCNN 推理、巡检流程、在线检测、结果处理、上传 (~50 文件)` | 文件数更新（~51），可补充「语音控制管理」 |
| 统一输入层描述 | `input/ 包提供触控、语音、头部手势的统一抽象` | 补充说明语音控制由 hiddenrisk 的 VoiceControlManager 与 input 的 VoiceController 协同实现 |

**注意**：CLAUDE.md 中的包结构表描述较概括，不一定需要在此层级列出每个具体类名。关键是确保模块职责描述准确反映新增的语音控制能力。

---

### 4. `AGENTS.md`

**变更原因**：AGENTS.md 中的包结构列表与 CLAUDE.md 保持一致，若新类具有足够重要性，需同步。

**具体更新**：`hiddenrisk/` 的职责描述可同步 CLAUDE.md 的更新。若 VoiceControlManager/ VoiceController 不涉及 JNI/模型等 AGENTS.md 特别关注的领域，变更可保持最小。

---

### 5. `docs/公共能力/架构总览.md` 和 `docs/公共能力/统一输入设计与接入.md`

**状态**：这两个文件当前在 git 中显示为已删除（`D` 状态），工作树中不存在。

**如果恢复这些文件**，需要更新：
- **架构总览.md**：在五层架构图中体现 hiddenrisk 与 input 的双向依赖，说明语音控制子系统的定位
- **统一输入设计与接入.md**：添加 VoiceController 的设计说明，以及接入 VoiceControlManager 的调用约定

**建议**：先确认这些文档是否应该恢复（从 git 历史中取回），或从 CLAUDE.md / AGENTS.md 中移除对它们的引用。

---

## 架构风险提示

### 循环依赖问题

`VoiceControlManager` 放在 `hiddenrisk` 包中，但被 `input` 包引用，造成：
- `hiddenrisk` 依赖 `input`（UnifiedInput）
- `input` 依赖 `hiddenrisk`（VoiceControlManager）

这在 Kotlin/Gradle 中会编译通过（同一个 Gradle 模块内），但从架构可维护性角度看存在问题：
- 两个模块的职责边界变得模糊
- 未来若要将 hiddenrisk 或 input 独立为 library module，会遇到循环引用

### 建议考虑的替代方案

1. **将 VoiceControlManager 移至 `input/` 包**：如果它本质上管理的是语音控制状态，这更符合 input 模块的职责（统一输入层），也消除了循环依赖。
2. **将 VoiceControlManager 移至独立的 `voice/` 或共享的 `component/` 包**：如果 hiddenrisk 和 input 都需要调用它，放在第三方中立位置更合理。
3. **保持现状但在文档中明确说明**：如果重构成本过高，至少在 README 中显式标记这个双向依赖，并说明这是一个「有意的例外」，防止后人误以为模块间是完全单向解耦的。

---

## 同步执行顺序建议

1. 先更新 `hiddenrisk/README.md`（新增文件索引 + 修正依赖声明）
2. 再更新 `input/README.md`（新增文件索引 + 修正依赖声明）
3. 更新 `CLAUDE.md`（模块职责描述、包结构表）
4. 视需要更新 `AGENTS.md`
5. 如 docs/ 下文档被恢复，同步更新架构总览和统一输入设计文档
