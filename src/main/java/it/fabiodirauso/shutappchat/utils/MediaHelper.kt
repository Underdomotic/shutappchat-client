package it.fabiodirauso.shutappchat.utils

import android.content.Context
import android.util.Base64
import android.util.Log
import it.fabiodirauso.shutappchat.api.MediaInitRequest
import it.fabiodirauso.shutappchat.api.ChatMediaInitResponse
import it.fabiodirauso.shutappchat.config.AppConfigManager
import it.fabiodirauso.shutappchat.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MediaHelper(private val context: Context) {
    
    companion object {
        private const val TAG = "MediaHelper"
        private const val ALGORITHM = "AES/CBC/PKCS5Padding"
        private const val CHUNK_SIZE = 512 * 1024
        
        @Volatile
        private var instance: MediaHelper? = null
        
        fun getInstance(context: Context): MediaHelper {
            return instance ?: synchronized(this) {
                instance ?: MediaHelper(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val configManager = AppConfigManager.getInstance(context)
    private val apiService = RetrofitClient.getApiService(configManager)
    
    sealed class MediaResult<out T> {
        data class Success<T>(val data: T) : MediaResult<T>()
        data class Error(val message: String, val exception: Exception? = null) : MediaResult<Nothing>()
        data class Progress(val progress: Float) : MediaResult<Nothing>()
    }
    
    suspend fun initMediaUpload(
        filename: String,
        mimeType: String,
        size: Long,
        receiver: String? = null,
        salvable: Boolean = true,
        senderAutoDelete: Boolean = false
    ): MediaResult<ChatMediaInitResponse> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Init upload: filename=$filename, size=$size, receiver=$receiver, salvable=$salvable, senderAutoDelete=$senderAutoDelete")
            
            // Usa MediaInitRequest esistente per compatibilità
            val request = MediaInitRequest(
                filename = filename,
                mime = mimeType,
                size = size,
                receiver = receiver,
                salvable = salvable,
                senderAutoDelete = senderAutoDelete
            )
            
            val response = apiService.initMediaUpload(request)
            
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                // Converti MediaInitResponse in ChatMediaInitResponse
                // Handle both numeric and string ID formats from server
                val mediaId = when (val rawId = body.id) {
                    is Number -> rawId.toInt().toString()  // Convert 96.0 -> "96"
                    is String -> rawId
                    else -> rawId.toString()
                }
                
                val initResponse = ChatMediaInitResponse(
                    id = mediaId,
                    key = body.key ?: "",
                    iv = body.iv ?: ""
                )
                Log.d(TAG, "Upload initialized: mediaId=${initResponse.id}")
                MediaResult.Success(initResponse)
            } else {
                val error = "Error init media: ${response.code()}"
                Log.e(TAG, error)
                MediaResult.Error(error)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during init", e)
            MediaResult.Error("Network error: ${e.message}", e)
        }
    }
    
    suspend fun encryptFile(
        inputStream: InputStream,
        aesKeyBase64: String,
        ivBase64: String
    ): MediaResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Encrypting file with AES-256-CBC")
            
            val aesKey = Base64.decode(aesKeyBase64, Base64.NO_WRAP)
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            
            val secretKeySpec = SecretKeySpec(aesKey, "AES")
            val ivParameterSpec = IvParameterSpec(iv)
            
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec)
            
            val plainBytes = inputStream.readBytes()
            val encryptedBytes = cipher.doFinal(plainBytes)
            
            Log.d(TAG, "File encrypted: ${plainBytes.size} -> ${encryptedBytes.size} bytes")
            MediaResult.Success(encryptedBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption error", e)
            MediaResult.Error("Encryption error: ${e.message}", e)
        } finally {
            inputStream.close()
        }
    }
    
    suspend fun uploadMediaChunked(
        mediaId: String,
        encryptedData: ByteArray,
        onProgress: ((Float) -> Unit)? = null
    ): MediaResult<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting chunked upload for mediaId=$mediaId, size=${encryptedData.size}")
            
            var offset = 0L
            val totalSize = encryptedData.size.toLong()
            
            while (offset < totalSize) {
                val chunkEnd = minOf(offset + CHUNK_SIZE, totalSize)
                val chunk = encryptedData.sliceArray(offset.toInt() until chunkEnd.toInt())
                
                Log.d(TAG, "Uploading chunk: offset=$offset, size=${chunk.size}")
                
                val requestBody = chunk.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                val response = apiService.uploadMediaData(mediaId, offset, requestBody)
                
                if (!response.isSuccessful || response.body() == null) {
                    val error = "Error uploading chunk: ${response.code()}"
                    Log.e(TAG, error)
                    return@withContext MediaResult.Error(error)
                }
                
                val uploadResponse = response.body()!!
                val ok = uploadResponse.ok ?: false
                val complete = uploadResponse.complete ?: false
                val nextOffset = uploadResponse.next
                
                if (!ok) {
                    Log.e(TAG, "Server returned ok=false")
                    return@withContext MediaResult.Error("Server rejected chunk")
                }
                
                offset = nextOffset ?: chunkEnd
                val progress = offset.toFloat() / totalSize.toFloat()
                onProgress?.invoke(progress)
                
                Log.d(TAG, "Chunk uploaded: next=$offset, progress=${(progress * 100).toInt()}%")
                
                if (complete) {
                    Log.d(TAG, "Upload complete!")
                    return@withContext MediaResult.Success(Unit)
                }
            }
            
            MediaResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error during chunked upload", e)
            MediaResult.Error("Upload error: ${e.message}", e)
        }
    }
    
    suspend fun downloadMedia(
        mediaId: String,
        onProgress: ((Float) -> Unit)? = null
    ): MediaResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Downloading media: mediaId=$mediaId")
            
            val response = apiService.downloadMediaData(mediaId)
            
            if (!response.isSuccessful || response.body() == null) {
                val error = "Download error: ${response.code()}"
                Log.e(TAG, error)
                return@withContext MediaResult.Error(error)
            }
            
            val body = response.body()!!
            val contentLength = body.contentLength()
            Log.d(TAG, "Starting download: $contentLength bytes")
            
            // ✅ STREAMING invece di bytes() - Gestisce file grandi senza problemi SSL
            val inputStream = body.byteStream()
            val outputStream = java.io.ByteArrayOutputStream()
            
            val buffer = ByteArray(8192)  // 8KB chunks (come il server)
            var totalBytesRead = 0L
            var bytesRead: Int
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                
                // Report progress se disponibile contentLength
                if (contentLength > 0) {
                    val progress = totalBytesRead.toFloat() / contentLength
                    onProgress?.invoke(progress)
                    
                    if (totalBytesRead % (1024 * 1024) == 0L) {  // Log ogni MB
                        Log.d(TAG, "Downloaded ${totalBytesRead / 1024 / 1024}MB / ${contentLength / 1024 / 1024}MB")
                    }
                }
            }
            
            inputStream.close()
            val encryptedBytes = outputStream.toByteArray()
            Log.d(TAG, "Download complete: ${encryptedBytes.size} bytes")
            
            MediaResult.Success(encryptedBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error during download", e)
            MediaResult.Error("Download error: ${e.message}", e)
        }
    }
    
    suspend fun decryptMedia(
        encryptedData: ByteArray,
        aesKeyBase64: String,
        ivBase64: String
    ): MediaResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Decrypting media: size=${encryptedData.size}")
            
            val aesKey = Base64.decode(aesKeyBase64, Base64.NO_WRAP)
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            
            val secretKeySpec = SecretKeySpec(aesKey, "AES")
            val ivParameterSpec = IvParameterSpec(iv)
            
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
            
            val decryptedBytes = cipher.doFinal(encryptedData)
            Log.d(TAG, "Media decrypted: ${encryptedData.size} -> ${decryptedBytes.size} bytes")
            
            MediaResult.Success(decryptedBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption error", e)
            MediaResult.Error("Decryption error: ${e.message}", e)
        }
    }
    
    fun generateRandomAESKey(): String {
        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)
        return Base64.encodeToString(keyBytes, Base64.NO_WRAP)
    }
    
    fun generateRandomIV(): String {
        val ivBytes = ByteArray(16)
        SecureRandom().nextBytes(ivBytes)
        return Base64.encodeToString(ivBytes, Base64.NO_WRAP)
    }
}