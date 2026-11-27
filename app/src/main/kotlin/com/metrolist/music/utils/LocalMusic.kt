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

/** O que é o arquivo de áudio: música de verdade ou áudio do dia a dia. */
enum class AudioKind(val label: String) {
    MUSIC("Música"),
    WHATSAPP("WhatsApp"),
    RECORDING("Gravações"),
    OTHER("Toques e outros"),
}

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
    val kind: AudioKind = AudioKind.MUSIC,
    /** Faixa como o MediaStore guarda (disco × 1000 + faixa): ordena CD 1 antes do CD 2. */
    val trackRaw: Int = track,
    /** Caminho completo da pasta (duas pastas "Rock" em lugares diferentes não se misturam). */
    val folderPath: String = folder,
) {
    val id: String get() = LocalMusic.ID_PREFIX + mediaStoreId
    val artworkUri: String get() = LocalMusic.albumArtUri(albumId)

    // Chaves sem acento e sem maiúscula: "Água" fica no A, e buscar "joao" acha "João"
    val titleKey: String = LocalMusic.norm(title)
    val artistKey: String = LocalMusic.norm(artist)
    val albumKey: String = LocalMusic.norm(album)

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

    private val accents = Regex("\\p{Mn}+")
    private val spaces = Regex("\\s+")

    /** Minúsculo, sem acento e sem espaço sobrando: para ordenar, agrupar e buscar. */
    fun norm(text: String): String {
        val lower = text.trim().lowercase()
        val plain =
            if (lower.all { it.code < 128 }) lower
            else accents.replace(java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD), "")
        return if (plain.contains("  ") || plain.contains('\t')) spaces.replace(plain, " ") else plain
    }

    private val recordingDirs =
        setOf(
            "recordings", "recording", "record", "records_voice", "callrecord", "callrecordings", "call recordings",
            "call_recordings", "voice recorder", "voicerecorder", "sound_recorder", "soundrecorder", "gravações",
            "gravacoes", "gravador", "gravador de voz", "audiorecorder", "recorder", "voice notes", "call",
        )
    private val otherDirs = setOf("ringtones", "notifications", "alarms", "ui", "telegram audio", "telegram")

    /**
     * Olha só as PASTAS (não o nome do arquivo): antes "Call Me Maybe.mp3" virava gravação
     * e a pasta "Def Jam Records" sumia das músicas.
     */
    internal fun classify(path: String, isMusic: Boolean, durationMs: Long): AudioKind {
        val dirs = path.lowercase().substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
        return when {
            dirs.any { it == DeviceExport.FOLDER.lowercase() } -> AudioKind.MUSIC
            dirs.any { "whatsapp" in it } -> AudioKind.WHATSAPP
            dirs.any { it in recordingDirs || it.startsWith("call record") || it.startsWith("callrec") } -> AudioKind.RECORDING
            dirs.any { it in otherDirs } -> AudioKind.OTHER
            !isMusic && durationMs < 60_000 -> AudioKind.OTHER
            durationMs < 20_000 -> AudioKind.OTHER
            else -> AudioKind.MUSIC
        }
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
                MediaStore.Audio.Media.IS_MUSIC,
            )
        // Lê todo áudio com mais de 1s e separa depois: músicas x WhatsApp x gravações x toques
        // Downloads do Leve entram mesmo se o celular ainda não leu a duração do arquivo
        val selection =
            "${MediaStore.Audio.Media.DURATION} >= 1000 OR " +
                "${MediaStore.Audio.Media.DATA} LIKE '%/${DeviceExport.FOLDER}/%'"
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
            val isMusicCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_MUSIC)
            while (c.moveToNext()) {
                // uma linha estranha (arquivo meio apagado, tag quebrada) não derruba a lista toda
                runCatching { rowToSong(c, idCol, titleCol, artistCol, albumCol, albumIdCol, durationCol, dateCol, sizeCol, dataCol, yearCol, trackCol, isMusicCol) }
                    .getOrNull()?.let { result += it }
            }
        }
        return result
    }

    private fun rowToSong(
        c: android.database.Cursor,
        idCol: Int, titleCol: Int, artistCol: Int, albumCol: Int, albumIdCol: Int, durationCol: Int,
        dateCol: Int, sizeCol: Int, dataCol: Int, yearCol: Int, trackCol: Int, isMusicCol: Int,
    ): LocalSong {
        val path = c.getString(dataCol).orEmpty()
        return song(
            id = c.getLong(idCol),
            path = path,
            title = c.getString(titleCol),
            artist = c.getString(artistCol),
            album = c.getString(albumCol),
            albumId = c.getLong(albumIdCol),
            durationMs = c.getLong(durationCol),
            dateAddedSec = c.getLong(dateCol),
            sizeBytes = c.getLong(sizeCol),
            year = c.getInt(yearCol),
            trackRaw = c.getInt(trackCol),
            isMusic = c.getInt(isMusicCol) != 0,
        )
    }

    private fun clean(text: String?): String? =
        text?.replace('\u0000', ' ')?.trim()?.takeUnless { it.isEmpty() || it == "<unknown>" }

    /** Monta a música a partir dos dados crus (tags vazias, "<unknown>" e lixo viram texto legível). */
    internal fun song(
        id: Long,
        path: String,
        title: String?,
        artist: String?,
        album: String?,
        albumId: Long,
        durationMs: Long,
        dateAddedSec: Long,
        sizeBytes: Long,
        year: Int,
        trackRaw: Int,
        isMusic: Boolean,
    ): LocalSong {
        val dirPath = path.substringBeforeLast('/', "")
        val fileName = path.substringAfterLast('/').substringBeforeLast('.').ifBlank { path.substringAfterLast('/') }
        val safeTrack = if (trackRaw in 0..99_999) trackRaw else 0
        return LocalSong(
            mediaStoreId = id,
            title = clean(title) ?: fileName.ifBlank { "Sem título" },
            artist = clean(artist) ?: UNKNOWN_ARTIST,
            album = clean(album) ?: UNKNOWN_ALBUM,
            albumId = albumId,
            durationMs = durationMs.coerceAtLeast(0),
            dateAddedSec = dateAddedSec,
            sizeBytes = sizeBytes.coerceAtLeast(0),
            folder = dirPath.substringAfterLast('/').ifBlank { "Outros" },
            year = if (year in 1000..3000) year else 0,
            track = safeTrack % 1000,
            kind = classify(path, isMusic, durationMs),
            trackRaw = safeTrack,
            folderPath = dirPath.ifBlank { "Outros" },
        )
    }
}
