package com.usportz.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Editor(s: SourceStore, done: () -> Unit) {
    var server by remember { mutableStateOf(s.server) }
    var user by remember { mutableStateOf(s.user) }
    var pass by remember { mutableStateOf(s.pass) }
    var m3u by remember { mutableStateOf(s.playlist) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(15.dp)) {
            Text("XTREAM CODES", fontWeight = FontWeight.Black)
            OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("Server") }, singleLine = true)
            OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Username") }, singleLine = true)
            OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Button(enabled = !busy && server.isNotBlank() && user.isNotBlank() && pass.isNotBlank(), onClick = {
                busy = true
                status = "Connecting to source…"
                s.saveXtream(server, user, pass,
                    done = { ok, msg -> busy = false; status = msg; if (ok) done() },
                    progress = { message -> status = message }
                )
            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(if (busy) "INDEXING…" else "CONNECT XTREAM") }

            HorizontalDivider(Modifier.padding(vertical = 13.dp))
            Text("M3U / M3U8", fontWeight = FontWeight.Black)
            OutlinedTextField(m3u, { m3u = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Playlist URL") }, singleLine = true)
            Button(enabled = !busy && m3u.isNotBlank(), onClick = {
                busy = true
                status = "Loading full playlist…"
                s.saveM3u(m3u) { ok, msg -> busy = false; status = msg; if (ok) done() }
            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(if (busy) "INDEXING…" else "LOAD PLAYLIST") }

            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
