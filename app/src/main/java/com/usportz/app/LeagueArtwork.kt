package com.usportz.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun artColors(key: String): List<Color> = when (key) {
    "nfl" -> listOf(Color(0xFF102B4E), Color(0xFF07101D))
    "nba", "wnba" -> listOf(Color(0xFF32184F), Color(0xFF0B1D31))
    "mlb" -> listOf(Color(0xFF123B54), Color(0xFF0A111C))
    "nhl" -> listOf(Color(0xFF173A43), Color(0xFF0A1117))
    "mls", "epl" -> listOf(Color(0xFF142F68), Color(0xFF10121F))
    "ufc", "boxing", "wwe" -> listOf(Color(0xFF4A101B), Color(0xFF120B0E))
    "aew" -> listOf(Color(0xFF102E42), Color(0xFF080D13))
    "tna" -> listOf(Color(0xFF1F2E13), Color(0xFF0B1009))
    "roh" -> listOf(Color(0xFF3C2410), Color(0xFF110C08))
    "nascar", "indycar", "f1", "motogp", "monster-jam" -> listOf(Color(0xFF4A2310), Color(0xFF0E0D0B))
    "tennis", "golf" -> listOf(Color(0xFF16402E), Color(0xFF09130F))
    else -> listOf(Color(0xFF241B43), Color(0xFF0D1F2A))
}

@Composable
internal fun LeagueArtwork(brand: SportsBrand, modifier: Modifier = Modifier, compact: Boolean = false) {
    val height = if (compact) 72.dp else 156.dp
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(if (compact) 18.dp else 24.dp)).background(Brush.linearGradient(artColors(brand.key))).padding(if (compact) 12.dp else 18.dp)) {
        Box(Modifier.align(Alignment.TopEnd).size(if (compact) 44.dp else 88.dp).clip(RoundedCornerShape(50.dp)).background(Color.White.copy(alpha = .07f)))
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(brand.icon, fontSize = if (compact) 10.sp else 18.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = .75f))
            Spacer(Modifier.height(3.dp))
            Text(brand.label, fontSize = if (compact) 17.sp else 28.sp, fontWeight = FontWeight.Black)
            if (!compact) Text(brand.accent, color = Color.White.copy(alpha = .62f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun LeagueHero(brand: SportsBrand, liveCount: Int, eventCount: Int) {
    Box(
        Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(artColors(brand.key))).padding(22.dp)
    ) {
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(brand.icon, fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = .7f))
            Text(brand.label, fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color.White)
            Text("$liveCount LIVE  •  $eventCount EVENTS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = .7f))
        }
    }
}
