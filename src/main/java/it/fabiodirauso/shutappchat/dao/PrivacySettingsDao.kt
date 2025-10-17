package it.fabiodirauso.shutappchat.dao

import androidx.room.*
import it.fabiodirauso.shutappchat.model.PrivacySettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PrivacySettingsDao {
    
    /**
     * Get the privacy settings as a Flow for reactive updates.
     * Returns null if no settings exist yet.
     */
    @Query("SELECT * FROM privacy_settings WHERE id = 1 LIMIT 1")
    fun getSettings(): Flow<PrivacySettingsEntity?>
    
    /**
     * Get the privacy settings synchronously (for non-suspend contexts).
     */
    @Query("SELECT * FROM privacy_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettingsSync(): PrivacySettingsEntity?
    
    /**
     * Insert or replace privacy settings.
     * Use this for full updates or initialization.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(settings: PrivacySettingsEntity)
    
    /**
     * Update existing settings.
     */
    @Update
    suspend fun updateSettings(settings: PrivacySettingsEntity)
    
    /**
     * Individual field updates for performance optimization
     */
    @Query("UPDATE privacy_settings SET allowReadReceipts = :value, lastSyncTimestamp = :timestamp WHERE id = 1")
    suspend fun updateAllowReadReceipts(value: Boolean, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE privacy_settings SET blockScreenshots = :value, lastSyncTimestamp = :timestamp WHERE id = 1")
    suspend fun updateBlockScreenshots(value: Boolean, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE privacy_settings SET protectMedia = :value, lastSyncTimestamp = :timestamp WHERE id = 1")
    suspend fun updateProtectMedia(value: Boolean, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE privacy_settings SET obfuscateMediaFiles = :value, lastSyncTimestamp = :timestamp WHERE id = 1")
    suspend fun updateObfuscateMediaFiles(value: Boolean, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE privacy_settings SET autoDeleteMediaOnOpen = :value, lastSyncTimestamp = :timestamp WHERE id = 1")
    suspend fun updateAutoDeleteMediaOnOpen(value: Boolean, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE privacy_settings SET autoDownloadVideos = :value, lastSyncTimestamp = :timestamp WHERE id = 1")
    suspend fun updateAutoDownloadVideos(value: Boolean, timestamp: Long = System.currentTimeMillis())
    
    /**
     * Delete all privacy settings (for logout/reset)
     */
    @Query("DELETE FROM privacy_settings")
    suspend fun deleteAll()
}
