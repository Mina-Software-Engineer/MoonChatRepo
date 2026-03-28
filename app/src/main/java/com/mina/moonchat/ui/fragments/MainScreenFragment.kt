package com.mina.moonchat.ui.fragments

import android.content.res.Resources
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.mina.moonchat.R
import com.mina.moonchat.adapters.ViewPagerAdapter
import com.mina.moonchat.databinding.FragmentMainScreenBinding

class MainScreenFragment : Fragment() {


    private lateinit var _binding: FragmentMainScreenBinding
    val binding get() = _binding

    private lateinit var adapter: ViewPagerAdapter

    /*private val mAuth: FirebaseAuth by lazy {       //instead of typing val mAuth: FirebaseAuth? = null
        FirebaseAuth.getInstance()                  //mAuth = FirebaseAuth.getInstance()
    }*/

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        _binding = FragmentMainScreenBinding.inflate(inflater, container, false)

        val tabLayout = binding.tabLayout
        val viewPager = binding.viewPager2

        adapter = ViewPagerAdapter(requireActivity().supportFragmentManager, lifecycle)

        tabLayout.addTab(tabLayout.newTab().setText("Chats"))
        tabLayout.addTab(tabLayout.newTab().setText("Friends"))
        tabLayout.addTab(tabLayout.newTab().setText("Profile"))



        viewPager.adapter = adapter

        /*tabLayout.setScrollPosition(1, 0F, true)
        viewPager.currentItem = 1*/

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                viewPager.currentItem = tab!!.position
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {

            }

            override fun onTabReselected(tab: TabLayout.Tab?) {

            }
        })
        val customFont = ResourcesCompat.getFont(requireContext(), R.font.comic_neue_bold)
        for (i in 0 until tabLayout.tabCount) {
            val tab = (tabLayout.getChildAt(0) as ViewGroup).getChildAt(i) as ViewGroup
            for (j in 0 until tab.childCount) {
                val tabView = tab.getChildAt(j)
                if (tabView is TextView) {
                    tabView.setPadding(15.dpToPx(), 0, 15.dpToPx(), 0) // Add 5dp horizontal padding
                    tabView.typeface = customFont
                }
            }
        }




        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                tabLayout.selectTab(tabLayout.getTabAt(position))
            }
        })
        return binding.root
    }
    // Extension function to convert dp to px:
    fun Int.dpToPx(): Int = (this * Resources.getSystem().displayMetrics.density).toInt()
}