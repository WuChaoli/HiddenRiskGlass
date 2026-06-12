package com.rokid.glass.hiddenrisk

import com.rokid.glass.workflow.InspectionWorkflowSession

/**
 * 检测路由上下文。
 * 根据企业扫码后的场景分类状态，决定各检测接口的调用策略（调用哪个端点或跳过）。
 *
 * 当前分类维度：placeCode 有无。
 * 后续可扩展更多维度（企业类型、风险等级等），所有业务页面无需感知路由逻辑变更。
 */
class DetectionRouteContext(
    private val autoUrl: String,
    private val generalUrl: String,
    private val deepUrl: String,
    private val gmUrl: String,
    enterpriseInfo: InspectionWorkflowSession.EnterpriseInfo?,
) {
    // ---- 场景分类状态 ----
    private val placeCode: String? = enterpriseInfo?.placeCode?.takeIf { it.isNotBlank() }
    private val hasScene: Boolean = placeCode != null

    // ---- 各接口端点（null = 跳过调用） ----

    /** 物品检测 — 无场景时跳过 */
    fun itemDetectionEndpoint(): String? = if (hasScene) autoUrl else null

    /** 环境检测 — 无场景时跳过 */
    fun sceneDetectionEndpoint(): String? = if (hasScene) generalUrl else null

    /** 深度分析 — 始终返回有效端点，无场景时降级到 gm */
    fun deepAnalysisEndpoint(): String = if (hasScene) deepUrl else gmUrl

    /** 提取 scene 参数（placeCode 本身） */
    fun sceneParam(): String? = placeCode
}
