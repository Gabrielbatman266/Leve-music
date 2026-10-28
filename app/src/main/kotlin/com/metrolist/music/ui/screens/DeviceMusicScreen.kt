/**
 * Leve Music — tela "No celular": músicas guardadas no aparelho
 */

package com.metrolist.music.ui.screens

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.utils.LocalMusic
import com.metrolist.music.utils.LocalSong
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.DeviceGroup
import com.metrolist.music.viewmodels.DeviceMusicViewModel
import com.metrolist.music.viewmodels.DeviceSort
import com.metrolist.music.viewmodels.DeviceTab
import com.metrolist.music.viewmodels.groupedBy
import com.metrolist.music.viewmodels.sortedForDevice

private val LeveGreen = Color(0xFF1DB954)
private val DeviceSortKey = stringPreferencesKey("leveDeviceSort")

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatTotal(ms: Long): String {
    val totalMin = ms / 60000
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}min" else "${m}min"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DeviceMusicScreen(
    viewModel: DeviceMusicViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val allSongs by viewModel.songs.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, LocalMusic.permission) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPermission = granted
            if (granted) viewModel.load(force = true)
        }
    LaunchedEffect(hasPermission) { if (hasPermission) viewModel.load() }

    var tabName by rememberSaveable { mutableStateOf(DeviceTab.SONGS.name) }
    val tab = DeviceTab.valueOf(tabName)
    var sortName by rememberPreference(DeviceSortKey, DeviceSort.A_Z.name)
    val sort = remember(sortName) { runCatching { DeviceSort.valueOf(sortName) }.getOrDefault(DeviceSort.A_Z) }
    var query by rememberSaveable { mutableStateOf("") }
    var openGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    BackHandler(enabled = openGroup != null) { openGroup = null }

    if (!hasPermission) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.leve_phone),
                contentDescription = null,
                tint = LeveGreen,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("Suas músicas do celular", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Permita o acesso aos arquivos de áudio pra tocar as músicas guardadas no aparelho.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { permissionLauncher.launch(LocalMusic.permission) },
                colors = ButtonDefaults.buttonColors(containerColor = LeveGreen, contentColor = Color.Black),
            ) {
                Text("Permitir acesso", fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    // Filtro de busca + ordenação feitos em memória (instantâneo)
    val filtered =
        remember(allSongs, query) {
            val q = query.trim().lowercase()
            val base = allSongs.orEmpty()
            if (q.isEmpty()) base else base.filter { it.titleKey.contains(q) || it.artistKey.contains(q) || it.albumKey.contains(q) }
        }
    val groups = remember(filtered, tab) { if (tab == DeviceTab.SONGS) emptyList() else filtered.groupedBy(tab) }
    val selectedGroup: DeviceGroup? = openGroup?.let { name -> groups.firstOrNull { it.name == name } }
    val visibleSongs: List<LocalSong> =
        remember(filtered, sort, tab, selectedGroup) {
            when {
                selectedGroup != null -> selectedGroup.songs
                tab == DeviceTab.SONGS -> filtered.sortedForDevice(sort)
                else -> emptyList()
            }
        }
    val showingSongs = tab == DeviceTab.SONGS || selectedGroup != null
    val currentId = mediaMetadata?.id

    fun play(songs: List<LocalSong>, index: Int, title: String) {
        if (songs.isEmpty()) return
        if (songs.getOrNull(index)?.id == currentId) {
            playerConnection.togglePlayPause()
            return
        }
        playerConnection.playQueue(
            ListQueue(title = title, items = songs.map { it.toMediaMetadata().toMediaItem() }, startIndex = index),
        )
    }

    fun shuffle(songs: List<LocalSong>, title: String) {
        if (songs.isEmpty()) return
        playerConnection.playQueue(ListQueue(title = title, items = songs.shuffled().map { it.toMediaMetadata().toMediaItem() }))
    }

    LaunchedEffect(tab, sort, openGroup) { listState.scrollToItem(0) }

    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = { viewModel.load(force = true) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Busca
            item(key = "search") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.search),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text("Buscar no celular", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(LeveGreen),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) {
                        Text(
                            "Limpar",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { query = "" }.padding(4.dp),
                        )
                    }
                }
            }

            // Abas
            item(key = "tabs") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    DeviceTab.entries.forEach { t ->
                        val selected = t == tab
                        Text(
                            text = t.label,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (selected) LeveGreen else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable {
                                    tabName = t.name
                                    openGroup = null
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            // Cabeçalho: contagem, duração, ordenar, tocar/aleatório
            item(key = "header") {
                val headerTitle = selectedGroup?.name ?: tab.label
                val count = if (showingSongs) visibleSongs.size else groups.size
                val countLabel =
                    if (showingSongs) {
                        "$count músicas · ${formatTotal(visibleSongs.sumOf { it.durationMs })}"
                    } else {
                        "$count ${tab.label.lowercase()}"
                    }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (selectedGroup != null) {
                            Text(
                                "‹ Voltar",
                                fontSize = 12.sp,
                                color = LeveGreen,
                                modifier = Modifier.clickable { openGroup = null }.padding(vertical = 2.dp),
                            )
                        }
                        Text(
                            headerTitle,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(countLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (tab == DeviceTab.SONGS && selectedGroup == null) {
                        Box {
                            Text(
                                text = sort.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { sortMenuOpen = true }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                                DeviceSort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                option.label,
                                                color = if (option == sort) LeveGreen else MaterialTheme.colorScheme.onSurface,
                                                fontWeight = if (option == sort) FontWeight.Bold else FontWeight.Normal,
                                            )
                                        },
                                        onClick = {
                                            sortName = option.name
                                            sortMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (showingSongs && visibleSongs.isNotEmpty()) {
                        IconButton(onClick = { shuffle(visibleSongs, headerTitle) }) {
                            Icon(painter = painterResource(R.drawable.shuffle), contentDescription = "Aleatório")
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(LeveGreen)
                                .clickable { play(visibleSongs, 0, headerTitle) },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.play),
                                contentDescription = "Tocar",
                                tint = Color.Black,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }

            if (allSongs != null && allSongs.orEmpty().isEmpty() && !isLoading) {
                item(key = "empty") {
                    Text(
                        "Nenhuma música encontrada no celular.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            if (showingSongs) {
                val title = selectedGroup?.name ?: "No celular"
                itemsIndexed(visibleSongs, key = { _, s -> s.mediaStoreId }) { index, song ->
                    LocalSongRow(
                        song = song,
                        active = song.id == currentId,
                        showTrack = selectedGroup != null && tab == DeviceTab.ALBUMS,
                        onClick = { play(visibleSongs, index, title) },
                        onLongClick = {
                            playerConnection.playNext(song.toMediaMetadata().toMediaItem())
                            Toast.makeText(context, "Vai tocar a seguir: ${song.title}", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            } else {
                itemsIndexed(groups, key = { _, g -> "g_${g.name}" }) { _, group ->
                    GroupRow(
                        group = group,
                        round = tab == DeviceTab.ARTISTS,
                        onClick = { openGroup = group.name },
                        onLongClick = { shuffle(group.songs, group.name) },
                    )
                }
            }

            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Artwork(uri: String?, round: Boolean, size: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.dp)
            .clip(if (round) CircleShape else RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Icon(
            painter = painterResource(R.drawable.music_note),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size((size / 2).dp),
        )
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalSongRow(
    song: LocalSong,
    active: Boolean,
    showTrack: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        if (showTrack) {
            Text(
                text = if (song.track > 0) song.track.toString() else "–",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
            )
        } else {
            Artwork(uri = song.artworkUri, round = false, size = 48)
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (active) LeveGreen else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${song.artist} · ${song.album}",
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(formatDuration(song.durationMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(
    group: DeviceGroup,
    round: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Artwork(uri = group.artworkUri, round = round, size = 56)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(group.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${group.songs.size} músicas · ${formatTotal(group.totalMs)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
