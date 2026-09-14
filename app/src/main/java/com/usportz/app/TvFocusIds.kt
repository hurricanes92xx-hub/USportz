package com.usportz.app

import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag

/** Stable, data-derived TV focus identifiers. Never derive focus identity from list position. */
object TvFocusIds {
    fun screen(name: String): String = "screen:${stable(name)}"
    fun tab(name: String): String = "tab:${stable(name)}"
    fun rail(name: String): String = "rail:${stable(name)}"
    fun event(id: String): String = "event:${stable(id)}"
    fun channel(id: String, url: String): String = "channel:${stable("$id|$url")}"
    fun source(eventId: String, channelId: String, url: String): String = "source:${stable("$eventId|$channelId|$url")}"

    private fun stable(value: String): String = value.trim().lowercase()
}

/** Use with Compose TV rows/cards so focus survives refreshes and reordering. */
fun Modifier.tvFocusId(id: String, group: Boolean = false): Modifier =
    this.then(if (group) Modifier.focusGroup() else Modifier)
        .semantics { testTag = id }
        .focusable()
