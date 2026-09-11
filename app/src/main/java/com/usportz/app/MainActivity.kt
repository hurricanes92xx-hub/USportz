package com.usportz.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** Single launcher for phone, tablet and TV-friendly sports dashboard. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RichSportsAppHost(this) }
    }
}

@androidx.compose.runtime.Composable
private fun RichSportsAppHost(activity: ComponentActivity) {
    androidx.compose.material3.MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme()
    ) {
        // Keep the launcher lightweight; the dashboard owns all source/index loading.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            activity.startActivity(android.content.Intent(activity, RichSportsActivity::class.java))
            activity.finish()
        }
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize())
    }
}
