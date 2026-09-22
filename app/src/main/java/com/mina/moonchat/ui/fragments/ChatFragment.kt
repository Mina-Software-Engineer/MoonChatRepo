package com.mina.moonchat.ui.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionInflater
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.mina.moonchat.R
import com.mina.moonchat.adapters.MessageAdapter
import com.mina.moonchat.base.BaseFragment
import com.mina.moonchat.databinding.FragmentChatBinding
import com.mina.moonchat.models.User
import com.mina.moonchat.utils.ActiveChatManager
import com.mina.moonchat.utils.NotificationHelper
import com.mina.moonchat.viewmodels.ChatViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject


class ChatFragment : BaseFragment() {

    companion object {
        private const val TAG = "ChatFragment"
    }

    private lateinit var binding: FragmentChatBinding
    override val _viewModel: ChatViewModel by inject()
    private val mAuth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private lateinit var messageAdapter: MessageAdapter
    private lateinit var chatLayoutManager: LinearLayoutManager
    private var messagesJob: Job? = null
    private var shouldScrollToBottom = true
    private var pendingBottomScroll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedElementEnterTransition = TransitionInflater.from(requireContext())
            .inflateTransition(android.R.transition.move)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentChatBinding.inflate(inflater, container, false)
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewmodel = _viewModel
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Postpone transition until image is loaded
        postponeEnterTransition()

        val recipientUser = ChatFragmentArgs.fromBundle(requireArguments()).user
        _viewModel.username.value = recipientUser.displayName
        _viewModel.onlineStatus.value = recipientUser.onlineState
        _viewModel.observeRecipientPresence(recipientUser.userId)

        Glide.with(binding.root)
            .load(recipientUser.profileImg)
            .placeholder(R.drawable.ic_account_circle)
            .error(R.drawable.ic_account_circle)
            .listener(object :
                com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: com.bumptech.glide.load.engine.GlideException?,
                    model: Any?,
                    target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                    isFirstResource: Boolean
                ): Boolean {
                    startPostponedEnterTransition()
                    return false
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable?,
                    model: Any?,
                    target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                    dataSource: com.bumptech.glide.load.DataSource?,
                    isFirstResource: Boolean
                ): Boolean {
                    startPostponedEnterTransition()
                    return false
                }
            })
            .into(binding.chatPfpImageView)


        setupRecyclerView()
        setupObservers()
        setupClickListeners(recipientUser)

        // Load messages
        loadMessages(recipientUser.userId)

        // Clear notification unread stack for this sender
        NotificationHelper.clearUnreadForSender(recipientUser.userId)

        // Mark messages as read when opening chat
        _viewModel.markMessagesAsRead(recipientUser.userId)

        // Sync any pending messages
        _viewModel.syncPendingMessages()
    }

    private fun setupRecyclerView() {
        val currentUserId = mAuth.currentUser?.uid ?: return

        messageAdapter = MessageAdapter(currentUserId)
        chatLayoutManager = LinearLayoutManager(requireContext()).apply {
            reverseLayout = true // Newest messages at bottom
            stackFromEnd = false
        }

        binding.chatRecyclerView.apply {
            adapter = messageAdapter
            layoutManager = chatLayoutManager
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
                            requestScrollToBottom(force = true)
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

        viewLifecycleOwner.lifecycleScope.launch {
            messageAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    if (positionStart == 0) {
                        if (isNearBottom() || pendingBottomScroll) {
                            binding.chatRecyclerView.post {
                                binding.chatRecyclerView.smoothScrollToPosition(0)
                                pendingBottomScroll = false
                            }
                        }
                    }
                }
            })
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

        // Observe text changes for typing state
        _viewModel.textMessage.observe(viewLifecycleOwner) { text ->
            _viewModel.onTextChanged(text.orEmpty())
        }
    }

    private fun setupClickListeners(recipientUser: User) {
        // Emoji button
        binding.emojiBtn.setOnClickListener {
            if (binding.emojiPicker.visibility == View.VISIBLE) {
                binding.emojiPicker.visibility = View.GONE
            } else {
                hideKeyboard()
                binding.emojiPicker.visibility = View.VISIBLE
            }
        }

        // Set emoji picker listener
        binding.emojiPicker.setOnEmojiPickedListener {
            binding.textViewMessageBox.append(it.emoji)
        }

        // Hide emoji picker when text input is clicked
        binding.textViewMessageBox.setOnClickListener {
            binding.emojiPicker.visibility = View.GONE
        }

        // Hide emoji picker when text input is focused
        binding.textViewMessageBox.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.emojiPicker.visibility = View.GONE
            }
        }

        // Send button
        binding.sendBtn.setOnClickListener {
            val messageText = binding.textViewMessageBox.text?.toString()?.trim()
            if (!messageText.isNullOrEmpty()) {
                requestScrollToBottom(force = true)
                _viewModel.onSendClicked(recipientUser, messageText)
                binding.textViewMessageBox.text?.clear()
            }
        }

        // Back button
        binding.toolbarBackBtn.setOnClickListener {
            if (binding.emojiPicker.visibility == View.VISIBLE) {
                binding.emojiPicker.visibility = View.GONE
            } else {
                findNavController().navigateUp()
            }
        }

        // Retry button for failed messages (if you have one in your layout)
        // binding.retryBtn.setOnClickListener { ... }
    }

    private fun loadMessages(recipientId: String) {
        messagesJob?.cancel()
        messagesJob = viewLifecycleOwner.lifecycleScope.launch {
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

    private fun isNearBottom(): Boolean {
        return chatLayoutManager.findFirstVisibleItemPosition() <= 1
    }

    private fun requestScrollToBottom(force: Boolean = false) {
        if (force || isNearBottom()) {
            pendingBottomScroll = true
        }
    }

    private fun hideKeyboard() {
        val imm =
            requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    override fun onStart() {
        super.onStart()
        try {
            val recipientUser = ChatFragmentArgs.fromBundle(requireArguments()).user
            ActiveChatManager.activeRecipientId = recipientUser.userId
        } catch (e: Exception) {
            Log.e(TAG, "Error setting activeRecipientId in onStart: ${e.message}")
        }
    }

    override fun onStop() {
        try {
            val recipientUser = ChatFragmentArgs.fromBundle(requireArguments()).user
            if (ActiveChatManager.activeRecipientId == recipientUser.userId) {
                ActiveChatManager.activeRecipientId = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing activeRecipientId in onStop: ${e.message}")
        }
        super.onStop()
    }

    override fun onDestroyView() {
        messagesJob?.cancel()
        super.onDestroyView()
        _viewModel.stopSync()
    }
}
