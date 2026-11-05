package app.obsibible.audio

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.obsibible.MainActivity
import app.obsibible.ObsiBibleApp
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Keeps audio going with the screen off and draws the media notification and lock screen controls:
 * previous verse and next verse beside play, and previous chapter and next chapter in the extra slots.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as ObsiBibleApp
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val prevVerse = SessionCommand(PREV_VERSE, Bundle.EMPTY)
        val nextVerse = SessionCommand(NEXT_VERSE, Bundle.EMPTY)
        val prevChapter = SessionCommand(PREV_CHAPTER, Bundle.EMPTY)
        val nextChapter = SessionCommand(NEXT_CHAPTER, Bundle.EMPTY)
        // Custom buttons, not the standard previous and next: phone makers draw custom buttons on
        // their media card, and the standard ones would jump between verse files, not verses.
        val buttons = ImmutableList.of(
            CommandButton.Builder(CommandButton.ICON_REWIND).setDisplayName("Previous verse").setSessionCommand(prevVerse).setSlots(CommandButton.SLOT_BACK).build(),
            CommandButton.Builder(CommandButton.ICON_FAST_FORWARD).setDisplayName("Next verse").setSessionCommand(nextVerse).setSlots(CommandButton.SLOT_FORWARD).build(),
            CommandButton.Builder(CommandButton.ICON_PREVIOUS).setDisplayName("Previous chapter").setSessionCommand(prevChapter).setSlots(CommandButton.SLOT_OVERFLOW).build(),
            CommandButton.Builder(CommandButton.ICON_NEXT).setDisplayName("Next chapter").setSessionCommand(nextChapter).setSlots(CommandButton.SLOT_OVERFLOW).build(),
        )
        session = MediaSession.Builder(this, VersePlayer(app.audio.player, app.audio))
            .setSessionActivity(open)
            .setMediaButtonPreferences(buttons)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(prevVerse).add(nextVerse).add(prevChapter).add(nextChapter).build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
                }

                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        PREV_VERSE -> app.audio.previousVerse()
                        NEXT_VERSE -> app.audio.nextVerse()
                        PREV_CHAPTER -> app.audio.previousChapter()
                        NEXT_CHAPTER -> app.audio.nextChapter()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // The player belongs to AudioEngine and lives as long as the app; only the session goes.
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        const val PREV_VERSE = "app.obsibible.PREV_VERSE"
        const val NEXT_VERSE = "app.obsibible.NEXT_VERSE"
        const val PREV_CHAPTER = "app.obsibible.PREV_CHAPTER"
        const val NEXT_CHAPTER = "app.obsibible.NEXT_CHAPTER"
    }
}

/**
 * Headphone and car buttons step by verse. The standard previous and next are hidden so Android
 * does not draw a second pair beside the verse buttons.
 */
private class VersePlayer(player: Player, private val engine: AudioEngine) : ForwardingPlayer(player) {
    private val hidden = listOf(
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
    )

    override fun getAvailableCommands(): Player.Commands {
        val b = super.getAvailableCommands().buildUpon()
        hidden.forEach { b.remove(it) }
        return b.build()
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun seekToPrevious() = engine.previousVerse()
    override fun seekToNext() = engine.nextVerse()
    override fun seekToPreviousMediaItem() = engine.previousVerse()
    override fun seekToNextMediaItem() = engine.nextVerse()
}
