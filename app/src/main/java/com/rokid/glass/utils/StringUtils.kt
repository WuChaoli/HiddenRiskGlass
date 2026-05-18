package com.rokid.glass.utils

/** 返回第一个非空白字符串（trim 后），全空白返回 null */
fun firstNonBlank(vararg values: String?): String? {
    return values.firstOrNull { !it.isNullOrBlank() }?.trim()
}
