package com.rokid.glass.input

/**
 * 语音指令项接口。
 * 实现此接口的类（如菜单卡片）可被自动发现并注册为离线语音指令，
 * 无需手动在 buildInputActions() 中逐个注册。
 *
 * 使用方式：
 * 1. 数据类实现此接口，提供 labelResId 和 pinyinResId
 * 2. Activity 中通过 buildCardVoiceActions() 批量生成 InputActionSpec
 * 3. Activity 切换时由 UnifiedInputSession 自动管理生命周期
 */
interface VoiceActionItem {
    /** 语音指令文字的资源 ID（同时用作卡片的显示标签） */
    val labelResId: Int
    /** 拼音的资源 ID，命名约定: <label_resource_name>_pinyin */
    val pinyinResId: Int
    /** 拼音别名资源 ID 列表，用于注册额外的语音触发器（如前后鼻音变体） */
    val pinyinAliases: List<Int> get() = emptyList()
    /** 语音/点击触发后执行的动作 */
    fun execute()
}
