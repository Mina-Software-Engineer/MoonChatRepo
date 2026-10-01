package com.mina.moonchat.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.mina.moonchat.adapters.FriendListItem
import com.mina.moonchat.adapters.FriendsAdapter
import com.mina.moonchat.adapters.SearchListener
import com.mina.moonchat.base.BaseFragment
import com.mina.moonchat.databinding.FragmentFriendsBinding
import com.mina.moonchat.models.User
import com.mina.moonchat.viewmodels.FriendsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.koin.android.ext.android.inject

class FriendsFragment : BaseFragment() {
    private lateinit var _binding: FragmentFriendsBinding
    val binding get() = _binding
    override val _viewModel: FriendsViewModel by inject()

    private val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    private val mAuth: FirebaseAuth by lazy {
        FirebaseAuth.getInstance()
    }

    private val presenceRef by lazy {
        FirebaseDatabase.getInstance().getReference("Users states")
    }

    private lateinit var rvAdapter: FriendsAdapter
    private var firestoreListener: ListenerRegistration? = null
    private var requestsListener: ListenerRegistration? = null
    private var presenceListener: ValueEventListener? = null

    private var cachedUsers: List<User> = emptyList()
    private var userOnlineStates: Map<String, Boolean> = emptyMap()
    private var pendingRequests: List<FriendListItem.Request> = emptyList()
    private var friendUserIdsSet: Set<String> = emptySet()
    private var friendsListener: ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentFriendsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvAdapter = FriendsAdapter(
            clickListener = SearchListener { user ->
                this.findNavController()
                    .navigate(
                        MainScreenFragmentDirections
                            .actionMainScreenFragmentToChatFragment(user)
                    )
            },
            onAcceptClicked = { request ->
                acceptFriendRequest(request)
            },
            onRejectClicked = { request ->
                rejectFriendRequest(request)
            }
        )

        binding.friendsRecyclerView.apply {
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(requireActivity())
            this.adapter = rvAdapter
        }

        binding.fabAddFriend.setOnClickListener {
            this.findNavController().navigate(
                MainScreenFragmentDirections.actionMainScreenFragmentToAddFriendsFragment()
            )
        }

        observeUsersPresence()
        fetchFriends()
        observeIncomingRequests()
        observeFriends()
    }

    private fun observeUsersPresence() {
        presenceListener?.let { presenceRef.removeEventListener(it) }
        presenceListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                userOnlineStates = snapshot.children.associate { child ->
                    val isOnline = child.child("state").getValue(String::class.java)
                        ?.equals("Online", ignoreCase = true) == true
                    (child.key ?: "") to isOnline
                }
                updateFriendsList()
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }
        presenceRef.addValueEventListener(presenceListener!!)
    }

    private fun fetchFriends() {
        firestoreListener?.remove()
        firestoreListener = db.collection("Users").addSnapshotListener { value, error ->
            if (error != null) return@addSnapshotListener

            val documents = value?.documents ?: emptyList()
            cachedUsers = documents.mapNotNull { doc ->
                val user = doc.toObject(User::class.java) ?: return@mapNotNull null
                if (user.userId.isEmpty()) {
                    user.userId = doc.id
                }
                user
            }
            updateFriendsList()
        }
    }

    private fun observeIncomingRequests() {
        val currentUserId = mAuth.currentUser?.uid ?: return
        requestsListener?.remove()
        requestsListener = db.collection("FriendRequests")
            .whereEqualTo("receiverId", currentUserId)
            .whereEqualTo("status", "pending")
            .addSnapshotListener { value, error ->
                if (error != null || value == null) return@addSnapshotListener

                val requestsList = value.documents.mapNotNull { doc ->
                    val senderId = doc.getString("senderId") ?: return@mapNotNull null
                    val senderName = doc.getString("senderName") ?: "MoonChat User"
                    val senderProfileImg = doc.getString("senderProfileImg")
                    val senderBio = doc.getString("senderBio") ?: "Hey there! I am using MoonChat."

                    val senderUser = User(
                        displayName = senderName,
                        bio = senderBio,
                        profileImg = senderProfileImg,
                        userId = senderId,
                        id = senderId
                    )
                    FriendListItem.Request(user = senderUser, requestId = doc.id)
                }

                pendingRequests = requestsList
                updateFriendsList()
            }
    }

    private fun acceptFriendRequest(request: FriendListItem.Request) {
        val currentUserId = mAuth.currentUser?.uid ?: return
        val senderId = request.user.userId.ifEmpty { request.user.id }
        if (senderId.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                db.collection("FriendRequests").document(request.requestId).update("status", "accepted")
                db.collection("Users").document(currentUserId).collection("Requests").document(senderId).delete()

                val time = System.currentTimeMillis()
                db.collection("Users").document(currentUserId).collection("Friends").document(senderId)
                    .set(mapOf("friendId" to senderId, "addedAt" to time))
                db.collection("Users").document(senderId).collection("Friends").document(currentUserId)
                    .set(mapOf("friendId" to currentUserId, "addedAt" to time))

                db.collection("Users").document(currentUserId).update("friends", FieldValue.arrayUnion(senderId))
                db.collection("Users").document(senderId).update("friends", FieldValue.arrayUnion(currentUserId))

                val currentUserDoc = db.collection("Users").document(currentUserId).get().await()
                val currentUserName = currentUserDoc.getString("displayName")
                    ?: mAuth.currentUser?.displayName
                    ?: "MoonChat User"
                val currentUserPfp = currentUserDoc.getString("profileImg")

                com.mina.moonchat.utils.FcmNotificationSender.sendFriendRequestAcceptedNotification(
                    recipientId = senderId,
                    senderId = currentUserId,
                    senderName = currentUserName,
                    senderProfileImg = currentUserPfp
                )
            } catch (_: Exception) {}
        }
    }

    private fun rejectFriendRequest(request: FriendListItem.Request) {
        val currentUserId = mAuth.currentUser?.uid ?: return
        val senderId = request.user.userId.ifEmpty { request.user.id }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                db.collection("FriendRequests").document(request.requestId).update("status", "rejected")
                if (senderId.isNotEmpty()) {
                    db.collection("Users").document(currentUserId).collection("Requests").document(senderId).delete()
                }
            } catch (_: Exception) {}
        }
    }

    private fun observeFriends() {
        val currentUserId = mAuth.currentUser?.uid ?: return
        friendsListener?.remove()
        friendsListener = db.collection("Users").document(currentUserId).collection("Friends")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener

                val ids = snapshot.documents.map { it.id }.toMutableSet()

                db.collection("Users").document(currentUserId).get()
                    .addOnSuccessListener { userDoc ->
                        val friendsArray = userDoc.get("friends") as? List<*>
                        friendsArray?.forEach { id ->
                            (id as? String)?.let { ids.add(it) }
                        }
                        friendUserIdsSet = ids
                        updateFriendsList()
                    }
                    .addOnFailureListener {
                        friendUserIdsSet = ids
                        updateFriendsList()
                    }
            }
    }

    private fun updateFriendsList() {
        val currentUserId = mAuth.currentUser?.uid ?: return

        // Filter to ONLY users who are confirmed friends
        val friends = cachedUsers.filter { user ->
            val uId = user.userId.ifEmpty { user.id }
            uId != currentUserId && (friendUserIdsSet.contains(uId) || friendUserIdsSet.contains(user.userId) || friendUserIdsSet.contains(user.id))
        }.map { user ->
            val uId = user.userId.ifEmpty { user.id }
            val isOnline = userOnlineStates[uId] ?: userOnlineStates[user.userId] ?: user.onlineState.equals("Online", ignoreCase = true)
            user.copy(onlineState = if (isOnline) "Online" else "Offline")
        }

        val (onlineFriends, offlineFriends) = friends.partition { it.onlineState.equals("Online", ignoreCase = true) }

        val listItems = ArrayList<FriendListItem>(friends.size + pendingRequests.size + 3)

        if (onlineFriends.isNotEmpty()) {
            listItems.add(FriendListItem.Header("Online", true))
            onlineFriends.mapTo(listItems) { FriendListItem.Friend(it) }
        }

        if (offlineFriends.isNotEmpty()) {
            listItems.add(FriendListItem.Header("Offline", false))
            offlineFriends.mapTo(listItems) { FriendListItem.Friend(it) }
        }

        if (pendingRequests.isNotEmpty()) {
            listItems.add(FriendListItem.Header("Friend Requests", false))
            listItems.addAll(pendingRequests)
        }

        rvAdapter.submitList(listItems)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        firestoreListener?.remove()
        firestoreListener = null
        requestsListener?.remove()
        requestsListener = null
        friendsListener?.remove()
        friendsListener = null
        presenceListener?.let { presenceRef.removeEventListener(it) }
        presenceListener = null
    }
}
