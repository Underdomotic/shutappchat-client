package it.fabiodirauso.shutappchat.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import it.fabiodirauso.shutappchat.api.MessageRequest
import it.fabiodirauso.shutappchat.network.RetrofitClient
import it.fabiodirauso.shutappchat.utils.CryptoUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MessageSender(
    private val retrofitClient: RetrofitClient,
    private val context: Context,
    private val webSocketFallback: ((String, String) -> Unit)? = null // Callback for WebSocket fallback
) {
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    
    companion object {
        private const val TAG = "MessageSender"
    }

    fun sendTextMessage(
        messageText: String, 
        recipientUsername: String,
        replyToMessageId: String? = null,
        replyToContent: String? = null,
        replyToSenderId: Long? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Iniziando invio messaggio a recipient: $recipientUsername")
                Log.d(TAG, "Contenuto messaggio: $messageText")
                if (replyToMessageId != null) {
                    Log.d(TAG, "Reply a messaggio ID: $replyToMessageId")
                }
                
                val token = sharedPreferences.getString("auth_token", "") ?: ""
                Log.d(TAG, "Token auth dal MessageSender: ${if (token.isNotEmpty()) "presente (${token.length} chars) - ${token.take(8)}..." else "mancante"}")
                Log.d(TAG, "Tentativo invio messaggio via API REST")
                
                if (token.isEmpty()) {
                    Log.e(TAG, "Token mancante - impossibile inviare messaggio")
                    return@launch
                }
                
                // Genera chiave AES e IV per questo messaggio
                val aesKey = CryptoUtils.generateAESKey()
                val iv = CryptoUtils.generateIV()
                Log.d(TAG, "Chiavi AES e IV generate")
                
                // Cripta il messaggio
                val encryptedMessage = CryptoUtils.encryptAES(
                    messageText.toByteArray(), 
                    aesKey, 
                    iv
                )
                Log.d(TAG, "Messaggio criptato, dimensione: ${encryptedMessage.size} bytes")
                
                // Prepara i dati per la richiesta
                val encryptedMessageBase64 = CryptoUtils.bytesToBase64(encryptedMessage)
                val aesKeyBase64 = CryptoUtils.keyToBase64(aesKey)
                val ivBase64 = CryptoUtils.bytesToBase64(iv)
                val unixTimestamp = System.currentTimeMillis() / 1000
                
                // Crea HMAC secondo il formato server: cipher + aesKey + unixTs
                val hmacData = encryptedMessageBase64 + aesKeyBase64 + unixTimestamp
                val hmac = CryptoUtils.generateHMAC(hmacData, token)
                Log.d(TAG, "HMAC generato")
                
                // Crea la richiesta
                val request = MessageRequest(
                    to = recipientUsername,
                    message = encryptedMessageBase64,
                    aesKey = aesKeyBase64,
                    iv = ivBase64,
                    hmac = hmac,
                    unixTs = unixTimestamp,
                    replyToMessageId = replyToMessageId,
                    replyToContent = replyToContent,
                    replyToSenderId = replyToSenderId
                )
                Log.d(TAG, "Richiesta creata per recipient: ${request.to}")
                Log.d(TAG, "Message (base64): ${request.message}")
                Log.d(TAG, "AES Key (base64): ${request.aesKey}")
                Log.d(TAG, "IV (base64): ${request.iv}")
                Log.d(TAG, "HMAC: ${request.hmac}")
                Log.d(TAG, "Unix TS: ${request.unixTs}")
                if (replyToMessageId != null) {
                    Log.d(TAG, "Reply To Message ID: $replyToMessageId")
                }
                
                // Invia via API
                Log.d(TAG, "Invio tramite API...")
                val response = retrofitClient.apiService.sendMessage(request)
                
                Log.d(TAG, "Risposta ricevuta - Successo: ${response.isSuccessful}, Codice: ${response.code()}")
                
                if (response.isSuccessful && response.body()?.isSuccess() == true) {
                    Log.d(TAG, "Messaggio inviato con successo via API (queued=${response.body()?.queued}, id=${response.body()?.id})")
                } else {
                    Log.e(TAG, "Errore invio messaggio via API: ${response.body()?.message}")
                    Log.e(TAG, "Response body: ${response.body()}")
                    Log.e(TAG, "Response code: ${response.code()}")
                    Log.e(TAG, "Response message: ${response.message()}")
                    
                    // Log error body as string if available
                    try {
                        val errorBody = response.errorBody()?.string()
                        Log.e(TAG, "Error body: $errorBody")
                    } catch (e: Exception) {
                        Log.e(TAG, "Cannot read error body", e)
                    }
                    
                    // Fallback WebSocket se disponibile
                    if (webSocketFallback != null) {
                        Log.w(TAG, "HTTP upload failed, trying WebSocket fallback")
                        webSocketFallback.invoke(recipientUsername, messageText)
                    } else {
                        Log.e(TAG, "HTTP upload failed and no WebSocket fallback available")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Errore invio messaggio", e)
                
                // Fallback WebSocket su eccezione di rete
                if (webSocketFallback != null) {
                    Log.w(TAG, "Network exception, trying WebSocket fallback")
                    webSocketFallback.invoke(recipientUsername, messageText)
                } else {
                    Log.e(TAG, "Network exception and no WebSocket fallback available")
                }
            }
        }
    }
    
    /**
     * Invia notifica di messaggio media al destinatario
     * Il media è già stato caricato sul server, questa funzione notifica solo il destinatario
     */
    fun sendMediaNotification(
        recipientUsername: String,
        mediaId: String,
        encryptedKey: String,
        iv: String,
        filename: String,
        mimeType: String,
        size: Long,
        salvable: Boolean,
        senderAutoDelete: Boolean = false,
        caption: String? = null,
        thumbnail: String? = null  // 🎯 Thumbnail base64 per cache
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Inviando notifica media a: $recipientUsername")
                Log.d(TAG, "Media ID: $mediaId")
                
                val token = sharedPreferences.getString("auth_token", "") ?: ""
                if (token.isEmpty()) {
                    Log.e(TAG, "Token mancante - impossibile inviare notifica media")
                    return@launch
                }
                
                // Crea un payload JSON con i dati del media
                val mediaPayload = mapOf(
                    "mediaId" to mediaId,
                    "encryptedKey" to encryptedKey,
                    "iv" to iv,
                    "filename" to filename,
                    "mime" to mimeType,
                    "size" to size,
                    "salvable" to salvable,
                    "senderAutoDelete" to senderAutoDelete,
                    "caption" to caption,
                    "thumbnail" to thumbnail  // 🎯 PASS THUMBNAIL!
                )
                
                val payloadJson = com.google.gson.Gson().toJson(mediaPayload)
                Log.d(TAG, "Payload media: $payloadJson")
                
                // Genera chiavi per criptare il payload
                val aesKey = CryptoUtils.generateAESKey()
                val payloadIv = CryptoUtils.generateIV()
                
                // Cripta il payload
                val encryptedPayload = CryptoUtils.encryptAES(
                    payloadJson.toByteArray(),
                    aesKey,
                    payloadIv
                )
                
                val encryptedPayloadBase64 = CryptoUtils.bytesToBase64(encryptedPayload)
                val aesKeyBase64 = CryptoUtils.keyToBase64(aesKey)
                val ivBase64 = CryptoUtils.bytesToBase64(payloadIv)
                val unixTimestamp = System.currentTimeMillis() / 1000
                
                // Genera HMAC
                val hmacData = encryptedPayloadBase64 + aesKeyBase64 + unixTimestamp
                val hmac = CryptoUtils.generateHMAC(hmacData, token)
                
                // Crea la richiesta
                val request = it.fabiodirauso.shutappchat.api.MessageRequest(
                    to = recipientUsername,
                    message = encryptedPayloadBase64,
                    aesKey = aesKeyBase64,
                    iv = ivBase64,
                    hmac = hmac,
                    unixTs = unixTimestamp
                )
                
                // Invia via API
                Log.d(TAG, "Invio notifica media tramite API...")
                val response = retrofitClient.apiService.sendMessage(request)
                
                Log.d(TAG, "Risposta API media - isSuccessful: ${response.isSuccessful}, code: ${response.code()}")
                Log.d(TAG, "Response body: queued=${response.body()?.queued}, id=${response.body()?.id}, success=${response.body()?.success}")
                
                if (response.isSuccessful && response.body()?.isSuccess() == true) {
                    val pendingId = response.body()?.id ?: 0
                    Log.d(TAG, "Notifica media inviata con successo (pending_id=$pendingId, queued=${response.body()?.queued})")
                } else {
                    Log.e(TAG, "Errore invio notifica media: ${response.body()?.message}")
                    Log.e(TAG, "Response code: ${response.code()}, message: ${response.message()}")
                    
                    try {
                        val errorBody = response.errorBody()?.string()
                        Log.e(TAG, "Error body: $errorBody")
                    } catch (e: Exception) {
                        Log.e(TAG, "Cannot read error body", e)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Errore invio notifica media", e)
            }
        }
    }
}
