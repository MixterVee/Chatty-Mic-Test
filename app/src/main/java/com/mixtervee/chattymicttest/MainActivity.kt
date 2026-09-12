package com.mixtervee.chattymicttest

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var questionText: TextView
    private lateinit var diagnosticsText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val pollStatus = object : Runnable {
        override fun run() {
            if (::statusText.isInitialized) updateStatus()
            handler.postDelayed(this, 500)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startChatty()
        else statusText.text = "Microphone permission is required for Chatty."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val pad = (28 * density).toInt()
        val gap = (12 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Chatty Mic Test v0.9"
            textSize = 30f
        })

        root.addView(TextView(this).apply {
            text = "Say “Hey Chatty”. After Chatty replies, ask your question normally."
            textSize = 19f
            setPadding(0, gap, 0, gap * 2)
        })

        statusText = TextView(this).apply {
            textSize = 24f
            text = "Chatty is stopped"
            setPadding(0, 0, 0, gap)
        }
        root.addView(statusText)

        root.addView(Button(this).apply {
            text = "START CHATTY"
            textSize = 20f
            isFocusable = true
            setOnClickListener { ensurePermissionAndStart() }
        })

        root.addView(Button(this).apply {
            text = "STOP CHATTY"
            textSize = 20f
            isFocusable = true
            setOnClickListener { stopChatty() }
        })

        root.addView(TextView(this).apply {
            text = "What Chatty heard"
            textSize = 22f
            setPadding(0, gap * 2, 0, gap)
        })

        questionText = TextView(this).apply {
            textSize = 24f
            text = "No question captured yet."
            setPadding(0, 0, 0, gap * 2)
        }
        root.addView(questionText)

        root.addView(TextView(this).apply {
            text = "Status"
            textSize = 20f
            setPadding(0, gap, 0, gap)
        })

        diagnosticsText = TextView(this).apply {
            textSize = 17f
            text = "Waiting to start."
        }
        root.addView(diagnosticsText)

        setContentView(ScrollView(this).apply {
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        })

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(pollStatus)
        handler.post(pollStatus)
    }

    override fun onPause() {
        handler.removeCallbacks(pollStatus)
        super.onPause()
    }

    private fun ensurePermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startChatty()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startChatty() {
        val prefs = getSharedPreferences(BackgroundMicService.PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        val intent = Intent(this, BackgroundMicService::class.java).apply {
            action = BackgroundMicService.ACTION_START
            putExtra(BackgroundMicService.EXTRA_DEVICE_ID, -1)
            putExtra(BackgroundMicService.EXTRA_OUTPUT_DEVICE_ID, -1)
        }
        ContextCompat.startForegroundService(this, intent)
        statusText.text = "Starting Chatty…"
        questionText.text = "Waiting for “Hey Chatty”…"
    }

    private fun stopChatty() {
        val intent = Intent(this, BackgroundMicService::class.java).apply {
            action = BackgroundMicService.ACTION_STOP
        }
        try {
            startService(intent)
        } catch (_: Exception) {
            stopService(Intent(this, BackgroundMicService::class.java))
        }
        statusText.text = "Chatty is stopped"
        updateStatus()
    }

    private fun updateStatus() {
        val p = getSharedPreferences(BackgroundMicService.PREFS, Context.MODE_PRIVATE)
        val running = p.getBoolean("running", false)
        val recognizerStatus = p.getString("recognizer_status", "Not started") ?: "Not started"
        val wakeCount = p.getInt("wake_count", 0)
        val wakeTime = p.getLong("last_wake_time", 0L)
        val replyStatus = p.getString("reply_status", "Not initialized") ?: "Not initialized"
        val questionStatus = p.getString("question_status", "Waiting for wake word") ?: "Waiting for wake word"
        val questionEngine = p.getString("question_engine", "Android / Google speech recognition") ?: "Android / Google speech recognition"
        val questionPartial = p.getString("question_partial", "") ?: ""
        val lastQuestion = p.getString("last_question", "") ?: ""
        val error = p.getString("error", "") ?: ""

        statusText.text = when {
            error.isNotBlank() -> "Chatty needs attention"
            running && recognizerStatus.contains("your question", ignoreCase = true) -> "🎤 Listening to your question…"
            running -> "● Chatty is listening for “Hey Chatty”"
            else -> "Chatty is stopped"
        }

        questionText.text = when {
            questionPartial.isNotBlank() -> "Hearing: “$questionPartial”"
            questionStatus.startsWith("LISTENING", ignoreCase = true) -> "Listening…"
            lastQuestion.isNotBlank() -> "You said: “$lastQuestion”"
            running -> "Waiting for “Hey Chatty”…"
            else -> "No question captured yet."
        }

        diagnosticsText.text = buildString {
            append("Wake detections: $wakeCount")
            if (wakeTime > 0L) {
                append("   •   Last: ")
                append(DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(wakeTime)))
            }
            append("\nWake recognizer: $recognizerStatus")
            append("\nVoice reply: $replyStatus")
            append("\nQuestion engine: $questionEngine")
            append("\nQuestion capture: $questionStatus")
            if (error.isNotBlank()) append("\nERROR: $error")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollStatus)
        super.onDestroy()
    }
}
