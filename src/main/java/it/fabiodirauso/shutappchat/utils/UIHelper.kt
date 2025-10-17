package it.fabiodirauso.shutappchat.utils

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Helper per gestire l'UI fullscreen e immersive mode
 */
object UIHelper {
    
    /**
     * Abilita l'immersive mode nascondendo status bar e navigation bar.
     * Le barre riappaiono con swipe e si ri-nascondono automaticamente.
     * 
     * @param activity L'activity su cui applicare l'immersive mode
     */
    fun enableImmersiveMode(activity: Activity) {
        val window = activity.window
        
        // Edge-to-Edge: il contenuto si estende sotto le system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+): Usa WindowInsetsController
            window.insetsController?.let { controller ->
                // Nascondi status bar e navigation bar
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                
                // Comportamento: le barre riappaiono con swipe e si ri-nascondono
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Android 10 e precedenti: Usa View flags
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }
    
    /**
     * Disabilita l'immersive mode ripristinando status bar e navigation bar.
     * 
     * @param activity L'activity su cui disabilitare l'immersive mode
     */
    fun disableImmersiveMode(activity: Activity) {
        val window = activity.window
        
        // Ripristina decorFitsSystemWindows
        WindowCompat.setDecorFitsSystemWindows(window, true)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: Mostra le system bars
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            // Android 10 e precedenti: Rimuovi i flags
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }
    
    /**
     * Applica padding alle view per evitare sovrapposizioni con system bars.
     * Utile quando si usa Edge-to-Edge ma si vuole che il contenuto non vada sotto le barre.
     * 
     * @param view La view root a cui applicare il padding
     */
    fun applySystemBarsPadding(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            // Applica padding per evitare sovrapposizioni
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            
            insets
        }
    }
}
