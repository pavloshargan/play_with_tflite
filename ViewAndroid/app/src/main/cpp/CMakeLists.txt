# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.19)
set(CMAKE_CXX_STANDARD 17)

# Quick debug: print whether QNN_SDK was passed by Gradle
if(NOT DEFINED QNN_SDK)
    message(WARNING "[native-lib CMake] QNN_SDK is NOT DEFINED")
else()
    message(STATUS "[native-lib CMake] QNN_SDK = ${QNN_SDK}")
endif()

#↓↓↓ 追加 ↓↓↓
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${PROJECT_SOURCE_DIR}/jniLibs/${ANDROID_ABI})
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 ")
#↑↑↑ 追加 ↑↑↑

#↓↓↓ 追加 (https://github.com/Tencent/ncnn/issues/976) ↓↓↓
# openmp
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fopenmp")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fopenmp")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -fopenmp")

if(DEFINED ANDROID_NDK_MAJOR AND ${ANDROID_NDK_MAJOR} GREATER 20)
    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -static-openmp")
endif()

add_library(
        native-lib
        SHARED
        native-lib.cpp )

find_library(
        log-lib
        log )

target_link_libraries(
        native-lib
        ${log-lib} )

# ↓↓↓ 追加 ↓↓↓
### For OpenCV
set(OpenCV_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../OpenCV-android-sdk/sdk/native/jni")
set(thirdparty_libs "${CMAKE_SOURCE_DIR}/../../../../OpenCV-android-sdk/sdk/native/3rdparty/libs")
set(include_dir "${CMAKE_SOURCE_DIR}/../../../../OpenCV-android-sdk/sdk/native/jni/include")

find_package(OpenCV REQUIRED)

target_include_directories(native-lib PUBLIC ${OpenCV_INCLUDE_DIRS})
target_link_libraries(native-lib ${OpenCV_LIBS})


### For my module
# Set TensorflowLite delegate options
set(INFERENCE_HELPER_ENABLE_OPENCV                  OFF CACHE BOOL "OPENCV" FORCE)
set(INFERENCE_HELPER_ENABLE_TFLITE_DELEGATE_XNNPACK ON CACHE BOOL "TFLITE_DELEGATE_XNNPACK" FORCE)
set(INFERENCE_HELPER_ENABLE_TFLITE_DELEGATE_GPU     ON  CACHE BOOL "TFLITE_DELEGATE_GPU" FORCE)
set(INFERENCE_HELPER_ENABLE_TFLITE_DELEGATE_EDGETPU OFF CACHE BOOL "TFLITE_DELEGATE_EDGETPU" FORCE)
set(INFERENCE_HELPER_ENABLE_TFLITE_DELEGATE_NNAPI   ON  CACHE BOOL "TFLITE_DELEGATE_XNNPACK" FORCE)
# Set INFERENCE_HELPER_ENABLE_TFLITE_DELEGATE_QNN to OFF if building for non-qualcomm device.
# Otherwise you'll get runtime errors
set(INFERENCE_HELPER_ENABLE_TFLITE_DELEGATE_QNN ON CACHE BOOL "TFLITE_DELEGATE_QNN" FORCE)

# You can change pj_tflite_det_yolov5 to any project from play_with_tflite directory to try different examples
# If you change the project, put the corresponding model from play_with_tflite/resource/model into the app/main/src/assets
set(ImageProcessor_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../../pj_tflite_det_yolov5/image_processor")
message(${ImageProcessor_DIR})

add_subdirectory(${ImageProcessor_DIR} ImageProcessor)
target_include_directories(native-lib PUBLIC ${ImageProcessor_DIR})
target_link_libraries(native-lib
        ImageProcessor
        z                       # Compression library
        android                 # Links with the Android NDK android library
        mediandk                # Links with the Android NDK media library
        camera2ndk)
# Link libippicv and libippiw only if x86 or x86_64
if("${ANDROID_ABI}" STREQUAL "x86" OR "${ANDROID_ABI}" STREQUAL "x86_64")
    target_link_libraries(
            native-lib
            libippicv
            libippiw
    )
endif()