@file:OptIn(ExperimentalMaterial3Api::class)

package com.blockhost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

sealed class Screen {
    object Dashboard : Screen()
    object Wizard : Screen()
    object Settings : Screen()
    data class Server(val id: String) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Root() }
            }
        }
    }
}

@Composable
fun Root() {
    var screen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    BackHandler(enabled = screen != Screen.Dashboard) { screen = Screen.Dashboard }
    when (val s = screen) {
        Screen.Dashboard -> DashboardScreen({ screen = Screen.Wizard }, { screen = Screen.Settings }, { screen = Screen.Server(it) })
        Screen.Wizard -> WizardScreen(onDone = { id -> screen = if (id != null) Screen.Server(id) else Screen.Dashboard })
        Screen.Settings -> SettingsScreen { screen = Screen.Dashboard }
        is Screen.Server -> ServerScreen(s.id) { screen = Screen.Dashboard }
    }
}

val app get() = BlockHostApp.instance

@Composable
fun Page(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) },
            navigationIcon = { if (onBack != null) TextButton(onClick = onBack) { Text("Back") } },
            actions = actions)
    }) { pad -> Column(Modifier.padding(pad).fillMaxSize(), content = content) }
}

@Composable
fun DashboardScreen(onCreate: () -> Unit, onSettings: () -> Unit, onOpen: (String) -> Unit) {
    val servers by app.manager.servers.collectAsState()
    Page("BlockHost", null, actions = { TextButton(onClick = onSettings) { Text("Settings") } }) {
        if (servers.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Create your first server", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text("Host Minecraft on this phone. Android may stop background apps; see Settings for battery guidance.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Create server") }
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(servers, key = { it.id }) { c -> ServerCard(c, onOpen) }
            }
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("New server") }
        }
    }
}

@Composable
fun ServerCard(c: ServerConfig, onOpen: (String) -> Unit) {
    val live = remember(c.id) { app.manager.live(c.id) }
    val st by live.state.collectAsState()
    val stats by live.stats.collectAsState()
    val err by live.error.collectAsState()
    Card(Modifier.fillMaxWidth(), onClick = { onOpen(c.id) }) {
        Column(Modifier.padding(14.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium)
            Text("${c.software.label} ${c.mcVersion}  •  ${c.memoryMb} MB", style = MaterialTheme.typography.bodySmall)
            Text(st.label + if (st == RunState.RUNNING && stats.players >= 0) "  •  ${stats.players}/${stats.maxPlayers} players" else "")
            err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val canStart = st == RunState.STOPPED || st == RunState.CRASHED || st == RunState.SLEEPING
                Button(enabled = canStart, onClick = { app.manager.start(c.id) }) { Text("Start") }
                OutlinedButton(enabled = st == RunState.RUNNING || st == RunState.STARTING, onClick = { app.manager.stop(c.id) }) { Text("Stop") }
            }
        }
    }
}
