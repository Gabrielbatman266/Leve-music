/**
 * Leve Music — home simplificada (baseada no Metrolist, GPL-3.0)
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
    val style: com.metrolist.music.utils.LeveStyle = com.metrolist.music.utils.LeveStyle.MUSICA,
) {
    val title: String
        get() {
            val who = seed.artists.firstOrNull()?.name ?: seed.title
            return when {
                style == com.metrolist.music.utils.LeveStyle.VIDEOS -> "Mix vídeos · $who"
                !style.isMusic -> "Mix ${style.label}"
                style == com.metrolist.music.utils.LeveStyle.MUSICA -> "Mix $who"
                else -> "Mix ${style.label} · $who"
            }
        }
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

    // Playlists do usuário (mais recentes primeiro)
    val playlists: StateFlow<List<Playlist>?> =
        database.playlistsByCreateDateAsc()
            .map { it.reversed() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Salva uma lista de músicas como playlist local (pode baixar, editar e tocar inteira).
     * Com [fixedId], a playlist é reaproveitada e o conteúdo é trocado.
     */
    fun savePlaylist(
        name: String,
        songs: List<MediaMetadata>,
        fixedId: String? = null,
        onSaved: (String) -> Unit = {},
    ) {
        if (songs.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val id = fixedId ?: PlaylistEntity.generatePlaylistId()
            database.insert(
                PlaylistEntity(
                    id = id,
                    name = name,
                    bookmarkedAt = LocalDateTime.now(),
                    isEditable = true,
                ),
            )
            if (fixedId != null) database.clearPlaylist(id)
            songs.distinctBy { it.id }.forEachIndexed { index, metadata ->
                database.insert(metadata)
                database.insert(PlaylistSongMap(playlistId = id, songId = metadata.id, position = index))
            }
            withContext(Dispatchers.Main) { onSaved(id) }
        }
    }

    /** Recomendadas: mistura mixes, "feitas pra você" e sugestões das buscas. */
    fun recommendedSongs(): List<MediaMetadata> {
        val fromMixes = mixes.value.orEmpty().flatMap { it.songs.take(8) }.map { it.toMediaMetadata() }
        val fromForYou = madeForYou.value.orEmpty().map { it.toMediaMetadata() }
        val fromSearch = searchSections.value.orEmpty().flatMap { it.songs.take(5) }.map { it.toMediaMetadata() }
        return (fromMixes + fromForYou + fromSearch).distinctBy { it.id }.shuffled().take(60)
    }
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

    // Mixes inteligentes: detecta o estilo do que você ouve (ASMR, meditação, funk, rock…)
    // e cada mix só tem coisas desse estilo. Só cria mix de um estilo que você realmente escuta.
    private suspend fun loadMixes() {
        val listened =
            (
                database.mostPlayedSongs(LocalDateTime.now().minusDays(30), limit = 30).first() +
                    database.recentlyPlayedSongs(40).first()
            ).filterNot { LocalMusic.isLocalId(it.id) }
                .distinctBy { it.id }
        if (listened.isEmpty()) {
            mixes.value = emptyList()
            return
        }
        val byStyle = listened.groupBy {
            com.metrolist.music.utils.LeveStyle.detect(
                it.title + " " + it.artists.joinToString(" ") { a -> a.name },
                it.song.duration,
            )
        }
        // Um mix por estilo (até 3 estilos), e até 2 artistas diferentes do estilo principal de música
        val seeds = mutableListOf<Pair<Song, com.metrolist.music.utils.LeveStyle>>()
        byStyle.entries.sortedByDescending { it.value.size }.take(4).forEach { (style, songs) ->
            songs.distinctBy { it.artists.firstOrNull()?.name ?: it.id }
                .shuffled()
                .take(if (style.isMusic && seeds.none { it.second.isMusic }) 2 else 1)
                .forEach { seeds += it to style }
        }
        mixes.value =
            coroutineScope {
                seeds.take(6).map { (seed, style) ->
                    async { runCatching { buildMix(seed, style) }.getOrNull() }
                }.awaitAll().filterNotNull()
            }
    }

    private fun styleOf(title: String, artists: List<String>) =
        com.metrolist.music.utils.LeveStyle.detect(title, artists)

    private suspend fun buildMix(seed: Song, style: com.metrolist.music.utils.LeveStyle): LeveMix? {
        val picked = linkedMapOf<String, SongItem>()
        fun addAll(items: List<SongItem>?) {
            items.orEmpty().forEach { item ->
                if (item.id == seed.id || picked.containsKey(item.id)) return@forEach
                val s = com.metrolist.music.utils.LeveStyle.detect(
                    item.title + " " + item.artists.joinToString(" ") { it.name },
                    item.duration,
                )
                val fits =
                    if (style.isMusic) {
                        // música: nada de ASMR/podcast/etc; gênero definido = só esse gênero (ou música genérica)
                        s.isMusic && (style == com.metrolist.music.utils.LeveStyle.MUSICA || s == style || s == com.metrolist.music.utils.LeveStyle.MUSICA)
                    } else if (style == com.metrolist.music.utils.LeveStyle.VIDEOS) {
                        !s.isMusic // vídeos longos parecidos, nada de música
                    } else {
                        s == style // ASMR só com ASMR, meditação só com meditação…
                    }
                if (fits) picked[item.id] = item
            }
        }
        if (style.isMusic) {
            addAll(YouTube.next(WatchEndpoint(videoId = seed.id, playlistId = "RDAMVM${seed.id}")).getOrNull()?.items)
        } else {
            // Vídeo "normal": usa os relacionados do YouTube, filtrados pelo estilo
            addAll(com.metrolist.music.utils.LeveYouTubeSearch.video(seed.id).getOrNull()?.related)
        }
        if (picked.size < 12 && style.query.isNotEmpty()) {
            val artist = seed.artists.firstOrNull()?.name.orEmpty()
            val q = if (style.isMusic) "${style.query} $artist" else style.query
            addAll(com.metrolist.music.utils.LeveYouTubeSearch.search(q.trim()).getOrNull()?.items)
        }
        val songs = picked.values.take(30)
        return if (songs.size >= 5) LeveMix(seed, songs, style) else null
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
