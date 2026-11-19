package com.voxycast.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var serverInput: EditText
    private lateinit var statusText: TextView
    private lateinit var codeText: TextView
    private var receiverRegistered = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(MirrorService.EXTRA_STATUS).orEmpty()
            val code = intent?.getStringExtra(MirrorService.EXTRA_CODE)
            if (message.isNotBlank()) statusText.text = message
            if (!code.isNullOrBlank()) codeText.text = code.chunked(3).joinToString(" ")
        }
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val serverUrl = serverInput.text.toString().trim()
                if (serverUrl.isBlank()) {
                    statusText.text = "Enter the signaling server URL first."
                    return@registerForActivityResult
                }
                val serviceIntent = Intent(this, MirrorService::class.java).apply {
                    putExtra(MirrorService.EXTRA_SERVER_URL, serverUrl)
                    putExtra(MirrorService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(MirrorService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                statusText.text = "Starting screen capture and connection…"
                codeText.text = "------"
            } else {
                statusText.text = "Screen sharing permission was not granted."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(30), dp(24), dp(24))
            setBackgroundColor(0xFF0B1020.toInt())
        }
        page.addView(TextView(this).apply {
            text = "VOXY CAST"
            textSize = 13f
            letterSpacing = 0.18f
            setTextColor(0xFF9EABFF.toInt())
        })
        page.addView(TextView(this).apply {
            text = "Mirror your screen."
            textSize = 30f
            setTextColor(0xFFF5F7FF.toInt())
            setPadding(0, dp(18), 0, dp(8))
        })
        page.addView(TextView(this).apply {
            text = "Connect to a browser receiver using a temporary pairing code. Keep both devices on the same Wi-Fi for the first test."
            textSize = 14f
            setTextColor(0xFFA7B2CC.toInt())
            setPadding(0, 0, 0, dp(24))
        })
        page.addView(TextView(this).apply {
            text = "SIGNALING SERVER URL"
            textSize = 11f
            setTextColor(0xFF9EABCE.toInt())
        })
        serverInput = EditText(this).apply {
            setSingleLine(true)
            textSize = 15f
            hint = "http://192.168.1.20:3000"
            setText("http://192.168.1.20:3000")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF697795.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(0xFF17213A.toInt())
        }
        page.addView(serverInput, matchWidth())
        page.addView(TextView(this).apply {
            text = "Replace this with the computer's LAN IP and the port where the Voxy Cast server is running."
            textSize = 11f
            setTextColor(0xFF7F8CAB.toInt())
            setPadding(0, dp(7), 0, dp(22))
        })
        page.addView(Button(this).apply {
            text = "Start screen mirroring"
            isAllCaps = false
            setOnClickListener {
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                projectionLauncher.launch(manager.createScreenCaptureIntent())
            }
        }, matchWidth())
        page.addView(TextView(this).apply {
            text = "YOUR PAIRING CODE"
            textSize = 11f
            setTextColor(0xFF9EABCE.toInt())
            setPadding(0, dp(27), 0, dp(8))
        })
        codeText = TextView(this).apply {
            text = "------"
            textSize = 34f
            letterSpacing = 0.18f
            setTextColor(0xFF9EABFF.toInt())
        }
        page.addView(codeText)
        statusText = TextView(this).apply {
            text = "Ready. Start mirroring to approve screen capture."
            textSize = 13f
            setTextColor(0xFFABB8D5.toInt())
            setPadding(0, dp(16), 0, dp(16))
        }
        page.addView(statusText)
        page.addView(Button(this).apply {
            text = "Stop mirroring"
            isAllCaps = false
            setOnClickListener {
                stopService(Intent(this@MainActivity, MirrorService::class.java))
                codeText.text = "------"
                statusText.text = "Mirroring stopped."
            }
        }, matchWidth())
        page.addView(TextView(this).apply {
            text = "Voxy Cast 0.1 · Video-only preview. Android asks for screen-capture approval each time."
            textSize = 11f
            setTextColor(0xFF71809F.toInt())
            setPadding(0, dp(24), 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(page) })
        ContextCompat.registerReceiver(
            this, statusReceiver, IntentFilter(MirrorService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(statusReceiver)
        super.onDestroy()
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}