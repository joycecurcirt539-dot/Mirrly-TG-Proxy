package com.mirrly.tgproxy.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val result = UpdateManager.checkForUpdates(applicationContext, notifyIfFound = true)
            if (result.isSuccess) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
