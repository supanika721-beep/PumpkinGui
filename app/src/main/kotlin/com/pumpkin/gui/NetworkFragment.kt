package com.pumpkin.gui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import com.pumpkin.gui.util.ConfigReader
import com.pumpkin.gui.util.NetworkInfo
import kotlinx.coroutines.*

class NetworkFragment : Fragment() {

    companion object {
        // Cache public IP per app session — fetch once, survive fragment destroy/recreate
        private var cachedPublicIp: String? = null
        // Track whether toggle button should be shown — survives onDestroyView/onViewCreated
        private var publicIpLoaded = false
    }

    private var tvLocalIp:       TextView?    = null
    private var tvLocalIpStatus: TextView?    = null
    private var btnCopyLocalIp:  TextView?    = null
    private var tvPublicIp:      TextView?    = null
    private var tvPublicIpHint:  TextView?    = null
    private var tvJavaPort:      TextView?    = null
    private var tvBedrockPort:   TextView?    = null
    private var btnRefresh:      TextView?    = null
    private var btnToggleIp:     ImageButton? = null

    // publicIpVisible is local to the view lifecycle (reset to hidden on each entry)
    private var publicIpVisible = false

    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_network, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Recreate scope each time the view is created (previous scope was cancelled in onDestroyView)
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        tvLocalIp       = view.findViewById(R.id.tvLocalIp)
        tvLocalIpStatus = view.findViewById(R.id.tvLocalIpStatus)
        btnCopyLocalIp  = view.findViewById(R.id.btnCopyLocalIp)
        tvPublicIp      = view.findViewById(R.id.tvPublicIp)
        tvPublicIpHint  = view.findViewById(R.id.tvPublicIpHint)
        tvJavaPort      = view.findViewById(R.id.tvJavaPort)
        tvBedrockPort   = view.findViewById(R.id.tvBedrockPort)
        btnRefresh      = view.findViewById(R.id.btnRefreshPublicIp)
        btnToggleIp     = view.findViewById(R.id.btnTogglePublicIp)

        btnCopyLocalIp?.setOnClickListener { copyLocalIp() }

        btnRefresh?.setOnClickListener {
            cachedPublicIp = null
            publicIpLoaded = false
            publicIpVisible = false
            loadPublicIp()
        }

        btnToggleIp?.setOnClickListener {
            publicIpVisible = !publicIpVisible
            renderPublicIp()
        }

        // Restore toggle button visibility from companion state
        // This fixes the bug: when user re-enters the tab, toggle was gone
        // because onViewCreated always recreates views with btnToggleIp visibility=gone (XML default)
        if (publicIpLoaded) {
            btnToggleIp?.visibility = View.VISIBLE
        }

        loadLocalIp()
        loadPorts()
        restoreOrFetchPublicIp()
    }

    override fun onResume() {
        super.onResume()
        // Always refresh local IP (WiFi can change)
        loadLocalIp()
        loadPorts()
    }

    // ── Local IP ──────────────────────────────────────────────────────────────

    private fun loadLocalIp() {
        if (!isAdded) return
        val ip = NetworkInfo.getLocalIp()
        tvLocalIp?.text = ip
        if (ip == "Not connected") {
            tvLocalIpStatus?.text = "No WiFi"
            tvLocalIpStatus?.setTextColor(0xFFFF5555.toInt())
        } else {
            tvLocalIpStatus?.text = "WiFi ●"
            tvLocalIpStatus?.setTextColor(0xFF50FA7B.toInt())
        }
    }

    private fun copyLocalIp() {
        val ip = tvLocalIp?.text?.toString()?.trim() ?: return
        if (ip == "Loading…" || ip == "Not connected") return
        val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("local_ip", ip))
        Toast.makeText(requireContext(), "Copied: $ip", Toast.LENGTH_SHORT).show()
    }

    // ── Public IP ─────────────────────────────────────────────────────────────

    private fun restoreOrFetchPublicIp() {
        val cached = cachedPublicIp
        if (cached != null) {
            // Already fetched this session — restore immediately without network call
            renderPublicIp()
            // Ensure toggle is visible (fixes bug: re-entering tab hides toggle)
            btnToggleIp?.visibility = View.VISIBLE
        } else {
            loadPublicIp()
        }
    }

    private fun loadPublicIp() {
        if (!isAdded) return
        tvPublicIp?.text      = "Loading…"
        tvPublicIp?.setTextColor(0xFF8B949E.toInt())
        btnRefresh?.alpha     = 0.4f
        btnRefresh?.isClickable = false
        // Keep toggle hidden during loading
        btnToggleIp?.visibility = View.GONE

        scope.launch {
            val ip = NetworkInfo.getPublicIp()
            if (!isAdded) return@launch
            cachedPublicIp = ip
            publicIpLoaded = true   // persist across view destroy/recreate
            btnRefresh?.alpha     = 1.0f
            btnRefresh?.isClickable = true
            btnToggleIp?.visibility = View.VISIBLE
            renderPublicIp()
        }
    }

    /**
     * Render public IP in current visibility state.
     * Also updates the hint text to match current state (show/hide toggle icon).
     */
    private fun renderPublicIp() {
        if (!isAdded) return
        val ip = cachedPublicIp ?: return

        if (ip.isEmpty() || ip == "Unavailable") {
            tvPublicIp?.text = "Unavailable"
            tvPublicIp?.setTextColor(0xFF8B949E.toInt())
            btnToggleIp?.visibility = View.GONE
            tvPublicIpHint?.text = "Could not determine public IP"
            return
        }

        if (publicIpVisible) {
            tvPublicIp?.text = ip
            tvPublicIp?.setTextColor(0xFFF0F6FC.toInt())
            btnToggleIp?.setImageResource(android.R.drawable.ic_menu_view)
            tvPublicIpHint?.text = "Internet-facing IP — tap 👁 to hide"
        } else {
            val parts = ip.split(".")
            val masked = if (parts.size == 4) "${parts[0]}.●●●.●●●.●●●" else "●●●.●●●.●●●.●●●"
            tvPublicIp?.text = masked
            tvPublicIp?.setTextColor(0xFF8B949E.toInt())
            btnToggleIp?.setImageResource(android.R.drawable.ic_secure)
            tvPublicIpHint?.text = "Internet-facing IP — tap 🔒 to reveal"
        }
    }

    // ── Ports ─────────────────────────────────────────────────────────────────

    private fun loadPorts() {
        if (!isAdded) return
        try {
            val ports = ConfigReader.readPorts(requireContext().filesDir)
            tvJavaPort?.text    = ports.javaPort.toString()
            tvBedrockPort?.text = ports.bedrockPort.toString()
        } catch (_: Exception) {}
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
        tvLocalIp = null; tvLocalIpStatus = null; btnCopyLocalIp = null
        tvPublicIp = null; tvPublicIpHint = null
        tvJavaPort = null; tvBedrockPort = null
        btnRefresh = null; btnToggleIp = null
    }

    override fun onDestroy() { super.onDestroy() }
}
