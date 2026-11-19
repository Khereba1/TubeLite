# TubeLite: handoff notes for an AI assistant

Paste this file together with the project zip when asking an AI to change the app.
The family-wide rules are in STANDARD.md (ask the owner to attach it if missing).

## What this is
A private WebView wrapper around m.youtube.com for ONE owner who signs into his own Google account in it.
Features: background playback (foreground service + media notification), ad blocking (network rules + bundled page script),
fullscreen video (swipe gestures, keep-screen-on), Picture-in-Picture (Home while fullscreen), opens youtube links. Android app, Kotlin (View-based, no Compose UI), source package `com.khereba1.app`,
applicationId `com.khereba1.tubelite`. minSdk 29, compileSdk 35. UI languages: Arabic + English (strings only: notification, toasts).
Developer: Khereba1. GitHub repo: Khereba1/TubeLite. The AI that edits this code cannot compile or run it: the owner builds through GitHub Actions and tests on his phone (older versions are known to run there; changes of the latest version are untested until he reports).

## SECURITY RULES (do not break, the owner logs into his account here)
1. NEVER load JavaScript or any code from the internet into the WebView (no CDN, no `@latest`). All page script lives in `assets/inject.js`.
   (Known, accepted exception outside the WebView: the download engine's own yt-dlp update fetches yt-dlp code; it never sees cookies or account data.)
2. NEVER strip or rewrite Content-Security-Policy, and NEVER proxy page requests through app code.
3. NEVER use `addJavascriptInterface`. The only app<->page channel is `WebViewCompat.addWebMessageListener("TubeBridge")`,
   restricted to https://m.youtube.com and https://www.youtube.com. It accepts ONLY short text messages: the player state
   "<1|0>|<videoId>|<posMs>|<durMs>|<title>", "open|<watch url>" (browse page), "browse|<youtube url>" (player page), "dbg|<text>" (player page, log only, truncated) and "dir|ltr|<how>" / "dir|rtl|<how>" (player page; <how> = layout/lang/dir/css/default, logged as "DIR" in the in-app log).
   Every URL is re-validated in Kotlin (https + youtube host) before use, and commands to the page go through COMMAND_REGEX.
4. NEVER expose cookies, tokens or any account data to JS or to any file/log. No `getCookie` helper.
5. Main-frame navigation is limited to youtube.com / google.com subdomains over https (`isAllowedHost` in MainActivity.kt); everything else goes to the external browser or is blocked.
6. Do not add permissions beyond: INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, FOREGROUND_SERVICE_DATA_SYNC (downloads), POST_NOTIFICATIONS.
   No microphone/camera/location (the WebChromeClient does not override onPermissionRequest on purpose).
7. Keep `allowBackup=false`, `usesCleartextTraffic=false`, mixed content NEVER_ALLOW, file access off.
8. Signing key only from GitHub Secrets (see below). Never the public AOSP test key.

## Two pages (browse + player)
`MainActivity` holds two WebViews that share cookies: `browseView` (home, search, channels, sign-in) and a lazily created `playerView`
(the watch page, inside `playerLayer`). `assets/inject.js` knows which one it is from `window.__tubeRole` ("browse"/"player").
- Browse page: a click on a `/watch` link is captured in JS and sent as `open|url`; Kotlin loads it in the player page and slides the player in.
  Fallbacks: `shouldOverrideUrlLoading` and `doUpdateVisitedHistory` in `TubeClient`.
- Player page: back button or the pill at the top minimizes it (`translationY` off screen, NOT visibility GONE, so audio keeps playing);
  a mini bar (thumbnail, title, play/pause, close) shows under the browse page. Tapping it expands the player again.
  Clicks on non-watch YouTube links in the player page are sent as `browse|url` (shown in the browse page, player minimized).
- Only the browse page is paused in `onPause()`. Never pause the player page. Only the player WebView uses the always-visible override.
- Notification/lock screen: `PlaybackService` shows position, duration, thumbnail (downloaded from i.ytimg.com without cookies or redirects),
  play/pause/prev/next/seek. Commands come back as "play|pause|toggle|next|prev|seek:<ms>" to `window.__tubeCmd` in the player page.
- While the player is expanded the browse page is paused (all its `<video>` elements) and set INVISIBLE (after the slide-in), and back to VISIBLE on
  minimize/close. Feed previews in the browse page are KEPT on purpose (the owner likes them); hiding the page is what prevents overlapping videos.
  Do not re-add a `play()` blocker.
- Player controls (built by `buildPlayerLayer()`, drawn over the watch page's own 48dp top bar):
  a dark pill with a wide chevron (`ic_minimize`) centered = minimize; a round 48dp-touch button (`ic_quality`) placed exactly over the site's "more" (three dots)
  button (top right on an LTR page, top left on an RTL/Arabic page: the page reports "dir|rtl|ltr", see `applyDirection`) = SETTINGS menu
  (`showSettingsDialog`): default video quality, default Shorts quality, default playback speed, error log. Saved in SharedPreferences "tubelite_prefs"
  (keys quality, shorts_quality, speed; values validated against fixed lists) and sent to BOTH pages by `prefsScript()` (document-start prefix, onPageFinished,
  and `window.__tubeApplyPrefs()` after a change). Since 1.26 the settings travel in their OWN document-start script per WebView (`registerPrefsScript`, handle kept in
  `prefsHandlers`, removed and added again on every change, forgotten when a page is destroyed); the role + inject.js script is static. Player page: `applyQuality` / `applySpeed` once per video id (a manual change inside YouTube sticks for that video).
  Browse page: Shorts play there, so `applyShorts` (inject.js) applies the Shorts quality and forces 1x. Shorts speed is always 1x (not configurable).
  Picks made inside YouTube's own menu are NOT detected. Unconfirmed on a real device: whether the player API call actually changes the quality.
- Window insets: the activity is ALWAYS edge-to-edge (`setDecorFitsSystemWindows(false)`, cutout mode SHORT_EDGES, set once in onCreate). `root` has an insets
  listener that pads `content` (browse page + mini bar) and `playerLayer` with systemBars|displayCutout|ime, recomputed on every change. The fullscreen video
  container is added to `root` unpadded, so it fills the screen including the camera cutout. Do NOT toggle decorFitsSystemWindows/cutout mode when entering or
  leaving fullscreen: an earlier version did, and after leaving landscape fullscreen the page kept a stale 36dp left padding (cutout width) in portrait.
- DOWNLOAD (experimental, opened from the settings menu row "Download video / audio"). Engine: `io.github.junkfood02.youtubedl-android:library` + `:ffmpeg` 0.18.1
  (yt-dlp + python + QuickJS + ffmpeg inside the app). Files:
  * `DownloadEngine.kt`: wrapper. `init` (ffmpeg is initialised by REFLECTION, class `com.yausername.ffmpeg.FFmpeg`: a wrong name only disables merging),
    `update(ctx, nightly)` = `updateYoutubeDL(ctx, UpdateChannel._STABLE/_NIGHTLY)`, `analyze` (`--dump-single-json`, parsed with org.json into video heights with approximate
    sizes plus EVERY audio-only stream: m4a/webm, one per container+bitrate, best first, each with its format id), `download` (`execute(request, processId) { progress, eta, line -> }`;
    video: `bv*[height<=H][ext=mp4]+ba[ext=m4a]/...` + `--merge-output-format mp4/mkv`; audio: `-f <formatId>`; without ffmpeg only `b[ext=mp4]/b`),
    `publishToDownloads` (MediaStore Downloads/TubeLite), `friendlyErrorRes` (raw yt-dlp error -> string resource dl_err_*), in-memory analysis cache (30 min per video id, cleared on update).
  * `DownloadService.kt`: foreground service (type dataSync, permission FOREGROUND_SERVICE_DATA_SYNC) that runs ONE download: progress notification with a Cancel action, final
    notification (opens the system Downloads app), `DownloadService.running` and `DownloadService.listener` (text, percent or -1, finished) for the screen.
  * `DownloadTestActivity.kt` (name kept from the first test version): the screen. Dark cards in the app's style, thumbnail (i.ytimg.com, fixed host, no redirects), qualities as radio
    buttons (video heights, then audio bitrates), default = highest video quality <= the app's default quality setting, Download/Cancel, Update (stable/nightly), collapsible log.
    The analysis starts by itself when the screen opens from a video; pressing Download during the analysis queues it.
  * BUGS FIXED in 1.25 (keep in mind): (1) `DownloadService` created the `Option` WITHOUT the stream container (`ext`), so `wantOpus` was never true and webm audio was saved as
    .webm: the extra `EXTRA_EXT` now carries it. (2) MediaStore: when the MIME type disagrees with the extension (e.g. "audio/webm" for ".webm", which Android maps to
    "video/webm") it treats the extension as part of the name and a duplicate becomes "name.webm (1)" (broken file). `publishToDownloads` now takes the MIME type from
    `MimeTypeMap` (fallback table), makes names unique itself (number BEFORE the extension) and, if Android still changes a .opus extension, saves it again as .ogg.
  * Opus audio: YouTube keeps Opus inside .webm. With ffmpeg ready, `buildRequest(toOpus = true)` adds `-x --audio-format opus` so yt-dlp REMUXES (copy, no re-encode) into a real
    `.opus` file (mime audio/ogg). If that step fails (`conversionFailed`) the download is repeated without it and the .webm is kept. MP3 (re-encoding, needs an mp3 encoder in the
    bundled ffmpeg: unverified) and `--embed-metadata` were discussed but are NOT built.
  * Notification: title = video title, large icon = square crop of the thumbnail (`DownloadEngine.loadThumbnail`, cached), subtext = chosen quality, text = percent + speed
    (parsed from yt-dlp's progress line) + ETA, own small icon `ic_stat_download`. Tapping it opens the download screen in "running" mode (`applyMode`: only the video being
    downloaded, no link/qualities). The finished notification opens the downloaded file itself (ACTION_VIEW on the MediaStore uri). The log shows "Average speed ...".
  * All texts are string resources dl_* in values and values-ar (keep both in sync).
  AUTO UPDATE: only inside `withAutoUpdate`: when analyze/download fails with something an update can fix (deny-list: bot check, private/unavailable video, no network,
  no space, cancel, age), the engine is updated ONCE per app session (stable channel) and the step is retried.
  ANALYSIS: never analyze videos in the background or in bulk: it costs CPU/data (about 30 s on the owner's phone) and risks YouTube's bot checks on his mobile IP.
  A "Test clients" benchmark (yt-dlp `player_client`) was discussed but NOT built; the default clients change with every yt-dlp release.
  Build config that must stay: `ndk.abiFilters = arm64-v8a`, `packaging.jniLibs.useLegacyPackaging = true`, the META-INF excludes.
  Rules agreed with the owner: NO cookies or account data are ever passed to yt-dlp; updates only by the user's button or the single automatic update after a failure,
  only through the library's official update, never in the background; the bundled version stays as fallback and the version is always shown. Only https YouTube
  links are accepted (`DownloadEngine.isYoutubeUrl`). The library is GPL-3.0 (fine for personal use; open the source if the APK is ever distributed).
  Slow webm download observed (about 150 KB/s for a 7 MB opus file) while a VPN was on: cause not established (VPN, YouTube throttling of that stream, or the JS challenge);
  compare the "Average speed" log line for m4a vs webm with the VPN off before changing anything. Idea not built: aria2c (multi-connection) via the library's aria2c module.
  Observed on the owner's phone: video+sound single-file download worked; audio-only failed until the engine was updated from inside the app (the bundled yt-dlp is old, so the
  auto update after a failure matters). Ideas not built yet: MP3 conversion, a "lite" (no download) and "full" build flavor with two release assets.
- RENDERER LOSS (1.26): `TubeClient.onRenderProcessGone` returns true (otherwise Android kills the whole app). Player page: `closePlayer()`. Browse page: `recreateBrowse()` builds a new
  browse WebView in `content` (a field now) and loads the home page. Both show the `load_error` toast and log "Render process gone".
- `configChanges` also contains `locale|layoutDirection` (1.26): a language change no longer recreates the activity (which destroyed the player and stopped playback).
- DOWNLOAD SPEED (1.26): the analysis (`--dump-single-json`) is saved to `cacheDir/info/<id>.json` and the download runs `--load-info-json <file>` WITHOUT a url, so the video is
  extracted only once (before, the download extracted it again: the second long wait). Valid for 30 min (stream links expire); if that download fails with anything that is not on the
  deny-list it falls back once to the normal url download. `YoutubeDLRequest(emptyList<String>())` is used for the url-less request (unverified against the library source).
  A cancel (`DownloadEngine.cancelRequested`) never triggers the auto update or a retry. Partial files (.part/.ytdl) older than 24 h are removed at the start of a download.
  Multi-threading does not speed up the analysis (yt-dlp is single-threaded Python); `-N` (concurrent fragments) only helps fragmented formats, aria2c is the idea for the download itself.
- EARLY QUALITY (1.27): YouTube starts at its own ~360p and the player API appears a moment after page start, so the first seconds used the wrong quality. inject.js `kickQuality()`
  polls the player every 150 ms (max 20 s, stops once applied) after page start, after `loadstart`/`emptied` and when the 3 s timer sees an unapplied id; it also re-applies once at the
  first `playing`. The log line "quality X set N ms after page start (player was at Y)" shows the timing. Shorts poll every 400 ms (cheap early return). If it is still late, the next idea
  is to write YouTube's own localStorage key `yt-player-quality` (we currently block writes to it; both WebViews share it, which caused the Shorts leak) before the player starts.
- HEADPHONES (1.27): `PlaybackService` registers `ACTION_AUDIO_BECOMING_NOISY` (not exported receiver) and sends "pause" while playing (headphones unplugged, Bluetooth lost).
- KNOWN, NOT FIXED: the two player buttons are positioned with fixed dp (pill: 7dp from top, settings: 48dp at the top right/left) assuming YouTube's 48dp top bar. On other phones/YouTube
  layouts the page can differ (video starts at the top, no bar). Planned fix: inject.js measures the real "more" button / bar and reports the rect over the bridge, Kotlin places the buttons from it.
  Unused: Compose plugin and dependencies are still in build.gradle.kts (no Compose code exists); removing them also needs `androidx.activity:activity` for ComponentActivity.
- Error log: launcher-icon long-press shortcut AND a neutral "Error log" button in the quality dialog (player page).
- Zoom-to-fill (`setZoom` in inject.js): computes the picture size from `videoWidth/videoHeight`, then scales the video's PARENT container with a rule in our own
  stylesheet (`.__tube_zoom{transform:scale(s) !important}`), because YouTube rewrites the <video> element's inline style. If the container scale changes nothing it
  falls back to the video element. Everything is logged as "PAGE zoom..." (box, element, picture, scale, widths before/after, and 500 ms later).
  On a real device (Android 15, 764x360 CSS px fullscreen) the pinch gesture and the scale value were confirmed correct from the log (about 1.19); the earlier
  inline `video.style.transform` approach showed no visible change.
- Player page unmutes each new video once (`ensureUnmuted` in inject.js; YouTube starts muted when the page has no user gesture).
- The two WebViews share site storage. YouTube saves the last chosen quality in localStorage "yt-player-quality", which made a Shorts quality leak into the regular
  player. inject.js (both pages, document start) ignores writes to that key and removes any old value; "auto" always resets the range to tiny..highres.
- `refreshPlayer()` (onResume) nudges the video surface to redraw after returning to the app. Best guess, unconfirmed.
- If WEB_MESSAGE_LISTENER is not supported the app falls back to ONE page (everything in `browseView`, no notification).
- Unconfirmed on a real device (nothing here was run): anchor click interception on m.youtube.com, `nextVideo()/previousVideo()` on the player element.

## How it works
- `YtWebView.kt`: overrides `onWindowVisibilityChanged` to always say VISIBLE (needed for background audio). Do not call `webView.onPause()`.
- `assets/inject.js` (added via `addDocumentStartJavaScript`, fallback: injected in `onPageStarted`):
  fake `document.hidden/visibilityState`; deletes `adPlacements/playerAds/adSlots` from `JSON.parse` / `Response.json` / `ytInitialPlayerResponse`;
  CSS hides ad UI; skips an ad if `.ad-showing` (on media events + 1 s safety timer); reports play state to `TubeBridge` (on media events + 3 s safety timer); `window.__tubeCmd(play|pause|toggle)`.
- `AdBlock.kt`: empty answer for ad hosts and ad-ping paths (`/pagead/`, `/api/stats/ads`, `/ptracking`, ...).
- `PlaybackService.kt`: foreground service (type mediaPlayback), MediaSession, notification with play/pause/stop. Controlled via
  `PlaybackService.instance.update()` and `PlaybackService.controller` (set by MainActivity). Auto-stops 5 min after pause.
- Sign-in: user agent drops "; wv" and "Version/4.0", X-Requested-With header is disabled. Google may still refuse WebView sign-in; this is the weakest point.
- YouTube changes its page often: ad blocking and selectors in inject.js WILL need fixes from time to time. The error log
  (long-press the launcher icon -> Error log) shows app-side problems only.

## Files (app/src/main/java/com/khereba1/app)
- App.kt: Application, AppLog (file log), LocaleHelper (from the kit, unchanged)
- MainActivity.kt: WebView setup, navigation rules, bridge, fullscreen, back handling, start-URL from intents
- YtWebView.kt: WebView; the player one always reports visible
- AdBlock.kt: network-level ad rules
- PlaybackService.kt: background playback service + notification
- GestureFrame.kt: fullscreen container (works in dispatchTouchEvent because the WebView asks parents not to intercept during pinch; takes a gesture over early and sends the page one ACTION_CANCEL); vertical swipe = brightness (left) / volume (right); pinch out/in = zoom-to-fill on/off (commands "zoom:1"/"zoom:0" -> `setZoom` in inject.js, which scales the <video> element)
- LogActivity.kt: shows AppLog
- assets/inject.js: bundled page script
- res/xml/shortcuts.xml: launcher shortcut to LogActivity

## How it is built (IMPORTANT)
The owner has only a phone. There is NO local build. Code is pushed from Termux to GitHub and GitHub Actions
(.github/workflows/build.yml) builds the APK with Gradle 8.10.2 (AGP 8.7.3, Kotlin 2.0.21). You cannot compile: be careful and conservative.
- Avoid Kotlin JVM signature clashes (`var x` with `private set` plus `fun setX(...)` breaks the build).
- Every new string needs an entry in values/strings.xml AND values-ar/strings.xml (missing translation fails the release build).
- Do not bump library versions without checking that the exact version exists (androidx.webkit 1.12.1 is used).

## REQUIRED answer format (the owner has only a phone + Termux)
1. Briefly say what you changed and why (plain language; the owner writes Egyptian Arabic mixed with English terms, reply in the same).
2. Deliver ONE zip containing only the changed/new files, complete (never snippets or diffs), with paths relative to the project root.
   Name it `tubeN.zip` (tube1, tube2, ...). If you cannot create a zip, give each file in full in its own code block with its path, and say so.
3. Raise `versionCode` (+1) and `versionName` in `app/build.gradle.kts` for every functional change, and include that file.
4. Give the install command, ready to copy:
   `cd ~/tubelite && unzip -o "$(ls -t ~/storage/downloads/tubeN*.zip | head -1)" -d . && git add . && git commit -m "short message" && git push`
   (GitHub repo: Khereba1/TubeLite, APK: github.com/Khereba1/TubeLite/releases/tag/latest)
5. Say honestly that nothing was compiled or tested, and what to check first on the phone.
6. If the build fails, the owner sends lines containing `e: ` from the Actions log; fix only those.

## Signing and sharing
The stable signing key lives in GitHub Secrets (KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD).
Never print, commit or ask for them, and never ask for the backup folder.
`bash share_zip.sh` builds a clean `<folder>-for-AI.zip` (no keys, env files, build output or APKs) for sharing.
