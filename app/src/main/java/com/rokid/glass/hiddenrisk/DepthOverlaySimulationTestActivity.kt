package com.rokid.glass.hiddenrisk

import android.os.Bundle

/** 固定请求画面，以手动距离模拟云端深度并只补偿检测框水平中心。 */
class DepthOverlaySimulationTestActivity : RawCameraPreviewDebugActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        intent.putExtra("mode", "depth_overlay_simulation")
        super.onCreate(savedInstanceState)
    }
}
