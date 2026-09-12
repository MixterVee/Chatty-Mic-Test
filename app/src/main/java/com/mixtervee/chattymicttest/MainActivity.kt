package com.mixtervee.chattymicttest

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class MainActivity : AppCompatActivity() {
    companion object {
        private const val UI_PREFS = "chatty_ui"
        private const val UI_OUTPUT_ID = "selected_output_id"
        private const val TTS_TEST_ID = "chatty_tts_test"
    }

    private lateinit var statusText: TextView
    private lateinit var requestedText: TextView
    private lateinit var activeText: TextView
    private lateinit var levelText: TextView
    private lateinit var wakeText: TextView
    private lateinit var outputStatusText: TextView
    private lateinit var ttsStatusText: TextView
    private lateinit var meter: ProgressBar
    private lateinit var devicesContainer: LinearLayout
    private lateinit var outputsContainer: LinearLayout

    @Volatile private var foregroundRunning = false
    private var foregroundRecorder: AudioRecord? = null
    private var foregroundThread: Thread? = null
    private var selectedDeviceId: Int? = null
    private var selectedOutputDeviceId: Int? = null

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    private val handler = Handler(Looper.getMainLooper())
    private val pollWakeStatus = object : Runnable {
        override fun run() {
            if (::wakeText.isInitialized) updateWakeStatus()
            handler.postDelayed(this, 500)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSelectedForegroundMic()
        else statusText.text = "Microphone permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        selectedOutputDeviceId = getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
            .getInt(UI_OUTPUT_ID, -1)
            .takeIf { it >= 0 }

        val density = resources.displayMetrics.density
        val pad = (28 * density).toInt()
        val gap = (10 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Chatty Mic Test v0.6"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "TTS + speaker-routing diagnostics. We already know the Onn microphones and background ‘Hey Chatty’ detection work."
            textSize = 17f
            setPadding(0, gap, 0, gap)
        })

        statusText = TextView(this).apply { textSize = 20f }
        requestedText = TextView(this).apply { textSize = 18f }
        activeText = TextView(this).apply { textSize = 18f }
        levelText = TextView(this).apply { textSize = 19f }
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }

        root.addView(statusText)
        root.addView(requestedText)
        root.addView(activeText)
        root.addView(levelText)
        root.addView(meter, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (40 * density).toInt()
        ))

        root.addView(Button(this).apply {
            text = "REFRESH AUDIO DEVICES"
            isFocusable = true
            setOnClickListener {
                stopWakeTest(silent = true)
                validateSelectedOutput()
                renderDeviceButtons()
                renderOutputButtons()
                startSelectedForegroundMic()
            }
        })

        root.addView(TextView(this).apply {
            text = "Android speech engine test"
            textSize = 21f
            setPadding(0, gap * 2, 0, gap)
        })
        ttsStatusText = TextView(this).apply {
            text = "Initializing Android Text-to-Speech…"
            textSize = 18f
            setPadding(0, 0, 0, gap)
        }
        root.addView(ttsStatusText)
        root.addView(Button(this).apply {
            text = "TEST ANDROID VOICE — ‘HI MIKE’"
            isFocusable = true
            setOnClickListener { testTtsDirectly() }
        })

        root.addView(TextView(this).apply {
            text = "Audio output routing"
            textSize = 21f
            setPadding(0, gap * 2, 0, gap)
        })
        outputStatusText = TextView(this).apply {
            text = "Choose AUTO or an output below."
            textSize = 18f
            setPadding(0, 0, 0, gap)
        }
        root.addView(outputStatusText)
        root.addView(Button(this).apply {
            text = "NORMAL BEEP — PREFERRED OUTPUT"
            isFocusable = true
            setOnClickListener { playOutputTest(useCommunicationRoute = false) }
        })
        root.addView(Button(this).apply {
            text = "COMMUNICATION-ROUTE BEEP"
            isFocusable = true
            setOnClickListener { playOutputTest(useCommunicationRoute = true) }
        })
        outputsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(outputsContainer)

        root.addView(TextView(this).apply {
            text = "Local ‘Hey Chatty’ wake + spoken reply"
            textSize = 21f
            setPadding(0, gap * 2, 0, gap)
        })
        wakeText = TextView(this).apply {
            text = "Not started yet."
            textSize = 18f
            setPadding(0, 0, 0, gap)
        }
        root.addView(wakeText)
        root.addView(Button(this).apply {
            text = "START ‘HEY CHATTY’ + SPOKEN REPLY TEST"
            isFocusable = true
            setOnClickListener { startWakeTest() }
        })
        root.addView(Button(this).apply {
            text = "STOP ‘HEY CHATTY’ TEST"
            isFocusable = true
            setOnClickListener { stopWakeTest() }
        })

        root.addView(TextView(this).apply {
            text = "Detected microphone inputs"
            textSize = 21f
            setPadding(0, gap * 2, 0, gap)
        })
        devicesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(devicesContainer)

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        })

        initTtsDiagnostics()
        validateSelectedOutput()
        renderOutputButtons()
        renderDeviceButtons()
        updateWakeStatus()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (isWakeTestRunning()) statusText.text = "Local wake-word service is running"
            else startSelectedForegroundMic()
        } else {
            statusText.text = "Requesting microphone permission…"
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(pollWakeStatus)
        handler.post(pollWakeStatus)
    }

    override fun onPause() {
        handler.removeCallbacks(pollWakeStatus)
        super.onPause()
    }

    private fun initTtsDiagnostics() {
        ttsStatusText.text = "Initializing Android Text-to-Speech…"
        tts = TextToSpeech(this) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                ttsReady = false
                runOnUiThread {
                    ttsStatusText.text = "TTS INIT FAILED — status=$status"
                }
                return@TextToSpeech
            }

            var lang = engine.setLanguage(Locale.CANADA)
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                lang = engine.setLanguage(Locale.US)
            }
            ttsReady = lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId == TTS_TEST_ID) runOnUiThread {
                        ttsStatusText.text = ttsDiagnosticSummary("TTS says it STARTED speaking")
                    }
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == TTS_TEST_ID) runOnUiThread {
                        ttsStatusText.text = ttsDiagnosticSummary("TTS says playback COMPLETED")
                        if (!isWakeTestRunning()) startSelectedForegroundMic()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == TTS_TEST_ID) runOnUiThread {
                        ttsStatusText.text = ttsDiagnosticSummary("TTS playback ERROR")
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == TTS_TEST_ID) runOnUiThread {
                        ttsStatusText.text = ttsDiagnosticSummary("TTS playback ERROR code=$errorCode")
                    }
                }
            })

            runOnUiThread {
                ttsStatusText.text = ttsDiagnosticSummary(
                    if (ttsReady) "TTS READY" else "TTS engine loaded but English voice unavailable"
                )
            }
        }
    }

    private fun ttsDiagnosticSummary(prefix: String): String {
        val engine = tts
        if (engine == null) return prefix
        val engineNames = try {
            engine.engines.joinToString { it.name }
        } catch (_: Exception) { "unknown" }
        val voiceName = try { engine.voice?.name ?: "unknown" } catch (_: Exception) { "unknown" }
        val defaultEngine = try { engine.defaultEngine ?: "unknown" } catch (_: Exception) { "unknown" }
        return "$prefix\nDefault engine: $defaultEngine\nInstalled engines: $engineNames\nVoice: $voiceName"
    }

    private fun testTtsDirectly() {
        stopWakeTest(silent = true)
        stopForegroundMic()
        val engine = tts
        if (!ttsReady || engine == null) {
            ttsStatusText.text = ttsDiagnosticSummary("TTS is NOT READY")
            return
        }
        val result = engine.speak(
            "Hi Mike, I'm listening.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            TTS_TEST_ID
        )
        ttsStatusText.text = ttsDiagnosticSummary(
            if (result == TextToSpeech.SUCCESS) "TTS speak() request ACCEPTED" else "TTS speak() request FAILED ($result)"
        )
    }

    private fun inputDevices(): Array<AudioDeviceInfo> {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    }

    private fun outputDevices(): Array<AudioDeviceInfo> {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    }

    private fun validateSelectedOutput() {
        val id = selectedOutputDeviceId ?: return
        if (outputDevices().none { it.id == id }) {
            selectedOutputDeviceId = null
            getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit().putInt(UI_OUTPUT_ID, -1).apply()
        }
    }

    private fun renderOutputButtons() {
        outputsContainer.removeAllViews()
        outputsContainer.addView(Button(this).apply {
            text = if (selectedOutputDeviceId == null) "✓ AUTO — ANDROID DEFAULT AUDIO OUTPUT"
            else "AUTO — ANDROID DEFAULT AUDIO OUTPUT"
            isFocusable = true
            setOnClickListener {
                stopWakeTest(silent = true)
                selectedOutputDeviceId = null
                getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit().putInt(UI_OUTPUT_ID, -1).apply()
                renderOutputButtons()
                outputStatusText.text = communicationSummary("Selected output: AUTO / Android default")
            }
        })

        val outputs = outputDevices()
        if (outputs.isEmpty()) {
            outputsContainer.addView(TextView(this).apply {
                text = "Android currently reports no audio output devices."
                textSize = 17f
            })
            return
        }

        outputs.forEachIndexed { index, device ->
            outputsContainer.addView(Button(this).apply {
                val mark = if (selectedOutputDeviceId == device.id) "✓ " else ""
                text = "$mark OUTPUT ${index + 1}: ${outputDeviceTypeName(device.type)}\n${device.productName}   •   ID ${device.id}"
                isAllCaps = false
                isFocusable = true
                setOnClickListener {
                    stopWakeTest(silent = true)
                    selectedOutputDeviceId = device.id
                    getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit().putInt(UI_OUTPUT_ID, device.id).apply()
                    renderOutputButtons()
                    outputStatusText.text = communicationSummary(
                        "Selected output: ${outputDeviceTypeName(device.type)} — ${device.productName} — ID ${device.id}"
                    )
                }
            })
        }
    }

    private fun communicationSummary(prefix: String): String {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return "$prefix\nCommunication-device API: unavailable below Android 12"
        }
        val available = am.availableCommunicationDevices
        val current = am.communicationDevice
        val list = if (available.isEmpty()) "none" else available.joinToString { d ->
            "${outputDeviceTypeName(d.type)}:${d.productName}(ID ${d.id})"
        }
        val currentText = if (current == null) "none" else "${outputDeviceTypeName(current.type)}:${current.productName}(ID ${current.id})"
        return "$prefix\nCommunication-capable outputs: $list\nCurrent communication route: $currentText"
    }

    private fun playOutputTest(useCommunicationRoute: Boolean) {
        stopWakeTest(silent = true)
        stopForegroundMic()
        validateSelectedOutput()
        val requested = selectedOutputDeviceId?.let { id -> outputDevices().firstOrNull { it.id == id } }
        outputStatusText.text = if (useCommunicationRoute) "Trying communication-route beep…" else "Playing normal preferred-output beep…"

        Thread {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val oldMode = am.mode
            var communicationAccepted: Boolean? = null
            var communicationRequested: AudioDeviceInfo? = null

            try {
                if (useCommunicationRoute) {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && requested != null) {
                        communicationRequested = am.availableCommunicationDevices.firstOrNull { it.id == requested.id }
                        if (communicationRequested != null) {
                            communicationAccepted = am.setCommunicationDevice(communicationRequested!!)
                        } else {
                            communicationAccepted = false
                        }
                    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && requested?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        @Suppress("DEPRECATION")
                        am.isSpeakerphoneOn = true
                        communicationAccepted = true
                    }
                    Thread.sleep(120)
                }

                val sampleRate = 16000
                val durationSeconds = 0.65
                val sampleCount = (sampleRate * durationSeconds).toInt()
                val samples = ShortArray(sampleCount) { i ->
                    val envelope = when {
                        i < 300 -> i / 300.0
                        i > sampleCount - 300 -> (sampleCount - i) / 300.0
                        else -> 1.0
                    }.coerceIn(0.0, 1.0)
                    (sin(2.0 * PI * 740.0 * i / sampleRate) * 10000.0 * envelope).toInt().toShort()
                }

                val usage = if (useCommunicationRoute) AudioAttributes.USAGE_VOICE_COMMUNICATION
                else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
                val content = if (useCommunicationRoute) AudioAttributes.CONTENT_TYPE_SPEECH
                else AudioAttributes.CONTENT_TYPE_SONIFICATION

                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(content).build())
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                try {
                    val preferredAccepted = requested?.let { track.setPreferredDevice(it) } ?: true
                    track.write(samples, 0, samples.size)
                    track.play()
                    Thread.sleep(180)
                    val routed = track.routedDevice
                    val commCurrent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.communicationDevice else null

                    runOnUiThread {
                        outputStatusText.text = buildString {
                            append(if (useCommunicationRoute) "COMMUNICATION ROUTE TEST" else "NORMAL ROUTE TEST")
                            append("\nRequested output: ")
                            append(if (requested == null) "AUTO" else "${outputDeviceTypeName(requested.type)} — ${requested.productName} — ID ${requested.id}")
                            append("\nAudioTrack preferred-device accepted: ${if (preferredAccepted) "YES" else "NO"}")
                            if (useCommunicationRoute) {
                                append("\nCommunication device match: ")
                                append(if (communicationRequested == null) "NO matching communication device" else "${communicationRequested!!.productName} — ID ${communicationRequested!!.id}")
                                append("\nsetCommunicationDevice accepted: ${communicationAccepted?.let { if (it) "YES" else "NO" } ?: "N/A"}")
                                append("\nCurrent communication route: ")
                                append(if (commCurrent == null) "none/not reported" else "${outputDeviceTypeName(commCurrent.type)} — ${commCurrent.productName} — ID ${commCurrent.id}")
                            }
                            append("\nActual AudioTrack route: ")
                            append(if (routed == null) "not reported" else "${outputDeviceTypeName(routed.type)} — ${routed.productName} — ID ${routed.id}")
                        }
                    }
                    Thread.sleep(650)
                } finally {
                    try { track.stop() } catch (_: Exception) {}
                    try { track.release() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                runOnUiThread { outputStatusText.text = "Output test failed: ${e.javaClass.simpleName}: ${e.message}" }
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try { am.clearCommunicationDevice() } catch (_: Exception) {}
                } else {
                    @Suppress("DEPRECATION")
                    try { am.isSpeakerphoneOn = false } catch (_: Exception) {}
                }
                try { am.mode = oldMode } catch (_: Exception) {}
                runOnUiThread {
                    if (!isWakeTestRunning()) startSelectedForegroundMic()
                }
            }
        }.apply {
            name = "ChattyOutputDiagnostics"
            start()
        }
    }

    private fun renderDeviceButtons() {
        devicesContainer.removeAllViews()
        devicesContainer.addView(Button(this).apply {
            text = if (selectedDeviceId == null) "✓ AUTO — LET ANDROID CHOOSE THE MICROPHONE"
            else "AUTO — LET ANDROID CHOOSE THE MICROPHONE"
            isFocusable = true
            setOnClickListener {
                stopWakeTest(silent = true)
                selectedDeviceId = null
                renderDeviceButtons()
                startForegroundMic(null)
            }
        })

        val inputs = inputDevices()
        if (inputs.isEmpty()) {
            devicesContainer.addView(TextView(this).apply {
                text = "Android currently reports no microphone input devices."
                textSize = 17f
            })
            return
        }

        inputs.forEachIndexed { index, device ->
            devicesContainer.addView(Button(this).apply {
                val mark = if (selectedDeviceId == device.id) "✓ " else ""
                text = "$mark TEST INPUT ${index + 1}: ${deviceTypeName(device.type)}\n${device.productName}   •   ID ${device.id}"
                isAllCaps = false
                isFocusable = true
                setOnClickListener {
                    stopWakeTest(silent = true)
                    selectedDeviceId = device.id
                    renderDeviceButtons()
                    startForegroundMic(device)
                }
            })
        }
    }

    private fun startSelectedForegroundMic() {
        if (isWakeTestRunning()) return
        val selected = selectedDeviceId?.let { id -> inputDevices().firstOrNull { it.id == id } }
        if (selectedDeviceId != null && selected == null) selectedDeviceId = null
        startForegroundMic(selected)
    }

    private fun startForegroundMic(preferredDevice: AudioDeviceInfo?) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (isWakeTestRunning()) return
        stopForegroundMic()

        val sampleRate = 16000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            statusText.text = "Unable to initialize microphone buffer"
            return
        }

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
        } catch (e: Exception) {
            statusText.text = "AudioRecord failed: ${e.message}"
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            statusText.text = "Microphone did not initialize"
            return
        }

        val accepted = preferredDevice?.let { audioRecord.setPreferredDevice(it) } ?: true
        requestedText.text = if (preferredDevice == null) {
            "Requested input: AUTO (Android chooses)"
        } else {
            "Requested input: ${deviceTypeName(preferredDevice.type)} — ${preferredDevice.productName} — ID ${preferredDevice.id}\nRouting accepted: ${if (accepted) "YES" else "NO"}"
        }

        foregroundRecorder = audioRecord
        foregroundRunning = true
        statusText.text = "Foreground mic test listening…"

        foregroundThread = Thread {
            val buffer = ShortArray(minBuffer)
            try {
                audioRecord.startRecording()
                while (foregroundRunning) {
                    val count = audioRecord.read(buffer, 0, buffer.size)
                    if (count <= 0) continue
                    var peak = 0
                    for (i in 0 until count) peak = maxOf(peak, abs(buffer[i].toInt()))
                    val percent = ((peak / 32767.0) * 100).toInt().coerceIn(0, 100)
                    val routed = audioRecord.routedDevice
                    runOnUiThread {
                        meter.progress = percent
                        levelText.text = "Live level: $percent%   •   Peak PCM: $peak"
                        activeText.text = if (routed == null) {
                            "Active/routed input: waiting for Android…"
                        } else {
                            "Active/routed input: ${deviceTypeName(routed.type)} — ${routed.productName} — ID ${routed.id}"
                        }
                    }
                }
            } catch (e: Exception) {
                if (foregroundRunning) runOnUiThread { statusText.text = "Recording error: ${e.message}" }
            }
        }.apply {
            name = "ChattyMicCapture"
            start()
        }
    }

    private fun startWakeTest() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        validateSelectedOutput()
        stopForegroundMic()
        getSharedPreferences(BackgroundMicService.PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        val intent = Intent(this, BackgroundMicService::class.java).apply {
            action = BackgroundMicService.ACTION_START
            putExtra(BackgroundMicService.EXTRA_DEVICE_ID, selectedDeviceId ?: -1)
            putExtra(BackgroundMicService.EXTRA_OUTPUT_DEVICE_ID, selectedOutputDeviceId ?: -1)
        }
        ContextCompat.startForegroundService(this, intent)
        statusText.text = "Starting local ‘Hey Chatty’ recognition + spoken reply…"
        wakeText.text = "Loading the offline model. Wait for LISTENING, then say ‘Hey Chatty’."
    }

    private fun stopWakeTest(silent: Boolean = false) {
        val intent = Intent(this, BackgroundMicService::class.java).apply { action = BackgroundMicService.ACTION_STOP }
        try { startService(intent) } catch (_: Exception) { stopService(Intent(this, BackgroundMicService::class.java)) }
        if (!silent && ::wakeText.isInitialized) {
            statusText.text = "Local wake-word test stopped"
            updateWakeStatus()
        }
    }

    private fun isWakeTestRunning(): Boolean =
        getSharedPreferences(BackgroundMicService.PREFS, Context.MODE_PRIVATE).getBoolean("running", false)

    private fun updateWakeStatus() {
        val p = getSharedPreferences(BackgroundMicService.PREFS, Context.MODE_PRIVATE)
        val running = p.getBoolean("running", false)
        val recognizerStatus = p.getString("recognizer_status", "Not started") ?: "Not started"
        val requestedId = p.getInt("requested_id", -1)
        val requestedOutputId = p.getInt("requested_output_id", -1)
        val routedId = p.getInt("routed_id", -1)
        val routedName = p.getString("routed_name", "Unknown") ?: "Unknown"
        val wakeCount = p.getInt("wake_count", 0)
        val lastWake = p.getString("last_wake_text", "") ?: ""
        val wakeTime = p.getLong("last_wake_time", 0L)
        val replyCount = p.getInt("reply_count", 0)
        val replyStatus = p.getString("reply_status", "Not initialized") ?: "Not initialized"
        val replyRoute = p.getString("reply_route", "Not used yet") ?: "Not used yet"
        val replyAccepted = p.getBoolean("reply_route_accepted", true)
        val replyRoutedName = p.getString("reply_routed_name", "Unknown") ?: "Unknown"
        val replyRoutedId = p.getInt("reply_routed_id", -1)
        val peak = p.getInt("last_peak", 0)
        val maxPeak = p.getInt("max_peak", 0)
        val error = p.getString("error", "") ?: ""

        wakeText.text = buildString {
            append("Service: ${if (running) "RUNNING" else "STOPPED"}\n")
            append("Recognizer: $recognizerStatus\n")
            append("Requested mic: ${if (requestedId < 0) "AUTO" else "ID $requestedId"}\n")
            append("Actual routed mic: $routedName")
            if (routedId >= 0) append("   •   ID $routedId")
            append("\nWake phrase detections: $wakeCount")
            if (lastWake.isNotBlank()) {
                append("\nLAST WAKE: “$lastWake”")
                if (wakeTime > 0) append(" at ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(wakeTime))}")
            }
            append("\nMic peak: $peak   •   Max: $maxPeak")
            append("\n\nSpoken replies attempted: $replyCount")
            append("\nReply status: $replyStatus")
            append("\nRequested reply output: ${if (requestedOutputId < 0) "AUTO" else "ID $requestedOutputId"}")
            append("\nReply route: $replyRoute")
            if (requestedOutputId >= 0) append("   •   Preferred accepted: ${if (replyAccepted) "YES" else "NO"}")
            if (replyRoutedId >= 0 || replyRoutedName != "Unknown") {
                append("\nActual reply output: $replyRoutedName")
                if (replyRoutedId >= 0) append("   •   ID $replyRoutedId")
            }
            if (error.isNotBlank()) append("\nERROR: $error")
        }
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset / speakerphone"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO microphone"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset microphone"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        25 -> "Remote submix"
        17 -> "TV tuner input"
        28 -> "Echo reference"
        else -> "Audio device type $type"
    }

    private fun outputDeviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI output"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC output"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio output"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset / speakerphone"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth audio"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        29 -> "HDMI eARC output"
        26 -> "Bluetooth LE speaker"
        else -> "Audio output type $type"
    }

    private fun stopForegroundMic() {
        foregroundRunning = false
        val oldRecorder = foregroundRecorder
        val oldThread = foregroundThread
        foregroundRecorder = null
        foregroundThread = null
        try { oldRecorder?.stop() } catch (_: Exception) {}
        try { oldThread?.join(300) } catch (_: InterruptedException) {}
        try { oldRecorder?.release() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollWakeStatus)
        stopForegroundMic()
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        super.onDestroy()
    }
}
