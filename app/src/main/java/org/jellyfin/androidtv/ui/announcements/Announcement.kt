package org.jellyfin.androidtv.ui.announcements

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.security.MessageDigest
import java.time.Instant

internal fun JsonObject.field(name: String) = entries.firstOrNull { it.key.equals(name, true) }?.value
internal fun JsonObject.string(name: String, default: String = "") = (field(name) as? JsonPrimitive)?.content ?: default
internal fun JsonObject.flag(name: String, default: Boolean = false) = (field(name) as? JsonPrimitive)?.booleanOrNull ?: default
internal fun JsonObject.list(name: String) = (field(name) as? JsonArray).orEmpty()
	.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.lowercase() }.filter { it.isNotEmpty() }
internal fun normalizedId(value: String) = value.trim().replace("-", "").lowercase()
internal fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
	.joinToString("") { "%02x".format(it) }

internal data class Announcement(val data: JsonObject) {
	val id = data.string("id")
	val title = data.string("title").take(200)
	val message = data.string("message").take(5000)
	val level = data.string("level", "info").lowercase()
	val priority = data.string("priority", "1").toIntOrNull() ?: 1
	val allowDismiss = data.flag("allowDismiss", true)
	val permanent = data.string("dismissMode", "permanent").equals("permanent", true)
	// Exclude analytics counters: a view must not invalidate a dismissal.
	val revision = digest(listOf("id", "title", "message", "level", "startsAt", "endsAt", "allowDismiss", "dismissMode", "priority")
		.joinToString("\u0000") { data.string(it) })

	fun active(now: Instant = Instant.now()): Boolean {
		if (id.isBlank() || message.isBlank() || !data.flag("isEnabled", true) || data.flag("isArchived")) return false
		return try {
			val start = data.string("startsAt")
			val end = data.string("endsAt")
			(start.isBlank() || !now.isBefore(Instant.parse(start))) &&
				(end.isBlank() || end == "null" || !now.isAfter(Instant.parse(end)))
		} catch (_: java.time.format.DateTimeParseException) {
			false
		}
	}

	fun matches(user: JsonObject, libraries: Set<String>): Boolean {
		val userId = normalizedId(user.string("id"))
		if (userId.isBlank()) return false
		val policy = user.field("policy") as? JsonObject ?: return false
		val admin = policy.flag("isAdministrator")
		val kid = policy.flag("isKid") || policy.flag("isKids") || user.flag("isKid")
		val roles = setOfNotNull("user", "admin".takeIf { admin }, "kid".takeIf { kid })
		val includes = data.list("includeUserIds").map(::normalizedId)
		if (userId in data.list("excludeUserIds").map(::normalizedId)) return false
		val explicit = userId in includes
		if (includes.isNotEmpty() && !explicit) return false
		val audienceMatches = when (data.string("audience", "all").lowercase()) {
			"all", "authenticated" -> true
			"admins" -> admin
			"nonadmins" -> !admin
			"kids" -> kid
			"nonkids" -> !kid
			else -> false
		}
		if (!audienceMatches) return false
		fun permits(include: String, exclude: String, values: Set<String>): Boolean {
			val allowed = data.list(include)
			return (allowed.isEmpty() || allowed.any { it in values }) && data.list(exclude).none { it in values }
		}
		if (!permits("includeDeviceTypes", "excludeDeviceTypes", setOf("tv"))) return false
		if (!explicit && !permits("includeUserRoles", "excludeUserRoles", roles)) return false
		val allowedLibraries = data.list("includeLibraryIds").map(::normalizedId)
		val blockedLibraries = data.list("excludeLibraryIds").map(::normalizedId)
		return (allowedLibraries.isEmpty() || allowedLibraries.any { it in libraries }) && blockedLibraries.none { it in libraries }
	}
}
