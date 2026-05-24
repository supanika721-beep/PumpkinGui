package com.pumpkin.gui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import com.pumpkin.gui.databinding.ActivityMainBinding
import com.pumpkin.gui.service.ServerService
import com.pumpkin.gui.util.NetworkInfo
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!

    private val consoleFragment  by lazy { ConsoleFragment() }
    private val configFragment   by lazy { ConfigFragment() }
    private val settingsFragment by lazy { SettingsFragment() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // PASANG LOGGER DISINI (Sebelum super.onCreate agar mencakup semua bug UI/Siklus Hidup)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            saveCrashLog(throwable)
        }

        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigation()
        
        if (savedInstanceState == null) {
            navigateTo(consoleFragment, "Console", binding.navConsole)
            // Auto-start server if enabled in settings
            val prefs = getSharedPreferences("pumpkin_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("auto_start_server", false) && !ServerService.isRunning) {
                startService(Intent(this, ServerService::class.java))
            }
        }

        // Handle server crash: navigate to Config tab and show crash popup
        ServerService.onCrashed = { exitCode ->
            runOnUiThread {
                navigateTo(configFragment, "Config", binding.navConfig)
                // navigateTo uses commitAllowingStateLoss which is async —
                // wait for the fragment to be fully attached before showing popup
                binding.root.post {
                    configFragment.showCrashPopupIfNeeded(exitCode)
                }
            }
        }

        // Status dot: uses onStateChangedGlobal to avoid conflict with ConsoleFragment
        ServerService.onStateChangedGlobal = { running ->
            runOnUiThread { updateStatusDot(running) }
        }
        updateStatusDot(ServerService.isRunning)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupNavigation() {
        binding.btnMenu.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }

        fun nav(frag: Fragment, title: String, navView: android.widget.TextView) {
            navigateTo(frag, title, navView)
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        binding.navConsole.setOnClickListener   { nav(consoleFragment,  "Console",  binding.navConsole) }
        binding.navFiles.setOnClickListener     { nav(FilesFragment(),  "Files",    binding.navFiles) }
        binding.navConfig.setOnClickListener    { nav(configFragment,   "Config",   binding.navConfig) }
        binding.navWorld.setOnClickListener     { nav(WorldFragment(),  "World",    binding.navWorld) }
        binding.navNetwork.setOnClickListener   { nav(NetworkFragment(),"Network",  binding.navNetwork) }
        binding.navSettings.setOnClickListener  { nav(settingsFragment, "Settings", binding.navSettings) }

        // Mencegah NetworkOnMainThreadException potensial
        Thread {
            try {
                val ipText = "Local IP: ${NetworkInfo.getLocalIp()}"
                runOnUiThread { binding.tvTopBarLocalIp.text = ipText }
            } catch (_: Exception) {}
        }.start()
    } // <--- Tanda kurung kurawal ini tadi hilang/salah letak

    private fun navigateTo(fragment: Fragment, title: String, activeNav: android.widget.TextView?) {
        binding.tvPageTitle.text = title
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commitAllowingStateLoss()

        val COLOR_ACTIVE = 0xFFF0F6FC.toInt()
        val COLOR_INACTIVE = 0xFF8B949E.toInt()

        listOf(binding.navConsole, binding.navFiles, binding.navConfig,
               binding.navWorld, binding.navNetwork, binding.navSettings).forEach { v ->
            v.setTextColor(if (v == activeNav) COLOR_ACTIVE else COLOR_INACTIVE)
        }
    }

    // FUNGSI UNTUK MENULIS FILE LOG DAN TOAST
    private fun saveCrashLog(throwable: Throwable) {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            
            // Lokasi penyimpanan file log: Android/data/com.pumpkin.gui/files/pumpkin_crash.txt
            val logDir = getExternalFilesDir(null)
            val logFile = File(logDir, "pumpkin_crash.txt")
            
            val writer = FileWriter(logFile, true)
            val printWriter = PrintWriter(writer)
            
            printWriter.println("\n================ CRASH LOG $timeStamp ================")
            throwable.printStackTrace(printWriter)
            printWriter.close()
            
            // Tampilkan Toast di Main Thread sebelum aplikasi mati total
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(
                    applicationContext, 
                    "CRASH DETECTED! Log saved to: ${logFile.absolutePath}", 
                    Toast.LENGTH_LONG
                ).show()
            }
            
            // Beri jeda 3 detik agar Toast sempat terbaca sebelum aplikasi keluar
            Thread.sleep(3000)
            
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // Selesaikan proses penutupan aplikasi bawaan sistem android
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
    }

    private fun updateStatusDot(running: Boolean) {
        binding.statusDot.setBackgroundColor(
            if (running) 0xFF238636.toInt() else 0xFF484F58.toInt()
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        ServerService.onCrashed           = null
        ServerService.onStateChangedGlobal = null
        _binding = null
    }
}
