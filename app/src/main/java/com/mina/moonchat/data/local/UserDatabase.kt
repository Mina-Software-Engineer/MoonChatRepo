package com.mina.moonchat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.mina.moonchat.data.dto.AuthUserDTO
import com.mina.moonchat.data.dto.MessagesDTO
import com.mina.moonchat.data.dto.UserInfoDTO


private const val DATABASE_NAME = "asteroids_database"

@Database(entities = [UserInfoDTO::class, AuthUserDTO::class, MessagesDTO::class], version = 1, exportSchema = false)
abstract class UserDatabase: RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun authDao(): AuthDao
    abstract fun messageDao(): MessageDao


    companion object {
        @Volatile
        private var INSTANCE: UserDatabase? = null

        fun getDatabase(
            context: Context
        ): UserDatabase {
            // if the INSTANCE is not null, then return it,
            // if it is, then create the database
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    UserDatabase::class.java,
                    DATABASE_NAME
                ).build()

                INSTANCE = instance
                // return instance
                instance
            }
        }


    }
}