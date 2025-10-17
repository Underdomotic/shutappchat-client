package it.fabiodirauso.shutappchat.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey
    val id: Long,
    val username: String,
    val nickname: String? = null, // API uses 'nickname' not 'displayName'
    val email: String? = null,
    val profile_picture: String? = null, // API field name for avatar media ID
    val avatarUrl: String? = null, // Legacy field, keep for compatibility
    val isOnline: Boolean = false,
    val lastSeen: Long? = null
)
