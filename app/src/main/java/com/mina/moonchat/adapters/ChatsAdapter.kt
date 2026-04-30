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
    private val onChatClicked: (ChatItem) -> Unit
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
    private val onChatClicked: (ChatItem) -> Unit
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(user: ChatItem) {
        if (user.profileImg.isNullOrBlank()) {
            binding.itemCircleImageView.setImageResource(R.drawable.ic_account_circle)
        } else {
            Glide.with(binding.root)
                .load(user.profileImg)
                .placeholder(R.drawable.ic_account_circle)
                .error(R.drawable.ic_account_circle)
                .into(binding.itemCircleImageView)
        }

        binding.itemNameTextView.text = user.username
        binding.itemTimeTextView.text = user.time
        binding.itemLastMessageTextView.text = user.lastMessage
        binding.itemLastMessageTextView.setTextColor(
            ContextCompat.getColor(
                binding.root.context,
                if (user.hasUnreadIncoming) R.color.white else R.color.chat_list_last_message_color
            )
        )
        binding.onlineIndicator.visibility = View.VISIBLE
        binding.onlineIndicator.setBackgroundResource(
            if (user.onlineState) R.drawable.online_indicator else R.drawable.offline_indicator
        )
        binding.root.setOnClickListener { onChatClicked(user) }
    }

    companion object {
        fun create(parent: ViewGroup, onChatClicked: (ChatItem) -> Unit): ChatsViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val binding = ChatListItemBinding.inflate(inflater, parent, false)
            return ChatsViewHolder(binding, onChatClicked)
        }
    }
}
