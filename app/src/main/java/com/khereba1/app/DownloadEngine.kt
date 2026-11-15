package com.khereba1.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.Locale

/**
 * Thin wrapper around youtubedl-android (yt-dlp). Everything here BLOCKS: call it from a background thread.
 * Security rules: no cookies are ever passed to yt-dlp; the engine is updated only on the user's request
 * (or once after a failed download, in a later version), and only through the library's official update.
 */
object DownloadEngine {
    const val PROCESS_ID = "tubelite_download"

    @Volatile
    private var ready = false

    @Volatile
    var initError: String? = null

    class Result(val ok: Boolean, val message: String, val file: File? = null)

    /** Only YouTube video links are accepted (also keeps anything that looks like an option out of yt-dlp). */
    fun isYoutubeUrl(url: String): Boolean {
        val u = Uri.parse(url)
        if (u.scheme?.lowercase(Locale.ROOT) != "https") return false
        val h = u.host?.lowercase(Locale.ROOT) ?: return false
        return h == "youtu.be" || h == "youtube.com" || h.endsWith(".youtube.com")
    }

    @Synchronized
    fun init(context: Context): Boolean {
        if (ready) return true
        return try {
            YoutubeDL.getInstance().init(context.applicationContext)
            ready = true
            initError = null
            true
        } catch (e: Throwable) {
            initError = e.toString()
            AppLog.log("ENGINE", "init failed", e)
            false
        }
    }

    fun version(context: Context): String {
        return try {
            YoutubeDL.getInstance().versionName(context.applicationContext) ?: "?"
        } catch (e: Throwable) {
            "?"
        }
    }

    /** Updates yt-dlp through the library. Returns a short status text. */
    fun update(context: Context, nightly: Boolean): String {
        val channel = if (nightly) YoutubeDL.UpdateChannel._NIGHTLY else YoutubeDL.UpdateChannel._STABLE
        val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, channel)
        return status?.toString() ?: "?"
    }

    fun cancel() {
        try {
            YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        } catch (e: Throwable) {
            AppLog.log("ENGINE", "cancel failed", e)
        }
    }

    /**
     * Downloads into the app's own folder (no permission needed). Phase 0 uses single-file formats only, so ffmpeg
     * is not needed yet (video+audio in one mp4, or audio-only m4a).
     */
    fun download(context: Context, url: String, audioOnly: Boolean, onProgress: (Float, Long, String) -> Unit): Result {
        val app = context.applicationContext
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "downloads")
        dir.mkdirs()
        val before = (dir.listFiles() ?: emptyArray()).map { it.absolutePath }.toSet()

        val request = YoutubeDLRequest(url)
        request.addOption("--no-mtime")
        request.addOption("--no-playlist")
        request.addOption("-f", if (audioOnly) "ba[ext=m4a]/ba" else "b[ext=mp4]/b")
        request.addOption("-o", dir.absolutePath + "/%(title).80s [%(id)s].%(ext)s")

        try {
            YoutubeDL.getInstance().execute(request, PROCESS_ID) { progress, eta, line ->
                onProgress(progress, eta, line)
            }
        } catch (e: Throwable) {
            AppLog.log("ENGINE", "download failed", e)
            return Result(false, e.message ?: e.toString())
        }

        val newest = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.absolutePath !in before && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            .maxByOrNull { it.lastModified() }
        return if (newest != null) {
            Result(true, "Downloaded: " + newest.name, newest)
        } else {
            Result(false, "yt-dlp finished but no new file was found in " + dir.absolutePath)
        }
    }

    /** Copies the file to Downloads/TubeLite (MediaStore, no permission needed). Returns the new Uri or null. */
    fun publishToDownloads(context: Context, file: File): Uri? {
        val resolver = context.applicationContext.contentResolver
        val mime = when (file.extension.lowercase(Locale.ROOT)) {
            "mp4" -> "video/mp4"
            "m4a" -> "audio/mp4"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
        val values = ContentValues()
        values.put(MediaStore.Downloads.DISPLAY_NAME, file.name)
        values.put(MediaStore.Downloads.MIME_TYPE, mime)
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TubeLite")
        values.put(MediaStore.Downloads.IS_PENDING, 1)
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val out = resolver.openOutputStream(uri)
        if (out == null) {
            resolver.delete(uri, null, null)
            return null
        }
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
        val done = ContentValues()
        done.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, done, null, null)
        return uri
    }
}
