package it.fabiodirauso.shutappchat.model

/**
 * Sealed class che rappresenta un item nella homepage
 * Può essere una conversazione 1-1 o un gruppo
 */
sealed class ConversationItem {
    abstract val id: String
    abstract val name: String
    abstract val lastMessage: String?
    abstract val lastMessageTime: Long
    abstract val unreadCount: Int
    
    data class Chat(
        val conversation: ConversationEntity
    ) : ConversationItem() {
        override val id: String get() = conversation.id
        override val name: String get() = conversation.participantName
        override val lastMessage: String? get() = conversation.lastMessage
        override val lastMessageTime: Long get() = conversation.lastMessageTime?.time ?: 0L
        override val unreadCount: Int get() = conversation.unreadCount
    }
    
    data class Group(
        val group: GroupEntity
    ) : ConversationItem() {
        override val id: String get() = group.groupId
        override val name: String get() = group.groupName
        override val lastMessage: String? get() = group.lastMessageContent
        override val lastMessageTime: Long get() = group.lastMessageTime
        override val unreadCount: Int get() = group.unreadCount
    }
}
