package com.mina.moonchat.ui.activities

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.content.Intent
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
        val senderId = intent?.getStringExtra("senderId") ?: return
        val senderName = intent.getStringExtra("senderName") ?: ""

        // Clear extra so it doesn't re-trigger on config changes
        intent.removeExtra("senderId")

        firestore.collection("Users").document(senderId).get()
            .addOnSuccessListener { doc ->
                val user = doc.toObject(User::class.java) ?: User(
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
        startPresenceTracking()
        (application as? MoonChat)?.repo?.startGlobalIncomingMessageListener()
    }

    override fun onStop() {
        setCurrentUserPresence("Offline")
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
        firestore.collection("Users").document(currentUserId)
            .set(mapOf("fcmToken" to token), com.google.firebase.firestore.SetOptions.merge())
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
                    mapOf("onlineState" to "Online"),
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
            mapOf("onlineState" to state),
            com.google.firebase.firestore.SetOptions.merge()
        )
    }
}
