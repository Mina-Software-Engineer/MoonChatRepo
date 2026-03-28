package com.mina.moonchat.ui.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.mina.moonchat.adapters.MessageAdapter
import com.mina.moonchat.base.BaseFragment
import com.mina.moonchat.databinding.FragmentChatBinding
import com.mina.moonchat.viewmodels.ChatViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Improved ChatFragment with:
 * - Proper lifecycle management
 * - Error handling
 * - Loading states
 * - Auto-scroll to bottom for new messages
 */
class ChatFragment : BaseFragment() {

    companion object {
        private const val TAG = "ChatFragment"
    }

    private lateinit var binding: FragmentChatBinding
    override val _viewModel: ChatViewModel by inject()
    private val mAuth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private lateinit var messageAdapter: MessageAdapter
    private var shouldScrollToBottom = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentChatBinding.inflate(inflater, container, false)
        binding.lifecycleOwner = viewLifecycleOwner
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recipientUser = ChatFragmentArgs.fromBundle(requireArguments()).user
        _viewModel.username.value = recipientUser.displayName

        setupRecyclerView()
        setupObservers()
        setupClickListeners(recipientUser)

        // Load messages
        loadMessages(recipientUser.userId)

        // Mark messages as read when opening chat
        _viewModel.markMessagesAsRead(recipientUser.userId)

        // Sync any pending messages
        _viewModel.syncPendingMessages()
    }

    private fun setupRecyclerView() {
        val currentUserId = mAuth.currentUser?.uid ?: return

        messageAdapter = MessageAdapter(currentUserId)

        binding.chatRecyclerView.apply {
            adapter = messageAdapter
            layoutManager = LinearLayoutManager(requireContext()).apply {
                reverseLayout = true // Newest messages at bottom
                stackFromEnd = false
            }
            itemAnimator = null // Disable animations for smoother paging
        }

        // Handle load states for scroll behavior
        viewLifecycleOwner.lifecycleScope.launch {
            messageAdapter.loadStateFlow.collectLatest { loadStates ->
                when (val refresh = loadStates.refresh) {
                    is LoadState.Loading -> {
                        binding.progressBar.isVisible = true
                        binding.chatRecyclerView.isVisible = false
                    }
                    is LoadState.NotLoading -> {
                        binding.progressBar.isVisible = false
                        binding.chatRecyclerView.isVisible = true

                        // Scroll to bottom on initial load
                        if (shouldScrollToBottom && messageAdapter.itemCount > 0) {
                            scrollToBottom()
                            shouldScrollToBottom = false
                        }
                    }
                    is LoadState.Error -> {
                        binding.progressBar.isVisible = false
                        binding.chatRecyclerView.isVisible = true
                        Toast.makeText(
                            requireContext(),
                            "Error loading messages: ${refresh.error.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun setupObservers() {
        // Observe error messages
        _viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                _viewModel.clearError()
            }
        }

        // Observe sending state
        _viewModel.isSending.observe(viewLifecycleOwner) { isSending ->
            binding.sendBtn.isEnabled = !isSending
            binding.sendBtn.alpha = if (isSending) 0.5f else 1.0f
        }
    }

    private fun setupClickListeners(recipientUser: User) {
        // Send button
        binding.sendBtn.setOnClickListener {
            val messageText = binding.textViewMessageBox.text?.toString()?.trim()
            if (!messageText.isNullOrEmpty()) {
                _viewModel.onSendClicked(recipientUser, messageText)
                binding.textViewMessageBox.text?.clear()
                scrollToBottom()
            }
        }

        // Back button
        binding.toolbarBackBtn.setOnClickListener {
            findNavController().navigateUp()
        }

        // Retry button for failed messages (if you have one in your layout)
        // binding.retryBtn.setOnClickListener { ... }
    }

    private fun loadMessages(recipientId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                _viewModel.getMessageStream(recipientId).collectLatest { pagingData ->
                    messageAdapter.submitData(pagingData)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting messages: ${e.message}")
                Toast.makeText(
                    requireContext(),
                    "Error loading messages",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun scrollToBottom() {
        if (messageAdapter.itemCount > 0) {
            binding.chatRecyclerView.post {
                binding.chatRecyclerView.scrollToPosition(0) // Position 0 is bottom with reverseLayout
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop real-time sync when leaving the fragment
        _viewModel.stopSync()
    }

    override fun onResume() {
        super.onResume()
        // Restart sync when coming back
        val recipientUser = try {
            ChatFragmentArgs.fromBundle(requireArguments()).user
        } catch (e: Exception) {
            null
        }
        recipientUser?.let {
            shouldScrollToBottom = false // Don't scroll on resume
            loadMessages(it.userId)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _viewModel.stopSync()
    }
}
