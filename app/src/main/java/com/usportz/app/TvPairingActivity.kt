package com.usportz.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class TvPairingActivity : ComponentActivity() {
    private lateinit var pairingServer: PairingManager.TvServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PairingManager.isPaired(this)) {
            openTv()
            return
        }
        val code = PairingManager.newCode(this)
        pairingServer = PairingManager.TvServer(this) { success ->
            if (success) runOnUiThread { openTv() }
        }
        pairingServer.start(code)
        setContent { TvPairingScreen(this, code) }
    }

    private fun openTv() {
        startActivity(Intent(this, TvMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    override fun onDestroy() {
        if (!PairingManager.isPaired(this)) pairingServer.stop()
        super.onDestroy()
    }
}

@Composable
private fun TvPairingScreen(context: Context, code: String) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF63D7FF), background = Color(0xFF060A10))) {
        Box(Modifier.fillMaxSize().background(Color(0xFF060A10)).padding(60.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 850.dp)) {
                Text("USPORTZ", color = Color(0xFF63D7FF), fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text("Connect your phone", fontSize = 46.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 16.dp))
                Text("No Xtream username or password is needed on this TV.", color = Color.LightGray, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101A25)), shape = RoundedCornerShape(28.dp), modifier = Modifier.padding(top = 42.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 70.dp, vertical = 42.dp)) {
                        Text("OPEN USPORTZ ON YOUR PHONE", color = Color(0xFF63D7FF), fontSize = 13.sp, fontWeight = FontWeight.Black)
                        Text("Tap  Connect to TV", fontSize = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                        Text("Select this TV and enter:", color = Color.Gray, fontSize = 17.sp, modifier = Modifier.padding(top = 16.dp))
                        Text(code.chunked(3).joinToString("  "), fontSize = 54.sp, fontWeight = FontWeight.Black, letterSpacing = 7.sp, modifier = Modifier.padding(top = 18.dp))
                        Text("This code expires when pairing finishes.", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp))
                    }
                }
            }
        }
    }
}
