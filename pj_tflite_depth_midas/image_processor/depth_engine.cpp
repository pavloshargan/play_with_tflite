/* Copyright 2020 iwatake2222

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
#include "depth_engine.h"

/*** Macro ***/
#define TAG "DepthEngine"
#define PRINT(...)   COMMON_HELPER_PRINT(TAG, __VA_ARGS__)
#define PRINT_E(...) COMMON_HELPER_PRINT_E(TAG, __VA_ARGS__)

/* Model parameters */
#define MODEL_TYPE_TFLITE
//#define MODEL_TYPE_ONNX

#if defined(MODEL_TYPE_TFLITE)
#if 1
#define MODEL_NAME  "lite-model_midas_v2_1_small_1_lite_1.tflite"
#define INPUT_NAME  "Const"
#define INPUT_DIMS  { 1, 256, 256, 3 }
#define IS_NCHW     false
#define IS_RGB      true
#define OUTPUT_NAME "midas_net_custom/sequential/re_lu_9/Relu"
#define TENSORTYPE  TensorInfo::kTensorTypeFp32
#else
/* https://github.com/PINTO0309/PINTO_model_zoo/blob/main/081_MiDaS_v2/download_256x256.sh */
#define MODEL_NAME  "midasv2_small_256x256.tflite"
#define INPUT_NAME  "0"
#define INPUT_DIMS  { 1, 256, 256, 3 }
#define IS_NCHW     false
#define IS_RGB      true
#define OUTPUT_NAME "Identity"
#define TENSORTYPE  TensorInfo::kTensorTypeFp32
#endif
#elif defined(MODEL_TYPE_ONNX)
#define MODEL_NAME  "midasv2_small_256x256.onnx"
#define INPUT_NAME  "inputs:0"
#define INPUT_DIMS  { 1, 256, 256, 3 }
#define IS_NCHW     false
#define IS_RGB      true
#define OUTPUT_NAME "Identity:0"
#define TENSORTYPE  TensorInfo::kTensorTypeFp32
#endif

/*** Function ***/
int32_t DepthEngine::Initialize(const std::string& work_dir, const int32_t num_threads)
{
    /* Set model information */
    std::string model_filename = work_dir + "/model/" + MODEL_NAME;

    /* Set input tensor info */
    input_tensor_info_list_.clear();
    InputTensorInfo input_tensor_info(INPUT_NAME, TENSORTYPE, IS_NCHW);
    input_tensor_info.tensor_dims = INPUT_DIMS;
    input_tensor_info.data_type = InputTensorInfo::kDataTypeImage;
    input_tensor_info.normalize.mean[0] = 0.485f;   	/* https://github.com/onnx/models/tree/master/vision/classification/mobilenet#preprocessing */
    input_tensor_info.normalize.mean[1] = 0.456f;
    input_tensor_info.normalize.mean[2] = 0.406f;
    input_tensor_info.normalize.norm[0] = 0.229f;
    input_tensor_info.normalize.norm[1] = 0.224f;
    input_tensor_info.normalize.norm[2] = 0.225f;
    input_tensor_info_list_.push_back(input_tensor_info);

    /* Set output tensor info */
    output_tensor_info_list_.clear();
    output_tensor_info_list_.push_back(OutputTensorInfo(OUTPUT_NAME, TENSORTYPE));

    /* Create and Initialize Inference Helper */
#if defined(MODEL_TYPE_TFLITE)
    //inference_helper_.reset(InferenceHelper::Create(InferenceHelper::kTensorflowLite));
//    inference_helper_.reset(InferenceHelper::Create(InferenceHelper::kTensorflowLiteXnnpack));
    inference_helper_.reset(InferenceHelper::Create(InferenceHelper::kTensorflowLiteQnn));
    //inference_helper_.reset(InferenceHelper::Create(InferenceHelper::kTensorflowLiteGpu));
    //inference_helper_.reset(InferenceHelper::Create(InferenceHelper::kTensorflowLiteEdgetpu));
    //inference_helper_.reset(InferenceHelper::Create(InferenceHelper::kTensorflowLiteNnapi));
#elif defined(MODEL_TYPE_ONNX)
    //inference_helper_.reset(InferenceHelper::Create(InferenceHelper::kOpencv));
    inference_helper_.reset(InferenceHelper::Create(InferenceHelper::kTensorrt));
#endif

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

int32_t DepthEngine::Finalize()
{
    if (!inference_helper_) {
        PRINT_E("Inference helper is not created\n");
        return kRetErr;
    }
    inference_helper_->Finalize();
    return kRetOk;
}


int32_t DepthEngine::Process(const cv::Mat& original_mat, Result& result)
{
    if (!inference_helper_) {
        PRINT_E("Inference helper is not created\n");
        return kRetErr;
    }
    /*** PreProcess ***/
    const auto& t_pre_process0 = std::chrono::steady_clock::now();
    InputTensorInfo& input_tensor_info = input_tensor_info_list_[0];
    /* do resize and color conversion here because some inference engine doesn't support these operations */
    int32_t crop_x = 0;
    int32_t crop_y = 0;
    int32_t crop_w = original_mat.cols;
    int32_t crop_h = original_mat.rows;
    cv::Mat img_src = cv::Mat::zeros(input_tensor_info.GetHeight(), input_tensor_info.GetWidth(), CV_8UC3);
    //CommonHelper::CropResizeCvt(original_mat, img_src, crop_x, crop_y, crop_w, crop_h, IS_RGB, CommonHelper::kCropTypeStretch);
    //CommonHelper::CropResizeCvt(original_mat, img_src, crop_x, crop_y, crop_w, crop_h, IS_RGB, CommonHelper::kCropTypeCut);
    CommonHelper::CropResizeCvt(original_mat, img_src, crop_x, crop_y, crop_w, crop_h, IS_RGB, CommonHelper::kCropTypeExpand);

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
    /* Retrieve the result */
    int32_t output_height = output_tensor_info_list_[0].tensor_dims[1];
    int32_t output_width = output_tensor_info_list_[0].tensor_dims[2];
    int32_t output_channel = output_tensor_info_list_[0].tensor_dims[3];
    float* values = output_tensor_info_list_[0].GetDataAsFloat();
    //printf("%f, %f, %f\n", values[0], values[100], values[400]);
    cv::Mat mat_out = cv::Mat(output_height, output_width, CV_32FC1, values);  /* value has no specific range */
    
    /* (255 * (prediction - depth_min) / (depth_max - depth_min)) */
    double depth_min, depth_max;
    cv::minMaxLoc(mat_out, &depth_min, &depth_max);
    mat_out.convertTo(mat_out, CV_8UC1, 255. / (depth_max - depth_min), (-255. * depth_min) / (depth_max - depth_min));
    const auto& t_post_process1 = std::chrono::steady_clock::now();

    /* Return the results */
    result.mat_out = mat_out;
    result.time_pre_process = static_cast<std::chrono::duration<double>>(t_pre_process1 - t_pre_process0).count() * 1000.0;
    result.time_inference = static_cast<std::chrono::duration<double>>(t_inference1 - t_inference0).count() * 1000.0;
    result.time_post_process = static_cast<std::chrono::duration<double>>(t_post_process1 - t_post_process0).count() * 1000.0;;

    return kRetOk;
}

