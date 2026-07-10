# 菜单卡片焦点动画系统设计

**日期**: 2026-06-11  
**状态**: 待实施  
**参考**: 官方 Rokid Glass3 Demo (`glass3sdkdemo/glassdemo/HomeAdapter.kt`)

## 背景

当前菜单页（`MainMenuActivity`、`AiInspectionMenuActivity`）的卡片选中态仅通过瞬间替换背景 drawable 实现，无过渡动画。左右滑动同时触发焦点移动和 RecyclerView 滚动，行为不够清晰。

目标：引入焦点悬停/消失/点击动画，左右滑动改为纯焦点移动，对齐官方 Demo 体验。

## 动画状态机

每张卡片有三个状态：

```
普通态 ──(焦点悬停 300ms)──▶ 聚焦态 ──(点击 150ms+100ms)──▶ 按下态
   ◀──(焦点消失 300ms)──        ◀──(回弹完成 100ms)──
```

### 普通态
- `translationY: 0`
- `scale: 1.0`
- 背景: `glass_menu_card`（边框 1.5dp, 40% 绿）
- 指示条: `GONE`

### 聚焦态
- `translationY: -12dp`
- `scale: 1.0`
- 背景: `glass_menu_card_selected`（边框 2dp, 100% 绿）
- 指示条: `VISIBLE`, alpha 动画从 0→1

### 按下态（点击瞬间）
- `scale: 0.95`（150ms 缩小）
- 回弹 `scale: 1.0`（100ms, OvershootInterpolator）
- 回弹完成后执行跳转

## 动画参数

| 过渡 | 属性 | 时长 | 插值器 |
|------|------|------|--------|
| 普通→聚焦 (焦点悬停) | `translationY: 0 → -12dp` | 300ms | DecelerateInterpolator |
| 普通→聚焦 | 背景 drawable 切换 | 瞬间 | — |
| 普通→聚焦 | 指示条 alpha: 0→1 | 200ms | LinearInterpolator |
| 聚焦→普通 (焦点消失) | `translationY: -12dp → 0` | 300ms | AccelerateInterpolator |
| 聚焦→普通 | 背景 drawable 切换 | 瞬间 | — |
| 聚焦→普通 | 指示条 alpha: 1→0 | 200ms | LinearInterpolator |
| 聚焦→按下 (点击反馈) | `scaleX/Y: 1.0 → 0.95` | 150ms | DecelerateInterpolator |
| 按下→完成 (回弹) | `scaleX/Y: 0.95 → 1.0` | 100ms | OvershootInterpolator |
| 翻页过渡 | RecyclerView smoothScroll | 250ms | DecelerateInterpolator |

## 交互逻辑

### 焦点移动（左滑/右滑）
- 前滑/后滑只改变 `selectedIndex`，不触发 RecyclerView 滚动
- 当前页内移动: selectedIndex ± 1
- 到达当前页边界时自动翻页（如有下一页/上一页）
- 翻页后焦点重置到新页首项（前进）或末项（后退）

### 确认（单击）
1. Adapter 执行点击动画（scale 0.95 → 1.0）
2. 动画完成后回调 Activity 执行业务跳转

### 长按（预留）
- Adapter 暴露 `onLongPress: ((Int) -> Unit)?` 回调
- 动画预留：呼吸灯脉冲（alpha 循环），本次不实现动画，仅保留接口

## 多卡片分页

当前主菜单和二级菜单各 3 张卡片，刚好一屏（80dp×3 + 边距 ≈ 264dp < 320dp），无需分页。分页逻辑为未来扩展预留：

- `MenuCardAdapter` 增加 `pageSize: Int` 参数（默认 3）
- `selectedIndex` 按页内位置映射
- 翻页通过 `smoothScrollToPosition` 触发横向滑动
- 翻页动画：250ms DecelerateInterpolator

## 改动文件

| 文件 | 改动说明 |
|------|---------|
| `MenuCardAdapter.kt` | 核心：onBindViewHolder 中执行平移动画 + 缩放动画；`animateClick(position, onComplete)` 方法；`onLongPress` 回调预留 |
| `item_menu_card.xml` | 卡片底部增加选中指示条 `ImageView`（`item_select_bar`，默认 GONE） |
| `MainMenuActivity.kt` | 移除 `ensureSelectedCardVisible()`；确认时调用 `adapter.animateClick()` 等待动画完成后跳转；预留 `adapter.onLongPress` |
| `AiInspectionMenuActivity.kt` | 同上 |
| `drawable/item_select_bar.xml` | 新建：绿色圆角指示条 shape（40dp×3dp, 圆角 16dp, 颜色 #CC40FF5E） |

## 不需要改动的部分

- `UnifiedInputSession` — 输入映射不变，仍然输出 Previous/Next/Confirm 动作
- `glass_menu_card.xml` / `glass_menu_card_selected.xml` — 现有背景 drawable 不变
- 语音输入 — 仍然直接触发 `onItemConfirmed(index)`，跳过焦点移动

## 边界情况

- 快速连续滑动：`animate().cancel()` 取消旧动画，避免动画堆积
- 唯一卡片：选中态不变（始终聚焦），点击动画正常执行
- RecyclerView 回收：ViewHolder 复用时通过 `onBindViewHolder` 重新设置正确状态

## 不在范围内

- 卡片加载动画（首次进入菜单时的入场动画）
- 卡片 icon 本身的动画（如旋转箭头）
- 长按动画的具体实现（仅预留接口）
