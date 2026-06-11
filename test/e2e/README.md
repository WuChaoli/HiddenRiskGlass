# E2E 端到端测试

验证完整用户场景，从 Launcher 到功能结束的全链路。

## 覆盖模块

- [menu/](menu/) — 主菜单、二级菜单交互
- [app/](app/) — 应用级行为（自启、可见性、全局错误）
- (其他模块预留)

## 执行方式

1. 构建 debug APK：`bash scripts/android/build-debug.sh`
2. 安装：`bash scripts/android/install-debug.sh -s <serial>`
3. 按各 `TEST_CASES.md` 中的验证步骤执行
4. 证据归档到对应模块的 `evidence/YYYY-MM-DD_<描述>/`
