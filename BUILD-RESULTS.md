# Build and validation results

Date: 12 October 2026. Target: Jellyfin Android TV 0.19.10, Announcements 0.2.0.7, user's Jellyfin server 12.2.

## Result

**Implementation complete for initial device testing; APK packaging blocked locally. No installable APK is included.** Do not treat this as a tested production client.

## Passed

- The actual `Announcement.kt` and `AnnouncementClient.kt` production files compiled in a separate JVM Gradle harness, with Kotlin 2.2.21, coroutines 1.10.2, serialization 1.9.0 and OkHttp 4.12.0.
- All **10 tests** passed (zero failures/errors): seven model tests and three HTTP tests. JUnit XML reports are included in the source archive under `announcements-validation/`.
- Model tests cover TV include/exclude targeting, explicit-user/role precedence, excluded users, audience restrictions, accessible libraries, unknown audiences, missing policy, scheduling, archive/disable, content revision dismissal keys, and server/user storage isolation.
- HTTP tests use a local HTTP server and check token headers on all requests, reverse-proxy base paths, user filtering, library lookup, missing-plugin 404, rejected 302 redirects, 401 errors, and cancellation of a pending request.
- All three production announcement Kotlin files, including the native presenter, compiled successfully in an isolated Android library harness against Android API 36, Fragment 1.8.9, Lifecycle 2.9.4 and Jellyfin API 1.7.1. The harness extracted the unchanged `Session`, `SessionRepositoryState` and `SessionRepository` declarations from the actual 0.19.10 source; it did not include the repository implementation. It generated minimal resource IDs from the actual new strings and `content_view` ID. This verifies types/imports in the new classes, not full-app integration.
- Git whitespace/error checks passed. The patch and source archive were checked against the original v0.19.10 source.

## Blocked / not verified

- The full `:app:assembleDebug :app:testDebugUnitTest` invocation stops at `:app:processDebugResources`. The complete app unit-test task never ran.
- Windows AAPT2 crashes during resource linking with access-violation exit code `-1073741819` (`0xC0000005`). The same crash occurs for a tiny standalone manifest with no framework resources or application code. Tested the Gradle-bundled AAPT2 8.11.1/12782657 and SDK Build Tools 35.0.0 and 36.0.0. Shorter source, cache and executable paths did not resolve it.
- WSL is not installed. No remote CI build has been dispatched, and no changes have been pushed to GitHub.
- No physical TV/emulator UI test, server 12.2 integration test, playback regression test, APK signature verification or installation test has been completed. The native banner's remote focus, long-message scrolling and playback interactions still require device testing.

## Finish the APK build

Use the included manual GitHub Actions workflow **Announcements test APK** on an authorised fork, or run the documented Gradle command on a working Android build host. The workflow runs the full app unit tests and only uploads the APK/report artifact after that step succeeds.

To continue here, provide a writable fork URL and explicit permission to push a test branch and run the workflow, or access to another Android build environment. Uploading an existing official APK alone would not fix this packaging failure or supply Jellyfin's signing key.

The source archive and patch contain no credentials, signing keys or downloaded toolchains. Keep the original Jellyfin installation while testing the separate `org.jellyfin.androidtv.announcements` package. See INSTALL-AND-TEST.md for behaviour, signing constraints and the acceptance checklist.
