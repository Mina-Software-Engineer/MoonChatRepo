package com.mina.moonchat.data.local

import android.content.Context
import androidx.room.Room

object LocalDB {
    fun createUserDao(context: Context): UserDao{
        return Room.databaseBuilder(
            context.applicationContext,
            UserDatabase::class.java,
            "UserDB"
        ).build().userDao()
    }
}