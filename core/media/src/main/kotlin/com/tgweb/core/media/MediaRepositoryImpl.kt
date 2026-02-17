package com.tgweb.core.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.data.DebugLogStore
import com.tgweb.core.data.MediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.zip.ZipFile

class MediaRepositoryImpl(
    private val context: Context,
    private val cacheManager: MediaCacheManager,
) : MediaRepository {

    private data class ContentRangeInfo(
        val start: Long,
        val endInclusive: Long,
        val total: Long?,
    )

    private data class SegmentAccumulator(
        val tempFile: File,
        var totalBytes: Long,
        var mimeType: String,
        var fileName: String?,
        val ranges: MutableList<LongRange>,
        var updatedAt: Long,
    )

    private data class SegmentSnapshot(
        val tempFile: File,
        val totalBytes: Long,
        val coveredBytes: Long,
        val mimeType: String,
        val fileName: String?,
        val complete: Boolean,
    )

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
        DebugLogStore.log(
            "DOWNLOAD",
            "cacheRemoteFile start fileId=$fileId url=${url.take(360)} mime=$mimeType name=${fileName.orEmpty()}",
        )
        val existing = cacheManager.resolve(fileId)
        if (existing != null) {
            DebugLogStore.log("DOWNLOAD", "cacheRemoteFile cache hit fileId=$fileId localUri=$existing")
            AppRepositories.postDownloadProgress(fileId = fileId, percent = 100, localUri = existing)
            return Result.success(existing)
        }

        if (url.isBlank()) {
            DebugLogStore.log("DOWNLOAD", "cacheRemoteFile failed: missing url for fileId=$fileId")
            AppRepositories.postDownloadProgress(fileId = fileId, percent = 0, error = "missing_url")
            return Result.failure(IllegalArgumentException("Remote url is empty"))
        }

        AppRepositories.postDownloadProgress(fileId = fileId, percent = 0)
        showDownloadNotification(fileId, fileName ?: fileId, 0, inProgress = true)

        return runCatching {
            var requestUrl = url
            var redirects = 0
            var connection: HttpURLConnection

            while (true) {
                connection = openConfiguredConnection(requestUrl)
                val responseCode = connection.responseCode
                DebugLogStore.log(
                    "DOWNLOAD_HTTP",
                    "HTTP $responseCode url=$requestUrl contentType=${connection.contentType.orEmpty()} len=${connection.contentLengthLong}",
                )

                if (responseCode in 300..399 && redirects < MAX_REDIRECTS) {
                    val location = connection.getHeaderField("Location").orEmpty().trim()
                    val nextUrl = if (location.isBlank()) {
                        null
                    } else {
                        runCatching { URL(URL(requestUrl), location).toString() }.getOrNull()
                    }
                    connection.disconnect()
                    if (nextUrl.isNullOrBlank()) break
                    DebugLogStore.log("DOWNLOAD_HTTP", "Redirect $requestUrl -> $nextUrl")
                    requestUrl = nextUrl
                    redirects += 1
                    continue
                }

                if (responseCode !in 200..299) {
                    connection.disconnect()
                    throw IllegalStateException("HTTP $responseCode for media download")
                }

                val contentType = connection.contentType?.substringBefore(';')?.trim().orEmpty()
                if (isLikelyHtmlResponse(contentType)) {
                    val htmlPayload = runCatching {
                        connection.inputStream.bufferedReader().use { reader ->
                            reader.readText().take(MAX_HTML_SNIFF)
                        }
                    }.getOrDefault("")
                    val htmlRedirect = extractRedirectUrlFromHtml(htmlPayload, requestUrl)
                    connection.disconnect()
                    if (!htmlRedirect.isNullOrBlank() && redirects < MAX_REDIRECTS) {
                        requestUrl = htmlRedirect
                        redirects += 1
                        continue
                    }
                    throw IllegalStateException("Unexpected HTML response for media download")
                }
                break
            }

            val serverMime = connection.contentType?.substringBefore(';')?.trim().orEmpty()
            val resolvedMime = resolveMimeType(requested = mimeType, detected = serverMime)
            val headerName = URLUtil.guessFileName(
                requestUrl,
                connection.getHeaderField("Content-Disposition"),
                resolvedMime,
            )
            val finalFileName = chooseFileName(provided = fileName, detected = headerName)
            val contentLength = connection.contentLengthLong.coerceAtLeast(0L)
            DebugLogStore.log(
                "DOWNLOAD_HTTP",
                "Resolved file fileId=$fileId finalName=$finalFileName provided=${fileName.orEmpty()} detected=$headerName mime=$resolvedMime len=$contentLength",
            )

            var lastProgress = -1
            val path = connection.inputStream.use { input ->
                cacheManager.cache(
                    fileId = fileId,
                    mimeType = resolvedMime,
                    bytes = contentLength,
                    source = input,
                    isPinned = false,
                    fileName = finalFileName,
                    onProgress = { copied ->
                        if (contentLength > 0L) {
                            val progress = ((copied * 100) / contentLength).toInt().coerceIn(0, 100)
                            if (progress != lastProgress && (progress == 100 || progress - lastProgress >= 3)) {
                                lastProgress = progress
                                AppRepositories.postDownloadProgress(fileId = fileId, percent = progress)
                                showDownloadNotification(fileId, finalFileName, progress, inProgress = progress < 100)
                                if (progress % 10 == 0 || progress >= 97) {
                                    DebugLogStore.log("DOWNLOAD", "Progress fileId=$fileId percent=$progress")
                                }
                            }
                        }
                    },
                )
            }
            connection.disconnect()

            val writtenBytes = runCatching { File(path).length() }.getOrDefault(0L)
            if (writtenBytes <= 0L) {
                throw IllegalStateException("Downloaded file is empty")
            }
            if (
                contentLength in 1 until MIN_VALID_FILE_SIZE &&
                resolvedMime.startsWith("text/") &&
                !finalFileName.endsWith(".txt", ignoreCase = true)
            ) {
                throw IllegalStateException("Download payload looks incomplete")
            }

            AppRepositories.postDownloadProgress(fileId = fileId, percent = 100, localUri = path)
            showDownloadNotification(fileId, finalFileName, 100, inProgress = false)
            DebugLogStore.log("DOWNLOAD", "cacheRemoteFile success fileId=$fileId localUri=$path bytes=$writtenBytes")
            path
        }.onFailure {
            DebugLogStore.logError("DOWNLOAD", "cacheRemoteFile failure fileId=$fileId", it)
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
            .ifBlank { "flygram_downloads" }
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$relativeFolder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return Result.failure(IllegalStateException("Cannot create download entry"))
        DebugLogStore.log("DOWNLOAD", "Export to public start fileId=$fileId uri=$uri")

        context.contentResolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)

        AppRepositories.postDownloadProgress(fileId = fileId, percent = 100, localUri = uri.toString())
        showDownloadNotification(fileId, source.name, 100, inProgress = false)
        DebugLogStore.log("DOWNLOAD", "Export to public success fileId=$fileId uri=$uri")
        return Result.success(uri.toString())
    }

    override suspend fun removeCachedFile(fileId: String): Boolean {
        synchronized(segmentLock) {
            segmentFinalizeJobs.remove(fileId)?.cancel()
            segmentStates.remove(fileId)?.tempFile?.delete()
        }
        return cacheManager.remove(fileId)
    }

    override suspend fun clearCache(scope: String) {
        if (scope == "all" || scope == "media") {
            synchronized(segmentLock) {
                segmentFinalizeJobs.values.forEach { it.cancel() }
                segmentFinalizeJobs.clear()
                segmentStates.values.forEach { it.tempFile.delete() }
                segmentStates.clear()
            }
            cacheManager.clearAll()
        }
    }

    private suspend fun cacheSegmentChunk(
        fileId: String,
        source: java.io.InputStream,
        mimeType: String,
        fileName: String?,
        url: String,
        contentLength: Long,
        contentRange: ContentRangeInfo?,
    ): String {
        val startOffset = contentRange?.start ?: extractOffsetFromUrl(url) ?: 0L
        val totalHint = contentRange?.total ?: extractTotalFromUrl(url)
        val expectedLength = contentRange
            ?.let { (it.endInclusive - it.start + 1).coerceAtLeast(0L) }
            ?: contentLength.coerceAtLeast(0L)

        val state = synchronized(segmentLock) {
            val existing = segmentStates[fileId]
            if (existing != null) {
                if (totalHint != null && totalHint > 0L) {
                    existing.totalBytes = maxOf(existing.totalBytes, totalHint)
                }
                existing.mimeType = mimeType
                if (!fileName.isNullOrBlank()) existing.fileName = fileName
                existing
            } else {
                val temp = File(context.cacheDir, "flygram_segment_${fileId.hashCode()}_${System.currentTimeMillis()}.tmp")
                temp.parentFile?.mkdirs()
                SegmentAccumulator(
                    tempFile = temp,
                    totalBytes = totalHint ?: -1L,
                    mimeType = mimeType,
                    fileName = fileName,
                    ranges = mutableListOf(),
                    updatedAt = System.currentTimeMillis(),
                ).also { segmentStates[fileId] = it }
            }
        }

        val written = RandomAccessFile(state.tempFile, "rw").use { raf ->
            raf.seek(startOffset.coerceAtLeast(0L))
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var copied = 0L
            while (true) {
                val read = source.read(buffer)
                if (read <= 0) break
                raf.write(buffer, 0, read)
                copied += read
            }
            copied
        }
        if (written <= 0L && expectedLength > 0L) {
            throw IllegalStateException("No bytes written for segmented download")
        }

        val rangeEnd = startOffset + written.coerceAtLeast(expectedLength).coerceAtLeast(1L) - 1L
        val snapshot = synchronized(segmentLock) {
            mergeRange(state.ranges, startOffset..rangeEnd)
            if (state.totalBytes <= 0L && totalHint != null && totalHint > 0L) {
                state.totalBytes = totalHint
            }
            state.updatedAt = System.currentTimeMillis()

            val covered = coveredBytes(state.ranges)
            val total = state.totalBytes
            val complete = total > 0L &&
                state.ranges.size == 1 &&
                state.ranges.first().first <= 0L &&
                state.ranges.first().last >= total - 1

            SegmentSnapshot(
                tempFile = state.tempFile,
                totalBytes = total,
                coveredBytes = covered,
                mimeType = state.mimeType,
                fileName = state.fileName,
                complete = complete,
            )
        }

        if (snapshot.complete) {
            val finalPath = persistSegmentSnapshot(fileId, snapshot)
            AppRepositories.postDownloadProgress(fileId = fileId, percent = 100, localUri = finalPath)
            showDownloadNotification(fileId, snapshot.fileName ?: fileId, 100, inProgress = false)
            return finalPath
        }

        val percent = if (snapshot.totalBytes > 0L) {
            ((snapshot.coveredBytes * 100) / snapshot.totalBytes).toInt().coerceIn(1, 99)
        } else {
            1
        }
        AppRepositories.postDownloadProgress(fileId = fileId, percent = percent)
        showDownloadNotification(fileId, snapshot.fileName ?: fileId, percent, inProgress = true)
        scheduleSegmentFinalize(fileId)
        return SEGMENT_PENDING_TOKEN
    }

    private fun mergeRange(ranges: MutableList<LongRange>, newRange: LongRange) {
        ranges.add(newRange)
        val merged = ranges.sortedBy { it.first }.fold(mutableListOf<LongRange>()) { acc, current ->
            val last = acc.lastOrNull()
            if (last == null || current.first > last.last + 1L) {
                acc += current
            } else {
                acc[acc.lastIndex] = last.first..maxOf(last.last, current.last)
            }
            acc
        }
        ranges.clear()
        ranges.addAll(merged)
    }

    private fun coveredBytes(ranges: List<LongRange>): Long {
        return ranges.sumOf { range -> (range.last - range.first + 1L).coerceAtLeast(0L) }
    }

    private fun scheduleSegmentFinalize(fileId: String) {
        synchronized(segmentLock) {
            segmentFinalizeJobs.remove(fileId)?.cancel()
            segmentFinalizeJobs[fileId] = segmentScope.launch {
                delay(SEGMENT_IDLE_FINALIZE_MS)
                val snapshot = synchronized(segmentLock) {
                    val state = segmentStates[fileId] ?: return@launch
                    val idleFor = System.currentTimeMillis() - state.updatedAt
                    if (idleFor < SEGMENT_IDLE_FINALIZE_MS) return@launch
                    val covered = coveredBytes(state.ranges)
                    if (covered <= 0L) return@launch
                    SegmentSnapshot(
                        tempFile = state.tempFile,
                        totalBytes = state.totalBytes,
                        coveredBytes = covered,
                        mimeType = state.mimeType,
                        fileName = state.fileName,
                        complete = state.totalBytes > 0L && covered >= state.totalBytes,
                    )
                }

                val bytesHint = snapshot.totalBytes.takeIf { it > 0L } ?: snapshot.coveredBytes
                runCatching {
                    persistSegmentSnapshot(fileId, snapshot.copy(totalBytes = bytesHint, complete = true))
                }.onSuccess { finalPath ->
                    AppRepositories.postDownloadProgress(fileId = fileId, percent = 100, localUri = finalPath)
                    showDownloadNotification(fileId, snapshot.fileName ?: fileId, 100, inProgress = false)
                }.onFailure {
                    AppRepositories.postDownloadProgress(fileId = fileId, percent = 0, error = "segment_finalize_failed")
                    showDownloadNotification(fileId, snapshot.fileName ?: fileId, 0, inProgress = false, failed = true)
                }
            }
        }
    }

    private suspend fun persistSegmentSnapshot(fileId: String, snapshot: SegmentSnapshot): String {
        val zipCandidate = snapshot.tempFile.takeIf { isLikelyZipFile(it) }
        val finalPath = if (zipCandidate != null) {
            runCatching { cacheZipSingleEntry(fileId, zipCandidate, snapshot) }.getOrElse {
                cacheRawSegment(fileId, snapshot)
            }
        } else {
            cacheRawSegment(fileId, snapshot)
        }

        synchronized(segmentLock) {
            segmentFinalizeJobs.remove(fileId)?.cancel()
            segmentStates.remove(fileId)?.tempFile?.delete()
        }
        return finalPath
    }

    private suspend fun cacheZipSingleEntry(fileId: String, zipFile: File, snapshot: SegmentSnapshot): String {
        ZipFile(zipFile).use { archive ->
            val entry = archive.entries().asSequence().firstOrNull { !it.isDirectory }
                ?: return cacheRawSegment(fileId, snapshot)
            val entryName = chooseFileName(
                provided = snapshot.fileName,
                detected = entry.name.substringAfterLast('/'),
            )
            archive.getInputStream(entry).use { input ->
                return cacheManager.cache(
                    fileId = fileId,
                    mimeType = snapshot.mimeType,
                    bytes = entry.size.takeIf { it > 0L } ?: snapshot.coveredBytes,
                    source = input,
                    isPinned = false,
                    fileName = entryName,
                    onProgress = null,
                )
            }
        }
    }

    private suspend fun cacheRawSegment(fileId: String, snapshot: SegmentSnapshot): String {
        return snapshot.tempFile.inputStream().use { input ->
            cacheManager.cache(
                fileId = fileId,
                mimeType = snapshot.mimeType,
                bytes = snapshot.totalBytes.coerceAtLeast(snapshot.coveredBytes),
                source = input,
                isPinned = false,
                fileName = snapshot.fileName,
                onProgress = null,
            )
        }
    }

    private fun isLikelyZipFile(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return runCatching {
            file.inputStream().use { input ->
                val signature = ByteArray(4)
                val read = input.read(signature)
                read == 4 && signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte()
            }
        }.getOrDefault(false)
    }

    private fun parseContentRange(raw: String?): ContentRangeInfo? {
        if (raw.isNullOrBlank()) return null
        val regex = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
        val match = regex.find(raw.trim()) ?: return null
        val start = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return null
        val end = match.groupValues.getOrNull(2)?.toLongOrNull() ?: return null
        val total = match.groupValues.getOrNull(3)?.takeUnless { it == "*" }?.toLongOrNull()
        if (end < start) return null
        return ContentRangeInfo(start = start, endInclusive = end, total = total)
    }

    private fun resolveMimeType(requested: String, detected: String): String {
        val requestedClean = requested.substringBefore(';').trim().lowercase()
        val detectedClean = detected.substringBefore(';').trim().lowercase()
        if (detectedClean.isNotBlank() && detectedClean != "application/octet-stream") {
            if (requestedClean in setOf("", "application/octet-stream", "text/plain", "binary/octet-stream")) {
                return detectedClean
            }
            if (requestedClean.startsWith("text/") && !detectedClean.startsWith("text/")) {
                return detectedClean
            }
        }
        return requestedClean.ifBlank {
            detectedClean.ifBlank { "application/octet-stream" }
        }
    }

    private fun chooseFileName(provided: String?, detected: String): String {
        val providedClean = provided?.trim().orEmpty()
        val detectedClean = detected.trim()
        val providedArtifact = isLikelyArtifactName(providedClean)
        val detectedArtifact = isLikelyArtifactName(detectedClean)
        if (providedArtifact && detectedClean.isNotBlank() && !detectedArtifact) {
            return detectedClean
        }
        if (detectedClean.isNotBlank() && !detectedClean.equals("download", ignoreCase = true)) {
            if (providedClean.isBlank()) return detectedClean
            val providedHasTextExt = providedClean.endsWith(".txt", ignoreCase = true)
            val detectedHasKnownExt = detectedClean.contains('.') && !detectedClean.endsWith(".txt", ignoreCase = true)
            if (providedHasTextExt && detectedHasKnownExt) {
                return detectedClean
            }
        }
        return providedClean.ifBlank { detectedClean.ifBlank { "download.bin" } }
    }

    private fun isLikelyHtmlResponse(contentType: String): Boolean {
        val lower = contentType.lowercase()
        return lower.contains("text/html") || lower.contains("application/xhtml+xml")
    }

    private fun isLikelyArtifactName(value: String): Boolean {
        if (value.isBlank()) return false
        val lower = value.lowercase()
        if (lower == "download" || lower == "download.bin") return true
        val onlyName = lower.substringAfterLast('/').substringAfterLast('\\')
        return Regex("^[0-9a-f]{8,}[_-].+\\.(zip|bin|tmp|dat|part)$").matches(onlyName) ||
            Regex("^[0-9a-f-]{24,}\\.(zip|bin|tmp|dat|part)$").matches(onlyName)
    }

    private fun extractRedirectUrlFromHtml(html: String, baseUrl: String): String? {
        if (html.isBlank()) return null

        val directUrlRegex = Regex(
            """https?:\/\/[^\s"'<>\\]+""",
            setOf(RegexOption.IGNORE_CASE),
        )
        val escapedUrlRegex = Regex(
            """https?:\\\/\\\/[^\s"'<>\\]+""",
            setOf(RegexOption.IGNORE_CASE),
        )

        val candidate = directUrlRegex.find(html)?.value
            ?: escapedUrlRegex.find(html)?.value?.replace("\\/", "/")
            ?: return null

        return runCatching { URL(URL(baseUrl), candidate).toString() }.getOrNull()
    }

    private fun isLikelySegmentRequest(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val keys = uri.queryParameterNames.map { it.lowercase() }.toSet()
        return keys.any { it in SEGMENT_QUERY_KEYS }
    }

    private fun extractOffsetFromUrl(url: String): Long? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val candidates = listOf("offset", "start", "from", "range_start")
        return candidates.firstNotNullOfOrNull { key -> uri.getQueryParameter(key)?.toLongOrNull() }
    }

    private fun extractTotalFromUrl(url: String): Long? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val candidates = listOf("total", "size", "file_size", "range_total")
        return candidates.firstNotNullOfOrNull { key -> uri.getQueryParameter(key)?.toLongOrNull() }
    }

    private fun openConfiguredConnection(url: String): HttpURLConnection {
        val connection = openConnection(url)
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", defaultUserAgent())
        connection.setRequestProperty("Referer", "https://web.telegram.org/")
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        if (!cookie.isNullOrBlank()) {
            connection.setRequestProperty("Cookie", cookie)
        }
        connection.connect()
        return connection
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

    private fun defaultUserAgent(): String {
        return runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrElse { "FlyGram/1.0 AndroidWebView" }
    }

    private fun showDownloadNotification(
        fileId: String,
        title: String,
        progress: Int,
        inProgress: Boolean,
        failed: Boolean = false,
    ) {
        if (failed || !inProgress || progress == 0 || progress >= 100 || progress % 20 == 0) {
            DebugLogStore.log(
                "DOWNLOAD_NOTIFY",
                "notify fileId=$fileId title=${title.take(60)} progress=$progress inProgress=$inProgress failed=$failed",
            )
        }
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
        private const val MAX_REDIRECTS = 8
        private const val MAX_HTML_SNIFF = 128 * 1024
        private const val DOWNLOAD_CHANNEL_ID = "flygram_downloads"
        private const val MIN_VALID_FILE_SIZE = 2048L
        private const val SEGMENT_IDLE_FINALIZE_MS = 2_600L
        private const val SEGMENT_PENDING_TOKEN = "__segment_pending__"
        private val SEGMENT_QUERY_KEYS = setOf("offset", "range", "start", "end", "part", "chunk", "segment")
    }

    private val segmentLock = Any()
    private val segmentStates = mutableMapOf<String, SegmentAccumulator>()
    private val segmentFinalizeJobs = mutableMapOf<String, Job>()
    private val segmentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
