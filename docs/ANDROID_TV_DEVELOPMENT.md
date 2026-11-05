# Android TV development handover

## Base and compatibility

Upstream: https://github.com/jellyfin/jellyfin-androidtv . Working runtime source baseline: 3867cfa48591fae2f1e084972de6400a165ab3fe, the existing SSH checkout snapshot. The fork retains the current upstream translation and CI metadata; runtime and dependency files are pinned to the working snapshot plus the documented Wallaby changes. The working installed later APK is `/home/dave/Documents/Codex/2026-10-13/in/outputs/Jellyfin-TV-SSH-Autologin-Fix.apk` (SHA256 34c6a3608aa8025c50fa263c508faf945436c15cc0a0370292b89042fe7fab78). Do not replace this tested base with upstream master during this task. Future upstream merges remain manual.

Minimum Android API 23; target tv-ferodale is Android 9/API28. Existing release configuration builds ARMv7. Release application ID remains org.jellyfin.androidtv.ssh. Both build labels now read Wallaby Popcorn. Use the original signing certificate (SHA256 1d004a2b91dc2a4e4b6a2f52ae27d35281ae3bb20e8e95b4d9efb05417363077); do not generate a new key or clear data. Signing credentials/keystores and local.properties are not published.

The old installed build used versionName 0.0.0-dev.1/versionCode1; the startup-crash repair uses 0.0.0-dev.3/versionCode3 (replacing the tested installation of 0.0.0-dev.2/versionCode2), preserving the upstream version formatter. This fork remains Development, not Stable.

## Native implementation

Movies and TV collection destinations route to WallabyLibraryFragment, with the upstream native details and playback destinations retained. Music, Live TV, seasons and other specialized native destinations retain their existing functionality. The old optional Movies/TV smart-screen preference no longer diverts the user around the unified library view selector.

Standard, Grid and List use the same RecyclerView adapter, complete result count and LibraryWindow cache: 50-item internal pages, at most eight retained summary pages (400 summaries), at most three page requests concurrently, and one selected full item. Counts/batch sizes are not user controls. No view truncates the result at 100. Four Kotest regressions cover beyond-100 access, bounded retention, selection protection and filter reset. Requests are canceled on filter changes or leaving the screen; selected index and view are restored. RecyclerView recycles off-screen views. Original library sorting and favorite/unwatched preference stores are reused, with a title-initial filter. No whole-library prefetch is needed for browsing.

Standard pane frames are fixed. Only the title list or inner details content scrolls. Focus and click are distinct: focus selects and changes the preview, OK enters the selected item. D-pad left/right connects the title area, toolbar and details action. Back navigates from details to the same library entry, then Home through the native back stack. No PC Escape/fullscreen hacks are injected.

BackgroundService applies a one-second focus debounce in both directions and loads a bounded 1920x1080 backdrop before changing the displayed image. A failure keeps previous artwork. Collection folders use the bundled cinema background. New-player entry disables background drawing and cancels pending artwork jobs; the legacy playback overlay already disables backgrounds. No wallpaper is mounted in front of the video surface.

Media details use the selected item's actual video/audio/subtitle streams, concise resolution/codec, audio channels/bitrate/sample rate, and language badges. Original language is deliberately shown as unavailable because this pinned Jellyfin SDK does not expose an original-language metadata field. It is never inferred from file audio or the preferred metadata UI language. This can be extended when the server/SDK supplies it.

Artwork is reused from existing Wallaby Popcorn mobile development and server web assets. Legacy density banner resources remain unused; the manifest explicitly selects the new Wallaby drawable banner. No new artwork or alternate mascot was generated.

## Networking preservation

The ssh directory and prepared authentication/startup repairs are copied unchanged from the existing TV source. This includes AAAA recovery, IPv6-first transport/fallback, public-key enrolment, host-key pinning, retry login and defensive handling of SSH failures. BackgroundService retains its SSH currentEndpoint routing. No SSH policy, listener, firewall or server setting is changed; the broader server networking change remains deferred.

## Startup crash repair, development version 3

All three tv-ferodale startup attempts with version 2 failed with `IllegalArgumentException: Only VectorDrawables and rasterized asset types are supported`. The release mapping ID matches the device stack, and the mapped caller is `SplashFragment.kt:48`, loading `R.drawable.app_logo` with Compose `painterResource`. Both base and API24+ logo resources were XML `<bitmap>` wrappers, which that loader attempts to parse as vectors and rejects.

The shared `app_logo` now resolves through a values resource alias directly to the existing `wallaby_mark.png`. The incompatible base and API24 wrappers are removed. Splash, toolbar and screensaver callers all retain the same logo resource ID, with no artwork or networking changes. `python3 scripts/test_brand_drawables.py -v` checks all qualified branded drawable variants without invoking Gradle. Both checks pass; the same guard rejects the old API24 wrapper.

## Prebuild boundary

Version 2 was built and installed; all 19 focused library/SSH unit tests passed, but the real TV startup exposed the resource crash above. Version 3 contains the source repair and has not been built or installed. Stop before Gradle until the user reboots and explicitly authorizes the next build. Actual startup, remote navigation and playback still require TV validation.

After authorization, use Java21 from `outputs/tv-build-deps/java-home.txt`, the existing Android SDK, a writable `java.io.tmpdir`, and one Gradle worker, no concurrent architecture builds. The prepared gradle.properties sets a 2GB Gradle heap, one worker and in-process Kotlin compilation. Build only the release ARMv7 APK first. A typical command is `./gradlew --no-daemon --max-workers=1 :app:assembleRelease`; do not execute it before authorization. Verify versionCode, minSdk, ABI, signature and matching source before sideloading. Do not install onto tv-jean or tv-granny as part of this task.

## Device validation after build

1. Update tv-ferodale with matching certificate and preserved data; verify SSH connection/autologin and connection-error recovery.
2. Check Standard, Grid and List; choose each, reopen library, restart/login, verify saved view and index.
3. Traverse past title100 and to the final entry. Change sort, favorites, unwatched and title-initial filters; verify queries, no visible page count or memory-batch selector, no repeated loading flashes.
4. Remote-only navigation: toolbar/dialogs, title rows, details action, enter details, Back to same entry, Back Home. Fast repeated D-pad actions and return from playback must preserve focus.
5. Check delayed fades for movie-to-movie and movie-to-library selections, bad artwork URLs and slow SSH links.
6. Play video using both configured native player paths if available. Confirm actual video is visible with no artwork overlay, audio/subtitles still work, and Android Back stops/returns through the established player behavior.

Publish or promote Stable only after the user approves actual TV testing.

For signing, explicitly set `KEYSTORE_FILE=/home/dave/Documents/Codex/android-user/debug.keystore` with the existing androiddebugkey credentials. The standard `~/.android/debug.keystore` is a different key and must not be used for this TV update. Preserve both keystores. The current environment's unit-test task is `:app:testDebugUnitTest`, not `:app:testReleaseUnitTest`; the MockK test requires its existing Byte Buddy agent to be supplied explicitly in this restricted environment. See `outputs/wallaby-popcorn-androidtv-build-complete.md` for the previous build environment.

## Development version 4: libraries and resume

Version 3 successfully starts and plays videos, but full libraries fail. Device request logs show the custom `/Items?limit=50` calls running on Main. Inspection of the pinned SDK's OkHttpClient confirms that after its async header callback it resumes the caller and performs `ResponseBody.bytes()` there. Larger library bodies can therefore trigger Android's main-thread network prohibition. Both custom library page and rich-item calls now enclose request plus content decoding in `withContext(Dispatchers.IO)`, matching the working native callers. No server, tunnel, paging size or memory-limit changes are required.

Page jobs are registered lazily before starting, so a synchronous failure cannot leave a completed job in the in-flight map and block Retry. Cancellations remain distinct. Failures now retain their diagnostic stack in Timber, and the UI distinguishes expired sign-in, permissions, missing libraries, server HTTP failures, timeouts, invalid data, genuine SSH outages and internal errors.

The observed process remained alive across the reported resume incidents, with no new fatal crash in the captured current-process crash buffer. Startup was repeatedly resetting navigation while an existing singleTask MainActivity could be stopped. Navigation now waits for RESUMED, with uniquely identified events acknowledged once per host. Acknowledgment is saved with fragment history, preventing replay of Back/reset on resubscription or restoration. Nullable fragment state is restored without a forced non-null assertion, and saving a temporarily empty host is safe. This is a source-level repair for the identified restore risks; actual repeated Home/relaunch testing remains required.

Next version is 0.0.0-dev.4/code4. Source syntax checks passed for eight changed/new Kotlin files; the two non-Gradle branded-resource checks still pass. Four NavigationDeliveryTest cases and three LibraryLoadFailureTest cases are prepared, not compiled/run. After authorized build run these together with LibraryWindowTest and SSH tests. No Gradle task or APK build/install was run for development4. Keep the current signing key and Android9/ARMv7 settings. Source changes remain local on Development pending commit/publication.

## Development version 5: Standard View remote navigation and series file metadata

Standard View now handles D-pad events at the fragment level through MainActivity's existing key forwarding. Up/Down are consumed everywhere on the library screen, advancing/clamping the selected index only when focus is inside the title list; they cannot escape to another control at the first/last title. Left/Right cycle the visible toolbar controls, optional Retry, the title area and the details button. Releases are consumed too. Selection dialogs retain their own normal navigation. Grid/List navigation remains native.

Selected titles are aligned toward the visible screen's vertical center using the list's screen location and a bounded scroll offset. Near either library end, scrolling is clamped to the content edges, with no artificial blank rows. The fixed row extent includes margins. New scroll regression cases cover top, middle, bottom, short lists, the toolbar offset and entries beyond100. Pending row focus is restored after layout when a target row was off screen.

The video badge replaces the square text glyph with a gold Android vector monitor icon, matching the monitor concept of the web interface's Tv icon. The audio badge styling is unchanged.

For SERIES, the selected-item task fetches one available episode under that series in ascending season/episode order (SortName only breaks ties), with MEDIA_SOURCES and MEDIA_STREAMS. This includes season0 if it is the earliest available season. No whole-series fetch is performed. Only technical video/audio/subtitle information uses the sampled episode; the series name, synopsis, art and details destination remain the series. A small First episode label identifies the sample. One selected episode DTO is retained; changing selection cancels the task and clears its sample, and a stale result cannot overwrite another series. Requests and decoding remain on Dispatchers.IO. Reopening the fragment retries details after any earlier cancellation.

Next version0.0.0-dev.5/code5. No Gradle/build/install has been run for version5. Kotlin syntax parsing and resource XML checks passed; the two existing branded-resource checks pass. Eight new Kotlin test cases (StandardTitleScrollTest and FirstEpisodeRequestTest) are prepared but not compiled or run. After reboot/authorization run the release build and all focused wallaby/navigation/SSH tests (expected34 Kotlin cases).

Device checks required: hold Up at the first title and Down at the final title; verify no focus escape. Verify Up/Down do nothing on toolbar/details controls; Left/Right reaches each control in both directions. Scroll down/up through center and both content edges, plus a short filtered list. Confirm grid/list remain usable and dialogs still accept D-pad selection. Compare series badges against the first available episode's native details, then quickly change between series to test cancellation. Test series with no playable episodes gracefully. Confirm details/Back, resume and playback still work.

## Development version 6: IPv6-preferred SSH with IPv4 fallback, IPv6 loopback tunnel

User explicitly reversed the IPv6-only external transport requirement for Android TV, and limited this work to the Android TV client. No server, other platform or firewall files were changed or server connections attempted.

The existing socket factory already sorted TCP addresses IPv6 first and fell back on failed socket opens. New connectSshTransport extends this policy to the full SSH session/negotiation: fresh sessions attempt resolved addresses in IPv6-first order, disconnect failed sessions and try IPv4 on transport/handshake errors. A verified usable IPv6 session wins without trying IPv4. Host identity and credential rejection remain fatal; pinned host keys, strict validation, authentication choices, private-key encryption and enrollment identities are preserved. Host-key inspection also tries IPv4 when IPv6 cannot complete its probe, but does not ignore a mismatched known server key. Use a hostname with both DNS records to enable both transports; an IPv6 literal has no inferred IPv4 alternative.

Both local and remote forwarding endpoints are now explicitly ::1. The local endpoint is http://[::1]:<ephemeral-port>, forwarded to server [::1]:8096 through either external SSH family. JSch binds the ephemeral port directly and returns it, avoiding the old separate port probe/race. There is no IPv4-loopback fallback and the listener remains loopback-only. Tunnel errors now identify the server IPv6 listener/forwarding requirement. Prior screenshots/version5 used IPv4 loopback; those are historical, not the development6 policy. The preserved-original SSH hash manifest documents the initial base; development6 intentionally modifies the transport/forwarding implementation.

Deferred SERVER requirements (documentation only, not implemented or checked live): IPv4 SSH must be reachable through server listeners, applicable firewall/NAT and a current DNS A record. Do not remove IPv6 listeners. Jellyfin must accept IPv6 loopback on ::1:8096, and sshd/key restrictions must allow that exact forwarding destination. Preserve existing destinations for older clients until separately authorized. Port8096 does not need public exposure. No server-side scripts were modified during this work.

Version0.0.0-dev.6/code6. Eleven SshTransportFallbackTest cases prepared: IPv6 preference, no-route/timeout/SSH-negotiation fallback, IPv4-only hosts, IPv6 literals, empty results, all-address failure, fatal identity/authentication failures and IPv6 local endpoint formatting. Syntax parsing passed for13 SSH source/test files; two non-Gradle brand checks passed; diff whitespace clean. No Gradle task, test compilation or APK build/install has been run for version6. After reboot and authorization, compile and run the focused suite (expected45 Kotlin cases), verify original certificate and Android9/ARMv7 compatibility. Test against an explicitly prepared dual-stack SSH server, both IPv6-capable and IPv4-only access networks, IPv6 remote forwarding/permissions, media playback and unchanged remote navigation.
