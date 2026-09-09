package com.usportz.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Local-only, one-time phone -> TV credential handoff. Credentials never go through a cloud service. */
object PairingManager {
    private const val SERVICE_TYPE = "_usportz._tcp."
    private const val SERVICE_PREFIX = "USportz-TV-"
    private const val PREFS = "usportz_pairing"
    private const val PAIRED = "paired"
    private const val CODE = "code"
    private const val MAX_BODY = 16_384

    data class TvEndpoint(val name: String, val host: InetAddress, val port: Int)

    fun isPaired(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PAIRED, false)

    fun markPaired(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PAIRED, true).apply()

    fun clearPairing(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()

    fun newCode(context: Context): String {
        val code = (100000..999999).random().toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CODE, code).apply()
        return code
    }

    fun currentCode(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CODE, "").orEmpty()

    fun encryptBundle(code: String, json: String): String {
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(code), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(json.toByteArray(StandardCharsets.UTF_8))
        return b64(iv) + "." + b64(ciphertext)
    }

    private fun decryptBundle(code: String, value: String): String {
        val parts = value.split('.', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(code), GCMParameterSpec(128, java.util.Base64.getDecoder().decode(parts[0])))
        return String(cipher.doFinal(java.util.Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8)
    }

    private fun key(code: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256").digest(("USportzPair/v1/" + code).toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    private fun b64(value: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    fun bundleForStore(store: SourceStore): String = JSONObject().apply {
        put("server", store.server)
        put("username", store.user)
        put("password", store.pass)
        put("playlist", store.playlist)
    }.toString()

    suspend fun sendBundle(endpoint: TvEndpoint, code: String, store: SourceStore): Boolean = withContext(Dispatchers.IO) {
        val encrypted = encryptBundle(code, bundleForStore(store))
        val body = encrypted.toByteArray(StandardCharsets.UTF_8)
        if (body.size > MAX_BODY) return@withContext false
        runCatching {
            Socket(endpoint.host, endpoint.port).use { socket ->
                socket.soTimeout = 8_000
                val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                writer.write("POST /pair HTTP/1.1\r\nHost: ${endpoint.host.hostAddress}\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                writer.flush()
                socket.getOutputStream().write(body)
                socket.getOutputStream().flush()
                val response = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine().orEmpty()
                response.contains(" 200 ")
            }
        }.getOrDefault(false)
    }

    class TvServer(private val context: Context, private val onPaired: (Boolean) -> Unit) {
        private var server: ServerSocket? = null
        private var nsd: NsdManager? = null
        private var registration: NsdManager.RegistrationListener? = null
        private val running = AtomicBoolean(false)
        private var thread: Thread? = null
        var port: Int = 0
            private set

        fun start(code: String) {
            stop()
            thread = Thread {
                try {
                    val ss = ServerSocket(0)
                    server = ss
                    port = ss.localPort
                    advertise()
                    running.set(true)
                    while (running.get()) handle(ss.accept(), code)
                } catch (_: Throwable) {
                    running.set(false)
                }
            }.apply { name = "USportzPairServer"; isDaemon = true; start() }
        }

        private fun advertise() {
            val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsd = manager
            val info = NsdServiceInfo().apply {
                serviceName = SERVICE_PREFIX + currentCode(context)
                serviceType = SERVICE_TYPE
                setPort(port)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
            registration = listener
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }

        private fun handle(socket: Socket, code: String) {
            socket.use {
                try {
                    socket.soTimeout = 8_000
                    val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    val request = input.readLine().orEmpty()
                    var contentLength = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                    if (!request.startsWith("POST /pair ") || contentLength !in 1..MAX_BODY) return
                    val body = CharArray(contentLength)
                    var offset = 0
                    while (offset < contentLength) {
                        val read = input.read(body, offset, contentLength - offset)
                        if (read <= 0) break
                        offset += read
                    }
                    if (offset != contentLength) return
                    val json = JSONObject(decryptBundle(code, String(body)))
                    val serverValue = json.optString("server")
                    val username = json.optString("username")
                    val password = json.optString("password")
                    val playlist = json.optString("playlist")
                    if (serverValue.isBlank() && playlist.isBlank()) return
                    val store = SourceStore(context)
                    store.importPairedSource(serverValue, username, password, playlist)
                    markPaired(context)
                    val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                    writer.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK")
                    writer.flush()
                    onPaired(true)
                } catch (_: Throwable) {
                    runCatching {
                        val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                        writer.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        writer.flush()
                    }
                }
            }
        }

        fun stop() {
            running.set(false)
            runCatching { server?.close() }
            server = null
            thread = null
            registration?.let { runCatching { nsd?.unregisterService(it) } }
            registration = null
        }
    }

    fun discover(context: Context, onFound: (TvEndpoint) -> Unit, onFinished: () -> Unit = {}): NsdManager.DiscoveryListener {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("_usportz._tcp")) return
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host ?: return
                        if (resolved.port > 0) onFound(TvEndpoint(resolved.serviceName, host, resolved.port))
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) { onFinished() }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { onFinished() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        return listener
    }

    fun stopDiscover(context: Context, listener: NsdManager.DiscoveryListener) {
        runCatching { (context.getSystemService(Context.NSD_SERVICE) as NsdManager).stopServiceDiscovery(listener) }
    }
}
