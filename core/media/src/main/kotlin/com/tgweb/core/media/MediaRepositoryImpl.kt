package com.tgweb.core.media

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.data.MediaRepository
import java.io.File

class MediaRepositoryImpl(
    private val context: Context,
    private val cacheManager: MediaCacheManager,
) : MediaRepository {

    override suspend fun getMediaFile(fileId: String): Result<String> {
        val path = cacheManager.resolve(fileId)
        if (path == null) {
            AppRepositories.postDownloadProgress(fileId = fileId, percent = 0, error = "cache_miss")
            return Result.failure(IllegalStateException("No cached media for $fileId"))
        }
        AppRepositories.postDownloadProgress(fileId = fileId, percent = 100, localUri = path)
        return Result.success(path)
    }

    override suspend fun prefetch(chatId: Long, window: Int) {
        // Placeholder for TDLib file prefetching rules.
    }

    override suspend fun downloadToPublicStorage(fileId: String, targetCollection: String): Result<String> {
        AppRepositories.postDownloadProgress(fileId = fileId, percent = 0)
        val path = cacheManager.resolve(fileId)
            ?: return Result.failure(IllegalStateException("No cached media for $fileId"))
        val source = File(path)
        if (!source.exists()) {
            return Result.failure(IllegalStateException("Cached file missing"))
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/tgweb")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return Result.failure(IllegalStateException("Cannot create download entry"))

        context.contentResolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)

        AppRepositories.postDownloadProgress(fileId = fileId, percent = 100, localUri = uri.toString())
        return Result.success(uri.toString())
    }

    override suspend fun clearCache(scope: String) {
        if (scope == "all" || scope == "media") {
            cacheManager.clearAll()
        }
    }
}
