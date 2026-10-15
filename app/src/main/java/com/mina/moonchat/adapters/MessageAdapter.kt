package com.mina.moonchat.adapters

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.google.firebase.auth.FirebaseAuth
import com.mina.moonchat.BuildConfig
import com.mina.moonchat.R
import com.mina.moonchat.databinding.RecipientItemImageMessageBinding
import com.mina.moonchat.databinding.RecipientItemTextMessageBinding
import com.mina.moonchat.databinding.SenderItemImageMessageBinding
import com.mina.moonchat.databinding.SenderItemTextMessageBinding
import com.mina.moonchat.models.ChatTimelineItem
import com.mina.moonchat.models.MessageDeliveryState
import com.mina.moonchat.models.MessageType
import com.mina.moonchat.models.TextMessage
import com.mina.moonchat.utils.ChatDateLabel
import java.io.File

class MessageAdapter(private val currentUserId: String) :
    PagingDataAdapter<ChatTimelineItem, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private const val TYPE_SENDER = 0
        private const val TYPE_RECIPIENT = 1
        private const val TYPE_SENDER_IMAGE = 2
        private const val TYPE_RECIPIENT_IMAGE = 3
        private const val TYPE_DATE_SEPARATOR = 4

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChatTimelineItem>() {
            override fun areItemsTheSame(oldItem: ChatTimelineItem, newItem: ChatTimelineItem): Boolean = when {
                oldItem is ChatTimelineItem.MessageItem && newItem is ChatTimelineItem.MessageItem ->
                    oldItem.message.id == newItem.message.id
                oldItem is ChatTimelineItem.DateSeparator && newItem is ChatTimelineItem.DateSeparator ->
                    oldItem.dayKey == newItem.dayKey
                else -> false
            }

            override fun areContentsTheSame(oldItem: ChatTimelineItem, newItem: ChatTimelineItem): Boolean =
                oldItem == newItem
        }
    }

    private var selectedMessageId: String? = null

    fun setSelectedMessageId(id: String?) {
        if (selectedMessageId != id) {
            selectedMessageId = id
            notifyDataSetChanged()
        }
    }

    fun getSelectedMessageId(): String? = selectedMessageId

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        if (item is ChatTimelineItem.DateSeparator) return TYPE_DATE_SEPARATOR
        val message = (item as? ChatTimelineItem.MessageItem)?.message
        val isSender = message?.senderId == currentUserId
        return when {
            message?.type == MessageType.IMAGE && isSender -> TYPE_SENDER_IMAGE
            message?.type == MessageType.IMAGE -> TYPE_RECIPIENT_IMAGE
            isSender -> TYPE_SENDER
            else -> TYPE_RECIPIENT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SENDER -> SenderViewHolder(SenderItemTextMessageBinding.inflate(inflater, parent, false))
            TYPE_SENDER_IMAGE -> SenderImageViewHolder(SenderItemImageMessageBinding.inflate(inflater, parent, false))
            TYPE_RECIPIENT_IMAGE -> RecipientImageViewHolder(RecipientItemImageMessageBinding.inflate(inflater, parent, false))
            TYPE_DATE_SEPARATOR -> DateSeparatorViewHolder(
                inflater.inflate(R.layout.item_message_date_separator, parent, false)
            )
            else -> RecipientViewHolder(RecipientItemTextMessageBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position) ?: return
        if (holder is DateSeparatorViewHolder) {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            (item as? ChatTimelineItem.DateSeparator)?.let(holder::bind)
            return
        }
        val message = (item as? ChatTimelineItem.MessageItem)?.message ?: return
        val isSelected = message.id == selectedMessageId
        holder.itemView.setBackgroundColor(
            if (isSelected) Color.parseColor("#333B82F6") else Color.TRANSPARENT
        )
        when (holder) {
            is SenderViewHolder -> holder.bind(message)
            is RecipientViewHolder -> holder.bind(message)
            is SenderImageViewHolder -> holder.bind(message)
            is RecipientImageViewHolder -> holder.bind(message)
        }
    }

    private fun loadMessageImage(imageView: ImageView, message: TextMessage) {
        val boundId = message.id
        imageView.tag = boundId
        Glide.with(imageView).clear(imageView)
        imageView.setImageResource(R.drawable.ic_image_message)
        imageView.contentDescription = imageView.context.getString(R.string.chat_image_description)

        val localFile = message.localImagePath?.let(::File)
        if (localFile?.isFile == true) {
            Glide.with(imageView).load(localFile)
                .placeholder(R.drawable.ic_image_message)
                .error(R.drawable.ic_image_message)
                .centerCrop()
                .into(imageView)
            return
        }
        if (message.media == null) {
            imageView.setImageResource(R.drawable.ic_image_message)
            return
        }

        val baseUrl = BuildConfig.CHAT_IMAGE_WORKER_URL.trimEnd('/')
        val user = FirebaseAuth.getInstance().currentUser
        if (baseUrl.isBlank() || user == null) {
            imageView.setImageResource(R.drawable.ic_image_message)
            return
        }
        val peerId = if (message.senderId == currentUserId) message.recipientId else message.senderId
        val imageUrl = "$baseUrl/v1/chat-images/${Uri.encode(message.id)}/content?peerId=${Uri.encode(peerId)}"
        user.getIdToken(false).addOnCompleteListener { task ->
            if (imageView.tag != boundId || !task.isSuccessful) return@addOnCompleteListener
            val token = task.result?.token ?: return@addOnCompleteListener
            val headers = LazyHeaders.Builder()
                .addHeader("Authorization", "Bearer $token")
                .build()
            Glide.with(imageView)
                .load(GlideUrl(imageUrl, headers))
                .placeholder(R.drawable.ic_image_message)
                .error(R.drawable.ic_image_message)
                .centerCrop()
                .into(imageView)
        }
    }

    inner class SenderViewHolder(
        private val binding: SenderItemTextMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: TextMessage) {
            binding.apply {
                this.message = message
                root.setOnLongClickListener {
                    onMessageClickListener?.onMessageLongClick(message)
                    true
                }

                if (message.isDeletedForEveryone) {
                    textViewMessage.text = itemView.context.getString(R.string.message_deleted_tombstone)
                    textViewMessage.setTypeface(null, Typeface.ITALIC)
                    imageViewStatus.visibility = View.GONE
                    failedMessageLabel.visibility = View.GONE
                    retryMessageButton.visibility = View.GONE
                } else {
                    val displayText = if (message.isEdited) {
                        "${message.text} ${itemView.context.getString(R.string.message_edited_marker)}"
                    } else {
                        message.text
                    }
                    textViewMessage.text = displayText
                    textViewMessage.setTypeface(null, Typeface.NORMAL)

                    val state = message.resolveDeliveryState()
                    imageViewStatus.apply {
                        visibility = View.VISIBLE
                        setImageResource(
                            when (state) {
                                MessageDeliveryState.PENDING -> R.drawable.ic_pending
                                MessageDeliveryState.SEEN -> R.drawable.ic_double_tick_seen
                                MessageDeliveryState.DELIVERED -> R.drawable.ic_double_tick
                                MessageDeliveryState.FAILED -> R.drawable.ic_failed
                                MessageDeliveryState.SENT -> R.drawable.ic_tick
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
                    bindTextRetry(message)
                }
                executePendingBindings()
            }
        }

        private fun bindTextRetry(message: TextMessage) {
            val canRetry = message.isRetryable() && !message.isDeletedForEveryone
            binding.failedMessageLabel.visibility = if (canRetry) View.VISIBLE else View.GONE
            binding.retryMessageButton.apply {
                setOnClickListener(null)
                visibility = if (canRetry) View.VISIBLE else View.GONE
                isEnabled = canRetry
                setText(R.string.retry_message)
                if (canRetry) {
                    setOnClickListener {
                        isEnabled = false
                        setText(R.string.retrying_message)
                        val boundMessageId = message.id
                        onMessageClickListener?.onRetryClick(message) { queued ->
                            val currentPosition = bindingAdapterPosition
                            if (currentPosition != RecyclerView.NO_POSITION &&
                                (getItem(currentPosition) as? ChatTimelineItem.MessageItem)?.message?.id == boundMessageId && message.isRetryable()
                            ) {
                                isEnabled = true
                                setText(R.string.retry_message)
                                if (!queued) visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
        }
    }

    inner class RecipientViewHolder(
        private val binding: RecipientItemTextMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: TextMessage) {
            binding.apply {
                this.message = message
                root.setOnLongClickListener {
                    onMessageClickListener?.onMessageLongClick(message)
                    true
                }

                if (message.isDeletedForEveryone) {
                    textViewMessage.text = itemView.context.getString(R.string.message_deleted_tombstone)
                    textViewMessage.setTypeface(null, Typeface.ITALIC)
                } else {
                    val displayText = if (message.isEdited) {
                        "${message.text} ${itemView.context.getString(R.string.message_edited_marker)}"
                    } else {
                        message.text
                    }
                    textViewMessage.text = displayText
                    textViewMessage.setTypeface(null, Typeface.NORMAL)
                }
                executePendingBindings()
            }
        }
    }

    inner class SenderImageViewHolder(
        private val binding: SenderItemImageMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: TextMessage) {
            binding.apply {
                this.message = message
                root.setOnLongClickListener {
                    onMessageClickListener?.onMessageLongClick(message)
                    true
                }

                if (message.isDeletedForEveryone) {
                    imageViewMessageImage.visibility = View.GONE
                    failedMessageLabel.visibility = View.GONE
                    retryMessageButton.visibility = View.GONE
                    imageViewStatus.visibility = View.GONE
                } else {
                    imageViewMessageImage.visibility = View.VISIBLE
                    loadMessageImage(imageViewMessageImage, message)
                    imageViewMessageImage.setOnClickListener {
                        if (!message.isRetryable()) {
                            onMessageClickListener?.onImageClick(message)
                        }
                    }
                    val state = message.resolveDeliveryState()
                    imageViewStatus.apply {
                        visibility = View.VISIBLE
                        setImageResource(
                            when (state) {
                                MessageDeliveryState.PENDING -> R.drawable.ic_pending
                                MessageDeliveryState.SEEN -> R.drawable.ic_double_tick_seen
                                MessageDeliveryState.DELIVERED -> R.drawable.ic_double_tick
                                MessageDeliveryState.FAILED -> R.drawable.ic_failed
                                MessageDeliveryState.SENT -> R.drawable.ic_tick
                            }
                        )
                    }
                }
                executePendingBindings()
            }
        }
    }

    inner class RecipientImageViewHolder(
        private val binding: RecipientItemImageMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: TextMessage) {
            binding.apply {
                this.message = message
                root.setOnLongClickListener {
                    onMessageClickListener?.onMessageLongClick(message)
                    true
                }

                if (message.isDeletedForEveryone) {
                    imageViewRecMessageImage.visibility = View.GONE
                } else {
                    imageViewRecMessageImage.visibility = View.VISIBLE
                    loadMessageImage(imageViewRecMessageImage, message)
                    imageViewRecMessageImage.setOnClickListener {
                        onMessageClickListener?.onImageClick(message)
                    }
                }
                executePendingBindings()
            }
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder.absoluteAdapterPosition == 0) {
            val animation = android.view.animation.AnimationUtils.loadAnimation(
                holder.itemView.context,
                R.anim.message_popup
            )
            holder.itemView.startAnimation(animation)
        }
    }

    inner class DateSeparatorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val labelView: TextView = itemView.findViewById(R.id.date_separator_text)

        fun bind(separator: ChatTimelineItem.DateSeparator) {
            labelView.text = when (val label = separator.label) {
                ChatDateLabel.Today -> itemView.context.getString(R.string.today)
                ChatDateLabel.Yesterday -> itemView.context.getString(R.string.yesterday)
                is ChatDateLabel.Absolute -> label.value
            }
        }
    }

    interface OnMessageClickListener {
        fun onMessageClick(message: TextMessage)
        fun onImageClick(message: TextMessage) {
            onMessageClick(message)
        }
        fun onRetryClick(message: TextMessage, onFinished: (queued: Boolean) -> Unit) {
            onMessageClick(message)
            onFinished(true)
        }
        fun onMessageLongClick(message: TextMessage) {}
    }

    private var onMessageClickListener: OnMessageClickListener? = null

    fun setOnMessageClickListener(listener: OnMessageClickListener) {
        this.onMessageClickListener = listener
    }
}
