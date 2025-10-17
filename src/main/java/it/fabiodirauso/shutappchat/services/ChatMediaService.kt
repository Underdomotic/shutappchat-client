package it.fabiodirauso.shutappchat.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import it.fabiodirauso.shutappchat.managers.SecuritySettingsManager
import it.fabiodirauso.shutappchat.utils.MediaHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class ChatMediaService(private val context: Context) {
    
    companion object {
        private const val TAG = "ChatMediaService"
        private const val MAX_IMAGE_SIZE = 5 * 1024 * 1024
        private const val COMPRESSION_QUALITY = 85
    }
    
    private val mediaHelper = MediaHelper.getInstance(context)
    private val securityManager = SecuritySettingsManager.getInstance(context)

    suspend fun uploadChatMedia(
        file: File, 
        receiverUsername: String,
        mimeType: String? = null,
        salvable: Boolean? = null,
        onProgress: ((Float) -> Unit)? = null
    ): ChatMediaUploadResult? = withContext(Dispatchers.IO) {
        try {
            val processedFile = if (isImage(file) && file.length() > MAX_IMAGE_SIZE) {
                Log.d(TAG, "Image exceeds 5MB, compressing...")
                compressImage(file) ?: file
            } else {
                file
            }
            
            val isSalvable = salvable ?: securityManager.getMediaSalvableFlag()
            val senderAutoDelete = securityManager.isAutoDeleteMediaEnabled()
            val resolvedMimeType = mimeType ?: getMimeType(processedFile)
            Log.d(TAG, "Uploading chat media: file=${processedFile.name}, size=${processedFile.length()}, salvable=$isSalvable, senderAutoDelete=$senderAutoDelete, mimeType=$resolvedMimeType")
            
            val initResult = mediaHelper.initMediaUpload(
                filename = processedFile.name,
                mimeType = resolvedMimeType,
                size = processedFile.length(),
                receiver = receiverUsername,
                salvable = isSalvable,
                senderAutoDelete = senderAutoDelete
            )
            
            if (initResult !is MediaHelper.MediaResult.Success) {
                Log.e(TAG, "Failed to init upload")
                return@withContext null
            }
            
            val initResponse = initResult.data
            Log.d(TAG, "Upload initialized: mediaId=.id")
            
            onProgress?.invoke(0.1f)
            val encryptResult = mediaHelper.encryptFile(
                inputStream = FileInputStream(processedFile),
                aesKeyBase64 = initResponse.key,
                ivBase64 = initResponse.iv
            )
            
            if (encryptResult !is MediaHelper.MediaResult.Success) {
                Log.e(TAG, "Failed to encrypt")
                return@withContext null
            }
            
            val encryptedData = encryptResult.data
            Log.d(TAG, "File encrypted: size=.size bytes")
            
            onProgress?.invoke(0.2f)
            val uploadResult = mediaHelper.uploadMediaChunked(
                mediaId = initResponse.id,
                encryptedData = encryptedData,
                onProgress = { progress ->
                    onProgress?.invoke(0.2f + (progress * 0.8f))
                }
            )
            
            if (uploadResult !is MediaHelper.MediaResult.Success) {
                Log.e(TAG, "Failed to upload")
                return@withContext null
            }
            
            Log.d(TAG, "Upload complete!")
            
            if (processedFile != file) {
                processedFile.delete()
            }
            
            ChatMediaUploadResult(
                mediaId = initResponse.id,
                encryptedKey = initResponse.key,
                iv = initResponse.iv,
                filename = processedFile.name,
                mimeType = resolvedMimeType,  // Use the resolved MIME type instead of detecting from file
                size = processedFile.length(),
                salvable = isSalvable,
                senderAutoDelete = senderAutoDelete
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading chat media", e)
            null
        }
    }
    
    suspend fun downloadChatMedia(
        mediaId: String,
        encryptedKey: String,
        iv: String,
        onProgress: ((Float) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Downloading chat media: mediaId=")
            
            // 0-70%: Download streaming (con progress reale!)
            val downloadResult = mediaHelper.downloadMedia(mediaId, onProgress = { progress ->
                onProgress?.invoke(progress * 0.7f)  // 0.0 -> 0.7
            })
            
            if (downloadResult !is MediaHelper.MediaResult.Success) {
                Log.e(TAG, "Download failed")
                return@withContext null
            }
            
            val encryptedData = downloadResult.data
            Log.d(TAG, "Downloaded size=.size encrypted bytes")
            
            // 70-90%: Decryption
            onProgress?.invoke(0.7f)
            val decryptResult = mediaHelper.decryptMedia(encryptedData, encryptedKey, iv)
            
            if (decryptResult !is MediaHelper.MediaResult.Success) {
                Log.e(TAG, "Decryption failed")
                return@withContext null
            }
            
            val decryptedData = decryptResult.data
            Log.d(TAG, "Decrypted size=.size bytes")
            
            // 90-100%: Scrittura su disco
            onProgress?.invoke(0.9f)
            val cacheFile = File(context.cacheDir, "media_")
            cacheFile.writeBytes(decryptedData)
            
            onProgress?.invoke(1.0f)
            Log.d(TAG, "Media saved to: path=.absolutePath")
            
            cacheFile
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading chat media", e)
            null
        }
    }
    
    private fun compressImage(file: File): File? {
        return try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            val compressedFile = File(context.cacheDir, "compressed_.name")
            
            FileOutputStream(compressedFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY, out)
            }
            
            Log.d(TAG, "Image compressed: old=.length() new=.length() bytes")
            compressedFile
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing image", e)
            null
        }
    }
    
    private fun isImage(file: File): Boolean {
        val mimeType = getMimeType(file)
        return mimeType.startsWith("image/")
    }
    
    private fun isVideo(file: File): Boolean {
        val mimeType = getMimeType(file)
        return mimeType.startsWith("video/")
    }
    
    /**
     * Genera thumbnail da video usando MediaMetadataRetriever
     * @return File del thumbnail JPEG o null se errore
     */
    fun generateVideoThumbnail(videoFile: File): File? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoFile.absolutePath)
            
            // Estrai frame a 2 secondi MAX (2000000 microseconds) - analizza solo inizio video
            val bitmap = retriever.getFrameAtTime(500000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            retriever.release()
            
            if (bitmap == null) {
                Log.e(TAG, "Failed to extract video frame")
                return null
            }
            
            // Salva thumbnail come JPEG
            val thumbnailFile = File(context.cacheDir, "video_thumb_${System.currentTimeMillis()}.jpg")
            FileOutputStream(thumbnailFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            
            Log.d(TAG, "Video thumbnail generated: ${thumbnailFile.length()} bytes")
            thumbnailFile
        } catch (e: Exception) {
            Log.e(TAG, "Error generating video thumbnail", e)
            null
        }
    }
    
    private fun getMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "avi" -> "video/avi"
            "mov" -> "video/mov"
            "mp3" -> "audio/mp3"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}

data class ChatMediaUploadResult(
    val mediaId: String,
    val encryptedKey: String,
    val iv: String,
    val filename: String,
    val mimeType: String,
    val size: Long,
    val salvable: Boolean,
    val senderAutoDelete: Boolean = false
)
