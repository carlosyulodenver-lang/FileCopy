package com.blockhost

import org.json.JSONObject
import java.io.IOException

/** Version discovery against the official vendor APIs. All calls are blocking; call from Dispatchers.IO. */
object Providers {
    const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private val memo = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cached(key: String, f: () -> T): T = synchronized(memo) { memo.getOrPut(key, f) as T }

    fun releases(): List<String> = cached("releases") {
        val a = Net.json(MANIFEST).getJSONArray("versions")
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getString("type") == "release" }.map { it.getString("id") }
    }

    fun mojangVersion(id: String): JSONObject {
        val a = Net.json(MANIFEST).getJSONArray("versions")
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.getString("id") == id) return Net.json(o.getString("url"))
        }
        throw IOException("Minecraft version $id not found in Mojang's manifest")
    }

    /** Java major version a Minecraft version needs. Termux has no Java 8/11, so anything below 17 maps to 17. */
    fun javaMajorFor(mc: String): Int {
        val m = try { mojangVersion(mc).optJSONObject("javaVersion")?.optInt("majorVersion", 0) ?: 0 } catch (e: Exception) { 0 }
        val v = if (m > 0) m else if (mcAtLeast(mc, "1.20.5")) 21 else 17
        return if (v < 17) 17 else v
    }

    fun mcVersions(sw: Software): List<String> = when (sw) {
        Software.VANILLA -> releases()
        Software.PAPER -> paperVersions()
        Software.SPIGOT -> releases().filter { mcAtLeast(it, "1.17") }
        Software.FABRIC -> fabricGame()
        Software.FORGE -> forgeMap().keys.sortedWith(verComparator.reversed())
        Software.NEOFORGE -> neoMap().keys.sortedWith(verComparator.reversed())
        Software.CUSTOM -> emptyList()
    }

    fun loaderVersions(sw: Software, mc: String): List<String> = when (sw) {
        Software.FABRIC -> fabricLoaders()
        Software.FORGE -> forgeMap()[mc] ?: emptyList()
        Software.NEOFORGE -> neoMap()[mc] ?: emptyList()
        else -> emptyList()
    }

    private fun paperVersions(): List<String> = cached("paper") {
        val rx = Regex("^\\d+\\.\\d+(\\.\\d+)?$")
        val list = try {
            val a = Net.json("https://api.papermc.io/v2/projects/paper").getJSONArray("versions")
            List(a.length()) { a.getString(it) }
        } catch (e: Exception) {
            val v = Net.json("https://fill.papermc.io/v3/projects/paper").getJSONObject("versions")
            v.keys().asSequence().flatMap { k -> val a = v.getJSONArray(k); (0 until a.length()).map { a.getString(it) } }.toList()
        }
        list.filter { rx.matches(it) }.sortedWith(verComparator.reversed())
    }

    private fun fabricGame(): List<String> = cached("fabricGame") {
        val a = Net.jsonArray("https://meta.fabricmc.net/v2/versions/game")
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optBoolean("stable") }.map { it.getString("version") }
    }

    private fun fabricLoaders(): List<String> = cached("fabricLoader") {
        val a = Net.jsonArray("https://meta.fabricmc.net/v2/versions/loader")
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optBoolean("stable") }.map { it.getString("version") }
    }

    fun fabricInstaller(): String {
        val a = Net.jsonArray("https://meta.fabricmc.net/v2/versions/installer")
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.optBoolean("stable")) return o.getString("version") }
        throw IOException("No stable Fabric installer found")
    }

    private fun mavenVersions(url: String): List<String> =
        Regex("<version>([^<]+)</version>").findAll(Net.text(url)).map { it.groupValues[1] }.toList()

    /** MC version -> Forge versions (newest first). Only 1.17.1+ (Java 17+, @argfile launch). */
    private fun forgeMap(): Map<String, List<String>> = cached("forge") {
        val rx = Regex("^(\\d+\\.\\d+(?:\\.\\d+)?)-(\\d[\\d.]*)$")
        val m = LinkedHashMap<String, MutableList<String>>()
        for (v in mavenVersions("https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml")) {
            val g = rx.matchEntire(v)?.groupValues ?: continue
            if (!mcAtLeast(g[1], "1.17.1")) continue
            m.getOrPut(g[1]) { mutableListOf() }.add(g[2])
        }
        m.mapValues { it.value.sortedWith(verComparator.reversed()) }
    }

    /** NeoForge "21.1.77" -> Minecraft 1.21.1. Versions using the newer numbering scheme are not listed. */
    private fun neoMap(): Map<String, List<String>> = cached("neo") {
        val rx = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(-beta)?$")
        val m = LinkedHashMap<String, MutableList<String>>()
        for (v in mavenVersions("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml")) {
            val g = rx.matchEntire(v)?.groupValues ?: continue
            val major = g[1].toInt(); val minor = g[2].toInt()
            if (major < 20 || major > 25) continue
            val mc = "1.$major" + if (minor == 0) "" else ".$minor"
            m.getOrPut(mc) { mutableListOf() }.add(v)
        }
        m.mapValues { it.value.sortedWith(verComparator.reversed()) }
    }
}
