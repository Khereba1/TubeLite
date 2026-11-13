package com.khereba1.app

import android.net.Uri
import java.util.Locale

/** Network-level ad blocking: requests to known ad hosts / ad-ping paths get an empty answer. */
object AdBlock {
    private val blockedHosts = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagservices.com",
        "adservice.google.com",
        "2mdn.net",
    )

    private val blockedPaths = listOf(
        "/pagead/",
        "/api/stats/ads",
        "/ptracking",
        "/pcs/activeview",
        "/get_midroll_info",
    )

    fun shouldBlock(uri: Uri): Boolean {
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        for (h in blockedHosts) {
            if (host == h || host.endsWith(".$h")) return true
        }
        if (host.endsWith("youtube.com") || host.endsWith("google.com")) {
            val path = uri.path ?: return false
            for (p in blockedPaths) {
                if (path.startsWith(p)) return true
            }
        }
        return false
    }
}
