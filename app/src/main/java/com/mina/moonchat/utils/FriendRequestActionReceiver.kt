package com.mina.moonchat.utils

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class FriendRequestActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "FriendRequestReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        val requestId = intent.getStringExtra("requestId") ?: return
        val senderId = intent.getStringExtra("senderId") ?: return
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        val notificationManager = context?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(requestId.hashCode())

        if (action == "ACTION_ACCEPT_FRIEND_REQUEST") {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = FirebaseFirestore.getInstance()

                    // Update FriendRequest status to accepted
                    db.collection("FriendRequests").document(requestId)
                        .update("status", "accepted").await()

                    db.collection("Users").document(currentUserId)
                        .collection("Requests").document(senderId).delete().await()

                    val time = System.currentTimeMillis()
                    // Add friend to User B (current user) subcollection
                    db.collection("Users").document(currentUserId)
                        .collection("Friends").document(senderId)
                        .set(mapOf("friendId" to senderId, "addedAt" to time)).await()

                    // Add friend to User A (sender) subcollection
                    db.collection("Users").document(senderId)
                        .collection("Friends").document(currentUserId)
                        .set(mapOf("friendId" to currentUserId, "addedAt" to time)).await()

                    db.collection("Users").document(currentUserId).update("friends", FieldValue.arrayUnion(senderId)).await()
                    db.collection("Users").document(senderId).update("friends", FieldValue.arrayUnion(currentUserId)).await()

                    Log.d(TAG, "Friend request accepted successfully for $requestId")

                    // Notify User A that User B accepted the request
                    val currentUserDoc = db.collection("Users").document(currentUserId).get().await()
                    val currentUserName = currentUserDoc.getString("displayName")
                        ?: FirebaseAuth.getInstance().currentUser?.displayName
                        ?: "MoonChat User"
                    val currentUserPfp = currentUserDoc.getString("profileImg")

                    FcmNotificationSender.sendFriendRequestAcceptedNotification(
                        recipientId = senderId,
                        senderId = currentUserId,
                        senderName = currentUserName,
                        senderProfileImg = currentUserPfp
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error accepting friend request: ${e.message}", e)
                }
            }
        } else if (action == "ACTION_REJECT_FRIEND_REQUEST") {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = FirebaseFirestore.getInstance()
                    db.collection("FriendRequests").document(requestId)
                        .update("status", "rejected").await()
                    db.collection("Users").document(currentUserId)
                        .collection("Requests").document(senderId).delete().await()
                    Log.d(TAG, "Friend request rejected for $requestId")
                } catch (e: Exception) {
                    Log.e(TAG, "Error rejecting friend request: ${e.message}", e)
                }
            }
        }
    }
}
