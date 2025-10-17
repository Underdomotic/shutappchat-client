package it.fabiodirauso.shutappchat.database

import androidx.room.*
import it.fabiodirauso.shutappchat.model.GroupEntity
import it.fabiodirauso.shutappchat.model.GroupMode
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    
    // ========== INSERT / UPDATE ==========
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: GroupEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroups(groups: List<GroupEntity>)
    
    @Update
    suspend fun updateGroup(group: GroupEntity)
    
    @Delete
    suspend fun deleteGroup(group: GroupEntity)
    
    // ========== QUERY ==========
    
    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    suspend fun getGroupById(groupId: String): GroupEntity?
    
    @Query("SELECT * FROM groups WHERE groupId = :groupId")
    fun getGroupByIdFlow(groupId: String): Flow<GroupEntity?>
    
    @Query("SELECT * FROM groups WHERE isActive = 1 ORDER BY lastMessageTime DESC")
    fun getAllActiveGroups(): Flow<List<GroupEntity>>
    
    @Query("SELECT * FROM groups ORDER BY lastMessageTime DESC")
    fun getAllGroups(): Flow<List<GroupEntity>>
    
    @Query("SELECT COUNT(*) FROM groups WHERE isActive = 1")
    suspend fun getActiveGroupCount(): Int
    
    @Query("SELECT SUM(unreadCount) FROM groups WHERE isActive = 1")
    fun getTotalUnreadCount(): Flow<Int?>
    
    // ========== UPDATE SPECIFIC FIELDS ==========
    
    @Query("""
        UPDATE groups 
        SET lastMessageContent = :content, 
            lastMessageTime = :timestamp,
            updatedAt = :timestamp
        WHERE groupId = :groupId
    """)
    suspend fun updateLastMessage(groupId: String, content: String, timestamp: Long)
    
    @Query("""
        UPDATE groups 
        SET unreadCount = unreadCount + :increment
        WHERE groupId = :groupId
    """)
    suspend fun incrementUnreadCount(groupId: String, increment: Int = 1)
    
    @Query("""
        UPDATE groups 
        SET unreadCount = 0
        WHERE groupId = :groupId
    """)
    suspend fun resetUnreadCount(groupId: String)
    
    @Query("""
        UPDATE groups 
        SET groupMode = :mode,
            updatedAt = :timestamp
        WHERE groupId = :groupId
    """)
    suspend fun updateGroupMode(groupId: String, mode: GroupMode, timestamp: Long)
    
    @Query("""
        UPDATE groups 
        SET groupName = :name,
            updatedAt = :timestamp
        WHERE groupId = :groupId
    """)
    suspend fun updateGroupName(groupId: String, name: String, timestamp: Long)
    
    @Query("""
        UPDATE groups 
        SET groupDescription = :description,
            updatedAt = :timestamp
        WHERE groupId = :groupId
    """)
    suspend fun updateGroupDescription(groupId: String, description: String?, timestamp: Long)
    
    @Query("""
        UPDATE groups 
        SET groupPictureId = :pictureId,
            updatedAt = :timestamp
        WHERE groupId = :groupId
    """)
    suspend fun updateGroupPicture(groupId: String, pictureId: String?, timestamp: Long)
    
    @Query("""
        UPDATE groups 
        SET totalMembers = :count
        WHERE groupId = :groupId
    """)
    suspend fun updateMemberCount(groupId: String, count: Int)
    
    @Query("""
        UPDATE groups 
        SET isActive = :active
        WHERE groupId = :groupId
    """)
    suspend fun updateGroupActiveStatus(groupId: String, active: Boolean)
    
    // ========== SEARCH ==========
    
    @Query("""
        SELECT * FROM groups 
        WHERE isActive = 1 
        AND (groupName LIKE '%' || :query || '%' 
             OR groupDescription LIKE '%' || :query || '%')
        ORDER BY lastMessageTime DESC
    """)
    fun searchGroups(query: String): Flow<List<GroupEntity>>
    
    // ========== DELETE ==========
    
    @Query("DELETE FROM groups WHERE groupId = :groupId")
    suspend fun deleteGroupById(groupId: String)
    
    @Query("DELETE FROM groups")
    suspend fun deleteAllGroups()
}
