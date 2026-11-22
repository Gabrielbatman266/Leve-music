/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.models.filterYoutubeShorts
import com.metrolist.innertube.pages.SearchResult
import com.metrolist.innertube.pages.SearchSummaryPage
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.HideYoutubeShortsKey
import com.metrolist.music.constants.PauseSearchHistoryKey
import com.metrolist.music.models.ItemsPage
import com.metrolist.music.utils.LeveYouTubeSearch
import com.metrolist.music.utils.SearchRoutes
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class OnlineSearchViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val query = SearchRoutes.decodeQuery(savedStateHandle.get<String>("query").orEmpty())
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()

    private suspend fun resolveSearchMetadata(items: List<YTItem>): List<YTItem> =
        coroutineScope {
            val knownDurations =
                items
                    .filterIsInstance<SongItem>()
                    .mapNotNull { song -> song.duration?.let { song.id to it } }
                    .toMap()
            val missingDurationIds =
                items
                    .filterIsInstance<SongItem>()
                    .filter { it.duration == null && it.id !in knownDurations }
                    .map { it.id }
                    .distinct()
            val resolvedArtists = async { YouTube.resolveArtistIds(items) }
            val fetchedDurations =
                async {
                    if (missingDurationIds.isEmpty()) {
                        emptyMap()
                    } else {
                        YouTube
                            .queue(videoIds = missingDurationIds)
                            .getOrDefault(emptyList())
                            .mapNotNull { song -> song.duration?.let { song.id to it } }
                            .toMap()
                    }
                }
            val durations = knownDurations + fetchedDurations.await()

            resolvedArtists.await().map { item ->
                if (item is SongItem && item.duration == null) {
                    item.copy(duration = durations[item.id])
                } else {
                    item
                }
            }
        }

    /** Filtros (ou SUMMARY_KEY) cuja busca falhou: a tela mostra "Tentar de novo" em vez de carregar para sempre. */
    val errorKeys = mutableStateMapOf<String, String>()
    @Volatile private var lastError: Throwable? = null

    private fun noteError(t: Throwable) {
        lastError = t
        reportException(t)
    }
    private var loadJob: Job? = null

    private val hideExplicit get() = context.dataStore.get(HideExplicitKey, false)
    private val hideVideoSongs get() = context.dataStore.get(HideVideoSongsKey, false)
    private val hideYoutubeShorts get() = context.dataStore.get(HideYoutubeShortsKey, false)
    private val recordHistory get() = !context.dataStore.get(PauseSearchHistoryKey, false)

    private fun List<YTItem>.applyUserFilters(): List<YTItem> =
        distinctBy { it.id }
            .filterExplicit(hideExplicit)
            .filterVideoSongs(hideVideoSongs)
            .filterYoutubeShorts(hideYoutubeShorts)

    /** Enriquecimento (id do artista, duração) é opcional: nunca pode travar nem derrubar a busca. */
    private suspend fun resolveSafely(items: List<YTItem>): List<YTItem> =
        withTimeoutOrNull(METADATA_TIMEOUT_MS) {
            runCatching { resolveSearchMetadata(items) }.getOrNull()
        } ?: items

    private suspend fun <T> withNetTimeout(block: suspend () -> Result<T>): Result<T> =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) { block() }
            ?: Result.failure(java.io.IOException("tempo esgotado (${REQUEST_TIMEOUT_MS / 1000}s)"))

    private suspend fun loadSummaryPage(): Boolean {
        if (summaryPage != null) return true
        val result = withNetTimeout {
            YouTube.searchSummary(query, recordSearchHistory = recordHistory)
        }
        val page = result.getOrElse {
            noteError(it)
            return false
        }
        // Mostra já; depois troca pela versão enriquecida.
        summaryPage = page.filterExplicit(hideExplicit).filterVideoSongs(hideVideoSongs).filterYoutubeShorts(hideYoutubeShorts)
        val resolvedItems = resolveSafely(page.summaries.flatMap { it.items })
        var offset = 0
        val resolvedSummaries =
            page.summaries.map { summary ->
                val nextOffset = offset + summary.items.size
                val resolvedSummary = summary.copy(items = resolvedItems.subList(offset, nextOffset))
                offset = nextOffset
                resolvedSummary
            }
        summaryPage =
            page.copy(summaries = resolvedSummaries)
                .filterExplicit(hideExplicit)
                .filterVideoSongs(hideVideoSongs)
                .filterYoutubeShorts(hideYoutubeShorts)
        return true
    }

    /** Busca filtrada do YouTube Music. Se o filtro "Músicas" vier vazio (o YouTube às vezes some com ele), usa as músicas do resumo e depois vídeos. */
    private suspend fun loadFiltered(filter: YouTube.SearchFilter): Boolean {
        val key = filter.value
        val result = withNetTimeout { YouTube.search(query, filter, recordSearchHistory = recordHistory) }
        var page = result.getOrElse {
            noteError(it)
            null
        }
        if (filter == YouTube.SearchFilter.FILTER_SONG && page?.items.isNullOrEmpty()) {
            val fromSummary =
                if (loadSummaryPage()) summaryPage?.summaries.orEmpty().flatMap { it.items }.filterIsInstance<SongItem>() else emptyList()
            page =
                if (fromSummary.isNotEmpty()) {
                    SearchResult(fromSummary, null)
                } else {
                    withNetTimeout { YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO, recordSearchHistory = false) }
                        .getOrNull() ?: page
                }
        }
        if (page == null) return false
        viewStateMap[key] = ItemsPage(page.items.applyUserFilters(), page.continuation)
        val resolved = resolveSafely(page.items)
        if (resolved !== page.items) {
            viewStateMap[key]?.let { current ->
                viewStateMap[key] = current.copy(items = resolved.applyUserFilters())
            }
        }
        return true
    }

    private suspend fun load(filter: YouTube.SearchFilter?) {
        val key = filter?.value ?: SUMMARY_KEY
        errorKeys.remove(key)
        lastError = null
        val ok =
            when {
                filter == null -> loadSummaryPage()
                viewStateMap[filter.value] != null -> true
                filter == LeveYouTubeSearch.FILTER_YT_CHANNELS ->
                    withNetTimeout { LeveYouTubeSearch.searchChannels(query) }
                        .onSuccess { (items, token) -> viewStateMap[filter.value] = ItemsPage(items, token) }
                        .onFailure { noteError(it) }
                        .isSuccess
                filter == LeveYouTubeSearch.FILTER_ALL_YOUTUBE ->
                    withNetTimeout { LeveYouTubeSearch.search(query) }
                        .onSuccess { page -> viewStateMap[filter.value] = ItemsPage(page.items, page.continuation) }
                        .onFailure { noteError(it) }
                        .isSuccess
                filter == YouTube.SearchFilter.FILTER_EPISODE -> {
                    // A API de episódios devolve outro formato; reaproveita a seção "Episodes" do resumo.
                    loadSummaryPage().also { ok ->
                        if (ok) {
                            val episodes = summaryPage?.summaries.orEmpty().firstOrNull { it.title == "Episodes" }?.items.orEmpty()
                            viewStateMap[filter.value] = ItemsPage(episodes, null)
                        }
                    }
                }
                else -> loadFiltered(filter)
            }
        if (!ok) {
            val e = lastError
            errorKeys[key] = e?.let { (it::class.simpleName ?: "Erro") + ": " + (it.message ?: "").take(140) } ?: "sem resposta"
        }
    }

    /** Botão "Tentar de novo". */
    fun retry() {
        val current = filter.value
        loadJob?.cancel()
        loadJob = viewModelScope.launch { load(current) }
    }

    init {
        viewModelScope.launch {
            // collectLatest: trocar de filtro cancela uma busca lenta em vez de esperar por ela.
            filter.collectLatest { f ->
                loadJob?.cancel()
                loadJob = launch { load(f) }
                loadJob?.join()
            }
        }
    }

    companion object {
        const val SUMMARY_KEY = "__summary__"
        private const val REQUEST_TIMEOUT_MS = 20_000L
        private const val METADATA_TIMEOUT_MS = 8_000L
    }

    fun loadMore() {
        val currentFilter = filter.value
        val filterValue = currentFilter?.value ?: return
        viewModelScope.launch {
            val viewState = viewStateMap[filterValue] ?: return@launch
            val continuation = viewState.continuation ?: return@launch
            if (currentFilter == LeveYouTubeSearch.FILTER_YT_CHANNELS) {
                val (items, token) = LeveYouTubeSearch.channelsContinuation(continuation).getOrNull() ?: return@launch
                viewStateMap[filterValue] = ItemsPage((viewState.items + items).distinctBy { it.id }, token)
                return@launch
            }
            if (currentFilter == LeveYouTubeSearch.FILTER_ALL_YOUTUBE) {
                val page = LeveYouTubeSearch.continuation(continuation).getOrNull() ?: return@launch
                viewStateMap[filterValue] = ItemsPage(
                    (viewState.items + page.items).distinctBy { it.id },
                    page.continuation,
                )
                return@launch
            }
            val searchResult =
                YouTube.searchContinuation(
                    continuation,
                    recordSearchHistory = !context.dataStore.get(PauseSearchHistoryKey, false),
                ).getOrNull() ?: return@launch
            val newItems = resolveSafely(searchResult.items).applyUserFilters()
            viewStateMap[filterValue] = ItemsPage(
                (viewState.items + newItems).distinctBy { it.id },
                searchResult.continuation
            )
        }
    }
}
