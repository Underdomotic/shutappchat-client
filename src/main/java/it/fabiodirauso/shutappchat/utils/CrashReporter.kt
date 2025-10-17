package it.fabiodirauso.shutappchat.utils

import android.content.Context
import android.util.Log
import it.fabiodirauso.shutappchat.services.LogUploadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sistema di crash reporting automatico
 * Intercetta le eccezioni non gestite e invia automaticamente i log al server
 */
object CrashReporter {
    
    private const val TAG = "CrashReporter"
    private const val CRASH_LOG_DIR = "crash_logs"
    
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: Context? = null
    
    /**
     * Inizializza il crash reporter
     * Da chiamare in Application.onCreate()
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        
        // Salva il default handler
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        // Imposta il nostro handler personalizzato
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleCrash(thread, throwable)
        }
        
        Log.i(TAG, "CrashReporter inizializzato")
        
        // Controlla se ci sono crash precedenti da inviare
        sendPendingCrashLogs()
    }
    
    /**
     * Gestisce un crash dell'app
     */
    private fun handleCrash(thread: Thread, throwable: Throwable) {
        try {
            Log.e(TAG, "App crash detected on thread: ${thread.name}", throwable)
            
            // Salva il crash log localmente
            saveCrashLog(thread, throwable)
            
            // Invia immediatamente i log al server (in background)
            sendCrashLogsAsync()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling crash", e)
        } finally {
            // Chiama il default handler per terminare l'app normalmente
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
    
    /**
     * Salva il crash log in un file locale
     */
    private fun saveCrashLog(thread: Thread, throwable: Throwable) {
        try {
            val context = appContext ?: return
            
            // Crea directory per crash logs
            val crashDir = File(context.filesDir, CRASH_LOG_DIR)
            if (!crashDir.exists()) {
                crashDir.mkdirs()
            }
            
            // Nome file con timestamp
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val crashFile = File(crashDir, "crash_$timestamp.txt")
            
            // Scrivi le informazioni del crash
            FileOutputStream(crashFile).use { fos ->
                val writer = PrintWriter(fos)
                
                writer.println("=== APP CRASH REPORT ===")
                writer.println("Timestamp: ${Date()}")
                writer.println("Thread: ${thread.name}")
                writer.println("Thread ID: ${thread.id}")
                writer.println()
                
                // Device info
                writer.println("=== DEVICE INFO ===")
                writer.println("Manufacturer: ${android.os.Build.MANUFACTURER}")
                writer.println("Model: ${android.os.Build.MODEL}")
                writer.println("Android Version: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                writer.println()
                
                // App info
                writer.println("=== APP INFO ===")
                try {
                    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    writer.println("Version: ${packageInfo.versionName} (${packageInfo.versionCode})")
                    writer.println("Package: ${context.packageName}")
                } catch (e: Exception) {
                    writer.println("Error getting app info: ${e.message}")
                }
                writer.println()
                
                // Stack trace
                writer.println("=== STACK TRACE ===")
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                writer.println(sw.toString())
                
                // Cause chain
                var cause = throwable.cause
                var level = 1
                while (cause != null) {
                    writer.println()
                    writer.println("=== CAUSED BY (Level $level) ===")
                    val causeSw = StringWriter()
                    cause.printStackTrace(PrintWriter(causeSw))
                    writer.println(causeSw.toString())
                    cause = cause.cause
                    level++
                }
                
                writer.flush()
            }
            
            Log.i(TAG, "Crash log saved: ${crashFile.absolutePath}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving crash log", e)
        }
    }
    
    /**
     * Invia i crash log al server in modo asincrono
     */
    private fun sendCrashLogsAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Thread.sleep(500) // Piccolo delay per permettere al file di essere salvato
                
                val context = appContext ?: return@launch
                
                // Usa LogUploadService per inviare i log
                val result = LogUploadService.collectAndUploadLogs(context)
                
                if (result.isSuccess) {
                    Log.i(TAG, "Crash logs sent successfully: ${result.getOrNull()}")
                    
                    // Elimina i crash log locali dopo l'invio
                    deleteCrashLogs()
                } else {
                    Log.e(TAG, "Failed to send crash logs: ${result.exceptionOrNull()?.message}")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sending crash logs", e)
            }
        }
    }
    
    /**
     * Invia crash log pendenti (salvati in precedenza)
     * Da chiamare all'avvio dell'app
     */
    private fun sendPendingCrashLogs() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val context = appContext ?: return@launch
                
                val crashDir = File(context.filesDir, CRASH_LOG_DIR)
                if (!crashDir.exists()) return@launch
                
                val crashFiles = crashDir.listFiles()?.filter { it.extension == "txt" }
                
                if (crashFiles.isNullOrEmpty()) {
                    Log.d(TAG, "No pending crash logs")
                    return@launch
                }
                
                Log.i(TAG, "Found ${crashFiles.size} pending crash logs, sending...")
                
                // Invia i log
                val result = LogUploadService.collectAndUploadLogs(context)
                
                if (result.isSuccess) {
                    Log.i(TAG, "Pending crash logs sent successfully")
                    deleteCrashLogs()
                } else {
                    Log.e(TAG, "Failed to send pending crash logs: ${result.exceptionOrNull()?.message}")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sending pending crash logs", e)
            }
        }
    }
    
    /**
     * Elimina i crash log locali
     */
    private fun deleteCrashLogs() {
        try {
            val context = appContext ?: return
            
            val crashDir = File(context.filesDir, CRASH_LOG_DIR)
            if (crashDir.exists()) {
                crashDir.listFiles()?.forEach { it.delete() }
                Log.d(TAG, "Crash logs deleted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting crash logs", e)
        }
    }
}
