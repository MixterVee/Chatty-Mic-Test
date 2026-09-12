package com.mixtervee.chattymicttest

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var requestedText: TextView
    private lateinit var activeText: TextView
    private lateinit var devicesContainer: LinearLayout
    private lateinit var levelText: TextView
    private lateinit var backgroundText: TextView
    private lateinit var meter: ProgressBar

    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var selectedDeviceId: Int? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            statusText.text = "Microphone permission granted"
            refreshDevicesAndStartSelected()
        } else {
            statusText.text = "Microphone permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val pad = (28 * density).toInt()
        val gap = (10 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "Chatty Mic Test v0.3"
            textSize = 28f
        }
        val instructions = TextView(this).apply {
            text = "Foreground: choose AUTO or a specific microphone below. Background: start the test, press Home, speak, then return to Chatty."
            textSize = 17f
            setPadding(0, gap, 0, gap)
        }
        statusText = TextView(this).apply { textSize = 20f }
        requestedText = TextView(this).apply { textSize = 18f }
        activeText = TextView(this).apply { textSize = 18f }
        levelText = TextView(this).apply {
            text = "Live level: --"
            textSize = 20f
            setPadding(0, gap, 0, 0)
        }
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }

        val refreshButton = Button(this).apply {
            text = "Refresh audio devices"
            isFocusable = true
            setOnClickListener { refreshDevicesAndStartSelected() }
        }

        val backgroundHeading = TextView(this).apply {
            text = "Background listening test"
            textSize = 21f
            setPadding(0, gap * 2, 0, gap)
        }
        backgroundText = TextView(this).apply {
            text = "Not started yet."
            textSize = 18f
            setPadding(0, 0, 0, gap)
        }
        val startBackgroundButton = Button(this).apply {
            text = "START BACKGROUND MIC TEST"
            isFocusable = true
            setOnClickListener { startBackgroundTest() }
        }
        val stopBackgroundButton = Button(this).apply {
            text = "STOP BACKGROUND MIC TEST"
            isFocusable = true
            setOnClickListener { stopBackgroundTest() }
        }

        val devicesHeading = TextView(this).apply {
            text = "Detected microphone inputs"
            textSize = 21f
            setPadding(0, gap * 2, 0, gap)
        }
        devicesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(title)
        root.addView(instructions)
        root.addView(statusText)
        root.addView(requestedText)
        root.addView(activeText)
        root.addView(levelText)
        root.addView(
            meter,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (40 * density).toInt()
            )
        )
        root.addView(refreshButton)
        root.addView(backgroundHeading)
        root.addView(backgroundText)
        root.addView(startBackgroundButton)
        root.addView(stopBackgroundButton)
        root.addView(devicesHeading)
        root.addView(devicesContainer)

        val scrollView = ScrollView(this).apply {
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(scrollView)

        renderDeviceButtons()
        updateBackgroundResults()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            if (!isBackgroundTestRunning()) {
                refreshDevicesAndStartSelected()
            } else {
                statusText.text = "Background microphone test is running"
            }
        } else {
            statusText.text = "Requesting microphone permission…"
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::backgroundText.isInitialized) updateBackgroundResults()
    }

    private fun getInputDevices(): Array<AudioDeviceInfo> {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    }

    private fun renderDeviceButtons() {
        devicesContainer.removeAllViews()

        val autoButton = Button(this).apply {
            text = if (selectedDeviceId == null) {
                "✓ AUTO — Let Android choose the microphone"
            } else {
                "AUTO — Let Android choose the microphone"
            }
            isFocusable = true
            setOnClickListener {
                stopBackgroundTest(silent = true)
                selectedDeviceId = null
                renderDeviceButtons()
                startMicTest(null)
            }
        }
        devicesContainer.addView(autoButton)

        val inputs = getInputDevices()
        if (inputs.isEmpty()) {
            devicesContainer.addView(TextView(this).apply {
                text = "Android currently reports no microphone input devices."
                textSize = 17f
            })
            return
        }

        inputs.forEachIndexed { index, device ->
            val selected = selectedDeviceId == device.id
            val prefix = if (selected) "✓ " else ""
            val button = Button(this).apply {
                text = buildString {
                    append(prefix)
                    append("TEST INPUT ${index + 1}: ")
                    append(deviceTypeName(device.type))
                    append("\n")
                    append(device.productName)
                    append("   •   ID ")
                    append(device.id)
                }
                isAllCaps = false
                isFocusable = true
                setOnClickListener {
                    stopBackgroundTest(silent = true)
                    selectedDeviceId = device.id
                    renderDeviceButtons()
                    startMicTest(device)
                }
            }
            devicesContainer.addView(button)
        }
    }

    private fun refreshDevicesAndStartSelected() {
        stopBackgroundTest(silent = true)
        renderDeviceButtons()
        val selected = selectedDeviceId?.let { id ->
            getInputDevices().firstOrNull { it.id == id }
        }

        if (selectedDeviceId != null && selected == null) {
            selectedDeviceId = null
            renderDeviceButtons()
            statusText.text = "Selected microphone disappeared; switched back to AUTO"
        }

        startMicTest(selected)
    }

    private fun startMicTest(preferredDevice: AudioDeviceInfo?) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            statusText.text = "Microphone permission is required"
            return
        }

        stopMicTest()
        meter.progress = 0
        levelText.text = "Live level: --"
        activeText.text = "Active/routed input: waiting for Android…"

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
            statusText.text = "AudioRecord failed: ${e.message}"
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            statusText.text = "Microphone did not initialize"
            audioRecord.release()
            return
        }

        val preferredAccepted = if (preferredDevice != null) {
            audioRecord.setPreferredDevice(preferredDevice)
        } else {
            true
        }

        requestedText.text = if (preferredDevice == null) {
            "Requested input: AUTO (Android chooses)"
        } else {
            "Requested input: ${deviceTypeName(preferredDevice.type)} — ${preferredDevice.productName} — ID ${preferredDevice.id}\n" +
                "Routing request accepted by Android: ${if (preferredAccepted) "YES" else "NO"}"
        }

        recorder = audioRecord
        running = true
        statusText.text = "Listening…"

        worker = Thread {
            val buffer = ShortArray(bufferSize / 2)
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
                        val percent = ((peak / 32767.0) * 100.0)
                            .toInt()
                            .coerceIn(0, 100)
                        val routed = audioRecord.routedDevice

                        runOnUiThread {
                            meter.progress = percent
                            levelText.text = "Live level: $percent%   •   Peak PCM: $peak"
                            activeText.text = if (routed != null) {
                                "Active/routed input: ${deviceTypeName(routed.type)} — ${routed.productName} — ID ${routed.id}"
                            } else {
                                "Active/routed input: Android has not reported the route yet"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    runOnUiThread { statusText.text = "Recording error: ${e.message}" }
                }
            }
        }.apply {
            name = "ChattyMicCapture"
            start()
        }
    }

    private fun startBackgroundTest() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            statusText.text = "Microphone permission is required"
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        stopMicTest()
        val prefs = getSharedPreferences(BackgroundMicService.PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .clear()
            .putInt("max_peak", 0)
            .putInt("last_peak", 0)
            .apply()

        val intent = Intent(this, BackgroundMicService::class.java).apply {
            action = BackgroundMicService.ACTION_START
            putExtra(BackgroundMicService.EXTRA_DEVICE_ID, selectedDeviceId ?: -1)
        }
        ContextCompat.startForegroundService(this, intent)
        statusText.text = "Background test started — press Home now"
        backgroundText.text = "STARTED. Press Home, speak several times from across the room, wait 10–20 seconds, then return to Chatty."
    }

    private fun stopBackgroundTest(silent: Boolean = false) {
        val intent = Intent(this, BackgroundMicService::class.java).apply {
            action = BackgroundMicService.ACTION_STOP
        }
        try { startService(intent) } catch (_: Exception) { stopService(Intent(this, BackgroundMicService::class.java)) }
        if (!silent && ::backgroundText.isInitialized) {
            updateBackgroundResults()
            statusText.text = "Background test stopped"
        }
    }

    private fun isBackgroundTestRunning(): Boolean {
        return getSharedPreferences(BackgroundMicService.PREFS, Context.MODE_PRIVATE)
            .getBoolean("running", false)
    }

    private fun updateBackgroundResults() {
        val prefs = getSharedPreferences(BackgroundMicService.PREFS, Context.MODE_PRIVATE)
        val runningNow = prefs.getBoolean("running", false)
        val maxPeak = prefs.getInt("max_peak", 0)
        val lastPeak = prefs.getInt("last_peak", 0)
        val routedId = prefs.getInt("routed_id", -1)
        val routedName = prefs.getString("routed_name", "Unknown") ?: "Unknown"
        val requestedId = prefs.getInt("requested_id", -1)
        val preferredAccepted = prefs.getBoolean("preferred_accepted", true)
        val error = prefs.getString("error", "") ?: ""
        val lastSampleTime = prefs.getLong("last_sample_time", 0L)

        backgroundText.text = buildString {
            append("Service: ${if (runningNow) "RUNNING" else "STOPPED"}\n")
            append("Requested input: ${if (requestedId < 0) "AUTO" else "ID $requestedId"}")
            if (requestedId >= 0) append("   •   Routing accepted: ${if (preferredAccepted) "YES" else "NO"}")
            append("\n")
            append("Actual routed input: $routedName")
            if (routedId >= 0) append("   •   ID $routedId")
            append("\n")
            append("Background max peak: $maxPeak   •   Latest peak: $lastPeak")
            if (lastSampleTime > 0) append("\nAudio samples received: YES")
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
        AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony input"
        else -> "Audio device type $type"
    }

    private fun stopMicTest() {
        running = false
        val oldRecorder = recorder
        val oldWorker = worker
        recorder = null
        worker = null

        try { oldRecorder?.stop() } catch (_: Exception) {}
        try { oldWorker?.join(300) } catch (_: InterruptedException) {}
        try { oldRecorder?.release() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopMicTest()
        super.onDestroy()
    }
}
