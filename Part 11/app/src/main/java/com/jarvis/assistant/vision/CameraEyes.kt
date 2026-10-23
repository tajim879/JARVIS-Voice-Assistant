package com.jarvis.assistant.vision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.jarvis.assistant.background.JarvisConversationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * "Jarvis Eyes": while switched on, the FRONT camera sends one small picture per second to the
 * live Gemini session so Jarvis can see you and your surroundings.
 *
 * Rules (privacy and Android limits):
 *  - opt-in only, off by default, and Android shows its own camera indicator while it runs;
 *  - works while the JARVIS app is open on screen (Android blocks hidden camera use);
 *  - pictures are only sent while a live session is running, and are never saved to disk.
 */
object CameraEyes {

    private const val TAG = "CameraEyes"
    private const val PREFS = "jarvis_eyes"
    private const val KEY_ENABLED = "enabled"
    private const val FRAME_INTERVAL_MS = 1000L
    private const val MAX_SIDE = 640
    private const val JPEG_QUALITY = 60
    private const val FRESH_MS = 8000L

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    @Volatile private var lastFrameJpeg: ByteArray? = null
    @Volatile private var lastFrameAt: Long = 0L
    @Volatile private var lastSentAt: Long = 0L

    private var provider: ProcessCameraProvider? = null
    private var boundActivity: WeakReference<AppCompatActivity>? = null
    private val analysisExecutor by lazy { Executors.newSingleThreadExecutor() }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Newest front-camera picture, or null when Eyes is off or the picture is older than a few seconds. */
    fun freshFrame(): ByteArray? {
        val frame = lastFrameJpeg ?: return null
        return if (SystemClock.elapsedRealtime() - lastFrameAt <= FRESH_MS) frame else null
    }

    /** Saves the choice and starts/stops the camera right away when the app is on screen. */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) {
            stop()
            return
        }
        val activity = boundActivity?.get()
        if (activity != null) startIfAllowed(activity)
    }

    /** Called by MainActivity.onResume. */
    fun onActivityResumed(activity: AppCompatActivity) {
        boundActivity = WeakReference(activity)
        if (isEnabled(activity)) startIfAllowed(activity)
    }

    /** Called by MainActivity.onPause: the camera is released whenever the app leaves the screen. */
    fun onActivityPaused(activity: AppCompatActivity) {
        if (boundActivity?.get() === activity) stop()
    }

    private fun startIfAllowed(activity: AppCompatActivity) {
        if (!hasPermission(activity)) return
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 640))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    try {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastSentAt >= FRAME_INTERVAL_MS) {
                            val raw = proxy.toBitmap()
                            val jpeg = encode(raw, proxy.imageInfo.rotationDegrees)
                            lastSentAt = now
                            lastFrameJpeg = jpeg
                            lastFrameAt = now
                            JarvisConversationService.sendCameraFrame(jpeg)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "frame failed: " + e.message)
                    } finally {
                        proxy.close()
                    }
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                _active.value = true
            } catch (e: Exception) {
                Log.w(TAG, "could not start front camera: " + e.message)
                _active.value = false
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    private fun stop() {
        try {
            provider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "stop failed: " + e.message)
        }
        _active.value = false
    }

    private fun encode(source: Bitmap, rotationDegrees: Int): ByteArray {
        val matrix = Matrix()
        if (rotationDegrees != 0) matrix.postRotate(rotationDegrees.toFloat())
        val longest = maxOf(source.width, source.height).toFloat()
        if (longest > MAX_SIDE) {
            val scale = MAX_SIDE / longest
            matrix.postScale(scale, scale)
        }
        val out = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        val stream = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        if (out !== source) out.recycle()
        return stream.toByteArray()
    }
}
