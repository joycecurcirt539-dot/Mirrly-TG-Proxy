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
            // Schedule the next daytime check (08:00, 14:00, 20:00)
            UpdateManager.scheduleDaytimeCheck(applicationContext)

            if (result.isSuccess) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (_: Exception) {
            UpdateManager.scheduleDaytimeCheck(applicationContext)
            Result.retry()
        }
    }
}
