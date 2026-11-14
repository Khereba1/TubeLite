package com.khereba1.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder

/**
 * Foreground service that keeps the process alive while audio plays in the background,
 * and shows a media notification / lock-screen controls (play, pause, previous, next, seek).
 * All state comes from the player page via MainActivity.
 */
class PlaybackService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.khereba1.tubelite.action.TOGGLE"
        const val ACTION_STOP = "com.khereba1.tubelite.action.STOP"
        const val ACTION_PREV = "com.khereba1.tubelite.action.PREV"
        const val ACTION_NEXT = "com.khereba1.tubelite.action.NEXT"
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POS = "pos"
        const val EXTRA_DUR = "dur"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var instance: PlaybackService? = null

        /** Receives "play", "pause", "toggle", "next", "prev" or "seek:<ms>" and forwards it to the page. */
        @Volatile
        var controller: ((String) -> Unit)? = null

        /** Thumbnail of the current video (set by MainActivity). */
        @Volatile
        var art: Bitmap? = null
    }

    private var session: MediaSession? = null
    private var playing = true
    private var title = ""
    private var positionMs = 0L
    private var durationMs = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        title = getString(R.string.app_name)
        try {
            val s = MediaSession(this, "TubeLite")
            s.setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    controller?.invoke("play")
                }

                override fun onPause() {
                    controller?.invoke("pause")
                }

                override fun onSkipToNext() {
                    controller?.invoke("next")
                }

                override fun onSkipToPrevious() {
                    controller?.invoke("prev")
                }

                override fun onSeekTo(pos: Long) {
                    controller?.invoke("seek:" + pos)
                }
            })
            s.isActive = true
            session = s
        } catch (e: Throwable) {
            AppLog.log("SERVICE", "MediaSession failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> controller?.invoke("toggle")
            ACTION_PREV -> controller?.invoke("prev")
            ACTION_NEXT -> controller?.invoke("next")
            ACTION_STOP -> {
                controller?.invoke("pause")
                shutdown()
                return START_NOT_STICKY
            }
            else -> {
                playing = intent?.getBooleanExtra(EXTRA_PLAYING, true) ?: true
                val t = intent?.getStringExtra(EXTRA_TITLE) ?: ""
                if (t.isNotEmpty()) title = t
                positionMs = intent?.getLongExtra(EXTRA_POS, 0L) ?: 0L
                durationMs = intent?.getLongExtra(EXTRA_DUR, 0L) ?: 0L
            }
        }
        showNotification()
        return START_NOT_STICKY
    }

    /** Called by MainActivity (main thread) when the page reports a new state. */
    fun update(isPlaying: Boolean, newTitle: String, pos: Long, dur: Long) {
        playing = isPlaying
        if (newTitle.isNotEmpty()) title = newTitle
        positionMs = pos
        durationMs = dur
        showNotification()
    }

    /** Redraw (e.g. when the thumbnail arrived). */
    fun refresh() {
        showNotification()
    }

    fun shutdown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun action(iconRes: Int, label: String, requestCode: Int, act: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, requestCode,
            Intent(this, PlaybackService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, iconRes), label, pi).build()
    }

    private fun showNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
                )
            }
            val open = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
            session?.let { style.setMediaSession(it.sessionToken) }

            val bmp = art
            val builder = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(getString(R.string.app_name))
                .setContentIntent(open)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(action(android.R.drawable.ic_media_previous, getString(R.string.previous), 3, ACTION_PREV))
                .addAction(
                    action(
                        if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                        getString(if (playing) R.string.pause else R.string.play), 1, ACTION_TOGGLE
                    )
                )
                .addAction(action(android.R.drawable.ic_media_next, getString(R.string.next), 4, ACTION_NEXT))
                .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), 2, ACTION_STOP))
                .setStyle(style)
            if (bmp != null) builder.setLargeIcon(bmp)
            val notification = builder.build()

            val meta = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
            if (durationMs > 0) meta.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
            if (bmp != null) meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp)
            session?.setMetadata(meta.build())

            session?.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_SEEK_TO
                    )
                    .setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        if (positionMs >= 0) positionMs else PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        if (playing) 1f else 0f
                    )
                    .build()
            )

            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Throwable) {
            AppLog.log("SERVICE", "showNotification failed", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        shutdown()
    }

    override fun onDestroy() {
        instance = null
        try {
            session?.release()
        } catch (e: Throwable) {
            // ignore
        }
        session = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
