package it.fabiodirauso.shutappchat.dao

import androidx.room.*
import it.fabiodirauso.shutappchat.model.ForceUpdateEntity

@Dao
interface ForceUpdateDao {
    
    @Query("SELECT * FROM force_updates WHERE version = :version LIMIT 1")
    suspend fun getForceUpdate(version: String): ForceUpdateEntity?
    
    @Query("SELECT * FROM force_updates WHERE isInstalling = 0 ORDER BY receivedAt DESC LIMIT 1")
    suspend fun getPendingForceUpdate(): ForceUpdateEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertForceUpdate(forceUpdate: ForceUpdateEntity)
    
    @Query("UPDATE force_updates SET isDownloading = :isDownloading, downloadProgress = :progress WHERE version = :version")
    suspend fun updateDownloadProgress(version: String, isDownloading: Boolean, progress: Int)
    
    @Query("UPDATE force_updates SET isInstalling = :isInstalling WHERE version = :version")
    suspend fun updateInstallingStatus(version: String, isInstalling: Boolean)
    
    @Query("DELETE FROM force_updates WHERE version = :version")
    suspend fun deleteForceUpdate(version: String)
    
    @Query("DELETE FROM force_updates")
    suspend fun clearAll()
}