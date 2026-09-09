package com.usportz.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private fun LazyListScope.SearchGroup(title: String, values: List<String>, onClick: (String) -> Unit) {
    if (values.isEmpty()) return
    item { SectionTitle(title, "${values.size} matches") }
    values.forEach { value ->
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { onClick(value) }) {
                Text(value, Modifier.padding(15.dp), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
