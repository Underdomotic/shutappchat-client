package it.fabiodirauso.shutappchat

import android.app.Application
import android.util.Log
import it.fabiodirauso.shutappchat.network.RetrofitClient
import it.fabiodirauso.shutappchat.utils.AppConstants
import it.fabiodirauso.shutappchat.utils.CrashReporter

/**
 * Application class for global initialization
 */
class ShutAppChatApplication : Application() {
    
    companion object {
        private const val TAG = "ShutAppChatApp"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize crash reporter FIRST (before anything else)
        CrashReporter.init(this)
        
        // Initialize RetrofitClient with application context
        RetrofitClient.initialize(this)
        
        // Initialize User-Agent for API and WebSocket
        val userAgent = AppConstants.getUserAgent(this)
        RetrofitClient.setUserAgent(userAgent)
        
        // Create notification channel for system notifications
        it.fabiodirauso.shutappchat.utils.SystemNotificationHelper.createNotificationChannel(this)
        
        Log.i(TAG, "ShutAppChat initialized")
        Log.i(TAG, "Version: ${AppConstants.getVersionName(this)} (${AppConstants.getVersionCode(this)})")
        Log.i(TAG, "User-Agent: $userAgent")
    }
}
