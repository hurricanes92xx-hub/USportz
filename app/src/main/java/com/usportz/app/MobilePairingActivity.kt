package com.usportz.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MobilePairingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MobilePairingScreen(this) }
    }
}

@Composable
private fun MobilePairingScreen(context: Context) {
    val store = remember { SourceStore(context) }
    val scope = rememberCoroutineScope()
    var tvs by remember { mutableStateOf(emptyList<PairingManager.TvEndpoint>()) }
    var selected by remember { mutableStateOf<PairingManager.TvEndpoint?>(null) }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Looking for USportz TVs on this Wi-Fi…") }
    var busy by remember { mutableStateOf(false) }
    var discovery by remember { mutableStateOf<android.net.nsd.NsdManager.DiscoveryListener?>(null) }

    fun scan() {
        discovery?.let { PairingManager.stopDiscover(context, it) }
        tvs = emptyList()
        status = "Looking for USportz TVs on this Wi-Fi…"
        discovery = PairingManager.discover(context, { endpoint -> if (tvs.none { it.name == endpoint.name && it.port == endpoint.port }) tvs = tvs + endpoint }, { if (tvs.isEmpty()) status = "No TV found. Make sure the TV and phone are on the same Wi-Fi." })
    }

    DisposableEffect(Unit) {
        scan()
        onDispose { discovery?.let { PairingManager.stopDiscover(context, it) } }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFBFA6FF), background = Color(0xFF080A10), surface = Color(0xFF10131D))) {
        Scaffold(topBar = { TopAppBar(title = { Text("Connect to TV") }) }) { pad ->
            Column(Modifier.fillMaxSize().background(Color(0xFF080A10)).padding(pad).padding(20.dp)) {
                Text("Your Xtream login stays on your phone.", fontSize = 20.sp)
                Text("Pair once and the TV can use the same source without typing credentials.", color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
                if (selected == null) {
                    Text(status, color = Color(0xFF63D7FF), modifier = Modifier.padding(top = 28.dp))
                    LazyColumn(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(tvs, key = { "${it.name}:${it.port}" }) { tv ->
                            ListItem(headlineContent = { Text(tv.name) }, supportingContent = { Text("USportz TV • ready to pair") }, leadingContent = { Icon(Icons.Default.Tv, null) }, modifier = Modifier.clickable { selected = tv })
                        }
                    }
                    OutlinedButton(onClick = ::scan, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Scan Again") }
                } else {
                    Text(selected!!.name, fontSize = 26.sp, modifier = Modifier.padding(top = 30.dp))
                    Text("Enter the 6-digit code shown on the TV.", color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
                    OutlinedTextField(value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, label = { Text("TV pairing code") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 24.dp))
                    Button(enabled = code.length == 6 && !busy, onClick = {
                        busy = true; status = "Pairing securely…"
                        scope.launch {
                            val ok = PairingManager.sendBundle(selected!!, code, store)
                            busy = false
                            status = if (ok) "Paired! Your TV is ready." else "Pairing failed. Check the code and try again."
                            if (ok) { kotlinx.coroutines.delay(700); (context as? ComponentActivity)?.finish() }
                        }
                    }, modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) { Text(if (busy) "Pairing…" else "Pair TV") }
                    TextButton(onClick = { selected = null; code = "" }) { Text("Back") }
                }
            }
        }
    }
}
