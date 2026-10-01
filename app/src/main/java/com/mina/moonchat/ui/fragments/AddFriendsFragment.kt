package com.mina.moonchat.ui.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.lottiefiles.dotlottie.core.model.Config
import com.lottiefiles.dotlottie.core.util.DotLottieSource
import com.mina.moonchat.adapters.SearchFriendListAdapter
import com.mina.moonchat.adapters.SearchListener
import com.mina.moonchat.databinding.FragmentAddFriendsBinding
import com.mina.moonchat.models.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class AddFriendsFragment : Fragment() {
    private lateinit var _binding: FragmentAddFriendsBinding
    val binding get() = _binding

    private val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }

    private val mAuth: FirebaseAuth by lazy {
        FirebaseAuth.getInstance()
    }

    private val presenceRef by lazy {
        FirebaseDatabase.getInstance().getReference("Users states")
    }

    private lateinit var rvAdapter: SearchFriendListAdapter
    private var presenceListener: ValueEventListener? = null
    private var requestsListener: ListenerRegistration? = null
    private var friendsListener: ListenerRegistration? = null
    private var userOnlineStates: Map<String, Boolean> = emptyMap()
    private var searchJob: Job? = null
    private var cachedUsersList: List<User>? = null
    private var cachedExcludedUserIds: Set<String>? = null
    private val pendingRequestsSet = mutableSetOf<String>()
    private val friendUserIdsSet = mutableSetOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentAddFriendsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.search.apply {
            isActivated = true
            onActionViewExpanded()
            clearFocus()
            setIconifiedByDefault(false)
        }

        rvAdapter = SearchFriendListAdapter(
            clickListener = SearchListener { user ->
                handleUserItemClick(user)
            },
            onAddFriendClicked = { targetUser, button ->
                sendFriendRequest(targetUser, button)
            }
        )

        binding.searchResultsRecyclerView.apply {
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(requireActivity())
            this.adapter = rvAdapter
        }

        setupLottieAnimation()
        observeUsersPresence()
        observeSentRequests()
        observeFriends()
        setupSearch()
    }

    private fun observeFriends() {
        val currentUserId = mAuth.currentUser?.uid ?: return
        friendsListener?.remove()
        friendsListener = db.collection("Users").document(currentUserId).collection("Friends")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                val ids = snapshot.documents.map { it.id }.toMutableSet()
                db.collection("Users").document(currentUserId).get().addOnSuccessListener { userDoc ->
                    (userDoc.get("friends") as? List<*>)?.forEach { id -> (id as? String)?.let { ids.add(it) } }
                    friendUserIdsSet.clear()
                    friendUserIdsSet.addAll(ids)
                    rvAdapter.friendUserIds = friendUserIdsSet
                }
            }
    }

    private fun handleUserItemClick(targetUser: User) {
        val currentUserId = mAuth.currentUser?.uid ?: return
        val targetUserId = targetUser.userId.ifEmpty { targetUser.id }
        if (targetUserId.isEmpty()) return

        // Fast path check from local loaded set
        if (friendUserIdsSet.contains(targetUserId) || friendUserIdsSet.contains(targetUser.userId) || friendUserIdsSet.contains(targetUser.id)) {
            findNavController().navigate(
                AddFriendsFragmentDirections.actionAddFriendsFragmentToChatFragment(targetUser)
            )
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            var isFriend = false
            try {
                val friendDoc = db.collection("Users").document(currentUserId)
                    .collection("Friends").document(targetUserId).get().await()
                if (friendDoc.exists()) {
                    isFriend = true
                } else {
                    val userDoc = db.collection("Users").document(currentUserId).get().await()
                    val friendsList = userDoc.get("friends") as? List<*>
                    if (friendsList?.contains(targetUserId) == true) {
                        isFriend = true
                    } else {
                        val reqId1 = "${currentUserId}_${targetUserId}"
                        val reqId2 = "${targetUserId}_${currentUserId}"
                        val req1 = db.collection("FriendRequests").document(reqId1).get().await()
                        val req2 = db.collection("FriendRequests").document(reqId2).get().await()

                        if ((req1.exists() && req1.getString("status") == "accepted") ||
                            (req2.exists() && req2.getString("status") == "accepted")
                        ) {
                            isFriend = true
                        }
                    }
                }
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                if (isFriend) {
                    findNavController().navigate(
                        AddFriendsFragmentDirections.actionAddFriendsFragmentToChatFragment(targetUser)
                    )
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Friend request must be accepted before starting a chat.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun setupLottieAnimation() {
        val lottieConfig = Config.Builder()
            .autoplay(true)
            .speed(1f)
            .loop(true)
            .source(DotLottieSource.Asset("loading.lottie"))
            .useFrameInterpolation(true)
            .build()
        binding.lottieView.load(lottieConfig)

        binding.lottieView.visibility = View.VISIBLE
        binding.lottieView.bringToFront()
        binding.lottieView.play()
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
                performSearch(binding.search.query?.toString())
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }
        presenceRef.addValueEventListener(presenceListener!!)
    }

    private fun observeSentRequests() {
        val currentUserId = mAuth.currentUser?.uid ?: return
        requestsListener?.remove()
        requestsListener = db.collection("FriendRequests")
            .whereEqualTo("senderId", currentUserId)
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                val ids = snapshot.documents.mapNotNull { doc ->
                    doc.getString("receiverId") ?: doc.getString("targetUserId")
                }.toSet()
                pendingRequestsSet.clear()
                pendingRequestsSet.addAll(ids)
                rvAdapter.pendingUserIds = pendingRequestsSet
            }
    }

    private fun sendFriendRequest(targetUser: User, button: MaterialButton) {
        val currentUserId = mAuth.currentUser?.uid ?: return
        val targetUserId = targetUser.userId.ifEmpty { targetUser.id }
        if (targetUserId.isEmpty() || targetUserId == currentUserId) return

        button.isEnabled = false
        button.text = "Pending"
        button.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#374151"))

        pendingRequestsSet.add(targetUserId)
        rvAdapter.pendingUserIds = pendingRequestsSet

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val currentUserDoc = db.collection("Users").document(currentUserId).get().await()
                val currentUserName = currentUserDoc.getString("displayName")
                    ?: mAuth.currentUser?.displayName
                    ?: "MoonChat User"
                val currentUserPfp = currentUserDoc.getString("profileImg")
                val currentUserBio = currentUserDoc.getString("bio") ?: "Hey there! I am using MoonChat."

                val requestId = "${currentUserId}_${targetUserId}"
                val requestData = hashMapOf(
                    "requestId" to requestId,
                    "senderId" to currentUserId,
                    "senderName" to currentUserName,
                    "senderProfileImg" to currentUserPfp,
                    "senderBio" to currentUserBio,
                    "receiverId" to targetUserId,
                    "status" to "pending",
                    "timestamp" to System.currentTimeMillis()
                )

                db.collection("FriendRequests").document(requestId).set(requestData).await()
                db.collection("Users").document(targetUserId).collection("Requests").document(currentUserId).set(requestData).await()

                com.mina.moonchat.utils.FcmNotificationSender.sendFriendRequestNotification(
                    recipientId = targetUserId,
                    senderId = currentUserId,
                    senderName = currentUserName,
                    senderProfileImg = currentUserPfp,
                    requestId = requestId
                )
            } catch (_: Exception) {}
        }
    }

    private fun setupSearch() {
        binding.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                performSearch(query)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                binding.lottieView.visibility = View.VISIBLE
                binding.lottieView.bringToFront()
                binding.lottieView.play()
                performSearch(newText)
                return false
            }
        })
    }

    private fun performSearch(queryText: String?) {
        searchJob?.cancel()

        val rawQuery = queryText?.trim().orEmpty()

        searchJob = lifecycleScope.launch {
            delay(300)

            try {
                val currentUserId = mAuth.currentUser?.uid.orEmpty()

                // 1. Fetch excluded user IDs (current user, blocked users)
                val excludedUserIds = getExcludedUserIds(currentUserId)

                // 2. Fetch candidate users from Firestore
                val candidateUsers = fetchAllUsers()

                val eligibleUsers = candidateUsers.filter { user ->
                    val uId = user.userId.ifEmpty { user.id }
                    !excludedUserIds.contains(uId) && !excludedUserIds.contains(user.userId) && !excludedUserIds.contains(user.id)
                }

                // 3. Fetch friends map to calculate mutual friends
                val allTargetIds = (eligibleUsers.map { it.userId.ifEmpty { it.id } } + currentUserId).distinct()
                val userFriendsMap = fetchUserFriendsMap(allTargetIds)
                val currentUserFriends = userFriendsMap[currentUserId] ?: emptySet()

                // 4. Score candidates using formula: score = mutualFriends × 30 + sharedGroups × 25 + contactMatch × 40 + recentlyActive (+5)
                val results = withContext(Dispatchers.Default) {
                    eligibleUsers
                        .mapNotNull { user ->
                            val uId = user.userId.ifEmpty { user.id }

                            val candidateFriends = userFriendsMap[uId] ?: userFriendsMap[user.userId] ?: emptySet()
                            val mutualFriendsCount = currentUserFriends.intersect(candidateFriends).size

                            val sharedGroupsCount = 0 // Placeholder for future grouping system
                            val sharedGroupNames = emptyList<String>()

                            val inContacts = false // Placeholder for future contacts system
                            val isRecentlyActive = userOnlineStates[uId] == true || userOnlineStates[user.userId] == true

                            val suggestedScore = (mutualFriendsCount * 30) +
                                    (sharedGroupsCount * 25) +
                                    (if (inContacts) 40 else 0) +
                                    (if (isRecentlyActive) 5 else 0)

                            val reasonString = getSuggestionReason(
                                mutualFriendsCount = mutualFriendsCount,
                                sharedGroupNames = sharedGroupNames,
                                inContacts = inContacts,
                                isRecentlyActive = isRecentlyActive
                            )

                            val updatedUser = user.copy(
                                bio = reasonString,
                                onlineState = if (isRecentlyActive) "Online" else "Offline"
                            )

                            if (rawQuery.isBlank()) {
                                // Blank query: Suggested Friends mode
                                updatedUser to suggestedScore
                            } else {
                                // Non-blank query: Query Match Weight + Suggested Score Bonus
                                val searchWeight = calculateMatchWeight(user, rawQuery)
                                if (searchWeight > 0) {
                                    val totalScore = searchWeight + suggestedScore
                                    updatedUser to totalScore
                                } else {
                                    null
                                }
                            }
                        }
                        .sortedWith(
                            compareByDescending<Pair<User, Int>> { it.second }
                                .thenBy { it.first.displayName }
                        )
                        .map { it.first }
                }

                rvAdapter.submitList(results)
            } finally {
                binding.lottieView.pause()
                binding.lottieView.visibility = View.GONE
            }
        }
    }

    private fun getSuggestionReason(
        mutualFriendsCount: Int,
        sharedGroupNames: List<String>,
        inContacts: Boolean,
        isRecentlyActive: Boolean
    ): String {
        val reasons = mutableListOf<String>()

        if (mutualFriendsCount > 0) {
            val label = if (mutualFriendsCount == 1) "1 mutual friend" else "$mutualFriendsCount mutual friends"
            reasons.add(label)
        }

        if (sharedGroupNames.isNotEmpty()) {
            reasons.add("Member of ${sharedGroupNames.first()}")
        }

        if (inContacts) {
            reasons.add("In your contacts")
        }

        if (isRecentlyActive && reasons.size < 2) {
            reasons.add("Active recently")
        }

        return if (reasons.isNotEmpty()) {
            reasons.joinToString(" • ")
        } else {
            "Suggested for you"
        }
    }

    private suspend fun fetchUserFriendsMap(userIds: List<String>): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        val map = HashMap<String, HashSet<String>>()
        for (id in userIds) {
            map[id] = HashSet()
        }
        if (userIds.isEmpty()) return@withContext map

        // 1. Fetch user documents to check array field 'friends'
        for (chunk in userIds.chunked(10)) {
            try {
                val docs = db.collection("Users").whereIn(FieldPath.documentId(), chunk).get().await()
                for (doc in docs.documents) {
                    val uId = doc.id
                    val set = map.getOrPut(uId) { HashSet() }
                    (doc.get("friends") as? List<*>)?.forEach { fId -> (fId as? String)?.let { set.add(it) } }
                }
            } catch (_: Exception) {}
        }

        // 2. Fetch Users/$userId/Friends subcollection
        for (uId in userIds) {
            try {
                val subDocs = db.collection("Users").document(uId).collection("Friends").get().await()
                val set = map.getOrPut(uId) { HashSet() }
                for (doc in subDocs.documents) {
                    set.add(doc.id)
                    doc.getString("friendId")?.let { set.add(it) }
                    doc.getString("userId")?.let { set.add(it) }
                }
                set.remove(uId)
            } catch (_: Exception) {}
        }

        map
    }

    private suspend fun fetchAllUsers(): List<User> = withContext(Dispatchers.IO) {
        cachedUsersList?.let { return@withContext it }
        try {
            val snapshot = db.collection("Users").get().await()
            val users = snapshot.documents.mapNotNull { doc ->
                val user = doc.toObject(User::class.java) ?: return@mapNotNull null
                if (user.userId.isEmpty()) {
                    user.userId = doc.id
                }
                user
            }
            cachedUsersList = users
            users
        } catch (_: Exception) {
            cachedUsersList ?: emptyList()
        }
    }

    private fun calculateMatchWeight(user: User, query: String): Int {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 0
        val qClean = q.removePrefix("#")

        val rawUsername = if (user.id.isNotBlank()) user.id else user.email.substringBefore("@")
        val uName = rawUsername.trim().lowercase()
        val uNameClean = uName.removePrefix("#")

        val dName = user.displayName.trim().lowercase()

        var weight = 0

        // Exact username +100
        val isExactUsername = (uName == q) || (uNameClean == qClean)
        if (isExactUsername) {
            weight += 100
        } else if (uName.startsWith(q) || uNameClean.startsWith(qClean)) {
            // Username starts with query +70
            weight += 70
        }

        // Exact display name +60
        val isExactDisplayName = (dName == q)
        if (isExactDisplayName) {
            weight += 60
        } else if (dName.startsWith(q)) {
            // Display name starts with query +40
            weight += 40
        }

        // Contains query +20
        val containsQuery = uName.contains(q) || uNameClean.contains(qClean) || dName.contains(q)
        if (containsQuery) {
            weight += 20
        }

        return weight
    }

    private suspend fun getExcludedUserIds(currentUserId: String): Set<String> = withContext(Dispatchers.IO) {
        cachedExcludedUserIds?.let { return@withContext it }
        val excluded = HashSet<String>()
        if (currentUserId.isNotEmpty()) {
            excluded.add(currentUserId)
        } else {
            return@withContext excluded
        }

        // Blocked users
        try {
            val userDoc = db.collection("Users").document(currentUserId).get().await()
            if (userDoc.exists()) {
                (userDoc.get("blocked") as? List<*>)?.forEach { id -> (id as? String)?.let { excluded.add(it) } }
                (userDoc.get("blockedUsers") as? List<*>)?.forEach { id -> (id as? String)?.let { excluded.add(it) } }
            }
        } catch (_: Exception) {}

        try {
            val userBlocked = db.collection("Users").document(currentUserId).collection("Blocked").get().await()
            for (doc in userBlocked.documents) {
                excluded.add(doc.id)
                doc.getString("blockedUserId")?.let { excluded.add(it) }
                doc.getString("userId")?.let { excluded.add(it) }
            }
        } catch (_: Exception) {}

        try {
            val topBlocked = db.collection("BlockedUsers").whereEqualTo("userId", currentUserId).get().await()
            for (doc in topBlocked.documents) {
                doc.getString("blockedUserId")?.let { excluded.add(it) }
            }
        } catch (_: Exception) {}

        cachedExcludedUserIds = excluded
        excluded
    }

    override fun onDestroyView() {
        super.onDestroyView()
        presenceListener?.let { presenceRef.removeEventListener(it) }
        presenceListener = null
        requestsListener?.remove()
        requestsListener = null
        friendsListener?.remove()
        friendsListener = null
        searchJob?.cancel()
        searchJob = null
    }
}
