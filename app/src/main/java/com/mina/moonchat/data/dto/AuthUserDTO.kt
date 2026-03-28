package com.mina.moonchat.data.dto

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

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
