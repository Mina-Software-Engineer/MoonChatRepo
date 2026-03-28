package com.mina.moonchat.ui.activities


import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.mina.moonchat.adapters.ViewPagerAdapter
import com.mina.moonchat.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ViewPagerAdapter

    private val mAuth: FirebaseAuth by lazy {       //instead of typing val mAuth: FirebaseAuth? = null
        FirebaseAuth.getInstance()                  //mAuth = FirebaseAuth.getInstance()
    }

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
}