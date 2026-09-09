package com.usportz.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Compact premium brand rail used by the home/sports surfaces. */
@Composable
internal fun PremiumBrandRail(onSelect: (String) -> Unit = {}) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        items(SportsBranding.brands, key = { it.key }) { brand ->
            Surface(
                onClick = { onSelect(brand.label) },
                modifier = Modifier.width(132.dp).height(72.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF111624)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF241B43), Color(0xFF103344)))),
                        contentAlignment = Alignment.Center
                    ) { Text(brand.icon, fontSize = 9.sp, fontWeight = FontWeight.Black) }
                    Column(Modifier.padding(start = 9.dp)) {
                        Text(brand.label, fontWeight = FontWeight.Black, fontSize = 12.sp, maxLines = 1)
                        Text(brand.accent, color = Color(0xFF7E8799), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PremiumSectionHeader(title: String, detail: String = "") {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
        if (detail.isNotBlank()) Text(detail, color = Color(0xFF7E8799), fontSize = 10.sp)
    }
}
