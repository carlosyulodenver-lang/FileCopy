package com.blockhost

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

class InstallUi(val log: (String) -> Unit, val progress: (Float) -> Unit)

object Installer {

    fun writeEula(dir: File) {
        File(dir, "eula.txt").writeText("#By changing the setting below to TRUE you are indicating your agreement to the Minecraft EULA (https://aka.ms/MinecraftEULA).\neula=true\n")
    }

    /** Blocking. Installs everything needed to launch [cfg]; returns the config with launch args and installed=true. */
    fun install(
        ctx: Context, rt: JavaRuntime, cfg: ServerConfig, jarUri: Uri?,
        initialProps: Map<String, String>, ui: InstallUi
    ): ServerConfig {
        val dir = cfg.dir
        if (!dir.isInside(File(dir.parentFile!!.parentFile!!, "servers"))) throw IOException("Invalid server path")
        dir.mkdirs()
        if (!dir.isDirectory || !dir.canWrite()) throw IOException("Cannot write to ${dir.path}")
        val free = dir.usableSpace
        if (free < 400L * 1024 * 1024) throw IOException("Not enough free storage (${fmtBytes(free)} free, ~400 MB needed)")

        val extraPkgs = if (cfg.software == Software.SPIGOT) listOf("git", "ca-certificates") else emptyList()
        if (rt.javaHome(cfg.javaMajor) == null || (extraPkgs.isNotEmpty() && !File(ctx.filesDir, "runtime/usr/bin/git").exists())) {
            ui.log("Installing Java ${cfg.javaMajor} runtime (one-time, ~200 MB)…")
            rt.install(cfg.javaMajor, extraPkgs, ui.log) { ui.progress(0.40f * it) }
        } else ui.progress(0.40f)

        val sp: (Float) -> Unit = { ui.progress(0.40f + 0.50f * it) }
        val launch: List<String> = when (cfg.software) {
            Software.VANILLA -> vanilla(cfg, dir, ui, sp)
            Software.PAPER -> paper(cfg, dir, ui, sp)
            Software.FABRIC -> fabric(cfg, dir, ui, sp)
            Software.FORGE -> forgeLike(ctx, rt, cfg, dir, ui, sp, forge = true)
            Software.NEOFORGE -> forgeLike(ctx, rt, cfg, dir, ui, sp, forge = false)
            Software.SPIGOT -> spigot(ctx, rt, cfg, dir, ui, sp)
            Software.CUSTOM -> custom(ctx, jarUri, dir, ui)
        }

        ui.log("Writing configuration…")
        File(dir, "tmp").mkdirs()
        val props = ServerProps.read(dir)
        for ((k, v) in initialProps) props.putIfAbsent(k, v)
        ServerProps.write(dir, props)
        if (cfg.eulaAccepted) writeEula(dir)
        ui.progress(1f)
        ui.log("Setup complete.")
        return cfg.copy(launch = launch, installed = true)
    }

    private fun prog(ui: InstallUi, sp: (Float) -> Unit, name: String): (Long, Long) -> Unit {
        var last = 0L
        return { d, t ->
            if (t > 0) sp(d / t.toFloat())
            val now = System.currentTimeMillis()
            if (now - last > 1500) { last = now; ui.log("  $name: ${fmtBytes(d)}${if (t > 0) " / ${fmtBytes(t)}" else ""}") }
        }
    }

    private fun vanilla(cfg: ServerConfig, dir: File, ui: InstallUi, sp: (Float) -> Unit): List<String> {
        ui.log("Looking up Minecraft ${cfg.mcVersion}…")
        val dl = Providers.mojangVersion(cfg.mcVersion).getJSONObject("downloads").optJSONObject("server")
            ?: throw IOException("Mojang publishes no server download for ${cfg.mcVersion}")
        val f = File(dir, "server.jar")
        ui.log("Downloading server.jar (SHA-1 verified)…")
        Net.download(dl.getString("url"), f, sha1 = dl.getString("sha1"), onProgress = prog(ui, sp, "server.jar"))
        validateJar(f)
        return listOf("-jar", "server.jar")
    }

    private fun paper(cfg: ServerConfig, dir: File, ui: InstallUi, sp: (Float) -> Unit): List<String> {
        val mc = cfg.mcVersion
        ui.log("Looking up latest Paper build for $mc…")
        var url = ""; var sha = ""
        try {
            val b = Net.json("https://api.papermc.io/v2/projects/paper/versions/$mc/builds").getJSONArray("builds")
            var pick = b.getJSONObject(b.length() - 1)
            for (i in b.length() - 1 downTo 0) if (b.getJSONObject(i).optString("channel") == "default") { pick = b.getJSONObject(i); break }
            val app = pick.getJSONObject("downloads").getJSONObject("application")
            url = "https://api.papermc.io/v2/projects/paper/versions/$mc/builds/${pick.getInt("build")}/downloads/${app.getString("name")}"
            sha = app.getString("sha256")
        } catch (e: Exception) {
            val d = Net.json("https://fill.papermc.io/v3/projects/paper/versions/$mc/builds/latest").getJSONObject("downloads").getJSONObject("server:default")
            url = d.getString("url"); sha = d.getJSONObject("checksums").getString("sha256")
        }
        val f = File(dir, "server.jar")
        ui.log("Downloading Paper (SHA-256 verified)…")
        Net.download(url, f, sha256 = sha, onProgress = prog(ui, sp, "paper.jar"))
        validateJar(f)
        return listOf("-jar", "server.jar")
    }

    private fun fabric(cfg: ServerConfig, dir: File, ui: InstallUi, sp: (Float) -> Unit): List<String> {
        val inst = Providers.fabricInstaller()
        ui.log("Downloading Fabric server launcher (loader ${cfg.loaderVersion})…")
        val f = File(dir, "fabric-server-launch.jar")
        Net.download("https://meta.fabricmc.net/v2/versions/loader/${cfg.mcVersion}/${cfg.loaderVersion}/$inst/server/jar", f, onProgress = prog(ui, sp, "fabric"))
        validateJar(f)
        File(dir, "mods").mkdirs()
        ui.log("Note: Fabric downloads the vanilla server and libraries on first start.")
        return listOf("-jar", "fabric-server-launch.jar")
    }

    /** Runs a command, streaming output to the log. Throws when the exit code is non-zero. */
    private fun runLogged(cmd: List<String>, dir: File, env: Map<String, String>, ui: InstallUi, what: String) {
        val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        p.outputStream.close()
        p.inputStream.bufferedReader().forEachLine { ui.log("  " + it.take(300)) }
        val code = p.waitFor()
        if (code != 0) throw IOException("$what failed (exit code $code). See the log above.")
    }

    private fun forgeLike(ctx: Context, rt: JavaRuntime, cfg: ServerConfig, dir: File, ui: InstallUi, sp: (Float) -> Unit, forge: Boolean): List<String> {
        val mc = cfg.mcVersion; val lv = cfg.loaderVersion
        val full = if (forge) "$mc-$lv" else lv
        val base = if (forge) "https://maven.minecraftforge.net/net/minecraftforge/forge/$full/forge-$full-installer.jar"
        else "https://maven.neoforged.net/releases/net/neoforged/neoforge/$lv/neoforge-$lv-installer.jar"
        val installer = File(dir, "installer.jar")
        ui.log("Downloading ${if (forge) "Forge" else "NeoForge"} installer…")
        val sha1 = try { Net.text("$base.sha1").trim().take(40) } catch (e: Exception) { null }
        if (sha1 == null) ui.log("  (no checksum published for this installer)")
        Net.download(base, installer, sha1 = sha1, onProgress = prog(ui, sp, "installer"))
        validateJar(installer)
        val jenv = rt.env(cfg.javaMajor, dir) ?: throw IOException("Java ${cfg.javaMajor} missing")
        ui.log("Running installer (downloads libraries; this can take several minutes)…")
        runLogged(listOf(jenv.bin.path, "-Xmx${minOf(cfg.memoryMb, 1024)}m", "-Djava.io.tmpdir=${File(dir, "tmp").path}") + jenv.sslFlags +
            listOf("-jar", "installer.jar", "--installServer"), dir, jenv.env, ui, "Installer")
        installer.delete(); File(dir, "installer.jar.log").delete()
        val args = if (forge) "libraries/net/minecraftforge/forge/$full/unix_args.txt" else "libraries/net/neoforged/neoforge/$lv/unix_args.txt"
        if (!File(dir, args).exists()) throw IOException("Installer finished but $args was not created. The loader version may be unsupported.")
        val jvmArgs = File(dir, "user_jvm_args.txt")
        if (!jvmArgs.exists()) jvmArgs.writeText("# Extra JVM arguments. Memory is set by BlockHost.\n")
        File(dir, "mods").mkdirs()
        return listOf("@user_jvm_args.txt", "@$args")
    }

    private fun spigot(ctx: Context, rt: JavaRuntime, cfg: ServerConfig, dir: File, ui: InstallUi, sp: (Float) -> Unit): List<String> {
        ui.log("Spigot cannot be redistributed; building it locally with BuildTools (can take 10-30+ minutes and ~1.5 GB RAM)…")
        val bt = File(dir, "buildtools").apply { mkdirs() }
        Net.download("https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar", File(bt, "BuildTools.jar"), onProgress = prog(ui, sp, "BuildTools"))
        validateJar(File(bt, "BuildTools.jar"))
        val jenv = rt.env(cfg.javaMajor, bt) ?: throw IOException("Java missing")
        runLogged(listOf(jenv.bin.path, "-Xmx1280m", "-Djava.io.tmpdir=${File(dir, "tmp").path}") + jenv.sslFlags +
            listOf("-jar", "BuildTools.jar", "--rev", cfg.mcVersion, "--output-dir", dir.path), bt, jenv.env, ui, "BuildTools")
        val built = dir.listFiles { f -> f.name.startsWith("spigot-") && f.name.endsWith(".jar") }?.firstOrNull()
            ?: throw IOException("BuildTools finished but produced no spigot jar")
        built.renameTo(File(dir, "server.jar"))
        bt.deleteRecursively()
        File(dir, "plugins").mkdirs()
        return listOf("-jar", "server.jar")
    }

    private fun custom(ctx: Context, uri: Uri?, dir: File, ui: InstallUi): List<String> {
        if (uri == null) throw IOException("Choose a JAR file first")
        ui.log("Importing ${displayName(ctx, uri)}…")
        val f = File(dir, "server.jar")
        ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } } ?: throw IOException("Cannot read the selected file")
        validateJar(f)
        return listOf("-jar", "server.jar")
    }
}
