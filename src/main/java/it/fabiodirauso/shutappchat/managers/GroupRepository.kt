package it.fabiodirauso.shutappchat.managers

import android.content.Context
import android.util.Log
import it.fabiodirauso.shutappchat.api.*
import it.fabiodirauso.shutappchat.config.AppConfigManager
import it.fabiodirauso.shutappchat.database.AppDatabase
import it.fabiodirauso.shutappchat.model.GroupEntity
import it.fabiodirauso.shutappchat.model.GroupMemberEntity
import it.fabiodirauso.shutappchat.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Repository per la gestione dei gruppi
 * Gestisce sia le chiamate API che la cache locale (Room)
 */
class GroupRepository(private val context: Context) {
    
    private val configManager = AppConfigManager.getInstance(context)
    private val apiService by lazy { RetrofitClient.getApiService(configManager) }
    private val groupDao = AppDatabase.getDatabase(context).groupDao()
    private val groupMemberDao = AppDatabase.getDatabase(context).groupMemberDao()
    
    companion object {
        private const val TAG = "GroupRepository"
        
        @Volatile
        private var instance: GroupRepository? = null
        
        fun getInstance(context: Context): GroupRepository {
            return instance ?: synchronized(this) {
                instance ?: GroupRepository(context.applicationContext).also { instance = it }
            }
        }
    }
    
    // ========== GROUPS CRUD ==========
    
    /**
     * Fetches groups from server and updates local cache
     */
    suspend fun refreshGroups(): Result<List<GroupEntity>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching groups from server...")
            val response = apiService.getGroups()
            
            Log.d(TAG, "Response code: ${response.code()}, isSuccessful: ${response.isSuccessful}")
            
            if (response.isSuccessful && response.body()?.success == true) {
                val groups = response.body()!!.groups?.map { it.toEntity() } ?: emptyList()
                
                // Update local cache
                groupDao.insertGroups(groups)
                
                Log.d(TAG, "Refreshed ${groups.size} groups from server")
                Result.success(groups)
            } else {
                val error = response.body()?.message ?: response.errorBody()?.string() ?: "Failed to fetch groups"
                Log.e(TAG, "Error fetching groups: $error (code: ${response.code()})")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching groups", e)
            Result.failure(e)
        }
    }
    
    /**
     * Creates a new group
     */
    suspend fun createGroup(
        name: String,
        description: String? = null,
        mode: String = "OPEN",
        initialMembers: List<Long>? = null
    ): Result<CreateGroupResponse> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Creating group: name=$name, mode=$mode, members=${initialMembers?.size ?: 0}")
            
            val request = CreateGroupRequest(
                name = name,
                description = description,
                mode = mode,
                initial_members = initialMembers
            )
            
            val response = apiService.createGroup(request)
            
            Log.d(TAG, "Create group response - code: ${response.code()}, isSuccessful: ${response.isSuccessful}")
            
            if (response.isSuccessful && response.body()?.success == true) {
                Log.d(TAG, "Group created successfully: ${response.body()?.group_name}")
                // Refresh groups after creation
                refreshGroups()
                Result.success(response.body()!!)
            } else {
                val errorBody = response.errorBody()?.string()
                val error = response.body()?.message ?: errorBody ?: "Failed to create group"
                Log.e(TAG, "Failed to create group - code: ${response.code()}, error: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating group", e)
            Result.failure(e)
        }
    }
    
    /**
     * Deletes/archives a group (admin only - removes for all members)
     */
    suspend fun deleteGroup(groupId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.deleteGroup(groupId)
            
            if (response.isSuccessful && response.body()?.success == true) {
                // Remove from local cache
                val group = groupDao.getGroupById(groupId)
                if (group != null) {
                    groupDao.deleteGroup(group)
                }
                // Remove all members
                groupMemberDao.deleteAllMembersInGroup(groupId)
                Result.success(true)
            } else {
                val error = response.body()?.message ?: "Failed to delete group"
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception deleting group", e)
            Result.failure(e)
        }
    }
    
    /**
     * Leave a group (user only - removes only from this device)
     */
    suspend fun leaveGroup(groupId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // Call API to remove user from group
            val response = apiService.leaveGroup(groupId)
            
            if (response.isSuccessful && response.body()?.success == true) {
                // Remove from local cache
                val group = groupDao.getGroupById(groupId)
                if (group != null) {
                    groupDao.deleteGroup(group)
                }
                // Remove all members from local cache
                groupMemberDao.deleteAllMembersInGroup(groupId)
                Result.success(true)
            } else {
                val error = response.body()?.message ?: "Failed to leave group"
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception leaving group", e)
            Result.failure(e)
        }
    }
    
    /**
     * Updates group settings (name, description, mode, picture)
     */
    suspend fun updateGroupSettings(
        groupId: String,
        name: String? = null,
        description: String? = null,
        mode: String? = null,
        pictureId: String? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = UpdateGroupSettingsRequest(
                name = name,
                description = description,
                mode = mode,
                picture_id = pictureId
            )
            
            val response = apiService.updateGroupSettings(groupId, request)
            
            if (response.isSuccessful && response.body()?.success == true) {
                // Refresh group info
                refreshGroupInfo(groupId)
                Result.success(true)
            } else {
                val error = response.body()?.message ?: "Failed to update settings"
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception updating group settings", e)
            Result.failure(e)
        }
    }
    
    /**
     * Gets group info from server
     */
    suspend fun refreshGroupInfo(groupId: String): Result<GroupEntity> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching group info for: $groupId")
            val response = apiService.getGroupInfo(groupId)
            
            Log.d(TAG, "Group info response - code: ${response.code()}, isSuccessful: ${response.isSuccessful}")
            
            if (response.isSuccessful && response.body()?.success == true) {
                val group = response.body()!!.group?.toEntity()
                
                if (group != null) {
                    Log.d(TAG, "Group info loaded: ${group.groupName}")
                    groupDao.insertGroup(group)
                    Result.success(group)
                } else {
                    Log.e(TAG, "Group data is null in response")
                    Result.failure(Exception("Group data is null"))
                }
            } else {
                val errorBody = response.errorBody()?.string()
                val error = response.body()?.message ?: errorBody ?: "Failed to fetch group info"
                Log.e(TAG, "Failed to fetch group info - code: ${response.code()}, error: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching group info", e)
            Result.failure(e)
        }
    }
    
    // ========== MEMBERS MANAGEMENT ==========
    
    /**
     * Adds members to a group
     */
    suspend fun addMembers(groupId: String, userIds: List<Long>): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = AddMembersRequest(user_ids = userIds)
            val response = apiService.addGroupMembers(groupId, request)
            
            if (response.isSuccessful && response.body()?.success == true) {
                // Refresh members list
                refreshGroupMembers(groupId)
                Result.success(true)
            } else {
                val error = response.body()?.message ?: "Failed to add members"
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception adding members", e)
            Result.failure(e)
        }
    }
    
    /**
     * Removes a member from a group
     */
    suspend fun removeMember(groupId: String, userId: Long): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.removeGroupMember(groupId, userId)
            
            if (response.isSuccessful && response.body()?.success == true) {
                // Remove from local cache
                groupMemberDao.removeMember(groupId, userId)
                // Update member count
                updateMemberCount(groupId)
                Result.success(true)
            } else {
                val error = response.body()?.message ?: "Failed to remove member"
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception removing member", e)
            Result.failure(e)
        }
    }
    
    /**
     * Updates member role (ADMIN/MEMBER)
     */
    suspend fun updateMemberRole(groupId: String, userId: Long, role: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = UpdateRoleRequest(role = role)
            val response = apiService.updateMemberRole(groupId, userId, request)
            
            if (response.isSuccessful && response.body()?.success == true) {
                // Refresh members
                refreshGroupMembers(groupId)
                Result.success(true)
            } else {
                val error = response.body()?.message ?: "Failed to update role"
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception updating role", e)
            Result.failure(e)
        }
    }
    
    /**
     * Refreshes group members from server
     */
    suspend fun refreshGroupMembers(groupId: String): Result<List<GroupMemberEntity>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching members for group: $groupId")
            val response = apiService.getGroupMembers(groupId)
            
            Log.d(TAG, "Members response - code: ${response.code()}, isSuccessful: ${response.isSuccessful}")
            Log.d(TAG, "Response body: ${response.body()}")
            Log.d(TAG, "Response success field: ${response.body()?.success}")
            Log.d(TAG, "Response members field: ${response.body()?.members}")
            
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true) {
                    val membersDto = body.members ?: emptyList()
                    Log.d(TAG, "Received ${membersDto.size} members from server")
                    
                    val members = membersDto.map { it.toEntity(groupId) }
                    
                    // Update local cache
                    groupMemberDao.insertMembers(members)
                    Log.d(TAG, "Members saved to local database")
                    
                    // Update member count in group entity
                    updateMemberCount(groupId)
                    
                    Result.success(members)
                } else {
                    val error = body?.message ?: "Server returned success=false or null"
                    Log.e(TAG, "Failed to fetch members - success is false or null. Body: $body")
                    Result.failure(Exception(error))
                }
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "HTTP error ${response.code()} - errorBody: $errorBody")
                Result.failure(Exception("HTTP ${response.code()}: $errorBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching members", e)
            Result.failure(e)
        }
    }
    
    // ========== HELPER METHODS ==========
    
    /**
     * Updates the member count in the group entity
     */
    private suspend fun updateMemberCount(groupId: String) = withContext(Dispatchers.IO) {
        try {
            val count = groupMemberDao.getMemberCount(groupId)
            groupDao.updateMemberCount(groupId, count)
            Log.d(TAG, "Updated member count for group $groupId: $count")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating member count for group $groupId", e)
        }
    }
    
    // ========== LOCAL CACHE QUERIES ==========
    
    /**
     * Observes all groups from local database
     */
    fun observeAllGroups(): Flow<List<GroupEntity>> {
        return groupDao.getAllGroups()
    }
    
    /**
     * Observes a specific group
     */
    fun observeGroup(groupId: String): Flow<GroupEntity?> {
        return groupDao.getGroupByIdFlow(groupId)
    }
    
    /**
     * Observes members of a group
     */
    fun observeGroupMembers(groupId: String): Flow<List<GroupMemberEntity>> {
        return groupMemberDao.getGroupMembers(groupId)
    }
    
    /**
     * Gets a group synchronously
     */
    suspend fun getGroup(groupId: String): GroupEntity? = withContext(Dispatchers.IO) {
        groupDao.getGroupById(groupId)
    }
    
    /**
     * Gets members synchronously
     */
    suspend fun getMembers(groupId: String): List<GroupMemberEntity> = withContext(Dispatchers.IO) {
        groupMemberDao.getGroupMembersList(groupId)
    }
}
