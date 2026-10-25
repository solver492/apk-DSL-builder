package com.digitalsolverland.opencode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class OpenCodeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var stopRequested = false
    private var restarts = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var settings: SecureSettings

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = SecureSettings(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground doit être appelé rapidement après startForegroundService()
        goForeground("Agent arrêté")

        when (intent?.action) {
            ACTION_STOP -> {
                stopAgent()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SEND -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (text.isNotBlank()) sendToAgent(text)
            }
            ACTION_STATUS -> checkHealth()
            else -> startAgent() // ACTION_START ou redémarrage système
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopAgent()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- agent

    private fun startAgent() {
        if (process?.isAlive == true) return
        stopRequested = false
        AgentBus.status.value = AgentStatus.STARTING
        updateNotification("Démarrage…")

        scope.launch {
            try {
                extractAssetsIfNeeded()

                // Le binaire Node est embarqué comme lib native (jniLibs/arm64-v8a/libnode.so)
                // car Android >= 10 interdit d'exécuter un fichier écrit dans filesDir.
                val nodeBin = File(applicationInfo.nativeLibraryDir, "libnode.so")
                if (!nodeBin.exists()) {
                    fail("libnode.so introuvable dans ${applicationInfo.nativeLibraryDir}. " +
                        "Place le binaire Node (arm64) dans app/src/main/jniLibs/arm64-v8a/libnode.so")
                    return@launch
                }

                val entry = File(filesDir, settings.entryScript)
                if (!entry.exists()) {
                    fail("Script d'entrée introuvable : ${entry.absolutePath}")
                    return@launch
                }

                val args = settings.extraArgs
                    .replace("{PORT}", settings.port.toString())
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }

                val cmd = listOf(nodeBin.absolutePath, entry.absolutePath) + args
                AgentBus.log("[sys] exec: ${cmd.joinToString(" ")}")

                val pb = ProcessBuilder(cmd)
                    .directory(File(filesDir, "workspace").apply { mkdirs() })
                    .redirectErrorStream(true)

                pb.environment().apply {
                    put("HOME", filesDir.absolutePath)
                    put("TMPDIR", cacheDir.absolutePath)
                    put("PORT", settings.port.toString())
                    put("NODE_ENV", "production")
                    if (settings.openAiKey.isNotBlank()) put("OPENAI_API_KEY", settings.openAiKey)
                    if (settings.openRouterKey.isNotBlank()) put("OPENROUTER_API_KEY", settings.openRouterKey)
                    if (settings.anthropicKey.isNotBlank()) put("ANTHROPIC_API_KEY", settings.anthropicKey)
                }

                val p = pb.start()
                process = p
                stdin = BufferedWriter(OutputStreamWriter(p.outputStream))
                acquireWakeLock()

                AgentBus.status.value = AgentStatus.RUNNING
                updateNotification("En cours · localhost:${settings.port}")
                restarts = 0

                launch {
                    p.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { AgentBus.log(it) }
                    }
                }

                val code = p.waitFor()
                AgentBus.log("[sys] processus terminé (code $code)")
                releaseWakeLock()

                if (!stopRequested && restarts < MAX_RESTARTS) {
                    restarts++
                    AgentBus.log("[sys] redémarrage auto $restarts/$MAX_RESTARTS dans 3s…")
                    delay(3000)
                    if (isActive && !stopRequested) startAgent()
                } else {
                    AgentBus.status.value = if (code == 0 || stopRequested) AgentStatus.STOPPED else AgentStatus.ERROR
                    updateNotification("Arrêté")
                }
            } catch (e: Exception) {
                fail("Erreur : ${e.message}")
            }
        }
    }

    private fun stopAgent() {
        stopRequested = true
        try { stdin?.close() } catch (_: Exception) {}
        process?.let {
            it.destroy()
            // destroyForcibly si le process ne meurt pas sous 2s
            scope.launch {
                delay(2000)
                if (it.isAlive) it.destroyForcibly()
            }
        }
        process = null
        stdin = null
        releaseWakeLock()
        AgentBus.status.value = AgentStatus.STOPPED
        AgentBus.log("[sys] agent arrêté")
    }

    private fun sendToAgent(text: String) {
        scope.launch {
            try {
                val w = stdin
                if (process?.isAlive != true || w == null) {
                    AgentBus.log("[err] agent non démarré")
                    return@launch
                }
                w.write(text)
                w.newLine()
                w.flush()
            } catch (e: Exception) {
                AgentBus.log("[err] envoi impossible : ${e.message}")
            }
        }
    }

    /** Ping HTTP de la boucle locale pour vérifier que le serveur répond. */
    private fun checkHealth() {
        scope.launch {
            val url = "http://127.0.0.1:${settings.port}/"
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 2000
                c.readTimeout = 2000
                AgentBus.log("[sys] GET $url -> HTTP ${c.responseCode}")
                c.disconnect()
            } catch (e: Exception) {
                val alive = process?.isAlive == true
                AgentBus.log("[sys] $url injoignable (${e.javaClass.simpleName}) · process vivant: $alive")
            }
        }
    }

    private fun fail(msg: String) {
        AgentBus.log("[err] $msg")
        AgentBus.status.value = AgentStatus.ERROR
        updateNotification("Erreur")
    }

    // --------------------------------------------------------------- assets

    private fun extractAssetsIfNeeded() {
        val marker = File(filesDir, "opencode/.version")
        val current = packageManager.getPackageInfo(packageName, 0).let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toString() else @Suppress("DEPRECATION") it.versionCode.toString()
        }
        if (marker.exists() && marker.readText() == current) return

        AgentBus.log("[sys] extraction des assets…")
        copyAsset("opencode", File(filesDir, "opencode"))
        marker.parentFile?.mkdirs()
        marker.writeText(current)
    }

    private fun copyAsset(assetPath: String, dest: File) {
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            dest.setExecutable(true)
        } else {
            dest.mkdirs()
            children.forEach { copyAsset("$assetPath/$it", File(dest, it)) }
        }
    }

    // ---------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "OpenCode Agent", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OpenCodeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("OpenCode Agent")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Arrêter", stop)
            .build()
    }

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencode:agent").apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.digitalsolverland.opencode.START"
        const val ACTION_STOP = "com.digitalsolverland.opencode.STOP"
        const val ACTION_SEND = "com.digitalsolverland.opencode.SEND"
        const val ACTION_STATUS = "com.digitalsolverland.opencode.STATUS"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL_ID = "opencode_agent"
        private const val NOTIF_ID = 4242
        private const val MAX_RESTARTS = 5

        fun intent(ctx: Context, action: String, text: String? = null): Intent =
            Intent(ctx, OpenCodeService::class.java).setAction(action).apply {
                if (text != null) putExtra(EXTRA_TEXT, text)
            }
    }
}
