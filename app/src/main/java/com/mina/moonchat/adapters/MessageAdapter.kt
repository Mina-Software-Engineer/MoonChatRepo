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
                        //statusIcon.visibility = View.VISIBLE
                        //statusIcon.setImageResource(android.R.drawable.ic_menu_recent_history)
                    }
                    message.isFailed() -> {
                        // Show failed indicator (e.g., error icon)
                        //statusIcon.visibility = View.VISIBLE
                        //statusIcon.setImageResource(android.R.drawable.ic_dialog_alert)
                        // Make clickable to retry
                        root.setOnClickListener {
                            onMessageClickListener?.onMessageClick(message)
                        }
                    }
                    else -> {
                        // Message sent successfully - hide or show checkmark
                        //statusIcon.visibility = View.GONE
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



/*
private const val MSG_TYPE_LEFT = 0
private const val MSG_TYPE_RIGHT = 1


class MessageAdapter : PagingDataAdapter<TextMessage, RecyclerView.ViewHolder>(COMPARATOR) {

    // Firebase Authentication instance for current user reference
    private val mAuth: FirebaseAuth by lazy {
        FirebaseAuth.getInstance()
    }

    // Create new view based on message type (sender or recipient)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        //Log.d("checckk", "This is last message from Paging source: $viewType")
        return MessagesViewHolder.create(parent, viewType)
    }

    // Bind message data to the views
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val messageItem = getItem(position)
        //Log.d("checckk", "This is last message from Paging source: $messageItem")
        if ((messageItem != null) && position != 0 && ((messageItem as TextMessage).id != getItem(position - 1)!!.id)) {
            (holder as MessagesViewHolder).bind(messageItem as TextMessage, getItemViewType(position))
        }

        // Scroll to the bottom when a new message is added
        //recyclerView.scrollToPosition(messages.size - 1)
    }

    // Determine message type based on sender
    override fun getItemViewType(position: Int): Int {
        return if (getItem(position)!!.senderId == mAuth.currentUser?.uid) {
            MSG_TYPE_RIGHT
        } else {
            MSG_TYPE_LEFT
        }
    }

    companion object {
        private val COMPARATOR = object : DiffUtil.ItemCallback<TextMessage>() {
            override fun areItemsTheSame(oldItem: TextMessage, newItem: TextMessage): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: TextMessage, newItem: TextMessage): Boolean =
                oldItem == newItem
        }
    }
}

class MessagesViewHolder(private var binding: ViewBinding) : RecyclerView.ViewHolder(binding.root) {

    private val messageText: TextView = itemView.findViewById(R.id.text_view_message)
    private val messageTime: TextView = itemView.findViewById(R.id.text_view_time)

    companion object {
        fun create(view: ViewGroup, viewType: Int): MessagesViewHolder {
            val inflater = LayoutInflater.from(view.context)
            val binding = if (viewType == MSG_TYPE_RIGHT) {
                SenderItemTextMessageBinding.inflate(inflater, view, false)
            } else {
                RecipientItemTextMessageBinding.inflate(inflater, view, false)
            }
            return MessagesViewHolder(binding)
        }
    }

    // Bind message data to the UI elements
    fun bind(message: TextMessage, viewType: Int) {
        if (viewType == MSG_TYPE_RIGHT) {
            //messageText.text = message.text
            //messageTime.text = message.date.toString()
            (binding as SenderItemTextMessageBinding).message = message
            //(binding as SenderItemTextMessageBinding).textViewMessage.text = message.text
            //(binding as SenderItemTextMessageBinding).textViewTime.text = message.date.toString()
        } else {
            //messageText.text = message.text
            //messageTime.text = message.date.toString()
            (binding as RecipientItemTextMessageBinding).message = message
            //(binding as RecipientItemTextMessageBinding).textViewMessage.text = message.text
            //(binding as RecipientItemTextMessageBinding).textViewTime.text = message.date.toString()
        }
        //Log.d("checkk", "This is last message from db: ${message.text}")

        //messageTime.text = message.date.toString() // Format date as needed
    }


}
*/


/*class ChatListAdapter : RecyclerView.Adapter<ChatListAdapter.ViewHolder>() {

    private val messages: MutableList<TextMessage> = mutableListOf()

    // Firebase Authentication instance for current user reference
    private val mAuth: FirebaseAuth by lazy {
        FirebaseAuth.getInstance()
    }

    // Create new view based on message type (sender or recipient)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = if (viewType == MSG_TYPE_RIGHT) {
            LayoutInflater.from(parent.context)
                .inflate(R.layout.sender_item_text_message, parent, false)
        } else {
            LayoutInflater.from(parent.context)
                .inflate(R.layout.recipient_item_text_message, parent, false)
        }
        return ViewHolder(view)
    }

    // Bind message data to the views
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = messages[position]

        holder.bind(message)
    }

    // Adds a message to the list and updates the RecyclerView
    fun addMessage(message: TextMessage, recyclerView: RecyclerView) {
        *//*for(msg in messages){
            if(message.date.time.toInt() != msg.date.time.toInt()){
                messages.add(message)
            }
        }*//*

        messages.add(message)
        notifyItemInserted(messages.size - 1)

        // Scroll to the bottom when a new message is added
        recyclerView.scrollToPosition(messages.size - 1)

        //messages.clear()
    }

    override fun getItemCount(): Int = messages.size

    // Determine message type based on sender
    override fun getItemViewType(position: Int): Int {
        return if (messages[position].senderId == mAuth.currentUser?.uid) {
            MSG_TYPE_RIGHT
        } else {
            MSG_TYPE_LEFT
        }
    }

    // ViewHolder to display message content and timestamp
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.text_view_message)
        private val messageTime: TextView = itemView.findViewById(R.id.text_view_time)

        // Bind message data to the UI elements
        fun bind(message: TextMessage) {
            messageText.text = message.text
            messageTime.text = message.date.toString() // Format date as needed
        }
    }
}*/


/*
package com.mina.moonchat.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.mina.moonchat.R
import com.mina.moonchat.models.TextMessage

private const val MSG_TYPE_LEFT = 0
private const val MSG_TYPE_RIGHT = 1

class ChatListAdapter : RecyclerView.Adapter<ChatListAdapter.ViewHolder>() {

    private val messages: MutableList<TextMessage> = mutableListOf()

    //current logged in user reference
    private val mAuth: FirebaseAuth by lazy {       //instead of typing val mAuth: FirebaseAuth? = null
        FirebaseAuth.getInstance()                  //mAuth = FirebaseAuth.getInstance()
    }


    // create new views
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // inflates the card_view_design view
        // that is used to hold list item
        //Log.d("checkkkk", "List is: $mList")
        return if (viewType == MSG_TYPE_RIGHT) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.sender_item_text_message, parent, false)
            ViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.recipient_item_text_message, parent, false)
            ViewHolder(view)
        }

    }

    // binds the list items to a view
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val messageInfo = messages[position]
        holder.messageText.text = messageInfo.text
        holder.messageTime.text = messageInfo.date.toString()
    }

    fun addMessage(message: TextMessage) {
        messages.add(message)
        notifyItemInserted(itemCount - 1)
    }

    override fun getItemCount(): Int {
        return messages.size
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].senderId == mAuth.currentUser!!.uid)
            MSG_TYPE_RIGHT
        else
            MSG_TYPE_LEFT
    }

    class ViewHolder(ItemView: View) : RecyclerView.ViewHolder(ItemView) {
        val messageText: TextView = itemView.findViewById(R.id.text_view_message)
        val messageTime: TextView = itemView.findViewById(R.id.text_view_time)
    }
}*/
