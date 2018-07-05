package com.shadow.eye

import android.app.Activity
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.Camera.AutoFocusCallback
import android.hardware.Camera.Parameters
import android.hardware.Camera.PictureCallback
import android.hardware.Camera.PreviewCallback
import android.media.AudioManager
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.os.SystemClock
import android.os.Vibrator
import android.view.KeyEvent
import android.view.Window
import android.view.WindowManager

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ShadowEyeActivity : Activity() {
    private val mSurfaceTexture = SurfaceTexture(0)
    private var mWakeLock: WakeLock? = null
    private var mAudioManager: AudioManager? = null
    private var mCamera: Camera? = null
    private val mAutoFocusCallback: AutoFocusCallback = object : AutoFocusCallback {
        override fun onAutoFocus(success: Boolean, camera: Camera) {
            if (success) {
                try {
                    camera.takePicture(null, null, mPictureCallback)
                } catch (e: Exception) {
                    closeCamera()
                }

            } else {
                mCamera!!.autoFocus(this)
            }
        }
    }
    private var mVibrator: Vibrator? = null
    private var mCurrentPath: String? = null
    private var mLastBackPress: Long = 0
    private var mCameraStatue = NULL_MODE
    private var mBackCamera = true
    private val mPreviewCallback: PreviewCallback = object : PreviewCallback {
        val autoFocusLightThreshold = 16
        var lastLight = 100000.0
        var stableTime: Long = 0
        var isFocused = false

        override fun onPreviewFrame(data: ByteArray, camera: Camera) {
            try {
                val previewSize = camera.parameters.previewSize
                val light = getYUV420SPLight(data, previewSize.width, previewSize.height)
                val currentTime = SystemClock.elapsedRealtime()
                // Light Change
                if (Math.abs(lastLight - light) > autoFocusLightThreshold) {
                    lastLight = light
                    stableTime = currentTime
                    isFocused = false
                } else if (!isFocused) { // If Change Little ,Focus Camera
                    if (currentTime - stableTime > 500) {
                        mVibrator!!.vibrate(50)
                        mCamera!!.setPreviewCallback(null)
                        if (mBackCamera) {
                            camera.autoFocus(mAutoFocusCallback)
                        } else {
                            try {
                                camera.takePicture(null, null, mPictureCallback)
                            } catch (e: Exception) {
                                closeCamera()
                            }

                        }
                        isFocused = true
                    }
                }

            } catch (e: Exception) {
                closeCamera()
            }

        }
    }
    private val mPictureCallback = PictureCallback { data, camera ->
        val l = longArrayOf(0, 100, 100, 100)
        mVibrator!!.vibrate(l, -1)
        savePicture(data)
        mCamera!!.startPreview()
        mCamera!!.setPreviewCallback(mPreviewCallback)
    }
    private var mLastVolumeKeyPressTime = 0L

    private val dateString: String
        get() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.CHINESE).format(Date())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        mAudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mVibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        setContentView(R.layout.activity_main)
        setBrightness(1f)
        acquireWakeLock()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        closeCamera()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 获取手机当前音量值
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                handleKeyPress(false)
                mAudioManager!!.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER, 0)
                mAudioManager!!.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE, 0)
                return true
            }

            KeyEvent.KEYCODE_VOLUME_UP -> {
                handleKeyPress(true)
                mAudioManager!!.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER, 0)
                mAudioManager!!.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE, 0)
                return true
            }
            KeyEvent.KEYCODE_HOME -> return true
            else -> {
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - mLastBackPress > 500) {
            mLastBackPress = currentTime
        } else {
            super.onBackPressed()
        }
    }

    private fun savePicture(data: ByteArray) {
        if (mCurrentPath == null) return
        val dateString = dateString
        val Path = "$mCurrentPath/$dateString.zip"
        File(mCurrentPath!!).mkdirs()
        val zipOutputStream: ZipOutputStream
        try {
            zipOutputStream = ZipOutputStream(FileOutputStream(File(Path)))
            try {
                val zipEntry = ZipEntry("$dateString.jpg")
                zipOutputStream.putNextEntry(zipEntry)
                zipOutputStream.write(data)
                zipOutputStream.flush()
                zipOutputStream.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }

        } catch (e1: Exception) {
            e1.printStackTrace()
        }

    }

    private fun setBrightness(brightness: Float) {
        val params = window.attributes
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        params.screenBrightness = brightness / 255.0f
        window.attributes = params
    }

    private fun handleKeyPress(backCamera: Boolean) {
        mVibrator!!.vibrate(50)
        if (mCameraStatue == NULL_MODE) {
            val currentTime = SystemClock.elapsedRealtime()
            if (mLastVolumeKeyPressTime + 3000 < currentTime) {
                mLastVolumeKeyPressTime = currentTime
                mBackCamera = backCamera
                return
            }
            mLastVolumeKeyPressTime = 0
            if (mCamera == null) {
                startCamera()
            }
        }
        if (mBackCamera == backCamera) {
            if (mCameraStatue == PHOTO_MODE) {
                mCamera!!.takePicture(null, null, mPictureCallback)
            } else if (mCameraStatue == NULL_MODE) {
                mCameraStatue = PHOTO_MODE
                mCamera!!.setPreviewCallback(mPreviewCallback)
            }
        } else {
            closeCamera()
            mCameraStatue = NULL_MODE
        }
    }

    private fun closeCamera() {
        if (mCamera != null) {
            mCamera!!.setPreviewCallback(null)
            mCamera!!.stopPreview()
            mCamera!!.release()
            mCamera = null
        }
    }

    private fun findCamera(back: Boolean): Int {
        val cameraInfo = Camera.CameraInfo()
        val cameraCount = Camera.getNumberOfCameras() // get cameras number
        val value = if (back) Camera.CameraInfo.CAMERA_FACING_BACK else Camera.CameraInfo.CAMERA_FACING_FRONT
        for (camIdx in 0 until cameraCount) {
            Camera.getCameraInfo(camIdx, cameraInfo)
            if (cameraInfo.facing == value) {
                return camIdx
            }
        }
        return -1
    }

    // Main Func of Catch Camera
    private fun openCamera(backCamera: Boolean): Camera? {
        val cameraIndex = findCamera(backCamera)
        if (cameraIndex == -1) {
            return null
        }
        try {
            val camera = Camera.open(cameraIndex)
            camera.setDisplayOrientation(90)

            try {
                val p = camera.parameters
                if (p != null) {
                    if (backCamera) {
                        p.flashMode = Parameters.FLASH_MODE_OFF
                        p.set("orientation", "portrait")
                        p.setRotation(90)
                    } else {
                        p.set("orientation", "portrait")
                        p.setRotation(270)
                    }
                    p.setPreviewSize(CAMERA_PREVIEW_WIDTH, CAMERA_PREVIEW_HEIGHT)
                    p.antibanding = Parameters.ANTIBANDING_60HZ
                    camera.parameters = p
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            camera.setPreviewTexture(mSurfaceTexture)
            return camera
        } catch (e1: Exception) {
            return null
        }

    }

    private fun startCamera() {
        mCamera = openCamera(mBackCamera)
        if (mCamera != null) {
            mCurrentPath = Environment.getExternalStorageDirectory().toString() + "/save/" + dateString
            mCamera!!.startPreview()
        }
    }

    private fun acquireWakeLock() {
        if (null == mWakeLock) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "LOCK")
            if (null != mWakeLock) mWakeLock!!.acquire()
        }
    }

    // 释放设备电源锁
    private fun releaseWakeLock() {
        if (null != mWakeLock) {
            mWakeLock!!.release()
            mWakeLock = null
        }
    }

    companion object {
        private val NULL_MODE = 0
        private val PHOTO_MODE = 1
        private val CAMERA_PREVIEW_WIDTH = 500
        private val CAMERA_PREVIEW_HEIGHT = 500

        /**
         * 通过YUV420SP数组 计算出中心区域亮度
         *
         * @param yuv420sp 图像帧数组
         * @param width    宽度
         * @param height   高度
         * @return 返回亮度, 这亮度值始终大于0
         */
        fun getYUV420SPLight(yuv420sp: ByteArray, width: Int,
                             height: Int): Double {
            var bright = 0.0
            var total = 0
            val xEdge = width / 3
            val yEdge = height / 3
            val focusWidth = width - xEdge * 2
            val focusHeight = height - yEdge * 2
            val dt = 32
            val dx = if (focusWidth <= dt) 1 else focusWidth / dt
            val dy = if (focusHeight <= dt) 1 else focusHeight / dt
            var j = yEdge
            while (j < height - yEdge) {
                var i = xEdge
                while (i < width - xEdge) {
                    bright += (0xff and yuv420sp[j * width + i].toInt()).toDouble()
                    i += dx
                    ++total
                }
                j += dy
            }
            return if (0 == total) 0.0 else bright / total
        }
    }
}