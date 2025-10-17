package it.fabiodirauso.shutappchat.api

import it.fabiodirauso.shutappchat.model.User

// Request per cercare utenti
data class UserSearchRequest(
    val query: String,
    val limit: Int = 20
)

// Response della ricerca utenti
data class UserSearchResponse(
    val success: Boolean,
    val users: List<User> = emptyList()
)

// Request per inviare richiesta di amicizia
data class ContactRequestRequest(
    val to: String, // Server expects 'to' not 'username'
    val message: String? = null
)

// Response per richiesta di amicizia
data class ContactRequestResponse(
    val requested: Boolean? = null,  // Server response field
    val success: Boolean? = null,     // Legacy field (optional)
    val message: String? = null,
    val already_contact: Boolean? = null  // If already contacts
) {
    // Helper to check if request was successful
    fun isSuccess(): Boolean = requested == true || success == true
}

// Request per accettare/rifiutare richiesta
data class ContactRespondRequest(
    val from: String? = null,  // Username (alternative to id)
    val id: Int? = null,       // Request ID (alternative to from)
    val action: String         // "accept" or "decline"
)

// Response per accettazione/rifiuto
data class ContactRespondResponse(
    val accepted: Boolean? = null,  // Server response for accept
    val declined: Boolean? = null,  // Server response for decline
    val success: Boolean? = null,   // Legacy field (optional)
    val message: String? = null
) {
    // Helper to check if request was successful
    fun isSuccess(): Boolean = accepted == true || declined == true || success == true
}

// Modello per richiesta di contatto pendente
data class ContactRequest(
    val id: String,
    val fromUser: User,
    val toUser: User,
    val message: String? = null,
    val status: String, // "pending", "accepted", "declined"
    val createdAt: String,
    val updatedAt: String? = null
)

// Response per lista richieste pendenti - il server ritorna solo 'requests'
data class ContactRequestsResponse(
    val requests: List<PendingContactRequestAPI> = emptyList()
)

// Modello semplificato per richieste dal server (formato API v2)
data class PendingContactRequestAPI(
    val id: Long,
    val sender: String,        // Username del mittente
    val receiver: String,      // Username del destinatario
    val status: String,        // "pending", "accepted", "declined"
    val timestamp: String,     // Data di creazione
    val processed_at: String? = null,  // Data di elaborazione
    val message: String? = null
)

// Response per lista contatti/amici
data class ContactsResponse(
    val success: Boolean,
    val contacts: List<User> = emptyList()
)