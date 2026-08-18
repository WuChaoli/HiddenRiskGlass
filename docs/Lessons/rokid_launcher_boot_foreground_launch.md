# Rokid Launcher 开机前台拉起应用经验

日期：2026-06-09

## 目标

眼镜开机完成后自动在前台打开业务应用：

- 包名：`com.rokid.glesse`
- 启动页：`com.rokid.glass.AiInspectionMenuActivity`
- 测试 OTA：`1.17.e002-20260509-150201`

## 普通 Android 开机广播测试

测试方案是在应用中声明：

- `android.permission.RECEIVE_BOOT_COMPLETED`
- `BOOT_COMPLETED`
- `LOCKED_BOOT_COMPLETED`
- Direct Boot 支持

接收广播后延迟 8 秒，通过 `FLAG_ACTIVITY_NEW_TASK` 拉起菜单页。

### 真机结果

应用在重启前已通过 Launcher 方式启动，`dumpsys package` 显示：

```text
stopped=false
```

重启后，系统日志出现：

```text
handleVendorSysTypeChange enterprise system enabled pkgName: com.rokid.glesse
```

随后应用状态变为：

```text
stopped=true
```

应用进程未启动，开机接收器没有收到广播，前台仍为：

```text
com.rokid.os.sprite.ebglauncher/com.rokid.os.sprite.launcher.main.SpriteMainActivity
```

结论：失败点不是 Android 12 阻止后台启动 Activity，而是 Rokid 双系统逻辑在开机广播分发前将业务应用置为 stopped 状态。stopped 包不会收到隐式开机广播。

## `need_open_app` 的真实语义

Launcher 日志中可看到：

```text
SpUtil -> get key -> need_open_app, value -> com.rokid.glesse
AppAssistUtil -> getNeedOpenApp needList = [com.rokid.glesse]
```

反编译 `RokidELauncher.apk` 后确认：

- `AppSearchManager` 发现新安装应用时调用 `AppAssistUtil.saveNeedOpenApp(packageName)`。
- `need_open_app` 存储在 Launcher 私有 SharedPreferences 中。
- Launcher 读取该列表用于应用列表排序和新安装应用处理。
- 应用卸载时会调用 `deleteNeedOpenApp(packageName)`。
- `LBootCompletedReceiver` 不会根据该列表调用 `startActivity()`。

因此，`need_open_app` 不是开机自动启动白名单，不能用于前台自动打开应用。

## 可行方向

### 1. Rokid 修改 Launcher

由 `LBootCompletedReceiver` 或其后续初始化流程在系统就绪后显式启动：

```kotlin
packageManager.getLaunchIntentForPackage("com.rokid.glesse")
    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ?.let(::startActivity)
```

### 2. Rokid 配置系统白名单

调整企业系统或双系统启动策略，避免开机时将 `com.rokid.glesse` 标记为 stopped。解除该限制后，应用自身的 `BOOT_COMPLETED` 接收器方案才可能生效。

## 对接 Rokid 所需信息

- 包名：`com.rokid.glesse`
- 启动 Activity：`com.rokid.glass.AiInspectionMenuActivity`
- OTA：`1.17.e002-20260509-150201`
- 诉求：企业系统开机完成后自动前台拉起，并排除开机 force-stop

## 当前处理结论

仓库暂不保留普通 Android 开机广播实现。后续拿到 Rokid Launcher 白名单、系统配置方式或新版 OTA 后，再按系统能力实现并重做真实重启验证。
