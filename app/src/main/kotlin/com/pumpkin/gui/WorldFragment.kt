package com.pumpkin.gui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.*
import com.pumpkin.gui.service.ServerService
import java.io.File
import java.util.zip.ZipInputStream

class WorldFragment : Fragment() {

    private var rvWorlds: RecyclerView? = null
    private var fabImport: com.google.android.material.floatingactionbutton.FloatingActionButton? = null

    // File picker for world import
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK)
            result.data?.data?.let { uri -> handleImport(uri) }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_world, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rvWorlds  = view.findViewById(R.id.rvWorlds)
        fabImport = view.findViewById(R.id.fabImportWorld)
        fabImport?.setOnClickListener {
            importLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "application/zip"
                addCategory(Intent.CATEGORY_OPENABLE)
                // Also accept octet-stream in case file manager doesn't send correct MIME
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "*/*"))
            })
        }
        loadWorlds()
    }

    override fun onResume() { super.onResume(); loadWorlds() }
    override fun onDestroyView() { super.onDestroyView(); rvWorlds = null; fabImport = null }

    private fun loadWorlds() {
        if (!isAdded) return
        val worlds = requireContext().filesDir.listFiles()?.filter { f ->
            f.isDirectory && (f.name.startsWith("world") || File(f, "level.dat").exists())
        }?.sortedBy { it.name } ?: emptyList()

        val items = if (worlds.isEmpty())
            listOf(WorldInfo("No worlds found", "Start server to generate worlds", null))
        else worlds.map { WorldInfo(it.name, fmtBytes(dirSize(it)), it) }

        rvWorlds?.layoutManager = LinearLayoutManager(requireContext())
        rvWorlds?.adapter = WorldAdapter(items) { w -> if (w.dir != null) showOptions(w) }
    }

    private fun showOptions(w: WorldInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle("🌍 ${w.name}")
            .setMessage("Size: ${w.sizeStr}")
            .setPositiveButton("⬇️ Download") { _, _ -> downloadWorld(w) }
            .setNeutralButton("🗑️ Delete") { _, _ -> confirmDelete(w) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadWorld(w: WorldInfo) {
        val dir = w.dir ?: return
        Toast.makeText(requireContext(), "Zipping ${w.name}…", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                downloadsDir.mkdirs()
                val zipFile = java.io.File(downloadsDir, "${w.name}.zip")
                zipFolder(dir, zipFile)

                activity?.runOnUiThread {
                    Toast.makeText(requireContext(),
                        "✅ Saved to Downloads/${w.name}.zip",
                        Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(),
                        "Download failed: ${e.message}",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun zipFolder(src: java.io.File, dest: java.io.File) {
        java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(dest.outputStream())).use { zos ->
            src.walkTopDown().forEach { file ->
                val entryName = src.parentFile?.let { file.relativeTo(it).path } ?: file.name
                if (file.isDirectory) {
                    zos.putNextEntry(java.util.zip.ZipEntry("$entryName/"))
                    zos.closeEntry()
                } else {
                    zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun confirmDelete(w: WorldInfo) {
        if (ServerService.isRunning) {
            AlertDialog.Builder(requireContext())
                .setTitle("⚠️ Server Running")
                .setMessage("Stop the server before deleting a world.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Delete ${w.name}?")
            .setMessage("This will permanently delete the world folder.\nThis cannot be undone!")
            .setPositiveButton("Delete") { _, _ ->
                w.dir?.deleteRecursively()
                Toast.makeText(requireContext(), "World deleted", Toast.LENGTH_SHORT).show()
                loadWorlds()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ── World Import ───────────────────────────────────────────────────────────

    private fun handleImport(uri: Uri) {
        if (!isAdded) return
        val ctx = requireContext()

        // Get file name for display
        val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        } ?: uri.lastPathSegment ?: "world.zip"

        Toast.makeText(ctx, "Validating $name…", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val stream = ctx.contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open file")

                // ── Validation pass ────────────────────────────────────────────
                // Read zip entries to check:
                // 1. It is a valid zip (will throw if not)
                // 2. Contains level.dat somewhere (valid Minecraft world)
                // 3. No path traversal attacks (no "../" in entry names)
                var worldName: String? = null
                var hasLevelDat = false
                ZipInputStream(stream.buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        // Security: reject path traversal
                        if (entryName.contains("..") || entryName.startsWith("/")) {
                            throw Exception("Invalid zip: unsafe path '$entryName'")
                        }
                        if (entryName.endsWith("level.dat")) {
                            hasLevelDat = true
                            // Infer world name from top-level folder
                            worldName = entryName.split("/").firstOrNull()
                                ?.takeIf { it.isNotBlank() }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                if (!hasLevelDat) {
                    activity?.runOnUiThread {
                        AlertDialog.Builder(ctx)
                            .setTitle("❌ Invalid World")
                            .setMessage("The zip does not contain a valid Minecraft world (level.dat not found).")
                            .setPositiveButton("OK", null).show()
                    }
                    return@Thread
                }

                // ── Confirm dialog ─────────────────────────────────────────────
                val displayName = worldName ?: name.removeSuffix(".zip")
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    AlertDialog.Builder(ctx)
                        .setTitle("Import World?")
                        .setMessage("Import '${displayName}' into the server?\n\nIf a world with the same name exists, it will be overwritten.")
                        .setPositiveButton("Import") { _, _ -> doImport(uri, ctx.filesDir) }
                        .setNegativeButton("Cancel", null).show()
                }

            } catch (e: Exception) {
                activity?.runOnUiThread {
                    Toast.makeText(ctx, "Validation failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun doImport(uri: Uri, destRoot: File) {
        if (!isAdded) return
        Toast.makeText(requireContext(), "Importing world…", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val ctx = requireContext()
                val stream = ctx.contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open file")

                ZipInputStream(stream.buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        // Security: skip dangerous entries (double-check)
                        if (!entry.name.contains("..") && !entry.name.startsWith("/")) {
                            val dest = File(destRoot, entry.name)
                            if (entry.isDirectory) {
                                dest.mkdirs()
                            } else {
                                dest.parentFile?.mkdirs()
                                dest.outputStream().use { out -> zis.copyTo(out) }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    Toast.makeText(requireContext(), "✅ World imported successfully!", Toast.LENGTH_LONG).show()
                    loadWorlds()
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun dirSize(d: File): Long = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    private fun fmtBytes(b: Long) = when {
        b < 1024L * 1024       -> "${"%.1f".format(b / 1024f)} KB"
        b < 1024L * 1024 * 1024 -> "${"%.1f".format(b / 1048576f)} MB"
        else                   -> "${"%.2f".format(b / 1073741824f)} GB"
    }

    data class WorldInfo(val name: String, val sizeStr: String, val dir: File?)

    class WorldAdapter(private val items: List<WorldInfo>, private val onClick: (WorldInfo) -> Unit)
        : RecyclerView.Adapter<WorldAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(android.R.id.text1)
            val tvSize: TextView = v.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(
            LayoutInflater.from(p.context).inflate(android.R.layout.simple_list_item_2, p, false)
        )

        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            h.tvName.text = if (item.dir != null) "🌍  ${item.name}" else item.name
            h.tvName.setTextColor(if (item.dir != null) 0xFF50FA7B.toInt() else 0xFF8B949E.toInt())
            h.tvName.textSize = 15f
            h.tvSize.text = item.sizeStr; h.tvSize.setTextColor(0xFF8B949E.toInt()); h.tvSize.textSize = 12f
            h.itemView.setBackgroundColor(0xFF161B22.toInt()); h.itemView.setPadding(32, 18, 32, 18)
            h.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
