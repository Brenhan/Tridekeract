package com.multidim.tridekeract

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var viz: Viz
    private lateinit var renderer: Renderer
    private lateinit var glView: GLSurfaceView
    private lateinit var engine: AudioEngine
    private lateinit var status: TextView
    private lateinit var dock: View
    private lateinit var feel: LinearLayout
    private lateinit var playBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var appsBtn: TextView
    private lateinit var micBtn: TextView
    private val modeBtns = HashMap<ViewMode, TextView>()
    private val ui = Handler(Looper.getMainLooper())
    private var density = 1f
    private var dark = false

    // Colors from the web page
    private var cFg = 0; private var cMuted = 0; private var cLine = 0; private var cAccent = 0; private var cFill = 0; private var cPanel = 0

    private var trackUri: Uri? = null
    private var trackName = ""

    private val prefs by lazy { getSharedPreferences("tridek", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        density = resources.displayMetrics.density
        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        setColors()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val reduced = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        viz = Viz(reduced)
        viz.gOpen = prefs.getFloat("open", Viz.GOPEN.toFloat()).toDouble().coerceIn(0.0, 1.0)
        viz.gSlow = prefs.getFloat("slow", Viz.GSLOW.toFloat()).toDouble().coerceIn(0.1, 20.0)
        viz.bloom = prefs.getFloat("bloom", Viz.GBLOOM.toFloat()).toDouble().coerceIn(0.02, 1.0)
        viz.depth = prefs.getFloat("depth", Viz.GDEPTH.toFloat()).toDouble().coerceIn(PI / 18, PI)
        viz.gFast = prefs.getFloat("fast", Viz.GFAST.toFloat()).toDouble().coerceIn(0.0, 3.0)
        viz.gClose = prefs.getFloat("close", Viz.GCLOSE.toFloat()).toDouble().coerceIn(0.0, 1.0)

        engine = AudioEngine(this)
        engine.onFinished = { ui.post { engine.stopTrack(); setStatus("Finished: $trackName. Tap Play to hear it again."); refresh(); showUI() } }
        engine.onError = { msg -> ui.post { engine.stopTrack(); setStatus(msg); refresh(); showUI() } }
        CaptureService.onStopped = { ui.post { refresh(); if (Shared.source == Source.NONE) setStatus("Stopped listening to other apps."); showUI() } }

        renderer = Renderer(viz, density)
        renderer.mode = try { ViewMode.valueOf(prefs.getString("mode", "CROSS")!!) } catch (_: Exception) { ViewMode.CROSS }
        renderer.palette = if (dark)
            Palette(rgb(0x141015), rgb(0xEFE6EC), 80f, 66f)
        else
            Palette(rgb(0xF6F3F5), rgb(0x22161E), 72f, 44f)
        renderer.onFrame = { dt ->
            val src = Shared.source
            val live = when (src) { Source.TRACK -> !engine.paused; Source.APPS, Source.MIC -> true; else -> false }
            if (Shared.restart) { Shared.restart = false; viz.resetWarm() }
            val end = if (src == Source.TRACK) Shared.trackEnd() else -1L
            viz.readBands(dt, live, Shared.analyser, end)
            viz.step(dt, live)
        }

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(MsaaChooser())
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            contentDescription = "Fifteen-dimensional cube moving with the music. Drag to turn, two fingers to fold through hidden dimensions, pinch to zoom, double-tap to reset, long-press for feel settings."
            setOnTouchListener { _, e -> onTouch(e); true }
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(if (dark) 0xFF141015.toInt() else 0xFFF6F3F5.toInt())
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))

        status = TextView(this).apply {
            setTextColor(cMuted); textSize = 13f; gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), 0)
            text = "Tap Other apps and play music in YouTube or Spotify, use the Microphone, or choose a track from your phone."
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        root.addView(buildDock(), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(12) })
        feel = buildFeel()
        // Scrolls when the panel is taller than the screen (six sliders plus meters).
        val feelScroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(feel) }
        root.addView(feelScroll, FrameLayout.LayoutParams(dp(330), -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(10); rightMargin = dp(12); bottomMargin = dp(10) })
        feel.visibility = View.GONE

        setContentView(root)
        immersive()
        refresh()
        showUI()
    }

    // ---------------- look ----------------
    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
    private fun dp(v: Int) = (v * density).roundToInt()

    private fun setColors() {
        if (dark) {
            cFg = 0xFFEFE6EC.toInt(); cMuted = 0xFFA18E9B.toInt(); cLine = 0xFF3A2E38.toInt()
            cAccent = 0xFFE58CCB.toInt(); cFill = 0x24E58CCB; cPanel = 0xE01C161D.toInt()
        } else {
            cFg = 0xFF22161E.toInt(); cMuted = 0xFF6A5964.toInt(); cLine = 0xFFE0D5DC.toInt()
            cAccent = 0xFF9A3A7E.toInt(); cFill = 0x1F9A3A7E; cPanel = 0xE0FFFFFF.toInt()
        }
    }

    private fun panelBg() = GradientDrawable().apply { setColor(cPanel); setStroke(dp(1), cLine); cornerRadius = dp(14).toFloat() }

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(cFg); gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(42); setPadding(dp(12), dp(6), dp(12), dp(6))
        isClickable = true; isFocusable = true
        setOnClickListener { onClick(); showUI() }
        style(this, false)
    }

    private fun style(b: TextView, on: Boolean) {
        b.background = GradientDrawable().apply {
            setColor(if (on) cFill else Color.TRANSPARENT); setStroke(dp(1), if (on) cAccent else cLine); cornerRadius = dp(9).toFloat()
        }
        b.setTextColor(if (on) cAccent else cFg)
    }

    private fun buildDock(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        fun add(v: View, gap: Boolean = true) { row.addView(v, LinearLayout.LayoutParams(-2, -2).apply { if (gap) leftMargin = dp(6) }) }
        playBtn = button("Play") { onPlay() }
        stopBtn = button("Stop") { stopAllSound() }
        appsBtn = button("Other apps") { onApps() }
        micBtn = button("Microphone") { onMic() }
        val pick = button("Choose a track") { onPick() }
        add(playBtn, false); add(stopBtn); add(appsBtn); add(micBtn); add(pick)
        val sep = View(this).apply { setBackgroundColor(cLine) }
        row.addView(sep, LinearLayout.LayoutParams(dp(1), dp(30)).apply { leftMargin = dp(8); rightMargin = dp(2); gravity = Gravity.CENTER_VERTICAL })
        for ((m, label) in listOf(ViewMode.MONO to "Single", ViewMode.CROSS to "Cross-eyed", ViewMode.PARALLEL to "Parallel")) {
            val b = button(label) { renderer.mode = m; prefs.edit().putString("mode", m.name).apply(); refresh() }
            modeBtns[m] = b; add(b)
        }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; background = panelBg(); addView(row) }
        scroll.setOnTouchListener { _, _ -> showUI(); false }
        dock = scroll
        return scroll
    }

    private val feelBars = ArrayList<Pair<SeekBar, () -> Int>>()

    private fun buildFeel(): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = panelBg(); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        box.addView(TextView(this).apply { text = "Feel"; setTextColor(cFg); textSize = 13f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        box.setOnTouchListener { _, _ -> showUI(); true }
        /** A linear slider from 0 to [top] in [steps] steps. */
        fun slider(label: String, top: Double, steps: Int, show: (Double) -> String, describe: String,
                   get: () -> Double, set: (Double) -> Unit): View {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val name = TextView(this).apply { text = label; setTextColor(cMuted); textSize = 12f; typeface = Typeface.MONOSPACE }
            val out = TextView(this).apply { setTextColor(cFg); textSize = 12f; typeface = Typeface.MONOSPACE; gravity = Gravity.END }
            val bar = SeekBar(this)
            bar.max = steps
            val pos = { (get() / top * steps).roundToInt().coerceIn(0, steps) }
            bar.progress = pos()
            out.text = show(get())
            bar.contentDescription = describe
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val t = p * top / steps; out.text = show(t)
                    if (fromUser) { synchronized(viz) { set(t) }; saveFeel(); showUI() }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            feelBars.add(bar to pos)
            row.addView(name, LinearLayout.LayoutParams(dp(84), -2))
            row.addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(out, LinearLayout.LayoutParams(dp(56), -2))
            return row
        }
        val pct = { t: Double -> "${(t * 100).roundToInt()}%" }
        // Times are in 0.005 s steps; 0 means instant.
        box.addView(slider("React", 3.0, 600, ::fmt, "React: how long each band's now is averaged over", { viz.gFast }) { viz.gFast = it })
        box.addView(slider("Linger", 1.0, 200, ::fmt, "Linger: how long an opening takes to fold back", { viz.gClose }) { viz.gClose = it })
        box.addView(slider("Attack", 1.0, 200, ::fmt, "Attack: how fast a dimension opens", { viz.gOpen }) { viz.gOpen = it })
        box.addView(slider("Memory", 20.0, 200, ::fmt, "Memory: how long a band's sense of normal lasts", { viz.gSlow }) { viz.gSlow = max(0.1, it) })
        box.addView(slider("Bloom", 1.0, 100, pct, "Bloom: how much of the time each band aims to be open", { viz.bloom }) { viz.bloom = max(0.02, it) })
        box.addView(slider("Depth", PI, 180, { t -> "${(t * 180 / PI).roundToInt()}°" }, "Depth: how far each fold swings when fully open", { viz.depth }) { viz.depth = max(PI / 18, it) })
        box.addView(MeterView(this, viz, cFg, cMuted, cAccent), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val reset = button("Reset") {
            synchronized(viz) { viz.gOpen = Viz.GOPEN; viz.gSlow = Viz.GSLOW; viz.bloom = Viz.GBLOOM; viz.depth = Viz.GDEPTH; viz.gFast = Viz.GFAST; viz.gClose = Viz.GCLOSE }
            saveFeel()
            for ((bar, pos) in feelBars) bar.progress = pos()
        }
        val done = button("Done") { closeFeel() }
        btns.addView(reset, LinearLayout.LayoutParams(0, -2, 1f))
        btns.addView(done, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        box.addView(btns, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        return box
    }

    private fun fmt(t: Double) = when {
        t == 0.0 -> "0s"
        t < 0.1 -> String.format("%.3fs", t)
        t < 1 -> String.format("%.2fs", t)
        t < 10 -> String.format("%.1fs", t)
        else -> "${t.roundToInt()}s"
    }

    private fun saveFeel() { prefs.edit().putFloat("fast", viz.gFast.toFloat()).putFloat("close", viz.gClose.toFloat()).putFloat("open", viz.gOpen.toFloat()).putFloat("slow", viz.gSlow.toFloat()).putFloat("bloom", viz.bloom.toFloat()).putFloat("depth", viz.depth.toFloat()).apply() }
    private fun openFeel() { feel.visibility = View.VISIBLE; glView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); showUI() }
    private fun closeFeel() { feel.visibility = View.GONE; showUI() }

    private fun setStatus(s: String) { status.text = s }

    /** Update button labels and highlights to match what's happening. */
    private fun refresh() {
        val src = Shared.source
        playBtn.text = when {
            src == Source.TRACK && !engine.paused -> "Pause"
            src == Source.TRACK && engine.paused -> "Resume"
            else -> "Play"
        }
        style(playBtn, true)
        stopBtn.visibility = if (src != Source.NONE) View.VISIBLE else View.GONE
        style(appsBtn, src == Source.APPS)
        style(micBtn, src == Source.MIC)
        for ((m, b) in modeBtns) style(b, m == renderer.mode)
    }

    // ---------------- controls that fade out ----------------
    private val hideRun = Runnable {
        val live = Shared.source != Source.NONE && !(Shared.source == Source.TRACK && engine.paused)
        if (live && feel.visibility != View.VISIBLE) fade(false)
    }
    private var shown = true
    private fun fade(show: Boolean) {
        shown = show
        for (v in listOf(status, dock, feel)) {
            v.animate().cancel()
            if (show) {
                if (v !== feel) v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(450).start()
            } else {
                v.animate().alpha(0f).setDuration(450).withEndAction { if (!shown && v === dock) v.visibility = View.INVISIBLE }.start()
            }
        }
    }
    private fun showUI() {
        if (!shown) fade(true)
        ui.removeCallbacks(hideRun)
        ui.postDelayed(hideRun, 3200)
    }
    private fun toggleUI() {
        val live = Shared.source != Source.NONE && !(Shared.source == Source.TRACK && engine.paused)
        if (!shown) showUI() else if (live) { ui.removeCallbacks(hideRun); fade(false) } else showUI()
    }

    // ---------------- sound sources ----------------
    private fun onPlay() {
        val src = Shared.source
        when {
            src == Source.TRACK && !engine.paused -> { engine.pause(); setStatus("Paused: $trackName") }
            src == Source.TRACK && engine.paused -> { engine.resume(); setStatus("Playing: $trackName") }
            trackUri == null -> onPick()
            else -> { stopListening(); engine.playTrack(trackUri!!, trackName); setStatus("Playing: $trackName") }
        }
        refresh()
    }

    private fun stopAllSound() {
        engine.stopTrack(); engine.stopMic(); stopCapture()
        setStatus("Stopped.")
        refresh()
    }

    private fun stopListening() { engine.stopMic(); stopCapture() }

    private fun stopCapture() {
        if (CaptureService.running) startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
    }

    private fun onMic() {
        if (Shared.source == Source.MIC) { engine.stopMic(); setStatus("Microphone off."); refresh(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return
        }
        startMic()
    }

    private fun startMic() {
        engine.stopTrack(); stopCapture()
        if (engine.startMic()) setStatus("Listening through the microphone. Play music nearby.")
        else setStatus("Couldn’t open the microphone.")
        refresh()
    }

    private fun onApps() {
        if (Shared.source == Source.APPS) { stopCapture(); return }
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), REQ_APPS); return }
        askProjection()
    }

    private fun askProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ)
    }

    private fun onPick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
        @Suppress("DEPRECATION")
        try { startActivityForResult(i, REQ_FILE) } catch (_: Exception) { setStatus("No file picker is available.") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_MIC -> if (micOk) startMic() else setStatus("The microphone needs permission to listen.")
            REQ_APPS -> if (micOk) askProjection() else setStatus("Listening to other apps needs the audio-recording permission.")
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PROJ -> {
                if (resultCode == RESULT_OK && data != null) {
                    engine.stopTrack(); engine.stopMic()
                    val i = Intent(this, CaptureService::class.java)
                        .putExtra(CaptureService.EXTRA_CODE, resultCode)
                        .putExtra(CaptureService.EXTRA_DATA, data)
                    startForegroundService(i)
                    setStatus("Listening to your other apps. Start music in YouTube or any player, then come back. (Some apps don’t allow it and stay silent.)")
                    ui.postDelayed({ refresh() }, 400)
                } else setStatus("Listening to other apps was cancelled.")
            }
            REQ_FILE -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) {
                    trackUri = uri
                    trackName = nameOf(uri)
                    stopListening()
                    engine.playTrack(uri, trackName)
                    setStatus("Playing: $trackName")
                }
            }
        }
        refresh(); showUI()
    }

    private fun nameOf(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0).substringBeforeLast('.')
            }
        } catch (_: Exception) {}
        return "your track"
    }

    // ---------------- gestures (same as the web page) ----------------
    private val px = HashMap<Int, Float>()
    private val py = HashMap<Int, Float>()
    private var tapX = 0f; private var tapY = 0f; private var tapT = 0L; private var tapOn = false; private var tapMoved = false
    private var lastTap = 0L
    private val pressRun = Runnable {
        if (tapOn && !tapMoved && px.size == 1) { tapOn = false; synchronized(viz) { viz.vYaw = 0.0; viz.vPitch = 0.0 }; openFeel() }
    }

    private fun onTouch(e: MotionEvent) {
        val d = density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; val id = e.getPointerId(i)
                px[id] = e.getX(i) / d; py[id] = e.getY(i) / d
                synchronized(viz) { viz.vYaw = 0.0; viz.vPitch = 0.0; viz.vFx = 0.0; viz.vFy = 0.0; viz.fingers = px.size }
                if (px.size == 1) { tapOn = true; tapMoved = false; tapX = px[id]!!; tapY = py[id]!!; tapT = SystemClock.uptimeMillis() } else tapOn = false
                ui.removeCallbacks(pressRun)
                if (px.size == 1) ui.postDelayed(pressRun, 550)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val ox = px[id] ?: continue; val oy = py[id] ?: continue
                    val cx = e.getX(i) / d; val cy = e.getY(i) / d
                    if (cx == ox && cy == oy) continue
                    if (tapOn && hypot(cx - tapX, cy - tapY) > 8) { tapMoved = true; ui.removeCallbacks(pressRun) }
                    synchronized(viz) {
                        if (px.size == 1) {
                            val dx = (cx - ox).toDouble(); val dy = (cy - oy).toDouble()
                            viz.ay += dx * 0.01; viz.ax += dy * 0.01; viz.vYaw = dx * 0.01; viz.vPitch = dy * 0.01
                        } else {
                            val oid = px.keys.first { it != id }
                            val qx = px[oid]!!; val qy = py[oid]!!
                            val mdx = ((cx + qx) / 2 - (ox + qx) / 2).toDouble()
                            val mdy = ((cy + qy) / 2 - (oy + qy) / 2).toDouble()
                            val d0 = hypot(ox - qx, oy - qy); val d1 = hypot(cx - qx, cy - qy)
                            val a0 = atan2(oy - qy, ox - qx); val a1 = atan2(cy - qy, cx - qx)
                            viz.foldBy(mdx * 2, mdy * 2); viz.vFx = mdx * 2; viz.vFy = mdy * 2
                            if (d0 > 10) viz.zoom = max(0.4, min(3.0, viz.zoom * d1 / d0))
                            var da = (a1 - a0).toDouble()
                            if (da > PI) da -= 2 * PI
                            if (da < -PI) da += 2 * PI
                            viz.roll -= da
                        }
                    }
                    px[id] = cx; py[id] = cy
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = e.getPointerId(e.actionIndex)
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) { px.clear(); py.clear() } else { px.remove(id); py.remove(id) }
                synchronized(viz) { viz.fingers = px.size }
                ui.removeCallbacks(pressRun)
                if (tapOn && !tapMoved && SystemClock.uptimeMillis() - tapT < 300 && px.isEmpty() && e.actionMasked != MotionEvent.ACTION_CANCEL) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTap < 320) { synchronized(viz) { viz.resetView() }; lastTap = 0 }
                    else { lastTap = now; if (feel.visibility == View.VISIBLE) closeFeel() else toggleUI() }
                }
                if (px.isEmpty()) tapOn = false
            }
        }
    }

    // ---------------- lifecycle ----------------
    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive() }
    override fun onResume() { super.onResume(); glView.onResume(); refresh() }
    override fun onPause() { glView.onPause(); super.onPause() }
    override fun onStop() {
        super.onStop()
        if (Shared.source == Source.TRACK && !engine.paused) { engine.pause(); setStatus("Paused: $trackName") }
        if (Shared.source == Source.MIC) engine.stopMic()
    }
    override fun onDestroy() {
        engine.release()
        CaptureService.onStopped = null
        super.onDestroy()
    }

    companion object {
        private const val REQ_MIC = 1
        private const val REQ_APPS = 2
        private const val REQ_PROJ = 3
        private const val REQ_FILE = 4
    }
}
