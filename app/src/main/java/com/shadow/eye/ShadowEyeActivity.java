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
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Calendar;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class DataSaver {
    String mPath = null;
    public DataSaver() {
    }

    String getDateString() {
        Calendar c = Calendar.getInstance();
        String Datestring = "" + c.get(Calendar.YEAR)
                + String.format("%02d", (c.get(Calendar.MONTH) + 1))
                + String.format("%02d", c.get(Calendar.DAY_OF_MONTH))
                + String.format("%02d", c.get(Calendar.HOUR_OF_DAY))
                + String.format("%02d", c.get(Calendar.MINUTE))
                + String.format("%02d", c.get(Calendar.SECOND));
        return Datestring;
    }

    void Open() {
        Close();

        String Datestring = getDateString();

        String DirPath = "/save";
        mPath = Environment.getExternalStorageDirectory() + DirPath;
        mPath += "/" + Datestring;
    }

    void Close()
    {
        mPath = null;
    }

    void SavePicture(byte[] data) {
        if(mPath==null)return;
        String Datestring = getDateString();
        String Path = mPath + "/" + Datestring + ".zip";
        new File(mPath).mkdirs();
        ZipOutputStream mZo;
        try {
            mZo = new ZipOutputStream(new FileOutputStream(new File(Path)));
            try {
                ZipEntry ze = new ZipEntry(Datestring + ".jpg");
                mZo.putNextEntry(ze);
                mZo.write(data);
                mZo.flush();
                mZo.close();
            } catch (IOException e) {
            }
        } catch (Exception e1) {

        }
    }
}

public class ShadowEyeActivity extends Activity {

    final int NULL_MODE = 0, PHOTO_MODE = 1, VIDEO_MODE = 2;
    protected int high = 500;
    protected int wide = 500;
    WakeLock mWakeLock = null;
    AudioManager mAudioManager;
    Camera mCamera = null;
    Vibrator mVibrator = null;
    SurfaceTexture mSt = null;
    DataSaver mDataSaver = new DataSaver();
    AutoFocusCallback mAfc = new AutoFocusCallback() {
        @Override
        public void onAutoFocus(boolean success, Camera camera) {
            if (success) {
                try {
                    camera.takePicture(null, null, mPcb);
                } catch (Exception e) {
                    camera.release();
                    CloseCamera();
                    mDataSaver.Close();
                }
            } else {
                mCamera.autoFocus(mAfc);
            }
        }
    };
    PreviewCallback PreviewCb = new PreviewCallback() {
        public double LastLightValue = 100000;
        long stableTime = 0;
        boolean isFocused = false;
        int AutoFocusLightThreshold = 5;

        public void onPreviewFrame(byte[] data, Camera camera) {
            try {
                int[] rgb = new int[data.length];
                decodeYUV420SP(rgb, data, wide, high);

                double bright = getLight(rgb);
                long currentTime = System.currentTimeMillis();

                // Light Change
                if (LastLightValue - AutoFocusLightThreshold > bright
                        || bright > LastLightValue
                        + AutoFocusLightThreshold) {
                    LastLightValue = bright;
                    stableTime = currentTime;
                    isFocused = false;
                } else if (!isFocused) { // If Change Little ,Focus Camera
                    if (currentTime - stableTime > 500) {
                        mVibrator.vibrate(50);
                        mCamera.setPreviewCallback(null);
                        camera.autoFocus(mAfc);
                        isFocused = true;
                    }
                }

            } catch (Exception e) {
                CloseCamera();
            }
        }
    };
    PictureCallback mPcb = new PictureCallback() {
        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            long l[] = {0, 100, 100, 100};
            mVibrator.vibrate(l, -1);

            mDataSaver.SavePicture(data);
            mCamera.startPreview();
            mCamera.setPreviewCallback(PreviewCb);
            //camera.release();
            //CloseCamera();
        }
    };
    long LastBackPress = 0;
    int mCameraStatue = NULL_MODE;
    boolean mBackCamera = true;
    long lastVolumnKeyPressTime = 0;

    static public void decodeYUV420SP(int[] rgb, byte[] yuv420sp, int width,
                                      int height) {
        final int frameSize = width * height;

        for (int j = 0, yp = 0; j < height; j++) {
            int uvp = frameSize + (j >> 1) * width, u = 0, v = 0;
            for (int i = 0; i < width; i++, yp++) {
                int y = (0xff & ((int) yuv420sp[yp])) - 16;
                if (y < 0)
                    y = 0;
                if ((i & 1) == 0) {
                    v = (0xff & yuv420sp[uvp++]) - 128;
                    u = (0xff & yuv420sp[uvp++]) - 128;
                }

                int y1192 = 1192 * y;
                int r = (y1192 + 1634 * v);
                int g = (y1192 - 833 * v - 400 * u);
                int b = (y1192 + 2066 * u);

                if (r < 0)
                    r = 0;
                else if (r > 262143)
                    r = 262143;
                if (g < 0)
                    g = 0;
                else if (g > 262143)
                    g = 262143;
                if (b < 0)
                    b = 0;
                else if (b > 262143)
                    b = 262143;

                rgb[yp] = 0xff000000 | ((r << 6) & 0xff0000)
                        | ((g >> 2) & 0xff00) | ((b >> 10) & 0xff);
            }
        }
    }

    // @Override
    // public boolean onCreateOptionsMenu(Menu menu) {
    // // Inflate the menu; this adds items to the action bar if it is present.
    // getMenuInflater().inflate(R.menu.main, menu);
    // return true;
    // }

    public double getLight(int rgb[]) {
        int i;
        double bright = 0;
        for (i = 0; i < rgb.length; ++i) {
            int localTemp = rgb[i];
            int r = (localTemp | 0xff00ffff) >> 16 & 0x00ff;
            int g = (localTemp | 0xffff00ff) >> 8 & 0x0000ff;
            int b = (localTemp | 0xffffff00) & 0x0000ff;
            bright = bright + 0.299 * r + 0.587 * g + 0.114 * b;
        }
        return bright / rgb.length;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        requestWindowFeature(Window.FEATURE_NO_TITLE);
        // 去掉Activity上面的状态栏
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        mAudioManager = (AudioManager) this
                .getSystemService(Context.AUDIO_SERVICE);

        // mAudioMode = mAudioManager.getRingerMode();
        // mAudioManager.setRingerMode(AudioManager.RINGER_MODE_SILENT);
        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        setContentView(R.layout.activity_main);
        setBritness(1);
        acquireWakeLock();
    }

    @Override
    protected void onDestroy() {
        releaseWakeLock();
        // mAudioManager.setRingerMode(mAudioMode);
        CloseCamera();
        mDataSaver.Close();

        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - LastBackPress > 500) {
            LastBackPress = currentTime;
        } else {
            super.onBackPressed();
        }
    }

    private void setBritness(float brightness) {

        WindowManager.LayoutParams params = getWindow().getAttributes();

        params.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

        params.screenBrightness = brightness / 255.0f;

        getWindow().setAttributes(params);

    }

    public void controlCamera(boolean BackCamera) {
        mVibrator.vibrate(50);

        if(mCameraStatue == NULL_MODE) {
            long currentTime = System.currentTimeMillis();
            if(lastVolumnKeyPressTime + 3000 < currentTime) {
                lastVolumnKeyPressTime = currentTime;
                mBackCamera = BackCamera;
                return;
            }
            lastVolumnKeyPressTime = 0;
            if (mCamera == null) {
                mCamera = OpenCamera(mBackCamera);
                if (mCamera != null) {// Auto Focus
                    mDataSaver.Open();
                    mCamera.startPreview();
                }
            }
        }
        if (mBackCamera == BackCamera) // TakePhoto
        {
            if(mCameraStatue==PHOTO_MODE) {
                mCamera.takePicture(null, null, mPcb);
            }
            else if(mCameraStatue==NULL_MODE) {
                mCamera.setPreviewCallback(PreviewCb);
                mCameraStatue = PHOTO_MODE;
            }
        }
        else //TakeVideo or Close
        {
            //if(mCameraStatue==PHOTO_MODE)
            {
                mCamera.setPreviewCallback(null);

                CloseCamera();
                mDataSaver.Close();
                mCameraStatue = NULL_MODE;
            }
			/*
			else if(mCameraStatue==VIDEO_MODE)
			{
				mCameraStatue = NULL_MODE;
			}
			else if(mCameraStatue==NULL_MODE)
			{
				mCameraStatue = VIDEO_MODE;
			}
			else
			{
				mCameraStatue = NULL_MODE;
			}
			*/
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 获取手机当前音量值
        switch (keyCode) {

            case KeyEvent.KEYCODE_VOLUME_DOWN:
                controlCamera(false);
                mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER, 0);
                mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE, 0);
                return true;

            case KeyEvent.KEYCODE_VOLUME_UP:
                controlCamera(true);
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

    private int FindCamera(boolean back) {
        int cameraCount = 0;
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        cameraCount = Camera.getNumberOfCameras(); // get cameras number
        int value = Camera.CameraInfo.CAMERA_FACING_FRONT;
        if (back) {
            value = Camera.CameraInfo.CAMERA_FACING_BACK;
        }
        for (int camIdx = 0; camIdx < cameraCount; camIdx++) {
            Camera.getCameraInfo(camIdx, cameraInfo); // get camerainfo
            if (cameraInfo.facing == value) {
                return camIdx;
            }
        }
        return -1;
    }

    public void CloseCamera() {
        if (mCamera != null) {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    // Main Func of Catch Camera
    public Camera OpenCamera(boolean backCamera) {

        int cameraindex = FindCamera(backCamera);
        if (cameraindex == -1) {
            return null;
        }

        // Open Camera, Set Camera
        Camera camera = null;
        try {
            camera = Camera.open(cameraindex);
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
                    p.setPreviewSize(wide, high);
                    p.setAntibanding(Parameters.ANTIBANDING_60HZ);
                    camera.setParameters(p);
                }
            } catch (Exception e) {
            }
            mSt = new SurfaceTexture(0);
            camera.setPreviewTexture(mSt);
        } catch (Exception e1) {
            return null;
        }

        return camera;
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