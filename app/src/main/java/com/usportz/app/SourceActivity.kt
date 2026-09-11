package com.usportz.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Dedicated source/settings screen. Keep this screen intentionally small and defensive. */
class SourceActivity : ComponentActivity() {
    private val store by lazy { SourceStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFF6A00),
                    secondary = Color(0xFF14D9FF),
                    background = Color(0xFF080A12),
                    surface = Color(0xFF121522)
                )
            ) {
                SourceEditorScreen(this, store)
            }
        }
    }
}

@Composable
private fun SourceEditorScreen(context: ComponentActivity, store: SourceStore) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF080A12))
            .padding(top = 8.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121522))
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("CONNECT YOUR TVs", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("Use this phone as the source manager and pair as many USportz TVs as you need.", color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                Button(
                    onClick = { context.startActivity(Intent(context, MobilePairingActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                ) { Text("CONNECT / MANAGE TVs") }
            }
        }
        Editor(store) {
            context.setResult(Activity.RESULT_OK)
            context.finish()
        }
    }
}
