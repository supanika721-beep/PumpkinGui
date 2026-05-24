package com.pumpkin.gui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.pumpkin.gui.service.ServerService
import com.pumpkin.gui.ui.PlayerAdapter

class ConsoleFragment : Fragment() {

    // All views null-able, set in onViewCreated, cleared in onDestroyView
    private var terminalView:  TerminalView? = null
    private var btnStartStop:  Button?       = null
    private var btnRestart:    Button?       = null
    private var btnCopyLog:    TextView?     = null
    private var etCommand:     EditText?     = null
    private var btnSend:       Button?       = null
    private var tvCpu:         TextView?     = null
    private var tvRam:         TextView?     = null
    private var tvPlayerCount: TextView?     = null
    private var progressCpu:   ProgressBar?  = null
    private var progressRam:   ProgressBar?  = null
    private var rvPlayers:     RecyclerView? = null

    private val playerAdapter = PlayerAdapter()

    // Minimal TerminalViewClient — all methods required by the interface
    private val tvClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(e: MotionEvent) {}
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldBackButtonBeSentToTerminal(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onEmulatorSet() {}
        override fun logError(tag: String, message: String) {}
        override fun logWarn(tag: String, message: String) {}
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
        override fun logStackTrace(tag: String, e: Exception) {}
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_console, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        terminalView  = view.findViewById(R.id.terminalView)
        btnStartStop  = view.findViewById(R.id.btnStartStop)
        btnRestart    = view.findViewById(R.id.btnRestart)
        btnCopyLog    = view.findViewById(R.id.btnCopyLog)
        etCommand     = view.findViewById(R.id.etCommand)
        btnSend       = view.findViewById(R.id.btnSend)
        tvCpu         = view.findViewById(R.id.tvCpu)
        tvRam         = view.findViewById(R.id.tvRam)
        tvPlayerCount = view.findViewById(R.id.tvPlayerCount)
        progressCpu   = view.findViewById(R.id.progressCpu)
        progressRam   = view.findViewById(R.id.progressRam)
        rvPlayers     = view.findViewById(R.id.rvPlayers)

        rvPlayers?.apply {
            adapter       = playerAdapter
            layoutManager = LinearLayoutManager(requireContext())
            itemAnimator  = null
        }

        // Setup TerminalView
        terminalView?.setTerminalViewClient(tvClient)
        terminalView?.setTextSize(14)

        // If server already running (e.g. navigating back to Console tab),
        // attach to the existing session immediately
        ServerService.session?.let { session ->
            terminalView?.attachSession(session)
        }

        btnStartStop?.setOnClickListener { (activity as? MainActivity)?.onStartStopClicked() }
        btnRestart?.setOnClickListener   { (activity as? MainActivity)?.onRestartClicked() }
        btnSend?.setOnClickListener      { sendCommand() }
        etCommand?.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) { sendCommand(); true } else false
        }

        btnCopyLog?.setOnClickListener {
        val session = ServerService.session
        if (session != null) {
            val rows = session.emulator?.mRows ?: 0
            val cols = session.emulator?.mColumns ?: 0
            val text = session.emulator?.screen?.getSelectedText(0, 0, rows, cols) ?: ""
            val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("pumpkin_log", text))
            Toast.makeText(requireContext(), "Logs copied", Toast.LENGTH_SHORT).show()
        }
    }

        registerCallbacks()
        updateButtons(ServerService.isRunning)
    }

    // ── Callbacks from ServerService ──────────────────────────────────────────

    private fun registerCallbacks() {
        // Called when TerminalSession has new output
        ServerService.onTerminalChanged = { session ->
            if (isAdded) {
                val tv = terminalView
                if (tv != null) {
                    if (tv.currentSession == null) {
                        tv.attachSession(session)
                    }
                    tv.post { tv.invalidate() }
                }
            }
        }

        ServerService.onStateChanged = { running ->
            if (isAdded) {
                updateButtons(running)
                if (!running) resetStats()
            }
        }

        ServerService.onStatsChanged = { cpu, ram ->
            if (isAdded) {
                val s = if (cpu < 10f) "%.2f".format(java.util.Locale.US, cpu)
                        else           "%.1f".format(java.util.Locale.US, cpu)
                tvCpu?.text           = "CPU:  $s%"
                tvRam?.text           = "RAM:  $ram MB"
                progressCpu?.progress = cpu.toInt().coerceIn(0, 100)
                progressRam?.progress = (ram / 10L).toInt().coerceIn(0, 100)
            }
        }

        ServerService.onPlayersChanged = { players ->
            if (isAdded) {
                playerAdapter.updatePlayers(players)
                tvPlayerCount?.text = "Players Online: ${players.size}"
            }
        }
    }

    // ── Public API called by MainActivity ─────────────────────────────────────

    fun updateButtons(running: Boolean) {
        btnStartStop?.text = if (running) "⏹ Stop" else "▶ Start"
        btnStartStop?.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                if (running) 0xFFB62324.toInt() else 0xFF238636.toInt()
            )
        btnRestart?.isEnabled = running
    }

    fun updatePlayerCount(online: Int, max: Int) {
        tvPlayerCount?.text = "Players Online: $online / $max"
    }

    fun resetStats() {
        tvCpu?.text = "CPU:  0.00%"; tvRam?.text = "RAM:  0 MB"
        progressCpu?.progress = 0;   progressRam?.progress = 0
        tvPlayerCount?.text = "Players Online: 0"
        playerAdapter.updatePlayers(emptyList())
    }

    private fun sendCommand() {
        val cmd = etCommand?.text?.toString()?.trim() ?: return
        if (cmd.isEmpty()) return
        if (!ServerService.isRunning) {
            Toast.makeText(requireContext(), "Server is not running", Toast.LENGTH_SHORT).show()
            return
        }
        (activity as? MainActivity)?.sendServerCommand(cmd)
        etCommand?.text?.clear()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Null all callbacks that might reference this fragment or its views
        ServerService.onTerminalChanged = null
        ServerService.onStateChanged    = null
        ServerService.onStatsChanged    = null
        ServerService.onPlayersChanged  = null
        
        terminalView  = null; btnStartStop  = null; btnRestart    = null
        btnCopyLog    = null; etCommand     = null; btnSend       = null
        tvCpu         = null; tvRam         = null; tvPlayerCount = null
        progressCpu   = null; progressRam   = null; rvPlayers     = null
    }
}
