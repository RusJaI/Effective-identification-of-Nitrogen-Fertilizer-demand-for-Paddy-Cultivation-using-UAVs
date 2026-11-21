package com.example.smart_rice_care;

import android.content.Context;
import android.util.Log;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import java.io.File;
import java.util.concurrent.Executor;

import com.google.common.util.concurrent.ListenableFuture;

/** Lifecycle-bound CameraX wrapper shared by online and offline capture flows. */
public final class CameraCaptureController {
    public interface CaptureCallback {
        void onSaved(File file);
        void onError(ImageCaptureException exception);
    }

    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private final PreviewView previewView;
    private final Executor mainExecutor;
    private ImageCapture imageCapture;

    public CameraCaptureController(Context context, LifecycleOwner lifecycleOwner, PreviewView previewView) {
        this.context = context;
        this.lifecycleOwner = lifecycleOwner;
        this.previewView = previewView;
        this.mainExecutor = ContextCompat.getMainExecutor(context);
    }

    public void start() {
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(context);
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setTargetRotation(previewView.getDisplay().getRotation())
                        .build();
                provider.unbindAll();
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);
            } catch (Exception e) {
                Log.e("CameraCapture", "Unable to start camera", e);
            }
        }, mainExecutor);
    }

    public void capture(File outputFile, CaptureCallback callback) {
        if (imageCapture == null) {
            callback.onError(new ImageCaptureException(ImageCapture.ERROR_CAMERA_CLOSED, "Camera is not ready", null));
            return;
        }
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(outputFile).build();
        imageCapture.takePicture(options, mainExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(ImageCapture.OutputFileResults result) {
                callback.onSaved(outputFile);
            }

            @Override
            public void onError(ImageCaptureException exception) {
                callback.onError(exception);
            }
        });
    }
}
