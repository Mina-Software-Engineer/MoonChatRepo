package com.mina.moonchat.ui.activities


import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.mina.moonchat.adapters.ViewPagerAdapter
import com.mina.moonchat.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private val mAuth: FirebaseAuth by lazy {       //instead of typing val mAuth: FirebaseAuth? = null
        FirebaseAuth.getInstance()                  //mAuth = FirebaseAuth.getInstance()
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        /*val tabLayout = binding.tabLayout
        val viewPager = binding.viewPager2

        adapter = MainScreenAdapter(supportFragmentManager, lifecycle)

        tabLayout.addTab(tabLayout.newTab().setText("Chats"))
        tabLayout.addTab(tabLayout.newTab().setText("Friends"))
        tabLayout.addTab(tabLayout.newTab().setText("Profile"))



        viewPager.adapter = adapter

        *//*tabLayout.setScrollPosition(1, 0F, true)
        viewPager.currentItem = 1*//*

        tabLayout.addOnTabSelectedListener(object : OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                viewPager.currentItem = tab!!.position
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {

            }

            override fun onTabReselected(tab: TabLayout.Tab?) {

            }
        })

        viewPager.registerOnPageChangeCallback(object : OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                tabLayout.selectTab(tabLayout.getTabAt(position))
            }
        })*/

        /*binding.signOut.setOnClickListener{
            mAuth.signOut()
            Toast.makeText(this, "User is is signed out successfully", Toast.LENGTH_LONG).show()
            val intentToIntro = Intent(this@MainActivity, IntroActivity::class.java)
            startActivity(intentToIntro)
            finish()
        }*/
    }

    override fun onStart() {
        super.onStart()
        startPresenceTracking()
    }

    override fun onStop() {
        setCurrentUserPresence("Offline")
        connectionListener?.let { connectedRef.removeEventListener(it) }
        connectionListener = null
        super.onStop()
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
