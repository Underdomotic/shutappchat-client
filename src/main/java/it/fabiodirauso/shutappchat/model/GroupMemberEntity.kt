package it.fabiodirauso.shutappchat.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entità per rappresentare un membro di un gruppo
 */
@Entity(
    tableName = "group_members",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["userId"]),
        Index(value = ["groupId", "userId"], unique = true)
    ]
)
data class GroupMemberEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val groupId: String,                // ID del gruppo
    val userId: Long,                   // ID dell'utente
    val username: String,               // Username dell'utente
    val displayName: String,            // Nome visualizzato
    
    val role: GroupRole = GroupRole.MEMBER,  // Ruolo: ADMIN o MEMBER
    
    val joinedAt: Long,                 // Timestamp di ingresso nel gruppo
    val addedBy: Long? = null,          // User ID di chi ha aggiunto questo membro
    
    val isActive: Boolean = true        // Se il membro è ancora attivo nel gruppo
)

/**
 * Ruolo di un membro nel gruppo
 */
enum class GroupRole {
    ADMIN,   // Amministratore: può gestire membri, impostazioni, eliminare messaggi
    MEMBER   // Membro normale: può solo leggere/scrivere (se consentito dalle impostazioni)
}
