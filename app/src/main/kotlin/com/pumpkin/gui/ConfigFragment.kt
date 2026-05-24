package com.pumpkin.gui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import java.io.File

class ConfigFragment : Fragment() {

    private var scrollContainer: LinearLayout? = null
    private var btnSave:         TextView?     = null
    private var btnReload:       TextView?     = null
    private var btnImport:       TextView?     = null
    private var tvStatus:        TextView?     = null

    private val configFile      get() = File(requireContext().filesDir, "config/configuration.toml")
    private val crashReportsDir get() = File(requireContext().filesDir, "crash-reports")

    private val entries = mutableListOf<ConfigEntry>()
    private val widgets = mutableMapOf<Int, View>()

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK)
            result.data?.data?.let { uri -> handleConfigImport(uri) }
    }

    sealed class ConfigEntry {
        data class Comment(val text: String)                     : ConfigEntry()
        data class Section(val name: String)                     : ConfigEntry()
        data class BoolField(val key: String, val value: Boolean): ConfigEntry()
        data class IntField(val key: String, val value: Long)    : ConfigEntry()
        data class FloatField(val key: String, val value: Double): ConfigEntry()
        data class StrField(val key: String, val value: String)  : ConfigEntry()
        data class Raw(val line: String)                         : ConfigEntry()
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_config, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scrollContainer = view.findViewById(R.id.configContainer)
        btnSave         = view.findViewById(R.id.btnSaveConfig)
        btnReload       = view.findViewById(R.id.btnReloadConfig)
        btnImport       = view.findViewById(R.id.btnImportConfig)
        tvStatus        = view.findViewById(R.id.tvConfigStatus)

        btnSave?.setOnClickListener   { saveConfig() }
        btnReload?.setOnClickListener { loadConfig() }
        btnImport?.setOnClickListener {
            importLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/octet-stream", "text/plain", "*/*"))
            })
        }
        loadConfig()
    }

    // ── Crash popup ──────────────────────────────────────────────────────────
    // Called by MainActivity when server exits with non-zero exit code.
    // Finds the newest .txt in crash-reports/ and shows it in a dialog.

    fun showCrashPopupIfNeeded(exitCode: Int) {
        if (!isAdded || exitCode == 0) return
        val latestCrash = crashReportsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() } ?: return
        val content = try { latestCrash.readText() } catch (_: Exception) { return }
        val ctx = requireContext()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF161B22.toInt())
        }

        // Red header
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFB62324.toInt())
            setPadding(40, 32, 40, 24)
            TextView(ctx).apply {
                text = "💥  Server Crashed  (exit: $exitCode)"
                textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
                typeface = Typeface.DEFAULT_BOLD
            }.also { addView(it) }
            TextView(ctx).apply {
                text = latestCrash.name; textSize = 10f
                setTextColor(0xFFFFAAAA.toInt()); setPadding(0, 6, 0, 0)
            }.also { addView(it) }
        }.also { root.addView(it) }

        // Crash content (read-only monospace)
        val crashText = EditText(ctx).apply {
            setText(content); isFocusable = false; isClickable = false
            typeface = Typeface.MONOSPACE; textSize = 11f
            setTextColor(0xFFFF5555.toInt()); setBackgroundColor(0xFF0D1117.toInt())
            setPadding(24, 20, 24, 20); gravity = Gravity.TOP or Gravity.START
        }
        ScrollView(ctx).apply {
            addView(crashText)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }.also { root.addView(it) }

        // Action buttons
        val dialog = AlertDialog.Builder(ctx).setView(root).create()

        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF21262D.toInt()); setPadding(16, 10, 16, 10)

            fun btn(label: String, bg: Int, action: () -> Unit) = TextView(ctx).apply {
                text = label; gravity = Gravity.CENTER; textSize = 12f
                setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(bg)
                setPadding(0, 14, 0, 14)
                layoutParams = LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .also { lp -> lp.setMargins(4, 0, 4, 0) }
                setOnClickListener { action() }
            }

            addView(btn("Clear Crash Reports", 0xFF30363D.toInt()) {
                dialog.dismiss(); clearCrashReports()
            })
            addView(btn("Dismiss", 0xFF21262D.toInt()) { dialog.dismiss() })
        }.also { root.addView(it) }

        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (ctx.resources.displayMetrics.widthPixels * 0.92).toInt(),
                (ctx.resources.displayMetrics.heightPixels * 0.72).toInt()
            )
        }
    }

    // ── Clear crash reports ───────────────────────────────────────────────────

    fun clearCrashReports() {
        if (!isAdded) return
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("Clear crash reports?")
            .setMessage("All .txt files in crash-reports/ will be deleted.")
            .setPositiveButton("Clear") { _, _ ->
                var count = 0
                crashReportsDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".txt") }
                    ?.forEach { if (it.delete()) count++ }
                Toast.makeText(ctx, "Deleted $count crash report(s)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Parse ─────────────────────────────────────────────────────────────────

    private fun loadConfig() {
        val container = scrollContainer ?: return
        if (!isAdded) return
        entries.clear(); widgets.clear(); container.removeAllViews()

        if (!configFile.exists()) {
            tvStatus?.text = "Config not found. Start server once to generate it."
            tvStatus?.visibility = View.VISIBLE
            setSaveEnabled(false); return
        }
        tvStatus?.text = "Loading…"; tvStatus?.visibility = View.VISIBLE
        setSaveEnabled(false)

        Thread {
            try {
                val lines = configFile.readLines()
                val parsed = lines.mapIndexed { idx, line ->
                    val t = line.trim()
                    idx to when {
                        t.startsWith("#") || t.isEmpty() -> ConfigEntry.Comment(line)
                        t.startsWith("[") && t.endsWith("]") ->
                            ConfigEntry.Section(t.removeSurrounding("[", "]"))
                        t.contains("=") -> parseKV(t)
                        else -> ConfigEntry.Raw(line)
                    }
                }
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    entries.clear(); widgets.clear(); container.removeAllViews()
                    tvStatus?.visibility = View.GONE; setSaveEnabled(true)
                    var sectionShown = ""
                    parsed.forEach { (idx, entry) ->
                        entries.add(entry)
                        when (entry) {
                            is ConfigEntry.Comment -> {}
                            is ConfigEntry.Section -> {
                                if (entry.name != sectionShown) {
                                    sectionShown = entry.name
                                    addSectionHeader(container, entry.name)
                                }
                            }
                            is ConfigEntry.BoolField  -> addBoolRow(container, idx, entry)
                            is ConfigEntry.IntField   -> addIntRow(container, idx, entry)
                            is ConfigEntry.FloatField -> addFloatRow(container, idx, entry)
                            is ConfigEntry.StrField   -> addStrRow(container, idx, entry)
                            is ConfigEntry.Raw        -> addRawRow(container, idx, entry)
                        }
                    }
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    tvStatus?.text = "Error reading config: ${e.message}"
                    tvStatus?.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun setSaveEnabled(on: Boolean) {
        btnSave?.alpha       = if (on) 1f else 0.5f
        btnSave?.isClickable = on
    }

    private fun parseKV(line: String): ConfigEntry {
        val eqIdx = line.indexOf('=')
        val key   = line.substring(0, eqIdx).trim()
        val raw   = line.substring(eqIdx + 1).trim()
        return when {
            raw == "true"  -> ConfigEntry.BoolField(key, true)
            raw == "false" -> ConfigEntry.BoolField(key, false)
            raw.startsWith("\"") && raw.endsWith("\"") ->
                ConfigEntry.StrField(key, raw.removeSurrounding("\""))
            raw.contains('.') ->
                raw.toDoubleOrNull()?.let { ConfigEntry.FloatField(key, it) }
                    ?: ConfigEntry.StrField(key, raw)
            else ->
                raw.toLongOrNull()?.let { ConfigEntry.IntField(key, it) }
                    ?: ConfigEntry.StrField(key, raw)
        }
    }

    private val DIFFICULTY_KEY    = "default_difficulty"
    private val GAMEMODE_KEY      = "default_gamemode"
    private val difficultyOptions = listOf("Peaceful", "Easy", "Normal", "Hard")
    private val gamemodeOptions   = listOf("Survival", "Creative", "Adventure")

    // ── Render helpers ────────────────────────────────────────────────────────

    private fun ctx() = requireContext()

    private fun addSectionHeader(parent: LinearLayout, name: String) {
        TextView(ctx()).apply {
            text = "[ $name ]"; textSize = 11f
            setTextColor(0xFF8B949E.toInt())
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setPadding(16, 24, 16, 6); setBackgroundColor(0xFF0D1117.toInt())
        }.also { parent.addView(it) }
        View(ctx()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1).also { it.setMargins(16, 0, 16, 0) }
            setBackgroundColor(0xFF30363D.toInt())
        }.also { parent.addView(it) }
    }

    private fun row(parent: LinearLayout, key: String): LinearLayout {
        val r = LinearLayout(ctx()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12); setBackgroundColor(0xFF161B22.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        TextView(ctx()).apply {
            text = key; textSize = 12f; typeface = Typeface.MONOSPACE
            setTextColor(0xFF8BE9FD.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }.also { r.addView(it) }
        divider(parent); parent.addView(r)
        return r
    }

    private fun divider(parent: LinearLayout) = parent.addView(
        View(ctx()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(0xFF21262D.toInt())
        }
    )

    /**
     * Select all text when the field gains focus so user can immediately
     * type a new value without manually clearing the old one.
     */
    private fun EditText.withAutoSelect(): EditText {
        setOnFocusChangeListener { _, hasFocus -> if (hasFocus) post { selectAll() } }
        return this
    }

    private fun addBoolRow(parent: LinearLayout, idx: Int, e: ConfigEntry.BoolField) {
        val r = row(parent, e.key)
        SwitchCompat(ctx()).apply {
            isChecked = e.value
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFF50FA7B.toInt())
            trackTintList = android.content.res.ColorStateList.valueOf(0xFF30363D.toInt())
        }.also { sw -> r.addView(sw); widgets[idx] = sw }
    }

    private fun addIntRow(parent: LinearLayout, idx: Int, e: ConfigEntry.IntField) {
        val r = row(parent, e.key)
        EditText(ctx()).apply {
            setText(e.value.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            textSize = 12f; typeface = Typeface.MONOSPACE
            setTextColor(0xFFF1FA8C.toInt()); setBackgroundColor(0xFF0D1117.toInt())
            setPadding(8, 4, 8, 4)
            layoutParams = LinearLayout.LayoutParams(180, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.END; withAutoSelect()
        }.also { et -> r.addView(et); widgets[idx] = et }
    }

    private fun addFloatRow(parent: LinearLayout, idx: Int, e: ConfigEntry.FloatField) {
        val r = row(parent, e.key)
        EditText(ctx()).apply {
            setText(e.value.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 12f; typeface = Typeface.MONOSPACE
            setTextColor(0xFFFFB86C.toInt()); setBackgroundColor(0xFF0D1117.toInt())
            setPadding(8, 4, 8, 4)
            layoutParams = LinearLayout.LayoutParams(180, ViewGroup.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.END; withAutoSelect()
        }.also { et -> r.addView(et); widgets[idx] = et }
    }

    private fun addStrRow(parent: LinearLayout, idx: Int, e: ConfigEntry.StrField) {
        when (e.key) {
            DIFFICULTY_KEY -> { addSpinnerRow(parent, idx, e.key, e.value, difficultyOptions); return }
            GAMEMODE_KEY   -> { addSpinnerRow(parent, idx, e.key, e.value, gamemodeOptions);   return }
        }
        val r = row(parent, e.key)
        EditText(ctx()).apply {
            setText(e.value); inputType = InputType.TYPE_CLASS_TEXT
            textSize = 12f; typeface = Typeface.MONOSPACE
            setTextColor(0xFFF8F8F2.toInt()); setBackgroundColor(0xFF0D1117.toInt())
            setPadding(8, 4, 8, 4)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            withAutoSelect()
        }.also { et -> r.addView(et); widgets[idx] = et }
    }

    private fun addSpinnerRow(parent: LinearLayout, idx: Int, key: String,
                               current: String, options: List<String>) {
        val r = row(parent, key)
        Spinner(ctx()).apply {
            val adp = android.widget.ArrayAdapter(ctx(),
                android.R.layout.simple_spinner_item, options)
            adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            adapter = adp
            val sel = options.indexOfFirst { it.equals(current, ignoreCase = true) }
            if (sel >= 0) setSelection(sel)
            setBackgroundColor(0xFF21262D.toInt())
        }.also { sp -> r.addView(sp); widgets[idx] = sp }
    }

    private fun addRawRow(parent: LinearLayout, idx: Int, e: ConfigEntry.Raw) {
        val r = LinearLayout(ctx()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16, 10, 16, 10)
            setBackgroundColor(0xFF161B22.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        TextView(ctx()).apply {
            text = e.line; textSize = 11f; typeface = Typeface.MONOSPACE
            setTextColor(0xFF6272A4.toInt())
        }.also { r.addView(it) }
        divider(parent); parent.addView(r); widgets[idx] = r
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private fun saveConfig() {
        if (!isAdded) return
        try {
            val sb = StringBuilder()
            entries.forEachIndexed { idx, entry ->
                val line = when (entry) {
                    is ConfigEntry.Comment   -> entry.text
                    is ConfigEntry.Section   -> "[${entry.name}]"
                    is ConfigEntry.BoolField -> {
                        val v = (widgets[idx] as? SwitchCompat)?.isChecked ?: entry.value
                        "${entry.key} = $v"
                    }
                    is ConfigEntry.IntField  -> {
                        val v = (widgets[idx] as? EditText)?.text?.toString()
                            ?.toLongOrNull() ?: entry.value
                        "${entry.key} = $v"
                    }
                    is ConfigEntry.FloatField -> {
                        val v = (widgets[idx] as? EditText)?.text?.toString()
                            ?.toDoubleOrNull() ?: entry.value
                        "${entry.key} = $v"
                    }
                    is ConfigEntry.StrField  -> {
                        val v = when (val w = widgets[idx]) {
                            is Spinner  -> w.selectedItem?.toString() ?: entry.value
                            is EditText -> w.text?.toString() ?: entry.value
                            else -> entry.value
                        }
                        "${entry.key} = \"$v\""
                    }
                    is ConfigEntry.Raw -> entry.line
                }
                sb.appendLine(line)
            }
            configFile.parentFile?.mkdirs()
            configFile.writeText(sb.toString().trimEnd() + "\n")
            Toast.makeText(requireContext(), "✅ Saved! Restart server to apply.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Config Import ─────────────────────────────────────────────────────────

    private fun handleConfigImport(uri: Uri) {
        if (!isAdded) return
        val ctx = requireContext()
        val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        } ?: uri.lastPathSegment ?: "config"

        Thread {
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.readBytes()
                    ?: throw Exception("Cannot open file")
                val text = String(bytes, Charsets.UTF_8)
                val ext  = name.substringAfterLast('.', "").lowercase()
                if (ext !in listOf("toml", "txt", ""))
                    throw Exception("Invalid file type .$ext — must be .toml or .txt")
                if (bytes.size > 64 * 1024)
                    throw Exception("File too large (${bytes.size / 1024}KB) — max 64KB")
                val hasKV = text.lines().any { l ->
                    val t = l.trim()
                    !t.startsWith("#") && !t.startsWith("[") && t.isNotEmpty() && t.contains("=")
                }
                if (!hasKV)
                    throw Exception("No key = value found — not a valid TOML config")
                val knownKeys = listOf("max_players", "online_mode", "default_gamemode", "motd")
                val hasKnown  = knownKeys.any { k -> text.contains(k) }

                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    val msg = if (!hasKnown)
                        "This file doesn't look like a Pumpkin config (missing known keys). Import anyway?"
                    else "Replace current configuration.toml with '$name'?\n\nRestart server to apply changes."
                    AlertDialog.Builder(ctx)
                        .setTitle(if (!hasKnown) "⚠️ Unrecognized Config" else "Import Config?")
                        .setMessage(msg)
                        .setPositiveButton("Import") { _, _ -> doImportConfig(text) }
                        .setNegativeButton("Cancel", null).show()
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    Toast.makeText(ctx, "❌ ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun doImportConfig(content: String) {
        try {
            configFile.parentFile?.mkdirs(); configFile.writeText(content)
            Toast.makeText(requireContext(),
                "✅ Config imported! Reload to view changes.", Toast.LENGTH_LONG).show()
            loadConfig()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (configFile.exists() && entries.isEmpty()) loadConfig()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scrollContainer = null; btnSave = null; btnReload = null
        btnImport = null; tvStatus = null
    }
}
