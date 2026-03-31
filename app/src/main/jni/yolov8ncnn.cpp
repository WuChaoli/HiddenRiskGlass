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

#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>

#include <jni.h>

#include <gpu.h>

#include <cstdio>
#include <dlfcn.h>
#include <string>
#include <vector>

#include <benchmark.h>
#include <platform.h>

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#include "yolov8.h"

static const char* LOG_TAG_NCNN = "HiddenRiskNcnn";
static const char* LOG_TAG_PROBE = "HiddenRiskProbe";

static YOLOv8* g_yolov8 = 0;
static ncnn::Mutex lock;
static std::vector<Object> g_latest_objects;
static int g_latest_image_width = 0;
static int g_latest_image_height = 0;
static int g_latest_backend_id = -1;
static std::string g_latest_backend_name;
static std::string g_latest_device_name;
static jlong g_latest_inference_time_ms = 0;
static std::string g_latest_error_stage;
static jint g_latest_error_code = 0;
static std::string g_latest_error_message;
static jint g_latest_prelimit_detection_count = 0;
static jint g_loaded_backend_id = -1;
static jint g_loaded_gpu_profile = -1;
static jint g_loaded_target_size = 0;
static const size_t k_max_java_detection_count = 32;

static const char* backend_label(int backend_id)
{
    switch (backend_id)
    {
    case 0:
        return "CPU";
    case 1:
        return "System Vulkan";
    case 2:
        return "Turnip";
    default:
        return "Unknown";
    }
}

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

static const char* driver_source_for_backend(int backend_id)
{
    switch (backend_id)
    {
    case 0:
        return "cpu";
    case 1:
        return "system";
    case 2:
        return "turnip";
    default:
        return "unknown";
    }
}

static std::string string_or_na(const char* value)
{
    if (!value || value[0] == '\0')
    {
        return "N/A";
    }

    return value;
}

static bool can_open_driver_library(const char* driver_path)
{
    if (!driver_path || driver_path[0] == '\0')
    {
        return true;
    }

    void* handle = dlopen(driver_path, RTLD_NOW);
    if (!handle)
    {
        const char* dlerror_message = dlerror();
        __android_log_print(
            ANDROID_LOG_ERROR,
            LOG_TAG_PROBE,
            "driver library open failed path=%s error=%s",
            driver_path,
            dlerror_message ? dlerror_message : "unknown");
        return false;
    }

    dlclose(handle);
    return true;
}

static void set_latest_backend_info_locked(int backend_id, const std::string& backend_name, const std::string& device_name)
{
    g_latest_backend_id = backend_id;
    g_latest_backend_name = backend_name;
    g_latest_device_name = device_name;
}

static void set_latest_error_locked(const std::string& stage, jint code, const std::string& message)
{
    g_latest_error_stage = stage;
    g_latest_error_code = code;
    g_latest_error_message = message;
}

static void clear_latest_frame_state_locked()
{
    g_latest_objects.clear();
    g_latest_image_width = 0;
    g_latest_image_height = 0;
    g_latest_inference_time_ms = 0;
    g_latest_prelimit_detection_count = 0;
    set_latest_error_locked("", 0, "");
}

static void clear_latest_frame_state()
{
    ncnn::MutexLockGuard g(lock);
    clear_latest_frame_state_locked();
}

static void set_latest_error(
    const std::string& stage,
    jint code,
    const std::string& message)
{
    ncnn::MutexLockGuard g(lock);
    set_latest_error_locked(stage, code, message);
}

static void set_latest_success_frame_state(
    int image_width,
    int image_height,
    const std::vector<Object>& objects,
    jint prelimit_detection_count,
    jlong inference_time_ms)
{
    ncnn::MutexLockGuard g(lock);
    g_latest_image_width = image_width;
    g_latest_image_height = image_height;
    g_latest_objects = objects;
    g_latest_prelimit_detection_count = prelimit_detection_count;
    g_latest_inference_time_ms = inference_time_ms;
    set_latest_error_locked("", 0, "");
}

static void set_latest_failed_frame_state(
    int image_width,
    int image_height,
    jlong inference_time_ms,
    const std::string& stage,
    jint code,
    const std::string& message)
{
    ncnn::MutexLockGuard g(lock);
    g_latest_image_width = image_width;
    g_latest_image_height = image_height;
    g_latest_objects.clear();
    g_latest_prelimit_detection_count = 0;
    g_latest_inference_time_ms = inference_time_ms;
    set_latest_error_locked(stage, code, message);
}

static jobjectArray create_detection_array(
    JNIEnv* env,
    const std::vector<Object>& objects,
    const std::vector<std::string>& labels)
{
    jclass detection_cls = env->FindClass("com/rokid/glass/hiddenrisk/DetectionResult");
    if (!detection_cls)
        return 0;

    jmethodID ctor = env->GetMethodID(detection_cls, "<init>", "(Ljava/lang/String;FFFFFI)V");
    if (!ctor)
        return 0;

    // Java 侧只需要绘制有限个高优先级框，避免把几百个 proposal 全量搬上去。
    const size_t detection_count = std::min(objects.size(), k_max_java_detection_count);
    jobjectArray result = env->NewObjectArray((jsize)detection_count, detection_cls, 0);
    if (!result)
        return 0;

    for (size_t i = 0; i < detection_count; i++)
    {
        const Object& obj = objects[i];
        jstring label = env->NewStringUTF(labels[i].c_str());
        jobject item = env->NewObject(
            detection_cls,
            ctor,
            label,
            obj.rect.x,
            obj.rect.y,
            obj.rect.width,
            obj.rect.height,
            obj.prob,
            obj.label);
        env->SetObjectArrayElement(result, (jsize)i, item);
        env->DeleteLocalRef(item);
        env->DeleteLocalRef(label);
    }

    return result;
}

static jobject create_inference_stats(JNIEnv* env)
{
    std::vector<Object> objects;
    std::vector<std::string> labels;
    int image_width = 0;
    int image_height = 0;
    int backend_id = -1;
    int gpu_profile_id = -1;
    int target_size = 0;
    std::string backend_name;
    std::string gpu_profile_name;
    std::string device_name;
    jlong inference_time_ms = 0;
    std::string error_stage;
    jint error_code = 0;
    std::string error_message;
    jint prelimit_detection_count = 0;
    {
        ncnn::MutexLockGuard g(lock);
        objects = g_latest_objects;
        image_width = g_latest_image_width;
        image_height = g_latest_image_height;
        backend_id = g_latest_backend_id;
        gpu_profile_id = g_loaded_gpu_profile;
        target_size = g_loaded_target_size;
        backend_name = g_latest_backend_name;
        gpu_profile_name = gpu_profile_label(g_loaded_gpu_profile);
        device_name = g_latest_device_name;
        inference_time_ms = g_latest_inference_time_ms;
        error_stage = g_latest_error_stage;
        error_code = g_latest_error_code;
        error_message = g_latest_error_message;
        prelimit_detection_count = g_latest_prelimit_detection_count;
        labels.reserve(objects.size());
        for (size_t i = 0; i < objects.size(); i++)
        {
            const char* label_name = g_yolov8 ? g_yolov8->label_name(objects[i].label) : "unknown";
            labels.push_back(label_name ? label_name : "unknown");
        }
    }

    jclass stats_cls = env->FindClass("com/rokid/glass/hiddenrisk/NativeInferenceStats");
    if (!stats_cls)
        return 0;

    jmethodID ctor = env->GetMethodID(
        stats_cls,
        "<init>",
        "(ILjava/lang/String;ILjava/lang/String;ILjava/lang/String;IIJLjava/lang/String;ILjava/lang/String;II[Lcom/rokid/glass/hiddenrisk/DetectionResult;)V");
    if (!ctor)
        return 0;

    jobjectArray detections = create_detection_array(env, objects, labels);
    if (!detections)
        return 0;

    jstring backend = backend_name.empty() ? 0 : env->NewStringUTF(backend_name.c_str());
    jstring profile = gpu_profile_name.empty() ? 0 : env->NewStringUTF(gpu_profile_name.c_str());
    jstring device = device_name.empty() ? 0 : env->NewStringUTF(device_name.c_str());
    jstring stage = error_stage.empty() ? 0 : env->NewStringUTF(error_stage.c_str());
    jstring error = error_message.empty() ? 0 : env->NewStringUTF(error_message.c_str());
    jobject result = env->NewObject(
        stats_cls,
        ctor,
        backend_id,
        backend,
        gpu_profile_id,
        profile,
        target_size,
        device,
        image_width,
        image_height,
        inference_time_ms,
        stage,
        error_code,
        error,
        prelimit_detection_count,
        (jint)objects.size(),
        detections);

    if (backend)
    {
        env->DeleteLocalRef(backend);
    }
    if (profile)
    {
        env->DeleteLocalRef(profile);
    }
    if (device)
    {
        env->DeleteLocalRef(device);
    }
    if (stage)
    {
        env->DeleteLocalRef(stage);
    }
    if (error)
    {
        env->DeleteLocalRef(error);
    }
    env->DeleteLocalRef(detections);
    return result;
}

static bool convert_bitmap_to_rgb(
    JNIEnv* env,
    jobject bitmap,
    cv::Mat& rgb,
    std::string* error_message)
{
    if (!bitmap)
    {
        if (error_message)
        {
            *error_message = "bitmap is null";
        }
        return false;
    }

    AndroidBitmapInfo info;
    const int get_info_rc = AndroidBitmap_getInfo(env, bitmap, &info);
    if (get_info_rc != ANDROID_BITMAP_RESULT_SUCCESS)
    {
        if (error_message)
        {
            *error_message = "AndroidBitmap_getInfo failed rc=" + std::to_string(get_info_rc);
        }
        return false;
    }

    void* pixels = 0;
    const int lock_pixels_rc = AndroidBitmap_lockPixels(env, bitmap, &pixels);
    if (lock_pixels_rc != ANDROID_BITMAP_RESULT_SUCCESS || !pixels)
    {
        if (error_message)
        {
            *error_message = "AndroidBitmap_lockPixels failed rc=" + std::to_string(lock_pixels_rc);
        }
        return false;
    }

    bool success = true;
    if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888)
    {
        cv::Mat rgba(info.height, info.width, CV_8UC4, pixels, info.stride);
        cv::cvtColor(rgba, rgb, cv::COLOR_RGBA2RGB);
    }
    else if (info.format == ANDROID_BITMAP_FORMAT_RGB_565)
    {
        cv::Mat rgb565(info.height, info.width, CV_8UC2, pixels, info.stride);
        cv::cvtColor(rgb565, rgb, cv::COLOR_BGR5652RGB);
    }
    else
    {
        success = false;
        if (error_message)
        {
            *error_message = "unsupported bitmap format=" + std::to_string(info.format);
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return success;
}

static bool run_detection_on_rgb(const cv::Mat& rgb)
{
    const double start_time_ms = ncnn::get_current_time();
    YOLOv8* yolov8 = 0;
    {
        ncnn::MutexLockGuard g(lock);
        yolov8 = g_yolov8;
    }

    if (!yolov8)
    {
        set_latest_failed_frame_state(rgb.cols, rgb.rows, 0, "model_state", -1, "model not loaded");
        return false;
    }

    std::vector<Object> objects;
    std::string detect_error_stage;
    int detect_error_code = 0;
    std::string detect_error_message;
    const int detect_result = yolov8->detect(rgb, objects, &detect_error_stage, &detect_error_code, &detect_error_message);
    const jint prelimit_detection_count = get_hiddenrisk_last_raw_detection_count();
    const jlong inference_time_ms = (jlong)(ncnn::get_current_time() - start_time_ms + 0.5);

    if (detect_result != 0)
    {
        const std::string stage = detect_error_stage.empty() ? "detect" : detect_error_stage;
        const jint code = detect_error_code != 0 ? detect_error_code : detect_result;
        const std::string message = detect_error_message.empty() ? "detect failed" : detect_error_message;
        set_latest_failed_frame_state(rgb.cols, rgb.rows, inference_time_ms, stage, code, message);
        __android_log_print(
            ANDROID_LOG_ERROR,
            LOG_TAG_NCNN,
            "detect failed backend=%s device=%s stage=%s code=%d message=%s",
            g_latest_backend_name.c_str(),
            g_latest_device_name.c_str(),
            stage.c_str(),
            code,
            message.c_str());
        return false;
    }

    set_latest_success_frame_state(rgb.cols, rgb.rows, objects, prelimit_detection_count, inference_time_ms);
    return true;
}

static bool run_detection_on_hardware_buffer(
    AHardwareBuffer* hardware_buffer,
    int image_width,
    int image_height,
    int rotation_degrees)
{
    const double start_time_ms = ncnn::get_current_time();
    YOLOv8* yolov8 = 0;
    {
        ncnn::MutexLockGuard g(lock);
        yolov8 = g_yolov8;
    }

    if (!yolov8)
    {
        set_latest_failed_frame_state(image_width, image_height, 0, "model_state", -1, "model not loaded");
        return false;
    }

#if __ANDROID_API__ < 26
    (void)hardware_buffer;
    (void)rotation_degrees;
    set_latest_failed_frame_state(image_width, image_height, 0, "hardware_buffer", -1, "hardware buffer requires api 26+");
    return false;
#else
    YOLOv8_det* detector = static_cast<YOLOv8_det*>(yolov8);
    std::vector<Object> objects;
    std::string detect_error_stage;
    int detect_error_code = 0;
    std::string detect_error_message;
    const int detect_result = detector->detect_hardware_buffer(
        hardware_buffer,
        image_width,
        image_height,
        rotation_degrees,
        objects,
        &detect_error_stage,
        &detect_error_code,
        &detect_error_message);
    const jint prelimit_detection_count = get_hiddenrisk_last_raw_detection_count();
    const jlong inference_time_ms = (jlong)(ncnn::get_current_time() - start_time_ms + 0.5);

    if (detect_result != 0)
    {
        const std::string stage = detect_error_stage.empty() ? "detect" : detect_error_stage;
        const jint code = detect_error_code != 0 ? detect_error_code : detect_result;
        const std::string message = detect_error_message.empty() ? "detect failed" : detect_error_message;
        set_latest_failed_frame_state(image_width, image_height, inference_time_ms, stage, code, message);
        __android_log_print(
            ANDROID_LOG_ERROR,
            LOG_TAG_NCNN,
            "detect hardware buffer failed backend=%s device=%s stage=%s code=%d message=%s",
            g_latest_backend_name.c_str(),
            g_latest_device_name.c_str(),
            stage.c_str(),
            code,
            message.c_str());
        return false;
    }

    set_latest_success_frame_state(image_width, image_height, objects, prelimit_detection_count, inference_time_ms);
    return true;
#endif
}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG_NCNN, "JNI_OnLoad");
    return JNI_VERSION_1_4;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved)
{
    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG_NCNN, "JNI_OnUnload");

    {
        ncnn::MutexLockGuard g(lock);
        delete g_yolov8;
        g_yolov8 = 0;
        g_latest_backend_id = -1;
        g_latest_backend_name.clear();
        g_latest_device_name.clear();
        g_loaded_backend_id = -1;
        g_loaded_gpu_profile = -1;
        g_loaded_target_size = 0;
        clear_latest_frame_state_locked();
    }

    ncnn::destroy_gpu_instance();
}

// public native void clearFrameState();
JNIEXPORT void JNICALL Java_com_rokid_glass_hiddenrisk_HiddenRiskNcnn_clearFrameState(JNIEnv* env, jobject thiz)
{
    clear_latest_frame_state();
}

// public native void setDebugResultLimit(int maxResults);
JNIEXPORT void JNICALL Java_com_rokid_glass_hiddenrisk_HiddenRiskNcnn_setDebugResultLimit(
    JNIEnv* env,
    jobject thiz,
    jint maxResults)
{
    (void)env;
    (void)thiz;
    set_hiddenrisk_detect_result_limit((int)maxResults);
    __android_log_print(
        ANDROID_LOG_INFO,
        LOG_TAG_NCNN,
        "setDebugResultLimit maxResults=%d",
        (int)maxResults);
}

// public native void setDebugCompareEnabled(boolean enabled);
JNIEXPORT void JNICALL Java_com_rokid_glass_hiddenrisk_HiddenRiskNcnn_setDebugCompareEnabled(
    JNIEnv* env,
    jobject thiz,
    jboolean enabled)
{
    (void)env;
    (void)thiz;
    set_hiddenrisk_debug_compare_enabled(enabled == JNI_TRUE);
    __android_log_print(
        ANDROID_LOG_INFO,
        LOG_TAG_NCNN,
        "setDebugCompareEnabled enabled=%d",
        enabled == JNI_TRUE ? 1 : 0);
}

// public native boolean loadModel(AssetManager mgr, int backend, int gpuProfile, int targetSize);
JNIEXPORT jboolean JNICALL Java_com_rokid_glass_hiddenrisk_HiddenRiskNcnn_loadModel(
    JNIEnv* env,
    jobject thiz,
    jobject assetManager,
    jint backend,
    jint gpuProfile,
    jint targetSize)
{
    const double load_start_ms = ncnn::get_current_time();
    if (backend < 0 || backend > 2)
    {
        ncnn::MutexLockGuard g(lock);
        set_latest_backend_info_locked(backend, backend_label((int)backend), "");
        set_latest_error_locked("validate_backend", -1, "invalid backend");
        return JNI_FALSE;
    }
    if (gpuProfile < 0 || gpuProfile > 2)
    {
        ncnn::MutexLockGuard g(lock);
        set_latest_backend_info_locked(backend, backend_label((int)backend), "");
        set_latest_error_locked("validate_gpu_profile", -1, "invalid gpu profile");
        return JNI_FALSE;
    }
    if (targetSize < 320 || targetSize > 1280)
    {
        ncnn::MutexLockGuard g(lock);
        set_latest_backend_info_locked(backend, backend_label((int)backend), "");
        set_latest_error_locked("validate_target_size", -1, "invalid target size");
        return JNI_FALSE;
    }

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr)
    {
        ncnn::MutexLockGuard g(lock);
        set_latest_backend_info_locked(backend, backend_label((int)backend), "");
        set_latest_error_locked("asset_manager", -1, "asset manager unavailable");
        return JNI_FALSE;
    }

    const char* parampath = "hiddenrisk.ncnn.param";
    const char* modelpath = "hiddenrisk.ncnn.bin";
    const bool use_gpu = (int)backend == 1;
    const bool use_turnip = (int)backend == 2;
    const std::string backend_name = backend_label((int)backend);
    const std::string driver_source = driver_source_for_backend((int)backend);
    const char* driver_path = use_turnip ? "libvulkan_freedreno.so" : 0;
    std::string device_name = use_gpu || use_turnip ? "N/A" : "CPU";
    YOLOv8* old_yolov8 = 0;
    double reuse_check_done_ms = ncnn::get_current_time();

    {
        // 同一进程内重复进入探针页时，优先复用已经加载好的同配置模型，避免再次触发耗时的图加载和 GPU 初始化。
        ncnn::MutexLockGuard g(lock);
        if (g_yolov8
            && g_loaded_backend_id == backend
            && g_loaded_gpu_profile == gpuProfile
            && g_loaded_target_size == targetSize)
        {
            set_latest_backend_info_locked(backend, backend_name, g_latest_device_name);
            set_latest_error_locked("", 0, "");
            __android_log_print(
                ANDROID_LOG_INFO,
                LOG_TAG_NCNN,
                "loadModel reuse backend=%s profile=%s targetSize=%d device=%s totalMs=%.0f",
                backend_name.c_str(),
                gpu_profile_label((int)gpuProfile),
                (int)targetSize,
                g_latest_device_name.c_str(),
                ncnn::get_current_time() - load_start_ms);
            return JNI_TRUE;
        }
    }
    reuse_check_done_ms = ncnn::get_current_time();

    {
        // 只在交换全局状态时加锁，避免模型加载阶段阻塞 UI 线程读取 stats。
        ncnn::MutexLockGuard g(lock);
        old_yolov8 = g_yolov8;
        g_yolov8 = 0;
        g_loaded_backend_id = -1;
        g_loaded_gpu_profile = -1;
        g_loaded_target_size = 0;
        set_latest_backend_info_locked(backend, backend_name, device_name);
        clear_latest_frame_state_locked();
    }

    const double cleanup_start_ms = ncnn::get_current_time();
    delete old_yolov8;
    ncnn::destroy_gpu_instance();
    const double cleanup_done_ms = ncnn::get_current_time();

    int create_gpu_rc = 0;
    int gpu_count = 0;
    const bool driver_openable = can_open_driver_library(driver_path);
    if (use_turnip && !driver_openable)
    {
        set_latest_error("turnip_loader", -1, "libvulkan_freedreno.so unavailable");
        __android_log_print(
            ANDROID_LOG_ERROR,
            LOG_TAG_NCNN,
            "loadModel backend=%s source=%s driver unavailable",
            backend_name.c_str(),
            driver_source.c_str());
        return JNI_FALSE;
    }
    if (use_turnip)
    {
        create_gpu_rc = ncnn::create_gpu_instance(driver_path);
    }
    else if (use_gpu)
    {
        create_gpu_rc = ncnn::create_gpu_instance();
    }
    const double gpu_instance_done_ms = ncnn::get_current_time();

    if (use_gpu || use_turnip)
    {
        gpu_count = ncnn::get_gpu_count();
        const double gpu_inventory_done_ms = ncnn::get_current_time();
        if (create_gpu_rc != 0)
        {
            const std::string message = "create_gpu_instance failed rc=" + std::to_string(create_gpu_rc);
            set_latest_error("create_gpu_instance", create_gpu_rc, message);
            __android_log_print(
                ANDROID_LOG_ERROR,
                LOG_TAG_NCNN,
                "loadModel backend=%s source=%s createGpuRc=%d createGpuMs=%.0f totalMs=%.0f",
                backend_name.c_str(),
                driver_source.c_str(),
                create_gpu_rc,
                gpu_instance_done_ms - cleanup_done_ms,
                gpu_inventory_done_ms - load_start_ms);
            return JNI_FALSE;
        }
        if (gpu_count <= 0)
        {
            set_latest_error("gpu_inventory", gpu_count, "no gpu devices reported by ncnn");
            __android_log_print(
                ANDROID_LOG_ERROR,
                LOG_TAG_NCNN,
                "loadModel backend=%s source=%s gpuCount=%d createGpuMs=%.0f inventoryMs=%.0f totalMs=%.0f",
                backend_name.c_str(),
                driver_source.c_str(),
                gpu_count,
                gpu_instance_done_ms - cleanup_done_ms,
                gpu_inventory_done_ms - gpu_instance_done_ms,
                gpu_inventory_done_ms - load_start_ms);
            return JNI_FALSE;
        }

        const ncnn::GpuInfo& info = ncnn::get_gpu_info();
        device_name = string_or_na(info.device_name());
        {
            ncnn::MutexLockGuard g(lock);
            g_latest_device_name = device_name;
        }
        __android_log_print(
            ANDROID_LOG_INFO,
            LOG_TAG_NCNN,
            "loadModel backend=%s source=%s createGpuRc=%d gpuCount=%d device=%s vendorId=%u deviceId=%u driver=%s driverId=%u queues=%d/%d fp16=%d int8=%d bf16=%d subgroup=%u",
            backend_name.c_str(),
            driver_source.c_str(),
            create_gpu_rc,
            gpu_count,
            device_name.c_str(),
            info.vendor_id(),
            info.device_id(),
            string_or_na(info.driver_name()).c_str(),
            info.driver_id(),
            info.compute_queue_count(),
            info.transfer_queue_count(),
            info.support_fp16_storage() || info.support_fp16_arithmetic() || info.support_fp16_packed(),
            info.support_int8_storage() || info.support_int8_arithmetic() || info.support_int8_packed(),
            info.support_bf16_storage() || info.support_bf16_packed(),
            info.subgroup_size());
        __android_log_print(
            ANDROID_LOG_INFO,
            LOG_TAG_NCNN,
            "gpu diagnostics profile=%s targetSize=%d unifiedQueue=%d fp16Packed=%d fp16Storage=%d fp16Arithmetic=%d bugStorageNoL1=%d bugBufferImageLoadZero=%d bugImplicitFp16Arithmetic=%d",
            gpu_profile_label((int)gpuProfile),
            (int)targetSize,
            info.unified_compute_transfer_queue() ? 1 : 0,
            info.support_fp16_packed() ? 1 : 0,
            info.support_fp16_storage() ? 1 : 0,
            info.support_fp16_arithmetic() ? 1 : 0,
            info.bug_storage_buffer_no_l1() ? 1 : 0,
            info.bug_buffer_image_load_zero() ? 1 : 0,
            info.bug_implicit_fp16_arithmetic() ? 1 : 0);
    }
    const double gpu_query_done_ms = ncnn::get_current_time();

    YOLOv8* new_yolov8 = new YOLOv8_det_hiddenrisk;
    const double yolo_create_done_ms = ncnn::get_current_time();
    std::string load_error_stage;
    int load_error_code = 0;
    std::string load_error_message;
    if (new_yolov8->load(
            mgr,
            parampath,
            modelpath,
            use_gpu || use_turnip,
            gpuProfile,
            &load_error_stage,
            &load_error_code,
            &load_error_message) != 0)
    {
        const double net_load_done_ms = ncnn::get_current_time();
        const std::string stage = load_error_stage.empty() ? "load_model" : load_error_stage;
        const std::string message = load_error_message.empty() ? "load hiddenrisk model failed" : load_error_message;
        set_latest_error(stage, load_error_code, message);
        __android_log_print(
            ANDROID_LOG_ERROR,
            LOG_TAG_NCNN,
            "loadModel failed backend=%s device=%s stage=%s code=%d message=%s reuseCheckMs=%.0f cleanupMs=%.0f createGpuMs=%.0f gpuQueryMs=%.0f yoloCreateMs=%.0f netLoadMs=%.0f totalMs=%.0f",
            backend_name.c_str(),
            device_name.c_str(),
            stage.c_str(),
            load_error_code,
            message.c_str(),
            reuse_check_done_ms - load_start_ms,
            cleanup_done_ms - cleanup_start_ms,
            gpu_instance_done_ms - cleanup_done_ms,
            gpu_query_done_ms - gpu_instance_done_ms,
            yolo_create_done_ms - gpu_query_done_ms,
            net_load_done_ms - yolo_create_done_ms,
            net_load_done_ms - load_start_ms);
        delete new_yolov8;
        return JNI_FALSE;
    }
    const double net_load_done_ms = ncnn::get_current_time();

    new_yolov8->set_det_target_size((int)targetSize);
    const double set_target_done_ms = ncnn::get_current_time();
    {
        ncnn::MutexLockGuard g(lock);
        g_yolov8 = new_yolov8;
        g_loaded_backend_id = backend;
        g_loaded_gpu_profile = gpuProfile;
        g_loaded_target_size = targetSize;
        set_latest_backend_info_locked(backend, backend_name, device_name);
        set_latest_error_locked("", 0, "");
    }
    __android_log_print(
        ANDROID_LOG_INFO,
        LOG_TAG_NCNN,
        "loadModel success backend=%s profile=%s targetSize=%d device=%s reuseCheckMs=%.0f cleanupMs=%.0f createGpuMs=%.0f gpuQueryMs=%.0f yoloCreateMs=%.0f netLoadMs=%.0f setTargetMs=%.0f totalMs=%.0f",
        backend_name.c_str(),
        gpu_profile_label((int)gpuProfile),
        (int)targetSize,
        device_name.c_str(),
        reuse_check_done_ms - load_start_ms,
        cleanup_done_ms - cleanup_start_ms,
        gpu_instance_done_ms - cleanup_done_ms,
        gpu_query_done_ms - gpu_instance_done_ms,
        yolo_create_done_ms - gpu_query_done_ms,
        net_load_done_ms - yolo_create_done_ms,
        set_target_done_ms - net_load_done_ms,
        set_target_done_ms - load_start_ms);

    return JNI_TRUE;
}

// public native boolean submitBitmap(Bitmap bitmap);
JNIEXPORT jboolean JNICALL Java_com_rokid_glass_hiddenrisk_HiddenRiskNcnn_submitBitmap(
    JNIEnv* env,
    jobject thiz,
    jobject bitmap)
{
    cv::Mat rgb;
    std::string error_message;
    const bool converted = convert_bitmap_to_rgb(env, bitmap, rgb, &error_message);
    if (!converted)
    {
        ncnn::MutexLockGuard g(lock);
        set_latest_error_locked(
            "convert_bitmap",
            -1,
            error_message.empty() ? "failed to convert bitmap" : error_message);
        return JNI_FALSE;
    }

    return run_detection_on_rgb(rgb) ? JNI_TRUE : JNI_FALSE;
}

// public native boolean submitHardwareBuffer(HardwareBuffer hardwareBuffer, int width, int height, int rotationDegrees);
JNIEXPORT jboolean JNICALL Java_com_rokid_glass_hiddenrisk_HiddenRiskNcnn_submitHardwareBuffer(
    JNIEnv* env,
    jobject thiz,
    jobject hardwareBuffer,
    jint width,
    jint height,
    jint rotationDegrees)
{
#if __ANDROID_API__ < 26
    (void)env;
    (void)thiz;
    (void)hardwareBuffer;
    (void)width;
    (void)height;
    (void)rotationDegrees;
    ncnn::MutexLockGuard g(lock);
    set_latest_error_locked("hardware_buffer", -1, "hardware buffer requires api 26+");
    return JNI_FALSE;
#else
    (void)thiz;
    if (!hardwareBuffer)
    {
        ncnn::MutexLockGuard g(lock);
        set_latest_error_locked("hardware_buffer", -1, "hardware buffer is null");
        return JNI_FALSE;
    }

    AHardwareBuffer* native_buffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!native_buffer)
    {
        ncnn::MutexLockGuard g(lock);
        set_latest_error_locked("hardware_buffer", -1, "failed to unwrap hardware buffer");
        return JNI_FALSE;
    }

    return run_detection_on_hardware_buffer(native_buffer, (int)width, (int)height, (int)rotationDegrees) ? JNI_TRUE : JNI_FALSE;
#endif
}

// public native NativeInferenceStats getLatestInferenceStats();
JNIEXPORT jobject JNICALL Java_com_rokid_glass_hiddenrisk_HiddenRiskNcnn_getLatestInferenceStats(
    JNIEnv* env,
    jobject thiz)
{
    return create_inference_stats(env);
}

}
