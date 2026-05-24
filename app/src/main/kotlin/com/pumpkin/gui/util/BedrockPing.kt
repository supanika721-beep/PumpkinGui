package com.pumpkin.gui.util

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RakNet Unconnected Ping/Pong for Minecraft Bedrock Edition.
 * Called from IO thread by ServerPing — all methods are blocking (non-suspend).
 *
 * Packet IDs:
 *   0x01 = Unconnected Ping  (we send)
 *   0x1C = Unconnected Pong  (server replies)
 *
 * Magic: 00 FF FF 00 FE FE FE FE FD FD FD FD 12 34 56 78
 *
 * Ping packet layout (33 bytes):
 *   [1B packet_id=0x01][8B timestamp LE][16B magic][8B client_guid LE]
 *
 * Pong packet layout:
 *   [1B 0x1C][8B timestamp][8B server_guid][16B magic][2B motd_len BE][motd UTF-8]
 *   Header = 1+8+8+16 = 33 bytes, then 2B motdLen, then motd
 *
 * MOTD format: "MCPE;name;protocol;version;playerOnline;playerMax;guid;level;gamemode;..."
 */
object BedrockPing {

    private val MAGIC = byteArrayOf(
        0x00.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00.toByte(),
        0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
        0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(),
        0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte()
    )

    private val startTime = System.currentTimeMillis()

    data class BedrockStatus(
        val online:       Boolean = false,
        val playerOnline: Int     = 0,
        val playerMax:    Int     = 0,
        val serverName:   String  = "",
        val version:      String  = "",
        val gamemode:     String  = "",
        val edition:      String  = ""
    )

    /**
     * Blocking ping — call from IO thread.
     * ServerPing uses async(Dispatchers.IO){ BedrockPing.pingRaw(...) }
     */
    fun pingRaw(host: String = "127.0.0.1", port: Int = 19132, timeoutMs: Int = 3000): BedrockStatus {
        val socket = DatagramSocket()
        return try {
            socket.soTimeout = timeoutMs

            // Build ping packet
            val buf = ByteBuffer.allocate(33).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                put(0x01.toByte())                                          // Packet ID
                putLong(System.currentTimeMillis() - startTime)             // Timestamp (ms, LE)
                put(MAGIC)                                                   // Magic
                putLong(System.nanoTime() and 0x7FFFFFFFFFFFFFFFL)         // Client GUID (random)
            }

            val addr = InetAddress.getByName(host)
            socket.send(DatagramPacket(buf.array(), 33, addr, port))

            // Receive pong
            val recvBuf = ByteArray(2048)
            val pkt     = DatagramPacket(recvBuf, recvBuf.size)
            socket.receive(pkt)

            val data = pkt.data
            val len  = pkt.length

            // Validate: need at least 35 bytes (33 header + 2 motdLen)
            if (len < 35 || data[0] != 0x1C.toByte()) return BedrockStatus()

            // Read MOTD: at offset 33 = 2B big-endian motd length
            val motdLen = ((data[33].toInt() and 0xFF) shl 8) or (data[34].toInt() and 0xFF)
            if (35 + motdLen > len) return BedrockStatus()

            val motd = String(data, 35, motdLen, Charsets.UTF_8)
            parseMotd(motd)

        } catch (_: Exception) { BedrockStatus() }
        finally { try { socket.close() } catch (_: Exception) {} }
    }

    /**
     * Parse MOTD: "MCPE;name;proto;version;online;max;guid;level;gamemode;..."
     */
    private fun parseMotd(motd: String): BedrockStatus {
        val p = motd.split(";")
        if (p.size < 6) return BedrockStatus()
        return try {
            BedrockStatus(
                online       = true,
                edition      = p.getOrElse(0) { "MCPE" },
                serverName   = p.getOrElse(1) { "" },
                version      = p.getOrElse(3) { "" },
                playerOnline = p.getOrElse(4) { "0" }.trim().toIntOrNull() ?: 0,
                playerMax    = p.getOrElse(5) { "0" }.trim().toIntOrNull() ?: 0,
                gamemode     = p.getOrElse(8) { "" }
            )
        } catch (_: Exception) { BedrockStatus() }
    }
}
