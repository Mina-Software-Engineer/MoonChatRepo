package com.mina.moonchat.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val senderId = intent?.getStringExtra("senderId") ?: return
        NotificationHelper.clearUnreadForSender(senderId)
    }
}
