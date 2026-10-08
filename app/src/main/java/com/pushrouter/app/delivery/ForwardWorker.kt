package com.pushrouter.app.delivery

import android.content.Context
import androidx.work.*
import com.pushrouter.app.RouterApplication
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

class ForwardWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as RouterApplication).repository ?: return Result.failure()
        val id = inputData.getString("deliveryId") ?: return Result.failure()
        // WorkManager resumes queued requests after connectivity returns and after process restarts.
        repeat(3) {
            val retryAfter = repo.deliver(id) ?: return Result.success()
            if (retryAfter > 60) return Result.retry()
            delay(retryAfter * 1000)
        }
        return Result.retry()
    }
    companion object {
        fun schedule(context: Context, id: String) {
            val request = OneTimeWorkRequestBuilder<ForwardWorker>()
                .setInputData(workDataOf("deliveryId" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("forward-$id", ExistingWorkPolicy.KEEP, request)
        }
    }
}
