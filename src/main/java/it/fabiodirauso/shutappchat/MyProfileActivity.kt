package it.fabiodirauso.shutappchat

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import it.fabiodirauso.shutappchat.api.MediaInitRequest
import it.fabiodirauso.shutappchat.api.UpdateProfilePictureRequest
import it.fabiodirauso.shutappchat.network.RetrofitClient
import it.fabiodirauso.shutappchat.utils.AvatarHelper
import it.fabiodirauso.shutappchat.utils.PermissionManager
import it.fabiodirauso.shutappchat.utils.PermissionDialogHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLConnection

class MyProfileActivity : AppCompatActivity() {
    
    private lateinit var imageViewProfile: ImageView
    // private lateinit var textViewUsername: TextView
    // private lateinit var textViewNickname: TextView
    private lateinit var buttonChangeAvatar: Button
    private lateinit var buttonEditProfile: Button
    
    private var currentUser: it.fabiodirauso.shutappchat.model.User? = null
    private var currentToken: String = ""
    
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
                        Toast.makeText(this@MyProfileActivity, "Errore nella copia dell'immagine", Toast.LENGTH_SHORT).show()
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
                    uploadAvatar(Uri.fromFile(file))
                } else {
                    Toast.makeText(this, "File elaborato non trovato", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_profile)
        
        // Enable fullscreen immersive mode
        it.fabiodirauso.shutappchat.utils.UIHelper.enableImmersiveMode(this)
        
        setupViews()
        setupAuth()
        loadProfile()
    }
    
    private fun setupViews() {
        imageViewProfile = findViewById(R.id.imageViewProfilePicture)
        // textViewUsername = findViewById(R.id.textViewUsername) // Not in layout
        // textViewNickname = findViewById(R.id.textViewNickname) // Not in layout  
        buttonChangeAvatar = findViewById(R.id.buttonChangePicture)
        buttonEditProfile = findViewById(R.id.buttonSaveProfile)
        
        buttonChangeAvatar.setOnClickListener { openImagePicker() }
        buttonEditProfile.setOnClickListener { 
            Toast.makeText(this, "Modifica profilo - Da implementare", Toast.LENGTH_SHORT).show()
        }
        
        supportActionBar?.title = getString(R.string.title_profile)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }
    
    private fun setupAuth() {
        val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        currentToken = sharedPreferences.getString("auth_token", "") ?: ""
        android.util.Log.d("MyProfileActivity", "Token loaded: ${if (currentToken.isNotEmpty()) "YES (${currentToken.take(10)}...)" else "NO TOKEN"}")
        RetrofitClient.setAuthToken(currentToken)
    }
    
    private fun loadProfile() {
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.apiService.getMyProfile()
                
                android.util.Log.d("MyProfileActivity", "Profile response: isSuccessful=${response.isSuccessful}, code=${response.code()}")
                android.util.Log.d("MyProfileActivity", "Profile body: ${response.body()}")
                
                if (response.isSuccessful && response.body()?.success == true) {
                    val user = response.body()!!.user
                    if (user != null) {
                        android.util.Log.d("MyProfileActivity", "User loaded: username=${user.username}, profile_picture=${user.profile_picture}")
                        currentUser = user
                        updateUI(user)
                    } else {
                        android.util.Log.w("MyProfileActivity", "User is null in response")
                    }
                } else {
                    android.util.Log.w("MyProfileActivity", "Profile load failed or success=false")
                    Toast.makeText(this@MyProfileActivity, "Errore nel caricamento profilo", Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Job was cancelled (activity closed), ignore this
                android.util.Log.d("MyProfileActivity", "Profile load cancelled")
            } catch (e: Exception) {
                android.util.Log.e("MyProfileActivity", "Error loading profile", e)
                Toast.makeText(this@MyProfileActivity, "Errore di rete", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun updateUI(user: it.fabiodirauso.shutappchat.model.User) {
        // textViewUsername.text = "@${user.username}"
        // textViewNickname.text = user.nickname ?: "Nessun nickname impostato"
        
        android.util.Log.d("MyProfileActivity", "updateUI called with profile_picture: ${user.profile_picture}")
        
        // Load avatar
        AvatarHelper.loadUserAvatar(
            context = this,
            imageView = imageViewProfile,
            username = user.username,
            userToken = currentToken,
            profilePictureId = user.profile_picture,
            lifecycleScope = lifecycleScope
        )
    }
    
    private fun openImagePicker() {
        // Verifica e richiedi permessi media
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
        imagePickerLauncher.launch(Intent.createChooser(intent, "Seleziona immagine profilo"))
    }

    private fun openImageEditor(imageUri: Uri) {
        android.util.Log.d("MyProfileActivity", "Opening editor with URI: $imageUri")
        val intent = Intent(this, ProfileImageEditorActivity::class.java).apply {
            putExtra(ProfileImageEditorActivity.EXTRA_IMAGE_URI, imageUri.toString())
        }
        imageEditorLauncher.launch(intent)
    }
    
    private suspend fun copyUriToTempFile(uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return@withContext null
            val tempFile = File(cacheDir, "temp_profile_image_${System.currentTimeMillis()}.jpg")
            
            inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            android.util.Log.d("MyProfileActivity", "Copied image to temp file: ${tempFile.absolutePath}")
            tempFile
        } catch (e: Exception) {
            android.util.Log.e("MyProfileActivity", "Error copying image to temp file", e)
            null
        }
    }
    
    private fun uploadAvatar(imageUri: Uri) {
        lifecycleScope.launch {
            try {
                buttonChangeAvatar.isEnabled = false
                buttonChangeAvatar.text = "Caricamento..."
                
                // Read image data
                val bytes = withContext(Dispatchers.IO) {
                    readBytesFromUri(imageUri)
                } ?: throw Exception("Impossibile leggere il file")
                
                // Get filename and mime type
                var filename = "avatar.jpg"
                if (imageUri.scheme == ContentResolver.SCHEME_FILE) {
                    filename = File(imageUri.path ?: "avatar.jpg").name
                } else {
                    val cursor = contentResolver.query(imageUri, null, null, null, null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) {
                                filename = it.getString(nameIndex) ?: "avatar.jpg"
                            }
                        }
                    }
                }
                
                val mimeType = contentResolver.getType(imageUri)
                    ?: URLConnection.guessContentTypeFromName(filename)
                    ?: "image/jpeg"
                
                android.util.Log.d("MyProfileActivity", "Uploading avatar: $filename, size: ${bytes.size}, mime: $mimeType")
                
                // Step 1: Initialize media upload
                val initRequest = MediaInitRequest(
                    filename = filename,
                    mime = mimeType,
                    size = bytes.size.toLong()
                )
                
                val initResponse = RetrofitClient.apiService.initMediaUpload(initRequest)
                
                if (!initResponse.isSuccessful) {
                    val errorBody = initResponse.errorBody()?.string()
                    android.util.Log.e("MyProfileActivity", "Init response code: ${initResponse.code()}")
                    android.util.Log.e("MyProfileActivity", "Init error body: $errorBody")
                    throw Exception("Failed to init media upload: ${initResponse.message()} - Body: $errorBody")
                }
                
                // Convert id to string, handling both Int and Double from JSON
                val rawId = initResponse.body()?.id ?: throw Exception("Missing media ID in response")
                val mediaId = when (rawId) {
                    is Number -> rawId.toLong().toString() // Convert number to Long then to String to avoid decimals
                    else -> rawId.toString()
                }
                android.util.Log.d("MyProfileActivity", "Media ID: $mediaId (from $rawId)")
                
                // Get encryption keys from server
                val aesKeyBase64 = initResponse.body()?.key ?: throw Exception("Missing AES key in response")
                val aesIvBase64 = initResponse.body()?.iv ?: throw Exception("Missing AES IV in response")
                
                // Decrypt Base64 keys
                val aesKey = android.util.Base64.decode(aesKeyBase64, android.util.Base64.DEFAULT)
                val aesIv = android.util.Base64.decode(aesIvBase64, android.util.Base64.DEFAULT)
                
                android.util.Log.d("MyProfileActivity", "AES Key length: ${aesKey.size}, IV length: ${aesIv.size}")
                
                // Step 2: Encrypt data with AES-CBC before upload
                val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                val secretKey = javax.crypto.spec.SecretKeySpec(aesKey, "AES")
                val ivSpec = javax.crypto.spec.IvParameterSpec(aesIv)
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, ivSpec)
                val encryptedBytes = cipher.doFinal(bytes)
                
                android.util.Log.d("MyProfileActivity", "Original size: ${bytes.size}, Encrypted size: ${encryptedBytes.size}")
                
                // Upload encrypted data
                val requestBody = encryptedBytes.toRequestBody(mimeType.toMediaTypeOrNull())
                val uploadResponse = RetrofitClient.apiService.uploadMediaData(mediaId, 0, requestBody)
                
                if (!uploadResponse.isSuccessful || uploadResponse.body()?.complete != true) {
                    throw Exception("Failed to upload media data: ${uploadResponse.message()}")
                }
                
                android.util.Log.d("MyProfileActivity", "Media uploaded successfully")
                
                // Step 3: Update profile picture with media:// format
                val profilePicturePath = "media://$mediaId"
                val updateRequest = UpdateProfilePictureRequest(path = profilePicturePath)
                val updateResponse = RetrofitClient.apiService.updateProfilePicture(updateRequest)
                
                android.util.Log.d("MyProfileActivity", "Update profile picture response: code=${updateResponse.code()}, success=${updateResponse.isSuccessful}, body=${updateResponse.body()}")
                
                if (updateResponse.isSuccessful) {
                    // Some endpoints return success field, others don't - accept both
                    val responseSuccess = updateResponse.body()?.success
                    if (responseSuccess == null || responseSuccess == true) {
                        Toast.makeText(this@MyProfileActivity, "Avatar aggiornato!", Toast.LENGTH_SHORT).show()
                        
                        // Clear avatar cache for this media ID to force reload
                        it.fabiodirauso.shutappchat.utils.AvatarCache.removeAvatar(mediaId)
                        
                        // Reload profile to get updated data
                        loadProfile()
                    } else {
                        throw Exception("Server returned success=false: ${updateResponse.body()?.message ?: "Unknown error"}")
                    }
                } else {
                    val errorBody = updateResponse.errorBody()?.string()
                    throw Exception("Failed to update profile picture (${updateResponse.code()}): $errorBody")
                }
                
            } catch (e: Exception) {
                android.util.Log.e("MyProfileActivity", "Error uploading avatar", e)
                Toast.makeText(this@MyProfileActivity, "Errore nel caricamento: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                if (imageUri.scheme == ContentResolver.SCHEME_FILE) {
                    val path = imageUri.path
                    if (!path.isNullOrEmpty() && path.startsWith(cacheDir.absolutePath)) {
                        runCatching { File(path).delete() }
                    }
                }
                buttonChangeAvatar.isEnabled = true
                buttonChangeAvatar.text = "Cambia Avatar"
            }
        }
    }

    private fun readBytesFromUri(imageUri: Uri): ByteArray? {
        return when (imageUri.scheme) {
            ContentResolver.SCHEME_FILE -> {
                val path = imageUri.path ?: return null
                val file = File(path)
                FileInputStream(file).use { it.readBytes() }
            }

            ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_ANDROID_RESOURCE, null -> {
                contentResolver.openInputStream(imageUri)?.use(InputStream::readBytes)
            }

            else -> null
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
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
                    // Permesso concesso, apri il picker
                    launchImagePicker()
                } else {
                    // Permesso negato
                    if (PermissionManager.shouldShowRationale(this, PermissionManager.PermissionType.MEDIA_IMAGES)) {
                        Toast.makeText(this, "Permesso necessario per selezionare immagini", Toast.LENGTH_LONG).show()
                    } else {
                        // Negato permanentemente
                        PermissionDialogHelper.showPermissionDeniedDialog(
                            activity = this,
                            permissionType = PermissionManager.PermissionType.MEDIA_IMAGES
                        )
                    }
                }
            }
        }
    }
}
