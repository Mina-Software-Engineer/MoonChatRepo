package com.mina.moonchat.data.dto

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mina.moonchat.models.User

@Entity(tableName = "AuthEntity")
data class AuthUserDTO(
    @PrimaryKey
    @ColumnInfo(name = "auth_id")
    val userId: String,
    @ColumnInfo(name = "name")
    var username: String,
    @ColumnInfo(name = "email")
    var email: String,
    @ColumnInfo(name = "password")
    var password: String,
    @ColumnInfo(name = "pfp")
    var pfp: String?,
    @ColumnInfo(name = "onlineStatus")
    var onlineStatus: String,
    @ColumnInfo(name = "bio")
    var bio: String,
    @ColumnInfo(name = "user_id")
    var id: String
)

fun User.toAuthUserDTO(): AuthUserDTO = AuthUserDTO(
    userId = userId,
    username = getResolvedName(),
    email = email,
    password = password,
    pfp = getResolvedProfileImg(),
    onlineStatus = getResolvedOnlineState(),
    bio = bio,
    id = id
)

fun AuthUserDTO.toUser(): User = User(
    userId = userId,
    displayName = username,
    email = email,
    password = password,
    profileImg = pfp,
    onlineState = onlineStatus,
    bio = bio,
    id = id
)
