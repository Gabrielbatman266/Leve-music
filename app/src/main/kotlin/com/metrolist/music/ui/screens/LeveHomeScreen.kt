/**
 * Leve Music — home no estilo Spotify, leve (baseada no Metrolist, GPL-3.0)
 */

package com.metrolist.music.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metrolist.music.utils.rememberPreference
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.menu.SongMenu
import com.metrolist.music.ui.menu.YouTubeSongMenu
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.viewmodels.LeveHomeViewModel
import java.time.LocalTime
import kotlinx.coroutines.launch

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

// ---- Categorias e temas ocultos (tudo em memória, sem rede) ----

private val HiddenTopicsKey = stringPreferencesKey("leveHiddenTopics")

private enum class HomeCategory(val label: String, val keywords: List<String>) {
    ALL("Tudo", emptyList()),
    MUSIC("Música", emptyList()),
    ASMR("ASMR", listOf("asmr", "tingle", "whisper", "sussurr", "mouth sounds", "tapping")),
    SLEEP("Dormir e relaxar", listOf("sleep", "dormir", "relax", "rain", "chuva", "lofi", "lo-fi", "medita", "white noise", "ruído", "ambient", "calm", "432hz", "binaural")),
    PODCAST("Podcasts", listOf("podcast", "entrevista", "cortes", "episódio", "episode")),
    GAMES("Games", listOf("gameplay", "minecraft", "free fire", "gta", "fortnite", "roblox", "speedrun", "jogando", "detonado")),
    FUNK("Funk", listOf("funk", "mc ", "mtg", "brega", "bregadeira")),
    RAP("Rap e Trap", listOf("rap", "trap", "drill", "freestyle", "boom bap")),
    GOSPEL("Gospel", listOf("gospel", "louvor", "worship", "hino", "adoração")),
    BRASIL("Sertanejo e pagode", listOf("sertanejo", "modão", "pagode", "forró", "piseiro", "arrocha", "samba")),
    ROCK("Rock", listOf("rock", "metal", "punk", "grunge", "hardcore")),
    ELETRONICA("Eletrônica", listOf("remix", "house", "techno", "edm", "phonk", "slowed", "sped up", "eletrônica")),
}

private val NonMusicCategories = listOf(HomeCategory.ASMR, HomeCategory.SLEEP, HomeCategory.PODCAST, HomeCategory.GAMES)

private fun textMatches(text: String, keyword: String): Boolean {
    if (keyword.contains(' ')) return text.contains(keyword)
    // palavra que começa com a chave (evita "rap" casar com "terapia")
    return text.split(Regex("[^\\p{L}\\p{N}]+")).any { it.startsWith(keyword) }
}

private fun HomeCategory.accepts(text: String): Boolean =
    when (this) {
        HomeCategory.ALL -> true
        HomeCategory.MUSIC -> NonMusicCategories.none { cat -> cat.keywords.any { textMatches(text, it) } }
        else -> keywords.any { textMatches(text, it) }
    }

private fun Song.searchText() = (title + " " + artists.joinToString(" ") { it.name }).lowercase()

private fun SongItem.searchText() = (title + " " + artists.joinToString(" ") { it.name }).lowercase()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val mixes by viewModel.mixes.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showCreatePlaylist by remember { mutableStateOf(false) }
    val updateScope = androidx.compose.runtime.rememberCoroutineScope()
    var availableUpdate by remember { mutableStateOf<com.metrolist.music.utils.LeveUpdate?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var updateProgress by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        com.metrolist.music.utils.LeveUpdater.check().onSuccess { availableUpdate = it }
    }

    fun startUpdate(update: com.metrolist.music.utils.LeveUpdate) {
        if (updateProgress != null) return
        updateProgress = 0f
        updateStatus = "Baixando atualização…"
        updateScope.launch {
            com.metrolist.music.utils.LeveUpdater.download(context, update) { p -> updateProgress = p }
                .onSuccess { file ->
                    updateProgress = null
                    val opened = com.metrolist.music.utils.LeveUpdater.install(context, file)
                    updateStatus = if (opened) "Toque em Instalar/Atualizar na tela do Android" else "Libere \"Instalar apps desconhecidos\" pro Leve Music e toque em Atualizar de novo"
                }.onFailure {
                    updateProgress = null
                    updateStatus = "Falhou ao baixar: ${it.message}"
                }
        }
    }

    fun checkUpdateNow() {
        updateStatus = "Procurando atualização…"
        updateScope.launch {
            com.metrolist.music.utils.LeveUpdater.check()
                .onSuccess { found ->
                    availableUpdate = found
                    updateStatus = if (found == null) "Você já está na versão mais nova (#${com.metrolist.music.utils.LeveUpdater.currentBuild})" else null
                }.onFailure { updateStatus = "Sem conexão com o GitHub agora" }
        }
    }
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    val currentId = mediaMetadata?.id
    var showAllTiles by rememberSaveable { mutableStateOf(false) }
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
        val picked = songs.getOrNull(index)
        if (picked?.id == currentId && currentId != null) {
            playerConnection.togglePlayPause()
        } else if (picked != null && com.metrolist.music.utils.LeveYouTubeSearch.isYouTubeVideo(picked.musicVideoType)) {
            playerConnection.playQueue(com.metrolist.music.playback.queues.LeveVideoQueue(picked))
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

    var hiddenRaw by rememberPreference(HiddenTopicsKey, "")
    val hiddenTopics = remember(hiddenRaw) { hiddenRaw.split("\n").map { it.trim().lowercase() }.filter { it.isNotEmpty() } }
    var categoryName by rememberSaveable { mutableStateOf(HomeCategory.ALL.name) }
    val category = HomeCategory.valueOf(categoryName)
    var showHideDialog by remember { mutableStateOf(false) }

    fun hideTopic(topic: String) {
        val t = topic.trim().lowercase()
        if (t.isNotEmpty() && t !in hiddenTopics) hiddenRaw = (hiddenTopics + t).joinToString("\n")
    }

    fun unhideTopic(topic: String) {
        hiddenRaw = hiddenTopics.filterNot { it == topic }.joinToString("\n")
    }

    fun isHidden(text: String) = hiddenTopics.any { text.contains(it) }
    fun keep(text: String) = !isHidden(text) && category.accepts(text)

    val recent = recentSongs.orEmpty().filter { keep(it.searchText()) }
    val liked = likedSongs.orEmpty().filter { keep(it.searchText()) }
    val forYou = madeForYou.orEmpty().filter { keep(it.searchText()) }
    val bySearch =
        searchSections.orEmpty()
            .filterNot { isHidden(it.query.lowercase()) }
            .mapNotNull { section ->
                val queryFits = category != HomeCategory.MUSIC && category != HomeCategory.ALL && category.accepts(section.query.lowercase())
                val songs = section.songs.filter { s ->
                    val text = s.searchText()
                    !isHidden(text) && (queryFits || category.accepts(text))
                }
                if (songs.isEmpty()) null else section.copy(songs = songs)
            }
    val visibleMixes =
        mixes.orEmpty()
            .filterNot { isHidden(it.title.lowercase()) || isHidden(it.seed.searchText()) }
            .filter { mix -> category.accepts(mix.seed.searchText()) || mix.songs.count { category.accepts(it.searchText()) } >= mix.songs.size / 2 }
    val everythingEmpty =
        category == HomeCategory.ALL && hiddenTopics.isEmpty() && recentSongs != null && likedSongs != null && searchSections != null &&
            recent.isEmpty() && liked.isEmpty() && forYou.isEmpty() && bySearch.isEmpty()

    if (showCreatePlaylist) {
        com.metrolist.music.ui.component.CreatePlaylistDialog(
            onDismiss = { showCreatePlaylist = false },
            allowSyncing = false,
        )
    }

    if (showHideDialog) {
        var draft by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showHideDialog = false },
            title = { Text("Ocultar tema") },
            text = {
                Column {
                    Text(
                        "Digite uma palavra (ex: asmr). Tudo que tiver ela no nome some do Início.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    hideTopic(draft)
                    showHideDialog = false
                }) { Text("Ocultar") }
            },
            dismissButton = {
                TextButton(onClick = { showHideDialog = false }) { Text("Cancelar") }
            },
        )
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            modifier = Modifier.fillMaxSize(),
        ) {
            availableUpdate?.let { update ->
                item(key = "update_banner") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SpotifyGreen)
                            .clickable { startUpdate(update) }
                            .padding(14.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Atualização disponível: #${update.build}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            Text(
                                updateProgress?.let { "Baixando… ${(it * 100).toInt()}%" } ?: (updateStatus ?: "Você está na #${com.metrolist.music.utils.LeveUpdater.currentBuild}. Toque pra atualizar"),
                                fontSize = 12.sp,
                                color = Color.Black,
                            )
                        }
                        Text("Atualizar", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }

            item(key = "greeting") {
                Text(
                    text = greeting,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                )
            }

            // Categorias: deslize pro lado
            item(key = "categories") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                ) {
                    Text(
                        text = "▶ YouTube",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFFE53935))
                            .combinedClickable(onClick = { navController.navigate("leve_youtube") })
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    HomeCategory.entries.forEach { cat ->
                        val selected = cat == category
                        Text(
                            text = cat.label,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (selected) SpotifyGreen else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .combinedClickable(onClick = { categoryName = cat.name })
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            // Atalhos rápidos (2 colunas), igual ao topo do Spotify
            item(key = "tiles") {
                // Suas músicas: tudo que você ouve fica salvo aqui (8 visíveis, "Mostrar mais" abre o resto)
                val visibleCount = if (showAllTiles) recent.size else (if (liked.isNotEmpty()) 7 else 8)
                val tiles = recent.take(visibleCount)
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

            if (recent.size > (if (liked.isNotEmpty()) 7 else 8)) {
                item(key = "tiles_more") {
                    Text(
                        text = if (showAllTiles) "Mostrar menos" else "Mostrar mais (${recent.size})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .combinedClickable(onClick = { showAllTiles = !showAllTiles })
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            item(key = "playlists") {
                val tiles = buildList {
                    add(PlaylistTile("new", "Nova playlist", "Monte a sua", null, isAction = true))
                    add(PlaylistTile("top", "Mais ouvidas", "As 50 que você mais escuta", recentSongs.orEmpty().firstOrNull()?.thumbnailUrl))
                    add(PlaylistTile("rec", "Recomendadas pra você", "Toque pra gerar e abrir", mixes.orEmpty().firstOrNull()?.songs?.firstOrNull()?.thumbnail))
                    if (likedSongs.orEmpty().isNotEmpty()) add(PlaylistTile("liked", "Curtidas", "${likedSongs.orEmpty().size}+ músicas", likedSongs.orEmpty().firstOrNull()?.thumbnailUrl))
                    add(PlaylistTile("downloaded", "Baixadas", "Tocam sem internet", null))
                    playlists.orEmpty().forEach { p ->
                        add(PlaylistTile("pl_${p.id}", p.title, "${p.songCount} músicas", p.thumbnails.firstOrNull()))
                    }
                }
                PlaylistRow(
                    tiles = tiles,
                    onClick = { tile ->
                        when {
                            tile.key == "new" -> showCreatePlaylist = true
                            tile.key == "top" -> navController.navigate("top_playlist/50")
                            tile.key == "liked" -> navController.navigate("auto_playlist/liked")
                            tile.key == "downloaded" -> navController.navigate("auto_playlist/downloaded")
                            tile.key == "rec" -> {
                                val songs = viewModel.recommendedSongs()
                                if (songs.isEmpty()) {
                                    android.widget.Toast.makeText(context, "Ouça e pesquise algumas músicas primeiro", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    viewModel.savePlaylist("Recomendadas pra você", songs, fixedId = "LP_LEVE_RECOMMENDED") { id ->
                                        navController.navigate("local_playlist/$id")
                                    }
                                }
                            }
                            tile.key.startsWith("pl_") -> navController.navigate("local_playlist/${tile.key.removePrefix("pl_")}")
                        }
                    },
                )
            }

            visibleMixes.takeIf { it.isNotEmpty() }?.let { mixList ->
                item(key = "mixes") {
                    MixRow(
                        mixes = mixList,
                        onHide = { mix -> hideTopic(mix.seed.artists.firstOrNull()?.name ?: mix.seed.title) },
                        onSave = { mix ->
                            viewModel.savePlaylist(
                                mix.title,
                                listOf(mix.seed.toMediaMetadata()) + mix.songs.map { it.toMediaMetadata() },
                            ) {
                                android.widget.Toast.makeText(context, "Playlist \"${mix.title}\" salva", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        onPlay = { mix ->
                            playerConnection.playQueue(
                                ListQueue(
                                    title = mix.title,
                                    items = listOf(mix.seed.toMediaItem()) + mix.songs.map { it.toMediaItem() },
                                ),
                            )
                        },
                        onShuffle = { mix ->
                            playerConnection.playQueue(
                                ListQueue(
                                    title = mix.title,
                                    items = (listOf(mix.seed.toMediaItem()) + mix.songs.map { it.toMediaItem() }).shuffled(),
                                ),
                            )
                        },
                    )
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
                        onHide = { hideTopic(section.query) },
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

            if (false) {
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

            item(key = "hidden_topics") {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 32.dp)) {
                    Text("Temas ocultos", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Não aparecem no Início (continuam na busca). Toque num tema pra mostrar de novo.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = "+ Ocultar tema",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(SpotifyGreen)
                                .clickable { showHideDialog = true }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                        hiddenTopics.forEach { topic ->
                            Text(
                                text = "$topic  ✕",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable { unhideTopic(topic) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            item(key = "update_check") {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp)) {
                    Text(
                        "Leve Music #${com.metrolist.music.utils.LeveUpdater.currentBuild}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Verificar atualização",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { checkUpdateNow() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    if (availableUpdate == null) {
                        updateStatus?.let {
                            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
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
    onHide: (() -> Unit)? = null,
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
            if (onHide != null) {
                Text(
                    text = "Ocultar",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onHide)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MixRow(
    mixes: List<com.metrolist.music.viewmodels.LeveMix>,
    onHide: (com.metrolist.music.viewmodels.LeveMix) -> Unit,
    onSave: (com.metrolist.music.viewmodels.LeveMix) -> Unit,
    onPlay: (com.metrolist.music.viewmodels.LeveMix) -> Unit,
    onShuffle: (com.metrolist.music.viewmodels.LeveMix) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 24.dp)) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
            Text(
                text = "Seus mixes",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Suas músicas + várias parecidas · segure pra aleatório",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(mixes, key = { _, mix -> "mix_${mix.seed.id}" }) { _, mix ->
                Column(
                    modifier = Modifier
                        .width(150.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .combinedClickable(onClick = { onPlay(mix) }, onLongClick = { onShuffle(mix) }),
                ) {
                    Box(modifier = Modifier.size(150.dp)) {
                        AsyncImage(
                            model = mix.seed.thumbnailUrl?.resize(320, 320),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(150.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(6.dp)
                                .background(SpotifyGreen),
                        )
                        Text(
                            text = "MIX",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(6.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(SpotifyGreen)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                        Text(
                            text = "${mix.songs.size + 1}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(bottom = 12.dp, end = 6.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0x99000000))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = mix.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = mix.subtitle,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            text = "Salvar",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SpotifyGreen,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .combinedClickable(onClick = { onSave(mix) })
                                .padding(vertical = 4.dp),
                        )
                        Text(
                            text = "Ocultar",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .combinedClickable(onClick = { onHide(mix) })
                                .padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private data class PlaylistTile(
    val key: String,
    val title: String,
    val subtitle: String,
    val thumbnail: String?,
    val isAction: Boolean = false,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    tiles: List<PlaylistTile>,
    onClick: (PlaylistTile) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 24.dp)) {
        Text(
            text = "Playlists",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(tiles, key = { _, t -> t.key }) { _, tile ->
                Column(
                    modifier = Modifier
                        .width(120.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .combinedClickable(onClick = { onClick(tile) }),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                when (tile.key) {
                                    "liked" -> Brush.linearGradient(listOf(Color(0xFF450AF5), Color(0xFFC4EFD9)))
                                    "rec" -> Brush.linearGradient(listOf(Color(0xFF1DB954), Color(0xFF0B3D20)))
                                    "top" -> Brush.linearGradient(listOf(Color(0xFFE8115B), Color(0xFF3A0A1C)))
                                    "downloaded" -> Brush.linearGradient(listOf(Color(0xFF2D46B9), Color(0xFF0D1440)))
                                    else -> Brush.linearGradient(listOf(Color(0xFF2E2E2E), Color(0xFF1A1A1A)))
                                },
                            ),
                    ) {
                        if (tile.thumbnail != null && tile.key.startsWith("pl_")) {
                            AsyncImage(
                                model = tile.thumbnail.resize(240, 240),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(120.dp),
                            )
                        } else {
                            Text(
                                text =
                                    when (tile.key) {
                                        "new" -> "+"
                                        "liked" -> "♥"
                                        "top" -> "★"
                                        "rec" -> "✦"
                                        "downloaded" -> "↓"
                                        else -> "♪"
                                    },
                                fontSize = 40.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = tile.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = tile.subtitle,
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
