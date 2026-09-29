package us.wangxy.voicebook.listen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import us.wangxy.voicebook.voice.listen.MediaNowPlaying
import us.wangxy.voicebook.voice.listen.MediaPlaybackHost
import us.wangxy.voicebook.voice.listen.MediaTransport

/**
 * Audiobook playback integration: audio focus (calls and other players pause us), pause on
 * headphone unplug, a [MediaSession] for lock screen / headset buttons, and
 * [ListenPlaybackService] so the process keeps playing with the screen off.
 */
internal class AndroidMediaPlaybackHost(private val context: Context) : MediaPlaybackHost {
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    /** Synthesis runs between audio buffers; the lock times out on its own if we ever leak it. */
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceBook:listen")
        ?.apply { setReferenceCounted(false) }

    @Volatile
    var transport: MediaTransport? = null
        private set

    @Volatile
    private var nowPlaying = MediaNowPlaying(title = "", subtitle = "", playing = false)

    // Main thread only.
    private var session: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var noisyRegistered = false

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                transport?.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = nowPlaying.playing
                transport?.pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) transport?.play()
                resumeOnFocusGain = false
            }
            // LOSS_TRANSIENT_CAN_DUCK: the system lowers our volume itself (API 26+).
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) transport?.pause()
        }
    }

    override fun activate(transport: MediaTransport): Boolean {
        if (this.transport != null) {
            this.transport = transport
            return true
        }
        if (!requestFocus()) return false
        this.transport = transport
        main.post {
            openSession()
            ListenPlaybackService.start(context)
        }
        return true
    }

    override fun outputWarning(): String? {
        val am = audioManager ?: return null
        // Media volume is separate from the call volume the voice-chat page uses, so it can be
        // near zero while everything else on the phone sounds fine.
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val level = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (am.isStreamMute(AudioManager.STREAM_MUSIC) || level == 0) {
            showVolumePanel(am)
            return "媒体音量为 0（已静音），请调大媒体音量"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val virtual = runCatching { am.getAudioDevicesForAttributes(attributes) }.getOrDefault(emptyList())
                .any { it.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX }
            if (virtual) return "声音被路由到虚拟设备（投屏/镜像/录屏），手机扬声器不会出声"
        }
        if (level * 100 / max < LowVolumePercent) {
            showVolumePanel(am)
            return "媒体音量过低（${level * 100 / max}%），请调大媒体音量"
        }
        return null
    }

    private fun showVolumePanel(am: AudioManager) {
        main.post { runCatching { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI) } }
    }

    override fun update(nowPlaying: MediaNowPlaying) {
        this.nowPlaying = nowPlaying
        main.post {
            if (transport == null) return@post
            publishSession()
            notifications?.notify(ListenPlaybackService.NotificationId, buildNotification())
            if (nowPlaying.playing) wakeLock?.acquire(WakeLockTimeoutMs) else releaseWakeLock()
        }
    }

    override fun deactivate() {
        if (transport == null) return
        transport = null
        main.post {
            abandonFocus()
            if (noisyRegistered) {
                runCatching { context.unregisterReceiver(noisyReceiver) }
                noisyRegistered = false
            }
            session?.release()
            session = null
            releaseWakeLock()
            context.stopService(Intent(context, ListenPlaybackService::class.java))
            notifications?.cancel(ListenPlaybackService.NotificationId)
        }
    }

    fun buildNotification(): Notification {
        ensureChannel()
        val np = nowPlaying
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, ChannelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val playPause = if (np.playing) {
            action(android.R.drawable.ic_media_pause, "暂停", ListenPlaybackService.ActionPause)
        } else {
            action(android.R.drawable.ic_media_play, "播放", ListenPlaybackService.ActionPlay)
        }
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        session?.sessionToken?.let(style::setMediaSession)
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(np.title.ifBlank { "听书" })
            .setContentText(np.subtitle)
            .setContentIntent(launchIntent())
            .setOngoing(np.playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "上一句", ListenPlaybackService.ActionPrevious))
            .addAction(playPause)
            .addAction(action(android.R.drawable.ic_media_next, "下一句", ListenPlaybackService.ActionNext))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "退出", ListenPlaybackService.ActionStop))
            .setStyle(style)
            .build()
    }

    private fun requestFocus(): Boolean {
        val am = audioManager ?: return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusListener, main)
                .build()
            focusRequest = request
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let(am::abandonAudioFocusRequest)
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
        resumeOnFocusGain = false
    }

    private fun openSession() {
        if (session == null) {
            session = MediaSession(context, "VoiceBookListen").apply {
                @Suppress("DEPRECATION")
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
                setCallback(
                    object : MediaSession.Callback() {
                        override fun onPlay() { transport?.play() }
                        override fun onPause() { transport?.pause() }
                        override fun onSkipToNext() { transport?.next() }
                        override fun onSkipToPrevious() { transport?.previous() }
                        override fun onStop() { transport?.stop() }
                    },
                    main,
                )
                isActive = true
            }
        }
        if (!noisyRegistered) {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(noisyReceiver, filter)
            }
            noisyRegistered = true
        }
        publishSession()
    }

    private fun publishSession() {
        val s = session ?: return
        val np = nowPlaying
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, np.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, np.subtitle)
                .build(),
        )
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (np.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .build(),
        )
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = notifications ?: return
        if (nm.getNotificationChannel(ChannelId) != null) return
        nm.createNotificationChannel(
            NotificationChannel(ChannelId, "听书", NotificationManager.IMPORTANCE_LOW).apply {
                description = "听书播放控制"
                setShowBadge(false)
            },
        )
    }

    private fun action(icon: Int, title: String, action: String): Notification.Action {
        val intent = Intent(context, ListenPlaybackService::class.java).setAction(action)
        val pending = PendingIntent.getService(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(context, icon), title, pending).build()
    }

    private fun launchIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private companion object {
        const val ChannelId = "listen"
        const val WakeLockTimeoutMs = 10 * 60_000L
        const val LowVolumePercent = 25
    }
}
