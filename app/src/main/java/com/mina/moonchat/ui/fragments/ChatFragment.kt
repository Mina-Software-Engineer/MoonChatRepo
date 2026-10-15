package com.mina.moonchat.ui.fragments

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.paging.LoadState
import androidx.paging.TerminalSeparatorType
import androidx.paging.insertSeparators
import androidx.paging.map
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionInflater
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.mina.moonchat.R
import com.mina.moonchat.adapters.MessageAdapter
import com.mina.moonchat.base.BaseFragment
import com.mina.moonchat.databinding.FragmentChatBinding
import com.mina.moonchat.models.ChatTimelineItem
import com.mina.moonchat.models.TextMessage
import com.mina.moonchat.models.User
import com.mina.moonchat.utils.ActiveChatManager
import com.mina.moonchat.utils.ChatDateSeparator
import com.mina.moonchat.utils.NotificationHelper
import com.mina.moonchat.viewmodels.ChatViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
    private var activeRecipientUser: User? = null
    private var dateReceiverRegistered = false

    private val datePillHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isDatePillVisible = false
    private var selectedMessage: TextMessage? = null

    private val dateChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val shouldRefresh = when (intent?.action) {
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> true
                else -> false
            }
            if (shouldRefresh) {
                if (::messageAdapter.isInitialized) messageAdapter.refresh()
            }
        }
    }

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { selectedImage: Uri? ->
        val recipient = activeRecipientUser
        if (selectedImage != null && recipient != null && isAdded) {
            requestScrollToBottom(force = true)
            _viewModel.sendImageMessage(recipient, selectedImage)
        }
    }

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

        postponeEnterTransition()

        val recipientUser = ChatFragmentArgs.fromBundle(requireArguments()).user
        activeRecipientUser = recipientUser
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
        setupActionModeBar(mAuth.currentUser?.uid.orEmpty())

        loadMessages(recipientUser.userId)
        NotificationHelper.clearUnreadForSender(recipientUser.userId)
        _viewModel.markMessagesAsRead(recipientUser.userId)
        _viewModel.syncPendingMessages()
    }

    private fun setupRecyclerView() {
        val currentUserId = mAuth.currentUser?.uid ?: return

        messageAdapter = MessageAdapter(currentUserId).apply {
            setOnMessageClickListener(object : MessageAdapter.OnMessageClickListener {
                override fun onMessageClick(message: TextMessage) {
                    if (selectedMessage != null) {
                        exitActionMode()
                        return
                    }
                    if (message.isFailed()) {
                        _viewModel.retryMessage(message)
                    }
                }
                override fun onImageClick(message: TextMessage) {
                    if (selectedMessage != null) {
                        exitActionMode()
                        return
                    }
                    com.mina.moonchat.ui.dialogs.ImageViewerDialog.show(
                        requireContext(),
                        message,
                        currentUserId
                    )
                }
                override fun onRetryClick(
                    message: TextMessage,
                    onFinished: (queued: Boolean) -> Unit
                ) {
                    _viewModel.retryMessage(message, onFinished)
                }
                override fun onMessageLongClick(message: TextMessage) {
                    enterActionMode(message)
                }
            })
        }
        chatLayoutManager = LinearLayoutManager(requireContext()).apply {
            reverseLayout = true
            stackFromEnd = false
        }

        binding.chatRecyclerView.apply {
            adapter = messageAdapter
            layoutManager = chatLayoutManager
            itemAnimator = null
        }

        setupFloatingDatePill()

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

    private fun enterActionMode(message: TextMessage) {
        selectedMessage = message
        messageAdapter.setSelectedMessageId(message.id)
        binding.actionModeToolbarContainer.visibility = View.VISIBLE
        binding.actionModeTitle.text = "1"
    }

    private fun exitActionMode() {
        selectedMessage = null
        messageAdapter.setSelectedMessageId(null)
        binding.actionModeToolbarContainer.visibility = View.GONE
    }

    private fun setupActionModeBar(currentUserId: String) {
        binding.actionModeCloseBtn.setOnClickListener {
            exitActionMode()
        }

        binding.actionModeCopyBtn.setOnClickListener {
            val msg = selectedMessage
            if (msg != null && msg.text.isNotBlank() && !msg.isDeletedForEveryone) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Copied Message", msg.text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), getString(R.string.message_copied), Toast.LENGTH_SHORT).show()
            }
            exitActionMode()
        }

        binding.actionModeDeleteBtn.setOnClickListener {
            val msg = selectedMessage
            if (msg != null) {
                showMessageContextMenu(msg, currentUserId)
            }
            exitActionMode()
        }

        binding.actionModeReplyBtn.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.action_reply_coming_soon), Toast.LENGTH_SHORT).show()
            exitActionMode()
        }

        binding.actionModeForwardBtn.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.action_forward_coming_soon), Toast.LENGTH_SHORT).show()
            exitActionMode()
        }
    }

    private fun showMessageContextMenu(message: TextMessage, currentUserId: String) {
        if (!isAdded) return

        val nowMs = System.currentTimeMillis()
        val canEdit = message.canBeEditedBy(currentUserId, nowMs)
        val canDeleteEveryone = message.canBeDeletedForEveryoneBy(currentUserId, nowMs)

        val options = mutableListOf<String>()

        if (canEdit) {
            options.add(getString(R.string.action_edit_message))
        }
        if (canDeleteEveryone) {
            options.add(getString(R.string.action_delete_for_everyone))
        }
        options.add(getString(R.string.action_delete_for_me))

        AlertDialog.Builder(requireContext())
            .setItems(options.toTypedArray()) { _, which ->
                val selectedOption = options[which]
                when (selectedOption) {
                    getString(R.string.action_edit_message) -> {
                        showEditMessageDialog(message)
                    }
                    getString(R.string.action_delete_for_everyone) -> {
                        showConfirmDeleteEveryoneDialog(message)
                    }
                    getString(R.string.action_delete_for_me) -> {
                        _viewModel.deleteMessageForMe(message)
                    }
                }
            }
            .show()
    }

    private fun showEditMessageDialog(message: TextMessage) {
        val input = EditText(requireContext()).apply {
            setText(message.text)
            setSelection(text.length)
        }
        val container = FrameLayout(requireContext()).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(input)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.edit_message_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isNotBlank() && newText != message.text) {
                    _viewModel.editMessage(message, newText)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showConfirmDeleteEveryoneDialog(message: TextMessage) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_for_everyone_title)
            .setMessage(R.string.delete_for_everyone_message)
            .setPositiveButton(R.string.action_delete_for_everyone) { _, _ ->
                _viewModel.deleteMessageForEveryone(message)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupFloatingDatePill() {
        binding.chatRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                super.onScrollStateChanged(rv, newState)
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING || newState == RecyclerView.SCROLL_STATE_SETTLING) {
                    datePillHandler.removeCallbacksAndMessages(null)
                    updateFloatingDatePillText()
                    showFloatingDatePill()
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    scheduleHideFloatingDatePill()
                }
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                if (dx != 0 || dy != 0 || isDatePillVisible) {
                    updateFloatingDatePillText()
                    if (!isDatePillVisible && (dx != 0 || dy != 0)) {
                        showFloatingDatePill()
                    }
                }
            }
        })
    }

    private fun updateFloatingDatePillText() {
        if (!this::chatLayoutManager.isInitialized || !this::messageAdapter.isInitialized) return

        val topPosition = chatLayoutManager.findLastVisibleItemPosition()
        if (topPosition == RecyclerView.NO_POSITION || topPosition < 0 || topPosition >= messageAdapter.itemCount) {
            return
        }

        var timelineItem = messageAdapter.peek(topPosition)
        if (timelineItem == null) {
            for (i in 1..3) {
                val checkPos = topPosition - i
                if (checkPos in 0 until messageAdapter.itemCount) {
                    val alt = messageAdapter.peek(checkPos)
                    if (alt != null) {
                        timelineItem = alt
                        break
                    }
                }
            }
        }

        val dateText = when (timelineItem) {
            is ChatTimelineItem.MessageItem -> {
                val label = ChatDateSeparator.label(
                    timelineItem.message.date,
                    Date(),
                    TimeZone.getDefault(),
                    Locale.getDefault()
                )
                formatDateLabel(label)
            }
            is ChatTimelineItem.DateSeparator -> {
                formatDateLabel(timelineItem.label)
            }
            else -> null
        }

        if (!dateText.isNullOrBlank()) {
            binding.floatingDatePill.text = dateText
        }
    }

    private fun formatDateLabel(label: com.mina.moonchat.utils.ChatDateLabel): String {
        return when (label) {
            com.mina.moonchat.utils.ChatDateLabel.Today -> getString(R.string.today)
            com.mina.moonchat.utils.ChatDateLabel.Yesterday -> getString(R.string.yesterday)
            is com.mina.moonchat.utils.ChatDateLabel.Absolute -> label.value
        }
    }

    private fun showFloatingDatePill() {
        val pill = binding.floatingDatePill
        if (pill.text.isNullOrBlank()) return

        datePillHandler.removeCallbacksAndMessages(null)

        if (!isDatePillVisible || pill.visibility != View.VISIBLE) {
            pill.animate().cancel()
            val offscreenY = -(pill.height.takeIf { it > 0 }?.toFloat() ?: 100f)
            if (pill.visibility != View.VISIBLE) {
                pill.translationY = offscreenY
                pill.alpha = 0f
                pill.visibility = View.VISIBLE
            }
            pill.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(250)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .setListener(null)
                .start()
            isDatePillVisible = true
        }
    }

    private fun hideFloatingDatePill() {
        val pill = binding.floatingDatePill
        if (pill.visibility != View.VISIBLE) return

        pill.animate().cancel()
        val targetY = -(pill.height.takeIf { it > 0 }?.toFloat() ?: 100f)
        pill.animate()
            .translationY(targetY)
            .alpha(0f)
            .setDuration(250)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    pill.visibility = View.GONE
                    isDatePillVisible = false
                }
            })
            .start()
    }

    private fun scheduleHideFloatingDatePill() {
        datePillHandler.removeCallbacksAndMessages(null)
        datePillHandler.postDelayed({
            if (isAdded) {
                hideFloatingDatePill()
            }
        }, 2000)
    }

    private fun setupObservers() {
        _viewModel.onlineStatus.observe(viewLifecycleOwner) { status ->
            val isOnline = status.equals("Online", ignoreCase = true)
            binding.onlinetv.setBackgroundResource(
                if (isOnline) R.drawable.online_indicator else R.drawable.offline_indicator
            )
        }

        _viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                _viewModel.clearError()
            }
        }

        _viewModel.isSending.observe(viewLifecycleOwner) { isSending ->
            binding.sendBtn.isEnabled = !isSending
            binding.sendBtn.alpha = if (isSending) 0.5f else 1.0f
            binding.attachImageBtn.isEnabled = !isSending
            binding.attachImageBtn.alpha = if (isSending) 0.5f else 1.0f
        }

        _viewModel.textMessage.observe(viewLifecycleOwner) { text ->
            _viewModel.onTextChanged(text.orEmpty())
        }
    }

    private fun setupClickListeners(recipientUser: User) {
        binding.emojiBtn.setOnClickListener {
            if (binding.emojiPicker.visibility == View.VISIBLE) {
                binding.emojiPicker.visibility = View.GONE
            } else {
                hideKeyboard()
                binding.emojiPicker.visibility = View.VISIBLE
            }
        }

        binding.emojiPicker.setOnEmojiPickedListener {
            binding.textViewMessageBox.append(it.emoji)
        }

        binding.textViewMessageBox.setOnClickListener {
            binding.emojiPicker.visibility = View.GONE
        }

        binding.textViewMessageBox.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.emojiPicker.visibility = View.GONE
            }
        }

        binding.sendBtn.setOnClickListener {
            val messageText = binding.textViewMessageBox.text?.toString()?.trim()
            if (!messageText.isNullOrEmpty()) {
                requestScrollToBottom(force = true)
                _viewModel.onSendClicked(recipientUser, messageText)
                binding.textViewMessageBox.text?.clear()
            }
        }

        binding.attachImageBtn.setOnClickListener {
            if (_viewModel.isSending.value != true) {
                binding.emojiPicker.visibility = View.GONE
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }

        binding.toolbarBackBtn.setOnClickListener {
            if (selectedMessage != null) {
                exitActionMode()
            } else if (binding.emojiPicker.visibility == View.VISIBLE) {
                binding.emojiPicker.visibility = View.GONE
            } else {
                findNavController().navigateUp()
            }
        }
    }

    private fun loadMessages(recipientId: String) {
        messagesJob?.cancel()
        messagesJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                _viewModel.getMessageStream(recipientId).collectLatest { pagingData ->
                    val now = Date()
                    val timeZone = TimeZone.getDefault()
                    val locale = Locale.getDefault()
                    val timeline = pagingData
                        .map<TextMessage, ChatTimelineItem> { ChatTimelineItem.MessageItem(it) }
                        .insertSeparators(terminalSeparatorType = TerminalSeparatorType.FULLY_COMPLETE) { before, after ->
                            val newer = before as? ChatTimelineItem.MessageItem
                            val older = after as? ChatTimelineItem.MessageItem
                            when {
                                newer == null -> null
                                older == null || !ChatDateSeparator.isSameLocalDay(
                                    newer.message.date,
                                    older.message.date,
                                    timeZone,
                                    locale
                                ) -> ChatTimelineItem.DateSeparator(
                                    dayKey = ChatDateSeparator.localDayKey(newer.message.date, timeZone, locale),
                                    label = ChatDateSeparator.label(newer.message.date, now, timeZone, locale)
                                )
                                else -> null
                            }
                        }
                    messageAdapter.submitData(timeline)
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
        if (!dateReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            ContextCompat.registerReceiver(
                requireContext(),
                dateChangeReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            dateReceiverRegistered = true
        }
        try {
            val recipientUser = ChatFragmentArgs.fromBundle(requireArguments()).user
            ActiveChatManager.activeRecipientId = recipientUser.userId
        } catch (e: Exception) {
            Log.e(TAG, "Error setting activeRecipientId in onStart: ${e.message}")
        }
    }

    override fun onStop() {
        if (dateReceiverRegistered) {
            requireContext().unregisterReceiver(dateChangeReceiver)
            dateReceiverRegistered = false
        }
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
        datePillHandler.removeCallbacksAndMessages(null)
        messagesJob?.cancel()
        activeRecipientUser = null
        selectedMessage = null
        super.onDestroyView()
        _viewModel.stopSync()
    }
}
