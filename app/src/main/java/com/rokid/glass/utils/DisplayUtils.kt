package com.rokid.glass.utils

import android.content.Context
import android.util.TypedValue

/** dp 转 px（基于 context.resources.displayMetrics） */
fun Context.dpToPx(value: Float): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics
    )
}

/** dp 转 px（基于 View.resources） */
fun android.view.View.dpToPx(value: Float): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics
    )
}
