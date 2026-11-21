package io.github.sherazi5110.cloudstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps music playing in the background (screen off, other apps open), with a notification to pause, skip or stop.
 * Starts with the first song and stops when the music is stopped.
 */
class MusicService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channel(this)
        val n = build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> Music.toggle()
            ACTION_NEXT -> Music.next()
            ACTION_PREVIOUS -> Music.previous()
            ACTION_STOP -> { Music.stop(); return START_NOT_STICKY }
        }
        if (Music.current == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        getSystemService(NotificationManager::class.java).notify(ID, build())
        return START_NOT_STICKY
    }

    private fun action(icon: Int, title: String, action: String) = NotificationCompat.Action(icon, title,
        PendingIntent.getService(this, action.hashCode(), Intent(this, MusicService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE))

    private fun build(): Notification {
        val t = Music.current
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(t?.title ?: "Music")
            .setContentText(t?.artist.orEmpty())
            .setContentIntent(open)
            .setOngoing(Music.playing)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREVIOUS))
            .addAction(if (Music.playing) action(android.R.drawable.ic_media_pause, "Pause", ACTION_TOGGLE) else action(android.R.drawable.ic_media_play, "Play", ACTION_TOGGLE))
            .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", ACTION_STOP))
            .build()
    }

    companion object {
        private const val ID = 4207
        private const val CHANNEL = "music"
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_NEXT = "next"
        private const val ACTION_PREVIOUS = "previous"
        private const val ACTION_STOP = "stop"

        private fun channel(c: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                c.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "Music", NotificationManager.IMPORTANCE_LOW))
        }

        /** Shows or updates the notification (and keeps playing in the background); removes it when stopped. */
        fun update(c: Context = App.context) {
            runCatching { ContextCompat.startForegroundService(c, Intent(c, MusicService::class.java)) }
        }
    }
}
