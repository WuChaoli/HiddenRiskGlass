# BBox 与隐患详情叠层 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Deep V2 结构化结果页实现固定比例装饰 bbox、单隐患单页详情卡和卡片区域 bbox 裁剪。

**Architecture:** bbox 仍由 `DeepV2ResultOverlayView` 以 Canvas 绢制，纯 Kotlin 几何类负责固定角部尺寸和极小框缩放。隐患详情使用组合式 Android View，根据单个 hazard 自适应高度并截断；Activity 将现有文本分页改为 hazard 索引分页，并把卡片屏幕矩形同步给 bbox 叠层作裁剪。

**Tech Stack:** Kotlin、Android View/XML、Canvas/Path、JUnit4、Gradle standard variant

**Spec:** `docs/superpowers/specs/2026-08-25-bbox-hazard-overlay-design.md`

## Global Constraints

- 显示基线为 480 × 640 px、240 dpi。
- bbox 颜色为 `#00FF66`；粗边 `4dp`、细线 `1dp`、粗角边长 `18dp`、圆角半径 `10dp`。
- bbox 比例变化只改变细线长度；极小框只能整体缩放角部几何。
- 焦点 bbox 放大 `1.10x`，不得改变描边宽度和文字字重。
- 每条 hazard 固定一页；长文本截断，不增加页数。
- 详情卡绿色背景 alpha 为 0.5，并通过裁剪禁止 bbox 在卡片区域绘制。
- 不修改 Deep V2 协议、归一化、保存、上传或网络行为。

---

### Task 1: BBox 纯几何与装饰框绘制

**Files:**
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2BBoxGeometry.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2ResultOverlayView.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2BBoxGeometryTest.kt`

**Interfaces:**
- Consumes: `RectFModel` 和 density 换算后的 px 参数。
- Produces: `DeepV2BBoxGeometry.compute(rect, cornerLength, cornerRadius): DeepV2BBoxShape`，shape 包含统一缩放后的角长、半径及四条非负细线区间。

- [ ] **Step 1: 写失败的几何测试**

```kotlin
@Test fun wideAndTallBoxes_keepCornerRatio() {
    val wide = DeepV2BBoxGeometry.compute(RectFModel(0f, 0f, 200f, 80f), 27f, 15f)
    val tall = DeepV2BBoxGeometry.compute(RectFModel(0f, 0f, 80f, 200f), 27f, 15f)
    assertEquals(wide.cornerLength, tall.cornerLength, 0.001f)
    assertEquals(wide.cornerRadius / wide.cornerLength, tall.cornerRadius / tall.cornerLength, 0.001f)
}

@Test fun tinyBox_scalesCornersTogetherAndKeepsSegmentsNonNegative() {
    val shape = DeepV2BBoxGeometry.compute(RectFModel(0f, 0f, 20f, 16f), 27f, 15f)
    assertTrue(shape.cornerLength <= 8f)
    assertEquals(15f / 27f, shape.cornerRadius / shape.cornerLength, 0.001f)
    assertTrue(shape.horizontalSegmentLength >= 0f)
    assertTrue(shape.verticalSegmentLength >= 0f)
}
```

- [ ] **Step 2: 运行测试确认因类型不存在而失败**

Run: `powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.DeepV2BBoxGeometryTest"`
Expected: FAIL，`DeepV2BBoxGeometry` unresolved。

- [ ] **Step 3: 实现最小纯几何模型**

```kotlin
internal data class DeepV2BBoxShape(
    val rect: RectFModel,
    val cornerLength: Float,
    val cornerRadius: Float,
    val horizontalSegmentLength: Float,
    val verticalSegmentLength: Float,
)

internal object DeepV2BBoxGeometry {
    fun compute(rect: RectFModel, cornerLength: Float, cornerRadius: Float): DeepV2BBoxShape {
        val scale = minOf(1f, rect.width / (cornerLength * 2f), rect.height / (cornerLength * 2f))
            .coerceAtLeast(0f)
        val length = cornerLength * scale
        return DeepV2BBoxShape(
            rect = rect,
            cornerLength = length,
            cornerRadius = cornerRadius * scale,
            horizontalSegmentLength = (rect.width - length * 2f).coerceAtLeast(0f),
            verticalSegmentLength = (rect.height - length * 2f).coerceAtLeast(0f),
        )
    }
}
```

- [ ] **Step 4: 替换 Overlay 绘制并保持选中等宽**

在 `drawBox()` 中保留 `expandAroundCenter(..., 1f + 0.10f * selectedFraction)`，固定
`boxPaint.strokeWidth = 1dp` 和 `cornerPaint.strokeWidth = 4dp`。用 Path 分别绘制左上直角、
其余三个圆角、四段细线、左下三个圆点；标签绘制 `box.label` 与 `box.highestLevel`。

- [ ] **Step 5: 运行几何和现有 Overlay 测试**

Run: `powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.DeepV2BBoxGeometryTest" --tests "com.rokid.glass.hiddenrisk.DeepV2OverlayGeometryTest"`
Expected: PASS。

### Task 2: 单条隐患展示模型与一条一页导航

**Files:**
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/HazardDetailDisplayModel.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`
- Modify: `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2PresentationStateMachineTest.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/HazardDetailDisplayModelTest.kt`

**Interfaces:**
- Consumes: `DeepV2PresentationHazard`。
- Produces: `HazardDetailDisplayModel.from(hazard)`，字段为 `label`、`hazardCode`、`level`、`description`、`advice`、`lawBasis`，空值统一为 `--` 或 `暂无`。

- [ ] **Step 1: 写失败的展示模型与 hazard 页数测试**

```kotlin
@Test fun from_usesPlaceholdersForBlankFields() {
    val model = HazardDetailDisplayModel.from(hazard(label = "燃气灶", hazardCode = "", level = ""))
    assertEquals("--", model.hazardCode)
    assertEquals("--", model.level)
    assertEquals("暂无", model.description)
}

@Test fun navigation_usesOnePagePerHazard() {
    val machine = DeepV2PresentationStateMachine(intArrayOf(2))
    assertEquals(DeepV2NavigationState.Focused(0, 0), machine.forward().state)
    assertEquals(DeepV2NavigationState.Focused(0, 1), machine.forward().state)
    assertEquals(DeepV2NavigationState.Defocused, machine.forward().state)
}
```

- [ ] **Step 2: 运行测试确认新模型失败**

Run: `powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.HazardDetailDisplayModelTest" --tests "com.rokid.glass.hiddenrisk.DeepV2PresentationStateMachineTest"`
Expected: 新模型 unresolved；现有状态机测试保持可诊断。

- [ ] **Step 3: 实现展示模型并替换文本分页数据**

将 Activity 的 `deepV2Pages: List<List<CharSequence>>` 改为
`deepV2Pages: List<List<DeepV2PresentationHazard>>`，赋值为
`deepV2DisplayTargets.map { it.hazards }`；删除 `paginateDeepV2Text()` 调用。状态机构造继续使用
`deepV2Pages.map(List<*>::size)`。

- [ ] **Step 4: 运行测试确认通过**

Run: 同 Step 2。
Expected: PASS。

### Task 3: 自适应 Hazard 详情组件与 XML 接入

**Files:**
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/HazardDetailOverlayView.kt`
- Modify: `app/src/main/res/layout/activity_ai_inspection.xml`
- Modify: `app/src/main/res/values/dimens.xml`
- Create: `app/src/main/res/drawable/bg_hazard_detail_overlay.xml`
- Create: `app/src/main/res/drawable/bg_hazard_detail_content.xml`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`

**Interfaces:**
- Consumes: `render(model: HazardDetailDisplayModel, pageIndex: Int, pageCount: Int)`。
- Produces: `setOnCardBoundsChangedListener((RectF?) -> Unit)`，返回相对 Activity 内容根容器的实际卡片矩形；`clear()` 隐藏内容并上报 null。

- [ ] **Step 1: 创建组合 View 的固定结构**

使用 Kotlin 构建内部纵向容器：固定标题、label、横向编号/等级、等宽双栏描述/建议、法律依据、
右下页码。外层背景为 `#8000FF66` 和 1dp `#DB00FF66` 圆角描边；中间框同为 1dp 描边。

- [ ] **Step 2: 实现动态高度和截断**

宽度固定为父布局减 `28dp × 2`，高度 `wrap_content`，最大底边不得越过状态栏上沿。正文使用
`maxLines` 与 `ellipsize=end`；测量超限时依次减少法律依据、建议、描述的可用行数，固定标题区
不删除。

- [ ] **Step 3: 替换旧 XML 卡片并接入 Activity**

删除 `layoutDeepV2HazardCard`、`tvDeepV2HazardTitle`、`tvDeepV2HazardText`、
`tvDeepV2PageIndicator`，新增 `HazardDetailOverlayView`。`showDeepV2Page()` 获取
`hazards[pageIndex]` 并调用 `render()`；pageCount 为 1 时组件隐藏页码。

- [ ] **Step 4: 编译资源与 Kotlin**

Run: `powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:compileStandardDebugKotlin`
Expected: BUILD SUCCESSFUL。

### Task 4: 卡片区域裁剪、回归测试与构建

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2ResultOverlayView.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`
- Modify/Delete: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2MeasuredPagePlanner.kt`（仅在无其他调用方时）
- Modify/Delete: corresponding measured-page tests（仅在生产符号删除时）

**Interfaces:**
- Consumes: `DeepV2ResultOverlayView.setExcludedCardRect(rect: RectF?)`。
- Produces: bbox Canvas 在 `clipOutRoundRect(rect, cardRadius, cardRadius)` 后绘制；null 恢复全区域。

- [ ] **Step 1: 接入卡片边界监听和裁剪**

Activity 将卡片 bounds 转换到 overlay 局部坐标并调用 `setExcludedCardRect()`。Overlay 在
`onDraw()` 中 save、clipOutRoundRect、画全部 box、restore；卡片淡出结束或页面清理时传 null。

- [ ] **Step 2: 搜索并清理旧分页代码**

Run: `rg -n "paginateDeepV2Text|DeepV2MeasuredPagePlanner|deepV2Pages" app/src/main app/src/test`
Expected: 不再有 Activity 文本测量分页调用；若 planner 无调用方则删除生产文件和旧测试。

- [ ] **Step 3: 运行目标单元测试**

Run: `powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.DeepV2*" --tests "com.rokid.glass.hiddenrisk.HazardDetailDisplayModelTest"`
Expected: PASS。

- [ ] **Step 4: 运行 standard 全量单元测试**

Run: `powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 构建 debug APK**

Run: `powershell -ExecutionPolicy Bypass -File scripts/android/build-debug.ps1`
Expected: BUILD SUCCESSFUL，生成 `app-standard-debug.apk`。

- [ ] **Step 6: 检查变更边界**

Run: `git diff --check`；`git status --short`。
Expected: 无空白错误；不包含用户已有的 `docs/.gitignore` 修改和浏览器截图。
