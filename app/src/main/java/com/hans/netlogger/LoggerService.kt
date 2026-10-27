package com.hans.netlogger

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.telephony.TelephonyManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class LoggerService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var spot = "unnamed"
    private var running = false
    private var lock: PowerManager.WakeLock? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            io.execute { sample() }
            h.postDelayed(this, 120_000L)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == "STOP") { stopLogging(); return START_NOT_STICKY }
        spot = i?.getStringExtra("spot") ?: spot
        show("Starting...")
        if (!running) {
            running = true
            lock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "netlogger:log")
                .apply { acquire(12 * 60 * 60 * 1000L) }
            h.post(tick)
        }
        return START_STICKY
    }

    private fun stopLogging() {
        running = false
        h.removeCallbacks(tick)
        lock?.let { if (it.isHeld) it.release() }
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        lock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun show(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("log", "Net logging", NotificationManager.IMPORTANCE_LOW))
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "log") else Notification.Builder(this)
        val n = b.setContentTitle("Net Logger · $spot").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
    }

    private fun sample() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        var dbm = 0
        var type = "?"
        try {
            if (Build.VERSION.SDK_INT >= 28)
                dbm = tm.signalStrength?.cellSignalStrengths?.firstOrNull()?.dbm ?: 0
            type = when (tm.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
                TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                else -> "?"
            }
        } catch (e: SecurityException) { }
        val (mbps, ms) = try {
            val t0 = System.nanoTime()
            val c = URL("https://speed.cloudflare.com/__down?bytes=100000").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            val s = c.inputStream
            val first = s.read()
            val tFirst = System.nanoTime()
            var bytes = if (first >= 0) 1L else 0L
            val buf = ByteArray(8192)
            while (true) { val n = s.read(buf); if (n < 0) break; bytes += n }
            val t1 = System.nanoTime()
            s.close(); c.disconnect()
            Pair(bytes * 8 / 1e6 / ((t1 - t0) / 1e9), (tFirst - t0) / 1_000_000)
        } catch (e: Exception) { Pair(0.0, -1L) }
        File(filesDir, "log.csv").appendText(
            "${System.currentTimeMillis()},$spot,$dbm,$type,${String.format(Locale.US, "%.2f", mbps)},$ms\n")
        val msg = if (ms < 0) "Offline · $dbm dBm · $type"
        else String.format(Locale.US, "%d dBm · %s · %.2f Mbps · %d ms", dbm, type, mbps, ms)
        h.post { if (running) show(msg) }
    }
}
