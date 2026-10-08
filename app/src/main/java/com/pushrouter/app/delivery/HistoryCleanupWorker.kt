package com.pushrouter.app.delivery

import android.content.Context
import androidx.work.*
import com.pushrouter.app.RouterApplication
import java.util.concurrent.TimeUnit

class HistoryCleanupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as RouterApplication).repository ?: return Result.failure()
        repository.prune()
        return Result.success()
    }
    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HistoryCleanupWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("history-cleanup", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
