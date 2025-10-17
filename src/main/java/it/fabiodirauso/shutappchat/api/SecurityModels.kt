package it.fabiodirauso.shutappchat.api

import com.google.gson.annotations.SerializedName

/**
 * Models per le impostazioni di sicurezza e privacy
 */

data class SecuritySettings(
    @SerializedName("allow_read_receipts")
    val allowReadReceipts: Int,
    
    @SerializedName("block_screenshots")
    val blockScreenshots: Int,
    
    @SerializedName("protect_media")
    val protectMedia: Int,
    
    @SerializedName("obfuscate_media_files")
    val obfuscateMediaFiles: Int,
    
    @SerializedName("auto_delete_media_on_open")
    val autoDeleteMediaOnOpen: Int
)

data class SecuritySettingsResponse(
    val settings: SecuritySettings
)

data class SecurityUpdateResponse(
    val ok: Boolean
)

data class SecurityUpdateRequest(
    @SerializedName("allow_read_receipts")
    val allowReadReceipts: Boolean? = null,
    
    @SerializedName("block_screenshots") 
    val blockScreenshots: Boolean? = null,
    
    @SerializedName("protect_media")
    val protectMedia: Boolean? = null,
    
    @SerializedName("obfuscate_media_files")
    val obfuscateMediaFiles: Boolean? = null,
    
    @SerializedName("auto_delete_media_on_open")
    val autoDeleteMediaOnOpen: Boolean? = null
)

// Modelli per la configurazione dell'app
data class AppServer(
    val name: String,
    val api_base: String,
    val ws_url: String
)

data class AppConfigResponse(
    val donate: String,
    val download: String,
    val servers: List<AppServer>
)