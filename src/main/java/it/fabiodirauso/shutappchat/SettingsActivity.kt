package it.fabiodirauso.shutappchat

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Switch
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import it.fabiodirauso.shutappchat.database.AppDatabase
import it.fabiodirauso.shutappchat.managers.SecuritySettingsManager
import it.fabiodirauso.shutappchat.network.RetrofitClient
import it.fabiodirauso.shutappchat.api.SecurityUpdateRequest
import it.fabiodirauso.shutappchat.utils.AppConstants
import it.fabiodirauso.shutappchat.services.WebSocketService
import android.widget.Toast
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.app.ActivityManager
import android.content.Context

/**
 * Activity unificata per Impostazioni Privacy + Info App + Stato Servizi
 * Mantiene TUTTE le funzionalità di PrivacySettingsActivity e aggiunge nuove sezioni
 */
class SettingsActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "Settings"
    }
    
    private lateinit var database: AppDatabase
    private lateinit var securityManager: SecuritySettingsManager
    
    // Privacy switches
    private lateinit var switchReadReceipts: Switch
    private lateinit var switchBlockScreenshots: Switch
    private lateinit var switchProtectMedia: Switch
    private lateinit var switchObfuscateMedia: Switch
    private lateinit var switchAutoDeleteMedia: Switch
    private lateinit var switchAutoDownloadVideos: Switch
    
    // Info app
    private lateinit var tvVersionName: TextView
    private lateinit var tvVersionCode: TextView
    private lateinit var tvUserAgent: TextView
    
    // Stato servizi
    private lateinit var tvWebSocketStatus: TextView
    private lateinit var tvApiStatus: TextView
    private lateinit var tvDatabaseStatus: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        // Enable fullscreen immersive mode
        it.fabiodirauso.shutappchat.utils.UIHelper.enableImmersiveMode(this)
        
        database = AppDatabase.getDatabase(this)
        securityManager = SecuritySettingsManager.getInstance(this)
        
        setupToolbar()
        initializeViews()
        loadAppInfo()
        
        // Inizializza settings di default se non esistono
        lifecycleScope.launch {
            securityManager.initializeDefaultSettings()
            loadLocalSettings()
            syncSettingsToServer()  // Sincronizza locale  remoto
            updateServiceStatus()
        }
    }
    
    private fun setupToolbar() {
        supportActionBar?.apply {
            title = "Impostazioni"
            setDisplayHomeAsUpEnabled(true)
        }
    }
    
    private fun initializeViews() {
        // Privacy switches
        switchReadReceipts = findViewById(R.id.switchReadReceipts)
        switchBlockScreenshots = findViewById(R.id.switchBlockScreenshots)
        switchProtectMedia = findViewById(R.id.switchProtectMedia)
        switchObfuscateMedia = findViewById(R.id.switchObfuscateMedia)
        switchAutoDeleteMedia = findViewById(R.id.switchAutoDeleteMedia)
        switchAutoDownloadVideos = findViewById(R.id.switchAutoDownloadVideos)
        
        // Info app
        tvVersionName = findViewById(R.id.tvVersionName)
        tvVersionCode = findViewById(R.id.tvVersionCode)
        tvUserAgent = findViewById(R.id.tvUserAgent)
        
        // Stato servizi
        tvWebSocketStatus = findViewById(R.id.tvWebSocketStatus)
        tvApiStatus = findViewById(R.id.tvApiStatus)
        tvDatabaseStatus = findViewById(R.id.tvDatabaseStatus)
    }
    
    private fun loadAppInfo() {
        tvVersionName.text = AppConstants.getVersionName(this)
        tvVersionCode.text = AppConstants.getVersionCode(this).toString()
        tvUserAgent.text = AppConstants.getUserAgent(this)
    }
    
    /**
     * Carica le impostazioni dal DB locale (source of truth)
     * e aggiorna l'UI - MANTIENE LOGICA ORIGINALE
     */
    private fun loadLocalSettings() {
        lifecycleScope.launch {
            try {
                val settings = securityManager.getSettingsAsync()
                Log.d(TAG, "Settings caricati dal DB locale: $settings")
                
                // Disabilita listeners temporaneamente
                removeListeners()
                
                // Aggiorna switches con valori dal DB locale
                switchReadReceipts.isChecked = settings.allowReadReceipts
                switchBlockScreenshots.isChecked = settings.blockScreenshots
                switchProtectMedia.isChecked = settings.protectMedia
                switchObfuscateMedia.isChecked = settings.obfuscateMediaFiles
                switchAutoDeleteMedia.isChecked = settings.autoDeleteMediaOnOpen
                switchAutoDownloadVideos.isChecked = settings.autoDownloadVideos
                
                // Riabilita listeners
                setupListeners()
                
                Log.d(TAG, "UI aggiornata con settings locali")
            } catch (e: Exception) {
                Log.e(TAG, "Errore caricamento settings locali", e)
                showError("Errore caricamento impostazioni")
            }
        }
    }
    
    /**
     * Sincronizza i settings locali (Room DB) verso il server
     * LOCAL  REMOTE - MANTIENE LOGICA ORIGINALE
     */
    private fun syncSettingsToServer() {
        lifecycleScope.launch {
            try {
                val localSettings = securityManager.getSettingsAsync()
                Log.d(TAG, "Sincronizzazione localeremoto: $localSettings")
                
                val request = SecurityUpdateRequest(
                    allowReadReceipts = localSettings.allowReadReceipts,
                    blockScreenshots = localSettings.blockScreenshots,
                    protectMedia = localSettings.protectMedia,
                    obfuscateMediaFiles = localSettings.obfuscateMediaFiles,
                    autoDeleteMediaOnOpen = localSettings.autoDeleteMediaOnOpen
                )
                
                val response = RetrofitClient.apiService.updateSecuritySettings(request)
                
                if (response.isSuccessful && response.body()?.ok == true) {
                    Log.d(TAG, " Settings sincronizzati con successo verso il server")
                    securityManager.updateAllSettings(localSettings.copy(
                        lastSyncTimestamp = System.currentTimeMillis()
                    ))
                } else {
                    Log.w(TAG, " Sincronizzazione fallita: ${response.code()} ${response.message()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, " Errore durante sincronizzazione", e)
            }
        }
    }
    
    private fun removeListeners() {
        switchReadReceipts.setOnCheckedChangeListener(null)
        switchBlockScreenshots.setOnCheckedChangeListener(null)
        switchProtectMedia.setOnCheckedChangeListener(null)
        switchObfuscateMedia.setOnCheckedChangeListener(null)
        switchAutoDeleteMedia.setOnCheckedChangeListener(null)
        switchAutoDownloadVideos.setOnCheckedChangeListener(null)
    }
    
    private fun setupListeners() {
        switchReadReceipts.setOnCheckedChangeListener { _, isChecked ->
            updateSetting("allow_read_receipts", isChecked)
        }
        
        switchBlockScreenshots.setOnCheckedChangeListener { _, isChecked ->
            updateSetting("block_screenshots", isChecked)
        }
        
        switchProtectMedia.setOnCheckedChangeListener { _, isChecked ->
            updateSetting("protect_media", isChecked)
        }
        
        switchObfuscateMedia.setOnCheckedChangeListener { _, isChecked ->
            updateSetting("obfuscate_media_files", isChecked)
        }
        
        switchAutoDeleteMedia.setOnCheckedChangeListener { _, isChecked ->
            updateSetting("auto_delete_media_on_open", isChecked)
        }
        
        switchAutoDownloadVideos.setOnCheckedChangeListener { _, isChecked ->
            updateSetting("auto_download_videos", isChecked)
        }
    }
    
    /**
     * Aggiorna un singolo setting - MANTIENE LOGICA ORIGINALE
     * STRATEGIA: Aggiorna PRIMA il DB locale (immediato), POI sincronizza con il server
     */
    private fun updateSetting(settingName: String, enabled: Boolean) {
        Log.d(TAG, "Aggiornamento setting: $settingName = $enabled")
        lifecycleScope.launch {
            try {
                // 1. Aggiorna DB locale IMMEDIATAMENTE
                securityManager.updateLocalSetting(settingName, enabled)
                Log.d(TAG, " Setting $settingName aggiornato nel DB locale: $enabled")
                
                // 2. Feedback immediato
                showSuccess("Impostazione aggiornata")
                
                // 3. Sincronizza con server in background
                syncSingleSettingToServer(settingName, enabled)
                
            } catch (e: Exception) {
                Log.e(TAG, "Errore aggiornamento setting $settingName", e)
                showError("Errore salvataggio impostazione")
                loadLocalSettings()
            }
        }
    }
    
    /**
     * Sincronizza un singolo setting modificato verso il server - MANTIENE LOGICA ORIGINALE
     */
    private suspend fun syncSingleSettingToServer(settingName: String, enabled: Boolean) {
        try {
            val request = when (settingName) {
                "allow_read_receipts" -> SecurityUpdateRequest(allowReadReceipts = enabled)
                "block_screenshots" -> SecurityUpdateRequest(blockScreenshots = enabled)
                "protect_media" -> SecurityUpdateRequest(protectMedia = enabled)
                "auto_delete_media_on_open" -> SecurityUpdateRequest(autoDeleteMediaOnOpen = enabled)
                else -> {
                    Log.e(TAG, "Setting sconosciuto: $settingName")
                    return
                }
            }
            
            val response = RetrofitClient.apiService.updateSecuritySettings(request)
            
            if (response.isSuccessful && response.body()?.ok == true) {
                Log.d(TAG, " Setting $settingName sincronizzato con il server")
            } else {
                Log.w(TAG, " Errore sincronizzazione server: ${response.code()} ${response.message()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, " Eccezione durante sincronizzazione", e)
        }
    }
    
    /**
     * NUOVA FUNZIONE: Aggiorna lo stato dei servizi
     */
    private fun updateServiceStatus() {
        lifecycleScope.launch {
            // WebSocket status - controlla se il servizio è in esecuzione
            val wsRunning = isServiceRunning(WebSocketService::class.java)
            withContext(Dispatchers.Main) {
                if (wsRunning) {
                    tvWebSocketStatus.text = "WebSocket: Connesso"
                    tvWebSocketStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                } else {
                    tvWebSocketStatus.text = "WebSocket: Disconnesso"
                    tvWebSocketStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                }
            }
            
            // API status - test con app config endpoint
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.getAppConfig()
                }
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        tvApiStatus.text = "API Server: Online"
                        tvApiStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                    } else {
                        tvApiStatus.text = "API Server: Errore ${response.code()}"
                        tvApiStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvApiStatus.text = "API Server: Offline"
                    tvApiStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                }
            }
            
            // Database status
            try {
                withContext(Dispatchers.IO) {
                    database.conversationDao().getAllConversations()
                }
                withContext(Dispatchers.Main) {
                    tvDatabaseStatus.text = "Database: OK"
                    tvDatabaseStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvDatabaseStatus.text = "Database: Errore"
                    tvDatabaseStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                }
            }
        }
    }
    
    /**
     * Helper per verificare se un servizio è in esecuzione
     */
    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
    
    private fun showSuccess(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
    
    override fun onResume() {
        super.onResume()
        // Aggiorna stato servizi quando si torna alla schermata
        updateServiceStatus()
    }
}
