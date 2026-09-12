package com.mixtervee.chattymicttest

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

class BackgroundMicService : Service() {
    companion object {
        const val ACTION_START = "com.mixtervee.chattymicttest.START_BACKGROUND_MIC"
        const val ACTION_STOP = "com.mixtervee.chattymicttest.STOP_BACKGROUND_MIC"
        const val EXTRA_DEVICE_ID = "device_id"
        const val PREFS = "background_mic_test"
        const val CHANNEL_ID = "chatty_background_mic"
        const val NOTIFICATION_ID = 1001
    }

    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val requestedId = intent?.getIntExtra(EXTRA_DEVICE_ID, -1) ?: -1
        startForeground(NOTIFICATION_ID, buildNotification("Starting background microphone test…"))
        startCapture(requestedId)
        return START_NOT_STICKY
    }

    private fun startCapture(requestedId: Int) {
        stopCapture()

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("running", true)
            .putInt("requested_id", requestedId)
            .putInt("last_peak", 0)
            .putString("error", "")
            .apply()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("running", false).putString("error", "RECORD_AUDIO permission missing").apply()
            stopSelf()
            return
        }

        val sampleRate = 16000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            prefs.edit().putBoolean("running", false).putString("error", "Unable to initialize microphone buffer").apply()
            stopSelf()
            return
        }

        val bufferSize = minBuffer * 2
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            prefs.edit().putBoolean("running", false).putString("error", "AudioRecord failed: ${e.message}").apply()
            stopSelf()
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            prefs.edit().putBoolean("running", false).putString("error", "Microphone did not initialize").apply()
            stopSelf()
            return
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val requestedDevice = if (requestedId >= 0) {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == requestedId }
        } else null
        val preferredAccepted = requestedDevice?.let { audioRecord.setPreferredDevice(it) } ?: true

        prefs.edit().putBoolean("preferred_accepted", preferredAccepted).apply()
        recorder = audioRecord
        running = true

        worker = Thread {
            val buffer = ShortArray(bufferSize / 2)
            var maxPeak = prefs.getInt("max_peak", 0)
            var lastNotificationAt = 0L
            try {
                audioRecord.startRecording()
                while (running) {
                    val count = audioRecord.read(buffer, 0, buffer.size)
                    if (count > 0) {
                        var peak = 0
                        for (i in 0 until count) {
                            val value = abs(buffer[i].toInt())
                            if (value > peak) peak = value
                        }
                        if (peak > maxPeak) maxPeak = peak
                        val routed = audioRecord.routedDevice
                        prefs.edit()
                            .putBoolean("running", true)
                            .putInt("last_peak", peak)
                            .putInt("max_peak", maxPeak)
                            .putInt("routed_id", routed?.id ?: -1)
                            .putString("routed_name", routed?.productName?.toString() ?: "Unknown")
                            .putInt("routed_type", routed?.type ?: -1)
                            .putLong("last_sample_time", System.currentTimeMillis())
                            .apply()

                        val now = System.currentTimeMillis()
                        if (now - lastNotificationAt >= 1000) {
                            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            manager.notify(NOTIFICATION_ID, buildNotification("Listening in background • Max peak: $maxPeak"))
                            lastNotificationAt = now
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    prefs.edit().putString("error", "Recording error: ${e.message}").apply()
                }
            } finally {
                prefs.edit().putBoolean("running", false).apply()
            }
        }.apply {
            name = "ChattyBackgroundMic"
            start()
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Chatty Mic Test")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Chatty background microphone test",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun stopCapture() {
        running = false
        val oldRecorder = recorder
        val oldWorker = worker
        recorder = null
        worker = null
        try { oldRecorder?.stop() } catch (_: Exception) {}
        try { oldWorker?.join(400) } catch (_: InterruptedException) {}
        try { oldRecorder?.release() } catch (_: Exception) {}
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("running", false).apply()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
