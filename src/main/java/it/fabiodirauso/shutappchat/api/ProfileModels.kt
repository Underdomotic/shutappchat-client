package it.fabiodirauso.shutappchat.api

import it.fabiodirauso.shutappchat.model.User

// Profile responses
data class UserProfileResponse(
    val success: Boolean,
    val user: User? = null,
    val message: String? = null
)

// Profile update requests
data class UpdateUserInfoRequest(
    val nickname: String
)

data class UpdateProfilePictureRequest(
    val url: String? = null,
    val path: String? = null
)

// Contact status response
data class ContactStatusResponse(
    val success: Boolean,
    val status: String // "self", "contact", "pending_outgoing", "pending_incoming", "none"
)

// Basic response for simple operations
data class BasicResponse(
    val success: Boolean,
    val message: String? = null
)

// Profile media models (per immagini profilo - LEGACY)
data class MediaInitRequest(
    val filename: String,
    val mime: String,
    val size: Long,
    val receiver: String? = null,
    val salvable: Boolean? = null,
    val senderAutoDelete: Boolean? = null  // Auto-delete setting del mittente
)

data class MediaInitResponse(
    val success: Boolean? = null,
    val id: Any? = null,  // Can be Int or String
    val key: String? = null,
    val iv: String? = null,
    val message: String? = null
)

data class MediaUploadResponse(
    val success: Boolean? = null,
    val ok: Boolean? = null,
    val next: Long? = null,
    val complete: Boolean? = null,
    val message: String? = null
)

// Chat media models (per messaggi multimediali con salvable)
data class ChatMediaInitRequest(
    val filename: String,
    val mime: String,
    val size: Long,
    val receiver: String? = null,
    val salvable: Boolean = true
)

data class ChatMediaInitResponse(
    val id: String,
    val key: String,
    val iv: String
)

data class ChatMediaUploadResponse(
    val ok: Boolean,
    val next: Long? = null,
    val complete: Boolean
)

// Message models
data class MessageRequest(
    val to: String,
    val message: String, // base64 ciphertext
    val aesKey: String, // base64 per-message key
    val iv: String? = null, // base64 IV (optional)
    val hmac: String, // base64 sha256(token)
    val unixTs: Long,
    // ✅ Reply fields (v1.2.0)
    val replyToMessageId: String? = null,
    val replyToContent: String? = null,
    val replyToSenderId: Long? = null
)

data class MessageResponse(
    val success: Boolean? = null,      // Legacy v1 API
    val message: String? = null,       // Legacy v1 API
    val queued: Boolean? = null,       // v2 API
    val id: Int? = null,               // v2 API (pending message ID)
    val duplicate: Boolean? = null     // v2 API (idempotency check)
) {
    // Helper per verificare se la richiesta ha avuto successo
    fun isSuccess(): Boolean = success == true || queued == true
}

// Pending messages
data class PendingMessage(
    val id: String,
    val sender: String,
    val message: String,
    val key: String? = null,
    val iv: String? = null,
    val timestamp: String
)

data class PendingMessagesResponse(
    val success: Boolean,
    val messages: List<PendingMessage> = emptyList()
)

// WebSocket service pending messages (different format)
data class PendingMessagesWsResponse(
    val messages: List<PendingMessage> = emptyList()
)