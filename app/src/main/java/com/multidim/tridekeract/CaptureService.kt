package com.multidim.tridekeract

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Listens to the sound other apps are playing (YouTube, Spotify, …) through Android's
 * playback-capture feature. Android requires this to run as a foreground service with a
 * visible notification while it listens. Apps can opt out of being heard; those stay silent.
 */
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var thread: Thread? = null
    @Volatile private var stop = false

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startAsForeground()
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try { mpm.getMediaProjection(code, data) } catch (e: Exception) { null }
        if (mp == null) { stopSelf(); return START_NOT_STICKY }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))

        val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val rate = 44100
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(min * 2, 8192))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
        } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { rec?.release(); stopSelf(); return START_NOT_STICKY }

        Shared.analyser.reset(rate)
        Shared.source = Source.APPS
        Shared.restart = true
        running = true
        stop = false
        val t = Thread({ AudioEngine.readLoop(rec) { stop } }, "capture")
        thread = t
        t.start()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening to other apps", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Pentadekeract is listening")
            .setContentText("The cube is moving to the sound from your other apps.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stopPi).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    override fun onDestroy() {
        stop = true
        thread?.let { try { it.join(800) } catch (_: InterruptedException) {} }
        thread = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        running = false
        if (Shared.source == Source.APPS) Shared.source = Source.NONE
        onStopped?.invoke()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "capture"
        @Volatile var running = false
        @Volatile var onStopped: (() -> Unit)? = null
    }
}
