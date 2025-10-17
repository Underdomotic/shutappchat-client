package it.fabiodirauso.shutappchat.database

import androidx.room.*
import it.fabiodirauso.shutappchat.model.GroupMemberEntity
import it.fabiodirauso.shutappchat.model.GroupRole
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupMemberDao {
    
    // ========== INSERT / UPDATE ==========
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: GroupMemberEntity): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<GroupMemberEntity>)
    
    @Update
    suspend fun updateMember(member: GroupMemberEntity)
    
    @Delete
    suspend fun deleteMember(member: GroupMemberEntity)
    
    // ========== QUERY ==========
    
    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND isActive = 1")
    fun getGroupMembers(groupId: String): Flow<List<GroupMemberEntity>>
    
    @Query("SELECT * FROM group_members WHERE groupId = :groupId AND isActive = 1")
    suspend fun getGroupMembersList(groupId: String): List<GroupMemberEntity>
    
    @Query("""
        SELECT * FROM group_members 
        WHERE groupId = :groupId 
        AND userId = :userId 
        AND isActive = 1
    """)
    suspend fun getMember(groupId: String, userId: Long): GroupMemberEntity?
    
    @Query("""
        SELECT * FROM group_members 
        WHERE groupId = :groupId 
        AND role = :role 
        AND isActive = 1
    """)
    fun getMembersByRole(groupId: String, role: GroupRole): Flow<List<GroupMemberEntity>>
    
    @Query("""
        SELECT * FROM group_members 
        WHERE groupId = :groupId 
        AND role = 'ADMIN' 
        AND isActive = 1
    """)
    suspend fun getGroupAdmins(groupId: String): List<GroupMemberEntity>
    
    @Query("""
        SELECT COUNT(*) FROM group_members 
        WHERE groupId = :groupId 
        AND isActive = 1
    """)
    suspend fun getMemberCount(groupId: String): Int
    
    @Query("""
        SELECT COUNT(*) FROM group_members 
        WHERE groupId = :groupId 
        AND role = 'ADMIN' 
        AND isActive = 1
    """)
    suspend fun getAdminCount(groupId: String): Int
    
    // ========== CHECK PERMISSIONS ==========
    
    @Query("""
        SELECT role FROM group_members 
        WHERE groupId = :groupId 
        AND userId = :userId 
        AND isActive = 1
    """)
    suspend fun getMemberRole(groupId: String, userId: Long): GroupRole?
    
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM group_members 
            WHERE groupId = :groupId 
            AND userId = :userId 
            AND role = 'ADMIN' 
            AND isActive = 1
        )
    """)
    suspend fun isAdmin(groupId: String, userId: Long): Boolean
    
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM group_members 
            WHERE groupId = :groupId 
            AND userId = :userId 
            AND isActive = 1
        )
    """)
    suspend fun isMember(groupId: String, userId: Long): Boolean
    
    // ========== UPDATE SPECIFIC FIELDS ==========
    
    @Query("""
        UPDATE group_members 
        SET role = :role
        WHERE groupId = :groupId 
        AND userId = :userId
    """)
    suspend fun updateMemberRole(groupId: String, userId: Long, role: GroupRole)
    
    @Query("""
        UPDATE group_members 
        SET isActive = :active
        WHERE groupId = :groupId 
        AND userId = :userId
    """)
    suspend fun updateMemberActiveStatus(groupId: String, userId: Long, active: Boolean)
    
    // ========== DELETE ==========
    
    @Query("""
        DELETE FROM group_members 
        WHERE groupId = :groupId 
        AND userId = :userId
    """)
    suspend fun removeMember(groupId: String, userId: Long)
    
    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun deleteAllMembersInGroup(groupId: String)
    
    @Query("DELETE FROM group_members")
    suspend fun deleteAllMembers()
    
    // ========== SEARCH ==========
    
    @Query("""
        SELECT * FROM group_members 
        WHERE groupId = :groupId 
        AND isActive = 1
        AND (username LIKE '%' || :query || '%' 
             OR displayName LIKE '%' || :query || '%')
    """)
    fun searchMembers(groupId: String, query: String): Flow<List<GroupMemberEntity>>
}
