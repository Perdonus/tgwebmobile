package com.tgweb.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tgweb.core.db.entity.PushTokenEntity

@Dao
interface PushTokenDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PushTokenEntity)

    @Query("SELECT * FROM push_tokens WHERE device_id = :deviceId LIMIT 1")
    suspend fun get(deviceId: String): PushTokenEntity?
}
