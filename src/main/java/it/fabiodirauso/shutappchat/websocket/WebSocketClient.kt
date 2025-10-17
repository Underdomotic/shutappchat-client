package it.fabiodirauso.shutappchat.websocket

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import it.fabiodirauso.shutappchat.auth.SessionValidator
import it.fabiodirauso.shutappchat.auth.TokenManager
import it.fabiodirauso.shutappchat.model.Message
import it.fabiodirauso.shutappchat.model.MessageType
import it.fabiodirauso.shutappchat.model.MessageStatus
import it.fabiodirauso.shutappchat.utils.CryptoUtils
import it.fabiodirauso.shutappchat.session.SessionHealthMonitor
import it.fabiodirauso.shutappchat.database.AppDatabase
import it.fabiodirauso.shutappchat.GroupChatActivity
import okhttp3.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.Date

class WebSocketClient(
    private val context: Context,
    private val baseUrl: String,
    private val token: String,
    val userId: Long,  // Made public so it can be accessed
    private val username: String,
    private val userAgent: String = "ShutAppChat|v1.0|1" // Default fallback
) {
    
    companion object {
        private const val TAG = "WebSocketClient"
        private const val RECONNECT_DELAY = 5000L // 5 seconds (fallback, ConnectionRecoveryManager uses exponential backoff)
        private const val PING_INTERVAL = 25000L // 25 seconds
    }
    
    // Auth components
    private val tokenManager = TokenManager.getInstance(context)
    private val sessionValidator = SessionValidator(context)
    private lateinit var recoveryManager: ConnectionRecoveryManager
    
    // Database for message status updates
    private val database = AppDatabase.getDatabase(context)
    
    // ✅ Time synchronization: offset in milliseconds (server time - client time)
    private var serverTimeOffset: Long = 0L
    private var lastSyncTime: Long = 0L
    
    enum class ConnectionState {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        ERROR
    }
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _incomingMessages = MutableStateFlow<Message?>(null)
    val incomingMessages: StateFlow<Message?> = _incomingMessages.asStateFlow()
    
    private val _typingUsers = MutableStateFlow<Set<Long>>(emptySet())
    val typingUsers: StateFlow<Set<Long>> = _typingUsers.asStateFlow()
    
    // Session recovery notification
    private val _sessionInvalidEvent = MutableStateFlow<SessionInvalidReason?>(null)
    val sessionInvalidEvent: StateFlow<SessionInvalidReason?> = _sessionInvalidEvent.asStateFlow()
    
    // Group events notification
    private val _groupEvent = MutableSharedFlow<GroupEvent>(replay = 0)
    val groupEvent: SharedFlow<GroupEvent> = _groupEvent.asSharedFlow()
    
    data class GroupEvent(
        val type: String, // "MEMBER_ADDED", "group_added", etc.
        val groupId: String,
        val groupName: String? = null,
        val data: Map<String, Any> = emptyMap()
    )
    
    enum class SessionInvalidReason {
        TOKEN_EXPIRED,
        UNAUTHORIZED,
        VALIDATION_FAILED
    }
    
    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL, TimeUnit.MILLISECONDS)
        .build()
    
    private val scope = CoroutineScope(Dispatchers.IO)
    private val sessionMonitor = SessionHealthMonitor.getInstance()
    
    init {
        // Inizializza ConnectionRecoveryManager con callback di reconnect
        recoveryManager = ConnectionRecoveryManager(context) {
            connectInternal()
        }
        // Health checks verranno avviati solo dopo connessione riuscita (in onOpen)
    }
    
    fun connect() {
        Log.i(TAG, "[CONNECT] Called - current state: ${_connectionState.value}")
        
        if (_connectionState.value == ConnectionState.CONNECTING || 
            _connectionState.value == ConnectionState.CONNECTED) {
            Log.d(TAG, "[CONNECT] Already connecting/connected, skipping")
            return
        }
        
        // Usa ConnectionRecoveryManager per gestire la connessione
        scope.launch {
            // TEMPORANEO: Skip session validation perché endpoint non esiste ancora (404)
            // Valida sessione prima di connettersi
            // Log.d(TAG, "[CONNECT] Validating session...")
            // val validationResult = sessionValidator.validateSession()
            
            // Per ora connetti direttamente
            Log.d(TAG, "[CONNECT] Skipping session validation (endpoint not available), connecting directly")
            connectInternal()
                
                /* TEMPORANEO: Commentato per skip validation
                when (validationResult) {
                    is SessionValidator.ValidationResult.Valid -> {
                        Log.d(TAG, "Session valid, connecting to WebSocket")
                        connectInternal()
                    }
                    is SessionValidator.ValidationResult.Invalid -> {
                        Log.e(TAG, "Session invalid: ${validationResult.message}")
                        _connectionState.value = ConnectionState.ERROR
                        if (validationResult.requiresReauth) {
                            // Notifica UI per re-login
                            Log.w(TAG, "Session requires re-authentication")
                            _sessionInvalidEvent.value = when {
                                validationResult.message.contains("expired", ignoreCase = true) -> SessionInvalidReason.TOKEN_EXPIRED
                                validationResult.message.contains("unauthorized", ignoreCase = true) -> SessionInvalidReason.UNAUTHORIZED
                                else -> SessionInvalidReason.VALIDATION_FAILED
                            }
                            
                            // Invalida sessione locale
                            scope.launch {
                                tokenManager.invalidateSession()
                            }
                        }
                    }
                    is SessionValidator.ValidationResult.NetworkError -> {
                        Log.w(TAG, "Network error during session validation: ${validationResult.error}")
                        // Avvia recovery che riproverà automaticamente
                        recoveryManager.startRecovery("Network error: ${validationResult.error}")
                    }
                    is SessionValidator.ValidationResult.ServerError -> {
                        Log.e(TAG, "Server error during session validation: ${validationResult.code} ${validationResult.message}")
                        recoveryManager.startRecovery("Server error: ${validationResult.code}")
                    }
                }
                */
            /* TEMPORANEO: Commentato catch block
            } catch (e: Exception) {
                Log.e(TAG, "Error during session validation", e)
                recoveryManager.startRecovery("Validation error: ${e.message}")
            }
            */
        }
    }
    
    private suspend fun connectInternal() {
        _connectionState.value = ConnectionState.CONNECTING
        Log.d(TAG, "Connecting to WebSocket...")
        Log.d(TAG, "User-Agent: $userAgent")
        
        // Ottieni token aggiornato dal TokenManager
        val currentToken = tokenManager.getToken() ?: token
        
        val url = "$baseUrl?u=$username&token=$currentToken"
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", userAgent)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "[WS OPEN] WebSocket connected successfully")
                _connectionState.value = ConnectionState.CONNECTED
                sessionMonitor.recordWebSocketSuccess()
                recoveryManager.stopRecovery() // Stop recovery on successful connection
                
                // Avvia health checks solo quando connesso
                Log.d(TAG, "[WS OPEN] Starting health checks")
                recoveryManager.startHealthChecks {
                    val connected = _connectionState.value == ConnectionState.CONNECTED
                    Log.v(TAG, "[WS HEALTH CHECK] Connection state: ${_connectionState.value}, healthy: $connected")
                    connected
                }
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                handleIncomingMessage(text)
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "[WS CLOSED] code=$code reason='$reason' - current state: ${_connectionState.value}")
                _connectionState.value = ConnectionState.DISCONNECTED
                recoveryManager.stopHealthChecks() // Stop health checks when disconnected
                
                // CRITICAL FIX: Code 1000 = Normal Closure, non serve recovery
                if (code == 1000) {
                    Log.i(TAG, "[WS CLOSED] Normal closure (code 1000), no recovery needed")
                } else {
                    // Chiusura anomala = problema di rete/server, non di auth
                    // NON registriamo come session failure, solo recovery
                    Log.i(TAG, "[WS CLOSED] Abnormal closure (code $code), starting recovery...")
                    recoveryManager.startRecovery("Connection closed: $code $reason")
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "[WS FAILURE] Exception: ${t.message} - current state: ${_connectionState.value}", t)
                _connectionState.value = ConnectionState.ERROR
                recoveryManager.stopHealthChecks() // Stop health checks on error
                
                // Failure = problema di rete/server (EOFException, 503, timeout)
                // NON registriamo come session failure, solo recovery
                Log.w(TAG, "[WS FAILURE] Starting recovery...")
                recoveryManager.startRecovery("Connection error: ${t.message}")
            }
        })
    }
    
    fun disconnect() {
        recoveryManager.stopRecovery()
        recoveryManager.stopHealthChecks()
        webSocket?.close(1000, "Client disconnect")
        _connectionState.value = ConnectionState.DISCONNECTED
    }
    
    fun cleanup() {
        disconnect()
        recoveryManager.cleanup()
    }
    
    /**
     * Reset session invalid event (consume event pattern)
     */
    fun resetSessionInvalidEvent() {
        _sessionInvalidEvent.value = null
    }
    
    /**
     * Map username to user ID from database
     * Falls back to hashCode if user not found in database
     */
    private suspend fun getUserIdFromUsername(username: String): Long {
        return try {
            database.userDao().getUserIdByUsername(username) ?: username.hashCode().toLong()
        } catch (e: Exception) {
            Log.w(TAG, "Error getting user ID for username=$username, using hashCode fallback", e)
            username.hashCode().toLong()
        }
    }
    
    /**
     * Invia un messaggio raw (per emoji e media)
     */
    fun sendRawMessage(messageJson: String) {
        webSocket?.send(messageJson)
        Log.d(TAG, "Sent raw: $messageJson")
    }
    
    /**
     * Invia un messaggio criptato via WebSocket secondo il protocollo v1
     * @param recipientUsername Username del destinatario
     * @param content Contenuto del messaggio in plaintext
     */
    suspend fun sendMessage(recipientUsername: String, content: String) {
        try {
            // Genera ID univoco per il messaggio
            val messageId = UUID.randomUUID().toString()
            
            // Timestamp unix in secondi
            val unixTimestamp = System.currentTimeMillis() / 1000
            
            // Genera chiave AES e IV per questo messaggio
            val aesKey = CryptoUtils.generateAESKey()
            val iv = CryptoUtils.generateIV()
            
            // Cripta il contenuto
            val encryptedBytes = CryptoUtils.encryptAES(
                content.toByteArray(Charsets.UTF_8),
                aesKey,
                iv
            )
            
            // Converte in base64
            val aesKeyBase64 = CryptoUtils.keyToBase64(aesKey)
            val ivBase64 = CryptoUtils.bytesToBase64(iv)
            val payloadBase64 = CryptoUtils.bytesToBase64(encryptedBytes)
            
            // Canonical string per HMAC: ${id}|${from}|${to}|${ts}|${iv}|${payload}|${key}
            val canonicalString = "$messageId|$username|$recipientUsername|$unixTimestamp|$ivBase64|$payloadBase64|$aesKeyBase64"
            
            // Genera HMAC con il token come chiave
            val hmac = CryptoUtils.generateHMAC(canonicalString, token)
            
            // Crea envelope
            val envelope = mapOf(
                "v" to 1,
                "type" to "msg",
                "id" to messageId,
                "from" to username,
                "to" to recipientUsername,
                "ts" to unixTimestamp,
                "iv" to ivBase64,
                "payload" to payloadBase64,
                "key" to aesKeyBase64,
                "hmac" to hmac
            )
            
            // Invia via WebSocket
            val json = gson.toJson(envelope)
            webSocket?.send(json)
            
            Log.d(TAG, "Sent encrypted message: id=$messageId to=$recipientUsername")
            Log.d(TAG, "Canonical: $canonicalString")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            throw e
        }
    }
    
    /**
     * Versione legacy per compatibilità con codice esistente
     * @deprecated Usare sendMessage(recipientUsername: String, content: String)
     */
    @Deprecated("Use sendMessage with username instead of ID")
    suspend fun sendMessage(recipientId: Long, content: String) {
        Log.w(TAG, "sendMessage(Long, String) is deprecated. Need username, not ID")
        // Non possiamo inviare senza lo username, serve conversione upstream
    }
    
    /**
     * Sends a message to a group chat
     * @param groupId Group ID (e.g., "group_123")
     * @param content Message content (plain text)
     */
    suspend fun sendGroupMessage(groupId: String, content: String, messageType: String = "TEXT") {
        try {
            // Generate unique message ID
            val messageId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()
            
            // Build GroupMessage payload
            val groupMessage = mapOf(
                "groupId" to groupId,
                "messageId" to messageId,
                "senderId" to userId,
                "senderUsername" to username,
                "content" to content,
                "messageType" to messageType,
                "timestamp" to timestamp
            )
            
            val payloadJson = gson.toJson(groupMessage)
            
            // Build envelope v2
            val envelope = mapOf(
                "v" to 2,
                "type" to "group_msg",
                "from" to username,
                "to" to groupId,
                "payload" to payloadJson,
                "ts" to timestamp
            )
            
            // Send via WebSocket
            val json = gson.toJson(envelope)
            webSocket?.send(json)
            
            Log.d(TAG, "Sent group message: id=$messageId to=$groupId content=$content")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending group message", e)
            throw e
        }
    }
    
    suspend fun sendTypingIndicator(recipientId: Long, isTyping: Boolean) {
        val envelope = mapOf(
            "v" to 1,
            "type" to "typing",
            "from" to username,
            "to" to recipientId.toString(),
            "ts" to (System.currentTimeMillis() / 1000),
            "state" to if (isTyping) "typing" else "idle"
        )
        
        val json = gson.toJson(envelope)
        webSocket?.send(json)
        Log.d(TAG, "Sent typing indicator: $json")
    }
    
    private fun handleIncomingMessage(json: String) {
        try {
            val envelope = JsonParser.parseString(json).asJsonObject
            val type = envelope.get("type")?.asString
            
            Log.d(TAG, "Processing message type: $type")
            
            when (type) {
                "msg" -> {
                    scope.launch {
                        handleTextMessage(envelope)
                    }
                }
                "emoji_msg" -> {
                    scope.launch {
                        handleEmojiMessage(envelope)
                    }
                }
                "media_msg" -> {
                    scope.launch {
                        handleMediaMessage(envelope)
                    }
                }
                "group_msg", "group_message" -> {
                    scope.launch {
                        handleGroupMessage(envelope)
                    }
                }
                "group_notify" -> {
                    Log.d(TAG, "Launching handleGroupNotification for type: group_notify")
                    scope.launch {
                        handleGroupNotification(envelope)
                    }
                }
                "contact_request" -> {
                    Log.d(TAG, "Received contact request notification")
                    scope.launch {
                        handleContactRequestNotification(envelope)
                    }
                }
                "contact_accepted" -> {
                    Log.d(TAG, "Received contact accepted notification")
                    scope.launch {
                        handleContactAcceptedNotification(envelope)
                    }
                }
                "system_notification" -> {
                    scope.launch {
                        handleSystemNotification(envelope)
                    }
                }
                "force_update" -> {
                    Log.d(TAG, "Received force update notification")
                    scope.launch {
                        handleForceUpdate(envelope)
                    }
                }
                "typing" -> {
                    handleTypingIndicator(envelope)
                }
                "ack" -> {
                    handleAck(envelope)
                }
                "error" -> {
                    handleError(envelope)
                }
                else -> {
                    Log.w(TAG, "Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: $json", e)
        }
    }
    
    private suspend fun handleTextMessage(envelope: JsonObject) {
        try {
            val from = envelope.get("from")?.asString ?: return
            val to = envelope.get("to")?.asString ?: return
            val id = envelope.get("id")?.asString ?: return
            val payloadBase64 = envelope.get("payload")?.asString ?: return
            val ivBase64 = envelope.get("iv")?.asString ?: ""
            val keyBase64 = envelope.get("key")?.asString ?: ""
            val ts = envelope.get("ts")?.asLong ?: (System.currentTimeMillis() / 1000)
            
            // ✅ v1.3.2: Use syncedTs from server for accurate timestamp synchronization
            val serverSyncedTs = envelope.get("syncedTs")?.asLong
            val syncedTimestamp = if (serverSyncedTs != null) {
                // Server provided syncedTs - use it directly (already in seconds)
                serverSyncedTs * 1000 // Convert to milliseconds
            } else {
                // Fallback: use client's ts adjusted with offset
                (ts * 1000) + serverTimeOffset
            }
            
            // ✅ NEW: Get sender/recipient IDs from envelope if available (v1.2.2)
            val fromID = envelope.get("fromID")?.asLong ?: getUserIdFromUsername(from)
            val toID = envelope.get("toID")?.asLong ?: getUserIdFromUsername(to)
            
            Log.d(TAG, "Received message from $from (ID=$fromID): ts=$ts syncedTs=$serverSyncedTs (using: ${syncedTimestamp/1000})")
            
            // Decodifica e decripta il messaggio
            val plaintext = try {
                if (keyBase64.isNotEmpty() && ivBase64.isNotEmpty()) {
                    // Decodifica da base64
                    val encryptedBytes = CryptoUtils.base64ToBytes(payloadBase64)
                    val aesKey = CryptoUtils.base64ToKey(keyBase64)
                    val iv = CryptoUtils.base64ToBytes(ivBase64)
                    
                    // Decripta
                    val decryptedBytes = CryptoUtils.decryptAES(encryptedBytes, aesKey, iv)
                    String(decryptedBytes, Charsets.UTF_8)
                } else {
                    // Fallback: assume plaintext (per compatibilità)
                    payloadBase64
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt message", e)
                "[Messaggio criptato - errore decodifica]"
            }
            
            Log.d(TAG, "Decrypted message: $plaintext")
            
            // Check if plaintext is actually a media payload JSON
            val isMediaPayload = try {
                val json = JsonParser.parseString(plaintext).asJsonObject
                json.has("mediaId") && json.has("encryptedKey") && json.has("iv")
            } catch (e: Exception) {
                false
            }
            
            if (isMediaPayload) {
                // This is actually a media message, handle it as such
                Log.d(TAG, "Detected media payload in text message, redirecting to handleMediaMessage")
                try {
                    val mediaJson = JsonParser.parseString(plaintext).asJsonObject
                    Log.d(TAG, "Media payload JSON: $mediaJson")
                    val mediaId = mediaJson.get("mediaId")?.asString ?: return
                    val encryptedKey = mediaJson.get("encryptedKey")?.asString ?: return
                    val mediaIv = mediaJson.get("iv")?.asString ?: return
                    val filename = mediaJson.get("filename")?.asString ?: "media"
                    val mimeType = mediaJson.get("mime")?.asString ?: "application/octet-stream"
                    val size = mediaJson.get("size")?.asLong ?: 0L
                    val salvable = mediaJson.get("salvable")?.asBoolean ?: true
                    val senderAutoDelete = mediaJson.get("senderAutoDelete")?.asBoolean ?: false
                    
                    Log.d(TAG, "Parsed media: salvable=$salvable, senderAutoDelete=$senderAutoDelete")
                    
                    // Determina il tipo di messaggio dal MIME type
                    val messageType = when {
                        mimeType.startsWith("image/") -> MessageType.IMAGE
                        mimeType.startsWith("video/") -> MessageType.VIDEO
                        else -> MessageType.DOCUMENT
                    }
                    
                    Log.d(TAG, "Creating media message: mediaId=$mediaId, type=$messageType, filename=$filename, salvable=$salvable, senderAutoDelete=$senderAutoDelete")
                    
                    // Crea messaggio media
                    val message = Message.create(
                        id = id,
                        senderId = fromID,
                        recipientId = toID,
                        content = mediaId,
                        timestampLong = syncedTimestamp, // ✅ v1.3.1: Use synced timestamp
                        messageType = messageType,
                        mediaId = mediaId,
                        mediaKey = encryptedKey,
                        mediaIv = mediaIv,
                        filename = filename,
                        mime = mimeType,
                        size = size,
                        mediaSalvable = salvable,
                        senderAutoDelete = senderAutoDelete
                    )
                    
                    _incomingMessages.value = message
                    Log.d(TAG, "Media message ready for UI: $messageType - $filename")
                    return
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing media payload from text message", e)
                    // Fall through to handle as regular text
                }
            }
            
            // Crea messaggio di testo normale
            val message = Message.create(
                id = id,
                senderId = fromID,
                senderUsername = from, // ✅ v1.2.5: Include sender username for conversation creation
                recipientId = toID,
                content = plaintext,
                timestampLong = syncedTimestamp, // ✅ v1.3.1: Use synced timestamp
                messageType = MessageType.TEXT,
                replyToMessageId = envelope.get("replyToMessageId")?.asString,
                replyToContent = envelope.get("replyToContent")?.asString,
                replyToSenderId = envelope.get("replyToSenderId")?.asLong
            )
            
            _incomingMessages.value = message
            Log.d(TAG, "Parsed and decrypted message from $from: $plaintext, hasReply=${message.replyToMessageId != null}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling text message", e)
        }
    }
    
    private suspend fun handleEmojiMessage(envelope: JsonObject) {
        try {
            val from = envelope.get("from")?.asString ?: return
            val to = envelope.get("to")?.asString ?: return
            val id = envelope.get("id")?.asString ?: return
            val payloadBase64 = envelope.get("payload")?.asString ?: return
            val ivBase64 = envelope.get("iv")?.asString ?: ""
            val keyBase64 = envelope.get("key")?.asString ?: ""
            val ts = envelope.get("ts")?.asLong ?: (System.currentTimeMillis() / 1000)
            
            // ✅ v1.3.1: Use server timestamp when available
            val serverTs = envelope.get("server_ts")?.asLong
            val syncedTimestamp = if (serverTs != null) {
                serverTs * 1000
            } else {
                (ts * 1000) + serverTimeOffset
            }
            
            // ✅ NEW: Get sender/recipient IDs from envelope if available (v1.2.2)
            val fromID = envelope.get("fromID")?.asLong ?: getUserIdFromUsername(from)
            val toID = envelope.get("toID")?.asLong ?: getUserIdFromUsername(to)
            
            Log.d(TAG, "Received EMOJI message from $from (ID=$fromID)")
            
            // Decripta il payload JSON (contiene {emoji: "😀", unicode: null})
            val emojiPayloadJson = try {
                if (keyBase64.isNotEmpty() && ivBase64.isNotEmpty()) {
                    val encryptedBytes = CryptoUtils.base64ToBytes(payloadBase64)
                    val aesKey = CryptoUtils.base64ToKey(keyBase64)
                    val iv = CryptoUtils.base64ToBytes(ivBase64)
                    val decryptedBytes = CryptoUtils.decryptAES(encryptedBytes, aesKey, iv)
                    String(decryptedBytes, Charsets.UTF_8)
                } else {
                    payloadBase64
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt emoji message", e)
                return
            }
            
            Log.d(TAG, "Decrypted emoji payload: $emojiPayloadJson")
            
            // Parse il JSON per estrarre l'emoji
            val emoji = try {
                val json = JsonParser.parseString(emojiPayloadJson).asJsonObject
                json.get("emoji")?.asString ?: "❓"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse emoji payload", e)
                "❓"
            }
            
            // Crea messaggio emoji
            val message = Message.create(
                id = id,
                senderId = fromID,
                senderUsername = from, // ✅ v1.2.5: Include sender username
                recipientId = toID,
                content = emoji,
                timestampLong = syncedTimestamp, // ✅ v1.3.1: Use synced timestamp
                messageType = MessageType.EMOJI
            )
            
            _incomingMessages.value = message
            Log.d(TAG, "Emoji message ready for UI: $emoji")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling emoji message", e)
        }
    }
    
    private suspend fun handleMediaMessage(envelope: JsonObject) {
        try {
            val from = envelope.get("from")?.asString ?: return
            val to = envelope.get("to")?.asString ?: return
            val id = envelope.get("id")?.asString ?: return
            val payloadBase64 = envelope.get("payload")?.asString ?: return
            val ivBase64 = envelope.get("iv")?.asString ?: ""
            val keyBase64 = envelope.get("key")?.asString ?: ""
            val ts = envelope.get("ts")?.asLong ?: (System.currentTimeMillis() / 1000)
            
            // ✅ v1.3.1: Use server timestamp when available
            val serverTs = envelope.get("server_ts")?.asLong
            val syncedTimestamp = if (serverTs != null) {
                serverTs * 1000
            } else {
                (ts * 1000) + serverTimeOffset
            }
            
            // ✅ Get sender/recipient IDs from envelope if available (v1.2.2)
            val fromID = envelope.get("fromID")?.asLong ?: getUserIdFromUsername(from)
            val toID = envelope.get("toID")?.asLong ?: getUserIdFromUsername(to)
            
            Log.d(TAG, "Received MEDIA message from $from (ID=$fromID)")
            
            // Decripta il payload JSON con i metadati del media
            val mediaPayloadJson = try {
                if (keyBase64.isNotEmpty() && ivBase64.isNotEmpty()) {
                    val encryptedBytes = CryptoUtils.base64ToBytes(payloadBase64)
                    val aesKey = CryptoUtils.base64ToKey(keyBase64)
                    val iv = CryptoUtils.base64ToBytes(ivBase64)
                    
                    val decryptedBytes = CryptoUtils.decryptAES(encryptedBytes, aesKey, iv)
                    String(decryptedBytes, Charsets.UTF_8)
                } else {
                    Log.e(TAG, "Missing encryption keys for media message")
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt media message", e)
                return
            }
            
            Log.d(TAG, "Decrypted media payload: $mediaPayloadJson")
            
            // Parsa il JSON dei metadati media
            val mediaData = try {
                JsonParser.parseString(mediaPayloadJson).asJsonObject
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse media JSON", e)
                return
            }
            
            // Estrai i campi del media
            val mediaId = mediaData.get("mediaId")?.asString ?: return
            val encryptedKey = mediaData.get("encryptedKey")?.asString ?: return
            val mediaIv = mediaData.get("iv")?.asString ?: return
            val filename = mediaData.get("filename")?.asString ?: "media"
            val mimeType = mediaData.get("mime")?.asString ?: "application/octet-stream"
            val size = mediaData.get("size")?.asLong ?: 0L
            val salvable = mediaData.get("salvable")?.asBoolean ?: true
            val thumbnail = mediaData.get("thumbnail")?.asString  // 🎯 Thumbnail base64 cache
            val caption = mediaData.get("caption")?.asString
            val senderAutoDelete = mediaData.get("senderAutoDelete")?.asBoolean ?: false
            
            // Determina il tipo di messaggio dal MIME type
            val messageType = when {
                mimeType.startsWith("image/") -> MessageType.IMAGE
                mimeType.startsWith("video/") -> MessageType.VIDEO
                else -> MessageType.DOCUMENT
            }
            
            Log.d(TAG, "Media message: mediaId=$mediaId, type=$messageType, filename=$filename, thumbnail=${thumbnail?.length ?: 0} chars")
            
            // Crea messaggio media per la UI
            val message = Message.create(
                id = id,
                senderId = fromID,
                senderUsername = from, // ✅ v1.2.5: Include sender username
                recipientId = toID,
                content = mediaId,
                timestampLong = syncedTimestamp, // ✅ v1.3.1: Use synced timestamp
                messageType = messageType,
                mediaId = mediaId,
                mediaKey = encryptedKey,
                mediaIv = mediaIv,
                filename = filename,
                mime = mimeType,
                size = size,
                thumbnail = thumbnail,  // 🎯 PASS THUMBNAIL!
                caption = caption,
                mediaSalvable = salvable,
                senderAutoDelete = senderAutoDelete
            )
            
            _incomingMessages.value = message
            Log.d(TAG, "Media message ready for UI: $messageType - $filename")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling media message", e)
        }
    }
    
    private fun handleTypingIndicator(envelope: JsonObject) {
        try {
            val from = envelope.get("from")?.asString ?: return
            val state = envelope.get("state")?.asString ?: return
            
            val fromId = from.hashCode().toLong() // Conversione temporanea
            val currentTyping = _typingUsers.value.toMutableSet()
            
            if (state == "typing") {
                currentTyping.add(fromId)
            } else {
                currentTyping.remove(fromId)
            }
            
            _typingUsers.value = currentTyping
            Log.d(TAG, "Typing indicator from $from: $state")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling typing indicator", e)
        }
    }
    
    private fun handleAck(envelope: JsonObject) {
        val id = envelope.get("id")?.asString
        val statusStr = envelope.get("status")?.asString
        val serverTs = envelope.get("server_ts")?.asLong // ✅ v1.3.1: Server timestamp in seconds
        
        Log.d(TAG, "Message ACK: id=$id status=$statusStr serverTs=$serverTs")
        
        // ✅ v1.3.1: Calculate server time offset for message synchronization
        if (serverTs != null) {
            val clientTs = System.currentTimeMillis() / 1000 // Client time in seconds
            val offset = (serverTs - clientTs) * 1000 // Offset in milliseconds
            
            // Update offset (use moving average to smooth out network jitter)
            if (lastSyncTime == 0L || Math.abs(offset - serverTimeOffset) > 1000) {
                // First sync or significant drift detected
                serverTimeOffset = offset
                lastSyncTime = System.currentTimeMillis()
                Log.i(TAG, "⏱️ Server time sync: offset=${offset}ms (server is ${if (offset > 0) "ahead" else "behind"} by ${Math.abs(offset)}ms)")
            } else {
                // Moving average: 70% old + 30% new
                serverTimeOffset = ((serverTimeOffset * 7) + (offset * 3)) / 10
                lastSyncTime = System.currentTimeMillis()
                Log.d(TAG, "⏱️ Server time updated: offset=${serverTimeOffset}ms")
            }
        }
        
        if (id == null || statusStr == null) {
            Log.w(TAG, "Invalid ACK: missing id or status")
            return
        }
        
        // Map server status to MessageStatus enum
        val messageStatus = when (statusStr.lowercase()) {
            "sent" -> MessageStatus.SENT
            "delivered" -> MessageStatus.DELIVERED
            "read" -> MessageStatus.READ
            else -> {
                Log.w(TAG, "Unknown ACK status: $statusStr")
                return
            }
        }
        
        // Update message status in database
        scope.launch {
            try {
                database.messageDao().updateMessageStatus(id, messageStatus)
                Log.d(TAG, "Message $id status updated to $messageStatus")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating message status for id=$id", e)
            }
        }
    }
    
    private fun handleError(envelope: JsonObject) {
        val code = envelope.get("code")?.asString ?: "UNKNOWN"
        val message = envelope.get("message")?.asString ?: "Unknown error"
        val messageId = envelope.get("id")?.asString
        
        Log.e(TAG, "Server error: code=$code message=$message messageId=$messageId")
        
        // If error is related to a specific message, mark it as FAILED
        if (messageId != null) {
            scope.launch {
                try {
                    database.messageDao().updateMessageStatus(messageId, MessageStatus.FAILED)
                    Log.d(TAG, "Message $messageId marked as FAILED due to server error")
                } catch (e: Exception) {
                    Log.e(TAG, "Error marking message as failed", e)
                }
            }
        }
        
        // TODO: Notify UI about the error (could use a StateFlow)
    }
    
    // ========== GROUP MESSAGES HANDLERS ==========
    
    /**
     * Handles incoming group messages
     * Envelope format: {
     *   "type": "group_msg",
     *   "group_id": "grp_xxx",
     *   "from": "username",
     *   "fromID": 123,
     *   "id": "msg_xxx",
     *   "payload": "base64_encrypted_content",
     *   "iv": "base64_iv",
     *   "key": "base64_key",
     *   "ts": 1234567890
     * }
     */
    private suspend fun handleGroupMessage(envelope: JsonObject) {
        try {
            Log.d(TAG, "handleGroupMessage called, envelope: $envelope")
            
            // Get group ID from "to" field (envelope v2)
            val groupId = envelope.get("to")?.asString
            if (groupId == null || groupId.isEmpty()) {
                Log.w(TAG, "Group message missing group ID")
                return
            }
            
            // Parse payload as GroupMessage JSON
            val payloadStr = envelope.get("payload")?.asString
            if (payloadStr == null || payloadStr.isEmpty()) {
                Log.w(TAG, "Group message missing payload")
                return
            }
            
            val groupMsgJson = try {
                JsonParser.parseString(payloadStr).asJsonObject
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse group message payload", e)
                return
            }
            
            val messageId = groupMsgJson.get("messageId")?.asString ?: return
            val senderId = groupMsgJson.get("senderId")?.asLong ?: return
            val senderUsername = groupMsgJson.get("senderUsername")?.asString ?: ""
            val content = groupMsgJson.get("content")?.asString ?: ""
            val messageType = groupMsgJson.get("messageType")?.asString ?: "TEXT"
            val timestamp = groupMsgJson.get("timestamp")?.asLong ?: System.currentTimeMillis()
            
            // ✅ v1.3.1: Use server timestamp when available
            val serverTs = envelope.get("server_ts")?.asLong
            val syncedTimestamp = if (serverTs != null) {
                serverTs * 1000
            } else {
                timestamp + serverTimeOffset
            }
            
            Log.d(TAG, "Received group message from $senderUsername (ID=$senderId) in group $groupId: $content")
            
            // Check if message already exists in DB (we might have saved it when sending)
            val existingMessage = database.messageDao().getMessageById(messageId)
            
            if (existingMessage != null) {
                // Message already exists (we sent it), just update status to DELIVERED
                Log.d(TAG, "Message already exists, updating status to DELIVERED")
                database.messageDao().updateMessageStatus(messageId, MessageStatus.DELIVERED)
                
                // Emit to incomingMessages flow so UI can scroll/update
                _incomingMessages.emit(existingMessage.copy(status = MessageStatus.DELIVERED))
                return
            }
            
            // Create Message object for group chat
            val message = Message(
                id = messageId,
                senderId = senderId,
                recipientId = 0L, // Group messages don't have single receiver
                content = content,
                timestamp = Date(syncedTimestamp), // ✅ v1.3.1: Use synced timestamp
                status = MessageStatus.DELIVERED,
                messageType = when (messageType) {
                    "TEXT" -> MessageType.TEXT
                    "IMAGE" -> MessageType.IMAGE
                    "VIDEO" -> MessageType.VIDEO
                    "AUDIO" -> MessageType.AUDIO
                    "FILE" -> MessageType.DOCUMENT
                    "DOCUMENT" -> MessageType.DOCUMENT
                    else -> MessageType.TEXT
                },
                isGroup = true,
                groupId = groupId
            )
            
            // Store in database
            database.messageDao().insertMessage(message)
            
            // Increment unread count for group (only for messages from others)
            database.groupDao().incrementUnreadCount(groupId, 1)
            
            // Show notification if app is in background or not in this group chat
            CoroutineScope(Dispatchers.IO).launch {
                showGroupMessageNotification(groupId, senderUsername, content)
            }
            
            // Notify UI
            _incomingMessages.value = message
            
            Log.d(TAG, "Group message processed and stored: $messageId from $senderUsername in $groupId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling group message", e)
        }
    }
    
    /**
     * Shows a notification for a group message
     */
    private suspend fun showGroupMessageNotification(groupId: String, senderUsername: String, content: String) {
        try {
            // Get group info
            val group = database.groupDao().getGroupById(groupId)
            if (group == null) {
                Log.w(TAG, "Group not found for notification: $groupId")
                return
            }
            
            // Create notification on main thread
            withContext(Dispatchers.Main) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                
                // Create channel if needed (Android O+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val channel = android.app.NotificationChannel(
                        "group_messages",
                        "Messaggi di Gruppo",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Notifiche per i messaggi di gruppo"
                        enableVibration(true)
                        enableLights(true)
                    }
                    notificationManager.createNotificationChannel(channel)
                }
                
                // Create intent to open group chat
                val intent = android.content.Intent(context, GroupChatActivity::class.java).apply {
                    putExtra("GROUP_ID", groupId)
                    putExtra("GROUP_NAME", group.groupName)
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                
                val pendingIntent = android.app.PendingIntent.getActivity(
                    context,
                    groupId.hashCode(),
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                
                // Build notification
                val notification = androidx.core.app.NotificationCompat.Builder(context, "group_messages")
                    .setSmallIcon(android.R.drawable.ic_dialog_info) // Use system icon as fallback
                    .setContentTitle(group.groupName)
                    .setContentText("$senderUsername: $content")
                    .setStyle(androidx.core.app.NotificationCompat.BigTextStyle()
                        .bigText("$senderUsername: $content"))
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setGroup("group_messages")
                    .build()
                
                // Show notification
                notificationManager.notify(groupId.hashCode(), notification)
                
                Log.d(TAG, "Group message notification shown for $groupId")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing group message notification", e)
        }
    }
    
    /**
     * Handles group notifications (member added/removed, role changed, settings updated)
     * Envelope format: {
     *   "type": "group_notify",
     *   "group_id": "grp_xxx",
     *   "notify_type": "MEMBER_ADDED|MEMBER_REMOVED|ROLE_CHANGED|SETTINGS_UPDATED",
     *   "data": { ... notification-specific data ... }
     * }
     */
    private suspend fun handleGroupNotification(envelope: JsonObject) {
        try {
            Log.d(TAG, "handleGroupNotification called, envelope: $envelope")
            
            // Parse payload which contains the actual notification data
            val payloadStr = envelope.get("payload")?.asString
            if (payloadStr == null) {
                Log.w(TAG, "Group notification missing payload")
                return
            }
            
            Log.d(TAG, "Payload string: $payloadStr")
            
            val payload = try {
                JsonParser.parseString(payloadStr).asJsonObject
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse group notification payload", e)
                return
            }
            
            val notifyType = payload.get("type")?.asString ?: return
            val groupId = payload.get("groupId")?.asString ?: return
            val actorId = payload.get("actorId")?.asLong
            val actorName = payload.get("actorName")?.asString
            val targetId = payload.get("targetId")?.asLong
            val data = payload.getAsJsonObject("data") ?: JsonObject()
            
            Log.d(TAG, "Received group notification: type=$notifyType group=$groupId actor=$actorName targetId=$targetId currentUserId=$userId")
            
            when (notifyType) {
                "MEMBER_ADDED" -> {
                    val targetName = data.get("targetName")?.asString
                    
                    Log.i(TAG, "Member added to group $groupId: $targetName (ID=$targetId) by $actorName")
                    
                    // Emit event for group members refresh
                    _groupEvent.emit(GroupEvent(
                        type = "MEMBER_ADDED",
                        groupId = groupId,
                        groupName = null,
                        data = mapOf(
                            "added_user_id" to (targetId ?: 0L),
                            "added_username" to (targetName ?: ""),
                            "added_by" to (actorName ?: "")
                        )
                    ))
                    
                    // If current user was added, emit special event for groups list refresh
                    if (targetId == userId) {
                        Log.i(TAG, "Current user was added to group $groupId, emitting group_added event")
                        _groupEvent.emit(GroupEvent(
                            type = "group_added",
                            groupId = groupId,
                            groupName = null
                        ))
                    } else {
                        Log.d(TAG, "Target user $targetId != current user $userId, not emitting group_added")
                    }
                }
                
                "MEMBER_REMOVED" -> {
                    val targetName = data.get("targetName")?.asString
                    
                    Log.i(TAG, "Member removed from group $groupId: $targetName (ID=$targetId) by $actorName")
                    
                    _groupEvent.emit(GroupEvent(
                        type = "MEMBER_REMOVED",
                        groupId = groupId,
                        data = mapOf(
                            "removed_user_id" to (targetId ?: 0L),
                            "removed_username" to (targetName ?: ""),
                            "removed_by" to (actorName ?: "")
                        )
                    ))
                }
                
                "ROLE_CHANGED" -> {
                    val targetName = data.get("targetName")?.asString
                    val newRole = data.get("newRole")?.asString
                    
                    Log.i(TAG, "Role changed in group $groupId: $targetName (ID=$targetId) → $newRole by $actorName")
                    
                    _groupEvent.emit(GroupEvent(
                        type = "ROLE_CHANGED",
                        groupId = groupId,
                        data = mapOf(
                            "target_user_id" to (targetId ?: 0L),
                            "target_username" to (targetName ?: ""),
                            "new_role" to (newRole ?: ""),
                            "changed_by" to (actorName ?: "")
                        )
                    ))
                }
                
                "SETTINGS_UPDATED" -> {
                    // Extract changes from data object
                    val changes = data.entrySet().associate { it.key to it.value.toString() }
                    
                    Log.i(TAG, "Settings updated in group $groupId by $actorName: changes=$changes")
                    
                    _groupEvent.emit(GroupEvent(
                        type = "SETTINGS_UPDATED",
                        groupId = groupId,
                        groupName = null,
                        data = mapOf(
                            "updated_by" to (actorName ?: ""),
                            "changes" to changes
                        )
                    ))
                }
                
                else -> {
                    Log.w(TAG, "Unknown group notification type: $notifyType")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling group notification", e)
        }
    }
    
    /**
     * Handle system notification from admin
     * Envelope format:
     * {
     *   "v": 1,
     *   "type": "system_notification",
     *   "id": 123,
     *   "title": "Titolo",
     *   "description": "Descrizione",
     *   "url": "https://...",
     *   "ts": 1234567890
     * }
     */
    private suspend fun handleSystemNotification(envelope: JsonObject) {
        try {
            val id = envelope.get("id")?.asLong ?: return
            val title = envelope.get("title")?.asString
            val description = envelope.get("description")?.asString
            val url = envelope.get("url")?.asString
            val ts = envelope.get("ts")?.asLong ?: (System.currentTimeMillis() / 1000)
            
            Log.i(TAG, "Received system notification: id=$id title='$title'")
            
            // Save to database
            val notification = it.fabiodirauso.shutappchat.model.SystemNotification(
                id = id,
                title = title,
                description = description,
                url = url,
                timestamp = ts * 1000, // Convert to milliseconds
                read = false
            )
            
            database.systemNotificationDao().insertNotification(notification)
            Log.d(TAG, "System notification saved to database")
            
            // Mostra notifica push Android
            withContext(Dispatchers.Main) {
                it.fabiodirauso.shutappchat.utils.SystemNotificationHelper.showNotification(
                    context,
                    notification
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling system notification", e)
        }
    }

    private suspend fun handleContactRequestNotification(envelope: JsonObject) {
        try {
            val payloadStr = envelope.get("payload")?.asString ?: return
            val payload = gson.fromJson(payloadStr, JsonObject::class.java)
            
            val senderName = payload.get("senderName")?.asString ?: return
            val senderId = payload.get("senderId")?.asLong ?: return
            val receiverId = payload.get("receiverId")?.asLong ?: return
            val message = payload.get("message")?.asString ?: "Vuole aggiungerti ai contatti"
            
            Log.i(TAG, "Contact request received from: $senderName (ID: $senderId) to receiver ID: $receiverId")
            
            // Salva la richiesta nel database locale
            try {
                val database = it.fabiodirauso.shutappchat.database.AppDatabase.getDatabase(context)
                val contactRequest = it.fabiodirauso.shutappchat.model.ContactRequest(
                    id = System.currentTimeMillis(), // Genera un ID temporaneo
                    fromUserId = senderId,
                    fromUsername = senderName,
                    fromNickname = null, // Non disponibile nella notifica
                    fromProfilePicture = null, // Non disponibile nella notifica
                    toUserId = receiverId,
                    status = it.fabiodirauso.shutappchat.model.ContactRequestStatus.PENDING,
                    createdAt = java.util.Date(),
                    updatedAt = null
                )
                database.contactRequestDao().insertRequest(contactRequest)
                Log.d(TAG, "Contact request saved to local database")
            } catch (dbError: Exception) {
                Log.e(TAG, "Error saving contact request to database", dbError)
            }
            
            // Mostra notifica Android
            withContext(Dispatchers.Main) {
                showContactRequestNotification(senderName, message)
            }
            
            // Broadcast per aggiornare UI se l'utente è nella schermata richieste
            val intent = android.content.Intent("it.fabiodirauso.shutappchat.CONTACT_REQUEST_RECEIVED")
            intent.putExtra("sender_name", senderName)
            intent.putExtra("sender_id", senderId)
            intent.putExtra("message", message)
            context.sendBroadcast(intent)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling contact request notification", e)
        }
    }

    private suspend fun handleContactAcceptedNotification(envelope: JsonObject) {
        try {
            val payloadStr = envelope.get("payload")?.asString ?: return
            val payload = gson.fromJson(payloadStr, JsonObject::class.java)
            
            val receiverName = payload.get("receiverName")?.asString ?: return
            val receiverId = payload.get("receiverId")?.asLong ?: return
            
            Log.i(TAG, "Contact request accepted by: $receiverName (ID: $receiverId)")
            
            // Mostra notifica Android
            withContext(Dispatchers.Main) {
                showContactAcceptedNotification(receiverName)
            }
            
            // Broadcast per aggiornare UI
            val intent = android.content.Intent("it.fabiodirauso.shutappchat.CONTACT_REQUEST_ACCEPTED")
            intent.putExtra("receiver_name", receiverName)
            intent.putExtra("receiver_id", receiverId)
            context.sendBroadcast(intent)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling contact accepted notification", e)
        }
    }

    private fun showContactRequestNotification(senderName: String, message: String) {
        val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        
        // Intent per aprire le richieste di contatto
        val intent = android.content.Intent(context, it.fabiodirauso.shutappchat.ContactRequestsActivity::class.java)
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = androidx.core.app.NotificationCompat.Builder(context, "messages_channel")
            .setSmallIcon(it.fabiodirauso.shutappchat.R.mipmap.ic_launcher)
            .setContentTitle("Nuova richiesta di amicizia")
            .setContentText("$senderName: $message")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        notificationManager.notify(("contact_request_$senderName").hashCode(), notification)
    }

    private fun showContactAcceptedNotification(receiverName: String) {
        val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        
        // Intent per aprire i contatti
        val intent = android.content.Intent(context, it.fabiodirauso.shutappchat.MyContactsActivity::class.java)
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = androidx.core.app.NotificationCompat.Builder(context, "messages_channel")
            .setSmallIcon(it.fabiodirauso.shutappchat.R.mipmap.ic_launcher)
            .setContentTitle("Richiesta accettata!")
            .setContentText("$receiverName ha accettato la tua richiesta di amicizia")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        
        notificationManager.notify(("contact_accepted_$receiverName").hashCode(), notification)
    }

    /**
     * Gestisce il messaggio di aggiornamento forzato
     * Salva nel database locale per mostrare il dialog all'apertura dell'app
     */
    private suspend fun handleForceUpdate(envelope: JsonObject) {
        try {
            val payloadStr = envelope.get("payload")?.asString ?: return
            val payload = gson.fromJson(payloadStr, JsonObject::class.java)
            
            val version = payload.get("version")?.asString ?: "unknown"
            val message = payload.get("message")?.asString ?: "È richiesto un aggiornamento critico dell'applicazione."
            val downloadUrl = payload.get("download_url")?.asString ?: "https://shutappchat.fabiodirauso.it/api/uploads/apk/shutappchat-latest.apk"
            
            Log.i(TAG, "Force update required: version=$version")
            
            // Salva nel database locale
            database.forceUpdateDao().insertForceUpdate(
                it.fabiodirauso.shutappchat.model.ForceUpdateEntity(
                    version = version,
                    message = message,
                    downloadUrl = downloadUrl,
                    receivedAt = System.currentTimeMillis(),
                    isDownloading = false,
                    isInstalling = false,
                    downloadProgress = 0
                )
            )
            
            // Mostra il dialog su UI thread
            withContext(Dispatchers.Main) {
                showForceUpdateDialog(version, message, downloadUrl)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling force update", e)
        }
    }

    /**
     * Mostra il dialog di aggiornamento forzato
     * Questo dialog non può essere chiuso finché l'utente non aggiorna
     */
    private fun showForceUpdateDialog(version: String, message: String, downloadUrl: String) {
        try {
            // Trova l'activity corrente in foreground
            val activity = (context as? android.app.Application)?.let { app ->
                // Ottieni l'activity corrente tramite ActivityLifecycleCallbacks
                // Per ora usiamo un Intent per aprire una activity dedicata
                val intent = android.content.Intent(context, it.fabiodirauso.shutappchat.HomepageActivity::class.java)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                intent.putExtra("show_force_update", true)
                intent.putExtra("update_version", version)
                intent.putExtra("update_message", message)
                intent.putExtra("update_url", downloadUrl)
                context.startActivity(intent)
            } ?: run {
                // Fallback: mostra notifica critica
                showForceUpdateNotification(version, message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing force update dialog", e)
            showForceUpdateNotification(version, message)
        }
    }

    /**
     * Mostra una notifica critica per l'aggiornamento forzato
     * Come fallback se non si può mostrare il dialog
     */
    private fun showForceUpdateNotification(version: String, message: String) {
        val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        
        val intent = android.content.Intent(context, it.fabiodirauso.shutappchat.HomepageActivity::class.java)
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        intent.putExtra("show_force_update", true)
        
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            999,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = androidx.core.app.NotificationCompat.Builder(context, "messages_channel")
            .setSmallIcon(it.fabiodirauso.shutappchat.R.mipmap.ic_launcher)
            .setContentTitle("⚠️ Aggiornamento Obbligatorio")
            .setContentText("Versione $version richiesta: $message")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(false) // Non può essere cancellata
            .setOngoing(true) // Persistente
            .setContentIntent(pendingIntent)
            .build()
        
        notificationManager.notify(999, notification)
    }
}

