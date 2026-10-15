package com.mina.moonchat.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mina.moonchat.adapters.ChatsAdapter
import com.mina.moonchat.base.BaseFragment
import com.mina.moonchat.databinding.FragmentChatListBinding
import com.mina.moonchat.models.User
import com.mina.moonchat.models.ChatListBodyState
import com.mina.moonchat.models.ChatListLoadState
import com.mina.moonchat.models.resolveChatListBodyState
import com.mina.moonchat.viewmodels.ChatListViewModel
import com.mina.moonchat.viewmodels.ChatListUiState
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

        binding.chatSearchInput.setText(_viewModel.searchQuery.value)
        binding.chatSearchInput.doAfterTextChanged { text ->
            _viewModel.setSearchQuery(text?.toString().orEmpty())
        }
        binding.clearSearchButton.setOnClickListener { binding.chatSearchInput.text?.clear() }
        binding.clearSearchEmptyButton.setOnClickListener { binding.chatSearchInput.text?.clear() }
        binding.chatListRetryButton.setOnClickListener { _viewModel.retryLoadingChats() }
        binding.addChatBtn.setOnClickListener {
            findNavController().navigate(
                MainScreenFragmentDirections.actionMainScreenFragmentToAddFriendsFragment()
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                _viewModel.uiState.collectLatest { state ->
                    if (binding.chatSearchInput.text?.toString() != state.query) {
                        binding.chatSearchInput.setText(state.query)
                        binding.chatSearchInput.setSelection(state.query.length)
                    }
                    chatsAdapter.submitList(state.conversations)
                    renderChatListState(state)
                }
            }
        }
    }

    private fun renderChatListState(state: ChatListUiState) {
        val bodyState = resolveChatListBodyState(
            loadState = state.loadState,
            allConversationCount = state.allConversationCount,
            visibleConversationCount = state.conversations.size,
            query = state.query
        )
        val hasConversations = state.conversations.isNotEmpty()

        binding.chatRecyclerView.isVisible = hasConversations
        binding.chatListProgress.isVisible = bodyState == ChatListBodyState.LOADING
        binding.chatListEmptyText.isVisible = bodyState != ChatListBodyState.CONTENT &&
            bodyState != ChatListBodyState.LOADING
        binding.clearSearchEmptyButton.isVisible = bodyState == ChatListBodyState.NO_SEARCH_RESULTS
        binding.clearSearchButton.isVisible = state.query.isNotBlank()

        binding.chatListEmptyText.text = when (bodyState) {
            ChatListBodyState.NO_CONVERSATIONS -> getString(com.mina.moonchat.R.string.no_conversations_yet)
            ChatListBodyState.NO_SEARCH_RESULTS -> getString(
                com.mina.moonchat.R.string.no_search_results,
                state.query.trim()
            )
            ChatListBodyState.OFFLINE_EMPTY -> getString(com.mina.moonchat.R.string.chat_list_offline_empty)
            ChatListBodyState.ERROR -> getString(com.mina.moonchat.R.string.chat_list_load_failed)
            ChatListBodyState.LOADING -> getString(com.mina.moonchat.R.string.chat_list_loading)
            ChatListBodyState.CONTENT -> ""
        }

        val showStatus = state.loadState == ChatListLoadState.OFFLINE_WITH_CACHE ||
            state.loadState == ChatListLoadState.ERROR
        binding.chatListStatusContainer.isVisible = showStatus
        binding.chatListStatus.text = when (state.loadState) {
            ChatListLoadState.OFFLINE_WITH_CACHE -> getString(com.mina.moonchat.R.string.chat_list_offline_cached)
            ChatListLoadState.ERROR -> getString(com.mina.moonchat.R.string.chat_list_load_failed)
            else -> ""
        }
        binding.chatListRetryButton.isVisible = showStatus
    }

    override fun onDestroyView() {
        binding.chatRecyclerView.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
