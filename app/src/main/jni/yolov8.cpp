// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2024 THL A29 Limited, a Tencent company. All rights reserved.
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

#include "yolov8.h"

#include <android/log.h>

#include <benchmark.h>
#include <gpu.h>

#include <string>

static const char* LOG_TAG_NCNN = "HiddenRiskNcnn";

static const char* gpu_profile_label(int gpu_profile)
{
    switch (gpu_profile)
    {
    case 0:
        return "Safe FP32";
    case 1:
        return "Balanced FP16";
    case 2:
        return "No Packing FP32";
    default:
        return "Unknown";
    }
}

static void set_load_error(
    std::string* error_stage,
    int* error_code,
    std::string* error_message,
    const char* stage,
    int code,
    const std::string& message)
{
    if (error_stage)
    {
        *error_stage = stage ? stage : "";
    }
    if (error_code)
    {
        *error_code = code;
    }
    if (error_message)
    {
        *error_message = message;
    }
}

static void configure_ncnn_options(ncnn::Option& opt, bool use_gpu, int gpu_profile)
{
    opt = ncnn::Option();
    // 当前阶段优先压缩峰值显存，允许 ncnn 尽早回收中间 tensor。
    opt.lightmode = true;
    opt.num_threads = 1;
    opt.openmp_blocktime = 0;
    // 恢复默认池化分配器，减少频繁大块分配带来的峰值内存抖动。
    opt.use_local_pool_allocator = true;
    opt.use_winograd_convolution = false;
    opt.use_sgemm_convolution = false;
    opt.use_packing_layout = gpu_profile != 2;
    opt.use_int8_inference = false;
    opt.use_bf16_storage = false;
    opt.use_int8_packed = false;
    opt.use_int8_storage = false;
    opt.use_int8_arithmetic = false;
    opt.use_bf16_packed = false;
    opt.flush_denormals = 3;

#if NCNN_VULKAN
    opt.use_vulkan_compute = use_gpu;
    if (use_gpu)
    {
        opt.vulkan_device_index = 0;
        opt.use_subgroup_ops = false;
        opt.use_fp16_uniform = false;
        opt.use_shader_local_memory = false;
        opt.use_cooperative_matrix = false;
        opt.use_tensor_storage = false;

        if (gpu_profile == 1)
        {
            // 平衡模式：保留省显存的 fp16，适合验证是否属于大张量压力问题。
            opt.use_fp16_packed = true;
            opt.use_fp16_storage = true;
            opt.use_fp16_arithmetic = true;
        }
        else
        {
            // 安全模式：尽量走更保守的 FP32 路径，排除 fp16 触发的驱动问题。
            opt.use_fp16_packed = false;
            opt.use_fp16_storage = false;
            opt.use_fp16_arithmetic = false;
        }
    }
    else
    {
        opt.use_fp16_packed = false;
        opt.use_fp16_storage = false;
        opt.use_fp16_arithmetic = false;
        opt.use_fp16_uniform = false;
        opt.use_subgroup_ops = false;
        opt.use_shader_local_memory = false;
        opt.use_cooperative_matrix = false;
        opt.use_tensor_storage = false;
    }
#endif

    __android_log_print(
        ANDROID_LOG_INFO,
        LOG_TAG_NCNN,
        "configure options profile=%s useGpu=%d lightmode=%d threads=%d blocktime=%d winograd=%d sgemm=%d packing=%d bf16=%d int8=%d localPool=%d subgroup=%d fp16p=%d fp16s=%d fp16a=%d fp16u=%d shaderLocal=%d coopMat=%d tensorStorage=%d",
        gpu_profile_label(gpu_profile),
        use_gpu ? 1 : 0,
        opt.lightmode ? 1 : 0,
        opt.num_threads,
        opt.openmp_blocktime,
        opt.use_winograd_convolution ? 1 : 0,
        opt.use_sgemm_convolution ? 1 : 0,
        opt.use_packing_layout ? 1 : 0,
        opt.use_bf16_storage ? 1 : 0,
        opt.use_int8_inference ? 1 : 0,
        opt.use_local_pool_allocator ? 1 : 0,
        opt.use_subgroup_ops ? 1 : 0,
        opt.use_fp16_packed ? 1 : 0,
        opt.use_fp16_storage ? 1 : 0,
        opt.use_fp16_arithmetic ? 1 : 0,
        opt.use_fp16_uniform ? 1 : 0,
        opt.use_shader_local_memory ? 1 : 0,
        opt.use_cooperative_matrix ? 1 : 0,
        opt.use_tensor_storage ? 1 : 0);
}

YOLOv8::YOLOv8()
    : det_target_size(640)
{
}

YOLOv8::~YOLOv8()
{
}

int YOLOv8::load(
    const char* parampath,
    const char* modelpath,
    bool use_gpu,
    int gpu_profile,
    std::string* error_stage,
    int* error_code,
    std::string* error_message)
{
    const double load_start_ms = ncnn::get_current_time();
    yolov8.clear();

    configure_ncnn_options(yolov8.opt, use_gpu, gpu_profile);
    const double configure_done_ms = ncnn::get_current_time();
#if NCNN_VULKAN
    if (use_gpu)
    {
        yolov8.set_vulkan_device(yolov8.opt.vulkan_device_index);
    }
#endif
    const double device_done_ms = ncnn::get_current_time();

    const int ret_param = yolov8.load_param(parampath);
    const double param_done_ms = ncnn::get_current_time();
    const int ret_model = yolov8.load_model(modelpath);
    const double model_done_ms = ncnn::get_current_time();

    __android_log_print(
        ANDROID_LOG_INFO,
        LOG_TAG_NCNN,
        "net load(file) useGpu=%d profile=%s configureMs=%.0f setDeviceMs=%.0f loadParamMs=%.0f loadModelMs=%.0f totalMs=%.0f param=%s model=%s retParam=%d retModel=%d",
        use_gpu ? 1 : 0,
        gpu_profile_label(gpu_profile),
        configure_done_ms - load_start_ms,
        device_done_ms - configure_done_ms,
        param_done_ms - device_done_ms,
        model_done_ms - param_done_ms,
        model_done_ms - load_start_ms,
        parampath ? parampath : "N/A",
        modelpath ? modelpath : "N/A",
        ret_param,
        ret_model);

    if (ret_param != 0)
    {
        set_load_error(error_stage, error_code, error_message, "load_param", ret_param, "load_param failed rc=" + std::to_string(ret_param));
        return ret_param;
    }
    if (ret_model != 0)
    {
        set_load_error(error_stage, error_code, error_message, "load_model", ret_model, "load_model failed rc=" + std::to_string(ret_model));
        return ret_model;
    }

    set_load_error(error_stage, error_code, error_message, "", 0, "");
    return 0;
}

int YOLOv8::load(
    AAssetManager* mgr,
    const char* parampath,
    const char* modelpath,
    bool use_gpu,
    int gpu_profile,
    std::string* error_stage,
    int* error_code,
    std::string* error_message)
{
    const double load_start_ms = ncnn::get_current_time();
    yolov8.clear();

    configure_ncnn_options(yolov8.opt, use_gpu, gpu_profile);
    const double configure_done_ms = ncnn::get_current_time();
#if NCNN_VULKAN
    if (use_gpu)
    {
        yolov8.set_vulkan_device(yolov8.opt.vulkan_device_index);
    }
#endif
    const double device_done_ms = ncnn::get_current_time();

    const int ret_param = yolov8.load_param(mgr, parampath);
    const double param_done_ms = ncnn::get_current_time();
    const int ret_model = yolov8.load_model(mgr, modelpath);
    const double model_done_ms = ncnn::get_current_time();

    __android_log_print(
        ANDROID_LOG_INFO,
        LOG_TAG_NCNN,
        "net load(asset) useGpu=%d profile=%s configureMs=%.0f setDeviceMs=%.0f loadParamMs=%.0f loadModelMs=%.0f totalMs=%.0f param=%s model=%s retParam=%d retModel=%d",
        use_gpu ? 1 : 0,
        gpu_profile_label(gpu_profile),
        configure_done_ms - load_start_ms,
        device_done_ms - configure_done_ms,
        param_done_ms - device_done_ms,
        model_done_ms - param_done_ms,
        model_done_ms - load_start_ms,
        parampath ? parampath : "N/A",
        modelpath ? modelpath : "N/A",
        ret_param,
        ret_model);

    if (ret_param != 0)
    {
        set_load_error(error_stage, error_code, error_message, "load_param", ret_param, "load_param failed rc=" + std::to_string(ret_param));
        return ret_param;
    }
    if (ret_model != 0)
    {
        set_load_error(error_stage, error_code, error_message, "load_model", ret_model, "load_model failed rc=" + std::to_string(ret_model));
        return ret_model;
    }

    set_load_error(error_stage, error_code, error_message, "", 0, "");
    return 0;
}

void YOLOv8::set_det_target_size(int target_size)
{
    det_target_size = target_size;
}
