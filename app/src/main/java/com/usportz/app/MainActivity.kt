package com.usportz.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/** Lightweight launcher for the resilient sports dashboard. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, RichSportsV2Activity::class.java))
        finish()
    }
}
