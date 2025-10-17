package it.fabiodirauso.shutappchat

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import it.fabiodirauso.shutappchat.adapter.ContactsAdapter
import it.fabiodirauso.shutappchat.model.User
import it.fabiodirauso.shutappchat.network.RetrofitClient
import it.fabiodirauso.shutappchat.services.ContactSyncService
import it.fabiodirauso.shutappchat.database.AppDatabase

class MyContactsActivity : AppCompatActivity() {

    private lateinit var adapter: ContactsAdapter
    private lateinit var editTextSearch: EditText
    private lateinit var recyclerView: RecyclerView
    private lateinit var syncStatusLayout: LinearLayout
    private lateinit var syncProgressBar: ProgressBar
    private lateinit var syncStatusText: TextView
    private lateinit var emptyStateLayout: LinearLayout
    
    private lateinit var contactSyncService: ContactSyncService
    private lateinit var database: AppDatabase
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_contacts)
        
        // Enable fullscreen immersive mode
        it.fabiodirauso.shutappchat.utils.UIHelper.enableImmersiveMode(this)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_my_contacts)

        // Initialize services
        contactSyncService = ContactSyncService(this)
        database = AppDatabase.getDatabase(this)
        
        setupAuthToken()
        setupViews()
        setupRecyclerView()
        setupSearch()
        
        // Load contacts from local DB first, then sync with server
        loadLocalContacts()
        syncContactsWithServer()
    }

    private fun setupAuthToken() {
        val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val token = sharedPreferences.getString("auth_token", null)
        
        if (token.isNullOrEmpty()) {
            android.util.Log.e("MyContactsActivity", "No auth token found!")
            Toast.makeText(this, "Errore di autenticazione. Rieffettua il login.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        android.util.Log.d("MyContactsActivity", "Auth token configured: ${token.take(20)}...")
        RetrofitClient.setAuthToken(token)
    }

    private fun setupViews() {
        editTextSearch = findViewById(R.id.editTextSearchContacts)
        recyclerView = findViewById(R.id.recyclerViewContacts)
        syncStatusLayout = findViewById(R.id.syncStatusLayout)
        syncProgressBar = findViewById(R.id.syncProgressBar)
        syncStatusText = findViewById(R.id.syncStatusText)
        emptyStateLayout = findViewById(R.id.emptyStateLayout)
    }

    private fun setupRecyclerView() {
        adapter = ContactsAdapter(this, lifecycleScope) { contact ->
            openChatWithContact(contact)
        }
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupSearch() {
        editTextSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString() ?: "")
            }
        })
    }

    /**
     * Load contacts from local database first for immediate display
     */
    private fun loadLocalContacts() {
        lifecycleScope.launch {
            try {
                android.util.Log.d("MyContactsActivity", "Loading contacts from local database...")
                
                val localContacts = database.userDao().getAllUsers()
                android.util.Log.d("MyContactsActivity", "Found ${localContacts.size} contacts in local database")
                
                adapter.updateContacts(localContacts)
                updateUIState(localContacts)
                
            } catch (e: Exception) {
                android.util.Log.e("MyContactsActivity", "Error loading local contacts", e)
                updateUIState(emptyList())
            }
        }
    }
    
    /**
     * Sync contacts with server and update local database
     */
    private fun syncContactsWithServer() {
        lifecycleScope.launch {
            try {
                showSyncStatus(true, "Sincronizzazione contatti in corso...")
                android.util.Log.d("MyContactsActivity", "Starting server sync...")
                
                val syncResult = contactSyncService.performFullSync()
                
                if (syncResult.success) {
                    android.util.Log.d("MyContactsActivity", "Sync successful: ${syncResult.itemsProcessed} contacts")
                    
                    // Reload from database to get updated data
                    val updatedContacts = database.userDao().getAllUsers()
                    adapter.updateContacts(updatedContacts)
                    updateUIState(updatedContacts)
                    
                    showSyncStatus(false, "")
                    
                    if (syncResult.itemsProcessed > 0) {
                        Toast.makeText(this@MyContactsActivity, 
                            "Sincronizzati ${syncResult.itemsProcessed} contatti", 
                            Toast.LENGTH_SHORT).show()
                    }
                } else {
                    android.util.Log.e("MyContactsActivity", "Sync failed: ${syncResult.error}")
                    showSyncStatus(false, "")
                    
                    // Show error only if we don't have local contacts
                    val localContacts = database.userDao().getAllUsers()
                    if (localContacts.isEmpty()) {
                        Toast.makeText(this@MyContactsActivity, 
                            "Errore sincronizzazione: ${syncResult.error}", 
                            Toast.LENGTH_LONG).show()
                    }
                }
                
            } catch (e: Exception) {
                android.util.Log.e("MyContactsActivity", "Exception during sync", e)
                showSyncStatus(false, "")
                
                // Only show error if we don't have local data
                val localContacts = database.userDao().getAllUsers()
                if (localContacts.isEmpty()) {
                    Toast.makeText(this@MyContactsActivity, 
                        "Errore di rete: ${e.message}", 
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * Show/hide sync status bar
     */
    private fun showSyncStatus(show: Boolean, message: String) {
        syncStatusLayout.visibility = if (show) View.VISIBLE else View.GONE
        syncStatusText.text = message
    }
    
    /**
     * Update UI state based on contacts list
     */
    private fun updateUIState(contacts: List<User>) {
        if (contacts.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyStateLayout.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyStateLayout.visibility = View.GONE
        }
    }

    private fun openChatWithContact(contact: User) {
        val intent = Intent(this, ChatActivity::class.java)
        intent.putExtra("contact_id", contact.id)
        intent.putExtra("contact_name", contact.nickname ?: contact.username)
        intent.putExtra("contact_username", contact.username)
        intent.putExtra("profile_picture_id", contact.profile_picture) // Aggiungi l'ID della foto profilo
        startActivity(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}