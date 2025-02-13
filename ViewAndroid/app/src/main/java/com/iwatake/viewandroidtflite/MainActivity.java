package com.iwatake.viewandroidtflite;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Formatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    /*** Fixed values ***/
    private static final String TAG = "MyApp";
    private int REQUEST_CODE_FOR_PERMISSIONS = 1234;;
    private final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA", "android.permission.WRITE_EXTERNAL_STORAGE"};
    private enum AppStatus {
        NotInitialized,
        Initialized,
        Running,
    };

    private enum ViewMode {
        Normal,
        BeforeAfter,
        Vr,
    };

    /*** Views ***/
    private PreviewView previewView;
    private ImageView imageView;
    private ImageView imageView2;
    private Button buttonCamera;
    private Button buttonBeforeAfter;
    private Button buttonVr;
    private Button buttonVrExit;
    private Button buttonCmd0;
    private Button buttonCmd1;
    private Button buttonCmd2;
    private TextView textViewFps;
    private TextView textViewImageProcessTime;

    /*** For CameraX ***/
    private Camera camera = null;
    private Preview preview = null;
    private ImageAnalysis imageAnalysis = null;
    private ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();

    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private AppStatus appStatus = AppStatus.NotInitialized;
    private ViewMode viewMode = ViewMode.Normal;

    private void copyResourceFolderToDocuments() {
        File documentsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (documentsDir == null) {
            Log.e(TAG, "External Documents directory is not available.");
            return;
        }
        // Destination = Documents/resource
        File resourceDir = new File(documentsDir, "resource");
        if (!resourceDir.exists()) {
            resourceDir.mkdirs();
        }

        try {
            copyAssetFolder("resource", resourceDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Recursively copy a folder from the APK assets to the specified output folder.
     * @param assetPath  folder name in assets (e.g. "resource")
     * @param outDir     destination directory on the file system
     */
    private void copyAssetFolder(String assetPath, File outDir) throws IOException {
        AssetManager assetManager = getAssets();
        String[] items = assetManager.list(assetPath);
        if (items == null) return;

        for (String item : items) {
            String fullAssetPath = assetPath + "/" + item;
            File outFile = new File(outDir, item);

            // Check if it's a folder by attempting to list its contents
            String[] subItems = assetManager.list(fullAssetPath);
            if (subItems != null && subItems.length > 0) {
                // It's a directory
                if (!outFile.exists()) {
                    outFile.mkdirs();
                }
                copyAssetFolder(fullAssetPath, outFile);
            } else {
                // It's a file
                copyAssetFile(fullAssetPath, outFile);
            }
        }
    }

    /**
     * Copy a single file from the APK assets to the given output file.
     */
    private void copyAssetFile(String assetPath, File outFile) throws IOException {
        AssetManager assetManager = getAssets();
        try (
                InputStream in = assetManager.open(assetPath);
                FileOutputStream out = new FileOutputStream(outFile)
        ) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    static {
        System.loadLibrary("native-lib");
        System.loadLibrary("opencv_java4");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        getViews();
        setEventListeners();
        exitVrMode();

        // Get the actual extracted .so directory on device
        String nativeLibDir = getApplicationInfo().nativeLibraryDir;
        Log.i(TAG, "nativeLibDir = " + nativeLibDir);
        // Pass it to native code
        setQnnSkelLibraryDir(nativeLibDir);

        copyResourceFolderToDocuments();
        // create data directory to save resource and model files
        File imageStorageDir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "child");

        if (checkPermissions()) {
            if (appStatus == AppStatus.NotInitialized) {
                if (ImageProcessorInitialize() == 0) {
                    ImageProcessorCommand(0);
                    appStatus = AppStatus.Initialized;
                } else {
                    Log.i(TAG, "[onCreate2] Failed to ImageProcessorInitialize");

                }
            }
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_FOR_PERMISSIONS);
        }
    }

    private void getViews() {
        previewView = findViewById(R.id.previewView);
        imageView = findViewById(R.id.imageView);
        imageView2 = findViewById(R.id.imageView2);
        buttonCamera = findViewById(R.id.buttonCamera);
        buttonBeforeAfter = findViewById(R.id.buttonBeforeAfter);
        buttonVr = findViewById(R.id.buttonVr);
        buttonVrExit = findViewById(R.id.buttonVrExit);
        buttonCmd0 = findViewById(R.id.buttonCmd0);
        buttonCmd1 = findViewById(R.id.buttonCmd1);
        buttonCmd2 = findViewById(R.id.buttonCmd2);
        textViewFps = findViewById(R.id.textViewFps);
        textViewImageProcessTime = findViewById(R.id.textViewImageProcessTime);
    }

    private void setEventListeners() {
        buttonCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    lensFacing = CameraSelector.LENS_FACING_FRONT;
                    buttonCamera.setText("BACK");
                } else {
                    lensFacing = CameraSelector.LENS_FACING_BACK;
                    buttonCamera.setText("FRONT");
                }
                startCamera();
            }
        });

        buttonCmd0.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ImageProcessorCommand((0));
            }
        });

        buttonCmd1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ImageProcessorCommand((1));
            }
        });

        buttonCmd2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ImageProcessorCommand((2));
            }
        });

        buttonBeforeAfter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                switchBeforeAfterViewMode();
            }
        });

        buttonVr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                enterVrMode();
            }
        });

        buttonVrExit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                exitVrMode();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (appStatus == AppStatus.Initialized) {
            appStatus = AppStatus.NotInitialized;
            ImageProcessorFinalize();
        }
    }

    private void switchBeforeAfterViewMode() {
        if (viewMode != ViewMode.BeforeAfter) {
            /* OFF -> ON */
            viewMode = ViewMode.BeforeAfter;
            imageView2.setVisibility(View.VISIBLE);
            imageView2.setLayoutParams(new TableRow.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        } else {
            /* ON -> OFF */
            viewMode = ViewMode.Normal;
            imageView2.setVisibility(View.INVISIBLE);
            imageView2.setLayoutParams(new TableRow.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f));
        }
    }

    private void enterVrMode() {
        viewMode = ViewMode.Vr;
        imageView2.setVisibility(View.VISIBLE);
        imageView2.setLayoutParams(new TableRow.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        buttonCamera.setVisibility(View.INVISIBLE);
        buttonBeforeAfter.setVisibility(View.INVISIBLE);
        buttonVr.setVisibility(View.INVISIBLE);
        buttonCmd0.setVisibility(View.INVISIBLE);
        buttonCmd1.setVisibility(View.INVISIBLE);
        buttonCmd2.setVisibility(View.INVISIBLE);
        textViewFps.setVisibility(View.INVISIBLE);
        textViewImageProcessTime.setVisibility(View.INVISIBLE);
        buttonVrExit.setVisibility(View.VISIBLE);
    }

    private void exitVrMode() {
        viewMode = ViewMode.Normal;
        imageView2.setVisibility(View.INVISIBLE);
        imageView2.setLayoutParams(new TableRow.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f));
        buttonCamera.setVisibility(View.VISIBLE);
        buttonBeforeAfter.setVisibility(View.VISIBLE);
        buttonVr.setVisibility(View.VISIBLE);
        buttonCmd0.setVisibility(View.VISIBLE);
//        buttonCmd1.setVisibility(View.VISIBLE);
//        buttonCmd2.setVisibility(View.VISIBLE);
        textViewFps.setVisibility(View.VISIBLE);
        textViewImageProcessTime.setVisibility(View.VISIBLE);
        buttonVrExit.setVisibility(View.INVISIBLE);
    }

    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        Context context = this;
        cameraProviderFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
//                    preview = new Preview.Builder().build();
                    imageAnalysis = new ImageAnalysis.Builder().build();
                    imageAnalysis.setAnalyzer(cameraExecutor, new MyImageAnalyzer());
                    CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();

                    cameraProvider.unbindAll();
//                    camera = cameraProvider.bindToLifecycle((LifecycleOwner)context, cameraSelector, preview, imageAnalysis);
                    camera = cameraProvider.bindToLifecycle((LifecycleOwner)context, cameraSelector,  imageAnalysis);
//                    preview.setSurfaceProvider(previewView.createSurfaceProvider(camera.getCameraInfo()));
                } catch(Exception e) {
                    Log.e(TAG, "[startCamera] Use case binding failed", e);
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private class MyImageAnalyzer implements ImageAnalysis.Analyzer {
        private long previousTime = System.nanoTime();
        private float averageFPS = 0;
        private long averageImageProcessTime = 0;
        private int frameCount = 0;

        @Override
        public void analyze(@NonNull ImageProxy image) {
            if (previewView.getDisplay() == null || appStatus != AppStatus.Initialized || image.getWidth() == 0) {
                image.close();
                return;
            }
            /* Create cv::mat(RGB888) from image(NV21) */
            Mat matOrg = getMatFromImage(image);
            /* Fix image rotation (it looks image in PreviewView is automatically fixed by CameraX???) */
            Mat mat = fixMatRotation(matOrg);
//            Log.i(TAG, "[analyze] width = " + image.getWidth() + ", height = " + image.getHeight() + "Rotation = " + previewView.getDisplay().getRotation());
//            Log.i(TAG, "[analyze] mat width = " + matOrg.cols() + ", mat height = " + matOrg.rows());

            /* store the original image */
            Bitmap bitmap_src;
            if (viewMode == ViewMode.BeforeAfter) {
                bitmap_src = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
                Utils.matToBitmap(mat, bitmap_src);
            } else {
                bitmap_src = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888); /* dummy */
            }

            /* Do some image processing */
            appStatus = AppStatus.Running;
            long imageProcessTimeStart = System.nanoTime();
            ImageProcessorProcess(mat.getNativeObjAddr());
            long imageProcessTimeEnd = System.nanoTime();
            appStatus = AppStatus.Initialized;
            Mat matOutput = mat;
//            Mat matOutput = new Mat(mat.rows(), mat.cols(), mat.type());
//            if (matPrevious == null) matPrevious = mat;
//            Core.absdiff(mat, matPrevious, matOutput);
//            matPrevious = mat;

            /* Calculate FPS */
            long currentTime = System.nanoTime();
            float fps = 1000000000 / (currentTime - previousTime);
            previousTime = currentTime;
            frameCount++;
            averageFPS = (averageFPS * (frameCount - 1) + fps) / frameCount;
            Formatter fmFps = new Formatter();
            fmFps.format("%4.1f (%4.1f) [FPS]", averageFPS, fps);

            long imageProcessTime = imageProcessTimeEnd - imageProcessTimeStart;
            averageImageProcessTime = (long)((averageImageProcessTime * (frameCount - 1) + imageProcessTime) / frameCount);
            Formatter fmImageProcessTime = new Formatter();
            fmImageProcessTime.format("%d (%d) [msec]", averageImageProcessTime/1000000, imageProcessTime/1000000);

            /* Convert cv::mat to bitmap for drawing */
            Bitmap bitmap = Bitmap.createBitmap(matOutput.cols(), matOutput.rows(),Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(matOutput, bitmap);

            /* Display the result onto ImageView */
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    imageView.setImageBitmap(bitmap);
                    if (viewMode == ViewMode.Vr) {
                        imageView2.setImageBitmap(bitmap);
                    } else if (viewMode == ViewMode.BeforeAfter) {
                        imageView2.setImageBitmap(bitmap_src);
                    }
                    textViewFps.setText(fmFps.toString());
                    textViewImageProcessTime.setText(fmImageProcessTime.toString());
                }
            });

            /* Close the image otherwise, this function is not called next time */
            image.close();
        }

        private Mat getMatFromImage(ImageProxy image) {
            /* https://stackoverflow.com/questions/30510928/convert-android-camera2-api-yuv-420-888-to-rgb */
            ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
            ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
            ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();
            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();
            byte[] nv21 = new byte[ySize + uSize + vSize];
            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);
            Mat yuv = new Mat(image.getHeight() + image.getHeight() / 2, image.getWidth(), CvType.CV_8UC1);
            yuv.put(0, 0, nv21);
            Mat mat = new Mat();
            Imgproc.cvtColor(yuv, mat, Imgproc.COLOR_YUV2RGB_NV21, 3);
            return mat;
        }

        private Mat fixMatRotation(Mat matOrg) {
            Mat mat;
            switch (previewView.getDisplay().getRotation()){
                default:
                case Surface.ROTATION_0:
                    mat = new Mat(matOrg.cols(), matOrg.rows(), matOrg.type());
                    Core.transpose(matOrg, mat);
                    if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        Core.flip(mat, mat, 1);
                    } else {
                        Core.flip(mat, mat, 0);
                    }
                    break;
                case Surface.ROTATION_90:
                    mat = matOrg;
                    break;
                case Surface.ROTATION_270:
                    mat = matOrg;
                    Core.flip(mat, mat, -1);
                    break;
            }

            return mat;
        }
    }

    private boolean checkPermissions(){
        for(String permission : REQUIRED_PERMISSIONS){
            if(ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED){
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == REQUEST_CODE_FOR_PERMISSIONS){
            if(checkPermissions()){
                if (ImageProcessorInitialize() == 0) {
                    ImageProcessorCommand(0);
                    appStatus = AppStatus.Initialized;
                    startCamera();
                } else {
                    Log.i(TAG, "[onRequestPermissionsResult2] Failed to ImageProcessorInitialize");
                    this.finish();
                }
            } else{
                Log.i(TAG, "[onRequestPermissionsResult2] Failed to get permissions");
                this.finish();
            }
        }
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
    public native void setQnnSkelLibraryDir(String skelDir);
    public native int ImageProcessorInitialize();
    public native int ImageProcessorProcess(long objMat);
    public native int ImageProcessorFinalize();
    public native int ImageProcessorCommand(int cmd);
}