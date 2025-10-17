package it.fabiodirauso.shutappchat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import it.fabiodirauso.shutappchat.adapter.MessageAdapter
import it.fabiodirauso.shutappchat.database.AppDatabase
import it.fabiodirauso.shutappchat.databinding.ActivityGroupChatBinding
import it.fabiodirauso.shutappchat.managers.GroupRepository
import it.fabiodirauso.shutappchat.model.GroupEntity
import it.fabiodirauso.shutappchat.model.GroupMode
import it.fabiodirauso.shutappchat.model.Message
import it.fabiodirauso.shutappchat.model.MessageStatus
import it.fabiodirauso.shutappchat.model.MessageType
import it.fabiodirauso.shutappchat.services.WebSocketService
import it.fabiodirauso.shutappchat.utils.AvatarHelper
import it.fabiodirauso.shutappchat.websocket.WebSocketClient
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Activity per la chat di gruppo
 * Supporta messaggi broadcast, indicatori membri, permessi OPEN/RESTRICTED
 */
class GroupChatActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityGroupChatBinding
    private lateinit var groupRepository: GroupRepository
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var database: AppDatabase
    
    // WebSocket service binding
    private var webSocketService: WebSocketService? = null
    private var serviceBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WebSocketService.WebSocketBinder
            webSocketService = binder.getService()
            serviceBound = true
            
            // Now we can observe WebSocket events
            observeWebSocketEvents()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            webSocketService = null
            serviceBound = false
        }
    }
    
    private var groupId: String = ""
    private var group: GroupEntity? = null
    private var currentUserId: Long = 0
    private var isUserAdmin: Boolean = false
    
    // Custom toolbar views
    private lateinit var toolbarGroupAvatar: ImageView
    private lateinit var toolbarGroupName: TextView
    private lateinit var toolbarMemberCount: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityGroupChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Enable fullscreen immersive mode
        it.fabiodirauso.shutappchat.utils.UIHelper.enableImmersiveMode(this)
        
        // Get group ID from intent
        groupId = intent.getStringExtra(EXTRA_GROUP_ID) ?: run {
            Toast.makeText(this, "Errore: ID gruppo mancante", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // Initialize
        sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        database = AppDatabase.getDatabase(this)
        groupRepository = GroupRepository(this)
        currentUserId = sharedPreferences.getLong("user_id", 0)
        
        // Bind to WebSocket service
        Intent(this, WebSocketService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        
        setupToolbar()
        setupRecyclerView()
        setupInputArea()
        
        // Load group info and messages
        loadGroupInfo()
        loadMessages()
        observeIncomingMessages()
        
        // Reset unread count when opening chat
        resetUnreadCount()
    }
    
    override fun onResume() {
        super.onResume()
        // Reset unread count when returning to chat
        resetUnreadCount()
    }
    
    private fun resetUnreadCount() {
        lifecycleScope.launch {
            try {
                database.groupDao().resetUnreadCount(groupId)
                Log.d("GroupChatActivity", "Reset unread count for group $groupId")
            } catch (e: Exception) {
                Log.e("GroupChatActivity", "Error resetting unread count", e)
            }
        }
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false) // Hide default title
        
        // Inflate custom toolbar layout
        val customView = LayoutInflater.from(this).inflate(R.layout.toolbar_group_chat, null)
        toolbarGroupAvatar = customView.findViewById(R.id.imageViewToolbarGroupAvatar)
        toolbarGroupName = customView.findViewById(R.id.textViewToolbarGroupName)
        toolbarMemberCount = customView.findViewById(R.id.textViewToolbarMemberCount)
        
        // Set custom view
        supportActionBar?.customView = customView
        supportActionBar?.setDisplayShowCustomEnabled(true)
        
        // Initial state
        toolbarGroupName.text = "Caricamento..."
        toolbarMemberCount.text = ""
    }
    
    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(
            context = this,
            messages = emptyList(),
            currentUserId = currentUserId,
            lifecycleScope = lifecycleScope,
            onMediaClick = { message -> /* Handle media click */ },
            onReplyMessage = { message -> /* Handle reply */ },
            onForwardMessage = { message -> /* Handle forward */ },
            onDeleteMessage = { message -> /* Handle delete */ },
            authToken = sharedPreferences.getString("session_token", null),
            isGroupChat = true,
            database = database
        )
        
        binding.recyclerViewMessages.apply {
            layoutManager = LinearLayoutManager(this@GroupChatActivity).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }
    }
    
    private fun setupInputArea() {
        binding.buttonSend.setOnClickListener {
            sendMessage()
        }
        
        binding.buttonAttach.setOnClickListener {
            // TODO: Implement media attachment
            Toast.makeText(this, "Allegati in arrivo...", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun loadGroupInfo() {
        lifecycleScope.launch {
            // Observe group from local DB
            groupRepository.observeGroup(groupId).collectLatest { groupEntity ->
                group = groupEntity
                updateUI()
            }
        }
        
        // Refresh from server
        lifecycleScope.launch {
            val result = groupRepository.refreshGroupInfo(groupId)
            if (result.isFailure) {
                Toast.makeText(
                    this@GroupChatActivity,
                    "Errore caricamento info gruppo",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        
        // Load members to check if user is admin
        lifecycleScope.launch {
            groupRepository.observeGroupMembers(groupId).collectLatest { members ->
                val currentMember = members.find { it.userId == currentUserId }
                isUserAdmin = currentMember?.role == it.fabiodirauso.shutappchat.model.GroupRole.ADMIN
                updateInputAreaPermissions()
            }
        }
    }
    
    private fun updateUI() {
        group?.let {
            // Update custom toolbar
            toolbarGroupName.text = it.groupName
            toolbarMemberCount.text = "${it.totalMembers} membri"
            
            // Load group avatar
            AvatarHelper.loadGroupAvatar(
                context = this,
                imageView = toolbarGroupAvatar,
                groupName = it.groupName,
                groupPictureId = it.groupPictureId,
                lifecycleScope = lifecycleScope
            )
            
            // Update mode indicator
            if (it.groupMode == GroupMode.RESTRICTED) {
                binding.textViewModeInfo.visibility = View.VISIBLE
                binding.textViewModeInfo.text = "🔒 Solo admin possono scrivere"
            } else {
                binding.textViewModeInfo.visibility = View.GONE
            }
        }
    }
    
    private fun updateInputAreaPermissions() {
        val canSend = when {
            group?.groupMode == GroupMode.OPEN -> true
            group?.groupMode == GroupMode.RESTRICTED && isUserAdmin -> true
            else -> false
        }
        
        binding.editTextMessage.isEnabled = canSend
        binding.buttonSend.isEnabled = canSend
        binding.buttonAttach.isEnabled = canSend
        
        if (!canSend && group?.groupMode == GroupMode.RESTRICTED) {
            binding.editTextMessage.hint = "Solo gli admin possono scrivere"
        }
    }
    
    private fun loadMessages() {
        lifecycleScope.launch {
            // Load group messages from database
            database.messageDao().getGroupMessages(groupId).collectLatest { messages ->
                messageAdapter.updateMessages(messages)
                if (messages.isNotEmpty()) {
                    // Scroll smooth all'ultimo messaggio
                    binding.recyclerViewMessages.post {
                        binding.recyclerViewMessages.smoothScrollToPosition(messages.size - 1)
                    }
                }
            }
        }
    }
    
    private fun observeIncomingMessages() {
        // Observe messages from WebSocket
        lifecycleScope.launch {
            webSocketService?.getWebSocketClient()?.incomingMessages?.collectLatest { message ->
                if (message?.isGroup == true && message.groupId == groupId) {
                    // Message already saved to DB by WebSocketClient
                    // Scroll smooth all'ultimo messaggio ricevuto
                    binding.recyclerViewMessages.post {
                        binding.recyclerViewMessages.smoothScrollToPosition(messageAdapter.itemCount - 1)
                    }
                }
            }
        }
    }
    
    private fun observeWebSocketEvents() {
        // Called when service is connected
        observeIncomingMessages()
    }
    
    private fun sendMessage() {
        val messageText = binding.editTextMessage.text.toString().trim()
        
        if (messageText.isBlank()) {
            return
        }
        
        // Check permissions
        if (group?.groupMode == GroupMode.RESTRICTED && !isUserAdmin) {
            Toast.makeText(this, "Solo gli admin possono inviare messaggi", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Send message via WebSocket
        lifecycleScope.launch {
            try {
                val messageId = java.util.UUID.randomUUID().toString()
                val timestamp = System.currentTimeMillis()
                
                // Save message to local DB immediately with PENDING status
                val message = Message(
                    id = messageId,
                    senderId = webSocketService?.getWebSocketClient()?.userId ?: 0L,
                    recipientId = 0L, // Group messages don't have single receiver
                    content = messageText,
                    timestamp = java.util.Date(timestamp),
                    status = MessageStatus.PENDING,
                    messageType = MessageType.TEXT,
                    isGroup = true,
                    groupId = groupId
                )
                database.messageDao().insertMessage(message)
                
                // Send via WebSocket
                webSocketService?.getWebSocketClient()?.sendGroupMessage(groupId, messageText, "TEXT")
                
                // Clear input
                binding.editTextMessage.text.clear()
                
                // Scroll smooth all'ultimo messaggio inviato
                binding.recyclerViewMessages.post {
                    binding.recyclerViewMessages.smoothScrollToPosition(messageAdapter.itemCount)
                }
                
                // Update status to SENT after successful send
                // (it will be updated to DELIVERED when we receive it back from server)
                database.messageDao().updateMessageStatus(messageId, MessageStatus.SENT)
                
            } catch (e: Exception) {
                Toast.makeText(
                    this@GroupChatActivity,
                    "Errore invio messaggio: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_group_chat, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            R.id.action_group_info -> {
                openGroupSettings()
                true
            }
            R.id.action_search_messages -> {
                // TODO: Implement search
                Toast.makeText(this, "Ricerca messaggi...", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun openGroupSettings() {
        val intent = Intent(this, GroupSettingsActivity::class.java)
        intent.putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, groupId)
        startActivity(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Unbind from WebSocket service
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
    
    companion object {
        const val EXTRA_GROUP_ID = "group_id"
    }
}
