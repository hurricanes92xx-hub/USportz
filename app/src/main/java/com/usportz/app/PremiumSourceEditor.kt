package com.usportz.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    var tvUrl by remember { mutableStateOf(s.tvFeedUrl) }
    var tvToken by remember { mutableStateOf(s.tvFeedToken) }
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

            HorizontalDivider(Modifier.padding(vertical = 13.dp))
            Text("TV SCHEDULE DATA", fontWeight = FontWeight.Black)
            Text("Licensed Ronin Sport / LiveSportsOnTV REST or JSON feed. USportz does not scrape the website.", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            OutlinedTextField(tvUrl, { tvUrl = it }, Modifier.fillMaxWidth().padding(top = 7.dp), label = { Text("Licensed feed / proxy URL") }, singleLine = true)
            OutlinedTextField(tvToken, { tvToken = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Feed token (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Button(enabled = !busy, onClick = {
                s.saveTvFeed(tvUrl, tvToken) { ok, msg -> status = msg; if (ok) done() }
            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("SAVE TV SCHEDULE") }

            if (status.isNotBlank()) Text(status, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
