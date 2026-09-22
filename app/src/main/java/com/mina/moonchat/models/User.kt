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

//converting the Asteroid Domain Model list to Asteroid Entity list
fun List<User>.asAsteroidEntityModel(): List<AuthUserDTO> {
    return map {
        AuthUserDTO(
        userId = it.userId,
        username = it.displayName,
        email = it.email,
        password = it.password,
        pfp = it.profileImg,
        onlineStatus = it.onlineState,
        bio = it.bio,
        id = it.id
        )
    }
}
