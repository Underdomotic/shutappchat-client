package it.fabiodirauso.shutappchat

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import it.fabiodirauso.shutappchat.adapter.GroupMembersAdapter
import it.fabiodirauso.shutappchat.api.MediaInitRequest
import it.fabiodirauso.shutappchat.managers.GroupRepository
import it.fabiodirauso.shutappchat.model.GroupEntity
import it.fabiodirauso.shutappchat.model.GroupMemberEntity
import it.fabiodirauso.shutappchat.model.GroupMode
import it.fabiodirauso.shutappchat.model.GroupRole
import it.fabiodirauso.shutappchat.network.RetrofitClient
import it.fabiodirauso.shutappchat.utils.AvatarHelper
import it.fabiodirauso.shutappchat.utils.PermissionManager
import it.fabiodirauso.shutappchat.utils.PermissionDialogHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLConnection

/**
 * Activity per gestire le impostazioni del gruppo
 * - Modifica nome/descrizione/modalit
 * - Gestione membri (aggiungi/rimuovi/cambia ruolo)
 * - Elimina gruppo
 */
class GroupSettingsActivity : AppCompatActivity() {
    
    private lateinit var groupRepository: GroupRepository
    private lateinit var membersAdapter: GroupMembersAdapter
    
    private var groupId: String = ""
    private var group: GroupEntity? = null
    private var members: List<GroupMemberEntity> = emptyList()
    private var currentUserId: Long = 0
    private var isUserAdmin: Boolean = false
    private var currentToken: String = ""
    
    private lateinit var imageViewGroupPicture: ImageView
    private lateinit var fabEditGroupPicture: FloatingActionButton
    private lateinit var editTextGroupName: EditText
    private lateinit var editTextGroupDescription: EditText
    private lateinit var switchGroupMode: SwitchMaterial
    private lateinit var recyclerViewMembers: RecyclerView
    private lateinit var buttonSaveChanges: Button
    private lateinit var buttonDeleteGroup: Button
    private lateinit var buttonLeaveGroup: Button
    private lateinit var buttonAddMembers: Button
    
    // Image picker launcher
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { imageUri ->
                lifecycleScope.launch {
                    val tempFile = copyUriToTempFile(imageUri)
                    if (tempFile != null) {
                        openImageEditor(Uri.fromFile(tempFile))
                    } else {
                        Toast.makeText(this@GroupSettingsActivity, "Errore nella copia dell'immagine", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val imageEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val path = result.data?.getStringExtra(ProfileImageEditorActivity.EXTRA_RESULT_PATH)
            if (!path.isNullOrEmpty()) {
                val file = File(path)
                if (file.exists()) {
                    uploadGroupPicture(Uri.fromFile(file))
                } else {
                    Toast.makeText(this, "File elaborato non trovato", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private val groupUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "it.fabiodirauso.shutappchat.GROUP_UPDATED" -> {
                    val updatedGroupId = intent.getStringExtra("group_id")
                    if (updatedGroupId == groupId) {
                        Log.d("GroupSettings", "Group updated event received, refreshing...")
                        lifecycleScope.launch {
                            groupRepository.refreshGroupMembers(groupId)
                            groupRepository.refreshGroupInfo(groupId)
                        }
                    }
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContentView(R.layout.activity_group_settings)
        
        // Enable fullscreen immersive mode
        it.fabiodirauso.shutappchat.utils.UIHelper.enableImmersiveMode(this)
        
        groupId = intent.getStringExtra(EXTRA_GROUP_ID) ?: run {
            Toast.makeText(this, "Errore: ID gruppo mancante", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        groupRepository = GroupRepository.getInstance(this)
        currentUserId = getSharedPreferences("app_prefs", MODE_PRIVATE).getLong("user_id", 0)
        
        setupAuth()
        setupToolbar()
        setupViews()
        setupRecyclerView()
        setupClickListeners()
        
        loadGroupData()
        loadMembers()
    }
    
    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("it.fabiodirauso.shutappchat.GROUP_UPDATED")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(groupUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(groupUpdateReceiver, filter)
        }
    }
    
    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(groupUpdateReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }
    
    private fun setupToolbar() {
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Impostazioni Gruppo"
    }
    
    private fun setupAuth() {
        val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        currentToken = sharedPreferences.getString("auth_token", "") ?: ""
        RetrofitClient.setAuthToken(currentToken)
    }
    
    private fun setupViews() {
        imageViewGroupPicture = findViewById(R.id.imageViewGroupPicture)
        fabEditGroupPicture = findViewById(R.id.fabEditGroupPicture)
        editTextGroupName = findViewById(R.id.editTextGroupName)
        editTextGroupDescription = findViewById(R.id.editTextGroupDescription)
        switchGroupMode = findViewById(R.id.switchGroupMode)
        recyclerViewMembers = findViewById(R.id.recyclerViewMembers)
        buttonSaveChanges = findViewById(R.id.buttonSaveChanges)
        buttonDeleteGroup = findViewById(R.id.buttonDeleteGroup)
        buttonLeaveGroup = findViewById(R.id.buttonLeaveGroup)
        buttonAddMembers = findViewById(R.id.buttonAddMembers)
    }
    
    private fun setupRecyclerView() {
        membersAdapter = GroupMembersAdapter(
            currentUserId = currentUserId,
            isCurrentUserAdmin = false, // Will be updated
            onRemoveMember = { member -> removeMember(member) },
            onChangeRole = { member -> changeMemberRole(member) }
        )
        
        recyclerViewMembers.apply {
            layoutManager = LinearLayoutManager(this@GroupSettingsActivity)
            adapter = membersAdapter
        }
    }
    
    private fun setupClickListeners() {
        fabEditGroupPicture.setOnClickListener {
            if (isUserAdmin) {
                openImagePicker()
            } else {
                Toast.makeText(this, "Solo gli admin possono modificare l'immagine del gruppo", Toast.LENGTH_SHORT).show()
            }
        }
        
        buttonSaveChanges.setOnClickListener {
            saveChanges()
        }
        
        buttonAddMembers.setOnClickListener {
            showAddMembersDialog()
        }
        
        buttonDeleteGroup.setOnClickListener {
            showDeleteGroupDialog()
        }
        
        buttonLeaveGroup.setOnClickListener {
            showLeaveGroupDialog()
        }
    }
    
    
    
    private fun loadGroupData() {
        // Prima forza un refresh immediato per caricare i dati
        lifecycleScope.launch {
            try {
                groupRepository.refreshGroupInfo(groupId)
            } catch (e: Exception) {
                Log.e("GroupSettings", "Error loading group info", e)
            }
        }
        
        // Poi osserva i cambiamenti dal database locale
        lifecycleScope.launch {
            groupRepository.observeGroup(groupId).collectLatest { groupEntity ->
                group = groupEntity
                updateUI()
            }
        }
    }
    
    private fun loadMembers() {
        // Prima forza un refresh immediato per caricare i membri
        lifecycleScope.launch {
            try {
                groupRepository.refreshGroupMembers(groupId)
            } catch (e: Exception) {
                Log.e("GroupSettings", "Error loading members", e)
            }
        }
        
        // Poi osserva i cambiamenti dal database locale
        lifecycleScope.launch {
            groupRepository.observeGroupMembers(groupId).collectLatest { membersList ->
                members = membersList
                
                // Check if current user is admin
                val currentMember = membersList.find { it.userId == currentUserId }
                isUserAdmin = currentMember?.role == GroupRole.ADMIN
                
                // Update adapter
                membersAdapter.updateMembers(membersList)
                membersAdapter.updateAdminStatus(isUserAdmin)
                
                // Update UI permissions
                updatePermissions()
            }
        }
    }
    
    private fun updateUI() {
        group?.let {
            editTextGroupName.setText(it.groupName)
            editTextGroupDescription.setText(it.groupDescription ?: "")
            switchGroupMode.isChecked = (it.groupMode == GroupMode.RESTRICTED)
            
            // Load group picture
            AvatarHelper.loadGroupAvatar(
                context = this,
                imageView = imageViewGroupPicture,
                groupName = it.groupName,
                groupPictureId = it.groupPictureId,
                lifecycleScope = lifecycleScope
            )
        }
    }
    
    private fun updatePermissions() {
        val editable = isUserAdmin
        
        editTextGroupName.isEnabled = editable
        editTextGroupDescription.isEnabled = editable
        switchGroupMode.isEnabled = editable
        buttonSaveChanges.isEnabled = editable
        buttonSaveChanges.visibility = if (editable) View.VISIBLE else View.GONE
        buttonDeleteGroup.visibility = if (editable) View.VISIBLE else View.GONE
        buttonLeaveGroup.visibility = if (!editable) View.VISIBLE else View.GONE
        buttonAddMembers.isEnabled = editable
        buttonAddMembers.visibility = if (editable) View.VISIBLE else View.GONE
        
        // Update adapter admin status
        membersAdapter.updateAdminStatus(editable)
    }
    
    private fun saveChanges() {
        if (!isUserAdmin) {
            Toast.makeText(this, "Solo gli admin possono modificare", Toast.LENGTH_SHORT).show()
            return
        }
        
        val newName = editTextGroupName.text.toString().trim()
        val newDescription = editTextGroupDescription.text.toString().trim()
        val newMode = if (switchGroupMode.isChecked) "RESTRICTED" else "OPEN"
        
        if (newName.isBlank()) {
            Toast.makeText(this, "Inserisci un nome per il gruppo", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            buttonSaveChanges.isEnabled = false
            
            val result = groupRepository.updateGroupSettings(
                groupId = groupId,
                name = newName,
                description = newDescription.takeIf { it.isNotBlank() },
                mode = newMode
            )
            
            buttonSaveChanges.isEnabled = true
            
            if (result.isSuccess) {
                Toast.makeText(
                    this@GroupSettingsActivity,
                    "Impostazioni aggiornate",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@GroupSettingsActivity,
                    "Errore aggiornamento: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    private fun removeMember(member: GroupMemberEntity) {
        AlertDialog.Builder(this)
            .setTitle("Rimuovi membro")
            .setMessage("Vuoi rimuovere ${member.displayName} dal gruppo?")
            .setPositiveButton("Rimuovi") { _, _ ->
                lifecycleScope.launch {
                    val result = groupRepository.removeMember(groupId, member.userId)
                    
                    if (result.isSuccess) {
                        Toast.makeText(
                            this@GroupSettingsActivity,
                            "${member.displayName} rimosso",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@GroupSettingsActivity,
                            "Errore rimozione membro",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
    
    private fun changeMemberRole(member: GroupMemberEntity) {
        val newRole = if (member.role == GroupRole.ADMIN) "MEMBER" else "ADMIN"
        val roleText = if (newRole == "ADMIN") "amministratore" else "membro"
        
        AlertDialog.Builder(this)
            .setTitle("Cambia ruolo")
            .setMessage("Vuoi rendere ${member.displayName} $roleText?")
            .setPositiveButton("Conferma") { _, _ ->
                lifecycleScope.launch {
                    val result = groupRepository.updateMemberRole(groupId, member.userId, newRole)
                    
                    if (result.isSuccess) {
                        Toast.makeText(
                            this@GroupSettingsActivity,
                            "Ruolo aggiornato",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@GroupSettingsActivity,
                            "Errore aggiornamento ruolo",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
    
    private fun showDeleteGroupDialog() {
        AlertDialog.Builder(this)
            .setTitle("Elimina gruppo")
            .setMessage("Sei sicuro di voler eliminare questo gruppo per tutti i membri? L'azione � irreversibile.")
            .setPositiveButton("Elimina") { _, _ ->
                deleteGroup()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
    
    private fun showLeaveGroupDialog() {
        AlertDialog.Builder(this)
            .setTitle("Abbandona gruppo")
            .setMessage("Sei sicuro di voler abbandonare questo gruppo? I dati del gruppo saranno eliminati solo dal tuo dispositivo.")
            .setPositiveButton("Abbandona") { _, _ ->
                leaveGroup()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
    
    private fun deleteGroup() {
        lifecycleScope.launch {
            val result = groupRepository.deleteGroup(groupId)
            
            if (result.isSuccess) {
                Toast.makeText(this@GroupSettingsActivity, "Gruppo eliminato per tutti i membri", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(
                    this@GroupSettingsActivity,
                    "Errore eliminazione gruppo: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    private fun leaveGroup() {
        lifecycleScope.launch {
            val result = groupRepository.leaveGroup(groupId)
            
            if (result.isSuccess) {
                Toast.makeText(this@GroupSettingsActivity, "Hai abbandonato il gruppo", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(
                    this@GroupSettingsActivity,
                    "Errore abbandono gruppo: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    private fun showAddMembersDialog() {
        val groupName = editTextGroupName.text.toString()
        val dialog = it.fabiodirauso.shutappchat.dialogs.AddMembersDialog.newInstance(groupId, groupName)
        
        dialog.onMembersAdded = { addedUserIds ->
            Log.d("GroupSettings", "Added ${addedUserIds.size} members to group")
            Toast.makeText(this, "${addedUserIds.size} membri aggiunti", Toast.LENGTH_SHORT).show()
            
            // Refresh members list
            lifecycleScope.launch {
                groupRepository.refreshGroupMembers(groupId)
            }
        }
        
        dialog.show(supportFragmentManager, "AddMembersDialog")
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
    
    // ========== GROUP PICTURE MANAGEMENT ==========
    
    private fun openImagePicker() {
        // Check and request media permissions
        if (!PermissionManager.hasMediaImagesPermission(this)) {
            PermissionDialogHelper.handlePermissionRequest(
                activity = this,
                permissionType = PermissionManager.PermissionType.MEDIA_IMAGES,
                onGranted = { launchImagePicker() },
                onDenied = {
                    Toast.makeText(this, "Permesso necessario per selezionare immagini", Toast.LENGTH_SHORT).show()
                }
            )
            return
        }
        
        launchImagePicker()
    }
    
    private fun launchImagePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        imagePickerLauncher.launch(Intent.createChooser(intent, "Seleziona immagine gruppo"))
    }

    private fun openImageEditor(imageUri: Uri) {
        Log.d("GroupSettings", "Opening editor with URI: $imageUri")
        val intent = Intent(this, ProfileImageEditorActivity::class.java).apply {
            putExtra(ProfileImageEditorActivity.EXTRA_IMAGE_URI, imageUri.toString())
        }
        imageEditorLauncher.launch(intent)
    }
    
    private suspend fun copyUriToTempFile(uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return@withContext null
            val tempFile = File(cacheDir, "temp_group_image_${System.currentTimeMillis()}.jpg")
            
            inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            Log.d("GroupSettings", "Copied image to temp file: ${tempFile.absolutePath}")
            tempFile
        } catch (e: Exception) {
            Log.e("GroupSettings", "Error copying image to temp file", e)
            null
        }
    }
    
    private fun uploadGroupPicture(imageUri: Uri) {
        lifecycleScope.launch {
            try {
                fabEditGroupPicture.isEnabled = false
                Toast.makeText(this@GroupSettingsActivity, "Caricamento...", Toast.LENGTH_SHORT).show()
                
                // Read image data
                val bytes = withContext(Dispatchers.IO) {
                    readBytesFromUri(imageUri)
                } ?: throw Exception("Impossibile leggere il file")
                
                // Get filename and mime type
                var filename = "group_picture.jpg"
                if (imageUri.scheme == ContentResolver.SCHEME_FILE) {
                    filename = File(imageUri.path ?: "group_picture.jpg").name
                } else {
                    val cursor = contentResolver.query(imageUri, null, null, null, null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) {
                                filename = it.getString(nameIndex) ?: "group_picture.jpg"
                            }
                        }
                    }
                }
                
                val mimeType = contentResolver.getType(imageUri)
                    ?: URLConnection.guessContentTypeFromName(filename)
                    ?: "image/jpeg"
                
                Log.d("GroupSettings", "Uploading group picture: $filename, size: ${bytes.size}, mime: $mimeType")
                
                // Step 1: Initialize media upload
                val initRequest = MediaInitRequest(
                    filename = filename,
                    mime = mimeType,
                    size = bytes.size.toLong()
                )
                
                val initResponse = RetrofitClient.apiService.initMediaUpload(initRequest)
                
                if (!initResponse.isSuccessful) {
                    val errorBody = initResponse.errorBody()?.string()
                    Log.e("GroupSettings", "Init response code: ${initResponse.code()}")
                    Log.e("GroupSettings", "Init error body: $errorBody")
                    throw Exception("Failed to init media upload: ${initResponse.message()}")
                }
                
                // Convert id to string
                val rawId = initResponse.body()?.id ?: throw Exception("Missing media ID in response")
                val mediaId = when (rawId) {
                    is Number -> rawId.toLong().toString()
                    else -> rawId.toString()
                }
                Log.d("GroupSettings", "Media ID: $mediaId")
                
                // Get encryption keys from server
                val aesKeyBase64 = initResponse.body()?.key ?: throw Exception("Missing AES key in response")
                val aesIvBase64 = initResponse.body()?.iv ?: throw Exception("Missing AES IV in response")
                
                // Decrypt Base64 keys
                val aesKey = android.util.Base64.decode(aesKeyBase64, android.util.Base64.DEFAULT)
                val aesIv = android.util.Base64.decode(aesIvBase64, android.util.Base64.DEFAULT)
                
                Log.d("GroupSettings", "AES Key length: ${aesKey.size}, IV length: ${aesIv.size}")
                
                // Step 2: Encrypt data with AES-CBC before upload
                val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                val secretKey = javax.crypto.spec.SecretKeySpec(aesKey, "AES")
                val ivSpec = javax.crypto.spec.IvParameterSpec(aesIv)
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, ivSpec)
                val encryptedBytes = cipher.doFinal(bytes)
                
                Log.d("GroupSettings", "Original size: ${bytes.size}, Encrypted size: ${encryptedBytes.size}")
                
                // Upload encrypted data
                val requestBody = encryptedBytes.toRequestBody(mimeType.toMediaTypeOrNull())
                val uploadResponse = RetrofitClient.apiService.uploadMediaData(mediaId, 0, requestBody)
                
                if (!uploadResponse.isSuccessful || uploadResponse.body()?.complete != true) {
                    throw Exception("Failed to upload media data: ${uploadResponse.message()}")
                }
                
                Log.d("GroupSettings", "Media uploaded successfully")
                
                // Step 3: Update group settings with new picture ID
                val result = groupRepository.updateGroupSettings(
                    groupId = groupId,
                    pictureId = mediaId
                )
                
                if (result.isSuccess) {
                    Toast.makeText(this@GroupSettingsActivity, "Immagine gruppo aggiornata!", Toast.LENGTH_SHORT).show()
                    // Refresh group info to get updated picture
                    groupRepository.refreshGroupInfo(groupId)
                } else {
                    throw result.exceptionOrNull() ?: Exception("Unknown error")
                }
                
            } catch (e: Exception) {
                Log.e("GroupSettings", "Error uploading group picture", e)
                Toast.makeText(
                    this@GroupSettingsActivity,
                    "Errore caricamento: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                fabEditGroupPicture.isEnabled = true
            }
        }
    }
    
    private suspend fun readBytesFromUri(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            }
        } catch (e: Exception) {
            Log.e("GroupSettings", "Error reading bytes from URI", e)
            null
        }
    }
    
    companion object {
        const val EXTRA_GROUP_ID = "group_id"
    }
}

