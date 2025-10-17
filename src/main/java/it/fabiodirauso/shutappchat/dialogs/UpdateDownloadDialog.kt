package it.fabiodirauso.shutappchat.dialogs

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Window
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import it.fabiodirauso.shutappchat.R
import it.fabiodirauso.shutappchat.database.AppDatabase
import it.fabiodirauso.shutappchat.model.ForceUpdateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class UpdateDownloadDialog(
    context: Context,
    private val version: String,
    private val message: String,
    private val downloadUrl: String
) : Dialog(context) {

    companion object {
        // ✅ Fallback URL fisso per prevenire errori quando download_url è vuoto
        private const val FALLBACK_DOWNLOAD_URL = "https://shutappchat.fabiodirauso.it/api/uploads/apk/shutappchat-latest.apk"
    }

    private lateinit var tvDownloadTitle: TextView
    private lateinit var tvDownloadVersion: TextView
    private lateinit var tvDownloadProgress: TextView
    private lateinit var tvDownloadSize: TextView
    private lateinit var tvDownloadStatus: TextView
    private lateinit var progressBar: ProgressBar
    
    private val scope = CoroutineScope(Dispatchers.Main)
    private val database by lazy { AppDatabase.getDatabase(context) }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_download_update)
        
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        
        tvDownloadTitle = findViewById(R.id.tvDownloadTitle)
        tvDownloadVersion = findViewById(R.id.tvDownloadVersion)
        tvDownloadProgress = findViewById(R.id.tvDownloadProgress)
        tvDownloadSize = findViewById(R.id.tvDownloadSize)
        tvDownloadStatus = findViewById(R.id.tvDownloadStatus)
        progressBar = findViewById(R.id.progressBar)
        
        tvDownloadVersion.text = "Versione $version"
        
        // ✅ Verifica permessi prima di iniziare
        checkPermissionsAndStartDownload()
    }
    
    private fun checkPermissionsAndStartDownload() {
        // Verifica permesso di installazione da fonti sconosciute su Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                android.util.Log.w("UpdateDownload", "Install permission not granted, requesting...")
                tvDownloadStatus.text = "Richiesta permessi installazione..."
                
                // Mostra dialog di spiegazione
                val builder = android.app.AlertDialog.Builder(context)
                builder.setTitle("Permesso Richiesto")
                builder.setMessage("Per installare l'aggiornamento è necessario concedere il permesso di installare app da questa fonte.\n\nClicca OK per aprire le impostazioni.")
                builder.setPositiveButton("OK") { _, _ ->
                    try {
                        // Apri impostazioni per concedere permesso
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        
                        // Dopo 3 secondi, controlla se è stato concesso
                        scope.launch {
                            kotlinx.coroutines.delay(3000)
                            if (context.packageManager.canRequestPackageInstalls()) {
                                android.util.Log.i("UpdateDownload", "Permission granted, starting download")
                                startDownload()
                            } else {
                                android.util.Log.w("UpdateDownload", "Permission still denied, opening browser")
                                openDownloadInBrowser()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("UpdateDownload", "Error opening settings", e)
                        openDownloadInBrowser()
                    }
                }
                builder.setNegativeButton("Usa Browser") { _, _ ->
                    android.util.Log.i("UpdateDownload", "User chose browser download")
                    openDownloadInBrowser()
                }
                builder.setCancelable(false)
                builder.show()
                return
            }
        }
        
        // Permesso già concesso o Android < 8, procedi con download
        android.util.Log.i("UpdateDownload", "Permission granted, starting download")
        startDownload()
    }
    
    private fun openDownloadInBrowser() {
        try {
            // Usa fallback URL se necessario
            val actualUrl = if (downloadUrl.isNullOrBlank() || !downloadUrl.startsWith("http")) {
                FALLBACK_DOWNLOAD_URL
            } else {
                downloadUrl
            }
            
            android.util.Log.i("UpdateDownload", "Opening browser for download: $actualUrl")
            
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(actualUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            
            Toast.makeText(
                context,
                "Download avviato nel browser. Installa manualmente l'APK.",
                Toast.LENGTH_LONG
            ).show()
            
            // Segna come installing per non riaprire il dialog
            scope.launch(Dispatchers.IO) {
                database.forceUpdateDao().updateInstallingStatus(version, true)
            }
            
            dismiss()
            
        } catch (e: Exception) {
            android.util.Log.e("UpdateDownload", "Error opening browser", e)
            Toast.makeText(
                context,
                "Errore apertura browser: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    private fun startDownload() {
        android.util.Log.d("UpdateDownload", "Starting update download")
        android.util.Log.d("UpdateDownload", "Version: $version")
        android.util.Log.d("UpdateDownload", "Download URL: $downloadUrl")
        
        scope.launch {
            try {
                // Salva nel database che stiamo scaricando
                database.forceUpdateDao().insertForceUpdate(
                    ForceUpdateEntity(
                        version = version,
                        message = message,
                        downloadUrl = downloadUrl,
                        isDownloading = true,
                        downloadProgress = 0
                    )
                )
                
                tvDownloadStatus.text = "Connessione al server..."
                
                val apkFile = downloadApk()
                
                if (apkFile != null) {
                    tvDownloadStatus.text = "Download completato! "
                    tvDownloadProgress.text = "100%"
                    progressBar.progress = 100
                    
                    // Segna come "installing" nel DB (così non verrà più mostrato il dialog)
                    database.forceUpdateDao().updateInstallingStatus(version, true)
                    
                    // Aspetta 500ms per mostrare il completamento
                    kotlinx.coroutines.delay(500)
                    
                    // Avvia installazione
                    installApk(apkFile)
                    
                    dismiss()
                } else {
                    tvDownloadStatus.text = " Download fallito"
                    Toast.makeText(context, "Errore durante il download", Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
                tvDownloadStatus.text = " Errore: ${e.message}"
                Toast.makeText(context, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private suspend fun downloadApk(): File? = withContext(Dispatchers.IO) {
        try {
            // ✅ Usa fallback URL se downloadUrl è vuoto o invalido
            val actualUrl = if (downloadUrl.isNullOrBlank() || !downloadUrl.startsWith("http")) {
                android.util.Log.w("UpdateDownload", "Download URL vuoto o invalido: '$downloadUrl', uso fallback")
                FALLBACK_DOWNLOAD_URL
            } else {
                downloadUrl
            }
            
            android.util.Log.d("UpdateDownload", "Starting download from: $actualUrl")
            
            val client = OkHttpClient()
            val request = Request.Builder().url(actualUrl).build()
            val response = client.newCall(request).execute()
            
            android.util.Log.d("UpdateDownload", "Response code: ${response.code}")
            
            if (!response.isSuccessful) {
                android.util.Log.e("UpdateDownload", "Download failed with code: ${response.code}")
                withContext(Dispatchers.Main) {
                    tvDownloadStatus.text = "❌ Errore HTTP: ${response.code}"
                }
                return@withContext null
            }
            
            val body = response.body
            if (body == null) {
                android.util.Log.e("UpdateDownload", "Response body is null")
                withContext(Dispatchers.Main) {
                    tvDownloadStatus.text = "❌ Errore: file vuoto"
                }
                return@withContext null
            }
            
            val contentLength = body.contentLength()
            android.util.Log.d("UpdateDownload", "Content length: $contentLength bytes")
            
            val apkFile = File(context.getExternalFilesDir(null), "update_$version.apk")
            android.util.Log.d("UpdateDownload", "Saving to: ${apkFile.absolutePath}")
            
            val outputStream = FileOutputStream(apkFile)
            val inputStream = body.byteStream()
            
            val buffer = ByteArray(8192)
            var downloaded: Long = 0
            var read: Int
            
            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
                downloaded += read
                
                val progress = ((downloaded * 100) / contentLength).toInt()
                val downloadedMB = downloaded / (1024.0 * 1024.0)
                val totalMB = contentLength / (1024.0 * 1024.0)
                
                withContext(Dispatchers.Main) {
                    progressBar.progress = progress
                    tvDownloadProgress.text = "$progress%"
                    tvDownloadSize.text = String.format("%.1f MB / %.1f MB", downloadedMB, totalMB)
                    tvDownloadStatus.text = "Download in corso..."
                    
                    // Aggiorna il progresso nel database
                    scope.launch(Dispatchers.IO) {
                        database.forceUpdateDao().updateDownloadProgress(version, true, progress)
                    }
                }
            }
            
            outputStream.flush()
            outputStream.close()
            inputStream.close()
            
            android.util.Log.d("UpdateDownload", "Download completed: ${apkFile.length()} bytes")
            
            return@withContext apkFile
            
        } catch (e: Exception) {
            android.util.Log.e("UpdateDownload", "Download error", e)
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                tvDownloadStatus.text = "❌ Errore: ${e.javaClass.simpleName}: ${e.message}"
            }
            return@withContext null
        }
    }
    
    private fun installApk(apkFile: File) {
        try {
            // Verifica permesso di installazione da fonti sconosciute su Android 8+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    // Richiedi permesso
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                    Toast.makeText(
                        context,
                        "Abilita l\'installazione da fonti sconosciute per continuare",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
            }
            
            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.startActivity(intent)
            
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Errore installazione: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}