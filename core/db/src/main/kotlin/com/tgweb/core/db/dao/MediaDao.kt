package com.tgweb.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tgweb.core.db.entity.MediaFileEntity

@Dao
interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(media: MediaFileEntity)

    @Query("SELECT * FROM media_files WHERE file_id = :fileId LIMIT 1")
    suspend fun get(fileId: String): MediaFileEntity?

    @Query("UPDATE media_files SET last_accessed_at = :timestamp WHERE file_id = :fileId")
    suspend fun touch(fileId: String, timestamp: Long)

    @Query("SELECT * FROM media_files WHERE is_pinned = 0 ORDER BY last_accessed_at ASC")
    suspend fun listForEviction(): List<MediaFileEntity>

    @Query("DELETE FROM media_files WHERE file_id = :fileId")
    suspend fun delete(fileId: String)

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM media_files")
    suspend fun totalSize(): Long
}
