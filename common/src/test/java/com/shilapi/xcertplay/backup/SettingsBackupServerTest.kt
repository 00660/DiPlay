package com.shilapi.xcertplay.backup

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsBackupServerTest {
    private lateinit var server: SettingsBackupServer
    private lateinit var endpoint: SettingsBackupEndpoint
    private val imported = AtomicReference<SettingsImportOutcome?>()

    @Before
    fun startServer() {
        server = SettingsBackupServer(
            page = "<html>backup-page</html>".toByteArray(),
            export = { "{\"exported\":true}".toByteArray() },
            import = { document ->
                val outcome = SettingsImportOutcome(4, 1, invalid = false)
                imported.set(outcome)
                outcome
            },
        )
        val started = server.start()
        assertNotNull(started)
        endpoint = started!!
    }

    @After
    fun stopServer() {
        server.stop()
    }

    private fun get(path: String, method: String = "GET", body: ByteArray? = null): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${endpoint.port}$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        if (body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.getOutputStream().use { it.write(body) }
        }
        val status = connection.responseCode
        val stream = if (status < 400) connection.inputStream else connection.errorStream
        val text = stream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        connection.disconnect()
        return status to text
    }

    @Test
    fun stopsByItselfWhenNobodyUsesThePage() {
        val idle = java.util.concurrent.CountDownLatch(1)
        val quiet = SettingsBackupServer(page = ByteArray(0), export = { ByteArray(0) },
            import = { SettingsImportOutcome(0, 0, invalid = false) })
        quiet.idleTimeoutMillis = 200
        quiet.onIdle = { idle.countDown() }
        val started = quiet.start()!!
        try {
            assertTrue(idle.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(runCatching { Socket("127.0.0.1", started.port).close() }.isFailure)
        } finally {
            quiet.stop()
        }
    }

    @Test
    fun servesThePageOnlyUnderTheTokenPath() {
        val (status, body) = get("/b/${endpoint.token}/")
        assertEquals(200, status)
        assertTrue(body.contains("backup-page"))
        assertEquals(404, get("/").first)
        assertEquals(404, get("/b/wrong-token/").first)
        assertEquals(404, get("/export").first)
    }

    @Test
    fun exportReturnsTheDocumentAsAnAttachment() {
        val (status, body) = get("/b/${endpoint.token}/export")
        assertEquals(200, status)
        assertEquals("{\"exported\":true}", body)
    }

    @Test
    fun importAppliesTheDocumentAndReportsTheOutcome() {
        val document = "{\"format\":\"diplay-settings\",\"version\":1,\"settings\":{}}"
        val (status, body) = get("/b/${endpoint.token}/import", method = "POST", body = document.toByteArray())
        assertEquals(200, status)
        assertTrue(body.contains("\"applied\":4"))
        assertTrue(body.contains("\"skipped\":1"))
        assertTrue(body.contains("\"invalid\":false"))
        assertEquals(4, imported.get()?.applied)
    }

    // Browsers open spare connections before they need them and may never use them.
    @Test
    fun aConnectionThatSendsNothingDoesNotHoldUpTheNextRequest() {
        Socket("127.0.0.1", endpoint.port).use {
            Thread.sleep(200)
            val started = System.nanoTime()
            val (status, body) = get("/b/${endpoint.token}/")
            val millis = (System.nanoTime() - started) / 1_000_000
            assertEquals(200, status)
            assertTrue(body.contains("backup-page"))
            assertTrue("the page took $millis ms behind an idle connection", millis < 2_000)
        }
    }

    @Test
    fun severalIdleConnectionsDoNotHoldUpTheNextRequestEither() {
        val idle = List(6) { Socket("127.0.0.1", endpoint.port) }
        try {
            Thread.sleep(200)
            assertEquals(200, get("/b/${endpoint.token}/export").first)
        } finally {
            idle.forEach { it.close() }
        }
    }

    @Test
    fun stopAlsoClosesAConnectionThatIsStillWaiting() {
        Socket("127.0.0.1", endpoint.port).use { waiting ->
            Thread.sleep(200)
            waiting.soTimeout = 3_000
            server.stop()
            // End of stream, or a reset: either way the head unit let go of it at once.
            val read = runCatching { waiting.getInputStream().read() }
            assertTrue(read.isFailure && read.exceptionOrNull() !is java.net.SocketTimeoutException || read.getOrNull() == -1)
        }
    }

    // Connections end on their own threads while stop() walks the ones still open.
    @Test
    fun stopDoesNotFailWhileConnectionsAreEnding() {
        repeat(1000) {
            val busy = SettingsBackupServer(page = ByteArray(0), export = { ByteArray(0) },
                import = { SettingsImportOutcome(0, 0, invalid = false) })
            val started = busy.start()!!
            val sockets = List(3) { Socket("127.0.0.1", started.port) }
            sockets.forEach { it.close() }
            busy.stop()
        }
    }

    // Without an answer the browser shows a connection that opened and then gave nothing.
    @Test
    fun aFailureWhilePreparingTheAnswerIsReportedToTheBrowser() {
        val failing = SettingsBackupServer(page = ByteArray(0), export = { error("storage unavailable") },
            import = { SettingsImportOutcome(0, 0, invalid = false) })
        val started = failing.start()!!
        try {
            val connection = URL("http://127.0.0.1:${started.port}/b/${started.token}/export").openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            assertEquals(500, connection.responseCode)
            connection.disconnect()
            // The page still answers afterwards.
            val page = URL("http://127.0.0.1:${started.port}/b/${started.token}/").openConnection() as HttpURLConnection
            assertEquals(200, page.responseCode)
            page.disconnect()
        } finally {
            failing.stop()
        }
    }

    @Test
    fun unknownRoutesAreNotFound() {
        assertEquals(404, get("/b/${endpoint.token}/settings").first)
        assertEquals(404, get("/b/${endpoint.token}/export", method = "POST", body = ByteArray(0)).first)
    }

    @Test
    fun stopClosesTheListener() {
        server.stop()
        val failure = runCatching {
            Socket("127.0.0.1", endpoint.port).use { it.getOutputStream().write(1) }
        }.exceptionOrNull()
        assertNotNull(failure)
    }

    @Test
    fun rankPrefersHotspotThenP2pThenStationInterfaces() {
        assertEquals(1, SettingsBackupServer.rank("ap0", null))
        assertEquals(1, SettingsBackupServer.rank("swlan0", null))
        assertEquals(2, SettingsBackupServer.rank("p2p0", null))
        assertEquals(3, SettingsBackupServer.rank("wlan0", null))
        assertEquals(3, SettingsBackupServer.rank("wlan1", null))
        assertEquals(4, SettingsBackupServer.rank("rmnet_data0", null))
        assertEquals(4, SettingsBackupServer.rank("lo", null))
    }

    @Test
    fun rankPutsThePreferredModeInterfaceFirst() {
        assertEquals(0, SettingsBackupServer.rank("p2p0", "p2p"))
        assertEquals(0, SettingsBackupServer.rank("ap0", "ap"))
        assertEquals(0, SettingsBackupServer.rank("wlan0", "wlan"))
        assertEquals(2, SettingsBackupServer.rank("p2p0", "ap"))
        assertEquals(3, SettingsBackupServer.rank("wlan1", "p2p"))
        assertEquals(4, SettingsBackupServer.rank("rmnet_data0", "p2p"))
    }
}
