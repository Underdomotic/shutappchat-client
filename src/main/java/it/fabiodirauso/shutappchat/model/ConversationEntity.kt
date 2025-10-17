package it.fabiodirauso.shutappchat.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    val participantId: String,
    val participantName: String,
    val participantUsername: String? = null, // ✅ Username per inviare messaggi
    val profilePictureId: String? = null,
    val lastMessage: String? = null,
    val lastMessageTime: Date? = null,
    val unreadCount: Int = 0,
    val isGroup: Boolean = false
)
