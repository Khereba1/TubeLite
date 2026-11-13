package com.khereba1.app

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * WebView that always tells Chromium its window is visible.
 * Without this the page is treated as hidden when the app goes to the background and playback stops.
 */
class YtWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }
}
