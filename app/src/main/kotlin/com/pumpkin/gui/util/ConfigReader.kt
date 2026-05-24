package com.pumpkin.gui.util

import java.io.File

/**
 * Reads port numbers from config/configuration.toml.
 * Used by ServerPing to always ping the correct ports even if user changed them.
 */
object ConfigReader {

    data class ServerPorts(val javaPort: Int = 25565, val bedrockPort: Int = 19132)
    data class ServerEditions(val java: Boolean = true, val bedrock: Boolean = true)

    fun readPorts(filesDir: File): ServerPorts {
        val config = File(filesDir, "config/configuration.toml")
        if (!config.exists()) return ServerPorts()
        var javaPort = 25565; var bedrockPort = 19132
        try {
            config.readLines().forEach { line ->
                val t = line.trim()
                if (t.startsWith("java_edition_address"))    extractPort(t)?.let { javaPort = it }
                if (t.startsWith("bedrock_edition_address")) extractPort(t)?.let { bedrockPort = it }
            }
        } catch (_: Exception) {}
        return ServerPorts(javaPort, bedrockPort)
    }

    fun readEditions(filesDir: File): ServerEditions {
        val config = File(filesDir, "config/configuration.toml")
        if (!config.exists()) return ServerEditions()
        var java = true; var bedrock = true
        try {
            config.readLines().forEach { line ->
                val t = line.trim()
                // Match exactly "java_edition = true/false" (not java_edition_address)
                if (t.matches(Regex("""java_edition\s*=\s*(true|false)""")))
                    java = t.endsWith("true")
                if (t.matches(Regex("""bedrock_edition\s*=\s*(true|false)""")))
                    bedrock = t.endsWith("true")
            }
        } catch (_: Exception) {}
        return ServerEditions(java, bedrock)
    }

    // Extract port from: key = "host:port"
    private fun extractPort(line: String): Int? {
        val quoted = Regex(""""([^"]+)"""").find(line)?.groupValues?.get(1) ?: return null
        val colonIdx = quoted.lastIndexOf(':')
        if (colonIdx < 0) return null
        return quoted.substring(colonIdx + 1).trim().toIntOrNull()
    }
}
