package it.fabiodirauso.shutappchat.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entità per rappresentare un gruppo di conversazione
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey
    val groupId: String,                    // ID univoco del gruppo (generato dal server)
    
    val groupName: String,                  // Nome del gruppo
    val groupDescription: String? = null,   // Descrizione del gruppo (opzionale)
    val groupPictureId: String? = null,     // ID immagine profilo gruppo
    
    val createdBy: Long,                    // User ID del creatore
    val createdAt: Long,                    // Timestamp creazione (millisecondi)
    val updatedAt: Long,                    // Timestamp ultimo aggiornamento
    
    val groupMode: GroupMode = GroupMode.OPEN,  // Modalità gruppo: OPEN o RESTRICTED
    
    val lastMessageContent: String? = null, // Ultimo messaggio (per preview)
    val lastMessageTime: Long = 0,          // Timestamp ultimo messaggio
    val unreadCount: Int = 0,               // Numero messaggi non letti
    
    val totalMembers: Int = 0,              // Numero totale membri
    val isActive: Boolean = true            // Se il gruppo è attivo
)

/**
 * Modalità del gruppo
 */
enum class GroupMode {
    OPEN,       // Tutti i membri possono scrivere e inviare media
    RESTRICTED  // Solo gli admin possono scrivere e inviare media
}
