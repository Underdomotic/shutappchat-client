package it.fabiodirauso.shutappchat.api

import it.fabiodirauso.shutappchat.model.User

data class RegisterResponse(
    val created: Boolean? = null,
    val success: Boolean? = null,
    val message: String? = null,
    val user: User? = null,
    val detail: String? = null
)
