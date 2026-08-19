# 智能眼镜单目空间定位技术博客 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建一篇不披露项目具体标定参数、能够完整解释智能眼镜单目空间定位与现实框叠加工程路径的中文技术博客。

**Architecture:** 文章采用“体验目标—几何原理—简化验证—深度升级—工程边界”的递进结构。事实依据来自两份方案文档、实测 Lessons 和现有隔离测试页；正文中的图示只表达通用坐标关系和数据流。

**Tech Stack:** Markdown、LaTeX、Mermaid；仓库内 Android/Kotlin 实现作为工程事实来源。

**Spec:** `docs/superpowers/specs/2026-08-19-monocular-spatial-localization-blog-design.md`

## Global Constraints

- 正文使用简体中文，公式变量和代码标识符使用 English。
- 不写入本项目的具体分辨率、视场角、缩放、偏移、距离拟合系数和接口地址。
- 明确区分理论完整方案、已经完成的测试验证和未来待接入能力。
- 未经外部原始资料核验，不引用模型的具体延迟、精度和参数量。
- 不把单目估深描述为精确三维定位，也不把二维框对齐描述为六自由度 AR 锚定。

---

### Task 1: 建立博客正文与论证主线

**Files:**
- Create: `docs/blog/monocular-spatial-localization-on-smart-glasses.md`
- Read: `docs/superpowers/specs/2026-08-19-monocular-spatial-localization-blog-design.md`
- Read: `docs/assets/摄像头画面与眼镜画面对齐/基于单目画面测距技术调研报告.md`
- Read: `docs/assets/摄像头画面与眼镜画面对齐/摄像头画面与眼镜画面对齐.md`

**Interfaces:**
- Consumes: 设计文档中的核心论点、叙事结构和写作约束。
- Produces: 包含完整章节标题、导语、结语和段落论点的博客正文。

- [ ] **Step 1: 创建正文文件和章节标题**

  按设计文档的 13 段叙事结构建立 Markdown 标题，标题面向读者表达问题，不使用内部测试模式名作为主标题。

- [ ] **Step 2: 写导语和问题定义**

  用“眼镜只显示透明框，但框看起来贴在现实物体上”的体验切入，随后区分二维定位、相机三维定位和显示定位。

- [ ] **Step 3: 写几何原理部分**

  依次解释：

  ```text
  camera pixel -> camera ray -> depth -> camera 3D point
  -> Camera-to-Eye transform -> eye ray -> display pixel
  ```

  保留以下通用公式：

  ```text
  r_c = K_c^-1 p_c
  P_c = Z r_c
  P_e = R_ec P_c + t_ec
  ```

- [ ] **Step 4: 写简化验证和完整方案的关系**

  说明中心裁剪、等比缩放和二维平移是验证工具，不是完整三维模型；解释按分辨率直接缩放为何忽略视场角。

- [ ] **Step 5: 完成文章结语**

  用“固定标定、距离模型、目标级深度、完整投影”四阶段总结工程演进路线。

- [ ] **Step 6: 检查正文结构**

  Run:

  ```powershell
  rg -n '^#{1,3} ' docs/blog/monocular-spatial-localization-on-smart-glasses.md
  ```

  Expected: 标题顺序与设计文档叙事一致，不存在空章节。

### Task 2: 写入测试页的工程验证过程

**Files:**
- Modify: `docs/blog/monocular-spatial-localization-on-smart-glasses.md`
- Read: `docs/Lessons/camera_display_alignment_calibration.md`
- Read: `app/src/main/java/com/rokid/glass/hiddenrisk/RawCameraPreviewDebugActivity.kt`
- Read: `app/src/main/java/com/rokid/glass/hiddenrisk/AlignmentCalibrationState.kt`
- Read: `app/src/main/java/com/rokid/glass/hiddenrisk/DepthOverlaySimulationTestActivity.kt`

**Interfaces:**
- Consumes: 已实现的半透明预览、多距离标定、反距离拟合、在线 Overlay 和模拟深度行为。
- Produces: 一段不暴露参数、但能复现实验思路的工程实践章节。

- [ ] **Step 1: 写半透明预览标定过程**

  说明先显示半透明 Camera Surface，以现实轮廓作为参照，验证方向、纵横比例、缩放和固定偏移。

- [ ] **Step 2: 写多距离实验与模型拟合过程**

  说明保持固定缩放与垂直项，在多个已知距离记录水平偏移，并用以下结构拟合：

  ```text
  offsetX(Z) = C + K / Z
  ```

  符号方向按坐标定义确定，正文不写实际系数。

- [ ] **Step 3: 写在线框渲染链路**

  说明从已经标定的 Surface 截帧，将检测结果按同源画面映射回 Overlay；Surface 可保持工作但视觉透明，仅显示框和 Label。

- [ ] **Step 4: 写模拟深度验证链路**

  说明请求画面固定在参考标定，手动距离只驱动框的相对水平补偿，以隔离验证深度与视差的关系；明确这是云端深度字段上线前的替代实验。

- [ ] **Step 5: 核对实现表述**

  Run:

  ```powershell
  rg -n 'PixelCopy|depth_overlay_simulation|deltaX|alpha = 0f|DISTANCE_STEP' app/src/main/java/com/rokid/glass/hiddenrisk
  ```

  Expected: 正文对截帧、透明 Surface、模拟深度和 Overlay 补偿的描述能在实现中找到对应依据。

### Task 3: 添加通用示意图

**Files:**
- Modify: `docs/blog/monocular-spatial-localization-on-smart-glasses.md`

**Interfaces:**
- Consumes: Task 1 的几何链路和 Task 2 的实验链路。
- Produces: 不少于四张不含真实参数的 Mermaid 或静态配图，与正文术语一致。

- [ ] **Step 1: 添加完整坐标变换图**

  图中必须包含 `Camera Pixel`、`Camera Ray`、`Depth`、`3D Point`、`Camera-to-Eye`、`Eye Ray` 和 `Display Pixel`。

- [ ] **Step 2: 添加视场裁剪图**

  表达显示视场通常只对应摄像头大视场中的中心区域，避免表达成整幅图像按分辨率压缩。

- [ ] **Step 3: 添加视差俯视图**

  表达 Camera 与 Eye 的非零基线，以及近处目标比远处目标产生更大视差。

- [ ] **Step 4: 添加目标级深度数据流图**

  表达同一帧中的目标检测和深度估计如何汇合，并为每个目标分别生成 Overlay。

- [ ] **Step 5: 检查图示完整性**

  Run:

  ```powershell
  $mermaidCount = (Select-String -Path docs/blog/monocular-spatial-localization-on-smart-glasses.md -Pattern '^```mermaid$').Count
  $imageCount = (Select-String -Path docs/blog/monocular-spatial-localization-on-smart-glasses.md -Pattern '^!\[').Count
  if (($mermaidCount + $imageCount) -lt 4) { throw "Expected at least 4 diagrams, got $($mermaidCount + $imageCount)" }
  ```

  Expected: 命令正常退出并确认共有不少于四张图示。

### Task 4: 完成技术准确性与脱敏复核

**Files:**
- Modify: `docs/blog/monocular-spatial-localization-on-smart-glasses.md`
- Read: `docs/superpowers/specs/2026-08-19-monocular-spatial-localization-blog-design.md`

**Interfaces:**
- Consumes: 完整博客草稿和设计约束。
- Produces: 可发布、无内部参数泄露、理论与实测边界清晰的最终文档。

- [ ] **Step 1: 检查内部参数和接口信息**

  Run:

  ```powershell
  rg -n '3024|4032|480|640|0\.790|115\.94|183\.147|10010|task_001|XFAQ' docs/blog/monocular-spatial-localization-on-smart-glasses.md
  ```

  Expected: 无匹配结果。

- [ ] **Step 2: 检查未经核验的性能宣传**

  Run:

  ```powershell
  rg -n '厘米级|分米级|ms|FPS|参数量|准确率|精度达到' docs/blog/monocular-spatial-localization-on-smart-glasses.md
  ```

  Expected: 不存在具体性能承诺；如“厘米级”仅出现在能力边界的否定表述中，应人工确认语义。

- [ ] **Step 3: 人工核对事实层级**

  逐节标记并复核三类表述：理论推导、测试页已验证、未来方案。确保模拟距离没有被描述成真实深度返回，中心平移验证没有被描述成完整框角点投影。

- [ ] **Step 4: 检查 Markdown 与工作树差异**

  Run:

  ```powershell
  git diff --check
  git diff -- docs/blog/monocular-spatial-localization-on-smart-glasses.md docs/superpowers/specs/2026-08-19-monocular-spatial-localization-blog-design.md docs/superpowers/plans/2026-08-19-monocular-spatial-localization-blog.md
  ```

  Expected: `git diff --check` 无输出；差异仅包含设计、计划和博客文档。

- [ ] **Step 5: 提交前等待用户确认**

  将博客文件路径、章节摘要和脱敏检查结果交给用户审阅。只有用户明确要求保存 Git 进度后，才执行提交。
