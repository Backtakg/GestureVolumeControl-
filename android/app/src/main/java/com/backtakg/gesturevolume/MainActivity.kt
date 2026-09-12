package com.backtakg.gesturevolume

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
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
    private lateinit var handOverlay: HandOverlayView
    private lateinit var volumeText: TextView
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
        if (granted) startCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        handOverlay = findViewById(R.id.handOverlay)
        volumeText = findViewById(R.id.volumeText)
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
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processResult(result: HandLandmarkerResult) {
        if (result.landmarks().isEmpty()) {
            smoothedPinchDistance = -1f
            runOnUiThread { handOverlay.setLandmarks(null) }
            return
        }

        val hand = result.landmarks()[0]
        val now = SystemClock.uptimeMillis()
        runOnUiThread { handOverlay.setLandmarks(hand) }

        // Only thumb + index distance controls volume. The other three fingers are ignored.
        val thumb = hand[4]
        val index = hand[8]
        val rawDistance = hypot(thumb.x() - index.x(), thumb.y() - index.y())
        smoothedPinchDistance = if (smoothedPinchDistance < 0f) rawDistance
        else smoothedPinchDistance * 0.72f + rawDistance * 0.28f

        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val normalized = ((smoothedPinchDistance - 0.035f) / (0.30f - 0.035f)).coerceIn(0f, 1f)
        val target = (normalized * max).roundToInt().coerceIn(0, max)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        if (now - lastVolumeCommand >= 70L && kotlin.math.abs(target - current) >= 1) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            lastVolumeCommand = now
        }

        runOnUiThread { updateVolumeDisplay() }
    }

    private fun updateVolumeDisplay() {
        if (!::audioManager.isInitialized || !::volumeText.isInitialized) return
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val percent = (current * 100f / max).roundToInt()
        volumeText.text = "$percent%"
        if (::volumeSlider.isInitialized) {
            volumeSlider.max = max
            volumeSlider.progress = current
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(volumeSync)
        if (::handLandmarker.isInitialized) handLandmarker.close()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        super.onDestroy()
    }
}

class HandOverlayView(context: Context) : View(context) {
    private var landmarks: List<NormalizedLandmark>? = null
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pinchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }

    private val connections = arrayOf(
        intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(2, 3), intArrayOf(3, 4),
        intArrayOf(0, 5), intArrayOf(5, 6), intArrayOf(6, 7), intArrayOf(7, 8),
        intArrayOf(5, 9), intArrayOf(9, 10), intArrayOf(10, 11), intArrayOf(11, 12),
        intArrayOf(9, 13), intArrayOf(13, 14), intArrayOf(14, 15), intArrayOf(15, 16),
        intArrayOf(13, 17), intArrayOf(17, 18), intArrayOf(18, 19), intArrayOf(19, 20),
        intArrayOf(0, 17)
    )

    fun setLandmarks(value: List<NormalizedLandmark>?) {
        landmarks = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val hand = landmarks ?: return
        if (hand.size < 21 || width <= 0 || height <= 0) return

        linePaint.strokeWidth = 5f
        for (connection in connections) {
            val a = hand[connection[0]]
            val b = hand[connection[1]]
            canvas.drawLine(a.x() * width, a.y() * height, b.x() * width, b.y() * height, linePaint)
        }

        for (i in hand.indices) {
            pointPaint.radius = 8f
            canvas.drawCircle(hand[i].x() * width, hand[i].y() * height, 8f, pointPaint)
        }

        val thumb = hand[4]
        val index = hand[8]
        pinchPaint.strokeWidth = 9f
        canvas.drawLine(
            thumb.x() * width,
            thumb.y() * height,
            index.x() * width,
            index.y() * height,
            pinchPaint
        )
        canvas.drawCircle(thumb.x() * width, thumb.y() * height, 15f, pinchPaint)
        canvas.drawCircle(index.x() * width, index.y() * height, 15f, pinchPaint)
    }
}
