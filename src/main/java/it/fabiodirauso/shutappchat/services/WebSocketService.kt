package it.fabiodirauso.shutappchat.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import it.fabiodirauso.shutappchat.websocket.WebSocketClient
import it.fabiodirauso.shutappchat.config.ServerConfig
import it.fabiodirauso.shutappchat.config.AppConfigManager
import it.fabiodirauso.shutappchat.HomepageActivity
import it.fabiodirauso.shutappchat.model.Message
import it.fabiodirauso.shutappchat.database.AppDatabase
import it.fabiodirauso.shutappchat.R
import it.fabiodirauso.shutappchat.network.RetrofitClient
import it.fabiodirauso.shutappchat.utils.CryptoUtils
import it.fabiodirauso.shutappchat.utils.MessageFormatter
import kotlinx.coroutines.delay
import android.util.Base64
import it.fabiodirauso.shutappchat.model.MessageType
import kotlinx.coroutines.flow.first

class WebSocketService : Service() {
    
    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        private const val TAG = "WebSocketService"
        private const val RETRY_DELAY_BASE = 1000L // 1 second
        private const val MAX_RETRY_DELAY = 60000L // 1 minute
        private const val MAX_RETRY_ATTEMPTS = 10
    }
    
    private val binder = WebSocketBinder()
    private val CHANNEL_ID = "WebSocketServiceChannel"
    private val MESSAGE_CHANNEL_ID = "MessageNotificationChannel"
    private val NOTIFICATION_ID = 1
    
    // WebSocket client and connection management
    private var webSocketClient: WebSocketClient? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var retryAttempt = 0
    private var reconnectJob: Job? = null
    private var pendingPollerJob: Job? = null
    
    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    // User credentials
    private var token: String? = null
    private var userId: Long = 0
    private var username: String? = null
    
    // Database
    private lateinit var database: AppDatabase
    
    enum class ConnectionState {
        CONNECTING, CONNECTED, DISCONNECTED, ERROR, RECONNECTING
    }
    
    inner class WebSocketBinder : Binder() {
        fun getService(): WebSocketService = this@WebSocketService
    }
    
    /**
     * Get WebSocketClient instance for direct access
     */
    fun getWebSocketClient(): WebSocketClient? = webSocketClient
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        database = AppDatabase.getDatabase(applicationContext)
        createNotificationChannels()
        loadUserCredentials()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started with action: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createServiceNotification())
                startWebSocketConnection()
            }
            ACTION_STOP -> {
                stopWebSocketConnection()
                stopSelf()
            }
        }
        
        return START_STICKY // Restart service if killed
    }
    
    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "Service bound")
        return binder
    }
    
    private fun loadUserCredentials() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        token = prefs.getString("user_token", null)
        userId = prefs.getLong("user_id", 0)
        username = prefs.getString("username", null)
        
        Log.d(TAG, "Loaded credentials - User: $username, ID: $userId, Token: ${token?.take(10)}...")
        
        // Fallback: se user_token è null, prova a recuperare da auth_token
        if (token == null) {
            token = prefs.getString("auth_token", null)
            Log.d(TAG, "Fallback to auth_token: ${token?.take(10)}...")
        }
    }
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            // Service notification channel (low priority)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Chat Connection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps chat connection active in background"
                setShowBadge(false)
            }
            
            // Message notification channel (high priority)
            val messageChannel = NotificationChannel(
                MESSAGE_CHANNEL_ID,
                "Chat Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "New chat messages"
                enableLights(true)
                enableVibration(true)
            }
            
            notificationManager?.createNotificationChannel(serviceChannel)
            notificationManager?.createNotificationChannel(messageChannel)
        }
    }
    
    private fun createServiceNotification(): Notification {
        val notificationIntent = Intent(this, HomepageActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ShutApp Chat")
            .setContentText(getConnectionStatusText())
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
    
    private fun getConnectionStatusText(): String {
        return when (_connectionState.value) {
            ConnectionState.CONNECTED -> "Connected - Ready to receive messages"
            ConnectionState.CONNECTING -> "Connecting to chat server..."
            ConnectionState.RECONNECTING -> "Reconnecting... (attempt ${retryAttempt + 1})"
            ConnectionState.ERROR -> "Connection error - Retrying..."
            ConnectionState.DISCONNECTED -> "Disconnected"
        }
    }
    
    private fun startWebSocketConnection() {
        if (token.isNullOrEmpty() || username.isNullOrEmpty()) {
            Log.w(TAG, "Cannot start WebSocket - missing credentials. Token: ${token?.take(10)}, Username: $username")
            return
        }
        
        Log.d(TAG, "Starting WebSocket connection...")
        _connectionState.value = ConnectionState.CONNECTING
        
        try {
            // CRITICAL FIX: Riutilizza istanza esistente o fai cleanup prima di creare nuova
            if (webSocketClient != null) {
                Log.d(TAG, "WebSocketClient already exists, reusing instance and calling connect()")
                webSocketClient?.connect()
                retryAttempt = 0
                return
            }
            
            // Usa la configurazione dinamica se disponibile, altrimenti fallback
            val configManager = AppConfigManager.getInstance(this)
            val wsUrl = configManager.wsUrl
            
            // Get User-Agent
            val userAgent = it.fabiodirauso.shutappchat.utils.AppConstants.getUserAgent(this)
            
            Log.d(TAG, "Creating NEW WebSocketClient instance")
            Log.d(TAG, "Using WebSocket URL: $wsUrl")
            Log.d(TAG, "Using User-Agent: $userAgent")
            
            webSocketClient = WebSocketClient(
                context = this,
                baseUrl = wsUrl,
                token = token!!,
                userId = userId,
                username = username!!,
                userAgent = userAgent
            )
            
            // Monitor connection state changes
            serviceScope.launch {
                webSocketClient?.connectionState?.collect { state ->
                    handleWebSocketStateChange(state)
                }
            }
            
            // Monitor incoming messages
            serviceScope.launch {
                webSocketClient?.incomingMessages?.collect { message ->
                    message?.let { handleIncomingMessage(it) }
                }
            }
            
            // Monitor session invalid events
            serviceScope.launch {
                webSocketClient?.sessionInvalidEvent?.collect { reason ->
                    reason?.let {
                        Log.e(TAG, "Session invalid detected: $it")
                        handleSessionInvalid(it)
                        webSocketClient?.resetSessionInvalidEvent()
                    }
                }
            }
            
            // Monitor group events
            serviceScope.launch {
                webSocketClient?.groupEvent?.collect { event ->
                    Log.d(TAG, "Group event received: type=${event.type}, groupId=${event.groupId}")
                    handleGroupEvent(event)
                }
            }
            
            webSocketClient?.connect()
            retryAttempt = 0 // Reset retry counter on successful start
            
            // Start polling for pending messages
            startPendingMessagesPoller()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting WebSocket connection", e)
            _connectionState.value = ConnectionState.ERROR
            scheduleReconnect()
        }
    }
    
    private suspend fun handleWebSocketStateChange(state: WebSocketClient.ConnectionState) {
        val serviceState = when (state) {
            WebSocketClient.ConnectionState.CONNECTING -> ConnectionState.CONNECTING
            WebSocketClient.ConnectionState.CONNECTED -> {
                retryAttempt = 0 // Reset on successful connection
                reconnectJob?.cancel() // CRITICAL FIX: Cancella reconnect job quando connesso
                Log.d(TAG, "WebSocket CONNECTED, cancelled any pending reconnect attempts")
                ConnectionState.CONNECTED
            }
            WebSocketClient.ConnectionState.DISCONNECTED -> {
                // Solo reconnect se non stiamo già riconnettendo
                if (_connectionState.value != ConnectionState.RECONNECTING) {
                    scheduleReconnect()
                }
                ConnectionState.DISCONNECTED
            }
            WebSocketClient.ConnectionState.ERROR -> {
                scheduleReconnect()
                ConnectionState.ERROR
            }
        }
        
        _connectionState.value = serviceState
        updateNotification()
    }
    
    private fun scheduleReconnect() {
        // CRITICAL FIX: Non schedulare se già connesso
        if (webSocketClient?.connectionState?.value == WebSocketClient.ConnectionState.CONNECTED) {
            Log.d(TAG, "Already connected, skipping reconnect schedule")
            return
        }
        
        if (retryAttempt >= MAX_RETRY_ATTEMPTS) {
            Log.w(TAG, "Max retry attempts reached, stopping reconnection")
            return
        }
        
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            val delay = calculateRetryDelay(retryAttempt)
            retryAttempt++
            
            Log.d(TAG, "Scheduling reconnect attempt $retryAttempt in ${delay}ms")
            _connectionState.value = ConnectionState.RECONNECTING
            updateNotification()
            
            delay(delay)
            
            if (isActive) {
                Log.d(TAG, "Attempting reconnection...")
                startWebSocketConnection()
            }
        }
    }
    
    private fun calculateRetryDelay(attempt: Int): Long {
        // Exponential backoff with jitter
        val baseDelay = RETRY_DELAY_BASE * (1 shl attempt.coerceAtMost(6)) // Cap at 2^6
        val jitter = (0..1000).random() // Add random jitter
        return (baseDelay + jitter).coerceAtMost(MAX_RETRY_DELAY)
    }
    
    /**
     * Starts periodic polling of pending messages from the server
     * This runs every 10 seconds to check for new messages that might have been missed
     */
    private fun startPendingMessagesPoller() {
        pendingPollerJob?.cancel()
        pendingPollerJob = serviceScope.launch {
            while (isActive) {
                try {
                    delay(10000) // Poll every 10 seconds
                    if (_connectionState.value == ConnectionState.CONNECTED) {
                        fetchAndProcessPendingMessages()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in pending messages poller", e)
                }
            }
        }
        Log.d(TAG, "Pending messages poller started")
    }
    
    /**
     * Fetches pending messages from the API and processes them
     */
    private suspend fun fetchAndProcessPendingMessages() {
        try {
            val response = RetrofitClient.apiService.getPendingMessages()
            if (response.isSuccessful) {
                val body = response.body()
                val messages = body?.get("messages") as? List<Map<String, Any>> ?: emptyList()
                
                if (messages.isNotEmpty()) {
                    Log.d(TAG, "Fetched ${messages.size} pending message(s)")
                    
                    for (msgData in messages) {
                        try {
                            val id = (msgData["id"] as? Number)?.toInt() ?: 0
                            val sender = msgData["sender"] as? String ?: ""
                            val encryptedPayload = msgData["message"] as? String ?: ""
                            val aesKeyB64 = msgData["key"] as? String ?: ""
                            val ivB64 = msgData["iv"] as? String ?: ""
                            
                            // Extract reply fields (v1.2.0)
                            val replyToMessageId = msgData["replyToMessageId"] as? String
                            val replyToContent = msgData["replyToContent"] as? String
                            val replyToSenderId = (msgData["replyToSenderId"] as? Number)?.toLong()
                            
                            Log.d(TAG, "Processing message: id=$id, sender=$sender, hasReply=${replyToMessageId != null}")
                            if (replyToMessageId != null) {
                                Log.d(TAG, "Reply fields: messageId=$replyToMessageId, content=$replyToContent, senderId=$replyToSenderId")
                            }
                            
                            // Decode Base64 to bytes
                            val encryptedBytes = Base64.decode(encryptedPayload, Base64.DEFAULT)
                            val ivBytes = Base64.decode(ivB64, Base64.DEFAULT)
                            
                            // Decrypt the message
                            val aesKey = CryptoUtils.base64ToKey(aesKeyB64)
                            val decryptedBytes = CryptoUtils.decryptAES(encryptedBytes, aesKey, ivBytes)
                            val decryptedContent = String(decryptedBytes, Charsets.UTF_8)
                            
                            // Get sender ID
                            val senderUser = RetrofitClient.apiService.getUser(sender).body()
                            val senderId = (senderUser?.get("id") as? Number)?.toLong() ?: 0L
                            
                            if (senderId > 0) {
                                // Generate conversation ID (same logic as ChatActivity)
                                val conversationId = generateConversationId(senderId, userId)
                                
                                Log.d(TAG, "Saving message with conversationId: $conversationId (sender=$senderId, recipient=$userId)")
                                
                                // Check if decryptedContent is a media payload JSON
                                val isMediaPayload = try {
                                    val json = com.google.gson.JsonParser.parseString(decryptedContent).asJsonObject
                                    json.has("mediaId") && json.has("encryptedKey") && json.has("iv")
                                } catch (e: Exception) {
                                    false
                                }
                                
                                // Check if decryptedContent is an emoji payload JSON
                                val isEmojiPayload = try {
                                    val json = com.google.gson.JsonParser.parseString(decryptedContent).asJsonObject
                                    json.has("emoji")
                                } catch (e: Exception) {
                                    false
                                }
                                
                                val message = if (isEmojiPayload) {
                                    // Parse emoji payload
                                    Log.d(TAG, "Detected emoji payload in pending message")
                                    try {
                                        val emojiJson = com.google.gson.JsonParser.parseString(decryptedContent).asJsonObject
                                        val emoji = emojiJson.get("emoji")?.asString ?: "❓"
                                        
                                        Log.d(TAG, "Creating emoji message: $emoji")
                                        
                                        // Create emoji message
                                        Message.create(
                                            id = System.currentTimeMillis().toString(),
                                            senderId = senderId,
                                            recipientId = userId,
                                            content = emoji,
                                            messageType = MessageType.EMOJI,
                                            timestampLong = System.currentTimeMillis(),
                                            conversationId = conversationId,
                                            replyToMessageId = replyToMessageId,
                                            replyToContent = replyToContent,
                                            replyToSenderId = replyToSenderId
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error parsing emoji payload, treating as text", e)
                                        // Fallback to text message
                                        Message.create(
                                            id = System.currentTimeMillis().toString(),
                                            senderId = senderId,
                                            recipientId = userId,
                                            content = decryptedContent,
                                            messageType = MessageType.TEXT,
                                            timestampLong = System.currentTimeMillis(),
                                            conversationId = conversationId,
                                            replyToMessageId = replyToMessageId,
                                            replyToContent = replyToContent,
                                            replyToSenderId = replyToSenderId
                                        )
                                    }
                                } else if (isMediaPayload) {
                                    // Parse media payload
                                    Log.d(TAG, "Detected media payload in pending message")
                                    try {
                                        val mediaJson = com.google.gson.JsonParser.parseString(decryptedContent).asJsonObject
                                        
                                        // Parse mediaId - handle both string and number formats
                                        val mediaIdElement = mediaJson.get("mediaId")
                                        val mediaId = when {
                                            mediaIdElement?.isJsonPrimitive == true && mediaIdElement.asJsonPrimitive.isNumber -> {
                                                mediaIdElement.asJsonPrimitive.asInt.toString()
                                            }
                                            else -> mediaIdElement?.asString ?: ""
                                        }
                                        
                                        val encryptedKey = mediaJson.get("encryptedKey")?.asString ?: ""
                                        val mediaIv = mediaJson.get("iv")?.asString ?: ""
                                        val filename = mediaJson.get("filename")?.asString ?: "media"
                                        val mimeType = mediaJson.get("mime")?.asString ?: "application/octet-stream"
                                        val size = mediaJson.get("size")?.asLong ?: 0L
                                        val salvable = mediaJson.get("salvable")?.asBoolean ?: true
                                        val senderAutoDelete = mediaJson.get("senderAutoDelete")?.asBoolean ?: false
                                        val caption = mediaJson.get("caption")?.asString
                                        
                                        // Determine message type from MIME type
                                        val messageType = when {
                                            mimeType.startsWith("image/") -> MessageType.IMAGE
                                            mimeType.startsWith("video/") -> MessageType.VIDEO
                                            else -> MessageType.DOCUMENT
                                        }
                                        
                                        Log.d(TAG, "Creating media message: mediaId=$mediaId, type=$messageType, mime=$mimeType, senderAutoDelete=$senderAutoDelete, caption=$caption")
                                        
                                        // Create media message
                                        Message.create(
                                            id = System.currentTimeMillis().toString(),
                                            senderId = senderId,
                                            recipientId = userId,
                                            content = mediaId,
                                            messageType = messageType,
                                            timestampLong = System.currentTimeMillis(),
                                            conversationId = conversationId,
                                            mediaId = mediaId,
                                            mediaKey = encryptedKey,
                                            mediaIv = mediaIv,
                                            filename = filename,
                                            mime = mimeType,
                                            size = size,
                                            mediaSalvable = salvable,
                                            senderAutoDelete = senderAutoDelete,
                                            caption = caption,
                                            replyToMessageId = replyToMessageId,
                                            replyToContent = replyToContent,
                                            replyToSenderId = replyToSenderId
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error parsing media payload, treating as text", e)
                                        // Fallback to text message
                                        Message.create(
                                            id = System.currentTimeMillis().toString(),
                                            senderId = senderId,
                                            recipientId = userId,
                                            content = decryptedContent,
                                            messageType = MessageType.TEXT,
                                            timestampLong = System.currentTimeMillis(),
                                            conversationId = conversationId,
                                            replyToMessageId = replyToMessageId,
                                            replyToContent = replyToContent,
                                            replyToSenderId = replyToSenderId
                                        )
                                    }
                                } else {
                                    // Create text message
                                    Message.create(
                                        id = System.currentTimeMillis().toString(),
                                        senderId = senderId,
                                        recipientId = userId,
                                        content = decryptedContent,
                                        messageType = MessageType.TEXT,
                                        timestampLong = System.currentTimeMillis(),
                                        conversationId = conversationId,
                                        replyToMessageId = replyToMessageId,
                                        replyToContent = replyToContent,
                                        replyToSenderId = replyToSenderId
                                    )
                                }
                                
                                // Save to database
                                database.messageDao().insertMessage(message)
                                Log.d(TAG, "Message inserted into database - ID: ${message.id}, conversationId: ${message.conversationId}")
                                Log.d(TAG, "Processed pending message from $sender: $decryptedContent")
                                
                                // Update conversation with unread count and last message
                                updateConversationUnreadCount(conversationId, sender, senderId, message)
                                
                                // Get the conversation to retrieve the display name (nickname)
                                val conversation = database.conversationDao().getConversationById(conversationId)
                                val senderDisplayName = conversation?.participantName ?: sender
                                
                                // Always show notification - let the app cancel it if chat is open
                                showMessageNotification(message, senderDisplayName)
                                
                                // Delete the pending message from server
                                try {
                                    RetrofitClient.apiService.deletePendingMessage(id)
                                    Log.d(TAG, "Deleted pending message $id from server")
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to delete pending message $id", e)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing individual pending message", e)
                        }
                    }
                }
            } else {
                Log.w(TAG, "Failed to fetch pending messages: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching pending messages", e)
        }
    }
    
    private suspend fun handleIncomingMessage(message: Message) {
        Log.d(TAG, "Received message: ${message.content.take(100)}... (type=${message.messageType})")
        
        // Skip group messages (they're handled separately)
        if (message.isGroup) {
            Log.d(TAG, "Skipping group message in handleIncomingMessage (handled separately)")
            return
        }
        
        // ✅ Get sender username for notification (before try-catch to be accessible outside)
        var senderUsername = message.senderUsername ?: "user_${message.senderId}"
        
        // Save message to database
        try {
            val database = AppDatabase.getDatabase(this@WebSocketService)
            
            // Generate conversationId based on sender and receiver IDs
            val conversationId = generateConversationId(message.senderId, message.recipientId)
            val messageWithConversation = message.copy(conversationId = conversationId)
            
            Log.d(TAG, "Saving message to DB: conversationId=$conversationId, type=${message.messageType}, mediaId=${message.mediaId}")
            database.messageDao().insertMessage(messageWithConversation)
            Log.d(TAG, "Message saved to database successfully")
            
            // ✅ CRITICAL FIX: Update or create conversation
            // Get sender username from message (provided by WebSocketClient) or existing conversation
            val existingConversation = database.conversationDao().getConversationById(conversationId)
            // ✅ v1.2.5: Preferisci username dal messaggio, poi dalla conversazione esistente, infine fallback
            senderUsername = message.senderUsername 
                ?: existingConversation?.participantUsername 
                ?: "user_${message.senderId}"
            updateConversationUnreadCount(conversationId, senderUsername, message.senderId, message)
            Log.d(TAG, "Conversation updated/created: $conversationId with username=$senderUsername")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving message to database", e)
        }
        
        // Show notification if app is not visible
        if (!isAppInForeground()) {
            // ✅ Pass sender username to notification
            showMessageNotification(message, senderUsername)
        }
    }
    
    private fun isAppInForeground(): Boolean {
        // Simple check - in a real app you might want a more sophisticated method
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val runningProcesses = activityManager.runningAppProcesses
        return runningProcesses?.any { 
            it.processName == packageName && 
            it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND 
        } ?: false
    }
    
    private fun showMessageNotification(message: Message, senderName: String = "User ${message.senderId}") {
        serviceScope.launch {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            // Get total unread count for badge - use first() to get single value from Flow
            val totalUnread = try {
                database.messageDao().getTotalUnreadCount(userId).first()
            } catch (e: Exception) {
                1 // Default to 1 if we can't get the count
            }
            
            // Create intent to open ChatActivity with this specific conversation
            val notificationIntent = Intent(this@WebSocketService, it.fabiodirauso.shutappchat.ChatActivity::class.java).apply {
                putExtra("contact_id", message.senderId)
                putExtra("contact_name", senderName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            
            val pendingIntent = PendingIntent.getActivity(
                this@WebSocketService, 
                message.senderId.toInt(), // Use senderId as request code to create unique pending intents
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // ✅ v1.2.4: Use MessageFormatter per formattare il testo della notifica
            val notificationText = MessageFormatter.getNotificationText(this@WebSocketService, message)
            
            val notification = NotificationCompat.Builder(this@WebSocketService, MESSAGE_CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_new_message_title, senderName))
                .setContentText(notificationText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setNumber(totalUnread) // This sets the badge count
                .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
                .build()
            
            notificationManager?.notify(message.senderId.toInt(), notification)
            Log.d(TAG, "Notification shown for message from $senderName (ID: ${message.senderId}), total unread: $totalUnread")
        }
    }
    
    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, createServiceNotification())
    }
    
    private fun stopWebSocketConnection() {
        Log.d(TAG, "Stopping WebSocket connection...")
        reconnectJob?.cancel()
        pendingPollerJob?.cancel()
        webSocketClient?.cleanup() // CRITICAL FIX: Chiama cleanup() invece di disconnect() per liberare network callbacks
        webSocketClient = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }
    
    fun sendMessage(recipientId: Long, content: String) {
        serviceScope.launch {
            webSocketClient?.sendMessage(recipientId, content)
        }
    }
    
    fun sendTypingIndicator(recipientId: Long, isTyping: Boolean) {
        serviceScope.launch {
            webSocketClient?.sendTypingIndicator(recipientId, isTyping)
        }
    }
    
    /**
     * Generates a unique conversation ID based on two user IDs
     * Uses the same logic as ChatActivity to ensure consistency
     */
    private fun generateConversationId(userId1: Long, userId2: Long): String {
        val sortedIds = listOf(userId1, userId2).sorted()
        return "conv_${sortedIds[0]}_${sortedIds[1]}"
    }
    
    /**
     * Updates the conversation unread count
     */
    private suspend fun updateConversationUnreadCount(conversationId: String, senderUsername: String, senderId: Long, message: Message) {
        try {
            val conversation = database.conversationDao().getConversationById(conversationId)
            // ✅ v1.2.4: Use MessageFormatter per formattare il preview
            val messagePreview = MessageFormatter.getLastMessagePreview(this@WebSocketService, message)
            
            if (conversation != null) {
                // Update conversation with new message info and increment unread count
                database.conversationDao().updateConversation(
                    conversation.copy(
                        lastMessage = messagePreview,
                        lastMessageTime = java.util.Date(),
                        unreadCount = conversation.unreadCount + 1
                    )
                )
                Log.d(TAG, "Updated conversation $conversationId - unread: ${conversation.unreadCount + 1}, lastMsg: $messagePreview")
            } else {
                // Fetch user profile to get nickname and profile picture
                var nickname = senderUsername
                var profilePictureId: String? = null
                
                Log.d(TAG, "Fetching profile for username: $senderUsername")
                try {
                    val configManager = it.fabiodirauso.shutappchat.config.AppConfigManager.getInstance(applicationContext)
                    val apiService = it.fabiodirauso.shutappchat.network.RetrofitClient.getApiService(configManager)
                    
                    // Use getUser which returns Map directly instead of getUserProfile
                    val response = apiService.getUser(senderUsername)
                    Log.d(TAG, "User API response: success=${response.isSuccessful}, code=${response.code()}")
                    if (response.isSuccessful) {
                        val userMap = response.body()
                        Log.d(TAG, "User data: $userMap")
                        if (userMap != null) {
                            val fetchedNickname = userMap["nickname"] as? String
                            nickname = fetchedNickname ?: senderUsername
                            profilePictureId = userMap["profile_picture"] as? String
                            Log.d(TAG, "Parsed user info - username=$senderUsername, fetchedNickname=$fetchedNickname, finalNickname=$nickname, profilePicId=$profilePictureId")
                        } else {
                            Log.w(TAG, "User API returned null body")
                        }
                    } else {
                        Log.w(TAG, "User API HTTP error: ${response.code()} - ${response.message()}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch user info for $senderUsername: ${e.message}", e)
                }
                
                // Create new conversation
                // ✅ v1.2.4: Use MessageFormatter per il preview
                val messagePreview = MessageFormatter.getLastMessagePreview(this@WebSocketService, message)
                val newConversation = it.fabiodirauso.shutappchat.model.ConversationEntity(
                    id = conversationId,
                    participantId = senderId.toString(),
                    participantName = nickname,
                    participantUsername = senderUsername, // ✅ v1.2.5: Salva username per invio messaggi
                    profilePictureId = profilePictureId,
                    lastMessage = messagePreview,
                    lastMessageTime = java.util.Date(),
                    unreadCount = 1,
                    isGroup = false
                )
                database.conversationDao().insertConversation(newConversation)
                Log.d(TAG, "Created new conversation $conversationId: participantName=$nickname, participantUsername=$senderUsername (from username=$senderUsername), unread=1, lastMsg=$messagePreview")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating conversation unread count", e)
        }
    }
    
    /**
     * Handle group events from WebSocket
     */
    private fun handleGroupEvent(event: it.fabiodirauso.shutappchat.websocket.WebSocketClient.GroupEvent) {
        when (event.type) {
            "group_added" -> {
                Log.i(TAG, "Current user was added to group: ${event.groupName} (${event.groupId})")
                
                // Broadcast event to refresh groups list
                val intent = Intent("it.fabiodirauso.shutappchat.GROUP_ADDED")
                intent.putExtra("group_id", event.groupId)
                intent.putExtra("group_name", event.groupName ?: "")
                sendBroadcast(intent)
                
                // Show notification
                showGroupAddedNotification(event.groupId, event.groupName ?: "Nuovo Gruppo")
            }
            
            "MEMBER_ADDED", "MEMBER_REMOVED", "ROLE_CHANGED", "SETTINGS_UPDATED" -> {
                // Broadcast event for group details refresh
                val intent = Intent("it.fabiodirauso.shutappchat.GROUP_UPDATED")
                intent.putExtra("group_id", event.groupId)
                intent.putExtra("event_type", event.type)
                sendBroadcast(intent)
            }
        }
    }
    
    /**
     * Show notification when user is added to a group
     */
    private fun showGroupAddedNotification(groupId: String, groupName: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        val notificationIntent = Intent(this, it.fabiodirauso.shutappchat.HomepageActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_groups", true)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            groupId.hashCode(),
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setContentTitle("Aggiunto a un gruppo")
            .setContentText("Sei stato aggiunto al gruppo: $groupName")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        
        notificationManager?.notify(groupId.hashCode(), notification)
        Log.d(TAG, "Group added notification shown for: $groupName")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        stopWebSocketConnection()
        serviceScope.cancel()
    }
    
    /**
     * Handle session invalid event - broadcast to activities
     */
    private fun handleSessionInvalid(reason: WebSocketClient.SessionInvalidReason) {
        Log.w(TAG, "Broadcasting session invalid event: $reason")
        
        // Send broadcast to notify activities
        val intent = Intent("it.fabiodirauso.shutappchat.SESSION_INVALID")
        intent.putExtra("reason", reason.name)
        sendBroadcast(intent)
        
        // Stop WebSocket connection
        stopWebSocketConnection()
    }
}
