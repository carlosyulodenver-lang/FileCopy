package com.blockhost

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class BlockHostApp : Application() {
    lateinit var store: ServerStore
    lateinit var prefs: Prefs
    lateinit var runtime: JavaRuntime
    lateinit var manager: Manager
    lateinit var tunnel: Tunnel

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = ServerStore(this); prefs = Prefs(this); runtime = JavaRuntime(this)
        manager = Manager(this); tunnel = Tunnel(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Server hosting", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while BlockHost is hosting or waiting to wake a server" })
        }
    }

    companion object {
        lateinit var instance: BlockHostApp
        const val CHANNEL = "host"
    }
}
