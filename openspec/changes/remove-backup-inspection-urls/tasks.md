## 1. 配置层清理

- [ ] 1.1 从 `SaveResultApiConfig` 中删除 `backupBaseUrl` 和 `backupSaveResultUrl` 字段
- [ ] 1.2 从 `SaveResultApiConfigOverride` 中删除 `backupBaseUrl` 字段
- [ ] 1.3 从 `InspectionConfigRepository` 的 merge 逻辑中删除 `backupBaseUrl` 覆盖行
- [ ] 1.4 从 `HttpUtils` 中删除 `BACKUP_BASE_URL` 和 `BACKUP_SAVE_RESULT_URL` 常量

## 2. Session 层清理

- [ ] 2.1 从 `InspectionWorkflowSession` 中删除 `DualSubmitProgress` 数据类
- [ ] 2.2 删除 `finishSubmitProgress`、`phoneSyncProgress` 属性，替换为简单布尔标志
- [ ] 2.3 删除 `markFinishSubmitBackupDone()`、`markPhoneSyncBackupDone()` 方法
- [ ] 2.4 简化 `markFinishSubmitPrimaryDone()`、`markPhoneSyncPrimaryDone()` 方法

## 3. Save API 改造

- [ ] 3.1 在 `LocalHazardPushService.pushLocalHazard()` 中移除备份端点提交分支
- [ ] 3.2 移除 `DualEndpointSubmitCoordinator` 的使用，直接绑定单端点回调
- [ ] 3.3 从 `LocalHazardPushApiProtocol` 中删除 `BACKUP_REQUEST_URL`
- [ ] 3.4 删除 `RequestContext` 中的 `backupUrl` 字段，重命名为更合适的名称

## 4. End API 改造

- [ ] 4.1 在 `InspectionFinishService.finishInspection()` 中移除备份端点提交分支
- [ ] 4.2 移除 `DualEndpointSubmitCoordinator` 的使用，直接绑定单端点回调
- [ ] 4.3 从 `InspectionFinishApiProtocol` 中删除 `BACKUP_REQUEST_URL`
- [ ] 4.4 移除 `markFinishSubmitBackupDone()` 调用

## 5. 删除 DualEndpointSubmitCoordinator

- [ ] 5.1 删除 `DualEndpointSubmitCoordinator.kt` 文件
- [ ] 5.2 删除 `DualEndpointSubmitCoordinatorTest.kt` 测试文件

## 6. 测试更新

- [ ] 6.1 更新 `LocalHazardPushApiProtocolTest`：删除 `backupRequestUrl_isFixedEndpoint` 测试
- [ ] 6.2 更新 `InspectionFinishApiProtocolTest`：删除 `backupRequestUrl_isFixedEndpoint` 测试
- [ ] 6.3 确认 `InspectionRetryExecutorTest` 无需修改

## 7. 构建验证

- [ ] 7.1 运行 `./gradlew test` 确保所有单元测试通过
- [ ] 7.2 运行 `./gradlew assembleDebug` 确保编译成功
