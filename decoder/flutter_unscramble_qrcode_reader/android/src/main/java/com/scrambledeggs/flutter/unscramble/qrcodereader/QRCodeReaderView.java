package com.scrambledeggs.flutter.unscramble.qrcodereader;


import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.client.android.camera.CameraManager;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;

import java.io.IOException;
import java.util.Map;

import static android.hardware.Camera.getCameraInfo;

public class QRCodeReaderView extends SurfaceView
        implements SurfaceHolder.Callback, Camera.PreviewCallback {

    QRCodeMultiReader multiReader;

    private OnQRCodeReadListener mOnQRCodeReadListener;
    private CameraManager mCameraManager;
    private Map<DecodeHintType, Object> decodeHints;

    public QRCodeReaderView(Context context) {
        this(context, null);
    }

    public QRCodeReaderView(Context context, AttributeSet attrs) {
        super(context, attrs);

        if (isInEditMode()) {
            return;
        }

        if (checkCameraHardware()) {
            mCameraManager = new CameraManager(getContext());
            mCameraManager.setPreviewCallback(this);
            getHolder().addCallback(this);
            setBackCamera();
        } else {
            throw new RuntimeException("Error: Camera not found");
        }
    }

    public void setOnQRCodeReadListener(OnQRCodeReadListener onQRCodeReadListener) {
        mOnQRCodeReadListener = onQRCodeReadListener;
    }

    public void setDecodeHints(Map<DecodeHintType, Object> decodeHints) {
        this.decodeHints = decodeHints;
    }

    public void startCamera() {
        mCameraManager.startPreview();
    }

    public void stopCamera() {
        mCameraManager.stopPreview();
    }

    public void setAutofocusInterval(long autofocusIntervalInMs) {
        if (mCameraManager != null) {
            mCameraManager.setAutofocusInterval(autofocusIntervalInMs);
        }
    }

    public void forceAutoFocus() {
        if (mCameraManager != null) {
            mCameraManager.forceAutoFocus();
        }
    }

    public void setTorchEnabled(boolean enabled) {
        if (mCameraManager != null) {
            mCameraManager.setTorchEnabled(enabled);
        }
    }

    public void setPreviewCameraId(int cameraId) {
        mCameraManager.setPreviewCameraId(cameraId);
    }

    public void setBackCamera() {
        setPreviewCameraId(Camera.CameraInfo.CAMERA_FACING_BACK);
    }

    public void setFrontCamera() {
        setPreviewCameraId(Camera.CameraInfo.CAMERA_FACING_FRONT);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            // Indicate camera, our View dimensions
            mCameraManager.openDriver(holder, this.getWidth(), this.getHeight());
        } catch (IOException | RuntimeException e) {
            mCameraManager.closeDriver();
        }

        try {
            multiReader = new QRCodeMultiReader();
            mCameraManager.startPreview();
        } catch (Exception e) {
            mCameraManager.closeDriver();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

        if (holder.getSurface() == null) {
            return;
        }

        if (mCameraManager.getPreviewSize() == null) {
            return;
        }

        mCameraManager.stopPreview();

        mCameraManager.setPreviewCallback(this);
        mCameraManager.setDisplayOrientation(getCameraDisplayOrientation());

        mCameraManager.startPreview();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mCameraManager.setPreviewCallback(null);
        mCameraManager.stopPreview();
        mCameraManager.closeDriver();
    }

    // Called when camera take a frame
    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        Camera.Size size = camera.getParameters().getPreviewSize();
        final PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                data, size.width, size.height, 0, 0, size.width, size.height, false);
        final BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

        try {
            Result[] results = multiReader.decodeMultiple(bitmap, decodeHints);
            this.mOnQRCodeReadListener.onQRCodeRead(results);
        } catch (NotFoundException e) {
        }
    }

    private boolean checkCameraHardware() {
        if (getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)) {
            return true;
        } else if (getContext().getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)) {
            return true;
        } else {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                    && getContext().getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_CAMERA_ANY);
        }
    }

    @SuppressWarnings("deprecation")
    private int getCameraDisplayOrientation() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        getCameraInfo(mCameraManager.getPreviewCameraId(), info);
        WindowManager windowManager =
                (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        int rotation = windowManager.getDefaultDisplay().getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
            default:
                break;
        }

        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }

    public interface OnQRCodeReadListener {
        void onQRCodeRead(Result[] codes);
    }
}
