package com.multidim.tridekeract

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything the Pentadekeract (15-cube) does each frame.
 * 12 frequency bands each build up one fold (4D–15D) of the 15-cube: while a band is above its
 * normal level its fold opens a step at a time, and it sinks back between hits. Each band tunes its
 * own sensitivity so its fold is, on average, as open as Bloom asks. Folds that open push the cube
 * around in 3D; hidden axes are projected away.
 * All public state is guarded by `synchronized(this)` — touch input comes from the UI thread.
 */
class Viz(private val reducedMotion: Boolean) {
    companion object {
        const val MAX = 16
        const val HB = 12
        const val DIM = 15
        const val OPEN_MAX = 1.2
        const val FLOOR = 0.035
        const val HD = 5.0
        const val PACE = 0.5
        const val LO = 20.0
        const val HI = 14000.0
        const val LOW_WIDEN = 0.45
        const val ADAPT = 0.6
        const val CAP = 0.027
        const val SMOOTH = 0.06   // s: how quickly a fold glides to its built-up amount
        const val HOLD_RATE = 2.0 // steps per second added while a sound holds above normal
        const val GSLOW = 3.4
        const val GBLOOM = 0.14
        const val GBLOOM_HI = 0.6
        const val GDEPTH = 1.2
        const val GSTEP = 0.2
        const val GLINGER = 3.0
        const val GADAPT = 2.0
        val BASE = doubleArrayOf(0.8, 0.5, 0.35)
    }

    private val SPIN_IDLE = if (reducedMotion) 0.3 else 0.6

    // Band i tilts visible axis planeV into its own hidden axis planeH (one band per foldable dimension).
    val planeV = IntArray(HB) { (it + it / (DIM - 3)) % 3 }
    val planeH = IntArray(HB) { 3 + (it % (DIM - 3)) }

    // The Feel panel's settings.
    /** Step: how much of a full opening each hit adds. */
    var stepSize = GSTEP
    /** Linger: how long a built-up opening takes to sink back. */
    var linger = GLINGER
    /** Memory: how long a band's sense of "normal" lasts. */
    var gSlow = GSLOW
    /** Bloom: how open each dimension aims to be on average, in the sparsest parts. */
    var bloom = GBLOOM
    /** Bloom high: the same for the fullest parts; Bloom slides between the two. */
    var bloomHi = GBLOOM_HI
    /** The Bloom in effect right now (for the meters). */
    var bloomNow = GBLOOM
        private set
    // How full the music is right now: loudness over the last ~4 s, placed between the quietest and
    // loudest it has been lately (both forget slowly, over about a minute).
    private var iE = 0.0
    private var iHi = 0.0
    private var iLo = 0.0
    var intensity = 0.0
        private set
    /** Depth: how far each fold swings when fully open, in radians. */
    var depth = GDEPTH

    val sgn = DoubleArray(MAX) { 1.0 }
    val hOpen = DoubleArray(MAX)
    val domBand = IntArray(MAX)
    val openAmt = DoubleArray(MAX)
    val manual = DoubleArray(MAX)
    private val bSlow = DoubleArray(MAX)                   // each band's normal level
    private val bThr = DoubleArray(MAX) { 0.15 }           // sensitivity margin above normal
    private val built = DoubleArray(MAX)                   // how far each fold has built up (0..1)
    private val avgOpen = DoubleArray(MAX)                 // its average over the Adapt time
    private val above = BooleanArray(MAX)                  // band currently above its normal?
    /** 1 while a band is above its normal, else 0 (for the meters). */
    val level = DoubleArray(MAX)
    /** Adapt: how long each dimension's average openness is measured over (same for every band). */
    var adapt = GADAPT

    private var lastRms = 0.0
    /** Raw loudness of the latest analysis window (for the meters). */
    val loudness get() = lastRms
    // Turned by the unfolding: each fold that opens gives the cube a push (turning the way its split
    // goes), friction slows it again. Closing doesn't push back, so turns add up rather than rock.
    private val PUSH_GAIN = if (reducedMotion) 0.6 else 2.0    // rad/s per full opening
    private val FRICTION = 1.2                                   // s for the spin to fade to ~37%
    private val OMEGA_MAX = if (reducedMotion) 0.6 else 2.0      // rad/s cap per axis
    val omega = DoubleArray(3)
    private val pushSign = DoubleArray(HB) { if ((it / 3) % 2 == 0) 1.0 else -1.0 }
    private var vol = 0.0
    private var volPeak = 0.02
    private var warmT = 99.0

    // View
    var ax = 0.35; var ay = 0.5; var roll = 0.0; var zoom = 1.0
    var fit = 0.0
    val theta = DoubleArray(3)
    var vYaw = 0.0; var vPitch = 0.0; var vFx = 0.0; var vFy = 0.0
    var fingers = 0

    // Projection output
    val axes = IntArray(DIM)
    var m = 3
    val q = FloatArray((1 shl DIM) * 3)
    private val rad = DoubleArray(1 shl DIM)
    private val radSorted = DoubleArray(1 shl DIM)

    private var bandsFor = -1
    private var bands = Array(HB) { DoubleArray(2) }

    private fun bandRanges(n: Int) {
        val w = DoubleArray(n) { 1 + LOW_WIDEN * (1 - it.toDouble() / max(1, n - 1)).pow(2) }
        val tot = w.sum()
        val l0 = ln(LO)
        val span = ln(HI) - l0
        var acc = 0.0
        for (a in 0 until n) {
            val lo = acc; val hi = acc + w[a] / tot; acc = hi
            bands[a][0] = exp(l0 + lo * span)
            bands[a][1] = exp(l0 + min(1.0, hi) * span)
        }
    }

    fun resetWarm() { warmT = 0.0; iE = 0.0; iHi = 0.0; iLo = 0.0; intensity = 0.0 }

    /** Share of the way to move toward a target this frame. A time of 0 means jump straight
     *  there (and avoids 0/0, which would leave a band stuck forever). */
    private fun ease(dt: Double, tau: Double) = if (tau > 1e-9) 1 - exp(-dt / tau) else 1.0

    /**
     * Listen: for each band, is it above its normal right now? If so its fold builds up — a step when
     * the hit starts, then slowly more while the sound holds — and it always sinks back over Linger.
     */
    fun readBands(dt: Double, live: Boolean, an: Analyser, end: Long) {
        if (!live) {
            for (a in 0 until MAX) { level[a] = 0.0; above[a] = false; built[a] *= exp(-dt / max(linger, 0.05)) }
            vol *= 0.9
            return
        }
        an.capture(end)
        val rms = an.rms().toDouble()
        lastRms = rms
        volPeak = max(max(rms, volPeak * 0.9985), 0.02)
        val vt = min(1.0, rms / volPeak)
        vol += (vt - vol) * (if (vt > vol) 0.45 else 0.15)
        an.computeBytes()
        warmT += dt
        // Fullness → Bloom: a smooth slide between Bloom (sparse) and Bloom high (fullest).
        iE += (rms - iE) * ease(dt, min(4.0, warmT))
        if (warmT <= dt * 1.5) { iHi = iE; iLo = iE }
        iHi = max(iE, iHi + (iE - iHi) * ease(dt, 60.0))
        iLo = min(iE, iLo + (iE - iLo) * ease(dt, 60.0))
        intensity = if (iHi - iLo > 1e-4) ((iE - iLo) / (iHi - iLo)).coerceIn(0.0, 1.0) else 0.0
        bloomNow = bloom + (bloomHi - bloom) * intensity
        val ny = an.sampleRate / 2.0
        val n = an.bytes.size
        if (bandsFor != an.sampleRate) { bandRanges(HB); bandsFor = an.sampleRate }
        val sink = exp(-dt / max(linger, 0.05))
        for (k in 0 until HB) {
            val i0 = floor(bands[k][0] / ny * n).toInt()
            val i1 = max(i0 + 1, ceil(bands[k][1] / ny * n).toInt())
            var s = 0.0
            var i = i0
            while (i < i1 && i < n) { s += an.bytes[i]; i++ }
            val v = s / ((i1 - i0) * 255.0)
            bSlow[k] += (v - bSlow[k]) * ease(dt, min(gSlow, warmT + dt)); if (!bSlow[k].isFinite()) bSlow[k] = v
            val audible = max(v, bSlow[k]) > FLOOR * 0.5
            val isAbove = audible && v > bSlow[k] * (1 + bThr[k])
            var b = built[k] * sink
            if (isAbove) b += if (!above[k]) stepSize else stepSize * HOLD_RATE * dt
            built[k] = if (b.isFinite()) min(1.0, b) else 0.0
            above[k] = isAbove
            level[k] = if (isAbove) 1.0 else 0.0
            // Self-adjust: steer this band's sensitivity so its fold's average openness matches Bloom.
            avgOpen[k] += (built[k] - avgOpen[k]) * ease(dt, adapt)
            bThr[k] = max(-0.5, min(0.6, bThr[k] + dt * ADAPT * (12 / max(adapt, CAP)) * (avgOpen[k] - bloomNow)))
        }
    }

    fun foldBy(dx: Double, dy: Double) {
        for (i in 0 until HB) manual[i] = max(-2.5, min(2.5, manual[i] + (if (i % 2 == 1) dx else dy) * 0.012 * (if (i % 3 == 0) -1 else 1)))
    }

    /** Everything after the analyser for one frame. */
    fun step(dt: Double, live: Boolean) {
        for (i in 0 until HB) {
            val tgt = built[i]
            if (openAmt[i] < 0.02 && tgt > openAmt[i] + 0.05) sgn[i] = -sgn[i] // a fold reopening from shut goes the other way
            val before = openAmt[i]
            openAmt[i] += (tgt - openAmt[i]) * ease(dt, SMOOTH); if (!openAmt[i].isFinite()) openAmt[i] = 0.0
            val opened = openAmt[i] - before
            if (opened > 0) omega[planeV[i]] += PUSH_GAIN * opened * pushSign[i]
            if (fingers == 0) manual[i] *= exp(-dt * 1.2)
        }
        // Friction, speed cap, then turn. With nothing playing, a slow drift.
        val fr = exp(-dt / FRICTION)
        for (a in 0 until 3) {
            omega[a] = (omega[a] * fr).coerceIn(-OMEGA_MAX, OMEGA_MAX)
            theta[a] += dt * omega[a] + (if (live) 0.0 else dt * 0.7 * PACE * SPIN_IDLE * BASE[a])
        }
        if (fingers == 0) {
            val k = 60 * dt
            ay += vYaw * k; ax += vPitch * k; foldBy(vFx * k, vFy * k)
            val d = exp(-dt * 2.2)
            vYaw *= d; vPitch *= d; vFx *= d; vFy *= d
        }
        hidden()
    }

    private val wk = DoubleArray(MAX)
    private val best = DoubleArray(MAX)
    private val plI = IntArray(HB); private val plJ = IntArray(HB)
    private val plC = DoubleArray(HB); private val plS = DoubleArray(HB)
    private val p = DoubleArray(MAX)

    /** Rotate each open fold and project the hidden axes away, building only the open corners. */
    private fun hidden() {
        wk.fill(0.0); best.fill(0.0)
        val angs = DoubleArray(HB)
        for (i in 0 until HB) {
            val h = planeH[i]
            val ang = openAmt[i] * depth * sgn[i] + manual[i]
            angs[i] = ang
            val w = min(1.0, abs(ang) / depth)
            if (w > wk[h]) wk[h] = w
            if (w >= best[h]) { best[h] = w; domBand[h] = i }
        }
        axes[0] = 0; axes[1] = 1; axes[2] = 2
        var mm = 3
        for (k in 3 until DIM) {
            if (wk[k] > 0.015) axes[mm++] = k else wk[k] = 0.0
            hOpen[k] = wk[k]
        }
        m = mm
        val n = 1 shl mm
        var np = 0
        for (i in 0 until HB) {
            if (abs(angs[i]) <= 1e-4) continue
            val h = planeH[i]
            var open = false
            for (b in 3 until mm) if (axes[b] == h) { open = true; break }
            if (!open) continue
            plI[np] = planeV[i]; plJ[np] = h; plC[np] = cos(angs[i]); plS[np] = sin(angs[i]); np++
        }
        val s0 = 1 / sqrt(DIM.toDouble())
        for (v in 0 until n) {
            p.fill(0.0)
            for (b in 0 until mm) p[axes[b]] = (if ((v shr b) and 1 == 1) 1.0 else -1.0) * s0
            for (r in 0 until np) {
                val i = plI[r]; val j = plJ[r]; val c = plC[r]; val s = plS[r]
                val a = p[i]; val bb = p[j]
                p[i] = a * c - bb * s; p[j] = a * s + bb * c
            }
            for (b in mm - 1 downTo 3) {
                val k = axes[b]
                val g = HD / (HD - p[k] * wk[k])
                for (i in 0 until k) p[i] *= g
            }
            q[v * 3] = p[0].toFloat(); q[v * 3 + 1] = p[1].toFloat(); q[v * 3 + 2] = p[2].toFloat()
            rad[v] = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
        }
        System.arraycopy(rad, 0, radSorted, 0, n)
        java.util.Arrays.sort(radSorted, 0, n)
        val q94 = radSorted[floor(n * 0.94).toInt().coerceIn(0, n - 1)]
        val rest = sqrt(3.0 / DIM) * 1.3
        val target = max(rest, q94)
        fit = if (fit != 0.0) fit + (target - fit) * (if (target > fit) 0.03 else 0.01) else rest
    }

    fun resetView() { ax = 0.35; ay = 0.5; roll = 0.0; zoom = 1.0; vYaw = 0.0; vPitch = 0.0; vFx = 0.0; vFy = 0.0 }

    /** Combined roll · yaw · pitch rotation, column-major for OpenGL. */
    fun rotation(out: FloatArray) {
        val yaw = ay + theta[0]; val pitch = ax + theta[1] * 0.5; val rl = roll + theta[2] * 0.6
        val cr = cos(rl); val sr = sin(rl); val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val rr = arrayOf(doubleArrayOf(cr, -sr, 0.0), doubleArrayOf(sr, cr, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        val ry = arrayOf(doubleArrayOf(cy, 0.0, -sy), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(sy, 0.0, cy))
        val rp = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cp, -sp), doubleArrayOf(0.0, sp, cp))
        fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(3) { i -> DoubleArray(3) { j -> a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j] } }
        val mtx = mul(rp, mul(ry, rr))
        for (c in 0 until 3) for (r in 0 until 3) out[c * 3 + r] = mtx[r][c].toFloat()
    }
}
