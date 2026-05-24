package com.pumpkin.gui.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket

data class ServerStatus(
    val online:        Boolean       = false,
    val playerOnline:  Int           = 0,
    val playerMax:     Int           = 0,
    val players:       List<String>  = emptyList(),
    val javaOnline:    Boolean       = false,
    val bedrockOnline: Boolean       = false
)

object ServerPing {

    // Called on Main thread — player join/leave events detected via ping delta
    var onPlayerJoined: ((String) -> Unit)? = null
    var onPlayerLeft:   ((String) -> Unit)? = null

    private var prevPlayers     = emptyList<String>()
    private var prevOnlineCount = 0

    suspend fun pingBoth(
        host: String      = "127.0.0.1",
        javaPort: Int     = 25565,
        bedrockPort: Int  = 19132
    ): ServerStatus = coroutineScope {

        // Skip ping if edition is disabled (port = -1)
        val javaD    = if (javaPort    > 0) async(Dispatchers.IO) { pingJavaRaw(host, javaPort) }
                       else null
        val bedrockD = if (bedrockPort > 0) async(Dispatchers.IO) { BedrockPing.pingRaw(host, bedrockPort) }
                       else null

        val java    = javaD?.await()    ?: JavaRaw()
        val bedrock = bedrockD?.await() ?: BedrockPing.BedrockStatus()

        val totalOnline = maxOf(java.playerOnline, bedrock.playerOnline)
        val totalMax    = maxOf(java.playerMax, bedrock.playerMax)

        // ── Player join/leave detection — called on Main dispatcher of coroutineScope ──
        // coroutineScope inherits context from caller which is Dispatchers.Main in MainActivity
        if (java.players.isNotEmpty() || prevPlayers.isNotEmpty()) {
            val joined = java.players.filter { it !in prevPlayers }
            val left   = prevPlayers.filter { it !in java.players }
            joined.forEach { onPlayerJoined?.invoke(it) }
            left.forEach   { onPlayerLeft?.invoke(it)  }
            prevPlayers = java.players
        } else {
            val delta = totalOnline - prevOnlineCount
            when {
                delta > 0 -> repeat(delta)  { onPlayerJoined?.invoke("a player") }
                delta < 0 -> repeat(-delta) { onPlayerLeft?.invoke("a player")   }
            }
        }
        prevOnlineCount = totalOnline

        ServerStatus(
            online        = java.online || bedrock.online,
            playerOnline  = totalOnline,
            playerMax     = totalMax,
            players       = java.players,
            javaOnline    = java.online,
            bedrockOnline = bedrock.online
        )
    }

    fun resetPlayerState() { prevPlayers = emptyList(); prevOnlineCount = 0 }

    // ── Java TCP ping — pure IO, NO UI calls ──────────────────────────────────

    private data class JavaRaw(
        val online: Boolean = false, val playerOnline: Int = 0,
        val playerMax: Int = 0, val players: List<String> = emptyList()
    )

    private fun pingJavaRaw(host: String, port: Int): JavaRaw {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), 3000)
            socket.soTimeout = 3000
            socket.tcpNoDelay = true

            val out = socket.getOutputStream()
            val inp = DataInputStream(BufferedInputStream(socket.getInputStream()))

            val hsBody = ByteArrayOutputStream()
            val hsDos  = DataOutputStream(hsBody)
            writeVarInt(hsDos, 0x00)
            writeVarInt(hsDos, -1)
            val hostBytes = host.toByteArray(Charsets.UTF_8)
            writeVarInt(hsDos, hostBytes.size)
            hsDos.write(hostBytes)
            hsDos.writeShort(port)
            writeVarInt(hsDos, 1)
            hsDos.flush()
            val hsBytes = hsBody.toByteArray()
            writeVarIntStream(out, hsBytes.size)
            out.write(hsBytes)

            writeVarIntStream(out, 1)
            out.write(0x00)
            out.flush()

            readVarInt(inp) // packet length
            val pktId   = readVarInt(inp)
            val jsonLen = readVarInt(inp)
            if (pktId != 0x00 || jsonLen <= 0 || jsonLen > 1_000_000) return JavaRaw()

            val jsonBytes = ByteArray(jsonLen)
            inp.readFully(jsonBytes)

            val root       = JSONObject(String(jsonBytes, Charsets.UTF_8))
            val playersObj = root.optJSONObject("players")
            val online     = playersObj?.optInt("online", 0) ?: 0
            val max        = playersObj?.optInt("max", 0) ?: 0
            val sample     = playersObj?.optJSONArray("sample")
            val names      = mutableListOf<String>()
            if (sample != null) {
                for (i in 0 until sample.length()) {
                    val name = sample.optJSONObject(i)?.optString("name") ?: continue
                    if (name.isNotBlank() && name != "Anonymous Player") names.add(name)
                }
            }
            JavaRaw(true, online, max, names)
        } catch (_: Exception) { JavaRaw() }
        finally { try { socket.close() } catch (_: Exception) {} }
    }

    // ── VarInt ────────────────────────────────────────────────────────────────

    private fun writeVarInt(out: DataOutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) { out.writeByte(v); return }
            out.writeByte((v and 0x7F) or 0x80); v = v ushr 7
        }
    }
    private fun writeVarIntStream(out: OutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) { out.write(v); return }
            out.write((v and 0x7F) or 0x80); v = v ushr 7
        }
    }
    private fun readVarInt(inp: DataInputStream): Int {
        var result = 0; var shift = 0
        while (true) {
            val b = inp.readByte().toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift >= 35) throw IOException("VarInt too big")
        }
        return result
    }
}
