package com.usportz.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
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
    private const val DEVICES = "devices"
    private const val MAX_BODY = 16_384

    data class TvEndpoint(val name: String, val host: InetAddress, val port: Int)
    data class PairedTv(val id: String, val name: String, val host: String, val port: Int)

    fun isPaired(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PAIRED, false)
    fun markPaired(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PAIRED, true).apply()
    fun clearPairing(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()

    fun pairedTvs(context: Context): List<PairedTv> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DEVICES, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val id = o.optString("id").trim()
                    val name = o.optString("name").trim()
                    val host = o.optString("host").trim()
                    val port = o.optInt("port", 0)
                    if (id.isNotBlank() && name.isNotBlank() && host.isNotBlank() && port in 1..65535) {
                        add(PairedTv(id, name, host, port))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun addPairedTv(context: Context, endpoint: TvEndpoint) {
        val devices = pairedTvs(context).toMutableList()
        val host = endpoint.host.hostAddress.orEmpty()
        val id = "${endpoint.name.lowercase().trim()}|$host|${endpoint.port}"
        val item = PairedTv(id, endpoint.name, host, endpoint.port)
        val index = devices.indexOfFirst { it.id == id }
        if (index >= 0) devices[index] = item else devices.add(item)
        writeDevices(context, devices)
    }

    fun removePairedTv(context: Context, id: String) {
        writeDevices(context, pairedTvs(context).filterNot { it.id == id })
    }

    private fun writeDevices(context: Context, devices: List<PairedTv>) {
        val array = JSONArray()
        devices.forEach { d ->
            array.put(JSONObject().apply {
                put("id", d.id); put("name", d.name); put("host", d.host); put("port", d.port)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(DEVICES, array.toString())
            .putBoolean(PAIRED, devices.isNotEmpty())
            .apply()
    }

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
        return b64(iv) + "." + b64(cipher.doFinal(json.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun decryptBundle(code: String, value: String): String {
        val parts = value.split('.', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(code), GCMParameterSpec(128, Base64.decode(parts[0], Base64.URL_SAFE or Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }

    private fun key(code: String): SecretKeySpec = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(("USportzPair/v1/" + code).toByteArray(StandardCharsets.UTF_8)), "AES")
    private fun b64(value: ByteArray): String = Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun bundleForStore(store: SourceStore): String = JSONObject().apply {
        put("server", store.server); put("username", store.user); put("password", store.pass); put("playlist", store.playlist)
    }.toString()

    suspend fun sendBundle(endpoint: TvEndpoint, code: String, store: SourceStore): Boolean = withContext(Dispatchers.IO) {
        val body = encryptBundle(code, bundleForStore(store)).toByteArray(StandardCharsets.UTF_8)
        if (body.size > MAX_BODY) return@withContext false
        runCatching {
            Socket(endpoint.host, endpoint.port).use { socket ->
                socket.soTimeout = 8_000
                val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                writer.write("POST /pair HTTP/1.1\r\nHost: ${endpoint.host.hostAddress}\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                writer.flush(); socket.getOutputStream().write(body); socket.getOutputStream().flush()
                BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine().orEmpty().contains(" 200 ")
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
                    server = ss; port = ss.localPort; advertise(); running.set(true)
                    while (running.get()) handle(ss.accept(), code)
                } catch (_: Throwable) { running.set(false) }
            }.apply { name = "USportzPairServer"; isDaemon = true; start() }
        }

        private fun advertise() {
            val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsd = manager
            val info = NsdServiceInfo().apply { serviceName = SERVICE_PREFIX + currentCode(context); serviceType = SERVICE_TYPE; setPort(port) }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
            registration = listener; manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }

        private fun handle(socket: Socket, code: String) {
            socket.use {
                try {
                    socket.soTimeout = 8_000
                    val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    val request = input.readLine().orEmpty(); var contentLength = 0
                    while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; if (line.startsWith("Content-Length:", true)) contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0 }
                    if (!request.startsWith("POST /pair ") || contentLength !in 1..MAX_BODY) return
                    val body = CharArray(contentLength); var offset = 0
                    while (offset < contentLength) { val read = input.read(body, offset, contentLength - offset); if (read <= 0) break; offset += read }
                    if (offset != contentLength) return
                    val json = JSONObject(decryptBundle(code, String(body)))
                    val serverValue = json.optString("server"); val username = json.optString("username"); val password = json.optString("password"); val playlist = json.optString("playlist")
                    if (serverValue.isBlank() && playlist.isBlank()) return
                    SourceStore(context).importPairedSource(serverValue, username, password, playlist)
                    markPaired(context)
                    OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8).apply { write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"); flush() }
                    onPaired(true)
                } catch (_: Throwable) {
                    runCatching { OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8).apply { write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"); flush() } }
                }
            }
        }

        fun stop() {
            running.set(false); runCatching { server?.close() }; server = null; thread = null
            registration?.let { runCatching { nsd?.unregisterService(it) } }; registration = null
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
                    override fun onServiceResolved(resolved: NsdServiceInfo) { val host = resolved.host ?: return; if (resolved.port > 0) onFound(TvEndpoint(resolved.serviceName, host, resolved.port)) }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) { onFinished() }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { onFinished() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener); return listener
    }

    fun stopDiscover(context: Context, listener: NsdManager.DiscoveryListener) { runCatching { (context.getSystemService(Context.NSD_SERVICE) as NsdManager).stopServiceDiscovery(listener) } }
}
