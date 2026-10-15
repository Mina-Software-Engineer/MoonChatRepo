package com.mina.moonchat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mina.moonchat.data.dto.AuthUserDTO
import com.mina.moonchat.data.dto.MessagesDTO
import com.mina.moonchat.data.dto.UserInfoDTO


private const val DATABASE_NAME = "asteroids_database"

val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE MessageEntity ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE MessageEntity ADD COLUMN last_attempt_time INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE MessageEntity ADD COLUMN media_json TEXT")
        database.execSQL("ALTER TABLE MessageEntity ADD COLUMN local_image_path TEXT")
        database.execSQL("ALTER TABLE MessageEntity ADD COLUMN local_image_mime_type TEXT")
        database.execSQL("ALTER TABLE MessageEntity ADD COLUMN local_image_size INTEGER")
        database.execSQL("ALTER TABLE MessageEntity ADD COLUMN local_image_width INTEGER")
        database.execSQL("ALTER TABLE MessageEntity ADD COLUMN local_image_height INTEGER")
    }
}

@Database(entities = [UserInfoDTO::class, AuthUserDTO::class, MessagesDTO::class], version = 4, exportSchema = false)
@TypeConverters(Converters::class)
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
                )
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance
                // return instance
                instance
            }
        }


    }
}
