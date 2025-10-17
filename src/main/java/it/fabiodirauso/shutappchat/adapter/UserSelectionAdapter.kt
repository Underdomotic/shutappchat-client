package it.fabiodirauso.shutappchat.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import it.fabiodirauso.shutappchat.R
import it.fabiodirauso.shutappchat.dialogs.UserSelectionItem
import it.fabiodirauso.shutappchat.utils.AvatarHelper

class UserSelectionAdapter(
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<UserSelectionAdapter.ViewHolder>() {
    
    private var users = listOf<UserSelectionItem>()
    
    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatarImageView: ImageView = view.findViewById(R.id.avatarImageView)
        val usernameTextView: TextView = view.findViewById(R.id.usernameTextView)
        val nicknameTextView: TextView = view.findViewById(R.id.nicknameTextView)
        val checkBox: CheckBox = view.findViewById(R.id.checkBox)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user_selection, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val user = users[position]
        
        // Display name
        holder.usernameTextView.text = "@${user.username}"
        
        if (!user.nickname.isNullOrEmpty()) {
            holder.nicknameTextView.visibility = View.VISIBLE
            holder.nicknameTextView.text = user.nickname
        } else {
            holder.nicknameTextView.visibility = View.GONE
        }
        
        // Avatar - show initials for simplicity
        val displayName = user.nickname ?: user.username
        val avatar = AvatarHelper.generateInitialsAvatar(displayName)
        holder.avatarImageView.setImageBitmap(avatar)
        
        // Checkbox state
        holder.checkBox.isChecked = user.isSelected
        
        // Click handlers
        holder.itemView.setOnClickListener {
            user.isSelected = !user.isSelected
            holder.checkBox.isChecked = user.isSelected
            onSelectionChanged()
        }
        
        holder.checkBox.setOnClickListener {
            user.isSelected = holder.checkBox.isChecked
            onSelectionChanged()
        }
    }
    
    override fun getItemCount() = users.size
    
    fun updateUsers(newUsers: List<UserSelectionItem>) {
        users = newUsers
        notifyDataSetChanged()
    }
    
    fun getSelectedUserIds(): List<Long> {
        return users.filter { it.isSelected }.map { it.id }
    }
}
