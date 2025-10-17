package it.fabiodirauso.shutappchat.model

data class MediaMessagePayload(
    val id: String,
    val mediaId: String,
    val encryptedKey: String,
    val iv: String,
    val filename: String,
    val mime: String,
    val size: Long,
    val salvable: Boolean,
    val senderAutoDelete: Boolean = false,  // Auto-delete setting del mittente
    val thumbnail: String? = null,
    val caption: String? = null
)
