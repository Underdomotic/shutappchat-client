package it.fabiodirauso.shutappchat.services

import android.content.Context
import android.util.Log
import it.fabiodirauso.shutappchat.api.MediaInitRequest
import it.fabiodirauso.shutappchat.config.AppConfigManager
import it.fabiodirauso.shutappchat.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * MediaService per upload PROFILO (NON criptati, compatibilità legacy)
 * Per media CHAT criptati usare ChatMediaService
 */
class MediaService(private val context: Context) {
    
    companion object {
        private const val TAG = "MediaService"
        private const val CHUNK_SIZE = 512 * 1024 // 512KB
    }
    
    private val configManager = AppConfigManager.getInstance(context)
    
    suspend fun uploadMedia(
        file: File,
        receiverId: String? = null,
        onProgress: ((Float) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val apiService = RetrofitClient.getApiService(configManager)
            
            // Step 1: Init upload
            val initRequest = MediaInitRequest(
                filename = file.name,
                mime = getMimeType(file),
                size = file.length(),
                receiver = receiverId
            )
            
            val initResponse = apiService.initMediaUpload(initRequest)
            
            if (!initResponse.isSuccessful || initResponse.body() == null) {
                Log.e(TAG, "Failed to init upload: ${initResponse.code()}")
                return@withContext null
            }
            
            val mediaId = initResponse.body()!!.id?.toString() ?: return@withContext null
            Log.d(TAG, "Upload initialized: mediaId=$mediaId")
            
            // Step 2: Upload chunked
            val fileBytes = file.readBytes()
            var offset = 0L
            val totalSize = fileBytes.size.toLong()
            
            while (offset < totalSize) {
                val chunkEnd = minOf(offset + CHUNK_SIZE, totalSize)
                val chunk = fileBytes.sliceArray(offset.toInt() until chunkEnd.toInt())
                
                val requestBody = chunk.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                val uploadResponse = apiService.uploadMediaData(mediaId, offset, requestBody)
                
                if (!uploadResponse.isSuccessful || uploadResponse.body()?.ok != true) {
                    Log.e(TAG, "Failed to upload chunk: ${uploadResponse.code()}")
                    return@withContext null
                }
                
                offset = uploadResponse.body()?.next ?: chunkEnd
                val progress = offset.toFloat() / totalSize.toFloat()
                onProgress?.invoke(progress)
                
                if (uploadResponse.body()?.complete == true) {
                    Log.d(TAG, "Upload complete!")
                    return@withContext mediaId
                }
            }
            
            mediaId
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading media", e)
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
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }
}