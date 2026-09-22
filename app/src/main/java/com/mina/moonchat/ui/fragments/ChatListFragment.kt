package com.mina.moonchat.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mina.moonchat.adapters.ChatsAdapter
import com.mina.moonchat.base.BaseFragment
import com.mina.moonchat.databinding.FragmentChatListBinding
import com.mina.moonchat.models.User
import com.mina.moonchat.viewmodels.ChatListViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class ChatListFragment : BaseFragment() {
    private var _binding: FragmentChatListBinding? = null
    private val binding get() = _binding!!
    private lateinit var chatsAdapter: ChatsAdapter

    override val _viewModel: ChatListViewModel by inject()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chatsAdapter = ChatsAdapter { chatItem, sharedViews ->
            val extras = FragmentNavigatorExtras(
                sharedViews[0] to "chat_profile_image_transition",
                sharedViews[1] to "chat_online_indicator_transition",
                sharedViews[2] to "chat_username_transition"
            )

            val user = User(
                userId = chatItem.recipientId,
                displayName = chatItem.username,
                email = "",
                password = "",
                profileImg = chatItem.profileImg,
                onlineState = if (chatItem.onlineState) "Online" else "Offline",
                bio = "",
                id = chatItem.recipientId
            )

            findNavController().navigate(
                MainScreenFragmentDirections.actionMainScreenFragmentToChatFragment(user),
                extras
            )
        }

        binding.chatRecyclerView.apply {
            adapter = chatsAdapter
            layoutManager = LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)
            setHasFixedSize(true)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                _viewModel.chatItems.collectLatest { chats ->
                    chatsAdapter.submitList(chats)
                }
            }
        }
    }

    override fun onDestroyView() {
        binding.chatRecyclerView.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
