package com.shadow.eye

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.Camera.*
import android.media.AudioManager
import android.os.*
import android.os.PowerManager.WakeLock
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.Camera.Parameters.FOCUS_MODE_AUTO
import java.io.ByteArrayOutputStream


class ShadowEyeActivity : Activity() {
    private val mSurfaceTexture = SurfaceTexture(0)
    private var mWakeLock: WakeLock? = null
    private var mAudioManager: AudioManager? = null
    private var mCamera: Camera? = null
    private var mCameraInfo: CameraInfo? = null
    private val mAutoFocusCallback: AutoFocusCallback = object : AutoFocusCallback {
        override fun onAutoFocus(success: Boolean, camera: Camera) {
            if (success) {
                try {
                    camera.takePicture(null, null, mPictureCallback)
                } catch (e: Exception) {
                    closeCamera()
                }

            } else {
                mCamera?.autoFocus(this)
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
                } else if (!isFocused) { // If Change Little, Focus Camera
                    if (currentTime - stableTime > 500) {
                        mVibrator?.vibrate(50)
                        mCamera?.setPreviewCallback(null)
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
        mVibrator?.vibrate(longArrayOf(0, 100, 100, 100), -1)
        savePicture(rotateJpeg(data, mCameraInfo!!.orientation))
        mCamera?.startPreview()
        mCamera?.setPreviewCallback(mPreviewCallback)
    }

    private fun rotateJpeg(data: ByteArray, orientation: Int): ByteArray {
        if (orientation == 0) return data
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
        val newBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { setRotate(orientation.toFloat(), bitmap.width.toFloat() / 2, bitmap.height.toFloat() / 2) }, true)
        return ByteArrayOutputStream().apply { newBitmap.compress(Bitmap.CompressFormat.JPEG, 100, this) }.toByteArray()
    }

    private var mLastVolumeKeyPressTime = 0L

    private val dateString: String
        get() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.CHINESE).format(Date())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            window.attributes.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE
        }
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        mAudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mVibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        setContentView(R.layout.activity_main)
        setBrightness(1f)
        acquireWakeLock()

        viewFinish.setOnClickListener { onBackPressed() } // top -> finish
        viewBack.setOnClickListener { handleKeyPress(true) } // middle -> back
        viewFront.setOnClickListener { handleKeyPress(false) } // bottom -> front
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
        var zipOutputStream: ZipOutputStream? = null
        try {
            zipOutputStream = ZipOutputStream(FileOutputStream(File(Path)))
            zipOutputStream.putNextEntry(ZipEntry("$dateString.jpg"))
            zipOutputStream.write(data)
            zipOutputStream.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            zipOutputStream?.close()
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
                mCamera?.takePicture(null, null, mPictureCallback)
            } else if (mCameraStatue == NULL_MODE) {
                mCameraStatue = PHOTO_MODE
                mCamera?.setPreviewCallback(mPreviewCallback)
            }
        } else {
            closeCamera()
            mCameraStatue = NULL_MODE
        }
    }

    private fun closeCamera() {
        if (mCamera != null) {
            mCamera?.setPreviewCallback(null)
            mCamera?.stopPreview()
            mCamera?.release()
            mCamera = null
            mCameraInfo = null
        }
    }

    private fun startCamera() {
        var cameraIndex = -1
        val cameraInfo = Camera.CameraInfo()
        val cameraFacing = if (mBackCamera) Camera.CameraInfo.CAMERA_FACING_BACK else Camera.CameraInfo.CAMERA_FACING_FRONT
        for (camIdx in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(camIdx, cameraInfo)
            if (cameraInfo.facing == cameraFacing) {
                cameraIndex = camIdx
                break
            }
        }
        if (cameraIndex == -1) {
            return
        }

        try {
            val camera = Camera.open(cameraIndex)
            camera.setDisplayOrientation(cameraInfo.orientation)

            try {
                val parameters = camera.parameters
                if (parameters != null) {
                    if (mBackCamera) {
                        parameters.flashMode = Parameters.FLASH_MODE_OFF
                    }
                    parameters.setRotation(cameraInfo.orientation)
                    parameters.sceneMode = Parameters.SCENE_MODE_PORTRAIT
                    val pictureSize = parameters.supportedPictureSizes.firstOrNull()
                    if (pictureSize != null) parameters.setPictureSize(pictureSize.width, pictureSize.height)
                    parameters.antibanding = Parameters.ANTIBANDING_60HZ
                    parameters.focusMode = FOCUS_MODE_AUTO
                    camera.parameters = parameters
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            camera.setPreviewTexture(mSurfaceTexture)
            mCurrentPath = Environment.getExternalStorageDirectory().toString() + "/save/" + dateString
            camera.startPreview()
            mCamera = camera
            mCameraInfo = cameraInfo
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("InvalidWakeLockTag", "WakelockTimeout")
    private fun acquireWakeLock() {
        if (null == mWakeLock) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "LOCK")
            mWakeLock?.acquire()
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