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
        database.recentlyPlayedSongs(30)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val likedSongs = MutableStateFlow<List<Song>?>(null)
    val madeForYou = MutableStateFlow<List<Song>?>(null)
    val searchSections = MutableStateFlow<List<SearchBasedSection>?>(null)
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
                }
            } finally {
                isRefreshing.value = false
            }
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
                    val songs = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                        .getOrNull()
                        ?.items
                        ?.filterIsInstance<SongItem>()
                        ?.distinctBy { it.id }
                        ?.take(12)
                        .orEmpty()
                    if (songs.isEmpty()) null else SearchBasedSection(query, songs)
                }
            }.awaitAll().filterNotNull()
        }
        searchSections.value = sections
    }
}
