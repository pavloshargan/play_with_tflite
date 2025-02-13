# play_with_tflite Android

A native C++ library for accelerated model inference using Qnn/GPU/XNNPack TFLite delegates, integrated with OpenCV 4.10 for image processing, and providing Java bindings for real-time camera preview and result visualization.

This is a fork iwatake2222/play_with_tflite with added QNN Tflite delegate

To clone build the android app:

```
git clone https://github.com/pavloshargan/play_with_tflite
git submodule update --init --recursive
cd InferenceHelper/third_party
sh download_prebuilt_libraries.sh
```

Then open the ViewAndroid project in Android Studio and run the app


Tested on Mac OS with latest Android Studio 2024 Ladybug
