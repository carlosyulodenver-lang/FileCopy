@file:OptIn(ExperimentalMaterial3Api::class)

package com.blockhost

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mem = remember { memInfo(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    var rtLog by remember { mutableStateOf("") }
    var rtBusy by remember { mutableStateOf(false) }
    var rtProgress by remember { mutableFloatStateOf(0f) }
    var importMajor by remember { mutableIntStateOf(21) }
    var keep by remember { mutableIntStateOf(app.prefs.backupKeep) }
    var idle by remember { mutableIntStateOf(app.prefs.defaultIdle) }
    var tree by remember { mutableStateOf(app.prefs.backupTree) }
    val installed = remember(tick) { app.runtime.installedMajors() }
    val ignoring = remember(tick) { (ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            rtBusy = true; rtLog = "Importing…"
            val name = displayName(ctx, u)
            scope.launch(Dispatchers.IO) {
                val r = try { ctx.contentResolver.openInputStream(u)!!.use { app.runtime.importArchive(it, name, importMajor) { } }; "Imported as Java $importMajor" }
                catch (e: Exception) { "Import failed: ${e.message}" }
                withContext(Dispatchers.Main) { rtLog = r; rtBusy = false; tick++ }
            }
        }
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) {
            ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            app.prefs.backupTree = u.toString(); tree = u.toString()
        }
    }

    Page("Settings", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Sec("Device")
            Text("CPU ABI: ${Build.SUPPORTED_ABIS.joinToString()}  •  Android ${Build.VERSION.RELEASE}")
            Text("RAM: ${mem.totalMb} MB total, ${mem.availMb} MB free. Safe server maximum: ${MemPolicy.maxSafe(mem.totalMb)} MB")
            if (Build.SUPPORTED_ABIS.firstOrNull() !in listOf("arm64-v8a", "x86_64")) Text("This CPU is not supported by the Java runtime download.", color = MaterialTheme.colorScheme.error)

            Sec("Java runtime")
            Note("Android cannot run Minecraft server JARs itself. BlockHost downloads an Android-compatible OpenJDK (~200 MB per version) from the Termux package repository (free) and runs servers as separate processes. Java 21 is needed for Minecraft 1.20.5+, Java 17 for 1.17–1.20.4.")
            listOf(17, 21).forEach { j ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Java $j: " + if (j in installed) "installed" else "not installed", Modifier.weight(1f))
                    if (j in installed) TextButton(onClick = { app.runtime.remove(j); tick++ }) { Text("Remove") }
                    else Button(enabled = !rtBusy, onClick = {
                        rtBusy = true; rtLog = "Starting…"; rtProgress = 0f
                        app.manager.installRuntime(j, { rtLog = it }, { rtProgress = it }) { err -> rtBusy = false; rtLog = err?.let { "Failed: $it" } ?: "Java $j installed."; tick++ }
                    }) { Text("Install") }
                }
            }
            if (rtBusy) LinearProgressIndicator(progress = { rtProgress }, modifier = Modifier.fillMaxWidth())
            if (rtLog.isNotEmpty()) Note(rtLog)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(17, 21).forEach { j -> FilterChip(selected = importMajor == j, onClick = { importMajor = j }, label = { Text("as Java $j") }) }
                OutlinedButton(enabled = !rtBusy, onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import JDK archive") }
            }
            Note("Import accepts a .tar.gz/.tar.xz/.zip of a JDK built for Android (bionic), containing bin/java.")

            Sec("Background hosting & battery")
            Text(if (ignoring) "Battery optimization: exempted ✓" else "Battery optimization: NOT exempted")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !ignoring, onClick = {
                    try { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
                    catch (e: Exception) { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }) { Text("Allow unrestricted") }
                OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }) { Text("App settings") }
            }
            Note("While a server runs (or sleeps with wake-on-join) BlockHost shows a persistent notification and holds a CPU and Wi-Fi wake lock. Even so, Android and some manufacturers (Xiaomi, Huawei, Samsung, OnePlus…) may kill background apps, throttle CPU when the screen is off, or drop Wi-Fi. Reliable 24/7 hosting on a phone is not guaranteed. Keep the phone plugged in, exempt BlockHost from battery optimization, and check dontkillmyapp.com for your brand. Servers stop if Android kills the app; they are not auto-resumed.")

            Sec("Defaults")
            Text(if (idle == 0) "Idle sleep for new servers: off" else "Idle sleep for new servers: $idle min")
            Slider(idle.toFloat(), { idle = it.toInt(); app.prefs.defaultIdle = idle }, valueRange = 0f..120f)

            Sec("Backups")
            Text("Keep newest $keep local backups per server")
            Slider(keep.toFloat(), { keep = it.toInt().coerceAtLeast(1); app.prefs.backupKeep = keep }, valueRange = 1f..20f)
            Text(if (tree != null) "Copy to external folder: set" else "Copy to external folder: not set")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { treePicker.launch(null) }) { Text("Choose folder") }
                if (tree != null) OutlinedButton(onClick = { app.prefs.backupTree = null; tree = null }) { Text("Clear") }
            }
            Note("Any folder provider installed on your phone works, including the Google Drive or OneDrive apps, SD cards or a USB drive. BlockHost has no cloud account of its own, needs no paid service, and uploads nothing unless you pick a folder here. Upload speed/cost depends on that app.")

            Sec("About")
            Note("BlockHost 0.2.0. Not affiliated with Mojang or Microsoft. A phone cannot provide DDoS protection; see the Network tab.")
        }
    }
}
