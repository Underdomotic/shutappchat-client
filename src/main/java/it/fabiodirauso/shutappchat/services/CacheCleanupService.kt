package it.fabiodirauso.shutappchat.services

import android.content.Context
import android.util.Log
import it.fabiodirauso.shutappchat.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Servizio per la pulizia automatica della cache locale.
 * - Elimina file media obsoleti (oltre 7 giorni)
 * - Pulisce log dell'app
 * - Elimina file temporanei
 */
class CacheCleanupService(private val context: Context) {
    
    companion object {
        private const val TAG = "CacheCleanupService"
        private const val PREF_LAST_CLEANUP = "last_cache_cleanup"
        private const val CLEANUP_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 ore
        private const val MEDIA_RETENTION_DAYS = 7L // Mantieni media per 7 giorni
        private const val LOG_RETENTION_DAYS = 3L // Mantieni log per 3 giorni
    }
    
    private val sharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private val database = AppDatabase.getDatabase(context)
    
    /**
     * Verifica se è necessario eseguire la pulizia e la esegue se necessario.
     * @return true se la pulizia è stata eseguita, false altrimenti
     */
    suspend fun checkAndCleanup(): Boolean = withContext(Dispatchers.IO) {
        val lastCleanup = sharedPreferences.getLong(PREF_LAST_CLEANUP, 0)
        val now = System.currentTimeMillis()
        
        if (now - lastCleanup >= CLEANUP_INTERVAL_MS) {
            Log.d(TAG, "Starting scheduled cache cleanup...")
            performCleanup()
            
            // Salva timestamp ultima pulizia
            sharedPreferences.edit()
                .putLong(PREF_LAST_CLEANUP, now)
                .apply()
            
            Log.d(TAG, "Cache cleanup completed. Next cleanup in 24 hours.")
            true
        } else {
            val hoursUntilNext = (CLEANUP_INTERVAL_MS - (now - lastCleanup)) / (60 * 60 * 1000)
            Log.d(TAG, "Cache cleanup not needed. Next cleanup in $hoursUntilNext hours.")
            false
        }
    }
    
    /**
     * Forza la pulizia immediata della cache (per test o pulizia manuale).
     */
    suspend fun forceCleanup(): CleanupResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Forcing cache cleanup...")
        performCleanup()
    }
    
    /**
     * Esegue la pulizia completa della cache.
     */
    private suspend fun performCleanup(): CleanupResult = withContext(Dispatchers.IO) {
        var totalFilesDeleted = 0
        var totalBytesFreed = 0L
        
        try {
            // 1. Pulisci media obsoleti
            val mediaResult = cleanupObsoleteMedia()
            totalFilesDeleted += mediaResult.filesDeleted
            totalBytesFreed += mediaResult.bytesFreed
            
            // 2. Pulisci log dell'app
            val logResult = cleanupAppLogs()
            totalFilesDeleted += logResult.filesDeleted
            totalBytesFreed += logResult.bytesFreed
            
            // 3. Pulisci file temporanei
            val tempResult = cleanupTempFiles()
            totalFilesDeleted += tempResult.filesDeleted
            totalBytesFreed += tempResult.bytesFreed
            
            // 4. Pulisci avatar cache obsoleti
            val avatarResult = cleanupAvatarCache()
            totalFilesDeleted += avatarResult.filesDeleted
            totalBytesFreed += avatarResult.bytesFreed
            
            Log.d(TAG, "Cleanup summary: $totalFilesDeleted files deleted, ${totalBytesFreed / 1024}KB freed")
            
            CleanupResult(
                success = true,
                filesDeleted = totalFilesDeleted,
                bytesFreed = totalBytesFreed
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
            CleanupResult(
                success = false,
                filesDeleted = totalFilesDeleted,
                bytesFreed = totalBytesFreed,
                error = e.message
            )
        }
    }
    
    /**
     * Elimina file media obsoleti che non sono più referenziati nei messaggi.
     */
    private suspend fun cleanupObsoleteMedia(): CleanupResult {
        var filesDeleted = 0
        var bytesFreed = 0L
        
        try {
            val cacheDir = context.cacheDir
            val now = System.currentTimeMillis()
            val retentionTime = TimeUnit.DAYS.toMillis(MEDIA_RETENTION_DAYS)
            
            // Recupera tutti i mediaId attivi dal database
            val activeMediaIds = database.messageDao().getAllMediaIds()
            
            // Scansiona i file nella cache
            cacheDir.listFiles { file ->
                file.name.startsWith("media_") || file.name.startsWith("thumb_")
            }?.forEach { file ->
                val fileAge = now - file.lastModified()
                val mediaId = file.name.removePrefix("media_").removePrefix("thumb_")
                
                // Elimina se:
                // 1. File più vecchio di MEDIA_RETENTION_DAYS giorni
                // 2. mediaId non è più nel database (messaggio eliminato)
                if (fileAge > retentionTime || !activeMediaIds.contains(mediaId)) {
                    val size = file.length()
                    if (file.delete()) {
                        filesDeleted++
                        bytesFreed += size
                        Log.d(TAG, "Deleted obsolete media: ${file.name} (${size / 1024}KB)")
                    }
                }
            }
            
            Log.d(TAG, "Media cleanup: $filesDeleted files, ${bytesFreed / 1024}KB freed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning media", e)
        }
        
        return CleanupResult(true, filesDeleted, bytesFreed)
    }
    
    /**
     * Elimina log dell'app più vecchi di LOG_RETENTION_DAYS giorni.
     */
    private suspend fun cleanupAppLogs(): CleanupResult {
        var filesDeleted = 0
        var bytesFreed = 0L
        
        try {
            val now = System.currentTimeMillis()
            val retentionTime = TimeUnit.DAYS.toMillis(LOG_RETENTION_DAYS)
            
            // Cerca log in cache e files dir
            val logDirs = listOf(
                context.cacheDir,
                context.filesDir,
                File(context.filesDir, "logs")
            )
            
            logDirs.forEach { dir ->
                if (dir.exists()) {
                    dir.listFiles { file ->
                        file.name.endsWith(".log") || 
                        file.name.startsWith("log_") ||
                        file.name.contains("logcat")
                    }?.forEach { file ->
                        val fileAge = now - file.lastModified()
                        
                        if (fileAge > retentionTime) {
                            val size = file.length()
                            if (file.delete()) {
                                filesDeleted++
                                bytesFreed += size
                                Log.d(TAG, "Deleted old log: ${file.name} (${size / 1024}KB)")
                            }
                        }
                    }
                }
            }
            
            Log.d(TAG, "Log cleanup: $filesDeleted files, ${bytesFreed / 1024}KB freed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning logs", e)
        }
        
        return CleanupResult(true, filesDeleted, bytesFreed)
    }
    
    /**
     * Elimina file temporanei dalla cache.
     */
    private suspend fun cleanupTempFiles(): CleanupResult {
        var filesDeleted = 0
        var bytesFreed = 0L
        
        try {
            val cacheDir = context.cacheDir
            val now = System.currentTimeMillis()
            val oneDayMs = TimeUnit.DAYS.toMillis(1)
            
            cacheDir.listFiles { file ->
                file.name.startsWith("tmp_") || 
                file.name.startsWith("temp_") ||
                file.name.startsWith("compressed_") ||
                file.name.contains("cropped_")
            }?.forEach { file ->
                val fileAge = now - file.lastModified()
                
                // Elimina file temporanei più vecchi di 1 giorno
                if (fileAge > oneDayMs) {
                    val size = file.length()
                    if (file.delete()) {
                        filesDeleted++
                        bytesFreed += size
                        Log.d(TAG, "Deleted temp file: ${file.name}")
                    }
                }
            }
            
            Log.d(TAG, "Temp files cleanup: $filesDeleted files, ${bytesFreed / 1024}KB freed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning temp files", e)
        }
        
        return CleanupResult(true, filesDeleted, bytesFreed)
    }
    
    /**
     * Pulisce avatar cache obsoleti.
     */
    private suspend fun cleanupAvatarCache(): CleanupResult {
        var filesDeleted = 0
        var bytesFreed = 0L
        
        try {
            val cacheDir = context.cacheDir
            val now = System.currentTimeMillis()
            val retentionTime = TimeUnit.DAYS.toMillis(7)
            
            cacheDir.listFiles { file ->
                file.name.startsWith("avatar_") || file.name.startsWith("profile_")
            }?.forEach { file ->
                val fileAge = now - file.lastModified()
                
                // Elimina avatar più vecchi di 7 giorni
                if (fileAge > retentionTime) {
                    val size = file.length()
                    if (file.delete()) {
                        filesDeleted++
                        bytesFreed += size
                        Log.d(TAG, "Deleted old avatar: ${file.name}")
                    }
                }
            }
            
            Log.d(TAG, "Avatar cache cleanup: $filesDeleted files, ${bytesFreed / 1024}KB freed")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning avatar cache", e)
        }
        
        return CleanupResult(true, filesDeleted, bytesFreed)
    }
    
    /**
     * Ottiene statistiche sulla cache corrente.
     */
    suspend fun getCacheStats(): CacheStats = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val filesDir = context.filesDir
        
        var totalFiles = 0
        var totalSize = 0L
        var mediaCount = 0
        var mediaSize = 0L
        
        cacheDir.listFiles()?.forEach { file ->
            totalFiles++
            totalSize += file.length()
            
            if (file.name.startsWith("media_") || file.name.startsWith("thumb_")) {
                mediaCount++
                mediaSize += file.length()
            }
        }
        
        CacheStats(
            totalFiles = totalFiles,
            totalSizeBytes = totalSize,
            mediaFilesCount = mediaCount,
            mediaSizeBytes = mediaSize
        )
    }
    
    data class CleanupResult(
        val success: Boolean,
        val filesDeleted: Int = 0,
        val bytesFreed: Long = 0,
        val error: String? = null
    )
    
    data class CacheStats(
        val totalFiles: Int,
        val totalSizeBytes: Long,
        val mediaFilesCount: Int,
        val mediaSizeBytes: Long
    ) {
        fun getTotalSizeMB(): Float = totalSizeBytes / (1024f * 1024f)
        fun getMediaSizeMB(): Float = mediaSizeBytes / (1024f * 1024f)
    }
}
