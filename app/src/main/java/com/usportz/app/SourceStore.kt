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

    /** Used only by the local, one-time phone-to-TV pairing flow. */
    fun importPairedSource(base: String, username: String, password: String, m3u: String) {
        server = SportsChannelBridge.normalizeXtreamServer(base)
        user = username.trim()
        pass = password
        playlist = m3u.trim()
    }

    fun saveXtream(base: String, username: String, password: String, done: (Boolean, String) -> Unit, progress: (String) -> Unit = {}) {
        val cleanUser = username.trim()
        val cleanPass = password
        val normalizedServer = runCatching { SportsChannelBridge.normalizeXtreamServer(base) }.getOrDefault("")
        if (normalizedServer.isBlank() || cleanUser.isBlank() || cleanPass.isBlank()) {
            main.post { done(false, "Enter a valid Xtream server, username and password") }
            return
        }
        io.launch {
            main.post { progress("Testing Xtream server…") }
            val workingServer = runCatching { SportsChannelBridge.authenticateXtream(normalizedServer, cleanUser, cleanPass) }.getOrNull()
            if (workingServer.isNullOrBlank()) {
                main.post { done(false, "Xtream login failed — server, username or password was rejected") }
                return@launch
            }
            runCatching {
                server = workingServer
                user = cleanUser
                pass = cleanPass
                playlist = ""
            }.onFailure {
                main.post { done(false, "Could not save the encrypted source settings on this device") }
                return@launch
            }
            main.post { progress("Authenticated • loading live channels…") }
            val result = runCatching { SportsChannelBridge.load(context, forceRefresh = true) }
            val channels = result.getOrDefault(emptyList())
            if (channels.isNotEmpty()) {
                main.post { progress("Connected • ${channels.size} channels loaded"); done(true, "Connected • ${channels.size} channels loaded") }
            } else {
                main.post { done(false, "Login succeeded, but the provider returned no live channels") }
            }
        }
    }

    fun saveM3u(url: String, done: (Boolean, String) -> Unit) {
        val cleanUrl = url.trim()
        val valid = runCatching {
            val parsed = java.net.URI(cleanUrl)
            parsed.scheme.equals("http", true) || parsed.scheme.equals("https", true)
        }.getOrDefault(false)
        if (!valid) {
            main.post { done(false, "Enter a valid http:// or https:// M3U/M3U8 playlist URL") }
            return
        }
        playlist = cleanUrl
        server = ""; user = ""; pass = ""
        io.launch {
            val result = runCatching { SportsChannelBridge.load(context, forceRefresh = true) }
            val channels = result.getOrDefault(emptyList())
            main.post { if (channels.isNotEmpty()) done(true, "Connected • ${channels.size} channels indexed") else done(false, result.exceptionOrNull()?.message?.let { "Source error: $it" } ?: "Source returned no channels") }
        }
    }

    companion object {
        private const val PREFS_NAME = "usportz"

        private fun securePrefs(context: Context): android.content.SharedPreferences {
            fun create(): android.content.SharedPreferences {
                val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                return EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }
            return runCatching { create() }.getOrElse {
                // A stale/corrupt encrypted preference file must never crash the Sources screen.
                // Remove only this app's encrypted store and recreate it; credentials are never
                // downgraded to plaintext SharedPreferences.
                context.deleteSharedPreferences(PREFS_NAME)
                runCatching { create() }.getOrElse { failure ->
                    throw IllegalStateException("Secure credential storage unavailable", failure)
                }
            }
        }
    }
}
