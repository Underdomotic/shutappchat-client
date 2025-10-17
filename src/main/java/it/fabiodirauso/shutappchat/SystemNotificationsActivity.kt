package it.fabiodirauso.shutappchat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import it.fabiodirauso.shutappchat.adapter.SystemNotificationAdapter
import it.fabiodirauso.shutappchat.database.AppDatabase
import it.fabiodirauso.shutappchat.model.SystemNotification
import it.fabiodirauso.shutappchat.utils.SwipeToActionCallback
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Activity to display system notifications from admin
 * Read-only chat-like interface
 */
class SystemNotificationsActivity : AppCompatActivity() {
    
    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SystemNotificationAdapter
    private lateinit var database: AppDatabase
    private lateinit var emptyView: View
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            android.util.Log.d("SystemNotifications", "onCreate started")
            setContentView(R.layout.activity_system_notifications)
            
            // Enable fullscreen immersive mode (DOPO setContentView per evitare NPE)
            it.fabiodirauso.shutappchat.utils.UIHelper.enableImmersiveMode(this)
            
            // Setup toolbar
            toolbar = findViewById(R.id.toolbar)
            setSupportActionBar(toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            
            android.util.Log.d("SystemNotifications", "Getting database instance")
            database = AppDatabase.getDatabase(this)
            
            android.util.Log.d("SystemNotifications", "Setting up views")
            setupViews()
            setupRecyclerView()
            observeNotifications()
            // Non marchiamo più come lette automaticamente - l'utente deve farlo manualmente
            
            android.util.Log.d("SystemNotifications", "onCreate completed successfully")
        } catch (e: Exception) {
            android.util.Log.e("SystemNotifications", "Error in onCreate", e)
            android.widget.Toast.makeText(
                this,
                "Errore nell'apertura delle notifiche: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }
    
    private fun setupViews() {
        recyclerView = findViewById(R.id.recyclerViewNotifications)
        emptyView = findViewById(R.id.emptyView)
    }
    
    private fun setupRecyclerView() {
        adapter = SystemNotificationAdapter(
            onNotificationClick = { notification ->
                handleNotificationClick(notification)
            },
            onNotificationLongClick = { notification ->
                handleNotificationLongClick(notification)
            },
            onSwipeToDelete = { notification ->
                // Questa callback non viene più usata, gestita da SwipeToActionCallback
            },
            onSwipeToOpenUrl = { notification ->
                // Questa callback non viene più usata, gestita da SwipeToActionCallback
            }
        )
        
        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@SystemNotificationsActivity).apply {
                reverseLayout = true // Show latest at bottom like chat
                stackFromEnd = true
            }
            adapter = this@SystemNotificationsActivity.adapter
        }
        
        // Aggiungi swipe gestures
        val swipeCallback = SwipeToActionCallback(
            this,
            adapter,
            onSwipeRight = { position ->
                // Swipe destro - Elimina
                handleSwipeToDelete(position)
            },
            onSwipeLeft = { position ->
                // Swipe sinistro - Apri link
                handleSwipeToOpenUrl(position)
            }
        )
        val itemTouchHelper = ItemTouchHelper(swipeCallback)
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }
    
    private fun observeNotifications() {
        lifecycleScope.launch {
            database.systemNotificationDao().getAllNotifications().collectLatest { notifications ->
                adapter.submitList(notifications)
                updateEmptyView(notifications.isEmpty())
            }
        }
    }
    
    private fun markAllAsRead() {
        lifecycleScope.launch {
            database.systemNotificationDao().markAllAsRead()
        }
    }
    
    private fun handleNotificationClick(notification: SystemNotification) {
        // Tap singolo: marca come letta se non letta
        if (!notification.read) {
            lifecycleScope.launch {
                database.systemNotificationDao().markAsRead(notification.id)
                android.widget.Toast.makeText(
                    this@SystemNotificationsActivity,
                    "Notifica marcata come letta",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    private fun handleNotificationLongClick(notification: SystemNotification) {
        // Toggle read/unread status
        lifecycleScope.launch {
            database.systemNotificationDao().toggleRead(notification.id)
            
            // Show toast feedback
            val newStatus = if (notification.read) "non letta" else "letta"
            android.widget.Toast.makeText(
                this@SystemNotificationsActivity,
                "Notifica marcata come $newStatus",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    private fun handleSwipeToDelete(position: Int) {
        val notification = adapter.getNotificationAt(position) ?: return
        
        lifecycleScope.launch {
            database.systemNotificationDao().deleteNotification(notification.id)
            
            // Mostra Snackbar con opzione di annullamento
            Snackbar.make(
                recyclerView,
                "Notifica eliminata",
                Snackbar.LENGTH_LONG
            ).setAction("ANNULLA") {
                // Ripristina la notifica
                lifecycleScope.launch {
                    database.systemNotificationDao().insertNotification(notification)
                }
            }.show()
        }
    }
    
    private fun handleSwipeToOpenUrl(position: Int) {
        val notification = adapter.getNotificationAt(position) ?: return
        
        if (!notification.url.isNullOrEmpty()) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(notification.url))
                startActivity(intent)
                
                // Marca come letta quando si apre il link
                lifecycleScope.launch {
                    database.systemNotificationDao().markAsRead(notification.id)
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    this,
                    "Link non valido: ${notification.url}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                
                // Ripristina la view (annulla swipe)
                adapter.notifyItemChanged(position)
            }
        } else {
            // Non dovrebbe mai succedere perché lo swipe sinistro è disabilitato senza URL
            adapter.notifyItemChanged(position)
        }
    }
    
    private fun updateEmptyView(isEmpty: Boolean) {
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        return try {
            if (menu != null) {
                menuInflater.inflate(R.menu.menu_system_notifications, menu)
                true
            } else {
                android.util.Log.w("SystemNotifications", "onCreateOptionsMenu called with null menu")
                super.onCreateOptionsMenu(menu)
            }
        } catch (e: Exception) {
            android.util.Log.e("SystemNotifications", "Error inflating menu", e)
            super.onCreateOptionsMenu(menu)
        }
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return try {
            when (item.itemId) {
                android.R.id.home -> {
                    finish()
                    true
                }
                R.id.action_mark_all_read -> {
                    markAllAsRead()
                    android.widget.Toast.makeText(
                        this,
                        "Tutte le notifiche marcate come lette",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    true
                }
                R.id.action_delete_all -> {
                    confirmDeleteAll()
                    true
                }
                else -> super.onOptionsItemSelected(item)
            }
        } catch (e: Exception) {
            android.util.Log.e("SystemNotifications", "Error handling menu item", e)
            super.onOptionsItemSelected(item)
        }
    }
    
    private fun confirmDeleteAll() {
        try {
            if (isFinishing || isDestroyed) {
                android.util.Log.w("SystemNotifications", "Activity is finishing/destroyed, skipping dialog")
                return
            }
            
            AlertDialog.Builder(this)
                .setTitle("Elimina tutte le notifiche")
                .setMessage("Sei sicuro di voler eliminare tutte le notifiche? Questa azione non può essere annullata.")
                .setPositiveButton("Elimina") { _, _ ->
                    deleteAllNotifications()
                }
                .setNegativeButton("Annulla", null)
                .show()
        } catch (e: Exception) {
            android.util.Log.e("SystemNotifications", "Error showing delete confirmation", e)
            android.widget.Toast.makeText(
                this,
                "Errore nell'apertura del dialogo",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    private fun deleteAllNotifications() {
        lifecycleScope.launch {
            database.systemNotificationDao().deleteAllNotifications()
            android.widget.Toast.makeText(
                this@SystemNotificationsActivity,
                "Tutte le notifiche sono state eliminate",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}