package it.fabiodirauso.shutappchat

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import it.fabiodirauso.shutappchat.api.LoginRequest
import it.fabiodirauso.shutappchat.auth.TokenManager
import it.fabiodirauso.shutappchat.network.RetrofitClient
import it.fabiodirauso.shutappchat.config.ServerConfig
import it.fabiodirauso.shutappchat.config.AppConfigManager
import it.fabiodirauso.shutappchat.databinding.ActivityAuthBinding
import it.fabiodirauso.shutappchat.services.WebSocketService
import it.fabiodirauso.shutappchat.utils.UIHelper

class AuthActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityAuthBinding
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var configManager: AppConfigManager
    private lateinit var tokenManager: TokenManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Enable fullscreen immersive mode
        UIHelper.enableImmersiveMode(this)
        
        android.util.Log.d("AuthActivity", "onCreate started")
        
        sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        configManager = AppConfigManager.getInstance(this)
        tokenManager = TokenManager.getInstance(this)
        
        // Inizializza RetrofitClient con context
        RetrofitClient.initialize(this)
        
        // Check if user is already logged in
        if (isUserLoggedIn()) {
            android.util.Log.d("AuthActivity", "User already logged in, redirecting to homepage")
            navigateToHomepage()
            return
        }
        
        android.util.Log.d("AuthActivity", "About to load app configuration")
        // Carica la configurazione dell'app all'avvio
        loadAppConfiguration()
        
        setupClickListeners()
    }
    
    private fun loadAppConfiguration() {
        binding.buttonLogin.isEnabled = false
        binding.buttonLogin.text = "Loading configuration..."
        
        lifecycleScope.launch {
            try {
                val success = configManager.loadConfig()
                if (success) {
                    android.util.Log.d("AuthActivity", "App configuration loaded successfully")
                    android.util.Log.d("AuthActivity", "API Base: ${configManager.apiBaseUrl}")
                    android.util.Log.d("AuthActivity", "WS URL: ${configManager.wsUrl}")
                } else {
                    android.util.Log.w("AuthActivity", "Failed to load app configuration, using defaults")
                    Toast.makeText(this@AuthActivity, "Using default configuration", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("AuthActivity", "Error loading app configuration", e)
                Toast.makeText(this@AuthActivity, "Configuration error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.buttonLogin.isEnabled = true
                binding.buttonLogin.text = "Login"
            }
        }
    }
    
    private fun setupClickListeners() {
        binding.buttonLogin.setOnClickListener {
            val username = binding.editTextEmail.text.toString().trim() // Using email field for username
            val password = binding.editTextPassword.text.toString().trim()
            
            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please enter username and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            performLogin(username, password)
        }
        
        binding.textViewRegister.setOnClickListener {
            navigateToRegister()
        }
    }
    
    private fun performLogin(username: String, password: String) {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.buttonLogin.isEnabled = false
        
        lifecycleScope.launch {
            try {
                android.util.Log.d("AuthActivity", "Attempting login for user: $username")
                android.util.Log.d("AuthActivity", "API URL: ${configManager.apiBaseUrl}")
                
                val loginRequest = LoginRequest(username = username, password = password)
                android.util.Log.d("AuthActivity", "Login request: $loginRequest")
                
                // Usa l'ApiService con configurazione dinamica
                val apiService = RetrofitClient.getApiService(configManager)
                val response = apiService.login(loginRequest)
                
                android.util.Log.d("AuthActivity", "Response code: ${response.code()}")
                android.util.Log.d("AuthActivity", "Response message: ${response.message()}")
                android.util.Log.d("AuthActivity", "Response body: ${response.body()}")
                android.util.Log.d("AuthActivity", "Response error body: ${response.errorBody()?.string()}")
                
                if (response.isSuccessful && response.body() != null) {
                    val loginResponse = response.body()!!
                    android.util.Log.d("AuthActivity", "Login successful for user: ${loginResponse.user?.username}")
                    
                    val authToken = loginResponse.token
                    if (authToken == null) {
                        android.util.Log.e("AuthActivity", "Token missing in response")
                        Toast.makeText(this@AuthActivity, "Errore: token mancante", Toast.LENGTH_LONG).show()
                    } else {
                        // Save authentication data using TokenManager (encrypted storage)
                        lifecycleScope.launch {
                            tokenManager.saveSession(
                                token = authToken,
                                userId = loginResponse.user?.id ?: 0,
                                username = loginResponse.user?.username ?: username
                            )
                        }
                        
                        // Save legacy data in SharedPreferences for backward compatibility
                        with(sharedPreferences.edit()) {
                            putString("auth_token", authToken)
                            putString("user_token", authToken)
                            putString("username", loginResponse.user?.username ?: username)
                            putLong("user_id", loginResponse.user?.id ?: 0)
                            apply()
                        }
                        
                        // Set token in RetrofitClient for authenticated requests
                        RetrofitClient.setAuthToken(authToken)
                        
                        // Start WebSocket service for background messaging
                        startWebSocketService()
                        
                        navigateToMainApp()
                    }
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Unknown error"
                    android.util.Log.e("AuthActivity", "Login failed: $errorBody")
                    Toast.makeText(this@AuthActivity, "Login failed: ${response.message()}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("AuthActivity", "Network error", e)
                Toast.makeText(this@AuthActivity, "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = android.view.View.GONE
                binding.buttonLogin.isEnabled = true
            }
        }
    }
    
    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun navigateToMainApp() {
        val intent = Intent(this, HomepageActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
    
    private fun navigateToHomepage() {
        val intent = Intent(this, HomepageActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
    
    private fun startWebSocketService() {
        val serviceIntent = Intent(this, WebSocketService::class.java).apply {
            action = WebSocketService.ACTION_START
        }
        startForegroundService(serviceIntent)
        android.util.Log.d("AuthActivity", "WebSocket service started")
    }
    
    private fun navigateToRegister() {
        val intent = Intent(this, RegisterActivity::class.java)
        startActivity(intent)
    }
    
    /**
     * Verifica se l'utente è già autenticato
     * Controlla prima TokenManager (encrypted), poi SharedPreferences (legacy)
     */
    private fun isUserLoggedIn(): Boolean {
        // Controlla prima TokenManager (encrypted storage)
        val tokenValid = tokenManager.isTokenValid()
        if (tokenValid) {
            android.util.Log.d("AuthActivity", "User authenticated via TokenManager")
            return true
        }
        
        // Fallback a SharedPreferences per compatibilità
        val token = sharedPreferences.getString("auth_token", null)
        val userId = sharedPreferences.getLong("user_id", -1L)
        val username = sharedPreferences.getString("username", null)
        
        val isLoggedIn = !token.isNullOrEmpty() && userId > 0 && !username.isNullOrEmpty()
        
        if (isLoggedIn) {
            android.util.Log.d("AuthActivity", "User authenticated via legacy SharedPreferences, migrating to TokenManager")
            // Migra a TokenManager
            lifecycleScope.launch {
                tokenManager.saveSession(token!!, userId, username!!)
            }
        }
        
        android.util.Log.d("AuthActivity", "Login check: tokenManager=$tokenValid, legacy=$isLoggedIn")
        
        return isLoggedIn
    }
}
