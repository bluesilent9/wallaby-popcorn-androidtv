# Android TV development handover

## Base and compatibility

Upstream: https://github.com/jellyfin/jellyfin-androidtv . Working runtime source baseline: 3867cfa48591fae2f1e084972de6400a165ab3fe, the existing SSH checkout snapshot. The fork retains the current upstream translation and CI metadata; runtime and dependency files are pinned to the working snapshot plus the documented Wallaby changes. The working installed later APK is `/home/dave/Documents/Codex/2026-10-13/in/outputs/Jellyfin-TV-SSH-Autologin-Fix.apk` (SHA256 34c6a3608aa8025c50fa263c508faf945436c15cc0a0370292b89042fe7fab78). Do not replace this tested base with upstream master during this task. Future upstream merges remain manual.

Minimum Android API 23; target tv-ferodale is Android 9/API28. Existing release configuration builds ARMv7. Release application ID remains org.jellyfin.androidtv.ssh. Both build labels now read Wallaby Popcorn. Use the original signing certificate (SHA256 1d004a2b91dc2a4e4b6a2f52ae27d35281ae3bb20e8e95b4d9efb05417363077); do not generate a new key or clear data. Signing credentials/keystores and local.properties are not published.

The old installed build used versionName 0.0.0-dev.1/versionCode1; the next uses 0.0.0-dev.2/versionCode2, preserving the upstream version formatter. This fork remains Development, not Stable.

## Native implementation

Movies and TV collection destinations route to WallabyLibraryFragment, with the upstream native details and playback destinations retained. Music, Live TV, seasons and other specialized native destinations retain their existing functionality. The old optional Movies/TV smart-screen preference no longer diverts the user around the unified library view selector.

Standard, Grid and List use the same RecyclerView adapter, complete result count and LibraryWindow cache: 50-item internal pages, at most eight retained summary pages (400 summaries), at most three page requests concurrently, and one selected full item. Counts/batch sizes are not user controls. No view truncates the result at 100. Four pending Kotest regressions cover beyond-100 access, bounded retention, selection protection and filter reset. Requests are canceled on filter changes or leaving the screen; selected index and view are restored. RecyclerView recycles off-screen views. Original library sorting and favorite/unwatched preference stores are reused, with a title-initial filter. No whole-library prefetch is needed for browsing.

Standard pane frames are fixed. Only the title list or inner details content scrolls. Focus and click are distinct: focus selects and changes the preview, OK enters the selected item. D-pad left/right connects the title area, toolbar and details action. Back navigates from details to the same library entry, then Home through the native back stack. No PC Escape/fullscreen hacks are injected.

BackgroundService applies a one-second focus debounce in both directions and loads a bounded 1920x1080 backdrop before changing the displayed image. A failure keeps previous artwork. Collection folders use the bundled cinema background. New-player entry disables background drawing and cancels pending artwork jobs; the legacy playback overlay already disables backgrounds. No wallpaper is mounted in front of the video surface.

Media details use the selected item's actual video/audio/subtitle streams, concise resolution/codec, audio channels/bitrate/sample rate, and language badges. Original language is deliberately shown as unavailable because this pinned Jellyfin SDK does not expose an original-language metadata field. It is never inferred from file audio or the preferred metadata UI language. This can be extended when the server/SDK supplies it.

Artwork is reused from existing Wallaby Popcorn mobile development and server web assets. Legacy density banner resources remain unused; the manifest explicitly selects the new Wallaby drawable banner. No new artwork or alternate mascot was generated.

## Networking preservation

The ssh directory and prepared authentication/startup repairs are copied unchanged from the existing TV source. This includes AAAA recovery, IPv6-first transport/fallback, public-key enrolment, host-key pinning, retry login and defensive handling of SSH failures. BackgroundService retains its SSH currentEndpoint routing. No SSH policy, listener, firewall or server setting is changed; the broader server networking change remains deferred.

## Prebuild boundary

No Gradle task, APK build, compiled test, emulator or device installation has been run for this preparation. Static Kotlin parsing, XML parsing, SDK signature inspection and source hash comparison do not establish runtime correctness. Compile/type-check, pending unit tests, resource validation and native D-pad/playback checks remain required after reboot and explicit authorization.

After authorization, use the original JDK/Android SDK/signing environment and one Gradle worker, no concurrent architecture builds. The prepared gradle.properties sets a 2GB Gradle heap, one worker and in-process Kotlin compilation. Build only the release ARMv7 APK first. A typical command is `./gradlew --no-daemon --max-workers=1 :app:assembleRelease`; do not execute it before authorization. Verify versionCode, minSdk, ABI, signature and matching source before sideloading. Do not install onto tv-jean or tv-granny as part of this task.

## Device validation after build

1. Update tv-ferodale with matching certificate and preserved data; verify SSH connection/autologin and connection-error recovery.
2. Check Standard, Grid and List; choose each, reopen library, restart/login, verify saved view and index.
3. Traverse past title100 and to the final entry. Change sort, favorites, unwatched and title-initial filters; verify queries, no visible page count or memory-batch selector, no repeated loading flashes.
4. Remote-only navigation: toolbar/dialogs, title rows, details action, enter details, Back to same entry, Back Home. Fast repeated D-pad actions and return from playback must preserve focus.
5. Check delayed fades for movie-to-movie and movie-to-library selections, bad artwork URLs and slow SSH links.
6. Play video using both configured native player paths if available. Confirm actual video is visible with no artwork overlay, audio/subtitles still work, and Android Back stops/returns through the established player behavior.

Publish or promote Stable only after the user approves actual TV testing.
