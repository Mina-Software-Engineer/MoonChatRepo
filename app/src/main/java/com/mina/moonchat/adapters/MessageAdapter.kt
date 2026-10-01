package com.mina.moonchat.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.google.firebase.auth.FirebaseAuth
import com.mina.moonchat.R
import com.mina.moonchat.databinding.RecipientItemTextMessageBinding
import com.mina.moonchat.databinding.SenderItemTextMessageBinding
import com.mina.moonchat.models.TextMessage
import android.graphics.Color
import com.mina.moonchat.models.MessageDeliveryState
//import com.mina.moonchat.models.resolveDeliveryState

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
                return oldItem.id == newItem.id &&
                       oldItem.text == newItem.text &&
                       oldItem.status == newItem.status &&
                       oldItem.isRead == newItem.isRead &&
                       oldItem.isDelivered == newItem.isDelivered
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

                val state = message.resolveDeliveryState()
                imageViewStatus.apply {
                    visibility = View.VISIBLE
                    setImageResource(
                        when (state) {
                            MessageDeliveryState.PENDING -> R.drawable.ic_pending
                            MessageDeliveryState.SEEN -> R.drawable.ic_double_tick_seen
                            MessageDeliveryState.DELIVERED -> R.drawable.ic_double_tick
                            MessageDeliveryState.FAILED -> R.drawable.ic_failed
                            else -> R.drawable.ic_tick
                        }
                    )

                    setColorFilter(
                        when (state) {
                            MessageDeliveryState.SEEN -> Color.parseColor("#93C5FD")
                            MessageDeliveryState.FAILED -> Color.parseColor("#FCA5A5")
                            else -> Color.GRAY
                        }
                    )
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

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        // Only animate if it's the very first item (position 0) in the reverse layout
        // and if it's relatively "fresh" (to avoid re-animating on scroll)
        if (holder.absoluteAdapterPosition == 0) {
            val animation = android.view.animation.AnimationUtils.loadAnimation(
                holder.itemView.context,
                R.anim.message_popup
            )
            holder.itemView.startAnimation(animation)
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