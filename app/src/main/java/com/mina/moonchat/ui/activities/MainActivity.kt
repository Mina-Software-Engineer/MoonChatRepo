package com.mina.moonchat.ui.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.mina.moonchat.R
import com.mina.moonchat.application.MoonChat
import com.mina.moonchat.databinding.ActivityMainBinding
import com.mina.moonchat.models.User
import com.mina.moonchat.models.toNormalizedUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val mAuth: FirebaseAuth by lazy {
        FirebaseAuth.getInstance()
    }
    private val connectedRef: DatabaseReference by lazy {
        FirebaseDatabase.getInstance().getReference(".info/connected")
    }
    private val presenceRootRef: DatabaseReference by lazy {
        FirebaseDatabase.getInstance().getReference("Users states")
    }
    private val firestore: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance()
    }
    private var connectionListener: ValueEventListener? = null

    private lateinit var binding: ActivityMainBinding

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.d("FCM", "Notification permission granted")
                fetchAndStoreFcmToken()
            } else {
                Log.d("FCM", "Notification permission denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (mAuth.currentUser?.isEmailVerified != true) {
            startActivity(Intent(this, IntroActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkNotificationPermission()
        handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return

        val navTarget = intent.getStringExtra("navTarget")
        if (!navTarget.isNullOrBlank()) {
            intent.removeExtra("navTarget")
            if (navTarget == "friends") {
                navigateToFriends()
                return
            }
        }

        val senderId = intent.getStringExtra("senderId")
        if (senderId.isNullOrBlank()) return

        val senderName = intent.getStringExtra("senderName") ?: ""

        // Clear intent extras immediately so activity recreation does not re-trigger navigation
        intent.removeExtra("senderId")
        intent.removeExtra("senderName")
        intent.removeExtra("type")

        val currentUserId = mAuth.currentUser?.uid
        if (currentUserId == null || senderId == currentUserId) return

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.fragmentContainerView3) as? NavHostFragment ?: return
        val navController = navHostFragment.navController

        // Prevent duplicate navigation if already on the chat screen for this exact sender
        if (navController.currentDestination?.id == R.id.chatFragment) {
            val currentArgUser = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                navController.currentBackStackEntry?.arguments?.getParcelable("user", User::class.java)
            } else {
                @Suppress("DEPRECATION")
                navController.currentBackStackEntry?.arguments?.getParcelable("user")
            }
            val currentChatSenderId = currentArgUser?.userId?.ifEmpty { currentArgUser.id }
            if (currentChatSenderId == senderId) {
                Log.d("MainActivity", "Already on chatFragment for senderId: $senderId, skipping duplicate navigation")
                return
            }
        }

        firestore.collection("Users").document(senderId).get()
            .addOnSuccessListener { doc ->
                val user = doc.toNormalizedUser() ?: User(
                    userId = senderId,
                    displayName = senderName,
                    id = senderId
                )
                navigateToChat(user)
            }
            .addOnFailureListener {
                val user = User(
                    userId = senderId,
                    displayName = senderName,
                    id = senderId
                )
                navigateToChat(user)
            }
    }

    private fun navigateToFriends() {
        try {
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.fragmentContainerView3) as? NavHostFragment ?: return
            val navController = navHostFragment.navController
            if (navController.currentDestination?.id != R.id.addFriendsFragment) {
                navController.navigate(R.id.addFriendsFragment)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error navigating to friends: ${e.message}")
        }
    }

    private fun navigateToChat(user: User) {
        try {
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.fragmentContainerView3) as? NavHostFragment ?: return
            val navController = navHostFragment.navController

            val bundle = Bundle().apply {
                putParcelable("user", user)
            }
            navController.navigate(R.id.chatFragment, bundle)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error navigating to chat fragment: ${e.message}")
        }
    }

    override fun onStart() {
        super.onStart()
        if (mAuth.currentUser?.isEmailVerified != true) {
            if (!isFinishing) {
                startActivity(Intent(this, IntroActivity::class.java))
                finish()
            }
            return
        }

        startPresenceTracking()
        val repository = (application as? MoonChat)?.repo
        repository?.startGlobalIncomingMessageListener()
        repository?.startNetworkObserver()
        lifecycleScope.launch(Dispatchers.IO) {
            repository?.syncPendingMessages()
        }
    }

    override fun onStop() {
        setCurrentUserPresence("Offline")
        (application as? MoonChat)?.repo?.stopNetworkObserver()
        connectionListener?.let { connectedRef.removeEventListener(it) }
        connectionListener = null
        super.onStop()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                fetchAndStoreFcmToken()
            }
        } else {
            fetchAndStoreFcmToken()
        }
    }

    private fun fetchAndStoreFcmToken() {
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w("FCM", "Fetching FCM registration token failed", task.exception)
                    return@addOnCompleteListener
                }
                val token = task.result
                Log.d("FCM", "FCM Registration TOKEN: $token")
                updateFcmToken(token)
            }
    }

    private fun updateFcmToken(token: String) {
        val currentUserId = mAuth.currentUser?.uid ?: return
        val updates = mapOf(
            "fcmToken" to token,
            "token" to token
        )
        firestore.collection("Users").document(currentUserId)
            .set(updates, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener {
                Log.d("FCM", "FCM Token updated in Firestore")
            }
    }

    private fun startPresenceTracking() {
        val currentUserId = mAuth.currentUser?.uid ?: return
        val userPresenceRef = presenceRootRef.child(currentUserId)

        if (connectionListener != null) return

        connectionListener = object : ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) == true
                if (!connected) {
                    setCurrentUserPresence("Offline")
                    return
                }

                val offlineState = mapOf(
                    "state" to "Offline",
                    "lastChanged" to ServerValue.TIMESTAMP
                )
                val onlineState = mapOf(
                    "state" to "Online",
                    "lastChanged" to ServerValue.TIMESTAMP
                )

                userPresenceRef.onDisconnect().setValue(offlineState)
                userPresenceRef.setValue(onlineState)
                firestore.document("Users/$currentUserId").set(
                    mapOf("onlineState" to "Online", "onlineStatus" to "Online"),
                    com.google.firebase.firestore.SetOptions.merge()
                )
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) = Unit
        }

        connectedRef.addValueEventListener(connectionListener!!)
    }

    private fun setCurrentUserPresence(state: String) {
        val currentUserId = mAuth.currentUser?.uid ?: return
        val presenceState = mapOf(
            "state" to state,
            "lastChanged" to ServerValue.TIMESTAMP
        )

        presenceRootRef.child(currentUserId).setValue(presenceState)
        firestore.document("Users/$currentUserId").set(
            mapOf("onlineState" to state, "onlineStatus" to state),
            com.google.firebase.firestore.SetOptions.merge()
        )
    }
}
