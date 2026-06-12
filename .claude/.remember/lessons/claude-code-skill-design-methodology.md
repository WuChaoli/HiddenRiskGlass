---
type: methodology
skill_candidate: false
skill_ref: ""
project: glassdemo
date: 2026-06-12
session: summarize-skill-session
commits: []
tags: [skill-design, claude-code, knowledge-management]
related_problems: []
---

# Claude Code Skill 设计方法

## 规则
一个合格的 Claude Code Skill 包含以下核心结构：
1. **name + description**（YAML frontmatter）：description 是触发关键词，需覆盖所有触发场景
2. **触发场景**（When to Use）：明确自动/手动触发条件
3. **核心理念**：用一句话 + 一个 ASCII 图解释 skill 做什么
4. **工作流**：分模式描述，每个步骤是可执行的动作而不是抽象指导
5. **输出格式**：给出具体模板，而不是"请用合适的格式"
6. **约束**：明确 DON'T（去重、不编造、增量更新）
7. **示例**：展示一个完整的输入→输出流程

## 原因
Skill 本质是一个 "元 prompt"——它告诉 Claude 在什么场景下、用什么步骤、产出什么格式。
Skill 的好坏决定了 Claude 能否在零额外指导的情况下正确执行任务。
好的 skill = 精确的触发条件 + 可执行的动作步骤 + 明确的输出模板 + 边界约束。

## 适用范围
全局（所有 Skill 创建场景）

## 来源
会话：实现 summarize skill 的过程
