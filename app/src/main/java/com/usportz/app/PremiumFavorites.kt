package com.usportz.app

import android.content.Context

/** Device-local favorites used by the premium shell. */
private class Favs(c: Context) {
    private val prefs = c.getSharedPreferences("usportz_favs", Context.MODE_PRIVATE)

    private fun values(key: String): Set<String> = prefs.getStringSet(key, emptySet()) ?: emptySet()

    private fun toggle(key: String, value: String) {
        val next = values(key).toMutableSet()
        if (!next.add(value)) next.remove(value)
        prefs.edit().putStringSet(key, next).apply()
    }

    fun isChannelFav(value: String) = values("channels").contains(value)
    fun isEventFav(value: String) = values("events").contains(value)
    fun toggleChannel(value: String) = toggle("channels", value)
    fun toggleEvent(value: String) = toggle("events", value)
}
