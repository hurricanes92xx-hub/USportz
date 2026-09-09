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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private fun eventColors(brand: SportsBrand?, event: SportsEvent): List<Color> {
    val key = brand?.key.orEmpty()
    val title = "${event.name} ${event.shortName}".lowercase()
    return when {
        key == "wwe" && title.contains("raw") -> listOf(Color(0xFF6E0B18), Color(0xFF13070A))
        key == "wwe" && title.contains("smackdown") -> listOf(Color(0xFF0B3E75), Color(0xFF070C15))
        key == "wwe" && title.contains("nxt") -> listOf(Color(0xFF3E2A06), Color(0xFF0B0A08))
        key == "aew" && title.contains("dynamite") -> listOf(Color(0xFF173C63), Color(0xFF080C14))
        key == "aew" && title.contains("collision") -> listOf(Color(0xFF4A1118), Color(0xFF0C080B))
        key == "aew" && title.contains("all in") -> listOf(Color(0xFF24144E), Color(0xFF090710))
        key == "tna" && title.contains("impact") -> listOf(Color(0xFF2C4510), Color(0xFF090D08))
        key == "tna" && title.contains("hard to kill") -> listOf(Color(0xFF4B1B12), Color(0xFF100907))
        key == "roh" && title.contains("final battle") -> listOf(Color(0xFF6B1018), Color(0xFF10070A))
        key == "roh" && title.contains("supercard") -> listOf(Color(0xFF3A183F), Color(0xFF0D0810))
        key == "ufc" || key == "boxing" -> listOf(Color(0xFF53111B), Color(0xFF0D080A))
        key == "nfl" -> listOf(Color(0xFF103B69), Color(0xFF070E18))
        key == "nba" || key == "wnba" -> listOf(Color(0xFF4B185E), Color(0xFF0B1320))
        key == "mlb" -> listOf(Color(0xFF164A68), Color(0xFF090F18))
        key == "nhl" -> listOf(Color(0xFF184C58), Color(0xFF080E13))
        key == "mls" || key == "epl" -> listOf(Color(0xFF193E80), Color(0xFF0A0E18))
        key == "nascar" || key == "indycar" || key == "f1" || key == "motogp" -> listOf(Color(0xFF55220F), Color(0xFF0D0B09))
        key == "tennis" || key == "golf" -> listOf(Color(0xFF175335), Color(0xFF08120D))
        else -> listOf(Color(0xFF2D214F), Color(0xFF0B101A))
    }
}

private fun eventTitle(brand: SportsBrand?, event: SportsEvent): String {
    val title = "${event.name} ${event.shortName}".lowercase()
    return when (brand?.key) {
        "wwe" -> when { "raw" in title -> "MONDAY NIGHT RAW"; "smackdown" in title -> "SMACKDOWN"; "nxt" in title -> "NXT"; else -> event.shortName.ifBlank { event.name } }
        "aew" -> when { "dynamite" in title -> "DYNAMITE"; "collision" in title -> "COLLISION"; "all in" in title -> "ALL IN"; else -> event.shortName.ifBlank { event.name } }
        "tna" -> when { "impact" in title -> "IMPACT!"; "hard to kill" in title -> "HARD TO KILL"; else -> event.shortName.ifBlank { event.name } }
        "roh" -> when { "final battle" in title -> "FINAL BATTLE"; "supercard" in title -> "SUPERCARD OF HONOR"; else -> event.shortName.ifBlank { event.name } }
        else -> event.shortName.ifBlank { event.name }
    }
}

@Composable
internal fun EventArtwork(event: SportsEvent, brand: SportsBrand? = SportsPresentation.brand(event), compact: Boolean = false) {
    val height = if (compact) 108.dp else 138.dp
    Box(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(if (compact) 16.dp else 20.dp))
            .background(Brush.linearGradient(eventColors(brand, event))).padding(if (compact) 12.dp else 15.dp)
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.TopCenter).background(Color.White.copy(alpha = .18f)))
        if (event.competitorLogos.isNotEmpty()) {
            Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                event.competitorLogos.take(2).forEachIndexed { index, logo ->
                    if (logo.isNotBlank()) AsyncImage(logo, event.competitors.getOrNull(index).orEmpty(), Modifier.size(if (compact) 42.dp else 52.dp), contentScale = ContentScale.Fit)
                }
            }
        }
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(brand?.icon ?: "SPORTS", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = .7f))
            Text(eventTitle(brand, event), fontSize = if (compact) 18.sp else 23.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (brand?.key !in setOf("wwe", "aew", "tna", "roh") && event.competitors.size >= 2) {
                Text(event.competitors.take(2).joinToString("  VS  "), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = .78f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else if (!compact) {
                Text(event.detail.ifBlank { "LIVE EVENT" }, fontSize = 9.sp, color = Color.White.copy(alpha = .7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
