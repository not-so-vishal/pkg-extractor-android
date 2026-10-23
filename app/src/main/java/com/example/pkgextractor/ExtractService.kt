package com.example.pkgextractor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock

/** Keeps the process alive and shows a progress notification while Engine is extracting. */
class ExtractService : Service(), Engine.Listener {
    companion object {
        const val ACTION_CANCEL = "com.example.pkgextractor.CANCEL"
        private const val CH_PROGRESS = "progress"
        private const val CH_DONE = "done"
        private const val ID = 42
    }

    private var wl: PowerManager.WakeLock? = null
    private var lastNotify = 0L
    private var ended = false

    override fun onBind(intent: Intent?): IBinder? = null

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Extraction progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Extraction finished", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { Engine.cancel(); return START_NOT_STICKY }
        channels()
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        Engine.add(this)
        if (wl == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pkgextractor:extract").apply { acquire(6L * 60 * 60 * 1000) }
        }
        onEngine()
        return START_NOT_STICKY
    }

    override fun onEngine() {
        if (ended) return
        if (Engine.running) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastNotify < 700) return
            lastNotify = now
            getSystemService(NotificationManager::class.java).notify(ID, build())
        } else if (Engine.finished) {
            ended = true
            if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_DETACH) else stopForeground(false)
            getSystemService(NotificationManager::class.java).notify(ID, build())
            stopSelf()
        } else {
            stopSelf()
        }
    }

    override fun onDestroy() {
        Engine.remove(this)
        try { wl?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun build(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ExtractService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val failed = Engine.finished && Engine.error.isNotEmpty()
        val b = Notification.Builder(this, if (Engine.running) CH_PROGRESS else CH_DONE)
            .setContentIntent(open).setOnlyAlertOnce(true)
        if (Engine.running) {
            val text = when {
                Engine.cancelled -> "Cancelling…"
                Engine.cooling -> "Battery is hot, pausing to cool down"
                Engine.cnt > 0 -> "${Engine.idx}/${Engine.cnt} · ${Engine.file}"
                else -> "Reading package…"
            }
            b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Extracting ${Engine.pkgName}")
                .setContentText(text)
                .setProgress(100, Engine.percent, Engine.cnt == 0)
                .setOngoing(true).setCategory(Notification.CATEGORY_PROGRESS)
                .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Cancel", cancel).build())
        } else {
            b.setSmallIcon(if (failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
                .setContentTitle(if (!failed) "Extraction complete" else if (Engine.error == "Cancelled") "Extraction cancelled" else "Extraction failed")
                .setContentText(if (!failed) Engine.resultPath else Engine.error)
                .setAutoCancel(true).setOngoing(false)
        }
        return b.build()
    }
}
