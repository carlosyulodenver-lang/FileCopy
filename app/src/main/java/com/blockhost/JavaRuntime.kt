package com.blockhost

import android.content.Context
import android.os.Build
import android.system.Os
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

class JavaEnv(val home: File, val bin: File, val env: Map<String, String>, val sslFlags: List<String>)

/**
 * Android cannot run desktop Java JARs with its own runtime (ART/Dalvik). BlockHost therefore installs a
 * bionic-built OpenJDK (the Termux OpenJDK packages, aarch64/x86_64) into app-private storage and runs the
 * server as a separate OS process. Alternatively the user can import their own bionic-compatible JDK archive.
 *
 * NOTE: this path has not been run on a device by the author of this code (no device in the build sandbox).
 */
class JavaRuntime(private val ctx: Context) {
    private val prefix = File(ctx.filesDir, "runtime/usr")
    private val customRoot = File(ctx.filesDir, "runtime/custom")
    private val termuxPrefix = "/data/data/com.termux/files/usr"
    private val repo = "https://packages.termux.dev/apt/termux-main"

    private fun termuxArch(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "x86_64" -> "x86_64"
        else -> throw IOException("Unsupported CPU (${Build.SUPPORTED_ABIS.joinToString()}). A 64-bit ARM phone is required.")
    }

    fun javaHome(major: Int): File? {
        val cands = listOf(File(prefix, "lib/jvm/java-$major-openjdk"), File(customRoot, "$major"))
        return cands.firstOrNull { File(it, "bin/java").exists() }
    }

    fun installedMajors(): List<Int> = listOf(8, 11, 17, 21, 25).filter { javaHome(it) != null }

    fun env(major: Int, serverDir: File): JavaEnv? {
        val home = javaHome(major) ?: return null
        val tmp = File(serverDir, "tmp").apply { mkdirs() }
        val libs = listOf(File(home, "lib"), File(home, "lib/server"), File(home, "lib/jli"), File(prefix, "lib"))
            .joinToString(":") { it.path }
        val env = hashMapOf(
            "JAVA_HOME" to home.path, "LD_LIBRARY_PATH" to libs,
            "PATH" to "${File(home, "bin").path}:${File(prefix, "bin").path}:/system/bin",
            "HOME" to serverDir.path, "TMPDIR" to tmp.path, "LANG" to "en_US.UTF-8",
            "GIT_EXEC_PATH" to File(prefix, "libexec/git-core").path,
            "GIT_TEMPLATE_DIR" to File(prefix, "share/git-core/templates").path,
            "GIT_SSL_CAINFO" to File(prefix, "etc/tls/cert.pem").path
        )
        return JavaEnv(home, File(home, "bin/java"), env, trustFlags(home))
    }

    /** If the JDK has no usable cacerts, build a truststore from Android's CA store so HTTPS/auth works. */
    private fun trustFlags(home: File): List<String> {
        val c = File(home, "lib/security/cacerts")
        if (c.exists() && c.length() > 0) return emptyList()
        return try {
            val f = File(ctx.filesDir, "runtime/android-cacerts.p12")
            if (!f.exists()) {
                val src = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
                val p12 = KeyStore.getInstance("PKCS12").apply { load(null, null) }
                for (a in src.aliases()) src.getCertificate(a)?.let { p12.setCertificateEntry(a.take(80), it) }
                FileOutputStream(f).use { p12.store(it, "changeit".toCharArray()) }
            }
            listOf("-Djavax.net.ssl.trustStore=${f.path}", "-Djavax.net.ssl.trustStoreType=PKCS12",
                "-Djavax.net.ssl.trustStorePassword=changeit")
        } catch (e: Exception) { emptyList() }
    }

    private class Pkg(val name: String, val filename: String, val sha256: String, val deps: List<String>)

    private fun parseIndex(text: String): Map<String, Pkg> {
        val map = HashMap<String, Pkg>()
        for (stanza in text.split("\n\n")) {
            val f = HashMap<String, String>()
            for (line in stanza.lines()) {
                if (line.startsWith(" ") || !line.contains(':')) continue
                f[line.substringBefore(':')] = line.substringAfter(':').trim()
            }
            val n = f["Package"] ?: continue
            val deps = (f["Depends"] ?: "").split(',').map { it.trim().substringBefore('|').trim().substringBefore(' ').trim() }.filter { it.isNotEmpty() }
            map[n] = Pkg(n, f["Filename"] ?: continue, f["SHA256"] ?: "", deps)
        }
        return map
    }

    fun install(major: Int, extra: List<String>, log: (String) -> Unit, progress: (Float) -> Unit) {
        val arch = termuxArch()
        val base = "$repo/dists/stable/main/binary-$arch"
        log("Fetching package index ($arch)…")
        val text = try { Net.text("$base/Packages") } catch (e: Exception) {
            val c = Net.open("$base/Packages.gz")
            try { GZIPInputStream(c.inputStream).bufferedReader().readText() } finally { c.disconnect() }
        }
        val index = parseIndex(text)
        val root = "openjdk-$major"
        if (root !in index) throw IOException("OpenJDK $major is not available for $arch in the runtime repository.")
        val order = LinkedHashSet<String>()
        fun visit(n: String) {
            if (n in order) return
            val p = index[n] ?: return
            order.add(n); p.deps.forEach { visit(it) }
        }
        visit(root); extra.forEach { visit(it) }
        log("Installing ${order.size} packages…")
        prefix.mkdirs()
        val cache = File(ctx.cacheDir, "debs").apply { mkdirs() }
        var i = 0
        for (n in order) {
            val p = index[n]!!
            val deb = File(cache, "$n.deb")
            log("Downloading $n…")
            Net.download("$repo/${p.filename}", deb, sha256 = p.sha256.ifBlank { null })
            extractDeb(deb)
            deb.delete()
            i++; progress(i / order.size.toFloat())
        }
        val home = javaHome(major) ?: throw IOException("Runtime installed but java binary not found (layout changed?).")
        File(home, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
        log("Java $major ready.")
    }

    private fun extractDeb(deb: File) {
        ArArchiveInputStream(BufferedInputStream(deb.inputStream())).use { ar ->
            while (true) {
                val e = ar.nextArEntry ?: break
                if (e.name.startsWith("data.tar")) {
                    val raw: InputStream = when {
                        e.name.endsWith(".xz") -> XZCompressorInputStream(ar)
                        e.name.endsWith(".gz") -> GzipCompressorInputStream(ar)
                        else -> throw IOException("Unsupported package compression: ${e.name}")
                    }
                    untar(TarArchiveInputStream(raw), prefix, "data/data/com.termux/files/usr/")
                    return
                }
            }
            throw IOException("Malformed package ${deb.name}")
        }
    }

    private fun untar(tar: TarArchiveInputStream, dest: File, strip: String) {
        while (true) {
            val e: TarArchiveEntry = tar.nextTarEntry ?: break
            var name = e.name.removePrefix("./")
            if (strip.isNotEmpty()) { if (!name.startsWith(strip)) continue; name = name.removePrefix(strip) }
            if (name.isBlank()) continue
            val t = File(dest, name)
            if (!t.isInside(dest)) continue // zip-slip guard
            when {
                e.isDirectory -> t.mkdirs()
                e.isSymbolicLink -> {
                    t.parentFile?.mkdirs(); t.delete()
                    var target = e.linkName
                    if (target.startsWith(termuxPrefix)) target = prefix.path + target.removePrefix(termuxPrefix)
                    Os.symlink(target, t.path)
                }
                e.isLink -> {
                    val src = File(dest, e.linkName.removePrefix("./").removePrefix(strip))
                    if (src.isInside(dest) && src.exists()) { t.parentFile?.mkdirs(); src.copyTo(t, true) }
                }
                else -> {
                    t.parentFile?.mkdirs(); if (t.exists()) t.delete()
                    FileOutputStream(t).use { tar.copyTo(it) }
                    t.setReadable(true, false)
                    if (e.mode and 0b001001001 != 0) t.setExecutable(true, false)
                }
            }
        }
    }

    /** Import a user-supplied JDK (.tar.gz / .tar.xz / .zip) built for Android/bionic (e.g. from Termux or Pojav). */
    fun importArchive(input: InputStream, fileName: String, major: Int, log: (String) -> Unit) {
        val dest = File(customRoot, "$major")
        dest.deleteRecursively(); dest.mkdirs()
        val bis = BufferedInputStream(input)
        val n = fileName.lowercase()
        when {
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> untar(TarArchiveInputStream(GzipCompressorInputStream(bis)), dest, "")
            n.endsWith(".tar.xz") -> untar(TarArchiveInputStream(XZCompressorInputStream(bis)), dest, "")
            n.endsWith(".zip") -> ZipInputStream(bis).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val t = File(dest, e.name)
                    if (!t.isInside(dest)) continue
                    if (e.isDirectory) t.mkdirs() else { t.parentFile?.mkdirs(); FileOutputStream(t).use { z.copyTo(it) } }
                }
            }
            else -> throw IOException("Use a .tar.gz, .tar.xz or .zip archive.")
        }
        // Flatten: find the directory that contains bin/java
        val javaBin = dest.walkTopDown().maxDepth(4).firstOrNull { it.name == "java" && it.parentFile?.name == "bin" }
            ?: throw IOException("No bin/java found in the archive.")
        val home = javaBin.parentFile!!.parentFile!!
        if (home != dest) {
            val tmp = File(customRoot, "$major.tmp"); tmp.deleteRecursively()
            home.renameTo(tmp); dest.deleteRecursively(); tmp.renameTo(dest)
        }
        dest.walkTopDown().filter { it.isFile && it.parentFile?.name == "bin" }.forEach { it.setExecutable(true, false) }
        log("Imported Java $major runtime.")
    }

    fun remove(major: Int) {
        File(prefix, "lib/jvm/java-$major-openjdk").deleteRecursively()
        File(customRoot, "$major").deleteRecursively()
    }
}
