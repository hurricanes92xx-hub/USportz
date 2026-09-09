package com.usportz.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Secure local source settings. Credentials are encrypted at rest and never logged. */
class SourceStore(private val context: Context) {
    private val prefs by lazy { securePrefs(context) }
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

    fun saveXtream(
        base: String,
        username: String,
        password: String,
        done: (Boolean, String) -> Unit,
        progress: (String) -> Unit = {}
    ) {
        server = base.trimEnd('/')
        user = username.trim()
        pass = password
        playlist = ""
        reloadSource(done, progress)
    }

    fun saveM3u(url: String, done: (Boolean, String) -> Unit) {
        playlist = url.trim()
        server = ""
        user = ""
        pass = ""
        reloadSource(done)
    }

    private fun reloadSource(done: (Boolean, String) -> Unit, progress: (String) -> Unit = {}) {
        io.launch {
            main.post { progress("Connecting to source…") }
            val result = runCatching {
                main.post { progress("Downloading full channel inventory…") }
                SportsChannelBridge.load(context, forceRefresh = true)
            }
            val channels = result.getOrDefault(emptyList())
            main.post {
                if (channels.isNotEmpty()) {
                    done(true, "Connected • ${channels.size} channels indexed")
                } else {
                    done(false, result.exceptionOrNull()?.message?.let { "Source error: $it" } ?: "Source returned no channels")
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "usportz"

        private fun securePrefs(context: Context) = runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            throw IllegalStateException("Secure credential storage unavailable", it)
        }
    }
}
