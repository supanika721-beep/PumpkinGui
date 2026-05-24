package com.pumpkin.gui

import android.content.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.pumpkin.gui.databinding.FragmentConsoleBinding
import com.pumpkin.gui.service.ServerService
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient
import org.json.JSONArray

class ConsoleFragment : Fragment() {

    private var _binding: FragmentConsoleBinding? = null
    private val binding get() = _binding!!

    // ── Command history ───────────────────────────────────────────────────────
    private val commandHistory = mutableListOf<String>()
    private var historyIndex   = -1
    private var draftCommand   = ""

    // ── Pinned commands ───────────────────────────────────────────────────────
    private val PREFS_KEY          = "pinned_commands"
    private val PREFS_FONT_SIZE    = "console_font_size"
    private val PREFS_NAME         = "pumpkin_prefs"
    private val pinnedCommands     = mutableListOf<String>()

    private val importPinnedLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK)
            result.data?.data?.let { uri -> doImportPinned(uri) }
    }

    // ── Uptime ────────────────────────────────────────────────────────────────
    private val uptimeHandler  = Handler(Looper.getMainLooper())
    private var serverStartMs  = 0L
    private val uptimeTick = object : Runnable {
        override fun run() {
            if (_binding == null || !ServerService.isRunning) return
            val secs  = (System.currentTimeMillis() - serverStartMs) / 1000
            val h     = secs / 3600
            val m     = (secs % 3600) / 60
            val s     = secs % 60
            binding.tvUptime.text = if (h > 0)
                "%d:%02d:%02d".format(h, m, s)
            else
                "%02d:%02d".format(m, s)
            uptimeHandler.postDelayed(this, 1000)
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────
    private var searchMatches  = listOf<Int>()   // line indices of matches
    private var searchCursor   = 0
    private var currentFilter  = "ALL"

    // ── TerminalViewClient ────────────────────────────────────────────────────
    private val tvClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(e: MotionEvent) {}
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput()      = false
        override fun shouldUseCtrlSpaceWorkaround()     = false
        override fun isTerminalViewSelected()           = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
        override fun onLongPress(event: MotionEvent)    = false
        override fun onCodePoint(cp: Int, ctrl: Boolean, session: TerminalSession) = false
        override fun readControlKey() = false
        override fun readAltKey()     = false
        override fun readShiftKey()   = false
        override fun readFnKey()      = false
        override fun onEmulatorSet()  {}
        override fun logError(tag: String, message: String)   {}
        override fun logWarn(tag: String, message: String)    {}
        override fun logInfo(tag: String, message: String)    {}
        override fun logDebug(tag: String, message: String)   {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
        override fun logStackTrace(tag: String, e: Exception) {}
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentConsoleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadPinnedCommands()
        setupTerminal()
        setupListeners()
        setupSearch()
        setupFilter()
        registerCallbacks()
        updateUIState(ServerService.isRunning)
        if (ServerService.isRunning) startUptimeTicker()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        uptimeHandler.removeCallbacks(uptimeTick)
        ServerService.onTerminalChanged    = null
        ServerService.onStateChanged       = null
        ServerService.onStatsChanged       = null
        ServerService.onPlayersChanged     = null
        ServerService.onPlayerCountChanged = null
        ServerService.onFontSizeChanged    = null
        _binding = null
    }

    // ── Terminal ──────────────────────────────────────────────────────────────

    private fun setupTerminal() {
        binding.terminalView.setTerminalViewClient(tvClient)
        val savedSize = requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(PREFS_FONT_SIZE, 24)
        binding.terminalView.setTextSize(savedSize)
        ServerService.session?.let { attachSessionWhenReady(it) }
    }

    private fun attachSessionWhenReady(session: TerminalSession) {
        val tv = _binding?.terminalView ?: return
        if (tv.width > 0 && tv.height > 0) {
            if (tv.currentSession == null || tv.currentSession != session) tv.attachSession(session)
            tv.invalidate()
        } else {
            tv.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    tv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    if (_binding != null && tv.width > 0) {
                        if (tv.currentSession == null || tv.currentSession != session) tv.attachSession(session)
                        tv.invalidate()
                    }
                }
            })
        }
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    private fun setupListeners() {
        binding.btnStartStop.setOnClickListener {
            val intent = Intent(requireContext(), ServerService::class.java)
            intent.action = if (ServerService.isRunning) "STOP" else "START"
            requireContext().startService(intent)
        }

        binding.btnRestart.setOnClickListener {
            requireContext().startService(
                Intent(requireContext(), ServerService::class.java).apply { action = "RESTART" }
            )
        }

        binding.btnSend.setOnClickListener { sendCommand() }

        binding.etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendCommand(); true } else false
        }

        binding.btnHistoryUp.setOnClickListener   { navigateHistory(-1) }
        binding.btnHistoryDown.setOnClickListener { navigateHistory(1)  }

        binding.etCommand.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP   -> { navigateHistory(-1); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { navigateHistory(1);  true }
                else -> false
            }
        }

        // Pin current command
        binding.btnPinCommand.setOnClickListener {
            val cmd = binding.etCommand.text.toString().trim()
            if (cmd.isBlank()) {
                showPinnedCommandsDialog()
            } else {
                pinCommand(cmd)
            }
        }

        binding.btnPinCommand.setOnLongClickListener {
            showPinnedCommandsDialog()
            true
        }

        binding.btnCopyLog.setOnClickListener {
            val session  = ServerService.session
            val emulator = session?.emulator
            if (session != null && emulator?.screen != null) {
                val text = emulator.screen.transcriptText
                if (!text.isNullOrBlank()) {
                    val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("Pumpkin Log", text))
                    Toast.makeText(requireContext(), "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Log is empty", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(requireContext(), "Server is not running or binary not ready", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnToggleSearch.setOnClickListener { toggleSearch() }
        binding.btnToggleFilter.setOnClickListener { toggleFilter() }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private fun setupSearch() {
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
        }
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { runSearch() }
        })
        binding.btnSearchNext.setOnClickListener  { stepSearch(+1) }
        binding.btnSearchPrev.setOnClickListener  { stepSearch(-1) }
        binding.btnSearchClose.setOnClickListener { closeSearch() }
    }

    private fun toggleSearch() {
        val visible = binding.searchBar.visibility == View.VISIBLE
        if (visible) {
            closeSearch()
        } else {
            binding.searchBar.visibility = View.VISIBLE
            binding.etSearch.requestFocus()
        }
    }

    private fun closeSearch() {
        binding.searchBar.visibility = View.GONE
        binding.etSearch.text?.clear()
        binding.tvSearchMatches.text = ""
        searchMatches = emptyList()
        searchCursor  = 0
        updateFilteredView()   // re-render without highlights
    }

    private fun runSearch() {
        val query = binding.etSearch.text?.toString()?.trim() ?: ""
        if (query.isEmpty()) {
            searchMatches = emptyList()
            binding.tvSearchMatches.text = ""
            updateFilteredView()
            return
        }
        val lines = getLogLines()
        searchMatches = lines.indices.filter { lines[it].contains(query, ignoreCase = true) }
        searchCursor  = if (searchMatches.isNotEmpty()) searchMatches.lastIndex else 0
        binding.tvSearchMatches.text = if (searchMatches.isEmpty()) "No match" else
            "${searchCursor + 1}/${searchMatches.size}"
        showFilteredWithHighlight(lines, query)
    }

    private fun stepSearch(dir: Int) {
        if (searchMatches.isEmpty()) return
        searchCursor = (searchCursor + dir + searchMatches.size) % searchMatches.size
        binding.tvSearchMatches.text = "${searchCursor + 1}/${searchMatches.size}"
        val query = binding.etSearch.text?.toString() ?: ""
        showFilteredWithHighlight(getLogLines(), query)
    }

    private fun showFilteredWithHighlight(lines: List<String>, query: String) {
        if (_binding == null) return
        val matchIdx = if (searchMatches.isNotEmpty()) searchMatches[searchCursor] else -1
        val sb = SpannableString(lines.joinToString("\n"))
        // Highlight all matches
        var offset = 0
        lines.forEachIndexed { idx, line ->
            var start = line.indexOf(query, ignoreCase = true)
            while (start >= 0) {
                val abs = offset + start
                val bg = if (idx == matchIdx) 0xFFD29922.toInt() else 0x664444FF
                sb.setSpan(BackgroundColorSpan(bg), abs, abs + query.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(0xFF000000.toInt()), abs, abs + query.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                start = line.indexOf(query, start + 1, ignoreCase = true)
            }
            offset += line.length + 1
        }
        binding.tvFilteredLog.text = sb
        binding.terminalView.visibility   = View.GONE
        binding.filteredLogScroll.visibility = View.VISIBLE
        // Scroll to current match
        if (matchIdx >= 0) {
            val approxY = (matchIdx.toFloat() / lines.size * binding.tvFilteredLog.height).toInt()
            binding.filteredLogScroll.post { binding.filteredLogScroll.scrollTo(0, approxY) }
        }
    }

    // ── Filter ────────────────────────────────────────────────────────────────

    private fun setupFilter() {
        val chips = listOf(
            binding.chipAll   to "ALL",
            binding.chipInfo  to "INFO",
            binding.chipWarn  to "WARN",
            binding.chipError to "ERROR"
        )
        chips.forEach { (chip, level) ->
            chip.setOnClickListener {
                currentFilter = level
                chips.forEach { (c, _) ->
                    c.setTextColor(0xFF8B949E.toInt())
                    c.setBackgroundColor(0xFF21262D.toInt())
                }
                chip.setTextColor(0xFFFFFFFF.toInt())
                chip.setBackgroundColor(0xFF1F6FEB.toInt())
                updateFilteredView()
            }
        }
    }

    private fun toggleFilter() {
        val visible = binding.filterBar.visibility == View.VISIBLE
        binding.filterBar.visibility = if (visible) View.GONE else View.VISIBLE
        if (visible) {
            currentFilter = "ALL"
            updateFilteredView()
        }
    }

    private fun updateFilteredView() {
        if (_binding == null) return
        val query = binding.etSearch.text?.toString()?.trim() ?: ""
        if (currentFilter == "ALL" && query.isEmpty()) {
            binding.terminalView.visibility      = View.VISIBLE
            binding.filteredLogScroll.visibility = View.GONE
            return
        }
        val lines = getLogLines().let { all ->
            if (currentFilter == "ALL") all
            else all.filter { it.contains(currentFilter, ignoreCase = true) }
        }
        if (query.isNotEmpty()) {
            showFilteredWithHighlight(lines, query)
        } else {
            binding.tvFilteredLog.text = lines.joinToString("\n")
            binding.terminalView.visibility      = View.GONE
            binding.filteredLogScroll.visibility = View.VISIBLE
        }
    }

    private fun getLogLines(): List<String> {
        val text = ServerService.session?.emulator?.screen?.transcriptText ?: return emptyList()
        return text.lines().filter { it.isNotBlank() }
    }

    // ── Pinned Commands ───────────────────────────────────────────────────────

    private fun loadPinnedCommands() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(PREFS_KEY, emptySet()) ?: emptySet()
        pinnedCommands.clear()
        pinnedCommands.addAll(saved.sorted())
        renderPinnedChips()
    }

    private fun savePinnedCommands() {
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(PREFS_KEY, pinnedCommands.toSet()).apply()
    }

    private fun pinCommand(cmd: String) {
        if (pinnedCommands.contains(cmd)) {
            Toast.makeText(requireContext(), "Already pinned", Toast.LENGTH_SHORT).show()
            return
        }
        pinnedCommands.add(cmd)
        savePinnedCommands()
        renderPinnedChips()
        Toast.makeText(requireContext(), "Pinned: $cmd", Toast.LENGTH_SHORT).show()
    }

    private fun renderPinnedChips() {
        val container = _binding?.pinnedContainer ?: return
        container.removeAllViews()
        if (pinnedCommands.isEmpty()) {
            binding.pinnedScroll.visibility = View.GONE
            return
        }
        binding.pinnedScroll.visibility = View.VISIBLE
        pinnedCommands.forEach { cmd ->
            TextView(requireContext()).apply {
                text = cmd
                textSize = 11f
                setTextColor(0xFFE6EDF3.toInt())
                setBackgroundColor(0xFF21262D.toInt())
                setPadding(22, 0, 22, 0)
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).also { it.setMargins(0, 4, 8, 4) }
                setOnClickListener {
                    ServerService.session?.write("$cmd\n")
                    Toast.makeText(requireContext(), "▶ $cmd", Toast.LENGTH_SHORT).show()
                }
                setOnLongClickListener {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Remove pinned command?")
                        .setMessage("\"$cmd\"")
                        .setPositiveButton("Remove") { _, _ ->
                            pinnedCommands.remove(cmd)
                            savePinnedCommands()
                            renderPinnedChips()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
            }.also { container.addView(it) }
        }
    }

    private fun showPinnedCommandsDialog() {
        if (pinnedCommands.isEmpty()) {
            Toast.makeText(requireContext(), "Type a command then tap ★ to pin it", Toast.LENGTH_SHORT).show()
            return
        }
        val items = pinnedCommands.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Pinned Commands")
            .setItems(items) { _, idx ->
                ServerService.session?.write("${pinnedCommands[idx]}\n")
            }
            .setPositiveButton("Export") { _, _ -> exportPinned() }
            .setNeutralButton("Import") { _, _ ->
                importPinnedLauncher.launch(
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                    }
                )
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun exportPinned() {
        if (pinnedCommands.isEmpty()) {
            Toast.makeText(requireContext(), "No pinned commands to export", Toast.LENGTH_SHORT).show()
            return
        }
        val json = JSONArray(pinnedCommands).toString(2)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, json)
            putExtra(Intent.EXTRA_SUBJECT, "Pumpkin Pinned Commands")
        }
        startActivity(Intent.createChooser(intent, "Export Pinned Commands"))
    }

    private fun doImportPinned(uri: android.net.Uri) {
        try {
            val text = requireContext().contentResolver.openInputStream(uri)
                ?.bufferedReader()?.readText() ?: return
            val arr  = JSONArray(text)
            var added = 0
            for (i in 0 until arr.length()) {
                val cmd = arr.getString(i).trim()
                if (cmd.isNotBlank() && !pinnedCommands.contains(cmd)) {
                    pinnedCommands.add(cmd); added++
                }
            }
            savePinnedCommands(); renderPinnedChips()
            Toast.makeText(requireContext(), "Imported $added command(s)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Uptime ────────────────────────────────────────────────────────────────

    private fun startUptimeTicker() {
        serverStartMs = ServerService.serverStartTime
        uptimeHandler.removeCallbacks(uptimeTick)
        uptimeHandler.post(uptimeTick)
    }

    private fun stopUptimeTicker() {
        uptimeHandler.removeCallbacks(uptimeTick)
        _binding?.tvUptime?.text = "—"
    }

    // ── History ───────────────────────────────────────────────────────────────

    private fun navigateHistory(direction: Int) {
        if (commandHistory.isEmpty()) return
        val et = _binding?.etCommand ?: return
        if (direction < 0) {
            if (historyIndex == -1) { draftCommand = et.text.toString(); historyIndex = commandHistory.lastIndex }
            else if (historyIndex > 0) historyIndex--
        } else {
            if (historyIndex == -1) return
            if (historyIndex < commandHistory.lastIndex) {
                historyIndex++
            } else {
                historyIndex = -1
                et.setText(draftCommand)
                et.setSelection(et.text.length)
                return
            }
        }
        et.setText(commandHistory[historyIndex])
        et.setSelection(et.text.length)
    }

    private fun sendCommand() {
        val cmd = binding.etCommand.text.toString().trim()
        if (cmd.isNotBlank()) {
            ServerService.session?.write("$cmd\n")
            if (commandHistory.isEmpty() || commandHistory.last() != cmd) {
                commandHistory.add(cmd)
                if (commandHistory.size > 50) commandHistory.removeAt(0)
            }
            historyIndex = -1
            draftCommand = ""
            binding.etCommand.text.clear()
        }
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────

    private fun registerCallbacks() {
        ServerService.onTerminalChanged = { session ->
            activity?.runOnUiThread {
                if (isAdded && _binding != null) attachSessionWhenReady(session)
            }
        }

        ServerService.onStateChanged = { running ->
            activity?.runOnUiThread {
                if (isAdded && _binding != null) {
                    updateUIState(running)
                    if (running) {
                        ServerService.session?.let { attachSessionWhenReady(it) }
                        startUptimeTicker()
                    } else {
                        stopUptimeTicker()
                    }
                }
            }
        }

        ServerService.onStatsChanged = { cpu, ram ->
            activity?.runOnUiThread {
                if (isAdded && _binding != null) {
                    binding.tvCpu.text      = "${"%.1f".format(cpu)}%"
                    binding.progressCpu.progress = cpu.toInt()
                    binding.tvRam.text      = "$ram MB"
                    binding.progressRam.progress = (ram / 10).toInt().coerceIn(0, 100)
                }
            }
        }

        ServerService.onPlayerCountChanged = { online, max ->
            activity?.runOnUiThread {
                if (isAdded && _binding != null)
                    binding.tvPlayerCount.text = if (max > 0) "$online/$max" else "$online"
            }
        }

        ServerService.onFontSizeChanged = { size ->
            activity?.runOnUiThread {
                if (isAdded && _binding != null)
                    binding.terminalView.setTextSize(size)
            }
        }
    }

    // ── UI State ──────────────────────────────────────────────────────────────

    private fun updateUIState(running: Boolean) {
        binding.btnStartStop.text = if (running) "STOP SERVER" else "START SERVER"
        binding.btnStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (running) ContextCompat.getColor(requireContext(), R.color.status_stopped)
            else         ContextCompat.getColor(requireContext(), R.color.status_running)
        )
        if (!running) {
            binding.tvCpu.text         = "0%"
            binding.progressCpu.progress = 0
            binding.tvRam.text         = "0 MB"
            binding.progressRam.progress = 0
            binding.tvPlayerCount.text = "0/0"
        }
    }
}
