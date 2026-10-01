package com.mina.moonchat.utils

import android.content.Context
import android.util.Log
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.firestore.FirebaseFirestore
import com.mina.moonchat.application.MoonChat
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object FcmNotificationSender {
    private const val TAG = "FcmNotificationSender"

    // Optional: Firebase Cloud Messaging Legacy Server Key (starts with AAAA...)
    private var serverKey: String = ""

    // Firebase Project ID
    private var projectId: String = "moon-chat-d7d85"

    private var googleCredentials: GoogleCredentials? = null

    fun setServerKey(key: String) {
        serverKey = key
    }

    private fun getFreshAccessToken(context: Context): Pair<String, String>? {
        return try {
            if (googleCredentials == null) {
                val inputStream = try {
                    context.assets.open("service_account.json")
                } catch (e: Exception) {
                    Log.w(TAG, "service_account.json not found in app/src/main/assets/")
                    null
                }

                if (inputStream != null) {
                    googleCredentials = GoogleCredentials.fromStream(inputStream)
                        .createScoped(listOf("https://www.googleapis.com/auth/firebase.messaging"))
                }
            }

            val creds = googleCredentials
            if (creds != null) {
                creds.refreshIfExpired()
                val token = creds.accessToken?.tokenValue
                if (!token.isNullOrBlank()) {
                    return Pair(token, projectId)
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error generating fresh OAuth2 token from service_account.json", e)
            null
        }
    }

    fun sendNotification(
        recipientId: String,
        senderId: String,
        senderName: String,
        senderProfileImg: String? = null,
        messageText: String,
        messageId: String = ""
    ) {
        sendNotificationInternal(
            recipientId = recipientId,
            senderId = senderId,
            senderName = senderName,
            senderProfileImg = senderProfileImg,
            messageText = messageText,
            messageId = messageId,
            type = "CHAT_MESSAGE"
        )
    }

    fun sendFriendRequestNotification(
        recipientId: String,
        senderId: String,
        senderName: String,
        senderProfileImg: String?,
        requestId: String
    ) {
        sendNotificationInternal(
            recipientId = recipientId,
            senderId = senderId,
            senderName = senderName,
            senderProfileImg = senderProfileImg,
            messageText = "Friend request from $senderName",
            messageId = requestId,
            type = "FRIEND_REQUEST"
        )
    }

    fun sendFriendRequestAcceptedNotification(
        recipientId: String,
        senderId: String,
        senderName: String,
        senderProfileImg: String?
    ) {
        sendNotificationInternal(
            recipientId = recipientId,
            senderId = senderId,
            senderName = senderName,
            senderProfileImg = senderProfileImg,
            messageText = "$senderName accepted your friend request!",
            messageId = "accepted_$senderId",
            type = "FRIEND_REQUEST_ACCEPTED"
        )
    }

    private fun sendNotificationInternal(
        recipientId: String,
        senderId: String,
        senderName: String,
        senderProfileImg: String?,
        messageText: String,
        messageId: String,
        type: String
    ) {
        if (recipientId.isBlank()) return

        FirebaseFirestore.getInstance()
            .collection("Users")
            .document(recipientId)
            .get()
            .addOnSuccessListener { document ->
                val fcmToken = document.getString("fcmToken") ?: ""
                if (fcmToken.isNotBlank()) {
                    thread {
                        val tokenAndProject = getFreshAccessToken(MoonChat.instance)
                        if (tokenAndProject != null) {
                            val (bearerToken, projId) = tokenAndProject
                            sendFcmV1Payload(
                                fcmToken = fcmToken,
                                projectId = projId,
                                bearerToken = bearerToken,
                                senderId = senderId,
                                senderName = senderName,
                                senderProfileImg = senderProfileImg,
                                messageText = messageText,
                                messageId = messageId,
                                type = type
                            )
                        } else if (serverKey.isNotBlank()) {
                            sendFcmLegacyPayload(
                                fcmToken = fcmToken,
                                senderId = senderId,
                                senderName = senderName,
                                senderProfileImg = senderProfileImg,
                                messageText = messageText,
                                messageId = messageId,
                                type = type
                            )
                        } else {
                            Log.w(
                                TAG,
                                "Cannot send push notification: Failed to generate OAuth2 token from assets/service_account.json and no Legacy Server Key configured."
                            )
                        }
                    }
                } else {
                    Log.d(TAG, "Recipient $recipientId has no FCM token registered in Firestore.")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to fetch FCM token for recipient $recipientId", e)
            }
    }

    private fun sendFcmV1Payload(
        fcmToken: String,
        projectId: String,
        bearerToken: String,
        senderId: String,
        senderName: String,
        senderProfileImg: String?,
        messageText: String,
        messageId: String,
        type: String
    ) {
        try {
            val url = URL("https://fcm.googleapis.com/v1/projects/$projectId/messages:send")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; UTF-8")
            conn.setRequestProperty("Authorization", "Bearer $bearerToken")
            conn.doOutput = true

            val messageObj = JSONObject().apply {
                put("token", fcmToken)

                val data = JSONObject().apply {
                    put("senderId", senderId)
                    put("senderName", senderName)
                    put("senderProfileImg", senderProfileImg ?: "")
                    put("message", messageText)
                    put("messageId", messageId)
                    put("requestId", messageId)
                    put("type", type)
                }
                put("data", data)

                val androidConfig = JSONObject().apply {
                    put("priority", "HIGH")
                }
                put("android", androidConfig)
            }

            val json = JSONObject().apply {
                put("message", messageObj)
            }

            val writer = OutputStreamWriter(conn.outputStream, "UTF-8")
            writer.write(json.toString())
            writer.flush()
            writer.close()

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                Log.d(TAG, "FCM v1 Push Notification sent successfully [$responseCode] (type=$type)")
            } else {
                val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "FCM v1 Push Failed [$responseCode]: $errorText")

                if (serverKey.isNotBlank()) {
                    Log.d(TAG, "Attempting Legacy FCM fallback...")
                    sendFcmLegacyPayload(
                        fcmToken,
                        senderId,
                        senderName,
                        senderProfileImg,
                        messageText,
                        messageId,
                        type
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending FCM v1 notification payload", e)
            if (serverKey.isNotBlank()) {
                sendFcmLegacyPayload(
                    fcmToken,
                    senderId,
                    senderName,
                    senderProfileImg,
                    messageText,
                    messageId,
                    type
                )
            }
        }
    }

    private fun sendFcmLegacyPayload(
        fcmToken: String,
        senderId: String,
        senderName: String,
        senderProfileImg: String?,
        messageText: String,
        messageId: String,
        type: String
    ) {
        if (serverKey.isBlank()) {
            Log.w(TAG, "FCM Server key is blank, skipping FCM Legacy HTTP request.")
            return
        }

        try {
            val url = URL("https://fcm.googleapis.com/fcm/send")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "key=$serverKey")
            conn.doOutput = true

            val json = JSONObject().apply {
                put("to", fcmToken)
                put("priority", "high")

                val data = JSONObject().apply {
                    put("senderId", senderId)
                    put("senderName", senderName)
                    put("senderProfileImg", senderProfileImg ?: "")
                    put("message", messageText)
                    put("messageId", messageId)
                    put("requestId", messageId)
                    put("type", type)
                }
                put("data", data)
            }

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(json.toString())
            writer.flush()
            writer.close()

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                Log.d(TAG, "FCM Legacy Push Notification sent successfully [$responseCode] (type=$type)")
            } else {
                val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "FCM Legacy Push Failed [$responseCode]: $errorText")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending FCM Legacy notification payload", e)
        }
    }
}
