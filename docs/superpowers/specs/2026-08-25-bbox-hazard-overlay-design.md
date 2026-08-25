# BBox 与隐患详情叠层设计

## 目标

统一 Deep V2 结构化结果页面的检测框和隐患详情视觉样式，使其适配 Rokid Glass 的
480 × 640 px、240 dpi 显示基线，同时保留现有目标选择、语音/按键导航、保存和上传链路。

本次只调整结构化结果展示，不修改推理协议、bbox 坐标映射、隐患归一化、保存协议或
上传逻辑。

视觉参考为 `docs/mockups/hazard-bbox-overlay.html`。

## 已确认的交互规则

1. 一个检测目标可以关联多条隐患。
2. 每条隐患固定占一页，页码在同一目标的隐患列表内切换。
3. 页面不按文本长度拆分。内容优先通过增加详情卡高度容纳；超过安全区最大高度时截断，
   不生成额外页面。
4. 只有总页数大于 1 时显示页码，格式为 `当前页 / 总页数`。
5. 焦点 bbox 围绕中心放大 10%，描边、圆角、装饰点和字体不加粗。
6. 无实体 bbox 的 `others` 全局隐患只显示详情卡，不选中检测框。

## 组件设计

### `DeepV2ResultOverlayView`

保留现有自定义 `View + Canvas` 方案和 `DeepV2OverlayBox` 输入模型，替换 bbox 绘制逻辑。

每个 bbox 包含：

- 左上角为直角粗边；
- 右上、右下、左下为圆角粗边；
- 四条边中段为细线；
- 左侧靠近左下角纵向排列三个实心圆点；
- 左上角内部显示两行标签：第一行 `label`，第二行最高隐患等级。

粗角长度、圆角半径和二者比例使用固定 dp 值。bbox 宽高变化时，只改变中段细线长度。
当 bbox 小于两个角部所需的最小尺寸时，按统一缩放系数缩小整套角部几何，避免角部重叠，
不分别拉伸某一条边。

建议初始视觉参数：

| 参数 | 值 |
| --- | --- |
| 颜色 | `#00FF66` |
| 粗边宽度 | `4dp` |
| 细线宽度 | `1dp` |
| 粗角边长 | `18dp` |
| 圆角半径 | `10dp` |
| 装饰点直径 | `2dp` |
| 装饰点间距 | `2dp` |
| 焦点放大 | `1.10x` |
| 动画时长 | 沿用 `220ms` |

标签背景使用深绿色半透明底，避免复杂相机画面影响可读性。标签随 bbox 几何一起移动和放大，
但文字字号、字重和框线宽度保持不变。

### `HazardDetailOverlayView`

新增组合式 Android View 组件，使用标准 TextView 和布局容器承载文字，不使用 Canvas 手工
排版中文。组件接收单个 `DeepV2PresentationHazard`、当前页和总页数，内部负责测量、动态
高度、文本截断和页码显隐。

组件包含：

1. 外层居中圆角细线框，绿色边框；
2. 透明度 0.5 的绿色背景；
3. 顶部标题区：
   - 固定文字“隐患详情”；
   - 大号 `label`；
   - 同一行显示“隐患编号 + hazardCode”和“隐患等级 + level”；
4. 中部圆角细线框，左右两栏等宽：
   - 左栏第一行为“隐患描述”，第二部分为 `description`；
   - 右栏第一行为“整改建议”，第二部分为 `advice`；
5. 底部显示“法律依据”和 `lawBasis`；
6. 右下角按需显示页码。

详情卡宽度使用屏幕宽度减固定左右安全边距；高度从内容自然高度开始增长，并限制在状态栏
以上的内容安全区。达到最大高度后，各正文 TextView 使用末尾省略号截断。

截断优先级从低到高为：法律依据正文、整改建议正文、隐患描述正文。固定标题、label、隐患
编号、隐患等级和各分区标题不得截断；超长 label 和隐患编号只允许单行末尾省略。

## 绘制层级与遮挡

页面层级从下到上为：

1. 冻结的检测帧；
2. bbox 叠层；
3. 隐患详情卡；
4. 状态栏和保存确认框。

详情卡背景保持 0.5 透明度时，同色 bbox 仍可能穿透，因此不能只依赖 View 的 Z 轴覆盖。
`HazardDetailOverlayView` 完成布局后，将其屏幕区域同步给 `DeepV2ResultOverlayView`。
bbox 绘制时通过 `Canvas.clipOutRoundRect()` 排除详情卡外轮廓区域，再绘制所有 bbox 和标签。
详情卡隐藏时清除排除区域并恢复完整 bbox。

排除区域应使用详情卡的实际布局矩形，不硬编码 HTML 样例坐标。详情卡淡入、淡出或高度变化
时同步更新矩形，避免动画过程中短暂穿透。

## 数据流与分页

现有 `DeepV2PresentationStateMachine` 继续管理目标索引和页索引，但 page 的含义从“测量后的
文本片段”改为“目标下的单条 hazard”。

数据流：

```text
DeepV2Presentation
  -> DeepV2DisplayTarget(hazards)
  -> pageCounts = 每个目标的 hazards.size
  -> Focused(targetIndex, pageIndex)
  -> hazards[pageIndex]
  -> HazardDetailOverlayView.render(hazard, pageIndex, pageCount)
```

删除 `paginateDeepV2Text()` 和 `DeepV2MeasuredPagePlanner` 在此页面中的调用。若这些符号无其他
调用方，则一并删除其生产代码和仅覆盖旧文本分页行为的测试。

前进操作依次浏览当前目标的每条隐患，然后进入下一个目标；后退操作执行相反顺序。浏览完
最后一条隐患后回到无焦点状态，沿用现有保存确认交互。

## 空值与异常显示

- `hazardCode` 为空：显示“隐患编号 --”。
- `level` 为空：显示“隐患等级 --”。
- `description`、`advice` 或 `lawBasis` 为空：对应正文显示“暂无”。
- 单页：隐藏页码 View，不保留空白占位。
- bbox 无效或映射后无面积：不绘制框，但关联 hazard 仍可作为详情页浏览。

## Android 文件范围

预计修改：

- `DeepV2ResultOverlayView.kt`：新 bbox 形状、标签、焦点等宽放大及详情卡裁剪；
- `AiInspectionActivity.kt`：按 hazard 建页、提交单页数据、同步详情卡裁剪区域；
- `activity_ai_inspection.xml`：用新详情组件替换旧 hazard card；
- `dimens.xml`、颜色和 drawable 资源：集中保存视觉参数；
- `DeepV2PresentationStateMachineTest.kt`：验证一条 hazard 一页的导航；
- 新增 bbox 几何、详情卡展示模型和截断策略测试。

预计新增：

- `HazardDetailOverlayView.kt`；
- `DeepV2BBoxGeometry.kt`：计算固定比例角部、细线和装饰点几何；
- `HazardDetailDisplayModel.kt`：将单条 hazard 转为包含空值占位的页面展示模型；
- 对应单元测试。

不修改：

- `DeepV2Protocol.kt`；
- `DeepV2ResultNormalizer.kt` 的协议归一化规则；
- 推理、上传、保存和网络模块。

## 验证标准

### 自动化测试

- 不同比例 bbox 的粗角边长、圆角半径比例保持一致，仅细线长度变化；
- 极小 bbox 不出现负长度、角部交叉或崩溃；
- 焦点与非焦点使用相同描边宽度；
- 每条 hazard 只产生一个页面，长文本不增加页数；
- 单页隐藏页码，多页显示正确索引；
- 空字段使用约定占位文本；
- `others` 页面不选择 bbox；
- 详情卡矩形存在时，bbox 绘制区域正确排除。

### 构建与视觉验证

- 执行 `:app:testStandardDebugUnitTest`；
- 执行 `scripts/android/build-debug.ps1`；
- 在 480 × 640、240 dpi 的模拟或真机页面检查横竖比例不同的 bbox；
- 通过 ADB 真机验证焦点动画、前后翻页、单页页码隐藏、长文截断和状态栏避让；
- 对比 HTML 视觉样例，确认颜色、层级、双栏结构和 bbox 标签一致。

转换工程或桌面 HTML 渲染通过不等于 Android 真机完成；真机验证结果必须单独报告。
