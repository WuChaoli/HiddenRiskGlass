# HiddenRisk UI 使用指南

## 概述

本文档说明如何在 HiddenRiskProbeActivity 和 HiddenRiskDetailActivity 中使用新的UI布局。

## 布局文件

### 1. activity_hidden_risk_probe.xml

支持以下状态：
- **LOADING** - 系统初始化中
- **ERROR** - 加载异常
- **CAPTURING** - 自动取景识别中
- **SAFE** - 此区域无隐患
- **ALERT** - 发现安全隐患

### 2. activity_hidden_risk_detail.xml

支持以下状态：
- **ANALYZING** - 显示隐患分析内容
- **SYNC_CONFIRM** - 询问是否同步到手机
- **SYNCED** - 隐患已同步

## Drawable 资源

已创建的图标资源：
- `ic_check_circle_green.xml` - 绿色对勾圆圈
- `ic_warning_triangle_green.xml` - 绿色警告三角形
- `ic_error_circle_green.xml` - 绿色错误圆圈
- `ic_mic_green.xml` - 绿色麦克风图标
- `bg_green_border.xml` - 绿色边框背景
- `progress_horizontal_green.xml` - 绿色水平进度条
- `anim_loading_green.xml` - 绿色加载动画
- `ic_progress_circle_green.xml` - 绿色圆形进度条

## 使用示例代码

### HiddenRiskProbeActivity 状态切换

```kotlin
class HiddenRiskProbeActivity : AppCompatActivity() {

    // View 引用
    private lateinit var progressLoading: ProgressBar
    private lateinit var iconStatus: ImageView
    private lateinit var textMainTitle: TextView
    private lateinit var textSubTitle: TextView
    private lateinit var layoutLoadingProgress: LinearLayout
    private lateinit var progressHorizontal: ProgressBar
    private lateinit var textProgressPercent: TextView
    private lateinit var textCapturingHint1: TextView
    private lateinit var textCapturingHint2: TextView
    private lateinit var textHintMain: TextView
    private lateinit var textHintSub: TextView
    private lateinit var layoutTopBar: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hidden_risk_probe)
        initViews()
    }

    private fun initViews() {
        progressLoading = findViewById(R.id.progressLoading)
        iconStatus = findViewById(R.id.iconStatus)
        textMainTitle = findViewById(R.id.textMainTitle)
        textSubTitle = findViewById(R.id.textSubTitle)
        layoutLoadingProgress = findViewById(R.id.layoutLoadingProgress)
        progressHorizontal = findViewById(R.id.progressHorizontal)
        textProgressPercent = findViewById(R.id.textProgressPercent)
        textCapturingHint1 = findViewById(R.id.textCapturingHint1)
        textCapturingHint2 = findViewById(R.id.textCapturingHint2)
        textHintMain = findViewById(R.id.textHintMain)
        textHintSub = findViewById(R.id.textHintSub)
        layoutTopBar = findViewById(R.id.layoutTopBar)
    }

    /**
     * 显示系统初始化状态 (对应 02-加载界面.png)
     */
    fun showLoadingState(progress: Int = 0) {
        // 隐藏顶部状态栏
        layoutTopBar.visibility = View.GONE
        
        // 显示加载相关视图
        progressLoading.visibility = View.VISIBLE
        layoutLoadingProgress.visibility = View.VISIBLE
        textMainTitle.visibility = View.VISIBLE
        textSubTitle.visibility = View.VISIBLE
        
        // 隐藏状态图标
        iconStatus.visibility = View.GONE
        textCapturingHint1.visibility = View.GONE
        textCapturingHint2.visibility = View.GONE
        
        // 设置文字
        textMainTitle.text = "系统初始化"
        textSubTitle.text = "正在准备检测设备..."
        
        // 更新进度
        progressHorizontal.progress = progress
        textProgressPercent.text = "${progress}%"
        
        // 底部提示
        textHintMain.visibility = View.GONE
        textHintSub.visibility = View.VISIBLE
        textHintSub.text = "双击"右触控板"返回主页"
    }

    /**
     * 显示加载异常状态 (对应 02-1-加载异常界面.png)
     */
    fun showErrorState() {
        // 隐藏顶部状态栏
        layoutTopBar.visibility = View.GONE
        
        // 隐藏加载视图
        progressLoading.visibility = View.GONE
        layoutLoadingProgress.visibility = View.GONE
        
        // 显示错误状态
        iconStatus.setImageResource(R.drawable.ic_error_circle_green)
        iconStatus.visibility = View.VISIBLE
        textMainTitle.visibility = View.VISIBLE
        textSubTitle.visibility = View.GONE
        
        // 隐藏取景提示
        textCapturingHint1.visibility = View.GONE
        textCapturingHint2.visibility = View.GONE
        
        // 设置文字
        textMainTitle.text = "加载异常，请重试"
        
        // 底部提示
        textHintMain.visibility = View.VISIBLE
        textHintMain.text = "说出"重试"或"退出""
        textHintSub.visibility = View.VISIBLE
        textHintSub.text = "双击"右触控板"返回主页"
    }

    /**
     * 显示取景识别状态 (对应 07-1-发现隐患界面.png 的取景中状态)
     */
    fun showCapturingState() {
        // 显示顶部状态栏
        layoutTopBar.visibility = View.VISIBLE
        
        // 隐藏加载和状态视图
        progressLoading.visibility = View.GONE
        iconStatus.visibility = View.GONE
        layoutLoadingProgress.visibility = View.GONE
        textMainTitle.visibility = View.GONE
        textSubTitle.visibility = View.GONE
        
        // 显示取景提示
        textCapturingHint1.visibility = View.VISIBLE
        textCapturingHint2.visibility = View.VISIBLE
        
        // 底部提示
        textHintMain.visibility = View.GONE
        textHintSub.visibility = View.VISIBLE
        textHintSub.text = "自动取景识别中\n请保持眼镜稳定，不要遮挡镜头"
    }

    /**
     * 显示无隐患状态 (对应 07-无隐患界面.png)
     */
    fun showSafeState() {
        // 显示顶部状态栏
        layoutTopBar.visibility = View.VISIBLE
        
        // 隐藏加载视图
        progressLoading.visibility = View.GONE
        layoutLoadingProgress.visibility = View.GONE
        textCapturingHint1.visibility = View.GONE
        textCapturingHint2.visibility = View.GONE
        
        // 显示安全状态
        iconStatus.setImageResource(R.drawable.ic_check_circle_green)
        iconStatus.visibility = View.VISIBLE
        textMainTitle.visibility = View.VISIBLE
        textSubTitle.visibility = View.VISIBLE
        
        // 设置文字
        textMainTitle.text = "此区域无隐患"
        textSubTitle.text = "是否继续巡检其他区域？"
        
        // 底部提示
        textHintMain.visibility = View.VISIBLE
        textHintMain.text = "说出"继续"或"结束"或双击退出"
        textHintSub.visibility = View.GONE
    }

    /**
     * 显示发现隐患状态 (对应 07-1-发现隐患界面.png)
     */
    fun showAlertState() {
        // 显示顶部状态栏
        layoutTopBar.visibility = View.VISIBLE
        
        // 隐藏加载视图
        progressLoading.visibility = View.GONE
        layoutLoadingProgress.visibility = View.GONE
        textCapturingHint1.visibility = View.GONE
        textCapturingHint2.visibility = View.GONE
        
        // 显示警告状态
        iconStatus.setImageResource(R.drawable.ic_warning_triangle_green)
        iconStatus.visibility = View.VISIBLE
        textMainTitle.visibility = View.VISIBLE
        textSubTitle.visibility = View.GONE
        
        // 设置文字
        textMainTitle.text = "发现安全隐患"
        
        // 底部提示
        textHintMain.visibility = View.GONE
        textHintSub.visibility = View.VISIBLE
        textHintSub.text = "自动取景识别中\n请保持眼镜稳定，不要遮挡镜头"
    }
}
```

### HiddenRiskDetailActivity 状态切换

```kotlin
class HiddenRiskDetailActivity : AppCompatActivity() {

    private lateinit var containerAnalysisResult: FrameLayout
    private lateinit var textAnalysisResult: TextView
    private lateinit var iconSyncSuccess: ImageView
    private lateinit var textSyncTitle: TextView
    private lateinit var textSyncDetail: TextView
    private lateinit var textConfirmQuestion: TextView
    private lateinit var textContinueQuestion: TextView
    private lateinit var layoutAnalyzingHints: LinearLayout
    private lateinit var textHintSyncConfirm: TextView
    private lateinit var textHintGeneral: TextView
    private lateinit var textHintBack: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hidden_risk_detail)
        initViews()
    }

    private fun initViews() {
        containerAnalysisResult = findViewById(R.id.containerAnalysisResult)
        textAnalysisResult = findViewById(R.id.textAnalysisResult)
        iconSyncSuccess = findViewById(R.id.iconSyncSuccess)
        textSyncTitle = findViewById(R.id.textSyncTitle)
        textSyncDetail = findViewById(R.id.textSyncDetail)
        textConfirmQuestion = findViewById(R.id.textConfirmQuestion)
        textContinueQuestion = findViewById(R.id.textContinueQuestion)
        layoutAnalyzingHints = findViewById(R.id.layoutAnalyzingHints)
        textHintSyncConfirm = findViewById(R.id.textHintSyncConfirm)
        textHintGeneral = findViewById(R.id.textHintGeneral)
        textHintBack = findViewById(R.id.textHintBack)
    }

    /**
     * 显示分析结果 (对应 07-2-显示隐患界面.png)
     */
    fun showAnalysisResult(content: String) {
        // 显示分析内容容器
        containerAnalysisResult.visibility = View.VISIBLE
        textAnalysisResult.text = content
        
        // 显示识别中提示
        layoutAnalyzingHints.visibility = View.VISIBLE
        
        // 显示同步确认问题
        textConfirmQuestion.visibility = View.VISIBLE
        
        // 隐藏同步成功相关视图
        iconSyncSuccess.visibility = View.GONE
        textSyncTitle.visibility = View.GONE
        textSyncDetail.visibility = View.GONE
        textContinueQuestion.visibility = View.GONE
        
        // 底部提示
        textHintSyncConfirm.visibility = View.VISIBLE
        textHintGeneral.visibility = View.GONE
        textHintBack.visibility = View.GONE
    }

    /**
     * 显示同步成功状态 (对应 07-3-隐患同步界面.png)
     */
    fun showSyncedState() {
        // 隐藏分析相关视图
        containerAnalysisResult.visibility = View.GONE
        layoutAnalyzingHints.visibility = View.GONE
        textConfirmQuestion.visibility = View.GONE
        textHintSyncConfirm.visibility = View.GONE
        
        // 显示同步成功图标和文字
        iconSyncSuccess.visibility = View.VISIBLE
        textSyncTitle.visibility = View.VISIBLE
        textSyncDetail.visibility = View.VISIBLE
        textContinueQuestion.visibility = View.VISIBLE
        
        textSyncTitle.text = "隐患已同步"
        textSyncDetail.text = "前往手机查看详情"
        textContinueQuestion.text = "是否继续巡检其他区域？"
        
        // 底部提示
        textHintGeneral.visibility = View.VISIBLE
        textHintGeneral.text = "说出"继续"或"结束"或双击退出"
        textHintBack.visibility = View.GONE
    }
}
```

## 设计规范

### 颜色方案
- 背景色: `#000000` (纯黑)
- 主绿色: `#00FF00` (亮绿)
- 副绿色: `#AAFFAA` (浅绿)
- 淡绿色: `#88FF88` (更浅绿)

### 字体大小
- 主标题: 24sp, Bold
- 副标题: 18sp
- 内容文字: 16sp
- 提示文字: 14sp
- 小提示: 12sp

### 图标尺寸
- 状态图标: 100dp x 100dp
- 加载动画: 72dp x 72dp
- 成功图标(详情页): 80dp x 80dp
- 麦克风图标: 24dp x 24dp

## 注意事项

1. 所有文字使用中文
2. 布局针对 480x640 分辨率优化
3. 支持语音和触控两种交互方式
4. 状态切换时确保隐藏不需要的视图，避免重叠
