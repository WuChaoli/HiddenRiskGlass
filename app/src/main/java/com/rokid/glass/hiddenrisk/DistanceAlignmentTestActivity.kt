package com.rokid.glass.hiddenrisk

import android.os.Bundle

/** 不启用识别链路，仅用于记录不同距离下的画面 XY 对齐参数。 */
class DistanceAlignmentTestActivity : RawCameraPreviewDebugActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        intent.putExtra("mode", "distance_alignment")
        super.onCreate(savedInstanceState)
    }
}
