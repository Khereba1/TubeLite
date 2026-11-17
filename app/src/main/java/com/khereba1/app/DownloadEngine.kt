package com.khereba1.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.TreeMap

/**
 * Thin wrapper around youtubedl-android (yt-dlp + ffmpeg). Everything here BLOCKS: call it from a background thread.
 * Security rules: no cookies are ever passed to yt-dlp; the engine is updated only when the user asks, or ONCE per app
 * session automatically after a failure that an update can fix, and only through the library's official update.
 */
object DownloadEngine {
    const val PROCESS_ID = "tubelite_download"

    @Volatile
    private var ready = false

    @Volatile
    var initError: String? = null

    @Volatile
    var ffmpegReady = false

    @Volatile
    var ffmpegError: String? = null

    @Volatile
    private var autoUpdated = false

    /**
     * height > 0: video of that height (+ best audio, merged). height <= 0 and not audioOnly: best single file.
     * audioOnly: a specific audio stream ([formatId], [abr] kbps, [ext]) or, without formatId, the best m4a/audio.
     */
    class Option(
        val height: Int, val audioOnly: Boolean, val bytes: Long,
        val formatId: String? = null, val abr: Int = 0, val ext: String = ""
    )

    class Info(val id: String, val title: String, val durationSec: Long, val options: List<Option>)

    // Thumbnail of the last analyzed / downloaded video (shared by the screen and the notification).
    @Volatile
    var thumbId = ""

    @Volatile
    var thumbBitmap: Bitmap? = null

    /** Blocking. Public thumbnail of the video: fixed host, no cookies, no redirects. */
    fun loadThumbnail(id: String): Bitmap? {
        if (!Regex("^[A-Za-z0-9_-]{11}$").matches(id)) return null
        if (id == thumbId) {
            val cached = thumbBitmap
            if (cached != null) return cached
        }
        return try {
            val conn = URL("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = false
            val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
            conn.disconnect()
            if (bmp != null) {
                thumbId = id
                thumbBitmap = bmp
            }
            bmp
        } catch (e: Throwable) {
            AppLog.log("ENGINE", "thumbnail failed", e)
            null
        }
    }

    fun mimeFor(file: File, audio: Boolean): String {
        return when (file.extension.lowercase(Locale.ROOT)) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "m4a" -> "audio/mp4"
            "webm" -> if (audio) "audio/webm" else "video/webm"
            else -> "application/octet-stream"
        }
    }

    private class AudioFmt(val id: String, val ext: String, val abr: Int, val bytes: Long)

    class Result(val ok: Boolean, val message: String, val file: File? = null)

    // The analysis of a video is kept in memory for a while, so opening the download screen again is instant
    // and YouTube is not asked twice for the same video.
    private class Cached(val time: Long, val info: Info)

    private val cache = HashMap<String, Cached>()
    private const val CACHE_MS = 30 * 60 * 1000L

    private fun videoKey(url: String): String {
        val u = Uri.parse(url)
        val host = u.host?.lowercase(Locale.ROOT) ?: return url
        if (host == "youtu.be") return u.lastPathSegment ?: url
        return u.getQueryParameter("v") ?: url
    }

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
            val app = context.applicationContext
            YoutubeDL.getInstance().init(app)
            ready = true
            initError = null
            initFfmpeg(app)
            true
        } catch (e: Throwable) {
            initError = e.toString()
            AppLog.log("ENGINE", "init failed", e)
            false
        }
    }

    /** ffmpeg lives in an optional module; it is initialised by reflection so a wrong name can only disable merging. */
    private fun initFfmpeg(app: Context) {
        try {
            val cls = Class.forName("com.yausername.ffmpeg.FFmpeg")
            val instance = try {
                cls.getMethod("getInstance").invoke(null)
            } catch (e: Throwable) {
                cls.getField("INSTANCE").get(null)
            }
            cls.getMethod("init", Context::class.java).invoke(instance, app)
            ffmpegReady = true
            ffmpegError = null
        } catch (e: Throwable) {
            ffmpegReady = false
            ffmpegError = (e.cause ?: e).toString()
            AppLog.log("ENGINE", "ffmpeg init failed", e)
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
        synchronized(cache) { cache.clear() } // a new engine may see other formats
        return status?.toString() ?: "?"
    }

    fun cancel() {
        try {
            YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        } catch (e: Throwable) {
            AppLog.log("ENGINE", "cancel failed", e)
        }
    }

    // ------------------------------------------------------------------ auto update after a failure

    private fun fixableByUpdate(message: String): Boolean {
        val m = message.lowercase(Locale.ROOT)
        val notFixable = listOf(
            "sign in to confirm", "not a bot", "private video", "video unavailable", "members-only", "join this channel",
            "network is unreachable", "temporary failure in name resolution", "no space left", "cancel", "interrupted",
            "age-restricted", "confirm your age"
        )
        return notFixable.none { m.contains(it) }
    }

    /** Runs [block]; if it fails with something an update can fix, updates the engine ONCE per app session and retries. */
    private fun <T> withAutoUpdate(context: Context, log: (String) -> Unit, block: () -> T): T {
        try {
            return block()
        } catch (e: Throwable) {
            val message = e.message ?: e.toString()
            if (autoUpdated || !fixableByUpdate(message)) throw e
            autoUpdated = true
            log("Failed. Updating the engine once, then trying again...")
            try {
                update(context, false)
            } catch (u: Throwable) {
                AppLog.log("ENGINE", "auto update failed", u)
                log("Engine update failed: " + (u.message ?: u.toString()))
                throw e
            }
            log("Engine updated to " + version(context))
            return block()
        }
    }

    /** Maps a raw yt-dlp error to a short explanation the user can read (string resource). */
    fun friendlyErrorRes(message: String): Int {
        val m = message.lowercase(Locale.ROOT)
        return when {
            m.contains("sign in to confirm") || m.contains("not a bot") -> R.string.dl_err_bot
            m.contains("private video") || m.contains("members-only") || m.contains("join this channel") -> R.string.dl_err_private
            m.contains("age-restricted") || m.contains("confirm your age") -> R.string.dl_err_age
            m.contains("no space left") -> R.string.dl_err_space
            m.contains("network is unreachable") || m.contains("name resolution") || m.contains("failed to resolve") ||
                m.contains("timed out") -> R.string.dl_err_network
            m.contains("requested format") -> R.string.dl_err_generic
            m.contains("video unavailable") || m.contains("this video is not available") -> R.string.dl_err_unavailable
            else -> R.string.dl_err_generic
        }
    }

    // ------------------------------------------------------------------ analysis

    fun analyze(context: Context, url: String, log: (String) -> Unit): Info {
        val key = videoKey(url)
        synchronized(cache) {
            val c = cache[key]
            if (c != null && System.currentTimeMillis() - c.time < CACHE_MS) return c.info
        }
        val info = withAutoUpdate(context, log) { analyzeOnce(url) }
        synchronized(cache) { cache[key] = Cached(System.currentTimeMillis(), info) }
        return info
    }

    private fun analyzeOnce(url: String): Info {
        val request = YoutubeDLRequest(url)
        request.addOption("--dump-single-json")
        request.addOption("--no-playlist")
        request.addOption("--no-warnings")
        val response = YoutubeDL.getInstance().execute(request, PROCESS_ID) { _, _, _ -> }
        val text = response.out ?: ""
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw IllegalStateException("yt-dlp returned no video information")
        return parseInfo(JSONObject(text.substring(start, end + 1)))
    }

    private fun parseInfo(obj: JSONObject): Info {
        val videoId = obj.optString("id", "")
        val title = obj.optString("title", "")
        val duration = obj.optLong("duration", 0L)
        val mp4Size = TreeMap<Int, Long>()
        val anySize = TreeMap<Int, Long>()
        var audioBytes = 0L
        val audioFmts = LinkedHashMap<String, AudioFmt>() // one entry per (container, bitrate)
        val formats = obj.optJSONArray("formats")
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val vcodec = f.optString("vcodec", "none")
                val acodec = f.optString("acodec", "none")
                val hasVideo = vcodec.isNotEmpty() && vcodec != "none" && vcodec != "null"
                val hasAudio = acodec.isNotEmpty() && acodec != "none" && acodec != "null"
                val height = f.optInt("height", 0)
                var size = f.optLong("filesize", 0L)
                if (size <= 0L) size = f.optLong("filesize_approx", 0L)
                val ext = f.optString("ext", "")
                if (hasVideo && height > 0) {
                    anySize[height] = maxOf(anySize[height] ?: 0L, size)
                    if (ext == "mp4") mp4Size[height] = maxOf(mp4Size[height] ?: 0L, size)
                } else if (hasAudio && !hasVideo && (ext == "m4a" || ext == "webm")) {
                    if (ext == "m4a") audioBytes = maxOf(audioBytes, size)
                    val fid = f.optString("format_id", "")
                    val abr = f.optDouble("abr", 0.0).toInt()
                    val key = ext + "-" + abr
                    if (fid.isNotEmpty() && fid != "null" && !audioFmts.containsKey(key)) {
                        audioFmts[key] = AudioFmt(fid, ext, abr, size)
                    }
                }
            }
        }
        val options = ArrayList<Option>()
        if (ffmpegReady && anySize.isNotEmpty()) {
            for (h in anySize.descendingKeySet()) {
                val v = mp4Size[h] ?: anySize[h] ?: 0L
                options.add(Option(h, false, if (v > 0L) v + audioBytes else 0L))
            }
        } else {
            options.add(Option(0, false, 0L)) // without ffmpeg only a ready-made single file can be used
        }
        // every audio stream YouTube offers, best bitrate first (m4a before webm/opus at the same bitrate)
        val audioSorted = audioFmts.values.sortedWith(compareByDescending<AudioFmt> { it.abr }.thenBy { if (it.ext == "m4a") 0 else 1 })
        if (audioSorted.isEmpty()) {
            options.add(Option(0, true, audioBytes))
        } else {
            for (a in audioSorted.take(6)) options.add(Option(0, true, a.bytes, a.id, a.abr, a.ext))
        }
        return Info(videoId, title, duration, options)
    }

    // ------------------------------------------------------------------ download

    fun download(
        context: Context, url: String, option: Option,
        onProgress: (Float, Long, String) -> Unit, log: (String) -> Unit
    ): Result {
        return try {
            withAutoUpdate(context, log) { downloadOnce(context, url, option, onProgress) }
        } catch (e: Throwable) {
            AppLog.log("ENGINE", "download failed", e)
            Result(false, e.message ?: e.toString())
        }
    }

    private fun downloadOnce(
        context: Context, url: String, option: Option, onProgress: (Float, Long, String) -> Unit
    ): Result {
        val app = context.applicationContext
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "downloads")
        dir.mkdirs()
        val before = (dir.listFiles() ?: emptyArray()).map { it.absolutePath }.toSet()

        val request = YoutubeDLRequest(url)
        request.addOption("--no-mtime")
        request.addOption("--no-playlist")
        when {
            option.audioOnly -> {
                val fid = option.formatId
                request.addOption("-f", if (fid != null && fid.isNotEmpty()) fid else "ba[ext=m4a]/ba")
            }
            option.height > 0 && ffmpegReady -> {
                val h = option.height
                request.addOption(
                    "-f",
                    "bv*[height<=" + h + "][ext=mp4]+ba[ext=m4a]/bv*[height<=" + h + "]+ba/b[height<=" + h + "]"
                )
                request.addOption("--merge-output-format", "mp4/mkv")
            }
            else -> request.addOption("-f", "b[ext=mp4]/b")
        }
        request.addOption("-o", dir.absolutePath + "/%(title).80s [%(id)s].%(ext)s")

        YoutubeDL.getInstance().execute(request, PROCESS_ID) { progress, eta, line ->
            onProgress(progress, eta, line)
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
    fun publishToDownloads(context: Context, file: File, audio: Boolean = false): Uri? {
        val resolver = context.applicationContext.contentResolver
        val mime = mimeFor(file, audio)
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
