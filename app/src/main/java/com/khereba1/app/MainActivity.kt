package com.khereba1.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private const val HOME_URL = "https://m.youtube.com/"
private const val PAUSED_STOP_DELAY_MS = 5 * 60 * 1000L
private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
private val COMMAND_REGEX = Regex("^(play|pause|toggle|next|prev|seek:[0-9]{1,9})$")

private fun isYoutubeHost(host: String?): Boolean {
    if (host == null) return false
    val h = host.lowercase(Locale.ROOT)
    return h == "youtube.com" || h.endsWith(".youtube.com")
}

/** Hosts a WebView may navigate to inside the app. Everything else opens in the normal browser. */
private fun isAllowedHost(host: String?): Boolean {
    if (host == null) return false
    val h = host.lowercase(Locale.ROOT)
    return isYoutubeHost(h) || h == "google.com" || h.endsWith(".google.com")
}

private fun isWatchUrl(url: String?): Boolean {
    if (url == null) return false
    val u = Uri.parse(url)
    return u.scheme == "https" && isYoutubeHost(u.host) && u.path == "/watch"
}

/**
 * Two pages: "browse" (home, search, channels) and "player" (the watch page).
 * Tapping a video in the browse page opens it in the player page. Back/minimize slides the player away,
 * leaving a small bar at the bottom; the video keeps playing while you browse.
 */
class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var browseView: YtWebView
    private var playerView: YtWebView? = null
    private lateinit var playerLayer: FrameLayout
    private lateinit var miniBar: LinearLayout
    private lateinit var miniThumb: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniToggle: ImageButton
    private var playerExpanded = false

    private var customView: View? = null
    private var customContainer: GestureFrame? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var volumeAccum = 0f

    private val handler = Handler(Looper.getMainLooper())
    private val stopServiceTask = Runnable { stopService(Intent(this, PlaybackService::class.java)) }
    private var isPlaying = false
    private var lastArtId = ""

    private var injectScript = ""
    private var userAgent = ""
    private var bridgeSupported = false
    private var documentStartSupported = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        userAgent = WebSettings.getDefaultUserAgent(this)
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
        bridgeSupported = try {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        } catch (e: Throwable) {
            false
        }
        documentStartSupported = try {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        } catch (e: Throwable) {
            false
        }
        try {
            injectScript = assets.open("inject.js").bufferedReader().use { it.readText() }
        } catch (e: Throwable) {
            AppLog.log("INJECT", "Cannot read inject.js", e)
        }
        if (!bridgeSupported) AppLog.log("INJECT", "WEB_MESSAGE_LISTENER not supported: single page mode, no notification")

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        browseView = createWebView("browse")
        miniBar = buildMiniBar()
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.addView(browseView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(
            miniBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(
            content,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        playerLayer = buildPlayerLayer()
        root.addView(
            playerLayer,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)

        PlaybackService.controller = { cmd -> runCommand(cmd) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    playerView != null && playerExpanded -> minimizePlayer()
                    browseView.canGoBack() -> browseView.goBack()
                    isPlaying -> moveTaskToBack(true)
                    else -> finish()
                }
            }
        })

        requestNotificationPermission()
        routeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            routeIntent(intent)
        } else if (playerView != null) {
            expandPlayer() // tapped the media notification
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Fullscreen video + Home button = Picture-in-Picture. Otherwise audio just continues in the background.
        if (customView != null) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            } catch (e: Throwable) {
                AppLog.log("PIP", "enter failed", e)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Only the browse page is paused. NEVER pause the player page: it would stop background playback.
        browseView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        browseView.onResume()
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopServiceTask)
        PlaybackService.controller = null
        PlaybackService.art = null
        stopService(Intent(this, PlaybackService::class.java))
        try {
            playerView?.let {
                playerLayer.removeView(it)
                it.destroy()
            }
            playerView = null
        } catch (e: Throwable) {
            AppLog.log("MAIN", "player destroy failed", e)
        }
        try {
            browseView.destroy()
        } catch (e: Throwable) {
            AppLog.log("MAIN", "browse destroy failed", e)
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- views

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(role: String): YtWebView {
        val wv = YtWebView(this, role == "player")
        wv.setBackgroundColor(Color.BLACK)
        wv.overScrollMode = View.OVER_SCROLL_NEVER // no stretch effect that makes the whole page jiggle

        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.safeBrowsingEnabled = true
        // Look like normal mobile Chrome (Google blocks sign-in from user agents that say "wv").
        s.userAgentString = userAgent
        // Do not send the X-Requested-With header (it contains this app's package name).
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
            }
        } catch (e: Throwable) {
            AppLog.log("MAIN", "X-Requested-With setting failed", e)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = TubeClient(role)
        wv.webChromeClient = TubeChrome()
        installBridge(wv, role)
        return wv
    }

    private fun installBridge(wv: WebView, role: String) {
        val origins = setOf("https://m.youtube.com", "https://www.youtube.com")
        try {
            if (bridgeSupported) {
                // The only way a page can talk to the app: a text message, from YouTube origins only.
                WebViewCompat.addWebMessageListener(
                    wv, "TubeBridge", origins,
                    WebViewCompat.WebMessageListener { _, message, _, isMainFrame, _ ->
                        if (isMainFrame) handleMessage(role, message.data ?: "")
                    }
                )
            }
            if (injectScript.isNotEmpty() && documentStartSupported) {
                WebViewCompat.addDocumentStartJavaScript(wv, "window.__tubeRole='" + role + "';\n" + injectScript, origins)
            }
        } catch (e: Throwable) {
            AppLog.log("INJECT", "Bridge/script setup failed", e)
        }
    }

    private fun buildPlayerLayer(): FrameLayout {
        val dp = resources.displayMetrics.density
        val layer = FrameLayout(this)
        layer.setBackgroundColor(Color.BLACK)
        layer.visibility = View.GONE

        val pill = ImageView(this)
        pill.setImageResource(android.R.drawable.arrow_down_float)
        pill.contentDescription = getString(R.string.minimize)
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#99000000"))
        bg.cornerRadius = 16 * dp
        pill.background = bg
        pill.setOnClickListener { minimizePlayer() }
        val lp = FrameLayout.LayoutParams((56 * dp).toInt(), (28 * dp).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        lp.topMargin = (4 * dp).toInt()
        layer.addView(pill, lp)
        return layer
    }

    private fun buildMiniBar(): LinearLayout {
        val dp = resources.displayMetrics.density
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Color.parseColor("#212121"))
        bar.setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
        bar.visibility = View.GONE
        bar.setOnClickListener { expandPlayer() }

        miniThumb = ImageView(this)
        miniThumb.scaleType = ImageView.ScaleType.CENTER_CROP
        miniThumb.setBackgroundColor(Color.BLACK)
        bar.addView(miniThumb, LinearLayout.LayoutParams((96 * dp).toInt(), (54 * dp).toInt()))

        miniTitle = TextView(this)
        miniTitle.setTextColor(Color.WHITE)
        miniTitle.textSize = 14f
        miniTitle.maxLines = 2
        miniTitle.ellipsize = android.text.TextUtils.TruncateAt.END
        miniTitle.setPadding((12 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        bar.addView(miniTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        miniToggle = ImageButton(this)
        miniToggle.setImageResource(android.R.drawable.ic_media_pause)
        miniToggle.contentDescription = getString(R.string.pause)
        miniToggle.setBackgroundColor(Color.TRANSPARENT)
        miniToggle.setOnClickListener { runCommand("toggle") }
        bar.addView(miniToggle, LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt()))

        val close = ImageButton(this)
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        close.contentDescription = getString(R.string.stop)
        close.setBackgroundColor(Color.TRANSPARENT)
        close.setOnClickListener { closePlayer() }
        bar.addView(close, LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt()))
        return bar
    }

    // ---------------------------------------------------------------- player page lifecycle

    private fun ensurePlayer(): YtWebView {
        playerView?.let { return it }
        val pv = createWebView("player")
        playerView = pv
        playerLayer.addView(
            pv, 0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        return pv
    }

    private fun openVideo(url: String) {
        val u = Uri.parse(url)
        if (u.scheme != "https" || !isYoutubeHost(u.host) || u.path != "/watch") return
        val target = u.buildUpon().authority("m.youtube.com").build().toString()
        if (!bridgeSupported) {
            browseView.loadUrl(target) // single page mode
            return
        }
        val pv = ensurePlayer()
        pv.loadUrl(target)
        expandPlayer()
    }

    private fun openInBrowse(url: String, minimize: Boolean) {
        val u = Uri.parse(url)
        if (u.scheme != "https" || !isYoutubeHost(u.host)) return
        browseView.loadUrl(u.buildUpon().authority("m.youtube.com").build().toString())
        if (minimize) minimizePlayer()
    }

    private fun expandPlayer() {
        if (playerView == null) return
        playerExpanded = true
        miniBar.visibility = View.GONE
        playerLayer.visibility = View.VISIBLE
        if (playerLayer.translationY > root.height) playerLayer.translationY = root.height.toFloat()
        playerLayer.animate().translationY(0f).setDuration(220).start()
    }

    private fun minimizePlayer() {
        if (playerView == null || !playerExpanded) return
        playerExpanded = false
        miniBar.visibility = View.VISIBLE
        playerLayer.animate().translationY(root.height.toFloat()).setDuration(220)
            .withEndAction { if (!playerExpanded) playerLayer.translationY = 20000f }
            .start()
    }

    private fun closePlayer() {
        if (customView != null) hideCustomView()
        val pv = playerView ?: return
        playerView = null
        playerExpanded = false
        try {
            playerLayer.removeView(pv)
            pv.destroy()
        } catch (e: Throwable) {
            AppLog.log("MAIN", "closePlayer failed", e)
        }
        playerLayer.animate().cancel()
        playerLayer.translationY = 0f
        playerLayer.visibility = View.GONE
        miniBar.visibility = View.GONE
        isPlaying = false
        lastArtId = ""
        PlaybackService.art = null
        handler.removeCallbacks(stopServiceTask)
        stopService(Intent(this, PlaybackService::class.java))
    }

    private fun runCommand(cmd: String) {
        if (!COMMAND_REGEX.matches(cmd)) return
        runOnUiThread {
            playerView?.evaluateJavascript("window.__tubeCmd&&window.__tubeCmd('" + cmd + "')", null)
        }
    }

    // ---------------------------------------------------------------- messages from pages

    private fun handleMessage(role: String, msg: String) {
        when {
            msg.startsWith("open|") -> if (role == "browse") openVideo(msg.substring(5))
            msg.startsWith("browse|") -> if (role == "player") openInBrowse(msg.substring(7), true)
            role == "player" -> onPlayState(msg)
        }
    }

    /** "<1|0>|<videoId>|<positionMs>|<durationMs>|<title>" */
    private fun onPlayState(msg: String) {
        val p = msg.split("|", limit = 5)
        if (p.size < 5) return
        val playing = p[0] == "1"
        val id = p[1]
        val pos = p[2].toLongOrNull() ?: 0L
        val dur = p[3].toLongOrNull() ?: 0L
        val title = p[4].take(120)
        isPlaying = playing
        handler.removeCallbacks(stopServiceTask)

        if (title.isNotEmpty()) miniTitle.text = title
        miniToggle.setImageResource(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        miniToggle.contentDescription = getString(if (playing) R.string.pause else R.string.play)

        if (id.isNotEmpty() && id != lastArtId) {
            lastArtId = id
            PlaybackService.art = null
            miniThumb.setImageDrawable(null)
            loadArt(id)
        }

        val svc = PlaybackService.instance
        if (svc != null) {
            svc.update(playing, title, pos, dur)
        } else if (playing) {
            try {
                val i = Intent(this, PlaybackService::class.java)
                    .putExtra(PlaybackService.EXTRA_PLAYING, true)
                    .putExtra(PlaybackService.EXTRA_TITLE, title)
                    .putExtra(PlaybackService.EXTRA_POS, pos)
                    .putExtra(PlaybackService.EXTRA_DUR, dur)
                ContextCompat.startForegroundService(this, i)
            } catch (e: Throwable) {
                AppLog.log("SERVICE", "Cannot start playback service", e)
            }
        }
        if (!playing) handler.postDelayed(stopServiceTask, PAUSED_STOP_DELAY_MS)
    }

    /** Downloads the video thumbnail (public image, no cookies, no redirects) for the notification and mini bar. */
    private fun loadArt(id: String) {
        if (!VIDEO_ID_REGEX.matches(id)) return
        Thread {
            try {
                val conn = URL("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg").openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = false
                val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                conn.disconnect()
                if (bmp != null) {
                    runOnUiThread {
                        if (!isDestroyed && id == lastArtId) {
                            PlaybackService.art = bmp
                            PlaybackService.instance?.refresh()
                            miniThumb.setImageBitmap(bmp)
                        }
                    }
                }
            } catch (e: Throwable) {
                AppLog.log("ART", "thumbnail failed", e)
            }
        }.start()
    }

    // ---------------------------------------------------------------- navigation helpers

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun resolveStartUrl(intent: Intent?): String {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return HOME_URL
        val uri = intent.data ?: return HOME_URL
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return HOME_URL
        val host = uri.host?.lowercase(Locale.ROOT) ?: return HOME_URL
        if (host == "youtu.be") {
            val id = uri.lastPathSegment ?: return HOME_URL
            return "https://m.youtube.com/watch?v=" + Uri.encode(id)
        }
        if (host == "youtube.com" || host.endsWith(".youtube.com")) {
            return uri.buildUpon().authority("m.youtube.com").build().toString()
        }
        return HOME_URL
    }

    private fun routeIntent(intent: Intent?) {
        val url = resolveStartUrl(intent)
        if (isWatchUrl(url)) openVideo(url) else browseView.loadUrl(url)
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: Throwable) {
            AppLog.log("NAV", "Cannot open external link", e)
        }
    }

    // ---------------------------------------------------------------- fullscreen video

    private fun hideCustomView() {
        val v = customView ?: return
        val container = customContainer
        if (container != null) {
            container.removeView(v)
            root.removeView(container)
        } else {
            root.removeView(v)
        }
        customContainer = null
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.show(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback?) {
        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        view.setBackgroundColor(Color.BLACK)
        val container = GestureFrame(this) { left, delta -> adjustFromGesture(left, delta) }
        container.setBackgroundColor(Color.BLACK)
        container.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        customContainer = container
        root.addView(
            container,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Vertical swipe in fullscreen: left half = brightness, right half = volume. */
    private fun adjustFromGesture(left: Boolean, delta: Float) {
        try {
            if (left) {
                val lp = window.attributes
                var b = lp.screenBrightness
                if (b < 0f) {
                    b = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
                }
                lp.screenBrightness = (b + delta).coerceIn(0.02f, 1f)
                window.attributes = lp
            } else {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                volumeAccum += delta * max * 1.5f
                val steps = volumeAccum.toInt()
                if (steps != 0) {
                    volumeAccum -= steps
                    val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (cur + steps).coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
                }
            }
        } catch (e: Throwable) {
            AppLog.log("GESTURE", "adjust failed", e)
        }
    }

    // ---------------------------------------------------------------- WebView clients

    private inner class TubeClient(private val role: String) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val uri = request.url
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme == "https" && isAllowedHost(uri.host)) {
                if (role == "browse" && bridgeSupported && isWatchUrl(uri.toString())) {
                    openVideo(uri.toString())
                    return true
                }
                return false
            }
            if (scheme == "https" || scheme == "http") openExternal(uri)
            return true // block everything else (intent:, file:, javascript:, ...)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            if (!bridgeSupported || url == null) return
            if (role == "browse" && isWatchUrl(url)) {
                // The page navigated to a video by itself (link routing missed it): move it to the player page.
                openVideo(url)
                view.post { if (view.canGoBack()) view.goBack() }
            } else if (role == "player" && url.startsWith("https://m.youtube.com") && !isWatchUrl(url)) {
                // The player page left the watch page (video would stop anyway): show it in the browse page instead.
                handler.post {
                    openInBrowse(url, false)
                    closePlayer()
                }
            }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (AdBlock.shouldBlock(request.url)) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (!documentStartSupported && injectScript.isNotEmpty() && url != null &&
                url.startsWith("https://m.youtube.com")
            ) {
                view.evaluateJavascript("window.__tubeRole='" + role + "';\n" + injectScript, null)
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                AppLog.log("WEB", "Load error " + error.errorCode + ": " + error.description + " @ " + request.url.host)
                Toast.makeText(this@MainActivity, R.string.load_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private inner class TubeChrome : WebChromeClient() {

        override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
            if (view == null) {
                callback?.onCustomViewHidden()
                return
            }
            showCustomView(view, callback)
        }

        override fun onHideCustomView() {
            hideCustomView()
        }

        override fun getDefaultVideoPoster(): Bitmap? {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        // onPermissionRequest / onCreateWindow / geolocation are NOT overridden: mic, camera, popups, location stay denied.
    }
}
