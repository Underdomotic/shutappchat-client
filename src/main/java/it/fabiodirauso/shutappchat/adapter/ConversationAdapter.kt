package it.fabiodirauso.shutappchat.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import it.fabiodirauso.shutappchat.R
import it.fabiodirauso.shutappchat.model.ConversationEntity
import it.fabiodirauso.shutappchat.model.ConversationItem
import it.fabiodirauso.shutappchat.utils.AvatarHelper

class ConversationAdapter(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private var items: List<ConversationItem>,
    private val onItemClick: (ConversationItem) -> Unit,
    private val onItemLongClick: ((ConversationItem) -> Unit)? = null
) : RecyclerView.Adapter<ConversationAdapter.ConversationViewHolder>() {

    fun updateItems(newItems: List<ConversationItem>) {
        items = newItems
        notifyDataSetChanged()
    }
    
    // ✅ Manteniamo anche il metodo vecchio per compatibilità
    fun updateConversations(newConversations: List<ConversationEntity>) {
        items = newConversations.map { ConversationItem.Chat(it) }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        return ConversationViewHolder(view)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ConversationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatarImageView: ImageView = itemView.findViewById(R.id.imageViewAvatar)
        private val onlineIndicator: View = itemView.findViewById(R.id.onlineStatusIndicator)
        private val participantName: TextView = itemView.findViewById(R.id.textViewName)
        private val lastMessage: TextView = itemView.findViewById(R.id.textViewLastMessage)
        private val unreadBadge: TextView = itemView.findViewById(R.id.textViewUnreadBadge)
        private val groupIcon: ImageView? = itemView.findViewById(R.id.imageViewGroupIcon)
        private val memberCount: TextView? = itemView.findViewById(R.id.textViewMemberCount)

        fun bind(item: ConversationItem) {
            participantName.text = item.name
            lastMessage.text = item.lastMessage ?: "Nessun messaggio"
            
            // Show unread badge if there are unread messages
            if (item.unreadCount > 0) {
                unreadBadge.visibility = View.VISIBLE
                unreadBadge.text = if (item.unreadCount > 99) "99+" else item.unreadCount.toString()
            } else {
                unreadBadge.visibility = View.GONE
            }
            
            when (item) {
                is ConversationItem.Chat -> {
                    // Conversazione 1-1
                    groupIcon?.visibility = View.GONE
                    memberCount?.visibility = View.GONE
                    onlineIndicator.visibility = View.GONE
                    
                    // Get auth token for avatar loading
                    val sharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    val authToken = sharedPreferences.getString("auth_token", "") ?: ""
                    
                    // Load avatar for the conversation participant
                    AvatarHelper.loadUserAvatar(
                        context = context,
                        imageView = avatarImageView,
                        username = item.conversation.participantName,
                        userToken = authToken,
                        profilePictureId = item.conversation.profilePictureId,
                        lifecycleScope = lifecycleScope
                    )
                }
                
                is ConversationItem.Group -> {
                    // Conversazione di gruppo
                    groupIcon?.visibility = View.VISIBLE
                    memberCount?.visibility = View.VISIBLE
                    memberCount?.text = "${item.group.totalMembers} membri"
                    onlineIndicator.visibility = View.GONE
                    
                    // Mostra icona gruppo di default o immagine gruppo se presente
                    if (item.group.groupPictureId != null) {
                        // TODO: Carica immagine gruppo dal server
                        avatarImageView.setImageResource(R.drawable.ic_group_default)
                    } else {
                        avatarImageView.setImageResource(R.drawable.ic_group_default)
                    }
                }
            }
            
            itemView.setOnClickListener {
                onItemClick(item)
            }
            
            itemView.setOnLongClickListener {
                onItemLongClick?.invoke(item)
                true
            }
        }
    }
}
