package com.sven.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getKwsModelConfig
import com.k2fsa.sherpa.onnx.getKeywordsFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Local keyword spotter. Microphone is active only after the user explicitly starts it
 * from the visible app and stops through the persistent notification/control panel.
 * The initial bundled English KWS model's supported phrase is "LIGHT UP".
 */
class WakeWordService : Service() {
    companion object {
        const val ACTION_START = "com.sven.assistant.WAKE_START"
        const val ACTION_STOP = "com.sven.assistant.WAKE_STOP"
        const val ACTION_WAKE = "com.sven.assistant.WAKE_DETECTED"
        private const val CHANNEL = "7sven_wake"
        private const val NOTIFICATION_ID = 7308
        private const val TAG = "7SVEN-Wake"
        private const val RATE = 16000
    }

    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (running.compareAndSet(false, true)) {
            worker = thread(name = "7SVEN-keyword-spotter") { listenLoop() }
        }
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "7SVEN Wake Word", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows when local wake-word detection is active"
            })
        }
    }

    private fun buildNotification(): Notification {
        val stop = Intent(this, WakeWordService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(this, 2, stop,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPi = PendingIntent.getActivity(this, 3, open,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("7SVEN wake detection active")
            .setContentText("Listening locally for “Light Up” · Tap Stop to disable")
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "Stop listening", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun listenLoop() {
        var spotter: KeywordSpotter? = null
        var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
        try {
            val config = KeywordSpotterConfig(
                featConfig = getFeatureConfig(sampleRate = RATE, featureDim = 80),
                modelConfig = getKwsModelConfig(type = 1) ?: error("English KWS model configuration unavailable"),
                keywordsFile = getKeywordsFile(type = 1),
                numTrailingBlanks = 2
            )
            spotter = KeywordSpotter(assetManager = assets, config = config)
            stream = spotter.createStream()
            val minBytes = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            check(minBytes > 0) { "AudioRecord buffer unavailable: $minBytes" }
            val audio = AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBytes * 2, RATE / 2)
            )
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            recorder = audio
            audio.startRecording()
            val pcm = ShortArray(maxOf(1024, minBytes / 2))
            while (running.get()) {
                val count = audio.read(pcm, 0, pcm.size)
                if (count <= 0) continue
                val samples = FloatArray(count) { pcm[it] / 32768.0f }
                stream.acceptWaveform(samples, sampleRate = RATE)
                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                    val keyword = spotter.getResult(stream).keyword
                    if (keyword.isNotBlank()) {
                        spotter.reset(stream)
                        if (keyword.contains("LIGHT_UP", ignoreCase = true) ||
                            keyword.contains("LIGHT UP", ignoreCase = true)) {
                            Log.i(TAG, "Wake phrase detected")
                            launchVoiceScreen()
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Wake-word engine stopped: ${t.message}", t)
            stopSelf()
        } finally {
            running.set(false)
            try { stream?.release() } catch (_: Throwable) {}
            try { spotter?.release() } catch (_: Throwable) {}
            try { recorder?.stop() } catch (_: Throwable) {}
            try { recorder?.release() } catch (_: Throwable) {}
            recorder = null
        }
    }

    override fun onDestroy() {
        running.set(false)
        try { recorder?.stop() } catch (_: Throwable) {}
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
        stopForeground(true)
        super.onDestroy()
    }

    /**
     * Android 10+ blocks background activity launches. With "Display over other apps" granted the
     * launch is allowed; otherwise a full-screen / heads-up notification takes the person to the
     * voice screen with one tap.
     */
    private fun launchVoiceScreen() {
        val wake = Intent(this, MainActivity::class.java)
            .setAction(ACTION_WAKE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val canLaunchDirectly = Build.VERSION.SDK_INT < 29 ||
            (Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this))
        if (canLaunchDirectly) {
            try {
                startActivity(wake)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Direct launch failed, using notification: ${e.message}")
            }
        }
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel("7sven_wake_alert", "7SVEN voice prompt", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val pending = PendingIntent.getActivity(
                this, 7309, wake, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "7sven_wake_alert")
            else @Suppress("DEPRECATION") Notification.Builder(this)
            val note = builder
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("7SVEN heard you")
                .setContentText("Tap to speak your command")
                .setContentIntent(pending)
                .setFullScreenIntent(pending, true)
                .setAutoCancel(true)
                .build()
            manager.notify(7310, note)
        } catch (e: Exception) {
            Log.w(TAG, "Wake notification failed: ${e.message}")
        }
    }
}
