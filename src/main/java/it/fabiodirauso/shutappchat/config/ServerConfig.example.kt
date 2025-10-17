package it.fabiodirauso.shutappchat.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import it.fabiodirauso.shutappchat.api.AppConfigResponse
import it.fabiodirauso.shutappchat.api.AppServer
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import it.fabiodirauso.shutappchat.network.ApiService

/**
 * IMPORTANT: This is a template file!
 * 
 * To configure your own server:
 * 1. Copy this file to ServerConfig.kt in the same directory
 * 2. Replace the placeholder URLs below with your actual server URLs
 * 3. Never commit ServerConfig.kt to version control (it's in .gitignore)
 */
object ServerConfig {
    // Replace with your WebSocket server URL
    const val WS_URL = "wss://your-server.example.com/ws"
    
    // Replace with your API base URL (must end with /)
    const val API_BASE_URL = "https://your-server.example.com/api/v2/"
    
    // Replace with your app links configuration URL
    const val APP_LINKS_URL = "https://your-server.example.com/api/v2/"
    
    fun getWebSocketUrl(): String {
        return WS_URL
    }
}

class AppConfigManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "AppConfigManager"
        private const val PREFS_NAME = "app_config"
        private const val KEY_API_BASE = "api_base"
        private const val KEY_WS_URL = "ws_url"
        private const val KEY_DONATE_URL = "donate_url"
        private const val KEY_DOWNLOAD_URL = "download_url"
        private const val KEY_CONFIG_TIMESTAMP = "config_timestamp"
        private const val CONFIG_CACHE_DURATION = 24 * 60 * 60 * 1000L
        
        @Volatile
        private var INSTANCE: AppConfigManager? = null
        
        fun getInstance(context: Context): AppConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppConfigManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    var apiBaseUrl: String = prefs.getString(KEY_API_BASE, ServerConfig.API_BASE_URL) ?: ServerConfig.API_BASE_URL
        private set
        
    var wsUrl: String = prefs.getString(KEY_WS_URL, ServerConfig.WS_URL) ?: ServerConfig.WS_URL
        private set
        
    var donateUrl: String = prefs.getString(KEY_DONATE_URL, "") ?: ""
        private set
        
    var downloadUrl: String = prefs.getString(KEY_DOWNLOAD_URL, "") ?: ""
        private set
    
    suspend fun loadConfig(forceRefresh: Boolean = false): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val lastUpdate = prefs.getLong(KEY_CONFIG_TIMESTAMP, 0)
                val now = System.currentTimeMillis()
                
                if (!forceRefresh && (now - lastUpdate) < CONFIG_CACHE_DURATION) {
                    Log.d(TAG, "Using cached configuration")
                    return@withContext true
                }
                
                Log.d(TAG, "Fetching fresh configuration from server")
                
                val retrofit = Retrofit.Builder()
                    .baseUrl(ServerConfig.APP_LINKS_URL)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                
                val apiService = retrofit.create(ApiService::class.java)
                val response = apiService.getAppConfig()
                
                if (response.isSuccessful && response.body() != null) {
                    val config = response.body()!!
                    updateConfiguration(config)
                    Log.d(TAG, "Configuration updated successfully")
                    true
                } else {
                    Log.e(TAG, "Failed to load configuration: ${esponse.code()}")
                    false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading configuration", e)
                false
            }
        }
    }
    
    private fun updateConfiguration(config: AppConfigResponse) {
        val primaryServer = config.servers.firstOrNull { it.name.contains("Produzione", ignoreCase = true) }
            ?: config.servers.firstOrNull()
        
        if (primaryServer != null) {
            apiBaseUrl = if (primaryServer.api_base.endsWith("/")) {
                primaryServer.api_base
            } else {
                primaryServer.api_base + "/"
            }
            wsUrl = primaryServer.ws_url
        }
        
        donateUrl = config.donate
        downloadUrl = config.download
        
        prefs.edit().apply {
            putString(KEY_API_BASE, apiBaseUrl)
            putString(KEY_WS_URL, wsUrl)
            putString(KEY_DONATE_URL, donateUrl)
            putString(KEY_DOWNLOAD_URL, downloadUrl)
            putLong(KEY_CONFIG_TIMESTAMP, System.currentTimeMillis())
            apply()
        }
        
        Log.d(TAG, "Configuration saved - API: $apiBaseUrl, WS: $wsUrl")
    }
    
    suspend fun getAvailableServers(): List<AppServer> {
        return withContext(Dispatchers.IO) {
            try {
                val retrofit = Retrofit.Builder()
                    .baseUrl(ServerConfig.APP_LINKS_URL)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                
                val apiService = retrofit.create(ApiService::class.java)
                val response = apiService.getAppConfig()
                
                if (response.isSuccessful && response.body() != null) {
                    response.body()!!.servers
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching servers list", e)
                emptyList()
            }
        }
    }
    
    suspend fun refreshConfig(): Boolean = loadConfig(forceRefresh = true)
}
