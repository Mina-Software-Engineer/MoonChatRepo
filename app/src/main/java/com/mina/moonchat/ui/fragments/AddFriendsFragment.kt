package com.mina.moonchat.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
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
    private var userOnlineStates: Map<String, Boolean> = emptyMap()
    private var searchJob: Job? = null
    private var cachedUsersList: List<User>? = null
    private var cachedExcludedUserIds: Set<String>? = null

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

        rvAdapter = SearchFriendListAdapter(SearchListener { user ->
            this.findNavController()
                .navigate(AddFriendsFragmentDirections.actionAddFriendsFragmentToChatFragment(user))
        })

        binding.searchResultsRecyclerView.apply {
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(requireActivity())
            this.adapter = rvAdapter
        }

        setupLottieAnimation()
        observeUsersPresence()
        setupSearch()
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

                // 1. Fetch excluded user IDs (current user, existing friends, blocked users, pending requests)
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

        // 1. Check currentUser document for array fields (friends, blocked, pendingRequests)
        try {
            val userDoc = db.collection("Users").document(currentUserId).get().await()
            if (userDoc.exists()) {
                (userDoc.get("friends") as? List<*>)?.forEach { id -> (id as? String)?.let { excluded.add(it) } }
                (userDoc.get("blocked") as? List<*>)?.forEach { id -> (id as? String)?.let { excluded.add(it) } }
                (userDoc.get("blockedUsers") as? List<*>)?.forEach { id -> (id as? String)?.let { excluded.add(it) } }
                (userDoc.get("pendingRequests") as? List<*>)?.forEach { id -> (id as? String)?.let { excluded.add(it) } }
            }
        } catch (_: Exception) {}

        // 2. Friends subcollections & collections
        try {
            val userFriends = db.collection("Users").document(currentUserId).collection("Friends").get().await()
            for (doc in userFriends.documents) {
                excluded.add(doc.id)
                doc.getString("friendId")?.let { excluded.add(it) }
                doc.getString("userId")?.let { excluded.add(it) }
            }
        } catch (_: Exception) {}

        try {
            val topFriends1 = db.collection("Friends").whereEqualTo("userId", currentUserId).get().await()
            for (doc in topFriends1.documents) {
                doc.getString("friendId")?.let { excluded.add(it) }
            }
            val topFriends2 = db.collection("Friends").whereEqualTo("friendId", currentUserId).get().await()
            for (doc in topFriends2.documents) {
                doc.getString("userId")?.let { excluded.add(it) }
            }
        } catch (_: Exception) {}

        // 3. Blocked users subcollections & collections
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

        // 4. Pending Requests (Sender or Receiver)
        try {
            val requestsSender = db.collection("FriendRequests")
                .whereEqualTo("senderId", currentUserId)
                .get().await()
            for (doc in requestsSender.documents) {
                val status = doc.getString("status") ?: "pending"
                if (status.equals("pending", ignoreCase = true)) {
                    doc.getString("receiverId")?.let { excluded.add(it) }
                    doc.getString("targetUserId")?.let { excluded.add(it) }
                }
            }

            val requestsReceiver = db.collection("FriendRequests")
                .whereEqualTo("receiverId", currentUserId)
                .get().await()
            for (doc in requestsReceiver.documents) {
                val status = doc.getString("status") ?: "pending"
                if (status.equals("pending", ignoreCase = true)) {
                    doc.getString("senderId")?.let { excluded.add(it) }
                }
            }

            val userRequests = db.collection("Users").document(currentUserId).collection("Requests").get().await()
            for (doc in userRequests.documents) {
                excluded.add(doc.id)
                doc.getString("userId")?.let { excluded.add(it) }
                doc.getString("senderId")?.let { excluded.add(it) }
                doc.getString("receiverId")?.let { excluded.add(it) }
            }
        } catch (_: Exception) {}

        cachedExcludedUserIds = excluded
        excluded
    }

    override fun onDestroyView() {
        super.onDestroyView()
        presenceListener?.let { presenceRef.removeEventListener(it) }
        presenceListener = null
        searchJob?.cancel()
        searchJob = null
    }
}
