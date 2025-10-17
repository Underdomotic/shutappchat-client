package it.fabiodirauso.shutappchat

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import it.fabiodirauso.shutappchat.adapter.MessageAdapter
import it.fabiodirauso.shutappchat.databinding.ActivityChatBinding
import it.fabiodirauso.shutappchat.model.Message
import it.fabiodirauso.shutappchat.model.MessageType
import it.fabiodirauso.shutappchat.model.ConversationEntity
import it.fabiodirauso.shutappchat.model.MediaMessagePayload
import it.fabiodirauso.shutappchat.model.EmojiMessagePayload
import it.fabiodirauso.shutappchat.network.RetrofitClient
import it.fabiodirauso.shutappchat.services.ChatMediaService
import it.fabiodirauso.shutappchat.services.MediaManager
import it.fabiodirauso.shutappchat.utils.AvatarHelper
import it.fabiodirauso.shutappchat.utils.PermissionManager
import it.fabiodirauso.shutappchat.utils.PermissionDialogHelper
import it.fabiodirauso.shutappchat.utils.UIHelper
import it.fabiodirauso.shutappchat.utils.MessageFormatter
import it.fabiodirauso.shutappchat.services.MessageSender
import it.fabiodirauso.shutappchat.config.ServerConfig
import it.fabiodirauso.shutappchat.database.AppDatabase
import com.google.gson.Gson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "ChatActivity"
    }
    
    private lateinit var binding: ActivityChatBinding
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var mediaManager: MediaManager
    private lateinit var chatMediaService: ChatMediaService
    private lateinit var messageSender: MessageSender
    private lateinit var database: AppDatabase
    private val messages = mutableListOf<Message>()
    private val gson = Gson()
    
    private var contactId: Long = 0
    private var contactName: String = ""
    private var contactUsername: String = ""
    private var profilePictureId: String? = null
    private var conversationId: String = ""
    
    // ✅ Variabili per gestire i gruppi
    private var groupId: String? = null
    private var isGroupChat: Boolean = false
    private var isGroupAdmin: Boolean = false
    private var groupMode: it.fabiodirauso.shutappchat.model.GroupMode = it.fabiodirauso.shutappchat.model.GroupMode.OPEN
    
    // Camera photo URI temporaneo
    private var currentPhotoUri: Uri? = null
    private var currentVideoUri: Uri? = null
    
    // ✅ Variabili per gestire la risposta a un messaggio
    private var replyToMessage: Message? = null
    
    // Riferimenti UI per aggiornamenti periodici

    
    // Activity result launchers per file selection
    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { openImageEditor(it) }
    }
    
    // Launcher per l'editor di immagini
    private val imageEditorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val editedUri = result.data?.getParcelableExtra<Uri>("edited_image_uri")
            editedUri?.let { sendMediaMessage(it, "image") }
        }
    }
    
    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { sendMediaMessage(it, "video") }
    }
    
    private val documentPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { sendMediaMessage(it, "document") }
    }
    
    // Camera launcher per scattare foto
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && currentPhotoUri != null) {
            openImageEditor(currentPhotoUri!!)
        } else {
            Toast.makeText(this, "Foto annullata o errore camera", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Video camera launcher per registrare video
    private val videoCameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && currentVideoUri != null) {
            sendMediaMessage(currentVideoUri!!, "video")
        } else {
            Toast.makeText(this, "Video annullato o errore", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Abilita immersive mode (nasconde status bar e navigation bar)
        UIHelper.enableImmersiveMode(this)
        
        // 🎯 Gestione tastiera in fullscreen - sposta bottom bar sopra la tastiera
        setupKeyboardAwareLayout()
        
        // Nascondi l'ActionBar di default per usare il nostro header personalizzato
        supportActionBar?.hide()
        
        sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        database = AppDatabase.getDatabase(this)
        
        // Set auth token for API calls
        val token = sharedPreferences.getString("auth_token", "") ?: ""
        RetrofitClient.setAuthToken(token)
        android.util.Log.d("ChatActivity", "Auth token impostato per RetrofitClient: ${if (token.isNotEmpty()) "presente (${token.length} chars)" else "mancante"}")
        
        // Get contact information from intent
        contactId = intent.getLongExtra("contact_id", 0)
        contactName = intent.getStringExtra("contact_name") ?: "Unknown"
        contactUsername = intent.getStringExtra("contact_username") ?: ""
        profilePictureId = intent.getStringExtra("profile_picture_id")
        
        // ✅ Verifica se è una chat di gruppo
        groupId = intent.getStringExtra("group_id")
        isGroupChat = groupId != null
        
        Log.d("ChatActivity", "=== Chat opened with ===")
        Log.d("ChatActivity", "contactId: $contactId")
        Log.d("ChatActivity", "contactName: $contactName")
        Log.d("ChatActivity", "contactUsername: $contactUsername")
        Log.d("ChatActivity", "isGroupChat: $isGroupChat")
        if (isGroupChat) {
            Log.d("ChatActivity", "groupId: $groupId")
        }
        Log.d("ChatActivity", "=======================")
        
        // Generate conversation ID based on user IDs (or use groupId for groups)
        val userId = sharedPreferences.getLong("user_id", 0)
        conversationId = if (isGroupChat) {
            groupId!!
        } else {
            generateConversationId(userId, contactId)
        }
        
        // ✅ Carica le informazioni del gruppo se è una chat di gruppo
        if (isGroupChat) {
            loadGroupInfo()
        }
        
        setupViews()
        setupClickListeners()
        setupMediaService()
        observeNewMessages() // This will load initial messages AND observe for changes
        markMessagesAsRead()
        createOrUpdateConversation()
    }
    
    private fun setupViews() {
        // Setup custom header bar navigation
        binding.btnBack.setOnClickListener {
            onBackPressed()
        }
        
        // Configura avatar e nome nella toolbar
        setupToolbarUserInfo()
        
        // Setup RecyclerView
        val userId = sharedPreferences.getLong("user_id", 0)
        val authToken = sharedPreferences.getString("auth_token", "") ?: ""
        messageAdapter = MessageAdapter(
            context = this,
            messages = messages,
            currentUserId = userId,
            lifecycleScope = lifecycleScope,
            onMediaClick = { message ->
                // Gestione del click su un messaggio
                when (message.messageType) {
                    MessageType.IMAGE, MessageType.VIDEO, MessageType.DOCUMENT, MessageType.MEDIA -> {
                        // Se è un media, apri/scarica il file
                        handleMediaMessageClick(message)
                    }
                    else -> {
                        // Per altri tipi di messaggio, nessuna azione specifica
                    }
                }
            },
            onReplyMessage = { message ->
                // ✅ Gestione risposta al messaggio
                handleReplyToMessage(message)
            },
            onForwardMessage = { message ->
                // ✅ Gestione inoltra messaggio
                handleForwardMessage(message)
            },
            onDeleteMessage = { message ->
                // ✅ Mostra popup di conferma per eliminare il messaggio
                showDeleteMessageDialog(message)
            },
            authToken = authToken,
            isGroupChat = isGroupChat,  // ✅ Passa flag gruppo
            database = database  // ✅ Passa database per caricare nomi membri
        )
        
        binding.rvMessages.apply {
            adapter = messageAdapter
            layoutManager = LinearLayoutManager(this@ChatActivity)
        }
    }
    
    private fun observeNewMessages() {
        // Observe database for new messages in this conversation
        Log.d("ChatActivity", "Starting to observe messages for conversationId: $conversationId")
        lifecycleScope.launch(Dispatchers.Main) {
            database.messageDao().getMessagesForConversation(conversationId).collect { dbMessages ->
                // Always update the list when database changes
                val oldSize = messages.size
                messages.clear()
                messages.addAll(dbMessages)
                messageAdapter.updateMessages(messages)
                
                Log.d("ChatActivity", "🔄 Flow emitted ${dbMessages.size} messages (oldSize=$oldSize)")
                
                // Scroll to bottom if there are new messages
                if (dbMessages.size > oldSize) {
                    binding.rvMessages.scrollToPosition(messages.size - 1)
                    Log.d("ChatActivity", "Received ${dbMessages.size - oldSize} new messages from database (total: ${dbMessages.size})")
                } else if (dbMessages.size != oldSize) {
                    // Messages changed but not necessarily more (could be less or same)
                    Log.d("ChatActivity", "Messages list updated: ${dbMessages.size} total (was $oldSize)")
                }
            }
        }
    }
    
    private fun setupClickListeners() {
        binding.btnSend.setOnClickListener {
            val content = binding.etMessage.text.toString().trim()
            if (content.isNotEmpty()) {
                sendTextMessage(content)
                binding.etMessage.text.clear()
            }
        }
        
        // ✅ Listener per chiudere la reply preview
        findViewById<View>(R.id.buttonCloseReply)?.setOnClickListener {
            cancelReply()
        }
        
        // Handle unified attachment button
        binding.buttonAttachment?.setOnClickListener {
            showAttachmentBottomSheet()
        }
        
        // Handle emoji button
        binding.buttonEmoji?.setOnClickListener {
            showEmojiPicker()
        }
    }
    
    // ===================== MENU =====================
    
    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_chat, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_delete_chat -> {
                showDeleteChatDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun setupMediaService() {
        chatMediaService = ChatMediaService(this)
        mediaManager = MediaManager(this)
        // Initialize message sender
        messageSender = MessageSender(RetrofitClient, this)
    }
    
    /**
     * 🎯 Gestisce il layout quando appare/scompare la tastiera
     * In fullscreen mode, adjustResize non funziona nativamente.
     * Usiamo WindowInsets per spostare la bottom bar sopra la tastiera.
     */
    private fun setupKeyboardAwareLayout() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val imeInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val systemBarsInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            
            // Applica padding bottom alla RecyclerView per evitare che i messaggi vadano sotto la tastiera
            binding.rvMessages.setPadding(
                binding.rvMessages.paddingLeft,
                binding.rvMessages.paddingTop,
                binding.rvMessages.paddingRight,
                imeInsets.bottom
            )
            
            // Sposta la bottom bar (input area) sopra la tastiera
            val inputAreaBottomMargin = if (imeInsets.bottom > 0) {
                // Tastiera aperta: posiziona sopra la tastiera
                imeInsets.bottom
            } else {
                // Tastiera chiusa: posizione normale
                0
            }
            
            val params = binding.linearLayoutInputArea.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.bottomMargin = inputAreaBottomMargin
            binding.linearLayoutInputArea.layoutParams = params
            
            // Auto-scroll all'ultimo messaggio quando si apre la tastiera
            if (imeInsets.bottom > 0 && messages.isNotEmpty()) {
                binding.rvMessages.post {
                    binding.rvMessages.smoothScrollToPosition(messages.size - 1)
                }
            }
            
            insets
        }
        
        // Abilita edge-to-edge layout per il window insets
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
    }
    
    /**
     * Mostra opzioni per allegati
     */
    private fun showAttachmentOptions() {
        val options = arrayOf("Immagine", "Video", "Documento")
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Seleziona tipo di allegato")
        builder.setItems(options) { _, which ->
            when (which) {
                0 -> requestMediaPermissionAndLaunch(PermissionManager.PermissionType.MEDIA_IMAGES) {
                    imagePickerLauncher.launch("image/*")
                }
                1 -> requestMediaPermissionAndLaunch(PermissionManager.PermissionType.MEDIA_VIDEO) {
                    videoPickerLauncher.launch("video/*")
                }
                2 -> requestMediaPermissionAndLaunch(PermissionManager.PermissionType.MEDIA_IMAGES) {
                    documentPickerLauncher.launch("*/*")
                }
            }
        }
        builder.show()
    }
    
    /**
     * 🎯 Mostra Bottom Sheet con tutte le opzioni allegati
     */
    private fun showAttachmentBottomSheet() {
        val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_attachment, null)
        bottomSheetDialog.setContentView(view)
        
        // Fotocamera
        view.findViewById<View>(R.id.btnCamera).setOnClickListener {
            bottomSheetDialog.dismiss()
            launchCamera()
        }
        
        // Galleria Immagini
        view.findViewById<View>(R.id.btnGalleryImage).setOnClickListener {
            bottomSheetDialog.dismiss()
            requestMediaPermissionAndLaunch(PermissionManager.PermissionType.MEDIA_IMAGES) {
                imagePickerLauncher.launch("image/*")
            }
        }
        
        // Videocamera
        view.findViewById<View>(R.id.btnVideoCamera).setOnClickListener {
            bottomSheetDialog.dismiss()
            launchVideoCamera()
        }
        
        // Galleria Video
        view.findViewById<View>(R.id.btnGalleryVideo).setOnClickListener {
            bottomSheetDialog.dismiss()
            requestMediaPermissionAndLaunch(PermissionManager.PermissionType.MEDIA_VIDEO) {
                videoPickerLauncher.launch("video/*")
            }
        }
        
        // Documenti
        view.findViewById<View>(R.id.btnDocument).setOnClickListener {
            bottomSheetDialog.dismiss()
            requestMediaPermissionAndLaunch(PermissionManager.PermissionType.MEDIA_IMAGES) {
                documentPickerLauncher.launch("*/*")
            }
        }
        
        // Audio (TODO: implementare registrazione audio)
        view.findViewById<View>(R.id.btnAudio).setOnClickListener {
            bottomSheetDialog.dismiss()
            Toast.makeText(this, "Funzione audio in arrivo!", Toast.LENGTH_SHORT).show()
        }
        
        // Pulsante Annulla
        view.findViewById<View>(R.id.btnCancel).setOnClickListener {
            bottomSheetDialog.dismiss()
        }
        
        bottomSheetDialog.show()
    }
    
    /**
     * Lancia videocamera per registrare video
     */
    private fun launchVideoCamera() {
        if (!PermissionManager.hasCameraPermission(this)) {
            PermissionManager.checkAndRequestCameraPermission(this)
            Toast.makeText(this, "Permesso camera necessario", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            val videoFile = createVideoFile()
            currentVideoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                videoFile
            )
            
            val intent = Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE)
            intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, currentVideoUri)
            intent.putExtra(android.provider.MediaStore.EXTRA_VIDEO_QUALITY, 1)
            intent.putExtra(android.provider.MediaStore.EXTRA_DURATION_LIMIT, 60) // Max 60 secondi
            
            videoCameraLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching video camera", e)
            Toast.makeText(this, "Errore apertura videocamera", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Crea file temporaneo per video
     */
    private fun createVideoFile(): File {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val storageDir = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
        return File.createTempFile("VIDEO_${timestamp}_", ".mp4", storageDir)
    }
    
    /**
     * Richiede i permessi media necessari prima di lanciare il picker
     */
    private fun requestMediaPermissionAndLaunch(
        permissionType: PermissionManager.PermissionType,
        onGranted: () -> Unit
    ) {
        if (!PermissionManager.isPermissionGranted(this, permissionType)) {
            PermissionDialogHelper.handlePermissionRequest(
                activity = this,
                permissionType = permissionType,
                onGranted = onGranted,
                onDenied = {
                    Toast.makeText(
                        this,
                        "Permesso necessario per selezionare file",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
            return
        }
        onGranted()
    }
    
    /**
     * 😀 Mostra Bottom Sheet emoji categorizzate
     */
    private fun showEmojiPicker() {
        val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_emoji, null)
        bottomSheetDialog.setContentView(view)
        
        // Categorie di emoji
        val emojiCategories = mapOf(
            R.id.gridEmojiFaces to listOf(
                "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃", "😉", "😊",
                "😇", "🥰", "😍", "🤩", "😘", "😗", "😚", "😙", "🥲", "😋", "😛", "😜",
                "🤪", "😝", "🤑", "🤗", "🤭", "🤫", "🤔", "🤐", "🤨", "😐", "😑", "😶",
                "😏", "😒", "🙄", "😬", "🤥", "😌", "😔", "�", "🤤", "😴", "😷", "🤒",
                "🤕", "🤢", "🤮", "🤧", "🥵", "🥶", "😵", "🤯", "😱", "😨", "😰", "😥",
                "�😢", "😭", "😓", "😞", "�", "😣", "😩", "😫", "🥱", "😤", "�😡", "😠"
            ),
            R.id.gridEmojiHands to listOf(
                "👋", "🤚", "🖐️", "✋", "�", "👌", "🤌", "🤏", "✌️", "🤞", "🤟", "🤘",
                "🤙", "👈", "👉", "👆", "🖕", "👇", "☝️", "�👍", "👎", "✊", "👊", "🤛",
                "🤜", "👏", "🙌", "👐", "🤲", "🤝", "🙏", "✍️", "�", "🦾", "🦿", "🦵",
                "🦶", "�", "🦻", "👃", "🧠", "🫀", "🫁", "🦷", "🦴", "👀", "👁️", "👅"
            ),
            R.id.gridEmojiHearts to listOf(
                "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❤️‍🔥", "❤️‍🩹",
                "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "☮️", "✝️", "☪️", "🕉️",
                "☸️", "✡️", "🔯", "🕎", "☯️", "☦️", "🛐", "⛎", "♈", "♉", "♊", "♋",
                "♌", "♍", "♎", "♏", "♐", "♑", "♒", "♓", "🆔", "⚛️", "🉑", "☢️"
            ),
            R.id.gridEmojiObjects to listOf(
                "🎉", "🎊", "🎈", "🎁", "🎀", "🎂", "🎄", "🎃", "🎆", "🎇", "🧨", "✨",
                "🎋", "🎍", "🎎", "🎏", "🎐", "🎑", "🧧", "🎗️", "🎟️", "🎫", "🎖️", "🏆",
                "🏅", "🥇", "🥈", "🥉", "⚽", "⚾", "🥎", "🏀", "🏐", "🏈", "🏉", "🎾",
                "🥏", "🎳", "🏏", "🏑", "🏒", "🥍", "🏓", "🏸", "🥊", "🥋", "🥅", "⛳",
                "⛸️", "🎣", "🤿", "🎽", "🎿", "🛷", "🥌", "🎯", "🪀", "🪁", "🔫", "🎱",
                "🔮", "🪄", "🎮", "🕹️", "🎰", "🎲", "🧩", "🧸", "🪅", "🪆", "🃏", "🀄"
            )
        )
        
        // Popola le griglie
        emojiCategories.forEach { (gridId, emojis) ->
            val grid = view.findViewById<android.widget.GridLayout>(gridId)
            emojis.forEach { emoji ->
                val button = android.widget.Button(this).apply {
                    text = emoji
                    textSize = 24f
                    setPadding(8, 8, 8, 8)
                    background = null
                    setOnClickListener {
                        // Inserisci emoji nel campo di testo invece di inviarla
                        insertEmojiInTextField(emoji)
                        bottomSheetDialog.dismiss()
                    }
                }
                grid.addView(button)
            }
        }
        
        // Pulsante Annulla
        view.findViewById<View>(R.id.btnCancelEmoji).setOnClickListener {
            bottomSheetDialog.dismiss()
        }
        
        bottomSheetDialog.show()
    }
    
    /**
     * Inserisce un'emoji nel campo di testo alla posizione del cursore
     */
    private fun insertEmojiInTextField(emoji: String) {
        val currentText = binding.etMessage.text.toString()
        val cursorPosition = binding.etMessage.selectionStart
        
        // Crea nuovo testo con emoji inserita alla posizione del cursore
        val newText = StringBuilder(currentText).insert(cursorPosition, emoji).toString()
        
        // Aggiorna il campo di testo
        binding.etMessage.setText(newText)
        
        // Posiziona il cursore dopo l'emoji inserita
        binding.etMessage.setSelection(cursorPosition + emoji.length)
        
        // Riporta focus sul campo di testo
        binding.etMessage.requestFocus()
    }
    
    /**
     * FASE 2.3 - Lancia la camera per scattare una foto
     */
    private fun launchCamera() {
        // Verifica permesso camera
        if (!PermissionManager.hasCameraPermission(this)) {
            val granted = PermissionManager.checkAndRequestCameraPermission(this)
            if (!granted) {
                Toast.makeText(this, "Permesso camera negato", Toast.LENGTH_SHORT).show()
                return
            }
        }
        
        try {
            // Crea file temporaneo per la foto
            val photoFile = createImageFile()
            
            // Genera URI con FileProvider
            currentPhotoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                photoFile
            )
            
            // Lancia camera
            cameraLauncher.launch(currentPhotoUri)
            Log.d("ChatActivity", "Camera launched with URI: $currentPhotoUri")
            
        } catch (e: Exception) {
            Log.e("ChatActivity", "Error launching camera", e)
            Toast.makeText(this, "Errore apertura camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * FASE 2.3 - Crea file temporaneo per foto camera
     */
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "CAMERA_${timeStamp}_"
        val storageDir = File(cacheDir, "camera")
        
        // Crea directory se non esiste
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        
        return File.createTempFile(
            imageFileName,  /* prefix */
            ".jpg",         /* suffix */
            storageDir      /* directory */
        )
    }
    
    /**
     * FASE 2.4 - Apre l'editor per modificare l'immagine prima di inviarla
     */
    private fun openImageEditor(imageUri: Uri) {
        Log.d("ChatActivity", "Opening image editor with URI: $imageUri")
        val intent = Intent(this, ImageAttachmentEditorActivity::class.java).apply {
            putExtra("image_uri", imageUri)
        }
        imageEditorLauncher.launch(intent)
    }
    
    /**
     * Invia messaggio multimediale
     */
    private fun sendMediaMessage(fileUri: Uri, mediaType: String) {
        // ✅ Verifica permessi per i gruppi
        if (!canSendInGroup()) {
            Toast.makeText(
                this,
                "Solo gli amministratori possono inviare media in questo gruppo",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        
        Log.d("ChatActivity", "sendMediaMessage called for mediaType=$mediaType")
        // Verifica se auto-delete è attivo
        lifecycleScope.launch {
            val securityManager = it.fabiodirauso.shutappchat.managers.SecuritySettingsManager.getInstance(this@ChatActivity)
            val isAutoDeleteEnabled = securityManager.isAutoDeleteMediaEnabled()
            
            Log.d("ChatActivity", "Auto-delete check: isAutoDeleteEnabled=$isAutoDeleteEnabled")
            
            // Mostra sempre il dialog per permettere l'inserimento del caption
            showSalvableChoiceDialog(fileUri, mediaType, isAutoDeleteEnabled)
        }
    }
    
    /**
     * Mostra dialog per scegliere se il media deve essere salvabile o protetto E aggiungere un caption
     */
    private fun showSalvableChoiceDialog(fileUri: Uri, mediaType: String, autoDeleteEnabled: Boolean = false) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Opzioni Media")
        
        // Layout con EditText per caption
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(50, 20, 50, 10)
        
        // EditText per caption
        val captionInput = android.widget.EditText(this)
        captionInput.hint = "Aggiungi un commento (opzionale)"
        captionInput.maxLines = 3
        layout.addView(captionInput)
        
        builder.setView(layout)
        
        if (autoDeleteEnabled) {
            // Auto-delete attivo: mostra solo messaggio informativo e pulsante Invia
            builder.setMessage("🔒 Auto-delete attivo: il media sarà protetto e auto-cancellato")
            
            builder.setPositiveButton("Invia") { _, _ ->
                val caption = captionInput.text.toString().trim().takeIf { it.isNotEmpty() }
                performMediaUpload(fileUri, mediaType, salvable = false, caption = caption)
            }
            
            builder.setNegativeButton("Annulla", null)
        } else {
            // Auto-delete disattivo: mostra scelta salvabile/protetto
            builder.setMessage("Vuoi permettere al destinatario di salvare questo ${getMediaTypeName(mediaType)}?")
            
            builder.setPositiveButton("Sì, salvabile") { _, _ ->
                val caption = captionInput.text.toString().trim().takeIf { it.isNotEmpty() }
                performMediaUpload(fileUri, mediaType, salvable = true, caption = caption)
            }
            
            builder.setNegativeButton("No, protetto 🔒") { _, _ ->
                val caption = captionInput.text.toString().trim().takeIf { it.isNotEmpty() }
                performMediaUpload(fileUri, mediaType, salvable = false, caption = caption)
            }
            
            builder.setNeutralButton("Annulla", null)
        }
        
        builder.show()
    }
    
    /**
     * Restituisce il nome leggibile del tipo di media
     */
    private fun getMediaTypeName(mediaType: String): String {
        return when (mediaType) {
            "image" -> "immagine"
            "video" -> "video"
            "document" -> "documento"
            else -> "file"
        }
    }
    
    /**
     * Esegue l'upload del media con la scelta di salvable e caption opzionale
     */
    private fun performMediaUpload(fileUri: Uri, mediaType: String, salvable: Boolean, caption: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {  // 🎯 FIX: Usa IO dispatcher per non bloccare UI
            try {
                // Mostra progress
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.VISIBLE
                    Toast.makeText(this@ChatActivity, "Caricamento allegato...", Toast.LENGTH_SHORT).show()
                }
                
                // Ottieni info file
                val fileName = getFileName(fileUri)
                var mimeType = getMimeType(fileUri)
                
                // Fix MIME type per camera photos (FileProvider non restituisce il tipo corretto)
                if (mimeType == "application/octet-stream" && mediaType == "image") {
                    mimeType = "image/jpeg"
                    Log.d("ChatActivity", "Fixed MIME type from octet-stream to image/jpeg")
                }
                
                // Converte Uri in File
                val file = getFileFromUri(fileUri)
                if (file == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ChatActivity, "Errore lettura file", Toast.LENGTH_SHORT).show()
                        binding.progressBar.visibility = View.GONE
                    }
                    return@launch
                }
                
                // Upload media criptato con progress
                Log.d("ChatActivity", "Uploading media to contactUsername='$contactUsername', contactId=$contactId, salvable=$salvable")
                val uploadResult = chatMediaService.uploadChatMedia(
                    file = file,
                    receiverUsername = contactId.toString(),  // Use ID instead of username (more reliable)
                    mimeType = mimeType,  // Pass resolved MIME type
                    salvable = salvable,  // Usa la scelta dell'utente
                    onProgress = { progress ->
                        // Aggiorna UI progress se necessario
                        Log.d("ChatActivity", "Upload progress: ${(progress * 100).toInt()}%")
                    }
                )
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                }
                
                if (uploadResult != null) {
                    // ❌ NON generare thumbnail durante upload - troppo lento e blocca tutto!
                    // Il thumbnail verrà generato quando il ricevente SCARICA il video
                    // e mostreremo un loader con % durante il download
                    
                    // ✅ v1.2.3: Use seconds-based timestamp for consistency with server
                    val unixSeconds = System.currentTimeMillis() / 1000
                    
                    // Crea e salva messaggio nel database
                    val message = Message.create(
                        id = System.currentTimeMillis().toString(),
                        senderId = sharedPreferences.getLong("user_id", 0),
                        recipientId = contactId,
                        content = uploadResult.mediaId,
                        messageType = when (mediaType) {
                            "image" -> MessageType.IMAGE
                            "video" -> MessageType.VIDEO
                            else -> MessageType.DOCUMENT
                        },
                        timestampLong = unixSeconds * 1000, // Convert to milliseconds
                        conversationId = conversationId,
                        mediaId = uploadResult.mediaId,
                        mediaKey = uploadResult.encryptedKey,
                        mediaIv = uploadResult.iv,
                        filename = uploadResult.filename,
                        mime = uploadResult.mimeType,
                        size = uploadResult.size,
                        thumbnail = null,  // ❌ Non generiamo più thumbnail durante upload
                        mediaSalvable = uploadResult.salvable,
                        caption = caption  // Aggiungi caption
                    )
                    
                    // Salva nel database (l'observer aggiornerà automaticamente la UI)
                    database.messageDao().insertMessage(message)
                    
                    // Invia notifica al destinatario tramite API
                    // Usa contactId invece di contactUsername per evitare problemi con username errati nel DB locale
                    messageSender.sendMediaNotification(
                        recipientUsername = contactId.toString(), // Server accetta sia username che ID
                        mediaId = uploadResult.mediaId,
                        encryptedKey = uploadResult.encryptedKey,
                        iv = uploadResult.iv,
                        filename = uploadResult.filename,
                        mimeType = uploadResult.mimeType,
                        size = uploadResult.size,
                        salvable = uploadResult.salvable,
                        senderAutoDelete = uploadResult.senderAutoDelete,
                        caption = caption,
                        thumbnail = null  // ❌ Non inviamo più thumbnail (verrà generato dal ricevente)
                    )
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ChatActivity, "Allegato inviato!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ChatActivity, "Errore invio allegato", Toast.LENGTH_LONG).show()
                    }
                }
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@ChatActivity, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
                }
                Log.e("ChatActivity", "Error sending media", e)
            }
        }
    }
    
    /**
     * Invia messaggio emoji
     */
    private fun sendEmojiMessage(emoji: String) {
        // Le emoji sono semplicemente caratteri Unicode - invia come testo normale!
        sendTextMessage(emoji)
    }
    
    /**
     * Gestisce il click su un messaggio media per scaricarlo
     */
    private fun handleMediaMessageClick(message: Message) {
        if (message.mediaId == null || message.mediaKey.isNullOrEmpty() || message.mediaIv.isNullOrEmpty()) {
            Toast.makeText(this, "Dati media non validi", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Apri MediaViewerActivity per visualizzare a schermo intero
        val intent = Intent(this, MediaViewerActivity::class.java).apply {
            putExtra(MediaViewerActivity.EXTRA_MEDIA_ID, message.mediaId)
            putExtra(MediaViewerActivity.EXTRA_ENCRYPTED_KEY, message.mediaKey)
            putExtra(MediaViewerActivity.EXTRA_IV, message.mediaIv)
            putExtra(MediaViewerActivity.EXTRA_FILENAME, message.filename ?: "Immagine")
            putExtra(MediaViewerActivity.EXTRA_MIME_TYPE, message.mime)
            putExtra(MediaViewerActivity.EXTRA_SALVABLE, message.mediaSalvable ?: true)
            putExtra(MediaViewerActivity.EXTRA_SENDER_AUTO_DELETE, message.senderAutoDelete ?: false)
            putExtra(MediaViewerActivity.EXTRA_MESSAGE_ID, message.id)
        }
        startActivity(intent)
    }
    
    /**
     * Invia un messaggio di testo
     */
    /**
     * Resolve the correct username for API calls
     * This handles cases where local database has incorrect username
     */
    private suspend fun getCorrectUsername(): String {
        // If we have a valid contactId, try to get username from server
        if (contactId > 0) {
            try {
                val response = RetrofitClient.apiService.getUserProfile(contactUsername)
                if (response.isSuccessful && response.body()?.success == true) {
                    return response.body()?.user?.username ?: contactUsername
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatActivity", "Failed to resolve username for contactId $contactId", e)
            }
        }
        
        // Fallback to contactUsername (even if potentially incorrect)
        return contactUsername
    }

    private fun sendTextMessage(content: String) {
        // ✅ Verifica permessi per i gruppi
        if (!canSendInGroup()) {
            Toast.makeText(
                this,
                "Solo gli amministratori possono inviare messaggi in questo gruppo",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                // Use contactId as string - server will try to resolve it
                val recipientIdentifier = if (isGroupChat) {
                    groupId!!
                } else {
                    contactId.toString()
                }
                
                Log.d("ChatActivity", "Sending message to ${if (isGroupChat) "group" else "user"}: $recipientIdentifier")
                
                // ✅ Prepara i dati della reply se presente
                val replyData = replyToMessage?.let {
                    Triple(it.id, it.content, it.senderId)
                }
                
                // Send via REST API - this will save to pending_messages
                // and the Go server will deliver via WebSocket if recipient is online
                messageSender.sendTextMessage(
                    messageText = content,
                    recipientUsername = recipientIdentifier,
                    replyToMessageId = replyData?.first,
                    replyToContent = replyData?.second,
                    replyToSenderId = replyData?.third
                )
                
                Log.d("ChatActivity", "Message sent to pending_messages via API")
                
                // ✅ v1.2.3: Use seconds-based timestamp for consistency with server
                val unixSeconds = System.currentTimeMillis() / 1000
                
                // Add to local list immediately for UI feedback
                val textMessage = Message.create(
                    id = System.currentTimeMillis().toString(),
                    senderId = sharedPreferences.getLong("user_id", 0),
                    recipientId = if (isGroupChat) 0 else contactId, // 0 per gruppi
                    content = content,
                    messageType = MessageType.TEXT,
                    timestampLong = unixSeconds * 1000, // Convert back to milliseconds
                    replyToMessageId = replyData?.first,
                    replyToContent = replyData?.second,
                    replyToSenderId = replyData?.third
                )
                addMessageToList(textMessage)
                Log.d("ChatActivity", "Message added to local list")
                
                // ✅ Pulisci la reply dopo l'invio
                cancelReply()
                
            } catch (e: Exception) {
                Log.e("ChatActivity", "Failed to send message", e)
                Toast.makeText(this@ChatActivity, "Errore invio messaggio: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    


    
    private fun addMessageToList(message: Message, saveToDatabase: Boolean = true) {
        messages.add(message)
        messageAdapter.updateMessages(messages)
        binding.rvMessages.scrollToPosition(messages.size - 1)
        
        // Salva nel database locale solo se richiesto (evita duplicati quando carichiamo dal DB)
        if (saveToDatabase) {
            saveMessageToDatabase(message)
        }
    }
    
    /**
     * Invia messaggio multimediale via WebSocket
     */
    private fun sendMediaWebSocketMessage(mediaPayload: MediaMessagePayload, mediaType: String) {
        val payloadJson = gson.toJson(mediaPayload)
        sendWebSocketMessage("media_msg", payloadJson)
        // Il messaggio è già stato salvato nel DB da sendMediaMessage()
        // L'observer si occuperà di mostrarlo nella UI
    }
    
    /**
     * Invia messaggio generico via WebSocket
     */
    private fun sendWebSocketMessage(type: String, payload: String) {
        lifecycleScope.launch {
            try {
                val username = sharedPreferences.getString("username", "")
                if (username.isNullOrEmpty()) {
                    Toast.makeText(this@ChatActivity, "Username non trovato", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                // Crea envelope WebSocket secondo il protocollo
                val envelope = mapOf(
                    "v" to 1,
                    "type" to type,
                    "id" to java.util.UUID.randomUUID().toString(),
                    "from" to username,
                    "to" to contactUsername,
                    "ts" to (System.currentTimeMillis() / 1000),
                    "payload" to payload
                )
                
                val messageJson = gson.toJson(envelope)
                // TODO: Send raw message via WebSocket when service binding is implemented
                // webSocketService?.getWebSocketClient()?.sendRawMessage(messageJson)
                Log.d("ChatActivity", "WebSocket message prepared: $type")
                
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "Errore invio: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Utility functions per file
     */
    private fun getFileName(uri: Uri): String {
        var result = "unknown_file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    result = cursor.getString(nameIndex)
                }
            }
        }
        return result
    }
    
    private fun getMimeType(uri: Uri): String {
        return contentResolver.getType(uri) ?: "application/octet-stream"
    }
    
    private fun getFileSize(uri: Uri): Long {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.available().toLong()
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun getFileFromUri(uri: Uri): java.io.File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val tempFile = java.io.File.createTempFile("upload_", ".tmp", cacheDir)
            inputStream?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            null
        }
    }
    
    override fun onResume() {
        super.onResume()
        // WebSocket is managed by WebSocketService
        // Mark messages as read when user returns to this chat
        markMessagesAsRead()
    }
    
    override fun onPause() {
        super.onPause()
        // WebSocket is managed by WebSocketService
    }
    
    /**
     * Mark all messages in this conversation as read
     */
    private fun markMessagesAsRead() {
        lifecycleScope.launch {
            val userId = sharedPreferences.getLong("user_id", 0)
            database.messageDao().markConversationAsRead(conversationId, userId)
            
            // Update conversation unread count
            val conversation = database.conversationDao().getConversationById(conversationId)
            conversation?.let {
                database.conversationDao().updateConversation(it.copy(unreadCount = 0))
            }
            
            Log.d("ChatActivity", "Marked all messages as read for conversation: $conversationId")
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
    
    /**
     * Genera un ID conversazione univoco basato sui due user ID
     */
    private fun generateConversationId(userId1: Long, userId2: Long): String {
        val sortedIds = listOf(userId1, userId2).sorted()
        return "conv_${sortedIds[0]}_${sortedIds[1]}"
    }
    
    /**
     * Crea o aggiorna la conversazione nel database
     */
    private fun createOrUpdateConversation() {
        lifecycleScope.launch {
            try {
                val existingConversation = database.conversationDao().getConversationById(conversationId)
                
                if (existingConversation == null) {
                    // Crea nuova conversazione
                    val conversation = ConversationEntity(
                        id = conversationId,
                        participantId = contactId.toString(),
                        participantName = contactName,
                        profilePictureId = profilePictureId,
                        lastMessage = null,
                        lastMessageTime = Date(),
                        unreadCount = 0,
                        isGroup = false
                    )
                    database.conversationDao().insertConversation(conversation)
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatActivity", "Error creating conversation", e)
            }
        }
    }
    
    /**
     * Salva un messaggio nel database locale
     */
    private fun saveMessageToDatabase(message: Message) {
        lifecycleScope.launch {
            try {
                val messageWithConversation = message.copy(conversationId = conversationId)
                database.messageDao().insertMessage(messageWithConversation)
                
                // Aggiorna anche la conversazione con l'ultimo messaggio
                updateConversationLastMessage(message)
            } catch (e: Exception) {
                android.util.Log.e("ChatActivity", "Error saving message to database", e)
            }
        }
    }
    
    /**
     * Aggiorna l'ultimo messaggio nella conversazione
     */
    private fun updateConversationLastMessage(message: Message) {
        lifecycleScope.launch {
            try {
                val conversation = database.conversationDao().getConversationById(conversationId)
                conversation?.let {
                    // ✅ v1.2.4: Use MessageFormatter per formattare il preview in base al tipo
                    val updatedConversation = it.copy(
                        lastMessage = MessageFormatter.getLastMessagePreview(this@ChatActivity, message),
                        lastMessageTime = message.timestamp
                    )
                    database.conversationDao().updateConversation(updatedConversation)
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatActivity", "Error updating conversation", e)
            }
        }
    }
    
    /**
     * Configura avatar e informazioni utente nella toolbar
     */
    /**
     * ✅ Carica le informazioni del gruppo e verifica i permessi dell'utente
     */
    private fun loadGroupInfo() {
        lifecycleScope.launch {
            try {
                val userId = sharedPreferences.getLong("user_id", 0)
                
                withContext(Dispatchers.IO) {
                    // Carica i dati del gruppo
                    val group = database.groupDao().getGroupById(groupId!!)
                    if (group != null) {
                        groupMode = group.groupMode
                        
                        // Verifica se l'utente è admin del gruppo
                        isGroupAdmin = database.groupMemberDao().isAdmin(groupId!!, userId)
                        
                        Log.d("ChatActivity", "Group loaded: ${group.groupName}, mode=$groupMode, isAdmin=$isGroupAdmin")
                    } else {
                        Log.e("ChatActivity", "Group not found: $groupId")
                    }
                }
                
                // Aggiorna UI in base ai permessi
                updateGroupPermissionsUI()
                
            } catch (e: Exception) {
                Log.e("ChatActivity", "Error loading group info", e)
                Toast.makeText(this@ChatActivity, "Errore caricamento gruppo", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * ✅ Aggiorna l'UI in base ai permessi del gruppo
     */
    private fun updateGroupPermissionsUI() {
        // Se il gruppo è RESTRICTED e l'utente non è admin, disabilita l'invio
        val canSendMessages = when (groupMode) {
            it.fabiodirauso.shutappchat.model.GroupMode.OPEN -> true
            it.fabiodirauso.shutappchat.model.GroupMode.RESTRICTED -> isGroupAdmin
        }
        
        if (!canSendMessages) {
            binding.etMessage.isEnabled = false
            binding.btnSend.isEnabled = false
            binding.buttonAttachment?.isEnabled = false
            binding.etMessage.hint = "Solo gli admin possono scrivere"
            
            Toast.makeText(
                this,
                "Questo gruppo è in modalità limitata. Solo gli amministratori possono inviare messaggi.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    /**
     * ✅ Verifica se l'utente può inviare messaggi nel gruppo
     */
    private fun canSendInGroup(): Boolean {
        if (!isGroupChat) return true // Chat normale, sempre permesso
        
        return when (groupMode) {
            it.fabiodirauso.shutappchat.model.GroupMode.OPEN -> true
            it.fabiodirauso.shutappchat.model.GroupMode.RESTRICTED -> isGroupAdmin
        }
    }
    
    private fun setupToolbarUserInfo() {
        // Trova gli elementi nella toolbar
        val avatarImageView = findViewById<ImageView>(R.id.imageViewToolbarUserAvatar)
        val userNameTextView = findViewById<TextView>(R.id.textViewToolbarUserName)

        
        // Imposta il nome utente
        userNameTextView.text = contactName
        
        // Imposta lo status offline di default - sarà aggiornato dopo l'inizializzazione WebSocket

        
        // Carica l'avatar se disponibile
        val authToken = sharedPreferences.getString("auth_token", "") ?: ""
        
        if (!profilePictureId.isNullOrEmpty() && authToken.isNotEmpty()) {
            AvatarHelper.loadUserAvatar(
                context = this,
                imageView = avatarImageView,
                username = contactUsername,
                userToken = authToken,
                profilePictureId = profilePictureId,
                lifecycleScope = lifecycleScope
            )
        } else {
            // Mostra avatar con iniziali se non c'è foto profilo
            val initialsAvatar = AvatarHelper.generateInitialsAvatar(contactName)
            avatarImageView.setImageBitmap(initialsAvatar)
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            PermissionManager.RequestCodes.READ_MEDIA_IMAGES -> {
                if (PermissionManager.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
                    // Permesso concesso - l'utente dovrà selezionare di nuovo dal menu
                    Toast.makeText(this, "Permesso concesso. Riprova a selezionare il file.", Toast.LENGTH_SHORT).show()
                } else {
                    handlePermissionDenied(PermissionManager.PermissionType.MEDIA_IMAGES)
                }
            }
            
            PermissionManager.RequestCodes.READ_MEDIA_VIDEO -> {
                if (PermissionManager.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
                    Toast.makeText(this, "Permesso concesso. Riprova a selezionare il file.", Toast.LENGTH_SHORT).show()
                } else {
                    handlePermissionDenied(PermissionManager.PermissionType.MEDIA_VIDEO)
                }
            }
        }
    }
    
    private fun handlePermissionDenied(permissionType: PermissionManager.PermissionType) {
        if (!PermissionManager.shouldShowRationale(this, permissionType)) {
            // Negato permanentemente
            PermissionDialogHelper.showPermissionDeniedDialog(
                activity = this,
                permissionType = permissionType
            )
        } else {
            Toast.makeText(
                this,
                "Permesso necessario per selezionare file",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ===================== ELIMINAZIONE MESSAGGI =====================
    
    /**
     * ✅ NUOVO: Gestisce la risposta ad un messaggio
     */
    private fun handleReplyToMessage(message: Message) {
        replyToMessage = message
        
        // Mostra la preview del messaggio
        val replyContainer = findViewById<View>(R.id.replyPreviewContainer)
        val senderNameView = findViewById<TextView>(R.id.textViewReplySenderName)
        val contentView = findViewById<TextView>(R.id.textViewReplyContent)
        
        // Imposta il nome del mittente
        val userId = sharedPreferences.getLong("user_id", 0)
        senderNameView?.text = if (message.senderId == userId) "Tu" else contactName
        
        // Imposta il contenuto (adattato in base al tipo)
        contentView?.text = when (message.messageType) {
            MessageType.TEXT -> message.content
            MessageType.IMAGE -> "📷 Immagine"
            MessageType.VIDEO -> "🎥 Video"
            MessageType.AUDIO -> "🎵 Audio"
            MessageType.DOCUMENT -> "📄 ${message.filename ?: "Documento"}"
            MessageType.EMOJI -> message.content
            else -> message.content
        }
        
        // Mostra il container
        replyContainer?.visibility = View.VISIBLE
        
        // Focus sull'EditText
        binding.etMessage.requestFocus()
    }
    
    /**
     * ✅ Annulla la risposta ad un messaggio
     */
    private fun cancelReply() {
        replyToMessage = null
        findViewById<View>(R.id.replyPreviewContainer)?.visibility = View.GONE
    }
    
    /**
     * ✅ NUOVO: Gestisce l'inoltro di un messaggio
     */
    private fun handleForwardMessage(message: Message) {
        lifecycleScope.launch {
            try {
                // Carica la lista delle conversazioni (contatti)
                val conversations = withContext(Dispatchers.IO) {
                    database.conversationDao().getAllConversations()
                }
                
                // Raccogli i contatti dal Flow (prendi solo il primo snapshot)
                conversations.collect { contactsList ->
                    // Filtra per escludere la conversazione corrente
                    val availableContacts = contactsList.filter { 
                        it.participantId != contactId.toString() 
                    }
                    
                    if (availableContacts.isEmpty()) {
                        Toast.makeText(
                            this@ChatActivity,
                            "Nessun contatto disponibile per inoltrare",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@collect
                    }
                    
                    // Mostra dialog di selezione contatti
                    showForwardDialog(message, availableContacts)
                    
                    // Stop collecting dopo il primo emit
                    return@collect
                }
                
            } catch (e: Exception) {
                Log.e("ChatActivity", "Errore caricamento contatti per inoltrare", e)
                Toast.makeText(this@ChatActivity, "Errore caricamento contatti", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * ✅ Mostra dialog per selezionare contatti a cui inoltrare il messaggio
     */
    private fun showForwardDialog(message: Message, contacts: List<it.fabiodirauso.shutappchat.model.ConversationEntity>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_forward_message, null)
        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewContacts)
        
        val selectedContacts = mutableSetOf<String>()
        
        // Adapter inline semplificato
        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            inner class ContactViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
                val checkbox: android.widget.CheckBox = view.findViewById(R.id.checkBoxContact)
                val nameView: TextView = view.findViewById(R.id.textViewContactName)
                val usernameView: TextView = view.findViewById(R.id.textViewContactUsername)
                val avatarView: ImageView = view.findViewById(R.id.imageViewContactAvatar)
            }
            
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val view = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_forward_contact, parent, false)
                return ContactViewHolder(view)
            }
            
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val contact = contacts[position]
                val vh = holder as ContactViewHolder
                
                vh.nameView.text = contact.participantName
                vh.usernameView.text = contact.participantId
                
                // Usa avatar di default per ora
                vh.avatarView.setImageResource(R.drawable.ic_person)
                
                vh.checkbox.isChecked = selectedContacts.contains(contact.participantId)
                
                holder.itemView.setOnClickListener {
                    vh.checkbox.isChecked = !vh.checkbox.isChecked
                    if (vh.checkbox.isChecked) {
                        selectedContacts.add(contact.participantId)
                    } else {
                        selectedContacts.remove(contact.participantId)
                    }
                }
                
                vh.checkbox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedContacts.add(contact.participantId)
                    } else {
                        selectedContacts.remove(contact.participantId)
                    }
                }
            }
            
            override fun getItemCount() = contacts.size
        }
        
        recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        recyclerView.adapter = adapter
        
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        dialogView.findViewById<android.widget.Button>(R.id.buttonCancel).setOnClickListener {
            dialog.dismiss()
        }
        
        dialogView.findViewById<android.widget.Button>(R.id.buttonForward).setOnClickListener {
            if (selectedContacts.isEmpty()) {
                Toast.makeText(this, "Seleziona almeno un contatto", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // Inoltra il messaggio ai contatti selezionati
            forwardMessageToContacts(message, selectedContacts.toList())
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    /**
     * ✅ Inoltra il messaggio ai contatti selezionati
     */
    private fun forwardMessageToContacts(message: Message, recipientIds: List<String>) {
        lifecycleScope.launch {
            try {
                var successCount = 0
                var failCount = 0
                
                for (recipientId in recipientIds) {
                    try {
                        // Invia il messaggio (solo testo per ora)
                        when (message.messageType) {
                            MessageType.TEXT -> {
                                messageSender.sendTextMessage(
                                    messageText = message.content,
                                    recipientUsername = recipientId
                                )
                            }
                            else -> {
                                // Media forward non supportato per ora
                                Log.w("ChatActivity", "Forward di media non ancora supportato")
                                failCount++
                                continue
                            }
                        }
                        successCount++
                    } catch (e: Exception) {
                        Log.e("ChatActivity", "Errore inoltrando messaggio a $recipientId", e)
                        failCount++
                    }
                }
                
                // Mostra risultato
                val resultMessage = when {
                    failCount == 0 -> "Messaggio inoltrato a $successCount contatt${if (successCount == 1) "o" else "i"}"
                    successCount == 0 -> "Errore: impossibile inoltrare il messaggio"
                    else -> "Inoltrato a $successCount, falliti $failCount"
                }
                
                Toast.makeText(this@ChatActivity, resultMessage, Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                Log.e("ChatActivity", "Errore durante inoltro messaggi", e)
                Toast.makeText(this@ChatActivity, "Errore durante l'inoltro", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Mostra popup di conferma per eliminare un singolo messaggio
     */
    private fun showDeleteMessageDialog(message: Message) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Elimina messaggio")
            .setMessage("Vuoi eliminare questo messaggio? L'azione è irreversibile.")
            .setPositiveButton("Elimina") { _, _ ->
                deleteMessage(message)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
    
    /**
     * Elimina un singolo messaggio dal database
     */
    private fun deleteMessage(message: Message) {
        lifecycleScope.launch {
            try {
                val database = AppDatabase.getDatabase(this@ChatActivity)
                
                // Elimina dal database
                withContext(Dispatchers.IO) {
                    database.messageDao().deleteMessage(message)
                }
                
                Toast.makeText(
                    this@ChatActivity,
                    "Messaggio eliminato",
                    Toast.LENGTH_SHORT
                ).show()
                
                Log.d("ChatActivity", "✅ Messaggio ${message.id} eliminato con successo")
                
            } catch (e: Exception) {
                Log.e("ChatActivity", "❌ Errore eliminazione messaggio", e)
                Toast.makeText(
                    this@ChatActivity,
                    "Errore durante l'eliminazione",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    /**
     * Mostra popup di conferma per eliminare l'intera chat
     */
    private fun showDeleteChatDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Elimina chat")
            .setMessage("Vuoi eliminare l'intera conversazione? Tutti i messaggi verranno cancellati in modo permanente.")
            .setPositiveButton("Elimina tutto") { _, _ ->
                deleteEntireChat()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
    
    /**
     * Elimina l'intera chat (tutti i messaggi + conversazione)
     */
    private fun deleteEntireChat() {
        lifecycleScope.launch {
            try {
                val database = AppDatabase.getDatabase(this@ChatActivity)
                
                withContext(Dispatchers.IO) {
                    // 1. Elimina tutti i messaggi della conversazione
                    database.messageDao().deleteAllMessagesInConversation(conversationId)
                    
                    // 2. Elimina la conversazione stessa
                    val conversation = database.conversationDao()
                        .getConversationById(conversationId)
                    
                    if (conversation != null) {
                        database.conversationDao().deleteConversation(conversation)
                    }
                }
                
                Toast.makeText(
                    this@ChatActivity,
                    "Chat eliminata",
                    Toast.LENGTH_SHORT
                ).show()
                
                Log.d("ChatActivity", "✅ Chat eliminata con successo")
                
                // Chiudi l'activity e torna alla lista chat
                finish()
                
            } catch (e: Exception) {
                Log.e("ChatActivity", "❌ Errore eliminazione chat", e)
                Toast.makeText(
                    this@ChatActivity,
                    "Errore durante l'eliminazione della chat",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    

    

    
}
