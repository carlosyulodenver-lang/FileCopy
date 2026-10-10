@file:OptIn(ExperimentalMaterial3Api::class)

package com.blockhost

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun Sec(t: String) { Text(t, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }

@Composable
fun Note(t: String) { Text(t, style = MaterialTheme.typography.bodySmall) }

@Composable
fun ServerScreen(id: String, onBack: () -> Unit) {
    val servers by app.manager.servers.collectAsState()
    val c = servers.firstOrNull { it.id == id }
    if (c == null) { LaunchedEffect(Unit) { onBack() }; return }
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Console", "Files", "Properties", "Network", "Backups", "Settings")
    Page(c.name, onBack) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> ConsoleTab(c); 1 -> FilesTab(c); 2 -> PropsTab(c)
            3 -> NetworkTab(c); 4 -> BackupTab(c); else -> ServerSettingsTab(c, onBack)
        }
    }
}

@Composable
fun ConsoleTab(c: ServerConfig) {
    val live = remember(c.id) { app.manager.live(c.id) }
    val st by live.state.collectAsState()
    val stats by live.stats.collectAsState()
    val err by live.error.collectAsState()
    val ver by live.log.version.collectAsState()
    val lines = remember(ver) { live.log.snapshot() }
    val ls = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    var cmd by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    var dev by remember { mutableStateOf(memInfo(ctx)) }
    LaunchedEffect(Unit) { while (true) { dev = memInfo(ctx); delay(3000) } }
    LaunchedEffect(ver, follow) { if (follow && lines.isNotEmpty()) ls.scrollToItem(lines.size - 1) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("State: ${st.label}", style = MaterialTheme.typography.titleSmall)
        Text(buildString {
            append("Server RAM: ${if (stats.rssMb >= 0) "${stats.rssMb} MB" else "–"} / ${c.memoryMb} MB heap cap")
            append("  •  CPU: ${if (stats.cpuPct >= 0) "${stats.cpuPct}%" else "–"} (100% = 1 core)")
            if (stats.players >= 0) append("  •  Players: ${stats.players}/${stats.maxPlayers}")
        }, style = MaterialTheme.typography.bodySmall)
        Text("Device RAM: ${dev.availMb} MB free of ${dev.totalMb} MB", style = MaterialTheme.typography.bodySmall)
        err?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { live.error.value = null }) { Text("Dismiss") }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val canStart = st == RunState.STOPPED || st == RunState.CRASHED || st == RunState.SLEEPING
            Button(enabled = canStart, onClick = { app.manager.start(c.id) }) { Text("Start") }
            OutlinedButton(enabled = st == RunState.RUNNING || st == RunState.STARTING, onClick = { app.manager.stop(c.id) }) { Text("Stop") }
            OutlinedButton(enabled = st == RunState.RUNNING, onClick = { app.manager.restart(c.id) }) { Text("Restart") }
            if (st == RunState.STOPPING) OutlinedButton(onClick = { app.manager.kill(c.id) }) { Text("Force kill") }
            if (st == RunState.SLEEPING) OutlinedButton(onClick = { app.manager.cancelSleep(c.id) }) { Text("Cancel sleep") }
            TextButton(onClick = { live.log.clear(); live.log.flush() }) { Text("Clear") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(follow, { follow = it }); Text("Follow output", style = MaterialTheme.typography.bodySmall) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = ls) {
            items(lines.size) { i -> Text(lines[i], fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 13.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(cmd, { cmd = it }, singleLine = true, label = { Text("Command (without /)") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Button(enabled = cmd.isNotBlank() && (st == RunState.RUNNING || st == RunState.STARTING), onClick = {
                if (!app.manager.sendCommand(c.id, cmd.trim())) live.error.value = "Could not send command: server is not accepting input."
                cmd = ""
            }) { Text("Send") }
        }
    }
}

private val textExt = setOf("properties", "yml", "yaml", "json", "txt", "toml", "cfg", "conf", "json5", "log", "ini", "mcmeta", "secrets")

@Composable
fun FilesTab(c: ServerConfig) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rel by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<File?>(null) }
    var editText by remember { mutableStateOf("") }
    var delTarget by remember { mutableStateOf<File?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    val cur = File(c.dir, rel)
    val safe = cur.isInside(c.dir) && cur.isDirectory
    val dir = if (safe) cur else c.dir
    val entries = remember(rel, tick) { (dir.listFiles()?.toList() ?: emptyList()).sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch(Dispatchers.IO) {
            val res = uris.map { u ->
                try {
                    val n = safeFileName(displayName(ctx, u)); val t = File(dir, n)
                    if (!t.isInside(c.dir)) "Rejected $n"
                    else if (t.exists()) "Skipped $n (already exists — delete it first)"
                    else { ctx.contentResolver.openInputStream(u)!!.use { i -> t.outputStream().use { o -> i.copyTo(o) } }; "Imported $n" }
                } catch (e: Exception) { "Failed: ${e.message}" }
            }
            withContext(Dispatchers.Main) { msg = res.joinToString("\n"); tick++ }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("/" + rel, fontFamily = FontFamily.Monospace)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(enabled = rel.isNotEmpty(), onClick = { rel = rel.substringBeforeLast('/', "") }) { Text("Up") }
            Button(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import here") }
            OutlinedButton(onClick = { folderName = ""; newFolder = true }) { Text("New folder") }
            listOf("mods", "plugins", "config", "world").filter { File(c.dir, it).isDirectory }.forEach { q ->
                AssistChip(onClick = { rel = q }, label = { Text(q) })
            }
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(Modifier.weight(1f)) {
            items(entries, key = { it.path }) { f ->
                Row(Modifier.fillMaxWidth().clickable {
                    if (f.isDirectory) rel = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                    else if (f.extension.lowercase() in textExt && f.length() <= 512 * 1024) { editText = f.readText(); editing = f }
                    else msg = "${f.name}: ${fmtBytes(f.length())} (binary or too large to edit)"
                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (f.isDirectory) "▸ " else "") + f.name)
                        Text(if (f.isDirectory) "folder" else fmtBytes(f.length()), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { delTarget = f }) { Text("Delete") }
                }
                HorizontalDivider()
            }
        }
    }
    editing?.let { f ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(f.name) },
            text = { OutlinedTextField(editText, { editText = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp)) },
            confirmButton = { TextButton(onClick = {
                try { val t = File(f.path + ".tmp"); t.writeText(editText); t.renameTo(f); msg = "Saved ${f.name}" } catch (e: Exception) { msg = "Save failed: ${e.message}" }
                editing = null; tick++
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
    delTarget?.let { f ->
        AlertDialog(onDismissRequest = { delTarget = null }, title = { Text("Delete ${f.name}?") },
            text = { Text(if (f.isDirectory) "This folder and everything in it will be deleted permanently." else "This file will be deleted permanently.") },
            confirmButton = { TextButton(onClick = {
                msg = if (f.isInside(c.dir) && f != c.dir && f.deleteRecursively()) "Deleted ${f.name}" else "Could not delete ${f.name}"
                delTarget = null; tick++
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { delTarget = null }) { Text("Cancel") } })
    }
    if (newFolder) AlertDialog(onDismissRequest = { newFolder = false }, title = { Text("New folder") },
        text = { OutlinedTextField(folderName, { folderName = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val n = safeFileName(folderName); val t = File(dir, n)
            msg = if (t.isInside(c.dir) && t.mkdirs()) "Created $n" else "Could not create $n"
            newFolder = false; tick++
        }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { newFolder = false }) { Text("Cancel") } })
}

@Composable
fun PropsTab(c: ServerConfig) {
    val running = app.manager.live(c.id).state.collectAsState().value in setOf(RunState.STARTING, RunState.RUNNING)
    var tick by remember { mutableIntStateOf(0) }
    val orig = remember(c.id, tick) { ServerProps.read(c.dir) }
    val edits = remember(c.id, tick) { mutableStateMapOf<String, String>() }
    var msg by remember { mutableStateOf<String?>(null) }
    val known = ServerProps.defs.map { it.key }.toSet()
    val extra = orig.keys.filter { it !in known }

    fun cur(d: PropDef) = edits[d.key] ?: orig[d.key] ?: d.def
    Column(Modifier.fillMaxSize()) {
        if (!c.dir.resolve("server.properties").exists()) Note("  server.properties does not exist yet; the server creates it on first start. Saving writes only the values you changed.")
        if (running) Note("  Server is running: changes apply after restart.")
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ServerProps.defs, key = { it.key }) { d ->
                val v = cur(d)
                val e = ServerProps.validate(d, v)
                when (d.type) {
                    PType.BOOL -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.key, Modifier.weight(1f)); Switch(v == "true", { edits[d.key] = it.toString() })
                    }
                    PType.ENUM -> Column {
                        Text(d.key, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            d.options.forEach { o -> FilterChip(selected = v == o, onClick = { edits[d.key] = o }, label = { Text(o) }) }
                        }
                    }
                    else -> OutlinedTextField(v, { edits[d.key] = it }, label = { Text(d.key) }, singleLine = true, isError = e != null,
                        supportingText = { if (e != null) Text(e) }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (extra.isNotEmpty()) item { Sec("Other keys in your file (loader/plugin specific)") }
            items(extra, key = { "x$it" }) { k ->
                OutlinedTextField(edits[k] ?: orig[k] ?: "", { edits[k] = it }, label = { Text(k) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        msg?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val errs = ServerProps.defs.mapNotNull { d -> edits[d.key]?.let { v -> ServerProps.validate(d, v)?.let { "${d.key}: $it" } } }
                if (errs.isNotEmpty()) msg = "Fix first: " + errs.first()
                else try {
                    val out = LinkedHashMap(orig)
                    edits.forEach { (k, v) -> val d = ServerProps.defs.firstOrNull { it.key == k }
                        if (v != (orig[k] ?: d?.def ?: "")) out[k] = v }
                    ServerProps.write(c.dir, out); msg = "Saved."; tick++
                } catch (e: Exception) { msg = "Save failed: ${e.message}" }
            }) { Text("Save") }
            OutlinedButton(onClick = { tick++ }) { Text("Discard changes") }
        }
    }
}

@Composable
fun NetworkTab(c: ServerConfig) {
    val scope = rememberCoroutineScope()
    val port = remember(c.id) { ServerProps.port(c.dir) }
    val props = remember(c.id) { ServerProps.read(c.dir) }
    var addrs by remember { mutableStateOf(localAddresses()) }
    var result by remember { mutableStateOf<String?>(null) }
    var pubIp by remember { mutableStateOf<String?>(null) }
    val tun by app.tunnel.state.collectAsState()
    var host by remember { mutableStateOf(app.prefs.getString("ssh_host")) }
    var sport by remember { mutableStateOf(app.prefs.getString("ssh_port", "22")) }
    var user by remember { mutableStateOf(app.prefs.getString("ssh_user")) }
    var rport by remember { mutableStateOf(app.prefs.getString("ssh_remote", port.toString())) }
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(app.tunnel.hasKey()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Sec("Connection addresses (port $port)")
        if (addrs.isEmpty()) Text("No network address found. Connect to Wi-Fi or enable hotspot.")
        addrs.forEach { Text("LAN: $it:$port", fontFamily = FontFamily.Monospace) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { addrs = localAddresses() }) { Text("Refresh") }
            OutlinedButton(onClick = {
                result = "Testing…"
                scope.launch(Dispatchers.IO) {
                    val s = McPing.ping(ServerProps.bindHost(c.dir), port)
                    val r = if (s != null) "Server answers locally: ${s.version}, ${s.online}/${s.max} players." else "No answer on port $port. The server is not running, still starting, or enable-status=false."
                    withContext(Dispatchers.Main) { result = r }
                }
            }) { Text("Test server") }
        }
        result?.let { Text(it) }
        Note("Players on the same Wi-Fi join with the LAN address. A phone hotspot works too (use the hotspot interface address).")

        Sec("Public access via router port forwarding")
        Note("1) Give the phone a fixed LAN IP (DHCP reservation). 2) In the router, forward TCP $port to that IP, port $port. 3) Share your public IP:$port. Carrier-grade NAT (most mobile data, some ISPs) makes this impossible — use the tunnel below.")
        OutlinedButton(onClick = {
            pubIp = "Looking up…"
            scope.launch(Dispatchers.IO) {
                val ip = try { Net.text("https://api.ipify.org").trim() } catch (e: Exception) { "failed: ${e.message}" }
                withContext(Dispatchers.Main) { pubIp = ip }
            }
        }) { Text("Look up public IP (contacts api.ipify.org)") }
        pubIp?.let { Text("Public IP: $it", fontFamily = FontFamily.Monospace) }
        Note("From inside your own network a self-test of your public IP often fails (NAT loopback), so BlockHost cannot reliably verify port forwarding. Ask a friend on mobile data to try, or use an external port checker.")

        Sec("Optional: reverse SSH tunnel (your own VPS)")
        Note("Needs a server you control (this is not a BlockHost service; any cost is your VPS's). sshd must allow remote forwards and GatewayPorts. Use an RSA/ECDSA key; the private key is stored encrypted in the Android Keystore.")
        OutlinedTextField(host, { host = it }, label = { Text("VPS host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(sport, { sport = it.filter(Char::isDigit) }, label = { Text("SSH port") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(user, { user = it }, label = { Text("User") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(rport, { rport = it.filter(Char::isDigit) }, label = { Text("Public port") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        if (hasKey) Row(verticalAlignment = Alignment.CenterVertically) { Text("Private key stored (encrypted)", Modifier.weight(1f)); TextButton(onClick = { app.tunnel.clearKey(); hasKey = false }) { Text("Remove") } }
        else {
            OutlinedTextField(key, { key = it }, label = { Text("Paste private key (PEM)") }, modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp))
            Button(enabled = key.contains("PRIVATE KEY"), onClick = { app.tunnel.saveKey(key); key = ""; hasKey = true }) { Text("Save key") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = hasKey && host.isNotBlank() && user.isNotBlank() && rport.isNotBlank(), onClick = {
                app.prefs.putString("ssh_host", host); app.prefs.putString("ssh_port", sport); app.prefs.putString("ssh_user", user); app.prefs.putString("ssh_remote", rport)
                app.tunnel.start(host.trim(), sport.toIntOrNull() ?: 22, user.trim(), rport.toIntOrNull() ?: port, port)
            }) { Text("Start tunnel") }
            OutlinedButton(onClick = { app.tunnel.stop() }) { Text("Stop") }
        }
        Text("Tunnel: $tun", style = MaterialTheme.typography.bodySmall)
        Note("The tunnel only runs while this app is alive. While the tunnel is up, anyone who knows host:port can reach the server — keep online-mode and the whitelist on.")

        Sec("Security")
        if (props["online-mode"] == "false") Text("online-mode is OFF: anyone can join under any name. Turn it on or use a whitelist.", color = MaterialTheme.colorScheme.error)
        Note("Only the game port ($port) needs to be exposed. Do not forward RCON (25575) or query ports. A phone cannot defend against DDoS: real protection needs a filtering proxy/tunnel provider or hosting network in front of it. Hiding your home IP behind a tunnel helps, but is not DDoS protection by itself.")
    }
}

@Composable
fun BackupTab(c: ServerConfig) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by app.manager.live(c.id).state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var restoreTarget by remember { mutableStateOf<File?>(null) }
    val files = remember(tick) { Backups.list(ctx, c.id) }
    val stopped = st == RunState.STOPPED || st == RunState.CRASHED || st == RunState.SLEEPING

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Note("Backups are zip files stored locally (excluding re-downloadable libraries and logs). Keeping the newest ${app.prefs.backupKeep}. " +
            if (app.prefs.backupTree != null) "Each backup is also copied to your chosen external/cloud folder." else "Choose an external or cloud folder in Settings to also copy them off the phone.")
        Button(enabled = !busy, onClick = {
            busy = true; msg = "Backing up…"
            scope.launch(Dispatchers.IO) {
                val running = st == RunState.RUNNING
                if (running) { app.manager.sendCommand(c.id, "save-off"); app.manager.sendCommand(c.id, "save-all flush"); delay(5000) }
                val r = try { "Created " + Backups.create(ctx, c, app.prefs.backupKeep, app.prefs.backupTree) { m -> msg = m }.name } catch (e: Exception) { "Backup failed: ${e.message}" }
                if (running) app.manager.sendCommand(c.id, "save-on")
                withContext(Dispatchers.Main) { msg = r; busy = false; tick++ }
            }
        }) { Text(if (busy) "Working…" else "Back up now") }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (!stopped) Note("Restore is only available while the server is stopped.")
        LazyColumn(Modifier.weight(1f)) {
            items(files, key = { it.path }) { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium)
                        Text("${fmtBytes(f.length())} · ${DateFormat.getDateTimeInstance().format(Date(f.lastModified()))}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(enabled = stopped && !busy, onClick = { restoreTarget = f }) { Text("Restore") }
                    TextButton(onClick = { f.delete(); tick++ }) { Text("Delete") }
                }
                HorizontalDivider()
            }
        }
    }
    restoreTarget?.let { f ->
        AlertDialog(onDismissRequest = { restoreTarget = null }, title = { Text("Restore ${f.name}?") },
            text = { Text("Files from the backup overwrite files with the same name. Newer files not in the backup are kept.") },
            confirmButton = { TextButton(onClick = {
                restoreTarget = null; busy = true
                scope.launch(Dispatchers.IO) {
                    val r = try { Backups.restore(c, f); "Restored ${f.name}" } catch (e: Exception) { "Restore failed: ${e.message}" }
                    withContext(Dispatchers.Main) { msg = r; busy = false }
                }
            }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("Cancel") } })
    }
}

@Composable
fun ServerSettingsTab(c: ServerConfig, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val mem = remember { memInfo(ctx) }
    val st by app.manager.live(c.id).state.collectAsState()
    val max = MemPolicy.maxSafe(mem.totalMb)
    var memMb by remember(c.id) { mutableIntStateOf(c.memoryMb.coerceAtMost(max)) }
    var idle by remember(c.id) { mutableIntStateOf(c.idleMinutes) }
    var wake by remember(c.id) { mutableStateOf(c.wakeOnJoin) }
    var eula by remember(c.id) { mutableStateOf(c.eulaAccepted) }
    var confirmDelete by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val busy = st in setOf(RunState.STARTING, RunState.RUNNING, RunState.STOPPING)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${c.software.label} ${c.mcVersion} ${c.loaderVersion}".trim() + " · Java ${c.javaMajor}")
        Note(c.rootPath)
        Sec("Memory: $memMb MB (safe max $max MB of ${mem.totalMb} MB)")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { memMb = MemPolicy.low(mem.totalMb) }, label = { Text("Low") })
            AssistChip(onClick = { memMb = MemPolicy.recommended(mem.totalMb) }, label = { Text("Recommended") })
            AssistChip(onClick = { memMb = MemPolicy.high(mem.totalMb) }, label = { Text("High") })
        }
        Slider(memMb.toFloat(), { memMb = ((it / 128).toInt() * 128).coerceIn(512, max) }, valueRange = 512f..max.toFloat().coerceAtLeast(640f))
        Sec(if (idle == 0) "Idle sleep: off" else "Idle sleep after $idle min with no players")
        Slider(idle.toFloat(), { idle = it.toInt() }, valueRange = 0f..120f)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Wake on join while sleeping", Modifier.weight(1f)); Switch(wake, { wake = it }) }
        Note("Idle sleep fully stops the server to save battery. With wake-on-join, BlockHost keeps listening on the port; the first player to connect is shown a 'waking up' message and must retry after ~1 minute. This only works while BlockHost's foreground service and network stay alive; it is not available if Android kills the app.")
        if (!c.eulaAccepted) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(eula, { eula = it }); Text("I accept the Minecraft EULA (aka.ms/MinecraftEULA)") }
        Button(enabled = true, onClick = {
            app.manager.update(c.copy(memoryMb = memMb, idleMinutes = idle, wakeOnJoin = wake, eulaAccepted = eula))
            if (eula) try { Installer.writeEula(c.dir) } catch (_: Exception) {}
            msg = if (busy) "Saved. Memory changes apply after restart." else "Saved."
        }) { Text("Save") }
        msg?.let { Note(it) }
        HorizontalDivider()
        OutlinedButton(enabled = !busy, onClick = { confirmDelete = true }) { Text("Delete server…", color = MaterialTheme.colorScheme.error) }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete ${c.name}?") },
        text = { Text("The world, mods and all files of this server are deleted permanently. Backups are kept.") },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            val e = app.manager.delete(c.id); if (e != null) msg = e else onDeleted()
        }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}
