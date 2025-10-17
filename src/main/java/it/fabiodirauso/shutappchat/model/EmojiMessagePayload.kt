package it.fabiodirauso.shutappchat.model

data class EmojiMessagePayload(
    val emoji: String,
    val unicode: String? = null
)
