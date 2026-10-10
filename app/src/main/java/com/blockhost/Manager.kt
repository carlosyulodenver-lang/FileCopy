package com.blockhost

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Stats(val rssMb: Int = -1, val cpuPct: Int = -1, val players: Int = -1, val maxPlayers: Int = -1)
data class InstallState(val logs: List<String> = emptyList(), val progress: Float = 0f, val running: Boolean = false, val error: String? = null, val doneId: String? = null)
data class Draft(val name: String, val software: Software, val mc: String, val loader: String, val memoryMb: Int, val base: File, val javaMajor: Int, val eula: Boolean, val preset: Int, val idle: Int)

/** Bounded console buffer. UI reads snapshots when [version] changes (coalesced to ~4 Hz). */
class LogBuffer(private val max: Int = 2000) {
    private val q = ArrayDeque<String>()
    @Volatile var dirty = false
    val version = MutableStateFlow(0)
    @Synchronized fun add(l: String) { if (q.size >= max) q.removeFirst(); q.addLast(l); dirty = true }
    @Synchronized fun snapshot(): List<String> = q.toList()
    @Synchronized fun clear() { q.clear(); dirty = true }
    fun flush() { if (dirty) { dirty = false; version.value++ } }
}

class LiveServer {
    val state = MutableStateFlow(RunState.STOPPED)
    val stats = MutableStateFlow(Stats())
    val error = MutableStateFlow<String?>(null)
    val log = LogBuffer()
    @Volatile var process: Process? = null
    @Volatile var stdin: BufferedWriter? = null
    @Volatile var idleStopping = false
    @Volatile var logPlayers = 0
    @Volatile var wake: WakeListener? = null
    var emptySince = 0L; var lastTicks = 0L; var lastAt = 0L
    var jobs: List<Job> = emptyList()
}

class Manager(private val app: BlockHostApp) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val servers = MutableStateFlow(app.store.loadAll())
    val install = MutableStateFlow<InstallState?>(null)
    val activity = MutableStateFlow(0 to 0)
    private val lives = ConcurrentHashMap<String, LiveServer>()

    fun live(id: String): LiveServer = lives.getOrPut(id) { LiveServer() }
    fun config(id: String): ServerConfig? = servers.value.firstOrNull { it.id == id }

    fun update(c: ServerConfig) {
        app.store.save(c)
        val cur = servers.value
        servers.value = (if (cur.any { it.id == c.id }) cur.map { if (it.id == c.id) c else it } else cur + c).sortedBy { it.name.lowercase() }
    }

    private fun refresh() {
        val run = lives.values.count { it.state.value in setOf(RunState.STARTING, RunState.RUNNING, RunState.STOPPING) } +
            (if (install.value?.running == true) 1 else 0)
        val sl = lives.values.count { it.state.value == RunState.SLEEPING }
        activity.value = run to sl
        if (run + sl > 0) try { ContextCompat.startForegroundService(app, Intent(app, HostService::class.java)) } catch (_: Exception) {}
    }

    // ---------------- create / install ----------------
    fun createServer(d: Draft, jarUri: Uri?) {
        if (install.value?.running == true) return
        val logs = ArrayList<String>()
        fun push(p: Float?, l: String?) {
            synchronized(logs) {
                if (l != null) { logs.add(l); if (logs.size > 300) logs.removeAt(0) }
                val cur = install.value ?: InstallState()
                install.value = cur.copy(logs = logs.toList(), progress = p ?: cur.progress)
            }
        }
        install.value = InstallState(running = true); refresh()
        scope.launch {
            val id = UUID.randomUUID().toString().take(8)
            val root = File(d.base, "servers/$id")
            try {
                val port = ServerProps.nextFreePort(servers.value)
                val cfg = ServerConfig(id, d.name.trim(), d.software, d.mc, d.loader, d.memoryMb, root.path, d.javaMajor, eulaAccepted = d.eula, idleMinutes = d.idle)
                val view = when (d.preset) { 0 -> 4; 1 -> 6; else -> 10 }
                val sim = when (d.preset) { 0 -> 3; 1 -> 4; else -> 6 }
                val pl = when (d.preset) { 0 -> 5; 1 -> 10; else -> 20 }
                val props = mapOf("motd" to cfg.name, "server-port" to port.toString(), "view-distance" to view.toString(),
                    "simulation-distance" to sim.toString(), "max-players" to pl.toString())
                val done = Installer.install(app, app.runtime, cfg, jarUri, props, InstallUi({ push(null, it) }, { push(it, null) }))
                update(done)
                install.value = (install.value ?: InstallState()).copy(running = false, doneId = id, progress = 1f)
            } catch (e: Exception) {
                root.deleteRecursively()
                push(null, "FAILED: ${e.message}")
                install.value = (install.value ?: InstallState()).copy(running = false, error = e.message ?: e.javaClass.simpleName)
            }
            refresh()
        }
    }

    fun installRuntime(major: Int, log: (String) -> Unit, progress: (Float) -> Unit, done: (String?) -> Unit) {
        scope.launch {
            try { app.runtime.install(major, emptyList(), log, progress); done(null) }
            catch (e: Exception) { done(e.message ?: e.javaClass.simpleName) }
        }
    }

    // ---------------- lifecycle ----------------
    private val ansi = Regex("\u001B\\[[;\\d]*[A-Za-z]")

    fun start(id: String): Boolean {
        val l = live(id)
        val err = startInternal(id, l)
        if (err != null) { l.error.value = err; l.log.add("[BlockHost] $err"); l.log.flush(); refresh() }
        return err == null
    }

    private fun startInternal(id: String, l: LiveServer): String? = synchronized(l) {
        val cfg = config(id) ?: return "Unknown server"
        if (l.state.value in setOf(RunState.STARTING, RunState.RUNNING, RunState.STOPPING)) return "Server is already running"
        if (!cfg.installed) return "Server is not installed"
        if (!cfg.eulaAccepted) return "You must accept the Minecraft EULA before starting"
        val j = app.runtime.env(cfg.javaMajor, cfg.dir) ?: return "Java ${cfg.javaMajor} runtime is not installed. Open Settings > Java runtime."
        val mem = memInfo(app)
        if (cfg.memoryMb > MemPolicy.maxSafe(mem.totalMb)) return "Memory (${cfg.memoryMb} MB) exceeds the safe limit of ${MemPolicy.maxSafe(mem.totalMb)} MB for this phone. Lower it in server settings."
        if (mem.availMb < cfg.memoryMb * 0.8) return "Only ${mem.availMb} MB RAM is free right now (${cfg.memoryMb} MB requested). Close other apps or lower memory."
        l.wake?.close(); l.wake = null
        val port = ServerProps.port(cfg.dir)
        try { ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress(port)) } }
        catch (e: Exception) { return "Port $port is already in use by another app or server." }
        if (cfg.eulaAccepted) Installer.writeEula(cfg.dir)

        val tmp = File(cfg.dir, "tmp").apply { mkdirs() }
        val cmd = listOf(j.bin.path, "-Xms${minOf(256, cfg.memoryMb)}m", "-Xmx${cfg.memoryMb}m", "-XX:+UseSerialGC",
            "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=${tmp.path}") + j.sslFlags + cfg.launch + "nogui"
        val pb = ProcessBuilder(cmd).directory(cfg.dir).redirectErrorStream(true)
        pb.environment().putAll(j.env)
        val p = try { pb.start() } catch (e: IOException) { return "Could not launch Java: ${e.message}" }

        l.error.value = null; l.idleStopping = false; l.logPlayers = 0; l.emptySince = 0; l.lastAt = 0
        l.log.add("[BlockHost] Starting ${cfg.name} (${cfg.software.label} ${cfg.mcVersion}) with ${cfg.memoryMb} MB…")
        l.process = p
        l.stdin = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))
        l.stats.value = Stats()
        l.state.value = RunState.STARTING
        val pid = pidOf(p)

        Thread({ readOutput(l, p) ; onExit(id, l, p) }, "mc-out-$id").apply { isDaemon = true }.start()
        val ticker = scope.launch { while (isActive) { delay(250); l.log.flush() } }
        val mon = scope.launch { monitor(id, l, p, pid) }
        l.jobs = listOf(ticker, mon)
        refresh()
        null
    }

    private fun readOutput(l: LiveServer, p: Process) {
        try {
            p.inputStream.bufferedReader().use { r ->
                while (true) {
                    val raw = r.readLine() ?: break
                    val line = ansi.replace(raw, "").take(2000)
                    l.log.add(line)
                    if (l.state.value == RunState.STARTING && line.contains("Done (") && line.contains("help")) l.state.value = RunState.RUNNING
                    if (line.contains("joined the game")) l.logPlayers++
                    else if (line.contains("left the game")) l.logPlayers = maxOf(0, l.logPlayers - 1)
                }
            }
        } catch (_: Exception) {}
    }

    private fun onExit(id: String, l: LiveServer, p: Process) {
        val code = try { p.waitFor() } catch (e: InterruptedException) { -1 }
        l.jobs.forEach { it.cancel() }
        try { l.stdin?.close() } catch (_: Exception) {}
        l.stdin = null; l.process = null
        val wasStopping = l.state.value == RunState.STOPPING
        val ok = wasStopping || code == 0
        l.stats.value = Stats()
        l.log.add("[BlockHost] Server process ended (exit code $code).")
        if (!ok) l.error.value = hint(code, l.log.snapshot().takeLast(80))
        val cfg = config(id)
        if (ok && l.idleStopping && cfg != null && cfg.wakeOnJoin) {
            val port = ServerProps.port(cfg.dir)
            val w = WakeListener(port, "§e[Sleeping] §7${cfg.name} §8— join to wake it up") { scope.launch { wake(id) } }
            if (w.start()) { l.wake = w; l.state.value = RunState.SLEEPING; l.log.add("[BlockHost] Sleeping. Listening on port $port; a join attempt will wake the server.") }
            else { l.state.value = RunState.STOPPED; l.error.value = "Server stopped for idle, but port $port could not be reserved for wake-on-join." }
        } else l.state.value = if (ok) RunState.STOPPED else RunState.CRASHED
        l.log.flush(); refresh()
    }

    private fun wake(id: String) {
        val l = live(id)
        l.log.add("[BlockHost] Player connection detected — waking server.")
        l.wake?.close(); l.wake = null
        l.state.value = RunState.STOPPED
        start(id)
    }

    private fun hint(code: Int, tail: List<String>): String {
        val t = tail.joinToString("\n")
        return when {
            "UnsupportedClassVersionError" in t -> "This server needs a newer Java than the one used. Reinstall the Java runtime or pick a matching version."
            "OutOfMemoryError" in t -> "Out of memory. Raise the memory preset or lower view distance."
            "Address already in use" in t || "FAILED TO BIND" in t.uppercase() -> "The port is already in use. Change server-port in the properties editor."
            code == 137 || code == 9 -> "The process was killed (exit $code), most likely by Android because the phone ran low on memory. Lower memory use and disable battery restrictions."
            code == 126 || code == 127 -> "Java could not be executed (exit $code). Reinstall the Java runtime in Settings."
            "Could not find or load main class" in t || "Error: Unable to access jarfile" in t -> "The server JAR is missing or damaged. Check the Files tab."
            else -> "Server exited unexpectedly (exit code $code). See the console for details."
        }
    }

    fun sendCommand(id: String, cmd: String): Boolean {
        val l = live(id)
        if (l.state.value != RunState.RUNNING && l.state.value != RunState.STARTING) return false
        val w = l.stdin ?: return false
        return try {
            synchronized(w) { w.write(cmd.replace("\r", "").replace("\n", " ")); w.write("\n"); w.flush() }; true
        } catch (e: IOException) { false }
    }

    fun stop(id: String) {
        val l = live(id); val p = l.process ?: return
        if (l.state.value != RunState.STARTING && l.state.value != RunState.RUNNING) return
        l.state.value = RunState.STOPPING; refresh()
        sendRaw(l, "stop")
        scope.launch {
            delay(45_000)
            if (p.isAlive) { l.log.add("[BlockHost] Server did not stop within 45 s; terminating."); p.destroy(); delay(10_000); if (p.isAlive) p.destroyForcibly() }
        }
    }

    private fun sendRaw(l: LiveServer, cmd: String) {
        val w = l.stdin ?: return
        try { synchronized(w) { w.write(cmd); w.write("\n"); w.flush() } } catch (_: IOException) {}
    }

    fun kill(id: String) { live(id).process?.destroyForcibly() }

    fun restart(id: String) {
        val l = live(id)
        scope.launch {
            stop(id)
            val end = SystemClock.elapsedRealtime() + 120_000
            while (SystemClock.elapsedRealtime() < end && l.state.value in setOf(RunState.STOPPING, RunState.STARTING, RunState.RUNNING)) delay(500)
            if (l.state.value in setOf(RunState.STOPPED, RunState.CRASHED, RunState.SLEEPING)) start(id)
            else l.error.value = "Restart aborted: server did not stop in time."
        }
    }

    /** Cancels wake-on-join without starting the server. */
    fun cancelSleep(id: String) {
        val l = live(id); l.wake?.close(); l.wake = null
        if (l.state.value == RunState.SLEEPING) l.state.value = RunState.STOPPED
        refresh()
    }

    fun stopAll() { lives.keys.forEach { stop(it); cancelSleep(it) } }

    fun delete(id: String): String? {
        val l = live(id)
        if (l.state.value in setOf(RunState.STARTING, RunState.RUNNING, RunState.STOPPING)) return "Stop the server first"
        cancelSleep(id)
        val cfg = config(id) ?: return null
        if (!cfg.dir.deleteRecursively() && cfg.dir.exists()) return "Could not delete all files"
        app.store.delete(id); lives.remove(id)
        servers.value = servers.value.filter { it.id != id }
        return null
    }

    // ---------------- monitoring & idle sleep ----------------
    private fun pidOf(p: Process): Int = try {
        val f = p.javaClass.getDeclaredField("pid"); f.isAccessible = true; f.getInt(p)
    } catch (e: Exception) { -1 }

    private fun readProc(pid: Int): Pair<Long, Long>? = try {
        val after = File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(' ')
        val ticks = after[11].toLong() + after[12].toLong()
        val rssKb = File("/proc/$pid/status").readLines().first { it.startsWith("VmRSS:") }.filter { it.isDigit() }.toLong()
        rssKb to ticks
    } catch (e: Exception) { null }

    private suspend fun monitor(id: String, l: LiveServer, p: Process, pid: Int) {
        var tick = 0
        while (currentCoroutineContext().isActive && p.isAlive) {
            delay(2000); tick++
            if (pid > 0) readProc(pid)?.let { (rssKb, ticks) ->
                val now = SystemClock.elapsedRealtime()
                val cpu = if (l.lastAt > 0) ((ticks - l.lastTicks) * 1000.0 / (now - l.lastAt)).toInt() else 0
                l.lastTicks = ticks; l.lastAt = now
                l.stats.value = l.stats.value.copy(rssMb = (rssKb / 1024).toInt(), cpuPct = cpu)
            }
            val st = l.state.value
            if (st == RunState.STARTING || (st == RunState.RUNNING && tick % 3 == 0)) {
                val cfg = config(id) ?: continue
                val s = McPing.ping(ServerProps.bindHost(cfg.dir), ServerProps.port(cfg.dir))
                if (s != null) {
                    if (l.state.value == RunState.STARTING) l.state.value = RunState.RUNNING
                    l.stats.value = l.stats.value.copy(players = s.online, maxPlayers = s.max)
                }
                val online = s?.online ?: l.logPlayers
                if (cfg.idleMinutes > 0 && l.state.value == RunState.RUNNING) {
                    if (online > 0) l.emptySince = 0
                    else if (l.emptySince == 0L) l.emptySince = SystemClock.elapsedRealtime()
                    else if (SystemClock.elapsedRealtime() - l.emptySince >= cfg.idleMinutes * 60_000L) {
                        l.log.add("[BlockHost] No players for ${cfg.idleMinutes} min — stopping server to save battery.")
                        l.idleStopping = true; l.emptySince = 0
                        stop(id)
                    }
                } else l.emptySince = 0
            }
        }
    }
}
