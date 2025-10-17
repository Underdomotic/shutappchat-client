package it.fabiodirauso.shutappchat.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import it.fabiodirauso.shutappchat.model.Message
import it.fabiodirauso.shutappchat.model.MessageStatus

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<Message>>
    
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesListForConversation(conversationId: String): List<Message>
    
    // Group messages queries
    @Query("SELECT * FROM messages WHERE isGroup = 1 AND groupId = :groupId ORDER BY timestamp ASC")
    fun getGroupMessages(groupId: String): Flow<List<Message>>
    
    @Query("SELECT * FROM messages WHERE isGroup = 1 AND groupId = :groupId ORDER BY timestamp ASC")
    suspend fun getGroupMessagesList(groupId: String): List<Message>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)

    @Update
    suspend fun updateMessage(message: Message)

    @Delete
    suspend fun deleteMessage(message: Message)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: String)
    
    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteAllMessagesInConversation(conversationId: String)

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): Message?
    
    // Unread messages queries
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND recipientId = :currentUserId AND isRead = 0")
    fun getUnreadCountForConversation(conversationId: String, currentUserId: Long): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM messages WHERE recipientId = :currentUserId AND isRead = 0")
    fun getTotalUnreadCount(currentUserId: Long): Flow<Int>
    
    @Query("UPDATE messages SET isRead = 1 WHERE conversationId = :conversationId AND recipientId = :currentUserId AND isRead = 0")
    suspend fun markConversationAsRead(conversationId: String, currentUserId: Long)
    
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND isRead = 0 AND recipientId = :currentUserId")
    suspend fun getUnreadMessagesForConversation(conversationId: String, currentUserId: Long): List<Message>
    
    // Message status updates
    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus)
    
    @Query("UPDATE messages SET status = :status WHERE id IN (:messageIds)")
    suspend fun updateMultipleMessageStatus(messageIds: List<String>, status: MessageStatus)
    
    // Thumbnail update for video messages
    @Query("UPDATE messages SET thumbnail = :thumbnailBase64 WHERE id = :messageId")
    suspend fun updateMessageThumbnail(messageId: String, thumbnailBase64: String)
    
    // Get all active media IDs for cache cleanup
    @Query("SELECT DISTINCT mediaId FROM messages WHERE mediaId IS NOT NULL")
    suspend fun getAllMediaIds(): List<String>
}
