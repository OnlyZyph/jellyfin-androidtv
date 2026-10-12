# Jellyfin TV Announcements — test build

**Delivery status: source and patch supplied; no installable APK was produced locally.** Windows AAPT2 crashes with exit code `0xC0000005` during resource linking, including for a separate minimal app. The steps below apply after a successful build. See BUILD-RESULTS.md for completed checks and remaining validation.

This modification is based on Jellyfin Android TV **v0.19.10**, commit `984181a3d6ab14e9a6d2dcc850c582e1c138bd95`. It implements the API and targeting rules inspected in blcksnake/jellyfin-admin-announcements-plugin **0.2.0.7**, commit `5ba5a8766ffb61a75793333f2461fe276f55275d`. Intended test server: your Jellyfin **12.2** installation.

## Install alongside your existing app

The test application is named **Jellyfin TV Announcements (Test)**, package `org.jellyfin.androidtv.announcements`. It has separate login and settings storage. Keep your existing Jellyfin installation; there is no need to uninstall it.

1. After building, copy the generated APK to the TV using your usual file transfer method.
2. Allow that file manager to install unknown apps when Android requests it, then open the APK and install.
3. Open **Jellyfin TV Announcements (Test)** and sign in to your server.
4. Create a short enabled announcement in the plugin, with a current start time, audience All or Authenticated, and device targeting TV (or no device restriction).
5. Stay in the app. Fetching starts on resume and repeats every 60 seconds. The first message should appear after the requests complete, normally within a few seconds.

Alternatively, after pairing/authorising Android debugging on your TV:

```text
adb install -r jellyfin-androidtv-v0.19.10-debug.apk
```

For Wi-Fi debugging, use the pairing and connection address/ports shown by your device; they vary by TV and Android version. USB or network installation instructions: https://developer.android.com/tools/adb

## How it behaves

- A native banner appears at the top of the main TV activity, including its in-app playback screens. New banners do not request focus or pause playback.
- Use the directional remote to focus the banner and Select/OK to open its full, scrollable text. **Close** or Back closes the popup without dismissing the announcement. **Dismiss announcement** is offered only when the plugin permits dismissal.
- Banners rotate in priority order for about 12 seconds each, separated by a 3-second gap. A focused banner stays available until focus moves away. Messages can recur until dismissed or deactivated. Temporarily hiding a banner is not recorded as dismissal.
- Permanent dismissals are local to this installation, server and user. Content edits create a new revision and can show again. Session dismissals last for the current app process/authentication-token session; restarting the process or changing accounts clears them. Merely backgrounding the app is not a new session.
- API requests use the signed-in user's token in the `X-Emby-Token` header, preserve a server base path such as `/jellyfin`, retain normal TLS certificate verification, and refuse HTTP redirects.
- The client filters audience, TV device, user IDs, roles, accessible libraries, schedules, archive and enabled state. Explicit user inclusion overrides role lists, but does not override the audience preset, device/library rules or explicit user exclusion; this matches the inspected plugin JavaScript.
- Unknown audiences and missing user policy fail closed. Profile/library request failures suppress announcements until the next successful poll. A missing plugin returns no messages. Network calls have timeouts and bounded response sizes.
- Logout, account changes, and backgrounding clear the banner and popup. Responses from obsolete sessions are discarded. Expired or removed announcements disappear as the client rechecks time and refreshes.

## TV acceptance checklist

These require your server and physical remote; a successful build alone cannot verify them.

1. **Basic display:** test Info, Warning and Critical messages, multiple messages and a long message; verify readable text, visible focus, directional navigation, popup scrolling, Close and Back.
2. **Playback regression:** start/stop a movie; pause, seek, change subtitles/audio, test Live TV and resume playback. Confirm that a newly arriving banner does not steal focus, pause playback, or stop remote controls. Verify popup navigation during playback on your TV.
3. **Targeting:** test TV-only and mobile-only messages, admin-only against an ordinary account, one specifically included user, one excluded user, and any library restrictions you use. Switch accounts while announcements are visible and confirm no previous user's message remains.
4. **Dismissal:** dismiss permanent/session messages, background and resume, restart the app, then edit the message. Test a non-dismissible announcement: the popup can close but no Dismiss action is available.
5. **Scheduling/removal:** future messages must stay hidden; expired, disabled, archived or deleted messages must disappear (server changes can take up to the polling interval).
6. **Failures:** stop/restart the server, disable the plugin, and test your reverse-proxy URL. Confirm playback/navigation remain usable, no crash occurs, and announcements recover after connectivity returns. A redirecting URL must be replaced with the server's final canonical URL.
7. **Lifecycle:** press Home, let the screensaver run, return to the app, sign out/in and switch servers. Check for duplicate or lingering banners/popups.

If something fails, record TV model/Android version, steps, message settings and whether it happens in browsing or playback. Avoid sharing access tokens or passwords. A screenshot of the banner or popup is useful.

## Limits and signing

This is a debug-signed experimental client, not an official Jellyfin release. Android requires matching signing certificates for an in-place update. We do not have Jellyfin's private signing key, so this APK cannot replace the official app as an update. Its separate package ID allows side-by-side installation instead. Future updates to this test package must use the same test signing key; a different key requires uninstalling only the test app, which clears its local settings and dismissals. Debug builds are not suitable for app-store publication. See https://developer.android.com/studio/publish/app-signing

No server password, API key or server address is embedded in the APK. You sign in normally. Existing upstream app behaviour and server compatibility have not been redesigned; any original 0.19.10 limitations with Jellyfin 12.2 remain possible.

This implementation polls rather than subscribing to push notifications. It displays only inside the main app activity, not over other apps, external players, login or separate settings activities. It renders message text literally; it does not execute HTML/JavaScript or open message links. Plugin view/dismiss/impression analytics are not submitted, and dismissals do not synchronise with web/mobile clients. Kid-role classification follows the plugin's explicit `IsKid`/`IsKids` fields rather than inferring it from parental ratings.

## Build from source

The source archive includes the original project, modifications, unit tests, upstream licence, and this guide. The patch applies to the exact v0.19.10 base above.

Install JDK 21 and Android SDK platform 36 with Build Tools 35.0.0 (the Android Gradle Plugin's default) and accept Google's SDK licence. Configure `JAVA_HOME` and `ANDROID_HOME`, or the SDK path in `local.properties`. Then run from the source root:

```text
gradlew.bat -Pjellyfin.version=0.19.10 :app:assembleDebug :app:testDebugUnitTest --no-daemon
```

On Linux/macOS use `./gradlew`. APKs are generated under `app/build/outputs/apk/debug`. Use the actual APK filename emitted by Gradle if it differs from the installation example above. Always supply the version property shown above: upstream otherwise defaults to a development version. Shorter source/cache paths did not resolve the local Windows compiler crash.

For a GitHub fork containing these changes, the included **Announcements test APK** workflow runs when the `announcements-tv-0.19.10` branch is pushed and also supports manual runs. It builds on Ubuntu with JDK 21, runs the full app unit-test task, and uploads APK/test reports as the `jellyfin-tv-announcements-test` artifact. This workflow has not been run remotely. Each fresh runner creates a new debug signing key, so APKs from separate runs generally cannot update one another: uninstall only the test package, or configure a persistent private test keystore in your fork for repeat updates.

The test package/name changes apply only to the debug build type. Do not distribute an unsigned release build or expect it to update the official app. Keep the same local debug keystore for subsequent test updates; do not use it as a production signing key.

Source references:

- https://github.com/jellyfin/jellyfin-androidtv/tree/v0.19.10
- https://github.com/blcksnake/jellyfin-admin-announcements-plugin/blob/5ba5a8766ffb61a75793333f2461fe276f55275d/Api/AnnouncementController.cs
- https://github.com/blcksnake/jellyfin-admin-announcements-plugin/blob/5ba5a8766ffb61a75793333f2461fe276f55275d/Web/announcement.js
