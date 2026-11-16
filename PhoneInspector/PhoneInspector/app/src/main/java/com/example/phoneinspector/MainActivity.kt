package com.example.phoneinspector

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var output: TextView
    private var benchText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 64, 24, 24)
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun btn(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }

        row1.addView(btn("Refresh") { refresh() })
        row1.addView(btn("Run benchmark") { runBenchmark() })
        row2.addView(btn("Copy report") { copyReport() })
        row2.addView(btn("Share") { shareReport() })
        root.addView(row1)
        root.addView(row2)

        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, 24, 0, 0)
        }
        val scroll = ScrollView(this)
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        refresh()
    }

    private fun refresh() {
        output.text = buildReport()
    }

    private fun copyReport() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("phone report", output.text))
        Toast.makeText(this, "Report copied. Paste it into the chat.", Toast.LENGTH_LONG).show()
    }

    private fun shareReport() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, output.text.toString())
        }
        startActivity(Intent.createChooser(i, "Share report"))
    }

    private fun gb(bytes: Long) = String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)

    private fun buildReport(): String {
        val sb = StringBuilder()
        fun sec(t: String) { sb.append("\n== ").append(t).append(" ==\n") }
        fun line(k: String, v: Any?) { sb.append(k).append(": ").append(v).append('\n') }

        sb.append("PHONE REPORT\n")

        sec("DEVICE")
        line("Brand/Model", "${Build.MANUFACTURER} ${Build.MODEL}")
        line("Device/Board", "${Build.DEVICE} / ${Build.BOARD}")
        line("Hardware", Build.HARDWARE)
        if (Build.VERSION.SDK_INT >= 31) line("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        line("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        line("Security patch", if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else "n/a")
        line("Build", Build.DISPLAY)
        line("ABIs", Build.SUPPORTED_ABIS.joinToString())
        line("Uptime since reboot", String.format(Locale.US, "%.1f hours", SystemClock.elapsedRealtime() / 3600000.0))

        sec("CPU")
        val cores = Runtime.getRuntime().availableProcessors()
        line("Cores", cores)
        val freqs = (0 until cores).map { i ->
            try {
                val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                "${f / 1000}MHz"
            } catch (e: Exception) { "?" }
        }
        line("Max freq per core", freqs.joinToString(" "))

        sec("MEMORY")
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        line("RAM total", gb(mi.totalMem))
        line("RAM available now", gb(mi.availMem))
        line("Low memory state", mi.lowMemory)
        line("Low-RAM device flag", am.isLowRamDevice)
        line("Per-app heap limit", "${am.memoryClass} MB")

        sec("STORAGE (internal)")
        val st = StatFs(Environment.getDataDirectory().path)
        line("Total", gb(st.totalBytes))
        line("Free", gb(st.availableBytes))
        line("Used %", String.format(Locale.US, "%.0f%%", 100.0 * (st.totalBytes - st.availableBytes) / st.totalBytes))

        sec("BATTERY")
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (b != null) {
            val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            line("Level", "${level * 100 / scale}%")
            line("Temperature", "${b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0} C")
            line("Health code (2=good)", b.getIntExtra(BatteryManager.EXTRA_HEALTH, -1))
            line("Charging code (2=charging)", b.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
            line("Technology", b.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY))
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        line("Battery saver on", pm.isPowerSaveMode)
        if (Build.VERSION.SDK_INT >= 29) line("Thermal status (0=none)", pm.currentThermalStatus)

        sec("DISPLAY")
        @Suppress("DEPRECATION")
        val d = windowManager.defaultDisplay
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        d.getRealMetrics(dm)
        line("Resolution", "${dm.widthPixels}x${dm.heightPixels}")
        line("Density", "${dm.densityDpi} dpi")
        line("Current refresh rate", "${d.refreshRate} Hz")
        line("Supported rates", d.supportedModes.map { it.refreshRate.toInt() }.distinct().sorted().joinToString(" ") + " Hz")

        sec("SPEED SETTINGS")
        fun scale(name: String) = try {
            Settings.Global.getFloat(contentResolver, name, 1f)
        } catch (e: Exception) { -1f }
        line("Window animation scale", scale(Settings.Global.WINDOW_ANIMATION_SCALE))
        line("Transition animation scale", scale(Settings.Global.TRANSITION_ANIMATION_SCALE))
        line("Animator duration scale", scale(Settings.Global.ANIMATOR_DURATION_SCALE))
        line("Developer options on", try {
            Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (e: Exception) { "?" })

        sec("APPS")
        try {
            val apps = packageManager.getInstalledApplications(0)
            val user = apps.filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            line("User-installed apps", user.size)
            line("System apps", apps.size - user.size)
            val names = user.map { packageManager.getApplicationLabel(it).toString() }.sorted()
            sb.append("User app names: ").append(names.take(120).joinToString(", ")).append('\n')
            if (names.size > 120) sb.append("(+${names.size - 120} more)\n")
        } catch (e: Exception) {
            line("App list error", e.message)
        }

        if (benchText.isNotEmpty()) {
            sec("BENCHMARK")
            sb.append(benchText)
        }
        return sb.toString()
    }

    private fun runBenchmark() {
        output.text = "Running benchmark (about 10 seconds)...\nDon't touch the phone."
        Thread {
            val r = StringBuilder()
            // CPU single-thread
            val t0 = System.nanoTime()
            var x = 0.0
            for (i in 1..30_000_000) x += Math.sqrt(i.toDouble())
            val cpuMs = (System.nanoTime() - t0) / 1_000_000
            r.append("CPU single-thread loop: ${cpuMs} ms (check ${x.toLong() % 1000})\n")

            // CPU multi-thread
            val n = Runtime.getRuntime().availableProcessors()
            val t1 = System.nanoTime()
            val ts = (1..n).map {
                Thread {
                    var y = 0.0
                    for (i in 1..10_000_000) y += Math.sqrt(i.toDouble())
                    if (y < 0) println(y)
                }.also { it.start() }
            }
            ts.forEach { it.join() }
            r.append("CPU multi-thread (${n} threads): ${(System.nanoTime() - t1) / 1_000_000} ms\n")

            // Storage
            try {
                val f = File(cacheDir, "bench.tmp")
                val chunk = ByteArray(1024 * 1024) { (it % 251).toByte() }
                val mb = 64
                val w0 = System.nanoTime()
                f.outputStream().use { o ->
                    repeat(mb) { o.write(chunk) }
                    o.fd.sync()
                }
                val wSec = (System.nanoTime() - w0) / 1e9
                val r0 = System.nanoTime()
                f.inputStream().use { i ->
                    val buf = ByteArray(1024 * 1024)
                    while (i.read(buf) > 0) { }
                }
                val rSec = (System.nanoTime() - r0) / 1e9
                f.delete()
                r.append(String.format(Locale.US, "Storage write: %.0f MB/s\n", mb / wSec))
                r.append(String.format(Locale.US, "Storage read (may be cached): %.0f MB/s\n", mb / rSec))
            } catch (e: Exception) {
                r.append("Storage test failed: ${e.message}\n")
            }
            benchText = r.toString()
            runOnUiThread { refresh() }
        }.start()
    }
}
