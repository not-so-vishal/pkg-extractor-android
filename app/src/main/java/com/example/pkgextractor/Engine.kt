package com.example.pkgextractor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.provider.DocumentsContract
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** Single extraction job that lives in the process, independent of the Activity. */
object Engine {
    interface Listener { fun onEngine() }

    @Volatile var running = false
    @Volatile var finished = false
    @Volatile var error = ""
    @Volatile var cancelled = false
    @Volatile var phase = ""
    @Volatile var file = ""
    @Volatile var fileSize = 0L
    @Volatile var idx = 0
    @Volatile var cnt = 0
    @Volatile var done = 0L
    @Volatile var total = 0L
    @Volatile var percent = 0
    @Volatile var startMs = 0L
    @Volatile var endMs = 0L
    @Volatile var resultPath = ""
    @Volatile var pkgName = ""
    @Volatile var cooling = false
    @Volatile private var cool = false
    @Volatile private var lowStorage = false
    private var lastCb = 0L
    private var appCtx: Context? = null

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val posted = AtomicBoolean(false)

    fun add(l: Listener) { listeners.add(l) }
    fun remove(l: Listener) { listeners.remove(l) }

    private fun fire() {
        if (posted.compareAndSet(false, true)) main.post {
            posted.set(false)
            for (l in listeners) l.onEngine()
        }
    }

    fun cancel() { cancelled = true; fire() }

    fun clearResult() { if (!running) { finished = false; error = ""; fire() } }

    fun batteryTemp(): Float {
        val c = appCtx ?: return 0f
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0f
        return i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
    }

    fun realPath(uri: Uri): String? {
        if (uri.authority == "com.android.externalstorage.documents") {
            val parts = DocumentsContract.getDocumentId(uri).split(":", limit = 2)
            if (parts.size == 2) {
                val f = if (parts[0] == "primary") File(Environment.getExternalStorageDirectory(), parts[1]) else File("/storage/${parts[0]}", parts[1])
                if (f.canRead()) return f.absolutePath
            }
        }
        return null
    }

    fun statFor(f: File): StatFs? {
        return try {
            var x = f
            while (!x.exists() && x.parentFile != null) x = x.parentFile!!
            StatFs(x.absolutePath)
        } catch (_: Throwable) { null }
    }

    private fun sleepMs(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    /** Cool mode: pause after each file (about 60% of the time it took) and wait while the battery is hot. */
    private fun throttle() {
        if (!cool) return
        val now = SystemClock.elapsedRealtime()
        if (lastCb != 0L) sleepMs(minOf(((now - lastCb) * 0.6).toLong(), 3000L))
        if (batteryTemp() >= 44f) {
            cooling = true; fire()
            while (!cancelled && batteryTemp() > 41f) sleepMs(2000)
            cooling = false
        }
        lastCb = SystemClock.elapsedRealtime()
    }

    private fun handle(p: Int, msg: String) {
        if (msg.startsWith("F\t")) {
            val a = msg.split("\t")
            if (a.size >= 7) {
                throttle()
                file = a[1]; fileSize = a[2].toLongOrNull() ?: 0L
                idx = a[3].toIntOrNull() ?: 0; cnt = a[4].toIntOrNull() ?: 0
                done = a[5].toLongOrNull() ?: 0L; total = a[6].toLongOrNull() ?: 0L
                percent = if (total > 0) (done * 100 / total).toInt() else p
                fire()
            }
        } else if (msg.startsWith("D\t")) {
            resultPath = msg.substring(2)
        } else {
            phase = msg; fire()
        }
    }

    fun start(ctx: Context, uri: Uri, name: String, out: File, coolMode: Boolean): Boolean {
        if (running) return false
        val app = ctx.applicationContext
        appCtx = app
        running = true; finished = false; error = ""; cancelled = false; cooling = false; lowStorage = false
        phase = ""; file = ""; fileSize = 0; idx = 0; cnt = 0; done = 0; total = 0; percent = 0; resultPath = ""
        pkgName = name; cool = coolMode; lastCb = 0L
        startMs = System.currentTimeMillis(); endMs = 0L
        val prefs = app.getSharedPreferences("state", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("running", true).commit()
        fire()

        Thread(null, {
            if (cool) Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            // stop before the disk is completely full
            val watchdog = Thread {
                try {
                    while (true) {
                        Thread.sleep(2000)
                        val sf = statFor(out)
                        if (sf != null && sf.availableBytes < 300L * 1024 * 1024 && !cancelled) {
                            lowStorage = true; cancelled = true; fire()
                        }
                    }
                } catch (_: InterruptedException) {}
            }
            watchdog.isDaemon = true; watchdog.start()

            var err: String
            try {
                val progress = NativeBridge.Progress { p, f -> handle(p, f); !cancelled }
                val direct = realPath(uri)
                err = if (direct != null) NativeBridge.extract(direct, out.absolutePath, progress)
                else app.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    NativeBridge.extract("/proc/self/fd/${pfd.fd}", out.absolutePath, progress)
                } ?: "Cannot open file"
            } catch (t: Throwable) {
                err = "${t.javaClass.simpleName}: ${t.message}"
            }
            watchdog.interrupt()
            if (lowStorage && err == "Cancelled") err = "Stopped: storage almost full"
            prefs.edit().putBoolean("running", false).commit()
            error = err; endMs = System.currentTimeMillis()
            cooling = false; running = false; finished = true
            fire()
        }, "extract", 64L * 1024 * 1024).start()
        return true
    }
}
