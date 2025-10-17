package it.fabiodirauso.shutappchat.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import it.fabiodirauso.shutappchat.R
import it.fabiodirauso.shutappchat.SystemNotificationsActivity
import it.fabiodirauso.shutappchat.model.SystemNotification
import it.fabiodirauso.shutappchat.services.LogUploadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SystemNotificationHelper {
    
    private const val TAG = "SystemNotifHelper"
    private const val CHANNEL_ID = "system_notifications"
    private const val CHANNEL_NAME = "Notifiche di Sistema"
    private const val CHANNEL_DESCRIPTION = "Notifiche importanti dall amministratore"
    
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESCRIPTION
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
                setShowBadge(true)
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    fun showNotification(context: Context, notification: SystemNotification) {
        createNotificationChannel(context)
        
        // Handle special URLs (shutapp:// protocol)
        if (notification.url?.startsWith("shutapp://") == true) {
            handleSpecialUrl(context, notification)
        }
        
        val intent = Intent(context, SystemNotificationsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            notification.id.toInt(),
            intent,
            pendingIntentFlags
        )
        
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(notification.title ?: "Notifica di Sistema")
            .setContentText(notification.description ?: "")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 250, 250, 250))
        
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        builder.setSound(defaultSoundUri)
        
        if (!notification.description.isNullOrEmpty() && notification.description.length > 40) {
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(notification.description)
                    .setBigContentTitle(notification.title ?: "Notifica di Sistema")
            )
        }
        
        if (!notification.url.isNullOrEmpty()) {
            val urlIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(notification.url))
            val urlPendingIntent = PendingIntent.getActivity(
                context,
                (notification.id + 1000).toInt(),
                urlIntent,
                pendingIntentFlags
            )
            builder.addAction(
                android.R.drawable.ic_menu_view,
                "Apri Link",
                urlPendingIntent
            )
        }
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notification.id.toInt(), builder.build())
    }
    
    /**
     * Gestisce URL speciali con protocollo shutapp://
     */
    private fun handleSpecialUrl(context: Context, notification: SystemNotification) {
        val url = notification.url ?: return
        
        Log.d(TAG, "Handling special URL: $url")
        
        when {
            url == "shutapp://upload_logs" -> {
                // Avvia automaticamente l'upload dei log
                Log.i(TAG, "Richiesta upload log ricevuta - avvio raccolta...")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val result = LogUploadService.collectAndUploadLogs(context)
                        if (result.isSuccess) {
                            Log.i(TAG, "Upload log completato: ${result.getOrNull()}")
                        } else {
                            Log.e(TAG, "Errore upload log: ${result.exceptionOrNull()?.message}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Eccezione durante upload log", e)
                    }
                }
            }
            else -> {
                Log.w(TAG, "URL speciale non gestito: $url")
            }
        }
    }
}
