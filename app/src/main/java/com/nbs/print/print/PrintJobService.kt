package com.nbs.print.print

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.nbs.print.R

/**
 * Keeps the app alive while a direct print job runs, so turning the screen off doesn't stop it. It does no
 * printing itself: being a foreground service stops Android from freezing the process, and the wake and
 * Wi-Fi locks stop the CPU and the Wi-Fi radio from sleeping mid-job. The job itself still runs in the
 * view model; this service is started when it begins and stopped when it ends.
 */
class PrintJobService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.print_job_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val printer = intent?.getStringExtra(EXTRA_PRINTER).orEmpty()
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.print_job_title))
            .setContentText(if (printer.isBlank()) getString(R.string.print_job_text) else getString(R.string.print_job_text_to, printer))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireLocks()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = runCatching {
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NBSPrint:job")
                    .apply { acquire(MAX_JOB_MS) }
            }.getOrNull()
        }
        if (wifiLock == null) {
            wifiLock = runCatching {
                applicationContext.getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NBSPrint:job")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "print_job"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_PRINTER = "printer"
        private const val MAX_JOB_MS = 2 * 60 * 60 * 1000L

        /** Call while the app is on screen (the Print button); Android won't start it from the background. */
        fun start(context: Context, printerName: String) {
            runCatching {
                context.startForegroundService(
                    Intent(context, PrintJobService::class.java).putExtra(EXTRA_PRINTER, printerName)
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PrintJobService::class.java)) }
        }
    }
}
