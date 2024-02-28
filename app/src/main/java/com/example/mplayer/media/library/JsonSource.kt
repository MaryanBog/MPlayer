package com.example.mplayer.media.library

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.example.mplayer.media.extensions.AlbumArtContentProvider
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Source of [MediaMetadataCompat] objects created from a basic JSON stream.
 *
 * The definition of the JSON is specified in the docs of [JsonMusic] in this file,
 * which is the object representation of it.
 */
internal class JsonSource(private val source: Uri) : AbstractMusicSource() {

    companion object {
        const val ORIGINAL_ARTWORK_URI_KEY = "com.example.android.uamp.JSON_ARTWORK_URI"
    }

    private var catalog: List<MediaItem> = emptyList()

    init {
        state = STATE_INITIALIZING
    }

    override fun iterator(): Iterator<MediaItem> = catalog.iterator()

    override suspend fun load() {
        updateCatalog(source)?.let { updatedCatalog ->
            catalog = updatedCatalog
            state = STATE_INITIALIZED
        } ?: run {
            catalog = emptyList()
            state = STATE_ERROR
        }
    }

    private suspend fun updateCatalog(catalogUri: Uri): List<MediaItem>? {
        return withContext(Dispatchers.IO) {
            val musicCat = try {
                downloadJson(catalogUri)
            } catch (ioException: IOException) {
                return@withContext null
            }

            // Get the base URI to fix up relative references later.
            val baseUri = catalogUri.toString().removeSuffix(catalogUri.lastPathSegment ?: "")

            musicCat.music.map { song ->
                // The JSON may have paths that are relative to the source of the JSON
                // itself. We need to fix them up here to turn them into absolute paths.
                catalogUri.scheme?.let { scheme ->
                    if (!song.source.startsWith(scheme)) {
                        song.source = baseUri + song.source
                    }
                    if (!song.image.startsWith(scheme)) {
                        song.image = baseUri + song.image
                    }
                }

                val jsonImageUri = Uri.parse(song.image)
                val imageUri = AlbumArtContentProvider.mapUri(jsonImageUri)
                val mediaMetadata = MediaMetadata.Builder()
                    .from(song)
                    .apply {
                        setArtworkUri(imageUri) // Used by ExoPlayer and Notification
                        // Keep the original artwork URI for being included in Cast metadata object.
                        val extras = Bundle()
                        extras.putString(ORIGINAL_ARTWORK_URI_KEY, jsonImageUri.toString())
                        setExtras(extras)
                    }
                    .build()
                MediaItem.Builder()
                    .apply {
                        setMediaId(song.id)
                        setUri(song.source)
                        setMimeType(MimeTypes.AUDIO_MPEG)
                        setMediaMetadata(mediaMetadata)
                    }.build()
            }.toList()
        }
    }

    @Throws(IOException::class)
    private fun downloadJson(catalogUri: Uri): JsonCatalog {
        val catalogConn = URL(catalogUri.toString())
        val reader = BufferedReader(InputStreamReader(catalogConn.openStream()))
        return Gson().fromJson(reader, JsonCatalog::class.java)
    }

    fun MediaMetadata.Builder.from(jsonMusic: JsonMusic): MediaMetadata.Builder {
        setTitle(jsonMusic.title)
        setDisplayTitle(jsonMusic.title)
        setArtist(jsonMusic.artist)
        setAlbumTitle(jsonMusic.album)
        setGenre(jsonMusic.genre)
        setArtworkUri(Uri.parse(jsonMusic.image))
        setTrackNumber(jsonMusic.trackNumber.toInt())
        setTotalTrackCount(jsonMusic.totalTrackCount.toInt())
        setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
        setIsPlayable(true)
        // The duration from the JSON is given in seconds, but the rest of the code works in
        // milliseconds. Here's where we convert to the proper units.
        val durationMs = TimeUnit.SECONDS.toMillis(jsonMusic.duration)
        val bundle = Bundle()
        bundle.putLong("durationMs", durationMs)
        return this
    }

    class JsonCatalog {
        var music: List<JsonMusic> = ArrayList()
    }

    @Suppress("unused")
    class JsonMusic {
        var id: String = ""
        var title: String = ""
        var album: String = ""
        var artist: String = ""
        var genre: String = ""
        var source: String = ""
        var image: String = ""
        var trackNumber: Long = 0
        var totalTrackCount: Long = 0
        var duration: Long = -1
        var site: String = ""
    }
}
