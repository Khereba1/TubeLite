package com.khereba1.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/**
 * Download screen (phase 0/1, plain views): analyze a YouTube link, choose a quality, download (video+audio merged
 * with ffmpeg, or audio only), update the engine by hand. The default quality comes from the app's settings.
 */
class DownloadTestActivity : Activity() {

    private lateinit var info: TextView
    private lateinit var urlBox: EditText
    private lateinit var videoTitle: TextView
    private lateinit var optionsBox: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var cancelButton: Button
    private val actionButtons = ArrayList<Button>()
    private val logBuffer = StringBuilder()

    private var currentInfo: DownloadEngine.Info? = null
    private var analyzedUrl = ""
    private var radioGroup: RadioGroup? = null

    @Volatile
    private var busy = false

    @Volatile
    private var engineReady = false
    private var lastUiUpdate = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "Download engine (experimental)"
        title.setTextColor(Color.WHITE)
        title.textSize = 18f
        root.addView(title)

        info = TextView(this)
        info.setTextColor(Color.LTGRAY)
        info.textSize = 13f
        info.text = "Engine: starting..."
        root.addView(info)

        urlBox = EditText(this)
        urlBox.hint = "YouTube video link"
        urlBox.setHintTextColor(Color.GRAY)
        urlBox.setTextColor(Color.WHITE)
        urlBox.isSingleLine = true
        urlBox.setText(intent.getStringExtra("url") ?: "")
        root.addView(urlBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        cancelButton = makeButton("Cancel") { DownloadEngine.cancel() }
        cancelButton.isEnabled = false
        actionButtons.remove(cancelButton)

        root.addView(row(makeButton("Analyze") { analyze(false) }, makeButton("Download") { onDownloadClicked() }, cancelButton))
        root.addView(row(makeButton("Update (stable)") { startUpdate(false) }, makeButton("Update (nightly)") { startUpdate(true) }))

        videoTitle = TextView(this)
        videoTitle.setTextColor(Color.WHITE)
        videoTitle.textSize = 15f
        videoTitle.visibility = View.GONE
        root.addView(videoTitle)

        optionsBox = LinearLayout(this)
        optionsBox.orientation = LinearLayout.VERTICAL
        root.addView(optionsBox)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        statusView = TextView(this)
        statusView.setTextColor(Color.WHITE)
        statusView.textSize = 14f
        root.addView(statusView)

        logView = TextView(this)
        logView.setTextColor(Color.LTGRAY)
        logView.textSize = 11f
        logView.setTextIsSelectable(true)
        root.addView(logView)

        scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#0F0F0F"))
        scroll.addView(root)
        setContentView(scroll)
        setBusy(true)
        startEngine()
    }

    private fun makeButton(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        actionButtons.add(b)
        return b
    }

    private fun row(vararg buttons: Button): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (b in buttons) {
            r.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return r
    }

    private fun setBusy(value: Boolean) {
        busy = value
        for (b in actionButtons) b.isEnabled = !value && engineReady
        cancelButton.isEnabled = value && engineReady
    }

    private fun appendLog(line: String) {
        if (line.isBlank()) return
        logBuffer.append(line.trim()).append('\n')
        if (logBuffer.length > 8000) logBuffer.delete(0, logBuffer.length - 6000)
        logView.text = logBuffer.toString()
    }

    private fun uiLog(line: String) {
        runOnUiThread {
            appendLog(line)
            statusView.text = line
        }
    }

    private fun engineText(version: String): String {
        val ffmpeg = if (DownloadEngine.ffmpegReady) "ffmpeg ready" else "ffmpeg NOT available (only single-file downloads)"
        return "Engine: yt-dlp " + version + "  |  " + ffmpeg
    }

    private fun startEngine() {
        statusView.text = "Starting the engine (the first time can take a while)..."
        Thread {
            val ok = DownloadEngine.init(applicationContext)
            val version = if (ok) DownloadEngine.version(applicationContext) else "-"
            runOnUiThread {
                engineReady = ok
                info.text = if (ok) engineText(version) else "Engine failed to start: " + DownloadEngine.initError
                statusView.text = if (ok) "Ready." else ""
                if (!ok) appendLog(DownloadEngine.initError ?: "unknown error")
                if (ok && !DownloadEngine.ffmpegReady) appendLog("ffmpeg: " + (DownloadEngine.ffmpegError ?: "unknown"))
                setBusy(false)
            }
        }.start()
    }

    private fun startUpdate(nightly: Boolean) {
        if (busy) return
        setBusy(true)
        statusView.text = "Updating the engine (" + (if (nightly) "nightly" else "stable") + ")..."
        Thread {
            val text = try {
                "Update: " + DownloadEngine.update(applicationContext, nightly)
            } catch (e: Throwable) {
                AppLog.log("ENGINE", "update failed", e)
                "Update failed: " + (e.message ?: e.toString())
            }
            val version = DownloadEngine.version(applicationContext)
            runOnUiThread {
                info.text = engineText(version)
                statusView.text = text
                appendLog(text)
                setBusy(false)
            }
        }.start()
    }

    // ------------------------------------------------------------------ analyze

    private fun currentUrl(): String = urlBox.text.toString().trim()

    private fun analyze(thenDownload: Boolean) {
        if (busy) return
        val url = currentUrl()
        if (!DownloadEngine.isYoutubeUrl(url)) {
            statusView.text = "Paste a valid https YouTube link first."
            return
        }
        setBusy(true)
        progressBar.progress = 0
        statusView.text = "Analyzing the video..."
        Thread {
            try {
                val result = DownloadEngine.analyze(applicationContext, url) { line -> uiLog(line) }
                runOnUiThread {
                    showInfo(url, result)
                    setBusy(false)
                    if (thenDownload) startDownloadSelected()
                }
            } catch (e: Throwable) {
                AppLog.log("ENGINE", "analyze failed", e)
                val message = "Analysis failed: " + (e.message ?: e.toString())
                runOnUiThread {
                    statusView.text = message
                    appendLog(message)
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
            o.audioOnly -> "Audio only (m4a)"
            o.height <= 0 -> "Best single file (no merging)"
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
        val dur = formatDuration(result.durationSec)
        videoTitle.text = result.title + (if (dur.isNotEmpty()) "  (" + dur + ")" else "")
        videoTitle.visibility = View.VISIBLE

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
        for (i in result.options.indices) {
            val rb = RadioButton(this)
            rb.id = 100 + i
            rb.text = optionLabel(result.options[i])
            rb.setTextColor(Color.WHITE)
            group.addView(rb)
        }
        group.check(100 + chosen)
        radioGroup = group
        optionsBox.addView(group)
        statusView.text = "Choose a quality and press Download."
    }

    // ------------------------------------------------------------------ download

    private fun onDownloadClicked() {
        if (busy) return
        val url = currentUrl()
        if (currentInfo != null && url == analyzedUrl) startDownloadSelected() else analyze(true)
    }

    private fun startDownloadSelected() {
        if (busy) return
        val parsed = currentInfo ?: return
        val group = radioGroup ?: return
        val index = group.checkedRadioButtonId - 100
        if (index < 0 || index >= parsed.options.size) {
            statusView.text = "Choose a quality first."
            return
        }
        val option = parsed.options[index]
        val url = analyzedUrl
        setBusy(true)
        progressBar.progress = 0
        statusView.text = "Starting..."
        Thread {
            val result = DownloadEngine.download(
                applicationContext, url, option, { p, eta, line -> onProgress(p, eta, line) }, { line -> uiLog(line) }
            )
            var message = result.message
            val file = result.file
            if (result.ok && file != null) {
                val size = Formatter.formatFileSize(applicationContext, file.length())
                val uri = try {
                    DownloadEngine.publishToDownloads(applicationContext, file)
                } catch (e: Throwable) {
                    AppLog.log("ENGINE", "copy to Downloads failed", e)
                    null
                }
                message = if (uri != null) {
                    file.delete()
                    "Saved to Downloads/TubeLite: " + file.name + " (" + size + ")"
                } else {
                    "Downloaded (" + size + ") but could not copy to Downloads. File: " + file.absolutePath
                }
            }
            runOnUiThread {
                if (result.ok) progressBar.progress = 100
                statusView.text = message
                appendLog(message)
                setBusy(false)
            }
        }.start()
    }

    private fun onProgress(progress: Float, eta: Long, line: String) {
        val now = System.currentTimeMillis()
        if (now - lastUiUpdate < 250) return // keep the screen light
        lastUiUpdate = now
        runOnUiThread {
            val pct = progress.toInt().coerceIn(0, 100)
            progressBar.progress = pct
            statusView.text = "" + pct + "%   (ETA " + eta + "s)"
            appendLog(line)
        }
    }
}
