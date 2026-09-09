package com.usportz.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Local source settings only. Channel loading/indexing belongs exclusively to SportsChannelBridge. */
class SourceStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("usportz", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(Dispatchers.IO)

    var server: String
        get() = prefs.getString("server", "").orEmpty()
        private set(value) { prefs.edit().putString("server", value).apply() }
    var user: String
        get() = prefs.getString("user", "").orEmpty()
        private set(value) { prefs.edit().putString("user", value).apply() }
    var pass: String
        get() = prefs.getString("pass", "").orEmpty()
        private set(value) { prefs.edit().putString("pass", value).apply() }
    var playlist: String
        get() = prefs.getString("playlist", "").orEmpty()
        private set(value) { prefs.edit().putString("playlist", value).apply() }

    var tvFeedUrl: String
        get() = prefs.getString("tv_feed_url", "").orEmpty()
        private set(value) { prefs.edit().putString("tv_feed_url", value).apply() }
    var tvFeedToken: String
        get() = prefs.getString("tv_feed_token", "").orEmpty()
        private set(value) { prefs.edit().putString("tv_feed_token", value).apply() }

    fun saveTvFeed(url: String, token: String, done: (Boolean, String) -> Unit) {
        tvFeedUrl = url.trim()
        tvFeedToken = token.trim()
        RoninTvSchedule.configure(context, tvFeedUrl, tvFeedToken)
        main.post { done(true, if (tvFeedUrl.isBlank()) "TV schedule feed cleared" else "TV schedule feed saved") }
    }

    fun saveXtream(base: String, username: String, password: String, done: (Boolean, String) -> Unit) {
        server = base.trimEnd('/'); user = username; pass = password; playlist = ""
        reloadSource(done)
    }

    fun saveM3u(url: String, done: (Boolean, String) -> Unit) {
        playlist = url.trim(); server = ""; user = ""; pass = ""
        reloadSource(done)
    }

    private fun reloadSource(done: (Boolean, String) -> Unit) {
        io.launch {
            val result = runCatching { SportsChannelBridge.load(context, forceRefresh = true) }
            val channels = result.getOrDefault(emptyList())
            main.post {
                if (channels.isNotEmpty()) done(true, "Connected • ${channels.size} channels indexed")
                else done(false, result.exceptionOrNull()?.message?.let { "Source error: $it" } ?: "Source returned no channels")
            }
        }
    }
}
