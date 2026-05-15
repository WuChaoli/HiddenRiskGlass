# Android 开发检查清单

## Phase 1: 架构设计
- [ ] 需求理解完整
- [ ] 模块划分合理
- [ ] 数据模型定义清晰
- [ ] 依赖关系明确
- [ ] 任务拆解完整

## Phase 2: 数据层
- [ ] Domain Model 是纯 Kotlin（零 Android 依赖）
- [ ] Network Model 与 API 响应匹配
- [ ] Entity Model 有正确的 Room 注解
- [ ] Model Mapper 完整覆盖所有转换
- [ ] Remote DataSource 使用 Retrofit
- [ ] Local DataSource 使用 Room DAO
- [ ] Repository 实现离线优先策略
- [ ] Repository 暴露 `Flow<T>` 而非 `suspend` 函数

## Phase 3: UI 层
- [ ] UiState 使用 sealed interface
- [ ] ViewModel 使用 `WhileSubscribed(5_000)`
- [ ] 单向数据流（事件向下，数据向上）
- [ ] Compose 组件无状态
- [ ] 使用 `collectAsStateWithLifecycle`
- [ ] LazyList 使用稳定的 item key
- [ ] 导航使用类型安全路由
- [ ] UI 设计有定制化主题（非 Material 默认）

## Phase 4: 依赖注入
- [ ] Hilt 模块正确配置
- [ ] 提供 Retrofit/OkHttp 实例
- [ ] 提供 Room Database 实例
- [ ] 提供 Repository 实现
- [ ] 提供 ViewModel 依赖
- [ ] 各层通过构造函数注入

## Phase 5: 测试
- [ ] ViewModel 单元测试覆盖
- [ ] Repository 单元测试覆盖
- [ ] 使用 Test Doubles（非 MockK/Mockito）
- [ ] Compose UI 测试覆盖
- [ ] 所有测试通过

## Phase 6: 构建验证
- [ ] `./gradlew clean` 成功
- [ ] `./gradlew assembleDebug` 成功
- [ ] `./gradlew test` 全部通过
- [ ] 无编译警告/错误
- [ ] APK/AAB 生成成功

## Phase 7: 代码审查
- [ ] 符合 NowInAndroid 架构模式
- [ ] 符合模块化规则
- [ ] 符合数据层规则
- [ ] 符合 UI 层规则
- [ ] 代码注释使用中文
- [ ] 无性能问题
- [ ] 无内存泄漏风险

## 架构合规性
- [ ] Feature 不依赖其他 Feature impl
- [ ] Core 不依赖 Feature
- [ ] core:model 零 Android 依赖
- [ ] 使用版本目录管理依赖
- [ ] 遵循单向数据流
