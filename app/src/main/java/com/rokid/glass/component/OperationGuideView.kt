package com.rokid.glass.component

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.rokid.glesse.R

/**
 * 操作指引组件。
 * 用于页面右上角显示操作指引，支持动态标题和多行提示内容。
 * 默认标题为"操作指引"，内容通过代码动态设置。
 */
class OperationGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val titleView: TextView
    private val contentView: TextView

    init {
        orientation = VERTICAL
        gravity = android.view.Gravity.END
        LayoutInflater.from(context).inflate(R.layout.view_operation_guide, this, true)
        titleView = findViewById(R.id.tvGuideTitle)
        contentView = findViewById(R.id.tvGuideContent)
    }

    /** 设置标题文字 */
    fun setTitle(text: String?) {
        titleView.text = text
    }

    /** 设置提示内容（支持换行符 \n） */
    fun setContent(text: String?) {
        contentView.text = text
    }

    /** 同时设置标题和内容 */
    fun setGuide(title: String? = "操作指引", content: String?) {
        setTitle(title)
        setContent(content)
    }
}
