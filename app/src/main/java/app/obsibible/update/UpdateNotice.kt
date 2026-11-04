package app.obsibible.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.obsibible.MainActivity
import app.obsibible.ObsiBibleApp
import app.obsibible.R
import java.util.concurrent.TimeUnit

/**
 * Tells the user when a new version is out, even when the app is closed.
 * A background job checks GitHub every six hours (only with internet) and posts one
 * notification per new version. Tapping Install opens the app and starts the update.
 */
object UpdateNotice {
    private const val CHANNEL = "updates"
    private const val ID = 7001
    const val ACTION_UPDATE = "app.obsibible.UPDATE"
    const val EXTRA_INSTALL = "install"

    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Posts the notice once per version. Returns true when it was posted. */
    fun show(context: Context, info: UpdateInfo): Boolean {
        val app = context.applicationContext as ObsiBibleApp
        if (app.settings.value.notifiedVersion == info.version) return false
        if (!canNotify(context)) return false
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Tells you when a new version of Obsi Bible is ready."
        })
        fun intent(install: Boolean, code: Int) = PendingIntent.getActivity(
            context, code,
            Intent(context, MainActivity::class.java).setAction(ACTION_UPDATE).putExtra(EXTRA_INSTALL, install)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val firstLine = info.notes.lineSequence().map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_book_mono)
            .setContentTitle("Obsi Bible ${info.version} is ready")
            .setContentText(firstLine ?: "Tap to see what is new.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(firstLine ?: "Tap to see what is new."))
            .setContentIntent(intent(false, 1))
            .addAction(0, "Install", intent(true, 2))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID, n) }.onFailure { return false }
        app.settings.update { it.copy(notifiedVersion = info.version) }
        return true
    }

    fun clear(context: Context) = NotificationManagerCompat.from(context).cancel(ID)
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ObsiBibleApp
        if (!app.settings.value.autoCheckUpdates) return Result.success()
        val info = runCatching { app.updates.checkNow() }.getOrElse { return Result.retry() }
        if (info != null) UpdateNotice.show(applicationContext, info)
        return Result.success()
    }
}
