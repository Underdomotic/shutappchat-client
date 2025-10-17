package it.fabiodirauso.shutappchat.dialogs

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Window
import android.widget.Button
import android.widget.TextView
import it.fabiodirauso.shutappchat.R

class ForceUpdateDialog(
    context: Context,
    private val version: String,
    private val message: String,
    private val downloadUrl: String
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_force_update)
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        setupViews()
    }
    
    private fun setupViews() {
        val tvVersion = findViewById<TextView>(R.id.tvUpdateVersion)
        val tvMessage = findViewById<TextView>(R.id.tvUpdateMessage)
        val btnUpdate = findViewById<Button>(R.id.btnUpdateNow)
        
        tvVersion?.text = "Versione richiesta: $version"
        tvMessage?.text = message
        
        btnUpdate?.setOnClickListener {
            // Chiudi questo dialog
            dismiss()
            
            // Apri il dialog di download con progress bar
            val downloadDialog = UpdateDownloadDialog(context, version, message, downloadUrl)
            downloadDialog.show()
        }
    }
}