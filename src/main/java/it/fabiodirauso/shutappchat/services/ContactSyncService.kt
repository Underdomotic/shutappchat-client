package it.fabiodirauso.shutappchat.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import it.fabiodirauso.shutappchat.database.AppDatabase
import it.fabiodirauso.shutappchat.network.RetrofitClient
import it.fabiodirauso.shutappchat.model.User
import it.fabiodirauso.shutappchat.model.ContactRequest
import it.fabiodirauso.shutappchat.model.ContactRequestStatus
import java.util.Date

class ContactSyncService(private val context: Context) {

    companion object {
        private const val TAG = "ContactSyncService"
        private const val PREFS_NAME = "contact_sync_prefs"
        private const val LAST_SYNC_KEY = "last_sync_timestamp"
    }

    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val database = AppDatabase.getDatabase(context)

    /**
     * Performs full synchronization of contacts from server
     * Updates local database with friends list
     */
    suspend fun performFullSync(): SyncResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting full contact synchronization...")
        
        try {
            // Ensure we have auth token set
            ensureAuthToken()
            
            // Fetch contacts from server
            val contactsResponse = RetrofitClient.apiService.getContacts()
            
            if (contactsResponse.isSuccessful && contactsResponse.body() != null) {
                val contacts = contactsResponse.body()!!.contacts
                Log.d(TAG, "Fetched ${contacts.size} contacts from server")
                
                // Clear existing users and insert new ones
                clearAndInsertContacts(contacts)
                
                // Update last sync time
                updateLastSyncTime()
                
                Log.d(TAG, "Contact synchronization completed successfully")
                SyncResult(true, contacts.size, null)
            } else {
                val error = "Server responded with error: ${contactsResponse.code()}"
                Log.e(TAG, error)
                SyncResult(false, 0, error)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during contact synchronization", e)
            SyncResult(false, 0, e.message)
        }
    }

    /**
     * Sync contact requests (pending friend requests)
     */
    suspend fun syncContactRequests(): SyncResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Syncing contact requests...")
        
        try {
            ensureAuthToken()
            
            // Ottieni l'ID dell'utente corrente
            val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val currentUserId = appPrefs.getLong("user_id", 0)
            
            val requestsResponse = RetrofitClient.apiService.getContactRequests()
            
            if (requestsResponse.isSuccessful && requestsResponse.body() != null) {
                val requestsData = requestsResponse.body()!!
                val totalRequests = requestsData.requests.size
                
                Log.d(TAG, "Found ${totalRequests} requests")
                
                // Convert API PendingContactRequestAPI to database ContactRequest
                val dbRequests = requestsData.requests.mapNotNull { apiRequest ->
                    try {
                        // Ottieni l'ID del sender facendo una chiamata API
                        val senderResponse = RetrofitClient.apiService.getUser(apiRequest.sender)
                        val senderId = if (senderResponse.isSuccessful) {
                            (senderResponse.body()?.get("id") as? Number)?.toLong() ?: 0L
                        } else {
                            0L
                        }
                        
                        if (senderId > 0) {
                            val requestStatus = when (apiRequest.status.lowercase()) {
                                "accepted" -> ContactRequestStatus.ACCEPTED
                                "rejected", "declined" -> ContactRequestStatus.REJECTED
                                else -> ContactRequestStatus.PENDING
                            }
                            
                            ContactRequest(
                                id = apiRequest.id,
                                fromUserId = senderId,
                                fromUsername = apiRequest.sender,
                                fromNickname = null, // Non disponibile dal nuovo formato API
                                fromProfilePicture = null, // Non disponibile dal nuovo formato API
                                toUserId = currentUserId,
                                status = requestStatus,
                                createdAt = parseDate(apiRequest.timestamp),
                                updatedAt = apiRequest.processed_at?.let { parseDate(it) }
                            )
                        } else {
                            Log.w(TAG, "Could not get sender ID for ${apiRequest.sender}")
                            null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error converting API request", e)
                        null
                    }
                }
                
                // Store in database
                database.contactRequestDao().insertRequests(dbRequests)
                Log.d(TAG, "Stored ${dbRequests.size} contact requests in database")
                
                SyncResult(true, dbRequests.size, null)
            } else {
                val error = "Failed to fetch contact requests: ${requestsResponse.code()}"
                Log.e(TAG, error)
                SyncResult(false, 0, error)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing contact requests", e)
            SyncResult(false, 0, e.message)
        }
    }

    private suspend fun ensureAuthToken() {
        val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val token = appPrefs.getString("auth_token", null)
        if (token != null) {
            RetrofitClient.setAuthToken(token)
        } else {
            throw IllegalStateException("No auth token available for contact sync")
        }
    }

    private suspend fun clearAndInsertContacts(contacts: List<User>) {
        Log.d(TAG, "Updating local contacts database...")
        
        // For simplicity, we'll replace all users
        // In a production app, you might want to do a smarter merge
        
        try {
            // Clear existing users (except current user)
            val currentUserId = getCurrentUserId()
            val existingUsers = database.userDao().getAllUsers()
            
            // Delete all users except current user
            existingUsers.filter { it.id != currentUserId }.forEach { user ->
                database.userDao().deleteUser(user)
            }
            
            // Insert new contacts
            contacts.forEach { contact ->
                if (contact.id != currentUserId) {
                    database.userDao().insertUser(contact)
                }
            }
            
            Log.d(TAG, "Successfully updated ${contacts.size} contacts in local database")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating local contacts database", e)
            throw e
        }
    }

    private fun getCurrentUserId(): Long {
        val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return appPrefs.getLong("user_id", 0)
    }

    fun updateLastSyncTime() {
        val currentTime = System.currentTimeMillis()
        sharedPreferences.edit()
            .putLong(LAST_SYNC_KEY, currentTime)
            .apply()
        Log.d(TAG, "Updated last sync time to $currentTime")
    }

    fun getLastSyncTime(): Long {
        return sharedPreferences.getLong(LAST_SYNC_KEY, 0)
    }

    fun shouldSync(): Boolean {
        val lastSync = getLastSyncTime()
        val now = System.currentTimeMillis()
        val syncInterval = 30 * 60 * 1000L // 30 minutes
        
        return (now - lastSync) > syncInterval
    }
    
    /**
     * Parse ISO 8601 date string to Date object
     * Falls back to current time if parsing fails
     */
    private fun parseDate(dateStr: String): Date {
        return try {
            // Try ISO 8601 format
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            format.parse(dateStr) ?: Date()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse date '$dateStr', using current time", e)
            Date()
        }
    }

    /**
     * Result of a synchronization operation
     */
    data class SyncResult(
        val success: Boolean,
        val itemsProcessed: Int,
        val error: String?
    )
}
