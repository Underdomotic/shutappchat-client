package it.fabiodirauso.shutappchat.adapter

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import it.fabiodirauso.shutappchat.R
import it.fabiodirauso.shutappchat.model.Message
import it.fabiodirauso.shutappchat.model.MessageType
import it.fabiodirauso.shutappchat.model.MessageStatus
import it.fabiodirauso.shutappchat.services.ChatMediaService
import it.fabiodirauso.shutappchat.network.RetrofitClient
import it.fabiodirauso.shutappchat.utils.VideoDownloadQueue
import it.fabiodirauso.shutappchat.managers.SecuritySettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

class MessageAdapter(
    private val context: Context,
    private var messages: List<Message>,
    private val currentUserId: Long,
    private val lifecycleScope: CoroutineScope,
    private val onMediaClick: (Message) -> Unit,
    private val onReplyMessage: (Message) -> Unit,  // ✅ NUOVO: Callback per rispondere
    private val onForwardMessage: (Message) -> Unit,  // ✅ NUOVO: Callback per inoltrare
    private val onDeleteMessage: (Message) -> Unit,  // ✅ NUOVO: Callback per eliminare (ex onMessageLongClick)
    private val authToken: String? = null,
    private val isGroupChat: Boolean = false,  // ✅ NUOVO: Flag per chat di gruppo
    private val database: it.fabiodirauso.shutappchat.database.AppDatabase? = null  // ✅ NUOVO: Per caricare nomi membri
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val securityManager = SecuritySettingsManager.getInstance(context)

    companion object {
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
        private const val VIEW_TYPE_IMAGE_SENT = 3
        private const val VIEW_TYPE_IMAGE_RECEIVED = 4
        private const val VIEW_TYPE_VIDEO_SENT = 5
        private const val VIEW_TYPE_VIDEO_RECEIVED = 6
    }

    fun updateMessages(newMessages: List<Message>) {
        messages = newMessages
        notifyDataSetChanged()
    }

    private fun showMessageOptionsMenu(anchorView: View, message: Message) {
        val popup = PopupMenu(context, anchorView)
        popup.menuInflater.inflate(R.menu.message_options_menu, popup.menu)
        
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_reply -> {
                    onReplyMessage(message)
                    true
                }
                R.id.action_forward -> {
                    onForwardMessage(message)
                    true
                }
                R.id.action_delete -> {
                    onDeleteMessage(message)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun getItemViewType(position: Int): Int {
        val message = messages[position]
        val isSent = message.senderId == currentUserId
        
        return when (message.messageType) {
            MessageType.IMAGE -> if (isSent) VIEW_TYPE_IMAGE_SENT else VIEW_TYPE_IMAGE_RECEIVED
            MessageType.VIDEO -> if (isSent) VIEW_TYPE_VIDEO_SENT else VIEW_TYPE_VIDEO_RECEIVED
            else -> if (isSent) VIEW_TYPE_SENT else VIEW_TYPE_RECEIVED
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_SENT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_sent, parent, false)
                SentMessageViewHolder(view)
            }
            VIEW_TYPE_RECEIVED -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_received, parent, false)
                ReceivedMessageViewHolder(view)
            }
            VIEW_TYPE_IMAGE_SENT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_image_sent, parent, false)
                ImageSentMessageViewHolder(view)
            }
            VIEW_TYPE_IMAGE_RECEIVED -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_image_received, parent, false)
                ImageReceivedMessageViewHolder(view)
            }
            VIEW_TYPE_VIDEO_SENT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_image_sent, parent, false)  // Riusa layout immagine
                VideoSentMessageViewHolder(view)
            }
            VIEW_TYPE_VIDEO_RECEIVED -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_image_received, parent, false)  // Riusa layout immagine
                VideoReceivedMessageViewHolder(view)
            }
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        when (holder) {
            is SentMessageViewHolder -> holder.bind(message)
            is ReceivedMessageViewHolder -> holder.bind(message)
            is ImageSentMessageViewHolder -> holder.bind(message)
            is ImageReceivedMessageViewHolder -> holder.bind(message)
            is VideoSentMessageViewHolder -> holder.bind(message)
            is VideoReceivedMessageViewHolder -> holder.bind(message)
        }
    }

    override fun getItemCount(): Int = messages.size

    // ViewHolder per messaggi INVIATI (a destra)
    inner class SentMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.textViewMessageContentSent)
        private val messageTime: TextView = itemView.findViewById(R.id.textViewMessageTimestampSent)
        private val imageAttachment: ImageView? = itemView.findViewById(R.id.imageViewAttachmentSent)
        private val statusIcon: ImageView = itemView.findViewById(R.id.imageViewMessageStatus)
        private val menuIcon: ImageView = itemView.findViewById(R.id.imageViewMessageMenu)
        private val quotedContainer: View? = itemView.findViewById(R.id.quotedMessageContainer)
        private val quotedSenderName: TextView? = quotedContainer?.findViewById(R.id.textViewQuotedSenderName)
        private val quotedContent: TextView? = quotedContainer?.findViewById(R.id.textViewQuotedContent)

        fun bind(message: Message) {
            // ✅ Click sul menu icon per mostrare opzioni
            menuIcon.setOnClickListener { view ->
                showMessageOptionsMenu(view, message)
            }
            
            // ✅ Mostra messaggio quotato se presente
            if (message.replyToMessageId != null && message.replyToContent != null) {
                quotedContainer?.visibility = View.VISIBLE
                quotedSenderName?.text = if (message.replyToSenderId == currentUserId) "Tu" else "Altro"
                quotedContent?.text = message.replyToContent
            } else {
                quotedContainer?.visibility = View.GONE
            }
            
            // Formatta timestamp - Forza formato 24H italiano per consistenza
            val timeFormat = SimpleDateFormat("HH:mm", Locale.ITALIAN)
            messageTime.text = timeFormat.format(message.timestamp)

            // Aggiorna icona status
            updateStatusIcon(message.status)

            when (message.messageType) {
                MessageType.TEXT -> {
                    messageText.visibility = View.VISIBLE
                    messageText.text = message.content
                    imageAttachment?.visibility = View.GONE
                }
                MessageType.IMAGE -> {
                    // Per ora nascondi l'immagine, implementeremo dopo
                    imageAttachment?.visibility = View.GONE
                    messageText.visibility = View.VISIBLE
                    messageText.text = message.content.ifEmpty { message.filename ?: "Immagine" }
                    itemView.setOnClickListener { onMediaClick(message) }
                }
                MessageType.VIDEO, MessageType.DOCUMENT, MessageType.AUDIO, MessageType.MEDIA -> {
                    messageText.visibility = View.VISIBLE
                    messageText.text = message.filename ?: "Media file"
                    imageAttachment?.visibility = View.GONE
                    itemView.setOnClickListener { onMediaClick(message) }
                }
                MessageType.EMOJI -> {
                    messageText.visibility = View.VISIBLE
                    messageText.text = message.content
                    imageAttachment?.visibility = View.GONE
                }
            }
        }

        private fun updateStatusIcon(status: MessageStatus) {
            val iconRes = when (status) {
                MessageStatus.PENDING -> R.drawable.ic_message_pending
                MessageStatus.SENT -> R.drawable.ic_message_sent
                MessageStatus.DELIVERED -> R.drawable.ic_message_delivered
                MessageStatus.READ -> R.drawable.ic_message_read
                MessageStatus.FAILED -> R.drawable.ic_message_failed
            }
            statusIcon.setImageResource(iconRes)
            
            // Colore specifico per READ (blu) e FAILED (rosso)
            when (status) {
                MessageStatus.READ -> statusIcon.setColorFilter(0xFF4CAF50.toInt())
                MessageStatus.FAILED -> statusIcon.setColorFilter(0xFFF44336.toInt())
                else -> statusIcon.clearColorFilter()
            }
        }
    }

    // ViewHolder per messaggi RICEVUTI (a sinistra)
    inner class ReceivedMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.textViewMessageContentReceived)
        private val messageTime: TextView = itemView.findViewById(R.id.textViewMessageTimestampReceived)
        private val senderName: TextView? = itemView.findViewById(R.id.textViewSenderName)
        private val senderAvatar: ImageView? = itemView.findViewById(R.id.imageViewSenderAvatar)
        private val imageAttachment: ImageView? = itemView.findViewById(R.id.imageViewAttachmentReceived)
        private val menuIcon: ImageView = itemView.findViewById(R.id.imageViewMessageMenu)
        private val quotedContainer: View? = itemView.findViewById(R.id.quotedMessageContainer)
        private val quotedSenderName: TextView? = quotedContainer?.findViewById(R.id.textViewQuotedSenderName)
        private val quotedContent: TextView? = quotedContainer?.findViewById(R.id.textViewQuotedContent)

        fun bind(message: Message) {
            // ✅ Click sul menu icon per mostrare opzioni
            menuIcon.setOnClickListener { view ->
                showMessageOptionsMenu(view, message)
            }
            
            // ✅ Mostra messaggio quotato se presente
            if (message.replyToMessageId != null && message.replyToContent != null) {
                quotedContainer?.visibility = View.VISIBLE
                quotedSenderName?.text = if (message.replyToSenderId == currentUserId) "Tu" else "Altro"
                quotedContent?.text = message.replyToContent
            } else {
                quotedContainer?.visibility = View.GONE
            }
            
            // Formatta timestamp - Forza formato 24H italiano per consistenza
            val timeFormat = SimpleDateFormat("HH:mm", Locale.ITALIAN)
            messageTime.text = timeFormat.format(message.timestamp)

            // ✅ Mostra nome mittente nei gruppi
            if (isGroupChat) {
                senderName?.visibility = View.VISIBLE
                // Carica il nome del mittente dal database
                lifecycleScope.launch {
                    val senderUsername = database?.userDao()?.getUserById(message.senderId.toString())?.username ?: "Utente"
                    senderName?.text = senderUsername
                }
            } else {
                senderName?.visibility = View.GONE
            }
            
            // Carica avatar mittente
            senderAvatar?.setImageResource(R.drawable.ic_person)
            
            when (message.messageType) {
                MessageType.TEXT -> {
                    messageText.visibility = View.VISIBLE
                    messageText.text = message.content
                    imageAttachment?.visibility = View.GONE
                }
                MessageType.IMAGE -> {
                    // Per ora nascondi l'immagine, implementeremo dopo
                    imageAttachment?.visibility = View.GONE
                    messageText.visibility = View.VISIBLE
                    messageText.text = message.content.ifEmpty { message.filename ?: "Immagine" }
                    itemView.setOnClickListener { onMediaClick(message) }
                }
                MessageType.VIDEO, MessageType.DOCUMENT, MessageType.AUDIO, MessageType.MEDIA -> {
                    messageText.visibility = View.VISIBLE
                    messageText.text = message.filename ?: "Media file"
                    imageAttachment?.visibility = View.GONE
                    itemView.setOnClickListener { onMediaClick(message) }
                }
                MessageType.EMOJI -> {
                    messageText.visibility = View.VISIBLE
                    messageText.text = message.content
                    imageAttachment?.visibility = View.GONE
                }
            }
        }
    }

    // ViewHolder per IMMAGINI INVIATE
    inner class ImageSentMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.imageViewMessageSent)
        private val messageTime: TextView = itemView.findViewById(R.id.textViewMessageTimestampSent)
        private val captionTextView: TextView = itemView.findViewById(R.id.textViewCaptionSent)
        private val statusIcon: ImageView = itemView.findViewById(R.id.imageViewMessageStatus)
        private val menuIcon: ImageView = itemView.findViewById(R.id.imageViewMessageMenu)
        private val chatMediaService = ChatMediaService(context)

        fun bind(message: Message) {
            // ✅ Click sul menu icon per mostrare opzioni
            menuIcon.setOnClickListener { view ->
                showMessageOptionsMenu(view, message)
            }
            
            // Formatta timestamp - Forza formato 24H italiano per consistenza
            val timeFormat = SimpleDateFormat("HH:mm", Locale.ITALIAN)
            messageTime.text = timeFormat.format(message.timestamp)

            // Aggiorna icona status
            updateStatusIcon(message.status)

            // Mostra caption se presente
            if (message.caption.isNullOrEmpty()) {
                captionTextView.visibility = View.GONE
            } else {
                captionTextView.text = message.caption
                captionTextView.visibility = View.VISIBLE
            }

            // Download e mostra immagine
            if (message.mediaId != null && message.mediaKey != null && message.mediaIv != null) {
                loadImage(message)
            } else {
                imageView.setImageResource(R.drawable.ic_image)
            }

            // Click per fullscreen
            itemView.setOnClickListener { onMediaClick(message) }
        }

        private fun loadImage(message: Message) {
            lifecycleScope.launch {
                try {
                    // Set auth token for download
                    if (!authToken.isNullOrEmpty()) {
                        RetrofitClient.setAuthToken(authToken)
                    }
                    
                    val imageFile = withContext(Dispatchers.IO) {
                        chatMediaService.downloadChatMedia(
                            mediaId = message.mediaId!!,
                            encryptedKey = message.mediaKey!!,
                            iv = message.mediaIv!!
                        )
                    }

                    if (imageFile != null && imageFile.exists()) {
                        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                        imageView.setImageBitmap(bitmap)
                    } else {
                        imageView.setImageResource(R.drawable.ic_image)
                    }
                } catch (e: Exception) {
                    Log.e("MessageAdapter", "Error loading image", e)
                    imageView.setImageResource(R.drawable.ic_image)
                }
            }
        }

        private fun updateStatusIcon(status: MessageStatus) {
            val iconRes = when (status) {
                MessageStatus.PENDING -> R.drawable.ic_message_pending
                MessageStatus.SENT -> R.drawable.ic_message_sent
                MessageStatus.DELIVERED -> R.drawable.ic_message_delivered
                MessageStatus.READ -> R.drawable.ic_message_read
                MessageStatus.FAILED -> R.drawable.ic_message_failed
            }
            statusIcon.setImageResource(iconRes)
            
            // Colore specifico per READ (verde) e FAILED (rosso)
            when (status) {
                MessageStatus.READ -> statusIcon.setColorFilter(0xFF4CAF50.toInt())
                MessageStatus.FAILED -> statusIcon.setColorFilter(0xFFF44336.toInt())
                else -> statusIcon.clearColorFilter()
            }
        }
    }

    // ViewHolder per IMMAGINI RICEVUTE
    inner class ImageReceivedMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.imageViewMessageReceived)
        private val messageTime: TextView = itemView.findViewById(R.id.textViewMessageTimestampReceived)
        private val captionTextView: TextView = itemView.findViewById(R.id.textViewCaptionReceived)
        private val menuIcon: ImageView = itemView.findViewById(R.id.imageViewMessageMenu)
        private val chatMediaService = ChatMediaService(context)

        fun bind(message: Message) {
            // ✅ Click sul menu icon per mostrare opzioni
            menuIcon.setOnClickListener { view ->
                showMessageOptionsMenu(view, message)
            }
            
            // Formatta timestamp - Forza formato 24H italiano per consistenza
            val timeFormat = SimpleDateFormat("HH:mm", Locale.ITALIAN)
            messageTime.text = timeFormat.format(message.timestamp)

            // Mostra caption se presente
            if (message.caption.isNullOrEmpty()) {
                captionTextView.visibility = View.GONE
            } else {
                captionTextView.text = message.caption
                captionTextView.visibility = View.VISIBLE
            }

            // Download e mostra immagine
            if (message.mediaId != null && message.mediaKey != null && message.mediaIv != null) {
                loadImage(message)
            } else {
                imageView.setImageResource(R.drawable.ic_image)
            }

            // Click per fullscreen
            itemView.setOnClickListener { onMediaClick(message) }
        }

        private fun loadImage(message: Message) {
            lifecycleScope.launch {
                try {
                    // Set auth token for download
                    if (!authToken.isNullOrEmpty()) {
                        RetrofitClient.setAuthToken(authToken)
                    }
                    
                    val imageFile = withContext(Dispatchers.IO) {
                        chatMediaService.downloadChatMedia(
                            mediaId = message.mediaId!!,
                            encryptedKey = message.mediaKey!!,
                            iv = message.mediaIv!!
                        )
                    }

                    if (imageFile != null && imageFile.exists()) {
                        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                        imageView.setImageBitmap(bitmap)
                    } else {
                        imageView.setImageResource(R.drawable.ic_image)
                    }
                } catch (e: Exception) {
                    Log.e("MessageAdapter", "Error loading image", e)
                    imageView.setImageResource(R.drawable.ic_image)
                }
            }
        }
    }
    
    // ViewHolder per VIDEO INVIATI
    inner class VideoSentMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.imageViewMessageSent)
        private val playIcon: ImageView? = itemView.findViewById(R.id.playIconOverlay)
        private val messageTime: TextView = itemView.findViewById(R.id.textViewMessageTimestampSent)
        private val captionTextView: TextView = itemView.findViewById(R.id.textViewCaptionSent)
        private val statusIcon: ImageView = itemView.findViewById(R.id.imageViewMessageStatus)
        private val progressContainer: View? = itemView.findViewById(R.id.downloadProgressContainer)
        private val progressBar: ProgressBar? = itemView.findViewById(R.id.progressBarDownload)
        private val progressText: TextView? = itemView.findViewById(R.id.textViewDownloadPercentage)
        private val menuIcon: ImageView = itemView.findViewById(R.id.imageViewMessageMenu)
        private val chatMediaService = ChatMediaService(context)
        
        // ✅ Job per cancellare download se la cella viene riciclata
        private var downloadJob: kotlinx.coroutines.Job? = null

        fun bind(message: Message) {
            // ✅ Click sul menu icon per mostrare opzioni
            menuIcon.setOnClickListener { view ->
                showMessageOptionsMenu(view, message)
            }
            
            // ✅ CANCELLA download precedente se questa cella viene riutilizzata
            downloadJob?.cancel()
            downloadJob = null
            
            // Formatta timestamp - Forza formato 24H italiano per consistenza
            val timeFormat = SimpleDateFormat("HH:mm", Locale.ITALIAN)
            messageTime.text = timeFormat.format(message.timestamp)

            // Aggiorna icona status
            updateStatusIcon(message.status)

            // Mostra caption se presente
            if (message.caption.isNullOrEmpty()) {
                captionTextView.visibility = View.GONE
            } else {
                captionTextView.text = message.caption
                captionTextView.visibility = View.VISIBLE
            }

            // Genera e mostra thumbnail video con play icon
            if (message.mediaId != null && message.mediaKey != null && message.mediaIv != null) {
                loadVideoThumbnail(message)
            } else {
                imageView.setImageResource(R.drawable.ic_play_circle)
                playIcon?.visibility = View.VISIBLE
            }

            // Click intelligente: download se manca thumbnail, altrimenti apri player
            itemView.setOnClickListener {
                if (message.thumbnail.isNullOrEmpty() && downloadJob == null) {
                    // Prima volta: scarica e genera thumbnail
                    startManualDownload(message)
                } else {
                    // Thumbnail già presente o download in corso: apri player
                    onMediaClick(message)
                }
            }
        }

        private fun loadVideoThumbnail(message: Message) {
            // PRIORITÀ 1: Usa thumbnail cached da DB (base64)
            if (!message.thumbnail.isNullOrEmpty()) {
                try {
                    val thumbnailBytes = android.util.Base64.decode(message.thumbnail, android.util.Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(thumbnailBytes, 0, thumbnailBytes.size)
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                        playIcon?.visibility = View.VISIBLE  // Mostra play icon
                        progressContainer?.visibility = View.GONE  // Nascondi loader
                        Log.d("MessageAdapter", "✅ SENT: Loaded cached thumbnail (${thumbnailBytes.size} bytes)")
                        return
                    }
                } catch (e: Exception) {
                    Log.w("MessageAdapter", "SENT: Failed to decode base64 thumbnail", e)
                }
            }
            
            // PRIORITÀ 2: Nessun thumbnail - Controlla se auto-download abilitato
            val autoDownload = securityManager.isAutoDownloadVideosEnabled()
            
            if (autoDownload) {
                // Auto-download abilitato: scarica automaticamente (in coda)
                imageView.setImageResource(android.R.color.transparent)  // ✅ NASCONDI placeholder
                playIcon?.visibility = View.GONE  // Nascondi play
                startAutoDownload(message)
            } else {
                // Auto-download disabilitato: mostra placeholder
                imageView.setImageResource(R.drawable.ic_play_circle)
                playIcon?.visibility = View.VISIBLE
                progressContainer?.visibility = View.GONE
                Log.d("MessageAdapter", "⏳ SENT: No thumbnail, auto-download disabled (click to download)")
            }
        }

        private fun startAutoDownload(message: Message) {
            if (message.mediaId == null || message.mediaKey == null || message.mediaIv == null) {
                progressContainer?.visibility = View.GONE
                return
            }

            // ✅ Aggiungi alla coda (max 1 download alla volta)
            VideoDownloadQueue.enqueue(
                messageId = message.id,
                mediaId = message.mediaId!!,
                mediaKey = message.mediaKey!!,
                mediaIv = message.mediaIv!!,
                onProgress = { progress ->
                    // Aggiorna UI progress bar
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        progressContainer?.visibility = View.VISIBLE
                        progressBar?.progress = (progress * 100).toInt()
                        progressText?.text = "${(progress * 100).toInt()}%"
                    }
                },
                onComplete = { success ->
                    // Questo viene chiamato quando il download finisce
                    if (!success) {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            progressContainer?.visibility = View.GONE
                        }
                    }
                }
            )
            
            // Avvia il download vero
            downloadJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                try {
                    val videoFile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        chatMediaService.downloadChatMedia(
                            message.mediaId!!,
                            message.mediaKey!!,
                            message.mediaIv!!,
                            onProgress = { progress ->
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                    val percentage = (progress * 100).toInt()
                                    progressBar?.progress = percentage
                                    progressText?.text = "$percentage%"
                                }
                            }
                        )
                    }

                    if (videoFile != null) {
                        // Download completato - genera thumbnail
                        Log.d("MessageAdapter", "✅ SENT: Download complete, generating thumbnail...")
                        generateAndCacheThumbnail(videoFile, message)
                        VideoDownloadQueue.notifyComplete()  // ✅ Notifica coda per passare al prossimo
                    } else {
                        Log.e("MessageAdapter", "❌ SENT: Download failed")
                        progressContainer?.visibility = View.GONE
                        VideoDownloadQueue.notifyComplete()  // ✅ Passa al prossimo anche se fallito
                    }
                } catch (e: Exception) {
                    Log.e("MessageAdapter", "❌ SENT: Download error", e)
                    progressContainer?.visibility = View.GONE
                    VideoDownloadQueue.notifyComplete()  // ✅ Passa al prossimo
                }
            }
        }

        // Per il click manuale (quando auto-download disabilitato)
        private fun startManualDownload(message: Message) {
            if (message.mediaId == null || message.mediaKey == null || message.mediaIv == null) {
                progressContainer?.visibility = View.GONE
                return
            }

            // ✅ Nascondi placeholder, mostra progress
            imageView.setImageResource(android.R.color.transparent)
            playIcon?.visibility = View.GONE
            progressContainer?.visibility = View.VISIBLE
            progressBar?.progress = 0
            progressText?.text = "0%"

            // Usa la stessa logica di auto-download (passa in coda)
            startAutoDownload(message)
        }

        private suspend fun generateAndCacheThumbnail(videoFile: java.io.File, message: Message) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    // Genera thumbnail
                    val thumbnailFile = chatMediaService.generateVideoThumbnail(videoFile)
                    if (thumbnailFile == null) {
                        Log.e("MessageAdapter", "❌ SENT: Failed to generate thumbnail file")
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            progressContainer?.visibility = View.GONE
                        }
                        return@withContext
                    }
                    
                    val originalBitmap = BitmapFactory.decodeFile(thumbnailFile.absolutePath)
                    if (originalBitmap == null) {
                        Log.e("MessageAdapter", "❌ SENT: Failed to decode thumbnail bitmap")
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            progressContainer?.visibility = View.GONE
                        }
                        return@withContext
                    }

                    // Comprimi (320x240 @ 30% quality)
                    val aspectRatio = originalBitmap.width.toFloat() / originalBitmap.height.toFloat()
                    val newWidth = 320
                    val newHeight = (newWidth / aspectRatio).toInt()
                    val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)

                    val outputStream = java.io.ByteArrayOutputStream()
                    scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 30, outputStream)
                    val compressedBytes = outputStream.toByteArray()
                    val thumbnailBase64 = android.util.Base64.encodeToString(compressedBytes, android.util.Base64.DEFAULT)

                    // Salva nel DB
                    val database = it.fabiodirauso.shutappchat.database.AppDatabase.getDatabase(context)
                    database.messageDao().updateMessageThumbnail(message.id, thumbnailBase64)

                    // Aggiorna UI
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        imageView.setImageBitmap(scaledBitmap)
                        playIcon?.visibility = View.VISIBLE
                        progressContainer?.visibility = View.GONE
                        Log.d("MessageAdapter", "✅ SENT: Thumbnail cached (${compressedBytes.size} bytes)")
                    }

                    // ❌ NON riciclare scaledBitmap - è ancora in uso dall'ImageView!
                    // scaledBitmap.recycle()
                    originalBitmap.recycle()  // Solo originalBitmap può essere riciclato
                } catch (e: Exception) {
                    Log.e("MessageAdapter", "❌ SENT: Thumbnail generation failed", e)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        progressContainer?.visibility = View.GONE
                    }
                }
            }
        }

        private fun updateStatusIcon(status: MessageStatus) {
            val iconRes = when (status) {
                MessageStatus.PENDING -> R.drawable.ic_message_pending
                MessageStatus.SENT -> R.drawable.ic_message_sent
                MessageStatus.DELIVERED -> R.drawable.ic_message_delivered
                MessageStatus.READ -> R.drawable.ic_message_read
                MessageStatus.FAILED -> R.drawable.ic_message_failed
            }
            statusIcon.setImageResource(iconRes)
            
            when (status) {
                MessageStatus.READ -> statusIcon.setColorFilter(0xFF4CAF50.toInt())
                MessageStatus.FAILED -> statusIcon.setColorFilter(0xFFF44336.toInt())
                else -> statusIcon.clearColorFilter()
            }
        }
    }
    
    // ViewHolder per VIDEO RICEVUTI
    inner class VideoReceivedMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.imageViewMessageReceived)
        private val playIcon: ImageView? = itemView.findViewById(R.id.playIconOverlay)
        private val messageTime: TextView = itemView.findViewById(R.id.textViewMessageTimestampReceived)
        private val captionTextView: TextView = itemView.findViewById(R.id.textViewCaptionReceived)
        private val progressContainer: View? = itemView.findViewById(R.id.downloadProgressContainer)
        private val progressBar: ProgressBar? = itemView.findViewById(R.id.progressBarDownload)
        private val progressText: TextView? = itemView.findViewById(R.id.textViewDownloadPercentage)
        private val menuIcon: ImageView = itemView.findViewById(R.id.imageViewMessageMenu)
        private val chatMediaService = ChatMediaService(context)
        
        // ✅ Job per cancellare download se la cella viene riciclata
        private var downloadJob: kotlinx.coroutines.Job? = null

        fun bind(message: Message) {
            // ✅ Click sul menu icon per mostrare opzioni
            menuIcon.setOnClickListener { view ->
                showMessageOptionsMenu(view, message)
            }
            
            // ✅ CANCELLA download precedente se questa cella viene riutilizzata
            downloadJob?.cancel()
            downloadJob = null
            
            // Formatta timestamp - Forza formato 24H italiano per consistenza
            val timeFormat = SimpleDateFormat("HH:mm", Locale.ITALIAN)
            messageTime.text = timeFormat.format(message.timestamp)

            // Mostra caption se presente
            if (message.caption.isNullOrEmpty()) {
                captionTextView.visibility = View.GONE
            } else {
                captionTextView.text = message.caption
                captionTextView.visibility = View.VISIBLE
            }

            // Genera e mostra thumbnail video con play icon
            if (message.mediaId != null && message.mediaKey != null && message.mediaIv != null) {
                loadVideoThumbnail(message)
                playIcon?.visibility = View.VISIBLE
            } else {
                imageView.setImageResource(R.drawable.ic_play_circle)
                playIcon?.visibility = View.VISIBLE
            }

            // Click intelligente: download se manca thumbnail, altrimenti apri player
            itemView.setOnClickListener {
                if (message.thumbnail.isNullOrEmpty() && downloadJob == null) {
                    // Prima volta: scarica e genera thumbnail
                    startManualDownload(message)
                } else {
                    // Thumbnail già presente o download in corso: apri player
                    onMediaClick(message)
                }
            }
        }

        private fun loadVideoThumbnail(message: Message) {
            // PRIORITÀ 1: Usa thumbnail cached da DB (base64)
            if (!message.thumbnail.isNullOrEmpty()) {
                try {
                    val thumbnailBytes = android.util.Base64.decode(message.thumbnail, android.util.Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(thumbnailBytes, 0, thumbnailBytes.size)
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                        playIcon?.visibility = View.VISIBLE  // Mostra play icon
                        progressContainer?.visibility = View.GONE  // Nascondi loader
                        Log.d("MessageAdapter", "✅ RECEIVED: Loaded cached thumbnail (${thumbnailBytes.size} bytes)")
                        return
                    }
                } catch (e: Exception) {
                    Log.w("MessageAdapter", "RECEIVED: Failed to decode base64 thumbnail", e)
                }
            }
            
            // PRIORITÀ 2: Nessun thumbnail - Controlla se auto-download abilitato
            val autoDownload = securityManager.isAutoDownloadVideosEnabled()
            
            if (autoDownload) {
                // Auto-download abilitato: scarica automaticamente (in coda)
                imageView.setImageResource(android.R.color.transparent)  // ✅ NASCONDI placeholder
                playIcon?.visibility = View.GONE  // Nascondi play
                startAutoDownload(message)
            } else {
                // Auto-download disabilitato: mostra placeholder
                imageView.setImageResource(R.drawable.ic_play_circle)
                playIcon?.visibility = View.VISIBLE
                progressContainer?.visibility = View.GONE
                Log.d("MessageAdapter", "⏳ RECEIVED: No thumbnail, auto-download disabled (click to download)")
            }
        }

        private fun startAutoDownload(message: Message) {
            if (message.mediaId == null || message.mediaKey == null || message.mediaIv == null) {
                progressContainer?.visibility = View.GONE
                return
            }

            // ✅ Aggiungi alla coda (max 1 download alla volta)
            VideoDownloadQueue.enqueue(
                messageId = message.id,
                mediaId = message.mediaId!!,
                mediaKey = message.mediaKey!!,
                mediaIv = message.mediaIv!!,
                onProgress = { progress ->
                    // Aggiorna UI progress bar
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        progressContainer?.visibility = View.VISIBLE
                        progressBar?.progress = (progress * 100).toInt()
                        progressText?.text = "${(progress * 100).toInt()}%"
                    }
                },
                onComplete = { success ->
                    // Questo viene chiamato quando il download finisce
                    if (!success) {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            progressContainer?.visibility = View.GONE
                        }
                    }
                }
            )
            
            // Avvia il download vero
            downloadJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                try {
                    val videoFile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        chatMediaService.downloadChatMedia(
                            message.mediaId!!,
                            message.mediaKey!!,
                            message.mediaIv!!,
                            onProgress = { progress ->
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                    val percentage = (progress * 100).toInt()
                                    progressBar?.progress = percentage
                                    progressText?.text = "$percentage%"
                                }
                            }
                        )
                    }

                    if (videoFile != null) {
                        // Download completato - genera thumbnail
                        Log.d("MessageAdapter", "✅ RECEIVED: Download complete, generating thumbnail...")
                        generateAndCacheThumbnail(videoFile, message)
                        VideoDownloadQueue.notifyComplete()  // ✅ Notifica coda per passare al prossimo
                    } else {
                        Log.e("MessageAdapter", "❌ RECEIVED: Download failed")
                        progressContainer?.visibility = View.GONE
                        VideoDownloadQueue.notifyComplete()  // ✅ Passa al prossimo anche se fallito
                    }
                } catch (e: Exception) {
                    Log.e("MessageAdapter", "❌ RECEIVED: Download error", e)
                    progressContainer?.visibility = View.GONE
                    VideoDownloadQueue.notifyComplete()  // ✅ Passa al prossimo
                }
            }
        }

        // Per il click manuale (quando auto-download disabilitato)
        private fun startManualDownload(message: Message) {
            if (message.mediaId == null || message.mediaKey == null || message.mediaIv == null) {
                progressContainer?.visibility = View.GONE
                return
            }

            // ✅ Nascondi placeholder, mostra progress
            imageView.setImageResource(android.R.color.transparent)
            playIcon?.visibility = View.GONE
            progressContainer?.visibility = View.VISIBLE
            progressBar?.progress = 0
            progressText?.text = "0%"

            // Usa la stessa logica di auto-download (passa in coda)
            startAutoDownload(message)
        }

        private suspend fun generateAndCacheThumbnail(videoFile: java.io.File, message: Message) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    // Genera thumbnail
                    val thumbnailFile = chatMediaService.generateVideoThumbnail(videoFile)
                    if (thumbnailFile == null) {
                        Log.e("MessageAdapter", "❌ RECEIVED: Failed to generate thumbnail file")
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            progressContainer?.visibility = View.GONE
                        }
                        return@withContext
                    }
                    
                    val originalBitmap = BitmapFactory.decodeFile(thumbnailFile.absolutePath)
                    if (originalBitmap == null) {
                        Log.e("MessageAdapter", "❌ RECEIVED: Failed to decode thumbnail bitmap")
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            progressContainer?.visibility = View.GONE
                        }
                        return@withContext
                    }

                    // Comprimi (320x240 @ 30% quality)
                    val aspectRatio = originalBitmap.width.toFloat() / originalBitmap.height.toFloat()
                    val newWidth = 320
                    val newHeight = (newWidth / aspectRatio).toInt()
                    val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)

                    val outputStream = java.io.ByteArrayOutputStream()
                    scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 30, outputStream)
                    val compressedBytes = outputStream.toByteArray()
                    val thumbnailBase64 = android.util.Base64.encodeToString(compressedBytes, android.util.Base64.DEFAULT)

                    // Salva nel DB
                    val database = it.fabiodirauso.shutappchat.database.AppDatabase.getDatabase(context)
                    database.messageDao().updateMessageThumbnail(message.id, thumbnailBase64)

                    // Aggiorna UI
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        imageView.setImageBitmap(scaledBitmap)
                        playIcon?.visibility = View.VISIBLE
                        progressContainer?.visibility = View.GONE
                        Log.d("MessageAdapter", "✅ RECEIVED: Thumbnail cached (${compressedBytes.size} bytes)")
                    }

                    // ❌ NON riciclare scaledBitmap - è ancora in uso dall'ImageView!
                    // scaledBitmap.recycle()
                    originalBitmap.recycle()  // Solo originalBitmap può essere riciclato
                } catch (e: Exception) {
                    Log.e("MessageAdapter", "❌ RECEIVED: Thumbnail generation failed", e)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        progressContainer?.visibility = View.GONE
                    }
                }
            }
        }
    }
}

