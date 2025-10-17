package it.fabiodirauso.shutappchat.api

import it.fabiodirauso.shutappchat.model.User

data class LoginResponse(
    val success: Boolean,
    val message: String? = null,
    val token: String? = null,
    val user: User? = null
)
