package com.mina.moonchat.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.mina.moonchat.R
import com.mina.moonchat.databinding.SearchFriendItemListBinding
import com.mina.moonchat.models.User

class SearchFriendListAdapter(
    private val clickListener: SearchListener
) : ListAdapter<User, SearchFriendListAdapter.SearchViewHolder>(SearchDiffCallback()) {

    class SearchViewHolder private constructor(
        val binding: SearchFriendItemListBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(user: User, clickListener: SearchListener) {
            binding.tvUsername.text = user.displayName

            if (user.id.isNotEmpty()) {
                binding.tvTag.text = user.id
            }

            binding.tvStatus.text = when {
                user.bio.isNotBlank() -> user.bio
                user.onlineState.equals("Online", ignoreCase = true) -> "Active recently"
                else -> "Offline"
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
        holder.bind(getItem(position), clickListener)
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
