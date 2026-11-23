package com.khereba1.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * The list of all downloads: waiting, running, finished, failed. Tap a row for its actions
 * (cancel, open, share, try again, remove). Data comes from [DownloadStore]; the work is done by [DownloadService].
 */
class DownloadsActivity : Activity() {

    private val bgColor = Color.parseColor("#0F0F0F")
    private val cardColor = Color.parseColor("#1F1F1F")
    private val fieldColor = Color.parseColor("#2C2C2C")
    private val accent = Color.parseColor("#E53935")
    private val muted = Color.parseColor("#B0B0B0")
    private val green = Color.parseColor("#43A047")

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var clearButton: Button
    private var data: List<DownloadStore.Item> = emptyList()

    private val storeListener: () -> Unit = {
        runOnUiThread { if (!isDestroyed) reload() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        return d
    }

    private fun text(value: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = value
        t.textSize = sizeSp
        t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD)
        return t
    }

    private fun smallButton(label: String, filled: Boolean, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.minHeight = dp(44)
        b.stateListAnimator = null
        b.background = roundBg(if (filled) accent else fieldColor, 22)
        b.setOnClickListener { onClick() }
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadStore.init(applicationContext)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bgColor)
        root.setPadding(dp(16), dp(16), dp(16), dp(8))

        root.addView(text(getString(R.string.dl_list_title), 24f, Color.WHITE, true))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        val newBtn = smallButton(getString(R.string.dl_new), true) {
            startActivity(Intent(this, DownloadTestActivity::class.java))
        }
        clearButton = smallButton(getString(R.string.dl_clear_finished), false) { DownloadStore.clearFinished() }
        val lp1 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp1.marginEnd = dp(8)
        bar.addView(newBtn, lp1)
        bar.addView(clearButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val barLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        barLp.topMargin = dp(12)
        barLp.bottomMargin = dp(8)
        root.addView(bar, barLp)

        emptyView = text(getString(R.string.dl_empty), 14f, muted)
        emptyView.setPadding(0, dp(24), 0, 0)
        root.addView(emptyView)

        listView = ListView(this)
        listView.divider = null
        listView.dividerHeight = dp(8)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in data.indices) showActions(data[position])
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        reload()
    }

    override fun onStart() {
        super.onStart()
        DownloadStore.addListener(storeListener)
        reload()
    }

    override fun onStop() {
        DownloadStore.removeListener(storeListener)
        super.onStop()
    }

    private fun reload() {
        data = DownloadStore.all()
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
        clearButton.isEnabled = data.any { it.finished }
        clearButton.alpha = if (clearButton.isEnabled) 1f else 0.4f
    }

    // ------------------------------------------------------------------ rows

    private class Row(
        val box: LinearLayout, val title: TextView, val sub: TextView, val bar: ProgressBar, val status: TextView
    )

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = data.size
        override fun getItem(position: Int): Any = data[position]
        override fun getItemId(position: Int): Long = data[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView?.tag as? Row) ?: makeRow()
            bind(row, data[position])
            return row.box
        }
    }

    private fun makeRow(): Row {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = roundBg(cardColor, 14)
        box.setPadding(dp(14), dp(12), dp(14), dp(12))
        val title = text("", 14f, Color.WHITE, true)
        title.maxLines = 2
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        val sub = text("", 12f, muted)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.progressTintList = ColorStateList.valueOf(accent)
        val status = text("", 12f, Color.WHITE)
        box.addView(title)
        box.addView(sub)
        val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        blp.topMargin = dp(6)
        box.addView(bar, blp)
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.topMargin = dp(2)
        box.addView(status, slp)
        val row = Row(box, title, sub, bar, status)
        box.tag = row
        return row
    }

    private fun bind(row: Row, item: DownloadStore.Item) {
        row.title.text = if (item.title.isNotEmpty()) item.title else item.url
        row.sub.text = item.label
        when (item.state) {
            DownloadStore.RUNNING -> {
                row.bar.visibility = View.VISIBLE
                row.bar.isIndeterminate = item.progress < 0
                if (item.progress >= 0) row.bar.progress = item.progress
                row.status.text = item.message
                row.status.setTextColor(Color.WHITE)
            }
            DownloadStore.QUEUED -> {
                row.bar.visibility = View.GONE
                row.status.text = getString(R.string.dl_state_queued)
                row.status.setTextColor(muted)
            }
            DownloadStore.DONE -> {
                row.bar.visibility = View.GONE
                row.status.text = item.message
                row.status.setTextColor(green)
            }
            else -> {
                row.bar.visibility = View.GONE
                row.status.text = item.message
                row.status.setTextColor(if (item.state == DownloadStore.FAILED) accent else muted)
            }
        }
    }

    // ------------------------------------------------------------------ actions

    private fun showActions(item: DownloadStore.Item) {
        val names = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun add(label: String, action: () -> Unit) {
            names.add(label)
            actions.add(action)
        }
        when (item.state) {
            DownloadStore.RUNNING -> add(getString(R.string.dl_cancel)) {
                startService(Intent(this, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL))
            }
            DownloadStore.QUEUED -> add(getString(R.string.dl_cancel)) {
                // it may have just started: then the running one is stopped instead
                if (!DownloadStore.cancelQueued(item, getString(R.string.dl_cancelled))) {
                    startService(Intent(this, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL))
                }
            }
            DownloadStore.DONE -> {
                add(getString(R.string.dl_act_open)) { openFile(item) }
                add(getString(R.string.dl_act_share)) { shareFile(item) }
                add(getString(R.string.dl_act_remove)) { DownloadStore.remove(item.id) }
            }
            else -> {
                add(getString(R.string.dl_act_retry)) {
                    DownloadStore.retry(item)
                    try {
                        ContextCompat.startForegroundService(
                            this, Intent(this, DownloadService::class.java).setAction(DownloadService.ACTION_START)
                        )
                    } catch (e: Throwable) {
                        AppLog.log("DL", "cannot start the download service", e)
                    }
                }
                add(getString(R.string.dl_act_remove)) { DownloadStore.remove(item.id) }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(if (item.title.isNotEmpty()) item.title else getString(R.string.dl_title))
            .setItems(names.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun openFile(item: DownloadStore.Item) {
        try {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.fileUri), item.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.dl_open_failed)
        } catch (e: Throwable) {
            AppLog.log("DL", "open failed", e)
            toast(R.string.dl_open_failed)
        }
    }

    private fun shareFile(item: DownloadStore.Item) {
        try {
            val send = Intent(Intent.ACTION_SEND).setType(item.mime.ifEmpty { "*/*" })
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(item.fileUri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, null))
        } catch (e: Throwable) {
            AppLog.log("DL", "share failed", e)
            toast(R.string.dl_open_failed)
        }
    }

    private fun toast(res: Int) {
        android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()
    }
}
