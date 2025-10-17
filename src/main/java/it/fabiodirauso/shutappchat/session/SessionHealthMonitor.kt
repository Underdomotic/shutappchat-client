package it.fabiodirauso.shutappchat.session

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Monitora la salute della sessione WebSocket.
 * Rileva problemi ripetuti di autenticazione e triggera invalidazione sessione.
 */
class SessionHealthMonitor private constructor() {
    
    companion object {
        private const val TAG = "SessionHealthMonitor"
        private const val MAX_FAILURES_THRESHOLD = 5  // Max errori prima di invalidare
        private const val FAILURE_WINDOW_MS = 30_000L // Finestra di 30 secondi
        
        @Volatile
        private var instance: SessionHealthMonitor? = null
        
        fun getInstance(): SessionHealthMonitor {
            return instance ?: synchronized(this) {
                instance ?: SessionHealthMonitor().also { instance = it }
            }
        }
    }
    
    // StateFlow per notificare l'UI che la sessione è invalida
    private val _sessionInvalid = MutableStateFlow(false)
    val sessionInvalid: StateFlow<Boolean> = _sessionInvalid.asStateFlow()
    
    // Coda di timestamp dei fallimenti recenti
    private val failureTimestamps = ConcurrentLinkedQueue<Long>()
    
    // Contatore fallimenti totali (per statistiche)
    private val totalFailures = AtomicInteger(0)
    
    /**
     * Registra un fallimento di connessione WebSocket
     */
    fun recordWebSocketFailure(reason: String = "unknown") {
        val now = System.currentTimeMillis()
        failureTimestamps.offer(now)
        totalFailures.incrementAndGet()
        
        // Rimuovi fallimenti vecchi (fuori dalla finestra)
        cleanOldFailures(now)
        
        val recentFailures = failureTimestamps.size
        Log.w(TAG, "WebSocket failure recorded: reason='reason', recent=recentFailures/MAX_FAILURES_THRESHOLD")
        
        // Se abbiamo troppi fallimenti recenti, invalida la sessione
        if (recentFailures >= MAX_FAILURES_THRESHOLD) {
            invalidateSession(recentFailures)
        }
    }
    
    /**
     * Registra una connessione WebSocket riuscita (resetta i contatori)
     */
    fun recordWebSocketSuccess() {
        failureTimestamps.clear()
        Log.d(TAG, "WebSocket connected successfully, failure count reset")
    }
    
    /**
     * Invalida la sessione e notifica l'UI
     */
    private fun invalidateSession(failureCount: Int) {
        if (_sessionInvalid.value) {
            return // Già invalidata
        }
        
        Log.e(TAG, "Session INVALIDATED: failureCount failures in {FAILURE_WINDOW_MS}ms window")
        _sessionInvalid.value = true
    }
    
    /**
     * Resetta lo stato (da chiamare dopo re-login)
     */
    fun reset() {
        failureTimestamps.clear()
        _sessionInvalid.value = false
        totalFailures.set(0)
        Log.d(TAG, "SessionHealthMonitor reset")
    }
    
    /**
     * Rimuove fallimenti vecchi dalla coda
     */
    private fun cleanOldFailures(currentTime: Long) {
        val cutoff = currentTime - FAILURE_WINDOW_MS
        while (failureTimestamps.peek()?.let { it < cutoff } == true) {
            failureTimestamps.poll()
        }
    }
    
    /**
     * Statistiche (per debug)
     */
    fun getStats(): String {
        return "Failures: recent={failureTimestamps.size}, total={totalFailures.get()}, invalid={_sessionInvalid.value}"
    }
}
