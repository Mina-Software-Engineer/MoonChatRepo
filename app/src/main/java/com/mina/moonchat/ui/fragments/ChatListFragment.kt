package com.mina.moonchat.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mina.moonchat.adapters.ChatsAdapter
import com.mina.moonchat.base.BaseFragment
import com.mina.moonchat.databinding.FragmentChatListBinding
import com.mina.moonchat.viewmodels.ChatListViewModel
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

        chatsAdapter = ChatsAdapter { chatItem ->
            _viewModel.openChat(chatItem.recipientId)
        }

        binding.chatRecyclerView.apply {
            adapter = chatsAdapter
            layoutManager = LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)
            setHasFixedSize(true)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                _viewModel.chatItems.collect { chats ->
                    chatsAdapter.submitList(chats)
                }
            }
        }

        _viewModel.selectedChatUser.observe(viewLifecycleOwner) { user ->
            user ?: return@observe
            findNavController().navigate(
                MainScreenFragmentDirections.actionMainScreenFragmentToChatFragment(user)
            )
            _viewModel.onChatNavigated()
        }
    }

    override fun onDestroyView() {
        binding.chatRecyclerView.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
