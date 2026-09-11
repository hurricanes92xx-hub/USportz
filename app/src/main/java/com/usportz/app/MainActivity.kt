package com.usportz.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/** Launch the production sports dashboard used by phone/tablet users. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, RichSportsActivity::class.java))
        finish()
    }
}
