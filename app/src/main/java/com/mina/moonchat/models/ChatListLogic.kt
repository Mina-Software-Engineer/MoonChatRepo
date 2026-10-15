package com.mina.moonchat.models

/** Load status for the current user's own conversation list. */
enum class ChatListLoadState {
    LOADING,
    READY,
    OFFLINE_WITH_CACHE,
    ERROR
}

enum class ChatListBodyState {
    CONTENT,
    LOADING,
    NO_CONVERSATIONS,
    NO_SEARCH_RESULTS,
    OFFLINE_EMPTY,
    ERROR
}

data class UnreadMessageRecord(
    val messageId: String,
    val senderId: String,
    val isRead: Boolean
)

/** Search only the already-authorized conversation list; ChatItem currently has no username/tag field. */
internal fun filterChatsByPartnerName(chats: List<ChatItem>, query: String): List<ChatItem> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return chats
    return chats.filter { it.username.contains(normalizedQuery, ignoreCase = true) }
}

/** Count unique incoming unread messages from this user's mirrored RTDB conversation records. */
internal fun countUnreadIncomingMessages(
    records: Iterable<UnreadMessageRecord>,
    currentUserId: String
): Int = records.asSequence()
    .filter { it.messageId.isNotBlank() && it.senderId != currentUserId && !it.isRead }
    .map { it.messageId }
    .distinct()
    .count()

internal fun resolveChatListBodyState(
    loadState: ChatListLoadState,
    allConversationCount: Int,
    visibleConversationCount: Int,
    query: String
): ChatListBodyState {
    if (visibleConversationCount > 0) return ChatListBodyState.CONTENT
    if (loadState == ChatListLoadState.LOADING && allConversationCount == 0) {
        return ChatListBodyState.LOADING
    }
    if (loadState == ChatListLoadState.ERROR && allConversationCount == 0) {
        return ChatListBodyState.ERROR
    }
    if (query.isNotBlank()) return ChatListBodyState.NO_SEARCH_RESULTS
    return when (loadState) {
        ChatListLoadState.LOADING -> ChatListBodyState.LOADING
        ChatListLoadState.READY -> ChatListBodyState.NO_CONVERSATIONS
        ChatListLoadState.OFFLINE_WITH_CACHE -> ChatListBodyState.OFFLINE_EMPTY
        ChatListLoadState.ERROR -> ChatListBodyState.ERROR
    }
}
