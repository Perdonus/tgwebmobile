package com.tgweb.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.tgweb.core.db.dao.ChatDao
import com.tgweb.core.db.dao.MediaDao
import com.tgweb.core.db.dao.MessageDao
import com.tgweb.core.db.dao.PushTokenDao
import com.tgweb.core.db.dao.SyncStateDao
import com.tgweb.core.db.entity.ChatEntity
import com.tgweb.core.db.entity.DownloadTaskEntity
import com.tgweb.core.db.entity.MediaFileEntity
import com.tgweb.core.db.entity.MessageEntity
import com.tgweb.core.db.entity.PushTokenEntity
import com.tgweb.core.db.entity.SyncStateEntity
import com.tgweb.core.db.entity.UserEntity
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [
        ChatEntity::class,
        MessageEntity::class,
        UserEntity::class,
        MediaFileEntity::class,
        DownloadTaskEntity::class,
        SyncStateEntity::class,
        PushTokenEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class TelegramDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun mediaDao(): MediaDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun pushTokenDao(): PushTokenDao
}

object TelegramDatabaseFactory {
    fun create(context: Context, passphrase: String): TelegramDatabase {
        val factory: SupportSQLiteOpenHelper.Factory =
            SupportFactory(SQLiteDatabase.getBytes(passphrase.toCharArray()))

        return Room.databaseBuilder(context, TelegramDatabase::class.java, "tgweb.db")
            .openHelperFactory(factory)
            .fallbackToDestructiveMigration()
            .build()
    }
}
