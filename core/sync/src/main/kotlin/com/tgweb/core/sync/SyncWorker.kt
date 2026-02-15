package com.tgweb.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tgweb.core.data.AppRepositories

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!AppRepositories.isInitialized()) return Result.retry()
        return runCatching {
            AppRepositories.chatRepository.syncNow(reason = "periodic")
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
