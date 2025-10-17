package it.fabiodirauso.shutappchat.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import it.fabiodirauso.shutappchat.R
import it.fabiodirauso.shutappchat.model.GroupEntity
import it.fabiodirauso.shutappchat.utils.AvatarHelper
import java.text.SimpleDateFormat
import java.util.*

/**
 * Adapter per la lista dei gruppi
 */
class GroupListAdapter(
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onGroupClick: (GroupEntity) -> Unit
) : ListAdapter<GroupEntity, GroupListAdapter.GroupViewHolder>(GroupDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_group, parent, false)
        return GroupViewHolder(view, lifecycleScope, onGroupClick)
    }
    
    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    class GroupViewHolder(
        itemView: View,
        private val lifecycleScope: LifecycleCoroutineScope,
        private val onGroupClick: (GroupEntity) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val groupIcon: ImageView = itemView.findViewById(R.id.imageViewGroupIcon)
        private val groupName: TextView = itemView.findViewById(R.id.textViewGroupName)
        private val lastMessage: TextView = itemView.findViewById(R.id.textViewLastMessage)
        private val lastMessageTime: TextView = itemView.findViewById(R.id.textViewLastMessageTime)
        private val unreadBadge: TextView = itemView.findViewById(R.id.textViewUnreadBadge)
        private val memberCount: TextView = itemView.findViewById(R.id.textViewMemberCount)
        private val groupModeIcon: ImageView = itemView.findViewById(R.id.imageViewGroupModeIcon)
        
        fun bind(group: GroupEntity) {
            // Immagine gruppo
            AvatarHelper.loadGroupAvatar(
                context = itemView.context,
                imageView = groupIcon,
                groupName = group.groupName,
                groupPictureId = group.groupPictureId,
                lifecycleScope = lifecycleScope
            )
            
            // Nome gruppo
            groupName.text = group.groupName
            
            // Ultimo messaggio
            if (group.lastMessageContent != null) {
                lastMessage.text = group.lastMessageContent
                lastMessage.visibility = View.VISIBLE
            } else {
                lastMessage.text = "Nessun messaggio"
                lastMessage.visibility = View.VISIBLE
            }
            
            // Timestamp ultimo messaggio
            if (group.lastMessageTime > 0) {
                lastMessageTime.text = formatTimestamp(group.lastMessageTime)
                lastMessageTime.visibility = View.VISIBLE
            } else {
                lastMessageTime.visibility = View.GONE
            }
            
            // Badge messaggi non letti
            if (group.unreadCount > 0) {
                unreadBadge.text = if (group.unreadCount > 99) "99+" else group.unreadCount.toString()
                unreadBadge.visibility = View.VISIBLE
            } else {
                unreadBadge.visibility = View.GONE
            }
            
            // Numero membri
            memberCount.text = "${group.totalMembers} membri"
            
            // Icona modalità gruppo
            when (group.groupMode) {
                it.fabiodirauso.shutappchat.model.GroupMode.RESTRICTED -> {
                    groupModeIcon.setImageResource(R.drawable.ic_lock)
                    groupModeIcon.visibility = View.VISIBLE
                }
                else -> {
                    groupModeIcon.visibility = View.GONE
                }
            }
            
            // Click listener
            itemView.setOnClickListener {
                onGroupClick(group)
            }
        }
        
        private fun formatTimestamp(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            
            return when {
                diff < 60_000 -> "Ora"
                diff < 3600_000 -> "${diff / 60_000}min"
                diff < 86400_000 -> SimpleDateFormat("HH:mm", Locale.ITALIAN).format(Date(timestamp))
                diff < 604800_000 -> SimpleDateFormat("EEE", Locale.ITALIAN).format(Date(timestamp))
                else -> SimpleDateFormat("dd/MM/yy", Locale.ITALIAN).format(Date(timestamp))
            }
        }
    }
    
    class GroupDiffCallback : DiffUtil.ItemCallback<GroupEntity>() {
        override fun areItemsTheSame(oldItem: GroupEntity, newItem: GroupEntity): Boolean {
            return oldItem.groupId == newItem.groupId
        }
        
        override fun areContentsTheSame(oldItem: GroupEntity, newItem: GroupEntity): Boolean {
            return oldItem == newItem
        }
    }
}
