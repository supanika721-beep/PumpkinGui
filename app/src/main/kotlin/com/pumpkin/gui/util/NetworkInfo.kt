package com.pumpkin.gui.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL

object NetworkInfo {

    /**
     * Get local WiFi/LAN IP address (IPv4 only, skip loopback).
     * Uses NetworkInterface enumeration — works on all Android versions without permissions.
     */
    fun getLocalIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { addr ->
                    !addr.isLoopbackAddress &&
                    addr is java.net.Inet4Address &&
                    addr.hostAddress?.startsWith("192.") == true ||
                    (addr is java.net.Inet4Address &&
                    !addr.isLoopbackAddress &&
                    (addr.hostAddress?.startsWith("10.") == true ||
                     addr.hostAddress?.startsWith("172.") == true))
                }
                ?.hostAddress ?: "Not connected"
        } catch (_: Exception) { "Not connected" }
    }

    /**
     * Fetch public IP from api.ipify.org — must run on IO thread.
     */
    suspend fun getPublicIp(): String = withContext(Dispatchers.IO) {
        try {
            val url  = URL("https://api.ipify.org")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout    = 5000
            conn.setRequestProperty("User-Agent", "PumpkinMCGui/1.0")
            val ip = conn.inputStream.bufferedReader().readText().trim()
            conn.disconnect()
            ip
        } catch (_: Exception) { "Unavailable" }
    }
}
