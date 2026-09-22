package com.mina.moonchat.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.mina.moonchat.R
import com.mina.moonchat.application.MoonChat
import com.mina.moonchat.ui.activities.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap

object NotificationHelper {
    private const val TAG = "NotificationHelper"
    const val CHANNEL_ID = "chat_messages_channel"
    const val CHANNEL_NAME = "Chat Messages"
    const val CHANNEL_DESC = "Notifications for incoming chat messages"

    private val shownMessageIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private val unreadMessagesBySender = ConcurrentHashMap<String, MutableList<String>>()

    fun clearUnreadForSender(senderId: String?) {
        if (senderId == null) return
        unreadMessagesBySender.remove(senderId)
        try {
            val context = MoonChat.instance
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(senderId.hashCode())
            Log.d(TAG, "Cleared unread notification stack for senderId: $senderId")
        } catch (e: Exception) {
            Log.e(TAG, "Error canceling notification for $senderId: ${e.message}")
        }
    }

    fun showNotification(
        context: Context = MoonChat.instance,
        messageId: String? = null,
        senderId: String? = null,
        senderName: String = "MoonChat",
        senderProfileImg: String? = null,
        messageText: String = "New incoming message",
        timeString: String? = null,
        type: String = "CHAT_MESSAGE"
    ) {
        if (senderId == null) return

        // Prevent duplicate notification for the exact same message ID
        if (!messageId.isNullOrBlank()) {
            if (shownMessageIds.contains(messageId)) {
                Log.d(TAG, "Notification for messageId $messageId already shown, skipping duplicate")
                return
            }
            shownMessageIds.add(messageId)
            if (shownMessageIds.size > 100) {
                val iterator = shownMessageIds.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }

        // Do not show notification if message sent by current logged in user
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
        if (senderId == currentUserId) {
            Log.d(TAG, "Message is from current user ($senderId), skipping notification")
            return
        }

        // Do not show notification if user is currently in the chat with this sender
        if (ActiveChatManager.activeRecipientId == senderId) {
            Log.d(TAG, "User is currently in chat with $senderId, skipping notification")
            clearUnreadForSender(senderId)
            return
        }

        // Append message to unread list for this sender
        val senderUnreadList = unreadMessagesBySender.getOrPut(senderId) {
            Collections.synchronizedList(mutableListOf())
        }
        senderUnreadList.add(messageText)

        val totalUnreadCount = senderUnreadList.size
        // Limit displayed lines in notification stack to the last 3 messages
        val displayedMessages = senderUnreadList.takeLast(3)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                // Create Notification Channel for Android 8.0+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = CHANNEL_DESC
                        enableLights(true)
                        enableVibration(true)
                    }
                    notificationManager.createNotificationChannel(channel)
                }

                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("senderId", senderId)
                    putExtra("senderName", senderName)
                    putExtra("type", type)
                }

                val pendingIntent = PendingIntent.getActivity(
                    context,
                    senderId.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val dismissIntent = Intent(context, NotificationDismissReceiver::class.java).apply {
                    putExtra("senderId", senderId)
                }
                val dismissPendingIntent = PendingIntent.getBroadcast(
                    context,
                    senderId.hashCode(),
                    dismissIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val titleText = if (totalUnreadCount == 1) "New message" else "New messages"
                val formattedContentText = "$senderName: $messageText"

                // Build InboxStyle notification for stacked messages (max 3 lines displayed)
                val inboxStyle = NotificationCompat.InboxStyle()
                    .setBigContentTitle(titleText)

                for (msg in displayedMessages) {
                    inboxStyle.addLine("$senderName: $msg")
                }

                if (totalUnreadCount > 3) {
                    inboxStyle.setSummaryText("+${totalUnreadCount - 3} more")
                } else if (totalUnreadCount > 1) {
                    inboxStyle.setSummaryText("$totalUnreadCount messages")
                }

                // Load Large Icon (Sender Profile Image)
                val largeIconBitmap = loadBitmap(context, senderProfileImg)

                val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_app_logo)
                    .setContentTitle(titleText)
                    .setSubText(senderName)
                    .setContentText(formattedContentText)
                    .setStyle(inboxStyle)
                    .setNumber(totalUnreadCount)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setDeleteIntent(dismissPendingIntent)

                if (largeIconBitmap != null) {
                    notificationBuilder.setLargeIcon(largeIconBitmap)
                }

                val notificationId = senderId.hashCode()
                notificationManager.notify(notificationId, notificationBuilder.build())
                Log.d(TAG, "Notification shown for sender: $senderName ($senderId) - title: '$titleText', content: '$formattedContentText'")
            } catch (e: Exception) {
                Log.e(TAG, "Error building notification: ${e.message}", e)
            }
        }
    }

    private fun loadBitmap(context: Context, url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return try {
            Glide.with(context.applicationContext)
                .asBitmap()
                .load(url)
                .circleCrop()
                .submit(200, 200)
                .get()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading notification large icon from $url: ${e.message}")
            null
        }
    }
}
