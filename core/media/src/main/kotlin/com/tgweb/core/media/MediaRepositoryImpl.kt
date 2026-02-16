package com.tgweb.core.media

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.data.MediaRepository
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

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

    override suspend fun cacheRemoteFile(fileId: String, url: String, mimeType: String, fileName: String?): Result<String> {
        val existing = cacheManager.resolve(fileId)
        if (existing != null) {
            AppRepositories.postDownloadProgress(fileId = fileId, percent = 100, localUri = existing)
            return Result.success(existing)
        }

        if (url.isBlank()) {
            AppRepositories.postDownloadProgress(fileId = fileId, percent = 0, error = "missing_url")
            return Result.failure(IllegalArgumentException("Remote url is empty"))
        }

        AppRepositories.postDownloadProgress(fileId = fileId, percent = 0)
        showDownloadNotification(fileId, fileName ?: fileId, 0, inProgress = true)
        return runCatching {
            val connection = openConnection(url)
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.connect()
            val resolvedMime = mimeType.ifBlank { connection.contentType ?: "application/octet-stream" }
            val sizeBytes = connection.contentLengthLong.coerceAtLeast(0L)
            var lastProgress = -1
            val path = connection.inputStream.use { input ->
                cacheManager.cache(
                    fileId = fileId,
                    mimeType = resolvedMime,
                    bytes = sizeBytes,
                    source = input,
                    isPinned = false,
                    fileName = fileName,
                    onProgress = { copied ->
                        if (sizeBytes > 0L) {
                            val progress = ((copied * 100) / sizeBytes).toInt().coerceIn(0, 100)
                            if (progress != lastProgress && (progress == 100 || progress - lastProgress >= 3)) {
                                lastProgress = progress
                                AppRepositories.postDownloadProgress(fileId = fileId, percent = progress)
                                showDownloadNotification(fileId, fileName ?: fileId, progress, inProgress = progress < 100)
                            }
                        }
                    },
                )
            }
            connection.disconnect()
            AppRepositories.postDownloadProgress(fileId = fileId, percent = 100, localUri = path)
            showDownloadNotification(fileId, fileName ?: fileId, 100, inProgress = false)
            path
        }.onFailure {
            AppRepositories.postDownloadProgress(fileId = fileId, percent = 0, error = "download_failed")
            showDownloadNotification(fileId, fileName ?: fileId, 0, inProgress = false, failed = true)
        }
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

        val relativeFolder = targetCollection
            .trim()
            .ifBlank { "tgweb_downloads" }
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$relativeFolder")
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
        showDownloadNotification(fileId, source.name, 100, inProgress = false)
        return Result.success(uri.toString())
    }

    override suspend fun removeCachedFile(fileId: String): Boolean {
        return cacheManager.remove(fileId)
    }

    override suspend fun clearCache(scope: String) {
        if (scope == "all" || scope == "media") {
            cacheManager.clearAll()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val proxyState = AppRepositories.getProxyState()
        val proxy = when {
            !proxyState.enabled -> Proxy.NO_PROXY
            proxyState.type == com.tgweb.core.webbridge.ProxyType.HTTP -> {
                Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyState.host, proxyState.port))
            }
            proxyState.type == com.tgweb.core.webbridge.ProxyType.SOCKS5 ||
                proxyState.type == com.tgweb.core.webbridge.ProxyType.MTPROTO -> {
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyState.host, proxyState.port))
            }
            else -> Proxy.NO_PROXY
        }
        return URL(url).openConnection(proxy) as HttpURLConnection
    }

    private fun showDownloadNotification(
        fileId: String,
        title: String,
        progress: Int,
        inProgress: Boolean,
        failed: Boolean = false,
    ) {
        val builder = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!inProgress)
            .setContentTitle(title.take(80))
            .setColor(0xFF3390EC.toInt())
            .setColorized(true)

        when {
            failed -> {
                builder
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentText("Download failed")
                    .setProgress(0, 0, false)
            }
            inProgress -> {
                builder
                    .setContentText("Downloading... $progress%")
                    .setProgress(100, progress.coerceIn(0, 100), false)
            }
            else -> {
                builder
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentText("Download complete")
                    .setProgress(0, 0, false)
            }
        }

        NotificationManagerCompat.from(context).notify(fileId.hashCode(), builder.build())
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val DOWNLOAD_CHANNEL_ID = "tgweb_downloads"
    }
}
