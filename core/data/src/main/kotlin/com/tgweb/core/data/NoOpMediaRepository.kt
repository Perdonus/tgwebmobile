package com.tgweb.core.data

class NoOpMediaRepository : MediaRepository {
    override suspend fun getMediaFile(fileId: String): Result<String> {
        return Result.failure(IllegalStateException("Media module not initialized"))
    }

    override suspend fun prefetch(chatId: Long, window: Int) {
        // No-op until real media prefetch strategy is wired with TDLib file API.
    }

    override suspend fun downloadToPublicStorage(fileId: String, targetCollection: String): Result<String> {
        return Result.failure(IllegalStateException("Media module not initialized"))
    }

    override suspend fun clearCache(scope: String) {
        // No-op.
    }
}
