# 模型导出流水线

<!-- Generated: 2026-03-30 | Files scanned: 25 | Token estimate: ~800 -->

## 导出流程

```
best.pt (PyTorch)
      │
      ▼
┌─────────────────────────────────────┐
│ Ultralytics YOLO.export()           │
│ format="onnx", imgsz=640, batch=1   │
└─────────────┬───────────────────────┘
              │
      ┌───────┴───────┐
      ▼               ▼
static ONNX    TorchScript
(1x3x640x640)   (中间产物)
      │
      ▼
┌─────────────────────────────────────┐
│ pnnx                                │
│ inputshape=[1,3,640,640]            │
│ fp16=1, optlevel=2                  │
└─────────────┬───────────────────────┘
              │
      ┌───────┴───────┐
      ▼               ▼
pnnx.ncnn.param   pnnx.ncnn.bin
      │
      ▼
┌─────────────────────────────────────┐
│ Python 后处理                        │
│ 重命名输出 blob 为 out0_raw         │
└─────────────┬───────────────────────┘
              │
      ┌───────┴───────┐
      ▼               ▼
hiddenrisk.ncnn.param   hiddenrisk.ncnn.bin
(最终资产)              (最终资产)
      │
      ▼
app/src/main/assets/
```

## 目录结构

```
models/
├── source/                 # 原始训练模型
│   ├── best.pt            # PyTorch 完整资产 (~38.8MB)
│   ├── best.onnx          # ONNX 中间产物 (~76.8MB)
│   └── hidden_risk_mini_0330.onnx   # 当前目标小模型源
│
├── scripts/               # 导出脚本
│   ├── export_hiddenrisk_640.sh      # 主导出脚本
│   ├── validate_hiddenrisk_assets.sh # 资产校验
│   ├── setup_export_env.sh           # 环境初始化
│   └── setup_ncnn_tools.sh           # NCNN 工具下载
│
├── generated/             # 导出产物
│   └── hiddenrisk_640/
│       ├── hiddenrisk_640.param      # NCNN 模型结构
│       ├── hiddenrisk_640.bin        # NCNN 权重
│       └── hiddenrisk_640_ncnn.py    # Python 推理脚本
│
└── pyproject.toml         # Python 依赖
```

## 脚本说明

| 脚本 | 用途 |
|------|------|
| `export_hiddenrisk_640.sh` | 完整导出流程：pt/onnx → pnnx → ncnn → 校验 → 覆盖 assets |
| `validate_hiddenrisk_assets.sh` | 校验 ONNX shape、NCNN blob 名、8400 anchors、排除 18900/Shape layer |
| `setup_export_env.sh` | 创建 venv，安装 torch==2.6.0+cpu、onnx、ultralytics |
| `setup_ncnn_tools.sh` | 下载 NCNN 预编译工具 (onnx2ncnn, ncnnoptimize) |

## 依赖

```toml
[project]
name = "hiddenrisk-model-export"
requires-python = ">=3.12,<3.13"
dependencies = [
    "onnx>=1.17,<2",
    "torch>=2.6,<3",
    "ultralytics>=8.3,<9",
]
```

## 关键约束

1. **输入尺寸**: 640x640 (static)
2. **输出 blob 名**: 必须标准化为 `out0_raw`
3. **Anchors**: 必须验证 8400 个 (排除 18900)
4. **体积门禁**: 新生成 bin 必须小于当前 assets 才允许覆盖
5. **精度**: fp16 (通过 pnnx fp16=1)
6. **一致性**: 必须先基于 `models/source/hidden_risk_mini_0330.onnx` 重导，再执行校验与推理验证
- **验证依据**: `models/generated/hiddenrisk_640` 这套资产在 CPU 端、按 JNI 一致的 `letterbox + pad114 + /255 -> out0_raw -> decoded postprocess` 流程下已与 ONNX 输出对齐；如果仍观察到检测漂移，应优先审查运行时 profile（Vulkan、packing、fp16 等）而非质疑导出流程。
  - 旧版 `models/scripts/compare_onnx_ncnn.py` 只做 `Resize(640,640)`，没有复现 HiddenRisk 的 letterbox/Pad 路径，因此不应该作为最终语义一致性结论。
- 详细准确率基线、样图结论、项目 runtime 对比方法、以及探针页相机生命周期经验，统一见 `docs/HiddenRisk_验证与排障.md`。

## NCNN 推理链路排查

- 先前在探针页/UI 中稳定观察到的 “Top5 detections” 是项目端多层裁剪（`yolov8_det.cpp` TopK 后处理、`yolov8ncnn.cpp` 回传限制、Java 的 multi overlay 过滤）引入的结果，不代表模型本体在 `out0_raw` 上的实际输出。
- 当前运行时已支持 `hiddenrisk.max_results_override=0`，可以关闭上述裁剪层后查看 `out0_raw` 的全部 detections，这样的配置在样图模式下（参数 `--es hiddenrisk.sample_image_path /sdcard/Download/stress_test.jpg`）得到的 `bitmap stats snapshot` log 中多轮都是 `detections=405`、`preLimitDetections=405`，说明项目链路本身在不过滤时稳定输出 405 个目标。
- 在同一张 `stress_test.jpg`、同一套 assets、同一份 C++ 后处理下，冷启动 `CPU` backend 后多轮稳定得到 `detections=403`，而 `System Vulkan / Balanced FP16` 多轮稳定得到 `detections=405`。这说明当前真正需要排查的不是 UI 裁剪，而是运行时 backend/profile 带来的数值漂移。
- 当前本地 Python NCNN 复刻脚本还没有和设备 CPU 结果对齐，不能把它的输出当作最终基线；继续对比前，必须先把独立 baseline 严格对齐到 JNI 的 `from_pixels_resize/copy_make_border + /255 + decoded head + class-wise NMS` 语义。
- 当前仓库的正式对比入口是 `models/scripts/compare_hiddenrisk_pipeline.py`。项目内链路需要配合 `hiddenrisk.debug_compare=true` 与 `hiddenrisk.max_results_override=0`，这样日志里会额外给出 `before_nms/after_nms` 统计，便于定位漂移首次出现的环节。

> 日志参考：`.reports/2026-03-31_project_vs_ncnn/project_sample_unlimited_balanced.log`
