@file:Suppress("DEPRECATION")

package com.example.test

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.TargetApi
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Camera
import android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import com.example.test.databinding.ActivityMainBinding
import com.mv.engine.BlurDetector
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import java.io.*


@ObsoleteCoroutinesApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var cameraViewModel:CameraViewModel = CameraViewModel()

    private var isAutoSave = true
    private var timeSaveImg = 20000
    private var timeSaveImgInterval = 500
    private var currTimeSave = 0
    private var enginePrepared: Boolean = false
    private lateinit var engineWrapper: EngineWrapper
    private var threshold: Float = defaultThreshold

    private var camera: Camera? = null
    private var cameraId: Int = Camera.CameraInfo.CAMERA_FACING_FRONT
    private var cameraId_front: Int = Camera.CameraInfo.CAMERA_FACING_FRONT
    private var cameraId_back: Int = Camera.CameraInfo.CAMERA_FACING_BACK
    private val previewWidth: Int = 1280
    private val previewHeight: Int = 960
    private var frameCount = 0
    private var frame_loading = 10
    private var count_check = 0
    private var count_check_live = 0
    private var check_real_alway = 10
    private var save_confidence = true

    var prevCenterPos: PointF? = null
    var byteArrayData: ByteArray? = null

    var check_live = false

    var confValues = mutableListOf<Float>()
//    var confValues: MutableList<Double> = mutableListOf()
//    var isFirst = true

    private var startTime = 0L
    /**
     *    1       2       3       4        5          6          7            8
     * <p>
     * 888888  888888      88  88      8888888888  88                  88  8888888888
     * 88          88      88  88      88  88      88  88          88  88      88  88
     * 8888      8888    8888  8888    88          8888888888  8888888888          88
     * 88          88      88  88
     * 88          88  888888  888888
     */
    private val frameOrientation_front: Int = 7 //Front camera
    private val frameOrientation_back: Int = 1 //Back camera

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    private var factorX: Float = 0F
    private var factorY: Float = 0F

    private val detectionContext = newSingleThreadContext("detection")
    private var working: Boolean = false

    private lateinit var scaleAnimator: ObjectAnimator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getPermission();
        if (hasPermissions()) {
            init()
        } else {
            requestPermission()
        }
    }

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (permission in permissions) {
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }
        }
        return true
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun requestPermission() = requestPermissions(permissions, permissionReqCode)

    private fun init() {
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.result = DetectionResult()
        binding.viewModel = cameraViewModel
        binding.btnFlip.setOnClickListener {
            if (cameraId == cameraId_back) {
                cameraId = cameraId_front
            } else {
                View.VISIBLE
                cameraId = cameraId_back
            }
            init()
        }
        calculateSize()

        binding.surface.holder.let {
            it.setFormat(ImageFormat.NV21)
            it.addCallback(object : SurfaceHolder.Callback, Camera.PreviewCallback {
                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {
                    if (holder?.surface == null)
                        return

                    if (camera == null)
                        return

                    try {
                        camera?.stopPreview()
                    } catch (e: java.lang.Exception) {
                        e.printStackTrace()
                    }

                    val parameters = camera?.parameters
                    parameters?.setPreviewSize(previewWidth, previewHeight)
                    parameters?.jpegQuality = 100


                    val jpegQuality = parameters?.getJpegQuality()

                    if (cameraId == cameraId_back){
                        // Scan image not blur when use focus mode continous video
                        parameters?.focusMode = FOCUS_MODE_CONTINUOUS_VIDEO
                    }

                    factorX = screenWidth / previewHeight.toFloat()
                    factorY = screenHeight / previewWidth.toFloat()
                    camera?.parameters = parameters

                    camera?.startPreview()
                    camera?.setPreviewCallback(this)

                    setCameraDisplayOrientation()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    camera?.setPreviewCallback(null)
                    camera?.release()
                    camera = null
                }


                // Run camera when start app
                override fun surfaceCreated(holder: SurfaceHolder) {
                    try {
                        camera = Camera.open(cameraId)
                    } catch (e: Exception) {
                        cameraId = Camera.CameraInfo.CAMERA_FACING_FRONT
//                        cameraId = Camera.CameraInfo.CAMERA_FACING_BACK
                        camera = Camera.open(cameraId)
                    }

                    try {
                        camera!!.setPreviewDisplay(binding.surface.holder)
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }


                override fun onPreviewFrame(data: ByteArray?, camera: Camera?) {

                    if (enginePrepared && data != null) {
                        byteArrayData = data
                        if (!working) {
                            if (frameCount == frame_loading) {
                                val endTime = System.currentTimeMillis()
                            }

                            GlobalScope.launch(detectionContext) {
                                working = true
                                var frameOrientation = frameOrientation_front
                                if (cameraId == cameraId_back){
                                    frameOrientation = frameOrientation_back
                                }
                                else{
                                    frameOrientation = frameOrientation_front
                                }

                                // Detect blur
                                val resultBlur = engineWrapper.detectBlur(
                                    data,
                                    previewWidth,
                                    previewHeight
                                )
                                binding.engineWrapper = engineWrapper
                                Log.d("ngoclh", "resultBlur: "+resultBlur)

                                // Check detect blur or not blur
                                if (resultBlur){

                                    // Detect bounding box and classify live or spoof
                                    val results = engineWrapper.detect(
                                        data,
                                        previewWidth,
                                        previewHeight,
                                        frameOrientation
                                    )

                                    // Check has face in camera ?
                                    if (results.isEmpty()) {
                                        frameCount = 0
                                        count_check_live = 0
                                        confValues.clear()
                                        check_live = false

                                        val rect = Rect(//FRONT
                                            2000,
                                            2000,
                                            2000,
                                            2000
                                        )
                                        val result = DetectionResult()
                                        binding.result = result.updateLocation(rect)
                                        binding.rectView.postInvalidate()

                                    }
                                    if (results.size == 1) {
                                        var result = DetectionResult()
                                        result = results.first() // Get bounding box with max confidence score

                                        // Check confidence
                                        save_confidence = true
                                        if (save_confidence){
                                            checkSaveImage(result.confidence)
                                        }

                                        //point center of box
                                        val centerX = (result.left + result.right) / 2f
                                        val centerY = (result.top + result.bottom) / 2f
                                        val currCenterPos = PointF(centerX, centerY)

                                        if (prevCenterPos != null) {
                                            var distance = PointF(currCenterPos.x - prevCenterPos!!.x, currCenterPos.y - prevCenterPos!!.y).length()
                                            // Tracking one face
                                            if (distance > 70) {
                                                check_live = false
                                                frameCount = 0
                                                count_check_live = 0
                                                confValues.clear()
                                            }
                                        }
                                        prevCenterPos = currCenterPos

                                        if (frameCount == 0) {
                                            count_check_live = 0
                                            check_live = false
                                            confValues.clear()
                                            // start time
                                            startTime = System.currentTimeMillis()
                                        }
                                        frameCount ++
                                        result.threshold = threshold
                                        confValues.add(result.confidence)

                                        confValues = confValues.takeLast(frame_loading).toMutableList()
                                        val average_Conf = confValues.average()
                                        if (confValues.size > frame_loading - 1) {

                                            confValues.removeAt(frame_loading - 1)
                                            confValues.add(average_Conf.toFloat())
                                        }

                                        // Check all threshold is bigger than defaultThreshold
                                        val allAboveThreshold = confValues.all { it > defaultThreshold }

                                        if (frameCount > frame_loading){
                                            if (allAboveThreshold) {
                                                count_check_live += 1
                                                if (count_check_live > check_real_alway){
                                                    check_live = true
                                                }
                                            }
                                            else{
                                                count_check_live = 0
                                                check_live = false
                                            }
                                        }
                                        if (frameCount > frame_loading) {

                                            if (check_live){
                                                result.confidence = 0.9999F
                                            }
                                            val rect = calculateBoxLocationOnScreen(//FRONT
                                                result.left,
                                                result.top,
                                                result.right,
                                                result.bottom
                                            )

                                            binding.result = result.updateLocation(rect)

                                        } else {
                                            count_check_live = 0
                                            check_live = false
                                            result.confidence = 0.toFloat()

                                            val rect = calculateBoxLocationOnScreen(//FRONT
                                                result.left,
                                                result.top,
                                                result.right,
                                                result.bottom
                                            )
                                            binding.result = result.updateLocation(rect)
                                        }
                                        binding.rectView.postInvalidate()
                                    }
                                    else {
                                        count_check_live = 0
                                        check_live = false
                                        frameCount = 0
                                        confValues.clear()
                                    }
                                }else{
                                    frameCount = 0
                                    count_check_live = 0
                                    confValues.clear()
                                    check_live = false

                                    val rect = Rect(//FRONT
                                        2000,
                                        2000,
                                        2000,
                                        2000
                                    )

                                    val result = DetectionResult()
                                    binding.result = result.updateLocation(rect)
                                    binding.rectView.postInvalidate()
                                }

                                working = false

//                                val results = engineWrapper.detect(
//                                    data,
//                                    previewWidth,
//                                    previewHeight,
//                                    frameOrientation
//                                )

//                                // Check has face in camera ?
//                                if (results.isEmpty()) {
//                                    frameCount = 0
//                                    count_check_live = 0
//                                    confValues.clear()
//                                    check_live = false
//
//                                    val rect = Rect(//FRONT
//                                        2000,
//                                        2000,
//                                        2000,
//                                        2000
//                                    )
//
//                                    val result = DetectionResult()
//                                    binding.result = result.updateLocation(rect)
//                                    binding.rectView.postInvalidate()
//
//                                }
//
//                                if (results.size == 1) {
//                                    var result = DetectionResult()
//                                    result = results.first()
//
//                                    //point center of box
//                                    val centerX = (result.left + result.right) / 2f
//                                    val centerY = (result.top + result.bottom) / 2f
//                                    val currCenterPos = PointF(centerX, centerY)
//
//                                    if (prevCenterPos != null) {
//                                        var distance = PointF(currCenterPos.x - prevCenterPos!!.x, currCenterPos.y - prevCenterPos!!.y).length()
//                                        // Tracking one face
//                                        if (distance > 70) {
//                                            check_live = false
//                                            frameCount = 0
//                                            count_check_live = 0
//                                            confValues.clear()
//                                        }
//                                    }
//                                    prevCenterPos = currCenterPos
//
//                                    if (frameCount == 0) {
//                                        count_check_live = 0
//                                        check_live = false
//                                        confValues.clear()
//                                        // start time
//                                        startTime = System.currentTimeMillis()
//
//                                    }
//                                    frameCount ++
//                                    result.threshold = threshold
//                                    confValues.add(result.confidence)
//
//                                    confValues = confValues.takeLast(frame_loading).toMutableList()
//                                    val average_Conf = confValues.average()
//                                    if (confValues.size > frame_loading - 1) {
//
//                                        confValues.removeAt(frame_loading - 1)
//                                        confValues.add(average_Conf.toFloat())
//                                    }
//
//                                    // Check all threshold is bigger than defaultThreshold
//                                    val allAboveThreshold = confValues.all { it > defaultThreshold }
//
//                                    if (frameCount > frame_loading){
//                                        if (allAboveThreshold) {
//                                            count_check_live += 1
//                                            if (count_check_live > check_real_alway){
//                                                check_live = true
//                                            }
//                                        }
//                                        else{
//                                            count_check_live = 0
//                                            check_live = false
//                                        }
//
//                                    }
//
//                                    if (frameCount > frame_loading) {
//
//                                        if (check_live == true){
//                                            result.confidence = 0.9999F
//                                        }
//
//                                        val rect = calculateBoxLocationOnScreen(//FRONT
//                                            result.left,
//                                            result.top,
//                                            result.right,
//                                            result.bottom
//                                        )
//
//                                        binding.result = result.updateLocation(rect)
//
//                                    } else {
//                                        count_check_live = 0
//                                        check_live = false
//                                        result.confidence = 0.toFloat()
//
//                                        val rect = calculateBoxLocationOnScreen(//FRONT
//                                            result.left,
//                                            result.top,
//                                            result.right,
//                                            result.bottom
//                                        )
//                                        binding.result = result.updateLocation(rect)
//                                    }
//                                    binding.rectView.postInvalidate()
//                                }
//                                else {
//                                    count_check_live = 0
//                                    check_live = false
//                                    frameCount = 0
//                                    confValues.clear()
//                                }
//                                working = false
                            }
                        }
                    }
                }
            })
        }

        scaleAnimator = ObjectAnimator.ofFloat(binding.scan, View.SCALE_Y, 1F, -1F, 1F).apply {
            this.duration = 3000
            this.repeatCount = ValueAnimator.INFINITE
            this.repeatMode = ValueAnimator.REVERSE
            this.interpolator = LinearInterpolator()
            this.start()
        }

        // Button save image
        binding.btnSave.setOnClickListener {
            cameraViewModel.isSaving.set(true)
            val folder = "folder_${System.currentTimeMillis()}"
            val file = File(
                Environment.getExternalStorageDirectory()
                    .path + "/Download/$folder"
            )
            if (!file.exists()) {
                file.mkdir()
            }
            saveImage(folder)
        }

        // Button download image
        binding.btnDwonload.setOnClickListener {
            cameraViewModel.isSaving.set(false)
            currTimeSave = 0
        }

    }

    private fun calculateSize() {
        // Return size and resolution of display current in Android
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(dm)
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
    }

    private fun checkSaveImage(confidence: Float){
        if (confidence > 0.4 && confidence < 0.6) {
            val folder = "test_live_frontcamera"
            val file = File(
                Environment.getExternalStorageDirectory()
                    .path + "/Download/$folder"
            )
            if (!file.exists()) {
                file.mkdir()
            }
            saveImage(folder)
        }
    }


    private fun checkSaveFace(confidence: Float, left: Int, top: Int, right: Int, bottom: Int){
        val folder = "folder"
        val file = File(
                Environment.getExternalStorageDirectory()
                    .path + "/Download/$folder"
        )
        if (!file.exists()) {
            file.mkdir()
        }
        saveFace_image(folder, left, top, right, bottom)
    }


    private fun calculateBoxLocationOnScreen(left: Int, top: Int, right: Int, bottom: Int): Rect =
        Rect(
            (left * factorX).toInt(),
            (top * factorY).toInt(),
            (right * factorX).toInt(),
            (bottom * factorY).toInt()
        )

    private fun setCameraDisplayOrientation() {
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(cameraId, info)
        val rotation = windowManager.defaultDisplay.rotation
        var degrees = 0
        when (rotation) {
            Surface.ROTATION_0 -> degrees = 0
            Surface.ROTATION_90 -> degrees = 90
            Surface.ROTATION_180 -> degrees = 180
            Surface.ROTATION_270 -> degrees = 270
        }
//        degrees = 180
        var result: Int
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {//
            result = (info.orientation + degrees) % 360
            result = (360 - result) % 360 // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360
        }

        camera!!.setDisplayOrientation(result)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if ((ContextCompat.checkSelfPermission(baseContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) ||
            (ContextCompat.checkSelfPermission(baseContext, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
        ) {
        }else{
            init()
        }
    }

    private fun hasCameraPermission(): Boolean {
        val selfPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        )
        return selfPermission == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 123)
    }

    override fun onResume() {
        if (hasCameraPermission()) {
            engineWrapper = EngineWrapper(assets)
            enginePrepared = engineWrapper.init()
            if (!enginePrepared) {
                Toast.makeText(this, "Engine init failed.", Toast.LENGTH_LONG).show()
            }
        } else {
            requestCameraPermission()
        }
        super.onResume()
    }

    override fun onDestroy() {
        engineWrapper.destroy()
        scaleAnimator.cancel()
        super.onDestroy()
    }

    companion object {
        const val tag = "MainActivity"
        const val defaultThreshold = 0.53F ///915 default 655 51F    ///Threshold

        val permissions: Array<String> = arrayOf(Manifest.permission.CAMERA)
        const val permissionReqCode = 1    }

    private fun getPermission() {
        if ((ContextCompat.checkSelfPermission(baseContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) || (
                    ContextCompat.checkSelfPermission(baseContext, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED)
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    // Save entire image
    fun saveImage(folder:String){
        Log.d("save_img", "start")
        if(byteArrayData != null){
            try {
                val parameters = camera!!.parameters
                val size: Camera.Size = parameters.previewSize
                val image = YuvImage(
                    byteArrayData, parameters.previewFormat,
                    previewWidth, previewHeight, null
                )
                Log.d("save_img", "start" + size.width + image.width + image.height)
                var name = "out_${System.currentTimeMillis()}.jpg"
                Log.d("save_img", "name: $name")
                val file = File(
                    Environment.getExternalStorageDirectory()
                        .path + "/Download/$folder/$name"
                )
                val filecon = FileOutputStream(file)
                image.compressToJpeg(
                    Rect(0, 0, image.width, image.height), 100,
                    filecon
                )
            } catch (e: FileNotFoundException) {
                Toast.makeText(baseContext, "Saved image failed", Toast.LENGTH_SHORT).show()
            }
            if (isAutoSave){
                if (cameraViewModel.isSaving.get() == true && currTimeSave<=timeSaveImg){
                    Log.d("mop", "saveImage: $currTimeSave, ${cameraViewModel.isSaving.get()}")
                    Handler().postDelayed({
                        currTimeSave+=timeSaveImgInterval
                        saveImage(folder)
                    }, timeSaveImgInterval.toLong())
                }else{
                    currTimeSave = 0
                    cameraViewModel.isSaving.set(false)
                    Log.d("mop", "stop saveImage: $currTimeSave, ${cameraViewModel.isSaving.get()}")
                }
            }
        }else{
            Toast.makeText(baseContext, "Saved image failed", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveFace_image(folder: String, left: Int, top: Int, right: Int, bottom: Int) {
        // Save face in image with bounding box
        Log.d("save_img", "start")
        if(byteArrayData != null){
            try {
                val parameters = camera!!.parameters
                val size: Camera.Size = parameters.previewSize
                val image = YuvImage(
                    byteArrayData, parameters.previewFormat,
                    previewWidth, previewHeight, null
                )

                Log.d("save_img", "start" + size.width)
                var name = "out_${System.currentTimeMillis()}.jpg"
                Log.d("save_img", "name: $name")
                val file = File(
                    Environment.getExternalStorageDirectory()
                        .path + "/Download/$folder/$name"
                )
                val filecon = FileOutputStream(file)
                val width = bottom - top;
                val height = right - left;

                image.compressToJpeg(
                    Rect(1280 - bottom, 960 - left, width, height), 100,
                    filecon
                )
            } catch (e: FileNotFoundException) {
                Toast.makeText(baseContext, "Saved image failed", Toast.LENGTH_SHORT).show()
            }
            if (isAutoSave){
                if (cameraViewModel.isSaving.get() == true && currTimeSave<=timeSaveImg){
                    Log.d("mop", "saveImage: $currTimeSave, ${cameraViewModel.isSaving.get()}")
                    Handler().postDelayed({
                        currTimeSave+=timeSaveImgInterval
                        saveImage(folder)
                    }, timeSaveImgInterval.toLong())
                }else{
                    currTimeSave = 0
                    cameraViewModel.isSaving.set(false)
                    Log.d("mop", "stop saveImage: $currTimeSave, ${cameraViewModel.isSaving.get()}")
                }
            }
        }else{
            Toast.makeText(baseContext, "Saved image failed", Toast.LENGTH_SHORT).show()
        }
    }

}
