package it.fabiodirauso.shutappchat.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "force_updates")
data class ForceUpdateEntity(
    @PrimaryKey
    val version: String,
    val message: String,
    val downloadUrl: String,
    val receivedAt: Long = System.currentTimeMillis(),
    val isDownloading: Boolean = false,
    val isInstalling: Boolean = false,
    val downloadProgress: Int = 0
)