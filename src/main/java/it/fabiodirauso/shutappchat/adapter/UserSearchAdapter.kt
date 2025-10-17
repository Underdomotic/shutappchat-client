package it.fabiodirauso.shutappchat.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import it.fabiodirauso.shutappchat.R
import it.fabiodirauso.shutappchat.model.User
import it.fabiodirauso.shutappchat.utils.AvatarHelper

// Adapter semplificato per visualizzare i contatti esistenti
class ContactsAdapter(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onContactClick: (User) -> Unit
) : RecyclerView.Adapter<ContactsAdapter.ContactViewHolder>() {

    private var contacts = mutableListOf<User>()
    private var filteredContacts = mutableListOf<User>()

    fun updateContacts(newContacts: List<User>) {
        contacts.clear()
        contacts.addAll(newContacts)
        filteredContacts.clear()
        filteredContacts.addAll(newContacts)
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        filteredContacts.clear()
        if (query.isEmpty()) {
            filteredContacts.addAll(contacts)
        } else {
            val searchQuery = query.lowercase()
            filteredContacts.addAll(contacts.filter { contact ->
                contact.username.lowercase().contains(searchQuery) ||
                contact.nickname?.lowercase()?.contains(searchQuery) == true
            })
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = filteredContacts[position]
        holder.bind(contact)
    }

    override fun getItemCount(): Int = filteredContacts.size

    inner class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageViewAvatar: ImageView = itemView.findViewById(R.id.imageViewContactAvatar)
        private val textViewName: TextView = itemView.findViewById(R.id.textViewContactName)
        private val textViewUsername: TextView = itemView.findViewById(R.id.textViewContactUsername)
        private val onlineStatusIndicator: View = itemView.findViewById(R.id.onlineStatusIndicator)
        private val buttonContact: Button = itemView.findViewById(R.id.buttonContact)

        fun bind(contact: User) {
            // Mostra nome preferito (nickname o username)
            textViewName.text = contact.nickname ?: contact.username
            textViewUsername.text = "@${contact.username}"

            // Mostra stato online
            onlineStatusIndicator.visibility = if (contact.isOnline) View.VISIBLE else View.GONE

            // Carica avatar - usando il token dell'utente corrente
            val sharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val currentUserToken = sharedPreferences.getString("auth_token", "") ?: ""
            
            AvatarHelper.loadUserAvatar(
                context = context,
                imageView = imageViewAvatar,
                username = contact.username,
                userToken = currentUserToken,
                profilePictureId = contact.profile_picture,
                lifecycleScope = lifecycleScope
            )

            // Click listener per pulsante "Contatta"
            buttonContact.setOnClickListener {
                onContactClick(contact)
            }
        }
    }
}

class UserSearchAdapter(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val currentUserToken: String,
    private val onUserClick: (User) -> Unit
) : RecyclerView.Adapter<UserSearchAdapter.UserViewHolder>() {

    private var users = mutableListOf<User>()
    private var requestSentUsers = mutableSetOf<Long>()

    fun updateUsers(newUsers: List<User>) {
        users.clear()
        users.addAll(newUsers)
        notifyDataSetChanged()
    }

    fun markRequestSent(userId: Long) {
        requestSentUsers.add(userId)
        val position = users.indexOfFirst { it.id == userId }
        if (position != -1) {
            notifyItemChanged(position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user_search_result, parent, false)
        return UserViewHolder(view)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        val user = users[position]
        holder.bind(user, requestSentUsers.contains(user.id))
    }

    override fun getItemCount(): Int = users.size

    inner class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageViewAvatar: ImageView = itemView.findViewById(R.id.imageViewUserAvatar)
        private val textViewUsername: TextView = itemView.findViewById(R.id.textViewUserName)
        private val buttonAddFriend: Button = itemView.findViewById(R.id.buttonAddContact)
        private val onlineStatusIndicator: View = itemView.findViewById(R.id.onlineStatusIndicator)

        fun bind(user: User, isRequestSent: Boolean) {
            // Display nickname or username
            if (!user.nickname.isNullOrEmpty()) {
                textViewUsername.text = user.nickname
            } else {
                textViewUsername.text = "@${user.username}"
            }

            // Show online status
            onlineStatusIndicator.visibility = if (user.isOnline) View.VISIBLE else View.GONE

            // Friend request button
            if (isRequestSent) {
                buttonAddFriend.text = "Richiesta Inviata"
                buttonAddFriend.isEnabled = false
                buttonAddFriend.alpha = 0.6f
            } else {
                buttonAddFriend.text = "Aggiungi"
                buttonAddFriend.isEnabled = true
                buttonAddFriend.alpha = 1.0f
                buttonAddFriend.setOnClickListener {
                    onUserClick(user)
                }
            }

            // Load avatar using AvatarHelper
            AvatarHelper.loadUserAvatar(
                context = context,
                imageView = imageViewAvatar,
                username = user.username,
                userToken = currentUserToken,
                profilePictureId = user.profile_picture,
                lifecycleScope = lifecycleScope
            )
        }
    }
}