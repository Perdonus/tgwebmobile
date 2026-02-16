package com.tgweb.core.media

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.tgweb.core.db.dao.MediaDao
import com.tgweb.core.db.entity.MediaFileEntity
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class MediaCacheManager(
    context: Context,
    private val mediaDao: MediaDao,
    private val maxBytes: Long = 5L * 1024L * 1024L * 1024L,
) {
    private val appContext = context.applicationContext
    private val cacheDir = File(context.filesDir, "media_cache").apply { mkdirs() }
    private val masterKeyAlias = MasterKey.DEFAULT_MASTER_KEY_ALIAS

    init {
        MasterKey.Builder(appContext, masterKeyAlias)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    suspend fun cache(
        fileId: String,
        mimeType: String,
        bytes: Long,
        source: InputStream,
        isPinned: Boolean = false,
        fileName: String? = null,
        onProgress: ((Long) -> Unit)? = null,
    ): String {
        val target = File(cacheDir, buildCacheFileName(fileId, mimeType, fileName))
        val encryptedFile = EncryptedFile.Builder(
            target,
            appContext,
            masterKeyAlias,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

        encryptedFile.openFileOutput().use { rawOutput: FileOutputStream ->
            BufferedOutputStream(rawOutput).use { output ->
                BufferedInputStream(source).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress?.invoke(copied)
                    }
                    output.flush()
                }
            }
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

    private fun buildCacheFileName(fileId: String, mimeType: String, rawFileName: String?): String {
        val safeBase = rawFileName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            ?.takeIf { it.isNotBlank() }
            ?: fileId.replace(Regex("[^a-zA-Z0-9._-]"), "_")

        val hasExtension = safeBase.contains('.')
        val extension = when {
            hasExtension -> ""
            mimeType.contains("jpeg", ignoreCase = true) -> ".jpg"
            mimeType.contains("png", ignoreCase = true) -> ".png"
            mimeType.contains("gif", ignoreCase = true) -> ".gif"
            mimeType.contains("mp4", ignoreCase = true) -> ".mp4"
            mimeType.contains("webm", ignoreCase = true) -> ".webm"
            mimeType.contains("mp3", ignoreCase = true) -> ".mp3"
            mimeType.contains("ogg", ignoreCase = true) -> ".ogg"
            mimeType.contains("pdf", ignoreCase = true) -> ".pdf"
            else -> ".bin"
        }
        return "${UUID.randomUUID()}_${safeBase}$extension"
    }

    suspend fun resolve(fileId: String): String? {
        val media = mediaDao.get(fileId) ?: return null
        mediaDao.touch(fileId, System.currentTimeMillis())
        return media.localPath
    }

    suspend fun pin(fileId: String, isPinned: Boolean = true) {
        mediaDao.setPinned(fileId = fileId, isPinned = isPinned)
    }

    suspend fun remove(fileId: String): Boolean {
        val media = mediaDao.get(fileId) ?: return false
        runCatching { File(media.localPath).delete() }
        mediaDao.delete(fileId)
        return true
    }

    suspend fun clearAll() {
        cacheDir.listFiles()?.forEach { it.delete() }
        mediaDao.deleteAll()
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
