package com.mina.moonchat.adapters

import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.mina.moonchat.R
import com.mina.moonchat.databinding.ChatListItemBinding
import com.mina.moonchat.models.ChatItem

class ChatsAdapter(
    private val onChatClicked: (ChatItem) -> Unit
) : PagingDataAdapter<ChatItem, RecyclerView.ViewHolder>(COMPARATOR) {

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val chatItem = getItem(position)
        if (chatItem != null) {
            (holder as ChatsViewHolder).bind(chatItem)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return ChatsViewHolder.create(parent, onChatClicked)
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
    private var binding: ChatListItemBinding,
    private val onChatClicked: (ChatItem) -> Unit
) :
    RecyclerView.ViewHolder(binding.root) {

    companion object {
        fun create(view: ViewGroup, onChatClicked: (ChatItem) -> Unit): ChatsViewHolder {

            val inflater = LayoutInflater.from(view.context)
            val binding = ChatListItemBinding.inflate(inflater, view, false)
            return ChatsViewHolder(binding, onChatClicked)
        }
    }

    // Bind message data to the UI elements
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
        binding.onlineIndicator.visibility = View.VISIBLE
        binding.onlineIndicator.setBackgroundResource(
            if (user.onlineState) R.drawable.online_indicator else R.drawable.offline_indicator
        )
        binding.root.setOnClickListener {
            onChatClicked(user)
        }
    }
}
