package it.fabiodirauso.shutappchat.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton per gestione centralizzata e sicura dei token di autenticazione
 * 
 * Features:
 * - Encrypted storage con EncryptedSharedPreferences
 * - Thread-safe operations con Mutex
 * - Token validation e expiration tracking
 * - Automatic refresh on expiration
 * - Session state monitoring
 */
class TokenManager private constructor(context: Context) {
    
    companion object {
        private const val TAG = "TokenManager"
        private const val PREFS_NAME = "secure_auth_prefs"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_TOKEN_TIMESTAMP = "token_timestamp"
        private const val KEY_SESSION_ACTIVE = "session_active"
        
        // Token validity: 24 hours
        private const val TOKEN_VALIDITY_MS = 24 * 60 * 60 * 1000L
        
        @Volatile
        private var INSTANCE: TokenManager? = null
        
        fun getInstance(context: Context): TokenManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TokenManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
    
    data class SessionInfo(
        val token: String,
        val userId: Long,
        val username: String,
        val isActive: Boolean,
        val tokenAge: Long // milliseconds since token creation
    )
    
    enum class SessionState {
        AUTHENTICATED,
        UNAUTHENTICATED,
        TOKEN_EXPIRED,
        VALIDATION_REQUIRED,
        REFRESHING
    }
    
    private val mutex = Mutex()
    private val isRefreshing = AtomicBoolean(false)
    
    private val _sessionState = MutableStateFlow(SessionState.UNAUTHENTICATED)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()
    
    // Encrypted SharedPreferences
    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences, falling back to standard", e)
            // Fallback to standard SharedPreferences if encryption fails
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
    
    init {
        // Check session state on initialization
        updateSessionState()
    }
    
    /**
     * Save authentication session
     */
    suspend fun saveSession(token: String, userId: Long, username: String) = mutex.withLock {
        try {
            val timestamp = System.currentTimeMillis()
            
            securePrefs.edit().apply {
                putString(KEY_AUTH_TOKEN, token)
                putLong(KEY_USER_ID, userId)
                putString(KEY_USERNAME, username)
                putLong(KEY_TOKEN_TIMESTAMP, timestamp)
                putBoolean(KEY_SESSION_ACTIVE, true)
                apply()
            }
            
            _sessionState.value = SessionState.AUTHENTICATED
            Log.i(TAG, "Session saved: user=$username, userId=$userId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session", e)
            throw e
        }
    }
    
    /**
     * Get current session token
     */
    fun getToken(): String? {
        return securePrefs.getString(KEY_AUTH_TOKEN, null)
    }
    
    /**
     * Get current user ID
     */
    fun getUserId(): Long {
        return securePrefs.getLong(KEY_USER_ID, -1L)
    }
    
    /**
     * Get current username
     */
    fun getUsername(): String? {
        return securePrefs.getString(KEY_USERNAME, null)
    }
    
    /**
     * Get full session info
     */
    fun getSessionInfo(): SessionInfo? {
        val token = getToken() ?: return null
        val userId = getUserId()
        val username = getUsername() ?: return null
        val timestamp = securePrefs.getLong(KEY_TOKEN_TIMESTAMP, 0L)
        val isActive = securePrefs.getBoolean(KEY_SESSION_ACTIVE, false)
        
        if (userId == -1L) return null
        
        val tokenAge = System.currentTimeMillis() - timestamp
        
        return SessionInfo(
            token = token,
            userId = userId,
            username = username,
            isActive = isActive,
            tokenAge = tokenAge
        )
    }
    
    /**
     * Check if token is expired
     */
    fun isTokenExpired(): Boolean {
        val timestamp = securePrefs.getLong(KEY_TOKEN_TIMESTAMP, 0L)
        if (timestamp == 0L) return true
        
        val tokenAge = System.currentTimeMillis() - timestamp
        return tokenAge > TOKEN_VALIDITY_MS
    }
    
    /**
     * Check if token is valid (exists and not expired)
     */
    fun isTokenValid(): Boolean {
        val token = getToken() ?: return false
        return !isTokenExpired() && securePrefs.getBoolean(KEY_SESSION_ACTIVE, false)
    }
    
    /**
     * Invalidate current session (server-side logout or token revoked)
     */
    suspend fun invalidateSession() = mutex.withLock {
        try {
            securePrefs.edit().apply {
                putBoolean(KEY_SESSION_ACTIVE, false)
                apply()
            }
            
            _sessionState.value = SessionState.UNAUTHENTICATED
            Log.i(TAG, "Session invalidated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to invalidate session", e)
        }
    }
    
    /**
     * Clear all session data (logout)
     */
    suspend fun clearSession() = mutex.withLock {
        try {
            securePrefs.edit().clear().apply()
            _sessionState.value = SessionState.UNAUTHENTICATED
            Log.i(TAG, "Session cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear session", e)
        }
    }
    
    /**
     * Update session state based on current token
     */
    fun updateSessionState() {
        _sessionState.value = when {
            !isTokenValid() && getToken() != null -> SessionState.TOKEN_EXPIRED
            isTokenValid() -> SessionState.AUTHENTICATED
            else -> SessionState.UNAUTHENTICATED
        }
    }
    
    /**
     * Mark that session needs validation
     */
    fun requireValidation() {
        _sessionState.value = SessionState.VALIDATION_REQUIRED
    }
    
    /**
     * Mark that token is being refreshed
     */
    fun setRefreshing(refreshing: Boolean) {
        isRefreshing.set(refreshing)
        if (refreshing) {
            _sessionState.value = SessionState.REFRESHING
        }
    }
    
    /**
     * Check if token is currently being refreshed
     */
    fun isRefreshing(): Boolean = isRefreshing.get()
    
    /**
     * Get token age in milliseconds
     */
    fun getTokenAge(): Long {
        val timestamp = securePrefs.getLong(KEY_TOKEN_TIMESTAMP, 0L)
        if (timestamp == 0L) return Long.MAX_VALUE
        return System.currentTimeMillis() - timestamp
    }
    
    /**
     * Get time until token expiration in milliseconds
     */
    fun getTokenTimeToLive(): Long {
        val age = getTokenAge()
        return (TOKEN_VALIDITY_MS - age).coerceAtLeast(0L)
    }
}
