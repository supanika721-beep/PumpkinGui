package com.pumpkin.gui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.*
import com.pumpkin.gui.service.ServerService
import java.io.File

class FilesFragment : Fragment() {

    private var tvBreadcrumb: TextView? = null
    private var rvFiles:      RecyclerView? = null
    private var fabUpload:    com.google.android.material.floatingactionbutton.FloatingActionButton? = null

    private var currentDir = File("")   // proper init di onViewCreated
    private val pathStack  = mutableListOf<File>()
    private val protected  = setOf("pumpkin")

    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK)
            result.data?.data?.let { handleUpload(it) }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_files, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvBreadcrumb = view.findViewById(R.id.tvBreadcrumb)
        rvFiles      = view.findViewById(R.id.rvFiles)
        fabUpload    = view.findViewById(R.id.fabUpload)

        currentDir = requireContext().filesDir
        fabUpload?.setOnClickListener {
            pickLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
            })
        }
        loadDir(currentDir)
    }

    override fun onResume() {
        super.onResume()
        if (currentDir.path.isNotEmpty() && currentDir.exists()) loadDir(currentDir)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tvBreadcrumb = null; rvFiles = null; fabUpload = null
    }

    // ── Directory loading ──────────────────────────────────────────────────────

    private fun loadDir(dir: File) {
        if (!isAdded) return
        currentDir = dir
        updateBreadcrumb(dir)

        val entries = mutableListOf<FileEntry>()
        if (pathStack.isNotEmpty()) entries.add(FileEntry("📁  ..", null, true, null))

        dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.forEach { f ->
                val icon = if (f.isDirectory) "📁" else iconFor(f.name)
                val size = if (f.isFile) fmt(f.length()) else "${f.listFiles()?.size ?: 0} items"
                entries.add(FileEntry("$icon  ${f.name}", size, f.isDirectory, f, f.name in protected))
            }

        val rv = rvFiles ?: return
        if (rv.layoutManager == null) rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = FileAdapter(entries) { entry ->
            when {
                entry.file == null      -> if (pathStack.isNotEmpty()) loadDir(pathStack.removeAt(pathStack.lastIndex))
                entry.file.isDirectory -> { pathStack.add(currentDir); loadDir(entry.file) }
                else                   -> showOptions(entry)
            }
        }

        // Show bulk-action button if we're in logs/ or crash-reports/
        updateFolderActions(dir)
    }

    /** Show folder-level action button when relevant (logs = clear gz, crash-reports = clear all) */
    private fun updateFolderActions(dir: File) {
        val ctx = context ?: return
        val serverRoot = ctx.filesDir
        val rel = try { dir.relativeTo(serverRoot).path } catch (_: Exception) { return }

        val btnFolderAction = view?.findViewById<TextView>(R.id.btnFolderAction) ?: return

        when (rel) {
            "logs" -> {
                val gzCount = dir.listFiles()?.count { it.name.endsWith(".gz") } ?: 0
                if (gzCount > 0) {
                    btnFolderAction.text = "🗑️ Clear $gzCount old log(s)"
                    btnFolderAction.visibility = View.VISIBLE
                    btnFolderAction.setOnClickListener { confirmClearLogs(dir, gzCount) }
                } else {
                    btnFolderAction.visibility = View.GONE
                }
            }
            "crash-reports" -> {
                val count = dir.listFiles()?.count { it.name.endsWith(".txt") } ?: 0
                if (count > 0) {
                    btnFolderAction.text = "🗑️ Clear $count crash report(s)"
                    btnFolderAction.visibility = View.VISIBLE
                    btnFolderAction.setOnClickListener { confirmClearCrashReports(dir, count) }
                } else {
                    btnFolderAction.visibility = View.GONE
                }
            }
            else -> btnFolderAction.visibility = View.GONE
        }
    }

    private fun confirmClearLogs(logsDir: File, count: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear $count old log(s)?")
            .setMessage("All .gz log files in logs/ will be deleted.\nlatest.log will NOT be affected.")
            .setPositiveButton("Clear") { _, _ ->
                var deleted = 0
                logsDir.listFiles()?.filter { it.name.endsWith(".gz") }?.forEach {
                    if (it.delete()) deleted++
                }
                toast("Deleted $deleted old log file(s)")
                loadDir(logsDir)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmClearCrashReports(dir: File, count: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear $count crash report(s)?")
            .setMessage("All .txt files in crash-reports/ will be deleted.")
            .setPositiveButton("Clear") { _, _ ->
                var deleted = 0
                dir.listFiles()?.filter { it.name.endsWith(".txt") }?.forEach {
                    if (it.delete()) deleted++
                }
                toast("Deleted $deleted crash report(s)")
                loadDir(dir)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun updateBreadcrumb(dir: File) {
        val rel = try { dir.relativeTo(requireContext().filesDir).path } catch (_: Exception) { "" }
        tvBreadcrumb?.text = if (rel.isEmpty()) "/ server" else "/ server / ${rel.replace(File.separator, " / ")}"
    }

    // ── Custom options dialog ──────────────────────────────────────────────────

    private fun showOptions(entry: FileEntry) {
        val file = entry.file ?: return
        val ctx  = requireContext()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF161B22.toInt())
        }

        // Header
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF21262D.toInt())
            setPadding(52, 40, 52, 28)
            TextView(ctx).apply {
                text = file.name; textSize = 15f
                setTextColor(0xFFF0F6FC.toInt())
                typeface = android.graphics.Typeface.MONOSPACE
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }.also { addView(it) }
            TextView(ctx).apply {
                text = buildString { append(fmt(file.length())); if (entry.isProtected) append("  🔒") }
                textSize = 11f; setTextColor(0xFF8B949E.toInt()); setPadding(0, 6, 0, 0)
            }.also { addView(it) }
        }.also { root.addView(it) }

        val dialog = AlertDialog.Builder(ctx).setView(root).create()

        fun row(icon: String, label: String, color: Int, action: () -> Unit) {
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(52, 30, 52, 30)
                isClickable = true; isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { dialog.dismiss(); action() }
                TextView(ctx).apply { text = icon; textSize = 19f; setPadding(0, 0, 32, 0) }.also { addView(it) }
                TextView(ctx).apply { text = label; textSize = 14f; setTextColor(color) }.also { addView(it) }
            }.also { root.addView(it) }
        }

        fun divider() = root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                .also { it.setMargins(52, 0, 52, 0) }
            setBackgroundColor(0xFF30363D.toInt())
        })

        if (entry.isProtected) {
            row("🔒", "Protected — cannot modify", 0xFF8B949E.toInt()) {}
        } else {
            row("👁️", "View / Edit",       0xFF8BE9FD.toInt()) { openEditor(file) }; divider()
            row("⬇️", "Download to phone", 0xFF50FA7B.toInt()) { downloadFile(file) }; divider()
            row("🗑️", "Delete",            0xFFFF5555.toInt()) { confirmDelete(file) }; divider()
        }

        TextView(ctx).apply {
            text = "Cancel"; textSize = 13f; setTextColor(0xFF8B949E.toInt())
            gravity = Gravity.CENTER; setPadding(0, 28, 0, 28)
            isClickable = true; isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { dialog.dismiss() }
        }.also { root.addView(it) }

        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(
                (ctx.resources.displayMetrics.widthPixels * 0.88).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    // ── Editor ─────────────────────────────────────────────────────────────────

    private fun openEditor(file: File) {
        if (file.length() > 512 * 1024) { toast("File too large to edit (max 512 KB)"); return }
        val content = try { file.readText() } catch (e: Exception) { toast("Cannot read: ${e.message}"); return }
        val ctx = requireContext()

        // Build layout
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D1117.toInt())
        }

        // Toolbar
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF161B22.toInt()); setPadding(28, 16, 28, 16)
            TextView(ctx).apply {
                text = "✏️  ${file.name}"; textSize = 13f; setTextColor(0xFF8BE9FD.toInt())
                typeface = android.graphics.Typeface.MONOSPACE; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }.also { addView(it) }
            TextView(ctx).apply { text = fmt(file.length()); textSize = 10f; setTextColor(0xFF444444.toInt()) }.also { addView(it) }
        }.also { root.addView(it) }

        // EditText
        val et = EditText(ctx).apply {
            setText(content); typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f
            setTextColor(0xFFF8F8F2.toInt()); setBackgroundColor(0xFF0D1117.toInt())
            setPadding(28, 20, 28, 20)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
        }
        ScrollView(ctx).apply {
            addView(et)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }.also { root.addView(it) }

        // Action bar
        val btnClose = Button(ctx).apply {
            text = "Close"; setTextColor(0xFFCCCCCC.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF21262D.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.setMargins(0, 0, 10, 0) }
        }
        val btnSave = Button(ctx).apply {
            text = "💾  Save"; setTextColor(0xFFFFFFFF.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF238636.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF161B22.toInt()); setPadding(16, 12, 16, 12)
            addView(btnClose); addView(btnSave)
        }.also { root.addView(it) }

        // Create dialog AFTER root is fully built
        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        btnClose.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            try { file.writeText(et.text.toString()); toast("✅ Saved!"); dialog.dismiss() }
            catch (e: Exception) { toast("Save failed: ${e.message}") }
        }
        dialog.show()
        dialog.window?.apply {
            // Remove grey AlertDialog default background so our dark root shows cleanly
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (ctx.resources.displayMetrics.heightPixels * 0.88).toInt()
            )
        }
    }

    // ── Download ───────────────────────────────────────────────────────────────

    private fun downloadFile(file: File) {
        try {
            val dest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), file.name)
            if (dest.exists()) {
                AlertDialog.Builder(requireContext())
                    .setTitle("File already exists")
                    .setMessage("'${file.name}' already in Downloads. Overwrite?")
                    .setPositiveButton("Overwrite") { _, _ -> doCopy(file, dest) }
                    .setNegativeButton("Cancel", null).show()
            } else doCopy(file, dest)
        } catch (e: Exception) { toast("Download failed: ${e.message}") }
    }

    private fun doCopy(src: File, dest: File) {
        try { src.copyTo(dest, overwrite = true); toast("Saved to Downloads/${dest.name}") }
        catch (e: Exception) { toast("Copy failed: ${e.message}") }
    }

    // ── Delete ─────────────────────────────────────────────────────────────────

    private fun confirmDelete(file: File) {
        if (ServerService.isRunning) {
            AlertDialog.Builder(requireContext())
                .setTitle("⚠️ Server Running")
                .setMessage("Stop the server before deleting files.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Delete ${file.name}?")
            .setMessage("This cannot be undone!")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                    toast("Deleted"); loadDir(currentDir)
                } catch (e: Exception) { toast("Failed: ${e.message}") }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ── Upload ─────────────────────────────────────────────────────────────────

    private fun handleUpload(uri: Uri) {
        val ctx  = requireContext()
        val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        } ?: uri.lastPathSegment ?: "upload"

        if (name in protected) { toast("🔒 Cannot overwrite protected: $name"); return }

        val dest = File(currentDir, name)
        val doUpload = {
            try {
                ctx.contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                toast("Uploaded: $name"); loadDir(currentDir)
            } catch (e: Exception) { toast("Upload failed: ${e.message}") }
        }

        if (dest.exists()) {
            AlertDialog.Builder(ctx).setTitle("File exists").setMessage("'$name' exists. Overwrite?")
                .setPositiveButton("Overwrite") { _, _ -> doUpload() }
                .setNegativeButton("Cancel", null).show()
        } else doUpload()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun toast(msg: String) { if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show() }

    private fun iconFor(name: String) = when {
        name.endsWith(".toml") || name.endsWith(".json") || name.endsWith(".yml") ||
        name.endsWith(".yaml") || name.endsWith(".properties") -> "⚙️"
        name.endsWith(".log")  -> "📜"
        name.endsWith(".dat")  -> "💾"
        name.endsWith(".png") || name.endsWith(".jpg") -> "🖼️"
        name.endsWith(".zip") || name.endsWith(".gz")  -> "📦"
        name.endsWith(".jar")  -> "☕"
        name.endsWith(".txt") || name.endsWith(".md")  -> "📝"
        else -> "📄"
    }

    private fun fmt(b: Long) = when {
        b < 1024L              -> "$b B"
        b < 1024L * 1024       -> "${"%.1f".format(b / 1024f)} KB"
        b < 1024L * 1024 * 1024 -> "${"%.1f".format(b / 1048576f)} MB"
        else                   -> "${"%.2f".format(b / 1073741824f)} GB"
    }

    fun onBackPressed() = if (pathStack.isNotEmpty()) { loadDir(pathStack.removeAt(pathStack.lastIndex)); true } else false

    // ── Data & Adapter ─────────────────────────────────────────────────────────

    data class FileEntry(val label: String, val size: String?, val isDir: Boolean, val file: File?, val isProtected: Boolean = false)

    class FileAdapter(private val items: List<FileEntry>, private val onClick: (FileEntry) -> Unit)
        : RecyclerView.Adapter<FileAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(android.R.id.text1)
            val tvSize: TextView = v.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(
            LayoutInflater.from(p.context).inflate(android.R.layout.simple_list_item_2, p, false)
        )

        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            h.tvName.text = if (item.isProtected) "${item.label}  🔒" else item.label
            h.tvName.setTextColor(when { item.isProtected -> 0xFFFFB86C.toInt(); item.isDir -> 0xFF8BE9FD.toInt(); else -> 0xFFF8F8F2.toInt() })
            h.tvName.textSize = 14f
            h.tvSize.text = item.size ?: ""; h.tvSize.setTextColor(0xFF6272A4.toInt()); h.tvSize.textSize = 11f
            h.itemView.setBackgroundColor(0xFF0D1117.toInt()); h.itemView.setPadding(32, 14, 32, 14)
            h.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
