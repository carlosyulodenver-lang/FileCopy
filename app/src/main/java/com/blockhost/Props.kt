package com.blockhost

import java.io.File
import java.util.Properties

enum class PType { BOOL, INT, ENUM, TEXT }

data class PropDef(val key: String, val type: PType, val def: String, val options: List<String> = emptyList())

object ServerProps {
    val defs: List<PropDef> = listOf(
        PropDef("motd", PType.TEXT, "A Minecraft Server"),
        PropDef("server-port", PType.INT, "25565"),
        PropDef("server-ip", PType.TEXT, ""),
        PropDef("max-players", PType.INT, "20"),
        PropDef("online-mode", PType.BOOL, "true"),
        PropDef("enforce-secure-profile", PType.BOOL, "true"),
        PropDef("white-list", PType.BOOL, "false"),
        PropDef("enforce-whitelist", PType.BOOL, "false"),
        PropDef("difficulty", PType.ENUM, "easy", listOf("peaceful", "easy", "normal", "hard")),
        PropDef("gamemode", PType.ENUM, "survival", listOf("survival", "creative", "adventure", "spectator")),
        PropDef("force-gamemode", PType.BOOL, "false"),
        PropDef("hardcore", PType.BOOL, "false"),
        PropDef("pvp", PType.BOOL, "true"),
        PropDef("spawn-protection", PType.INT, "16"),
        PropDef("view-distance", PType.INT, "10"),
        PropDef("simulation-distance", PType.INT, "10"),
        PropDef("entity-broadcast-range-percentage", PType.INT, "100"),
        PropDef("max-world-size", PType.INT, "29999984"),
        PropDef("allow-nether", PType.BOOL, "true"),
        PropDef("allow-flight", PType.BOOL, "false"),
        PropDef("enable-command-block", PType.BOOL, "false"),
        PropDef("spawn-animals", PType.BOOL, "true"),
        PropDef("spawn-monsters", PType.BOOL, "true"),
        PropDef("spawn-npcs", PType.BOOL, "true"),
        PropDef("generate-structures", PType.BOOL, "true"),
        PropDef("level-name", PType.TEXT, "world"),
        PropDef("level-seed", PType.TEXT, ""),
        PropDef("level-type", PType.TEXT, "minecraft:normal"),
        PropDef("generator-settings", PType.TEXT, "{}"),
        PropDef("initial-enabled-packs", PType.TEXT, "vanilla"),
        PropDef("initial-disabled-packs", PType.TEXT, ""),
        PropDef("resource-pack", PType.TEXT, ""),
        PropDef("resource-pack-sha1", PType.TEXT, ""),
        PropDef("resource-pack-prompt", PType.TEXT, ""),
        PropDef("require-resource-pack", PType.BOOL, "false"),
        PropDef("enable-status", PType.BOOL, "true"),
        PropDef("hide-online-players", PType.BOOL, "false"),
        PropDef("broadcast-console-to-ops", PType.BOOL, "true"),
        PropDef("broadcast-rcon-to-ops", PType.BOOL, "true"),
        PropDef("enable-query", PType.BOOL, "false"),
        PropDef("query.port", PType.INT, "25565"),
        PropDef("enable-rcon", PType.BOOL, "false"),
        PropDef("rcon.port", PType.INT, "25575"),
        PropDef("rcon.password", PType.TEXT, ""),
        PropDef("network-compression-threshold", PType.INT, "256"),
        PropDef("max-tick-time", PType.INT, "60000"),
        PropDef("rate-limit", PType.INT, "0"),
        PropDef("player-idle-timeout", PType.INT, "0"),
        PropDef("op-permission-level", PType.INT, "4"),
        PropDef("function-permission-level", PType.INT, "2"),
        PropDef("sync-chunk-writes", PType.BOOL, "true"),
        PropDef("region-file-compression", PType.ENUM, "deflate", listOf("deflate", "lz4", "none")),
        PropDef("use-native-transport", PType.BOOL, "true"),
        PropDef("prevent-proxy-connections", PType.BOOL, "false"),
        PropDef("log-ips", PType.BOOL, "true"),
        PropDef("accepts-transfers", PType.BOOL, "false"),
        PropDef("pause-when-empty-seconds", PType.INT, "60"),
        PropDef("max-chained-neighbor-updates", PType.INT, "1000000"),
        PropDef("text-filtering-config", PType.TEXT, "")
    )

    private fun file(dir: File) = File(dir, "server.properties")

    fun read(dir: File): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val f = file(dir)
        if (f.exists()) {
            val p = Properties()
            f.inputStream().use { p.load(it) }
            val order = f.readLines(Charsets.ISO_8859_1).mapNotNull { l ->
                val t = l.trim(); if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) null
                else t.split('=', ':', limit = 2)[0].trim()
            }
            for (k in order) p.getProperty(k)?.let { out[k] = it }
            for (k in p.stringPropertyNames()) if (k !in out) out[k] = p.getProperty(k)
        }
        return out
    }

    fun write(dir: File, values: Map<String, String>) {
        dir.mkdirs()
        val sb = StringBuilder("#Minecraft server properties\n#Edited by BlockHost\n")
        for ((k, v) in values) sb.append(esc(k, true)).append('=').append(esc(v, false)).append('\n')
        val tmp = File(dir, "server.properties.tmp")
        tmp.writeText(sb.toString(), Charsets.ISO_8859_1)
        if (!tmp.renameTo(file(dir))) throw java.io.IOException("Could not write server.properties")
    }

    private fun esc(s: String, key: Boolean): String {
        val sb = StringBuilder()
        for (c in s) when {
            c == '\\' -> sb.append("\\\\"); c == '\n' -> sb.append("\\n"); c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t"); c == ':' || c == '=' || c == '#' || c == '!' -> sb.append('\\').append(c)
            c == ' ' && key -> sb.append("\\ ")
            c.code > 126 || c.code < 32 -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        return sb.toString()
    }

    fun validate(def: PropDef?, v: String): String? {
        if (def == null) return null
        return when (def.type) {
            PType.BOOL -> if (v == "true" || v == "false") null else "must be true or false"
            PType.INT -> {
                val n = v.toLongOrNull() ?: return "must be a whole number"
                if (def.key.endsWith("port") && n !in 1..65535) "port must be 1-65535" else if (n < 0) "must not be negative" else null
            }
            PType.ENUM -> if (v in def.options) null else "must be one of ${def.options.joinToString()}"
            PType.TEXT -> null
        }
    }

    fun port(dir: File) = read(dir)["server-port"]?.toIntOrNull() ?: 25565
    fun bindHost(dir: File) = read(dir)["server-ip"]?.takeIf { it.isNotBlank() } ?: "127.0.0.1"

    /** Ports already claimed by existing servers, used to choose a free default for a new one. */
    fun nextFreePort(existing: List<ServerConfig>): Int {
        val used = existing.map { port(it.dir) }.toSet()
        var p = 25565
        while (p in used) p++
        return p
    }
}
