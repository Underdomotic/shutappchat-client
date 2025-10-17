package it.fabiodirauso.shutappchat.database

import androidx.room.*
import it.fabiodirauso.shutappchat.model.SystemNotification
import kotlinx.coroutines.flow.Flow

@Dao
interface SystemNotificationDao {
    
    @Query("SELECT * FROM system_notifications ORDER BY read ASC, timestamp DESC")
    fun getAllNotifications(): Flow<List<SystemNotification>>
    
    @Query("SELECT * FROM system_notifications WHERE id = :id")
    suspend fun getNotificationById(id: Long): SystemNotification?
    
    @Query("SELECT COUNT(*) FROM system_notifications WHERE read = 0")
    fun getUnreadCount(): Flow<Int>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: SystemNotification)
    
    @Query("UPDATE system_notifications SET read = 1 WHERE id = :id")
    suspend fun markAsRead(id: Long)
    
    @Query("UPDATE system_notifications SET read = NOT read WHERE id = :id")
    suspend fun toggleRead(id: Long)
    
    @Query("UPDATE system_notifications SET read = 1")
    suspend fun markAllAsRead()
    
    @Query("DELETE FROM system_notifications WHERE id = :id")
    suspend fun deleteNotification(id: Long)
    
    @Query("DELETE FROM system_notifications")
    suspend fun deleteAllNotifications()
}