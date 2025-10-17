package it.fabiodirauso.shutappchat.utils

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Helper per mostrare dialog informativi sui permessi
 */
object PermissionDialogHelper {

    /**
     * Mostra un dialog che spiega perché serve un permesso
     */
    fun showRationaleDialog(
        activity: Activity,
        permissionType: PermissionManager.PermissionType,
        onPositive: () -> Unit,
        onNegative: (() -> Unit)? = null
    ) {
        val title = PermissionManager.getPermissionTitle(permissionType)
        val message = PermissionManager.getRationaleMessage(permissionType)
        
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Concedi") { dialog, _ ->
                dialog.dismiss()
                onPositive()
            }
            .setNegativeButton("Non ora") { dialog, _ ->
                dialog.dismiss()
                onNegative?.invoke()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Mostra un dialog quando un permesso è stato negato permanentemente
     */
    fun showPermissionDeniedDialog(
        activity: Activity,
        permissionType: PermissionManager.PermissionType,
        onGoToSettings: () -> Unit = { openAppSettings(activity) }
    ) {
        val title = "Permesso necessario"
        val message = "${PermissionManager.getRationaleMessage(permissionType)}\n\n" +
                "Hai negato questo permesso. Per utilizzare questa funzione, " +
                "è necessario concedere il permesso dalle impostazioni dell'app."
        
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Vai alle impostazioni") { dialog, _ ->
                dialog.dismiss()
                onGoToSettings()
            }
            .setNegativeButton("Annulla") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Mostra un dialog generico di errore permessi
     */
    fun showPermissionRequiredDialog(
        activity: Activity,
        title: String,
        message: String,
        onDismiss: (() -> Unit)? = null
    ) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                onDismiss?.invoke()
            }
            .show()
    }

    /**
     * Apre le impostazioni dell'app
     */
    fun openAppSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", activity.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            // Fallback alle impostazioni generali
            val intent = Intent(Settings.ACTION_SETTINGS)
            activity.startActivity(intent)
        }
    }

    /**
     * Gestisce il flusso completo di richiesta permesso con rationale
     */
    fun handlePermissionRequest(
        activity: Activity,
        permissionType: PermissionManager.PermissionType,
        onGranted: () -> Unit,
        onDenied: (() -> Unit)? = null
    ) {
        when {
            // Permesso già garantito
            PermissionManager.isPermissionGranted(activity, permissionType) -> {
                onGranted()
            }
            
            // Mostra rationale se necessario
            PermissionManager.shouldShowRationale(activity, permissionType) -> {
                showRationaleDialog(
                    activity = activity,
                    permissionType = permissionType,
                    onPositive = {
                        PermissionManager.requestPermission(activity, permissionType)
                    },
                    onNegative = onDenied
                )
            }
            
            // Richiedi direttamente
            else -> {
                PermissionManager.requestPermission(activity, permissionType)
            }
        }
    }
}
