package com.mixtervee.chattymicttest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var levelText: TextView
    private lateinit var meter: ProgressBar

    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startMicTest() else statusText.text = "Microphone permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (32 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "Chatty Mic Test v0.1"
            textSize = 28f
        }
        statusText = TextView(this).apply { textSize = 20f }
        deviceText = TextView(this).apply { textSize = 18f }
        levelText = TextView(this).apply { textSize = 20f }
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(deviceText)
        root.addView(levelText)
        root.addView(meter, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (40 * resources.displayMetrics.density).toInt()
        ))
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startMicTest()
        } else {
            statusText.text = "Requesting microphone permission…"
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startMicTest() {
        stopMicTest()

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

        recorder = audioRecord
        running = true
        statusText.text = "Listening… talk toward the Onn box"
        showInputDevices()

        worker = Thread {
            val buffer = ShortArray(bufferSize / 2)
            try {
                audioRecord.startRecording()
                while (running) {
                    val count = audioRecord.read(buffer, 0, buffer.size)
                    if (count > 0) {
                        var peak = 0
                        for (i in 0 until count) {
                            val v = abs(buffer[i].toInt())
                            if (v > peak) peak = v
                        }
                        val percent = ((peak / 32767.0) * 100.0).toInt().coerceIn(0, 100)
                        runOnUiThread {
                            meter.progress = percent
                            levelText.text = "Live level: $percent%   Peak PCM: $peak"
                            val routed = audioRecord.routedDevice
                            if (routed != null) {
                                deviceText.text = "Active input: ${deviceTypeName(routed.type)}\nProduct: ${routed.productName}\nID: ${routed.id}"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Recording error: ${e.message}" }
            }
        }.apply { start() }
    }

    private fun showInputDevices() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        if (inputs.isEmpty()) {
            deviceText.text = "Android reports no audio input devices"
            return
        }
        deviceText.text = inputs.joinToString("\n\n") { d ->
            "Input: ${deviceTypeName(d.type)}\nProduct: ${d.productName}\nID: ${d.id}"
        }
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        else -> "Audio device type $type"
    }

    private fun stopMicTest() {
        running = false
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        worker = null
    }

    override fun onDestroy() {
        stopMicTest()
        super.onDestroy()
    }
}
