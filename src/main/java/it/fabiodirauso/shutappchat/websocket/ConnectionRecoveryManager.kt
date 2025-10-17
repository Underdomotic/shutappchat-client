package it.fabiodirauso.shutappchat.websocket

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import kotlin.math.pow

/**
 * Manages WebSocket connection recovery with:
 * - Exponential backoff retry
 * - Network state monitoring
 * - Session validation before reconnect
 * - Connection health tracking
 */
class ConnectionRecoveryManager(
    private val context: Context,
    private val onReconnect: suspend () -> Unit
) {
    
    companion object {
        private const val TAG = "ConnectionRecovery"
        
        // Exponential backoff parameters
        private const val INITIAL_RETRY_DELAY_MS = 1000L  // 1 second
        private const val MAX_RETRY_DELAY_MS = 64000L     // 64 seconds
        private const val MAX_RETRY_ATTEMPTS = 10
        
        // Connection health
        private const val HEALTH_CHECK_INTERVAL_MS = 30000L // 30 seconds
    }
    
    enum class NetworkState {
        CONNECTED,
        DISCONNECTED,
        METERED,  // Mobile data
        WIFI
    }
    
    data class RecoveryState(
        val isRecovering: Boolean = false,
        val attempt: Int = 0,
        val nextRetryIn: Long = 0L,
        val lastError: String? = null
    )
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _networkState = MutableStateFlow(NetworkState.DISCONNECTED)
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()
    
    private val _recoveryState = MutableStateFlow(RecoveryState())
    val recoveryState: StateFlow<RecoveryState> = _recoveryState.asStateFlow()
    
    private var retryJob: Job? = null
    private var healthCheckJob: Job? = null
    
    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Network available")
            updateNetworkState()
            // Trigger immediate reconnect when network becomes available
            if (_recoveryState.value.isRecovering) {
                cancelRetry()
                scheduleRetry(0, 0L) // Immediate retry
            }
        }
        
        override fun onLost(network: Network) {
            Log.w(TAG, "Network lost")
            _networkState.value = NetworkState.DISCONNECTED
        }
        
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            updateNetworkState()
        }
    }
    
    init {
        // Register network callback
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
            updateNetworkState()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }
    
    /**
     * Start recovery process with exponential backoff
     */
    fun startRecovery(error: String) {
        if (_recoveryState.value.isRecovering) {
            Log.d(TAG, "Recovery already in progress (error: $error, current attempt: ${_recoveryState.value.attempt})")
            return
        }
        
        Log.i(TAG, "[RECOVERY START] Error: $error")
        _recoveryState.value = RecoveryState(
            isRecovering = true,
            attempt = 0,
            lastError = error
        )
        
        scheduleRetry(0)
    }
    
    /**
     * Schedule retry with exponential backoff
     */
    private fun scheduleRetry(attempt: Int, customDelay: Long? = null) {
        // Cancel any existing retry
        retryJob?.cancel()
        
        // Check if max attempts reached
        if (attempt >= MAX_RETRY_ATTEMPTS) {
            Log.e(TAG, "Max retry attempts reached ($MAX_RETRY_ATTEMPTS)")
            _recoveryState.value = _recoveryState.value.copy(
                isRecovering = false,
                lastError = "Max retry attempts reached"
            )
            return
        }
        
        // Calculate delay with exponential backoff
        val delay = customDelay ?: min(
            INITIAL_RETRY_DELAY_MS * 2.0.pow(attempt).toLong(),
            MAX_RETRY_DELAY_MS
        )
        
        Log.d(TAG, "Scheduling retry attempt ${attempt + 1}/$MAX_RETRY_ATTEMPTS in ${delay}ms")
        
        _recoveryState.value = _recoveryState.value.copy(
            attempt = attempt + 1,
            nextRetryIn = delay
        )
        
        retryJob = scope.launch {
            // Wait for delay
            delay(delay)
            
            // Check network state before attempting
            if (_networkState.value == NetworkState.DISCONNECTED) {
                Log.w(TAG, "Network disconnected, waiting for network...")
                _recoveryState.value = _recoveryState.value.copy(
                    lastError = "Waiting for network connection"
                )
                return@launch
            }
            
            // Attempt reconnect
            try {
                Log.i(TAG, "Attempting reconnect (attempt ${attempt + 1}/$MAX_RETRY_ATTEMPTS)")
                onReconnect()
                
                // Success - reset recovery state
                _recoveryState.value = RecoveryState(isRecovering = false)
                Log.i(TAG, "Reconnect successful")
                
            } catch (e: Exception) {
                Log.e(TAG, "Reconnect failed: ${e.message}", e)
                
                // Schedule next retry
                scheduleRetry(attempt + 1)
            }
        }
    }
    
    /**
     * Stop recovery process
     */
    fun stopRecovery() {
        Log.i(TAG, "[RECOVERY STOP] Stopping recovery (was recovering: ${_recoveryState.value.isRecovering})")
        retryJob?.cancel()
        _recoveryState.value = RecoveryState(isRecovering = false)
    }
    
    /**
     * Cancel current retry and reset
     */
    private fun cancelRetry() {
        retryJob?.cancel()
    }
    
    /**
     * Start periodic health checks
     */
    fun startHealthChecks(onHealthCheck: suspend () -> Boolean) {
        healthCheckJob?.cancel()
        
        Log.i(TAG, "[HEALTH CHECK] Starting periodic health checks (interval: ${HEALTH_CHECK_INTERVAL_MS}ms)")
        
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                
                try {
                    val healthy = onHealthCheck()
                    Log.d(TAG, "[HEALTH CHECK] Result: healthy=$healthy, recoveryInProgress=${_recoveryState.value.isRecovering}")
                    
                    if (!healthy) {
                        Log.w(TAG, "[HEALTH CHECK FAILED] Starting recovery")
                        startRecovery("Health check failed")
                    } else {
                        Log.v(TAG, "[HEALTH CHECK OK] Connection healthy")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[HEALTH CHECK ERROR] Exception during check", e)
                }
            }
        }
    }
    
    /**
     * Stop health checks
     */
    fun stopHealthChecks() {
        Log.i(TAG, "[HEALTH CHECK] Stopping health checks")
        healthCheckJob?.cancel()
    }
    
    /**
     * Update current network state
     */
    private fun updateNetworkState() {
        val capabilities = connectivityManager.activeNetwork?.let {
            connectivityManager.getNetworkCapabilities(it)
        }
        
        _networkState.value = when {
            capabilities == null -> NetworkState.DISCONNECTED
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkState.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkState.METERED
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkState.CONNECTED
            else -> NetworkState.CONNECTED
        }
        
        Log.d(TAG, "Network state: $networkState")
    }
    
    /**
     * Check if network is available
     */
    fun isNetworkAvailable(): Boolean {
        return _networkState.value != NetworkState.DISCONNECTED
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering network callback", e)
        }
        
        retryJob?.cancel()
        healthCheckJob?.cancel()
        scope.cancel()
    }
}
