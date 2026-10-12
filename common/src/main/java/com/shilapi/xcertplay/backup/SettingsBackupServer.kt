package com.shilapi.xcertplay.backup

import com.shilapi.xcertplay.airplay.RtspMessage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class SettingsBackupEndpoint(val port: Int, val token: String) {
    fun url(address: String) = "http://$address:$port/b/$token/"
}

/**
 * One-request-per-connection HTTP transport that serves the settings backup page.
 *
 * Each connection is read on its own thread. A browser opens spare connections before it needs
 * them and may never use them; read in turn, one of those would hold up every request behind it
 * until it timed out. The requests themselves are still answered one at a time.
 */
class SettingsBackupServer(
    private val page: ByteArray,
    private val export: () -> ByteArray,
    private val import: (ByteArray) -> SettingsImportOutcome,
) {
    private var server: ServerSocket? = null
    private var thread: Thread? = null
    private var connections: ExecutorService? = null
    private val open: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())
    private val answering = Any()
    private var token: String = ""
    @Volatile private var closed = true
    var onImported: ((SettingsImportOutcome) -> Unit)? = null
    /** Called on the server thread when nobody used the page for [idleTimeoutMillis]. */
    var onIdle: (() -> Unit)? = null
    var idleTimeoutMillis = IDLE_TIMEOUT_MILLIS

    fun start(): SettingsBackupEndpoint? {
        val bound = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                // Anyone on the car's network can reach it, so it does not wait for them forever.
                soTimeout = idleTimeoutMillis
                bind(InetSocketAddress(0), BACKLOG)
            }
        }.getOrNull() ?: return null
        val random = ByteArray(8)
        SecureRandom().nextBytes(random)
        token = random.joinToString("") { "%02x".format(it) }
        server = bound
        connections = ThreadPoolExecutor(0, MAX_CONNECTIONS, 30L, TimeUnit.SECONDS, SynchronousQueue()) { task ->
            Thread(task, "settings-backup-connection").apply { isDaemon = true }
        }
        closed = false
        thread = Thread({ loop() }, "settings-backup").apply { isDaemon = true; start() }
        return SettingsBackupEndpoint(bound.localPort, token)
    }

    fun stop() {
        closed = true
        runCatching { server?.close() }
        server = null
        thread = null
        connections?.shutdown()
        connections = null
        // A connection still waiting for its request would otherwise outlive the page by its timeout.
        // Walked in place: connection threads drop their own entry at any moment, and a copy taken
        // with toList() can fail when one goes between its size check and its read.
        for (socket in open) runCatching { socket.close() }
    }

    private fun loop() {
        while (!closed) {
            val socket = try {
                server?.accept()
            } catch (_: SocketTimeoutException) {
                if (!closed) {
                    stop()
                    onIdle?.invoke()
                }
                null
            } catch (_: Exception) {
                null
            } ?: break
            try {
                connections?.execute { handle(socket) } ?: socket.close()
            } catch (_: RejectedExecutionException) {
                // Every thread is busy: refuse this one, and the browser asks again.
                runCatching { socket.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        open.add(socket)
        if (closed) runCatching { socket.close() }
        try {
            serve(socket)
        } finally {
            open.remove(socket)
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            runCatching {
                it.soTimeout = 10_000
                it.tcpNoDelay = true
                val request = readRequest(it.getInputStream()) ?: return
                // A failure while preparing the answer is told to the browser; without an answer it
                // would show a connection that opened and then gave nothing.
                val (status, headers, body) = synchronized(answering) {
                    runCatching { respond(request) }.getOrElse { failed() }
                }
                it.getOutputStream().apply {
                    write(
                        RtspMessage.buildResponse(
                            request,
                            RtspMessage.Response(
                                protocol = "HTTP/1.1",
                                status = status,
                                headers = headers + ("Connection" to "close"),
                                body = body,
                            ),
                        ),
                    )
                    flush()
                }
            }
        }
    }

    private fun readRequest(input: InputStream): RtspMessage.Request? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (buffer.size() <= MAX_REQUEST) {
            val read = try { input.read(chunk) } catch (_: SocketTimeoutException) { break } ?: break
            if (read <= 0) break
            buffer.write(chunk, 0, read)
            val parsed = RtspMessage.parseMessages(buffer.toByteArray())
            if (parsed.messages.isNotEmpty()) return parsed.messages.first()
        }
        return null
    }

    private fun respond(request: RtspMessage.Request): Triple<Int, Map<String, String>, ByteArray> {
        val path = request.path.substringBefore('?')
        val prefix = "/b/$token"
        val authorized = path == prefix || path.startsWith("$prefix/")
        if (!authorized) return notFound()
        val route = path.removePrefix("$prefix/").trimEnd('/')
        return when {
            route.isEmpty() && request.method == "GET" ->
                Triple(200, mapOf("Content-Type" to "text/html; charset=utf-8", "Cache-Control" to "no-store"), page)
            route == "export" && request.method == "GET" -> Triple(
                200,
                mapOf(
                    "Content-Type" to "application/json",
                    "Content-Disposition" to "attachment; filename=\"$EXPORT_FILE\"",
                    "Cache-Control" to "no-store",
                ),
                export(),
            )
            route == "import" && request.method == "POST" -> {
                val outcome = import(request.body)
                onImported?.invoke(outcome)
                Triple(
                    200,
                    mapOf("Content-Type" to "application/json", "Cache-Control" to "no-store"),
                    "{\"applied\":${outcome.applied},\"skipped\":${outcome.skipped},\"invalid\":${outcome.invalid}}"
                        .toByteArray(Charsets.UTF_8),
                )
            }
            else -> notFound()
        }
    }

    private fun failed() = Triple(500, mapOf("Content-Type" to "text/plain", "Cache-Control" to "no-store"),
        "The head unit could not prepare this. Try again.".toByteArray(Charsets.UTF_8))

    private fun notFound() = Triple(404, mapOf("Content-Type" to "text/plain", "Cache-Control" to "no-store"), "Not found".toByteArray(Charsets.UTF_8))

    companion object {
        private const val BACKLOG = 4
        /** More than a browser opens to one host; the rest are refused rather than queued. */
        private const val MAX_CONNECTIONS = 16
        const val IDLE_TIMEOUT_MILLIS = 10 * 60_000
        private const val MAX_REQUEST = 2 shl 20
        const val EXPORT_FILE = "diplay-settings.json"

        fun addressCandidates(preferredPrefix: String? = null): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { iface -> iface.inetAddresses.toList().asSequence().map { iface to it } }
                .filter { (_, address) ->
                    address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress
                }
                .map { (iface, address) -> rank(iface.name, preferredPrefix) to (address as Inet4Address).hostAddress }
                .filter { it.second != null }
                .sortedBy { it.first }
                .map { it.second!! }
                .distinct()
                .toList()
        }.getOrDefault(emptyList())

        internal fun rank(name: String, preferredPrefix: String?): Int = when {
            preferredPrefix != null && name.startsWith(preferredPrefix) -> 0
            name.startsWith("ap") || name.startsWith("swlan") -> 1
            name.startsWith("p2p") -> 2
            name.startsWith("wlan") -> 3
            else -> 4
        }

        fun tokenMatches(expected: String, provided: String): Boolean =
            MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), provided.toByteArray(Charsets.UTF_8))
    }
}
