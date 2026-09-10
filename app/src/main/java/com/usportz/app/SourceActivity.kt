package com.usportz.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Dedicated source screen so the sports dashboard never loops back into MainActivity. */
class SourceActivity : ComponentActivity() {
    private val store by lazy { SourceStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF080A12), surface = Color(0xFF121522))) {
                Box(Modifier.fillMaxSize().background(Color(0xFF080A12))) {
                    StableSources(store) { setResult(RESULT_OK); finish() }
                }
            }
        }
    }
}
