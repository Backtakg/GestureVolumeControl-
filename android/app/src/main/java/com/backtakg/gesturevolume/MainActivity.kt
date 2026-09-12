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

    private var lastGesture = "NONE"
    private var lastVolumeCommand = 0L
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
            statusText.text = "Ready — use thumbs up/down"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processResult(result: HandLandmarkerResult) {
        if (result.landmarks().isEmpty()) {
            lastGesture = "NONE"
            runOnUiThread {
                statusText.text = "Show one hand — 👍 up / 👎 down"
            }
            return
        }

        val hand = result.landmarks()[0]
        val gesture = classifyGesture(hand)
        val now = SystemClock.uptimeMillis()

        if (gesture != "NONE" && gesture != "OPEN" && now - lastVolumeCommand >= 140L) {
            when (gesture) {
                "THUMB_UP" -> changeVolumeBy(1)
                "THUMB_DOWN" -> changeVolumeBy(-1)
            }
            lastVolumeCommand = now
        }
        lastGesture = gesture

        val gestureText = when (gesture) {
            "THUMB_UP" -> "👍 Volume UP"
            "THUMB_DOWN" -> "👎 Volume DOWN"
            "FIST" -> "✊ Fist detected — no action"
            "OPEN" -> "✋ Open hand — ready"
            else -> "Show 👍 up or 👎 down"
        }

        runOnUiThread {
            statusText.text = gestureText
            updateVolumeDisplay()
        }
    }

    private fun changeVolumeBy(direction: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (current + direction).coerceIn(0, max)
        if (target != current) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
    }

    private fun updateVolumeDisplay() {
        if (!::audioManager.isInitialized || !::volumeText.isInitialized) return
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val percent = (current * 100f / max).roundToInt()
        volumeText.text = "Phone media volume: $percent%  ($current/$max)"
    }

    private fun classifyGesture(hand: List<NormalizedLandmark>): String {
        if (isFist(hand)) return "FIST"

        val indexExtended = isFingerExtended(hand, 8, 6, 5)
        val middleExtended = isFingerExtended(hand, 12, 10, 9)
        val ringExtended = isFingerExtended(hand, 16, 14, 13)
        val pinkyExtended = isFingerExtended(hand, 20, 18, 17)
        val thumbExtended = isThumbExtended(hand)

        // Thumb-only gesture: index/middle/ring/pinky must stay folded.
        if (thumbExtended && !indexExtended && !middleExtended && !ringExtended && !pinkyExtended) {
            val wrist = hand[0]
            val thumbTip = hand[4]
            val thumbMcp = hand[2]
            val thumbUp = thumbTip.y() < thumbMcp.y() - 0.025f
            val thumbDown = thumbTip.y() > thumbMcp.y() + 0.025f
            if (thumbUp) return "THUMB_UP"
            if (thumbDown) return "THUMB_DOWN"
        }

        if (indexExtended && middleExtended && ringExtended && pinkyExtended) return "OPEN"
        return "NONE"
    }

    private fun isFingerExtended(hand: List<NormalizedLandmark>, tip: Int, pip: Int, mcp: Int): Boolean {
        val wrist = hand[0]
        val tipPoint = hand[tip]
        val pipPoint = hand[pip]
        val mcpPoint = hand[mcp]

        val tipFromWrist = distance(tipPoint, wrist)
        val pipFromWrist = distance(pipPoint, wrist)
        val mcpFromWrist = distance(mcpPoint, wrist)
        val jointAngle = angle(pipPoint, mcpPoint, tipPoint)

        return tipFromWrist > pipFromWrist * 1.10f &&
            tipFromWrist > mcpFromWrist * 1.35f &&
            jointAngle > 150.0
    }

    private fun isThumbExtended(hand: List<NormalizedLandmark>): Boolean {
        val wrist = hand[0]
        val thumbTip = hand[4]
        val thumbIp = hand[3]
        val thumbMcp = hand[2]
        val thumbCmc = hand[1]

        val tipDistance = distance(thumbTip, wrist)
        val ipDistance = distance(thumbIp, wrist)
        val cmcDistance = distance(thumbCmc, wrist)
        val jointAngle = angle(thumbIp, thumbMcp, thumbTip)

        return tipDistance > ipDistance * 1.08f &&
            tipDistance > cmcDistance * 1.30f &&
            jointAngle > 145.0
    }

    private fun isFist(hand: List<NormalizedLandmark>): Boolean {
        val indexFolded = isFingerFolded(hand, 8, 6, 5)
        val middleFolded = isFingerFolded(hand, 12, 10, 9)
        val ringFolded = isFingerFolded(hand, 16, 14, 13)
        val pinkyFolded = isFingerFolded(hand, 20, 18, 17)
        val thumbFolded = distance(hand[4], hand[5]) < distance(hand[3], hand[5]) * 1.20f
        return indexFolded && middleFolded && ringFolded && pinkyFolded && thumbFolded
    }

    private fun isFingerFolded(hand: List<NormalizedLandmark>, tip: Int, pip: Int, mcp: Int): Boolean {
        val wrist = hand[0]
        val tipDistance = distance(hand[tip], wrist)
        val mcpDistance = distance(hand[mcp], wrist)
        val pipDistance = distance(hand[pip], wrist)
        return tipDistance < pipDistance * 1.08f || tipDistance < mcpDistance * 1.45f
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
