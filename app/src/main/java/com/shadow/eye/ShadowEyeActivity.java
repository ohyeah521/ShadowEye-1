package com.shadow.eye;

import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.PreviewCallback;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ShadowEyeActivity extends Activity {
    private static final int NULL_MODE = 0, PHOTO_MODE = 1;
    private static final int CAMERA_PREVIEW_WIDTH = 500, CAMERA_PREVIEW_HEIGHT = 500;
    private final SurfaceTexture mSurfaceTexture = new SurfaceTexture(0);
    private WakeLock mWakeLock = null;
    private AudioManager mAudioManager;
    private Camera mCamera = null;
    private final AutoFocusCallback mAutoFocusCallback = new AutoFocusCallback() {
        @Override
        public void onAutoFocus(boolean success, Camera camera) {
            if (success) {
                try {
                    camera.takePicture(null, null, mPictureCallback);
                } catch (Exception e) {
                    closeCamera();
                }
            } else {
                mCamera.autoFocus(mAutoFocusCallback);
            }
        }
    };
    private Vibrator mVibrator = null;
    private String mCurrentPath;
    private long mLastBackPress = 0;
    private int mCameraStatue = NULL_MODE;
    private boolean mBackCamera = true;
    private final PreviewCallback mPreviewCallback = new PreviewCallback() {
        final int autoFocusLightThreshold = 16;
        double lastLight = 100000;
        long stableTime = 0;
        boolean isFocused = false;

        public void onPreviewFrame(byte[] data, Camera camera) {
            try {
                double light = getYUV420SPLight(data, CAMERA_PREVIEW_WIDTH, CAMERA_PREVIEW_HEIGHT);
                long currentTime = SystemClock.elapsedRealtime();
                // Light Change
                if (Math.abs(lastLight - light) > autoFocusLightThreshold) {
                    lastLight = light;
                    stableTime = currentTime;
                    isFocused = false;
                } else if (!isFocused) { // If Change Little ,Focus Camera
                    if (currentTime - stableTime > 500) {
                        mVibrator.vibrate(50);
                        mCamera.setPreviewCallback(null);
                        if (mBackCamera) {
                            camera.autoFocus(mAutoFocusCallback);
                        } else {
                            try {
                                camera.takePicture(null, null, mPictureCallback);
                            } catch (Exception e) {
                                closeCamera();
                            }
                        }
                        isFocused = true;
                    }
                }

            } catch (Exception e) {
                closeCamera();
            }
        }
    };
    private final PictureCallback mPictureCallback = new PictureCallback() {
        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            long l[] = {0, 100, 100, 100};
            mVibrator.vibrate(l, -1);
            savePicture(data);
            mCamera.startPreview();
            mCamera.setPreviewCallback(mPreviewCallback);
        }
    };
    private long mLastVolumeKeyPressTime = 0;

    /**
     * 通过YUV420SP数组 计算出中心区域亮度
     *
     * @param yuv420sp 图像帧数组
     * @param width    宽度
     * @param height   高度
     * @return 返回亮度, 这亮度值始终大于0
     */
    public static double getYUV420SPLight(byte[] yuv420sp, int width,
                                          int height) {
        double bright = 0;
        int total = 0;
        final int xEdge = width / 3, yEdge = height / 3, focusWidth = width - xEdge * 2, focusHeight = height - yEdge * 2;
        final int dt = 32, dx = (focusWidth <= dt ? 1 : focusWidth / dt), dy = (focusHeight <= dt ? 1 : focusHeight / dt);
        for (int j = yEdge; j < height - yEdge; j += dy) {
            for (int i = xEdge; i < width - xEdge; i += dx, ++total) {
                bright += (0xff & ((int) yuv420sp[j * width + i]));
            }
        }
        return 0 == total ? 0 : (bright / total);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        setContentView(R.layout.activity_main);
        setBrightness(1);
        acquireWakeLock();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
        closeCamera();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 获取手机当前音量值
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                handleKeyPress(false);
                mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER, 0);
                mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE, 0);
                return true;

            case KeyEvent.KEYCODE_VOLUME_UP:
                handleKeyPress(true);
                mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER, 0);
                mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE, 0);
                return true;
            case KeyEvent.KEYCODE_HOME:
                return true;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        long currentTime = SystemClock.elapsedRealtime();
        if (currentTime - mLastBackPress > 500) {
            mLastBackPress = currentTime;
        } else {
            super.onBackPressed();
        }
    }

    private void savePicture(byte[] data) {
        if (mCurrentPath == null) return;
        String dateString = getDateString();
        String Path = mCurrentPath + "/" + dateString + ".zip";
        new File(mCurrentPath).mkdirs();
        ZipOutputStream zipOutputStream;
        try {
            zipOutputStream = new ZipOutputStream(new FileOutputStream(new File(Path)));
            try {
                ZipEntry zipEntry = new ZipEntry(dateString + ".jpg");
                zipOutputStream.putNextEntry(zipEntry);
                zipOutputStream.write(data);
                zipOutputStream.flush();
                zipOutputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } catch (Exception e1) {
            e1.printStackTrace();
        }
    }

    private String getDateString() {
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.CHINESE).format(new Date());
    }

    private void setBrightness(float brightness) {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        params.screenBrightness = brightness / 255.0f;
        getWindow().setAttributes(params);
    }

    private void handleKeyPress(boolean backCamera) {
        mVibrator.vibrate(50);
        if (mCameraStatue == NULL_MODE) {
            long currentTime = SystemClock.elapsedRealtime();
            if (mLastVolumeKeyPressTime + 3000 < currentTime) {
                mLastVolumeKeyPressTime = currentTime;
                mBackCamera = backCamera;
                return;
            }
            mLastVolumeKeyPressTime = 0;
            if (mCamera == null) {
                startCamera();
            }
        }
        if (mBackCamera == backCamera) {
            if (mCameraStatue == PHOTO_MODE) {
                mCamera.takePicture(null, null, mPictureCallback);
            } else if (mCameraStatue == NULL_MODE) {
                mCameraStatue = PHOTO_MODE;
                mCamera.setPreviewCallback(mPreviewCallback);
            }
        } else {
            closeCamera();
            mCameraStatue = NULL_MODE;
        }
    }

    private void closeCamera() {
        if (mCamera != null) {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    private int findCamera(boolean back) {
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        int cameraCount = Camera.getNumberOfCameras(); // get cameras number
        int value = back ? Camera.CameraInfo.CAMERA_FACING_BACK : Camera.CameraInfo.CAMERA_FACING_FRONT;
        for (int camIdx = 0; camIdx < cameraCount; camIdx++) {
            Camera.getCameraInfo(camIdx, cameraInfo);
            if (cameraInfo.facing == value) {
                return camIdx;
            }
        }
        return -1;
    }

    // Main Func of Catch Camera
    private Camera openCamera(boolean backCamera) {
        int cameraIndex = findCamera(backCamera);
        if (cameraIndex == -1) {
            return null;
        }
        try {
            Camera camera = Camera.open(cameraIndex);
            camera.setDisplayOrientation(90);

            try {
                Parameters p = camera.getParameters();
                if (p != null) {
                    if (backCamera) {
                        p.setFlashMode(Parameters.FLASH_MODE_OFF);
                        p.set("orientation", "portrait");
                        p.setRotation(90);
                    } else {
                        p.set("orientation", "portrait");
                        p.setRotation(270);
                    }
                    p.setPreviewSize(CAMERA_PREVIEW_WIDTH, CAMERA_PREVIEW_HEIGHT);
                    p.setAntibanding(Parameters.ANTIBANDING_60HZ);
                    camera.setParameters(p);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            camera.setPreviewTexture(mSurfaceTexture);
            return camera;
        } catch (Exception e1) {
            return null;
        }
    }

    private void startCamera() {
        mCamera = openCamera(mBackCamera);
        if (mCamera != null) {
            mCurrentPath = Environment.getExternalStorageDirectory() + "/save/" + getDateString();
            mCamera.startPreview();
        }
    }

    private void acquireWakeLock() {
        if (null == mWakeLock) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK
                    | PowerManager.ON_AFTER_RELEASE, "LOCK");
            if (null != mWakeLock) {
                mWakeLock.acquire();
            }
        }
    }

    // 释放设备电源锁
    private void releaseWakeLock() {
        if (null != mWakeLock) {
            mWakeLock.release();
            mWakeLock = null;
        }
    }
}