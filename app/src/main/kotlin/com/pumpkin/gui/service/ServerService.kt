package com.pumpkin.gui.service

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import com.pumpkin.gui.MainActivity
import com.pumpkin.gui.util.ConfigReader
import com.pumpkin.gui.util.ServerPing
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Field

class ServerService : Service() {

    companion object {
        const val CHANNEL_ID = "pumpkin_server_v4"
        const val NOTIF_ID    = 101

        var isRunning  = false
        var isStopping = false
        var serverStartTime = 0L   // System.currentTimeMillis() when server started
        var session: TerminalSession? = null

        // Callbacks for UI
        var onTerminalChanged:     ((TerminalSession) -> Unit)? = null
        var onStateChanged:        ((Boolean) -> Unit)? = null
        var onStateChangedGlobal:  ((Boolean) -> Unit)? = null  // used by MainActivity (status dot)
        var onFontSizeChanged:     ((Int) -> Unit)?     = null  // used by ConsoleFragment
        var onStatsChanged:        ((Float, Long) -> Unit)? = null
        var onPlayersChanged:      ((List<String>) -> Unit)? = null
        var onPlayerCountChanged:  ((online: Int, max: Int) -> Unit)? = null
        var onCrashed:             ((Int) -> Unit)? = null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var lastProcTicks = 0L
    private var lastMonitorTime = 0L
    private var isRestarting = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Server is starting..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP"    -> stopServer()
            "RESTART" -> restartServer()
            else      -> if (!isRunning) startServer()
        }
        return START_NOT_STICKY
    }

    private fun startServer() {
        if (isRunning) return
        isRunning = true
        isStopping = false
        serverStartTime = System.currentTimeMillis()
        
        serviceScope.launch {
            try {
                val binPath = prepareBinary()
                val workDir = filesDir.absolutePath
                val tempDir = cacheDir.absolutePath
                
                val env = arrayOf(
                    "HOME=$workDir",
                    "TMPDIR=$tempDir",
                    "TERM=xterm-256color"
                )

                val client = object : TerminalSessionClient {
                    
                    override fun getTerminalCursorStyle(): Int = 0

                    override fun onTextChanged(changedSession: TerminalSession) {
                        serviceScope.launch(Dispatchers.Main) {
                            onTerminalChanged?.invoke(changedSession)
                        }
                    }

                    override fun onSessionFinished(finishedSession: TerminalSession) {
                        val exitCode = finishedSession.exitStatus
                        if (exitCode != 0) {
                            saveBinaryErrorLog(exitCode)
                        }
                        serviceScope.launch(Dispatchers.Main) {
                            isRunning = false
                            if (isRestarting) {
                                isRestarting = false
                                startServer()
                            } else {
                                onStateChanged?.invoke(false); onStateChangedGlobal?.invoke(false)
                                if (exitCode != 0 && !isStopping) onCrashed?.invoke(exitCode)
                                stopSelf()
                            }
                        }
                    }

                    // Required interface stubs — no-op implementations
                    override fun onTitleChanged(s: TerminalSession) {}
                    override fun onCopyTextToClipboard(s: TerminalSession, t: String) {}
                    override fun onPasteTextFromClipboard(s: TerminalSession) {}
                    override fun onBell(s: TerminalSession) {}
                    override fun onColorsChanged(s: TerminalSession) {}
                    override fun onTerminalCursorStateChange(b: Boolean) {}
                    override fun logError(t: String, m: String) {}
                    override fun logWarn(t: String, m: String) {}
                    override fun logInfo(t: String, m: String) {}
                    override fun logDebug(t: String, m: String) {}
                    override fun logVerbose(t: String, m: String) {}
                    override fun logStackTraceWithMessage(t: String, m: String, e: Exception) {}
                    override fun logStackTrace(t: String, e: Exception) {}
                }


                // Run on Main Thread to avoid Looper.prepare() errors
                withContext(Dispatchers.Main) {
                    session = TerminalSession(
                        binPath, workDir, arrayOf(), env, null, client
                    )

                    onStateChanged?.invoke(true); onStateChangedGlobal?.invoke(true)
                    updateNotification("Server is running")
                }
                
                startMonitoring()

            } catch (e: Exception) {
                isRunning = false
                saveCustomErrorLog("Failed during server init: ${e.message}\n${android.util.Log.getStackTraceString(e)}")
                serviceScope.launch(Dispatchers.Main) { onStateChanged?.invoke(false); onStateChangedGlobal?.invoke(false) }
                stopSelf()
            }
        }
    }

    private fun restartServer() {
        if (!isRunning) { startServer(); return }
        isStopping = true
        isRestarting = true
        isRunning = false
        monitorJob?.cancel()
        ServerPing.resetPlayerState()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onStateChanged?.invoke(false); onStateChangedGlobal?.invoke(false)
            onPlayersChanged?.invoke(emptyList())
            onPlayerCountChanged?.invoke(0, 0)
        }
        session?.write("stop\n")
        val timeoutSecs = applicationContext
            .getSharedPreferences("pumpkin_prefs", android.content.Context.MODE_PRIVATE)
            .getInt("stop_timeout_secs", 10).toLong()
        serviceScope.launch {
            delay(timeoutSecs * 1000)
            if (isRestarting) {
                isRestarting = false
                withContext(Dispatchers.Main) { startServer() }
            }
        }
    }

    private fun stopServer() {
        isStopping = true
        isRunning = false
        serverStartTime = 0L
        monitorJob?.cancel()
        ServerPing.resetPlayerState()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onStateChanged?.invoke(false); onStateChangedGlobal?.invoke(false)
            onPlayersChanged?.invoke(emptyList())
            onPlayerCountChanged?.invoke(0, 0)
        }
        val currentSession = session
        if (currentSession != null) {
            currentSession.write("stop\n")
            val timeoutSecs = applicationContext
                .getSharedPreferences("pumpkin_prefs", android.content.Context.MODE_PRIVATE)
                .getInt("stop_timeout_secs", 10).toLong()
            serviceScope.launch {
                delay(timeoutSecs * 1000)
                stopSelf()
            }
        } else {
            stopSelf()
        }
    }

    private fun prepareBinary(): String {
        val outFile  = File(filesDir, "pumpkin")
        val zipName  = if (Build.SUPPORTED_ABIS.contains("arm64-v8a")) "arm64-v8a.zip" else "armeabi-v7a.zip"
        val prefs    = getSharedPreferences("pumpkin_prefs", android.content.Context.MODE_PRIVATE)

        // Get current APK versionCode to detect updates
        val currentVersion = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
            }
        } catch (_: Exception) { -1L }

        val extractedVersion = prefs.getLong("binary_extracted_version", -1L)
        val needsExtract     = !outFile.exists() || extractedVersion != currentVersion

        if (needsExtract) {
            // Extract "pumpkin" entry from the appropriate zip in assets
            assets.open(zipName).use { assetStream ->
                val zipStream = java.util.zip.ZipInputStream(assetStream)
                var entry = zipStream.nextEntry
                while (entry != null) {
                    if (entry.name == "pumpkin") {
                        FileOutputStream(outFile).use { out -> zipStream.copyTo(out) }
                        zipStream.closeEntry()
                        break
                    }
                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
                zipStream.close()
            }
            outFile.setExecutable(true)
            // Save the version so we skip extraction next launch unless APK updates
            prefs.edit().putLong("binary_extracted_version", currentVersion).apply()
        }

        return outFile.absolutePath
    }

    private fun startMonitoring() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val ports    = ConfigReader.readPorts(filesDir)
            val editions = ConfigReader.readEditions(filesDir)
            // Pass -1 for disabled editions so ServerPing skips them
            val javaPort    = if (editions.java)    ports.javaPort    else -1
            val bedrockPort = if (editions.bedrock) ports.bedrockPort else -1
            var pingTick = 0
            while (isActive && isRunning) {
                val pid = getSessionPid()
                if (pid > 0) {
                    val cpu = readCpu(pid)
                    val ram = readRam(pid)
                    withContext(Dispatchers.Main) {
                        onStatsChanged?.invoke(cpu, ram)
                    }
                }
                pingTick++
                if (pingTick >= 3) {
                    pingTick = 0
                    try {
                        val status = ServerPing.pingBoth("127.0.0.1", javaPort, bedrockPort)
                        withContext(Dispatchers.Main) {
                            val playerList = status.players.ifEmpty {
                                List(status.playerOnline) { "" }
                            }
                            onPlayersChanged?.invoke(playerList)
                            onPlayerCountChanged?.invoke(status.playerOnline, status.playerMax)
                        }
                    } catch (_: Exception) {}
                }
                delay(2000)
            }
        }
    }

    private fun getSessionPid(): Int {
        return try {
            val f: Field = TerminalSession::class.java.getDeclaredField("mShellPid")
            f.isAccessible = true
            f.getInt(session)
        } catch (e: Exception) { -1 }
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

    private fun readRam(pid: Int): Long {
        return try {
            val stat = File("/proc/$pid/status").readLines()
            val rssLine = stat.find { it.startsWith("VmRSS:") }
            rssLine?.split("\\s+".toRegex())?.get(1)?.toLong()?.div(1024) ?: 0L
        } catch (e: Exception) { 0L }
    }
    
    private fun saveBinaryErrorLog(exitCode: Int) {
        try {
            val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val logFile = File(getExternalFilesDir(null), "pumpkin_crash.txt")
            val writer = java.io.FileWriter(logFile, true)
            val printWriter = java.io.PrintWriter(writer)
            printWriter.println("\n================ BINARY EXIT DETECTED $timeStamp ================")
            printWriter.println("Server binary stopped unexpectedly with exit code: $exitCode")
            if (exitCode == 127) {
                printWriter.println("Analysis: Binary file may be corrupted or linker dependencies (.so) missing.")
            } else if (exitCode == 126) {
                printWriter.println("Analysis: Permission denied — binary execution failed.")
            }
            printWriter.close()
        } catch (_: Exception) {}
    }

    private fun saveCustomErrorLog(message: String) {
        try {
            val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val logFile = File(getExternalFilesDir(null), "pumpkin_crash.txt")
            val writer = java.io.FileWriter(logFile, true)
            writer.write("\n================ SERVICE ERROR $timeStamp ================\n$message\n")
            writer.close()
        } catch (_: Exception) {}
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Pumpkin Server", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
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
    }
}
