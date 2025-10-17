package it.fabiodirauso.shutappchat

import android.content.ContentValues
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import it.fabiodirauso.shutappchat.database.AppDatabase
import it.fabiodirauso.shutappchat.databinding.ActivityMediaViewerBinding
import it.fabiodirauso.shutappchat.managers.SecuritySettingsManager
import it.fabiodirauso.shutappchat.services.ChatMediaService
import it.fabiodirauso.shutappchat.network.RetrofitClient
import it.fabiodirauso.shutappchat.utils.UIHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.OutputStream

class MediaViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEDIA_ID = "media_id"
        const val EXTRA_ENCRYPTED_KEY = "encrypted_key"
        const val EXTRA_IV = "iv"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_MIME_TYPE = "mime_type"
        const val EXTRA_SALVABLE = "salvable"
        const val EXTRA_SENDER_AUTO_DELETE = "sender_auto_delete"
        const val EXTRA_MESSAGE_ID = "message_id"
    }

    private lateinit var binding: ActivityMediaViewerBinding
    private lateinit var chatMediaService: ChatMediaService
    private lateinit var securityManager: SecuritySettingsManager
    private lateinit var sharedPreferences: SharedPreferences
    private var downloadedMediaFile: File? = null
    private var currentBitmap: Bitmap? = null
    private var shouldAutoDelete = false
    private var isSalvable = true
    private var messageId: String? = null
    private var exoPlayer: ExoPlayer? = null
    private var isVideo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        securityManager = SecuritySettingsManager.getInstance(this)
        
        // Leggi subito salvable per impostare FLAG_SECURE PRIMA di mostrare la UI
        val salvable = intent.getBooleanExtra(EXTRA_SALVABLE, true)
        isSalvable = salvable
        
        // IMPORTANTE: Imposta FLAG_SECURE PRIMA di setContentView per bloccare screenshot
        if (!salvable) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
            Log.d("MediaViewer", "Screenshot protection enabled (salvable=false)")
        }
        
        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Abilita immersive mode per visualizzazione fullscreen
        UIHelper.enableImmersiveMode(this)

        chatMediaService = ChatMediaService(this)

        // Set auth token for API calls
        val token = sharedPreferences.getString("auth_token", "") ?: ""
        RetrofitClient.setAuthToken(token)
        Log.d("MediaViewer", "Auth token set for download: ${if (token.isNotEmpty()) "present (${token.length} chars)" else "missing"}")

        supportActionBar?.hide()

        val mediaId = intent.getStringExtra(EXTRA_MEDIA_ID)
        val encryptedKey = intent.getStringExtra(EXTRA_ENCRYPTED_KEY)
        val iv = intent.getStringExtra(EXTRA_IV)
        val filename = intent.getStringExtra(EXTRA_FILENAME)
        messageId = intent.getStringExtra(EXTRA_MESSAGE_ID)
        
        // Configura pulsante salva in base a salvable
        if (salvable) {
            binding.buttonSave.isEnabled = true
            binding.buttonSave.alpha = 1.0f
        } else {
            binding.buttonSave.isEnabled = false
            binding.buttonSave.alpha = 0.3f
        }

        // Avviso se il media non è salvabile
        if (!salvable) {
            Toast.makeText(this, "⚠️ Media protetto: screenshot e salvataggio disabilitati", Toast.LENGTH_LONG).show()
        }

        // Setup auto-delete: usa l'impostazione del MITTENTE (non del ricevente)
        // Il mittente decide se il media deve essere auto-cancellato dopo la visualizzazione
        shouldAutoDelete = intent.getBooleanExtra(EXTRA_SENDER_AUTO_DELETE, false)
        Log.d("MediaViewer", "Auto-delete check: shouldAutoDelete=$shouldAutoDelete (from sender's settings)")
        
        if (shouldAutoDelete) {
            Toast.makeText(this, "🔒 Media con auto-delete: verrà eliminato dopo la chiusura", Toast.LENGTH_SHORT).show()
        }

        binding.buttonClose.setOnClickListener {
            finish()
        }
        
        binding.buttonSave.setOnClickListener {
            if (isSalvable) {
                if (isVideo) {
                    saveVideoToGallery()
                } else {
                    saveImageToGallery()
                }
            } else {
                Toast.makeText(this, "⚠️ Media protetto: salvataggio non consentito", Toast.LENGTH_SHORT).show()
            }
        }

        if (mediaId != null && encryptedKey != null && iv != null) {
            loadAndDisplayMedia(mediaId, encryptedKey, iv)
        } else {
            Toast.makeText(this, "Dati media non validi", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadAndDisplayMedia(mediaId: String, encryptedKey: String, iv: String) {
        // Mostra loading spinner generico (progress viene mostrato nella chat, non qui)
        binding.progressBarLoading.visibility = View.VISIBLE
        binding.imageViewFullscreen.visibility = View.GONE
        binding.playerViewVideo.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val mediaFile = withContext(Dispatchers.IO) {
                    chatMediaService.downloadChatMedia(
                        mediaId = mediaId,
                        encryptedKey = encryptedKey,
                        iv = iv,
                        onProgress = null  // ❌ Non mostriamo più progress qui
                    )
                }

                binding.progressBarLoading.visibility = View.GONE

                if (mediaFile != null && mediaFile.exists()) {
                    downloadedMediaFile = mediaFile
                    
                    // Determina se è un video o un'immagine
                    // Prima prova con MIME type dall'Intent (dal DB), fallback su file extension
                    val actualMimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: getMimeType(mediaFile)
                    isVideo = actualMimeType.startsWith("video/")
                    
                    Log.d("MediaViewer", "Media loaded: mimeType=$actualMimeType (from Intent=${intent.getStringExtra(EXTRA_MIME_TYPE)}), isVideo=$isVideo")
                    
                    // 🎯 GENERA THUMBNAIL PER VIDEO DOPO DOWNLOAD COMPLETATO
                    if (isVideo && messageId != null) {
                        generateAndSaveThumbnail(mediaFile, messageId!!)
                    }
                    
                    if (isVideo) {
                        // Mostra video player
                        displayVideo(mediaFile)
                    } else {
                        // Mostra immagine
                        val bitmap = BitmapFactory.decodeFile(mediaFile.absolutePath)
                        currentBitmap = bitmap
                        binding.imageViewFullscreen.setImageBitmap(bitmap)
                        binding.imageViewFullscreen.visibility = View.VISIBLE
                    }
                } else {
                    Toast.makeText(this@MediaViewerActivity, "Errore download media", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e("MediaViewer", "Error loading media", e)
                binding.progressBarLoading.visibility = View.GONE
                Toast.makeText(this@MediaViewerActivity, "Errore caricamento", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    private fun displayVideo(videoFile: File) {
        try {
            // Inizializza ExoPlayer
            exoPlayer = ExoPlayer.Builder(this).build()
            binding.playerViewVideo.player = exoPlayer
            
            // Prepara il video
            val videoUri = Uri.fromFile(videoFile)
            val mediaItem = MediaItem.fromUri(videoUri)
            
            exoPlayer?.apply {
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = false // Non auto-play, l'utente clicca play
                
                // Listener per eventi
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                Log.d("MediaViewer", "Video ready to play")
                            }
                            Player.STATE_ENDED -> {
                                Log.d("MediaViewer", "Video playback ended")
                            }
                            Player.STATE_BUFFERING -> {
                                Log.d("MediaViewer", "Video buffering")
                            }
                        }
                    }
                })
            }
            
            binding.playerViewVideo.visibility = View.VISIBLE
            Log.d("MediaViewer", "Video player initialized: ${videoFile.name}")
            
        } catch (e: Exception) {
            Log.e("MediaViewer", "Error initializing video player", e)
            Toast.makeText(this, "❌ Errore apertura video", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return when (extension) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }
    
    private fun saveVideoToGallery() {
        if (downloadedMediaFile == null || !downloadedMediaFile!!.exists()) {
            Toast.makeText(this, "Video non disponibile", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val timestamp = System.currentTimeMillis()
                    val extension = downloadedMediaFile!!.extension
                    val filename = "ShutApp_$timestamp.$extension"
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Android 10+ usa MediaStore
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                            put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(downloadedMediaFile!!))
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ShutApp")
                        }
                        
                        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                        uri?.let {
                            contentResolver.openOutputStream(it)?.use { outputStream ->
                                FileInputStream(downloadedMediaFile!!).use { inputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                            true
                        } ?: false
                    } else {
                        // Android 9 e precedenti
                        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                        val shutAppDir = File(moviesDir, "ShutApp")
                        if (!shutAppDir.exists()) {
                            shutAppDir.mkdirs()
                        }
                        
                        val videoFile = File(shutAppDir, filename)
                        FileOutputStream(videoFile).use { outputStream ->
                            FileInputStream(downloadedMediaFile!!).use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                        
                        // Aggiungi alla galleria
                        val contentValues = ContentValues().apply {
                            put(MediaStore.Video.Media.DATA, videoFile.absolutePath)
                        }
                        contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                        
                        true
                    }
                }
                
                if (saved) {
                    Toast.makeText(this@MediaViewerActivity, "✅ Video salvato nella galleria", Toast.LENGTH_SHORT).show()
                    Log.d("MediaViewer", "Video saved to gallery successfully")
                } else {
                    Toast.makeText(this@MediaViewerActivity, "❌ Errore salvataggio video", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                Log.e("MediaViewer", "Error saving video to gallery", e)
                Toast.makeText(this@MediaViewerActivity, "❌ Errore: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun saveImageToGallery() {
        if (currentBitmap == null) {
            Toast.makeText(this, "Immagine non disponibile", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val timestamp = System.currentTimeMillis()
                    val filename = "ShutApp_$timestamp.jpg"
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Android 10+ usa MediaStore
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ShutApp")
                        }
                        
                        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                        uri?.let {
                            contentResolver.openOutputStream(it)?.use { outputStream ->
                                currentBitmap!!.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                            }
                            true
                        } ?: false
                    } else {
                        // Android 9 e precedenti
                        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        val shutAppDir = File(picturesDir, "ShutApp")
                        if (!shutAppDir.exists()) {
                            shutAppDir.mkdirs()
                        }
                        
                        val imageFile = File(shutAppDir, filename)
                        FileOutputStream(imageFile).use { outputStream ->
                            currentBitmap!!.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                        }
                        
                        // Aggiungi alla galleria
                        val contentValues = ContentValues().apply {
                            put(MediaStore.Images.Media.DATA, imageFile.absolutePath)
                        }
                        contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                        
                        true
                    }
                }
                
                if (saved) {
                    Toast.makeText(this@MediaViewerActivity, "✅ Immagine salvata nella galleria", Toast.LENGTH_SHORT).show()
                    Log.d("MediaViewer", "Image saved to gallery successfully")
                } else {
                    Toast.makeText(this@MediaViewerActivity, "❌ Errore salvataggio immagine", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                Log.e("MediaViewer", "Error saving image to gallery", e)
                Toast.makeText(this@MediaViewerActivity, "❌ Errore: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Rilascia ExoPlayer se attivo
        exoPlayer?.let {
            it.release()
            Log.d("MediaViewer", "ExoPlayer released")
        }
        exoPlayer = null
        
        Log.d("MediaViewer", "onDestroy called: shouldAutoDelete=$shouldAutoDelete, downloadedMediaFile=${downloadedMediaFile?.name}")
        
        // Auto-delete media se abilitato nelle impostazioni di sicurezza
        if (shouldAutoDelete && downloadedMediaFile != null) {
            try {
                // 1. Elimina file dalla cache locale
                val deleted = downloadedMediaFile!!.delete()
                Log.d("MediaViewer", "Auto-delete media file: success=$deleted, file=${downloadedMediaFile!!.name}")
                if (deleted) {
                    Log.i("MediaViewer", "✅ Media file deleted from cache: ${downloadedMediaFile!!.absolutePath}")
                } else {
                    Log.w("MediaViewer", "⚠️ Failed to delete media file: ${downloadedMediaFile!!.absolutePath}")
                }
                
                // 2. Elimina messaggio dal database locale
                if (messageId != null) {
                    GlobalScope.launch(Dispatchers.IO) {
                        try {
                            val database = AppDatabase.getDatabase(this@MediaViewerActivity)
                            database.messageDao().deleteMessageById(messageId!!)
                            Log.i("MediaViewer", "✅ Message deleted from database: messageId=$messageId")
                        } catch (e: Exception) {
                            Log.e("MediaViewer", "❌ Error deleting message from database", e)
                        }
                    }
                } else {
                    Log.w("MediaViewer", "⚠️ messageId is null, cannot delete from database")
                }
                
            } catch (e: Exception) {
                Log.e("MediaViewer", "❌ Error auto-deleting media", e)
            }
        } else {
            Log.d("MediaViewer", "Auto-delete skipped: shouldAutoDelete=$shouldAutoDelete, fileExists=${downloadedMediaFile != null}")
        }
    }
    
    /**
     * Genera thumbnail del video dopo download completato e salvalo nel database
     */
    private fun generateAndSaveThumbnail(videoFile: File, messageId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("MediaViewer", "🎬 Generating thumbnail for video after download...")
                
                // 1. Genera thumbnail (primi 0.5 secondi del video)
                val thumbnailFile = chatMediaService.generateVideoThumbnail(videoFile)
                
                if (thumbnailFile != null && thumbnailFile.exists()) {
                    // 2. Comprimi thumbnail per ridurre dimensione DB
                    val originalBitmap = BitmapFactory.decodeFile(thumbnailFile.absolutePath)
                    
                    if (originalBitmap != null) {
                        // Ridimensiona a max 320x240 (sufficiente per anteprima chat)
                        val scaledBitmap = if (originalBitmap.width > 320 || originalBitmap.height > 240) {
                            val scale = minOf(320f / originalBitmap.width, 240f / originalBitmap.height)
                            val newWidth = (originalBitmap.width * scale).toInt()
                            val newHeight = (originalBitmap.height * scale).toInt()
                            Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)
                        } else {
                            originalBitmap
                        }
                        
                        // Comprimi a bassa qualità (30%) per ridurre dimensione
                        val outputStream = java.io.ByteArrayOutputStream()
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 30, outputStream)
                        val compressedBytes = outputStream.toByteArray()
                        
                        val thumbnailBase64 = android.util.Base64.encodeToString(compressedBytes, android.util.Base64.DEFAULT)
                        Log.d("MediaViewer", "✅ Generated COMPRESSED thumbnail: ${compressedBytes.size} bytes -> ${thumbnailBase64.length} chars")
                        
                        // 3. Salva thumbnail nel database
                        val database = AppDatabase.getDatabase(this@MediaViewerActivity)
                        database.messageDao().updateMessageThumbnail(messageId, thumbnailBase64)
                        Log.d("MediaViewer", "✅ Thumbnail saved to database for message $messageId")
                        
                        // Cleanup
                        originalBitmap.recycle()
                        if (scaledBitmap != originalBitmap) scaledBitmap.recycle()
                        thumbnailFile.delete()  // Elimina file temporaneo
                    } else {
                        Log.w("MediaViewer", "❌ Failed to decode thumbnail bitmap")
                    }
                } else {
                    Log.w("MediaViewer", "❌ generateVideoThumbnail returned NULL or file doesn't exist")
                }
            } catch (e: Exception) {
                Log.e("MediaViewer", "❌ Error generating/saving video thumbnail", e)
            }
        }
    }
}