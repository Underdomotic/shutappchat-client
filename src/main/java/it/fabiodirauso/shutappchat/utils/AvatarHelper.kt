package it.fabiodirauso.shutappchat.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.ImageView
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import it.fabiodirauso.shutappchat.R
import it.fabiodirauso.shutappchat.network.RetrofitClient

object AvatarHelper {
    
    /**
     * Loads avatar image for a user from server or shows initials as fallback
     */
    fun loadUserAvatar(
        context: Context,
        imageView: ImageView,
        username: String,
        userToken: String,
        profilePictureId: String?,
        lifecycleScope: LifecycleCoroutineScope
    ) {
        if (profilePictureId.isNullOrEmpty() || userToken.isEmpty()) {
            // Show initials if no profile picture or no token
            val initialsAvatar = generateInitialsAvatar(username)
            imageView.setImageBitmap(initialsAvatar)
            return
        }
        
        // Extract ID from media:// format if present
        val mediaId = if (profilePictureId.startsWith("media://")) {
            profilePictureId.removePrefix("media://")
        } else {
            profilePictureId
        }
        
        // Get current user's username for API authentication (Go server expects username, not ID)
        val sharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val currentUsername = sharedPreferences.getString("username", "") ?: ""
        
        if (currentUsername.isEmpty()) {
            // If no username, fallback to initials
            val initialsAvatar = generateInitialsAvatar(username)
            imageView.setImageBitmap(initialsAvatar)
            return
        }
        
        // Controlla prima la cache
        
        // Check cache first
        val cachedAvatar = AvatarCache.getAvatar(mediaId)
        if (cachedAvatar != null) {
            imageView.setImageBitmap(cachedAvatar)
            return
        }
        
        lifecycleScope.launch {
            try {
                android.util.Log.d("AvatarHelper", "Loading avatar for mediaId: $mediaId, username: $currentUsername, token: ${userToken.take(10)}...")
                val response = RetrofitClient.wsApiService.getAvatarThumbnail(
                    id = mediaId,
                    user = currentUsername, // Pass username for Go server authentication
                    token = userToken
                )
                
                android.util.Log.d("AvatarHelper", "Avatar response: ${response.code()}, successful: ${response.isSuccessful}")
                
                // Log error body for 500 errors
                if (!response.isSuccessful) {
                    try {
                        val errorBody = response.errorBody()?.string()
                        android.util.Log.e("AvatarHelper", "Error ${response.code()}: $errorBody")
                    } catch (e: Exception) {
                        android.util.Log.e("AvatarHelper", "Could not read error body", e)
                    }
                }
                
                if (response.isSuccessful && response.body() != null) {
                    try {
                        val imageBytes = response.body()!!.bytes()
                        
                        val bitmap = withContext(Dispatchers.Default) {
                            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        }
                        
                        if (bitmap != null) {
                            android.util.Log.d("AvatarHelper", "Bitmap decoded successfully: ${bitmap.width}x${bitmap.height}")
                            // Salva nella cache prima di impostare l'immagine
                            AvatarCache.putAvatar(mediaId, bitmap)
                            imageView.setImageBitmap(bitmap)
                        } else {
                            android.util.Log.w("AvatarHelper", "Bitmap decoding failed, using initials")
                            // Fallback to initials
                            val initialsAvatar = generateInitialsAvatar(username)
                            imageView.setImageBitmap(initialsAvatar)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("AvatarHelper", "Error decoding bitmap", e)
                        val initialsAvatar = generateInitialsAvatar(username)
                        imageView.setImageBitmap(initialsAvatar)
                    }
                } else {
                    android.util.Log.w("AvatarHelper", "Avatar request failed: ${response.code()}, ${response.message()}")
                    // Fallback to initials
                    val initialsAvatar = generateInitialsAvatar(username)
                    imageView.setImageBitmap(initialsAvatar)
                }
            } catch (e: Exception) {
                android.util.Log.e("AvatarHelper", "Error loading avatar", e)
                // Fallback to initials
                val initialsAvatar = generateInitialsAvatar(username)
                imageView.setImageBitmap(initialsAvatar)
            }
        }
    }
    
    /**
     * Loads avatar image for a group from server or shows initials as fallback
     */
    fun loadGroupAvatar(
        context: Context,
        imageView: ImageView,
        groupName: String,
        groupPictureId: String?,
        lifecycleScope: LifecycleCoroutineScope
    ) {
        val sharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val userToken = sharedPreferences.getString("auth_token", "") ?: ""
        val currentUsername = sharedPreferences.getString("username", "") ?: ""
        
        if (groupPictureId.isNullOrEmpty() || userToken.isEmpty() || currentUsername.isEmpty()) {
            // Show initials if no group picture or no token
            val initialsAvatar = generateInitialsAvatar(groupName)
            imageView.setImageBitmap(initialsAvatar)
            return
        }
        
        // Extract ID from media:// format if present
        val mediaId = if (groupPictureId.startsWith("media://")) {
            groupPictureId.removePrefix("media://")
        } else {
            groupPictureId
        }
        
        // Check cache first
        val cachedAvatar = AvatarCache.getAvatar(mediaId)
        if (cachedAvatar != null) {
            imageView.setImageBitmap(cachedAvatar)
            return
        }
        
        lifecycleScope.launch {
            try {
                android.util.Log.d("AvatarHelper", "Loading group avatar for mediaId: $mediaId")
                val response = RetrofitClient.wsApiService.getAvatarThumbnail(
                    id = mediaId,
                    user = currentUsername,
                    token = userToken
                )
                
                android.util.Log.d("AvatarHelper", "Group avatar response: ${response.code()}, successful: ${response.isSuccessful}")
                
                if (!response.isSuccessful) {
                    try {
                        val errorBody = response.errorBody()?.string()
                        android.util.Log.e("AvatarHelper", "Error ${response.code()}: $errorBody")
                    } catch (e: Exception) {
                        android.util.Log.e("AvatarHelper", "Could not read error body", e)
                    }
                }
                
                if (response.isSuccessful && response.body() != null) {
                    try {
                        val imageBytes = response.body()!!.bytes()
                        
                        val bitmap = withContext(Dispatchers.Default) {
                            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        }
                        
                        if (bitmap != null) {
                            android.util.Log.d("AvatarHelper", "Group bitmap decoded successfully: ${bitmap.width}x${bitmap.height}")
                            AvatarCache.putAvatar(mediaId, bitmap)
                            imageView.setImageBitmap(bitmap)
                        } else {
                            android.util.Log.w("AvatarHelper", "Group bitmap decoding failed, using initials")
                            val initialsAvatar = generateInitialsAvatar(groupName)
                            imageView.setImageBitmap(initialsAvatar)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("AvatarHelper", "Error decoding group bitmap", e)
                        val initialsAvatar = generateInitialsAvatar(groupName)
                        imageView.setImageBitmap(initialsAvatar)
                    }
                } else {
                    android.util.Log.w("AvatarHelper", "Group avatar request failed: ${response.code()}")
                    val initialsAvatar = generateInitialsAvatar(groupName)
                    imageView.setImageBitmap(initialsAvatar)
                }
            } catch (e: Exception) {
                android.util.Log.e("AvatarHelper", "Error loading group avatar", e)
                val initialsAvatar = generateInitialsAvatar(groupName)
                imageView.setImageBitmap(initialsAvatar)
            }
        }
    }
    
    /**
     * Generates a circular avatar with user initials
     */
    fun generateInitialsAvatar(username: String, size: Int = 128): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Background color based on username hash
        val colors = arrayOf(
            Color.parseColor("#FF5722"), // Red
            Color.parseColor("#FF9800"), // Orange  
            Color.parseColor("#FFC107"), // Amber
            Color.parseColor("#4CAF50"), // Green
            Color.parseColor("#2196F3"), // Blue
            Color.parseColor("#9C27B0"), // Purple
            Color.parseColor("#E91E63"), // Pink
            Color.parseColor("#009688")  // Teal
        )
        
        val colorIndex = Math.abs(username.hashCode()) % colors.size
        val backgroundColor = colors[colorIndex]
        
        // Draw circle background
        val paint = Paint().apply {
            color = backgroundColor
            isAntiAlias = true
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        
        // Draw initials
        val initials = getInitials(username)
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = size * 0.4f
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        
        val textBounds = android.graphics.Rect()
        textPaint.getTextBounds(initials, 0, initials.length, textBounds)
        val textY = size / 2f + textBounds.height() / 2f
        
        canvas.drawText(initials, size / 2f, textY, textPaint)
        
        return bitmap
    }
    
    /**
     * Extracts initials from username (first 2 characters, uppercase)
     */
    private fun getInitials(username: String): String {
        return if (username.length >= 2) {
            username.take(2).uppercase()
        } else {
            username.uppercase().padEnd(2, '?')
        }
    }
}