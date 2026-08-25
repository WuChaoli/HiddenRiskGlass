package com.rokid.glass

import com.rokid.glass.config.BusinessMockConfig
import com.rokid.glass.workflow.InspectionWorkflowSession

internal object BusinessMockEnterpriseInfoFactory {
    fun create(config: BusinessMockConfig): InspectionWorkflowSession.EnterpriseInfo? {
        val placeCode = config.placeCode.trim()
        if (!config.enabled || placeCode.isEmpty()) return null
        return InspectionWorkflowSession.EnterpriseInfo(
            companyName = "联调企业",
            siteName = "联调场所",
            inspectorName = "联调人员",
            qrContent = "business_mock",
            placeCode = placeCode,
        )
    }
}
