package com.mina.moonchat.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.mina.moonchat.R
import com.mina.moonchat.databinding.ChatListItemBinding
import com.mina.moonchat.models.ChatItem

class ChatsAdapter : PagingDataAdapter<ChatItem, RecyclerView.ViewHolder>(COMPARATOR) {

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val chatItem = getItem(position)
        if (chatItem != null) {
            (holder as ChatsViewHolder).bind(chatItem as ChatItem)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return ChatsViewHolder.create(parent)
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


class ChatsViewHolder(private var binding: ChatListItemBinding) :
    RecyclerView.ViewHolder(binding.root) {

    companion object {
        fun create(view: ViewGroup): ChatsViewHolder {

            val inflater = LayoutInflater.from(view.context)
            val binding = ChatListItemBinding.inflate(inflater, view, false)
            return ChatsViewHolder(binding)
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
        //binding.itemStatusTextView.text = if (user.onlineState) "Online" else "Offline"
        binding.itemLastMessageTextView.text = user.lastMessage
    }
}
