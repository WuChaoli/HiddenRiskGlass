package com.rokid.glass.component

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.rokid.glesse.R

/**
 * 底部提示组件。
 * 用于页面底部居中显示提示信息，支持主标题 + 可选副标题。
 * 内容通过 XML 属性或代码动态设置。
 */
class BottomPromptView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val titleView: TextView
    private val subtitleView: TextView

    init {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.view_bottom_prompt, this, true)
        titleView = findViewById(R.id.tvPromptTitle)
        subtitleView = findViewById(R.id.tvPromptSubtitle)
    }

    /** 设置主标题文字 */
    fun setTitle(text: String?) {
        titleView.text = text
    }

    /** 设置副标题文字，传 null 或空字符串时隐藏 */
    fun setSubtitle(text: String?) {
        if (text.isNullOrBlank()) {
            subtitleView.isVisible = false
        } else {
            subtitleView.text = text
            subtitleView.isVisible = true
        }
    }

    /** 同时设置主标题和副标题 */
    fun setPrompt(title: String?, subtitle: String? = null) {
        setTitle(title)
        setSubtitle(subtitle)
    }
}
