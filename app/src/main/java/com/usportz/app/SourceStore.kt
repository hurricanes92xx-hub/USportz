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

    /**
     * Fast Xtream sign-in: authenticate against the small player_api response first,
     * then build the complete channel index off the UI path. The user can enter the
     * app as soon as credentials are accepted instead of waiting for a huge M3U.
     */
    fun saveXtream(
        base: String,
        username: String,
        password: String,
        done: (Boolean, String) -> Unit,
        progress: (String) -> Unit = {}
    ) {
        val cleanBase = base.trimEnd('/')
        val cleanUser = username.trim()
        val cleanPass = password
        if (cleanBase.isBlank() || cleanUser.isBlank() || cleanPass.isBlank()) {
            main.post { done(false, "Enter server, username and password") }
            return
        }

        io.launch {
            main.post { progress("Authenticating…") }
            val valid = SportsChannelBridge.validateXtream(cleanBase, cleanUser, cleanPass)
            if (!valid) {
                main.post { done(false, "Xtream login failed — check server or credentials") }
                return@launch
            }

            server = cleanBase
            user = cleanUser
            pass = cleanPass
            playlist = ""

            // Never make the initial UI wait on the complete playlist. Index it in the
            // existing IO scope; future launches use the on-device channel cache.
            main.post { progress("Connected • indexing channels in background…") }
            main.post { done(true, "Connected • channel indexing started") }

            io.launch {
                runCatching { SportsChannelBridge.load(context, forceRefresh = true) }
                    .onSuccess { channels -> main.post { progress("Indexed ${channels.size} channels") } }
                    .onFailure { error -> main.post { progress("Connected • channel indexing retry needed: ${error.message ?: "source error"}") } }
            }
        }
    }

    fun saveM3u(url: String, done: (Boolean, String) -> Unit) {
        playlist = url.trim()
        server = ""
        user = ""
        pass = ""
        io.launch {
            val result = runCatching { SportsChannelBridge.load(context, forceRefresh = true) }
            val channels = result.getOrDefault(emptyList())
            main.post {
                if (channels.isNotEmpty()) done(true, "Connected • ${channels.size} channels indexed")
                else done(false, result.exceptionOrNull()?.message?.let { "Source error: $it" } ?: "Source returned no channels")
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
