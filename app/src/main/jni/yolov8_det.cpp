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

#include <cfloat>
#if __ANDROID_API__ >= 26
#include <android/hardware_buffer.h>
#endif
#include <allocator.h>
#include <command.h>
#include <gpu.h>
#include <pipeline.h>
#include <algorithm>
#include <cstdio>
#include <string>

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>

static int g_hiddenrisk_last_raw_detection_count = 0;
static bool g_hiddenrisk_debug_compare_enabled = false;

void set_hiddenrisk_detect_result_limit(int limit)
{
    // 兼容保留 JNI 接口，但不再对 NMS 后结果做截断。
    (void)limit;
}

int get_hiddenrisk_last_raw_detection_count()
{
    return g_hiddenrisk_last_raw_detection_count;
}

void set_hiddenrisk_debug_compare_enabled(bool enabled)
{
    g_hiddenrisk_debug_compare_enabled = enabled;
}

static std::string summarize_objects(
    const std::vector<Object>& objects,
    const std::vector<int>* picked,
    size_t max_count)
{
    std::string summary;
    const size_t count = picked
        ? std::min(max_count, picked->size())
        : std::min(max_count, objects.size());
    char buffer[256];
    for (size_t i = 0; i < count; i++)
    {
        const Object& obj = picked ? objects[(*picked)[i]] : objects[i];
        std::snprintf(
            buffer,
            sizeof(buffer),
            "%s#%zu(label=%d prob=%.6f rect=%.1f,%.1f,%.1f,%.1f)",
            i == 0 ? "" : " ",
            i,
            obj.label,
            obj.prob,
            obj.rect.x,
            obj.rect.y,
            obj.rect.width,
            obj.rect.height);
        summary += buffer;
    }

    if (summary.empty())
    {
        summary = "none";
    }

    return summary;
}

static inline float intersection_area(const Object& a, const Object& b)
{
    cv::Rect_<float> inter = a.rect & b.rect;
    return inter.area();
}

static void qsort_descent_inplace(std::vector<Object>& objects, int left, int right)
{
    int i = left;
    int j = right;
    float p = objects[(left + right) / 2].prob;

    while (i <= j)
    {
        while (objects[i].prob > p)
            i++;

        while (objects[j].prob < p)
            j--;

        if (i <= j)
        {
            // swap
            std::swap(objects[i], objects[j]);

            i++;
            j--;
        }
    }

    // #pragma omp parallel sections
    {
        // #pragma omp section
        {
            if (left < j) qsort_descent_inplace(objects, left, j);
        }
        // #pragma omp section
        {
            if (i < right) qsort_descent_inplace(objects, i, right);
        }
    }
}

static void qsort_descent_inplace(std::vector<Object>& objects)
{
    if (objects.empty())
        return;

    qsort_descent_inplace(objects, 0, objects.size() - 1);
}

static void nms_sorted_bboxes(const std::vector<Object>& objects, std::vector<int>& picked, float nms_threshold, bool agnostic = false)
{
    picked.clear();

    const int n = objects.size();

    std::vector<float> areas(n);
    for (int i = 0; i < n; i++)
    {
        areas[i] = objects[i].rect.area();
    }

    for (int i = 0; i < n; i++)
    {
        const Object& a = objects[i];

        int keep = 1;
        for (int j = 0; j < (int)picked.size(); j++)
        {
            const Object& b = objects[picked[j]];

            if (!agnostic && a.label != b.label)
                continue;

            // intersection over union
            float inter_area = intersection_area(a, b);
            float union_area = areas[i] + areas[picked[j]] - inter_area;
            // float IoU = inter_area / union_area
            if (inter_area / union_area > nms_threshold)
                keep = 0;
        }

        if (keep)
            picked.push_back(i);
    }
}

static inline float sigmoid(float x)
{
    return 1.0f / (1.0f + expf(-x));
}

// 白名单模式：只保留与隐患检测相关的目标类别，其余全部过滤
static inline bool is_filtered_label(int label)
{
    switch (label)
    {
    case 0:  // T_btn               T字按钮
    case 4:  // gas_alarm           可燃气体报警器
    case 6:  // fire_cabinet        室内消火栓箱
    case 9:  // emergency_light     应急灯
    case 11: // hydrant_nozzle      栓口
    case 12: // regulator           气瓶调压阀
    case 14: // hose                水带
    case 15: // nozzle              水枪
    case 17: // lpg_cylinder        液化石油气瓶
    case 18: // extinguisher        灭火器
    case 22: // coal_stove          煤炉
    case 24: // flameout_protection 熄火保护装置
    case 25: // gas_range           燃气灶
    case 26: // electric_tricycle   电动三轮车
    case 27: // electric_bike       电动车
    case 28: // load_switch         负荷开关
        return false; // 白名单内，不过滤
    default:
        return true;  // 白名单外，过滤掉
    }
}

static void generate_proposals(const ncnn::Mat& pred, int stride, const ncnn::Mat& in_pad, float prob_threshold, std::vector<Object>& objects)
{
    const int w = in_pad.w;
    const int h = in_pad.h;

    const int num_grid_x = w / stride;
    const int num_grid_y = h / stride;

    const int reg_max_1 = 16;
    const int num_class = pred.w - reg_max_1 * 4; // number of classes. 80 for COCO

    for (int y = 0; y < num_grid_y; y++)
    {
        for (int x = 0; x < num_grid_x; x++)
        {
            const ncnn::Mat pred_grid = pred.row_range(y * num_grid_x + x, 1);

            // find label with max score
            int label = -1;
            float score = -FLT_MAX;
            {
                const ncnn::Mat pred_score = pred_grid.range(reg_max_1 * 4, num_class);

                for (int k = 0; k < num_class; k++)
                {
                    float s = pred_score[k];
                    if (s > score)
                    {
                        label = k;
                        score = s;
                    }
                }

                // 模型输出已经过 sigmoid，无需重复应用
                // score = sigmoid(score);
            }

            if (is_filtered_label(label))
                continue;

            if (score >= prob_threshold)
            {
                ncnn::Mat pred_bbox = pred_grid.range(0, reg_max_1 * 4).reshape(reg_max_1, 4);

                {
                    ncnn::Layer* softmax = ncnn::create_layer("Softmax");

                    ncnn::ParamDict pd;
                    pd.set(0, 1); // axis
                    pd.set(1, 1);
                    softmax->load_param(pd);

                    ncnn::Option opt;
                    opt.num_threads = 1;
                    opt.use_packing_layout = false;

                    softmax->create_pipeline(opt);

                    softmax->forward_inplace(pred_bbox, opt);

                    softmax->destroy_pipeline(opt);

                    delete softmax;
                }

                float pred_ltrb[4];
                for (int k = 0; k < 4; k++)
                {
                    float dis = 0.f;
                    const float* dis_after_sm = pred_bbox.row(k);
                    for (int l = 0; l < reg_max_1; l++)
                    {
                        dis += l * dis_after_sm[l];
                    }

                    pred_ltrb[k] = dis * stride;
                }

                float pb_cx = (x + 0.5f) * stride;
                float pb_cy = (y + 0.5f) * stride;

                float x0 = pb_cx - pred_ltrb[0];
                float y0 = pb_cy - pred_ltrb[1];
                float x1 = pb_cx + pred_ltrb[2];
                float y1 = pb_cy + pred_ltrb[3];

                Object obj;
                obj.rect.x = x0;
                obj.rect.y = y0;
                obj.rect.width = x1 - x0;
                obj.rect.height = y1 - y0;
                obj.label = label;
                obj.prob = score;

                objects.push_back(obj);
            }
        }
    }
}

static void generate_proposals(const ncnn::Mat& pred, const std::vector<int>& strides, const ncnn::Mat& in_pad, float prob_threshold, std::vector<Object>& objects)
{
    const int w = in_pad.w;
    const int h = in_pad.h;

    int pred_row_offset = 0;
    for (size_t i = 0; i < strides.size(); i++)
    {
        const int stride = strides[i];

        const int num_grid_x = w / stride;
        const int num_grid_y = h / stride;
        const int num_grid = num_grid_x * num_grid_y;

        generate_proposals(pred.row_range(pred_row_offset, num_grid), stride, in_pad, prob_threshold, objects);
        pred_row_offset += num_grid;
    }
}

static void generate_proposals_decoded(const ncnn::Mat& pred, float prob_threshold, std::vector<Object>& objects)
{
    const int num_class = pred.w - 4;
    if (num_class <= 0)
        return;

    for (int i = 0; i < pred.h; i++)
    {
        const float* values = pred.row(i);
        if (!values)
            continue;

        int label = -1;
        float score = -FLT_MAX;
        for (int k = 0; k < num_class; k++)
        {
            const float s = values[4 + k];
            if (s > score)
            {
                label = k;
                score = s;
            }
        }

        // 模型输出已经过 sigmoid，无需重复应用
        // score = sigmoid(score);

        if (is_filtered_label(label))
            continue;

        if (label < 0 || score < prob_threshold)
            continue;

        const float cx = values[0];
        const float cy = values[1];
        const float box_w = values[2];
        const float box_h = values[3];

        Object obj;
        obj.rect.x = cx - box_w * 0.5f;
        obj.rect.y = cy - box_h * 0.5f;
        obj.rect.width = box_w;
        obj.rect.height = box_h;
        obj.label = label;
        obj.prob = score;
        objects.push_back(obj);
    }
}

static bool normalize_prediction_layout(const ncnn::Mat& pred, int feature_size, int anchor_count, ncnn::Mat& normalized)
{
    if (pred.dims == 2)
    {
        if (pred.w == feature_size && pred.h == anchor_count)
        {
            normalized = pred;
            return true;
        }

        if (pred.w == anchor_count && pred.h == feature_size)
        {
            normalized.create(feature_size, anchor_count, (size_t)4u, 1);
            if (normalized.empty())
            {
                return false;
            }

            for (int y = 0; y < anchor_count; y++)
            {
                float* row = normalized.row(y);
                if (!row)
                {
                    return false;
                }

                for (int x = 0; x < feature_size; x++)
                {
                    row[x] = pred.row(x)[y];
                }
            }
            return true;
        }
    }

    if (pred.total() == (size_t)feature_size * anchor_count)
    {
        normalized = pred.reshape(feature_size, anchor_count);
        return !normalized.empty();
    }

    return false;
}

static void set_detect_error(
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

static bool compute_letterbox_geometry(
    int source_width,
    int source_height,
    int target_size,
    int& resized_width,
    int& resized_height,
    int& wpad,
    int& hpad,
    float& scale)
{
    if (source_width <= 0 || source_height <= 0 || target_size <= 0)
    {
        return false;
    }

    resized_width = source_width;
    resized_height = source_height;
    scale = 1.f;
    if (resized_width > resized_height)
    {
        scale = (float)target_size / resized_width;
        resized_width = target_size;
        resized_height = resized_height * scale;
    }
    else
    {
        scale = (float)target_size / resized_height;
        resized_height = target_size;
        resized_width = resized_width * scale;
    }

    wpad = target_size - resized_width;
    hpad = target_size - resized_height;
    return true;
}

// 中心裁剪几何计算 - 当输入图像大于目标尺寸时，直接裁剪中心区域
// 这相当于数字变焦，避免了resize导致的信息丢失
static bool compute_center_crop_geometry(
    int source_width,
    int source_height,
    int target_size,
    int& crop_x,
    int& crop_y,
    int& crop_width,
    int& crop_height,
    float& scale)
{
    if (source_width <= 0 || source_height <= 0 || target_size <= 0)
    {
        return false;
    }

    // 如果图像已经等于目标尺寸，直接返回
    if (source_width == target_size && source_height == target_size)
    {
        crop_x = 0;
        crop_y = 0;
        crop_width = target_size;
        crop_height = target_size;
        scale = 1.0f;
        return true;
    }

    // 如果图像任一维度小于目标尺寸，无法裁剪
    if (source_width < target_size || source_height < target_size)
    {
        return false;
    }

    // 计算中心裁剪区域
    crop_width = target_size;
    crop_height = target_size;
    crop_x = (source_width - target_size) / 2;
    crop_y = (source_height - target_size) / 2;
    scale = 1.0f;  // 裁剪模式下无缩放

    return true;
}

static int rotation_degrees_to_rotate_from(int rotation_degrees)
{
    switch (rotation_degrees)
    {
    case 0:
        return 1;
    case 90:
        return 6;
    case 180:
        return 3;
    case 270:
        return 8;
    default:
        return 0;
    }
}

static void rotate_dimensions_if_needed(int rotation_degrees, int& width, int& height)
{
    if (rotation_degrees == 90 || rotation_degrees == 270)
    {
        std::swap(width, height);
    }
}

static int postprocess_hiddenrisk_output(
    const ncnn::Mat& out,
    int image_width,
    int image_height,
    int resized_width,
    int resized_height,
    int wpad,
    int hpad,
    float scale,
    std::vector<Object>& objects,
    std::string* error_stage,
    int* error_code,
    std::string* error_message,
    int crop_offset_x = 0,
    int crop_offset_y = 0)
{
    const float prob_threshold = 0.70f;
    const float nms_threshold = 0.45f;

    std::vector<int> strides(3);
    strides[0] = 8;
    strides[1] = 16;
    strides[2] = 32;

    const int in_pad_width = resized_width + wpad;
    const int in_pad_height = resized_height + hpad;
    const int anchor_count = (in_pad_width / 8) * (in_pad_height / 8)
        + (in_pad_width / 16) * (in_pad_height / 16)
        + (in_pad_width / 32) * (in_pad_height / 32);

    __android_log_print(
        ANDROID_LOG_INFO,
        "HiddenRiskNcnn",
        "detect padded in_pad=%dx%d wpad=%d hpad=%d anchors=%d",
        in_pad_width,
        in_pad_height,
        wpad,
        hpad,
        anchor_count);

    if (out.empty())
    {
        __android_log_print(ANDROID_LOG_ERROR, "HiddenRiskNcnn", "extract out0 returned empty tensor");
        set_detect_error(
            error_stage,
            error_code,
            error_message,
            "extract_empty",
            -1,
            "extract out0 returned empty tensor");
        return -1;
    }

    std::vector<Object> proposals;
    ncnn::Mat normalized_pred;
    // YOLOv11 模型: 33个类别 (0-32), raw输出=64+33=97, decoded输出=4+33=37
    const int raw_feature_size = 64 + 33;
    const int decoded_feature_size = 4 + 33;
    if (normalize_prediction_layout(out, raw_feature_size, anchor_count, normalized_pred))
    {
        ncnn::Mat in_pad_shape;
        in_pad_shape.create(in_pad_width, in_pad_height, (size_t)4u, 1);
        __android_log_print(
            ANDROID_LOG_INFO,
            "HiddenRiskNcnn",
            "detect output layout=raw rows=%d features=%d",
            normalized_pred.h,
            normalized_pred.w);
        generate_proposals(normalized_pred, strides, in_pad_shape, prob_threshold, proposals);
    }
    else if (normalize_prediction_layout(out, decoded_feature_size, anchor_count, normalized_pred))
    {
        __android_log_print(
            ANDROID_LOG_INFO,
            "HiddenRiskNcnn",
            "detect output layout=decoded rows=%d features=%d",
            normalized_pred.h,
            normalized_pred.w);
        generate_proposals_decoded(normalized_pred, prob_threshold, proposals);
    }
    else
    {
        __android_log_print(
            ANDROID_LOG_ERROR,
            "HiddenRiskNcnn",
            "unexpected out0 shape dims=%d w=%d h=%d c=%d total=%zu anchors=%d",
            out.dims,
            out.w,
            out.h,
            out.c,
            out.total(),
            anchor_count);
        set_detect_error(
            error_stage,
            error_code,
            error_message,
            "output_shape",
            -1,
            "unexpected out0 shape dims=" + std::to_string(out.dims)
                + " w=" + std::to_string(out.w)
                + " h=" + std::to_string(out.h)
                + " c=" + std::to_string(out.c)
                + " total=" + std::to_string((unsigned long long)out.total())
                + " anchors=" + std::to_string(anchor_count));
        return -1;
    }

    qsort_descent_inplace(proposals);

    if (g_hiddenrisk_debug_compare_enabled)
    {
        __android_log_print(
            ANDROID_LOG_INFO,
            "HiddenRiskNcnn",
            "compare proposals before_nms=%zu top=%s",
            proposals.size(),
            summarize_objects(proposals, 0, 5).c_str());
    }

    std::vector<int> picked;
    nms_sorted_bboxes(proposals, picked, nms_threshold);

    if (g_hiddenrisk_debug_compare_enabled)
    {
        __android_log_print(
            ANDROID_LOG_INFO,
            "HiddenRiskNcnn",
            "compare proposals after_nms=%zu top=%s",
            picked.size(),
            summarize_objects(proposals, &picked, 5).c_str());
    }

    const int count = picked.size();
    objects.resize(count);
    for (int i = 0; i < count; i++)
    {
        objects[i] = proposals[picked[i]];

        // 坐标映射：考虑letterbox padding和裁剪偏移量
        float x0 = (objects[i].rect.x - (wpad / 2)) / scale + crop_offset_x;
        float y0 = (objects[i].rect.y - (hpad / 2)) / scale + crop_offset_y;
        float x1 = (objects[i].rect.x + objects[i].rect.width - (wpad / 2)) / scale + crop_offset_x;
        float y1 = (objects[i].rect.y + objects[i].rect.height - (hpad / 2)) / scale + crop_offset_y;

        x0 = std::max(std::min(x0, (float)(image_width - 1)), 0.f);
        y0 = std::max(std::min(y0, (float)(image_height - 1)), 0.f);
        x1 = std::max(std::min(x1, (float)(image_width - 1)), 0.f);
        y1 = std::max(std::min(y1, (float)(image_height - 1)), 0.f);

        objects[i].rect.x = x0;
        objects[i].rect.y = y0;
        objects[i].rect.width = x1 - x0;
        objects[i].rect.height = y1 - y0;
    }

    struct
    {
        bool operator()(const Object& a, const Object& b) const
        {
            return a.prob > b.prob;
        }
    } objects_prob_greater;
    std::sort(objects.begin(), objects.end(), objects_prob_greater);

    g_hiddenrisk_last_raw_detection_count = (int)objects.size();

    // 按 label id 升序排列，保证结果顺序与类别编号对应，便于上层逻辑稳定处理
    std::sort(objects.begin(), objects.end(), [](const Object& a, const Object& b) {
        return a.label < b.label;
    });

    set_detect_error(error_stage, error_code, error_message, "", 0, "");
    return 0;
}

int YOLOv8_det::detect(
    const cv::Mat& rgb,
    std::vector<Object>& objects,
    std::string* error_stage,
    int* error_code,
    std::string* error_message)
{
    const int target_size = det_target_size;//640;
    g_hiddenrisk_last_raw_detection_count = 0;

    int img_w = rgb.cols;
    int img_h = rgb.rows;

    // 尝试中心裁剪模式（数字变焦）
    int crop_x = 0, crop_y = 0;
    int crop_w = 0, crop_h = 0;
    float scale = 1.f;
    int wpad = 0, hpad = 0;
    int letterbox_w = 0, letterbox_h = 0;  // letterbox路径下resize后的尺寸（不含padding）
    ncnn::Mat in_pad;

    if (compute_center_crop_geometry(img_w, img_h, target_size, crop_x, crop_y, crop_w, crop_h, scale))
    {
        // 中心裁剪路径：从原始图像直接裁剪640x640中心区域
        __android_log_print(
            ANDROID_LOG_INFO,
            "HiddenRiskNcnn",
            "detect center_crop input=%dx%d crop=[%d,%d,%d,%d] (digital zoom)",
            img_w, img_h, crop_x, crop_y, crop_w, crop_h);

        // 使用OpenCV裁剪ROI区域，避免全图处理
        cv::Mat cropped = rgb(cv::Rect(crop_x, crop_y, crop_w, crop_h));

        // 直接转换为ncnn::Mat，无需resize
        in_pad = ncnn::Mat::from_pixels(cropped.data, ncnn::Mat::PIXEL_RGB, crop_w, crop_h);

        // 裁剪模式下无padding
        wpad = 0;
        hpad = 0;
        scale = 1.0f;
    }
    else
    {
        // Fallback: 原有letterbox resize路径，将结果存入外层变量
        if (!compute_letterbox_geometry(img_w, img_h, target_size, letterbox_w, letterbox_h, wpad, hpad, scale))
        {
            set_detect_error(error_stage, error_code, error_message, "geometry", -1, "invalid input image size");
            return -1;
        }

        __android_log_print(
            ANDROID_LOG_INFO,
            "HiddenRiskNcnn",
            "detect letterbox target=%d input=%dx%d resized=%dx%d scale=%.4f",
            target_size,
            img_w,
            img_h,
            letterbox_w,
            letterbox_h,
            scale);

        ncnn::Mat in = (img_w == letterbox_w && img_h == letterbox_h)
            ? ncnn::Mat::from_pixels(rgb.data, ncnn::Mat::PIXEL_RGB, img_w, img_h)
            : ncnn::Mat::from_pixels_resize(rgb.data, ncnn::Mat::PIXEL_RGB, img_w, img_h, letterbox_w, letterbox_h);

        ncnn::copy_make_border(in, in_pad, hpad / 2, hpad - hpad / 2, wpad / 2, wpad - wpad / 2, ncnn::BORDER_CONSTANT, 114.f);
    }

    const float norm_vals[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    in_pad.substract_mean_normalize(0, norm_vals);

    ncnn::Extractor ex = yolov8.create_extractor();
    // 运行时与 Net 选项保持一致，extract 阶段尽量复用并释放中间结果。
    ex.set_light_mode(true);

#if NCNN_VULKAN
    // 当前阶段先回退到 ncnn 默认 VkAllocator，排除自定义 allocator 组合触发的驱动问题。
#endif

    __android_log_print(ANDROID_LOG_INFO, "HiddenRiskNcnn", "detect ex.input start");
    const int input_rc = ex.input("in0", in_pad);
    if (input_rc != 0)
    {
        __android_log_print(ANDROID_LOG_ERROR, "HiddenRiskNcnn", "input in0 failed rc=%d", input_rc);
        set_detect_error(error_stage, error_code, error_message, "input", input_rc, "input in0 failed rc=" + std::to_string(input_rc));
        return input_rc;
    }
    __android_log_print(ANDROID_LOG_INFO, "HiddenRiskNcnn", "detect ex.input done");

    static const char* kFormalOutputBlobName = "out0";
    ncnn::Mat out;
    __android_log_print(ANDROID_LOG_INFO, "HiddenRiskNcnn", "detect ex.extract start");
    const int extract_rc = ex.extract(kFormalOutputBlobName, out);
    if (extract_rc != 0)
    {
        __android_log_print(
            ANDROID_LOG_ERROR,
            "HiddenRiskNcnn",
            "extract failed blob=%s rc=%d",
            kFormalOutputBlobName,
            extract_rc);
        set_detect_error(
            error_stage,
            error_code,
            error_message,
            "extract",
            extract_rc,
            "extract failed blob=" + std::string(kFormalOutputBlobName) + " rc=" + std::to_string(extract_rc));
        return extract_rc;
    }
    __android_log_print(
        ANDROID_LOG_INFO,
        "HiddenRiskNcnn",
        "detect ex.extract done blob=%s dims=%d w=%d h=%d c=%d total=%zu",
        kFormalOutputBlobName,
        out.dims,
        out.w,
        out.h,
        out.c,
        out.total());

    // 对于中心裁剪模式，需要调整坐标映射参数
    int effective_w = (crop_w > 0) ? crop_w : letterbox_w;
    int effective_h = (crop_h > 0) ? crop_h : letterbox_h;
    int crop_offset_x = (crop_w > 0) ? crop_x : 0;
    int crop_offset_y = (crop_h > 0) ? crop_y : 0;

    return postprocess_hiddenrisk_output(
        out,
        img_w,
        img_h,
        effective_w,
        effective_h,
        wpad,
        hpad,
        scale,
        objects,
        error_stage,
        error_code,
        error_message,
        crop_offset_x,
        crop_offset_y);
}

#if __ANDROID_API__ >= 26
int YOLOv8_det::detect_hardware_buffer(
    AHardwareBuffer* hardware_buffer,
    int image_width,
    int image_height,
    int rotation_degrees,
    std::vector<Object>& objects,
    std::string* error_stage,
    int* error_code,
    std::string* error_message)
{
#if !NCNN_VULKAN
    (void)hardware_buffer;
    (void)image_width;
    (void)image_height;
    (void)rotation_degrees;
    (void)objects;
    set_detect_error(error_stage, error_code, error_message, "vulkan_unavailable", -1, "ncnn vulkan is disabled");
    return -1;
#else
    if (!hardware_buffer)
    {
        set_detect_error(error_stage, error_code, error_message, "hardware_buffer", -1, "hardware buffer is null");
        return -1;
    }
    if (!yolov8.opt.use_vulkan_compute)
    {
        set_detect_error(error_stage, error_code, error_message, "backend", -1, "hardware buffer path requires vulkan backend");
        return -1;
    }

    const int rotate_from = rotation_degrees_to_rotate_from(rotation_degrees);
    if (rotate_from == 0)
    {
        set_detect_error(
            error_stage,
            error_code,
            error_message,
            "rotation",
            -1,
            "unsupported rotation degrees=" + std::to_string(rotation_degrees));
        return -1;
    }

    const int target_size = det_target_size;
    int rotated_width = image_width;
    int rotated_height = image_height;
    rotate_dimensions_if_needed(rotation_degrees, rotated_width, rotated_height);

    int resized_width = 0;
    int resized_height = 0;
    int wpad = 0;
    int hpad = 0;
    float scale = 1.f;
    if (!compute_letterbox_geometry(
            rotated_width,
            rotated_height,
            target_size,
            resized_width,
            resized_height,
            wpad,
            hpad,
            scale))
    {
        set_detect_error(error_stage, error_code, error_message, "geometry", -1, "invalid hardware buffer size");
        return -1;
    }

    __android_log_print(
        ANDROID_LOG_INFO,
        "HiddenRiskNcnn",
        "detect hardware buffer input=%dx%d rotation=%d rotated=%dx%d resized=%dx%d scale=%.4f",
        image_width,
        image_height,
        rotation_degrees,
        rotated_width,
        rotated_height,
        resized_width,
        resized_height,
        scale);

    ncnn::VulkanDevice* vkdev = ncnn::get_gpu_device(yolov8.opt.vulkan_device_index);
    if (!vkdev || !vkdev->is_valid())
    {
        set_detect_error(error_stage, error_code, error_message, "vulkan_device", -1, "vulkan device unavailable");
        return -1;
    }

    ncnn::VkAllocator* blob_vkallocator = vkdev->acquire_blob_allocator();
    ncnn::VkAllocator* staging_vkallocator = vkdev->acquire_staging_allocator();
    ncnn::Option preprocess_opt = yolov8.opt;
    preprocess_opt.blob_vkallocator = blob_vkallocator;
    preprocess_opt.workspace_vkallocator = blob_vkallocator;
    preprocess_opt.staging_vkallocator = staging_vkallocator;

    ncnn::Layer* padding_layer = 0;
    ncnn::Layer* normalize_layer = 0;
    ncnn::VkMat in_rgb;
    ncnn::VkMat in_pad;
    ncnn::VkMat in_norm;
    ncnn::ImportAndroidHardwareBufferPipeline import_pipeline(vkdev);
    ncnn::VkAndroidHardwareBufferImageAllocator ahb_allocator(vkdev, hardware_buffer);

    auto cleanup = [&]() {
        in_norm.release();
        in_pad.release();
        in_rgb.release();
        if (normalize_layer)
        {
            normalize_layer->destroy_pipeline(preprocess_opt);
            delete normalize_layer;
            normalize_layer = 0;
        }
        if (padding_layer)
        {
            padding_layer->destroy_pipeline(preprocess_opt);
            delete padding_layer;
            padding_layer = 0;
        }
        import_pipeline.destroy();
        if (staging_vkallocator)
        {
            vkdev->reclaim_staging_allocator(staging_vkallocator);
            staging_vkallocator = 0;
        }
        if (blob_vkallocator)
        {
            vkdev->reclaim_blob_allocator(blob_vkallocator);
            blob_vkallocator = 0;
        }
    };

    const int init_rc = ahb_allocator.init();
    if (init_rc != 0)
    {
        cleanup();
        set_detect_error(
            error_stage,
            error_code,
            error_message,
            "ahb_allocator_init",
            init_rc,
            "hardware buffer allocator init failed rc=" + std::to_string(init_rc));
        return init_rc;
    }

    const int import_create_rc = import_pipeline.create(
        &ahb_allocator,
        ncnn::Mat::PIXEL_RGB,
        rotate_from,
        resized_width,
        resized_height,
        preprocess_opt);
    if (import_create_rc != 0)
    {
        cleanup();
        set_detect_error(
            error_stage,
            error_code,
            error_message,
            "import_pipeline_create",
            import_create_rc,
            "import pipeline create failed rc=" + std::to_string(import_create_rc));
        return import_create_rc;
    }

    ncnn::VkImageMat src = ncnn::VkImageMat::from_android_hardware_buffer(&ahb_allocator);
    const size_t elemsize = (preprocess_opt.use_fp16_storage && vkdev->info.support_fp16_storage()) ? (size_t)2u : (size_t)4u;
    in_rgb.create(resized_width, resized_height, 3, elemsize, 1, blob_vkallocator);
    if (in_rgb.empty())
    {
        cleanup();
        set_detect_error(error_stage, error_code, error_message, "preprocess_alloc", -1, "failed to allocate rgb vk tensor");
        return -1;
    }

    padding_layer = ncnn::create_layer("Padding");
    if (!padding_layer)
    {
        cleanup();
        set_detect_error(error_stage, error_code, error_message, "padding_layer", -1, "failed to create padding layer");
        return -1;
    }
    padding_layer->vkdev = vkdev;
    {
        ncnn::ParamDict pd;
        pd.set(0, hpad / 2);
        pd.set(1, hpad - hpad / 2);
        pd.set(2, wpad / 2);
        pd.set(3, wpad - wpad / 2);
        pd.set(4, 0);
        pd.set(5, 114.f);
        padding_layer->load_param(pd);
    }
    {
        const int padding_create_rc = padding_layer->create_pipeline(preprocess_opt);
        if (padding_create_rc != 0)
        {
            cleanup();
            set_detect_error(
                error_stage,
                error_code,
                error_message,
                "padding_pipeline",
                padding_create_rc,
                "padding create_pipeline failed rc=" + std::to_string(padding_create_rc));
            return padding_create_rc;
        }
    }

    normalize_layer = ncnn::create_layer("BinaryOp");
    if (!normalize_layer)
    {
        cleanup();
        set_detect_error(error_stage, error_code, error_message, "binaryop_layer", -1, "failed to create binaryop layer");
        return -1;
    }
    normalize_layer->vkdev = vkdev;
    {
        ncnn::ParamDict pd;
        pd.set(0, 2);
        pd.set(1, 1);
        pd.set(2, 1.f / 255.f);
        normalize_layer->load_param(pd);
    }
    {
        const int binary_create_rc = normalize_layer->create_pipeline(preprocess_opt);
        if (binary_create_rc != 0)
        {
            cleanup();
            set_detect_error(
                error_stage,
                error_code,
                error_message,
                "binaryop_pipeline",
                binary_create_rc,
                "binaryop create_pipeline failed rc=" + std::to_string(binary_create_rc));
            return binary_create_rc;
        }
    }

    {
        ncnn::VkCompute preprocess_cmd(vkdev);
        preprocess_cmd.record_import_android_hardware_buffer(&import_pipeline, src, in_rgb);

        const int padding_forward_rc = padding_layer->forward(in_rgb, in_pad, preprocess_cmd, preprocess_opt);
        if (padding_forward_rc != 0)
        {
            cleanup();
            set_detect_error(
                error_stage,
                error_code,
                error_message,
                "padding_forward",
                padding_forward_rc,
                "padding forward failed rc=" + std::to_string(padding_forward_rc));
            return padding_forward_rc;
        }

        const int normalize_forward_rc = normalize_layer->forward(in_pad, in_norm, preprocess_cmd, preprocess_opt);
        if (normalize_forward_rc != 0)
        {
            cleanup();
            set_detect_error(
                error_stage,
                error_code,
                error_message,
                "binaryop_forward",
                normalize_forward_rc,
                "binaryop forward failed rc=" + std::to_string(normalize_forward_rc));
            return normalize_forward_rc;
        }

        const int preprocess_submit_rc = preprocess_cmd.submit_and_wait();
        if (preprocess_submit_rc != 0)
        {
            cleanup();
            set_detect_error(
                error_stage,
                error_code,
                error_message,
                "preprocess_submit",
                preprocess_submit_rc,
                "preprocess submit failed rc=" + std::to_string(preprocess_submit_rc));
            return preprocess_submit_rc;
        }
    }

    ncnn::Mat out;
    {
        ncnn::Extractor ex = yolov8.create_extractor();
        ex.set_light_mode(true);
        ex.set_blob_vkallocator(blob_vkallocator);
        ex.set_workspace_vkallocator(blob_vkallocator);
        ex.set_staging_vkallocator(staging_vkallocator);

        const int input_rc = ex.input("in0", in_norm);
        if (input_rc != 0)
        {
            cleanup();
            set_detect_error(
                error_stage,
                error_code,
                error_message,
                "input",
                input_rc,
                "input in0 failed rc=" + std::to_string(input_rc));
            return input_rc;
        }

        static const char* kFormalOutputBlobName = "out0";
        const int extract_rc = ex.extract(kFormalOutputBlobName, out);
        if (extract_rc != 0)
        {
            cleanup();
            set_detect_error(
                error_stage,
                error_code,
                error_message,
                "extract",
                extract_rc,
                "extract failed blob=" + std::string(kFormalOutputBlobName) + " rc=" + std::to_string(extract_rc));
            return extract_rc;
        }
        __android_log_print(
            ANDROID_LOG_INFO,
            "HiddenRiskNcnn",
            "detect ex.extract done blob=%s dims=%d w=%d h=%d c=%d total=%zu",
            kFormalOutputBlobName,
            out.dims,
            out.w,
            out.h,
            out.c,
            out.total());
    }

    cleanup();
    return postprocess_hiddenrisk_output(
        out,
        rotated_width,
        rotated_height,
        resized_width,
        resized_height,
        wpad,
        hpad,
        scale,
        objects,
        error_stage,
        error_code,
        error_message);
#endif
}
#endif

int YOLOv8_det_hiddenrisk::draw(cv::Mat& rgb, const std::vector<Object>& objects)
{
    // YOLOv11: 33个类别 (索引0-32)
    static const char* class_names[] = {
        "T_btn",                    // 0: T字按钮
        "tee_joint",                // 1: 三通接口
        "cutoff_linkage",           // 2: 切断联动装置
        "cassette_stove",           // 3: 卡式炉
        "gas_alarm",                // 4: 可燃气体报警器
        "exit_sign",                // 5: 安全出口标志
        "fire_cabinet",             // 6: 室内消火栓箱
        "hydrant_outdoor",          // 7: 室外消火栓
        "industrial_gas_detector",  // 8: 工业可燃气体探测器
        "emergency_light",          // 9: 应急灯
        "exhaust_fan",              // 10: 排气扇
        "hydrant_nozzle",           // 11: 栓口
        "regulator",                // 12: 气瓶调压阀
        "oxygen_cylinder",          // 13: 氧气瓶
        "hose",                     // 14: 水带
        "nozzle",                   // 15: 水枪
        "pump_connector",           // 16: 水泵接合器
        "lpg_cylinder",             // 17: 液化石油气瓶
        "extinguisher",             // 18: 灭火器
        "extinguisher_box",         // 19: 灭火器箱
        "charcoal_stove",           // 20: 炭炉
        "igniter",                  // 21: 点火针
        "coal_stove",               // 22: 煤炉
        "lighting_fixture",         // 23: 照明灯具
        "flameout_protection",      // 24: 熄火保护装置
        "gas_range",                // 25: 燃气灶
        "electric_tricycle",        // 26: 电动三轮车
        "electric_bike",            // 27: 电动车
        "load_switch",              // 28: 负荷开关
        "gas_hose",                 // 29: 软管
        "door_closer",              // 30: 防火门闭门器
        "door_sequencer",           // 31: 防火门顺序器
        "security_window"           // 32: 防盗窗
    };

    static cv::Scalar colors[] = {
        cv::Scalar(67, 54, 244),
        cv::Scalar(30, 99, 233),
        cv::Scalar(39, 176, 156),
        cv::Scalar(58, 183, 103),
        cv::Scalar(81, 181, 63),
        cv::Scalar(150, 243, 33),
        cv::Scalar(169, 244, 3),
        cv::Scalar(188, 212, 0),
        cv::Scalar(150, 136, 0),
        cv::Scalar(175, 80, 76),
        cv::Scalar(195, 74, 139),
        cv::Scalar(220, 57, 205),
        cv::Scalar(235, 59, 255),
        cv::Scalar(193, 7, 255),
        cv::Scalar(152, 0, 255),
        cv::Scalar(87, 34, 255),
        cv::Scalar(85, 72, 121),
        cv::Scalar(158, 158, 158),
        cv::Scalar(125, 139, 96)
    };

    for (size_t i = 0; i < objects.size(); i++)
    {
        const Object& obj = objects[i];
        const cv::Scalar& color = colors[i % 19];

        __android_log_print(
            ANDROID_LOG_INFO,
            "HiddenRiskNcnn",
            "draw object[%zu] label=%d prob=%.4f rect=[x=%.1f y=%.1f w=%.1f h=%.1f]",
            i,
            obj.label,
            obj.prob,
            obj.rect.x,
            obj.rect.y,
            obj.rect.width,
            obj.rect.height);

        cv::rectangle(rgb, obj.rect, color);

        char text[256];
        const char* label_name = "unknown";
        if (obj.label >= 0 && obj.label < (int)(sizeof(class_names) / sizeof(class_names[0])))
        {
            label_name = class_names[obj.label];
        }
        sprintf(text, "%s %.1f%%", label_name, obj.prob * 100);

        int baseLine = 0;
        cv::Size label_size = cv::getTextSize(text, cv::FONT_HERSHEY_SIMPLEX, 0.5, 1, &baseLine);

        int x = obj.rect.x;
        int y = obj.rect.y - label_size.height - baseLine;
        if (y < 0)
            y = 0;
        if (x + label_size.width > rgb.cols)
            x = rgb.cols - label_size.width;

        cv::rectangle(rgb, cv::Rect(cv::Point(x, y), cv::Size(label_size.width, label_size.height + baseLine)),
                      cv::Scalar(255, 255, 255), -1);

        cv::putText(rgb, text, cv::Point(x, y + label_size.height),
                    cv::FONT_HERSHEY_SIMPLEX, 0.5, cv::Scalar(0, 0, 0));
    }

    return 0;
}

const char* YOLOv8_det_hiddenrisk::label_name(int label) const
{
    // YOLOv11: 33个类别
    static const char* class_names[] = {
        "T_btn",                    // 0: T字按钮
        "tee_joint",                // 1: 三通接口
        "cutoff_linkage",           // 2: 切断联动装置
        "cassette_stove",           // 3: 卡式炉
        "gas_alarm",                // 4: 可燃气体报警器
        "exit_sign",                // 5: 安全出口标志
        "fire_cabinet",             // 6: 室内消火栓箱
        "hydrant_outdoor",          // 7: 室外消火栓
        "industrial_gas_detector",  // 8: 工业可燃气体探测器
        "emergency_light",          // 9: 应急灯
        "exhaust_fan",              // 10: 排气扇
        "hydrant_nozzle",           // 11: 栓口
        "regulator",                // 12: 气瓶调压阀
        "oxygen_cylinder",          // 13: 氧气瓶
        "hose",                     // 14: 水带
        "nozzle",                   // 15: 水枪
        "pump_connector",           // 16: 水泵接合器
        "lpg_cylinder",             // 17: 液化石油气瓶
        "extinguisher",             // 18: 灭火器
        "extinguisher_box",         // 19: 灭火器箱
        "charcoal_stove",           // 20: 炭炉
        "igniter",                  // 21: 点火针
        "coal_stove",               // 22: 煤炉
        "lighting_fixture",         // 23: 照明灯具
        "flameout_protection",      // 24: 熄火保护装置
        "gas_range",                // 25: 燃气灶
        "electric_tricycle",        // 26: 电动三轮车
        "electric_bike",            // 27: 电动车
        "load_switch",              // 28: 负荷开关
        "gas_hose",                 // 29: 软管
        "door_closer",              // 30: 防火门闭门器
        "door_sequencer",           // 31: 防火门顺序器
        "security_window"           // 32: 防盗窗
    };
    int class_count = sizeof(class_names) / sizeof(class_names[0]);
    if (label < 0 || label >= class_count)
        return "unknown";
    return class_names[label];
}
