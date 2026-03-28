package com.mina.moonchat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mina.moonchat.data.dto.AuthUserDTO

@Dao
interface AuthDao {

  @Query("SELECT * FROM AuthEntity")
  suspend fun getCurrentUserInfo(): AuthUserDTO

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun addCurrentUserInfo(currentUser: AuthUserDTO)

  @Query("DELETE FROM AuthEntity")
  suspend fun deleteCurrentUserInfo()
}