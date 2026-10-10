@file:OptIn(ExperimentalMaterial3Api::class)

package com.blockhost

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun VersionPicker(options: List<String>, selected: String, loading: Boolean, error: String?, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    OutlinedTextField(q, { q = it }, label = { Text("Filter") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    when {
        loading -> Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Loading versions…") }
        error != null -> Text("Could not load versions: $error", color = MaterialTheme.colorScheme.error)
        else -> LazyColumn(Modifier.height(240.dp)) {
            items(options.filter { it.contains(q, true) }) { v ->
                Row(Modifier.fillMaxWidth().clickable { onPick(v) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = v == selected, onClick = { onPick(v) }); Text(v)
                }
            }
        }
    }
}

@Composable
fun WizardScreen(onDone: (String?) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("My Server") }
    var sw by remember { mutableStateOf(Software.PAPER) }
    var mc by remember { mutableStateOf("") }
    var loader by remember { mutableStateOf("") }
    var mcList by remember { mutableStateOf<List<String>>(emptyList()) }
    var loaderList by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadErr by remember { mutableStateOf<String?>(null) }
    var jar by remember { mutableStateOf<Uri?>(null) }
    var jarName by remember { mutableStateOf("") }
    var customJava by remember { mutableIntStateOf(21) }
    val mem = remember { memInfo(ctx) }
    var preset by remember { mutableIntStateOf(1) }
    var memMb by remember { mutableIntStateOf(MemPolicy.recommended(mem.totalMb)) }
    var external by remember { mutableStateOf(false) }
    var eula by remember { mutableStateOf(false) }
    val inst by app.manager.install.collectAsState()
    val extDir = remember { ctx.getExternalFilesDir(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { jar = u; jarName = displayName(ctx, u) }
    }

    LaunchedEffect(sw) {
        mc = ""; loader = ""; mcList = emptyList(); loaderList = emptyList(); loadErr = null
        if (sw != Software.CUSTOM) {
            loading = true
            try { mcList = withContext(Dispatchers.IO) { Providers.mcVersions(sw) }; if (mcList.isEmpty()) loadErr = "no versions found" }
            catch (e: Exception) { loadErr = e.message ?: "network error" }
            loading = false
        }
    }
    LaunchedEffect(mc) {
        loader = ""; loaderList = emptyList()
        if (sw.hasLoader && mc.isNotEmpty()) {
            loading = true
            try { loaderList = withContext(Dispatchers.IO) { Providers.loaderVersions(sw, mc) }; loader = loaderList.firstOrNull() ?: "" }
            catch (e: Exception) { loadErr = e.message }
            loading = false
        }
    }

    val titles = listOf("Name", "Software", "Version", "Memory", "Storage", "EULA", "Setup")
    Page("Create server (${step + 1}/${titles.size}): ${titles[step]}", if (step < 6) ({ if (step == 0) onDone(null) else step-- }) else null) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (step) {
                0 -> {
                    Text("Create your first server", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(name, { name = it.take(32) }, label = { Text("Server name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                1 -> Software.values().forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { sw = s }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = sw == s, onClick = { sw = s }); Text(s.label)
                    }
                }
                2 -> if (sw == Software.CUSTOM) {
                    Text("Import your own server JAR. It runs with the Java version you choose.")
                    Button(onClick = { pick.launch(arrayOf("*/*")) }) { Text(if (jar == null) "Choose JAR…" else jarName) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(17, 21).forEach { j -> FilterChip(selected = customJava == j, onClick = { customJava = j }, label = { Text("Java $j") }) }
                    }
                    OutlinedTextField(mc, { mc = it.take(20) }, label = { Text("Minecraft version (label only)") }, singleLine = true)
                } else {
                    Text("Minecraft version"); VersionPicker(mcList, mc, loading && mcList.isEmpty(), loadErr) { mc = it }
                    if (sw.hasLoader && mc.isNotEmpty()) { Text("${sw.label} version"); VersionPicker(loaderList.take(60), loader, loading, null) { loader = it } }
                    if (sw == Software.SPIGOT) Text("Spigot is compiled on your phone with BuildTools (slow, experimental, needs ~1.5 GB RAM). Paper is recommended.", style = MaterialTheme.typography.bodySmall)
                    if (sw == Software.FORGE || sw == Software.NEOFORGE) Text("Only Minecraft 1.17.1+ is listed (Java 17+).", style = MaterialTheme.typography.bodySmall)
                }
                3 -> {
                    val max = MemPolicy.maxSafe(mem.totalMb)
                    Text("Device RAM: ${mem.totalMb} MB total, ${mem.availMb} MB free now")
                    Text("Safe maximum for the server: $max MB")
                    listOf("Low" to MemPolicy.low(mem.totalMb), "Recommended" to MemPolicy.recommended(mem.totalMb), "High" to MemPolicy.high(mem.totalMb)).forEachIndexed { i, (n, v) ->
                        Row(Modifier.fillMaxWidth().clickable { preset = i; memMb = v }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = preset == i, onClick = { preset = i; memMb = v }); Text("$n — $v MB")
                        }
                    }
                    Text("Fine tune: $memMb MB")
                    Slider(value = memMb.toFloat(), onValueChange = { memMb = (it / 128).toInt() * 128 }, valueRange = 512f..max.toFloat().coerceAtLeast(640f))
                    Text("Values above the safe maximum are blocked. Android needs the rest of the RAM.", style = MaterialTheme.typography.bodySmall)
                }
                4 -> {
                    Row(Modifier.fillMaxWidth().clickable { external = false }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !external, onClick = { external = false })
                        Column { Text("Internal (private)"); Text("${fmtBytes(ctx.filesDir.usableSpace)} free", style = MaterialTheme.typography.bodySmall) }
                    }
                    Row(Modifier.fillMaxWidth().clickable(enabled = extDir != null) { external = true }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = external, enabled = extDir != null, onClick = { external = true })
                        Column {
                            Text("App folder on shared storage")
                            Text(if (extDir != null) "${fmtBytes(extDir.usableSpace)} free · visible to file managers (Android/data/com.blockhost)" else "unavailable", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("Nothing is uploaded anywhere. Backups can optionally be copied to a cloud folder later.", style = MaterialTheme.typography.bodySmall)
                }
                5 -> {
                    Text("Minecraft EULA", style = MaterialTheme.typography.titleMedium)
                    Text("To run a Minecraft server you must agree to Mojang's EULA (https://aka.ms/MinecraftEULA). BlockHost writes eula=true only if you confirm below.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(eula, { eula = it }); Text("I have read and accept the Minecraft EULA")
                    }
                }
                else -> {
                    val s = inst
                    if (s == null || (!s.running && s.error == null && s.doneId == null)) Text("Starting…")
                    s?.let {
                        LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth())
                        Text(if (it.running) "Installing… ${(it.progress * 100).toInt()}%" else if (it.error != null) "Failed" else "Done")
                        it.error?.let { e -> Text(e, color = MaterialTheme.colorScheme.error) }
                        Text(it.logs.takeLast(40).joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
        }
        val needsLoader = sw.hasLoader
        val ok = when (step) {
            0 -> name.isNotBlank()
            2 -> if (sw == Software.CUSTOM) jar != null else mc.isNotBlank() && (!needsLoader || loader.isNotBlank())
            3 -> memMb <= MemPolicy.maxSafe(mem.totalMb) && memMb >= 512
            5 -> eula
            else -> true
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
            if (step in 0..4) Button(enabled = ok, onClick = { step++ }) { Text("Next") }
            if (step == 5) Button(enabled = ok, onClick = {
                val base = if (external && extDir != null) extDir else ctx.filesDir
                step = 6
                val dMc = mc.ifBlank { "custom" }
                app.manager.scope.launch {
                    val jv = if (sw == Software.CUSTOM) customJava else Providers.javaMajorFor(mc)
                    app.manager.createServer(Draft(name, sw, dMc, loader, memMb, base, jv, eula, preset, app.prefs.defaultIdle), jar)
                }
            }) { Text("Install") }
            if (step == 6) {
                val s = inst
                if (s?.doneId != null) Button(onClick = { app.manager.install.value = null; onDone(s.doneId) }) { Text("Open server") }
                else if (s?.running != true) Button(onClick = { app.manager.install.value = null; step = 5 }) { Text("Back") }
            }
        }
    }
}

