/**
 * Leve Music — player de vídeo próprio, inspirado no ReVanced:
 * sem anúncios, SponsorBlock, Return YouTube Dislike, escolha de qualidade,
 * velocidade lembrada, repetir, tela cheia e "ouvir só o áudio".
 * Se o vídeo não puder ser tocado direto, cai no player embutido do YouTube.
 */

package com.metrolist.music.ui.screens

import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.playback.queues.LeveVideoQueue
import com.metrolist.music.utils.InnerTubeXPlayer
import com.metrolist.music.utils.LeveVideoDetails
import com.metrolist.music.utils.LeveYouTubeSearch
import com.metrolist.music.utils.rememberPreference
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

private val RvGreen = Color(0xFF1DB954)
private val VideoSpeedKey = floatPreferencesKey("leveVideoSpeed")
private val VideoQualityKey = intPreferencesKey("leveVideoQuality")
private val SponsorBlockKey = booleanPreferencesKey("leveSponsorBlock")

private data class SkipSegment(val startMs: Long, val endMs: Long, val category: String)

private data class Votes(val likes: Long?, val dislikes: Long?)

private object RvApis {
    private val client by lazy { HttpClient(OkHttp) }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun sponsorSegments(videoId: String): List<SkipSegment> =
        runCatching {
            val categories = "[\"sponsor\",\"selfpromo\",\"interaction\",\"intro\",\"outro\",\"music_offtopic\"]"
            val text = client.get("https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=$categories").bodyAsText()
            (json.parseToJsonElement(text) as? JsonArray).orEmpty().mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val seg = obj["segment"] as? JsonArray ?: return@mapNotNull null
                val start = (seg.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                val end = (seg.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                SkipSegment((start * 1000).toLong(), (end * 1000).toLong(), (obj["category"] as? JsonPrimitive)?.contentOrNull ?: "sponsor")
            }
        }.getOrDefault(emptyList())

    suspend fun votes(videoId: String): Votes? =
        runCatching {
            val text = client.get("https://returnyoutubedislikeapi.com/votes?videoId=$videoId").bodyAsText()
            val obj = json.parseToJsonElement(text) as JsonObject
            Votes((obj["likes"] as? JsonPrimitive)?.longOrNull, (obj["dislikes"] as? JsonPrimitive)?.longOrNull)
        }.getOrNull()
}

private fun categoryLabel(category: String) =
    when (category) {
        "sponsor" -> "patrocínio"
        "selfpromo" -> "autopromoção"
        "interaction" -> "pedido de inscrição"
        "intro" -> "abertura"
        "outro" -> "encerramento"
        "music_offtopic" -> "parte sem música"
        else -> "trecho"
    }

private fun compact(n: Long?): String {
    if (n == null) return "?"
    return when {
        n >= 1_000_000 -> String.format("%.1f mi", n / 1_000_000.0)
        n >= 1_000 -> String.format("%.1f mil", n / 1_000.0)
        else -> n.toString()
    }
}

@OptIn(UnstableApi::class)
@Composable
fun LeveVideoScreen(videoId: String) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val activity = remember { context.findActivity() }

    var useEmbed by remember(videoId) { mutableStateOf(false) }
    if (useEmbed) {
        LeveEmbedVideoScreen(videoId)
        return
    }

    var speed by rememberPreference(VideoSpeedKey, 1f)
    var quality by rememberPreference(VideoQualityKey, 720)
    var sponsorBlock by rememberPreference(SponsorBlockKey, true)
    var loop by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var showDescription by remember { mutableStateOf(false) }
    var heights by remember(videoId) { mutableStateOf<List<Int>>(emptyList()) }
    var loading by remember(videoId) { mutableStateOf(true) }

    val details by produceState<LeveVideoDetails?>(null, videoId) {
        value = withContext(Dispatchers.IO) { LeveYouTubeSearch.video(videoId).getOrNull() }
    }
    val votes by produceState<Votes?>(null, videoId) { value = withContext(Dispatchers.IO) { RvApis.votes(videoId) } }
    val segments by produceState<List<SkipSegment>>(emptyList(), videoId) {
        value = withContext(Dispatchers.IO) { RvApis.sponsorSegments(videoId) }
    }

    val exo = remember(videoId) { ExoPlayer.Builder(context).build() }
    DisposableEffect(exo) { onDispose { exo.release() } }

    // Música para enquanto o vídeo toca
    LaunchedEffect(videoId) { playerConnection?.player?.pause() }

    // Carrega (ou recarrega ao trocar a qualidade) mantendo a posição
    LaunchedEffect(videoId, quality) {
        loading = true
        val keepPosition = exo.currentPosition
        val result = withContext(Dispatchers.IO) { InnerTubeXPlayer.leveVideoStream(videoId, quality) }
        result.onSuccess { stream ->
            heights = stream.availableHeights
            val http =
                DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(stream.headers)
                    .apply { stream.headers["User-Agent"]?.let { setUserAgent(it) } }
            val factory = ProgressiveMediaSource.Factory(http)
            exo.setMediaSource(
                MergingMediaSource(
                    factory.createMediaSource(MediaItem.fromUri(stream.videoUrl)),
                    factory.createMediaSource(MediaItem.fromUri(stream.audioUrl)),
                ),
            )
            exo.prepare()
            if (keepPosition > 0) exo.seekTo(keepPosition)
            exo.playbackParameters = PlaybackParameters(speed)
            exo.playWhenReady = true
            loading = false
        }.onFailure {
            useEmbed = true
        }
    }

    // Se o stream direto falhar ao tocar, usa o player embutido
    DisposableEffect(exo) {
        val listener =
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    useEmbed = true
                }
            }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    LaunchedEffect(speed) { exo.playbackParameters = PlaybackParameters(speed) }
    LaunchedEffect(loop) { exo.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }

    // SponsorBlock: pula trechos automaticamente
    LaunchedEffect(segments, sponsorBlock) {
        if (!sponsorBlock || segments.isEmpty()) return@LaunchedEffect
        val skipped = mutableSetOf<Int>()
        while (true) {
            val pos = exo.currentPosition
            segments.forEachIndexed { i, seg ->
                if (i !in skipped && pos >= seg.startMs && pos < seg.endMs - 500) {
                    skipped += i
                    exo.seekTo(seg.endMs)
                    Toast.makeText(context, "Pulou ${categoryLabel(seg.category)}", Toast.LENGTH_SHORT).show()
                }
            }
            delay(400)
        }
    }

    DisposableEffect(fullscreen) {
        activity?.requestedOrientation =
            if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    BackHandler(enabled = fullscreen) { fullscreen = false }

    fun listenAsAudio() {
        val d = details ?: return
        val start =
            SongItem(
                id = d.videoId,
                title = d.title,
                artists = listOf(Artist(name = d.channelName ?: "YouTube", id = d.channelId)),
                musicVideoType = LeveYouTubeSearch.UGC_TYPE,
                thumbnail = "https://i.ytimg.com/vi/${d.videoId}/hqdefault.jpg",
                endpoint = WatchEndpoint(videoId = d.videoId),
            )
        val position = exo.currentPosition
        exo.pause()
        playerConnection?.playQueue(LeveVideoQueue(start, d.related.take(20)))
        scope.launch {
            delay(1200)
            if (position > 3000) playerConnection?.player?.seekTo(position)
        }
        Toast.makeText(context, "Tocando só o áudio (dá pra apagar a tela)", Toast.LENGTH_SHORT).show()
    }

    @Composable
    fun VideoSurface(modifier: Modifier, active: Boolean) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowFastForwardButton(true)
                    setShowRewindButton(true)
                    keepScreenOn = true
                }
            },
            update = { view -> view.player = if (active) exo else null },
            modifier = modifier,
        )
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                VideoSurface(Modifier.fillMaxSize(), active = true)
                Text(
                    "Sair da tela cheia",
                    fontSize = 12.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x99000000))
                        .clickable { fullscreen = false }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }

    LazyColumn(
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "player") {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
            ) {
                VideoSurface(Modifier.fillMaxSize(), active = !fullscreen)
                if (loading) CircularProgressIndicator(color = RvGreen)
            }
        }

        item(key = "controls") {
            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                ) {
                    VideoChip("Velocidade ${if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()}x", highlighted = speed != 1f) {
                        val options = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.5f, 0.75f)
                        speed = options[(options.indexOf(speed).coerceAtLeast(0) + 1) % options.size]
                    }
                    VideoChip(if (loop) "Repetir: sim" else "Repetir: não", highlighted = loop) { loop = !loop }
                    VideoChip("Tela cheia") { fullscreen = true }
                    VideoChip("Ouvir só o áudio", highlighted = true) { listenAsAudio() }
                    VideoChip(if (sponsorBlock) "SponsorBlock: ligado" else "SponsorBlock: desligado", highlighted = sponsorBlock) {
                        sponsorBlock = !sponsorBlock
                    }
                    VideoChip("Compartilhar") {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=$videoId")
                                },
                                null,
                            ),
                        )
                    }
                    VideoChip("Player do YouTube") { useEmbed = true }
                }
                if (heights.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    ) {
                        Text("Qualidade", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        heights.filter { it in 144..2160 }.forEach { h ->
                            VideoChip("${h}p", highlighted = h == quality) { quality = h }
                        }
                    }
                }
                if (segments.isNotEmpty()) {
                    Text(
                        "SponsorBlock: ${segments.size} trecho(s) marcado(s) neste vídeo",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                    )
                }
            }
        }

        item(key = "info") {
            val d = details
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                if (d == null) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = RvGreen)
                } else {
                    Text(d.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val stats = listOfNotNull(
                        d.views,
                        votes?.let { "👍 ${compact(it.likes)}  ·  👎 ${compact(it.dislikes)}" },
                    ).joinToString("   ")
                    if (stats.isNotEmpty()) Text(stats, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { d.channelId?.let { navController.navigate("leve_channel/$it") } }
                            .padding(10.dp),
                    ) {
                        AsyncImage(
                            model = d.channelAvatar,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(d.channelName ?: "Canal", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            d.subscribers?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text("Ver canal ›", fontSize = 13.sp, color = RvGreen, fontWeight = FontWeight.Bold)
                    }
                    if (!d.description.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            d.description,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (showDescription) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { showDescription = !showDescription },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Vídeos parecidos", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Toque pra assistir · segure pra ouvir só o áudio", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // Sem Shorts nos parecidos (como no ReVanced)
        val related = details?.related.orEmpty().filter { (it.duration ?: Int.MAX_VALUE) > 60 }
        itemsIndexed(related, key = { i, v -> "nrel_${i}_${v.id}" }) { index, video ->
            VideoRow(
                video = video,
                onClick = { navController.navigate("leve_video/${video.id}") },
                onLongClick = {
                    exo.pause()
                    playerConnection?.playQueue(LeveVideoQueue(video, related.drop(index + 1).take(20)))
                    Toast.makeText(context, "Tocando o áudio: ${video.title}", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}
