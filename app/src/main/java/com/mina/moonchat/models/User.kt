package com.mina.moonchat.models

import android.os.Parcelable
import com.mina.moonchat.data.dto.AuthUserDTO
import kotlinx.android.parcel.Parcelize

/**
 * Data class that captures user information for logged in user
 */
@Parcelize
data class User(
    var userId: String = "",
    var displayName: String = "",
    var email: String = "",
    var password: String = "",
    var profileImg: String? = null,
    var onlineState: String = "",
    var bio: String = "",
    var id: String = "",
    var fcmToken: String = ""
): Parcelable
