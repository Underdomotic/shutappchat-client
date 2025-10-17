package it.fabiodirauso.shutappchat.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import it.fabiodirauso.shutappchat.CreateGroupActivity
import it.fabiodirauso.shutappchat.GroupChatActivity
import it.fabiodirauso.shutappchat.R
import it.fabiodirauso.shutappchat.adapter.GroupListAdapter
import it.fabiodirauso.shutappchat.managers.GroupRepository
import it.fabiodirauso.shutappchat.model.GroupEntity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Fragment per visualizzare la lista dei gruppi
 */
class GroupListFragment : Fragment() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var searchView: SearchView
    private lateinit var fabCreateGroup: FloatingActionButton
    private lateinit var emptyView: View
    
    private lateinit var groupAdapter: GroupListAdapter
    private lateinit var groupRepository: GroupRepository
    
    private var allGroups: List<GroupEntity> = emptyList()
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_group_list, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Inizializza repository
        groupRepository = GroupRepository.getInstance(requireContext())
        
        // Setup views
        recyclerView = view.findViewById(R.id.recyclerViewGroups)
        swipeRefresh = view.findViewById(R.id.swipeRefreshLayout)
        searchView = view.findViewById(R.id.searchViewGroups)
        fabCreateGroup = view.findViewById(R.id.fabCreateGroup)
        emptyView = view.findViewById(R.id.textViewEmpty)
        
        setupRecyclerView()
        setupSearch()
        setupSwipeRefresh()
        setupFab()
        
        // Osserva i gruppi dal database locale
        observeGroups()
        
        // Carica i gruppi dal server
        refreshGroups()
    }
    
    private fun setupRecyclerView() {
        groupAdapter = GroupListAdapter(
            lifecycleScope = viewLifecycleOwner.lifecycleScope
        ) { group ->
            onGroupClick(group)
        }
        
        recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = groupAdapter
        }
    }
    
    private fun setupSearch() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                filterGroups(query ?: "")
                return true
            }
            
            override fun onQueryTextChange(newText: String?): Boolean {
                filterGroups(newText ?: "")
                return true
            }
        })
    }
    
    private fun setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener {
            refreshGroups()
        }
    }
    
    private fun setupFab() {
        fabCreateGroup.setOnClickListener {
            val intent = Intent(requireContext(), CreateGroupActivity::class.java)
            startActivity(intent)
        }
    }
    
    private fun observeGroups() {
        viewLifecycleOwner.lifecycleScope.launch {
            groupRepository.observeAllGroups().collectLatest { groups ->
                allGroups = groups
                updateUI(groups)
            }
        }
    }
    
    private fun refreshGroups() {
        viewLifecycleOwner.lifecycleScope.launch {
            swipeRefresh.isRefreshing = true
            
            val result = groupRepository.refreshGroups()
            
            swipeRefresh.isRefreshing = false
            
            if (result.isFailure) {
                Toast.makeText(
                    requireContext(),
                    "Errore nel caricamento dei gruppi: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    private fun filterGroups(query: String) {
        val filtered = if (query.isEmpty()) {
            allGroups
        } else {
            allGroups.filter { group ->
                group.groupName.contains(query, ignoreCase = true) ||
                group.groupDescription?.contains(query, ignoreCase = true) == true
            }
        }
        updateUI(filtered)
    }
    
    private fun updateUI(groups: List<GroupEntity>) {
        if (groups.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            groupAdapter.submitList(groups)
        }
    }
    
    private fun onGroupClick(group: GroupEntity) {
        // Navigate to GroupChatActivity
        val intent = Intent(requireContext(), GroupChatActivity::class.java)
        intent.putExtra(GroupChatActivity.EXTRA_GROUP_ID, group.groupId)
        startActivity(intent)
    }
    
    companion object {
        fun newInstance() = GroupListFragment()
    }
}
