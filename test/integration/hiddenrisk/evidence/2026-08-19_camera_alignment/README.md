# 2026-08-19 摄像头距离对齐测试证据

本目录保存 Rokid Glass 真机上的摄像头画面、距离标定和在线检测框对齐测试截图，以及反距离拟合过程导出的 CSV。

## 最终验证基线

```text
offsetX(distance) = 108.00 - 115.94 / distance
scale = 0.79049903
offsetY = -234 px
alpha = 0
distance step = 0.5 m
```

适用条件与完整推导见 `docs/Lessons/camera_display_alignment_calibration.md`。

## 文件分组

- `detection-distance-*.png`：打框测试页在不同距离参数下的显示与按键验证。
- `distance-alignment-*.png`：按距离独立调整 X/Y 的人工校准过程。
- `inverse-distance-*.png`：B/K 倒数关系拟合与默认值验证过程。
- `inverse-distance-records/`：拟合页面保存的参数截图与 CSV。
- `alignment-debug/`：早期摄像头画面对齐预览证据。

这些文件属于集成测试证据，不作为 Android APK 的资源参与打包。
