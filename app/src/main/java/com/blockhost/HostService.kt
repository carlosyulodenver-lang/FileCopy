package com.blockhost

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/** Foreground service that keeps the process alive while servers run or sleep (wake-on-join). */
class HostService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as BlockHostApp
        if (intent?.action == ACTION_STOP_ALL) { app.manager.stopAll() }
        val (r, s) = app.manager.activity.value
        startForeground(NOTIF_ID, notification(r, s))
        acquireLocks()
        if (!started) {
            started = true
            scope.launch {
                app.manager.activity.collect { (run, sleep) ->
                    if (run + sleep == 0) { releaseLocks(); stopForeground(true); stopSelf() }
                    else (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(NOTIF_ID, notification(run, sleep))
                }
            }
        }
        return START_NOT_STICKY // the server processes die with the app; do not pretend to resume them
    }

    private fun notification(run: Int, sleep: Int): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, HostService::class.java).setAction(ACTION_STOP_ALL), PendingIntent.FLAG_IMMUTABLE)
        val text = buildList {
            if (run > 0) add("$run active"); if (sleep > 0) add("$sleep sleeping (wake on join)")
            if (isEmpty()) add("Idle")
        }.joinToString(" • ")
        return NotificationCompat.Builder(this, BlockHostApp.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("BlockHost").setContentText(text)
            .setOngoing(true).setContentIntent(open)
            .addAction(0, "Stop all", stop).build()
    }

    private fun acquireLocks() {
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BlockHost:server")
                .apply { setReferenceCounted(false) }
        }
        if (wake?.isHeld == false) wake?.acquire()
        if (wifi == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = wm.createWifiLock(mode, "BlockHost:wifi").apply { setReferenceCounted(false) }
        }
        if (wifi?.isHeld == false) wifi?.acquire()
    }

    private fun releaseLocks() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        try { if (wifi?.isHeld == true) wifi?.release() } catch (_: Exception) {}
    }

    override fun onDestroy() { releaseLocks(); scope.cancel(); super.onDestroy() }

    companion object {
        const val NOTIF_ID = 42
        const val ACTION_STOP_ALL = "com.blockhost.STOP_ALL"
    }
}
