package it.fabiodirauso.shutappchat.utils

import android.content.Context
import it.fabiodirauso.shutappchat.R
import it.fabiodirauso.shutappchat.model.Message
import it.fabiodirauso.shutappchat.model.MessageType

/**
 * Helper per formattare i messaggi nella lista conversazioni e nelle notifiche
 */
object MessageFormatter {
    
    /**
     * Restituisce un testo descrittivo per l'ultimo messaggio di una conversazione
     * basato sul tipo di messaggio.
     * 
     * @param context Context Android per accedere alle risorse
     * @param message Il messaggio da formattare
     * @return Testo descrittivo (es. "📷 Photo", "🎥 Video", "📎 Document")
     */
    fun getLastMessagePreview(context: Context, message: Message): String {
        return when (message.messageType) {
            MessageType.TEXT -> message.content
            MessageType.EMOJI -> message.content
            MessageType.IMAGE -> context.getString(R.string.message_preview_photo)
            MessageType.VIDEO -> context.getString(R.string.message_preview_video)
            MessageType.DOCUMENT -> context.getString(R.string.message_preview_document)
            else -> message.content
        }
    }
    
    /**
     * Restituisce un testo descrittivo per le notifiche
     * 
     * @param context Context Android per accedere alle risorse
     * @param message Il messaggio da formattare
     * @param senderName Nome del mittente (opzionale)
     * @return Testo per la notifica
     */
    fun getNotificationText(context: Context, message: Message, senderName: String? = null): String {
        val prefix = if (senderName != null) "$senderName: " else ""
        
        return when (message.messageType) {
            MessageType.TEXT -> prefix + message.content
            MessageType.EMOJI -> prefix + message.content
            MessageType.IMAGE -> prefix + context.getString(R.string.message_notification_sent_photo)
            MessageType.VIDEO -> prefix + context.getString(R.string.message_notification_sent_video)
            MessageType.DOCUMENT -> prefix + context.getString(R.string.message_notification_sent_document)
            else -> prefix + message.content
        }
    }
    
    /**
     * Restituisce solo l'icona per il tipo di messaggio
     * 
     * @param messageType Il tipo di messaggio
     * @return Emoji icona
     */
    fun getMessageTypeIcon(messageType: MessageType): String {
        return when (messageType) {
            MessageType.IMAGE -> "📷"
            MessageType.VIDEO -> "🎥"
            MessageType.DOCUMENT -> "📎"
            MessageType.EMOJI -> "😀"
            MessageType.TEXT -> "💬"
            else -> "📄"
        }
    }
    
    /**
     * Restituisce la descrizione del tipo di messaggio
     * 
     * @param context Context Android per accedere alle risorse
     * @param messageType Il tipo di messaggio
     * @return Descrizione testuale
     */
    fun getMessageTypeDescription(context: Context, messageType: MessageType): String {
        return when (messageType) {
            MessageType.IMAGE -> context.getString(R.string.message_preview_photo)
            MessageType.VIDEO -> context.getString(R.string.message_preview_video)
            MessageType.DOCUMENT -> context.getString(R.string.message_preview_document)
            MessageType.EMOJI -> "Emoji"
            MessageType.TEXT -> "Message"
            else -> "Content"
        }
    }
}
