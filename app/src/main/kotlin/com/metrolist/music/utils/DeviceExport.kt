/**
 * Leve Music — copia uma música baixada para Music/Leve Music como arquivo normal
 */

package com.metrolist.music.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

val SaveDownloadsToDeviceKey = androidx.datastore.preferences.core.booleanPreferencesKey("leveSaveDownloadsToDevice")

object DeviceExport {
    const val FOLDER = "Leve Music"

    private fun safeName(text: String): String =
        text.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(120)

    /** Retorna o caminho amigável onde a música foi salva. */
    @OptIn(UnstableApi::class)
    suspend fun save(
        context: Context,
        cache: Cache,
        database: MusicDatabase,
        song: Song,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Precisa de Android 10 ou mais novo" }

                val mime = database.format(song.id).first()?.mimeType.orEmpty()
                val isWebm = mime.contains("webm")
                val extension = if (isWebm) "weba" else "m4a"
                val outMime = if (isWebm) "audio/webm" else "audio/mp4"
                val artists = song.artists.joinToString(", ") { it.name }.ifBlank { "Desconhecido" }
                val displayName = safeName("$artists - ${song.title}").ifBlank { song.id } + "." + extension

                val resolver = context.contentResolver
                val values =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.MIME_TYPE, outMime)
                        put(MediaStore.MediaColumns.TITLE, song.title)
                        put(MediaStore.Audio.Media.ARTIST, artists)
                        put(MediaStore.Audio.Media.ALBUM, song.song.albumName ?: FOLDER)
                        put(MediaStore.Audio.Media.IS_MUSIC, 1)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/$FOLDER")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }

                var friendlyPath = "Music/$FOLDER"
                val target: Uri =
                    try {
                        resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    } catch (_: Exception) {
                        null
                    } ?: run {
                        // Alguns aparelhos não aceitam o formato na pasta de músicas: usa Download/Leve Music
                        friendlyPath = "Download/$FOLDER"
                        values.remove(MediaStore.Audio.Media.IS_MUSIC)
                        values.remove(MediaStore.Audio.Media.ARTIST)
                        values.remove(MediaStore.Audio.Media.ALBUM)
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$FOLDER")
                        requireNotNull(
                            resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values),
                        ) { "Não foi possível criar o arquivo" }
                    }

                try {
                    val source = CacheDataSource(cache, null)
                    val spec =
                        DataSpec.Builder()
                            .setUri(song.id)
                            .setKey(song.id)
                            .setLength(C.LENGTH_UNSET.toLong())
                            .build()
                    resolver.openOutputStream(target)!!.use { out ->
                        source.open(spec)
                        try {
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = source.read(buffer, 0, buffer.size)
                                if (read == C.RESULT_END_OF_INPUT) break
                                out.write(buffer, 0, read)
                            }
                        } finally {
                            source.close()
                        }
                    }
                    resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    runCatching { resolver.delete(target, null, null) }
                    throw IllegalStateException("A música precisa estar totalmente baixada", e)
                }
                "$friendlyPath/$displayName"
            }
        }
}
