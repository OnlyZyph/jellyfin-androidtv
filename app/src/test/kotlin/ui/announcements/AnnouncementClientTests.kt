package org.jellyfin.androidtv.ui.announcements

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.io.Closeable
import kotlin.concurrent.thread
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeoutOrNull

// Use only standard sockets: Android's test compile classpath omits jdk.httpserver.
private class TestHttpServer(private val respond: (String, Map<String, String>) -> Pair<Int, String>) : Closeable {
	private val listener = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
	val port get() = listener.localPort
	private val worker = thread(isDaemon = true) {
		try {
			while (!listener.isClosed) listener.accept().use { socket ->
				val reader = socket.getInputStream().bufferedReader()
				val path = reader.readLine().split(' ')[1]
				val headers = mutableMapOf<String, String>()
				while (true) {
					val line = reader.readLine() ?: break
					if (line.isEmpty()) break
					headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
				}
				val (status, body) = respond(path, headers)
				val bytes = body.toByteArray(Charsets.UTF_8)
				val response = "HTTP/1.1 $status Test\r\nContent-Length: ${bytes.size}\r\nLocation: /redirected\r\nConnection: close\r\n\r\n"
				socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII) + bytes)
			}
		} catch (_: IOException) {
			// Closing the listener or a cancelled request terminates the fixture.
		}
	}
	override fun close() { listener.close(); worker.join(1000) }
}

private suspend fun expectHttpFailure(block: suspend () -> Unit) {
	var failed = false
	try { block() } catch (_: IOException) { failed = true }
	failed shouldBe true
}

class AnnouncementClientTests : FunSpec({
	test("cancelling a session cancels a pending HTTP request") {
		val release = CountDownLatch(1)
		val server = TestHttpServer { _, _ ->
			release.await(5, TimeUnit.SECONDS)
			200 to "[]"
		}
		try {
			val identity = AnnouncementIdentity("http://127.0.0.1:${server.port}", "abcd", "test-token")
			withTimeoutOrNull(250) { AnnouncementClient().fetch(identity); true } shouldBe null
		} finally {
			release.countDown()
			server.close()
		}
	}
	test("authenticated requests preserve reverse proxy paths and filter users") {
		val requests = CopyOnWriteArrayList<Pair<String, String?>>()
		val server = TestHttpServer { path, headers ->
			requests.add(path to headers["x-emby-token"])
			val body = when (path) {
				"/jellyfin/Plugins/Announcements" -> """[
					{"id":"1","message":"TV notice","includeDeviceTypes":["tv"],"includeLibraryIds":["lib"]},
					{"id":"2","message":"Admin only","audience":"admins"}
				]"""
				"/jellyfin/Users/abcd" -> """{"Id":"abcd","Policy":{"IsAdministrator":false}}"""
				"/jellyfin/Users/abcd/Views" -> """{"Items":[{"Id":"view","CollectionFolderId":"lib"}]}"""
				else -> error("Unexpected request")
			}
			200 to body
		}
		try {
			val identity = AnnouncementIdentity("http://127.0.0.1:${server.port}/jellyfin", "abcd", "test-token")
			AnnouncementClient().fetch(identity).map { it.id } shouldBe listOf("1")
			requests.size shouldBe 3
			requests.all { it.second == "test-token" } shouldBe true
		} finally {
			server.close()
		}
	}
	test("missing plugin is harmless and redirects are not followed") {
		var status = 404
		var redirected = false
		val server = TestHttpServer { path, _ ->
			if (path == "/redirected") redirected = true
			status to ""
		}
		try {
			val identity = AnnouncementIdentity("http://127.0.0.1:${server.port}", "abcd", "test-token")
			val client = AnnouncementClient()
			client.fetch(identity) shouldBe emptyList()
			status = 302
			expectHttpFailure { client.fetch(identity) }
			redirected shouldBe false
			status = 401
			expectHttpFailure { client.fetch(identity) }
		} finally {
			server.close()
		}
	}
})
