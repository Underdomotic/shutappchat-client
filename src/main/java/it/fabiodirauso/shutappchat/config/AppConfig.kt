package it.fabiodirauso.shutappchat.config

object AppConfig {
    const val DATABASE_NAME = "testshut_database"
    const val DATABASE_VERSION = 12 // v1.3.0: Added ForceUpdateEntity for managed APK downloads
    const val PREFS_NAME = "testshut_prefs"
    const val API_BASE_URL = "https://shutappchat.fabiodirauso.it/api/v2/"
    
    // Keys for SharedPreferences
    const val KEY_USER_TOKEN = "user_token"
    const val KEY_USER_ID = "user_id"
    const val KEY_USERNAME = "username"
    const val KEY_PASSWORD_HASH = "password_hash"
}
