---
name: workflow-android-dev
description: "Android 开发工作流：从需求到上线的完整 Android 开发流程。用于需要从零开始开发 Android 功能模块、按 NowInAndroid 架构模式分步实现 UI/数据层/测试/构建时。"
---

# Workflow: Android Dev

## Overview

将 Android 功能需求拆解为完整的开发流程，按 NowInAndroid 架构模式分步实现：架构设计 → UI 开发 → 数据层 → 依赖注入 → 测试 → 构建验证。

本工作流由 Orchestrator Agent 加载执行，通过分派 subagent 完成各阶段任务。

## Execution Phases

### Phase 1: 架构设计与模块规划

**目标**：理解需求，设计 Android 架构方案

**步骤**：
1. 读取需求文档或功能描述
2. 委派 `architect` subagent 进行 Android 架构设计：
   - 输入：需求文档、现有项目结构
   - 输出：架构文档（含模块划分、数据模型、接口设计、依赖关系）
3. 委派 `planner` subagent 进行任务拆解：
   - 输入：架构设计文档
   - 输出：详细的 Android task 列表（按架构层次排序）

**Checkpoint - 架构确认**：
- 向用户展示架构设计文档
- 向用户展示 task 列表
- 等待用户确认或提出修改意见
- **用户确认前不得进入 Phase 2**

### Phase 2: 数据层开发

**目标**：实现数据模型、数据源、Repository

**步骤**：
1. 按 task 列表分派 `backend-dev` 或 `android-dev` 执行：
   - 定义 Domain Model（core:model，纯 Kotlin，零 Android 依赖）
   - 定义 Network Model（API 响应数据结构）
   - 定义 Entity Model（Room 数据库实体）
   - 实现 Model Mapper（Network/Entity ↔ Domain）
2. 实现数据源：
   - Remote DataSource（Retrofit + OkHttp）
   - Local DataSource（Room DAO + DataStore）
3. 实现 Repository（离线优先策略）：
   - 优先从本地数据库读取
   - 后台同步远程数据
   - 暴露 `Flow<T>` 而非 `suspend` 函数

**注意**：
- 此阶段全自动执行
- 遵循 `android-development` skill 中的 `data-*` 规则

### Phase 3: UI 层开发

**目标**：实现 ViewModel、Compose UI、导航

**步骤**：
1. 实现 ViewModel：
   - 定义 UiState（sealed interface：Loading/Success/Error）
   - 暴露 `StateFlow<UiState>` 使用 `WhileSubscribed(5000)`
   - 处理用户事件（单向数据流）
2. 实现 Compose UI：
   - Route 层（ViewModel + 导航逻辑）
   - Screen 层（纯 UI，无状态）
   - Component 层（可复用组件）
3. 实现导航：
   - 使用 Navigation Compose 2.8+
   - 类型安全的路由定义
   - 深链接支持（如需要）

**注意**：
- 此阶段全自动执行
- 遵循 `jetpack-compose` 和 `android-development` skill 中的 `compose-*` 规则
- UI 设计避免 Material 默认样式，使用定制化主题

### Phase 4: 依赖注入与集成

**目标**：配置 Hilt DI，集成各层

**步骤**：
1. 配置 Hilt 模块：
   - 提供 Retrofit/OkHttp 实例
   - 提供 Room Database 实例
   - 提供 Repository 实现
   - 提供 ViewModel 依赖
2. 集成各层：
   - UI 层 → ViewModel（通过 `hiltViewModel()`）
   - ViewModel → Repository（通过构造函数注入）
   - Repository → DataSource（通过构造函数注入）

**注意**：
- 此阶段全自动执行
- 遵循 `android-kotlin-development` skill 中的 Hilt 配置指南

### Phase 5: 测试

**目标**：确保代码质量

**步骤**：
1. 委派 `android-test-engineer` subagent：
   - 单元测试（ViewModel + Repository + UseCase）
   - 使用 Test Doubles（不使用 MockK/Mockito）
   - Compose UI 测试
   - 仪器测试（如需要）
2. 执行测试：
   - `./gradlew testDebugUnitTest` - 单元测试
   - `./gradlew connectedAndroidTest` - 仪器测试
3. 修复测试失败：
   - 代码问题 → 分派回对应 subagent 修复
   - 测试问题 → `android-test-engineer` 自行修复

**注意**：
- 此阶段全自动执行
- 遵循 `qa-testing-android` skill 中的测试工作流

### Phase 6: 构建验证

**目标**：确保项目可构建、产物正确

**步骤**：
1. 委派 `android-build-engineer` subagent：
   - 清理构建：`./gradlew clean`
   - 完整构建：`./gradlew assembleDebug`
   - Release 构建：`./gradlew assembleRelease`（如需要）
   - 运行所有测试：`./gradlew test`
2. 验证构建产物：
   - APK/AAB 生成成功
   - 无编译警告/错误
   - 测试全部通过
3. 构建优化（如需要）：
   - 检查依赖大小
   - 检查 R8/ProGuard 规则
   - 检查构建时间

**注意**：
- 此阶段全自动执行
- 构建失败时，`android-build-engineer` 自动诊断和修复

### Phase 7: 代码审查与提交

**目标**：代码质量审查，提交代码

**步骤**：
1. 委派 `analyze` subagent 进行代码审查：
   - 检查架构合规性（是否符合 NowInAndroid 模式）
   - 检查代码质量和最佳实践
   - 检查性能问题
2. 修复审查发现的问题：
   - 严重问题 → 分派回对应 subagent 修复
   - 轻微问题 → 记录到 TODO
3. 创建 Git 提交：
   - 按功能模块分组 commit
   - commit message 遵循 Conventional Commits

**Checkpoint - 提交确认**：
- 向用户展示代码审查报告
- 向用户展示变更摘要
- 等待用户确认提交

## Task Assignment Matrix

| 任务类型 | Subagent | 所需权限 |
|---------|----------|---------|
| 架构设计 | `architect` | read, write |
| 任务拆解 | `planner` | read, write |
| 数据层开发 | `android-dev` / `backend-dev` | read, write, bash |
| UI 层开发 | `android-dev` | read, write, bash |
| 依赖注入 | `android-dev` | read, write, bash |
| 测试 | `android-test-engineer` | read, write, bash |
| 构建验证 | `android-build-engineer` | read, write, bash |
| 代码审查 | `analyze` | read |

## Architecture Rules

开发过程中必须遵循的架构规则：

### 数据层规则
- Domain Model 必须是纯 Kotlin，零 Android 依赖
- Repository 暴露 `Flow<T>`，不使用 `suspend getX()`
- 离线优先：本地数据库是数据源真相
- 使用 Model Mapper 分离各层模型

### UI 层规则
- 单向数据流：事件向下，数据向上
- UiState 使用 sealed interface（Loading/Success/Error）
- Compose 组件无状态，状态提升到调用方
- 使用 `collectAsStateWithLifecycle` 收集 Flow
- LazyList 使用稳定的 item key

### 模块化规则
- Feature 模块分为 api（导航接口）和 impl（实现）
- Feature 之间不互相依赖
- Core 模块不依赖 Feature 模块
- 使用版本目录（libs.versions.toml）管理依赖

### 测试规则
- 使用 Test Doubles（接口实现），不使用 MockK/Mockito
- ViewModel 测试验证 UiState 转换
- 使用 TestDispatcherRule 控制协程时序
- Compose UI 测试使用 `createComposeRule`

## Error Handling

当 subagent 执行失败时：

1. **分析原因**：检查错误信息，判断失败原因
2. **调整策略**：
   - 依赖缺失 → 安装依赖
   - 配置错误 → 修正配置
   - 代码问题 → 提供更多信息或调整 task 描述
3. **自动重试**：最多重试 3 次，每次重试前调整策略
4. **暂停汇报**：3 次重试后仍失败：
   - 记录失败的任务信息
   - 记录已尝试的重试策略
   - 记录错误详情
   - 提供建议的解决方案
   - 暂停执行，等待用户指示

## Constraints

- 严格按照 Phase 顺序执行，不得跳过 Phase
- Phase 1 完成后必须等待用户确认架构设计
- Phase 7 完成后必须等待用户确认才能提交
- 除汇报点外，其他阶段全自动执行
- 遵循 NowInAndroid 架构模式和 Google 官方指南
- 遵循所有依赖的 Android skill 中的规则
- 代码注释使用中文
