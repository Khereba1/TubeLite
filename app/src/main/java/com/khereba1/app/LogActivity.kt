package com.khereba1.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** Shows the in-app error log. Opened from the launcher-icon long-press shortcut. */
class LogActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = AppLog.read()
        val tv = TextView(this)
        tv.text = if (text.isEmpty()) getString(R.string.log_empty) else text
        tv.setTextIsSelectable(true)
        tv.setTextColor(Color.WHITE)
        tv.textSize = 12f
        tv.setPadding(32, 32, 32, 32)
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor("#0F0F0F"))
        sv.addView(tv)
        setContentView(sv)
    }
}
