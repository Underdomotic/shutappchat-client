package it.fabiodirauso.shutappchat

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import it.fabiodirauso.shutappchat.databinding.ActivityPrivacySettingsBinding
import it.fabiodirauso.shutappchat.database.AppDatabase
import it.fabiodirauso.shutappchat.managers.SecuritySettingsManager
import it.fabiodirauso.shutappchat.model.PrivacySettingsEntity
import it.fabiodirauso.shutappchat.network.RetrofitClient
import it.fabiodirauso.shutappchat.api.SecurityUpdateRequest
import kotlinx.coroutines.launch

/**
 * Activity per gestire le impostazioni di privacy e sicurezza dell'utente
 * STRATEGIA DI SINCRONIZZAZIONE: Local (Room DB) → Remote (API Server)
 * - Il DB locale è la source of truth
 * - All'apertura della pagina, sincronizziamo i settings locali verso il server
 * - Quando l'utente cambia un setting, aggiorniamo prima il DB locale, poi il server
 */
class PrivacySettingsActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "PrivacySettings"
    }
    
    private lateinit var binding: ActivityPrivacySettingsBinding
    private lateinit var database: AppDatabase
    private lateinit var securityManager: SecuritySettingsManager
    
    // Switches per le impostazioni
    private lateinit var switchReadReceipts: Switch
    private lateinit var switchBlockScreenshots: Switch
    private lateinit var switchProtectMedia: Switch
    private lateinit var switchObfuscateMedia: Switch
    private lateinit var switchAutoDeleteMedia: Switch
    private lateinit var switchAutoDownloadVideos: Switch  // 🎯 NUOVO
    
    // Debug button
    private lateinit var btnTestCrash: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Enable fullscreen immersive mode
        it.fabiodirauso.shutappchat.utils.UIHelper.enableImmersiveMode(this)
        
        database = AppDatabase.getDatabase(this)
        securityManager = SecuritySettingsManager.getInstance(this)
        
        setupToolbar()
        initializeViews()
        
        // Inizializza settings di default se non esistono
        lifecycleScope.launch {
            securityManager.initializeDefaultSettings()
            loadLocalSettings()
            syncSettingsToServer()  // Sincronizza locale → remoto
        }
    }
    
    private fun setupToolbar() {
        supportActionBar?.apply {
            title = "Impostazioni Privacy"
            setDisplayHomeAsUpEnabled(true)
        }
    }
    
    private fun initializeViews() {
        switchReadReceipts = binding.switchReadReceipts
        switchBlockScreenshots = binding.switchBlockScreenshots
        switchProtectMedia = binding.switchProtectMedia
        switchObfuscateMedia = binding.switchObfuscateMedia
        switchAutoDeleteMedia = binding.switchAutoDeleteMedia
        switchAutoDownloadVideos = binding.switchAutoDownloadVideos  // 🎯 NUOVO
        
        // Debug button
        btnTestCrash = binding.btnTestCrash
        btnTestCrash.setOnClickListener {
            showCrashTestDialog()
        }
    }
    
    /**
     * Carica le impostazioni dal DB locale (source of truth)
     * e aggiorna l'UI
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
                switchAutoDownloadVideos.isChecked = settings.autoDownloadVideos  // 🎯 NUOVO
                
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
     * LOCAL → REMOTE
     */
    private fun syncSettingsToServer() {
        lifecycleScope.launch {
            try {
                val localSettings = securityManager.getSettingsAsync()
                Log.d(TAG, "Sincronizzazione locale→remoto: $localSettings")
                
                val request = SecurityUpdateRequest(
                    allowReadReceipts = localSettings.allowReadReceipts,
                    blockScreenshots = localSettings.blockScreenshots,
                    protectMedia = localSettings.protectMedia,
                    obfuscateMediaFiles = localSettings.obfuscateMediaFiles,
                    autoDeleteMediaOnOpen = localSettings.autoDeleteMediaOnOpen
                )
                
                val response = RetrofitClient.apiService.updateSecuritySettings(request)
                
                if (response.isSuccessful && response.body()?.ok == true) {
                    Log.d(TAG, "✅ Settings sincronizzati con successo verso il server")
                    // Aggiorna timestamp di sincronizzazione
                    securityManager.updateAllSettings(localSettings.copy(
                        lastSyncTimestamp = System.currentTimeMillis()
                    ))
                } else {
                    Log.w(TAG, "⚠️ Sincronizzazione fallita: ${response.code()} ${response.message()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Errore durante sincronizzazione", e)
                // Non mostriamo errore all'utente, la sincronizzazione è in background
            }
        }
    }
    
    
    private fun removeListeners() {
        switchReadReceipts.setOnCheckedChangeListener(null)
        switchBlockScreenshots.setOnCheckedChangeListener(null)
        switchProtectMedia.setOnCheckedChangeListener(null)
        switchObfuscateMedia.setOnCheckedChangeListener(null)
        switchAutoDeleteMedia.setOnCheckedChangeListener(null)
    }
    
    private fun setupListeners() {
        // Listener per conferme di lettura
        switchReadReceipts.setOnCheckedChangeListener { _, isChecked ->
            updateSetting("allow_read_receipts", isChecked)
        }
        
        // Listener per blocco screenshot
        switchBlockScreenshots.setOnCheckedChangeListener { _, isChecked ->
            updateSetting("block_screenshots", isChecked)
        }
        
        // Listener per protezione media
        switchProtectMedia.setOnCheckedChangeListener { _, isChecked ->
            updateSetting("protect_media", isChecked)
        }
        
        // Listener per offuscamento file media
        switchObfuscateMedia.setOnCheckedChangeListener { _, isChecked ->
            updateSetting("obfuscate_media_files", isChecked)
        }
        
        // Listener per auto-eliminazione media
        switchAutoDeleteMedia.setOnCheckedChangeListener { _, isChecked ->
            updateSetting("auto_delete_media_on_open", isChecked)
        }
        
        // Listener per download automatico video
        switchAutoDownloadVideos.setOnCheckedChangeListener { _, isChecked ->
            updateSetting("auto_download_videos", isChecked)
        }
    }
    
    /**
     * Aggiorna un singolo setting
     * STRATEGIA: Aggiorna PRIMA il DB locale (immediato), POI sincronizza con il server
     */
    private fun updateSetting(settingName: String, enabled: Boolean) {
        Log.d(TAG, "Aggiornamento setting: $settingName = $enabled")
        lifecycleScope.launch {
            try {
                // 1. Aggiorna DB locale IMMEDIATAMENTE (source of truth)
                securityManager.updateLocalSetting(settingName, enabled)
                Log.d(TAG, "✅ Setting $settingName aggiornato nel DB locale: $enabled")
                
                // 2. Feedback immediato all'utente
                showSuccess("Impostazione aggiornata")
                
                // 3. Sincronizza con il server in background
                syncSingleSettingToServer(settingName, enabled)
                
            } catch (e: Exception) {
                Log.e(TAG, "Errore aggiornamento setting $settingName", e)
                showError("Errore salvataggio impostazione")
                
                // Rollback: ricarica settings dal DB
                loadLocalSettings()
            }
        }
    }
    
    /**
     * Sincronizza un singolo setting modificato verso il server
     */
    private suspend fun syncSingleSettingToServer(settingName: String, enabled: Boolean) {
        try {
            val request = when (settingName) {
                "allow_read_receipts" -> SecurityUpdateRequest(allowReadReceipts = enabled)
                "block_screenshots" -> SecurityUpdateRequest(blockScreenshots = enabled)
                "protect_media" -> SecurityUpdateRequest(protectMedia = enabled)
                "obfuscate_media_files" -> SecurityUpdateRequest(obfuscateMediaFiles = enabled)
                "auto_delete_media_on_open" -> SecurityUpdateRequest(autoDeleteMediaOnOpen = enabled)
                else -> {
                    Log.e(TAG, "Setting sconosciuto: $settingName")
                    return
                }
            }
            
            val response = RetrofitClient.apiService.updateSecuritySettings(request)
            
            if (response.isSuccessful && response.body()?.ok == true) {
                Log.d(TAG, "✅ Setting $settingName sincronizzato con il server")
            } else {
                Log.w(TAG, "⚠️ Errore sincronizzazione server: ${response.code()} ${response.message()}")
                // Non facciamo rollback perché il locale è source of truth
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Eccezione durante sincronizzazione", e)
            // Non facciamo rollback perché il locale è source of truth
        }
    }
    
    private fun showSuccess(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    
    /**
     * Mostra un dialogo di conferma prima di forzare un crash
     */
    private fun showCrashTestDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Test Crash Reporter")
            .setMessage(
                "Questa azione forzerà un crash dell'app per testare il sistema di crash reporting.\n\n" +
                "L'app si chiuderà e:\n" +
                "1. Verrà salvato un crash report locale\n" +
                "2. I log verranno inviati automaticamente al server\n" +
                "3. Potrai vedere i log nel pannello admin\n\n" +
                "Vuoi continuare?"
            )
            .setPositiveButton("💥 Forza Crash") { _, _ ->
                Log.w(TAG, "🔴 CRASH TEST - Utente ha confermato il test crash")
                
                // Piccolo delay per permettere al dialogo di chiudersi
                binding.root.postDelayed({
                    // Lancia un'eccezione non gestita per testare il crash reporter
                    throw RuntimeException(
                        "🧪 TEST CRASH - Crash intenzionale per testare il sistema di crash reporting automatico.\n" +
                        "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                        "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n" +
                        "Timestamp: ${java.util.Date()}"
                    )
                }, 500)
            }
            .setNegativeButton("❌ Annulla", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}