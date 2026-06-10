# Test Evidence

## 2026-06-10_app_visibility

- 目标：验证隐藏远程协作及其他业务应用，并在三轮息屏、亮屏后保持目标应用顺序。
- 设备：`1901092544019017`，adb 状态为 `device`。
- 构建：`:app:testStandardDebugUnitTest` 定向测试与 `:app:assembleStandardDebug` 通过，`adb install -r` 安装成功。
- 结论：
  - 最终顺序为隐患巡检、扫一扫、设置、系统切换。
  - 远程协作、AI 工作助手、AI 问答、AI 巡检、离线人脸、离线车牌、拍照和 AI 识别均隐藏。
  - 三轮亮屏均触发两次延迟配置，第一次与第二次回调各 `3/3` 次成功。
  - 三轮结束后应用进程仍存活，Launcher 未恢复被隐藏应用。
- 证据：
  - `launcher_after_three_screen_cycles.png`
  - `visibility_refresh_logcat.txt`
  - `verification_summary.txt`

## 2026-06-10_boot_auto_start

- 目标：验证应用写入 `persist.vendor.boot.pkg` 后，Rokid Glass 重启可自动拉起应用。
- 设备：`1901092544019017`，adb 状态为 `device`。
- 构建：`:app:assembleStandardDebug` 通过，`adb install -r -d` 安装成功。
- 结论：
  - 首次启动将空属性写为 `com.rokid.glesse`，回读成功。
  - 再次启动识别属性已配置并跳过重复写入。
  - 设备重启后，`RokidLauncher` 自动拉起 `AiInspectionMenuActivity`。
  - 应用按既有启动流程进入 `EnterpriseQrScanActivity`，进程和前台 Activity 正常。
- 证据：
  - `property_after_reboot.txt`
  - `logcat_boot_auto_start.txt`
  - `activity_after_reboot.txt`
  - `01_after_reboot.png`

## 2026-05-19_menu_confirm_focus_fix

- 目标：验证菜单确认只走统一输入，避免点击时 RecyclerView 子项抢焦点导致跳到“检查更新”。
- 设备：`1901092534053550`，adb 状态为 `device`。
- 构建：`:app:assembleStandardDebug` 通过，`adb install -r app-standard-debug.apk` 安装成功。
- 结论：
  - `MenuCardAdapter` 已移除 item/card 的普通 View click 回调。
  - 菜单功能进入统一保留为 `selectedIndex` 确认路径。
  - 安装后应用可启动，无菜单 / RecyclerView 相关崩溃日志。
  - 当前设备停在 `EnterpriseQrScanActivity`，因未扫码进入菜单，本轮未完成菜单确认行为的人工真机闭环。
- 证据：
  - `01_after_install_enterprise_qr.png`
  - `logcat_after_install.txt`
  - `activity_after_install.txt`

## 2026-05-19_menu_update_prompt_and_confirm

- 目标：验证更新提示自动弹窗去重、取消后生命周期内不再自动弹出，以及菜单确认错位修复。
- 设备：`1901092534053550`，adb 状态为 `device`。
- 构建：`:app:assembleStandardDebug` 通过，`adb install -r app-standard-debug.apk` 安装成功。
- 结论：
  - 启动企业扫码页后自动更新提示可正常弹出。
  - 返回提示页后，同一 app 进程内再次进入入口链路，没有第二次自动弹出更新提示。
  - 菜单 item click 已改为只同步选中态，确认动作统一按 `selectedIndex` 进入功能，避免触发中间卡片。
  - 当前设备停在 `EnterpriseQrScanActivity`，因未扫码进入菜单，本轮未完成菜单确认错位的人工真机闭环。
- 证据：
  - `01_after_second_auto_check_no_prompt.png`
  - `logcat_update_prompt_and_confirm.txt`
  - `activity_after_second_auto_check.txt`

## 2026-05-19_menu_scroll_bounds

- 目标：验证主菜单选中卡片滑动边界修复。
- 设备：`1901092534053550`，adb 状态为 `device`。
- 构建：`:app:assembleStandardDebug` 通过，`adb install -r app-standard-debug.apk` 安装成功。
- 结论：
  - 代码已改为按选中卡片实际边界校准横向滚动，首尾索引不变时不会继续滚动。
  - 安装后应用可启动，无 `FATAL EXCEPTION` / `RecyclerView` 相关崩溃日志。
  - 当前设备停在 `EnterpriseQrScanActivity`，因未扫码进入菜单，本轮未完成菜单首尾滑动的人工真机确认。
- 证据：
  - `01_after_install_enterprise_qr.png`
  - `logcat_after_install.txt`
  - `activity_after_install.txt`

## 2026-05-19_apk_update_device_smoke

- 目标：验证局域网 APK 热更新链路的服务器、自动检查、提示页、下载安装和系统安装器拉起流程。
- 设备：`1901092534053550`，adb 状态为 `device`。
- 服务：`tools/apk_update_server/serve.ps1 -Port 8080`，本机 `update.json` 可访问。
- 构建：`:app:assembleStandardDebug` 通过，`adb install -r` 安装成功。
- 结论：
  - 自动检查命中新版本并启动 `AppUpdatePromptActivity`。
  - 自动提示页随后被 `InspectionLoadingActivity` 的后续导航顶掉，最终进入 `EnterpriseQrScanActivity`，这是本轮发现的问题。
  - 通过提示页确认后，APK 下载和 `FileProvider` 安装 intent 已触发，系统 `PackageInstallerActivity` 成为 top resumed activity。
  - 手动检查入口所在的 `AiInspectionMenuActivity` 为 `exported=false`，本轮未能通过 adb 直接进入菜单页完成自动化验证。
- 证据：
  - `server_manifest.json`
  - `logcat_update.txt`
  - `activity_state_after_install_attempt.txt`
  - `window_state_after_install_attempt.txt`
  - `01_auto_update_prompt.png`
  - `02_menu_update_entry.png`
  - `04_downloading.png`
  - `05_package_installer_or_permission.png`
