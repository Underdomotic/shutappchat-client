package it.fabiodirauso.shutappchat.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

enum class MessageStatus {
    PENDING,    // Message locally stored, not sent yet
    SENT,       // Message sent to server
    DELIVERED,  // Message delivered to recipient
    READ,       // Message read by recipient
    FAILED      // Message failed to send
}

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey
    val id: String,
    val conversationId: String = "",
    val senderId: Long,
    val senderUsername: String? = null,  // ✅ v1.2.5: Username del mittente (per creare conversazioni)
    val recipientId: Long,
    val content: String,
    val messageType: MessageType,
    val timestamp: Date,
    val isRead: Boolean = false,
    val mediaId: String? = null,
    val mediaKey: String? = null,
    val mediaIv: String? = null,
    val filename: String? = null,
    val mime: String? = null,
    val size: Long? = null,
    val thumbnail: String? = null,
    val caption: String? = null,
    val mediaSalvable: Boolean? = null,  // Se true, il media può essere salvato in chiaro
    val senderAutoDelete: Boolean? = null,  // Auto-delete setting del mittente
    val status: MessageStatus = MessageStatus.PENDING,  // Message delivery status
    val replyToMessageId: String? = null,  // ✅ ID del messaggio a cui si risponde
    val replyToContent: String? = null,  // ✅ Contenuto del messaggio quotato (cache)
    val replyToSenderId: Long? = null,  // ✅ ID del mittente del messaggio quotato
    val isGroup: Boolean = false,  // ✅ Se true, questo è un messaggio di gruppo
    val groupId: String? = null  // ✅ ID del gruppo (se isGroup=true)
) {
    companion object {
        fun create(
            id: String,
            senderId: Long,
            recipientId: Long,
            content: String,
            messageType: MessageType,
            timestampLong: Long,
            conversationId: String = "",
            senderUsername: String? = null,
            isRead: Boolean = false,
            mediaId: String? = null,
            mediaKey: String? = null,
            mediaIv: String? = null,
            filename: String? = null,
            mime: String? = null,
            size: Long? = null,
            thumbnail: String? = null,
            caption: String? = null,
            mediaSalvable: Boolean? = null,
            senderAutoDelete: Boolean? = null,
            status: MessageStatus = MessageStatus.PENDING,
            replyToMessageId: String? = null,
            replyToContent: String? = null,
            replyToSenderId: Long? = null
        ): Message {
            return Message(
                id = id,
                conversationId = conversationId,
                senderId = senderId,
                senderUsername = senderUsername,
                recipientId = recipientId,
                content = content,
                messageType = messageType,
                timestamp = Date(timestampLong),
                isRead = isRead,
                mediaId = mediaId,
                mediaKey = mediaKey,
                mediaIv = mediaIv,
                filename = filename,
                mime = mime,
                size = size,
                thumbnail = thumbnail,
                caption = caption,
                mediaSalvable = mediaSalvable,
                senderAutoDelete = senderAutoDelete,
                status = status,
                replyToMessageId = replyToMessageId,
                replyToContent = replyToContent,
                replyToSenderId = replyToSenderId
            )
        }
    }
}
