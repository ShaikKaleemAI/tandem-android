package app.tandem.multi

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

object Notifier {
    const val CH_DONE = "tandem_done"
    const val CH_RUN = "tandem_running"
    const val RUN_ID = 4242

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val done = NotificationChannel(CH_DONE, "Response finished", NotificationManager.IMPORTANCE_HIGH)
        done.description = "Tells you when an AI screen has finished answering"
        nm.createNotificationChannel(done)
        val run = NotificationChannel(CH_RUN, "Running in background", NotificationManager.IMPORTANCE_LOW)
        run.description = "Shown while responses are still being generated"
        nm.createNotificationChannel(run)
    }

    private fun openIntent(ctx: Context, paneId: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (paneId != null) i.putExtra("pane", paneId)
        return PendingIntent.getActivity(
            ctx,
            (paneId ?: "main").hashCode(),
            i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun done(ctx: Context, pane: Pane) {
        if (!canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val n = Notification.Builder(ctx, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(pane.label + " finished")
            .setContentText("Your response is ready. Tap to view.")
            .setColor(pane.color)
            .setAutoCancel(true)
            .setContentIntent(openIntent(ctx, pane.id))
            .build()
        nm.notify(pane.id.hashCode(), n)
    }

    fun cancel(ctx: Context, pane: Pane) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(pane.id.hashCode())
    }

    fun running(ctx: Context, count: Int): Notification {
        val text = if (count == 1) "1 response is being generated" else "$count responses are being generated"
        return Notification.Builder(ctx, CH_RUN)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Tandem is working")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent(ctx, null))
            .build()
    }
}

/**
 * Keeps the app process alive while an AI is still answering, so the response
 * keeps streaming (and you get a notification) after you leave the app.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra("count", 1) ?: 1
        Notifier.ensureChannels(this)
        val n = Notifier.running(this, count)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifier.RUN_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(Notifier.RUN_ID, n)
            }
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
