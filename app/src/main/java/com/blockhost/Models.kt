package com.blockhost

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Software(val label: String, val hasLoader: Boolean) {
    VANILLA("Vanilla", false),
    PAPER("Paper", false),
    SPIGOT("Spigot (BuildTools, experimental)", false),
    FABRIC("Fabric", true),
    FORGE("Forge", true),
    NEOFORGE("NeoForge", true),
    CUSTOM("Custom JAR", false)
}

enum class RunState(val label: String) {
    STOPPED("Stopped"), STARTING("Starting"), RUNNING("Running"),
    STOPPING("Stopping"), CRASHED("Crashed"), SLEEPING("Sleeping (wake on join)")
}

data class ServerConfig(
    val id: String,
    val name: String,
    val software: Software,
    val mcVersion: String,
    val loaderVersion: String,
    val memoryMb: Int,
    val rootPath: String,
    val javaMajor: Int,
    val launch: List<String> = emptyList(),
    val installed: Boolean = false,
    val eulaAccepted: Boolean = false,
    val idleMinutes: Int = 15,
    val wakeOnJoin: Boolean = true
) {
    val dir: File get() = File(rootPath)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("software", software.name)
        put("mc", mcVersion); put("loader", loaderVersion); put("mem", memoryMb)
        put("root", rootPath); put("java", javaMajor); put("launch", JSONArray(launch))
        put("installed", installed); put("eula", eulaAccepted)
        put("idle", idleMinutes); put("wake", wakeOnJoin)
    }

    companion object {
        fun fromJson(o: JSONObject): ServerConfig {
            val l = o.optJSONArray("launch") ?: JSONArray()
            return ServerConfig(
                id = o.getString("id"), name = o.getString("name"),
                software = Software.valueOf(o.getString("software")),
                mcVersion = o.optString("mc"), loaderVersion = o.optString("loader"),
                memoryMb = o.optInt("mem", 1024), rootPath = o.getString("root"),
                javaMajor = o.optInt("java", 21),
                launch = List(l.length()) { l.getString(it) },
                installed = o.optBoolean("installed"), eulaAccepted = o.optBoolean("eula"),
                idleMinutes = o.optInt("idle", 15), wakeOnJoin = o.optBoolean("wake", true)
            )
        }
    }
}

class ServerStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "configs").apply { mkdirs() }

    fun loadAll(): List<ServerConfig> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { f -> runCatching { ServerConfig.fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedBy { it.name.lowercase() }

    @Synchronized
    fun save(c: ServerConfig) {
        val tmp = File(dir, "${c.id}.tmp")
        tmp.writeText(c.toJson().toString())
        if (!tmp.renameTo(File(dir, "${c.id}.json"))) throw java.io.IOException("Could not save server config")
    }

    fun delete(id: String) { File(dir, "$id.json").delete() }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("blockhost", Context.MODE_PRIVATE)
    var backupKeep: Int
        get() = sp.getInt("backup_keep", 5)
        set(v) { sp.edit().putInt("backup_keep", v).apply() }
    var backupTree: String?
        get() = sp.getString("backup_tree", null)
        set(v) { sp.edit().putString("backup_tree", v).apply() }
    var defaultIdle: Int
        get() = sp.getInt("default_idle", 15)
        set(v) { sp.edit().putInt("default_idle", v).apply() }
    fun getString(k: String, d: String = "") = sp.getString(k, d) ?: d
    fun putString(k: String, v: String) { sp.edit().putString(k, v).apply() }
}
