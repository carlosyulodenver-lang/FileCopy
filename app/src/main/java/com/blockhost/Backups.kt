package com.blockhost

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object Backups {
    private val skip = setOf("libraries", "logs", "cache", "versions", "tmp", "crash-reports", "buildtools", ".fabric", "installer.jar")

    fun dirFor(ctx: Context, id: String) = File(ctx.filesDir, "backups/$id").apply { mkdirs() }
    fun list(ctx: Context, id: String): List<File> =
        dirFor(ctx, id).listFiles { f -> f.extension == "zip" }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Creates a zip of the server (without re-downloadable files). Optionally copies it to a user-chosen SAF folder. */
    fun create(ctx: Context, cfg: ServerConfig, keep: Int, tree: String?, log: (String) -> Unit): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dirFor(ctx, cfg.id), "${safeFileName(cfg.name)}-$stamp.zip")
        val part = File(out.path + ".part")
        try {
            ZipOutputStream(part.outputStream().buffered()).use { z ->
                fun walk(f: File, rel: String) {
                    if (f.isDirectory) {
                        if (rel.isNotEmpty() && f.name in skip) return
                        f.listFiles()?.forEach { walk(it, if (rel.isEmpty()) it.name else "$rel/${it.name}") }
                    } else if (rel !in skip && !f.name.endsWith(".part") && !f.name.endsWith(".lock")) {
                        try {
                            z.putNextEntry(ZipEntry(rel).apply { time = f.lastModified() })
                            f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                        } catch (e: IOException) { log("Skipped $rel: ${e.message}") }
                    }
                }
                walk(cfg.dir, "")
            }
            if (!part.renameTo(out)) throw IOException("Could not finalize backup")
        } catch (e: Exception) { part.delete(); out.delete(); throw e }
        // retention
        list(ctx, cfg.id).drop(maxOf(1, keep)).forEach { it.delete() }
        if (tree != null) {
            val t = DocumentFile.fromTreeUri(ctx, Uri.parse(tree)) ?: throw IOException("Backup folder is no longer accessible")
            val d = t.createFile("application/zip", out.name) ?: throw IOException("Could not create file in the backup folder")
            ctx.contentResolver.openOutputStream(d.uri)?.use { o -> out.inputStream().use { it.copyTo(o) } } ?: throw IOException("Cannot write to backup folder")
            log("Copied to external folder.")
        }
        return out
    }

    fun restore(cfg: ServerConfig, zip: File) {
        val root = cfg.dir
        ZipFile(zip).use { z ->
            val entries = z.entries().toList()
            for (e in entries) { val t = File(root, e.name); if (!t.isInside(root)) throw IOException("Backup contains an unsafe path: ${e.name}") }
            for (e in entries) {
                val t = File(root, e.name)
                if (e.isDirectory) t.mkdirs() else { t.parentFile?.mkdirs(); z.getInputStream(e).use { i -> t.outputStream().use { i.copyTo(it) } } }
            }
        }
    }
}
