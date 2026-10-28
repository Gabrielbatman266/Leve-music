/**
 * Leve Music — home simplificada (baseada no Metrolist, GPL-3.0)
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.utils.LeveYouTubeSearch
import com.metrolist.music.utils.LocalMusic
import com.metrolist.innertube.models.WatchEndpoint
import java.time.LocalDateTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LeveMix(
    val seed: Song,
    val songs: List<SongItem>,
) {
    val title: String get() = "Mix " + (seed.artists.firstOrNull()?.name ?: seed.title)
    val subtitle: String
        get() {
            val names = (seed.artists.map { it.name } + songs.flatMap { s -> s.artists.map { it.name } })
                .distinct()
                .take(3)
            return names.joinToString(", ") + " e mais"
        }
}

data class SearchBasedSection(
    val query: String,
    val songs: List<SongItem>,
)

@HiltViewModel
class LeveHomeViewModel @Inject constructor(
    val database: MusicDatabase,
) : ViewModel() {

    // Tudo que vem do banco local é reativo e barato (sem rede).
    val recentSongs: StateFlow<List<Song>?> =
        database.recentlyPlayedSongs(60)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val likedSongs = MutableStateFlow<List<Song>?>(null)
    val madeForYou = MutableStateFlow<List<Song>?>(null)
    val searchSections = MutableStateFlow<List<SearchBasedSection>?>(null)
    val mixes = MutableStateFlow<List<LeveMix>?>(null)
    val isRefreshing = MutableStateFlow(false)

    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        refresh()
    }

    fun refresh() {
        if (isRefreshing.value) return
        isRefreshing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                coroutineScope {
                    launch { likedSongs.value = database.likedSongsByCreateDateDesc(30, 0) }
                    launch {
                        madeForYou.value = database.quickPicks().first()
                            .distinctBy { it.id }
                            .shuffled()
                            .take(20)
                    }
                    launch { loadSearchSections() }
                    launch { loadMixes() }
                }
            } finally {
                isRefreshing.value = false
            }
        }
    }

    // Mixes: cada um começa com uma música que você já ouve e segue com parecidas (rádio do YouTube Music)
    private suspend fun loadMixes() {
        val seeds =
            (
                database.mostPlayedSongs(LocalDateTime.now().minusDays(30), limit = 15).first() +
                    database.recentlyPlayedSongs(20).first()
            ).filterNot { LocalMusic.isLocalId(it.id) }
                .distinctBy { it.id }
                .distinctBy { it.artists.firstOrNull()?.name ?: it.id }
                .shuffled()
                .take(5)
        if (seeds.isEmpty()) {
            mixes.value = emptyList()
            return
        }
        mixes.value =
            coroutineScope {
                seeds.map { seed ->
                    async {
                        YouTube.next(WatchEndpoint(videoId = seed.id, playlistId = "RDAMVM${seed.id}"))
                            .getOrNull()
                            ?.items
                            ?.filter { it.id != seed.id }
                            ?.distinctBy { it.id }
                            ?.take(30)
                            ?.takeIf { it.size >= 5 }
                            ?.let { LeveMix(seed, it) }
                    }
                }.awaitAll().filterNotNull()
            }
    }

    // Poucas requisições: só as 3 últimas buscas, em paralelo.
    private suspend fun loadSearchSections() {
        val queries = database.searchHistory().first()
            .map { it.query.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(3)
        if (queries.isEmpty()) {
            searchSections.value = emptyList()
            return
        }
        val sections = coroutineScope {
            queries.map { query ->
                async {
                    val musicSongs = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                        .getOrNull()
                        ?.items
                        ?.filterIsInstance<SongItem>()
                        .orEmpty()
                    // Se a busca não for de música (ou trouxer pouco), completa com vídeos do YouTube
                    val videos =
                        if (musicSongs.size < 6) {
                            LeveYouTubeSearch.search(query).getOrNull()?.items.orEmpty()
                        } else {
                            emptyList()
                        }
                    val songs = (musicSongs + videos).distinctBy { it.id }.take(12)
                    if (songs.isEmpty()) null else SearchBasedSection(query, songs)
                }
            }.awaitAll().filterNotNull()
        }
        searchSections.value = sections
    }
}
