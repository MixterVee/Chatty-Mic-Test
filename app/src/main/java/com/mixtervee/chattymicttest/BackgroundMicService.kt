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
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        private const val REPLY_UTTERANCE_ID = "chatty_direct_reply"
        private const val QUESTION_TIMEOUT_MS = 12000L
        private const val QUESTION_SILENCE_MS = 1700L
    }

    @Volatile private var running = false
    @Volatile private var wanted = false
    @Volatile private var ttsReady = false
    @Volatile private var replyLatch: CountDownLatch? = null

    private var recorder: AudioRecord? = null
    private var recognizer: Recognizer? = null
    private var model: Model? = null
    private var worker: Thread? = null
    private var generation = 0
    private var textToSpeech: TextToSpeech? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
                ttsReady = false
                prefs.edit().putString("reply_status", "Android speech engine failed to initialize").apply()
                return@TextToSpeech
            }

            var languageResult = tts.setLanguage(Locale.CANADA)
            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                languageResult = tts.setLanguage(Locale.US)
            }
            ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                languageResult != TextToSpeech.LANG_NOT_SUPPORTED

            if (!ttsReady) {
                prefs.edit().putString("reply_status", "English speech voice is unavailable").apply()
                return@TextToSpeech
            }

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId == REPLY_UTTERANCE_ID) {
                        prefs.edit().putString("reply_status", "Wake reply speaking").apply()
                    }
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == REPLY_UTTERANCE_ID) {
                        prefs.edit().putString("reply_status", "Wake reply completed").apply()
                        replyLatch?.countDown()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == REPLY_UTTERANCE_ID) {
                        prefs.edit().putString("reply_status", "Wake reply error").apply()
                        replyLatch?.countDown()
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == REPLY_UTTERANCE_ID) {
                        prefs.edit().putString("reply_status", "Wake reply error $errorCode").apply()
                        replyLatch?.countDown()
                    }
                }
            })

            prefs.edit().putString("reply_status", "Android voice ready").apply()
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
            .putString("last_question", "")
            .putString("question_partial", "")
            .putString("question_status", "Waiting for wake word")
            .putString("reply_route", "Android default / TV")
            .putBoolean("reply_route_accepted", true)
            .putString("reply_routed_name", "Android default / TV")
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
                    beginCapture(requestedId, loadedModel, myGeneration)
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
            .putString("recognizer_status", "LISTENING for ‘Hey Chatty’")
            .putString("question_status", "Waiting for wake word")
            .apply()

        recorder = audioRecord
        recognizer = voskRecognizer
        running = true
        updateNotification("Listening for “Hey Chatty”")

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
                            .putString("question_status", "Wake word heard")
                            .putString("question_partial", "")
                            .apply()
                        updateNotification("Hey Chatty detected")

                        try { audioRecord.stop() } catch (_: Exception) {}
                        Thread.sleep(150)
                        playDirectTtsReply()
                        Thread.sleep(250)

                        if (running && wanted && myGeneration == generation) {
                            try {
                                audioRecord.startRecording()
                                try { voskRecognizer.reset() } catch (_: Exception) {}
                                captureQuestion(audioRecord, loadedModel, bufferSize, myGeneration)
                                try { voskRecognizer.reset() } catch (_: Exception) {}
                                prefs.edit()
                                    .putString("recognizer_status", "LISTENING for ‘Hey Chatty’")
                                    .apply()
                                updateNotification("Listening for “Hey Chatty”")
                            } catch (e: Exception) {
                                prefs.edit().putString("error", "Could not resume microphone after TTS: ${e.message}").apply()
                            }
                        }
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
                        updateNotification("Listening for “Hey Chatty” • Wakes: $wakeCount")
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

    private fun captureQuestion(
        audioRecord: AudioRecord,
        loadedModel: Model,
        bufferSize: Int,
        myGeneration: Int
    ) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val questionRecognizer = try {
            Recognizer(loadedModel, SAMPLE_RATE.toFloat())
        } catch (e: Exception) {
            prefs.edit().putString("question_status", "Question recognizer failed: ${e.message}").apply()
            return
        }

        val buffer = ShortArray(bufferSize / 2)
        val startedAt = System.currentTimeMillis()
        var lastChangedAt = 0L
        var lastPartial = ""
        var captured = ""
        var heardAnything = false

        prefs.edit()
            .putString("recognizer_status", "LISTENING for your question")
            .putString("question_status", "LISTENING — ask your question now")
            .putString("question_partial", "")
            .apply()
        updateNotification("Listening for your question…")

        try {
            while (running && wanted && myGeneration == generation) {
                val now = System.currentTimeMillis()
                if (now - startedAt >= QUESTION_TIMEOUT_MS) break

                val count = audioRecord.read(buffer, 0, buffer.size)
                if (count <= 0) continue

                val complete = questionRecognizer.acceptWaveForm(buffer, count)
                if (complete) {
                    val text = extractText(questionRecognizer.result, "text")
                    if (text.isNotBlank()) {
                        captured = text
                        heardAnything = true
                        break
                    }
                } else {
                    val partial = extractText(questionRecognizer.partialResult, "partial")
                    if (partial.isNotBlank()) {
                        heardAnything = true
                        if (partial != lastPartial) {
                            lastPartial = partial
                            lastChangedAt = now
                            prefs.edit()
                                .putString("question_partial", partial)
                                .putString("question_status", "Hearing: “$partial”")
                                .apply()
                        }
                    }
                }

                if (heardAnything && lastChangedAt > 0L && now - lastChangedAt >= QUESTION_SILENCE_MS) {
                    val finalText = extractText(questionRecognizer.finalResult, "text")
                    captured = if (finalText.isNotBlank()) finalText else lastPartial
                    break
                }
            }

            if (captured.isBlank()) {
                val finalText = extractText(questionRecognizer.finalResult, "text")
                captured = if (finalText.isNotBlank()) finalText else lastPartial
            }

            if (captured.isNotBlank()) {
                prefs.edit()
                    .putString("last_question", captured)
                    .putString("question_status", "CAPTURED")
                    .putString("question_partial", "")
                    .apply()
                updateNotification("You said: $captured")
            } else {
                prefs.edit()
                    .putString("question_status", "No question heard — waiting for ‘Hey Chatty’")
                    .putString("question_partial", "")
                    .apply()
            }
        } catch (e: Exception) {
            prefs.edit().putString("question_status", "Question capture error: ${e.message}").apply()
        } finally {
            try { questionRecognizer.close() } catch (_: Exception) {}
        }
    }

    private fun playDirectTtsReply() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tts = textToSpeech
        val currentReplyCount = prefs.getInt("reply_count", 0)

        if (!ttsReady || tts == null) {
            prefs.edit()
                .putString("reply_status", "Wake reply unavailable — speech engine not ready")
                .apply()
            return
        }

        val latch = CountDownLatch(1)
        replyLatch = latch
        prefs.edit().putString("reply_status", "Starting wake reply…").apply()

        val result = try {
            tts.speak(REPLY_TEXT, TextToSpeech.QUEUE_FLUSH, null, REPLY_UTTERANCE_ID)
        } catch (e: Exception) {
            prefs.edit().putString("reply_status", "Wake reply exception: ${e.message}").apply()
            replyLatch = null
            return
        }

        if (result != TextToSpeech.SUCCESS) {
            prefs.edit().putString("reply_status", "Wake reply request failed ($result)").apply()
            replyLatch = null
            return
        }

        prefs.edit().putInt("reply_count", currentReplyCount + 1).apply()

        val callbackArrived = try {
            latch.await(6, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!callbackArrived) {
            prefs.edit().putString("reply_status", "Wake reply timed out").apply()
        }
        replyLatch = null
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
                "Chatty wake-word service",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun stopCapture(closeModel: Boolean = true) {
        running = false
        replyLatch?.countDown()
        replyLatch = null
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
        try { textToSpeech?.stop() } catch (_: Exception) {}
        try { textToSpeech?.shutdown() } catch (_: Exception) {}
        textToSpeech = null
        ttsReady = false
        super.onDestroy()
    }
}
