package it.fabiodirauso.shutappchat.services

import android.content.Context
import android.util.Log
import it.fabiodirauso.shutappchat.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogUploadService {
    private const val TAG = "LogUploadService"
    
    /**
     * Raccoglie i log Logcat delle ultime 24 ore, li comprime in un file ZIP
     * e li carica sul server
     */
    suspend fun collectAndUploadLogs(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Avvio raccolta log delle ultime 24 ore...")
            
            // 1. Raccogli i log
            val logFile = collectLogcat24Hours(context)
            if (logFile == null || !logFile.exists()) {
                return@withContext Result.failure(Exception("Impossibile raccogliere i log"))
            }
            
            Log.d(TAG, "Log raccolti: ${logFile.length()} bytes")
            
            // 2. Comprimi in ZIP
            val zipFile = compressToZip(logFile, context)
            if (zipFile == null || !zipFile.exists()) {
                logFile.delete()
                return@withContext Result.failure(Exception("Impossibile comprimere i log"))
            }
            
            Log.d(TAG, "Log compressi: ${zipFile.length()} bytes")
            
            // 3. Upload al server
            val uploadResult = uploadLogFile(zipFile)
            
            // 4. Cleanup
            logFile.delete()
            zipFile.delete()
            
            uploadResult
            
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante raccolta/upload log", e)
            Result.failure(e)
        }
    }
    
    /**
     * Raccoglie i log Logcat delle ultime 24 ore (solo della nostra app)
     */
    private fun collectLogcat24Hours(context: Context): File? {
        return try {
            val logFile = File(context.cacheDir, "logcat_24h.txt")
            
            // Ottieni il package name dell'app per filtrare solo i nostri log
            val packageName = context.packageName
            val pid = android.os.Process.myPid()
            
            Log.d(TAG, "Raccolta log per package: $packageName, PID: $pid")
            
            // Raccoglie gli ultimi log disponibili nel buffer filtrati per la nostra app
            // --pid=<pid> = filtra solo i log del nostro processo
            // -v time = include timestamp per ogni riga
            // -t <count> = mostra le ultime <count> righe
            val process = Runtime.getRuntime().exec(arrayOf(
                "logcat",
                "-d",              // dump (non segue in tempo reale)
                "-v", "time",      // formato con timestamp
                "--pid=$pid",      // filtra solo il nostro processo
                "-t", "10000",     // ultime 10000 righe
                "*:V"              // tutti i tag e livelli (Verbose)
            ))
            
            val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
            val fileOutputStream = FileOutputStream(logFile)
            
            var lineCount = 0
            bufferedReader.useLines { lines ->
                lines.forEach { line ->
                    fileOutputStream.write("$line\n".toByteArray())
                    lineCount++
                }
            }
            
            fileOutputStream.close()
            process.waitFor()
            
            Log.d(TAG, "Raccolte $lineCount righe di log per $packageName")
            logFile
        } catch (e: Exception) {
            Log.e(TAG, "Errore raccolta logcat", e)
            null
        }
    }
    
    /**
     * Comprimi il file di log in ZIP, includendo anche i crash report se presenti
     */
    private fun compressToZip(logFile: File, context: Context): File? {
        return try {
            val zipFile = File(context.cacheDir, "logcat_24h.zip")
            
            ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                // 1. Aggiungi il file logcat
                logFile.inputStream().use { inputStream ->
                    val entry = ZipEntry(logFile.name)
                    zipOut.putNextEntry(entry)
                    
                    val buffer = ByteArray(8192)
                    var length: Int
                    while (inputStream.read(buffer).also { length = it } > 0) {
                        zipOut.write(buffer, 0, length)
                    }
                    
                    zipOut.closeEntry()
                }
                
                // 2. Aggiungi i crash report se esistono
                val crashDir = File(context.filesDir, "crash_logs")
                if (crashDir.exists() && crashDir.isDirectory) {
                    val crashFiles = crashDir.listFiles()?.filter { it.extension == "txt" }
                    if (!crashFiles.isNullOrEmpty()) {
                        Log.d(TAG, "Trovati ${crashFiles.size} crash report da includere")
                        
                        crashFiles.forEach { crashFile ->
                            crashFile.inputStream().use { inputStream ->
                                // Mantieni la struttura: crash_logs/crash_*.txt
                                val entry = ZipEntry("crash_logs/${crashFile.name}")
                                zipOut.putNextEntry(entry)
                                
                                val buffer = ByteArray(8192)
                                var length: Int
                                while (inputStream.read(buffer).also { length = it } > 0) {
                                    zipOut.write(buffer, 0, length)
                                }
                                
                                zipOut.closeEntry()
                            }
                        }
                    }
                }
            }
            
            zipFile
        } catch (e: Exception) {
            Log.e(TAG, "Errore compressione ZIP", e)
            null
        }
    }
    
    /**
     * Upload del file ZIP al server
     */
    private suspend fun uploadLogFile(zipFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Upload log al server...")
            
            val requestBody = zipFile.asRequestBody("application/zip".toMediaTypeOrNull())
            val multipartBody = MultipartBody.Part.createFormData("logfile", zipFile.name, requestBody)
            
            val response = RetrofitClient.apiService.uploadLogs(multipartBody)
            
            if (response.isSuccessful) {
                val body = response.body()
                val success = body?.get("success")?.asJsonPrimitive?.asBoolean ?: false
                if (success) {
                    val filename = body?.get("filename")?.asJsonPrimitive?.asString ?: "unknown"
                    Log.i(TAG, "Upload completato: $filename")
                    Result.success(filename)
                } else {
                    val error = body?.get("error")?.asJsonPrimitive?.asString ?: "Errore sconosciuto"
                    Result.failure(Exception(error))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code()}: ${response.message()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore upload", e)
            Result.failure(e)
        }
    }
}
