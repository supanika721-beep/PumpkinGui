package com.pumpkin.gui.service

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pumpkin.gui.MainActivity
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.*
import java.io.File

class ServerService : Service() {

    companion object {
        const val CHANNEL_ID = "pumpkin_channel"
        const val NOTIF_ID   = 1

        // UI callbacks — always called on Main thread
        var onStateChanged:   ((Boolean) -> Unit)?               = null
        var onPlayersChanged: ((List<String>) -> Unit)?          = null
        var onStatsChanged:   ((cpu: Float, ram: Long) -> Unit)? = null
        var onCrashed:        ((exitCode: Int) -> Unit)?         = null
        // Called when terminal output changes — ConsoleFragment handles rendering
        var onTerminalChanged: ((TerminalSession) -> Unit)?      = null

        @Volatile var isRunning  = false
        @Volatile var isStopping = false

        // The active TerminalSession — ConsoleFragment attaches TerminalView to this
        @Volatile var session: TerminalSession? = null
    }

    private val binder = ServerBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val onlinePlayers = mutableListOf<String>()
    private var lastProcTicks   = 0L
    private var lastMonitorTime = 0L
    private var serverPid       = -1

    inner class ServerBinder : Binder() {
        fun getService(): ServerService = this@ServerService
    }

    override fun onBind(intent: Intent): IBinder = binder
    override fun onCreate() { super.onCreate(); createNotificationChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START"   -> startServer()
            "STOP"    -> stopServer()
            "RESTART" -> restartServer()
        }
        return START_NOT_STICKY
    }

    // ── Start ─────────────────────────────────────────────────────────────────

    fun startServer() {
        if (isRunning || isStopping) return

        val binary = File(filesDir, "pumpkin")
        if (!binary.exists()) {
            postSystemLog("[ERROR] Binary 'pumpkin' not found in ${filesDir.absolutePath}")
            return
        }
        binary.setExecutable(true, false)
        startForeground(NOTIF_ID, buildNotification("Server running…"))

        // TerminalSessionClient — called from background thread by TerminalSession
        val client = object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                // Notify ConsoleFragment to update TerminalView — MUST be on Main thread
                serviceScope.launch(Dispatchers.Main) {
                    onTerminalChanged?.invoke(changedSession)
                }
            }
            override fun onSessionFinished(finishedSession: TerminalSession) {
                val exit = finishedSession.exitStatus
                serviceScope.launch(Dispatchers.Main) {
                    isRunning  = false
                    isStopping = false
                    onStateChanged?.invoke(false)
                    onPlayersChanged?.invoke(emptyList())
                    onStatsChanged?.invoke(0f, 0L)
                    if (exit != 0 && !isStopping) {
                        onCrashed?.invoke(exit)
                    }
                }
                cleanupState()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            override fun onTitleChanged(s: TerminalSession) {}
            override fun onCopyTextToClipboard(s: TerminalSession, t: String) {}
            override fun onPasteTextFromClipboard(s: TerminalSession) {}
            override fun onBell(s: TerminalSession) {}
            override fun onColorsChanged(s: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
            override fun getTerminalCursorStyle(): Int? = null  // null = use default style
            override fun logError(tag: String, msg: String) {}
            override fun logWarn(tag: String, msg: String) {}
            override fun logInfo(tag: String, msg: String) {}
            override fun logDebug(tag: String, msg: String) {}
            override fun logVerbose(tag: String, msg: String) {}
            override fun logStackTraceWithMessage(tag: String, msg: String, e: Exception) {}
            override fun logStackTrace(tag: String, e: Exception) {}
        }

        val env = arrayOf(
            "HOME=${filesDir.absolutePath}",
            "TMPDIR=${cacheDir.absolutePath}",
            // TERM=xterm-256color tells Pumpkin this IS a proper terminal
            // → Pumpkin enables full ANSI color output, no "not a TTY" warning
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=en_US.UTF-8",
            "PATH=/system/bin"
        )

        // TerminalSession spawns binary via PTY (openpty + fork + execve via JNI)
        // This is exactly what Termux does — the binary sees a real TTY
        val newSession = TerminalSession(
            binary.absolutePath,           // executable
            filesDir.absolutePath,         // working directory
            arrayOf(),                     // args (none extra)
            env,                           // environment
            2000,
            client
        )

        session    = newSession
        isRunning  = true
        isStopping = false

        serviceScope.launch(Dispatchers.Main) {
            onStateChanged?.invoke(true)
            onTerminalChanged?.invoke(newSession)  // trigger ConsoleFragment to attach
        }

        // Resolve PID for stats monitoring
        serviceScope.launch {
            delay(2000)
            serverPid = getPidFromSession(newSession)
            if (serverPid <= 0) serverPid = scanProcForPid("pumpkin")
            if (serverPid > 0) monitorStats()
        }
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    fun stopServer() {
        if (isStopping) return
        isStopping = true
        isRunning  = false
        session?.write("stop\n")
        serviceScope.launch {
            delay(3000)
            session?.finishIfRunning()
            withContext(Dispatchers.Main) {
                onStateChanged?.invoke(false)
                onPlayersChanged?.invoke(emptyList())
                onStatsChanged?.invoke(0f, 0L)
            }
            cleanupState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ── Restart ───────────────────────────────────────────────────────────────

    fun restartServer() {
        if (isStopping) return
        isStopping = true
        isRunning  = false
        session?.write("stop\n")
        serviceScope.launch {
            delay(3000)
            session?.finishIfRunning()
            delay(500)
            cleanupState()
            startServer()
        }
    }

    // ── Send command ──────────────────────────────────────────────────────────

    fun sendCommand(command: String) {
        session?.write("$command\n")
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private fun cleanupState() {
        isRunning      = false
        isStopping     = false
        serverPid      = -1
        lastProcTicks  = 0L
        lastMonitorTime = 0L
        onlinePlayers.clear()
    }

    // ── PID scan ─────────────────────────────────────────────────────────────

    private fun getPidFromSession(session: TerminalSession): Int = try {
        val f = TerminalSession::class.java.getDeclaredField("mShellPid")
        f.isAccessible = true
        f.getInt(session)
    } catch (_: Exception) { -1 }

    private fun scanProcForPid(name: String): Int = try {
        File("/proc").listFiles()
            ?.filter { it.isDirectory && it.name.all(Char::isDigit) }
            ?.firstOrNull { dir ->
                try {
                    val cmdline = File(dir, "cmdline").readText().replace('\u0000', ' ')
                    cmdline.contains(name, ignoreCase = true)
                } catch (_: Exception) { false }
            }?.name?.toInt() ?: -1
    } catch (_: Exception) { -1 }

    // ── Stats monitoring ──────────────────────────────────────────────────────

    private suspend fun monitorStats() {
        delay(2000)
        while (isRunning) {
            if (serverPid == -1) serverPid = scanProcForPid("pumpkin")
            val pid = serverPid
            if (pid > 0) {
                val cpu = readCpu(pid)
                val ram = readRam(pid)
                withContext(Dispatchers.Main) { onStatsChanged?.invoke(cpu, ram) }
            }
            delay(2000)
        }
    }

    private fun readCpu(pid: Int): Float {
        val CLK_TCK = 100L
        return try {
            val raw    = File("/proc/$pid/stat").readText()
            val fields = raw.substringAfterLast(')').trim().split("\\s+".toRegex())
            if (fields.size < 13) return 0f
            val ticks  = (fields[11].toLongOrNull() ?: 0L) + (fields[12].toLongOrNull() ?: 0L)
            val nowMs  = android.os.SystemClock.elapsedRealtime()
            val dTicks = ticks - lastProcTicks
            val dMs    = nowMs - lastMonitorTime
            lastProcTicks   = ticks
            lastMonitorTime = nowMs
            if (lastMonitorTime == 0L || dMs <= 0L || dTicks < 0L) return 0f
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            (dTicks.toFloat() / ((dMs / 1000f) * CLK_TCK * cores) * 100f).coerceIn(0f, 100f)
        } catch (_: Exception) { 0f }
    }

    private fun readRam(pid: Int): Long = try {
        val status = File("/proc/$pid/status").readLines()
        val rss = status.firstOrNull { it.startsWith("VmRSS:") }
            ?.split("\\s+".toRegex())?.getOrNull(1)?.toLongOrNull() ?: 0L
        val rssFile = status.firstOrNull { it.startsWith("RssFile:") }
            ?.split("\\s+".toRegex())?.getOrNull(1)?.toLongOrNull() ?: 0L
        (rss - rssFile).coerceAtLeast(0L) / 1024L
    } catch (_: Exception) { 0L }

    private fun postSystemLog(msg: String) {
        serviceScope.launch(Dispatchers.Main) {
            // For system messages we inject directly into the active session if possible
            // Otherwise they appear when ConsoleFragment next opens
        }
        android.util.Log.w("ServerService", msg)
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Pumpkin Server", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val openPi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, ServerService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val restartPi = PendingIntent.getService(this, 2,
            Intent(this, ServerService::class.java).apply { action = "RESTART" },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎃 Pumpkin Server")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "⏹ Stop", stopPi)
            .addAction(android.R.drawable.ic_media_rew, "↺ Restart", restartPi)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        session?.finishIfRunning()
    }
}
