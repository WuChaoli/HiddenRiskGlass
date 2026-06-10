# Integration 集成测试

验证组件间协作、真机链路行为，非完整用户场景。

## 覆盖模块

- [hiddenrisk/](hiddenrisk/) — 隐患识别链路时序
- [updater/](updater/) — APK 热更新全链路
- [app/](app/) — 应用级文件日志
- (其他模块预留)

## 执行方式

1. 构建 debug APK：`bash scripts/android/build-debug.sh`
2. 安装：`bash scripts/android/install-debug.sh -s <serial>`
3. 按各 `TEST_CASES.md` 中的验证步骤执行
4. 证据归档到对应模块的 `evidence/YYYY-MM-DD_<描述>/`
