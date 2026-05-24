package com.pumpkin.gui

import android.content.*
import android.os.Bundle
import android.os.IBinder
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pumpkin.gui.databinding.ActivityMainBinding
import com.pumpkin.gui.service.ServerService
import com.pumpkin.gui.util.ConfigReader
import com.pumpkin.gui.util.NetworkInfo
import com.pumpkin.gui.util.ServerPing
import kotlinx.coroutines.*
import java.io.File

class MainActivity : AppCompatActivity() {

    private var _binding: ActivityMainBinding? = null
    // Safe accessor — guards against post-onDestroy calls from callbacks
    private val binding get() = _binding

    private var serverService: ServerService? = null
    private var isBound = false
    private val scope   = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pingJob: Job? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            val svc = (b as ServerService.ServerBinder).getService()
            serverService = svc
            isBound = true
            val console = currentFragment() as? ConsoleFragment
            // TerminalView auto-replays via session attachment
        }
        override fun onServiceDisconnected(n: ComponentName?) {
            isBound = false; serverService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(_binding!!.root)

        setupNavigation()
        setupServerButtons()
        setupServiceCallbacks()
        checkBinaryOrDownload()

        // Player join/leave from ping delta → write to terminal session
        ServerPing.onPlayerJoined = { name ->
            ServerService.session?.write("[INFO] $name joined the game\r\n")
        }
        ServerPing.onPlayerLeft = { name ->
            ServerService.session?.write("[INFO] $name left the game\r\n")
        }

        // Open Console as default — ALWAYS before checking running state
        if (savedInstanceState == null) {
            navigateTo(ConsoleFragment(), "Console", binding?.navConsole)
        }

        // If service already running (activity re-opened / rotated)
        if (ServerService.isRunning) {
            updateState(true)
            startPingLoop()
            if (!isBound) {
                bindService(Intent(this, ServerService::class.java), conn, Context.BIND_AUTO_CREATE)
            }
        }

        // Handle back press — close drawer if open, else default back behaviour
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val b = _binding
                if (b != null && b.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    b.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private val COLOR_ACTIVE   = 0xFFF0F6FC.toInt()
    private val COLOR_INACTIVE = 0xFF8B949E.toInt()

    private fun setupNavigation() {
        val b = _binding ?: return
        b.btnMenu.setOnClickListener { b.drawerLayout.openDrawer(GravityCompat.START) }

        fun nav(frag: Fragment, title: String, navView: android.widget.TextView) {
            navigateTo(frag, title, navView)
            b.drawerLayout.closeDrawer(GravityCompat.START)
        }

        b.navConsole .setOnClickListener { nav(ConsoleFragment(),  "Console",  b.navConsole)  }
        b.navFiles   .setOnClickListener { nav(FilesFragment(),    "Files",    b.navFiles)    }
        b.navConfig  .setOnClickListener { nav(ConfigFragment(),   "Config",   b.navConfig)   }
        b.navWorld   .setOnClickListener { nav(WorldFragment(),    "World",    b.navWorld)    }
        b.navNetwork .setOnClickListener { nav(NetworkFragment(),  "Network",  b.navNetwork)  }

        // Show local IP in top bar — synchronous, no network call
        b.tvTopBarLocalIp.text = "Local IP: ${NetworkInfo.getLocalIp()}"
    }

    internal fun navigateTo(fragment: Fragment, title: String, activeNav: android.widget.TextView?) {
        val b = _binding ?: return
        b.tvPageTitle.text = title
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commitAllowingStateLoss()

        listOf(b.navConsole, b.navFiles, b.navConfig, b.navWorld, b.navNetwork).forEach { v ->
            v.setTextColor(if (v == activeNav) COLOR_ACTIVE else COLOR_INACTIVE)
        }
    }

    private fun currentFragment(): Fragment? =
        supportFragmentManager.findFragmentById(R.id.fragmentContainer)

    // ── Server Buttons ────────────────────────────────────────────────────────

    private fun setupServerButtons() {
        val b = _binding ?: return
        b.btnStartStop.setOnClickListener {
            if (ServerService.isRunning) showStopDialog() else startServer()
        }
        b.btnRestart.setOnClickListener {
            if (!ServerService.isRunning) return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setTitle("Restart Server?")
                .setMessage("Players will be disconnected briefly.")
                .setPositiveButton("Restart") { _, _ ->
                    startService(Intent(this, ServerService::class.java).apply { action = "RESTART" })
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun showStopDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Stop Server?")
            .setMessage("All players will be disconnected.")
            .setPositiveButton("Stop") { _, _ ->
                startService(Intent(this, ServerService::class.java).apply { action = "STOP" })
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ── Service Callbacks ─────────────────────────────────────────────────────

    private fun setupServiceCallbacks() {
        ServerService.onStateChanged = { running ->
            // Use root.post to ensure UI updates are safe even if called during transition
            binding?.root?.post {
                updateState(running)
                (currentFragment() as? ConsoleFragment)?.updateButtons(running)
                if (running) {
                    startPingLoop()
                } else {
                    stopPingLoop()
                    ServerPing.resetPlayerState()
                    (currentFragment() as? ConsoleFragment)?.resetStats()
                }
            }
        }
        ServerService.onCrashed = { exitCode ->
            binding?.root?.post {
                val b = _binding ?: return@post
                navigateTo(ConfigFragment(), "Config", b.navConfig)
                b.root.post {
                    (currentFragment() as? ConfigFragment)?.showCrashPopupIfNeeded(exitCode)
                }
            }
        }
    }

    // ── Ping Loop — reads ports from configuration.toml ───────────────────────

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            delay(4000)
            while (isActive && ServerService.isRunning) {
                try {
                    val ports    = ConfigReader.readPorts(filesDir)
                    val editions = ConfigReader.readEditions(filesDir)
                    val status   = ServerPing.pingBoth(
                        javaPort    = if (editions.java)    ports.javaPort    else -1,
                        bedrockPort = if (editions.bedrock) ports.bedrockPort else -1
                    )
                    if (status.online && isActive) {
                        // Dispatch UI updates safely to main thread
                        withContext(Dispatchers.Main) {
                            if (status.players.isNotEmpty())
                                ServerService.onPlayersChanged?.invoke(status.players)
                            
                            (currentFragment() as? ConsoleFragment)
                                ?.updatePlayerCount(status.playerOnline, status.playerMax)
                        }
                    }
                } catch (_: Exception) {}
                delay(5000)
            }
        }
    }

    private fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
        ServerService.onPlayersChanged?.invoke(emptyList())
    }

    // ── Public helpers for Fragments ─────────────────────────────────────────

    fun onStartStopClicked() {
        if (ServerService.isRunning) showStopDialog() else startServer()
    }

    fun onRestartClicked() {
        if (!ServerService.isRunning) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Restart Server?")
            .setMessage("Players will be disconnected briefly.")
            .setPositiveButton("Restart") { _, _ ->
                startService(Intent(this, ServerService::class.java).apply { action = "RESTART" })
            }
            .setNegativeButton("Cancel", null).show()
    }

    fun sendServerCommand(cmd: String) { serverService?.sendCommand(cmd) }

    // ── State UI ──────────────────────────────────────────────────────────────

    private fun updateState(running: Boolean) {
        val b = _binding ?: return  // guard against post-destroy calls
        b.btnStartStop.text = if (running) "⏹  Stop Server" else "▶  Start Server"
        b.btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (running) 0xFFB62324.toInt() else 0xFF238636.toInt()
        )
        b.tvStatus.text = if (running) "● RUNNING" else "● STOPPED"
        b.tvStatus.setTextColor(
            if (running) 0xFF50FA7B.toInt() else 0xFFFF5555.toInt()
        )
    }

    // ── Binary Extraction ─────────────────────────────────────────────────────

    private fun checkBinaryOrDownload() {
        val dest   = File(filesDir, "pumpkin")
        val prefs  = getSharedPreferences("pumpkin_prefs", MODE_PRIVATE)
        val stored = prefs.getString("extracted_abi", null)
        val primaryAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

        val (abi, rawRes) = when {
            primaryAbi.contains("arm64") -> "arm64" to R.raw.arm64
            primaryAbi.contains("arm")   -> "arm32" to R.raw.arm32
            else                         -> null to null
        }

        if (abi == null || rawRes == null) {
            android.util.Log.e("MainActivity", "Unsupported ABI: $primaryAbi")
            return
        }

        if (!dest.exists() || stored != abi) {
            try {
                resources.openRawResource(rawRes).use { i ->
                    dest.outputStream().use { o -> i.copyTo(o) }
                }
                dest.setExecutable(true, false)
                prefs.edit().putString("extracted_abi", abi).apply()
                android.util.Log.i("MainActivity", "Binary extracted ($abi)")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Extract failed: ${e.message}")
            }
        }
    }

    private fun startServer() {
        val intent = Intent(this, ServerService::class.java).apply { action = "START" }
        startService(intent)
        if (!isBound) bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    override fun onStart() {
        super.onStart()
        setupServiceCallbacks() // Re-register callbacks when returning to app
        if (ServerService.isRunning && !isBound) {
            bindService(Intent(this, ServerService::class.java), conn, Context.BIND_AUTO_CREATE)
        }
        // Restore status indicator in case it was lost
        updateState(ServerService.isRunning)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) { unbindService(conn); isBound = false }
        // Null out callbacks to prevent leaks/crashes when activity is in background
        ServerService.onStateChanged = null
        ServerService.onCrashed = null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        _binding = null
        // Do NOT null out ServerService callbacks — service may still be running
    }
}
