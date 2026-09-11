package com.usportz.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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
        Editor(store) {
            context.setResult(Activity.RESULT_OK)
            context.finish()
        }
    }
}
