package com.pumpkin.gui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import com.pumpkin.gui.service.ServerService

class SettingsFragment : Fragment() {

    private val PREFS_NAME         = "pumpkin_prefs"
    private val PREFS_FONT_SIZE    = "console_font_size"
    private val PREFS_AUTO_START   = "auto_start_server"
    private val PREFS_STOP_TIMEOUT = "stop_timeout_secs"

    // Min font = 12, slider max = 32, so actual = progress + 12
    private val FONT_MIN = 12
    // Stop timeout: slider 0-50 maps to 5-55 seconds (step 1, offset 5)
    private val TIMEOUT_MIN = 5

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // ── Font size ─────────────────────────────────────────────────────────
        val tvFontValue   = view.findViewById<TextView>(R.id.tvFontSizeValue)
        val tvFontPreview = view.findViewById<TextView>(R.id.tvFontPreview)
        val seekFont      = view.findViewById<SeekBar>(R.id.seekFontSize)

        val savedFont = prefs.getInt(PREFS_FONT_SIZE, 24)
        seekFont.progress = savedFont - FONT_MIN
        tvFontValue.text  = savedFont.toString()
        tvFontPreview.textSize = savedFont.toFloat()

        seekFont.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val size = progress + FONT_MIN
                tvFontValue.text       = size.toString()
                tvFontPreview.textSize = size.toFloat()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                val size = sb.progress + FONT_MIN
                prefs.edit().putInt(PREFS_FONT_SIZE, size).apply()
                // Notify ConsoleFragment to apply new size immediately if it's alive
                ServerService.onFontSizeChanged?.invoke(size)
            }
        })

        // ── Auto-start ────────────────────────────────────────────────────────
        val switchAutoStart = view.findViewById<SwitchCompat>(R.id.switchAutoStart)
        switchAutoStart.isChecked = prefs.getBoolean(PREFS_AUTO_START, false)
        switchAutoStart.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREFS_AUTO_START, checked).apply()
        }

        // ── Stop timeout ──────────────────────────────────────────────────────
        val tvTimeout  = view.findViewById<TextView>(R.id.tvStopTimeout)
        val seekTimeout = view.findViewById<SeekBar>(R.id.seekStopTimeout)

        val savedTimeout = prefs.getInt(PREFS_STOP_TIMEOUT, 10)
        seekTimeout.progress  = savedTimeout - TIMEOUT_MIN
        tvTimeout.text        = "${savedTimeout}s"

        seekTimeout.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvTimeout.text = "${progress + TIMEOUT_MIN}s"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                prefs.edit().putInt(PREFS_STOP_TIMEOUT, sb.progress + TIMEOUT_MIN).apply()
            }
        })
    }
}
