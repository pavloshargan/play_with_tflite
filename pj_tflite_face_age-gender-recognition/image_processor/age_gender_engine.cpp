/* Copyright 2021 iwatake2222

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/
/*** Include ***/
/* for general */
#include <cstdint>
#include <cstdlib>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>
#include <array>
#include <algorithm>
#include <chrono>
#include <fstream>

/* for OpenCV */
#include <opencv2/opencv.hpp>

/* for My modules */
#include "common_helper.h"
#include "common_helper_cv.h"
#include "inference_helper.h"
#include "age_gender_engine.h"

/*** Macro ***/
#define TAG "AgeGenderEngine"
#define PRINT(...)   COMMON_HELPER_PRINT(TAG, __VA_ARGS__)
#define PRINT_E(...) COMMON_HELPER_PRINT_E(TAG, __VA_ARGS__)

/* Model parameters */
#define MODEL_NAME  "age-gender-recognition.tflite"
#define TENSORTYPE  TensorInfo::kTensorTypeFp32
#define INPUT_NAME  "data"
#define INPUT_DIMS  { 1, 62, 62, 3 }
#define IS_NCHW     false
#define IS_RGB      false
#define OUTPUT_NAME_0 "Identity"
#define OUTPUT_NAME_1 "Identity_1"

/*** Function ***/
int32_t AgeGenderEngine::Initialize(const std::string& work_dir, const int32_t num_threads)
{
    /* Set model information */
    std::string model_filename = work_dir + "/model/" + MODEL_NAME;

    /* Set input tensor info */
    input_tensor_info_list_.clear();
    InputTensorInfo input_tensor_info(INPUT_NAME, TENSORTYPE, IS_NCHW);
    input_tensor_info.tensor_dims = INPUT_DIMS;
    input_tensor_info.data_type = InputTensorInfo::kDataTypeImage;
    input_tensor_info.normalize.mean[0] = 0.0f;     /* 0 - 255 */
    input_tensor_info.normalize.mean[1] = 0.0f;
    input_tensor_info.normalize.mean[2] = 0.0f;
    input_tensor_info.normalize.norm[0] = 1.0f / 255.0f;
    input_tensor_info.normalize.norm[1] = 1.0f / 255.0f;
    input_tensor_info.normalize.norm[2] = 1.0f / 255.0f;
    input_tensor_info_list_.push_back(input_tensor_info);

    /* Set output tensor info */
    output_tensor_info_list_.clear();
    output_tensor_info_list_.push_back(OutputTensorInfo(OUTPUT_NAME_0, TENSORTYPE));
    output_tensor_info_list_.push_back(OutputTensorInfo(OUTPUT_NAME_1, TENSORTYPE));

    /* Create and Initialize Inference Helper */
    //inference_helper_.reset(InferenceHelper::Create(InferenceHelper::kTensorflowLite));
//    inference_helper_.reset(InferenceHelper::Create(InferenceHelper::kTensorflowLiteXnnpack));
    inference_helper_.reset(InferenceHelper::Create(InferenceHelper::kTensorflowLiteQnn));
    //inference_helper_.reset(InferenceHelper::Create(InferenceHelper::kTensorflowLiteGpu1));
    //inference_helper_.reset(InferenceHelper::Create(InferenceHelper::kTensorflowLiteEdgetpu));
    // inference_helper_.reset(InferenceHelper::Create(InferenceHelper::kTensorflowLiteNnapi));
    

    if (!inference_helper_) {
        return kRetErr;
    }
    if (inference_helper_->SetNumThreads(num_threads) != InferenceHelper::kRetOk) {
        inference_helper_.reset();
        return kRetErr;
    }
    if (inference_helper_->Initialize(model_filename, input_tensor_info_list_, output_tensor_info_list_) != InferenceHelper::kRetOk) {
        inference_helper_.reset();
        return kRetErr;
    }

    return kRetOk;
}

int32_t AgeGenderEngine::Finalize()
{
    if (!inference_helper_) {
        PRINT_E("Inference helper is not created\n");
        return kRetErr;
    }
    inference_helper_->Finalize();
    return kRetOk;
}


int32_t AgeGenderEngine::Process(const cv::Mat& original_mat, const BoundingBox& bbox, Result& result)
{
    if (!inference_helper_) {
        PRINT_E("Inference helper is not created\n");
        return kRetErr;
    }

    InputTensorInfo& input_tensor_info = input_tensor_info_list_[0];

    /*** PreProcess ***/
    const auto& t_pre_process0 = std::chrono::steady_clock::now();
    int32_t cx = bbox.x + bbox.w / 2;
    int32_t cy = bbox.y + bbox.h / 2;
    int32_t face_size = static_cast<int32_t>((std::max)(bbox.w, bbox.h) * 1.7f);   /* expand face bbox */
    int32_t crop_x = (std::max)(0, cx - face_size / 2);
    int32_t crop_y = (std::max)(0, cy - face_size / 2);
    int32_t crop_w = (std::min)(face_size, original_mat.cols - crop_x);
    int32_t crop_h = (std::min)(face_size, original_mat.rows - crop_y);
    cv::Mat img_src = cv::Mat::zeros(input_tensor_info.GetHeight(), input_tensor_info.GetWidth(), CV_8UC3);
    CommonHelper::CropResizeCvt(original_mat, img_src, crop_x, crop_y, crop_w, crop_h, IS_RGB, CommonHelper::kCropTypeStretch);

    input_tensor_info.data = img_src.data;
    input_tensor_info.data_type = InputTensorInfo::kDataTypeImage;
    input_tensor_info.image_info.width = img_src.cols;
    input_tensor_info.image_info.height = img_src.rows;
    input_tensor_info.image_info.channel = img_src.channels();
    input_tensor_info.image_info.crop_x = 0;
    input_tensor_info.image_info.crop_y = 0;
    input_tensor_info.image_info.crop_width = img_src.cols;
    input_tensor_info.image_info.crop_height = img_src.rows;
    input_tensor_info.image_info.is_bgr = false;
    input_tensor_info.image_info.swap_color = false;
    if (inference_helper_->PreProcess(input_tensor_info_list_) != InferenceHelper::kRetOk) {
        return kRetErr;
    }
    const auto& t_pre_process1 = std::chrono::steady_clock::now();

    /*** Inference ***/
    const auto& t_inference0 = std::chrono::steady_clock::now();
    if (inference_helper_->Process(output_tensor_info_list_) != InferenceHelper::kRetOk) {
        return kRetErr;
    }
    const auto& t_inference1 = std::chrono::steady_clock::now();

    /*** PostProcess ***/
    const auto& t_post_process0 = std::chrono::steady_clock::now();
    float* raw_age = output_tensor_info_list_[0].GetDataAsFloat();
    float* raw_gender = output_tensor_info_list_[1].GetDataAsFloat();
    result.age = static_cast<int32_t>(raw_age[0] * 100);
    if (raw_gender[0] > raw_gender[1] && raw_gender[0] > threshold_fender_) {
        result.gender = kGenderFemale;
        result.gender_str = "Female";
    }
    if (raw_gender[1] > raw_gender[0] && raw_gender[1] > threshold_fender_) {
        result.gender = kGenderMale;
        result.gender_str = "Male";
    }
    const auto& t_post_process1 = std::chrono::steady_clock::now();

    result.time_pre_process = static_cast<std::chrono::duration<double>>(t_pre_process1 - t_pre_process0).count() * 1000.0;
    result.time_inference = static_cast<std::chrono::duration<double>>(t_inference1 - t_inference0).count() * 1000.0;
    result.time_post_process = static_cast<std::chrono::duration<double>>(t_post_process1 - t_post_process0).count() * 1000.0;;

    return kRetOk;
}
