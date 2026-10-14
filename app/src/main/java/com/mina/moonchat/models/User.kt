package com.mina.moonchat.models

import android.os.Parcelable
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

/**
 * Normalized User model supporting bidirectional compatibility with legacy Firestore schemas.
 * Firebase Authentication is the sole password holder; password is NEVER persisted to Firestore or Room.
 */
@IgnoreExtraProperties
@Parcelize
data class User(
    @get:PropertyName("userId") @set:PropertyName("userId") var userId: String = "",
    @get:PropertyName("displayName") @set:PropertyName("displayName") var displayName: String = "",
    @get:PropertyName("email") @set:PropertyName("email") var email: String = "",
    @get:Exclude @set:Exclude var password: String = "",
    @get:PropertyName("profileImg") @set:PropertyName("profileImg") var profileImg: String? = null,
    @get:PropertyName("onlineState") @set:PropertyName("onlineState") var onlineState: String = "",
    @get:PropertyName("bio") @set:PropertyName("bio") var bio: String = "",
    @get:PropertyName("id") @set:PropertyName("id") var id: String = "",
    @get:PropertyName("fcmToken") @set:PropertyName("fcmToken") var fcmToken: String = ""
) : Parcelable {

    // Compatibility Property Aliases for Legacy Firestore Fields

    @IgnoredOnParcel
    @get:PropertyName("username")
    @set:PropertyName("username")
    var usernameAlias: String? = null
        set(value) {
            field = value
            if (!value.isNullOrBlank() && displayName.isBlank()) {
                displayName = value
            }
        }

    @IgnoredOnParcel
    @get:PropertyName("name")
    @set:PropertyName("name")
    var nameAlias: String? = null
        set(value) {
            field = value
            if (!value.isNullOrBlank() && displayName.isBlank()) {
                displayName = value
            }
        }

    @IgnoredOnParcel
    @get:PropertyName("pfp")
    @set:PropertyName("pfp")
    var pfpAlias: String? = null
        set(value) {
            field = value
            if (!value.isNullOrBlank() && profileImg.isNullOrBlank()) {
                profileImg = value
            }
        }

    @IgnoredOnParcel
    @get:PropertyName("profilePic")
    @set:PropertyName("profilePic")
    var profilePicAlias: String? = null
        set(value) {
            field = value
            if (!value.isNullOrBlank() && profileImg.isNullOrBlank()) {
                profileImg = value
            }
        }

    @IgnoredOnParcel
    @get:PropertyName("onlineStatus")
    @set:PropertyName("onlineStatus")
    var onlineStatusAlias: String? = null
        set(value) {
            field = value
            if (!value.isNullOrBlank() && onlineState.isBlank()) {
                onlineState = value
            }
        }

    @IgnoredOnParcel
    @get:PropertyName("state")
    @set:PropertyName("state")
    var stateAlias: String? = null
        set(value) {
            field = value
            if (!value.isNullOrBlank() && onlineState.isBlank()) {
                onlineState = value
            }
        }

    @IgnoredOnParcel
    @get:PropertyName("uid")
    @set:PropertyName("uid")
    var uidAlias: String? = null
        set(value) {
            field = value
            if (!value.isNullOrBlank() && userId.isBlank()) {
                userId = value
            }
        }

    /**
     * Resolves display name with fallback to username, name, email prefix, or default
     */
    @Exclude
    fun getResolvedName(): String {
        return displayName.ifBlank {
            usernameAlias?.takeIf { it.isNotBlank() }
                ?: nameAlias?.takeIf { it.isNotBlank() }
                ?: email.substringBefore("@").takeIf { it.isNotBlank() }
                ?: "MoonChat User"
        }
    }

    /**
     * Resolves profile image URL with fallback to pfp or profilePic
     */
    @Exclude
    fun getResolvedProfileImg(): String? {
        return profileImg ?: pfpAlias ?: profilePicAlias
    }

    /**
     * Resolves online status string with fallback
     */
    @Exclude
    fun getResolvedOnlineState(): String {
        return onlineState.ifBlank {
            onlineStatusAlias?.takeIf { it.isNotBlank() }
                ?: stateAlias?.takeIf { it.isNotBlank() }
                ?: "Offline"
        }
    }

    /**
     * Converts User to a complete Firestore map containing both canonical
     * and legacy compatibility keys for 100% backward/forward compatibility.
     * Note: Cleartext password is explicitly EXCLUDED to satisfy security guidelines.
     */
    @Exclude
    fun toFirestoreMap(): Map<String, Any?> {
        val resolvedName = getResolvedName()
        val resolvedPfp = getResolvedProfileImg()
        val resolvedStatus = getResolvedOnlineState()

        return hashMapOf(
            "userId" to userId,
            "uid" to userId,
            "displayName" to resolvedName,
            "username" to resolvedName,
            "name" to resolvedName,
            "email" to email,
            "profileImg" to resolvedPfp,
            "pfp" to resolvedPfp,
            "onlineState" to resolvedStatus,
            "onlineStatus" to resolvedStatus,
            "bio" to bio,
            "id" to id,
            "fcmToken" to fcmToken
        )
    }
}

/**
 * Extension function to parse DocumentSnapshot safely with document ID fallback and alias resolution
 */
fun DocumentSnapshot.toNormalizedUser(): User? {
    if (!exists()) return null
    val user = toObject(User::class.java) ?: User()

    if (user.userId.isBlank()) {
        user.userId = id
    }

    user.displayName = user.getResolvedName()
    user.profileImg = user.getResolvedProfileImg()
    user.onlineState = user.getResolvedOnlineState()

    if (user.id.isBlank()) {
        user.id = "#${user.displayName.replace(" ", "")}"
    }

    return user
}
