package com.mina.moonchat.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.mina.moonchat.ui.fragments.ChatListFragment
import com.mina.moonchat.ui.fragments.FriendsFragment
import com.mina.moonchat.ui.fragments.ProfileFragment

class ViewPagerAdapter(
    fragmentManager: FragmentManager,
    lifecycle: Lifecycle
): FragmentStateAdapter( fragmentManager, lifecycle) {
    override fun getItemCount(): Int {
        return 3
    }

    override fun createFragment(position: Int): Fragment {
        return when(position){
            0 -> ChatListFragment()
            1 -> FriendsFragment()
            2 -> ProfileFragment()

            else -> ChatListFragment()
        }
            /*ProfileFragment()
        else if(position == 1)
            ChatListFragment()
        else
            ChatListFragment()*/
    }
}