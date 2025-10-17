package it.fabiodirauso.shutappchat.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity per memorizzare i settings di privacy localmente
 * Usa un singolo record con id=1 per tutti i settings dell'utente corrente
 */
@Entity(tableName = "privacy_settings")
data class PrivacySettingsEntity(
    @PrimaryKey
    val id: Int = 1, // Sempre 1, singolo record per utente
    
    val allowReadReceipts: Boolean = true,
    val blockScreenshots: Boolean = false,
    val protectMedia: Boolean = false,
    val obfuscateMediaFiles: Boolean = false,
    val autoDeleteMediaOnOpen: Boolean = false,
    val autoDownloadVideos: Boolean = false,  // 🎯 NUOVO: Download automatico video
    
    val lastSyncTimestamp: Long = System.currentTimeMillis()
)
