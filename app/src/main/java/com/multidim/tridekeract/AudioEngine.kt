package com.multidim.tridekeract

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import java.nio.ByteOrder

/** Shared between the activity, the drawing thread and the capture service. */
object Shared {
    val analyser = Analyser()
    /** Which sound the cube is listening to. */
    @Volatile var source = Source.NONE
    /** For the built-in track: the analyser frame now coming out of the speaker. */
    @Volatile var trackEnd: () -> Long = { -1L }
    /** Set when a new stream starts from its beginning, so the trackers warm up again. */
    @Volatile var restart = false
}

enum class Source { NONE, TRACK, APPS, MIC }

/**
 * Plays a track the person picks from their phone and listens to the
 * microphone. Decoded samples go to the speaker and, in step, to the analyser.
 */
class AudioEngine(private val ctx: Context) {
    private val an = Shared.analyser

    var onFinished: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    // ---------- track ----------
    private var thread: Thread? = null
    @Volatile private var stopReq = false
    @Volatile var paused = false
        private set
    @Volatile private var track: AudioTrack? = null
    @Volatile private var base = 0L
    var trackName = ""
        private set
    val hasTrack get() = thread != null

    fun playTrack(uri: Uri, name: String) {
        stopTrack()
        stopMic()
        trackName = name
        paused = false
        stopReq = false
        Shared.source = Source.TRACK
        Shared.restart = true
        val t = Thread({ decodeLoop(uri) }, "decode")
        thread = t
        t.start()
    }

    fun pause() {
        paused = true
        try { track?.pause() } catch (_: Exception) {}
    }

    fun resume() {
        paused = false
        try { track?.play() } catch (_: Exception) {}
        Shared.source = Source.TRACK
    }

    fun stopTrack() {
        stopReq = true
        paused = false
        try { track?.pause(); track?.flush() } catch (_: Exception) {}
        thread?.let { try { it.join(1500) } catch (_: InterruptedException) {} }
        thread = null
        if (Shared.source == Source.TRACK) Shared.source = Source.NONE
        Shared.trackEnd = { -1L }
    }

    private fun decodeLoop(uri: Uri) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var at: AudioTrack? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var ti = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { ti = i; break }
            }
            if (ti < 0) throw IllegalStateException("no audio")
            val fmt = ex.getTrackFormat(ti)
            ex.selectTrack(ti)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var shorts = ShortArray(0)
            var mono = FloatArray(0)
            while (!stopReq) {
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 5000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (oi >= 0) {
                    if (at == null) {
                        an.reset(rate)
                        val made = makeTrack(rate, ch)
                        at = made
                        track = made
                        base = an.written()
                        Shared.trackEnd = { base + (made.playbackHeadPosition.toLong() and 0xffffffffL) }
                        if (!paused) made.play()
                    }
                    val out: AudioTrack = at!!
                    val ob = c.getOutputBuffer(oi)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val n = sb.remaining()
                    if (shorts.size < n) shorts = ShortArray(n)
                    sb.get(shorts, 0, n)
                    c.releaseOutputBuffer(oi, false)
                    val frames = n / ch
                    if (mono.size < frames) mono = FloatArray(frames)
                    for (f in 0 until frames) {
                        var s = 0
                        for (k in 0 until ch) s += shorts[f * ch + k]
                        mono[f] = s / (32768f * ch)
                    }
                    // Write in small pieces so pause and stop answer quickly.
                    var off = 0
                    var fOff = 0
                    while (off < n && !stopReq) {
                        while (paused && !stopReq) Thread.sleep(20)
                        if (stopReq) break
                        val chunk = minOf(n - off, 2048 * ch)
                        val chunkFrames = chunk / ch
                        an.write(mono.copyOfRange(fOff, fOff + chunkFrames), chunkFrames)
                        var w = 0
                        while (w < chunk && !stopReq) {
                            val r = out.write(shorts, off + w, chunk - w)
                            if (r <= 0) { if (paused) Thread.sleep(20) else break } else w += r
                        }
                        off += chunk; fOff += chunkFrames
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
            if (!stopReq && at != null) {
                // Let the last of the sound play out, then report the end.
                val total = an.written() - base
                val tEnd = System.currentTimeMillis() + 3000
                while (!stopReq && (at!!.playbackHeadPosition.toLong() and 0xffffffffL) < total - 64 && System.currentTimeMillis() < tEnd) Thread.sleep(30)
                if (!stopReq) onFinished?.invoke()
            }
        } catch (e: Exception) {
            if (!stopReq) onError?.invoke("Couldn’t play “$trackName”. Use a file you own, such as an MP3, M4A or WAV.")
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
            try { at?.stop() } catch (_: Exception) {}
            try { at?.release() } catch (_: Exception) {}
            if (track === at) track = null
        }
    }

    private fun makeTrack(rate: Int, ch: Int): AudioTrack {
        val mask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(mask).build())
            .setBufferSizeInBytes(maxOf(min * 2, 16384))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    // ---------- microphone ----------
    private var micThread: Thread? = null
    @Volatile private var micStop = false
    val micOn get() = micThread != null

    @SuppressLint("MissingPermission")
    fun startMic(): Boolean {
        stopMic()
        if (hasTrack) { stopTrack() }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Unprocessed: no call-style echo cancelling, noise reduction or auto volume.
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val srcType = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val rate = 44100
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(srcType, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 8192))
        } catch (e: Exception) { return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        an.reset(rate)
        micStop = false
        Shared.source = Source.MIC
        Shared.restart = true
        val t = Thread({ readLoop(rec) { micStop } }, "mic")
        micThread = t
        t.start()
        return true
    }

    fun stopMic() {
        micStop = true
        micThread?.let { try { it.join(800) } catch (_: InterruptedException) {} }
        micThread = null
        if (Shared.source == Source.MIC) Shared.source = Source.NONE
    }

    fun release() { stopTrack(); stopMic() }

    companion object {
        /** Read 16-bit mono from a recorder into the analyser until told to stop. */
        fun readLoop(rec: AudioRecord, stop: () -> Boolean) {
            val buf = ShortArray(1024)
            val f = FloatArray(1024)
            try {
                rec.startRecording()
                while (!stop()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        for (i in 0 until n) f[i] = buf[i] / 32768f
                        Shared.analyser.write(f, n)
                    } else if (n < 0) break
                }
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
        }
    }
}
