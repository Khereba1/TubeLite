package com.khereba1.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder

/**
 * Foreground service that keeps the process alive while audio plays in the background,
 * and shows a media notification (play/pause/stop). All state comes from the page via MainActivity.
 */
class PlaybackService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.khereba1.tubelite.action.TOGGLE"
        const val ACTION_STOP = "com.khereba1.tubelite.action.STOP"
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_TITLE = "title"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var instance: PlaybackService? = null

        /** Set by MainActivity: receives "play", "pause" or "toggle" and forwards it to the page. */
        @Volatile
        var controller: ((String) -> Unit)? = null
    }

    private var session: MediaSession? = null
    private var playing = true
    private var title = ""

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
            ACTION_STOP -> {
                controller?.invoke("pause")
                shutdown()
                return START_NOT_STICKY
            }
            else -> {
                playing = intent?.getBooleanExtra(EXTRA_PLAYING, true) ?: true
                val t = intent?.getStringExtra(EXTRA_TITLE) ?: ""
                if (t.isNotEmpty()) title = t
            }
        }
        showNotification()
        return START_NOT_STICKY
    }

    /** Called by MainActivity (main thread) when the page reports a new state. */
    fun update(isPlaying: Boolean, newTitle: String) {
        playing = isPlaying
        if (newTitle.isNotEmpty()) title = newTitle
        showNotification()
    }

    fun shutdown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
                )
            }
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val open = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                flags
            )
            val toggle = PendingIntent.getService(
                this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE), flags
            )
            val stop = PendingIntent.getService(
                this, 2, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP), flags
            )
            val toggleIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            val toggleLabel = getString(if (playing) R.string.pause else R.string.play)

            val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1)
            session?.let { style.setMediaSession(it.sessionToken) }

            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(getString(R.string.app_name))
                .setContentIntent(open)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(Notification.Action.Builder(Icon.createWithResource(this, toggleIcon), toggleLabel, toggle).build())
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        getString(R.string.stop),
                        stop
                    ).build()
                )
                .setStyle(style)
                .build()

            session?.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).build())
            session?.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        1f
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
