/**
 * Leve Music — fila para vídeos do YouTube "normal" (ASMR, podcasts, games...):
 * em vez do rádio de música, segue com os vídeos relacionados do próprio YouTube.
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.LeveYouTubeSearch

class LeveVideoQueue(
    private val start: SongItem,
    private val extra: List<SongItem> = emptyList(),
) : Queue {
    override val preloadItem: MediaMetadata = start.toMediaMetadata()

    private val seen = linkedSetOf(start.id)
    private var lastSeedId: String = start.id
    private var pagesLoaded = 0
    private var exhausted = false

    override suspend fun getInitialStatus(): Queue.Status {
        val related = fetchRelated(start.id)
        val items = (listOf(start) + extra + related).distinctBy { it.id }
        items.forEach { seen += it.id }
        items.lastOrNull()?.let { lastSeedId = it.id }
        return Queue.Status(
            title = "Vídeos parecidos",
            items = items.map { it.toMediaItem() },
            mediaItemIndex = 0,
        )
    }

    override fun hasNextPage(): Boolean = !exhausted && pagesLoaded < 8

    override suspend fun nextPage(): List<MediaItem> {
        pagesLoaded++
        val fresh = fetchRelated(lastSeedId).filter { seen.add(it.id) }.take(15)
        if (fresh.isEmpty()) {
            exhausted = true
            return emptyList()
        }
        lastSeedId = fresh.last().id
        return fresh.map { it.toMediaItem() }
    }

    private suspend fun fetchRelated(videoId: String): List<SongItem> =
        LeveYouTubeSearch.video(videoId).getOrNull()?.related.orEmpty()
}
