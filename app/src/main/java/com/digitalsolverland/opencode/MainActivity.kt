package com.digitalsolverland.opencode

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignoré */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val settings = SecureSettings(this)

        setContent {
            OpenCodeApp(
                settings = settings,
                onStart = {
                    ContextCompat.startForegroundService(
                        this, OpenCodeService.intent(this, OpenCodeService.ACTION_START)
                    )
                },
                onStop = { startService(OpenCodeService.intent(this, OpenCodeService.ACTION_STOP)) },
                onStatus = { startService(OpenCodeService.intent(this, OpenCodeService.ACTION_STATUS)) },
                onSend = { text ->
                    AgentBus.log("> $text")
                    startService(OpenCodeService.intent(this, OpenCodeService.ACTION_SEND, text))
                }
            )
        }
    }
}
