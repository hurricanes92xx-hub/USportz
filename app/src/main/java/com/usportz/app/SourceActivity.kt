package com.usportz.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Dedicated source/settings screen so the sports dashboard never loops back into MainActivity. */
class SourceActivity : ComponentActivity() {
    private val store by lazy { SourceStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF080A12), surface = Color(0xFF121522))) {
                SourceSettingsScreen(this, store)
            }
        }
    }
}

@Composable
private fun SourceSettingsScreen(context: ComponentActivity, store: SourceStore) {
    var pairedCount by remember { mutableIntStateOf(PairingManager.pairedTvs(context).size) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFF080A12)),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).clickable {
                    context.startActivity(Intent(context, MobilePairingActivity::class.java))
                },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF151124))
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp)) {
                    Icon(Icons.Default.Tv, contentDescription = null, tint = Color(0xFF63D7FF))
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text("TV CONNECTION", color = Color(0xFF63D7FF), style = MaterialTheme.typography.labelSmall)
                        Text(
                            if (pairedCount == 0) "Connect a TV" else "$pairedCount TV${if (pairedCount == 1) "" else "s"} connected",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text("Pair multiple TVs • no Xtream password typing on TV", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("›", color = Color.Gray, style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
        item {
            StableSources(store) {
                pairedCount = PairingManager.pairedTvs(context).size
                context.setResult(Activity.RESULT_OK)
            }
        }
    }
}
