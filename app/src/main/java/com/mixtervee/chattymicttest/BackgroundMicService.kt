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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File
import java.util.Locale
import kotlin.math.abs

class BackgroundMicService : Service() {
    companion object {
        const val ACTION_START = "com.mixtervee.chattymicttest.START_BACKGROUND_MIC"
        const val ACTION_STOP = "com.mixtervee.chattymicttest.STOP_BACKGROUND_MIC"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_OUTPUT_DEVICE_ID = "output_device_id"
        const val PREFS = "background_mic_test"
        const val CHANNEL_ID = "chatty_background_mic"
        const val NOTIFICATION_ID = 1001
        private const val SAMPLE_RATE = 16000
        private const val WAKE_PHRASE = "hey chatty"
        private const val REPLY_TEXT = "Hi Mike, I'm listening."
        private const val REPLY_UTTERANCE_ID = "chatty_reply_file"
    }

    @Volatile private var running = false
    @Volatile private var wanted = false
    @Volatile private var replyReady = false
    private var recorder: AudioRecord? = null
    private var recognizer: Recognizer? = null
    private var model: Model? = null
    private var worker: Thread? = null
    private var generation = 0
    private var textToSpeech: TextToSpeech? = null
    private var replyPlayer: MediaPlayer? = null
    private lateinit var replyFile: File

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        replyFile = File(cacheDir, "chatty_reply.wav")
        initTextToSpeech()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            wanted = false
            generation++
            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val requestedId = intent?.getIntExtra(EXTRA_DEVICE_ID, -1) ?: -1
        val requestedOutputId = intent?.getIntExtra(EXTRA_OUTPUT_DEVICE_ID, -1) ?: -1
        startForeground(NOTIFICATION_ID, buildNotification("Loading offline wake-word model…"))
        startWakeWordTest(requestedId, requestedOutputId)
        return START_NOT_STICKY
    }

    private fun initTextToSpeech() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString("reply_status", "Initializing Android speech engine…").apply()

        textToSpeech = TextToSpeech(this) { status ->
            val tts = textToSpeech
            if (status != TextToSpeech.SUCCESS || tts == null) {
                prefs.edit().putString("reply_status", "Android speech engine failed to initialize").apply()
                return@TextToSpeech
            }

            var languageResult = tts.setLanguage(Locale.CANADA)
            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                languageResult = tts.setLanguage(Locale.US)
            }
            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                prefs.edit().putString("reply_status", "English speech voice is unavailable").apply()
                return@TextToSpeech
            }

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == REPLY_UTTERANCE_ID) {
                        replyReady = replyFile.exists() && replyFile.length() > 0
                        prefs.edit().putString(
                            "reply_status",
                            if (replyReady) "Spoken reply ready" else "Spoken reply file was not created"
                        ).apply()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == REPLY_UTTERANCE_ID) {
                        prefs.edit().putString("reply_status", "Could not create spoken reply").apply()
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == REPLY_UTTERANCE_ID) {
                        prefs.edit().putString("reply_status", "Could not create spoken reply (error $errorCode)").apply()
                    }
                }
            })

            try { replyFile.delete() } catch (_: Exception) {}
            val result = tts.synthesizeToFile(REPLY_TEXT, Bundle(), replyFile, REPLY_UTTERANCE_ID)
            prefs.edit().putString(
                "reply_status",
                if (result == TextToSpeech.SUCCESS) "Preparing spoken reply…" else "Speech synthesis request failed"
            ).apply()
        }
    }

    private fun startWakeWordTest(requestedId: Int, requestedOutputId: Int) {
        wanted = true
        generation++
        val myGeneration = generation
        stopCapture(closeModel = true)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("running", true)
            .putInt("requested_id", requestedId)
            .putInt("requested_output_id", requestedOutputId)
            .putInt("last_peak", 0)
            .putInt("max_peak", 0)
            .putInt("wake_count", 0)
            .putInt("reply_count", 0)
            .putString("last_partial", "")
            .putString("last_result", "")
            .putString("last_wake_text", "")
            .putLong("last_wake_time", 0L)
            .putString("reply_route", "Not used yet")
            .putBoolean("reply_route_accepted", true)
            .putString("reply_routed_name", "Unknown")
            .putInt("reply_routed_id", -1)
            .putString("recognizer_status", "Loading offline model…")
            .putString("error", "")
            .apply()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("running", false).putString("error", "RECORD_AUDIO permission missing").apply()
            stopSelf()
            return
        }

        StorageService.unpack(
            this,
            "model-en-us",
            "chatty-vosk-model",
            { loadedModel ->
                if (!wanted || myGeneration != generation) {
                    try { loadedModel.close() } catch (_: Exception) {}
                } else {
                    model = loadedModel
                    prefs.edit().putString("recognizer_status", "Offline model ready; opening microphone…").apply()
                    beginCapture(requestedId, requestedOutputId, loadedModel, myGeneration)
                }
            },
            { exception ->
                if (myGeneration == generation) {
                    prefs.edit()
                        .putBoolean("running", false)
                        .putString("recognizer_status", "Model load failed")
                        .putString("error", "Vosk model load failed: ${exception.message}")
                        .apply()
                    updateNotification("Offline model failed to load")
                    stopSelf()
                }
            }
        )
    }

    private fun beginCapture(
        requestedId: Int,
        requestedOutputId: Int,
        loadedModel: Model,
        myGeneration: Int
    ) {
        if (!wanted || myGeneration != generation) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            fail("Unable to initialize microphone buffer")
            return
        }

        val bufferSize = minBuffer * 2
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            fail("AudioRecord failed: ${e.message}")
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            fail("Microphone did not initialize")
            return
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val requestedDevice = if (requestedId >= 0) {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == requestedId }
        } else null
        val preferredAccepted = requestedDevice?.let { audioRecord.setPreferredDevice(it) } ?: true

        val grammar = "[\"hey chatty\", \"chatty\", \"hey\", \"[unk]\"]"
        val voskRecognizer = try {
            Recognizer(loadedModel, SAMPLE_RATE.toFloat(), grammar)
        } catch (e: Exception) {
            audioRecord.release()
            fail("Vosk recognizer failed: ${e.message}")
            return
        }

        prefs.edit()
            .putBoolean("preferred_accepted", preferredAccepted)
            .putString("recognizer_status", "LISTENING locally for ‘Hey Chatty’")
            .apply()

        recorder = audioRecord
        recognizer = voskRecognizer
        running = true
        updateNotification("Listening locally for “Hey Chatty”")

        worker = Thread {
            val buffer = ShortArray(bufferSize / 2)
            var maxPeak = 0
            var wakeCount = 0
            var lastWakeAt = 0L
            var lastNotificationAt = 0L

            try {
                audioRecord.startRecording()
                while (running && wanted && myGeneration == generation) {
                    val count = audioRecord.read(buffer, 0, buffer.size)
                    if (count <= 0) continue

                    var peak = 0
                    for (i in 0 until count) {
                        val value = abs(buffer[i].toInt())
                        if (value > peak) peak = value
                    }
                    if (peak > maxPeak) maxPeak = peak

                    val complete = voskRecognizer.acceptWaveForm(buffer, count)
                    val json = if (complete) voskRecognizer.result else voskRecognizer.partialResult
                    val key = if (complete) "text" else "partial"
                    val heard = extractText(json, key)

                    if (heard.isNotBlank()) {
                        prefs.edit()
                            .putString(if (complete) "last_result" else "last_partial", heard)
                            .apply()
                    }

                    val now = System.currentTimeMillis()
                    if (isWakePhrase(heard) && now - lastWakeAt > 2200) {
                        wakeCount++
                        lastWakeAt = now
                        prefs.edit()
                            .putInt("wake_count", wakeCount)
                            .putString("last_wake_text", heard)
                            .putLong("last_wake_time", now)
                            .apply()
                        updateNotification("HEY CHATTY detected! • Count: $wakeCount")
                        playReply(requestedOutputId)
                        try { voskRecognizer.reset() } catch (_: Exception) {}
                    }

                    val routed = audioRecord.routedDevice
                    prefs.edit()
                        .putBoolean("running", true)
                        .putInt("last_peak", peak)
                        .putInt("max_peak", maxPeak)
                        .putInt("routed_id", routed?.id ?: -1)
                        .putString("routed_name", routed?.productName?.toString() ?: "Unknown")
                        .putInt("routed_type", routed?.type ?: -1)
                        .putLong("last_sample_time", now)
                        .apply()

                    if (now - lastNotificationAt >= 5000 && now - lastWakeAt > 1500) {
                        updateNotification("Listening locally for “Hey Chatty” • Wakes: $wakeCount")
                        lastNotificationAt = now
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    prefs.edit().putString("error", "Recognition error: ${e.message}").apply()
                }
            } finally {
                prefs.edit().putBoolean("running", false).apply()
            }
        }.apply {
            name = "ChattyWakeWord"
            start()
        }
    }

    private fun playReply(requestedOutputId: Int) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentReplyCount = prefs.getInt("reply_count", 0)

        if (!replyReady || !replyFile.exists()) {
            val tts = textToSpeech
            if (tts != null) {
                val result = tts.speak(REPLY_TEXT, TextToSpeech.QUEUE_FLUSH, null, "chatty_direct_reply")
                prefs.edit()
                    .putInt("reply_count", currentReplyCount + if (result == TextToSpeech.SUCCESS) 1 else 0)
                    .putString("reply_route", "DEFAULT Android audio route (TTS fallback)")
                    .putString("reply_status", if (result == TextToSpeech.SUCCESS) "Spoken reply played using default route" else "Spoken reply failed")
                    .apply()
            } else {
                prefs.edit().putString("reply_status", "Speech engine not ready when wake phrase was detected").apply()
            }
            return
        }

        try {
            try { replyPlayer?.release() } catch (_: Exception) {}
            replyPlayer = null

            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(replyFile.absolutePath)
            player.prepare()

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val requestedOutput = if (requestedOutputId >= 0) {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == requestedOutputId }
            } else null
            val routeAccepted = requestedOutput?.let { player.setPreferredDevice(it) } ?: true

            prefs.edit()
                .putInt("reply_count", currentReplyCount + 1)
                .putString(
                    "reply_route",
                    if (requestedOutput == null) "AUTO / Android default output" else "${requestedOutput.productName} • ID ${requestedOutput.id}"
                )
                .putBoolean("reply_route_accepted", routeAccepted)
                .putString("reply_status", "Playing: “$REPLY_TEXT”")
                .apply()

            player.setOnCompletionListener { completed ->
                val routed = completed.routedDevice
                prefs.edit()
                    .putString("reply_status", "Last spoken reply completed")
                    .putString("reply_routed_name", routed?.productName?.toString() ?: "Unknown")
                    .putInt("reply_routed_id", routed?.id ?: -1)
                    .apply()
                try { completed.release() } catch (_: Exception) {}
                if (replyPlayer === completed) replyPlayer = null
            }
            player.setOnErrorListener { failed, what, extra ->
                prefs.edit().putString("reply_status", "Reply playback error $what/$extra").apply()
                try { failed.release() } catch (_: Exception) {}
                if (replyPlayer === failed) replyPlayer = null
                true
            }

            replyPlayer = player
            player.start()
        } catch (e: Exception) {
            prefs.edit().putString("reply_status", "Reply playback failed: ${e.message}").apply()
        }
    }

    private fun extractText(json: String, key: String): String {
        return try {
            JSONObject(json).optString(key, "").trim().lowercase()
        } catch (_: Exception) {
            ""
        }
    }

    private fun isWakePhrase(text: String): Boolean {
        val normalized = text.trim().lowercase().replace(Regex("\\s+"), " ")
        return normalized.contains(WAKE_PHRASE)
    }

    private fun fail(message: String) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("running", false)
            .putString("recognizer_status", "STOPPED")
            .putString("error", message)
            .apply()
        updateNotification(message)
        wanted = false
        stopSelf()
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

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Chatty local wake-word test",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun stopCapture(closeModel: Boolean = true) {
        running = false
        val oldRecorder = recorder
        val oldRecognizer = recognizer
        val oldWorker = worker
        recorder = null
        recognizer = null
        worker = null

        try { oldRecorder?.stop() } catch (_: Exception) {}
        try { oldWorker?.join(500) } catch (_: InterruptedException) {}
        try { oldRecorder?.release() } catch (_: Exception) {}
        try { oldRecognizer?.close() } catch (_: Exception) {}
        if (closeModel) {
            try { model?.close() } catch (_: Exception) {}
            model = null
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("running", false).apply()
    }

    override fun onDestroy() {
        wanted = false
        generation++
        stopCapture(closeModel = true)
        try { replyPlayer?.release() } catch (_: Exception) {}
        replyPlayer = null
        try { textToSpeech?.stop() } catch (_: Exception) {}
        try { textToSpeech?.shutdown() } catch (_: Exception) {}
        textToSpeech = null
        super.onDestroy()
    }
}
