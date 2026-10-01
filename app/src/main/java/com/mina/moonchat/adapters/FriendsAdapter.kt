package com.mina.moonchat.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.mina.moonchat.R
import com.mina.moonchat.databinding.ItemFriendHeaderBinding
import com.mina.moonchat.databinding.ItemFriendRequestBinding
import com.mina.moonchat.databinding.SearchFriendItemListBinding
import com.mina.moonchat.models.User

sealed class FriendListItem {
    data class Header(val title: String, val isOnline: Boolean) : FriendListItem()
    data class Friend(val user: User) : FriendListItem()
    data class Request(val user: User, val requestId: String) : FriendListItem()
}

class FriendsAdapter(
    private val clickListener: SearchListener,
    private val onAcceptClicked: ((FriendListItem.Request) -> Unit)? = null,
    private val onRejectClicked: ((FriendListItem.Request) -> Unit)? = null
) : ListAdapter<FriendListItem, RecyclerView.ViewHolder>(FriendListDiffCallback()) {

    private val ITEM_VIEW_TYPE_HEADER = 0
    private val ITEM_VIEW_TYPE_ITEM = 1
    private val ITEM_VIEW_TYPE_REQUEST = 2

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is FriendListItem.Header -> ITEM_VIEW_TYPE_HEADER
            is FriendListItem.Friend -> ITEM_VIEW_TYPE_ITEM
            is FriendListItem.Request -> ITEM_VIEW_TYPE_REQUEST
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            ITEM_VIEW_TYPE_HEADER -> HeaderViewHolder.from(parent)
            ITEM_VIEW_TYPE_ITEM -> FriendViewHolder.from(parent)
            ITEM_VIEW_TYPE_REQUEST -> RequestViewHolder.from(parent)
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
            is RequestViewHolder -> {
                val item = getItem(position) as FriendListItem.Request
                holder.bind(item, onAcceptClicked, onRejectClicked)
            }
        }
    }

    class HeaderViewHolder(
        private val binding: ItemFriendHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(header: FriendListItem.Header) {
            binding.tvHeaderTitle.text = header.title
            binding.headerDot.setBackgroundResource(
                if (header.isOnline) R.drawable.online_indicator_dot else R.drawable.offline_indicator
            )

            if (header.title == "Online" || header.title == "Offline") {
                binding.headerDot.visibility = View.VISIBLE
            } else {
                binding.headerDot.visibility = View.GONE
            }
        }

        companion object {
            fun from(parent: ViewGroup): HeaderViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ItemFriendHeaderBinding.inflate(layoutInflater, parent, false)
                return HeaderViewHolder(binding)
            }
        }
    }

    class FriendViewHolder(private val binding: SearchFriendItemListBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(user: User, clickListener: SearchListener) {
            binding.tvUsername.text = user.displayName
            if (user.id.isNotEmpty()) {
                binding.tvTag.text = user.id
            }

            binding.tvStatus.text = if (user.onlineState.equals("online", ignoreCase = true)) "Online" else "Offline"
            binding.btnAddFriend.visibility = View.GONE

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

    class RequestViewHolder(private val binding: ItemFriendRequestBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            request: FriendListItem.Request,
            onAcceptClicked: ((FriendListItem.Request) -> Unit)?,
            onRejectClicked: ((FriendListItem.Request) -> Unit)?
        ) {
            val user = request.user
            binding.tvRequestUsername.text = user.displayName
            binding.tvRequestBio.text = if (user.bio.isNotBlank()) user.bio else "Hey there! I am using MoonChat."

            if (user.profileImg.isNullOrEmpty()) {
                binding.requestCircleImageView.setImageResource(R.drawable.ic_account_circle)
            } else {
                Glide.with(binding.requestCircleImageView)
                    .load(user.profileImg)
                    .placeholder(R.drawable.ic_account_circle)
                    .error(R.drawable.ic_account_circle)
                    .into(binding.requestCircleImageView)
            }

            binding.btnAccept.setOnClickListener {
                onAcceptClicked?.invoke(request)
            }

            binding.btnReject.setOnClickListener {
                onRejectClicked?.invoke(request)
            }
        }

        companion object {
            fun from(parent: ViewGroup): RequestViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ItemFriendRequestBinding.inflate(layoutInflater, parent, false)
                return RequestViewHolder(binding)
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
            oldItem is FriendListItem.Request && newItem is FriendListItem.Request -> {
                oldItem.requestId == newItem.requestId
            }
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: FriendListItem, newItem: FriendListItem): Boolean {
        return oldItem == newItem
    }
}
