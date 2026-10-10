package com.blockhost

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

object Net {
    private const val UA = "BlockHost/0.2 (Android; +local)"

    fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        return c
    }

    fun text(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    fun json(url: String) = JSONObject(text(url))
    fun jsonArray(url: String) = JSONArray(text(url))

    /** Downloads to dest via a .part file; verifies sha1/sha256 when given. Never leaves partial files. */
    fun download(
        url: String, dest: File, sha1: String? = null, sha256: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("Download failed: HTTP ${c.responseCode} ($url)")
            val total = c.contentLengthLong
            val want = (sha256 ?: sha1)?.lowercase()
            val md = when { sha256 != null -> MessageDigest.getInstance("SHA-256"); sha1 != null -> MessageDigest.getInstance("SHA-1"); else -> null }
            c.inputStream.use { ins ->
                part.outputStream().buffered().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                    while (ins.read(buf).also { n = it } >= 0) {
                        out.write(buf, 0, n); md?.update(buf, 0, n); done += n; onProgress(done, total)
                    }
                }
            }
            if (md != null) {
                val got = md.digest().joinToString("") { "%02x".format(it) }
                if (got != want) throw IOException("Checksum mismatch for ${dest.name}: expected $want, got $got")
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw IOException("Could not finalize ${dest.name}")
        } catch (e: Exception) {
            part.delete(); throw e
        } finally { c.disconnect() }
    }
}

fun File.isInside(root: File): Boolean {
    val r = root.canonicalPath; val p = canonicalPath
    return p == r || p.startsWith(r + File.separator)
}

fun validateJar(f: File) {
    try {
        ZipFile(f).use { z -> if (z.getEntry("META-INF/MANIFEST.MF") == null) throw IOException("not a runnable JAR (no manifest)") }
    } catch (e: IOException) {
        throw IOException("${f.name} is corrupted or not a valid JAR: ${e.message}")
    } catch (e: Exception) {
        throw IOException("${f.name} is corrupted or not a valid JAR")
    }
}

fun fmtBytes(b: Long): String = when {
    b < 1024 -> "$b B"; b < 1024 * 1024 -> "${b / 1024} KB"
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

fun displayName(ctx: Context, uri: Uri): String {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) return it.getString(0) ?: "file"
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
}

/** Strips any path components and odd characters from an imported file name. */
fun safeFileName(n: String): String {
    val base = n.substringAfterLast('/').substringAfterLast('\\').trim()
    val cleaned = base.replace(Regex("[^A-Za-z0-9._+\\- ]"), "_").trimStart('.')
    return cleaned.ifBlank { "file" }
}

val verComparator = Comparator<String> { a, b ->
    val x = Regex("\\d+").findAll(a).map { it.value.toIntOrNull() ?: 0 }.toList()
    val y = Regex("\\d+").findAll(b).map { it.value.toIntOrNull() ?: 0 }.toList()
    for (i in 0 until maxOf(x.size, y.size)) {
        val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
        if (c != 0) return@Comparator c
    }
    0
}
fun mcAtLeast(v: String, min: String) = verComparator.compare(v, min) >= 0

data class MemInfo(val totalMb: Int, val availMb: Int)

fun memInfo(ctx: Context): MemInfo {
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
    return MemInfo((mi.totalMem / 1048576).toInt(), (mi.availMem / 1048576).toInt())
}

/** Memory rules: the JVM heap may never exceed ~55% of physical RAM (Android + JVM overhead need the rest). */
object MemPolicy {
    private fun r(x: Int) = x / 128 * 128
    fun maxSafe(total: Int) = r((total * 0.55).toInt()).coerceIn(512, 12288)
    fun low(total: Int) = minOf(r(maxOf(512, (total * 0.15).toInt())), maxSafe(total))
    fun recommended(total: Int) = minOf(r(maxOf(1024, (total * 0.30).toInt())), maxSafe(total))
    fun high(total: Int) = maxSafe(total)
}

fun localAddresses(): List<String> = try {
    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { ni -> ni.inetAddresses.toList().filter { !it.isLoopbackAddress && it.address.size == 4 }.map { it.hostAddress ?: "" } }
        .filter { it.isNotBlank() }
} catch (e: Exception) { emptyList() }
