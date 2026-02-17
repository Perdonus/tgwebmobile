package com.tgweb.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.tgweb.core.data.AppRepositories
import com.tgweb.core.data.DebugLogStore

class PushProcessWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        DebugLogStore.log("SYNC_WORK", "PushProcessWorker start runAttempt=$runAttemptCount")
        if (!AppRepositories.isInitialized()) {
            DebugLogStore.log("SYNC_WORK", "PushProcessWorker retry: repositories not initialized")
            return Result.retry()
        }

        val payload = inputData.keyValueMap.mapValues { (_, v) -> v?.toString().orEmpty() }
        DebugLogStore.log("SYNC_WORK", "PushProcessWorker payload keys=${payload.keys.joinToString(",")}")
        return runCatching {
            AppRepositories.notificationService.handlePush(payload)
            AppRepositories.chatRepository.syncNow(reason = "push")
            DebugLogStore.log("SYNC_WORK", "PushProcessWorker success")
            Result.success()
        }.getOrElse {
            DebugLogStore.logError("SYNC_WORK", "PushProcessWorker failed", it)
            Result.retry()
        }
    }

    companion object {
        fun payloadData(payload: Map<String, String>): Data {
            val builder = Data.Builder()
            payload.forEach { (k, v) -> builder.putString(k, v) }
            return builder.build()
        }
    }
}
