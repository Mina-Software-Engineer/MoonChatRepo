package com.mina.moonchat.data.dto

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mina.moonchat.models.User
import java.util.*

@Entity(tableName = "UserEntity")
data class UserInfoDTO(
    @PrimaryKey @ColumnInfo(name = "entry_id")
    val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "name")
    var username: String,
    @ColumnInfo(name = "pfp")
    var pfp: String,
    @ColumnInfo(name = "onlineStatus")
    var onlineStatus: String,
    @ColumnInfo(name = "bio")
    var bio: String
)

fun User.toUserInfoDTO(): UserInfoDTO = UserInfoDTO(
    id = userId.ifBlank { id },
    username = getResolvedName(),
    pfp = getResolvedProfileImg().orEmpty(),
    onlineStatus = getResolvedOnlineState(),
    bio = bio
)

fun UserInfoDTO.toUser(): User = User(
    userId = id,
    displayName = username,
    profileImg = pfp,
    onlineState = onlineStatus,
    bio = bio,
    id = id
)
