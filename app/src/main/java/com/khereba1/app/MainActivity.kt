package com.khereba1.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
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
import android.webkit.RenderProcessGoneDetail
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private const val HOME_URL = "https://m.youtube.com/"
private const val PAUSED_STOP_DELAY_MS = 5 * 60 * 1000L
private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
private val COMMAND_REGEX = Regex("^(play|pause|toggle|next|prev|seek:[0-9]{1,9}|zoom:[01])$")

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

private fun isShortsUrl(url: String?): Boolean {
    if (url == null) return false
    val u = Uri.parse(url)
    val p = u.path ?: return false
    return u.scheme == "https" && isYoutubeHost(u.host) && (p == "/shorts" || p.startsWith("/shorts/"))
}

/** YouTube is shown without Shorts: /shorts/ID opens as the normal video /watch?v=ID. Null for the /shorts feed itself and anything else. */
private fun shortsToWatch(url: String?): String? {
    val s = url ?: return null
    if (!isShortsUrl(s)) return null
    val seg = Uri.parse(s).pathSegments
    if (seg.size < 2 || !VIDEO_ID_REGEX.matches(seg[1])) return null
    return "https://m.youtube.com/watch?v=" + seg[1]
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
    private lateinit var content: LinearLayout
    private lateinit var browseView: YtWebView
    private var playerView: YtWebView? = null
    private lateinit var playerLayer: FrameLayout
    private lateinit var miniBar: LinearLayout
    private lateinit var miniThumb: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniToggle: ImageButton
    private var playerExpanded = false
    private lateinit var qualityBtn: ImageButton
    private var pageRtl: Boolean? = null
    private var quality = "auto"        // default video quality
    private var shortsQuality = "auto"  // default Shorts (reels) quality
    private var speed = "1.0"           // default video playback speed
    private var hideShorts = false      // YouTube is shown without Shorts (off by default)
    private var shortsAsVideo = false   // (only with hideShorts) Shorts links open as normal videos
    private val qualityValues = arrayOf("auto", "tiny", "small", "medium", "large", "hd720", "hd1080")
    private val speedValues = arrayOf("0.5", "0.75", "1.0", "1.25", "1.5", "1.75", "2.0")

    private var customView: View? = null
    private var customContainer: GestureFrame? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var volumeAccum = 0f

    private val handler = Handler(Looper.getMainLooper())
    private val stopServiceTask = Runnable { stopService(Intent(this, PlaybackService::class.java)) }
    private var isPlaying = false
    private var lastArtId = ""

    private val bridgeOrigins = setOf("https://m.youtube.com", "https://www.youtube.com")

    // The saved settings are handed to each page by its own small document-start script, which is registered again
    // after every settings change (a baked-in copy would stay stale for the next page load).
    private val prefsHandlers = HashMap<WebView, ScriptHandler>()

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
        val prefs = getSharedPreferences("tubelite_prefs", Context.MODE_PRIVATE)
        quality = prefs.getString("quality", "auto") ?: "auto"
        shortsQuality = prefs.getString("shorts_quality", "auto") ?: "auto"
        speed = prefs.getString("speed", "1.0") ?: "1.0"
        if (quality !in qualityValues) quality = "auto"
        if (shortsQuality !in qualityValues) shortsQuality = "auto"
        if (speed !in speedValues) speed = "1.0"
        hideShorts = prefs.getBoolean("hide_shorts", false)
        shortsAsVideo = prefs.getBoolean("shorts_as_video", false)
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
        content = LinearLayout(this)
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

        // The window always draws edge-to-edge (also under the camera cutout). The normal UI (browse page, mini bar,
        // player page) gets its padding from the CURRENT insets every time they change (rotation, bars shown/hidden,
        // keyboard), so nothing can stay stale. The fullscreen video container is added to `root` without padding,
        // so it really fills the screen, cutout included.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val wlp = window.attributes
        wlp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = wlp
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val pad = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            content.setPadding(pad.left, pad.top, pad.right, pad.bottom)
            playerLayer.setPadding(pad.left, pad.top, pad.right, pad.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

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
        refreshPlayer()
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopServiceTask)
        PlaybackService.controller = null
        PlaybackService.art = null
        stopService(Intent(this, PlaybackService::class.java))
        prefsHandlers.clear()
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
        try {
            if (bridgeSupported) {
                // The only way a page can talk to the app: a text message, from YouTube origins only.
                WebViewCompat.addWebMessageListener(
                    wv, "TubeBridge", bridgeOrigins,
                    WebViewCompat.WebMessageListener { _, message, _, isMainFrame, _ ->
                        if (isMainFrame) handleMessage(role, message.data ?: "")
                    }
                )
            }
            if (injectScript.isNotEmpty() && documentStartSupported) {
                WebViewCompat.addDocumentStartJavaScript(wv, "window.__tubeRole='" + role + "';\n" + injectScript, bridgeOrigins)
                registerPrefsScript(wv)
            }
        } catch (e: Throwable) {
            AppLog.log("INJECT", "Bridge/script setup failed", e)
        }
    }

    /** (Re)registers the document-start script that carries the current settings, replacing the previous one. */
    private fun registerPrefsScript(wv: WebView) {
        if (!documentStartSupported) return
        try {
            prefsHandlers.remove(wv)?.remove()
            prefsHandlers[wv] = WebViewCompat.addDocumentStartJavaScript(wv, prefsScript(), bridgeOrigins)
        } catch (e: Throwable) {
            AppLog.log("INJECT", "Settings script setup failed", e)
        }
    }

    private fun forgetPrefsScript(wv: WebView) {
        try {
            prefsHandlers.remove(wv)?.remove()
        } catch (e: Throwable) {
            // the view is going away anyway
        }
    }

    private fun iconButton(
        iconRes: Int, description: String, bg: Drawable, padH: Int, padV: Int, onClick: () -> Unit
    ): ImageButton {
        val b = ImageButton(this)
        b.setImageResource(iconRes)
        b.contentDescription = description
        b.scaleType = ImageView.ScaleType.FIT_CENTER
        b.setPadding(padH, padV, padH, padV)
        b.background = bg
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildPlayerLayer(): FrameLayout {
        val dp = resources.displayMetrics.density
        val layer = FrameLayout(this)
        layer.setBackgroundColor(Color.BLACK)
        layer.visibility = View.GONE
        val dark = Color.parseColor("#212121") // solid, so nothing shows through

        // Minimize: a clear pill in the middle of YouTube's top bar (the bar is 48dp tall, the middle is empty).
        val pillBg = GradientDrawable()
        pillBg.setColor(dark)
        pillBg.cornerRadius = 17 * dp
        val pill = iconButton(
            R.drawable.ic_minimize, getString(R.string.minimize), pillBg, (10 * dp).toInt(), (6 * dp).toInt()
        ) { minimizePlayer() }
        val plp = FrameLayout.LayoutParams((64 * dp).toInt(), (34 * dp).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        plp.topMargin = (7 * dp).toInt() // (48 - 34) / 2: centered in the 48dp bar
        layer.addView(pill, plp)

        // Settings: a round button placed exactly over the site's useless "more" (three dots) button, which sits at the
        // top right (LTR page) or top left (RTL page, see applyDirection). The view is 48dp (same touch area as the site
        // button), the visible circle is 40dp.
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        circle.setColor(dark)
        qualityBtn = iconButton(
            R.drawable.ic_quality, getString(R.string.settings_title),
            InsetDrawable(circle, (4 * dp).toInt()), (12 * dp).toInt(), (12 * dp).toInt()
        ) { showSettingsDialog() }
        val qlp = FrameLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt(), Gravity.TOP or Gravity.RIGHT)
        layer.addView(qualityBtn, qlp)
        return layer
    }

    /** The page tells us if YouTube is shown right-to-left (Arabic): then its "more" button is on the left. */
    private fun applyDirection(rtl: Boolean, how: String) {
        if (pageRtl != rtl) AppLog.log("DIR", "page direction: " + (if (rtl) "rtl" else "ltr") + " (" + how + ")")
        pageRtl = rtl
        val lp = qualityBtn.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.TOP or (if (rtl) Gravity.LEFT else Gravity.RIGHT)
        qualityBtn.layoutParams = lp
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

    /**
     * What to do with a Shorts url. Null = leave it alone (Shorts shown, or hidden but not converted). Otherwise the url to open instead:
     * /watch?v=ID (only with "open as normal videos") or the home page (the Shorts feed itself does not exist while Shorts are hidden).
     */
    private fun shortsTarget(url: String?): String? {
        if (!hideShorts || !isShortsUrl(url)) return null
        val w = shortsToWatch(url)
        if (w != null) return if (shortsAsVideo) w else null
        return HOME_URL
    }

    private fun openInBrowse(url: String, minimize: Boolean) {
        val u = Uri.parse(url)
        if (u.scheme != "https" || !isYoutubeHost(u.host)) return
        val st = shortsTarget(url)
        if (st != null) {
            if (isWatchUrl(st)) {
                openVideo(st)
            } else {
                browseView.loadUrl(HOME_URL)
                if (minimize) minimizePlayer()
            }
            return
        }
        browseView.loadUrl(u.buildUpon().authority("m.youtube.com").build().toString())
        if (minimize) minimizePlayer()
    }

    private fun expandPlayer() {
        if (playerView == null) return
        playerExpanded = true
        miniBar.visibility = View.GONE
        playerLayer.visibility = View.VISIBLE
        if (playerLayer.translationY > root.height) playerLayer.translationY = root.height.toFloat()
        playerLayer.animate().translationY(0f).setDuration(220)
            .withEndAction {
                if (playerExpanded) {
                    refreshPlayer(true) // make sure the video layer sits exactly under the top bar
                    // The player now covers the browse page: stop its previews/Shorts and hide it (no overlapping videos).
                    browseView.evaluateJavascript(
                        "document.querySelectorAll('video').forEach(function(v){try{v.pause()}catch(e){}})", null
                    )
                    browseView.visibility = View.INVISIBLE
                }
            }
            .start()
    }

    private fun minimizePlayer() {
        if (playerView == null || !playerExpanded) return
        playerExpanded = false
        browseView.visibility = View.VISIBLE
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
        forgetPrefsScript(pv)
        try {
            playerLayer.removeView(pv)
            pv.destroy()
        } catch (e: Throwable) {
            AppLog.log("MAIN", "closePlayer failed", e)
        }
        playerLayer.animate().cancel()
        playerLayer.translationY = 0f
        playerLayer.visibility = View.GONE
        browseView.visibility = View.VISIBLE
        miniBar.visibility = View.GONE
        isPlaying = false
        lastArtId = ""
        PlaybackService.art = null
        handler.removeCallbacks(stopServiceTask)
        stopService(Intent(this, PlaybackService::class.java))
    }

    /** The browse page's renderer died (usually the system reclaimed memory): build a fresh page instead of crashing. */
    private fun recreateBrowse() {
        val old = browseView
        val index = content.indexOfChild(old).coerceAtLeast(0)
        forgetPrefsScript(old)
        try {
            content.removeView(old)
            old.destroy()
        } catch (e: Throwable) {
            AppLog.log("MAIN", "old browse page cleanup failed", e)
        }
        browseView = createWebView("browse")
        content.addView(browseView, index, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        browseView.visibility = if (playerExpanded) View.INVISIBLE else View.VISIBLE
        browseView.loadUrl(HOME_URL)
    }

    private fun runCommand(cmd: String) {
        if (!COMMAND_REGEX.matches(cmd)) return
        runOnUiThread {
            playerView?.evaluateJavascript("window.__tubeCmd&&window.__tubeCmd('" + cmd + "')", null)
        }
    }

    // ---------------------------------------------------------------- quality + surface refresh

    private fun qualityLabel(q: String): String {
        return when (q) {
            "tiny" -> "144p"
            "small" -> "240p"
            "medium" -> "360p"
            "large" -> "480p"
            "hd720" -> "720p"
            "hd1080" -> "1080p"
            else -> getString(R.string.quality_auto)
        }
    }

    private fun speedLabel(v: String): String = (if (v == "1.0") "1" else v) + "\u00D7"

    /** JS that hands the saved settings to a page. Values can only be entries of the fixed lists. */
    private fun prefsScript(): String =
        "window.__tubeQuality='" + quality + "';window.__tubeShortsQuality='" + shortsQuality +
            "';window.__tubeSpeed='" + speed + "';window.__tubeHideShorts='" + (if (hideShorts) "1" else "0") +
            "';window.__tubeShortsAsVideo='" + (if (shortsAsVideo) "1" else "0") +
            "';window.__tubeSettingsLabel=" + JSONObject.quote(getString(R.string.settings_title)) + ";"

    /** Save, then tell both pages to apply the new settings to what is playing now. */
    private fun savePrefsAndApply() {
        getSharedPreferences("tubelite_prefs", Context.MODE_PRIVATE).edit()
            .putString("quality", quality)
            .putString("shorts_quality", shortsQuality)
            .putString("speed", speed)
            .putBoolean("hide_shorts", hideShorts)
            .putBoolean("shorts_as_video", shortsAsVideo)
            .apply()
        playerView?.let { registerPrefsScript(it) }
        registerPrefsScript(browseView)
        val js = prefsScript() + "window.__tubeApplyShortsPrefs&&window.__tubeApplyShortsPrefs();" +
            "window.__tubeApplyPrefs&&window.__tubeApplyPrefs()"
        playerView?.evaluateJavascript(js, null)
        browseView.evaluateJavascript(js, null)
    }

    private fun pickOption(
        titleRes: Int, labels: Array<String>, values: Array<String>, current: String, onPick: (String) -> Unit
    ) {
        val checked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                onPick(values[which])
                dialog.dismiss()
                savePrefsAndApply()
                showSettingsDialog() // back to the list, now showing the new value
            }
            .show()
    }

    private fun onOff(v: Boolean): String = getString(if (v) R.string.settings_on else R.string.settings_off)

    private fun showSettingsDialog() {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        val qualityLabels = Array(qualityValues.size) { i -> qualityLabel(qualityValues[i]) }
        val speedLabels = Array(speedValues.size) { i -> speedLabel(speedValues[i]) }
        fun add(label: String, action: () -> Unit) {
            labels.add(label)
            actions.add(action)
        }
        fun toggle(change: () -> Unit) {
            change()
            savePrefsAndApply()
            showSettingsDialog() // back to the list, now showing the new value
        }
        add(getString(R.string.settings_video_quality) + ": " + qualityLabel(quality)) {
            pickOption(R.string.settings_video_quality, qualityLabels, qualityValues, quality) { quality = it }
        }
        if (!hideShorts) {
            add(getString(R.string.settings_shorts_quality) + ": " + qualityLabel(shortsQuality)) {
                pickOption(R.string.settings_shorts_quality, qualityLabels, qualityValues, shortsQuality) { shortsQuality = it }
            }
        }
        add(getString(R.string.settings_speed) + ": " + speedLabel(speed)) {
            pickOption(R.string.settings_speed, speedLabels, speedValues, speed) { speed = it }
        }
        add(getString(R.string.settings_hide_shorts) + ": " + onOff(hideShorts)) { toggle { hideShorts = !hideShorts } }
        if (hideShorts) {
            add(getString(R.string.settings_shorts_as_video) + ": " + onOff(shortsAsVideo)) { toggle { shortsAsVideo = !shortsAsVideo } }
        }
        add(getString(R.string.settings_download_test)) {
            startActivity(Intent(this, DownloadTestActivity::class.java).putExtra("url", playerView?.url ?: ""))
        }
        add(getString(R.string.shortcut_log)) { startActivity(Intent(this, LogActivity::class.java)) }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    /** After returning to the app the video surface can show stale frames (sound is fine): nudge it to redraw. */
    private fun refreshPlayer(always: Boolean = false) {
        val pv = playerView ?: return
        // [always]: also for a paused video. Used after the slide-in and after leaving fullscreen: the video layer was once seen
        // detached from the top bar (a transparent gap) after such a layout change.
        val cond = if (always) "v" else "v&&!v.paused"
        handler.postDelayed({
            if (playerView === pv) {
                pv.invalidate()
                pv.evaluateJavascript(
                    "(function(){var v=document.querySelector('video');" +
                        "if(" + cond + "){v.style.display='none';void v.offsetHeight;v.style.display='';" +
                        "window.dispatchEvent(new Event('resize'));}})()",
                    null
                )
            }
        }, 300)
    }

    // ---------------------------------------------------------------- messages from pages

    private fun handleMessage(role: String, msg: String) {
        when {
            msg == "ui|settings" -> if (role == "browse") showSettingsDialog()
            msg.startsWith("open|") -> if (role == "browse") openVideo(msg.substring(5))
            msg.startsWith("browse|") -> if (role == "player") openInBrowse(msg.substring(7), true)
            msg.startsWith("dbg|") -> AppLog.log("PAGE", msg.substring(4).take(200)) // log only (either page)
            msg.startsWith("dir|") -> if (role == "player") {
                val parts = msg.substring(4).split("|")
                applyDirection(parts[0] == "rtl", if (parts.size > 1) parts[1] else "")
            }
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
            val st = shortsTarget(uri.toString())
            if (st != null) return st
            return uri.buildUpon().authority("m.youtube.com").build().toString()
        }
        return HOME_URL
    }

    private fun routeIntent(intent: Intent?) {
        val url = resolveStartUrl(intent)
        if (isWatchUrl(url)) {
            openVideo(url)
        } else {
            browseView.loadUrl(url)
            if (playerView != null) minimizePlayer()
        }
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
        runCommand("zoom:0") // leave fullscreen with the video back at normal size
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
        refreshPlayer(true)
    }

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback?) {
        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        view.setBackgroundColor(Color.BLACK)
        val container = GestureFrame(
            this,
            { left, delta -> adjustFromGesture(left, delta) },
            { fill -> runCommand(if (fill) "zoom:1" else "zoom:0") } // pinch out = fill the screen
        )
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
                val st = if (role == "browse") shortsTarget(uri.toString()) else null
                if (st != null) {
                    if (isWatchUrl(st)) openVideo(st) else view.post { view.loadUrl(HOME_URL) }
                    return true
                }
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
            } else if (role == "browse" && shortsTarget(url) != null) {
                // The page moved to a Short by itself: play it as a normal video (if so set), or go home for the Shorts feed.
                val st = shortsTarget(url)
                if (st != null && isWatchUrl(st)) openVideo(st)
                view.post { if (view.canGoBack()) view.goBack() else if (st != null && !isWatchUrl(st)) view.loadUrl(HOME_URL) }
            } else if (role == "player" && url.startsWith("https://m.youtube.com") && !isWatchUrl(url) && !isShortsUrl(url)) {
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

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            // also re-applies the Shorts setting: the document-start copy of the settings may have arrived after inject.js ran
            view.evaluateJavascript(prefsScript() + "window.__tubeApplyShortsPrefs&&window.__tubeApplyShortsPrefs()", null)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            if (!documentStartSupported && injectScript.isNotEmpty() && url != null &&
                url.startsWith("https://m.youtube.com")
            ) {
                view.evaluateJavascript("window.__tubeRole='" + role + "';" + prefsScript() + "\n" + injectScript, null)
            }
        }

        // Without this the whole app is killed when the WebView's renderer process dies (typical while playing in the
        // background on a phone that is short of memory).
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            AppLog.log("WEB", "Render process gone (" + role + ", crashed=" + detail.didCrash() + ")")
            handler.post {
                try {
                    if (view === playerView) {
                        closePlayer()
                        Toast.makeText(this@MainActivity, R.string.load_error, Toast.LENGTH_SHORT).show()
                    } else if (view === browseView) {
                        recreateBrowse()
                        Toast.makeText(this@MainActivity, R.string.load_error, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Throwable) {
                    AppLog.log("WEB", "recovery after renderer loss failed", e)
                }
            }
            return true
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
