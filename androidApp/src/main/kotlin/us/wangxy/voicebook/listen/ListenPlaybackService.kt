package us.wangxy.voicebook.listen

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.koin.core.context.GlobalContext
import us.wangxy.voicebook.voice.listen.MediaPlaybackHost

/**
 * Foreground service that keeps audiobook playback alive in the background. It holds no player
 * itself: the notification actions are forwarded to the active [AndroidMediaPlaybackHost].
 */
class ListenPlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = GlobalContext.getOrNull()?.getOrNull<MediaPlaybackHost>() as? AndroidMediaPlaybackHost
        // startForegroundService() requires startForeground() even when we're about to stop.
        val notification = host?.buildNotification()
        if (notification != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NotificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NotificationId, notification)
            }
        }
        val transport = host?.transport
        if (transport == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ActionPlay -> transport.play()
            ActionPause -> transport.pause()
            ActionNext -> transport.next()
            ActionPrevious -> transport.previous()
            ActionStop -> transport.stop()
        }
        return START_NOT_STICKY
    }

    companion object {
        const val NotificationId = 4101
        const val ActionPlay = "us.wangxy.voicebook.listen.PLAY"
        const val ActionPause = "us.wangxy.voicebook.listen.PAUSE"
        const val ActionNext = "us.wangxy.voicebook.listen.NEXT"
        const val ActionPrevious = "us.wangxy.voicebook.listen.PREVIOUS"
        const val ActionStop = "us.wangxy.voicebook.listen.STOP"

        fun start(context: Context) {
            val intent = Intent(context, ListenPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
