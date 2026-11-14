# TubeLite: handoff notes for an AI assistant

Paste this file together with the project zip when asking an AI to change the app.
The family-wide rules are in STANDARD.md (ask the owner to attach it if missing).

## What this is
A private WebView wrapper around m.youtube.com for ONE owner who signs into his own Google account in it.
Features: background playback (foreground service + media notification), ad blocking (network rules + bundled page script),
fullscreen video (swipe gestures, keep-screen-on), Picture-in-Picture (Home while fullscreen), opens youtube links. Android app, Kotlin (View-based, no Compose UI), source package `com.khereba1.app`,
applicationId `com.khereba1.tubelite`. minSdk 29, compileSdk 35. UI languages: Arabic + English (strings only: notification, toasts).
Developer: Khereba1. GitHub repo: Khereba1/TubeLite. Nothing here was ever compiled or run by the AI that wrote it.

## SECURITY RULES (do not break, the owner logs into his account here)
1. NEVER load JavaScript or any code from the internet (no CDN, no `@latest`). All page script lives in `assets/inject.js`.
2. NEVER strip or rewrite Content-Security-Policy, and NEVER proxy page requests through app code.
3. NEVER use `addJavascriptInterface`. The only app<->page channel is `WebViewCompat.addWebMessageListener("TubeBridge")`,
   restricted to https://m.youtube.com and https://www.youtube.com. It accepts ONLY short text messages: the player state
   "<1|0>|<videoId>|<posMs>|<durMs>|<title>", "open|<watch url>" (browse page) and "browse|<youtube url>" (player page).
   Every URL is re-validated in Kotlin (https + youtube host) before use, and commands to the page go through COMMAND_REGEX.
4. NEVER expose cookies, tokens or any account data to JS or to any file/log. No `getCookie` helper.
5. Main-frame navigation is limited to youtube.com / google.com subdomains over https (`isAllowedHost` in MainActivity.kt); everything else goes to the external browser or is blocked.
6. Do not add permissions beyond: INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, POST_NOTIFICATIONS.
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
- Player controls (built by `buildPlayerLayer()`, drawn over the watch page's own 56dp top bar, assuming an LTR/English YouTube UI):
  a dark pill with a wide chevron (`ic_minimize`) centered = minimize; a round 48dp-touch button (`ic_quality`) placed exactly over the site's "more" (three dots)
  button at the top right = quality dialog (Auto, 144p..1080p). Saved in SharedPreferences "tubelite_prefs"/"quality" and sent to the
  page as `window.__tubeQuality` (document-start prefix + onPageFinished + `__tubeSetQuality`). The page calls the player's `setPlaybackQualityRange`.
  Picks made inside YouTube's own menu are NOT detected. Unconfirmed on a real device: whether the player API call actually changes the quality.
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
- GestureFrame.kt: fullscreen container; vertical swipe = brightness (left) / volume (right)
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
