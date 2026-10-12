package org.jellyfin.androidtv.ui.announcements

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.sdk.api.client.ApiClient

/** A native, opt-in-to-focus overlay: arriving messages never steal the TV remote. */
class AnnouncementPresenter(
	private val activity: FragmentActivity,
	private val banner: TextView,
	private val sessions: SessionRepository,
	private val api: ApiClient,
) {
	private val client = AnnouncementClient()
	private val preferences = activity.getSharedPreferences("announcements", Context.MODE_PRIVATE)
	private var dialog: AlertDialog? = null
	private var displayed: Announcement? = null

	fun start() {
		activity.lifecycleScope.launch {
			activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				sessions.currentSession.collectLatest { session ->
					clear()
					val baseUrl = api.baseUrl ?: return@collectLatest
					if (session == null || api.accessToken != session.accessToken) return@collectLatest
					val identity = AnnouncementIdentity(baseUrl, session.userId.toString(), session.accessToken)
					val sessionKey = digest(identity.storageKey + identity.token)
					if (sessionKey != dismissalSession) {
						dismissalSession = sessionKey
						sessionDismissals.clear()
					}
					try {
						run(identity)
					} finally {
						clear()
					}
				}
			}
		}
	}

	private suspend fun run(identity: AnnouncementIdentity) = coroutineScope {
		var notices = emptyList<Announcement>()
		launch {
			while (isActive) {
				try {
					val result = client.fetch(identity)
					// Discard responses from a session that changed during network I/O.
					if (api.baseUrl != identity.baseUrl || api.accessToken != identity.token) break
					notices = result
					if (displayed?.let { current -> result.none { it.revision == current.revision } } == true) clear()
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (_: Exception) {
					// No stale targeted messages and no token/URL logging on failures.
					notices = emptyList()
					clear()
				}
				delay(60_000)
			}
		}
		var index = 0
		while (isActive) {
			if (displayed?.active() == false) clear()
			val candidates = notices.filter { it.active() && !dismissed(identity, it) }
			if (candidates.isEmpty() || dialog != null) {
				delay(1_000)
				continue
			}
			val notice = candidates[index.mod(candidates.size)]
			index = (index + 1).mod(candidates.size)
			show(identity, notice)
			// Keep a focused banner in place so it cannot disappear under the remote.
			repeat(12) {
				delay(1_000)
				if (!notice.active()) clear()
			}
			while (banner.hasFocus() && notice.active()) delay(1_000)
			hideBanner()
			delay(3_000)
		}
	}

	private fun dismissed(identity: AnnouncementIdentity, notice: Announcement): Boolean {
		if (!notice.allowDismiss) return false
		return if (notice.permanent) preferences.getStringSet(identity.storageKey, emptySet()).orEmpty().contains(notice.revision)
		else notice.revision in sessionDismissals
	}

	private fun show(identity: AnnouncementIdentity, notice: Announcement) {
		displayed = notice
		banner.text = "${notice.title}\n${notice.message}"
		banner.contentDescription = activity.getString(R.string.announcement_open, notice.title, notice.message)
		banner.setBackgroundColor(when (notice.level) {
			"danger" -> Color.rgb(130, 25, 25)
			"warning" -> Color.rgb(105, 65, 0)
			else -> Color.rgb(20, 48, 75)
		})
		banner.visibility = View.VISIBLE
		banner.setOnClickListener { open(identity, notice) }
	}

	private fun open(identity: AnnouncementIdentity, notice: Announcement) {
		if (!notice.active() || api.accessToken != identity.token || api.baseUrl != identity.baseUrl) return
		val padding = (24 * activity.resources.displayMetrics.density).toInt()
		val message = TextView(activity).apply {
			text = notice.message
			textSize = 20f
			setTextColor(Color.WHITE)
			setPadding(padding, padding, padding, padding)
		}
		val scroll = ScrollView(activity).apply { addView(message) }
		val builder = AlertDialog.Builder(activity).setTitle(notice.title).setView(scroll)
			.setPositiveButton(R.string.announcement_close, null)
		if (notice.allowDismiss) builder.setNegativeButton(R.string.announcement_dismiss) { _, _ ->
			if (notice.permanent) {
				val revisions = preferences.getStringSet(identity.storageKey, emptySet()).orEmpty().toMutableSet()
				revisions.add(notice.revision)
				preferences.edit().putStringSet(identity.storageKey, revisions).apply()
			} else sessionDismissals.add(notice.revision)
			hideBanner()
		}
		dialog = builder.create().apply {
			setOnDismissListener { dialog = null }
			show()
		}
	}

	private fun hideBanner() {
		if (banner.hasFocus()) {
			banner.clearFocus()
			activity.findViewById<View>(R.id.content_view).requestFocus()
		}
		banner.visibility = View.GONE
		banner.setOnClickListener(null)
	}

	private fun clear() {
		dialog?.dismiss()
		dialog = null
		displayed = null
		hideBanner()
	}

	private companion object {
		var dismissalSession: String? = null
		val sessionDismissals = mutableSetOf<String>()
	}
}
