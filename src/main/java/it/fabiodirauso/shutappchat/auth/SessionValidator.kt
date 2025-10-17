package it.fabiodirauso.shutappchat.auth

import android.content.Context
import android.util.Log
import it.fabiodirauso.shutappchat.config.AppConfig
import it.fabiodirauso.shutappchat.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Client-side session validator with server communication
 * 
 * Responsibilities:
 * - Validate token with server
 * - Handle session expiration
 * - Trigger re-authentication when needed
 * - Provide session diagnostics
 */
class SessionValidator(private val context: Context) {
    
    companion object {
        private const val TAG = "SessionValidator"
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2000L
    }
    
    data class ValidationRequest(
        val username: String,
        val token: String
    )
    
    data class ValidationResponse(
        val valid: Boolean,
        val session_active: Boolean = false,
        val username: String = "",
        val user_id: Int = 0,
        val last_activity: String = "",
        val created_at: String = "",
        val message: String = "",
        val requires_reauth: Boolean = false
    )
    
    sealed class ValidationResult {
        data class Valid(val response: ValidationResponse) : ValidationResult()
        data class Invalid(val message: String, val requiresReauth: Boolean) : ValidationResult()
        data class NetworkError(val error: String) : ValidationResult()
        data class ServerError(val code: Int, val message: String) : ValidationResult()
    }
    
    interface SessionValidationApi {
        @POST("api/session/validate")
        suspend fun validate(@Body request: ValidationRequest): Response<ValidationResponse>
    }
    
    private val api: SessionValidationApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(context) { "ShutAppChat|v1.0|1" })
            .build()
        
        Retrofit.Builder()
            .baseUrl(AppConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SessionValidationApi::class.java)
    }
    
    private val tokenManager by lazy {
        TokenManager.getInstance(context)
    }
    
    /**
     * Validate current session with server
     */
    suspend fun validateSession(): ValidationResult = withContext(Dispatchers.IO) {
        val sessionInfo = tokenManager.getSessionInfo()
        if (sessionInfo == null) {
            Log.w(TAG, "No session info available")
            return@withContext ValidationResult.Invalid(
                message = "No active session",
                requiresReauth = true
            )
        }
        
        // Check local token expiration first
        if (tokenManager.isTokenExpired()) {
            val tokenAge = tokenManager.getTokenAge()
            Log.w(TAG, "Token expired locally (age: ${tokenAge}ms)")
            return@withContext ValidationResult.Invalid(
                message = "Token expired",
                requiresReauth = true
            )
        }
        
        // Validate with server
        return@withContext validateWithRetry(sessionInfo.username, sessionInfo.token)
    }
    
    /**
     * Validate session with automatic retry on network errors
     */
    private suspend fun validateWithRetry(
        username: String,
        token: String,
        attempt: Int = 1
    ): ValidationResult {
        try {
            val request = ValidationRequest(username, token)
            val response = api.validate(request)
            
            return when {
                response.isSuccessful -> {
                    val body = response.body()
                    if (body == null) {
                        Log.e(TAG, "Validation response body is null")
                        ValidationResult.ServerError(200, "Empty response")
                    } else if (body.valid) {
                        Log.i(TAG, "Session validated successfully: user=$username")
                        ValidationResult.Valid(body)
                    } else {
                        Log.w(TAG, "Session invalid: ${body.message}")
                        if (body.requires_reauth) {
                            tokenManager.requireValidation()
                        }
                        ValidationResult.Invalid(
                            message = body.message,
                            requiresReauth = body.requires_reauth
                        )
                    }
                }
                
                response.code() == 401 -> {
                    Log.w(TAG, "Unauthorized - token invalid")
                    tokenManager.requireValidation()
                    ValidationResult.Invalid(
                        message = "Unauthorized",
                        requiresReauth = true
                    )
                }
                
                response.code() in 500..599 -> {
                    Log.e(TAG, "Server error: ${response.code()}")
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        Log.i(TAG, "Retrying validation (attempt $attempt/$MAX_RETRY_ATTEMPTS)")
                        kotlinx.coroutines.delay(RETRY_DELAY_MS * attempt)
                        return validateWithRetry(username, token, attempt + 1)
                    }
                    ValidationResult.ServerError(response.code(), "Server error after $MAX_RETRY_ATTEMPTS attempts")
                }
                
                else -> {
                    ValidationResult.ServerError(response.code(), "HTTP ${response.code()}")
                }
            }
            
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "Network error: Unknown host", e)
            return ValidationResult.NetworkError("Server unreachable - check connection")
            
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Network error: Timeout", e)
            if (attempt < MAX_RETRY_ATTEMPTS) {
                Log.i(TAG, "Retrying after timeout (attempt $attempt/$MAX_RETRY_ATTEMPTS)")
                kotlinx.coroutines.delay(RETRY_DELAY_MS * attempt)
                return validateWithRetry(username, token, attempt + 1)
            }
            return ValidationResult.NetworkError("Connection timeout after $MAX_RETRY_ATTEMPTS attempts")
            
        } catch (e: Exception) {
            Log.e(TAG, "Validation error", e)
            if (attempt < MAX_RETRY_ATTEMPTS) {
                Log.i(TAG, "Retrying after error (attempt $attempt/$MAX_RETRY_ATTEMPTS)")
                kotlinx.coroutines.delay(RETRY_DELAY_MS * attempt)
                return validateWithRetry(username, token, attempt + 1)
            }
            return ValidationResult.NetworkError("Validation failed: ${e.message}")
        }
    }
    
    /**
     * Quick local validation without server call
     */
    fun validateLocal(): Boolean {
        return tokenManager.isTokenValid()
    }
    
    /**
     * Get session diagnostics
     */
    fun getSessionDiagnostics(): String {
        val sessionInfo = tokenManager.getSessionInfo()
        if (sessionInfo == null) {
            return "No session"
        }
        
        val tokenAgeSec = sessionInfo.tokenAge / 1000
        val tokenTTL = tokenManager.getTokenTimeToLive() / 1000
        
        return buildString {
            appendLine("Session Diagnostics:")
            appendLine("  Username: ${sessionInfo.username}")
            appendLine("  User ID: ${sessionInfo.userId}")
            appendLine("  Active: ${sessionInfo.isActive}")
            appendLine("  Token Age: ${tokenAgeSec}s")
            appendLine("  Time To Live: ${tokenTTL}s")
            appendLine("  Expired: ${tokenManager.isTokenExpired()}")
        }
    }
}
