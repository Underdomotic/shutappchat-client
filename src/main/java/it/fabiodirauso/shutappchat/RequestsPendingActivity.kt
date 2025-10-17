package it.fabiodirauso.shutappchat

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import it.fabiodirauso.shutappchat.network.RetrofitClient
import it.fabiodirauso.shutappchat.adapter.PendingRequestsAdapter
import it.fabiodirauso.shutappchat.api.ContactRequest

class RequestsPendingActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: LinearLayout
    private lateinit var adapter: PendingRequestsAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_requests_pending)
        
        // Enable fullscreen immersive mode
        it.fabiodirauso.shutappchat.utils.UIHelper.enableImmersiveMode(this)
        
        // Setup toolbar
        supportActionBar?.title = getString(R.string.title_pending_requests)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        setupViews()
        setupAuth()
        loadPendingRequests()
    }
    
    private fun setupViews() {
        try {
            recyclerView = findViewById(R.id.recyclerViewPendingRequests)
            emptyView = findViewById(R.id.textViewEmpty)
            
            adapter = PendingRequestsAdapter(
                context = this,
                lifecycleScope = lifecycleScope,
                onAccept = { request -> 
                    respondToRequest(request.id, "accept")
                },
                onDecline = { request -> 
                    respondToRequest(request.id, "decline")
                }
            )
            
            recyclerView.layoutManager = LinearLayoutManager(this)
            recyclerView.adapter = adapter
        } catch (e: Exception) {
            android.util.Log.e("RequestsPendingActivity", "Error setting up views", e)
            finish() // Close activity if views can't be set up
        }
    }
    
    private fun setupAuth() {
        val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val token = sharedPreferences.getString("auth_token", null)
        if (!token.isNullOrEmpty()) {
            RetrofitClient.setAuthToken(token)
        }
    }
    
    private fun loadPendingRequests() {
        lifecycleScope.launch {
            try {
                android.util.Log.d("RequestsPendingActivity", "Loading pending requests...")
                
                val response = RetrofitClient.apiService.getContactRequests("incoming")
                
                android.util.Log.d("RequestsPendingActivity", "Response code: ${response.code()}")
                
                if (response.isSuccessful && response.body() != null) {
                    val result = response.body()!!
                    val apiRequests = result.requests
                    
                    android.util.Log.d("RequestsPendingActivity", "Found ${apiRequests.size} requests")
                    
                    if (apiRequests.isEmpty()) {
                        showEmptyView()
                    } else {
                        // Converti PendingContactRequestAPI -> ContactRequest (vecchio formato per l'adapter)
                        val requests = apiRequests.map { apiReq ->
                            ContactRequest(
                                id = apiReq.id.toString(),
                                fromUser = it.fabiodirauso.shutappchat.model.User(
                                    id = 0, // Non disponibile, dovremmo fare fetch
                                    username = apiReq.sender,
                                    nickname = null,
                                    profile_picture = null,
                                    isOnline = false
                                ),
                                toUser = it.fabiodirauso.shutappchat.model.User(
                                    id = 0,
                                    username = apiReq.receiver,
                                    nickname = null,
                                    profile_picture = null,
                                    isOnline = false
                                ),
                                message = apiReq.message,
                                status = apiReq.status,
                                createdAt = apiReq.timestamp,
                                updatedAt = apiReq.processed_at
                            )
                        }
                        showRequests(requests)
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    android.util.Log.e("RequestsPendingActivity", "Request failed: code=${response.code()}, error=$errorBody")
                    Toast.makeText(this@RequestsPendingActivity, "Errore: ${response.message()}", Toast.LENGTH_SHORT).show()
                    showEmptyView()
                }
            } catch (e: Exception) {
                android.util.Log.e("RequestsPendingActivity", "Error loading pending requests", e)
                Toast.makeText(this@RequestsPendingActivity, "Errore di rete: ${e.message}", Toast.LENGTH_SHORT).show()
                showEmptyView()
            }
        }
    }
    
    private fun respondToRequest(requestId: String, action: String) {
        lifecycleScope.launch {
            try {
                android.util.Log.d("RequestsPendingActivity", "Responding to request $requestId with action: $action")
                
                val request = it.fabiodirauso.shutappchat.api.ContactRespondRequest(
                    id = requestId.toIntOrNull(),
                    action = action
                )
                
                val response = RetrofitClient.apiService.respondToContactRequest(request)
                
                if (response.isSuccessful && response.body() != null) {
                    val result = response.body()!!
                    if (result.isSuccess()) {
                        val actionText = if (action == "accept") "accettata" else "rifiutata"
                        Toast.makeText(this@RequestsPendingActivity, "Richiesta $actionText", Toast.LENGTH_SHORT).show()
                        // Reload requests
                        loadPendingRequests()
                    } else {
                        Toast.makeText(this@RequestsPendingActivity, "Errore: ${result.message ?: "Richiesta non riuscita"}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@RequestsPendingActivity, "Errore nella risposta: ${response.message()}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("RequestsPendingActivity", "Error responding to request", e)
                Toast.makeText(this@RequestsPendingActivity, "Errore di rete: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showEmptyView() {
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
    }
    
    private fun showRequests(requests: List<ContactRequest>) {
        recyclerView.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        adapter.updateRequests(requests)
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
