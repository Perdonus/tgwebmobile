package com.tgweb.core.data

class NoOpMediaRepository : MediaRepository {
    override suspend fun getMediaFile(fileId: String): Result<String> {
        return Result.failure(IllegalStateException("Media module not initialized"))
    }

    override suspend fun cacheRemoteFile(fileId: String, url: String, mimeType: String, fileName: String?): Result<String> {
        return Result.failure(IllegalStateException("Media module not initialized"))
    }

    override suspend fun prefetch(chatId: Long, window: Int) {
        // No-op until real media prefetch strategy is wired with TDLib file API.
    }

    override suspend fun downloadToPublicStorage(fileId: String, targetCollection: String): Result<String> {
        return Result.failure(IllegalStateException("Media module not initialized"))
    }

    override suspend fun removeCachedFile(fileId: String): Boolean {
        return false
    }

    override suspend fun clearCache(scope: String) {
        // No-op.
    }
}
