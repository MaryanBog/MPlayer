package com.example.mplayer.ui.viewmodels

import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat.METADATA_KEY_MEDIA_ID
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.mplayer.data.entities.Song
import com.example.mplayer.media.MusicServiceConnection
import com.example.mplayer.other.Constants.MEDIA_ROOT_ID
import com.example.mplayer.other.Resource
import com.example.playerdemo.media.extensions.isEnded
import com.example.playerdemo.media.extensions.isPlayEnabled
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val musicServiceConnection: MusicServiceConnection
) : ViewModel() {

    private val _mediaItems = MutableLiveData<Resource<List<Song>>>()
    val mediaItems: LiveData<Resource<List<Song>>> = _mediaItems

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    val isConnected = musicServiceConnection.isConnected
    val networkError = musicServiceConnection.networkError
    val curPlayingSong = musicServiceConnection.curPlayingSong
    val playbackState = musicServiceConnection.playbackState

    init {
        _mediaItems.postValue(Resource.loading(null))
        serviceScope.launch {
            val children: MutableList<MediaItem> = musicServiceConnection.subscribe(
                MEDIA_ROOT_ID
            )
            val items = children.map {
                Song(it.mediaId)
            }
            _mediaItems.postValue(Resource.success(items))
        }
    }

    fun playMedia(
        mediaItem: MediaItem,
        pauseThenPlaying: Boolean = true,
        parentMediaId: String? = null
    ) {

        val nowPlaying = musicServiceConnection.nowPlaying.value
        val player = musicServiceConnection.player?: return

        val isPrepared = player.playbackState != Player.STATE_IDLE
        if (isPrepared && mediaItem.mediaId == nowPlaying?.mediaId) {
            when {
                player.isPlaying ->
                    if (pauseThenPlaying) player.pause() else Unit
                player.isPlayEnabled -> player.play()
                player.isEnded -> player.seekTo(C.TIME_UNSET)
                else -> {
                    Log.w(
                        TAG, "Playable item clicked but neither play nor pause are enabled!" +
                                " (mediaId=${mediaItem.mediaId})"
                    )
                }
            }
        } else {
            viewModelScope.launch {
                var playlist: MutableList<MediaItem> = arrayListOf()
                // load the children of the parent if requested
                parentMediaId?.let {
                    playlist = musicServiceConnection.subscribe(parentMediaId).let { children ->
                        children.filter {
                            it.mediaMetadata.isPlayable ?: false
                        }
                    }.toMutableList()
                }
                if (playlist.isEmpty()) {
                    playlist.add(mediaItem)
                }
                val indexOf = playlist.indexOf(mediaItem)
                val startWindowIndex = if (indexOf >= 0) indexOf else 0
                player.setMediaItems(
                    playlist, startWindowIndex, /* startPositionMs= */ C.TIME_UNSET
                )
                player.prepare()
                player.play()
            }
        }
    }

}

private const val TAG = "MainActivitytVM"