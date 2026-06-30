package com.rokid.glass.component

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.rokid.glesse.R

/**
 * 右上角功能菜单组件。
 * 标题固定展示菜单类型，内容支持多行文本，方便不同页面复用同一布局。
 */
class FunctionMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val titleView: TextView
    private val contentView: TextView

    init {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.view_function_menu, this, true)
        titleView = findViewById(R.id.tvFunctionMenuTitle)
        contentView = findViewById(R.id.tvFunctionMenuContent)
    }

    /** 设置菜单标题。 */
    fun setTitle(text: String?) {
        titleView.text = text ?: context.getString(R.string.inspection_function_menu_title)
    }

    /** 设置菜单内容，支持使用 \n 分隔多行任务。 */
    fun setMenuContent(text: String?) {
        contentView.text = text ?: context.getString(R.string.inspection_function_menu_content)
    }

    /** 同时设置标题和菜单内容。 */
    fun setMenu(title: String? = null, content: String? = null) {
        setTitle(title)
        setMenuContent(content)
    }
}
