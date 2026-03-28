package com.mina.moonchat.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mina.moonchat.databinding.RecipientItemTextMessageBinding
import com.mina.moonchat.databinding.SenderItemTextMessageBinding
import com.mina.moonchat.models.TextMessage

/**
 * Improved MessageAdapter with:
 * - Better ViewHolder implementation
 * - Message status indicators (pending, sent, failed)
 * - Optimized DiffUtil
 */
class MessageAdapter(private val currentUserId: String) :
    PagingDataAdapter<TextMessage, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private const val TYPE_SENDER = 0
        private const val TYPE_RECIPIENT = 1

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<TextMessage>() {
            override fun areItemsTheSame(oldItem: TextMessage, newItem: TextMessage): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: TextMessage, newItem: TextMessage): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        return if (message?.senderId == currentUserId) TYPE_SENDER else TYPE_RECIPIENT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SENDER -> {
                val binding = SenderItemTextMessageBinding.inflate(inflater, parent, false)
                SenderViewHolder(binding)
            }
            else -> {
                val binding = RecipientItemTextMessageBinding.inflate(inflater, parent, false)
                RecipientViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position) ?: return
        when (holder) {
            is SenderViewHolder -> holder.bind(message)
            is RecipientViewHolder -> holder.bind(message)
        }
    }

    /**
     * ViewHolder for sender messages with status indicators
     */
    inner class SenderViewHolder(
        private val binding: SenderItemTextMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: TextMessage) {
            binding.apply {
                this.message = message

                // Show status indicator based on message state
                when {
                    message.isPending() -> {
                        // Show pending indicator (e.g., clock icon)
                        statusIcon.visibility = View.VISIBLE
                        statusIcon.setImageResource(android.R.drawable.ic_menu_recent_history)
                    }
                    message.isFailed() -> {
                        // Show failed indicator (e.g., error icon)
                        statusIcon.visibility = View.VISIBLE
                        statusIcon.setImageResource(android.R.drawable.ic_dialog_alert)
                        // Make clickable to retry
                        root.setOnClickListener {
                            onMessageClickListener?.onMessageClick(message)
                        }
                    }
                    else -> {
                        // Message sent successfully - hide or show checkmark
                        statusIcon.visibility = View.GONE
                    }
                }

                executePendingBindings()
            }
        }
    }

    /**
     * ViewHolder for recipient messages
     */
    inner class RecipientViewHolder(
        private val binding: RecipientItemTextMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: TextMessage) {
            binding.apply {
                this.message = message
                executePendingBindings()
            }
        }
    }

    // Click listener interface for message interactions (retry, delete, etc.)
    interface OnMessageClickListener {
        fun onMessageClick(message: TextMessage)
    }

    private var onMessageClickListener: OnMessageClickListener? = null

    fun setOnMessageClickListener(listener: OnMessageClickListener) {
        this.onMessageClickListener = listener
    }
}
