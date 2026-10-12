package org.jellyfin.androidtv.ui.announcements

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class AnnouncementIdentity(val baseUrl: String, val userId: String, val token: String) {
	val storageKey get() = digest("${baseUrl.trimEnd('/')}|${normalizedId(userId)}")
}

internal class AnnouncementClient {
	// Never forward a session token to a redirect target. Use the configured server URL,
	// including its reverse-proxy base path, and normal system TLS verification.
	private val http = OkHttpClient.Builder()
		.followRedirects(false).followSslRedirects(false)
		.connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
		.callTimeout(10, TimeUnit.SECONDS).build()

	suspend fun fetch(identity: AnnouncementIdentity): List<Announcement> = withContext(Dispatchers.IO) {
		val payload = get(identity, "Plugins/Announcements", optional = true) ?: return@withContext emptyList()
		val announcements = (Json.parseToJsonElement(payload) as? JsonArray ?: throw IOException("Expected announcement list"))
			.mapNotNull { it as? JsonObject }.map(::Announcement)
		if (announcements.isEmpty()) return@withContext emptyList()
		val user = Json.parseToJsonElement(get(identity, "Users/${identity.userId}")!!) as? JsonObject
			?: throw IOException("Expected user profile")
		if (normalizedId(user.string("id")) != normalizedId(identity.userId)) throw IOException("Unexpected user profile")
		val needsLibraries = announcements.any { it.data.list("includeLibraryIds").isNotEmpty() || it.data.list("excludeLibraryIds").isNotEmpty() }
		val libraries = if (needsLibraries) {
			val views = Json.parseToJsonElement(get(identity, "Users/${identity.userId}/Views")!!) as? JsonObject
				?: throw IOException("Expected library views")
			val items = views.field("Items") as? JsonArray ?: throw IOException("Expected library items")
			items.mapNotNull { it as? JsonObject }.map {
				normalizedId(it.string("CollectionFolderId").takeUnless { id -> id.isBlank() || id == "null" } ?: it.string("Id"))
			}.toSet()
		} else emptySet()
		announcements.filter { it.active() && it.matches(user, libraries) }.sortedByDescending { it.priority }
	}

	private suspend fun get(identity: AnnouncementIdentity, path: String, optional: Boolean = false): String? {
		val url = (identity.baseUrl.trimEnd('/') + "/").toHttpUrl().newBuilder().addPathSegments(path).build()
		val request = Request.Builder().url(url).header("X-Emby-Token", identity.token)
			.header("Accept", "application/json").build()
		return suspendCancellableCoroutine { continuation ->
			val call = http.newCall(request)
			continuation.invokeOnCancellation { call.cancel() }
			call.enqueue(object : Callback {
				override fun onFailure(call: Call, e: IOException) {
					if (continuation.isActive) continuation.resumeWithException(e)
				}

				override fun onResponse(call: Call, response: Response) {
					try {
						val result = response.use {
							if (optional && response.code == 404) return@use null
							if (!response.isSuccessful) throw IOException("Announcement request failed: ${response.code}")
							val body = response.body ?: throw IOException("Empty response")
							// Bound memory use for chunked responses and incorrectly configured proxies.
							val source = body.source()
							if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("Response too large")
							source.readUtf8()
						}
						if (continuation.isActive) continuation.resume(result)
					} catch (error: Exception) {
						if (continuation.isActive) continuation.resumeWithException(error)
					}
				}
			})
		}
	}

	private companion object {
		const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
	}
}
