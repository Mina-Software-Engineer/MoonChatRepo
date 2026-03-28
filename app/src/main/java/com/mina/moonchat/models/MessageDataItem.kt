package com.mina.moonchat.models

import android.os.Parcelable
import com.mina.moonchat.data.dto.MessagesDTO
import kotlinx.android.parcel.Parcelize
import java.util.*
@Parcelize
class MessageDataItem(
    var recipientId: String,
    var recipientName: String,
    var senderId: String,
    var senderName: String,
    val messageText: String,
    var messageType: String,
    //val messageId: String = UUID.randomUUID().toString(),
    //var messageTime: String,
): Parcelable{
    constructor(): this("", "", "", "", "", ""){}
}

