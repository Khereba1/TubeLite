package com.khereba1.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL

/**
 * The download screen (class name kept from the first test version). Analyzes a YouTube link (automatically when it
 * opens from a video), lists the video and audio qualities, and hands the download to [DownloadService], which keeps
 * working in the background with a notification.
 */
class DownloadTestActivity : Activity() {

    private val bgColor = Color.parseColor("#0F0F0F")
    private val cardColor = Color.parseColor("#1F1F1F")
    private val fieldColor = Color.parseColor("#2C2C2C")
    private val accent = Color.parseColor("#E53935")
    private val muted = Color.parseColor("#B0B0B0")

    private lateinit var engineView: TextView
    private lateinit var urlBox: EditText
    private lateinit var infoCard: LinearLayout
    private lateinit var thumb: ImageView
    private lateinit var videoTitle: TextView
    private lateinit var videoMeta: TextView
    private lateinit var optionsCard: LinearLayout
    private lateinit var optionsBox: LinearLayout
    private lateinit var analyzeButton: Button
    private lateinit var downloadButton: Button
    private lateinit var cancelButton: Button
    private lateinit var updateStableButton: Button
    private lateinit var updateNightlyButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private val logBuffer = StringBuilder()

    private var currentInfo: DownloadEngine.Info? = null
    private var analyzedUrl = ""
    private var analyzedId = ""
    private var radioGroup: RadioGroup? = null

    @Volatile
    private var busy = false // analysis or engine update in progress

    @Volatile
    private var engineReady = false

    @Volatile
    private var analyzing = false
    private var pendingDownload = false

    // shows "Analyzing...  7s" so the wait is visible (an extraction takes a while on a phone)
    private val ticker = Handler(Looper.getMainLooper())
    private var phaseText: String? = null
    private var phaseStart = 0L
    private val tick = object : Runnable {
        override fun run() {
            val text = phaseText ?: return
            statusView.text = text + "  " + ((System.currentTimeMillis() - phaseStart) / 1000) + "s"
            ticker.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------------ small view helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        return d
    }

    private fun label(value: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = value
        t.textSize = sizeSp
        t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD)
        return t
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = roundBg(cardColor, 16)
        c.setPadding(dp(14), dp(14), dp(14), dp(14))
        return c
    }

    private fun addTo(parent: LinearLayout, child: View, topMarginDp: Int = 12) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(topMarginDp)
        parent.addView(child, lp)
    }

    private fun styledButton(text: String, filled: Boolean, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.minHeight = dp(48)
        b.stateListAnimator = null
        b.background = roundBg(if (filled) accent else fieldColor, 24)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun look(b: Button, enabled: Boolean) {
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.4f
    }

    // ------------------------------------------------------------------ screen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(16), dp(16), dp(24))

        root.addView(label(getString(R.string.dl_title), 24f, Color.WHITE, true))
        engineView = label(getString(R.string.dl_status_starting_engine), 12f, muted)
        root.addView(engineView)

        // link
        val linkCard = card()
        urlBox = EditText(this)
        urlBox.hint = getString(R.string.dl_link_hint)
        urlBox.setHintTextColor(Color.GRAY)
        urlBox.setTextColor(Color.WHITE)
        urlBox.isSingleLine = true
        urlBox.background = roundBg(fieldColor, 12)
        urlBox.setPadding(dp(12), dp(10), dp(12), dp(10))
        urlBox.setText(intent.getStringExtra("url") ?: "")
        linkCard.addView(urlBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        analyzeButton = styledButton(getString(R.string.dl_analyze), false) { analyze(false) }
        addTo(linkCard, analyzeButton, 8)
        addTo(root, linkCard)

        // video info
        infoCard = card()
        infoCard.visibility = View.GONE
        thumb = ImageView(this)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.background = roundBg(Color.BLACK, 12)
        thumb.outlineProvider = ViewOutlineProvider.BACKGROUND
        thumb.clipToOutline = true
        infoCard.addView(thumb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)))
        videoTitle = label("", 15f, Color.WHITE, true)
        addTo(infoCard, videoTitle, 10)
        videoMeta = label("", 12f, muted)
        addTo(infoCard, videoMeta, 2)
        addTo(root, infoCard)

        // qualities
        optionsCard = card()
        optionsCard.visibility = View.GONE
        optionsBox = LinearLayout(this)
        optionsBox.orientation = LinearLayout.VERTICAL
        optionsCard.addView(optionsBox)
        addTo(root, optionsCard)

        // actions
        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        downloadButton = styledButton(getString(R.string.dl_download), true) { onDownloadClicked() }
        cancelButton = styledButton(getString(R.string.dl_cancel), false) { cancelDownload() }
        val dlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        dlp.marginEnd = dp(8)
        actions.addView(downloadButton, dlp)
        actions.addView(cancelButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addTo(root, actions)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.progressTintList = ColorStateList.valueOf(accent)
        addTo(root, progressBar, 10)
        statusView = label("", 13f, Color.WHITE)
        addTo(root, statusView, 4)

        // engine
        val engineCard = card()
        engineCard.addView(label(getString(R.string.dl_engine_title), 14f, Color.WHITE, true))
        val engineRow = LinearLayout(this)
        engineRow.orientation = LinearLayout.HORIZONTAL
        updateStableButton = styledButton(getString(R.string.dl_update_stable), false) { startUpdate(false) }
        updateNightlyButton = styledButton(getString(R.string.dl_update_nightly), false) { startUpdate(true) }
        val srow = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        srow.marginEnd = dp(8)
        engineRow.addView(updateStableButton, srow)
        engineRow.addView(updateNightlyButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addTo(engineCard, engineRow, 8)
        val details = label(getString(R.string.dl_details), 13f, accent)
        details.setOnClickListener {
            logView.visibility = if (logView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        addTo(engineCard, details, 10)
        logView = label("", 11f, muted)
        logView.setTextIsSelectable(true)
        logView.visibility = View.GONE
        addTo(engineCard, logView, 6)
        addTo(root, engineCard)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bgColor)
        scroll.addView(root)
        setContentView(scroll)

        refreshButtons()
        startEngine()
    }

    override fun onStart() {
        super.onStart()
        DownloadService.listener = { text, pct, finished ->
            runOnUiThread {
                if (pct >= 0) progressBar.progress = pct
                statusView.text = text
                if (pct < 0 || finished) appendLog(text)
                refreshButtons()
            }
        }
        if (DownloadService.running) statusView.text = getString(R.string.dl_downloading)
        refreshButtons()
    }

    override fun onStop() {
        DownloadService.listener = null
        super.onStop()
    }

    override fun onDestroy() {
        ticker.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun refreshButtons() {
        val downloading = DownloadService.running
        look(downloadButton, engineReady && !downloading && (!busy || analyzing))
        look(analyzeButton, engineReady && !busy && !downloading)
        look(updateStableButton, engineReady && !busy && !downloading)
        look(updateNightlyButton, engineReady && !busy && !downloading)
        look(cancelButton, downloading)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        refreshButtons()
    }

    private fun startPhase(text: String) {
        ticker.removeCallbacks(tick)
        phaseText = text
        phaseStart = System.currentTimeMillis()
        statusView.text = text
        ticker.postDelayed(tick, 1000)
    }

    private fun endPhase() {
        phaseText = null
        ticker.removeCallbacks(tick)
    }

    private fun appendLog(line: String) {
        if (line.isBlank()) return
        logBuffer.append(line.trim()).append('\n')
        if (logBuffer.length > 8000) logBuffer.delete(0, logBuffer.length - 6000)
        logView.text = logBuffer.toString()
    }

    private fun uiLog(line: String) {
        runOnUiThread { appendLog(line) }
    }

    private fun engineText(version: String): String {
        val ffmpeg = getString(if (DownloadEngine.ffmpegReady) R.string.dl_ffmpeg_ok else R.string.dl_ffmpeg_missing)
        return getString(R.string.dl_engine_info, version, ffmpeg)
    }

    private fun currentUrl(): String = urlBox.text.toString().trim()

    // ------------------------------------------------------------------ engine

    private fun startEngine() {
        setBusy(true)
        statusView.text = getString(R.string.dl_status_starting_engine)
        Thread {
            val ok = DownloadEngine.init(applicationContext)
            val version = if (ok) DownloadEngine.version(applicationContext) else "-"
            runOnUiThread {
                engineReady = ok
                engineView.text = if (ok) engineText(version) else getString(R.string.dl_engine_failed) + ": " + DownloadEngine.initError
                statusView.text = if (ok) getString(R.string.dl_status_ready) else ""
                if (!ok) appendLog(DownloadEngine.initError ?: "unknown error")
                if (ok && !DownloadEngine.ffmpegReady) appendLog("ffmpeg: " + (DownloadEngine.ffmpegError ?: "unknown"))
                setBusy(false)
                // Opening this screen from a video starts its analysis right away (one extraction; cached for 30 minutes).
                if (ok && DownloadEngine.isYoutubeUrl(currentUrl())) analyze(false)
            }
        }.start()
    }

    private fun startUpdate(nightly: Boolean) {
        if (busy) return
        setBusy(true)
        startPhase(getString(R.string.dl_status_updating))
        Thread {
            val text = try {
                "Update: " + DownloadEngine.update(applicationContext, nightly)
            } catch (e: Throwable) {
                AppLog.log("ENGINE", "update failed", e)
                "Update failed: " + (e.message ?: e.toString())
            }
            val version = DownloadEngine.version(applicationContext)
            runOnUiThread {
                endPhase()
                engineView.text = engineText(version)
                statusView.text = text
                appendLog(text)
                setBusy(false)
            }
        }.start()
    }

    private fun cancelDownload() {
        startService(Intent(this, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL))
    }

    // ------------------------------------------------------------------ analysis

    private fun analyze(thenDownload: Boolean) {
        if (busy) return
        val url = currentUrl()
        if (!DownloadEngine.isYoutubeUrl(url)) {
            statusView.text = getString(R.string.dl_status_paste)
            return
        }
        setBusy(true)
        analyzing = true
        progressBar.progress = 0
        startPhase(getString(R.string.dl_status_analyzing))
        val t0 = System.currentTimeMillis()
        Thread {
            try {
                val result = DownloadEngine.analyze(applicationContext, url) { line -> uiLog(line) }
                val secs = (System.currentTimeMillis() - t0) / 1000
                runOnUiThread {
                    endPhase()
                    showInfo(url, result)
                    appendLog("Analysis took " + secs + "s")
                    analyzing = false
                    setBusy(false)
                    val go = thenDownload || pendingDownload
                    pendingDownload = false
                    if (go) startDownloadSelected()
                }
            } catch (e: Throwable) {
                AppLog.log("ENGINE", "analyze failed", e)
                val raw = e.message ?: e.toString()
                runOnUiThread {
                    endPhase()
                    statusView.text = getString(DownloadEngine.friendlyErrorRes(raw))
                    appendLog(raw)
                    analyzing = false
                    pendingDownload = false
                    setBusy(false)
                }
            }
        }.start()
    }

    private fun preferredHeight(): Int {
        val q = getSharedPreferences("tubelite_prefs", Context.MODE_PRIVATE).getString("quality", "auto") ?: "auto"
        return when (q) {
            "tiny" -> 144
            "small" -> 240
            "medium" -> 360
            "large" -> 480
            "hd720" -> 720
            else -> 1080 // auto and 1080p
        }
    }

    private fun optionLabel(o: DownloadEngine.Option): String {
        val name = when {
            o.audioOnly && o.abr > 0 -> getString(R.string.dl_audio_label, o.abr, o.ext)
            o.audioOnly -> getString(R.string.dl_audio_best)
            o.height <= 0 -> getString(R.string.dl_single_file)
            else -> "" + o.height + "p"
        }
        return if (o.bytes > 0L) name + "   ~" + Formatter.formatFileSize(this, o.bytes) else name
    }

    private fun formatDuration(sec: Long): String {
        if (sec <= 0L) return ""
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun showInfo(url: String, result: DownloadEngine.Info) {
        currentInfo = result
        analyzedUrl = url
        analyzedId = result.id
        videoTitle.text = result.title
        videoMeta.text = formatDuration(result.durationSec)
        infoCard.visibility = View.VISIBLE
        thumb.setImageDrawable(null)
        loadThumb(result.id)

        // default: the highest video quality that is not above the quality chosen in the app's settings
        val wanted = preferredHeight()
        val videoIdx = result.options.indices.filter { !result.options[it].audioOnly }
        var chosen = 0
        if (videoIdx.isNotEmpty()) {
            val within = videoIdx.filter { result.options[it].height <= wanted }
            chosen = if (within.isNotEmpty()) within.first() else videoIdx.last()
        }

        optionsBox.removeAllViews()
        val group = RadioGroup(this)
        group.addView(label(getString(R.string.dl_section_video), 13f, muted, true))
        for (i in result.options.indices) {
            if (!result.options[i].audioOnly) group.addView(radio(i, result.options[i]))
        }
        val audioIdx = result.options.indices.filter { result.options[it].audioOnly }
        if (audioIdx.isNotEmpty()) {
            val header = label(getString(R.string.dl_section_audio), 13f, muted, true)
            header.setPadding(0, dp(10), 0, 0)
            group.addView(header)
            for (i in audioIdx) group.addView(radio(i, result.options[i]))
        }
        group.check(100 + chosen)
        radioGroup = group
        optionsBox.addView(group)
        optionsCard.visibility = View.VISIBLE
        statusView.text = getString(R.string.dl_status_choose)
    }

    private fun radio(index: Int, o: DownloadEngine.Option): RadioButton {
        val rb = RadioButton(this)
        rb.id = 100 + index
        rb.text = optionLabel(o)
        rb.setTextColor(Color.WHITE)
        rb.buttonTintList = ColorStateList.valueOf(accent)
        return rb
    }

    /** Public thumbnail of the video (fixed host, no cookies, no redirects). */
    private fun loadThumb(id: String) {
        if (!Regex("^[A-Za-z0-9_-]{11}$").matches(id)) return
        Thread {
            try {
                val conn = URL("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg").openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = false
                val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                conn.disconnect()
                if (bmp != null) {
                    runOnUiThread { if (!isDestroyed && analyzedId == id) thumb.setImageBitmap(bmp) }
                }
            } catch (e: Throwable) {
                AppLog.log("ENGINE", "thumbnail failed", e)
            }
        }.start()
    }

    // ------------------------------------------------------------------ download (runs in DownloadService)

    private fun onDownloadClicked() {
        if (DownloadService.running) {
            statusView.text = getString(R.string.dl_in_progress)
            return
        }
        if (busy) {
            if (analyzing) {
                pendingDownload = true
                statusView.text = getString(R.string.dl_queued)
            }
            return
        }
        val url = currentUrl()
        if (currentInfo != null && url == analyzedUrl) startDownloadSelected() else analyze(true)
    }

    private fun startDownloadSelected() {
        val parsed = currentInfo ?: return
        val group = radioGroup ?: return
        val index = group.checkedRadioButtonId - 100
        if (index < 0 || index >= parsed.options.size) {
            statusView.text = getString(R.string.dl_status_choose)
            return
        }
        startDownload(analyzedUrl, parsed.options[index])
    }

    private fun startDownload(url: String, option: DownloadEngine.Option) {
        val i = Intent(this, DownloadService::class.java)
            .setAction(DownloadService.ACTION_START)
            .putExtra(DownloadService.EXTRA_URL, url)
            .putExtra(DownloadService.EXTRA_HEIGHT, option.height)
            .putExtra(DownloadService.EXTRA_AUDIO, option.audioOnly)
            .putExtra(DownloadService.EXTRA_FORMAT_ID, option.formatId ?: "")
        try {
            progressBar.progress = 0
            statusView.text = getString(R.string.dl_notif_preparing)
            ContextCompat.startForegroundService(this, i)
        } catch (e: Throwable) {
            AppLog.log("DL", "cannot start the download service", e)
            statusView.text = getString(R.string.dl_err_generic)
        }
    }
}
