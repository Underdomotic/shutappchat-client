package it.fabiodirauso.shutappchat.utils

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Singleton per gestire la coda dei download video.
 * Garantisce che solo 1 video alla volta venga scaricato.
 */
object VideoDownloadQueue {
    private const val TAG = "VideoDownloadQueue"
    
    // Coda thread-safe per i download in attesa
    private val downloadQueue = ConcurrentLinkedQueue<DownloadTask>()
    
    // Job corrente in esecuzione
    private var currentJob: Job? = null
    
    // Flag per sapere se stiamo già processando
    private var isProcessing = false
    
    data class DownloadTask(
        val messageId: String,
        val mediaId: String,
        val mediaKey: String,
        val mediaIv: String,
        val onProgress: (Float) -> Unit,
        val onComplete: (success: Boolean) -> Unit
    )
    
    /**
     * Aggiunge un download alla coda.
     * Se non c'è nessun download in corso, inizia subito.
     */
    fun enqueue(
        messageId: String,
        mediaId: String,
        mediaKey: String,
        mediaIv: String,
        onProgress: (Float) -> Unit,
        onComplete: (success: Boolean) -> Unit
    ) {
        val task = DownloadTask(messageId, mediaId, mediaKey, mediaIv, onProgress, onComplete)
        
        // Rimuovi eventuali task duplicati per lo stesso messageId
        downloadQueue.removeAll { it.messageId == messageId }
        
        downloadQueue.offer(task)
        Log.d(TAG, "📥 Enqueued download for message $messageId (queue size: ${downloadQueue.size})")
        
        // Avvia processamento se non già in corso
        if (!isProcessing) {
            processNext()
        }
    }
    
    /**
     * Cancella un download specifico dalla coda (se non ancora iniziato).
     */
    fun cancel(messageId: String) {
        val removed = downloadQueue.removeAll { it.messageId == messageId }
        if (removed) {
            Log.d(TAG, "🚫 Cancelled download for message $messageId")
        }
    }
    
    /**
     * Processa il prossimo download nella coda.
     */
    private fun processNext() {
        if (isProcessing) {
            Log.d(TAG, "⏸️ Already processing, skipping")
            return
        }
        
        val task = downloadQueue.poll()
        if (task == null) {
            Log.d(TAG, "✅ Queue empty, waiting for new tasks")
            isProcessing = false
            return
        }
        
        isProcessing = true
        Log.d(TAG, "⬇️ Starting download for message ${task.messageId} (${downloadQueue.size} remaining)")
        
        // Esegui download in IO dispatcher
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                // Il download vero viene fatto dal chiamante tramite callback
                task.onProgress(0f)
                
                // Attendi completamento (simulato, il vero lavoro è nel callback)
                // Il chiamante chiamerà notifyComplete() quando finisce
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Download failed for message ${task.messageId}", e)
                task.onComplete(false)
                notifyComplete()
            }
        }
    }
    
    /**
     * Notifica che il download corrente è completato.
     * Passa al prossimo nella coda.
     */
    fun notifyComplete() {
        Log.d(TAG, "✅ Download completed, processing next...")
        isProcessing = false
        currentJob = null
        
        // Processa il prossimo
        processNext()
    }
    
    /**
     * Ritorna il task corrente in esecuzione (se presente).
     */
    fun getCurrentTask(): DownloadTask? {
        return if (isProcessing && downloadQueue.isNotEmpty()) {
            downloadQueue.peek()
        } else {
            null
        }
    }
    
    /**
     * Svuota completamente la coda.
     */
    fun clearAll() {
        downloadQueue.clear()
        currentJob?.cancel()
        currentJob = null
        isProcessing = false
        Log.d(TAG, "🗑️ Queue cleared")
    }
}
