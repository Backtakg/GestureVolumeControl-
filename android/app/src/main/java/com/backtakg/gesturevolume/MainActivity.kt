package com.backtakg.gesturevolume

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var fingerDetectionText: TextView
    private lateinit var volumeText: TextView
    private lateinit var statusText: TextView
    private lateinit var volumeSlider: ProgressBar
    private lateinit var audioManager: AudioManager
    private lateinit var handLandmarker: HandLandmarker
    private lateinit var cameraExecutor: ExecutorService

    private var lastVolumeCommand = 0L
    private var smoothedPinchDistance = -1f
    private val uiHandler = Handler(Looper.getMainLooper())

    private val volumeSync = object : Runnable {
        override fun run() {
            updateVolumeDisplay()
            uiHandler.postDelayed(this, 100L)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else statusText.text = "Camera permission is required"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        fingerDetectionText = findViewById(R.id.fingerDetectionText)
        volumeText = findViewById(R.id.volumeText)
        statusText = findViewById(R.id.statusText)
        volumeSlider = findViewById(R.id.volumeSlider)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupHandLandmarker()
        updateVolumeDisplay()
        uiHandler.post(volumeSync)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupHandLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.65f)
            .setMinHandPresenceConfidence(0.65f)
            .setMinTrackingConfidence(0.65f)
            .setResultListener { result, _ -> processResult(result) }
            .setErrorListener { error -> runOnUiThread { statusText.text = "Vision error: ${error.message}" } }
            .build()

        handLandmarker = HandLandmarker.createFromOptions(this, options)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                        try {
                            val bitmap = imageProxy.toBitmap()
                            val mpImage = BitmapImageBuilder(bitmap).build()
                            handLandmarker.detectAsync(mpImage, SystemClock.uptimeMillis())
                        } catch (_: Exception) {
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            statusText.text = "Ready — only thumb + index control volume"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processResult(result: HandLandmarkerResult) {
        if (result.landmarks().isEmpty()) {
            smoothedPinchDistance = -1f
            runOnUiThread {
                fingerDetectionText.text = "Thumb: —   Index: —   Middle: —   Ring: —   Little: —"
                statusText.text = "Show your hand"
            }
            return
        }

        val hand = result.landmarks()[0]
        val now = SystemClock.uptimeMillis()

        val thumbExtended = isThumbExtended(hand)
        val indexExtended = isIndexExtended(hand)
        val middleExtended = isFingerExtended(hand, 12, 10, 9)
        val ringExtended = isFingerExtended(hand, 16, 14, 13)
        val littleExtended = isFingerExtended(hand, 20, 18, 17)

        runOnUiThread {
            fingerDetectionText.text = "Thumb: ${state(thumbExtended)}   Index: ${state(indexExtended)}   Middle: ${state(middleExtended)}   Ring: ${state(ringExtended)}   Little: ${state(littleExtended)}"
        }

        // Only thumb + index matter for volume control.
        // Middle, ring and little fingers are intentionally ignored.
        if (!indexExtended || !thumbExtended) {
            smoothedPinchDistance = -1f
            runOnUiThread {
                statusText.text = "Show thumb + index finger — other fingers can be anywhere"
            }
            return
        }

        val thumb = hand[4]
        val index = hand[8]
        val rawDistance = hypot(thumb.x() - index.x(), thumb.y() - index.y())
        smoothedPinchDistance = if (smoothedPinchDistance < 0f) rawDistance
        else smoothedPinchDistance * 0.72f + rawDistance * 0.28f

        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val normalized = ((smoothedPinchDistance - 0.035f) / (0.30f - 0.035f)).coerceIn(0f, 1f)
        val target = (normalized * max).roundToInt().coerceIn(0, max)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        if (now - lastVolumeCommand >= 90L && kotlin.math.abs(target - current) >= 1) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            lastVolumeCommand = now
        }

        val percent = (target * 100f / max).roundToInt()
        runOnUiThread {
            statusText.text = "Thumb + index distance: $percent%"
            updateVolumeDisplay()
        }
    }

    private fun state(extended: Boolean): String = if (extended) "OPEN" else "FOLDED"

    private fun updateVolumeDisplay() {
        if (!::audioManager.isInitialized || !::volumeText.isInitialized) return
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val percent = (current * 100f / max).roundToInt()
        volumeText.text = "Phone media volume: $percent%"
        if (::volumeSlider.isInitialized) {
            volumeSlider.max = max
            volumeSlider.progress = current
        }
    }

    private fun isIndexExtended(hand: List<NormalizedLandmark>): Boolean =
        isFingerExtended(hand, 8, 6, 5, 150.0)

    private fun isThumbExtended(hand: List<NormalizedLandmark>): Boolean {
        val tip = hand[4]
        val ip = hand[3]
        val mcp = hand[2]
        val wrist = hand[0]
        return distance(tip, wrist) > distance(ip, wrist) * 1.06f &&
            distance(tip, wrist) > distance(mcp, wrist) * 1.20f &&
            angle(ip, mcp, tip) > 135.0
    }

    private fun isFingerExtended(hand: List<NormalizedLandmark>, tip: Int, pip: Int, mcp: Int, minAngle: Double = 145.0): Boolean {
        val wrist = hand[0]
        return distance(hand[tip], wrist) > distance(hand[pip], wrist) * 1.10f &&
            angle(hand[pip], hand[mcp], hand[tip]) > minAngle
    }

    private fun distance(a: NormalizedLandmark, b: NormalizedLandmark): Float =
        hypot(a.x() - b.x(), a.y() - b.y())

    private fun angle(a: NormalizedLandmark, b: NormalizedLandmark, c: NormalizedLandmark): Double {
        val abx = a.x() - b.x()
        val aby = a.y() - b.y()
        val cbx = c.x() - b.x()
        val cby = c.y() - b.y()
        val denominator = hypot(abx, aby) * hypot(cbx, cby)
        if (denominator == 0f) return 0.0
        val cosine = ((abx * cbx + aby * cby) / denominator).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(cosine).toDouble())
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(volumeSync)
        if (::handLandmarker.isInitialized) handLandmarker.close()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        super.onDestroy()
    }
}
