package it.fabiodirauso.shutappchat.api

import it.fabiodirauso.shutappchat.model.GroupEntity
import it.fabiodirauso.shutappchat.model.GroupMemberEntity

// ========== GROUPS API MODELS ==========

/**
 * Response for listing user's groups
 */
data class GroupsListResponse(
    val success: Boolean,
    val groups: List<GroupData>? = null,
    val message: String? = null
)

data class GroupData(
    val group_id: String,
    val group_name: String,
    val group_description: String? = null,
    val group_picture_id: String? = null,
    val created_by: Long,
    val created_at: String,
    val updated_at: String,
    val group_mode: String, // "OPEN" or "RESTRICTED"
    val total_members: Int,
    val is_active: Boolean = true,
    val user_role: String, // "ADMIN" or "MEMBER"
    val last_message: String? = null,
    val last_message_time: String? = null,
    val unread_count: Int = 0
)

/**
 * Request to create a new group
 */
data class CreateGroupRequest(
    val name: String,
    val description: String? = null,
    val mode: String = "OPEN", // "OPEN" or "RESTRICTED"
    val picture_id: String? = null,
    val initial_members: List<Long>? = null // User IDs to add initially
)

/**
 * Response after creating a group
 */
data class CreateGroupResponse(
    val success: Boolean,
    val group_id: String? = null,
    val group_name: String? = null,
    val message: String? = null
)

/**
 * Request to add members to a group
 */
data class AddMembersRequest(
    val user_ids: List<Long>
)

/**
 * Request to update member role
 */
data class UpdateRoleRequest(
    val role: String // "ADMIN" or "MEMBER"
)

/**
 * Request to update group settings
 */
data class UpdateGroupSettingsRequest(
    val name: String? = null,
    val description: String? = null,
    val mode: String? = null, // "OPEN" or "RESTRICTED"
    val picture_id: String? = null
)

/**
 * Response for group members list
 */
data class GroupMembersResponse(
    val success: Boolean,
    val members: List<GroupMemberData>? = null,
    val message: String? = null
)

data class GroupMemberData(
    val user_id: Long,
    val username: String,
    val nickname: String,
    val profile_picture: String? = null,
    val role: String, // "ADMIN" or "MEMBER"
    val joined_at: String
)

/**
 * Response for group info
 */
data class GroupInfoResponse(
    val success: Boolean,
    val group: GroupData? = null,
    val message: String? = null
)

// ========== EXTENSION FUNCTIONS ==========

/**
 * Converts API GroupData to local GroupEntity
 */
fun GroupData.toEntity(): GroupEntity {
    return GroupEntity(
        groupId = this.group_id,
        groupName = this.group_name,
        groupDescription = this.group_description,
        groupPictureId = this.group_picture_id,
        createdBy = this.created_by,
        createdAt = parseTimestamp(this.created_at),
        updatedAt = parseTimestamp(this.updated_at),
        groupMode = it.fabiodirauso.shutappchat.model.GroupMode.valueOf(this.group_mode),
        lastMessageContent = this.last_message,
        lastMessageTime = this.last_message_time?.let { parseTimestamp(it) } ?: 0L,
        unreadCount = this.unread_count,
        totalMembers = this.total_members,
        isActive = this.is_active
    )
}

/**
 * Converts API GroupMemberData to local GroupMemberEntity
 */
fun GroupMemberData.toEntity(groupId: String): GroupMemberEntity {
    return GroupMemberEntity(
        groupId = groupId,
        userId = this.user_id,
        username = this.username,
        displayName = this.nickname ?: this.username, // Fallback to username if nickname is null
        role = it.fabiodirauso.shutappchat.model.GroupRole.valueOf(this.role),
        joinedAt = parseTimestamp(this.joined_at),
        isActive = true
    )
}

/**
 * Parses ISO timestamp to milliseconds
 */
private fun parseTimestamp(timestamp: String): Long {
    return try {
        java.time.Instant.parse(timestamp).toEpochMilli()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }
}
