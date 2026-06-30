package com.rokid.glass.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.rokid.glass.utils.dpToPx
import com.rokid.glesse.R
import com.rokid.glass.input.VoiceActionItem

class MenuCardAdapter(
    val cards: List<MenuCardData>,
) : RecyclerView.Adapter<MenuCardAdapter.ViewHolder>() {

    data class MenuCardData(
        val iconResId: Int,
        override val labelResId: Int,
        override val pinyinResId: Int = 0,
        override val pinyinAliases: List<Int> = emptyList(), // 拼音别名（如前后鼻音变体），自动注册为额外语音触发器
        val iconChar: String? = null,
        val onClick: (() -> Unit)? = null,
    ) : VoiceActionItem {
        override fun execute() { onClick?.invoke() }
    }

    /** 当前选中位置，-1 表示无选中 */
    var selectedIndex: Int = -1
        set(value) {
            val old = field
            field = value
            if (old != value) {
                lastSelectedIndex = old
                if (old in 0 until itemCount) notifyItemChanged(old)
                if (value in 0 until itemCount) notifyItemChanged(value)
            }
        }

    /** 上一个选中位置，用于判断动画方向 */
    private var lastSelectedIndex = -1

    /** 长按回调（预留） */
    var onLongPress: ((Int) -> Unit)? = null

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: FrameLayout = itemView.findViewById(R.id.itemCard)
        val icon: ImageView = itemView.findViewById(R.id.ivCardIcon)
        val iconText: TextView = itemView.findViewById(R.id.tvCardIconText)
        val label: TextView = itemView.findViewById(R.id.tvCardLabel)
        val selectBar: ImageView = itemView.findViewById(R.id.itemSelectBar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_menu_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val card = cards[position]
        if (card.iconChar != null) {
            holder.iconText.visibility = View.VISIBLE
            holder.iconText.text = card.iconChar
            holder.icon.visibility = View.GONE
        } else {
            holder.iconText.visibility = View.GONE
            holder.icon.setImageResource(card.iconResId)
            holder.icon.visibility = View.VISIBLE
        }
        holder.label.setText(card.labelResId)

        val isSelected = position == selectedIndex
        val wasSelected = position == lastSelectedIndex

        // 取消旧动画，避免快速滑动时动画堆积
        holder.card.animate().cancel()
        holder.selectBar.animate().cancel()

        // 仅在焦点状态变化时播放平移动画，其余情况直接设置
        if (wasSelected != isSelected) {
            val targetY = if (isSelected) -holder.card.dpToPx(12f) else 0f
            holder.card.animate()
                .translationY(targetY)
                .setDuration(300)
                .setInterpolator(if (isSelected) DecelerateInterpolator() else AccelerateInterpolator())
                .start()
        } else {
            holder.card.translationY = if (isSelected) -holder.card.dpToPx(12f) else 0f
        }

        // 背景 drawable 瞬间切换
        holder.card.setBackgroundResource(
            if (isSelected) R.drawable.glass_menu_card_selected
            else R.drawable.glass_menu_card
        )

        // 文字粗细切换
        holder.label.setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

        // 选中指示条显现/消失
        if (isSelected) {
            holder.selectBar.visibility = View.VISIBLE
            holder.selectBar.alpha = 0f
            holder.selectBar.animate()
                .alpha(1f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            holder.selectBar.animate()
                .alpha(0f)
                .setDuration(200)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { holder.selectBar.visibility = View.GONE }
                .start()
        }
    }

    /**
     * 执行点击按压回弹动画
     * @param viewHolder 当前选中的 ViewHolder
     * @param onComplete 回弹完成后回调（执行业务跳转）
     */
    fun animateClick(viewHolder: ViewHolder, onComplete: () -> Unit) {
        viewHolder.card.animate().cancel()
        viewHolder.card.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(150)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                viewHolder.card.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .setInterpolator(OvershootInterpolator())
                    .withEndAction(onComplete)
                    .start()
            }
            .start()
    }

    override fun getItemCount(): Int = cards.size
}
