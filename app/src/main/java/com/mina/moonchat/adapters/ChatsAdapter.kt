package com.mina.moonchat.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.mina.moonchat.R
import com.mina.moonchat.databinding.ChatListItemBinding
import com.mina.moonchat.models.ChatItem

class ChatsAdapter(
    private val onChatClicked: (ChatItem, Array<View>) -> Unit
) : ListAdapter<ChatItem, ChatsViewHolder>(COMPARATOR) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatsViewHolder {
        return ChatsViewHolder.create(parent, onChatClicked)
    }

    override fun onBindViewHolder(holder: ChatsViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val COMPARATOR = object : DiffUtil.ItemCallback<ChatItem>() {
            override fun areItemsTheSame(oldItem: ChatItem, newItem: ChatItem): Boolean =
                oldItem.chatId == newItem.chatId

            override fun areContentsTheSame(oldItem: ChatItem, newItem: ChatItem): Boolean =
                oldItem == newItem
        }
    }
}

class ChatsViewHolder(
    private val binding: ChatListItemBinding,
    private val onChatClicked: (ChatItem, Array<View>) -> Unit
) : RecyclerView.ViewHolder(binding.root) {

    private var currentItem: ChatItem? = null

    init {
        binding.root.setOnClickListener {
            currentItem?.let { user ->
                onChatClicked(user, arrayOf(
                    binding.itemCircleImageView,
                    binding.onlineIndicator,
                    binding.itemNameTextView
                ))
            }
        }
    }

    fun bind(user: ChatItem) {
        currentItem = user

        if (user.profileImg.isNullOrBlank()) {
            binding.itemCircleImageView.setImageResource(R.drawable.ic_account_circle)
        } else {
            Glide.with(binding.itemCircleImageView)
                .load(user.profileImg)
                .placeholder(R.drawable.ic_account_circle)
                .error(R.drawable.ic_account_circle)
                .into(binding.itemCircleImageView)
        }

        binding.itemNameTextView.text = user.username
        binding.itemTimeTextView.text = user.time

        // Set unique transition names for shared element animation
        binding.itemCircleImageView.transitionName = "profile_${user.recipientId}"
        binding.onlineIndicator.transitionName = "online_${user.recipientId}"
        binding.itemNameTextView.transitionName = "username_${user.recipientId}"

        val context = binding.root.context
        val isUnread = user.hasUnreadIncoming && user.unreadCount > 0

        if (isUnread) {
            binding.itemNameTextView.setTextColor(ContextCompat.getColor(context, R.color.chat_list_username_unread_color))
            binding.itemTimeTextView.setTextColor(ContextCompat.getColor(context, R.color.chat_list_time_unread_color))
            binding.itemUnreadCountTextView.visibility = View.VISIBLE
            binding.itemUnreadCountTextView.text = user.unreadCount.toString()
        } else {
            binding.itemNameTextView.setTextColor(ContextCompat.getColor(context, R.color.chat_list_username_color))
            binding.itemTimeTextView.setTextColor(ContextCompat.getColor(context, R.color.chat_list_time_color))
            binding.itemUnreadCountTextView.visibility = View.GONE
        }

        val stateColor = ContextCompat.getColor(
            context,
            if (isUnread) R.color.chat_list_last_message_unread_color else R.color.chat_list_last_message_color
        )

        if (user.isTyping) {
            binding.itemLastMessageTextView.text = "Typing..."
            binding.itemLastMessageTextView.setTextColor(android.graphics.Color.parseColor("#059669"))
            binding.itemStatusImageView.visibility = View.GONE
        } else {
            binding.itemLastMessageTextView.text = user.lastMessage
            binding.itemLastMessageTextView.setTextColor(stateColor)

            if (user.lastMessageStatus.isBlank()) {
                binding.itemStatusImageView.visibility = View.GONE
            } else {
                binding.itemStatusImageView.apply {
                    visibility = View.VISIBLE
                    setImageResource(
                        when (user.lastMessageStatus) {
                            "Pending" -> R.drawable.ic_pending
                            "Seen" -> R.drawable.ic_double_tick_seen
                            "Delivered" -> R.drawable.ic_double_tick
                            "Failed" -> R.drawable.ic_failed
                            else -> R.drawable.ic_tick
                        }
                    )
                    
                    val iconTint = when (user.lastMessageStatus) {
                        "Seen" -> android.graphics.Color.parseColor("#93C5FD")
                        "Failed" -> android.graphics.Color.parseColor("#FCA5A5")
                        else -> stateColor
                    }
                    setColorFilter(iconTint)
                }
            }
        }

        binding.onlineIndicator.visibility = View.VISIBLE
        binding.onlineIndicator.setBackgroundResource(
            if (user.onlineState) R.drawable.online_indicator else R.drawable.offline_indicator
        )
    }

    companion object {
        fun create(parent: ViewGroup, onChatClicked: (ChatItem, Array<View>) -> Unit): ChatsViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ChatListItemBinding.inflate(inflater, parent, false)
            return ChatsViewHolder(binding, onChatClicked)
        }
    }
}