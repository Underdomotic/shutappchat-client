package it.fabiodirauso.shutappchat.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * App-wide constants including version and User-Agent
 */
object AppConstants {
    
    /**
     * Get app version name from BuildConfig/PackageInfo
     */
    fun getVersionName(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }
    
    /**
     * Get app version code from BuildConfig/PackageInfo
     */
    fun getVersionCode(context: Context): Long {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            1L
        }
    }
    
    /**
     * Generate User-Agent string in format: ShutAppChat|v{version}|{versionCode}
     * Example: ShutAppChat|v1.0|1
     */
    fun getUserAgent(context: Context): String {
        val versionName = getVersionName(context)
        val versionCode = getVersionCode(context)
        return "ShutAppChat|v$versionName|$versionCode"
    }
}
