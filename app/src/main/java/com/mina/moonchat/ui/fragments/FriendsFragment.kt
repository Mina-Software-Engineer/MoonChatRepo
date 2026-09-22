package com.mina.moonchat.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.mina.moonchat.adapters.FriendListItem
import com.mina.moonchat.adapters.FriendsAdapter
import com.mina.moonchat.adapters.SearchListener
import com.mina.moonchat.base.BaseFragment
import com.mina.moonchat.databinding.FragmentFriendsBinding
import com.mina.moonchat.models.User
import com.mina.moonchat.viewmodels.FriendsViewModel
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
    private var presenceListener: ValueEventListener? = null

    private var cachedUsers: List<User> = emptyList()
    private var userOnlineStates: Map<String, Boolean> = emptyMap()

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
            SearchListener { user ->
                this.findNavController()
                    .navigate(
                        MainScreenFragmentDirections
                            .actionMainScreenFragmentToChatFragment(user)
                    )
            },
            onAddFriendClicked = {
                this.findNavController().navigate(
                    MainScreenFragmentDirections.actionMainScreenFragmentToAddFriendsFragment()
                )
            }
        )

        binding.friendsRecyclerView.apply {
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(requireActivity())
            this.adapter = rvAdapter
        }

        observeUsersPresence()
        fetchFriends()
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

    private fun updateFriendsList() {
        val currentUserId = mAuth.currentUser?.uid

        // Filter out current user and resolve online status from Realtime Database presence
        val friends = cachedUsers.filter { user ->
            val uId = user.userId.ifEmpty { user.id }
            uId != currentUserId && user.userId != currentUserId
        }.map { user ->
            val uId = user.userId.ifEmpty { user.id }
            val isOnline = userOnlineStates[uId] ?: userOnlineStates[user.userId] ?: user.onlineState.equals("Online", ignoreCase = true)
            user.copy(onlineState = if (isOnline) "Online" else "Offline")
        }

        val (onlineFriends, offlineFriends) = friends.partition { it.onlineState.equals("Online", ignoreCase = true) }

        val listItems = ArrayList<FriendListItem>(friends.size + 2)

        if (onlineFriends.isNotEmpty()) {
            listItems.add(FriendListItem.Header("Online", true))
            onlineFriends.mapTo(listItems) { FriendListItem.Friend(it) }
        }

        if (offlineFriends.isNotEmpty()) {
            listItems.add(FriendListItem.Header("Offline", false))
            offlineFriends.mapTo(listItems) { FriendListItem.Friend(it) }
        }

        rvAdapter.submitList(listItems)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        firestoreListener?.remove()
        firestoreListener = null
        presenceListener?.let { presenceRef.removeEventListener(it) }
        presenceListener = null
    }
}
