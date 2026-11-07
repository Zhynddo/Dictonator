package com.ngan.dichthuat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One-tap setup that keeps running after the app is closed:
 *  1. downloads the Vietnamese translation pack (English is built in)
 *  2. installs the dictionary bundled in the app
 *  3. shows a notification when everything is ready
 * Waits for internet by itself and retries if the connection drops.
 */
class SetupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val engine = Engine()
        return try {
            setProgress(workDataOf(STEP to "Đang tải gói tiếng Việt…\nDownloading Vietnamese"))
            engine.download("vi")

            val dict = Dictionary(applicationContext)
            try {
                if (dict.count() == 0L && hasAsset(DICT_ASSET)) {
                    setProgress(workDataOf(STEP to "Đang cài từ điển Anh–Việt…\nInstalling the dictionary"))
                    applicationContext.assets.open(DICT_ASSET).use { s -> dict.import(s) { } }
                }
            } finally {
                dict.close()
            }

            applicationContext.getSharedPreferences("app", Context.MODE_PRIVATE)
                .edit().putBoolean("setupDone", true).apply()
            notifyReady()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 5) Result.retry()
            else Result.failure(workDataOf(ERROR to (e.message ?: e.javaClass.simpleName)))
        } finally {
            engine.close()
        }
    }

    private fun hasAsset(name: String) = try {
        applicationContext.assets.open(name).close(); true
    } catch (e: IOException) { false }

    private fun notifyReady() {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel("setup", "Setup", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, "setup")
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Dịch Thuật đã sẵn sàng ✓")
            .setContentText("Dùng được khi không có mạng · Ready to use offline")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(1, n)
    }

    companion object {
        const val NAME = "setup"
        const val STEP = "step"
        const val ERROR = "error"
        const val DICT_ASSET = "dict_en_vi.gz"

        fun start(ctx: Context, restart: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<SetupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                NAME, if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req
            )
        }
    }
}
