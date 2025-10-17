package it.fabiodirauso.shutappchat.dialogs

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import it.fabiodirauso.shutappchat.R
import it.fabiodirauso.shutappchat.adapter.UserSelectionAdapter
import it.fabiodirauso.shutappchat.network.RetrofitClient
import kotlinx.coroutines.launch

class AddMembersDialog : DialogFragment() {
    
    companion object {
        private const val TAG = "AddMembersDialog"
        private const val ARG_GROUP_ID = "group_id"
        private const val ARG_GROUP_NAME = "group_name"
        
        fun newInstance(groupId: String, groupName: String): AddMembersDialog {
            return AddMembersDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                    putString(ARG_GROUP_NAME, groupName)
                }
            }
        }
    }
    
    private lateinit var groupId: String
    private lateinit var groupName: String
    
    private lateinit var searchEditText: EditText
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyTextView: TextView
    private lateinit var addButton: Button
    private lateinit var cancelButton: Button
    
    private lateinit var adapter: UserSelectionAdapter
    private var allUsers = mutableListOf<UserSelectionItem>()
    
    var onMembersAdded: ((List<Long>) -> Unit)? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupId = arguments?.getString(ARG_GROUP_ID) ?: ""
        groupName = arguments?.getString(ARG_GROUP_NAME) ?: ""
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_add_members, null)
        
        searchEditText = view.findViewById(R.id.searchEditText)
        recyclerView = view.findViewById(R.id.recyclerViewUsers)
        progressBar = view.findViewById(R.id.progressBar)
        emptyTextView = view.findViewById(R.id.emptyTextView)
        addButton = view.findViewById(R.id.buttonAdd)
        cancelButton = view.findViewById(R.id.buttonCancel)
        
        setupRecyclerView()
        setupSearch()
        setupButtons()
        loadUsers()
        
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("Aggiungi membri a $groupName")
            .setView(view)
            .create()
    }
    
    private fun setupRecyclerView() {
        adapter = UserSelectionAdapter { updateAddButtonState() }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }
    
    private fun setupSearch() {
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterUsers(s?.toString() ?: "")
            }
        })
    }
    
    private fun setupButtons() {
        cancelButton.setOnClickListener { dismiss() }
        addButton.setOnClickListener { addSelectedMembers() }
        updateAddButtonState()
    }
    
    private fun updateAddButtonState() {
        val selectedCount = adapter.getSelectedUserIds().size
        addButton.isEnabled = selectedCount > 0
        addButton.text = if (selectedCount > 0) {
            "Aggiungi ($selectedCount)"
        } else {
            "Aggiungi membri"
        }
    }
    
    private fun loadUsers() {
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyTextView.visibility = View.GONE
        
        lifecycleScope.launch {
            try {
                // Load user's contacts instead of all users
                val response = RetrofitClient.apiService.getContacts()
                if (response.isSuccessful) {
                    val contactsResponse = response.body()
                    val contacts = contactsResponse?.contacts ?: emptyList()
                    Log.d(TAG, "Loaded ${contacts.size} contacts")
                    
                    // Get current group members to exclude them
                    val membersResponse = RetrofitClient.apiService.getGroupMembers(groupId)
                    val currentMembers = if (membersResponse.isSuccessful) {
                        membersResponse.body()?.members?.map { it.user_id } ?: emptyList()
                    } else {
                        emptyList()
                    }
                    
                    allUsers = contacts
                        .filter { contact ->
                            contact.id !in currentMembers
                        }
                        .map { contact ->
                            UserSelectionItem(
                                id = contact.id,
                                username = contact.username,
                                nickname = contact.nickname,
                                profilePicture = contact.profile_picture
                            )
                        }
                        .toMutableList()
                    
                    if (allUsers.isEmpty()) {
                        emptyTextView.visibility = View.VISIBLE
                        emptyTextView.text = "Tutti i tuoi contatti sono già nel gruppo"
                    } else {
                        adapter.updateUsers(allUsers)
                        recyclerView.visibility = View.VISIBLE
                    }
                } else {
                    Log.e(TAG, "Failed to load contacts: ${response.code()}")
                    Toast.makeText(requireContext(), "Errore caricamento contatti", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading contacts", e)
                Toast.makeText(requireContext(), "Errore: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }
    
    private fun filterUsers(query: String) {
        val filtered = if (query.isEmpty()) {
            allUsers
        } else {
            allUsers.filter { user ->
                user.username.contains(query, ignoreCase = true) ||
                user.nickname?.contains(query, ignoreCase = true) == true
            }
        }
        adapter.updateUsers(filtered)
        
        emptyTextView.visibility = if (filtered.isEmpty() && allUsers.isNotEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }
    
    private fun addSelectedMembers() {
        val selectedIds = adapter.getSelectedUserIds()
        if (selectedIds.isEmpty()) {
            Toast.makeText(requireContext(), "Seleziona almeno un utente", Toast.LENGTH_SHORT).show()
            return
        }
        
        progressBar.visibility = View.VISIBLE
        addButton.isEnabled = false
        
        lifecycleScope.launch {
            try {
                val request = it.fabiodirauso.shutappchat.api.AddMembersRequest(
                    user_ids = selectedIds
                )
                
                val response = RetrofitClient.apiService.addGroupMembers(groupId, request)
                
                if (response.isSuccessful && response.body()?.success == true) {
                    Toast.makeText(requireContext(), "${selectedIds.size} membri aggiunti", Toast.LENGTH_SHORT).show()
                    onMembersAdded?.invoke(selectedIds)
                    dismiss()
                } else {
                    val errorMsg = response.body()?.message ?: "Errore sconosciuto"
                    Toast.makeText(requireContext(), "Errore: $errorMsg", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding members", e)
                Toast.makeText(requireContext(), "Errore: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar.visibility = View.GONE
                addButton.isEnabled = true
            }
        }
    }
}

data class UserSelectionItem(
    val id: Long,
    val username: String,
    val nickname: String?,
    val profilePicture: String?,
    var isSelected: Boolean = false
)
