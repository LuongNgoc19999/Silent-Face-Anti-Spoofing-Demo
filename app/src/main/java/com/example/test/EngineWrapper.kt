package com.example.test

import android.content.res.AssetManager
import android.util.Log
import androidx.databinding.BaseObservable
import androidx.databinding.ObservableBoolean
import com.mv.engine.FaceBox
import com.mv.engine.FaceDetector
import com.mv.engine.Live
import com.mv.engine.BlurDetector


class EngineWrapper(private var assetManager: AssetManager) {
    var blurDetectorResult:Boolean = true
    private var faceDetector: FaceDetector = FaceDetector() // Face Detector
    private var live: Live = Live() // Face classify (live / spoof)
    private var blur: BlurDetector = BlurDetector() // Detect blur or not blur
    // Load model detection and load model classify live / spoof
    fun init(): Boolean {
        var ret = faceDetector.loadModel(assetManager)
        if (ret == 0) {
            ret = live.loadModel(assetManager)
            return ret == 0
        }
        return false
    }

    fun destroy() {
        faceDetector.destroy()
        live.destroy()
        blur.destroy()
    }

    fun detect(yuv: ByteArray, width: Int, height: Int, orientation: Int): List<DetectionResult> {
        val begin = System.currentTimeMillis()

        val results = mutableListOf<DetectionResult>()
        var boxes = detectFace(yuv, width, height, orientation)

        /// get face with confidence score max
        boxes = boxes.take(1)
        boxes.forEach {

            val box = it.apply {
                val c = detectLive(yuv, width, height, orientation, this)
                confidence = c


            }
            val end = System.currentTimeMillis()
            val result = DetectionResult(box, end - begin, true)
            results.add(result)
        }

        return results
    }

    fun detectBlur(yuv: ByteArray, width: Int, height: Int): Boolean {
        blurDetectorResult = blur.detect_blur(yuv, width, height)
        return blurDetectorResult
    }

    private fun detectFace(
        yuv: ByteArray,
        width: Int,
        height: Int,
        orientation: Int
    ): List<FaceBox> = faceDetector.detect(yuv, width, height, orientation)

    private fun detectLive(
        yuv: ByteArray,
        width: Int,
        height: Int,
        orientation: Int,
        faceBox: FaceBox
    ): Float = live.detect(yuv, width, height, orientation, faceBox)
}