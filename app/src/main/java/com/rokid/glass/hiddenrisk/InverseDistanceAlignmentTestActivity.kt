package com.rokid.glass.hiddenrisk

import android.os.Bundle

/** 使用 X = B - K / distance 实时计算水平偏移的半透明对齐测试页。 */
class InverseDistanceAlignmentTestActivity : RawCameraPreviewDebugActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        intent.putExtra("mode", "inverse_distance_alignment")
        super.onCreate(savedInstanceState)
    }
}
