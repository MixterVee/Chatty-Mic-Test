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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class MainActivity : AppCompatActivity() {
    companion object {
        private const val UI_PREFS = "chatty_ui"
        private const val UI_OUTPUT_ID = "selected_output_id"
    }

    private lateinit var statusText: TextView
    private lateinit var requestedText: TextView
    private lateinit var activeText: TextView
    private lateinit var levelText: TextView
    private lateinit var wakeText: TextView
    private lateinit var outputStatusText: TextView
    private lateinit var meter: ProgressBar
    private lateinit var devicesContainer: LinearLayout
    private lateinit var outputsContainer: LinearLayout

    @Volatile private var foregroundRunning = false
    private var foregroundRecorder: AudioRecord? = null
    private var foregroundThread: Thread? = null
    private var selectedDeviceId: Int? = null
    private var selectedOutputDeviceId: Int? = null

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
        if (granted) startForegroundMic(null)
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
            text = "Chatty Mic Test v0.5"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "Wake + speaker test: say ‘Hey Chatty’ and Chatty should answer, “Hi Mike, I’m listening.”"
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
            text = "Spoken reply / audio output"
            textSize = 21f
            setPadding(0, gap * 2, 0, gap)
        })
        outputStatusText = TextView(this).apply {
            text = "Choose AUTO or an output below. Use the beep test to identify the Onn’s own speaker."
            textSize = 18f
            setPadding(0, 0, 0, gap)
        }
        root.addView(outputStatusText)
        root.addView(Button(this).apply {
            text = "PLAY BEEP ON SELECTED OUTPUT"
            isFocusable = true
            setOnClickListener { playOutputTest() }
        })
        outputsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(outputsContainer)

        root.addView(TextView(this).apply {
            text = "Local ‘Hey Chatty’ wake + spoken reply test"
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
                outputStatusText.text = "Selected output: AUTO / Android default"
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
                    outputStatusText.text = "Selected output: ${outputDeviceTypeName(device.type)} — ${device.productName} — ID ${device.id}"
                }
            })
        }
    }

    private fun playOutputTest() {
        val requested = selectedOutputDeviceId?.let { id -> outputDevices().firstOrNull { it.id == id } }
        if (selectedOutputDeviceId != null && requested == null) {
            selectedOutputDeviceId = null
            getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit().putInt(UI_OUTPUT_ID, -1).apply()
            renderOutputButtons()
        }

        outputStatusText.text = "Playing test beep…"
        Thread {
            val sampleRate = 16000
            val durationSeconds = 0.45
            val sampleCount = (sampleRate * durationSeconds).toInt()
            val samples = ShortArray(sampleCount) { i ->
                val envelope = when {
                    i < 300 -> i / 300.0
                    i > sampleCount - 300 -> (sampleCount - i) / 300.0
                    else -> 1.0
                }.coerceIn(0.0, 1.0)
                (sin(2.0 * PI * 740.0 * i / sampleRate) * 9000.0 * envelope).toInt().toShort()
            }

            var track: AudioTrack? = null
            try {
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
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

                val accepted = requested?.let { track.setPreferredDevice(it) } ?: true
                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep(120)
                val routed = track.routedDevice

                runOnUiThread {
                    outputStatusText.text = buildString {
                        append("Requested: ")
                        append(if (requested == null) "AUTO / Android default" else "${outputDeviceTypeName(requested.type)} — ${requested.productName} — ID ${requested.id}")
                        append("\nRouting request accepted: ${if (accepted) "YES" else "NO"}")
                        append("\nActual routed output: ")
                        append(if (routed == null) "not reported yet" else "${outputDeviceTypeName(routed.type)} — ${routed.productName} — ID ${routed.id}")
                    }
                }
                Thread.sleep(450)
            } catch (e: Exception) {
                runOnUiThread { outputStatusText.text = "Output test failed: ${e.message}" }
            } finally {
                try { track?.stop() } catch (_: Exception) {}
                try { track?.release() } catch (_: Exception) {}
            }
        }.apply {
            name = "ChattyOutputTest"
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
        val selected = selectedDeviceId?.let { id -> inputDevices().firstOrNull { it.id == id } }
        if (selectedDeviceId != null && selected == null) selectedDeviceId = null
        startForegroundMic(selected)
    }

    private fun startForegroundMic(preferredDevice: AudioDeviceInfo?) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
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
        renderOutputButtons()
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
        val partial = p.getString("last_partial", "") ?: ""
        val result = p.getString("last_result", "") ?: ""
        val peak = p.getInt("last_peak", 0)
        val maxPeak = p.getInt("max_peak", 0)
        val replyCount = p.getInt("reply_count", 0)
        val replyStatus = p.getString("reply_status", "Not initialized") ?: "Not initialized"
        val replyRoute = p.getString("reply_route", "Not used yet") ?: "Not used yet"
        val replyAccepted = p.getBoolean("reply_route_accepted", true)
        val replyRoutedName = p.getString("reply_routed_name", "Unknown") ?: "Unknown"
        val replyRoutedId = p.getInt("reply_routed_id", -1)
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
            if (partial.isNotBlank()) append("\nCurrently hearing: “$partial”")
            if (result.isNotBlank()) append("\nLast completed phrase: “$result”")
            append("\nMic peak: $peak   •   Max: $maxPeak")
            append("\n\nSpoken replies: $replyCount")
            append("\nReply status: $replyStatus")
            append("\nRequested reply output: ${if (requestedOutputId < 0) "AUTO" else "ID $requestedOutputId"}")
            append("\nReply route: $replyRoute")
            if (requestedOutputId >= 0) append("   •   Accepted: ${if (replyAccepted) "YES" else "NO"}")
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
        super.onDestroy()
    }
}
