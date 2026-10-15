package com.mina.moonchat.utils

internal object ChatNotificationRoutePolicy {
    private val userIdPattern = Regex("[A-Za-z0-9._-]{1,128}")
    private val messageIdPattern = Regex("[A-Za-z0-9_-]{1,128}")

    fun isValidUserId(value: String?): Boolean =
        value != null && userIdPattern.matches(value)

    fun isValidMessageId(value: String?): Boolean =
        value == null || messageIdPattern.matches(value)

    fun canOpenConversation(
        authenticatedUserId: String?,
        senderId: String?,
        channelDocumentExists: Boolean,
        channelRecipientId: String?
    ): Boolean = isValidUserId(authenticatedUserId) &&
        isValidUserId(senderId) &&
        authenticatedUserId != senderId &&
        channelDocumentExists &&
        channelRecipientId == senderId

    fun canOpenAcceptedFriendChat(
        authenticatedUserId: String?,
        senderId: String?,
        friendshipDocumentExists: Boolean
    ): Boolean = isValidUserId(authenticatedUserId) &&
        isValidUserId(senderId) &&
        authenticatedUserId != senderId &&
        friendshipDocumentExists
}
