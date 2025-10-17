package it.fabiodirauso.shutappchat.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Centralizza la gestione dei permessi runtime per l'app.
 * Fornisce metodi per verificare, richiedere e spiegare i permessi necessari.
 */
object PermissionManager {

    /**
     * Codici di richiesta permessi per identificare i callback
     */
    object RequestCodes {
        const val CAMERA = 1001
        const val READ_MEDIA_IMAGES = 1002
        const val READ_MEDIA_VIDEO = 1003
        const val POST_NOTIFICATIONS = 1004
        const val STORAGE_LEGACY = 1005
        const val MEDIA_BUNDLE = 1006
    }

    /**
     * Tipi di permesso raggruppati per funzionalità
     */
    enum class PermissionType {
        CAMERA,
        MEDIA_IMAGES,
        MEDIA_VIDEO,
        NOTIFICATIONS,
        STORAGE_LEGACY,
        MEDIA_FULL
    }

    /**
     * Ottiene i permessi Android corrispondenti al tipo
     */
    private fun getPermissionsForType(type: PermissionType): Array<String> {
        return when (type) {
            PermissionType.CAMERA -> arrayOf(Manifest.permission.CAMERA)
            
            PermissionType.MEDIA_IMAGES -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                } else {
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            
            PermissionType.MEDIA_VIDEO -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
                } else {
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            
            PermissionType.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    emptyArray() // Non necessario pre-Android 13
                }
            }
            
            PermissionType.STORAGE_LEGACY -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                } else {
                    emptyArray()
                }
            }
            
            PermissionType.MEDIA_FULL -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VIDEO
                    )
                } else {
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }

    /**
     * Ottiene il codice di richiesta appropriato per il tipo di permesso
     */
    private fun getRequestCodeForType(type: PermissionType): Int {
        return when (type) {
            PermissionType.CAMERA -> RequestCodes.CAMERA
            PermissionType.MEDIA_IMAGES -> RequestCodes.READ_MEDIA_IMAGES
            PermissionType.MEDIA_VIDEO -> RequestCodes.READ_MEDIA_VIDEO
            PermissionType.NOTIFICATIONS -> RequestCodes.POST_NOTIFICATIONS
            PermissionType.STORAGE_LEGACY -> RequestCodes.STORAGE_LEGACY
            PermissionType.MEDIA_FULL -> RequestCodes.MEDIA_BUNDLE
        }
    }

    /**
     * Verifica se un tipo di permesso è garantito
     */
    fun isPermissionGranted(context: Context, type: PermissionType): Boolean {
        val permissions = getPermissionsForType(type)
        if (permissions.isEmpty()) return true // Permesso non necessario per questa versione
        
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Verifica se tutti i permessi di un array sono garantiti
     */
    fun arePermissionsGranted(context: Context, permissions: Array<String>): Boolean {
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Verifica se l'app dovrebbe mostrare una spiegazione per il permesso
     */
    fun shouldShowRationale(activity: Activity, type: PermissionType): Boolean {
        val permissions = getPermissionsForType(type)
        if (permissions.isEmpty()) return false
        
        return permissions.any { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
    }

    /**
     * Richiede un tipo di permesso
     */
    fun requestPermission(activity: Activity, type: PermissionType) {
        val permissions = getPermissionsForType(type)
        if (permissions.isEmpty()) return
        
        val requestCode = getRequestCodeForType(type)
        ActivityCompat.requestPermissions(activity, permissions, requestCode)
    }

    /**
     * Richiede più tipi di permesso contemporaneamente
     */
    fun requestPermissions(activity: Activity, types: List<PermissionType>, requestCode: Int) {
        val allPermissions = types.flatMap { getPermissionsForType(it).toList() }.distinct().toTypedArray()
        if (allPermissions.isEmpty()) return
        
        ActivityCompat.requestPermissions(activity, allPermissions, requestCode)
    }

    /**
     * Processa il risultato di una richiesta permessi
     * @return true se tutti i permessi sono stati concessi
     */
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        if (permissions.isEmpty()) return false
        return grantResults.all { it == PackageManager.PERMISSION_GRANTED }
    }

    /**
     * Ottiene il messaggio di spiegazione per un tipo di permesso
     */
    fun getRationaleMessage(type: PermissionType): String {
        return when (type) {
            PermissionType.CAMERA -> 
                "L'accesso alla fotocamera è necessario per scattare foto da usare come avatar o da inviare nelle chat."
            
            PermissionType.MEDIA_IMAGES -> 
                "L'accesso alle immagini è necessario per selezionare foto dalla galleria."
            
            PermissionType.MEDIA_VIDEO -> 
                "L'accesso ai video è necessario per condividere video dalla galleria."
            
            PermissionType.NOTIFICATIONS -> 
                "Le notifiche sono necessarie per avvisarti quando ricevi nuovi messaggi."
            
            PermissionType.STORAGE_LEGACY, PermissionType.MEDIA_FULL -> 
                "L'accesso alla memoria è necessario per selezionare e salvare file multimediali."
        }
    }

    /**
     * Ottiene il titolo per la richiesta di un tipo di permesso
     */
    fun getPermissionTitle(type: PermissionType): String {
        return when (type) {
            PermissionType.CAMERA -> "Permesso fotocamera"
            PermissionType.MEDIA_IMAGES -> "Permesso immagini"
            PermissionType.MEDIA_VIDEO -> "Permesso video"
            PermissionType.NOTIFICATIONS -> "Permesso notifiche"
            PermissionType.STORAGE_LEGACY, PermissionType.MEDIA_FULL -> "Permesso archiviazione"
        }
    }

    /**
     * Verifica se il permesso fotocamera è disponibile
     */
    fun hasCameraPermission(context: Context): Boolean {
        return isPermissionGranted(context, PermissionType.CAMERA)
    }

    /**
     * Verifica se i permessi per le immagini sono disponibili
     */
    fun hasMediaImagesPermission(context: Context): Boolean {
        return isPermissionGranted(context, PermissionType.MEDIA_IMAGES)
    }

    /**
     * Verifica se i permessi per le notifiche sono disponibili
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return isPermissionGranted(context, PermissionType.NOTIFICATIONS)
    }

    /**
     * Verifica e richiede i permessi per le immagini se necessario
     * @return true se i permessi sono già garantiti
     */
    fun checkAndRequestMediaPermissions(activity: Activity, requestCode: Int): Boolean {
        val type = PermissionType.MEDIA_IMAGES
        
        if (isPermissionGranted(activity, type)) {
            return true
        }
        
        requestPermission(activity, type)
        return false
    }

    /**
     * Verifica e richiede i permessi per la fotocamera se necessario
     * @return true se i permessi sono già garantiti
     */
    fun checkAndRequestCameraPermission(activity: Activity): Boolean {
        val type = PermissionType.CAMERA
        
        if (isPermissionGranted(activity, type)) {
            return true
        }
        
        requestPermission(activity, type)
        return false
    }

    /**
     * Verifica e richiede i permessi per le notifiche se necessario
     * @return true se i permessi sono già garantiti o non necessari
     */
    fun checkAndRequestNotificationPermission(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true // Non necessario pre-Android 13
        }
        
        val type = PermissionType.NOTIFICATIONS
        
        if (isPermissionGranted(activity, type)) {
            return true
        }
        
        requestPermission(activity, type)
        return false
    }
}
