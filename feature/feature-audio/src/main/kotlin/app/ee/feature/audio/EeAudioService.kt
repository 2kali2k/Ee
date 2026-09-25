package app.ee.feature.audio

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import androidx.media3.exoplayer.ExoPlayer

/**
 * MediaSession host (M3b): gives the audio player lock-screen / notification
 * controls via the system media UI while the app is in the background.
 *
 * M3b limitation (flagged): when the player screen closes (last controller
 * disconnects) the service — and playback — stops; continuous background
 * playback with a foreground notification lands in M4.
 */
class EeAudioService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        session = MediaSession.Builder(this, player).build()
        TOKEN = session?.sessionToken
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.release()
        TOKEN = null
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.release()
        TOKEN = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var TOKEN: SessionToken? = null
    }
}
