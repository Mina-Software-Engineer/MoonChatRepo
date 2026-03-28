package com.mina.moonchat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mina.moonchat.data.dto.UserInfoDTO


@Dao
interface UserDao {

    @Query("SELECT * FROM UserEntity")
    suspend fun getChatsList(): List<UserInfoDTO>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveChat(user: UserInfoDTO)
}