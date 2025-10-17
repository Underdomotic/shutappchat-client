package it.fabiodirauso.shutappchat.managers

import android.content.Context
import android.util.Log
import it.fabiodirauso.shutappchat.database.AppDatabase
import it.fabiodirauso.shutappchat.model.PrivacySettingsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking

/**
 * Manager per le impostazioni di sicurezza e privacy
 * Usa Room database come storage locale (source of truth)
 */
class SecuritySettingsManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "SecuritySettingsManager"
        
        @Volatile
        private var instance: SecuritySettingsManager? = null
        
        fun getInstance(context: Context): SecuritySettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SecuritySettingsManager(context.applicationContext).also { 
                    instance = it 
                }
            }
        }
    }
    
    private val database = AppDatabase.getDatabase(context)
    private val privacyDao = database.privacySettingsDao()
    
    /**
     * Inizializza i settings di default se non esistono ancora
     * Da chiamare al primo avvio o dopo il login
     */
    suspend fun initializeDefaultSettings() = withContext(Dispatchers.IO) {
        try {
            val existing = privacyDao.getSettingsSync()
            if (existing == null) {
                Log.d(TAG, "Inizializzazione settings di default")
                privacyDao.insertSettings(PrivacySettingsEntity())
            } else {
                Log.d(TAG, "Settings già esistenti, skip inizializzazione")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante inizializzazione settings", e)
        }
    }
    
    /**
     * Ottiene i settings correnti (sincrono, per compatibilità)
     * NOTA: Usa runBlocking, preferire getSettingsAsync() quando possibile
     */
    private fun getSettingsOrDefault(): PrivacySettingsEntity {
        return runBlocking {
            privacyDao.getSettingsSync() ?: PrivacySettingsEntity()
        }
    }
    
    /**
     * Ottiene i settings correnti (async, preferito)
     */
    suspend fun getSettingsAsync(): PrivacySettingsEntity {
        return privacyDao.getSettingsSync() ?: PrivacySettingsEntity()
    }
    
    fun isReadReceiptsEnabled(): Boolean {
        return getSettingsOrDefault().allowReadReceipts
    }
    
    fun isScreenshotBlockEnabled(): Boolean {
        return getSettingsOrDefault().blockScreenshots
    }
    
    fun isProtectMediaEnabled(): Boolean {
        return getSettingsOrDefault().protectMedia
    }
    
    fun isMediaObfuscationEnabled(): Boolean {
        return getSettingsOrDefault().obfuscateMediaFiles
    }
    
    fun isAutoDeleteMediaEnabled(): Boolean {
        return getSettingsOrDefault().autoDeleteMediaOnOpen
    }
    
    fun isAutoDownloadVideosEnabled(): Boolean {
        return getSettingsOrDefault().autoDownloadVideos
    }

    fun getMediaSalvableFlag(): Boolean {
        return !isProtectMediaEnabled()
    }
    
    /**
     * Aggiorna un singolo campo nei settings locali
     * IMPORTANTE: Questo aggiorna SOLO il DB locale, NON sincronizza con il server
     * La sincronizzazione è responsabilità di PrivacySettingsActivity
     */
    suspend fun updateLocalSetting(settingName: String, value: Boolean) = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            when (settingName) {
                "allow_read_receipts" -> privacyDao.updateAllowReadReceipts(value, timestamp)
                "block_screenshots" -> privacyDao.updateBlockScreenshots(value, timestamp)
                "protect_media" -> privacyDao.updateProtectMedia(value, timestamp)
                "obfuscate_media_files" -> privacyDao.updateObfuscateMediaFiles(value, timestamp)
                "auto_delete_media_on_open" -> privacyDao.updateAutoDeleteMediaOnOpen(value, timestamp)
                "auto_download_videos" -> privacyDao.updateAutoDownloadVideos(value, timestamp)  // 🎯 NUOVO
            }
            Log.d(TAG, "Setting locale aggiornato: $settingName = $value")
        } catch (e: Exception) {
            Log.e(TAG, "Errore aggiornamento setting locale", e)
        }
    }
    
    /**
     * Aggiorna tutti i settings in una sola operazione
     */
    suspend fun updateAllSettings(settings: PrivacySettingsEntity) = withContext(Dispatchers.IO) {
        try {
            privacyDao.insertSettings(settings.copy(lastSyncTimestamp = System.currentTimeMillis()))
            Log.d(TAG, "Tutti i settings aggiornati")
        } catch (e: Exception) {
            Log.e(TAG, "Errore aggiornamento settings", e)
        }
    }
}
