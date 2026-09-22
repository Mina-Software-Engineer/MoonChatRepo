package com.mina.moonchat.utils

object ActiveChatManager {
    @Volatile
    var activeRecipientId: String? = null
}
