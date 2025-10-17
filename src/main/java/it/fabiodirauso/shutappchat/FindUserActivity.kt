package it.fabiodirauso.shutappchat

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import it.fabiodirauso.shutappchat.adapter.UserSearchAdapter
import it.fabiodirauso.shutappchat.adapter.ContactsAdapter
import it.fabiodirauso.shutappchat.api.ContactRequestRequest
import it.fabiodirauso.shutappchat.model.User
import it.fabiodirauso.shutappchat.network.RetrofitClient
import it.fabiodirauso.shutappchat.database.AppDatabase

class FindUserActivity : AppCompatActivity() {

    private lateinit var friendsAdapter: ContactsAdapter
    private lateinit var newContactsAdapter: UserSearchAdapter
    private var searchJob: Job? = null
    
    // UI Elements
    private lateinit var textViewFriendsHeader: TextView
    private lateinit var recyclerViewFriends: RecyclerView
    private lateinit var textViewNewContactsHeader: TextView
    private lateinit var recyclerViewNewContacts: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_find_user)

        // Enable fullscreen immersive mode
        it.fabiodirauso.shutappchat.utils.UIHelper.enableImmersiveMode(this)

        initViews()
        setupRecyclerViews()
        setupSearch()
        ensureAuthToken()
    }

    private fun initViews() {
        textViewFriendsHeader = findViewById(R.id.textViewFriendsHeader)
        recyclerViewFriends = findViewById(R.id.recyclerViewFriends)
        textViewNewContactsHeader = findViewById(R.id.textViewNewContactsHeader)
        recyclerViewNewContacts = findViewById(R.id.recyclerViewNewContacts)
    }

    private fun setupRecyclerViews() {
        val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val token = sharedPreferences.getString("auth_token", "") ?: ""
        
        // Setup Friends Adapter (Local Contacts)
        friendsAdapter = ContactsAdapter(
            context = this,
            lifecycleScope = lifecycleScope,
            onContactClick = { user ->
                openConversationWithFriend(user)
            }
        )
        
        recyclerViewFriends.layoutManager = LinearLayoutManager(this)
        recyclerViewFriends.adapter = friendsAdapter
        
        // Setup New Contacts Adapter (Online Users)
        newContactsAdapter = UserSearchAdapter(
            context = this,
            lifecycleScope = lifecycleScope,
            currentUserToken = token,
            onUserClick = { user ->
                sendFriendRequest(user)
            }
        )
        
        recyclerViewNewContacts.layoutManager = LinearLayoutManager(this)
        recyclerViewNewContacts.adapter = newContactsAdapter
    }

    private fun setupSearch() {
        val editTextSearch = findViewById<android.widget.EditText>(R.id.editTextSearchUser)
        
        editTextSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                
                // Cancel previous search
                searchJob?.cancel()
                
                if (query.length >= 2) {
                    // Debounce search for 500ms
                    searchJob = lifecycleScope.launch {
                        delay(500)
                        performSearch(query)
                    }
                } else {
                    // Clear results if query is too short
                    clearResults()
                }
            }
        })
    }

    private suspend fun performSearch(query: String) {
        // Search in both local friends and online users
        searchLocalFriends(query)
        searchOnlineUsers(query)
    }

    private suspend fun searchLocalFriends(query: String) {
        try {
            val database = AppDatabase.getDatabase(this)
            val localUsers = database.userDao().searchUsers("%$query%")
            
            if (localUsers.isNotEmpty()) {
                textViewFriendsHeader.visibility = View.VISIBLE
                recyclerViewFriends.visibility = View.VISIBLE
                friendsAdapter.updateContacts(localUsers)
            } else {
                textViewFriendsHeader.visibility = View.GONE
                recyclerViewFriends.visibility = View.GONE
            }
        } catch (e: Exception) {
            e.printStackTrace()
            textViewFriendsHeader.visibility = View.GONE
            recyclerViewFriends.visibility = View.GONE
        }
    }

    private suspend fun searchOnlineUsers(query: String) {
        try {
            // Get local users to filter them out from online results
            val database = AppDatabase.getDatabase(this)
            val localUsers = database.userDao().getAllUsers()
            val localUsernames = localUsers.map { it.username }.toSet()
            
            val response = RetrofitClient.apiService.searchUsers(query)
            
            if (response.isSuccessful && response.body() != null) {
                // Filter out users that are already in local contacts
                val allUsers = response.body()!!.users
                val filteredUsers = allUsers.filter { user -> 
                    user.username !in localUsernames
                }
                
                if (filteredUsers.isNotEmpty()) {
                    textViewNewContactsHeader.visibility = View.VISIBLE
                    recyclerViewNewContacts.visibility = View.VISIBLE
                    newContactsAdapter.updateUsers(filteredUsers)
                } else {
                    textViewNewContactsHeader.visibility = View.GONE
                    recyclerViewNewContacts.visibility = View.GONE
                }
            } else {
                textViewNewContactsHeader.visibility = View.GONE
                recyclerViewNewContacts.visibility = View.GONE
                Toast.makeText(this, "Errore nella ricerca online", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            textViewNewContactsHeader.visibility = View.GONE
            recyclerViewNewContacts.visibility = View.GONE
            Toast.makeText(this, "Errore di connessione", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearResults() {
        textViewFriendsHeader.visibility = View.GONE
        recyclerViewFriends.visibility = View.GONE
        textViewNewContactsHeader.visibility = View.GONE
        recyclerViewNewContacts.visibility = View.GONE
        
        friendsAdapter.updateContacts(emptyList())
        newContactsAdapter.updateUsers(emptyList())
    }

    private fun openConversationWithFriend(user: User) {
        // Open ChatActivity with the selected friend
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("contact_username", user.username)
            putExtra("contact_id", user.id)
            putExtra("contact_name", user.nickname ?: user.username)
            putExtra("profile_picture_id", user.profile_picture)
        }
        startActivity(intent)
        finish()
    }

    private fun sendFriendRequest(user: User) {
        lifecycleScope.launch {
            try {
                val request = ContactRequestRequest(
                    to = user.username,
                    message = "Ciao! Vorrei aggiungerti come contatto."
                )
                
                val response = RetrofitClient.apiService.sendContactRequest(request)
                
                if (response.isSuccessful) {
                    Toast.makeText(this@FindUserActivity, 
                        "Richiesta di amicizia inviata a ${user.username}", 
                        Toast.LENGTH_SHORT).show()
                    newContactsAdapter.markRequestSent(user.id)
                } else {
                    Toast.makeText(this@FindUserActivity, 
                        "Errore nell'invio della richiesta", 
                        Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@FindUserActivity, 
                    "Errore di connessione", 
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun ensureAuthToken() {
        val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val token = sharedPreferences.getString("auth_token", null)
        RetrofitClient.setAuthToken(token)
    }
}