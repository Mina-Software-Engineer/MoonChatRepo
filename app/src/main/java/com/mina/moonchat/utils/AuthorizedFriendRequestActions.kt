package com.mina.moonchat.utils

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** Uses the signed-in Firebase client and the same accepted-request migration as FriendsFragment. */
object AuthorizedFriendRequestActions {
    suspend fun process(
        db: FirebaseFirestore,
        authenticatedUserId: String,
        requestId: String,
        expectedSenderId: String,
        accept: Boolean
    ): Boolean {
        if (FirebaseAuth.getInstance().currentUser?.uid != authenticatedUserId ||
            !ChatNotificationRoutePolicy.isValidUserId(authenticatedUserId) ||
            !ChatNotificationRoutePolicy.isValidUserId(expectedSenderId) ||
            authenticatedUserId == expectedSenderId ||
            requestId.isBlank() || requestId.length > 256
        ) return false

        val requestRef = db.collection("FriendRequests").document(requestId)
        val updated = db.runTransaction { transaction ->
            val request = transaction.get(requestRef)
            if (!request.exists() ||
                request.getString("receiverId") != authenticatedUserId ||
                request.getString("senderId") != expectedSenderId ||
                request.getString("status")?.equals("pending", ignoreCase = true) != true
            ) {
                false
            } else {
                transaction.update(requestRef, "status", if (accept) "accepted" else "rejected")
                true
            }
        }.await()
        if (!updated) return false

        db.collection("Users").document(authenticatedUserId)
            .collection("Requests").document(expectedSenderId).delete().await()

        if (accept) {
            FriendshipDataMigration.reconcileAndMigrateFriendships(db, authenticatedUserId)
        }
        return true
    }
}
