package org.jellyfin.androidtv.ui.announcements

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant

class AnnouncementTests : FunSpec({
	val user = Json.parseToJsonElement("""{"Id":"AB-CD","Policy":{"IsAdministrator":false}}""") as JsonObject
	fun notice(fields: String = "") = Announcement(Json.parseToJsonElement(
		"""{"id":"1","title":"Notice","message":"Hello"$fields}"""
	) as JsonObject)

	test("TV include and exclude filters are respected") {
		notice(""", "includeDeviceTypes":["tv"]""").matches(user, emptySet()) shouldBe true
		notice(""", "includeDeviceTypes":["mobile"]""").matches(user, emptySet()) shouldBe false
		notice(""", "excludeDeviceTypes":["tv"]""").matches(user, emptySet()) shouldBe false
	}
	test("explicit users override role filters but not exclusion or audience") {
		notice(""", "includeUserIds":["abcd"], "excludeUserRoles":["user"]""").matches(user, emptySet()) shouldBe true
		notice(""", "includeUserIds":["abcd"], "excludeUserIds":["AB-CD"]""").matches(user, emptySet()) shouldBe false
		notice(""", "includeUserIds":["abcd"], "audience":"admins"""").matches(user, emptySet()) shouldBe false
	}
	test("library targeting uses accessible library IDs") {
		notice(""", "includeLibraryIds":["12-34"]""").matches(user, setOf("1234")) shouldBe true
		notice(""", "includeLibraryIds":["1234"]""").matches(user, emptySet()) shouldBe false
		notice(""", "excludeLibraryIds":["1234"]""").matches(user, setOf("1234")) shouldBe false
	}
	test("unknown audience and missing user policy fail closed") {
		notice(""", "audience":"future-segment"""").matches(user, emptySet()) shouldBe false
		notice().matches(Json.parseToJsonElement("""{"Id":"abcd"}""") as JsonObject, emptySet()) shouldBe false
	}
	test("schedule, disable and archive prevent display") {
		val now = Instant.parse("2026-10-12T12:00:00Z")
		notice(""", "startsAt":"2026-10-13T00:00:00Z"""").active(now) shouldBe false
		notice(""", "endsAt":"2026-10-11T00:00:00Z"""").active(now) shouldBe false
		notice(""", "endsAt":null""").active(now) shouldBe true
		notice(""", "startsAt":"broken"""").active(now) shouldBe false
		notice(""", "isEnabled":false""").active(now) shouldBe false
		notice(""", "isArchived":true""").active(now) shouldBe false
	}
	test("analytics changes preserve dismissal while content edits reset it") {
		(notice().revision == notice(""", "viewCount":42""").revision) shouldBe true
		(notice().revision == notice(""", "level":"danger"""").revision) shouldBe false
	}
	test("dismissals are isolated by server and user") {
		val a = AnnouncementIdentity("https://server/jellyfin", "AB-CD", "secret")
		(a.storageKey == a.copy(userId = "abcd").storageKey) shouldBe true
		(a.storageKey == a.copy(userId = "other").storageKey) shouldBe false
		(a.storageKey == a.copy(baseUrl = "https://other").storageKey) shouldBe false
	}
})
