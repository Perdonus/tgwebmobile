package com.tgweb.core.media

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.tgweb.core.db.dao.MediaDao
import com.tgweb.core.db.entity.MediaFileEntity
import java.io.File
import java.io.InputStream
import java.util.UUID

class MediaCacheManager(
    context: Context,
    private val mediaDao: MediaDao,
    private val maxBytes: Long = 5L * 1024L * 1024L * 1024L,
) {
    private val appContext = context.applicationContext
    private val cacheDir = File(context.filesDir, "media_cache").apply { mkdirs() }
    private val masterKey = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()

    suspend fun cache(fileId: String, mimeType: String, bytes: Long, source: InputStream, isPinned: Boolean = false): String {
        val target = File(cacheDir, "${UUID.randomUUID()}_$fileId.bin")
        val encryptedFile = EncryptedFile.Builder(
            target,
            appContext,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

        encryptedFile.openFileOutput().use { output ->
            source.copyTo(output)
        }

        mediaDao.upsert(
            MediaFileEntity(
                fileId = fileId,
                remoteId = fileId,
                mimeType = mimeType,
                sizeBytes = bytes,
                localPath = target.absolutePath,
                lastAccessedAt = System.currentTimeMillis(),
                isPinned = isPinned,
            )
        )
        evictIfNeeded()
        return target.absolutePath
    }

    suspend fun resolve(fileId: String): String? {
        val media = mediaDao.get(fileId) ?: return null
        mediaDao.touch(fileId, System.currentTimeMillis())
        return media.localPath
    }

    suspend fun clearAll() {
        cacheDir.listFiles()?.forEach { it.delete() }
        mediaDao.listForEviction().forEach { mediaDao.delete(it.fileId) }
    }

    private suspend fun evictIfNeeded() {
        var total = mediaDao.totalSize()
        if (total <= maxBytes) return

        val candidates = mediaDao.listForEviction()
        for (item in candidates) {
            if (total <= maxBytes) break
            File(item.localPath).delete()
            mediaDao.delete(item.fileId)
            total -= item.sizeBytes
        }
    }
}
