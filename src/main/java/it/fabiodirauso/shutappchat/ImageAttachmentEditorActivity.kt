package it.fabiodirauso.shutappchat

import android.app.AlertDialog
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import it.fabiodirauso.shutappchat.databinding.ActivityImageAttachmentEditorBinding
import it.fabiodirauso.shutappchat.ui.DrawingCanvasView
import java.io.File
import java.io.FileOutputStream

class ImageAttachmentEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageAttachmentEditorBinding
    private var imageUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageAttachmentEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Modalità immersive vera: nasconde status e navigation bar
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        )

        imageUri = intent.getParcelableExtra("image_uri")
        loadImage()
        setupListeners()
        setupTextLongPressHandler()
    }

    private fun loadImage() {
        imageUri?.let { uri ->
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            binding.cropImageView.setImageBitmapWithReset(bitmap)
        }
    }

    private fun setupListeners() {
        binding.buttonBack.setOnClickListener {
            finish()
        }

        binding.buttonDone.setOnClickListener {
            saveAndSend()
        }

        binding.buttonAddEmoji.setOnClickListener {
            showEmojiPicker()
        }

        binding.buttonAddText.setOnClickListener {
            showSimpleTextInput()
        }

        binding.buttonUndo.setOnClickListener {
            binding.drawingCanvas.removeLastElement()
        }
    }

    private fun setupTextLongPressHandler() {
        binding.drawingCanvas.onTextLongPress = { element, index ->
            showTextStyleDialog(element, index)
        }
    }

    private fun showSimpleTextInput() {
        val editText = EditText(this).apply {
            hint = "Inserisci testo..."
            setPadding(32, 32, 32, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Aggiungi Testo")
            .setMessage("Dopo l'\''inserimento, tocca il testo per selezionarlo e ridimensionarlo trascinando gli angoli. Tieni premuto per modificare lo stile.")
            .setView(editText)
            .setPositiveButton("Aggiungi") { _, _ ->
                val text = editText.text.toString()
                if (text.isNotEmpty()) {
                    binding.drawingCanvas.addText(text)
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showTextStyleDialog(element: DrawingCanvasView.DrawingElement, index: Int) {
        // Create a context wrapper with MaterialComponents theme
        val contextWrapper = androidx.appcompat.view.ContextThemeWrapper(
            this,
            com.google.android.material.R.style.Theme_MaterialComponents_Dialog
        )
        val dialogView = LayoutInflater.from(contextWrapper).inflate(R.layout.dialog_text_style, null)
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("Stile Testo")
            .setView(dialogView)
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()

        // Get views
        val textPreview = dialogView.findViewById<TextView>(R.id.textPreview)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.editTextContent)
        val chipGroupFont = dialogView.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupFont)
        val chipBold = dialogView.findViewById<Chip>(R.id.chipBold)
        val chipItalic = dialogView.findViewById<Chip>(R.id.chipItalic)
        val chipUnderline = dialogView.findViewById<Chip>(R.id.chipUnderline)
        val spinnerTextColor = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerTextColor)
        val switchBackground = dialogView.findViewById<SwitchMaterial>(R.id.switchBackground)
        val spinnerBgColor = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerBgColor)

        // Setup color spinners
        val colorNames = arrayOf("Bianco", "Nero", "Rosso", "Arancione", "Giallo", "Verde", "Ciano", "Blu", "Magenta", "Rosa")
        val colorValues = intArrayOf(
            Color.WHITE, Color.BLACK, Color.RED, Color.rgb(255, 165, 0),
            Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE,
            Color.MAGENTA, Color.rgb(255, 192, 203)
        )

        spinnerTextColor.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, colorNames)
        spinnerTextColor.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                textPreview.setTextColor(colorValues[position])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        spinnerBgColor.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, colorNames)
        spinnerBgColor.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                textPreview.background = ColorDrawable(colorValues[position])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Pre-fill with current values
        editText.setText(element.content)
        textPreview.text = element.content
        textPreview.textSize = 24f
        textPreview.setTextColor(element.color)
        chipBold.isChecked = element.isBold
        chipItalic.isChecked = element.isItalic
        chipUnderline.isChecked = element.isUnderline

        // Set color spinner selection
        val currentColorIndex = colorValues.indexOf(element.color).takeIf { it >= 0 } ?: 0
        spinnerTextColor.setSelection(currentColorIndex)

        // Set font selection
        when (element.typeface) {
            Typeface.SANS_SERIF -> chipGroupFont.check(R.id.chipFontSans)
            Typeface.SERIF -> chipGroupFont.check(R.id.chipFontSerif)
            Typeface.MONOSPACE -> chipGroupFont.check(R.id.chipFontMono)
            else -> chipGroupFont.check(R.id.chipFontDefault)
        }

        // Background
        if (element.backgroundColor != null) {
            switchBackground.isChecked = true
            spinnerBgColor.visibility = View.VISIBLE
            textPreview.background = ColorDrawable(element.backgroundColor)
            val bgColorIndex = colorValues.indexOf(element.backgroundColor).takeIf { it >= 0 } ?: 1
            spinnerBgColor.setSelection(bgColorIndex)
        }

        // Apply current style to preview
        updatePreviewStyle(textPreview, chipGroupFont, chipBold, chipItalic, chipUnderline)

        // Live preview updates
        editText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                textPreview.text = s?.toString() ?: "Anteprima"
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        chipGroupFont.setOnCheckedChangeListener { _, _ ->
            updatePreviewStyle(textPreview, chipGroupFont, chipBold, chipItalic, chipUnderline)
        }

        chipBold.setOnCheckedChangeListener { _, _ ->
            updatePreviewStyle(textPreview, chipGroupFont, chipBold, chipItalic, chipUnderline)
        }

        chipItalic.setOnCheckedChangeListener { _, _ ->
            updatePreviewStyle(textPreview, chipGroupFont, chipBold, chipItalic, chipUnderline)
        }

        chipUnderline.setOnCheckedChangeListener { _, isChecked ->
            textPreview.paintFlags = if (isChecked) {
                textPreview.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            } else {
                textPreview.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            }
        }

        switchBackground.setOnCheckedChangeListener { _, isChecked ->
            spinnerBgColor.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                textPreview.background = null
            }
        }

        dialog.show()

        // Override positive button to save
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = editText.text.toString()
            if (text.isNotEmpty()) {
                val typeface = when (chipGroupFont.checkedChipId) {
                    R.id.chipFontSans -> Typeface.SANS_SERIF
                    R.id.chipFontSerif -> Typeface.SERIF
                    R.id.chipFontMono -> Typeface.MONOSPACE
                    else -> Typeface.DEFAULT
                }

                val isBold = chipBold.isChecked
                val isItalic = chipItalic.isChecked
                val isUnderline = chipUnderline.isChecked
                val color = textPreview.currentTextColor
                val bgColor = if (switchBackground.isChecked) {
                    (textPreview.background as? ColorDrawable)?.color
                } else null

                binding.drawingCanvas.updateTextElement(
                    index, text, element.size, color, typeface,
                    isBold, isItalic, isUnderline, bgColor
                )
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Inserisci del testo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePreviewStyle(
        textView: TextView,
        chipGroupFont: com.google.android.material.chip.ChipGroup,
        chipBold: Chip,
        chipItalic: Chip,
        chipUnderline: Chip
    ) {
        val baseTypeface = when (chipGroupFont.checkedChipId) {
            R.id.chipFontSans -> Typeface.SANS_SERIF
            R.id.chipFontSerif -> Typeface.SERIF
            R.id.chipFontMono -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }

        val style = when {
            chipBold.isChecked && chipItalic.isChecked -> Typeface.BOLD_ITALIC
            chipBold.isChecked -> Typeface.BOLD
            chipItalic.isChecked -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }

        textView.typeface = Typeface.create(baseTypeface, style)
        textView.paintFlags = if (chipUnderline.isChecked) {
            textView.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        } else {
            textView.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        }
    }

    private fun showEmojiPicker() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_emoji, null)
        bottomSheetDialog.setContentView(view)

        val emojiCategories = mapOf(
            R.id.gridEmojiFaces to listOf(
                "😀", "😃", "😄", "😁", "😅", "😂", "🤣", "😊", "😇", "🙂",
                "😉", "😍", "🥰", "😘", "😗", "😙", "😚", "😋", "😛", "😝",
                "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🥳", "😏", "😒", "😞"
            ),
            R.id.gridEmojiHands to listOf(
                "👋", "🤚", "🖐", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞",
                "🤟", "🤘", "🤙", "👈", "👉", "👆", "🖕", "👇", "☝️", "👍",
                "👎", "✊", "👊", "🤛", "🤜", "👏", "🙌", "👐", "🤲", "🙏"
            ),
            R.id.gridEmojiHearts to listOf(
                "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔",
                "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "☮️",
                "✝️", "☪️", "🕉", "☸️", "✡️", "🔯", "🕎", "☯️", "☦️", "🛐"
            ),
            R.id.gridEmojiObjects to listOf(
                "🎉", "🎊", "🎈", "🎁", "🏆", "🥇", "🥈", "🥉", "⚽", "🏀",
                "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🥏", "🎱", "🪀", "🏓",
                "🏸", "🏒", "🏑", "🥍", "🏏", "🥅", "⛳", "🪁", "🏹", "🎣"
            )
        )

        emojiCategories.forEach { (gridId, emojis) ->
            val grid = view.findViewById<android.widget.GridLayout>(gridId)
            emojis.forEach { emoji ->
                val button = android.widget.Button(this).apply {
                    text = emoji
                    textSize = 24f
                    setPadding(8, 8, 8, 8)
                    background = null
                    setOnClickListener {
                        binding.drawingCanvas.addEmoji(emoji)
                        bottomSheetDialog.dismiss()
                    }
                }
                grid.addView(button)
            }
        }

        bottomSheetDialog.show()
    }

    private fun saveAndSend() {
        try {
            val croppedBitmap = binding.cropImageView.getCroppedBitmap() ?: run {
                Toast.makeText(this, "Errore: immagine non disponibile", Toast.LENGTH_SHORT).show()
                return
            }
            val elements = binding.drawingCanvas.getElements()

            // Create final bitmap
            val finalBitmap = Bitmap.createBitmap(
                croppedBitmap.width,
                croppedBitmap.height,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(finalBitmap)
            canvas.drawBitmap(croppedBitmap, 0f, 0f, null)

            // Scale factors from canvas to final bitmap
            val scaleX = croppedBitmap.width.toFloat() / binding.cropImageView.width
            val scaleY = croppedBitmap.height.toFloat() / binding.cropImageView.height

            // Draw elements
            elements.forEach { element ->
                val scaledX = element.x * scaleX
                val scaledY = element.y * scaleY
                val scaledSize = element.size * ((scaleX + scaleY) / 2)

                // Save canvas state and apply rotation
                canvas.save()
                canvas.rotate(element.rotation, scaledX, scaledY)

                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = scaledSize
                    color = element.color
                    
                    if (element.type == DrawingCanvasView.ElementType.TEXT) {
                        var style = Typeface.NORMAL
                        if (element.isBold && element.isItalic) style = Typeface.BOLD_ITALIC
                        else if (element.isBold) style = Typeface.BOLD
                        else if (element.isItalic) style = Typeface.ITALIC
                        
                        typeface = Typeface.create(element.typeface, style)
                        isUnderlineText = element.isUnderline
                    }
                }

                // Draw background if specified
                element.backgroundColor?.let { bgColor ->
                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
                    val bounds = Rect()
                    paint.getTextBounds(element.content, 0, element.content.length, bounds)
                    val textWidth = paint.measureText(element.content)
                    val textHeight = bounds.height().toFloat()
                    
                    val left = scaledX - 16f * scaleX
                    val top = scaledY - textHeight - 8f * scaleY
                    val right = scaledX + textWidth + 16f * scaleX
                    val bottom = scaledY + 8f * scaleY
                    
                    canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, bgPaint)
                }

                // Draw text with outline
                val outlinePaint = Paint(paint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 4f * scaleX
                    setColor(Color.BLACK)
                }
                canvas.drawText(element.content, scaledX, scaledY, outlinePaint)
                canvas.drawText(element.content, scaledX, scaledY, paint)
                
                // Restore canvas state
                canvas.restore()
            }

            // Save to temp file
            val tempFile = File(cacheDir, "edited_image_${System.currentTimeMillis()}.jpg")
            FileOutputStream(tempFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            val resultUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                tempFile
            )

            val resultIntent = Intent().apply {
                putExtra("edited_image_uri", resultUri)
            }
            setResult(RESULT_OK, resultIntent)
            finish()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Errore durante il salvataggio: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.cropImageView.release()
    }
}
