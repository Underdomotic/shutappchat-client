package it.fabiodirauso.shutappchat.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import it.fabiodirauso.shutappchat.R
import it.fabiodirauso.shutappchat.model.User
import it.fabiodirauso.shutappchat.utils.AvatarHelper

class ContactSelectionAdapter(
    private var contacts: List<User>,
    private val onContactSelectionChanged: (User, Boolean) -> Unit
) : RecyclerView.Adapter<ContactSelectionAdapter.ContactViewHolder>() {
    
    private val selectedContactIds = mutableSetOf<Long>()
    
    inner class ContactViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatarImageView: ImageView = view.findViewById(R.id.imageViewContactAvatar)
        val nameTextView: TextView = view.findViewById(R.id.textViewContactName)
        val usernameTextView: TextView = view.findViewById(R.id.textViewContactUsername)
        val checkbox: CheckBox = view.findViewById(R.id.checkBoxSelect)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact_selection, parent, false)
        return ContactViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = contacts[position]
        
        holder.nameTextView.text = contact.nickname ?: contact.username
        holder.usernameTextView.text = "@${contact.username}"
        
        // Genera avatar iniziali
        val initialsAvatar = AvatarHelper.generateInitialsAvatar(contact.nickname ?: contact.username)
        holder.avatarImageView.setImageBitmap(initialsAvatar)
        
        // Gestione checkbox
        holder.checkbox.isChecked = selectedContactIds.contains(contact.id)
        
        holder.itemView.setOnClickListener {
            val isSelected = !holder.checkbox.isChecked
            holder.checkbox.isChecked = isSelected
            
            if (isSelected) {
                selectedContactIds.add(contact.id)
            } else {
                selectedContactIds.remove(contact.id)
            }
            
            onContactSelectionChanged(contact, isSelected)
        }
        
        holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedContactIds.add(contact.id)
            } else {
                selectedContactIds.remove(contact.id)
            }
            onContactSelectionChanged(contact, isChecked)
        }
    }
    
    override fun getItemCount() = contacts.size
    
    fun updateContacts(newContacts: List<User>) {
        contacts = newContacts
        notifyDataSetChanged()
    }
    
    fun clearSelection() {
        selectedContactIds.clear()
        notifyDataSetChanged()
    }
}
