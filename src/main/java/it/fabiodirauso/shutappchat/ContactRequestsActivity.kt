package it.fabiodirauso.shutappchat

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import it.fabiodirauso.shutappchat.adapter.PendingRequestsAdapter
import it.fabiodirauso.shutappchat.api.ContactRequest
import it.fabiodirauso.shutappchat.api.ContactRespondRequest
import it.fabiodirauso.shutappchat.database.AppDatabase
import it.fabiodirauso.shutappchat.model.ContactRequestStatus
import it.fabiodirauso.shutappchat.model.User
import it.fabiodirauso.shutappchat.network.RetrofitClient
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import retrofit2.Response
import java.util.Date

class ContactRequestsActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var emptyStateLayout: View
    private lateinit var progressBar: ProgressBar
    private lateinit var adapter: PendingRequestsAdapter
    private lateinit var database: AppDatabase
    private var currentUserId: Long = 0
    private var currentUsername: String = ""
    
    companion object {
        private const val TAG = "ContactRequestsActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_requests)
        
        // Enable fullscreen immersive mode
        it.fabiodirauso.shutappchat.utils.UIHelper.enableImmersiveMode(this)
        
        database = AppDatabase.getDatabase(this)
        
        val sharedPreferences = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        currentUserId = sharedPreferences.getLong("user_id", 0)
        currentUsername = sharedPreferences.getString("username", "") ?: ""
        
        setupViews()
        setupRecyclerView()
        setupSwipeRefresh()
        loadPendingRequests()
    }
    
    private fun setupViews() {
        recyclerView = findViewById(R.id.recyclerViewRequests)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        emptyStateLayout = findViewById(R.id.emptyStateLayout)
        progressBar = findViewById(R.id.progressBar)
        
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }
    }
    
    private fun setupRecyclerView() {
        adapter = PendingRequestsAdapter(
            context = this,
            lifecycleScope = lifecycleScope,
            onAccept = { request -> acceptRequest(request) },
            onDecline = { request -> declineRequest(request) }
        )
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }
    
    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener {
            refreshRequestsFromApi()
        }
        swipeRefreshLayout.setColorSchemeResources(
            android.R.color.holo_blue_bright,
            android.R.color.holo_green_light,
            android.R.color.holo_orange_light,
            android.R.color.holo_red_light
        )
    }
    
    private fun refreshRequestsFromApi() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Fetching contact requests from API...")
                val response = RetrofitClient.apiService.getContactRequests(type = "incoming")
                
                if (response.isSuccessful) {
                    val apiRequests = response.body()?.requests ?: emptyList()
                    Log.d(TAG, "Received ${apiRequests.size} requests from API")
                    
                    // Sincronizza con il database locale
                    syncRequestsToDatabase(apiRequests)
                    
                    Toast.makeText(
                        this@ContactRequestsActivity,
                        "Aggiornato: ${apiRequests.size} richieste",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Log.e(TAG, "API error: ${response.code()} - ${response.message()}")
                    Toast.makeText(
                        this@ContactRequestsActivity,
                        "Errore aggiornamento: ${response.code()}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing from API", e)
                Toast.makeText(
                    this@ContactRequestsActivity,
                    "Errore di rete: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }
    
    private suspend fun syncRequestsToDatabase(apiRequests: List<it.fabiodirauso.shutappchat.api.PendingContactRequestAPI>) {
        try {
            // Converti le richieste API in formato database
            val dbRequests = apiRequests.mapNotNull { apiReq ->
                try {
                    // Ottieni l'ID del sender (facciamo una chiamata API per username -> ID)
                    val senderResponse = RetrofitClient.apiService.getUser(apiReq.sender)
                    val senderId = if (senderResponse.isSuccessful) {
                        (senderResponse.body()?.get("id") as? Number)?.toLong() ?: 0L
                    } else {
                        0L
                    }
                    
                    if (senderId > 0) {
                        it.fabiodirauso.shutappchat.model.ContactRequest(
                            id = apiReq.id,
                            fromUserId = senderId,
                            fromUsername = apiReq.sender,
                            fromNickname = null,
                            fromProfilePicture = null,
                            toUserId = currentUserId,
                            status = when (apiReq.status.lowercase()) {
                                "pending" -> ContactRequestStatus.PENDING
                                "accepted" -> ContactRequestStatus.ACCEPTED
                                "declined" -> ContactRequestStatus.REJECTED
                                else -> ContactRequestStatus.PENDING
                            },
                            createdAt = java.util.Date(), // Parsing timestamp se necessario
                            updatedAt = apiReq.processed_at?.let { java.util.Date() }
                        )
                    } else {
                        Log.w(TAG, "Could not get sender ID for ${apiReq.sender}")
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error converting API request to DB model", e)
                    null
                }
            }
            
            // Salva nel database (REPLACE strategy sovrascriverebbe duplicati)
            database.contactRequestDao().insertRequests(dbRequests)
            Log.d(TAG, "Synced ${dbRequests.size} requests to local database")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing requests to database", e)
        }
    }
    
    private fun loadPendingRequests() {
        lifecycleScope.launch {
            try {
                // Observe from database
                database.contactRequestDao().getPendingRequestsForUser(currentUserId).collect { dbRequests ->
                    if (dbRequests.isEmpty()) {
                        showEmptyState()
                    } else {
                        hideEmptyState()
                        // Convert database ContactRequest to API ContactRequest for adapter
                        val apiRequests = dbRequests.map { dbRequest ->
                            ContactRequest(
                                id = dbRequest.id.toString(),
                                fromUser = User(
                                    id = dbRequest.fromUserId,
                                    username = dbRequest.fromUsername,
                                    nickname = dbRequest.fromNickname,
                                    profile_picture = dbRequest.fromProfilePicture,
                                    isOnline = false // Not stored in DB, would need to fetch from server
                                ),
                                toUser = User(
                                    id = dbRequest.toUserId,
                                    username = "", // Not needed for display
                                    nickname = null,
                                    profile_picture = null,
                                    isOnline = false
                                ),
                                message = null, // Not stored in current schema
                                status = dbRequest.status.name.lowercase(),
                                createdAt = dbRequest.createdAt.toString(),
                                updatedAt = dbRequest.updatedAt?.toString()
                            )
                        }
                        adapter.updateRequests(apiRequests)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading requests from database", e)
                showEmptyState()
            }
        }
    }
    
    private fun acceptRequest(request: ContactRequest) {
        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            try {
                val respondRequest = ContactRespondRequest(
                    id = request.id.toIntOrNull(),
                    from = request.fromUser.username, // Aggiungi username come fallback
                    action = "accept"
                )
                
                val response = RetrofitClient.apiService.respondToContactRequest(respondRequest)
                
                if (response.isSuccessful && response.body()?.isSuccess() == true) {
                    // Update database
                    database.contactRequestDao().updateRequestStatus(
                        requestId = request.id.toLongOrNull() ?: 0,
                        status = ContactRequestStatus.ACCEPTED,
                        updatedAt = Date()
                    )
                    
                    Toast.makeText(this@ContactRequestsActivity, "Richiesta accettata!", Toast.LENGTH_SHORT).show()
                } else {
                    Log.e(TAG, "Server error: ${response.code()} - ${response.errorBody()?.string()}")
                    Toast.makeText(this@ContactRequestsActivity, "Errore: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting request", e)
                Toast.makeText(this@ContactRequestsActivity, "Errore di rete", Toast.LENGTH_SHORT).show()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }
    
    private fun declineRequest(request: ContactRequest) {
        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            try {
                val respondRequest = ContactRespondRequest(
                    id = request.id.toIntOrNull(),
                    from = request.fromUser.username, // Aggiungi username come fallback
                    action = "decline"
                )
                
                val response = RetrofitClient.apiService.respondToContactRequest(respondRequest)
                
                if (response.isSuccessful && response.body()?.isSuccess() == true) {
                    // Update database
                    database.contactRequestDao().updateRequestStatus(
                        requestId = request.id.toLongOrNull() ?: 0,
                        status = ContactRequestStatus.REJECTED,
                        updatedAt = Date()
                    )
                    
                    Toast.makeText(this@ContactRequestsActivity, "Richiesta rifiutata", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@ContactRequestsActivity, "Errore: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error declining request", e)
                Toast.makeText(this@ContactRequestsActivity, "Errore di rete", Toast.LENGTH_SHORT).show()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }
    
    private fun showEmptyState() {
        emptyStateLayout.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
    }
    
    private fun hideEmptyState() {
        emptyStateLayout.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
    }
}
