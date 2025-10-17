package it.fabiodirauso.shutappchat

import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import it.fabiodirauso.shutappchat.adapter.ConversationAdapter
import it.fabiodirauso.shutappchat.auth.TokenManager
import it.fabiodirauso.shutappchat.database.AppDatabase
import it.fabiodirauso.shutappchat.model.ConversationEntity
import it.fabiodirauso.shutappchat.model.ConversationItem
import it.fabiodirauso.shutappchat.services.ContactSyncService
import it.fabiodirauso.shutappchat.services.WebSocketService
import it.fabiodirauso.shutappchat.config.AppConfigManager
import it.fabiodirauso.shutappchat.utils.PermissionManager
import it.fabiodirauso.shutappchat.utils.PermissionDialogHelper
import it.fabiodirauso.shutappchat.utils.UIHelper
import it.fabiodirauso.shutappchat.session.SessionHealthMonitor
import it.fabiodirauso.shutappchat.ui.GroupListFragment
import java.io.File

class HomepageActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var fabNewChat: FloatingActionButton
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var bottomNavigationView: BottomNavigationView
    private lateinit var fragmentContainer: FrameLayout
    private lateinit var mainContainer: FrameLayout
    private lateinit var database: AppDatabase
    private lateinit var contactSyncService: ContactSyncService
    private lateinit var tokenManager: TokenManager
    
    private var currentNavigationItem = R.id.nav_chats
    private var notificationPermissionRequested = false
    private var optionsMenu: Menu? = null // Per aggiornare il badge delle notifiche
    
    // BroadcastReceiver per sessione invalida
    private val sessionInvalidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val reason = intent?.getStringExtra("reason")
            android.util.Log.w("HomepageActivity", "Received session invalid broadcast: $reason")
            runOnUiThread {
                showSessionExpiredDialog()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_homepage)
        
        // Abilita immersive mode (nasconde status bar e navigation bar)
        UIHelper.enableImmersiveMode(this)
        
        // Inizializza TokenManager
        tokenManager = TokenManager.getInstance(this)
        
        // Inizializza database PRIMA di controllare force update
        database = AppDatabase.getDatabase(this)
        contactSyncService = ContactSyncService(this)
        
        // Controlla se c'è una richiesta di aggiornamento forzato dall'intent
        if (intent.getBooleanExtra("show_force_update", false)) {
            showForceUpdateDialog(
                intent.getStringExtra("update_version") ?: "unknown",
                intent.getStringExtra("update_message") ?: "È richiesto un aggiornamento critico.",
                intent.getStringExtra("update_url") ?: "https://shutappchat.fabiodirauso.it/api/uploads/apk/shutappchat-latest.apk"
            )
        } else {
            // Controlla se c'è un force update pending nel database locale
            lifecycleScope.launch {
                val pendingUpdate = database.forceUpdateDao().getPendingForceUpdate()
                if (pendingUpdate != null) {
                    showForceUpdateDialog(
                        pendingUpdate.version,
                        pendingUpdate.message,
                        pendingUpdate.downloadUrl
                    )
                }
            }
        }
        
        // Imposta il token di autenticazione se disponibile
        setupAuthToken()
        
        // Inizializza privacy settings di default se non esistono
        initializePrivacySettings()
        
        // Assicurati che il servizio WebSocket sia attivo
        ensureWebSocketServiceRunning()
        
        setupViews()
        setupRecyclerView()
        setupClickListeners()
        observeConversations()
        
        // Monitora lo stato della sessione
        observeSessionHealth()
        
        // Richiedi permessi notifiche se necessario
        checkNotificationPermission()
        
        // Esegui pulizia automatica della cache (ogni 24 ore)
        performAutomaticCacheCleanup()
    }
    
    private fun setupAuthToken() {
        val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val token = sharedPreferences.getString("auth_token", null)
        if (!token.isNullOrEmpty()) {
            it.fabiodirauso.shutappchat.network.RetrofitClient.setAuthToken(token)
        }
    }
    
    private fun initializePrivacySettings() {
        lifecycleScope.launch {
            try {
                val securityManager = it.fabiodirauso.shutappchat.managers.SecuritySettingsManager.getInstance(this@HomepageActivity)
                securityManager.initializeDefaultSettings()
                android.util.Log.d("HomepageActivity", "Privacy settings initialized")
            } catch (e: Exception) {
                android.util.Log.e("HomepageActivity", "Error initializing privacy settings", e)
            }
        }
    }
    
    private fun setupViews() {
        // Toolbar
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.title = getString(R.string.title_chat)
        
        // Views
        recyclerView = findViewById(R.id.recyclerViewConversations)
        fabNewChat = findViewById(R.id.fabNewChat)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        bottomNavigationView = findViewById(R.id.bottomNavigationView)
        fragmentContainer = findViewById(R.id.fragmentContainer)
        mainContainer = findViewById(R.id.mainContainer)
        
        // Setup SwipeRefresh
        setupSwipeRefresh()
        
        // Setup Bottom Navigation
        setupBottomNavigation()
    }
    
    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener {
            refreshConversations()
        }
        
        // Configura colori
        swipeRefreshLayout.setColorSchemeResources(
            android.R.color.holo_blue_bright,
            android.R.color.holo_green_light,
            android.R.color.holo_orange_light,
            android.R.color.holo_red_light
        )
    }
    
    private fun refreshConversations() {
        lifecycleScope.launch {
            try {
                // 1. Sincronizza contatti
                val syncResult = contactSyncService.performFullSync()
                
                if (syncResult.success) {
                    android.util.Log.d("HomepageActivity", "Refresh: ${syncResult.itemsProcessed} contatti sincronizzati")
                }
                
                // 2. Le conversazioni si aggiorneranno automaticamente tramite Flow
                // Non serve ricaricarle manualmente
                
                // 3. Ferma l'animazione di refresh
                swipeRefreshLayout.isRefreshing = false
                
                if (syncResult.success) {
                    Toast.makeText(
                        this@HomepageActivity,
                        "Aggiornato: ${syncResult.itemsProcessed} contatti",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                
            } catch (e: Exception) {
                android.util.Log.e("HomepageActivity", "Error refreshing", e)
                swipeRefreshLayout.isRefreshing = false
                Toast.makeText(
                    this@HomepageActivity,
                    "Errore durante l'aggiornamento",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    private fun setupRecyclerView() {
        conversationAdapter = ConversationAdapter(
            context = this,
            lifecycleScope = lifecycleScope,
            items = emptyList(),
            onItemClick = { item ->
                openItem(item)
            },
            onItemLongClick = { item ->
                showDeleteItemDialog(item)
            }
        )
        
        recyclerView.apply {
            adapter = conversationAdapter
            layoutManager = LinearLayoutManager(this@HomepageActivity)
        }
    }
    
    private fun setupBottomNavigation() {
        bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chats -> {
                    showChatsView()
                    supportActionBar?.title = getString(R.string.title_chat)
                    currentNavigationItem = R.id.nav_chats
                    true
                }
                R.id.nav_groups -> {
                    showGroupsView()
                    supportActionBar?.title = "Gruppi"
                    currentNavigationItem = R.id.nav_groups
                    true
                }
                R.id.nav_search -> {
                    // Keep current view, open search activity
                    val intent = Intent(this, FindUserActivity::class.java)
                    startActivity(intent)
                    false // Don't change selection
                }
                R.id.nav_pending -> {
                    // Keep current view, open pending requests
                    val intent = Intent(this, ContactRequestsActivity::class.java)
                    startActivity(intent)
                    false // Don't change selection
                }
                R.id.nav_profile -> {
                    // Keep current view, show profile menu
                    showProfileMenu()
                    false // Don't change selection
                }
                else -> false
            }
        }
        
        // Set default selection
        bottomNavigationView.selectedItemId = R.id.nav_chats
        
        // Observe pending requests count for badge
        observePendingRequestsCount()
        
        // Observe unread counts for chat and groups badges
        observeChatsUnreadCount()
        observeGroupsUnreadCount()
    }
    
    private fun showChatsView() {
        swipeRefreshLayout.visibility = View.VISIBLE
        fragmentContainer.visibility = View.GONE
        fabNewChat.setImageResource(R.drawable.ic_add)
    }
    
    private fun showGroupsView() {
        swipeRefreshLayout.visibility = View.GONE
        fragmentContainer.visibility = View.VISIBLE
        fabNewChat.setImageResource(R.drawable.ic_add)
        
        // Replace with GroupListFragment
        val fragment = supportFragmentManager.findFragmentByTag("GroupListFragment")
        if (fragment == null) {
            try {
                val groupFragment = GroupListFragment.newInstance()
                
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, groupFragment, "GroupListFragment")
                    .commit()
            } catch (e: Exception) {
                android.util.Log.e("HomepageActivity", "Error loading GroupListFragment", e)
                Toast.makeText(this, "Errore caricamento gruppi", Toast.LENGTH_SHORT).show()
                // Fallback to chats
                bottomNavigationView.selectedItemId = R.id.nav_chats
            }
        }
    }
    
    private fun setupClickListeners() {
        // FAB click behavior based on current tab
        fabNewChat.setOnClickListener {
            when (currentNavigationItem) {
                R.id.nav_chats -> showNewChatOrGroupDialog()
                R.id.nav_groups -> openCreateGroup()
                else -> showNewChatOrGroupDialog()
            }
        }
        
        // Long-click on FAB always shows dialog
        fabNewChat.setOnLongClickListener {
            showNewChatOrGroupDialog()
            true
        }
    }
    
    /**
     * ✅ Mostra dialog per scegliere tra nuova chat o nuovo gruppo
     */
    private fun showNewChatOrGroupDialog() {
        val options = arrayOf("Nuova Chat", "Nuovo Gruppo")
        AlertDialog.Builder(this)
            .setTitle("Crea")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openUserListForNewChat()
                    1 -> openCreateGroup()
                }
            }
            .show()
    }
    
    /**
     * ✅ Apre activity per creare un gruppo
     */
    private fun openCreateGroup() {
        val intent = Intent(this, CreateGroupActivity::class.java)
        startActivity(intent)
    }
    
    private fun observeConversations() {
        lifecycleScope.launch {
            // Combina conversazioni e gruppi in un'unica lista
            kotlinx.coroutines.flow.combine(
                database.conversationDao().getAllConversations(),
                database.groupDao().getAllActiveGroups()
            ) { conversations, groups ->
                val items = mutableListOf<ConversationItem>()
                
                // Aggiungi conversazioni 1-1
                items.addAll(conversations.map { 
                    ConversationItem.Chat(it) 
                })
                
                // Aggiungi gruppi
                items.addAll(groups.map { 
                    ConversationItem.Group(it) 
                })
                
                // Ordina per ultimo messaggio (più recente prima)
                items.sortedByDescending { it.lastMessageTime }
            }.collect { items ->
                conversationAdapter.updateItems(items)
            }
        }
    }
    
    private fun observePendingRequestsCount() {
        lifecycleScope.launch {
            val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val currentUserId = sharedPreferences.getLong("user_id", 0)
            
            if (currentUserId > 0) {
                database.contactRequestDao().getPendingRequestCount(currentUserId)
                    .collect { count ->
                        runOnUiThread {
                            val badge = bottomNavigationView.getOrCreateBadge(R.id.nav_pending)
                            if (count > 0) {
                                badge.isVisible = true
                                badge.number = count
                                if (count > 99) {
                                    badge.number = 99
                                    badge.text = "99+"
                                }
                            } else {
                                badge.isVisible = false
                            }
                        }
                    }
            }
        }
    }
    
    /**
     * Osserva il conteggio totale dei messaggi non letti nelle chat individuali
     */
    private fun observeChatsUnreadCount() {
        lifecycleScope.launch {
            val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val currentUserId = sharedPreferences.getLong("user_id", 0)
            
            if (currentUserId > 0) {
                database.messageDao().getTotalUnreadCount(currentUserId)
                    .collect { count ->
                        runOnUiThread {
                            val badge = bottomNavigationView.getOrCreateBadge(R.id.nav_chats)
                            if (count > 0) {
                                badge.isVisible = true
                                badge.number = count
                                badge.backgroundColor = resources.getColor(android.R.color.holo_red_dark, null)
                                if (count > 99) {
                                    badge.number = 99
                                    badge.text = "99+"
                                }
                            } else {
                                badge.isVisible = false
                            }
                        }
                    }
            }
        }
    }
    
    /**
     * Osserva il conteggio totale dei messaggi non letti nei gruppi
     */
    private fun observeGroupsUnreadCount() {
        lifecycleScope.launch {
            database.groupDao().getTotalUnreadCount()
                .collect { count ->
                    runOnUiThread {
                        val badge = bottomNavigationView.getOrCreateBadge(R.id.nav_groups)
                        val totalCount = count ?: 0
                        if (totalCount > 0) {
                            badge.isVisible = true
                            badge.number = totalCount
                            badge.backgroundColor = resources.getColor(android.R.color.holo_red_dark, null)
                            if (totalCount > 99) {
                                badge.number = 99
                                badge.text = "99+"
                            }
                        } else {
                            badge.isVisible = false
                        }
                    }
                }
        }
    }
    
    private fun openUserListForNewChat() {
        // Apre la lista dei contatti amici per iniziare una nuova chat
        val intent = Intent(this, MyContactsActivity::class.java)
        startActivity(intent)
    }
    
    /**
     * ✅ Apre una chat o un gruppo
     */
    private fun openItem(item: it.fabiodirauso.shutappchat.model.ConversationItem) {
        when (item) {
            is it.fabiodirauso.shutappchat.model.ConversationItem.Chat -> {
                openChat(item.conversation)
            }
            is it.fabiodirauso.shutappchat.model.ConversationItem.Group -> {
                openGroup(item.group)
            }
        }
    }
    
    /**
     * ✅ Apre una chat di gruppo
     */
    private fun openGroup(group: it.fabiodirauso.shutappchat.model.GroupEntity) {
        val intent = Intent(this, ChatActivity::class.java)
        intent.putExtra("group_id", group.groupId)
        intent.putExtra("contact_name", group.groupName)
        startActivity(intent)
    }
    
    private fun openChat(conversation: ConversationEntity) {
        val intent = Intent(this, ChatActivity::class.java)
        intent.putExtra("contact_id", conversation.participantId.toLongOrNull() ?: 0L)
        intent.putExtra("contact_name", conversation.participantName)
        // ✅ v1.2.5: Usa participantUsername se disponibile, altrimenti fallback a participantName
        intent.putExtra("contact_username", conversation.participantUsername ?: conversation.participantName)
        intent.putExtra("profile_picture_id", conversation.profilePictureId) // Passa l'ID della foto profilo
        intent.putExtra("conversation_id", conversation.id)
        startActivity(intent)
    }
    
    private fun showDeleteChatDialog(conversation: ConversationEntity) {
        AlertDialog.Builder(this)
            .setTitle("Elimina chat")
            .setMessage("Sei sicuro di voler eliminare tutta la conversazione con ${conversation.participantName}? Questa azione non può essere annullata.")
            .setPositiveButton("Elimina") { _, _ ->
                deleteConversation(conversation)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
    
    /**
     * ✅ Mostra dialog per eliminare chat o gruppo
     */
    private fun showDeleteItemDialog(item: it.fabiodirauso.shutappchat.model.ConversationItem) {
        when (item) {
            is it.fabiodirauso.shutappchat.model.ConversationItem.Chat -> {
                showDeleteChatDialog(item.conversation)
            }
            is it.fabiodirauso.shutappchat.model.ConversationItem.Group -> {
                showDeleteGroupDialog(item.group)
            }
        }
    }
    
    /**
     * ✅ Mostra dialog per eliminare un gruppo
     */
    private fun showDeleteGroupDialog(group: it.fabiodirauso.shutappchat.model.GroupEntity) {
        val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val userId = sharedPreferences.getLong("user_id", 0)
        
        lifecycleScope.launch {
            val isAdmin = withContext(kotlinx.coroutines.Dispatchers.IO) {
                database.groupMemberDao().isAdmin(group.groupId, userId)
            }
            
            if (isAdmin) {
                AlertDialog.Builder(this@HomepageActivity)
                    .setTitle("Elimina gruppo")
                    .setMessage("Sei l'amministratore. Vuoi eliminare il gruppo \"${group.groupName}\" per tutti?")
                    .setPositiveButton("Elimina") { _, _ ->
                        deleteGroup(group)
                    }
                    .setNegativeButton("Annulla", null)
                    .show()
            } else {
                AlertDialog.Builder(this@HomepageActivity)
                    .setTitle("Esci dal gruppo")
                    .setMessage("Vuoi uscire dal gruppo \"${group.groupName}\"?")
                    .setPositiveButton("Esci") { _, _ ->
                        leaveGroup(group, userId)
                    }
                    .setNegativeButton("Annulla", null)
                    .show()
            }
        }
    }
    
    /**
     * ✅ Elimina un gruppo (solo admin)
     */
    private fun deleteGroup(group: it.fabiodirauso.shutappchat.model.GroupEntity) {
        lifecycleScope.launch {
            try {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    database.groupDao().updateGroupActiveStatus(group.groupId, false)
                }
                Toast.makeText(this@HomepageActivity, "Gruppo eliminato", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e("HomepageActivity", "Error deleting group", e)
                Toast.makeText(this@HomepageActivity, "Errore eliminazione gruppo", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * ✅ Esci da un gruppo (membri non-admin)
     */
    private fun leaveGroup(group: it.fabiodirauso.shutappchat.model.GroupEntity, userId: Long) {
        lifecycleScope.launch {
            try {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    database.groupMemberDao().updateMemberActiveStatus(group.groupId, userId, false)
                }
                Toast.makeText(this@HomepageActivity, "Hai lasciato il gruppo", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e("HomepageActivity", "Error leaving group", e)
                Toast.makeText(this@HomepageActivity, "Errore uscita dal gruppo", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun deleteConversation(conversation: ConversationEntity) {
        lifecycleScope.launch {
            try {
                // 1. Recupera tutti i messaggi della conversazione per eliminare i media
                val messages = database.messageDao().getMessagesListForConversation(conversation.id)
                
                // 2. Elimina tutti i file media associati ai messaggi
                var deletedMediaCount = 0
                withContext(Dispatchers.IO) {
                    messages.forEach { message ->
                        // Elimina file media dalla cache se presente
                        if (message.mediaId != null) {
                            val mediaFile = File(cacheDir, "media_${message.mediaId}")
                            if (mediaFile.exists()) {
                                val deleted = mediaFile.delete()
                                if (deleted) {
                                    deletedMediaCount++
                                    android.util.Log.d("HomepageActivity", "Deleted media file: ${mediaFile.name}")
                                }
                            }
                            
                            // Elimina anche eventuali thumbnail
                            val thumbnailFile = File(cacheDir, "thumb_${message.mediaId}")
                            if (thumbnailFile.exists()) {
                                thumbnailFile.delete()
                                android.util.Log.d("HomepageActivity", "Deleted thumbnail: ${thumbnailFile.name}")
                            }
                        }
                    }
                }
                
                // 3. Elimina tutti i messaggi della conversazione dal database
                database.messageDao().deleteAllMessagesInConversation(conversation.id)
                android.util.Log.d("HomepageActivity", "Deleted ${messages.size} messages from conversation")
                
                // 4. Elimina la conversazione stessa
                database.conversationDao().deleteConversation(conversation)
                
                // 5. Mostra messaggio di conferma
                val message = if (deletedMediaCount > 0) {
                    "Chat eliminata: ${messages.size} messaggi e $deletedMediaCount file media"
                } else {
                    "Chat con ${conversation.participantName} eliminata"
                }
                
                Toast.makeText(
                    this@HomepageActivity,
                    message,
                    Toast.LENGTH_SHORT
                ).show()
                
            } catch (e: Exception) {
                android.util.Log.e("HomepageActivity", "Error deleting conversation", e)
                Toast.makeText(
                    this@HomepageActivity,
                    "Errore durante l'eliminazione: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Esegue la pulizia automatica della cache ogni 24 ore.
     */
    private fun performAutomaticCacheCleanup() {
        lifecycleScope.launch {
            try {
                val cleanupService = it.fabiodirauso.shutappchat.services.CacheCleanupService(this@HomepageActivity)
                val wasExecuted = cleanupService.checkAndCleanup()
                
                if (wasExecuted) {
                    android.util.Log.d("HomepageActivity", "Automatic cache cleanup completed")
                }
            } catch (e: Exception) {
                android.util.Log.e("HomepageActivity", "Error during automatic cache cleanup", e)
            }
        }
    }
    
    /**
     * Esegue la pulizia manuale della cache (chiamata dal menu).
     */
    private fun performManualCacheCleanup() {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@HomepageActivity, "Pulizia cache in corso...", Toast.LENGTH_SHORT).show()
                
                val cleanupService = it.fabiodirauso.shutappchat.services.CacheCleanupService(this@HomepageActivity)
                
                // Mostra statistiche prima della pulizia
                val statsBefore = cleanupService.getCacheStats()
                android.util.Log.d("HomepageActivity", "Cache before cleanup: ${statsBefore.totalFiles} files, ${statsBefore.getTotalSizeMB()}MB")
                
                // Esegui pulizia forzata
                val result = cleanupService.forceCleanup()
                
                if (result.success) {
                    val message = "Cache pulita: ${result.filesDeleted} file eliminati, ${result.bytesFreed / 1024}KB liberati"
                    Toast.makeText(this@HomepageActivity, message, Toast.LENGTH_LONG).show()
                    android.util.Log.d("HomepageActivity", message)
                } else {
                    Toast.makeText(this@HomepageActivity, "Errore durante la pulizia: ${result.error}", Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                android.util.Log.e("HomepageActivity", "Error during manual cache cleanup", e)
                Toast.makeText(this@HomepageActivity, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_conversations, menu)
        this.optionsMenu = menu
        observeUnreadNotifications() // Avvia osservazione badge
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_contacts -> {
                val intent = Intent(this, MyContactsActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_search -> {
                val intent = Intent(this, FindUserActivity::class.java)
                intent.putExtra("mode", "search")
                startActivity(intent)
                true
            }
            R.id.action_sync_contacts -> {
                syncContacts()
                true
            }
            R.id.action_system_notifications -> {
                val intent = Intent(this, SystemNotificationsActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_clear_cache -> {
                performManualCacheCleanup()
                true
            }
            R.id.action_donate -> {
                openDonationPage()
                true
            }
            R.id.action_download -> {
                openDownloadPage()
                true
            }
            R.id.action_logout -> {
                performLogout()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun syncContacts() {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@HomepageActivity, "Sincronizzazione contatti in corso...", Toast.LENGTH_SHORT).show()
                
                val result = contactSyncService.performFullSync()
                
                if (result.success) {
                    Toast.makeText(this@HomepageActivity, 
                        "Sincronizzati ${result.itemsProcessed} contatti con successo!", 
                        Toast.LENGTH_SHORT).show()
                    // Refresh conversations list after sync
                    observeConversations()
                } else {
                    Toast.makeText(this@HomepageActivity, 
                        "Errore sincronizzazione: ${result.error}", 
                        Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@HomepageActivity, 
                    "Errore durante sincronizzazione: ${e.message}", 
                    Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun showProfileMenu() {
        val options = arrayOf("Il Mio Profilo", "Impostazioni", "Annulla")
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Profilo")
        builder.setItems(options) { dialog, which ->
            when (which) {
                0 -> {
                    // Il Mio Profilo
                    val intent = Intent(this, MyProfileActivity::class.java)
                    startActivity(intent)
                }
                1 -> {
                    // Impostazioni (unificata: privacy + info + stato)
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                }
                2 -> {
                    // Annulla
                    dialog.dismiss()
                }
            }
        }
        builder.show()
    }
    
    private fun ensureWebSocketServiceRunning() {
        val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val token = sharedPreferences.getString("user_token", null)
        
        if (!token.isNullOrEmpty()) {
            val serviceIntent = Intent(this, WebSocketService::class.java).apply {
                action = WebSocketService.ACTION_START
            }
            startForegroundService(serviceIntent)
            android.util.Log.d("HomepageActivity", "WebSocket service ensured running")
        }
    }
    
    private fun performLogout() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Sei sicuro di voler effettuare il logout?")
            .setPositiveButton("Sì") { _, _ ->
                logout()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
    
    private fun logout() {
        lifecycleScope.launch {
            // Clear TokenManager (encrypted storage)
            tokenManager.clearSession()
            
            // Clear saved authentication data (legacy)
            val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
            sharedPreferences.edit().clear().apply()
            
            // Stop WebSocket service
            val serviceIntent = Intent(this@HomepageActivity, WebSocketService::class.java)
            stopService(serviceIntent)
            
            // Clear RetrofitClient auth token
            it.fabiodirauso.shutappchat.network.RetrofitClient.setAuthToken("")
            
            // Redirect to login
            val intent = Intent(this@HomepageActivity, AuthActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            
            Toast.makeText(this@HomepageActivity, "Logout effettuato con successo", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun openDonationPage() {
        val configManager = AppConfigManager.getInstance(this)
        val donateUrl = configManager.donateUrl
        
        if (donateUrl.isNotEmpty()) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(donateUrl))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Errore apertura pagina donazioni: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Link donazioni non disponibile", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun openDownloadPage() {
        val configManager = AppConfigManager.getInstance(this)
        val downloadUrl = configManager.downloadUrl
        
        if (downloadUrl.isNotEmpty()) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Errore apertura pagina download: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Link download non disponibile", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Verifica e richiede il permesso per le notifiche
     */
    private fun checkNotificationPermission() {
        if (notificationPermissionRequested) return
        
        // Verifica se è necessario richiedere il permesso (solo Android 13+)
        if (!PermissionManager.hasNotificationPermission(this)) {
            // Mostra una spiegazione prima di richiedere
            val sharedPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            val notificationPermissionDenied = sharedPrefs.getBoolean("notification_permission_denied", false)
            
            if (!notificationPermissionDenied) {
                notificationPermissionRequested = true
                PermissionDialogHelper.handlePermissionRequest(
                    activity = this,
                    permissionType = PermissionManager.PermissionType.NOTIFICATIONS,
                    onGranted = {
                        Toast.makeText(this, "Notifiche abilitate", Toast.LENGTH_SHORT).show()
                    },
                    onDenied = {
                        // Salva che l'utente ha rifiutato
                        sharedPrefs.edit().putBoolean("notification_permission_denied", true).apply()
                    }
                )
            }
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            PermissionManager.RequestCodes.POST_NOTIFICATIONS -> {
                val granted = PermissionManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
                if (granted) {
                    Toast.makeText(this, "Riceverai notifiche per i nuovi messaggi", Toast.LENGTH_SHORT).show()
                } else {
                    // Salva preferenza
                    getSharedPreferences("app_prefs", MODE_PRIVATE)
                        .edit()
                        .putBoolean("notification_permission_denied", true)
                        .apply()
                }
            }
        }
    }
    
    /**
     * Monitora lo stato di salute della sessione WebSocket.
     * Se la sessione diventa invalida, mostra un dialog e fa logout.
     */
    private fun observeSessionHealth() {
        val sessionMonitor = SessionHealthMonitor.getInstance()
        
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionMonitor.sessionInvalid.collect { isInvalid ->
                    if (isInvalid) {
                        showSessionExpiredDialog()
                    }
                }
            }
        }
    }
    
    /**
     * Mostra dialog di sessione scaduta e fa logout
     */
    private fun showSessionExpiredDialog() {
        AlertDialog.Builder(this)
            .setTitle("Sessione scaduta")
            .setMessage("La tua sessione è scaduta o non è più valida. È necessario effettuare nuovamente il login.")
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                performSessionRecovery()
            }
            .show()
    }
    
    /**
     * Esegue il logout per recovery sessione: pulisce dati e reindirizza al login
     */
    private fun performSessionRecovery() {
        lifecycleScope.launch {
            try {
                // 1. Ferma il servizio WebSocket
                stopService(Intent(this@HomepageActivity, WebSocketService::class.java))
                
                // 2. Pulisci TokenManager (encrypted storage)
                tokenManager.clearSession()
                
                // 3. Pulisci SharedPreferences
                val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
                sharedPreferences.edit().clear().apply()
                
                // 4. Pulisci database locale
                val database = AppDatabase.getDatabase(this@HomepageActivity)
                database.clearAllTables()
                
                // 5. Resetta il monitor di sessione
                SessionHealthMonitor.getInstance().reset()
                
                // 6. Reindirizza al login
                val intent = Intent(this@HomepageActivity, AuthActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
                
            } catch (e: Exception) {
                Toast.makeText(this@HomepageActivity, "Errore durante il logout: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    /**
     * Osserva il conteggio delle notifiche non lette e aggiorna il badge nel menu
     */
    private fun observeUnreadNotifications() {
        lifecycleScope.launch {
            database.systemNotificationDao().getUnreadCount().collect { count ->
                updateNotificationBadge(count)
            }
        }
    }
    
    /**
     * Aggiorna il badge delle notifiche nel menu
     */
    private fun updateNotificationBadge(count: Int? = null) {
        val menu = optionsMenu ?: return
        val menuItem = menu.findItem(R.id.action_system_notifications) ?: return
        
        if (count != null && count > 0) {
            // Mostra badge con numero
            val badge = "📢 ($count)"
            menuItem.title = "Notifiche di Sistema $badge"
        } else {
            // Nessuna notifica non letta
            menuItem.title = "Notifiche di Sistema"
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Registra broadcast receiver per eventi sessione invalida
        val filter = IntentFilter("it.fabiodirauso.shutappchat.SESSION_INVALID")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sessionInvalidReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(sessionInvalidReceiver, filter)
        }
        android.util.Log.d("HomepageActivity", "Session invalid receiver registered")
        
        // Aggiorna badge notifiche quando si ritorna all'activity
        updateNotificationBadge()
    }
    
    override fun onPause() {
        super.onPause()
        // Deregistra broadcast receiver
        try {
            unregisterReceiver(sessionInvalidReceiver)
            android.util.Log.d("HomepageActivity", "Session invalid receiver unregistered")
        } catch (e: Exception) {
            android.util.Log.w("HomepageActivity", "Error unregistering receiver: ${e.message}")
        }
    }

    /**
     * Mostra il dialog di aggiornamento forzato
     * Questo dialog non può essere chiuso e blocca l'utilizzo dell'app
     */
    private fun showForceUpdateDialog(version: String, message: String, downloadUrl: String) {
        try {
            val dialog = it.fabiodirauso.shutappchat.dialogs.ForceUpdateDialog(
                this,
                version,
                message,
                downloadUrl
            )
            dialog.show()
        } catch (e: Exception) {
            android.util.Log.e("HomepageActivity", "Error showing force update dialog", e)
        }
    }
}

