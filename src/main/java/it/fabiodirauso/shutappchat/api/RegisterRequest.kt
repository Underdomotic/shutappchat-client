package it.fabiodirauso.shutappchat.api

data class RegisterRequest(
    val username: String,
    val password: String,
    val nickname: String? = null
)
