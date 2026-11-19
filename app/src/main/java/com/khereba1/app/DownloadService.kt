package com.khereba1.app

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.IBinder
import android.text.format.Formatter
import java.util.Locale

/**
 * Runs ONE download in the background (foreground service with a progress notification and a Cancel button),
 * so it keeps going when the download screen or the whole app is closed. The screen listens through [listener].
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.khereba1.tubelite.action.DL_START"
        const val ACTION_CANCEL = "com.khereba1.tubelite.action.DL_CANCEL"
        const val EXTRA_URL = "url"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_FORMAT_ID = "format_id"
        const val EXTRA_EXT = "ext"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LABEL = "label"
        const val EXTRA_VIDEO_ID = "video_id"
        private const val CHANNEL_PROGRESS = "downloads"
        private const val CHANNEL_DONE = "downloads_done"
        private const val NOTIF_PROGRESS = 2
        private const val NOTIF_DONE = 3

        @Volatile
        var running = false

        // what is being downloaded (read by the download screen when it is opened from the notification)
        @Volatile
        var currentTitle = ""

        @Volatile
        var currentLabel = ""

        @Volatile
        var currentId = ""

        /** (text, percent or -1, finished). Called from a background thread. */
        @Volatile
        var listener: ((String, Int, Boolean) -> Unit)? = null
    }

    @Volatile
    private var cancelRequested = false
    private var lastNotify = 0L

    // last progress text/percent, so a repeated start request does not reset the notification to "Preparing"
    @Volatile
    private var lastText = ""

    @Volatile
    private var lastPct = -1

    @Volatile
    private var largeIcon: Bitmap? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val i = intent ?: return START_NOT_STICKY
        if (i.action == ACTION_CANCEL) {
            cancelRequested = true
            DownloadEngine.cancel()
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        if (i.action != ACTION_START) return START_NOT_STICKY

        // startForeground must be called right after startForegroundService()
        if (running) {
            startForegroundNow(buildProgress(if (lastText.isNotEmpty()) lastText else getString(R.string.dl_notif_preparing), lastPct))
            return START_NOT_STICKY
        }
        startForegroundNow(buildProgress(getString(R.string.dl_notif_preparing), -1))

        val url = i.getStringExtra(EXTRA_URL) ?: ""
        if (!DownloadEngine.isYoutubeUrl(url)) {
            finishWith(false, getString(R.string.dl_err_generic))
            return START_NOT_STICKY
        }
        val height = i.getIntExtra(EXTRA_HEIGHT, 0)
        val audio = i.getBooleanExtra(EXTRA_AUDIO, false)
        val formatId = i.getStringExtra(EXTRA_FORMAT_ID) ?: ""
        val ext = i.getStringExtra(EXTRA_EXT) ?: ""
        val option = DownloadEngine.Option(height, audio, 0L, if (formatId.isEmpty()) null else formatId, 0, ext)
        currentTitle = i.getStringExtra(EXTRA_TITLE) ?: ""
        currentLabel = i.getStringExtra(EXTRA_LABEL) ?: ""
        currentId = i.getStringExtra(EXTRA_VIDEO_ID) ?: ""
        largeIcon = null

        running = true
        cancelRequested = false
        lastText = ""
        lastPct = -1
        publish(getString(R.string.dl_notif_preparing), -1, false)
        Thread { work(url, option) }.start()
        return START_NOT_STICKY
    }

    private fun squareCrop(b: Bitmap): Bitmap {
        val side = minOf(b.width, b.height)
        return Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
    }

    private fun work(url: String, option: DownloadEngine.Option) {
        var ok = false
        var text = ""
        var savedUri: Uri? = null
        var savedMime = ""
        val t0 = System.currentTimeMillis()
        try {
            if (currentId.isNotEmpty()) {
                val thumb = DownloadEngine.loadThumbnail(currentId)
                if (thumb != null) {
                    largeIcon = squareCrop(thumb)
                    publish(getString(R.string.dl_notif_preparing), -1, false)
                }
            }
            if (!DownloadEngine.init(applicationContext)) {
                AppLog.log("DL", "engine init failed: " + DownloadEngine.initError)
                text = getString(R.string.dl_engine_failed)
            } else {
                val result = DownloadEngine.download(
                    applicationContext, url, option,
                    { p, eta, line -> onProgress(p, eta, line) },
                    { line -> publish(line, -1, false) }
                )
                val file = result.file
                if (result.ok && file != null) {
                    val size = Formatter.formatFileSize(applicationContext, file.length())
                    val uri = try {
                        DownloadEngine.publishToDownloads(applicationContext, file, option.audioOnly)
                    } catch (e: Throwable) {
                        AppLog.log("DL", "copy to Downloads failed", e)
                        null
                    }
                    if (uri != null) {
                        val bytes = file.length()
                        val secs = maxOf(1L, (System.currentTimeMillis() - t0) / 1000)
                        val avg = "Average speed " + Formatter.formatFileSize(applicationContext, bytes / secs) + "/s (" + size + " in " + secs + "s)"
                        AppLog.log("DL", avg)
                        publish(avg, -1, false) // goes to the log on the screen
                        savedUri = uri
                        savedMime = DownloadEngine.mimeFor(file, option.audioOnly)
                        file.delete()
                        ok = true
                        text = getString(R.string.dl_saved, file.name, size)
                    } else {
                        text = getString(R.string.dl_err_copy) + " " + file.absolutePath
                    }
                } else {
                    AppLog.log("DL", "failed: " + result.message)
                    publish(result.message, -1, false) // the raw error goes to the log on the screen
                    text = if (cancelRequested) getString(R.string.dl_cancelled)
                    else getString(DownloadEngine.friendlyErrorRes(result.message))
                }
            }
        } catch (e: Throwable) {
            AppLog.log("DL", "download crashed", e)
            text = getString(R.string.dl_err_generic)
        }
        finishWith(ok, text, savedUri, savedMime)
    }

    private fun formatEta(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    private fun onProgress(p: Float, eta: Long, line: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 700) return // keep the notification light
        lastNotify = now
        val pct = p.toInt().coerceIn(0, 100)
        // yt-dlp's own progress line carries the speed, e.g. "[download]  38.0% of 7.40MiB at 166.00KiB/s ETA 00:40"
        val speed = Regex("at\\s+(\\S+/s)").find(line)?.groupValues?.get(1)
        var text = "" + pct + "%"
        if (speed != null) text += "  \u2022  " + speed
        if (eta > 0L) text += "  \u2022  " + formatEta(eta)
        publish(text, pct, false)
    }

    private fun publish(text: String, pct: Int, finished: Boolean) {
        if (!finished) {
            lastText = text
            lastPct = pct
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIF_PROGRESS, buildProgress(text, pct))
            } catch (e: Throwable) {
                // notifications can be blocked: the download itself goes on
            }
        }
        listener?.invoke(text, pct, finished)
    }

    private fun finishWith(ok: Boolean, text: String, uri: Uri? = null, mime: String = "") {
        running = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            val nm = getSystemService(NotificationManager::class.java)
            ensureChannels(nm)
            // tap = open the downloaded file itself (falls back to the Downloads app)
            val openIntent = if (ok && uri != null) {
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val open = PendingIntent.getActivity(this, 5, openIntent, PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, CHANNEL_DONE)
                .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setColor(0xFFE53935.toInt())
                .setContentTitle(if (currentTitle.isNotEmpty()) currentTitle else getString(if (ok) R.string.dl_notif_done else R.string.dl_failed))
                .setSubText(getString(if (ok) R.string.dl_notif_done else R.string.dl_failed))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_DONE, n)
        } catch (e: Throwable) {
            AppLog.log("DL", "final notification failed", e)
        }
        listener?.invoke(text, if (ok) 100 else -1, true)
        stopSelf()
    }

    private fun startForegroundNow(n: Notification) {
        try {
            startForeground(NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Throwable) {
            AppLog.log("DL", "startForeground failed", e)
        }
    }

    private fun ensureChannels(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_PROGRESS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, getString(R.string.dl_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        if (nm.getNotificationChannel(CHANNEL_DONE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, getString(R.string.dl_channel_done), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun buildProgress(text: String, pct: Int): Notification {
        ensureChannels(getSystemService(NotificationManager::class.java))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val cancel = PendingIntent.getService(
            this, 4, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL), flags
        )
        val open = PendingIntent.getActivity(this, 6, Intent(this, DownloadTestActivity::class.java), flags)
        val builder = Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(0xFFE53935.toInt())
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentTitle(if (currentTitle.isNotEmpty()) currentTitle else getString(R.string.dl_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, if (pct < 0) 0 else pct, pct < 0)
        if (currentLabel.isNotEmpty()) builder.setSubText(currentLabel)
        val icon = largeIcon
        if (icon != null) builder.setLargeIcon(icon)
        return builder
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.dl_cancel), cancel
                ).build()
            )
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
