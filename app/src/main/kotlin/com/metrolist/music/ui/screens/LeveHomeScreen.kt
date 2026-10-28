/**
 * Leve Music — home no estilo Spotify, leve (baseada no Metrolist, GPL-3.0)
 */

package com.metrolist.music.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.menu.SongMenu
import com.metrolist.music.ui.menu.YouTubeSongMenu
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.viewmodels.LeveHomeViewModel
import java.time.LocalTime

private val SpotifyGreen = Color(0xFF1DB954)

private data class CardData(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnail: String?,
)

private fun Song.toCard() =
    CardData(id, title, artists.joinToString(", ") { it.name }, thumbnailUrl)

private fun SongItem.toCard() =
    CardData(id, title, artists.joinToString(", ") { it.name }, thumbnail)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeveHomeScreen(
    viewModel: LeveHomeViewModel = hiltViewModel(),
) {
    val navController = LocalNavController.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val recentSongs by viewModel.recentSongs.collectAsStateWithLifecycle()
    val likedSongs by viewModel.likedSongs.collectAsStateWithLifecycle()
    val madeForYou by viewModel.madeForYou.collectAsStateWithLifecycle()
    val searchSections by viewModel.searchSections.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    val currentId = mediaMetadata?.id
    val greeting = remember {
        when (LocalTime.now().hour) {
            in 5..11 -> "Bom dia"
            in 12..17 -> "Boa tarde"
            else -> "Boa noite"
        }
    }

    fun playLocal(title: String, songs: List<Song>, index: Int) {
        if (songs.getOrNull(index)?.id == currentId && currentId != null) {
            playerConnection.togglePlayPause()
        } else {
            playerConnection.playQueue(
                ListQueue(title = title, items = songs.map { it.toMediaItem() }, startIndex = index),
            )
        }
    }

    fun playOnline(title: String, songs: List<SongItem>, index: Int) {
        if (songs.getOrNull(index)?.id == currentId && currentId != null) {
            playerConnection.togglePlayPause()
        } else {
            playerConnection.playQueue(
                ListQueue(title = title, items = songs.map { it.toMediaItem() }, startIndex = index),
            )
        }
    }

    fun shuffleLocal(title: String, songs: List<Song>) {
        if (songs.isEmpty()) return
        playerConnection.playQueue(ListQueue(title = title, items = songs.shuffled().map { it.toMediaItem() }))
    }

    val recent = recentSongs.orEmpty()
    val liked = likedSongs.orEmpty()
    val forYou = madeForYou.orEmpty()
    val bySearch = searchSections.orEmpty()
    val everythingEmpty =
        recentSongs != null && likedSongs != null && searchSections != null &&
            recent.isEmpty() && liked.isEmpty() && forYou.isEmpty() && bySearch.isEmpty()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "greeting") {
                Text(
                    text = greeting,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                )
            }

            // Atalhos rápidos (2 colunas), igual ao topo do Spotify
            item(key = "tiles") {
                val tiles = recent.take(if (liked.isNotEmpty()) 5 else 6)
                // null = bloco "Músicas curtidas"; índice >= 0 = música recente
                val cells: List<Int?> =
                    (if (liked.isNotEmpty()) listOf<Int?>(null) else emptyList()) + tiles.indices.map { it }
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    cells.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { cell ->
                                Box(modifier = Modifier.weight(1f)) {
                                    if (cell == null) {
                                        QuickTile(
                                            title = "Músicas curtidas",
                                            thumbnail = null,
                                            isLikedTile = true,
                                            active = false,
                                            onClick = { navController.navigate("auto_playlist/liked") },
                                            onLongClick = {},
                                        )
                                    } else {
                                        val song = tiles[cell]
                                        QuickTile(
                                            title = song.title,
                                            thumbnail = song.thumbnailUrl,
                                            isLikedTile = false,
                                            active = song.id == currentId,
                                            onClick = { playLocal("Tocadas recentemente", recent, cell) },
                                            onLongClick = {
                                                menuState.show { SongMenu(originalSong = song, onDismiss = menuState::dismiss) }
                                            },
                                        )
                                    }
                                }
                            }
                            if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            if (everythingEmpty) {
                item(key = "empty") {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                    ) {
                        Text(
                            text = "Pesquise uma música pra começar",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Suas músicas e sugestões aparecem aqui conforme você ouve e pesquisa.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { navController.navigate(Screens.Search.route) },
                            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                        ) {
                            Text("Pesquisar", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (liked.isNotEmpty()) {
                item(key = "liked") {
                    CardRow(
                        title = "Suas curtidas",
                        subtitle = null,
                        cards = liked.map { it.toCard() },
                        currentId = currentId,
                        onShuffle = { shuffleLocal("Suas curtidas", liked) },
                        onClick = { index -> playLocal("Suas curtidas", liked, index) },
                        onLongClick = { index ->
                            menuState.show { SongMenu(originalSong = liked[index], onDismiss = menuState::dismiss) }
                        },
                    )
                }
            }

            bySearch.forEach { section ->
                item(key = "search_${section.query}") {
                    CardRow(
                        title = "Porque você pesquisou",
                        subtitle = "\"${section.query}\"",
                        cards = section.songs.map { it.toCard() },
                        currentId = currentId,
                        onShuffle = {
                            playerConnection.playQueue(
                                ListQueue(title = section.query, items = section.songs.shuffled().map { it.toMediaItem() }),
                            )
                        },
                        onClick = { index -> playOnline(section.query, section.songs, index) },
                        onLongClick = { index ->
                            menuState.show { YouTubeSongMenu(song = section.songs[index], onDismiss = menuState::dismiss) }
                        },
                    )
                }
            }

            if (forYou.isNotEmpty()) {
                item(key = "for_you") {
                    CardRow(
                        title = "Feitas pra você",
                        subtitle = "Com base no que você ouve",
                        cards = forYou.map { it.toCard() },
                        currentId = currentId,
                        onShuffle = { shuffleLocal("Feitas pra você", forYou) },
                        onClick = { index -> playLocal("Feitas pra você", forYou, index) },
                        onLongClick = { index ->
                            menuState.show { SongMenu(originalSong = forYou[index], onDismiss = menuState::dismiss) }
                        },
                    )
                }
            }

            if (recent.size > 6) {
                item(key = "recent") {
                    CardRow(
                        title = "Tocadas recentemente",
                        subtitle = null,
                        cards = recent.map { it.toCard() },
                        currentId = currentId,
                        onShuffle = { shuffleLocal("Tocadas recentemente", recent) },
                        onClick = { index -> playLocal("Tocadas recentemente", recent, index) },
                        onLongClick = { index ->
                            menuState.show { SongMenu(originalSong = recent[index], onDismiss = menuState::dismiss) }
                        },
                    )
                }
            }

            item(key = "bottom_space") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickTile(
    title: String,
    thumbnail: String?,
    isLikedTile: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (isLikedTile) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .background(Brush.linearGradient(listOf(Color(0xFF450AF5), Color(0xFFC4EFD9)))),
            ) {
                Icon(
                    painter = painterResource(R.drawable.favorite),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            AsyncImage(
                model = thumbnail?.resize(144, 144),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (active) SpotifyGreen else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardRow(
    title: String,
    subtitle: String?,
    cards: List<CardData>,
    currentId: String?,
    onShuffle: () -> Unit,
    onClick: (Int) -> Unit,
    onLongClick: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 24.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, bottom = 10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (subtitle != null) {
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = subtitle,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Text(
                        text = title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            IconButton(
                onClick = onShuffle,
                colors = IconButtonDefaults.iconButtonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.shuffle),
                    contentDescription = "Aleatório",
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(cards, key = { _, card -> card.id }) { index, card ->
                Column(
                    modifier = Modifier
                        .width(140.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .combinedClickable(onClick = { onClick(index) }, onLongClick = { onLongClick(index) }),
                ) {
                    AsyncImage(
                        model = card.thumbnail?.resize(320, 320),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(140.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = card.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (card.id == currentId) SpotifyGreen else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = card.subtitle,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
