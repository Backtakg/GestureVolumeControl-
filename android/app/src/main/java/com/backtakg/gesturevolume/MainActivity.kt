package com.backtakg.gesturevolume

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private lateinit var volumeText: TextView
    private lateinit var statusText: TextView
    private lateinit var audioManager: AudioManager
    private lateinit var handLandmarker: HandLandmarker
    private lateinit var cameraExecutor: ExecutorService

    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastTargetVolume = -1
    private var smoothedDistance = -1f

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
        volumeText = findViewById(R.id.volumeText)
        statusText = findViewById(R.id.statusText)
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
                            // Dropped camera frames are safe; the next frame will be analyzed.
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
            statusText.text = "Ready — move thumb and index finger"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processResult(result: HandLandmarkerResult) {
        if (result.landmarks().isEmpty()) {
            smoothedDistance = -1f
            runOnUiThread { statusText.text = "Show one hand — thumb + index control volume" }
            return
        }

        val hand = result.landmarks()[0]
        val thumb = hand[4]
        val index = hand[8]
        val rawDistance = distance(thumb, index)

        // Smooth small tracking noise so the phone volume does not jump around.
        smoothedDistance = if (smoothedDistance < 0f) {
            rawDistance
        } else {
            smoothedDistance * 0.72f + rawDistance * 0.28f
        }

        // Closed pinch = minimum volume, wider thumb/index gap = higher volume.
        val minDistance = 0.025f
        val maxDistance = 0.32f
        val normalized = ((smoothedDistance - minDistance) / (maxDistance - minDistance))
            .coerceIn(0f, 1f)
        val targetPercent = (normalized * 100f).roundToInt()

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetVolume = (normalized * maxVolume).roundToInt().coerceIn(0, maxVolume)

        if (targetVolume != lastTargetVolume) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            lastTargetVolume = targetVolume
        }

        runOnUiThread {
            volumeText.text = "Phone media volume: ${targetPercent}%  ($targetVolume/$maxVolume)"
            statusText.text = "Thumb ↔ Index: ${targetPercent}% — wider = louder"
        }
    }

    private fun updateVolumeDisplay() {
        if (!::audioManager.isInitialized || !::volumeText.isInitialized) return
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val percent = (current * 100f / max).roundToInt()
        volumeText.text = "Phone media volume: $percent%  ($current/$max)"
    }

    private fun distance(a: NormalizedLandmark, b: NormalizedLandmark): Float =
        hypot(a.x() - b.x(), a.y() - b.y())

    override fun onDestroy() {
        uiHandler.removeCallbacks(volumeSync)
        if (::handLandmarker.isInitialized) handLandmarker.close()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        super.onDestroy()
    }
}
