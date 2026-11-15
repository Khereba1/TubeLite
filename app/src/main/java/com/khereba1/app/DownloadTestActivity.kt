package com.khereba1.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Phase 0: a plain test screen for the download engine (yt-dlp inside the app).
 * Shows the engine version, can update it, and downloads one video (mp4 with sound) or its audio (m4a).
 */
class DownloadTestActivity : Activity() {

    private lateinit var info: TextView
    private lateinit var urlBox: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var cancelButton: Button
    private val actionButtons = ArrayList<Button>()
    private val logBuffer = StringBuilder()

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
        root.setBackgroundColor(Color.parseColor("#0F0F0F"))
        root.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = "Download engine test (experimental)"
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

        root.addView(row(makeButton("Download video") { startDownload(false) }, makeButton("Audio only") { startDownload(true) }, cancelButton))
        root.addView(row(makeButton("Update (stable)") { startUpdate(false) }, makeButton("Update (nightly)") { startUpdate(true) }))

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
        logScroll = ScrollView(this)
        logScroll.addView(logView)
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
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
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun startEngine() {
        statusView.text = "Starting the engine (the first time can take a while)..."
        Thread {
            val ok = DownloadEngine.init(applicationContext)
            val version = if (ok) DownloadEngine.version(applicationContext) else "-"
            runOnUiThread {
                engineReady = ok
                info.text = if (ok) "Engine: yt-dlp " + version else "Engine failed to start: " + DownloadEngine.initError
                statusView.text = if (ok) "Ready." else ""
                if (!ok) appendLog(DownloadEngine.initError ?: "unknown error")
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
                info.text = "Engine: yt-dlp " + version
                statusView.text = text
                appendLog(text)
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

    private fun startDownload(audioOnly: Boolean) {
        if (busy) return
        val url = urlBox.text.toString().trim()
        if (!DownloadEngine.isYoutubeUrl(url)) {
            statusView.text = "Paste a valid https YouTube link first."
            return
        }
        setBusy(true)
        progressBar.progress = 0
        statusView.text = "Starting..."
        Thread {
            val result = DownloadEngine.download(applicationContext, url, audioOnly) { p, eta, line -> onProgress(p, eta, line) }
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
                if (uri != null) {
                    file.delete()
                    message = "Saved to Downloads/TubeLite: " + file.name + " (" + size + ")"
                } else {
                    message = "Downloaded (" + size + ") but could not copy to Downloads. File: " + file.absolutePath
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
}
