package com.usportz.app

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Single premium league hub rail. Uses the existing schedule/channel data layer. */
@Composable
internal fun PremiumBrandRail(onSelect: (String) -> Unit = {}) {
    val context = LocalContext.current
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(SportsBranding.brands, key = { it.key }) { brand ->
            Surface(
                onClick = {
                    onSelect(brand.label)
                    context.startActivity(Intent(context, LeagueHubActivity::class.java).putExtra(LeagueHubActivity.EXTRA_BRAND, brand.key))
                },
                modifier = Modifier.width(142.dp).height(92.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface
            ) { LeagueArtwork(brand, Modifier.padding(0.dp), compact = true) }
        }
    }
}

@Composable
internal fun PremiumSectionHeader(title: String, detail: String = "") {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
        Text(title, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black, modifier = Modifier.weight(1f))
        if (detail.isNotBlank()) Text(detail, color = androidx.compose.ui.graphics.Color(0xFF7E8799), fontSize = 10.sp)
    }
}
