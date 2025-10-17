package it.fabiodirauso.shutappchat

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import it.fabiodirauso.shutappchat.databinding.ActivityProfileImageEditorBinding
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileImageEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EXTRA_RESULT_PATH = "extra_result_path"

        private const val DEFAULT_TEXT_SIZE_SP = 24
        private const val MIN_TEXT_SIZE_SP = 12
        private const val DEFAULT_OUTPUT_SIZE = 512
        private const val RESIZE_MIN = 256
        private const val RESIZE_STEP = 64
    }

    private lateinit var binding: ActivityProfileImageEditorBinding
    private var imageUri: Uri? = null
    private var baseBitmap: Bitmap? = null

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var overlayMovedByUser = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileImageEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Enable fullscreen immersive mode
        it.fabiodirauso.shutappchat.utils.UIHelper.enableImmersiveMode(this)

        setSupportActionBar(binding.toolbarEditor)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbarEditor.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        imageUri = intent.getStringExtra(EXTRA_IMAGE_URI)?.let { Uri.parse(it) }
        if (imageUri == null) {
            Toast.makeText(this, "Impossibile aprire l'immagine", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.buttonApply.isEnabled = false

        imageUri?.let { uri ->
            lifecycleScope.launch {
                loadBitmap(uri)
            }
        }
        binding.frameImageEditor.doOnLayout { centerOverlay() }

        setupOverlayControls()
        setupButtons()
    setupResizeControl()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupOverlayControls() {
        binding.textOverlay.isVisible = false
        binding.seekBarTextSize.progress = DEFAULT_TEXT_SIZE_SP - MIN_TEXT_SIZE_SP

        binding.editTextOverlay.setOnEditorActionListener { v, _, _ ->
            v.clearFocus()
            true
        }

        binding.editTextOverlay.addTextChangedListener { editable ->
            val text = editable?.toString().orEmpty()
            binding.textOverlay.text = text
            binding.textOverlay.isVisible = text.isNotBlank()
            if (text.isNotBlank() && !overlayMovedByUser) {
                binding.textOverlay.post { centerOverlay() }
            }
            if (text.isBlank()) {
                overlayMovedByUser = false
            }
        }

        binding.seekBarTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateOverlayTextSize(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.radioGroupTextColor.setOnCheckedChangeListener { group, checkedId ->
            val radioButton = group.findViewById<RadioButton>(checkedId)
            val colorTag = radioButton?.tag as? String
            colorTag?.let {
                try {
                    binding.textOverlay.setTextColor(Color.parseColor(it))
                } catch (_: IllegalArgumentException) {
                    binding.textOverlay.setTextColor(Color.WHITE)
                }
            }
        }

        binding.textOverlay.setOnTouchListener { view, event ->
            handleOverlayDrag(view, event)
        }

        updateOverlayTextSize(binding.seekBarTextSize.progress)
    }

    private fun setupButtons() {
        binding.buttonCancel.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        binding.buttonReset.setOnClickListener {
            binding.cropImageView.reset()
            binding.editTextOverlay.setText("")
            binding.radioGroupTextColor.check(R.id.radioColorWhite)
            binding.seekBarTextSize.progress = DEFAULT_TEXT_SIZE_SP - MIN_TEXT_SIZE_SP
            binding.seekBarResize.progress = valueToProgress(DEFAULT_OUTPUT_SIZE)
            updateResizeLabel(DEFAULT_OUTPUT_SIZE)
            binding.textOverlay.isVisible = false
            overlayMovedByUser = false
            centerOverlay()
        }
        
        // Zoom controls
        binding.buttonZoomIn.setOnClickListener {
            binding.cropImageView.zoomIn()
        }
        
        binding.buttonZoomOut.setOnClickListener {
            binding.cropImageView.zoomOut()
        }
        
        // Rotate control
        binding.buttonRotate.setOnClickListener {
            binding.cropImageView.rotate90Degrees()
        }

        binding.buttonApply.setOnClickListener {
            val originalText = binding.buttonApply.text
            binding.buttonApply.isEnabled = false
            binding.buttonApply.text = getString(R.string.profile_editor_processing)

            lifecycleScope.launch {
                try {
                    processImageAndReturn()
                } finally {
                    binding.buttonApply.isEnabled = true
                    binding.buttonApply.text = originalText
                }
            }
        }
    }

    private fun setupResizeControl() {
        binding.seekBarResize.progress = valueToProgress(DEFAULT_OUTPUT_SIZE)
        updateResizeLabel(DEFAULT_OUTPUT_SIZE)
        binding.seekBarResize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    updateResizeLabel(resizeProgressToValue(progress))
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateResizeLabel(value: Int) {
        binding.textViewResizeValue.text = getString(R.string.profile_editor_output_value, value)
    }

    private fun updateOverlayTextSize(progress: Int) {
    val sizeSp = (MIN_TEXT_SIZE_SP + progress).toFloat()
    binding.textOverlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    private fun centerOverlay() {
        val parent = binding.frameImageEditor
        val overlay = binding.textOverlay
        parent.post {
            if (!overlay.isVisible || parent.width == 0 || parent.height == 0) return@post
            overlay.x = (parent.width - overlay.width) / 2f
            overlay.y = (parent.height - overlay.height) / 2f
        }
    }

    private fun handleOverlayDrag(view: View, event: MotionEvent): Boolean {
        val parent = binding.frameImageEditor
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                overlayMovedByUser = true
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastTouchX
                val dy = event.rawY - lastTouchY

                var newX = view.x + dx
                var newY = view.y + dy

                if (parent.width > 0) {
                    newX = newX.coerceIn(0f, (parent.width - view.width).toFloat())
                }
                if (parent.height > 0) {
                    newY = newY.coerceIn(0f, (parent.height - view.height).toFloat())
                }

                view.x = newX
                view.y = newY

                lastTouchX = event.rawX
                lastTouchY = event.rawY
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                return true
            }
        }
        return false
    }

    private suspend fun processImageAndReturn() {
        try {
            val overlayInfo = captureOverlayInfo()

            val croppedBitmap = binding.cropImageView.getCroppedBitmap() ?: run {
                Toast.makeText(this, "Impossibile elaborare l'immagine", Toast.LENGTH_LONG).show()
                return
            }

            val targetSize = currentResizeValue()

            val resizedBitmap = withContext(Dispatchers.Default) {
                resizeBitmap(croppedBitmap, targetSize)
            }

            val finalBitmap = withContext(Dispatchers.Default) {
                applyOverlayText(resizedBitmap, overlayInfo)
            }

            val resultFile = withContext(Dispatchers.IO) {
                val file = File(cacheDir, "profile_edit_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { outputStream ->
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                }
                file
            }

            if (finalBitmap !== resizedBitmap && !resizedBitmap.isRecycled) {
                resizedBitmap.recycle()
            }
            if (croppedBitmap !== resizedBitmap && !croppedBitmap.isRecycled) {
                croppedBitmap.recycle()
            }
            if (!finalBitmap.isRecycled) {
                finalBitmap.recycle()
            }

            val resultIntent = Intent().apply {
                putExtra(EXTRA_RESULT_PATH, resultFile.absolutePath)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Errore durante l'elaborazione: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun loadBitmap(uri: Uri) {
        try {
            android.util.Log.d("ProfileImageEditor", "Loading bitmap from URI: $uri")
            val bitmap = withContext(Dispatchers.IO) { decodeBitmap(uri) }
            if (bitmap == null) {
                android.util.Log.e("ProfileImageEditor", "Failed to decode bitmap from URI: $uri")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ProfileImageEditorActivity, "Impossibile caricare l'immagine", Toast.LENGTH_LONG).show()
                    finish()
                }
                return
            }
            android.util.Log.d("ProfileImageEditor", "Bitmap loaded successfully: ${bitmap.width}x${bitmap.height}")
            baseBitmap = bitmap
            binding.cropImageView.setImageBitmapWithReset(bitmap)
            binding.buttonApply.isEnabled = true
        } catch (e: Exception) {
            android.util.Log.e("ProfileImageEditor", "Error loading bitmap", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ProfileImageEditorActivity, "Errore apertura immagine: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        val maxDimension = 2048
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        
        // Prima lettura: ottieni le dimensioni senza caricare il bitmap
        try {
            val inputStream = when (uri.scheme) {
                ContentResolver.SCHEME_FILE -> {
                    val path = uri.path
                    android.util.Log.d("ProfileImageEditor", "URI scheme: file, path: $path")
                    if (path == null) {
                        android.util.Log.e("ProfileImageEditor", "URI path is null")
                        return null
                    }
                    val file = File(path)
                    android.util.Log.d("ProfileImageEditor", "File exists: ${file.exists()}, canRead: ${file.canRead()}, absolutePath: ${file.absolutePath}")
                    if (!file.exists()) {
                        android.util.Log.e("ProfileImageEditor", "File does not exist: ${file.absolutePath}")
                        return null
                    }
                    if (!file.canRead()) {
                        android.util.Log.e("ProfileImageEditor", "File not readable: ${file.absolutePath}")
                        return null
                    }
                    FileInputStream(file)
                }
                ContentResolver.SCHEME_CONTENT -> {
                    contentResolver.openInputStream(uri)
                }
                else -> {
                    android.util.Log.e("ProfileImageEditor", "Unsupported URI scheme: ${uri.scheme}")
                    return null
                }
            }
            
            if (inputStream == null) {
                android.util.Log.e("ProfileImageEditor", "InputStream is null for URI: $uri")
                return null
            }
            
            android.util.Log.d("ProfileImageEditor", "Reading image dimensions with inJustDecodeBounds=true...")
            inputStream.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
            
            android.util.Log.d("ProfileImageEditor", "Image dimensions: ${options.outWidth}x${options.outHeight}")
        } catch (e: Exception) {
            android.util.Log.e("ProfileImageEditor", "Error reading image dimensions", e)
            return null
        }

        if (options.outWidth <= 0 || options.outHeight <= 0) {
            android.util.Log.e("ProfileImageEditor", "Invalid image dimensions: ${options.outWidth}x${options.outHeight}")
            return null
        }

        // Calcola il sample size e prepara per il caricamento reale
        options.inSampleSize = calculateInSampleSize(options, maxDimension, maxDimension)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        
        android.util.Log.d("ProfileImageEditor", "Decoding full bitmap with inSampleSize=${options.inSampleSize}...")

        // Seconda lettura: carica il bitmap effettivo
        return try {
            val inputStream2 = when (uri.scheme) {
                ContentResolver.SCHEME_FILE -> FileInputStream(File(uri.path!!))
                ContentResolver.SCHEME_CONTENT -> contentResolver.openInputStream(uri)
                else -> null
            }
            
            val bitmap = inputStream2?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
            
            if (bitmap == null) {
                android.util.Log.e("ProfileImageEditor", "Failed to decode bitmap from URI: $uri")
            } else {
                android.util.Log.d("ProfileImageEditor", "Bitmap loaded successfully: ${bitmap.width}x${bitmap.height}")
            }
            bitmap
        } catch (e: Exception) {
            android.util.Log.e("ProfileImageEditor", "Error decoding bitmap", e)
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun resizeBitmap(bitmap: Bitmap, targetMaxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val maxDim = maxOf(width, height)
        if (maxDim <= targetMaxSize) {
            return bitmap
        }

        val scale = targetMaxSize.toFloat() / maxDim.toFloat()
        val newWidth = (width * scale).toInt().coerceAtLeast(1)
        val newHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun currentResizeValue(): Int = resizeProgressToValue(binding.seekBarResize.progress)

    private fun resizeProgressToValue(progress: Int): Int = RESIZE_MIN + (progress * RESIZE_STEP)

    private fun valueToProgress(value: Int): Int = ((value - RESIZE_MIN) / RESIZE_STEP).coerceAtLeast(0)

    private fun applyOverlayText(bitmap: Bitmap, overlayInfo: OverlayInfo?): Bitmap {
        val info = overlayInfo ?: return bitmap
        if (info.text.isBlank()) return bitmap

        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = info.color
            textAlign = Paint.Align.CENTER
            textSize = info.textSizePx * (mutableBitmap.height.toFloat() / info.parentHeight.toFloat())
            setShadowLayer(4f, 2f, 2f, Color.argb(153, 0, 0, 0))
            typeface = info.typeface
        }

        val canvasX = info.centerXRatio * mutableBitmap.width
        val canvasY = info.centerYRatio * mutableBitmap.height

        val metrics = paint.fontMetrics
        val baseline = canvasY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(info.text, canvasX, baseline, paint)

        return mutableBitmap
    }

    private fun captureOverlayInfo(): OverlayInfo? {
        val overlay = binding.textOverlay
        val parent = binding.frameImageEditor

        if (!overlay.isVisible) return null
        if (parent.width == 0 || parent.height == 0) return null

        val text = overlay.text?.toString().orEmpty()
        if (text.isBlank()) return null

        val centerX = overlay.x + overlay.width / 2f
        val centerY = overlay.y + overlay.height / 2f

        return OverlayInfo(
            text = text,
            centerXRatio = centerX / parent.width.toFloat(),
            centerYRatio = centerY / parent.height.toFloat(),
            textSizePx = overlay.textSize,
            color = overlay.currentTextColor,
            typeface = overlay.typeface,
            parentHeight = parent.height.toFloat()
        )
    }

    private data class OverlayInfo(
        val text: String,
        val centerXRatio: Float,
        val centerYRatio: Float,
        val textSizePx: Float,
        val color: Int,
        val typeface: Typeface?,
        val parentHeight: Float
    )

    override fun onDestroy() {
        super.onDestroy()
        binding.cropImageView.release()
        baseBitmap?.let {
            if (!it.isRecycled) it.recycle()
        }
        baseBitmap = null
    }
}