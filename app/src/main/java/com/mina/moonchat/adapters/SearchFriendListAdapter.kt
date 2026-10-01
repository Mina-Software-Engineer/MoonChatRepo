package com.mina.moonchat.adapters

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.mina.moonchat.R
import com.mina.moonchat.databinding.SearchFriendItemListBinding
import com.mina.moonchat.models.User

class SearchFriendListAdapter(
    private val clickListener: SearchListener,
    private val onAddFriendClicked: ((user: User, button: MaterialButton) -> Unit)? = null
) : ListAdapter<User, SearchFriendListAdapter.SearchViewHolder>(SearchDiffCallback()) {

    var friendUserIds: Set<String> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var pendingUserIds: Set<String> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class SearchViewHolder private constructor(
        val binding: SearchFriendItemListBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            user: User,
            clickListener: SearchListener,
            isFriend: Boolean,
            isPending: Boolean,
            onAddFriendClicked: ((user: User, button: MaterialButton) -> Unit)?
        ) {
            binding.tvUsername.text = user.displayName

            if (user.id.isNotEmpty()) {
                binding.tvTag.text = user.id
            }

            if (user.profileImg.isNullOrEmpty()) {
                binding.searchItemCircleImageView.setImageResource(R.drawable.ic_account_circle)
            } else {
                Glide.with(binding.searchItemCircleImageView)
                    .load(user.profileImg)
                    .placeholder(R.drawable.ic_account_circle)
                    .error(R.drawable.ic_account_circle)
                    .into(binding.searchItemCircleImageView)
            }

            if (isFriend) {
                binding.btnAddFriend.visibility = View.GONE
                binding.tvStatus.text = "Friend"
                binding.tvStatus.setTextColor(Color.parseColor("#10B981"))
            } else if (isPending) {
                binding.btnAddFriend.visibility = View.VISIBLE
                binding.btnAddFriend.text = "Pending"
                binding.btnAddFriend.isEnabled = false
                binding.btnAddFriend.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#374151"))
                binding.tvStatus.text = if (user.bio.isNotBlank()) user.bio else "Request Sent"
                binding.tvStatus.setTextColor(Color.parseColor("#6B7280"))
            } else {
                binding.btnAddFriend.visibility = View.VISIBLE
                binding.btnAddFriend.text = "Add Friend"
                binding.btnAddFriend.isEnabled = true
                binding.btnAddFriend.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#6366F1"))
                binding.tvStatus.text = when {
                    user.bio.isNotBlank() -> user.bio
                    user.onlineState.equals("Online", ignoreCase = true) -> "Active recently"
                    else -> "Offline"
                }
                binding.tvStatus.setTextColor(Color.parseColor("#6B7280"))
            }

            binding.btnAddFriend.setOnClickListener {
                onAddFriendClicked?.invoke(user, binding.btnAddFriend)
            }

            binding.user = user
            binding.clickListener = clickListener
            binding.executePendingBindings()
        }

        companion object {
            fun from(parent: ViewGroup): SearchViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = SearchFriendItemListBinding.inflate(layoutInflater, parent, false)
                return SearchViewHolder(binding)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchViewHolder {
        return SearchViewHolder.from(parent)
    }

    override fun onBindViewHolder(holder: SearchViewHolder, position: Int) {
        val user = getItem(position)
        val uId = user.userId.ifEmpty { user.id }
        val isFriend = friendUserIds.contains(uId) || friendUserIds.contains(user.userId) || friendUserIds.contains(user.id)
        val isPending = pendingUserIds.contains(uId) || pendingUserIds.contains(user.userId) || pendingUserIds.contains(user.id)
        holder.bind(user, clickListener, isFriend, isPending, onAddFriendClicked)
    }
}

class SearchDiffCallback : DiffUtil.ItemCallback<User>() {
    override fun areItemsTheSame(oldItem: User, newItem: User): Boolean {
        val oldId = oldItem.userId.ifEmpty { oldItem.id }
        val newId = newItem.userId.ifEmpty { newItem.id }
        return oldId == newId
    }

    override fun areContentsTheSame(oldItem: User, newItem: User): Boolean {
        return oldItem == newItem
    }
}

class SearchListener(val clickListener: (user: User) -> Unit) {
    fun onClick(user: User) = clickListener(user)
}
