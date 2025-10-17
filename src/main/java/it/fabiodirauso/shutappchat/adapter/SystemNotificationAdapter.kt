package it.fabiodirauso.shutappchat.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import it.fabiodirauso.shutappchat.R
import it.fabiodirauso.shutappchat.model.SystemNotification
import java.text.SimpleDateFormat
import java.util.*

class SystemNotificationAdapter(
    private val onNotificationClick: (SystemNotification) -> Unit,
    private val onNotificationLongClick: (SystemNotification) -> Unit,
    private val onSwipeToDelete: (SystemNotification) -> Unit,
    private val onSwipeToOpenUrl: (SystemNotification) -> Unit
) : ListAdapter<SystemNotification, SystemNotificationAdapter.ViewHolder>(DiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_system_notification, parent, false)
        return ViewHolder(view, onNotificationClick, onNotificationLongClick, onSwipeToDelete, onSwipeToOpenUrl)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    fun getNotificationAt(position: Int): SystemNotification? {
        return if (position >= 0 && position < currentList.size) {
            currentList[position]
        } else {
            null
        }
    }
    
    class ViewHolder(
        itemView: View,
        private val onNotificationClick: (SystemNotification) -> Unit,
        private val onNotificationLongClick: (SystemNotification) -> Unit,
        private val onSwipeToDelete: (SystemNotification) -> Unit,
        private val onSwipeToOpenUrl: (SystemNotification) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val cardView: CardView = itemView as CardView
        private val containerLayout: View = itemView.findViewById(R.id.notificationContainer)
        private val titleTextView: TextView = itemView.findViewById(R.id.textViewTitle)
        private val descriptionTextView: TextView = itemView.findViewById(R.id.textViewDescription)
        private val timestampTextView: TextView = itemView.findViewById(R.id.textViewTimestamp)
        private val urlIndicator: View = itemView.findViewById(R.id.urlIndicator)
        private val unreadIndicator: View = itemView.findViewById(R.id.unreadIndicator)
        private val swipeHintTextView: TextView = itemView.findViewById(R.id.textViewSwipeHint)
        
        private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.ITALIAN)
        
        fun bind(notification: SystemNotification) {
            // Title
            if (!notification.title.isNullOrEmpty()) {
                titleTextView.text = notification.title
                titleTextView.visibility = View.VISIBLE
            } else {
                titleTextView.visibility = View.GONE
            }
            
            // Description
            if (!notification.description.isNullOrEmpty()) {
                descriptionTextView.text = notification.description
                descriptionTextView.visibility = View.VISIBLE
            } else {
                descriptionTextView.visibility = View.GONE
            }
            
            // Timestamp
            timestampTextView.text = dateFormat.format(Date(notification.timestamp))
            
            // URL indicator
            urlIndicator.visibility = if (!notification.url.isNullOrEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }
            
            // Swipe hint - mostra solo se c'è un URL
            swipeHintTextView.visibility = if (!notification.url.isNullOrEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }
            
            // Visual feedback per notifiche lette/non lette
            if (notification.read) {
                // Notifica letta: sfondo più scuro e leggermente trasparente
                containerLayout.setBackgroundColor(Color.parseColor("#40000000")) // Grigio scuro semi-trasparente
                itemView.alpha = 0.65f
                unreadIndicator.visibility = View.GONE
            } else {
                // Notifica non letta: sfondo normale e più visibile
                containerLayout.setBackgroundColor(Color.TRANSPARENT)
                itemView.alpha = 1.0f
                unreadIndicator.visibility = View.VISIBLE
            }
            
            // Click handler
            itemView.setOnClickListener {
                onNotificationClick(notification)
            }
            
            // Long click handler per marcare come letta/non letta
            itemView.setOnLongClickListener {
                onNotificationLongClick(notification)
                true
            }
        }
    }
    
    class DiffCallback : DiffUtil.ItemCallback<SystemNotification>() {
        override fun areItemsTheSame(oldItem: SystemNotification, newItem: SystemNotification): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: SystemNotification, newItem: SystemNotification): Boolean {
            return oldItem == newItem
        }
    }
}