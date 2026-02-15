package com.tgweb.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "push_tokens")
data class PushTokenEntity(
    @PrimaryKey @ColumnInfo(name = "device_id") val deviceId: String,
    val token: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
