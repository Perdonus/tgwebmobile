package com.tgweb.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.data.DebugLogStore

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        DebugLogStore.log("SYNC_WORK", "SyncWorker start runAttempt=$runAttemptCount")
        if (!AppRepositories.isInitialized()) {
            DebugLogStore.log("SYNC_WORK", "SyncWorker retry: repositories not initialized")
            return Result.retry()
        }
        return runCatching {
            AppRepositories.chatRepository.syncNow(reason = "periodic")
            DebugLogStore.log("SYNC_WORK", "SyncWorker success")
            Result.success()
        }.getOrElse {
            DebugLogStore.logError("SYNC_WORK", "SyncWorker failed", it)
            Result.retry()
        }
    }
}
