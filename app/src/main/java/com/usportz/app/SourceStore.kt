package com.usportz.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
            main.post { progress("Connecting to Xtream…") }
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

            // Tuvora-style flow: authenticate once, stream the complete provider catalogue
            // into the local database in bounded batches, then expose the completed snapshot.
            // Do not fetch a sports-only subset first and do not keep thousands of channel
            // objects in memory. The Sports screen reads only the indexed sports projection.
            main.post { progress("Connected • importing all channels…") }
            runCatching { SportsChannelBridge.load(context, forceRefresh = true) }

            // forceRefresh intentionally starts the disk import without blocking. Wait here
            // using cheap SQLite-backed reads so the login screen does not report success while
            // the dashboard still sees an empty catalogue. No IPTV request is made by this loop.
            var waitedMs = 0L
            var lastProgress = 0L
            var indexedSports = emptyList<SportsChannel>()
            while (waitedMs < 180_000L) {
                delay(1_000L)
                waitedMs += 1_000L
                indexedSports = runCatching { SportsChannelBridge.load(context, forceRefresh = false) }.getOrDefault(emptyList())
                if (indexedSports.isNotEmpty()) break
                if (waitedMs - lastProgress >= 5_000L) {
                    lastProgress = waitedMs
                    val seconds = waitedMs / 1_000L
                    main.post { progress("Connected • importing all channels… ${seconds}s") }
                }
            }

            if (indexedSports.isNotEmpty()) {
                main.post { done(true, "Connected • ${indexedSports.size} sports channels indexed from full catalogue") }
            } else {
                // Authentication succeeded even if this provider is unusually slow or has no
                // channels classified as sports. Keep the credentials and let the normal app
                // refresh continue rather than falsely telling the user that login failed.
                main.post { done(true, "Connected • full channel import is still finishing in background") }
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
                context.deleteSharedPreferences(PREFS_NAME)
                runCatching { create() }.getOrElse { failure ->
                    throw IllegalStateException("Secure credential storage unavailable", failure)
                }
            }
        }
    }
}
