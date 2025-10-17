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
import it.fabiodirauso.shutappchat.api.ContactRequest
import it.fabiodirauso.shutappchat.utils.AvatarHelper

class PendingRequestsAdapter(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onAccept: (ContactRequest) -> Unit,
    private val onDecline: (ContactRequest) -> Unit
) : RecyclerView.Adapter<PendingRequestsAdapter.RequestViewHolder>() {
    
    private var requests = listOf<ContactRequest>()
    
    fun updateRequests(newRequests: List<ContactRequest>) {
        requests = newRequests
        notifyDataSetChanged()
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RequestViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pending_request, parent, false)
        return RequestViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: RequestViewHolder, position: Int) {
        val request = requests[position]
        holder.bind(request, context, lifecycleScope, onAccept, onDecline)
    }
    
    override fun getItemCount(): Int = requests.size
    
    class RequestViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatarImageView: ImageView = itemView.findViewById(R.id.ivRequestAvatar)
        private val onlineStatusIndicator: View = itemView.findViewById(R.id.onlineStatusIndicator)
        private val textViewFrom: TextView = itemView.findViewById(R.id.tvRequestName)
        private val textViewMessage: TextView = itemView.findViewById(R.id.tvRequestMessage)
        private val textViewTime: TextView = itemView.findViewById(R.id.tvRequestTime)
        private val buttonAccept: Button = itemView.findViewById(R.id.btnAccept)
        private val buttonDecline: Button = itemView.findViewById(R.id.btnDecline)
        
        fun bind(
            request: ContactRequest,
            context: Context,
            lifecycleScope: LifecycleCoroutineScope,
            onAccept: (ContactRequest) -> Unit,
            onDecline: (ContactRequest) -> Unit
        ) {
            textViewFrom.text = "Da: ${request.fromUser.username}"
            
            // Show online status
            onlineStatusIndicator.visibility = if (request.fromUser.isOnline) View.VISIBLE else View.GONE
            
            // Load avatar
            val sharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val currentUserToken = sharedPreferences.getString("auth_token", "") ?: ""
            
            AvatarHelper.loadUserAvatar(
                context = context,
                imageView = avatarImageView,
                username = request.fromUser.username,
                userToken = currentUserToken,
                profilePictureId = request.fromUser.profile_picture,
                lifecycleScope = lifecycleScope
            )
            
            if (!request.message.isNullOrEmpty()) {
                textViewMessage.text = request.message
                textViewMessage.visibility = View.VISIBLE
            } else {
                textViewMessage.visibility = View.GONE
            }
            
            textViewTime.text = formatTimestamp(request.createdAt)
            
            buttonAccept.setOnClickListener { onAccept(request) }
            buttonDecline.setOnClickListener { onDecline(request) }
        }
        
        private fun formatTimestamp(timestamp: String): String {
            // Simple timestamp formatting - could be improved
            return try {
                timestamp.substringBefore('T').replace('-', '/')
            } catch (e: Exception) {
                timestamp
            }
        }
    }
}