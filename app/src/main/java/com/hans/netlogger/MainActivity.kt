package com.hans.netlogger

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import java.io.File

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var spot: EditText
    private lateinit var toggle: Button
    private lateinit var report: TextView
    private var running = false
    private val file by lazy { File(filesDir, "log.csv") }
    private val prefs by lazy { getSharedPreferences("s", 0) }

    private val refresh = object : Runnable {
        override fun run() { showReport(); ui.postDelayed(this, 5_000L) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val need = mutableListOf(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        spot = EditText(this).apply { hint = "Where are you? (e.g. bedroom)"; setSingleLine() }
        spot.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { if (running) startSvc() }
            override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
        })
        toggle = Button(this).apply { setOnClickListener { flip() } }
        val row = LinearLayout(this)
        fun btn(label: String, act: () -> Unit) = row.addView(
            Button(this).apply { text = label; setOnClickListener { act() } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btn("Share CSV") { share() }
        btn("Clear log") { file.delete(); showReport() }
        report = TextView(this).apply { textSize = 15f; setPadding(0, 30, 0, 0) }
        listOf(spot, toggle, row, report).forEach { col.addView(it) }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        running = prefs.getBoolean("on", false)
        setLabel()
        ui.post(refresh)
    }

    override fun onPause() { super.onPause(); ui.removeCallbacks(refresh) }

    private fun setLabel() { toggle.text = if (running) "Stop logging" else "Start logging" }

    private fun startSvc() {
        val name = spot.text.toString().trim().ifEmpty { "unnamed" }.replace(",", " ")
        val i = Intent(this, LoggerService::class.java).putExtra("spot", name)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun flip() {
        if (running) startService(Intent(this, LoggerService::class.java).setAction("STOP")) else startSvc()
        running = !running
        prefs.edit().putBoolean("on", running).apply()
        setLabel()
    }

    private fun share() {
        if (!file.exists()) return
        val text = "time,spot,dbm,type,mbps,ms\n" + file.readLines().takeLast(5000).joinToString("\n")
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share log"))
    }

    private fun showReport() {
        val rows = if (file.exists()) Stats.parse(file.readLines()) else emptyList()
        val last = rows.lastOrNull()?.let {
            String.format("Last: %d dBm · %s · %.2f Mbps\n\n", it.dbm, it.type, it.mbps)
        } ?: ""
        report.text = last + Stats.report(rows)
    }
}
