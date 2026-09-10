package com.usportz.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
private fun VaultSection(title: String, subtitle: String) = Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.width(7.dp))
    Text(subtitle, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
}

@Composable
internal fun StableSources(store: SourceStore, onChanged: () -> Unit) {
    var server by remember { mutableStateOf(store.server) }
    var user by remember { mutableStateOf(store.user) }
    var pass by remember { mutableStateOf(store.pass) }
    var m3u by remember { mutableStateOf(store.playlist) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(14.dp)) {
        item { VaultSection("SOURCES", "Xtream Codes + M3U/M3U8") }
        item { OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("Xtream server") }, singleLine = true) }
        item { OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true) }
        item { OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true) }
        item {
            Button(
                onClick = {
                    busy = true
                    status = "Connecting…"
                    store.saveXtream(server, user, pass,
                        { ok, message -> busy = false; status = message; if (ok) onChanged() },
                        { status = it }
                    )
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "CONNECTING…" else "CONNECT XTREAM") }
        }
        item { OutlinedTextField(m3u, { m3u = it }, Modifier.fillMaxWidth(), label = { Text("Playlist URL") }, singleLine = true) }
        item {
            Button(
                onClick = {
                    busy = true
                    status = "Loading playlist…"
                    store.saveM3u(m3u) { ok, message -> busy = false; status = message; if (ok) onChanged() }
                },
                enabled = !busy && m3u.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("LOAD PLAYLIST") }
        }
        if (status.isNotBlank()) item { Text(status, color = if (status.startsWith("Connected")) Color(0xFF63D7FF) else Color.Gray) }
        item { Text("Credentials remain encrypted on this device and are never bundled into the APK.", color = Color.Gray, style = MaterialTheme.typography.labelSmall) }
    }
}
