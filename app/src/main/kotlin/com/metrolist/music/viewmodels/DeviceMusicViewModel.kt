/**
 * Leve Music — músicas do celular
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.utils.LocalMusic
import com.metrolist.music.utils.LocalSong
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DeviceSort(val label: String) {
    A_Z("A–Z"),
    Z_A("Z–A"),
    NEWEST("Mais novas"),
    OLDEST("Mais antigas"),
    ARTIST("Artista"),
    ALBUM("Álbum"),
    YEAR("Ano de lançamento"),
    LONGEST("Mais longas"),
    SHORTEST("Mais curtas"),
    BIGGEST("Maior arquivo"),
}

enum class DeviceTab(val label: String) {
    SONGS("Músicas"),
    ARTISTS("Artistas"),
    ALBUMS("Álbuns"),
    FOLDERS("Pastas"),
    AUDIOS("Áudios"),
}

data class DeviceGroup(
    val name: String,
    val songs: List<LocalSong>,
    /** Identidade do grupo (dois álbuns "Greatest Hits" de artistas diferentes têm chaves diferentes). */
    val key: String = name,
    val subtitle: String? = null,
) {
    val artworkUri: String? get() = songs.firstOrNull()?.artworkUri
    val totalMs: Long get() = songs.sumOf { it.durationMs }
}

// Desempate sempre pelo id: a mesma lista sai sempre na mesma ordem
private val byTitle = compareBy<LocalSong>({ it.titleKey }, { it.mediaStoreId })
private val inAlbum = compareBy<LocalSong>({ it.trackRaw == 0 }, { it.trackRaw }, { it.titleKey }, { it.mediaStoreId })

fun List<LocalSong>.sortedForDevice(sort: DeviceSort): List<LocalSong> =
    when (sort) {
        DeviceSort.A_Z -> sortedWith(byTitle)
        DeviceSort.Z_A -> sortedWith(byTitle.reversed())
        DeviceSort.NEWEST -> sortedWith(compareByDescending<LocalSong> { it.dateAddedSec }.then(byTitle))
        DeviceSort.OLDEST -> sortedWith(compareBy<LocalSong> { it.dateAddedSec }.then(byTitle))
        DeviceSort.ARTIST -> sortedWith(compareBy<LocalSong>({ it.artistKey }, { it.albumKey }, { it.albumId }).then(inAlbum))
        DeviceSort.ALBUM -> sortedWith(compareBy<LocalSong>({ it.albumKey }, { it.albumId }).then(inAlbum))
        // sem ano (0) vai pro fim
        DeviceSort.YEAR -> sortedWith(compareBy<LocalSong>({ it.year == 0 }, { -it.year }).then(byTitle))
        DeviceSort.LONGEST -> sortedWith(compareByDescending<LocalSong> { it.durationMs }.then(byTitle))
        DeviceSort.SHORTEST -> sortedWith(compareBy<LocalSong> { it.durationMs }.then(byTitle))
        DeviceSort.BIGGEST -> sortedWith(compareByDescending<LocalSong> { it.sizeBytes }.then(byTitle))
    }

/** Nome que mais aparece no grupo ("Anitta" ganha de "ANITTA" e "anitta "). */
private fun List<LocalSong>.commonName(pick: (LocalSong) -> String): String =
    groupingBy { pick(it).trim() }.eachCount().maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })?.key ?: ""

fun List<LocalSong>.groupedBy(tab: DeviceTab): List<DeviceGroup> {
    if (tab == DeviceTab.SONGS || tab == DeviceTab.AUDIOS) return emptyList()
    val groups =
        when (tab) {
            // Artista: ignora maiúscula, acento e espaço sobrando
            DeviceTab.ARTISTS ->
                groupBy { it.artistKey }.map { (k, songs) ->
                    DeviceGroup(songs.commonName { it.artist }, songs.sortedWith(byTitle), key = "ar:$k")
                }
            // Álbum: pelo id do álbum (mesmo nome de artistas diferentes não se misturam)
            DeviceTab.ALBUMS ->
                groupBy { if (it.albumId != 0L) "id:${it.albumId}" else "n:${it.albumKey}|${it.artistKey}" }.map { (k, songs) ->
                    DeviceGroup(songs.commonName { it.album }, songs.sortedWith(inAlbum), key = "al:$k", subtitle = songs.commonName { it.artist })
                }
            // Pasta: pelo caminho completo; nomes repetidos ganham a pasta de cima entre parênteses
            else -> {
                val byPath = groupBy { it.folderPath }
                val nameCount = byPath.values.groupingBy { it.first().folder.lowercase() }.eachCount()
                byPath.map { (path, songs) ->
                    val name = songs.first().folder
                    val parent = path.substringBeforeLast('/', "").substringAfterLast('/')
                    val shown = if ((nameCount[name.lowercase()] ?: 0) > 1 && parent.isNotEmpty()) "$name ($parent)" else name
                    DeviceGroup(shown, songs.sortedWith(byTitle), key = "pa:$path")
                }
            }
        }
    return groups.sortedWith(compareBy<DeviceGroup>({ LocalMusic.norm(it.name) }, { it.subtitle.orEmpty() }, { it.key }))
}

@HiltViewModel
class DeviceMusicViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val songs = MutableStateFlow<List<LocalSong>?>(null)
    val isLoading = MutableStateFlow(false)

    fun load(force: Boolean = false) {
        if (isLoading.value || (!force && songs.value != null)) return
        isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            songs.value = runCatching { LocalMusic.load(context) }.getOrDefault(emptyList())
            isLoading.value = false
        }
    }
}

/** "Hoje", "Ontem", "Esta semana" ou "novembro de 2026" — usado nos áudios. */
fun dateLabel(epochSec: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val date = java.time.Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    return when {
        date == today -> "Hoje"
        date == today.minusDays(1) -> "Ontem"
        date.isAfter(today.minusDays(7)) -> "Esta semana"
        else -> date.format(java.time.format.DateTimeFormatter.ofPattern("MMMM 'de' yyyy", java.util.Locale.forLanguageTag("pt-BR")))
            .replaceFirstChar { it.uppercase() }
    }
}
