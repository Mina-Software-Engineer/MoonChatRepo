package com.mina.moonchat.data.server

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.mina.moonchat.utils.NotificationHelper

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCM_Service"
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d(TAG, "Message received from: ${remoteMessage.from}")
        Log.d(TAG, "Data payload: ${remoteMessage.data}")

        val data = remoteMessage.data
        val notification = remoteMessage.notification

        val senderName = data["senderName"]
            ?: data["title"]
            ?: notification?.title
            ?: "MoonChat"

        val senderProfileImg = data["senderProfileImg"]
            ?: data["profileImg"]
            ?: data["pfp"]

        val messageText = data["message"]
            ?: data["body"]
            ?: data["messageText"]
            ?: notification?.body
            ?: "New incoming message"

        val timeString = data["time"] ?: data["timestamp"]
        val senderId = data["senderId"]
        val messageId = data["messageId"] ?: data["id"]
        val type = data["type"] ?: "CHAT_MESSAGE"

        NotificationHelper.showNotification(
            context = this,
            messageId = messageId,
            senderId = senderId,
            senderName = senderName,
            senderProfileImg = senderProfileImg,
            messageText = messageText,
            timeString = timeString,
            type = type
        )
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM Token: $token")
        uploadTokenToBackend(token)
    }

    private fun uploadTokenToBackend(token: String) {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance()
            .collection("Users")
            .document(currentUserId)
            .set(mapOf("fcmToken" to token), com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener {
                Log.d(TAG, "Uploaded new FCM token to Firestore")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to upload FCM token to Firestore", e)
            }
    }
}
