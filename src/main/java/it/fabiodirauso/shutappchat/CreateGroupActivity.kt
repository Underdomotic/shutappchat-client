package it.fabiodirauso.shutappchat

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import it.fabiodirauso.shutappchat.adapter.ContactSelectionAdapter
import it.fabiodirauso.shutappchat.databinding.ActivityCreateGroupBinding
import it.fabiodirauso.shutappchat.database.AppDatabase
import it.fabiodirauso.shutappchat.managers.GroupRepository
import it.fabiodirauso.shutappchat.model.GroupEntity
import it.fabiodirauso.shutappchat.model.GroupMemberEntity
import it.fabiodirauso.shutappchat.model.GroupMode
import it.fabiodirauso.shutappchat.model.GroupRole
import it.fabiodirauso.shutappchat.model.User
import it.fabiodirauso.shutappchat.utils.AvatarHelper
import it.fabiodirauso.shutappchat.utils.PermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CreateGroupActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityCreateGroupBinding
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var database: AppDatabase
    private lateinit var groupRepository: GroupRepository
    private lateinit var contactAdapter: ContactSelectionAdapter
    
    private val selectedContacts = mutableSetOf<User>()
    private var groupPictureUri: Uri? = null
    private var currentPhotoUri: Uri? = null
    
    // Image picker launcher
    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleGroupPictureSelected(it) }
    }
    
    // Camera launcher
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && currentPhotoUri != null) {
            handleGroupPictureSelected(currentPhotoUri!!)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityCreateGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Enable fullscreen immersive mode
        it.fabiodirauso.shutappchat.utils.UIHelper.enableImmersiveMode(this)
        
        sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        database = AppDatabase.getDatabase(this)
        groupRepository = GroupRepository.getInstance(this)
        
        setupToolbar()
        setupViews()
        setupClickListeners()
        loadContacts()
    }
    
    private fun setupToolbar() {
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Crea Gruppo"
    }
    
    private fun setupViews() {
        // Setup RecyclerView per selezione contatti
        contactAdapter = ContactSelectionAdapter(
            contacts = emptyList(),
            onContactSelectionChanged = { contact, isSelected ->
                if (isSelected) {
                    selectedContacts.add(contact)
                } else {
                    selectedContacts.remove(contact)
                }
                updateSelectedCountUI()
            }
        )
        
        binding.recyclerViewContacts.apply {
            adapter = contactAdapter
            layoutManager = LinearLayoutManager(this@CreateGroupActivity)
        }
        
        // Setup group picture placeholder
        updateGroupPictureUI()
    }
    
    private fun setupClickListeners() {
        // Selezione immagine gruppo
        binding.imageViewGroupPicture.setOnClickListener {
            showGroupPictureOptions()
        }
        
        // Pulsante crea gruppo
        binding.buttonCreateGroup.setOnClickListener {
            createGroup()
        }
        
        // Switch modalit� gruppo
        binding.switchGroupMode.setOnCheckedChangeListener { _, isChecked ->
            binding.textViewGroupModeDescription.text = if (isChecked) {
                "Solo gli amministratori possono inviare messaggi e media"
            } else {
                "Tutti i membri possono inviare messaggi e media"
            }
        }
    }
    
    private fun loadContacts() {
        lifecycleScope.launch {
            try {
                val contacts = withContext(Dispatchers.IO) {
                    database.userDao().getAllUsers()
                }
                
                // Filtra l\'utente corrente
                val userId = sharedPreferences.getLong("user_id", 0)
                val filteredContacts = contacts.filter { it.id != userId }
                
                contactAdapter.updateContacts(filteredContacts)
                
            } catch (e: Exception) {
                android.util.Log.e("CreateGroupActivity", "Error loading contacts", e)
                Toast.makeText(this@CreateGroupActivity, "Errore caricamento contatti", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showGroupPictureOptions() {
        val options = arrayOf("Scatta foto", "Scegli dalla galleria", "Rimuovi foto")
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Immagine Gruppo")
        builder.setItems(options) { _, which ->
            when (which) {
                0 -> launchCamera()
                1 -> imagePickerLauncher.launch("image/*")
                2 -> removeGroupPicture()
            }
        }
        builder.show()
    }
    
    private fun launchCamera() {
        if (!PermissionManager.hasCameraPermission(this)) {
            PermissionManager.checkAndRequestCameraPermission(this)
            return
        }
        
        try {
            val photoFile = createImageFile()
            currentPhotoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                photoFile
            )
            cameraLauncher.launch(currentPhotoUri)
        } catch (e: Exception) {
            Toast.makeText(this, "Errore apertura camera", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "GROUP_${timeStamp}_"
        val storageDir = File(cacheDir, "group_pictures")
        
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        
        return File.createTempFile(imageFileName, ".jpg", storageDir)
    }
    
    private fun handleGroupPictureSelected(uri: Uri) {
        groupPictureUri = uri
        updateGroupPictureUI()
    }
    
    private fun removeGroupPicture() {
        groupPictureUri = null
        updateGroupPictureUI()
    }
    
    private fun updateGroupPictureUI() {
        if (groupPictureUri != null) {
            binding.imageViewGroupPicture.setImageURI(groupPictureUri)
        } else {
            // Mostra icona default
            binding.imageViewGroupPicture.setImageResource(R.drawable.ic_group_default)
        }
    }
    
    private fun updateSelectedCountUI() {
        binding.textViewSelectedCount.text = "${selectedContacts.size} contatti selezionati"
        binding.buttonCreateGroup.isEnabled = 
            binding.editTextGroupName.text.toString().isNotBlank() && selectedContacts.isNotEmpty()
    }
    
    private fun createGroup() {
        val groupName = binding.editTextGroupName.text.toString().trim()
        val groupDescription = binding.editTextGroupDescription.text.toString().trim()
        
        if (groupName.isBlank()) {
            Toast.makeText(this, "Inserisci un nome per il gruppo", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (selectedContacts.isEmpty()) {
            Toast.makeText(this, "Seleziona almeno un contatto", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.progressBar.visibility = View.VISIBLE
        binding.buttonCreateGroup.isEnabled = false
        
        lifecycleScope.launch {
            try {
                // Determina modalità gruppo
                val groupMode = if (binding.switchGroupMode.isChecked) {
                    "RESTRICTED"
                } else {
                    "OPEN"
                }
                
                // TODO: Upload immagine gruppo se presente
                // val groupPictureId: String? = uploadGroupPicture()
                
                // Prepara lista membri iniziali (ID utenti)
                val initialMembers = selectedContacts.map { it.id }
                
                // Chiama API per creare gruppo
                val result = groupRepository.createGroup(
                    name = groupName,
                    description = groupDescription.takeIf { it.isNotBlank() },
                    mode = groupMode,
                    initialMembers = initialMembers
                )
                
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.buttonCreateGroup.isEnabled = true
                    
                    if (result.isSuccess) {
                        val response = result.getOrNull()
                        Toast.makeText(
                            this@CreateGroupActivity,
                            "Gruppo creato: ${response?.group_name ?: groupName}",
                            Toast.LENGTH_SHORT
                        ).show()
                        
                        // Torna alla homepage
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        val error = result.exceptionOrNull()?.message ?: "Errore sconosciuto"
                        Toast.makeText(
                            this@CreateGroupActivity,
                            "Errore creazione gruppo: $error",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                
            } catch (e: Exception) {
                android.util.Log.e("CreateGroupActivity", "Error creating group", e)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.buttonCreateGroup.isEnabled = true
                    Toast.makeText(
                        this@CreateGroupActivity,
                        "Errore creazione gruppo: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
