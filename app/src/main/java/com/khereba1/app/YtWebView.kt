package com.khereba1.app

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * WebView used for both the browsing page and the player page.
 * The player one (alwaysVisible = true) tells Chromium its window is always visible,
 * otherwise the page is treated as hidden when the app goes to the background and playback stops.
 */
class YtWebView(context: Context, private val alwaysVisible: Boolean) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(if (alwaysVisible) View.VISIBLE else visibility)
    }
}
