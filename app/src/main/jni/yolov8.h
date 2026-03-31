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

#ifndef YOLOV8_H
#define YOLOV8_H

#include <opencv2/core/core.hpp>

#include <string>

#include <net.h>

#if __ANDROID_API__ >= 26
struct AHardwareBuffer;
#endif

struct Object
{
    cv::Rect_<float> rect;
    int label;
    float prob;
};

void set_hiddenrisk_detect_result_limit(int limit);
int get_hiddenrisk_detect_result_limit();
int get_hiddenrisk_last_raw_detection_count();
void set_hiddenrisk_debug_compare_enabled(bool enabled);

class YOLOv8
{
public:
    YOLOv8();
    virtual ~YOLOv8();

    int load(
        const char* parampath,
        const char* modelpath,
        bool use_gpu = false,
        int gpu_profile = 0,
        std::string* error_stage = 0,
        int* error_code = 0,
        std::string* error_message = 0);
    int load(
        AAssetManager* mgr,
        const char* parampath,
        const char* modelpath,
        bool use_gpu = false,
        int gpu_profile = 0,
        std::string* error_stage = 0,
        int* error_code = 0,
        std::string* error_message = 0);

    void set_det_target_size(int target_size);

    virtual int detect(
        const cv::Mat& rgb,
        std::vector<Object>& objects,
        std::string* error_stage = 0,
        int* error_code = 0,
        std::string* error_message = 0) = 0;
    virtual int draw(cv::Mat& rgb, const std::vector<Object>& objects) = 0;
    virtual const char* label_name(int label) const { return "unknown"; }

protected:
    ncnn::Net yolov8;
    int det_target_size;
};

class YOLOv8_det : public YOLOv8
{
public:
    virtual int detect(
        const cv::Mat& rgb,
        std::vector<Object>& objects,
        std::string* error_stage = 0,
        int* error_code = 0,
        std::string* error_message = 0);
#if __ANDROID_API__ >= 26
    int detect_hardware_buffer(
        AHardwareBuffer* hardware_buffer,
        int image_width,
        int image_height,
        int rotation_degrees,
        std::vector<Object>& objects,
        std::string* error_stage = 0,
        int* error_code = 0,
        std::string* error_message = 0);
#endif
};

class YOLOv8_det_hiddenrisk : public YOLOv8_det
{
public:
    virtual int draw(cv::Mat& rgb, const std::vector<Object>& objects);
    virtual const char* label_name(int label) const;
};

#endif // YOLOV8_H
