package com.epaper.reader.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.epaper.reader.EpaperApp
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Periodically pulls the feed and downloads new papers, so they are already there when the app opens. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val c = (applicationContext as EpaperApp).container
        c.repository.refresh(limit = 15)
        c.autoDownloader.runBackground(c.settings.state.value)
        Result.success()
    } catch (e: IOException) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    companion object {
        private const val NAME = "feed-sync"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(NAME); return }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
