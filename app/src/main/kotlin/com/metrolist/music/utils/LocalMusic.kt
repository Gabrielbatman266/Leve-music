/**
 * Leve Music — leitura rápida das músicas do celular (MediaStore)
 */

package com.metrolist.music.utils

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.core.net.toUri
import com.metrolist.music.models.MediaMetadata

data class LocalSong(
    val mediaStoreId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val dateAddedSec: Long,
    val sizeBytes: Long,
    val folder: String,
    val year: Int,
    val track: Int,
) {
    val id: String get() = LocalMusic.ID_PREFIX + mediaStoreId
    val artworkUri: String get() = LocalMusic.albumArtUri(albumId)
    val titleKey: String = title.lowercase()
    val artistKey: String = artist.lowercase()
    val albumKey: String = album.lowercase()

    fun toMediaMetadata() =
        MediaMetadata(
            id = id,
            title = title,
            artists = listOf(MediaMetadata.Artist(id = null, name = artist)),
            duration = (durationMs / 1000).toInt(),
            thumbnailUrl = artworkUri,
        )
}

object LocalMusic {
    const val ID_PREFIX = "local_"
    private const val UNKNOWN_ARTIST = "Artista desconhecido"
    private const val UNKNOWN_ALBUM = "Álbum desconhecido"

    fun isLocalId(id: String): Boolean = id.startsWith(ID_PREFIX)

    /** URI que o player usa: arquivos do celular viram content://, o resto continua sendo o id do YouTube. */
    fun playbackUri(id: String): String =
        if (isLocalId(id)) {
            id.removePrefix(ID_PREFIX).toLongOrNull()
                ?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it).toString() }
                ?: id
        } else {
            id
        }

    fun albumArtUri(albumId: Long): String =
        ContentUris.withAppendedId("content://media/external/audio/albumart".toUri(), albumId).toString()

    val permission: String
        get() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_AUDIO
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }

    /** Uma única consulta ao MediaStore, só com as colunas necessárias. */
    fun load(context: Context): List<LocalSong> {
        val projection =
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.TRACK,
            )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 20000"
        val result = ArrayList<LocalSong>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            null,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            while (c.moveToNext()) {
                val path = c.getString(dataCol).orEmpty()
                val artist = c.getString(artistCol)?.takeUnless { it.isBlank() || it == "<unknown>" } ?: UNKNOWN_ARTIST
                val album = c.getString(albumCol)?.takeUnless { it.isBlank() || it == "<unknown>" } ?: UNKNOWN_ALBUM
                result +=
                    LocalSong(
                        mediaStoreId = c.getLong(idCol),
                        title = c.getString(titleCol)?.takeUnless { it.isBlank() } ?: path.substringAfterLast('/'),
                        artist = artist,
                        album = album,
                        albumId = c.getLong(albumIdCol),
                        durationMs = c.getLong(durationCol),
                        dateAddedSec = c.getLong(dateCol),
                        sizeBytes = c.getLong(sizeCol),
                        folder = path.substringBeforeLast('/', "").substringAfterLast('/').ifBlank { "Outros" },
                        year = c.getInt(yearCol),
                        track = c.getInt(trackCol) % 1000,
                    )
            }
        }
        return result
    }
}
