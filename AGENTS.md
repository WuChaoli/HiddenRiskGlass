# 项目特定配置

本文档仅记录 `glassdemo` 项目的项目级约定与已验证经验，避免和全局 `AGENTS.md` 混淆。

## HiddenRisk NCNN 经验

详细验证方法、准确率基线、历史根因与探针页排障经验，统一收敛到：

- `docs/HiddenRisk_验证与排障.md`

### 当前已验证可运行的 GPU 组合

- HiddenRisk 当前在眼镜端已验证可运行的组合为：
  - 推理尺寸：`640`
  - 后端：`System Vulkan`
  - GPU Profile：`Balanced FP16`
  - `ncnn::Option.lightmode = true`
  - `ncnn::Option.use_local_pool_allocator = true`
- 该组合下，`detect ex.extract` 已可稳定完成，不再像 `960 + No Packing FP32` 那样在 extract 阶段被 `lmkd` 杀进程。

### 当前仓库中的模型资产状态

- `app/src/main/assets/hiddenrisk.ncnn.param` 与 `app/src/main/assets/hiddenrisk.ncnn.bin` 已由同一次正式重导成对替换。
- 当前正式小模型源目标为 `models/source/hidden_risk_mini_0330.onnx`。
- 若运行时资产不是由该源同次重导得到，需先重导 `param + bin` 再做验证，避免“源模型与资产不一致”。
- `models/generated/hiddenrisk_640` 这套由 `hidden_risk_mini_0330.onnx` 重导出的 ONNX/NCNN 产物已在 CPU 端按 JNI 一致的 `letterbox + pad114 + /255 -> out0_raw -> decoded postprocess` 流程验证与 ONNX 输出对齐；出现语义漂移时请优先排查运行时 profile，而不是重提转换链漂移。
- 旧的 `models/scripts/compare_onnx_ncnn.py` 仅对图像执行 `Resize(640,640)`，没有复用 JNI 中的 `letterbox+pad114` 流程，因此不应作为 HiddenRisk 语义结论的直接依据。
- 原生侧统一读取 `out0_raw`，C++ 后处理兼容：
  - raw proposal `64 + 26`
  - decoded proposal `4 + 26`
- 当前 mini 模型的 ONNX / NCNN 检测头为单输出 `1x30x8400`，落在 decoded 分支。

### 正式重导约束

- `models/scripts/export_hiddenrisk_640.sh` 当前支持 `.pt` 与 `.onnx` 两类正式输入源。
- `models/source/best.pt` 保留为完整训练资产源。
- `models/source/hidden_risk_mini_0330.onnx` 是当前目标小模型部署源。
- 正式发布前，必须通过仓库内脚本执行完整链路：
  - `best.pt -> static torchscript(imgsz=640) -> pnnx(fp16=1) -> ncnn`
  - `hidden_risk_mini_0330.onnx -> pnnx(fp16=1) -> ncnn`
- 正式资产必须使用同一次重导生成的 `param + bin` 成对替换当前 patch 资产。
- 正式运行时统一使用 `out0_raw` 作为最终 blob 名称。
- `models/source/model_20251218.onnx` 仅保留为历史参考，不再作为正式导出输入。

### 参数图 patch 方案的使用边界

- 该方案适合：
  - 当前环境缺少 `torch / ultralytics / onnx / onnx2ncnn / ncnnoptimize` 工具链时，先快速验证眼镜端 GPU 是否能跑通。
  - 目标是先降低显存峰值，优先验证 `extract` 是否可稳定执行。
- 该方案不适合：
  - 需要长期维护、可重复构建、可审计的模型发布流程。
  - 需要严格保证图结构、decode 逻辑、输出语义完全来自同一份源模型导出结果。

### 后续更推荐的正式方案

- 正式发布前，优先采用“从源模型重导”的完整链路：
  - `best.pt -> torchscript(imgsz=640) -> pnnx(fp16=1) -> ncnn`
- 若已经拿到定型的小模型 ONNX，可直接走：
  - `hidden_risk_mini_0330.onnx -> pnnx(fp16=1) -> ncnn`
- 重导后应使用新生成的 `param + bin` 成对替换当前资产，不要长期维持“新 param + 旧 bin + 绕过 decode tail”的混合状态。
- 正式重导与校验入口固定为：
  - `models/scripts/export_hiddenrisk_640.sh`
  - `models/scripts/validate_hiddenrisk_assets.sh`

## 探针页经验

- 当前 `HiddenRiskProbeActivity` 只是探针页，不适合展示全量检测结果。
- 当检测结果接近数千条时，如果每轮都把全量 detections 从 JNI 搬到 Java，再渲染到 `TextView`，会显著增加：
  - JNI 对象分配压力
  - Java 堆压力
  - UI 布局和主线程卡顿
- 当前项目已限制调试页最多显示前 `20` 条 detection，同时保留总 `detectionCount`。
- 如果后续仍需要长时间循环压测，优先减少 UI 刷新频率或完全关闭调试页明细渲染。

## 调试与验证建议

- 关注以下日志是否出现：
  - `detect preprocess target=640`
  - `detect padded ... anchors=8400`
  - `detect ex.extract done blob=out0_raw`
- 若再次出现 GPU 稳定性问题，先按以下顺序排查：
  1. 确认 `TARGET_INPUT_SIZE` 是否仍为 `640`
  2. 确认 `GPU_PROFILE` 是否仍为 `Balanced FP16`
  3. 确认 `lightmode/local_pool_allocator` 没被改回诊断配置
  4. 区分是 ncnn 推理失败，还是探针页/UI 自身导致进程退出
