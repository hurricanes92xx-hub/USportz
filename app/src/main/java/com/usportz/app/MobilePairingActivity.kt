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
import androidx.compose.material.icons.filled.DeleteOutline
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobilePairingScreen(context: Context) {
    val store = remember { SourceStore(context) }
    val scope = rememberCoroutineScope()
    var tvs by remember { mutableStateOf(emptyList<PairingManager.TvEndpoint>()) }
    var paired by remember { mutableStateOf(PairingManager.pairedTvs(context)) }
    var selected by remember { mutableStateOf<PairingManager.TvEndpoint?>(null) }
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Looking for USportz TVs on this Wi-Fi…") }
    var busy by remember { mutableStateOf(false) }
    var discovery by remember { mutableStateOf<android.net.nsd.NsdManager.DiscoveryListener?>(null) }

    fun scan() {
        discovery?.let { PairingManager.stopDiscover(context, it) }
        tvs = emptyList()
        status = "Looking for USportz TVs on this Wi-Fi…"
        discovery = PairingManager.discover(context, { endpoint ->
            if (tvs.none { it.name == endpoint.name && it.port == endpoint.port }) tvs = tvs + endpoint
        }, {
            if (tvs.isEmpty()) status = "No new TV found. Make sure the TV and phone are on the same Wi-Fi."
        })
    }

    DisposableEffect(Unit) {
        scan()
        onDispose { discovery?.let { PairingManager.stopDiscover(context, it) } }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFBFA6FF), background = Color(0xFF080A10), surface = Color(0xFF10131D))) {
        Scaffold(topBar = { TopAppBar(title = { Text("TV Connections") }) }) { pad ->
            Column(Modifier.fillMaxSize().background(Color(0xFF080A10)).padding(pad).padding(20.dp)) {
                Text("USPORTZ TV CONNECTIONS", fontSize = 12.sp, color = Color(0xFF63D7FF))
                Text("Pair as many TVs as you need", fontSize = 25.sp)
                Text("Your Xtream login stays on your phone. Each TV receives the source securely during pairing.", color = Color.Gray, modifier = Modifier.padding(top = 6.dp))

                if (paired.isNotEmpty()) {
                    Text("PAIRED TVs", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
                    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(paired, key = { it.id }) { tv ->
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111624))) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Tv, null, tint = Color(0xFF63D7FF))
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(tv.name, fontSize = 16.sp)
                                        Text("Connected • ${tv.host}", color = Color.Gray, fontSize = 11.sp)
                                    }
                                    IconButton(onClick = {
                                        PairingManager.removePairedTv(context, tv.id)
                                        paired = PairingManager.pairedTvs(context)
                                    }) { Icon(Icons.Default.DeleteOutline, "Remove TV") }
                                }
                            }
                        }
                    }
                }

                if (selected == null) {
                    Text("ADD ANOTHER TV", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
                    Text(status, color = Color(0xFF63D7FF), fontSize = 12.sp)
                    LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 250.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(tvs, key = { "${it.name}:${it.port}" }) { tv ->
                            ListItem(
                                headlineContent = { Text(tv.name) },
                                supportingContent = { Text(if (paired.any { it.name == tv.name && it.port == tv.port }) "Already paired" else "USportz TV • ready to pair") },
                                leadingContent = { Icon(Icons.Default.Tv, null) },
                                modifier = Modifier.clickable { if (!paired.any { it.name == tv.name && it.port == tv.port }) selected = tv }
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = ::scan, modifier = Modifier.weight(1f)) { Text("Scan Again") }
                    }
                } else {
                    Text(selected!!.name, fontSize = 24.sp, modifier = Modifier.padding(top = 24.dp))
                    Text("Enter the 6-digit code shown on the TV.", color = Color.Gray, modifier = Modifier.padding(top = 6.dp))
                    OutlinedTextField(value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, label = { Text("TV pairing code") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 18.dp))
                    Button(enabled = code.length == 6 && !busy, onClick = {
                        busy = true; status = "Pairing securely…"
                        scope.launch {
                            val endpoint = selected!!
                            val ok = PairingManager.sendBundle(endpoint, code, store)
                            busy = false
                            if (ok) {
                                PairingManager.addPairedTv(context, endpoint)
                                paired = PairingManager.pairedTvs(context)
                                status = "Paired! ${endpoint.name} is ready."
                                selected = null
                                code = ""
                                scan()
                            } else {
                                status = "Pairing failed. Check the code and try again."
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) { Text(if (busy) "Pairing…" else "Pair TV") }
                    TextButton(onClick = { selected = null; code = "" }) { Text("Back") }
                }
            }
        }
    }
}
