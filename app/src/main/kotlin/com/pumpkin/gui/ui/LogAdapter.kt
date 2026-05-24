package com.pumpkin.gui.ui

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Log adapter that renders each line as a separately-colored span inside
 * ONE TextView per item. The "Copy all" button copies raw text; users can
 * long-press any item to select text within that line.
 *
 * Note on cross-item selection: Android's text selection cannot span across
 * separate View boundaries. The "Copy all logs" button in ConsoleFragment
 * is the intended way to copy multiple lines at once.
 */
class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {

    private val logs      = mutableListOf<String>()   // raw stored lines
    private val displayed = mutableListOf<String>()   // formatted for display
    private val colors    = mutableListOf<Int>()

    private val ansiRegex  = Regex("""\u001B\[[;?\d]*[A-Za-z]""")
    private val pumpkinLog = Regex(
        """^\d{4}-\d{2}-\d{2}\s+(\d{2}:\d{2}:\d{2})\s+(INFO|WARN|ERROR|DEBUG|TRACE)\s+.+?:\s+(.+)$"""
    )

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(12, 3, 12, 3)
            typeface     = Typeface.MONOSPACE
            textSize     = 11.5f
            isSingleLine = false
            maxLines     = Int.MAX_VALUE
            setTextIsSelectable(true)
        }
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.tv.text = displayed[position]
        holder.tv.setTextColor(colors[position])
    }

    override fun getItemCount() = logs.size

    fun addLog(raw: String) {
        val clean = ansiRegex.replace(raw, "").trim()
        if (clean.isEmpty()) return

        if (logs.size >= 500) {
            logs.removeAt(0)
            displayed.removeAt(0)
            colors.removeAt(0)
            notifyItemRemoved(0)
        }
        logs.add(clean)
        displayed.add(formatLine(clean))
        colors.add(colorFor(clean))
        notifyItemInserted(logs.size - 1)
    }

    fun clear() {
        val size = logs.size
        logs.clear(); displayed.clear(); colors.clear()
        notifyItemRangeRemoved(0, size)
    }

    private fun formatLine(line: String): String {
        val m = pumpkinLog.find(line) ?: return line
        return "${m.groupValues[1]} [${m.groupValues[2]}] ${m.groupValues[3]}"
    }

    private fun colorFor(line: String): Int = when {
        line.contains(" ERROR ") || line.contains("[ERROR]") -> Color.parseColor("#FF5555")
        line.contains(" WARN ")  || line.contains("[WARN]")  -> Color.parseColor("#FFB86C")
        line.contains(" INFO ")  || line.contains("[INFO]")  -> Color.parseColor("#50FA7B")
        line.contains(" DEBUG ") || line.contains("[DEBUG]") -> Color.parseColor("#BD93F9")
        line.contains(" TRACE ") || line.contains("[TRACE]") -> Color.parseColor("#6272A4")
        line.startsWith("[SYSTEM]")                          -> Color.parseColor("#8BE9FD")
        line.startsWith(">")                                 -> Color.parseColor("#F1FA8C")
        else                                                 -> Color.parseColor("#F8F8F2")
    }

    /** Raw unformatted lines for clipboard copy */
    fun getAllLogs(): List<String> = logs.toList()
}
