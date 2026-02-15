package com.tgweb.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "media_files", indices = [Index(value = ["local_path", "file_id"], unique = false)])
data class MediaFileEntity(
    @PrimaryKey @ColumnInfo(name = "file_id") val fileId: String,
    @ColumnInfo(name = "remote_id") val remoteId: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "local_path") val localPath: String,
    @ColumnInfo(name = "last_accessed_at") val lastAccessedAt: Long,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
)
