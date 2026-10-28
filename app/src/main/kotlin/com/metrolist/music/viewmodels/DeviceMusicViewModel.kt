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
}

data class DeviceGroup(
    val name: String,
    val songs: List<LocalSong>,
) {
    val artworkUri: String? get() = songs.firstOrNull()?.artworkUri
    val totalMs: Long get() = songs.sumOf { it.durationMs }
}

fun List<LocalSong>.sortedForDevice(sort: DeviceSort): List<LocalSong> =
    when (sort) {
        DeviceSort.A_Z -> sortedBy { it.titleKey }
        DeviceSort.Z_A -> sortedByDescending { it.titleKey }
        DeviceSort.NEWEST -> sortedByDescending { it.dateAddedSec }
        DeviceSort.OLDEST -> sortedBy { it.dateAddedSec }
        DeviceSort.ARTIST -> sortedWith(compareBy<LocalSong>({ it.artistKey }, { it.albumKey }, { it.track }, { it.titleKey }))
        DeviceSort.ALBUM -> sortedWith(compareBy<LocalSong>({ it.albumKey }, { it.track }, { it.titleKey }))
        DeviceSort.YEAR -> sortedWith(compareByDescending<LocalSong> { it.year }.thenBy { it.titleKey })
        DeviceSort.LONGEST -> sortedByDescending { it.durationMs }
        DeviceSort.SHORTEST -> sortedBy { it.durationMs }
        DeviceSort.BIGGEST -> sortedByDescending { it.sizeBytes }
    }

fun List<LocalSong>.groupedBy(tab: DeviceTab): List<DeviceGroup> {
    val key: (LocalSong) -> String =
        when (tab) {
            DeviceTab.ARTISTS -> { s -> s.artist }
            DeviceTab.ALBUMS -> { s -> s.album }
            DeviceTab.FOLDERS -> { s -> s.folder }
            DeviceTab.SONGS -> { _ -> "" }
        }
    return groupBy(key)
        .map { (name, songs) ->
            DeviceGroup(
                name = name,
                songs =
                    if (tab == DeviceTab.ALBUMS) {
                        songs.sortedWith(compareBy<LocalSong>({ it.track }, { it.titleKey }))
                    } else {
                        songs.sortedBy { it.titleKey }
                    },
            )
        }.sortedBy { it.name.lowercase() }
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
