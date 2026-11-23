package com.khereba1.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The list of downloads (waiting, running, finished, failed), kept in a small JSON file in the app's private storage.
 * [DownloadService] works through the waiting ones one after the other; the Downloads screen shows the whole list.
 * Only state changes are written to disk (not every progress tick).
 */
object DownloadStore {

    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    private const val MAX_ITEMS = 200

    class Item(
        val id: Long,
        val url: String,
        val videoId: String,
        val title: String,
        val label: String,
        val height: Int,
        val audio: Boolean,
        val formatId: String,
        val ext: String,
        val createdAt: Long
    ) {
        @Volatile var state = QUEUED
        @Volatile var progress = -1
        @Volatile var message = ""
        @Volatile var fileUri = ""
        @Volatile var mime = ""
        @Volatile var finishedAt = 0L

        val finished: Boolean get() = state == DONE || state == FAILED || state == CANCELLED
    }

    private val lock = Any()
    private val items = ArrayList<Item>() // oldest first
    private var file: File? = null
    private var loaded = false

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** Listeners are called (from any thread) whenever the list or a progress value changed. */
    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        for (l in listeners) {
            try {
                l()
            } catch (e: Throwable) {
                // a broken listener must not stop the download
            }
        }
    }

    fun init(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (loaded) return
            loaded = true
            file = File(app.filesDir, "downloads.json")
            try {
                val f = file
                if (f != null && f.exists()) {
                    val arr = JSONArray(f.readText())
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val it = Item(
                            o.getLong("id"), o.optString("url"), o.optString("videoId"), o.optString("title"),
                            o.optString("label"), o.optInt("height"), o.optBoolean("audio"), o.optString("formatId"),
                            o.optString("ext"), o.optLong("createdAt")
                        )
                        it.state = o.optString("state", FAILED)
                        it.message = o.optString("message")
                        it.fileUri = o.optString("fileUri")
                        it.mime = o.optString("mime")
                        it.finishedAt = o.optLong("finishedAt")
                        // The app was closed while it was waiting or running: it can be started again from the list.
                        if (it.state == QUEUED || it.state == RUNNING) {
                            it.state = FAILED
                            it.message = app.getString(R.string.dl_interrupted)
                            it.finishedAt = System.currentTimeMillis()
                        }
                        items.add(it)
                    }
                }
            } catch (e: Throwable) {
                AppLog.log("DL", "reading the download list failed", e)
                items.clear()
            }
            saveLocked()
        }
    }

    private fun saveLocked() {
        val f = file ?: return
        try {
            val arr = JSONArray()
            for (it in items) {
                val o = JSONObject()
                o.put("id", it.id)
                o.put("url", it.url)
                o.put("videoId", it.videoId)
                o.put("title", it.title)
                o.put("label", it.label)
                o.put("height", it.height)
                o.put("audio", it.audio)
                o.put("formatId", it.formatId)
                o.put("ext", it.ext)
                o.put("createdAt", it.createdAt)
                o.put("state", it.state)
                o.put("message", it.message)
                o.put("fileUri", it.fileUri)
                o.put("mime", it.mime)
                o.put("finishedAt", it.finishedAt)
                arr.put(o)
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) f.writeText(arr.toString())
        } catch (e: Throwable) {
            AppLog.log("DL", "saving the download list failed", e)
        }
    }

    /** Newest first. */
    fun all(): List<Item> = synchronized(lock) { items.asReversed().toList() }

    fun activeCount(): Int = synchronized(lock) { items.count { it.state == QUEUED || it.state == RUNNING } }

    fun queuedCount(): Int = synchronized(lock) { items.count { it.state == QUEUED } }

    /** A waiting or running download of exactly this video and quality, if any (so a double tap does not add it twice). */
    fun findActive(url: String, height: Int, audio: Boolean, formatId: String): Item? = synchronized(lock) {
        items.firstOrNull {
            (it.state == QUEUED || it.state == RUNNING) &&
                it.url == url && it.height == height && it.audio == audio && it.formatId == formatId
        }
    }

    fun add(
        url: String, videoId: String, title: String, label: String,
        height: Int, audio: Boolean, formatId: String, ext: String
    ): Item {
        val item: Item
        synchronized(lock) {
            var id = System.currentTimeMillis()
            while (items.any { it.id == id }) id++
            item = Item(id, url, videoId, title, label, height, audio, formatId, ext, id)
            items.add(item)
            // keep the list bounded: drop the oldest finished entries
            while (items.size > MAX_ITEMS) {
                val victim = items.firstOrNull { it.finished } ?: break
                items.remove(victim)
            }
            saveLocked()
        }
        notifyChanged()
        return item
    }

    /** Takes the oldest waiting download and marks it running (one step, so it cannot be cancelled in between). */
    fun claimNext(preparingText: String): Item? {
        var found: Item? = null
        synchronized(lock) {
            found = items.firstOrNull { it.state == QUEUED }
            found?.let {
                it.state = RUNNING
                it.progress = -1
                it.message = preparingText
                saveLocked()
            }
        }
        if (found != null) notifyChanged()
        return found
    }

    /** Cancels a download that is still waiting. False when it already started (then the running one must be stopped). */
    fun cancelQueued(item: Item, text: String): Boolean {
        var changed = false
        synchronized(lock) {
            if (item.state == QUEUED) {
                item.state = CANCELLED
                item.message = text
                item.finishedAt = System.currentTimeMillis()
                saveLocked()
                changed = true
            }
        }
        if (changed) notifyChanged()
        return changed
    }

    fun finish(item: Item, state: String, message: String, fileUri: String, mime: String) {
        synchronized(lock) {
            item.state = state
            item.progress = if (state == DONE) 100 else -1
            item.message = message
            item.fileUri = fileUri
            item.mime = mime
            item.finishedAt = System.currentTimeMillis()
            saveLocked()
        }
        notifyChanged()
    }

    /** Progress of the running download (not written to disk). */
    fun progress(item: Item, percent: Int, text: String) {
        item.progress = percent
        item.message = text
        notifyChanged()
    }

    fun remove(id: Long) {
        synchronized(lock) {
            items.removeAll { it.id == id }
            saveLocked()
        }
        notifyChanged()
    }

    fun clearFinished() {
        synchronized(lock) {
            items.removeAll { it.finished }
            saveLocked()
        }
        notifyChanged()
    }

    /** Puts a failed or cancelled download at the end of the waiting line again (as a new entry). */
    fun retry(old: Item): Item {
        val fresh = add(old.url, old.videoId, old.title, old.label, old.height, old.audio, old.formatId, old.ext)
        remove(old.id)
        return fresh
    }
}
