package com.example.mplayer.media

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
import androidx.media3.common.PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED
import androidx.media3.common.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
import androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
import androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_MEDIA_ITEM_TRANSITION
import androidx.media3.common.Player.EVENT_MEDIA_METADATA_CHANGED
import androidx.media3.common.Player.EVENT_PLAYBACK_STATE_CHANGED
import androidx.media3.common.Player.EVENT_PLAY_WHEN_READY_CHANGED
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.example.mplayer.other.Event
import com.example.mplayer.other.Resource
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Class that manages a connection to a [MediaLibraryService] instance, typically a
 * [MusicService] or one of its subclasses.
 *
 * Typically it's best to construct/inject dependencies either using DI or, as UAMP does,
 * using [InjectorUtils] in the app module. There are a few difficulties for that here:
 * - [MediaBrowser] is a final class, so mocking it directly is difficult.
 * - A [MediaBrowserConnectionCallback] is a parameter into the construction of
 *   a [MediaBrowserCompat], and provides callbacks to this class.
 * - [MediaBrowserCompat.ConnectionCallback.onConnected] is the best place to construct
 *   a [MediaControllerCompat] that will be used to control the [MediaSessionCompat].
 *
 *  Because of these reasons, rather than constructing additional classes, this is treated as
 *  a black box (which is why there's very little logic here).
 *
 *  This is also why the parameters to construct a [MusicServiceConnection] are simple
 *  parameters, rather than private properties. They're only required to build the
 *  [MediaBrowserConnectionCallback] and [MediaBrowserCompat] objects.
 */
class MusicServiceConnection(context: Context, serviceComponent: ComponentName) {

    private val _isConnected = MutableLiveData<Event<Resource<Boolean>>>()
    val isConnected: LiveData<Event<Resource<Boolean>>> = _isConnected

    private val _networkError = MutableLiveData<Event<Resource<Boolean>>>()
    val networkError: LiveData<Event<Resource<Boolean>>> = _networkError

    private val _playbackState = MutableLiveData<PlaybackState>()
    val playbackState: LiveData<PlaybackState> = _playbackState

    private val _curPlayingSong = MutableLiveData<MediaMetadataCompat?>()
    val curPlayingSong: LiveData<MediaMetadataCompat?> = _curPlayingSong

    val rootMediaItem = MutableLiveData<MediaItem>().apply { postValue(MediaItem.EMPTY) }

    val nowPlaying = MutableLiveData<MediaItem>().apply { postValue(NOTHING_PLAYING) }
    val player: Player? get() = browser


    private var browser: MediaBrowser? = null
    private val playerListener: PlayerListener = PlayerListener()

    private val coroutineContext: CoroutineContext = Dispatchers.Main
    private val scope = CoroutineScope(coroutineContext + SupervisorJob())

    init {
        scope.launch {
            val newBrowser = MediaBrowser.Builder(context, SessionToken(context, serviceComponent))
                .setListener(BrowserListener()).buildAsync().await()
            newBrowser.addListener(playerListener)
            browser = newBrowser
            rootMediaItem.postValue(
                newBrowser.getLibraryRoot(/* params= */ null).await().value
            )
            newBrowser.currentMediaItem?.let {
                nowPlaying.postValue(it)
            }
        }
    }
    suspend fun subscribe(parentId: String): MutableList<MediaItem> {
        return this.browser?.getChildren(parentId, 0, 100, null)?.await()?.value
            ?: ImmutableList.of()
    }

    suspend fun sendCommand(command: String, parameters: Bundle?): Boolean =
        sendCommand(command, parameters) { _, _ -> }

    suspend fun sendCommand(
        command: String, parameters: Bundle?, resultCallback: ((Int, Bundle?) -> Unit)
    ): Boolean = if (browser?.isConnected == true) {
        val args = parameters ?: Bundle()
        browser?.sendCustomCommand(SessionCommand(command, args), args)?.await()?.let {
            resultCallback(it.resultCode, it.extras)
        }
        true
    } else {
        false
    }

    fun release() {
        rootMediaItem.postValue(MediaItem.EMPTY)
        nowPlaying.postValue(NOTHING_PLAYING)
        browser?.let {
            it.removeListener(playerListener)
            it.release()
        }
        instance = null
    }


    private fun updatePlaybackState(player: Player) {
        _playbackState.postValue(
            PlaybackState(
                player.playbackState, player.playWhenReady, player.duration
            )
        )
    }

    private fun updateNowPlaying(player: Player) {
        val mediaItem = player.currentMediaItem ?: MediaItem.EMPTY
        if (mediaItem == MediaItem.EMPTY) {
            return
        }
        // The current media item from the CastPlayer may have lost some information.
        val mediaItemFuture = browser!!.getItem(mediaItem.mediaId)
        mediaItemFuture.addListener(
            Runnable {
                val fullMediaItem = mediaItemFuture.get().value ?: return@Runnable
                nowPlaying.postValue(
                    mediaItem.buildUpon().setMediaMetadata(fullMediaItem.mediaMetadata).build()
                )
            }, MoreExecutors.directExecutor()
        )
    }

    companion object {
        // For Singleton instantiation.
        @Volatile
        private var instance: MusicServiceConnection? = null

        fun getInstance(context: Context, serviceComponent: ComponentName) =
            instance ?: synchronized(this) {
                instance ?: MusicServiceConnection(context, serviceComponent).also { instance = it }
            }
    }

    private inner class BrowserListener : MediaBrowser.Listener {
        override fun onDisconnected(controller: MediaController) {
            release()
        }
    }

    private inner class PlayerListener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(EVENT_PLAY_WHEN_READY_CHANGED) || events.contains(
                    EVENT_PLAYBACK_STATE_CHANGED
                ) || events.contains(EVENT_MEDIA_ITEM_TRANSITION)
            ) {
                updatePlaybackState(player)
                if (player.playbackState != Player.STATE_IDLE) {
                    _networkError.postValue(
                        Event(
                            Resource.error(
                                "on event", null
                            )
                        )
                    )
                }
            }
            if (events.contains(EVENT_MEDIA_METADATA_CHANGED) || events.contains(
                    EVENT_MEDIA_ITEM_TRANSITION
                ) || events.contains(EVENT_PLAY_WHEN_READY_CHANGED)
            ) {
                updateNowPlaying(player)
            }
        }

        override fun onPlayerErrorChanged(error: PlaybackException?) {
            when (error?.errorCode) {
                ERROR_CODE_IO_BAD_HTTP_STATUS, ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED, ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                    _networkError.postValue(
                        Event(
                            Resource.error(
                                "Couldn't connect to the server. Please check your internet connection.",
                                null
                            )
                        )
                    )
                }
            }
        }
    }
}

class PlaybackState(
    private val playbackState: Int = Player.STATE_IDLE,
    private val playWhenReady: Boolean = false,
    val duration: Long = C.TIME_UNSET
) {
    val isPlaying: Boolean
        get() {
            return (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY) && playWhenReady
        }
}

@Suppress("PropertyName")
val EMPTY_PLAYBACK_STATE: PlaybackState = PlaybackState()

@Suppress("PropertyName")
val NOTHING_PLAYING: MediaItem = MediaItem.EMPTY