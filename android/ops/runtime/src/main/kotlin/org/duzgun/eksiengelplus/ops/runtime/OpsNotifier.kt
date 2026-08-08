package org.duzgun.eksiengelplus.ops.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.app.PendingIntent
import androidx.work.WorkManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Two channels, because progress and outcomes want opposite treatment.
 *
 * Progress is unavoidably long-lived -- a full run spans days -- so it must be
 * silent or it becomes intolerable. Outcomes are rare and actionable, so they
 * must be able to interrupt. Written fresh rather than ported:
 * notificationHandler.js is 454 lines of chrome.notifications semantics that do
 * not map onto NotificationManagerCompat.
 */
class OpsNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_PROGRESS = "ops_progress"
        const val CHANNEL_ALERTS = "ops_alerts"
        const val NOTIFICATION_ID_PROGRESS = 1001
        const val NOTIFICATION_ID_ALERT = 1002
        const val NOTIFICATION_ID_BUDGET_WARNING = 1003

        const val ACTION_PAUSE = "org.duzgun.eksiengelplus.PAUSE"
        const val ACTION_STOP = "org.duzgun.eksiengelplus.STOP"
        const val ACTION_RESUME = "org.duzgun.eksiengelplus.RESUME"
        const val EXTRA_OPERATION_ID = "operationId"
    }

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val progress = NotificationChannel(
            CHANNEL_PROGRESS,
            "İşlem durumu",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Devam eden toplu işlemin ilerlemesi"
            setShowBadge(false)
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            "İşlem bildirimleri",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Tamamlanan işlemler ve dikkat gerektiren durumlar" }

        manager.createNotificationChannel(progress)
        manager.createNotificationChannel(alerts)
    }

    fun progress(
        operationId: String,
        title: String,
        processed: Int,
        total: Int,
        /**
         * Milliseconds left before the next action may go out, or 0 when running.
         *
         * This replaced a whole-run ETA. The estimate was derived from the same
         * rate limit the run is waiting on, so it only ever restated the API
         * ceiling, and it sat there unchanged for minutes -- a static number
         * where the user was looking for a sign of life. The wait is the thing
         * actually ticking, so the wait is what the notification shows.
         */
        waitMs: Long = 0L,
    ): Notification {
        val waitText = if (waitMs > 0L) {
            " · API limiti bekleniyor ${(waitMs + 999) / 1000} sn"
        } else {
            ""
        }

        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("$processed / $total işlendi$waitText")
            .setProgress(total.coerceAtLeast(1), processed, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "Duraklat", commandIntent(operationId, ACTION_PAUSE))
            .addAction(0, "Durdur", commandIntent(operationId, ACTION_STOP))
            .build()
    }

    /**
     * The run is paused and waiting for the user.
     *
     * Deliberately on NOTIFICATION_ID_PROGRESS: pausing ends the foreground
     * service and takes its notification with it, so without this the run
     * disappeared with no way back to it. Reusing the id means resuming replaces
     * this notification with the live one rather than stacking a second.
     *
     * Ongoing, because a paused run is unfinished business -- swiping it away
     * would strand work the user explicitly chose to keep.
     */
    fun paused(operationId: String, processed: Int, total: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("İşlem duraklatıldı")
            .setContentText("$processed / $total işlendi · devam etmek için dokunun")
            .setProgress(total.coerceAtLeast(1), processed, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // The body opens the app; it does not resume.
            //
            // A swipe that registers as a tap would otherwise restart a bulk run
            // against real accounts, which is far too much to hang on a gesture
            // the user meant as "go away". Resuming stays an explicit button.
            .setContentIntent(openAppIntent())
            .addAction(0, "Devam et", commandIntent(operationId, ACTION_RESUME))
            .addAction(0, "Durdur", commandIntent(operationId, ACTION_STOP))
            .build()

    fun showPaused(operationId: String, processed: Int, total: Int) {
        manager.notify(NOTIFICATION_ID_PROGRESS, paused(operationId, processed, total))
    }

    fun clearProgress() = manager.cancel(NOTIFICATION_ID_PROGRESS)

    fun alert(title: String, text: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        // POST_NOTIFICATIONS is a runtime permission from API 33. Denial degrades
        // rather than blocks: the operation still runs and the in-app screen stays
        // authoritative, the user just loses background visibility.
        //
        // The check is inlined rather than delegated to canPost() because lint
        // cannot follow the permission test through a helper.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (!manager.areNotificationsEnabled()) return
        manager.notify(NOTIFICATION_ID_ALERT, n)
    }

    /**
     * Fired once, shortly before the foreground budget runs out.
     *
     * There is no honest way to extend background time -- the uncapped
     * foreground-service types would all misrepresent what this service does. But
     * work performed while the app is VISIBLE costs no budget at all, because a
     * visible activity keeps the process alive without a service. So the offer is
     * "open the app and finish now", not "we found more time".
     */
    fun budgetWarning(remainingItems: Int, launchIntent: PendingIntent?) {
        val text = if (remainingItems > 0) {
            "Arka plan süresi azalıyor. Kalan $remainingItems işlemi hemen bitirmek " +
                "için uygulamayı açık tutun; açıkken arka plan süresi harcanmaz."
        } else {
            "Arka plan süresi azalıyor. Uygulamayı açık tutarsanız işlem kesintisiz sürer."
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("İşleme devam edilsin mi?")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        launchIntent?.let { builder.setContentIntent(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (!manager.areNotificationsEnabled()) return
        manager.notify(NOTIFICATION_ID_BUDGET_WARNING, builder.build())
    }

    /** False when the user refused notifications, or disabled them later. */
    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return manager.areNotificationsEnabled()
    }

    /** Brings the app forward, so tapping the notification lands somewhere useful. */
    private fun openAppIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(
            context,
            "open-app".hashCode(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun commandIntent(operationId: String, action: String): PendingIntent {
        val intent = Intent(context, OperationCommandReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_OPERATION_ID, operationId)
        }
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * Receives the notification actions.
 *
 * A receiver rather than a callback because the tap may arrive when no screen
 * exists — which is the normal case for an operation spanning days. It writes to
 * the command bus and returns; the worker picks it up at its next checkpoint.
 */
@AndroidEntryPoint
class OperationCommandReceiver : BroadcastReceiver() {

    @Inject lateinit var commands: OperationCommandBus

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(OpsNotifier.EXTRA_OPERATION_ID) ?: return
        when (intent.action) {
            OpsNotifier.ACTION_PAUSE -> commands.post(id, OperationCommand.PAUSE)
            OpsNotifier.ACTION_STOP -> commands.post(id, OperationCommand.STOP)
            // Resume is not a command for a running worker to pick up -- there is
            // no worker any more. It schedules a fresh one, which reads the stored
            // checkpoint and carries on from the cursor.
            OpsNotifier.ACTION_RESUME ->
                OperationWorker.enqueueExisting(WorkManager.getInstance(context), id)
        }
    }
}
