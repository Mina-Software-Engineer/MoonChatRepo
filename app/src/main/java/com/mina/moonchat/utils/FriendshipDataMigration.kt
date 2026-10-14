package com.mina.moonchat.utils

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object FriendshipDataMigration {
    private const val TAG = "FriendshipMigration"

    /**
     * Discovers friend IDs across all legacy and current storage patterns:
     * 1. Subcollection: Users/{userId}/Friends/{friendId}
     * 2. Array field: Users/{userId}.friends
     * 3. Accepted log: FriendRequests where (senderId == userId || receiverId == userId) && status == "accepted"
     */
    suspend fun fetchConfirmedFriendIds(db: FirebaseFirestore, userId: String): Set<String> {
        if (userId.isBlank()) return emptySet()
        val friendIds = HashSet<String>()

        // Source 1: Users/{userId}/Friends subcollection
        try {
            val subcollectionDocs = db.collection("Users")
                .document(userId)
                .collection("Friends")
                .get()
                .await()

            for (doc in subcollectionDocs.documents) {
                friendIds.add(doc.id)
                doc.getString("friendId")?.let { friendIds.add(it) }
                doc.getString("userId")?.let { friendIds.add(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Friends subcollection for $userId: ${e.message}")
        }

        // Source 2: Users/{userId} document array field 'friends'
        try {
            val userDoc = db.collection("Users").document(userId).get().await()
            val friendsArray = userDoc.get("friends") as? List<*>
            friendsArray?.forEach { id ->
                (id as? String)?.let { if (it.isNotBlank()) friendIds.add(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching friends array for $userId: ${e.message}")
        }

        // Source 3: Accepted FriendRequests where user is sender or receiver
        try {
            val senderRequests = db.collection("FriendRequests")
                .whereEqualTo("senderId", userId)
                .whereEqualTo("status", "accepted")
                .get()
                .await()

            for (doc in senderRequests.documents) {
                val receiverId = doc.getString("receiverId") ?: doc.getString("targetUserId")
                receiverId?.let { if (it.isNotBlank()) friendIds.add(it) }
            }

            val receiverRequests = db.collection("FriendRequests")
                .whereEqualTo("receiverId", userId)
                .whereEqualTo("status", "accepted")
                .get()
                .await()

            for (doc in receiverRequests.documents) {
                val senderId = doc.getString("senderId")
                senderId?.let { if (it.isNotBlank()) friendIds.add(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching accepted FriendRequests for $userId: ${e.message}")
        }

        friendIds.remove(userId)
        return friendIds
    }

    /**
     * Idempotently reconciles and migrates friendship records to ensure
     * both the Friends subcollection and the friends array field are updated.
     */
    suspend fun reconcileAndMigrateFriendships(db: FirebaseFirestore, userId: String): Set<String> {
        val confirmedFriendIds = fetchConfirmedFriendIds(db, userId)
        if (confirmedFriendIds.isEmpty()) return emptySet()

        val time = System.currentTimeMillis()

        for (friendId in confirmedFriendIds) {
            try {
                // Ensure subcollection record exists for current user
                db.collection("Users")
                    .document(userId)
                    .collection("Friends")
                    .document(friendId)
                    .set(mapOf("friendId" to friendId, "addedAt" to time), com.google.firebase.firestore.SetOptions.merge())

                // Ensure array record exists for current user
                db.collection("Users")
                    .document(userId)
                    .update("friends", FieldValue.arrayUnion(friendId))

                // Ensure subcollection record exists for friend
                db.collection("Users")
                    .document(friendId)
                    .collection("Friends")
                    .document(userId)
                    .set(mapOf("friendId" to userId, "addedAt" to time), com.google.firebase.firestore.SetOptions.merge())

                // Ensure array record exists for friend
                db.collection("Users")
                    .document(friendId)
                    .update("friends", FieldValue.arrayUnion(userId))
            } catch (e: Exception) {
                Log.e(TAG, "Error reconciling friendship between $userId and $friendId: ${e.message}")
            }
        }

        return confirmedFriendIds
    }
}
