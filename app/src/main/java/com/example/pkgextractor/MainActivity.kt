package com.example.pkgextractor

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.content.res.ColorStateList
import android.os.Build
import android.os.StatFs
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ---------- palette ----------
private const val BG1 = 0xFF06030F.toInt()
private const val BG2 = 0xFF150A36.toInt()
private const val CARD = 0xF0140E30.toInt()
private const val STROKE = 0xFF33266B.toInt()
private const val CYAN = 0xFF4FD1FF.toInt()
private const val VIOLET = 0xFF8B5CF6.toInt()
private const val GREEN = 0xFF34D399.toInt()
private const val RED = 0xFFF87171.toInt()
private const val TXT = 0xFFE6EDF7.toInt()
private const val TXT2 = 0xFFB4B9E6.toInt()

// ---------- animated background ----------
private class BgView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val n = 18
    private val px = FloatArray(n) { Random.nextFloat() }
    private val py = FloatArray(n) { Random.nextFloat() }
    private val pr = FloatArray(n) { 0.006f + Random.nextFloat() * 0.02f }
    private val ps = FloatArray(n) { 0.02f + Random.nextFloat() * 0.05f }
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 9000; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            for (i in 0 until n) { py[i] -= ps[i] * 0.01f; if (py[i] < -0.05f) { py[i] = 1.05f; px[i] = Random.nextFloat() } }
            invalidate()
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val mid = ArgbEvaluator().evaluate(t, BG2, 0xFF0A1C52.toInt()) as Int
        p.shader = LinearGradient(0f, 0f, w * (0.4f + 0.6f * t), h, intArrayOf(BG1, mid, BG2), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        // soft colour glows that drift slowly
        p.shader = RadialGradient(w * (0.1f + 0.3f * t), h * 0.18f, w * 0.95f, intArrayOf(0x667C3AED, 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(w * (0.95f - 0.3f * t), h * 0.62f, w * 0.9f, intArrayOf(0x4D0EA5E9, 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(w * (0.2f + 0.2f * t), h * 1.0f, w * 0.8f, intArrayOf(0x44DB2777, 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        for (i in 0 until n) {
            p.color = if (i % 3 == 0) CYAN else if (i % 3 == 1) VIOLET else 0xFFF472B6.toInt()
            p.alpha = 38
            c.drawCircle(px[i] * w + sin((t * 6.28f) + i) * 12f, py[i] * h, pr[i] * w, p)
        }
    }
}

// ---------- progress ring ----------
private class RingView(ctx: Context) : View(ctx) {
    enum class S { IDLE, RUNNING, DONE, ERROR }
    private var state = S.IDLE
    private var shown = 0f
    private var spin = 0f
    private var pulse = 0f
    private var checkP = 0f
    private var label = "ready"
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x33A78BFA }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TXT; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TXT2; textAlign = Paint.Align.CENTER }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = GREEN }
    private val spinAnim = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2200; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { spin = it.animatedValue as Float; invalidate() }
    }
    private val pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
        addUpdateListener { pulse = it.animatedValue as Float }
    }
    private var progAnim: ValueAnimator? = null

    fun setMode(s: S, text: String) {
        state = s; label = text
        if (s == S.RUNNING) { if (!spinAnim.isStarted) spinAnim.start(); if (!pulseAnim.isStarted) pulseAnim.start() }
        else { spinAnim.cancel(); pulseAnim.cancel(); pulse = 0f }
        if (s == S.DONE) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 700; interpolator = DecelerateInterpolator()
                addUpdateListener { checkP = it.animatedValue as Float; invalidate() }
            }.start()
            animate().scaleX(1.08f).scaleY(1.08f).setDuration(220).withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator()).start()
            }.start()
        } else checkP = 0f
        invalidate()
    }

    fun setProgress(p: Float) {
        progAnim?.cancel()
        progAnim = ValueAnimator.ofFloat(shown, p.coerceIn(0f, 1f)).apply {
            duration = 450; interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun reset() { progAnim?.cancel(); shown = 0f; setMode(S.IDLE, "ready") }

    override fun onDetachedFromWindow() { spinAnim.cancel(); pulseAnim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val sw = w * 0.07f
        val r = RectF(sw * 1.6f, sw * 1.6f, w - sw * 1.6f, w - sw * 1.6f)
        track.strokeWidth = sw; arc.strokeWidth = sw
        c.drawOval(r, track)
        val cx = w / 2f; val cy = w / 2f
        val sweep = 360f * shown
        val colA = when (state) { S.DONE -> GREEN; S.ERROR -> RED; else -> CYAN }
        val colB = when (state) { S.DONE -> GREEN; S.ERROR -> RED; else -> VIOLET }
        val sg = SweepGradient(cx, cy, intArrayOf(colA, colB, colA), null)
        val m = Matrix(); m.postRotate(-90f, cx, cy); sg.setLocalMatrix(m)
        arc.shader = sg
        val arcSweep = if (state == S.DONE) 360f else sweep
        if (arcSweep > 0.5f) {
            // glow
            arc.alpha = (40 + 40 * pulse).toInt(); arc.strokeWidth = sw * 2.2f
            c.drawArc(r, -90f, arcSweep, false, arc)
            arc.alpha = 255; arc.strokeWidth = sw
            c.drawArc(r, -90f, arcSweep, false, arc)
        }
        if (state == S.RUNNING) {
            val ang = Math.toRadians((if (shown < 0.02f) spin else -90f + sweep).toDouble())
            val rad = (r.width() / 2f)
            dot.color = Color.WHITE; dot.alpha = (160 + 95 * pulse).toInt()
            c.drawCircle(cx + (rad * cos(ang)).toFloat(), cy + (rad * sin(ang)).toFloat(), sw * (0.55f + 0.25f * pulse), dot)
        }
        if (state == S.DONE) {
            val path = Path().apply { moveTo(w * 0.31f, w * 0.52f); lineTo(w * 0.45f, w * 0.65f); lineTo(w * 0.70f, w * 0.37f) }
            val pm = PathMeasure(path, false); val seg = Path()
            pm.getSegment(0f, pm.length * checkP, seg, true)
            checkPaint.strokeWidth = sw * 0.9f
            c.drawPath(seg, checkPaint)
        } else {
            big.textSize = w * 0.22f; small.textSize = w * 0.075f
            c.drawText("${(shown * 100).toInt()}%", cx, cy + w * 0.06f, big)
            c.drawText(label, cx, cy + w * 0.2f, small)
        }
    }
    override fun onMeasure(wSpec: Int, hSpec: Int) { val s = MeasureSpec.getSize(wSpec); setMeasuredDimension(s, s) }
}


// ---------- storage bar ----------
private class BarView(ctx: Context) : View(ctx) {
    private var frac = 0f
    private var warn = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var anim: ValueAnimator? = null
    fun set(target: Float, warning: Boolean) {
        warn = warning
        anim?.cancel()
        anim = ValueAnimator.ofFloat(frac, target.coerceIn(0f, 1f)).apply {
            duration = 600; interpolator = DecelerateInterpolator()
            addUpdateListener { frac = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        p.shader = null; p.color = 0x33A78BFA
        c.drawRoundRect(0f, 0f, w, h, r, r, p)
        val fw = (w * frac).coerceAtLeast(if (frac > 0f) h else 0f)
        if (fw > 0f) {
            p.shader = LinearGradient(0f, 0f, w, 0f, if (warn) intArrayOf(0xFFFBBF24.toInt(), RED) else intArrayOf(CYAN, VIOLET), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(0f, 0f, fw, h, r, r, p)
        }
    }
}


class MainActivity : Activity(), Engine.Listener {
    private var pkg: Uri? = null
    private var pkgName = ""
    private var pkgSize = 0L
    private val ui = Handler(Looper.getMainLooper())
    private var uiRunning = false
    private var shownFinalFor = 0L
    private var lastFile = ""
    private var tickCount = 0
    private var askedNotif = false

    private lateinit var ring: RingView
    private lateinit var pkgText: TextView
    private lateinit var fileText: TextView
    private lateinit var fileSub: TextView
    private lateinit var statsFiles: TextView
    private lateinit var statsBytes: TextView
    private lateinit var statsTime: TextView
    private lateinit var statsTemp: TextView
    private lateinit var status: TextView
    private lateinit var pickBtn: TextView
    private lateinit var goBtn: TextView
    private lateinit var cancelBtn: TextView
    private lateinit var openBtn: TextView
    private lateinit var storageBar: BarView
    private lateinit var storageText: TextView
    private lateinit var storageWarn: TextView
    private lateinit var outLabel: TextView
    private lateinit var customChip: TextView
    private val chips = ArrayList<TextView>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density
    private fun prefs() = getSharedPreferences("state", MODE_PRIVATE)

    // ---------- output folder ----------
    private fun outBase(): File = when (prefs().getInt("mode", 0)) {
        1 -> File(Environment.getExternalStorageDirectory(), "PS4Games")
        2 -> File(prefs().getString("custom", "") ?: "")
        else -> File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "pkgextracted")
    }

    private fun hasAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun askAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE), 2)
        }
    }

    // ---------- view helpers ----------
    private fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; if (stroke != 0) setStroke(strokeW, stroke)
    }

    private fun pressable(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(140).setInterpolator(OvershootInterpolator()).start()
            }
            false
        }
    }

    private fun button(label: String, filled: Boolean): TextView = TextView(this).apply {
        text = label; textSize = 16f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        setTextColor(if (filled) Color.WHITE else CYAN)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = if (filled) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(CYAN, VIOLET)).apply { cornerRadius = dpf(16) }
        else rounded(0x00000000, dpf(16), CYAN, dp(1))
        isClickable = true
        pressable(this)
    }

    private fun setEnabledLook(v: View, on: Boolean) { v.isEnabled = on; v.animate().alpha(if (on) 1f else 0.4f).setDuration(200).start() }

    private fun chip(label: String): TextView = TextView(this).apply {
        text = label; textSize = 13f; setTextColor(TXT2); gravity = Gravity.CENTER
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = rounded(0x00000000, dpf(20), STROKE, dp(1))
        isClickable = true; pressable(this)
    }

    private fun selectChip(index: Int) {
        chips.forEachIndexed { i, c ->
            val sel = i == index
            c.setTextColor(if (sel) Color.WHITE else TXT2)
            c.background = if (sel) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(CYAN and 0x00FFFFFF or 0x99000000.toInt(), VIOLET and 0x00FFFFFF or 0x99000000.toInt())).apply { cornerRadius = dpf(20); setStroke(dp(1), CYAN) }
            else rounded(0x00000000, dpf(20), STROKE, dp(1))
            if (sel) c.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).withEndAction { c.animate().scaleX(1f).scaleY(1f).setDuration(160).start() }.start()
        }
        outLabel.text = "→ ${outBase().absolutePath}"
        refreshStorage()
    }

    private fun label(text: String, size: Float = 13f, color: Int = TXT2, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = rounded(CARD, dpf(22), STROKE, dp(1)); setPadding(dp(16), dp(14), dp(16), dp(16))
    }

    private fun toggleRow(title: String, sub: String, key: String, def: Boolean): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 15f, TXT, true))
        texts.addView(label(sub, 11f, TXT2))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(10) })
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val sw = Switch(this).apply {
            isChecked = prefs().getBoolean(key, def)
            thumbTintList = ColorStateList(states, intArrayOf(CYAN, 0xFF6B7A90.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(0x884FD1FF.toInt(), 0x44FFFFFF))
            setOnCheckedChangeListener { _, on -> prefs().edit().putBoolean(key, on).apply() }
        }
        row.addView(sw)
        return row
    }

    // ---------- UI ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG1; window.navigationBarColor = BG1

        val root = FrameLayout(this)
        root.addView(BgView(this), FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(44), dp(20), dp(28)) }
        scroll.addView(col, FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        val animTargets = ArrayList<View>()
        fun gap(h: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(h) }
        fun add(v: View, lp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2)) { col.addView(v, lp); animTargets.add(v) }

        add(label("PKG Extractor", 30f, TXT, true))
        add(label("Unpack PS4 packages right on your phone", 14f), gap(2))

        // ring card
        val ringCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(CARD, dpf(28), STROKE, dp(1)); setPadding(dp(20), dp(22), dp(20), dp(20))
        }
        ring = RingView(this)
        ringCard.addView(ring, LinearLayout.LayoutParams(dp(220), dp(220)))
        fileText = label("No file yet", 16f, TXT, true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        fileSub = label("Choose a .pkg to begin", 12f).apply { gravity = Gravity.CENTER }
        ringCard.addView(fileText, gap(16)); ringCard.addView(fileSub, gap(2))
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun statCell(t: TextView) { t.gravity = Gravity.CENTER; stats.addView(t, LinearLayout.LayoutParams(0, -2, 1f)) }
        statsFiles = label("Items\n—", 12f, TXT); statsBytes = label("Data\n—", 12f, TXT)
        statsTime = label("Time\n—", 12f, TXT); statsTemp = label("Battery\n—", 12f, TXT)
        statCell(statsFiles); statCell(statsBytes); statCell(statsTime); statCell(statsTemp)
        ringCard.addView(stats, gap(16))
        add(ringCard, gap(18))

        // storage card
        val stCard = card()
        stCard.addView(label("STORAGE", 11f, TXT2, true))
        storageBar = BarView(this)
        stCard.addView(storageBar, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(10) })
        storageText = label("—", 13f, TXT)
        stCard.addView(storageText, gap(8))
        storageWarn = label("", 12f, RED).apply { visibility = View.GONE }
        stCard.addView(storageWarn, gap(4))
        add(stCard, gap(14))

        // package card
        val pkgCard = card()
        pkgCard.addView(label("PACKAGE", 11f, TXT2, true))
        pkgText = label("None selected", 15f, TXT).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        pkgCard.addView(pkgText, gap(4))
        pickBtn = button("Choose PKG", false)
        pkgCard.addView(pickBtn, gap(10))
        add(pkgCard, gap(14))

        // output folder card
        val outCard = card()
        outCard.addView(label("OUTPUT FOLDER", 11f, TXT2, true))
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val c1 = chip("Downloads"); val c2 = chip("PS4Games"); customChip = chip("Custom…")
        chips.addAll(listOf(c1, c2, customChip))
        for (c in chips) chipRow.addView(c, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        outCard.addView(chipRow, gap(10))
        outLabel = label("", 12f, TXT2).apply { setTextIsSelectable(true) }
        outCard.addView(outLabel, gap(10))
        add(outCard, gap(14))

        // options card
        val optCard = card()
        optCard.addView(label("OPTIONS", 11f, TXT2, true))
        optCard.addView(toggleRow("Cool mode", "Lower CPU priority, rest between files, pause if battery is hot (44°C+)", "cool", false), gap(10))
        optCard.addView(toggleRow("Run in background", "Keep extracting when you leave the app, with a progress notification", "bg", true), gap(14))
        add(optCard, gap(14))

        // actions
        goBtn = button("Extract", true); setEnabledLook(goBtn, false)
        cancelBtn = button("Cancel", false).apply { visibility = View.GONE }
        openBtn = button("Open output folder", false).apply { visibility = View.GONE }
        add(goBtn, gap(18)); add(cancelBtn, gap(10)); add(openBtn, gap(10))
        status = label("", 12f, TXT2).apply { gravity = Gravity.CENTER; setTextIsSelectable(true) }
        add(status, gap(12))

        c1.setOnClickListener { if (!Engine.running) { prefs().edit().putInt("mode", 0).apply(); selectChip(0) } }
        c2.setOnClickListener { if (!Engine.running) { prefs().edit().putInt("mode", 1).apply(); selectChip(1) } }
        customChip.setOnClickListener { if (!Engine.running) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 3) }
        val mode = prefs().getInt("mode", 0)
        if (mode == 2) customChip.text = "Custom: ${File(prefs().getString("custom", "") ?: "").name}"
        selectChip(mode)

        pickBtn.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 1)
        }
        goBtn.setOnClickListener { extract() }
        cancelBtn.setOnClickListener { Engine.cancel() }
        openBtn.setOnClickListener { openOutput() }

        animTargets.forEachIndexed { i, v ->
            v.alpha = 0f; v.translationY = dpf(28)
            v.animate().alpha(if (v.visibility != View.VISIBLE) 0f else if (v.isEnabled) 1f else 0.4f).translationY(0f)
                .setStartDelay(60L * i).setDuration(420).setInterpolator(DecelerateInterpolator()).start()
        }

        if (!hasAllFiles()) askAllFiles()
        saveCrashLogIfLastRunDied()
    }

    override fun onStart() {
        super.onStart()
        Engine.add(this)
        uiRunning = false
        render()
        ui.removeCallbacks(ticker); ui.post(ticker)
    }

    override fun onStop() {
        Engine.remove(this)
        ui.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (hasAllFiles() && !askedNotif && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askedNotif = true
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 5)
        }
        refreshStorage()
    }

    override fun onEngine() = render()

    // ---------- file picking ----------
    @Deprecated("fine for this app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            1 -> {
                if (Engine.running) return
                Engine.clearResult()
                pkg = uri
                pkgSize = 0L
                contentResolver.query(uri, null, null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) {
                        val ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME); val si = cur.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) pkgName = cur.getString(ni) ?: ""
                        if (si >= 0) pkgSize = cur.getLong(si)
                    }
                }
                if (pkgName.isEmpty()) pkgName = uri.lastPathSegment ?: "package.pkg"
                pkgText.text = pkgName
                fileText.text = pkgName
                fileSub.text = if (pkgSize > 0) fmt(pkgSize) + " · ready to extract" else "ready to extract"
                ring.reset()
                openBtn.visibility = View.GONE
                setEnabledLook(goBtn, true)
                status.text = ""
                refreshStorage()
            }
            3 -> {
                val path = treeToPath(uri)
                if (path == null) { Toast.makeText(this, "That folder can't be used. Pick one on internal or SD storage.", Toast.LENGTH_LONG).show(); return }
                prefs().edit().putInt("mode", 2).putString("custom", path).apply()
                customChip.text = "Custom: ${File(path).name}"
                selectChip(2)
            }
        }
    }

    private fun treeToPath(uri: Uri): String? {
        val id = DocumentsContract.getTreeDocumentId(uri)
        val parts = id.split(":", limit = 2)
        if (parts.size != 2) return null
        return if (parts[0] == "primary") File(Environment.getExternalStorageDirectory(), parts[1]).absolutePath
        else File("/storage/${parts[0]}", parts[1]).absolutePath
    }

    // ---------- storage ----------
    private fun refreshStorage() {
        if (!::storageBar.isInitialized) return
        val sf: StatFs? = Engine.statFor(outBase())
        if (sf == null) { storageText.text = "Storage info unavailable"; return }
        val total = sf.totalBytes; val free = sf.availableBytes; val used = total - free
        val needed = (pkgSize * 1.05).toLong()
        val low = free < 2L * 1024 * 1024 * 1024
        val notEnough = pkgSize > 0 && free < needed
        storageBar.set(if (total > 0) used.toFloat() / total else 0f, low || notEnough)
        storageText.text = "Used ${fmt(used)}  ·  Free ${fmt(free)}  ·  Total ${fmt(total)}"
        when {
            notEnough -> { storageWarn.text = "Not enough space: this package is ${fmt(pkgSize)} and extracted data can be larger. Only ${fmt(free)} free."; storageWarn.visibility = View.VISIBLE }
            low -> { storageWarn.text = "Low storage. Extraction stops automatically if less than 300 MB is left."; storageWarn.visibility = View.VISIBLE }
            else -> storageWarn.visibility = View.GONE
        }
    }

    // ---------- extraction ----------
    private fun extract() {
        val uri = pkg ?: return
        if (Engine.running) return
        if (!hasAllFiles()) { status.text = "Allow 'All files access', then press Extract again."; askAllFiles(); return }
        val out = outBase()
        if (!out.exists() && !out.mkdirs()) { status.text = "Cannot create ${out.absolutePath}"; return }
        if (!out.canWrite()) { status.text = "Cannot write to ${out.absolutePath}"; return }
        val started = Engine.start(this, uri, pkgName, out, prefs().getBoolean("cool", false))
        if (started && prefs().getBoolean("bg", true)) {
            try { startForegroundService(Intent(this, ExtractService::class.java)) } catch (t: Throwable) { status.text = "Background mode unavailable: ${t.message}" }
        }
        render()
    }

    /** Draws everything from the shared Engine state. */
    private fun render() {
        if (!::ring.isInitialized) return
        if (Engine.running) {
            if (!uiRunning) enterRunningUi()
            val frac = if (Engine.total > 0) Engine.done.toFloat() / Engine.total else Engine.percent / 100f
            ring.setProgress(frac)
            ring.setMode(RingView.S.RUNNING, if (Engine.cooling) "cooling" else "extracting")
            if (Engine.cnt > 0) {
                if (Engine.file != lastFile) { swapFile(Engine.file, if (Engine.fileSize > 0) fmt(Engine.fileSize) else "folder"); lastFile = Engine.file }
                statsFiles.text = "Items\n${Engine.idx} / ${Engine.cnt}"
                statsBytes.text = "Data\n${fmt(Engine.done)} / ${fmt(Engine.total)}"
            } else if (Engine.phase.isNotEmpty()) fileSub.text = Engine.phase
            cancelBtn.isEnabled = !Engine.cancelled
            status.text = when {
                Engine.cancelled -> "Cancelling… (finishing the current file)"
                Engine.cooling -> "Battery is hot. Paused until it cools down."
                else -> etaText()
            }
        } else if (uiRunning) {
            leaveRunningUi()
            showFinal()
        } else if (Engine.finished && shownFinalFor != Engine.endMs) {
            showFinal()
        }
    }

    private fun enterRunningUi() {
        uiRunning = true; lastFile = ""
        ring.reset(); ring.setMode(RingView.S.RUNNING, "starting")
        fileText.text = "Reading package…"; fileSub.text = Engine.pkgName
        statsFiles.text = "Items\n—"; statsBytes.text = "Data\n—"; statsTime.text = "Time\n0:00"
        setEnabledLook(pickBtn, false)
        goBtn.visibility = View.GONE; openBtn.visibility = View.GONE
        showPop(cancelBtn); cancelBtn.isEnabled = true
        for (c in chips) c.isEnabled = false
    }

    private fun leaveRunningUi() {
        uiRunning = false
        hidePop(cancelBtn)
        goBtn.visibility = View.VISIBLE; goBtn.alpha = 0f; goBtn.animate().alpha(1f).setDuration(250).start()
        setEnabledLook(goBtn, pkg != null); setEnabledLook(pickBtn, true)
        for (c in chips) c.isEnabled = true
        refreshStorage()
    }

    private fun showFinal() {
        shownFinalFor = Engine.endMs
        val err = Engine.error
        val secs = (Engine.endMs - Engine.startMs) / 1000
        when {
            err.isEmpty() -> {
                ring.setProgress(1f); ring.setMode(RingView.S.DONE, "done")
                fileText.text = "All done!"
                fileSub.text = "Extracted in ${clock(secs)}"
                status.text = Engine.resultPath.ifEmpty { outBase().absolutePath }
                showPop(openBtn)
            }
            err == "Cancelled" -> {
                ring.reset(); fileText.text = "Cancelled"; fileSub.text = "Partial files may remain in the output folder"; status.text = ""
            }
            else -> {
                ring.setMode(RingView.S.ERROR, "error")
                fileText.text = "Something went wrong"; fileSub.text = ""; status.text = err
            }
        }
    }

    private fun swapFile(name: String, sub: String) {
        fileText.animate().alpha(0f).translationY(-dpf(8)).setDuration(110).withEndAction {
            fileText.text = name; fileSub.text = sub
            fileText.translationY = dpf(8)
            fileText.animate().alpha(1f).translationY(0f).setDuration(160).start()
        }.start()
    }

    private fun etaText(): String {
        val el = (System.currentTimeMillis() - Engine.startMs) / 1000
        if (Engine.total > 0 && Engine.done > 0) {
            val eta = el * (Engine.total - Engine.done) / Engine.done
            return "About ${clock(eta)} left"
        }
        return ""
    }

    private val ticker = object : Runnable {
        override fun run() {
            tickCount++
            val t = Engine.batteryTemp().takeIf { it > 0f } ?: readTemp()
            statsTemp.text = if (t > 0f) "Battery\n%.1f°C".format(t) else "Battery\n—"
            statsTemp.setTextColor(if (t >= 44f) RED else if (t >= 40f) 0xFFFBBF24.toInt() else TXT)
            if (Engine.running) {
                statsTime.text = "Time\n${clock((System.currentTimeMillis() - Engine.startMs) / 1000)}"
                if (!Engine.cancelled && !Engine.cooling) status.text = etaText()
            }
            if (tickCount % 3 == 0) refreshStorage()
            ui.postDelayed(this, 1000)
        }
    }

    private fun readTemp(): Float {
        val i = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0f
        return i.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
    }

    private fun clock(sec: Long): String =
        if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

    private fun showPop(v: View) {
        v.visibility = View.VISIBLE; v.alpha = 0f; v.scaleX = 0.85f; v.scaleY = 0.85f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator()).start()
    }

    private fun hidePop(v: View) {
        v.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(180).withEndAction { v.visibility = View.GONE; v.scaleX = 1f; v.scaleY = 1f }.start()
    }

    private fun openOutput() {
        val p = Engine.resultPath.ifEmpty { outBase().absolutePath }
        try {
            val base = Environment.getExternalStorageDirectory().absolutePath
            if (p.startsWith(base)) {
                val rel = p.removePrefix(base).trim('/')
                val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else Toast.makeText(this, p, Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "Open your Files app and go to $p", Toast.LENGTH_LONG).show()
        }
    }

    private fun fmt(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
        b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
        b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
        else -> "$b B"
    }

    // ---------- crash log ----------
    private fun saveCrashLogIfLastRunDied() {
        if (Engine.running || !prefs().getBoolean("running", false)) return
        prefs().edit().putBoolean("running", false).commit()
        Thread {
            try {
                val text = listOf(arrayOf("logcat", "-d", "-b", "crash", "-t", "300"), arrayOf("logcat", "-d", "-t", "1500", "*:W"))
                    .joinToString("\n\n=====\n\n") { Runtime.getRuntime().exec(it).inputStream.bufferedReader().readText() }
                for (dir in listOfNotNull(getExternalFilesDir(null), outBase())) {
                    try { dir.mkdirs(); File(dir, "crash_log.txt").writeText(text) } catch (_: Throwable) {}
                }
                runOnUiThread { status.text = "Last run crashed. Log saved: ${getExternalFilesDir(null)}/crash_log.txt" }
            } catch (_: Throwable) {}
        }.start()
    }
}
