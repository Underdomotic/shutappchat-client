package it.fabiodirauso.shutappchat.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

enum class ContactRequestStatus {
    PENDING,
    ACCEPTED,
    REJECTED
}

@Entity(tableName = "contact_requests")
data class ContactRequest(
    @PrimaryKey
    val id: Long,
    val fromUserId: Long,
    val fromUsername: String,
    val fromNickname: String? = null,
    val fromProfilePicture: String? = null,
    val toUserId: Long,
    val status: ContactRequestStatus = ContactRequestStatus.PENDING,
    val createdAt: Date,
    val updatedAt: Date? = null
)
