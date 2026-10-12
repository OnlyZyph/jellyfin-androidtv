package org.jellyfin.androidtv.ui.announcements

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeoutOrNull

class AnnouncementClientTests : FunSpec({
	test("cancelling a session cancels a pending HTTP request") {
		val release = CountDownLatch(1)
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/") { exchange ->
			release.await(5, TimeUnit.SECONDS)
			exchange.close()
		}
		server.start()
		try {
			val identity = AnnouncementIdentity("http://127.0.0.1:${server.address.port}", "abcd", "test-token")
			withTimeoutOrNull(250) { AnnouncementClient().fetch(identity); true } shouldBe null
		} finally {
			release.countDown()
			server.stop(0)
		}
	}
	test("authenticated requests preserve reverse proxy paths and filter users") {
		val requests = CopyOnWriteArrayList<Pair<String, String?>>()
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/") { exchange ->
			requests.add(exchange.requestURI.path to exchange.requestHeaders.getFirst("X-Emby-Token"))
			val body = when (exchange.requestURI.path) {
				"/jellyfin/Plugins/Announcements" -> """[
					{"id":"1","message":"TV notice","includeDeviceTypes":["tv"],"includeLibraryIds":["lib"]},
					{"id":"2","message":"Admin only","audience":"admins"}
				]"""
				"/jellyfin/Users/abcd" -> """{"Id":"abcd","Policy":{"IsAdministrator":false}}"""
				"/jellyfin/Users/abcd/Views" -> """{"Items":[{"Id":"view","CollectionFolderId":"lib"}]}"""
				else -> error("Unexpected request")
			}.toByteArray()
			exchange.sendResponseHeaders(200, body.size.toLong())
			exchange.responseBody.use { it.write(body) }
		}
		server.start()
		try {
			val identity = AnnouncementIdentity("http://127.0.0.1:${server.address.port}/jellyfin", "abcd", "test-token")
			AnnouncementClient().fetch(identity).map { it.id } shouldBe listOf("1")
			requests.size shouldBe 3
			requests.all { it.second == "test-token" } shouldBe true
		} finally {
			server.stop(0)
		}
	}
	test("missing plugin is harmless and redirects are not followed") {
		var status = 404
		var redirected = false
		val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
		server.createContext("/Plugins/Announcements") { exchange ->
			exchange.responseHeaders.add("Location", "/redirected")
			exchange.sendResponseHeaders(status, -1)
			exchange.close()
		}
		server.createContext("/redirected") { exchange ->
			redirected = true
			exchange.sendResponseHeaders(200, -1)
			exchange.close()
		}
		server.start()
		try {
			val identity = AnnouncementIdentity("http://127.0.0.1:${server.address.port}", "abcd", "test-token")
			val client = AnnouncementClient()
			client.fetch(identity) shouldBe emptyList()
			status = 302
			shouldThrow<IOException> { client.fetch(identity) }
			redirected shouldBe false
			status = 401
			shouldThrow<IOException> { client.fetch(identity) }
		} finally {
			server.stop(0)
		}
	}
})
