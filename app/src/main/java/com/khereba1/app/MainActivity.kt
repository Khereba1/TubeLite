package com.khereba1.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
import java.util.Locale

private const val HOME_URL = "https://m.youtube.com/"
private const val PAUSED_STOP_DELAY_MS = 5 * 60 * 1000L

/** Hosts the WebView may navigate to inside the app. Everything else opens in the normal browser. */
private fun isAllowedHost(host: String?): Boolean {
    if (host == null) return false
    val h = host.lowercase(Locale.ROOT)
    return h == "youtube.com" || h.endsWith(".youtube.com") ||
        h == "google.com" || h.endsWith(".google.com")
}

class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: YtWebView
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopServiceTask = Runnable { stopService(Intent(this, PlaybackService::class.java)) }
    private var isPlaying = false
    private var injectScript = ""
    private var documentStartSupported = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        webView = YtWebView(this)
        webView.setBackgroundColor(Color.BLACK)
        root.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)

        configureWebView()
        installBridgeAndScript()

        PlaybackService.controller = { cmd ->
            runOnUiThread {
                webView.evaluateJavascript("window.__tubeCmd&&window.__tubeCmd('$cmd')", null)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    isPlaying -> moveTaskToBack(true)
                    else -> finish()
                }
            }
        })

        requestNotificationPermission()
        webView.loadUrl(resolveStartUrl(intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        webView.loadUrl(resolveStartUrl(intent))
    }

    override fun onPause() {
        super.onPause()
        // Do NOT call webView.onPause(): it would stop background playback.
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopServiceTask)
        PlaybackService.controller = null
        stopService(Intent(this, PlaybackService::class.java))
        try {
            root.removeView(webView)
            webView.destroy()
        } catch (e: Throwable) {
            AppLog.log("MAIN", "destroy failed", e)
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- setup

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val s = webView.settings
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
        s.userAgentString = WebSettings.getDefaultUserAgent(this)
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
        // Do not send the X-Requested-With header (it contains this app's package name).
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
            }
        } catch (e: Throwable) {
            AppLog.log("MAIN", "X-Requested-With setting failed", e)
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = TubeClient()
        webView.webChromeClient = TubeChrome()
    }

    private fun installBridgeAndScript() {
        val origins = setOf("https://m.youtube.com", "https://www.youtube.com")
        try {
            injectScript = assets.open("inject.js").bufferedReader().use { it.readText() }
        } catch (e: Throwable) {
            AppLog.log("INJECT", "Cannot read inject.js", e)
        }
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                // The only way the page can talk to the app: a text message, from YouTube origins only.
                WebViewCompat.addWebMessageListener(
                    webView, "TubeBridge", origins,
                    WebViewCompat.WebMessageListener { _, message, _, isMainFrame, _ ->
                        if (isMainFrame) onPlayState(message.data ?: "")
                    }
                )
            } else {
                AppLog.log("INJECT", "WEB_MESSAGE_LISTENER not supported: no background notification")
            }
            if (injectScript.isNotEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, injectScript, origins)
                documentStartSupported = true
            } else {
                AppLog.log("INJECT", "DOCUMENT_START_SCRIPT not supported: using late injection")
            }
        } catch (e: Throwable) {
            AppLog.log("INJECT", "Bridge/script setup failed", e)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // ---------------------------------------------------------------- playback state

    private fun onPlayState(msg: String) {
        val playing = msg.startsWith("1|")
        val title = msg.substringAfter('|', "").take(120)
        isPlaying = playing
        handler.removeCallbacks(stopServiceTask)

        val svc = PlaybackService.instance
        if (svc != null) {
            svc.update(playing, title)
        } else if (playing) {
            try {
                val i = Intent(this, PlaybackService::class.java)
                    .putExtra(PlaybackService.EXTRA_PLAYING, true)
                    .putExtra(PlaybackService.EXTRA_TITLE, title)
                ContextCompat.startForegroundService(this, i)
            } catch (e: Throwable) {
                AppLog.log("SERVICE", "Cannot start playback service", e)
            }
        }
        if (!playing) handler.postDelayed(stopServiceTask, PAUSED_STOP_DELAY_MS)
    }

    // ---------------------------------------------------------------- navigation helpers

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
        root.removeView(v)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.show(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback?) {
        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        view.setBackgroundColor(Color.BLACK)
        root.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // ---------------------------------------------------------------- WebView clients

    private inner class TubeClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val uri = request.url
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme == "https" && isAllowedHost(uri.host)) return false
            if (scheme == "https" || scheme == "http") openExternal(uri)
            return true // block everything else (intent:, file:, javascript:, ...)
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
                view.evaluateJavascript(injectScript, null)
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
