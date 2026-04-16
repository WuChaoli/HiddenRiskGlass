package com.rokid.glass.component

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.rokid.glesse.R
import kotlin.math.roundToInt

/**
 * 页面内复用的状态提醒浮层。
 */
class StatusAlertOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val stateMachine = StatusAlertStateMachine()
    private val uiHandler = Handler(Looper.getMainLooper())

    private val cardContainer: LinearLayout
    private val iconView: ImageView
    private val titleView: TextView
    private val messageView: TextView
    private val actionView: TextView
    private val countdownBar: ProgressBar

    private val defaultCardWidthPx: Int
    private val defaultCardMinHeightPx: Int
    private val defaultCardPaddingStartPx: Int
    private val defaultCardPaddingTopPx: Int
    private val defaultCardPaddingEndPx: Int
    private val defaultCardPaddingBottomPx: Int
    private val defaultCardBackground: Drawable?
    private val defaultIconWidthPx: Int
    private val defaultIconHeightPx: Int
    private val defaultCountdownBarHeightPx: Int
    private val defaultCountdownDrawable: Drawable?

    private var countdownStartElapsedMs = 0L
    private var countdownDurationMs = 0L

    private val hideRunnable = Runnable {
        stateMachine.reset()
        stopBehavior()
        clearInternalState()
        visibility = View.GONE
    }

    private val countdownRunnable = Runnable { updateCountdownProgress() }

    private val loadingRotateAnimation: RotateAnimation by lazy {
        RotateAnimation(
            0f,
            360f,
            Animation.RELATIVE_TO_SELF,
            0.5f,
            Animation.RELATIVE_TO_SELF,
            0.5f,
        ).apply {
            duration = 900L
            repeatCount = Animation.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_status_alert_overlay, this, true)
        visibility = View.GONE
        cardContainer = findViewById(R.id.layoutStatusAlertCard)
        iconView = findViewById(R.id.ivStatusAlertIcon)
        titleView = findViewById(R.id.tvStatusAlertTitle)
        messageView = findViewById(R.id.tvStatusAlertMessage)
        actionView = findViewById(R.id.tvStatusAlertAction)
        countdownBar = findViewById(R.id.progressStatusAlertCountdown)
        countdownBar.max = COUNTDOWN_PROGRESS_MAX

        defaultCardWidthPx = cardContainer.layoutParams.width
        defaultCardMinHeightPx = cardContainer.minimumHeight
        defaultCardPaddingStartPx = cardContainer.paddingStart
        defaultCardPaddingTopPx = cardContainer.paddingTop
        defaultCardPaddingEndPx = cardContainer.paddingEnd
        defaultCardPaddingBottomPx = cardContainer.paddingBottom
        defaultCardBackground = cloneDrawable(cardContainer.background)
        defaultIconWidthPx = iconView.layoutParams.width
        defaultIconHeightPx = iconView.layoutParams.height
        defaultCountdownBarHeightPx = countdownBar.layoutParams.height
        defaultCountdownDrawable = cloneDrawable(countdownBar.progressDrawable)
    }

    fun render(model: StatusAlertModel?) {
        when (val decision = stateMachine.render(model)) {
            is StatusAlertStateMachine.RenderDecision.Show -> {
                if (decision.rebind) {
                    bind(decision.model)
                }
                visibility = View.VISIBLE
                restartBehavior(decision.model.behavior)
            }

            StatusAlertStateMachine.RenderDecision.Hide -> {
                stopBehavior()
                clearInternalState()
                visibility = View.GONE
            }

            StatusAlertStateMachine.RenderDecision.Noop -> Unit
        }
    }

    fun reset() {
        when (stateMachine.reset()) {
            StatusAlertStateMachine.RenderDecision.Hide -> {
                stopBehavior()
                clearInternalState()
                visibility = View.GONE
            }

            else -> Unit
        }
    }

    override fun onDetachedFromWindow() {
        stopBehavior()
        super.onDetachedFromWindow()
    }

    private fun bind(model: StatusAlertModel) {
        iconView.setImageResource(model.style.iconResId)
        applyIconAnimation(model.style.iconResId)
        titleView.text = model.titleText
        messageView.text = model.messageText

        actionView.text = model.action.text
        actionView.visibility = if (model.action.visible) View.VISIBLE else View.GONE

        updateSize(
            cardContainer,
            widthPx = model.style.cardWidthPx ?: defaultCardWidthPx,
            minHeightPx = model.style.cardMinHeightPx ?: defaultCardMinHeightPx,
        )
        updateSize(
            iconView,
            widthPx = model.style.iconWidthPx ?: defaultIconWidthPx,
            heightPx = model.style.iconHeightPx ?: defaultIconHeightPx,
        )

        if (model.style.cardBackgroundResId != null) {
            cardContainer.background = AppCompatResources.getDrawable(context, model.style.cardBackgroundResId)
        } else {
            cardContainer.background = cloneDrawable(defaultCardBackground)
        }
        applyContentPadding(model.style)
        applyCountdownStyle(model.style)
    }

    private fun restartBehavior(behavior: AlertBehavior) {
        stopBehavior()
        val autoDismissMs = behavior.autoDismissMs
        if (autoDismissMs == null) {
            countdownBar.visibility = View.GONE
            countdownBar.progress = 0
            return
        }

        val shouldShowCountdown = behavior.showCountdownBar
        countdownBar.visibility = if (shouldShowCountdown) View.VISIBLE else View.GONE
        countdownBar.progress = COUNTDOWN_PROGRESS_MAX
        countdownStartElapsedMs = SystemClock.elapsedRealtime()
        countdownDurationMs = autoDismissMs

        if (shouldShowCountdown) {
            uiHandler.post(countdownRunnable)
        }
        uiHandler.postDelayed(hideRunnable, autoDismissMs)
    }

    private fun stopBehavior() {
        uiHandler.removeCallbacks(hideRunnable)
        uiHandler.removeCallbacks(countdownRunnable)
        countdownDurationMs = 0L
    }

    private fun updateCountdownProgress() {
        val total = countdownDurationMs
        if (total <= 0L) {
            countdownBar.progress = 0
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - countdownStartElapsedMs
        val remainingRatio = ((total - elapsed).coerceAtLeast(0L)).toFloat() / total.toFloat()
        countdownBar.progress = (COUNTDOWN_PROGRESS_MAX * remainingRatio).roundToInt()
        if (elapsed < total && visibility == View.VISIBLE) {
            uiHandler.postDelayed(countdownRunnable, COUNTDOWN_TICK_MS)
        }
    }

    private fun clearInternalState() {
        iconView.clearAnimation()
        countdownBar.visibility = View.GONE
        countdownBar.progress = 0
    }

    private fun applyIconAnimation(iconResId: Int) {
        if (iconResId == R.mipmap.ic_loading) {
            iconView.startAnimation(loadingRotateAnimation)
        } else {
            iconView.clearAnimation()
        }
    }

    private fun applyContentPadding(style: AlertStyle) {
        val start = style.contentPaddingStartPx ?: defaultCardPaddingStartPx
        val top = style.contentPaddingTopPx ?: defaultCardPaddingTopPx
        val end = style.contentPaddingEndPx ?: defaultCardPaddingEndPx
        val bottom = style.contentPaddingBottomPx ?: defaultCardPaddingBottomPx
        cardContainer.setPaddingRelative(start, top, end, bottom)
    }

    private fun applyCountdownStyle(style: AlertStyle) {
        updateSize(countdownBar, widthPx = null, heightPx = style.countdownBarHeightPx ?: defaultCountdownBarHeightPx)
        if (style.countdownBarDrawableResId != null) {
            countdownBar.progressDrawable = AppCompatResources.getDrawable(context, style.countdownBarDrawableResId)
        } else {
            countdownBar.progressDrawable = cloneDrawable(defaultCountdownDrawable)
        }
    }

    private fun updateSize(view: View, widthPx: Int? = null, heightPx: Int? = null, minHeightPx: Int? = null) {
        val params = view.layoutParams
        if (widthPx != null) {
            params.width = widthPx
        }
        if (heightPx != null) {
            params.height = heightPx
        }
        view.layoutParams = params
        if (minHeightPx != null) {
            view.minimumHeight = minHeightPx
        }
    }

    companion object {
        private const val COUNTDOWN_PROGRESS_MAX = 1000
        private const val COUNTDOWN_TICK_MS = 33L

        private fun cloneDrawable(drawable: Drawable?): Drawable? {
            return drawable?.constantState?.newDrawable()?.mutate()
        }
    }
}
