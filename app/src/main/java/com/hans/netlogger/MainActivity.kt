package com.hans.netlogger

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    data class Row(val t: Long, val spot: String, val dbm: Int, val type: String, val mbps: Double)

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var spot: EditText
    private lateinit var toggle: Button
    private lateinit var status: TextView
    private lateinit var report: TextView
    private var running = false
    private val file by lazy { File(filesDir, "log.csv") }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            sample()
            ui.postDelayed(this, 120_000L) // one sample every 2 minutes
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 1)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        spot = EditText(this).apply { hint = "Where are you? (e.g. bedroom)"; setSingleLine() }
        toggle = Button(this).apply { text = "Start logging"; setOnClickListener { flip() } }
        status = TextView(this).apply { textSize = 16f; text = "Idle" }
        report = TextView(this).apply { textSize = 15f }
        listOf(spot, toggle, status, report).forEach { col.addView(it) }
        setContentView(ScrollView(this).apply { addView(col) })
        showReport()
    }

    private fun flip() {
        running = !running
        toggle.text = if (running) "Stop logging" else "Start logging"
        if (running) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ui.post(tick)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ui.removeCallbacks(tick)
            status.text = "Stopped"
        }
    }

    private fun readSignal(): Pair<Int, String> {
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
        return dbm to type
    }

    // Returns (Mbps, latency ms). (0.0, -1) means the test failed, i.e. an outage.
    private fun speedTest(): Pair<Double, Long> = try {
        val t0 = System.nanoTime()
        val c = URL("https://speed.cloudflare.com/__down?bytes=100000").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        val s = c.inputStream
        val first = s.read()
        val tFirst = System.nanoTime()
        var bytes = if (first >= 0) 1L else 0L
        val buf = ByteArray(8192)
        while (true) { val n = s.read(buf); if (n
