package com.jarvis.assistant.ui.math

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ActivityMathBinding
import com.jarvis.assistant.util.MathEngine
import com.jarvis.assistant.util.MathSolver
import com.jarvis.assistant.util.ThemeManager
import com.jarvis.assistant.vision.CameraEyes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * Home -> Math card. Solves any kind of math from typed text, a gallery picture or a live camera
 * picture. Plain calculations are answered instantly on the device; everything else is solved
 * step by step with Gemini.
 */
class MathActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROBLEM = "problem"
        const val EXTRA_AUTO_SOLVE = "auto_solve"
    }

    private lateinit var binding: ActivityMathBinding
    private var pickedBitmap: Bitmap? = null
    private var topic = "auto"
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var useFront = false
    private var solveJob: Job? = null
    private var lastSolutionText = ""

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) loadFromUri(uri)
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startCameraPreview()
        } else {
            Toast.makeText(this, R.string.math_camera_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMathBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClear.setOnClickListener { clearAll() }

        binding.etProblem.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateLiveCalc() }
        })

        val chips = listOf(
            Pair(binding.chipAuto, "auto"),
            Pair(binding.chipAlgebra, "algebra"),
            Pair(binding.chipGeometry, "geometry"),
            Pair(binding.chipCalculus, "calculus"),
            Pair(binding.chipStats, "statistics"),
            Pair(binding.chipWord, "word problem")
        )
        for ((chip, key) in chips) {
            chip.setOnClickListener {
                topic = key
                styleChips(chips)
            }
        }

        binding.btnCamera.setOnClickListener {
            if (CameraEyes.hasPermission(this)) startCameraPreview()
            else cameraPermission.launch(android.Manifest.permission.CAMERA)
        }
        binding.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        binding.btnSolve.setOnClickListener { solve() }
        binding.btnCapture.setOnClickListener { capture() }
        binding.btnFlipCamera.setOnClickListener {
            useFront = !useFront
            startCameraPreview()
        }
        binding.btnCloseCamera.setOnClickListener { stopCameraPreview() }
        binding.btnRemoveImage.setOnClickListener { setPicked(null) }

        binding.btnCopy.setOnClickListener {
            if (lastSolutionText.isNotBlank()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("JARVIS math", lastSolutionText))
                Toast.makeText(this, R.string.popup_copied, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnShare.setOnClickListener {
            if (lastSolutionText.isNotBlank()) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, lastSolutionText)
                }
                startActivity(Intent.createChooser(send, getString(R.string.popup_share)))
            }
        }

        // Opened by voice ("solve this") with a problem prefilled.
        val presetProblem = intent.getStringExtra(EXTRA_PROBLEM).orEmpty()
        if (presetProblem.isNotBlank()) {
            binding.etProblem.setText(presetProblem)
            if (intent.getBooleanExtra(EXTRA_AUTO_SOLVE, false)) solve()
        }
    }

    override fun onStop() {
        stopCameraPreview()
        super.onStop()
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true) && tv.data != 0) tv.data else fallback
    }

    private fun styleChips(chips: List<Pair<TextView, String>>) {
        val accent = themeColor(R.attr.themeAccent, Color.parseColor("#2F6BFF"))
        val secondary = themeColor(R.attr.themeTextSecondary, Color.GRAY)
        for ((chip, key) in chips) {
            if (key == topic) {
                chip.setBackgroundResource(R.drawable.bg_chip_selected)
                chip.setTextColor(accent)
            } else {
                chip.setBackgroundResource(R.drawable.bg_chip_unselected)
                chip.setTextColor(secondary)
            }
        }
    }

    private fun updateLiveCalc() {
        val text = binding.etProblem.text?.toString().orEmpty()
        if (pickedBitmap == null && MathEngine.looksLikeExpression(text)) {
            val v = MathEngine.evaluate(text)
            if (v != null) {
                binding.tvLiveCalc.visibility = View.VISIBLE
                binding.tvLiveCalc.text = "= " + MathEngine.format(v)
                return
            }
        }
        binding.tvLiveCalc.visibility = View.GONE
    }

    private fun clearAll() {
        solveJob?.cancel()
        binding.etProblem.setText("")
        setPicked(null)
        binding.cardResult.visibility = View.GONE
        binding.layoutSolving.visibility = View.GONE
        binding.btnSolve.isEnabled = true
        lastSolutionText = ""
        stopCameraPreview()
    }

    private fun setPicked(bitmap: Bitmap?) {
        pickedBitmap = bitmap
        if (bitmap == null) {
            binding.cardImage.visibility = View.GONE
        } else {
            binding.ivPicked.setImageBitmap(bitmap)
            binding.cardImage.visibility = View.VISIBLE
        }
        updateLiveCalc()
    }

    // ------------------------------------------------------------------ pictures

    private fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var s = 1
        while (maxOf(width, height) / s > maxSide) s *= 2
        return s
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val m = Matrix()
        m.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun decodeBytes(bytes: ByteArray, rotation: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 2000)
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return rotate(bmp, rotation)
    }

    private fun loadFromUri(uri: Uri) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@withContext null
                    var rotation = 0
                    try {
                        val exif = ExifInterface(ByteArrayInputStream(bytes))
                        rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }
                    } catch (e: Exception) {
                        rotation = 0
                    }
                    decodeBytes(bytes, rotation)
                } catch (e: Exception) {
                    null
                }
            }
            if (bmp != null) setPicked(bmp)
            else Toast.makeText(this@MathActivity, R.string.math_need_input, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ live camera

    private fun startCameraPreview() {
        binding.cardCamera.visibility = View.VISIBLE
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(binding.previewView.surfaceProvider)
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                imageCapture = capture
                val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, capture)
            } catch (e: Exception) {
                binding.cardCamera.visibility = View.GONE
                Toast.makeText(this, R.string.math_camera_denied, Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCameraPreview() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            // ignore
        }
        imageCapture = null
        if (::binding.isInitialized) binding.cardCamera.visibility = View.GONE
    }

    private fun capture() {
        val capture = imageCapture ?: return
        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    var bmp: Bitmap? = null
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        bmp = decodeBytes(bytes, image.imageInfo.rotationDegrees)
                    } catch (e: Exception) {
                        bmp = null
                    } finally {
                        image.close()
                    }
                    if (bmp != null) {
                        setPicked(bmp)
                        stopCameraPreview()
                        solve()
                    } else {
                        Toast.makeText(this@MathActivity, R.string.math_camera_denied, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(this@MathActivity, R.string.math_camera_denied, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // ------------------------------------------------------------------ solving

    private fun solve() {
        val text = binding.etProblem.text?.toString()?.trim().orEmpty()
        val bmp = pickedBitmap
        if (text.isEmpty() && bmp == null) {
            Toast.makeText(this, R.string.math_need_input, Toast.LENGTH_SHORT).show()
            return
        }
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etProblem.windowToken, 0)

        solveJob?.cancel()
        val localLine: String? = if (bmp == null && MathEngine.looksLikeExpression(text)) {
            MathEngine.evaluate(text)?.let { text + " = " + MathEngine.format(it) }
        } else {
            null
        }

        binding.cardResult.visibility = View.VISIBLE
        binding.layoutResultActions.visibility = View.GONE
        binding.tvSolution.text = localLine ?: ""
        binding.layoutSolving.visibility = View.VISIBLE
        binding.btnSolve.isEnabled = false

        solveJob = lifecycleScope.launch {
            val jpeg: ByteArray? = if (bmp != null) {
                withContext(Dispatchers.Default) { MathSolver.encodeJpeg(bmp) }
            } else {
                null
            }
            val solution = MathSolver.solveJpeg(text, jpeg, topic)
            binding.layoutSolving.visibility = View.GONE
            binding.btnSolve.isEnabled = true

            val prefix = if (localLine != null) localLine + "\n\n" else ""
            val full = when {
                solution.error == null -> prefix + solution.text
                // The instant on-device answer is still correct when only the step-by-step part failed.
                localLine != null -> localLine
                else -> solution.error + (if (solution.detail != null) "\n(" + solution.detail + ")" else "")
            }
            lastSolutionText = full
            binding.tvSolution.text = full
            binding.layoutResultActions.visibility = View.VISIBLE
        }
    }
}
