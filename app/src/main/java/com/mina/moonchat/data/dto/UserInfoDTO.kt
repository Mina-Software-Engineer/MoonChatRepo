package com.mina.moonchat.data.dto

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
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
