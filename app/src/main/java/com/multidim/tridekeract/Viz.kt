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
 * Everything the Tridekeract does each frame, ported line for line from the web page:
 * 13 frequency bands each open one fold of the 13-cube; the whole-mix swell drives the
 * two-finger swipe; the visible cube tumbles with the volume; hidden axes are projected away.
 * All public state is guarded by `synchronized(this)` — touch input comes from the UI thread.
 */
class Viz(private val reducedMotion: Boolean) {
    companion object {
        const val MAX = 16
        const val HB = 13
        const val DIM = 13
        const val OPEN_MAX = 1.2
        const val FLOOR = 0.035
        const val HD = 5.0
        const val PACE = 0.5
        const val LO = 40.0
        const val HI = 14000.0
        const val LOW_WIDEN = 0.45
        const val DUTY = 0.14
        const val ADAPT = 0.6
        const val CAP = 0.027
        // Whole-mix swell → the music's two-finger swipe
        const val RISE_NOW = 0.3
        const val RISE_NORMAL = 8.0
        const val SWELL_FULL = 1.5
        const val SWELL_ADAPT = 0.2
        const val SWELL_RELAX = 240.0
        const val PUSH_START = 0.5
        const val PUSH_GAIN = 5.0
        val TWDEF = doubleArrayOf(0.21, 0.29, 0.479, 0.95, 1.56, 2.52, 4.0, 7.269, 17.928, 60.0, 60.0, 60.0, 60.0)
        val BASE = doubleArrayOf(0.8, 0.5, 0.35)
        const val GFAST = 0.29
        const val GCLOSE = 0.15
    }

    private val SPIN_MAX = if (reducedMotion) 1.5 else 9.0
    private val SPIN_IDLE = if (reducedMotion) 0.3 else 0.6
    private val WHIP_GAIN = if (reducedMotion) 0.0 else 26.0
    private val WHIP_CAP = if (reducedMotion) 0.0 else 34.0
    private val WHIP_DECAY = 2.4
    private val WHIP_COOLDOWN = 2.5

    val planeV = IntArray(HB) { it % 3 }
    val planeH = IntArray(HB) { 3 + (it % 10) }

    // Timings: the two the Feel panel changes, plus the baked ones.
    var gFast = GFAST
    var gClose = GCLOSE
    private val gSlow = 3.4
    private val gPeak = 0.36
    private val gOpen = 0.13

    val sgn = DoubleArray(MAX) { 1.0 }
    val hOpen = DoubleArray(MAX)
    val domBand = IntArray(MAX)
    val openAmt = DoubleArray(MAX)
    val manual = DoubleArray(MAX)
    private val bFast = DoubleArray(MAX)
    private val bSlow = DoubleArray(MAX)
    private val bPeak = DoubleArray(MAX) { 0.02 }
    private val bThr = DoubleArray(MAX) { 0.15 }
    private val bDuty = DoubleArray(MAX) { 0.08 }
    val level = DoubleArray(MAX)
    private val TW = DoubleArray(MAX) { if (it < TWDEF.size) TWDEF[it] else 12.0 }

    private var lastRms = 0.0
    private var fastE = 0.0
    private var slowE = 0.0
    private var whip = 0.0
    private var whipCool = 0.0
    private var vol = 0.0
    private var volPeak = 0.02
    private var warmT = 99.0
    private var sNow = 0.0
    private var sNorm = 0.0
    private var sFull = SWELL_FULL

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

    fun resetWarm() { warmT = 0.0; sFull = SWELL_FULL }

    /** Per-band chain (Now / Normal / change / peak memory / self-adjust), as on the page. */
    fun readBands(dt: Double, live: Boolean, an: Analyser, end: Long) {
        if (!live) {
            for (a in 0 until MAX) level[a] *= 0.9
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
        val ny = an.sampleRate / 2.0
        val n = an.bytes.size
        if (bandsFor != an.sampleRate) { bandRanges(HB); bandsFor = an.sampleRate }
        for (k in 0 until HB) {
            val i0 = floor(bands[k][0] / ny * n).toInt()
            val i1 = max(i0 + 1, ceil(bands[k][1] / ny * n).toInt())
            var s = 0.0
            var i = i0
            while (i < i1 && i < n) { s += an.bytes[i]; i++ }
            val v = s / ((i1 - i0) * 255.0)
            bFast[k] += (v - bFast[k]) * (1 - exp(-dt / min(gFast, warmT + dt)))
            bSlow[k] += (v - bSlow[k]) * (1 - exp(-dt / min(gSlow, warmT + dt)))
            var change = max(0.0, bFast[k] - bSlow[k] * (1 + bThr[k]))
            val gate = max(0.0, min(1.0, (max(v, bSlow[k]) - FLOOR * 0.5) / (FLOOR * 0.5)))
            change *= gate
            bPeak[k] = max(max(change, bPeak[k] * exp(-dt / gPeak)), 0.012)
            level[k] = min(1.0, change / bPeak[k])
            bDuty[k] += ((if (level[k] > 0.3) 1.0 else 0.0) - bDuty[k]) * (1 - exp(-dt / TW[k]))
            bThr[k] = max(0.0, min(0.6, bThr[k] + dt * ADAPT * (12 / max(TW[k], CAP)) * (bDuty[k] - DUTY)))
        }
    }

    /** The music does the two-finger swipe, driven by the whole mix's swell. */
    private fun listenWhole(dt: Double, live: Boolean) {
        if (!live) return
        sNow += (lastRms - sNow) * (1 - exp(-dt / min(RISE_NOW, warmT + dt)))
        sNorm += (lastRms - sNorm) * (1 - exp(-dt / min(RISE_NORMAL, warmT + dt)))
        val sw = if (warmT > RISE_NORMAL) (sNow / max(sNorm, 1e-4) - 1) / (sFull - 1) else 0.0
        val push = max(0.0, (sw - PUSH_START) / (1 - PUSH_START)) * PUSH_GAIN
        var lo = 0.0; var hi = 0.0
        for (i in 0 until HB) if (i < 6) lo += level[i] / 6 else hi += level[i] / (HB - 6)
        val bal = (lo - hi) / (lo + hi + 1e-6)
        val dx = push * (0.6 + 0.4 * bal)
        val dy = push * (0.6 - 0.4 * bal)
        if (push > 0) for (i in 0 until HB) {
            manual[i] = max(-2.5, min(2.5, manual[i] + (if (i % 2 == 1) dx else dy) * (if (i % 3 == 0) -1 else 1) * dt))
        }
        var all = 1.0
        for (k in 3 until DIM) if (hOpen[k] < 0.5) { all = 0.0; break }
        sFull += dt * (SWELL_ADAPT * all - (sFull - SWELL_FULL) / SWELL_RELAX)
    }

    fun foldBy(dx: Double, dy: Double) {
        for (i in 0 until HB) manual[i] = max(-2.5, min(2.5, manual[i] + (if (i % 2 == 1) dx else dy) * 0.012 * (if (i % 3 == 0) -1 else 1)))
    }

    /** Everything after the analyser for one frame. */
    fun step(dt: Double, live: Boolean) {
        listenWhole(dt, live)
        if (live) {
            fastE += (lastRms - fastE) * (1 - exp(-dt / 0.12))
            slowE += (lastRms - slowE) * (1 - exp(-dt / 4))
            whipCool -= dt
            val surge = fastE / (slowE + 0.004)
            if (surge > 1.5 && whipCool <= 0 && fastE > 0.04) {
                whip = min(WHIP_CAP, whip + WHIP_GAIN * min(1.0, (surge - 1.5) + 0.4))
                whipCool = WHIP_COOLDOWN
            }
        } else { fastE *= 0.9; slowE *= 0.98 }
        whip *= exp(-dt * WHIP_DECAY)
        val spin = if (live) SPIN_MAX * vol.pow(1.6) + whip else SPIN_IDLE
        for (a in 0 until 3) theta[a] += dt * 0.7 * PACE * spin * BASE[a]
        for (i in 0 until HB) {
            val tgt = if (live) level[i] else 0.0
            if (openAmt[i] < 0.02 && tgt > 0.1) sgn[i] = -sgn[i]
            openAmt[i] += (tgt - openAmt[i]) * (1 - exp(-dt / (if (tgt > openAmt[i]) gOpen else gClose)))
            if (fingers == 0) manual[i] *= exp(-dt * 1.2)
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
            val ang = openAmt[i] * OPEN_MAX * sgn[i] + manual[i]
            angs[i] = ang
            val w = min(1.0, abs(ang) / OPEN_MAX)
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
