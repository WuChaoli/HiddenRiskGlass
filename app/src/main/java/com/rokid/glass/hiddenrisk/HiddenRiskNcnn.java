// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2021 THL A29 Limited, a Tencent company. All rights reserved.
//
// Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
// in compliance with the License. You may obtain a copy of the License at
//
// https://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.rokid.glass.hiddenrisk;

import android.graphics.Bitmap;
import android.content.res.AssetManager;
import android.hardware.HardwareBuffer;

public class HiddenRiskNcnn
{
    public static final int BACKEND_UNKNOWN = -1;
    public static final int BACKEND_CPU = 0;
    public static final int BACKEND_GPU = 1;
    public static final int BACKEND_TURNIP = 2;
    public static final int GPU_PROFILE_SAFE_FP32 = 0;
    public static final int GPU_PROFILE_BALANCED_FP16 = 1;
    public static final int GPU_PROFILE_NO_PACKING_FP32 = 2;

    public native boolean loadModel(AssetManager mgr, int backend, int gpuProfile, int targetSize);
    public native boolean submitBitmap(Bitmap bitmap);
    public native boolean submitHardwareBuffer(HardwareBuffer hardwareBuffer, int width, int height, int rotationDegrees);
    public native void clearFrameState();
    public native void setDebugResultLimit(int maxResults);
    public native void setDebugCompareEnabled(boolean enabled);
    public native NativeInferenceStats getLatestInferenceStats();

    public static String backendLabel(int backendId)
    {
        switch (backendId)
        {
            case BACKEND_CPU:
                return "CPU";
            case BACKEND_GPU:
                return "System Vulkan";
            case BACKEND_TURNIP:
                return "Turnip";
            default:
                return "Unknown";
        }
    }

    public static String gpuProfileLabel(int profileId)
    {
        switch (profileId)
        {
            case GPU_PROFILE_SAFE_FP32:
                return "Safe FP32";
            case GPU_PROFILE_BALANCED_FP16:
                return "Balanced FP16";
            case GPU_PROFILE_NO_PACKING_FP32:
                return "No Packing FP32";
            default:
                return "Unknown";
        }
    }

    static {
        System.loadLibrary("hiddenriskncnn");
    }
}
