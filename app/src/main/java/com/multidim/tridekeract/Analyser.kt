package com.multidim.tridekeract

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A copy of the browser's AnalyserNode, so the tuning from the web version carries over:
 * 4096-point FFT, Blackman window, magnitude smoothing 0.55 per read, decibels -100..-30
 * mapped to 0..255. Audio threads write mono samples into a ring buffer, indexed by the
 * absolute frame number; the drawing thread reads the window ending at the frame that is
 * actually coming out of the speaker (or the newest frame, for live input).
 */
class Analyser(val fftSize: Int = 4096) {
    @Volatile var sampleRate = 44100
        private set

    private val ring = FloatArray(1 shl 18)
    private val mask = (ring.size - 1).toLong()
    private var written = 0L
    private val lock = Any()

    val time = FloatArray(fftSize)
    val bytes = IntArray(fftSize / 2)
    private val smooth = FloatArray(fftSize / 2)
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val win = FloatArray(fftSize) { i ->
        val a = 0.16
        val x = 2.0 * PI * i / fftSize
        ((1 - a) / 2 - 0.5 * cos(x) + a / 2 * cos(2 * x)).toFloat()
    }
    private val bitrev = IntArray(fftSize)
    private val cosT = FloatArray(fftSize / 2)
    private val sinT = FloatArray(fftSize / 2)

    init {
        var bits = 0
        while ((1 shl bits) < fftSize) bits++
        for (i in 0 until fftSize) {
            var r = 0
            var x = i
            for (b in 0 until bits) { r = (r shl 1) or (x and 1); x = x shr 1 }
            bitrev[i] = r
        }
        for (i in 0 until fftSize / 2) {
            cosT[i] = cos(-2.0 * PI * i / fftSize).toFloat()
            sinT[i] = sin(-2.0 * PI * i / fftSize).toFloat()
        }
    }

    /** Start a fresh stream at the given sample rate. */
    fun reset(rate: Int) {
        synchronized(lock) {
            sampleRate = rate
            written = 0
            ring.fill(0f)
        }
        smooth.fill(0f)
    }

    fun write(mono: FloatArray, n: Int) {
        synchronized(lock) {
            for (i in 0 until n) ring[((written + i) and mask).toInt()] = mono[i]
            written += n
        }
    }

    fun written(): Long = synchronized(lock) { written }

    /** Copy the fftSize samples ending at absolute frame [end] (newest if end < 0) into [time]. */
    fun capture(end: Long) {
        synchronized(lock) {
            val e = if (end < 0 || end > written) written else end
            for (i in 0 until fftSize) {
                val idx = e - fftSize + i
                time[i] = if (idx < 0 || idx < written - ring.size) 0f else ring[(idx and mask).toInt()]
            }
        }
    }

    fun rms(): Float {
        var s = 0.0
        for (v in time) s += (v * v).toDouble()
        return sqrt(s / fftSize).toFloat()
    }

    /** Fill [bytes] from [time], the way getByteFrequencyData does. */
    fun computeBytes(smoothing: Float = 0.55f, minDb: Float = -100f, maxDb: Float = -30f) {
        for (i in 0 until fftSize) { re[bitrev[i]] = time[i] * win[i]; im[bitrev[i]] = 0f }
        var size = 2
        while (size <= fftSize) {
            val half = size / 2
            val step = fftSize / size
            var start = 0
            while (start < fftSize) {
                var k = 0
                for (j in start until start + half) {
                    val c = cosT[k]; val s = sinT[k]
                    val l = j + half
                    val tr = re[l] * c - im[l] * s
                    val ti = re[l] * s + im[l] * c
                    re[l] = re[j] - tr; im[l] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
        val scale = 1f / fftSize
        val range = maxDb - minDb
        for (k in 0 until fftSize / 2) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k]) * scale
            smooth[k] = smoothing * smooth[k] + (1 - smoothing) * mag
            val db = if (smooth[k] > 0f) 20f * log10(smooth[k]) else -1000f
            val b = (255f * (db - minDb) / range).toInt()
            bytes[k] = if (b < 0) 0 else if (b > 255) 255 else b
        }
    }
}
