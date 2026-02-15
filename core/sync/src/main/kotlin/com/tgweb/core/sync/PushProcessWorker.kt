package com.tgweb.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.tgweb.core.data.AppRepositories

class PushProcessWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!AppRepositories.isInitialized()) return Result.retry()

        val payload = inputData.keyValueMap.mapValues { (_, v) -> v?.toString().orEmpty() }
        return runCatching {
            AppRepositories.notificationService.handlePush(payload)
            AppRepositories.chatRepository.syncNow(reason = "push")
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        fun payloadData(payload: Map<String, String>): Data {
            val builder = Data.Builder()
            payload.forEach { (k, v) -> builder.putString(k, v) }
            return builder.build()
        }
    }
}
