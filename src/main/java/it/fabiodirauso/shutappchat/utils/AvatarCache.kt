package it.fabiodirauso.shutappchat.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Cache manager per gli avatar utente
 * Gestisce cache in memoria (LRU) e cache su disco
 */
object AvatarCache {
    
    // Cache in memoria (max 50 avatar)
    private val memoryCache = LruCache<String, Bitmap>(50)
    
    // Directory cache su disco
    private var diskCacheDir: File? = null
    
    /**
     * Inizializza la cache
     */
    fun initialize(context: Context) {
        // Crea directory cache se non esiste
        diskCacheDir = File(context.cacheDir, "avatars")
        if (diskCacheDir?.exists() != true) {
            diskCacheDir?.mkdirs()
        }
    }
    
    /**
     * Ottiene un avatar dalla cache
     */
    fun getAvatar(userId: String): Bitmap? {
        // Prima controlla la cache in memoria
        memoryCache.get(userId)?.let { return it }
        
        // Poi controlla la cache su disco
        return loadFromDisk(userId)?.also { bitmap ->
            // Se trovato su disco, aggiungilo alla memoria
            memoryCache.put(userId, bitmap)
        }
    }
    
    /**
     * Salva un avatar nella cache
     */
    fun putAvatar(userId: String, bitmap: Bitmap) {
        // Salva in memoria
        memoryCache.put(userId, bitmap)
        
        // Salva su disco in background
        saveToDisk(userId, bitmap)
    }
    
    /**
     * Rimuove un avatar dalla cache
     */
    fun removeAvatar(userId: String) {
        memoryCache.remove(userId)
        getDiskFile(userId)?.delete()
    }
    
    /**
     * Pulisce tutta la cache
     */
    fun clearCache() {
        memoryCache.evictAll()
        diskCacheDir?.listFiles()?.forEach { it.delete() }
    }
    
    /**
     * Carica un avatar dal disco
     */
    private fun loadFromDisk(userId: String): Bitmap? {
        return try {
            val file = getDiskFile(userId)
            if (file?.exists() == true) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else null
        } catch (e: Exception) {
            android.util.Log.w("AvatarCache", "Error loading avatar from disk: $userId", e)
            null
        }
    }
    
    /**
     * Salva un avatar su disco
     */
    private fun saveToDisk(userId: String, bitmap: Bitmap) {
        try {
            val file = getDiskFile(userId) ?: return
            FileOutputStream(file).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            }
        } catch (e: IOException) {
            android.util.Log.w("AvatarCache", "Error saving avatar to disk: $userId", e)
        }
    }
    
    /**
     * Ottiene il file di cache per un utente
     */
    private fun getDiskFile(userId: String): File? {
        return diskCacheDir?.let { dir ->
            File(dir, "avatar_$userId.jpg")
        }
    }
    
    /**
     * Controlla se un avatar è in cache
     */
    fun hasAvatar(userId: String): Boolean {
        return memoryCache.get(userId) != null || 
               getDiskFile(userId)?.exists() == true
    }
    
    /**
     * Ottiene la dimensione della cache in memoria
     */
    fun getMemoryCacheSize(): Int = memoryCache.size()
    
    /**
     * Ottiene la dimensione della cache su disco
     */
    fun getDiskCacheSize(): Long {
        return diskCacheDir?.listFiles()?.sumOf { it.length() } ?: 0L
    }
}