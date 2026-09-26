package com.rtree.LIFELiNK

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Keeps the Beacon scan owned by a visible foreground service so the OS does not freeze the app while locked.
class BeaconMonitorService : Service() {
    private var scope: CoroutineScope? = null
    private var screenReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AdvertisementRegistry.appendBeaconLog("Monitoring stopped (stop action from notification)")
            stopMonitoring()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
        val filterCount = runCatching { BeaconTriggerManager.start(this) }.getOrElse { error ->
            AdvertisementRegistry.appendBeaconLog("Monitoring start failed: ${error.message}")
            stopMonitoring()
            return START_NOT_STICKY
        }
        mutableRunning.value = true
        AdvertisementRegistry.appendBeaconLog(
            "Monitoring started (ongoing notification) filter=$filterCount batteryOptimizationExempt=${isIgnoringBatteryOptimizations(this)}",
        )
        startHeartbeat()
        registerScreenReceiver()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        screenReceiver?.let(::unregisterReceiver)
        screenReceiver = null
        scope?.cancel()
        scope = null
        mutableRunning.value = false
        AdvertisementRegistry.appendBeaconLog("Monitoring service destroyed")
        super.onDestroy()
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val label = when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> "screen OFF"
                    Intent.ACTION_SCREEN_ON -> "screen ON"
                    Intent.ACTION_USER_PRESENT -> "unlocked"
                    else -> return
                }
                val charging = context.getSystemService(android.os.BatteryManager::class.java)?.isCharging == true
                AdvertisementRegistry.appendBeaconLog("$label charging=${if (charging) "yes" else "no"}")
            }
        }
        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
        screenReceiver = receiver
    }

    private fun startHeartbeat() {
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { heartbeatScope ->
            heartbeatScope.launch {
                while (true) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    AdvertisementRegistry.appendBeaconLog(
                        "Monitoring heartbeat ${AdvertisementRegistry.screenState(this@BeaconMonitorService)}",
                    )
                }
            }
        }
    }

    private fun stopMonitoring() {
        BeaconTriggerManager.stop(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "LIFELiNK monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown the whole time LIFELiNK is waiting for your button. If it disappears, monitoring has stopped."
            },
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BeaconMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val mode = if (EmergencyPreferences(this).beaconDryRun) "dry run (no alert sent)" else "long press sends an alert"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("LIFELiNK is monitoring")
            .setContentText("Waiting for your button • $mode")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
            .addAction(0, "Stop monitoring", stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "lifelink_monitoring"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.rtree.LIFELiNK.STOP_MONITORING"
        private const val HEARTBEAT_INTERVAL_MS = 60_000L

        private val mutableRunning = MutableStateFlow(false)
        val running = mutableRunning.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BeaconMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BeaconMonitorService::class.java).setAction(ACTION_STOP))
        }

        fun isIgnoringBatteryOptimizations(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
}
