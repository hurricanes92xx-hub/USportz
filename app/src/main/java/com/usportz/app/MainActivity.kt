package com.usportz.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/** Lightweight launcher: all dashboard work stays outside the launcher activity. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, RichSportsActivity::class.java))
        finish()
    }
}
