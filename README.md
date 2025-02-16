# play_with_tflite Android

A C++ library for accelerated model inference using Qnn/GPU/XNNPack TFLite delegates, integrated with OpenCV 4.10 for image processing, and providing Java bindings for real-time camera preview and result visualization.

This is a fork iwatake2222/play_with_tflite with added QNN Tflite delegate

Instructions to build the Android app:

Note: One of the pre-built libraries provided was built by the author of the original repository (TensorFlow Lite library). Other dependencies are downloaded from official sources.

```
git clone https://github.com/pavloshargan/play_with_tflite
git submodule update --init --recursive
cd InferenceHelper/third_party
sh download_prebuilt_libraries.sh

```

download models:
```
sh download_resource.sh
```

Then copy the downloaded resource directory to ViewAndroid/app/src/main/assets/

Open the ViewAndroid project in Android Studio and run the app



Tested on Mac OS with latest Android Studio 2024 Ladybug
