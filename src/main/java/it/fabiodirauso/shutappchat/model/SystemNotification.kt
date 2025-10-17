package it.fabiodirauso.shutappchat.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * System notification entity
 * Represents admin notifications sent to users
 */
@Entity(tableName = "system_notifications")
data class SystemNotification(
    @PrimaryKey
    val id: Long,
    val title: String?,
    val description: String?,
    val url: String?,
    val timestamp: Long,
    val read: Boolean = false
)

/**
 * WebSocket envelope for system notifications
 */
data class SystemNotificationEnvelope(
    val v: Int = 1,
    val type: String = "system_notification",
    val title: String?,
    val description: String?,
    val url: String?,
    val ts: Long,
    val id: Long
)