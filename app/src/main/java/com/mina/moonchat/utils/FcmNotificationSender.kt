package com.mina.moonchat.utils

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object FcmNotificationSender {
    private const val TAG = "FcmNotificationSender"

    private var serverKey: String =
        "BF18QkrltOxu1esWX2XHNfLR4etGDtFDnrg8TsCHunW78HoZdyBWN7OUyfH5VkXq4AzEWtXKfLjtb3Scwju0g6k"
    private var projectId: String = "moon-chat-d7d85"
    private var accessToken: String ="ya29.c.c0AZ4bNpYTLeRrbXxzxXSrwaEruBEfh4d7_xNgT9wh2pKaNoTNqz9Q7LMztokwAu4czXoh18MCFLJBlV-w8WHi36_7sgVyKGUpCBa-p03Hvsh4aT4TKbXK9nRuzcgLtkituQ1ek9ofNQ3JG3-dPcgVvXwLI6RJGnpWsiPKquyp3btMorltO_JhMoSSlN-JVx8tmLdtSXjahtOA-w6cGvaag9hWPiYo9zp4SLbFtE6CQQcfu5pt-iq_Pk47EnQhMDQsid6W5_s9vNNSQ9luWTQWvlRtQzrC_HRkfiFfeveOMb97XkaZQIcM3LkWwyiFksIBIoGFBVXhRNnsu8S58PNxdurkTHYxMHLlXe19QT_Qi4Y8dqDFnuBHoMQyT385D_dV_dwteIB_sJMMYv8Qsm-Qmb7ikJWkZO58k5SRtXsn-jlrepQ2hdV6VmM27ur0eUw_4lgXg7xdIkoldpnVui1Wf1uzXrzaWk60hqfeUwyd7jmvBv8ds9pXUUMi-emsXov9OXd-mx6Fcxt5r6Vkb5MRnF8OYfFSgmv-M2pxzbwB-zIbd87S2Qwmchr02mY46XatxdoIkx0IeOaW2Wsv3u26bpkqqVawXo70IisXStzsJ9pi1o9bwS0dtV2oVWkafzo_v5olYOy2v0sdxYmtdvamcU1_QVl0Xmlqp08r-aIhl8zwBQfpXFzXz9xuwm1ikUhnhjZ-dB92vkF0Irv7bUyO1h-bhuJMicbWMRXnJ3fYtgWqbX5eIaeVram8YzXXU6yX4epikOzOy-pFFaRZMUrreF9S9fRjhm0zIo8nUFMoOZlVWe8ZFhaxjMld0gs_wqzS2Q2Oj_xyWv6Xn4gZ0pUlQfxg2gw6phcUubaQxquB4pq6Iuitsd9b9xSlV-FOpr48dru73noWa_Mf7-kXMXhnn1jcVlja4v-mRgQ4YiW-IoSja7X_p8rRf4gliqxB-5kJJX3-t7575afczauV8Ba-XVSp0fJW9aghastkrQU7r7mYq9ZaORtSYZ2"

    fun setServerKey(key: String) {
        serverKey = key
    }

    fun configureV1(projectId: String, accessToken: String) {
        this.projectId = projectId
        this.accessToken = accessToken
    }

    fun sendNotification(
        recipientId: String,
        senderId: String,
        senderName: String,
        senderProfileImg: String? = null,
        messageText: String,
        messageId: String = ""
    ) {
        if (recipientId.isBlank()) return

        FirebaseFirestore.getInstance()
            .collection("Users")
            .document(recipientId)
            .get()
            .addOnSuccessListener { document ->
                val fcmToken = document.getString("fcmToken") ?: ""
                if (fcmToken.isNotBlank()) {
                    if (projectId.isNotBlank() && accessToken.isNotBlank()) {
                        sendFcmV1Payload(
                            fcmToken = fcmToken,
                            senderId = senderId,
                            senderName = senderName,
                            senderProfileImg = senderProfileImg,
                            messageText = messageText,
                            messageId = messageId
                        )
                    } else {
                        sendFcmLegacyPayload(
                            fcmToken = fcmToken,
                            senderId = senderId,
                            senderName = senderName,
                            senderProfileImg = senderProfileImg,
                            messageText = messageText,
                            messageId = messageId
                        )
                    }
                } else {
                    Log.d(TAG, "Recipient $recipientId has no FCM token registered.")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to fetch FCM token for recipient $recipientId", e)
            }
    }

    private fun sendFcmV1Payload(
        fcmToken: String,
        senderId: String,
        senderName: String,
        senderProfileImg: String?,
        messageText: String,
        messageId: String
    ) {
        thread {
            try {
                val url = URL("https://fcm.googleapis.com/v1/projects/$projectId/messages:send")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; UTF-8")
                conn.setRequestProperty("Authorization", "Bearer $accessToken")
                conn.doOutput = true

                val messageObj = JSONObject().apply {
                    put("token", fcmToken)

                    val notification = JSONObject().apply {
                        put("title", "New message")
                        put("body", "$senderName: $messageText")
                    }
                    put("notification", notification)

                    val data = JSONObject().apply {
                        put("senderId", senderId)
                        put("senderName", senderName)
                        put("senderProfileImg", senderProfileImg ?: "")
                        put("message", messageText)
                        put("messageId", messageId)
                        put("type", "CHAT_MESSAGE")
                    }
                    put("data", data)

                    val androidConfig = JSONObject().apply {
                        put("priority", "HIGH")
                        val androidNotification = JSONObject().apply {
                            put("channel_id", NotificationHelper.CHANNEL_ID)
                            put("sound", "default")
                        }
                        put("notification", androidNotification)
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
                Log.d(TAG, "FCM v1 Push HTTP response code: $responseCode")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending FCM v1 notification payload", e)
            }
        }
    }

    private fun sendFcmLegacyPayload(
        fcmToken: String,
        senderId: String,
        senderName: String,
        senderProfileImg: String?,
        messageText: String,
        messageId: String
    ) {
        if (serverKey.isBlank()) {
            Log.w(TAG, "FCM Server key is blank, skipping FCM Legacy HTTP request.")
            return
        }

        thread {
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

                    val notification = JSONObject().apply {
                        put("title", "New message")
                        put("body", "$senderName: $messageText")
                        put("android_channel_id", NotificationHelper.CHANNEL_ID)
                        put("sound", "default")
                    }
                    put("notification", notification)

                    val data = JSONObject().apply {
                        put("senderId", senderId)
                        put("senderName", senderName)
                        put("senderProfileImg", senderProfileImg ?: "")
                        put("message", messageText)
                        put("messageId", messageId)
                        put("type", "CHAT_MESSAGE")
                    }
                    put("data", data)
                }

                val writer = OutputStreamWriter(conn.outputStream)
                writer.write(json.toString())
                writer.flush()
                writer.close()

                val responseCode = conn.responseCode
                Log.d(TAG, "FCM Legacy Push HTTP response code: $responseCode")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending FCM notification payload", e)
            }
        }
    }
}
