package com.mina.moonchat.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.mina.moonchat.R
import com.mina.moonchat.databinding.ItemFriendHeaderBinding
import com.mina.moonchat.databinding.SearchFriendItemListBinding
import com.mina.moonchat.models.User

sealed class FriendListItem {
    data class Header(val title: String, val isOnline: Boolean) : FriendListItem()
    data class Friend(val user: User) : FriendListItem()
}

class FriendsAdapter(
    private val clickListener: SearchListener,
    private val onAddFriendClicked: () -> Unit
) : ListAdapter<FriendListItem, RecyclerView.ViewHolder>(FriendListDiffCallback()) {

    private val ITEM_VIEW_TYPE_HEADER = 0
    private val ITEM_VIEW_TYPE_ITEM = 1

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is FriendListItem.Header -> ITEM_VIEW_TYPE_HEADER
            is FriendListItem.Friend -> ITEM_VIEW_TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            ITEM_VIEW_TYPE_HEADER -> HeaderViewHolder.from(parent, onAddFriendClicked)
            ITEM_VIEW_TYPE_ITEM -> FriendViewHolder.from(parent)
            else -> throw ClassCastException("Unknown viewType $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> {
                val item = getItem(position) as FriendListItem.Header
                holder.bind(item)
            }
            is FriendViewHolder -> {
                val item = getItem(position) as FriendListItem.Friend
                holder.bind(item.user, clickListener)
            }
        }
    }

    class HeaderViewHolder(
        private val binding: ItemFriendHeaderBinding,
        onAddFriendClicked: () -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.btnAddFriendHeader.setOnClickListener { onAddFriendClicked() }
        }

        fun bind(header: FriendListItem.Header) {
            binding.tvHeaderTitle.text = header.title
            binding.headerDot.setBackgroundResource(
                if (header.isOnline) R.drawable.online_indicator_dot else R.drawable.offline_indicator
            )

            if (header.title == "Online") {
                binding.btnAddFriendHeader.visibility = android.view.View.VISIBLE
            } else {
                binding.btnAddFriendHeader.visibility = android.view.View.GONE
            }
        }

        companion object {
            fun from(parent: ViewGroup, onAddFriendClicked: () -> Unit): HeaderViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ItemFriendHeaderBinding.inflate(layoutInflater, parent, false)
                return HeaderViewHolder(binding, onAddFriendClicked)
            }
        }
    }

    class FriendViewHolder(private val binding: SearchFriendItemListBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(user: User, clickListener: SearchListener) {
            binding.tvUsername.text = user.displayName
            if (user.id.isNotEmpty()) {
                binding.tvTag.text = user.id
            }

            // In the design, it shows "Online" or "Offline"
            binding.tvStatus.text = if (user.onlineState.equals("online", ignoreCase = true)) "Online" else "Offline"

            if (user.profileImg.isNullOrEmpty()) {
                binding.searchItemCircleImageView.setImageResource(R.drawable.ic_account_circle)
            } else {
                Glide.with(binding.searchItemCircleImageView)
                    .load(user.profileImg)
                    .placeholder(R.drawable.ic_account_circle)
                    .error(R.drawable.ic_account_circle)
                    .into(binding.searchItemCircleImageView)
            }

            binding.user = user
            binding.clickListener = clickListener
            binding.executePendingBindings()
        }

        companion object {
            fun from(parent: ViewGroup): FriendViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = SearchFriendItemListBinding.inflate(layoutInflater, parent, false)
                return FriendViewHolder(binding)
            }
        }
    }
}

class FriendListDiffCallback : DiffUtil.ItemCallback<FriendListItem>() {
    override fun areItemsTheSame(oldItem: FriendListItem, newItem: FriendListItem): Boolean {
        return when {
            oldItem is FriendListItem.Header && newItem is FriendListItem.Header -> oldItem.title == newItem.title
            oldItem is FriendListItem.Friend && newItem is FriendListItem.Friend -> {
                val oldId = oldItem.user.userId.ifEmpty { oldItem.user.id }
                val newId = newItem.user.userId.ifEmpty { newItem.user.id }
                oldId == newId
            }
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: FriendListItem, newItem: FriendListItem): Boolean {
        return oldItem == newItem
    }
}
