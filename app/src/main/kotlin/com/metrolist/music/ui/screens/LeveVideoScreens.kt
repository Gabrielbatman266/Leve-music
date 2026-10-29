/**
 * Leve Music — player de vídeo simples e tela de canal do YouTube
 */

package com.metrolist.music.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.LeveVideoQueue
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.utils.LeveYouTubeSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val VideoGreen = Color(0xFF1DB954)

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun formatSeconds(total: Int?): String {
    if (total == null || total <= 0) return ""
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Abre o canal: usa o id que já temos ou descobre pelo vídeo. */
fun openLeveChannel(
    navController: NavController,
    scope: CoroutineScope,
    context: Context,
    videoId: String,
    channelIdHint: String?,
) {
    if (channelIdHint != null && channelIdHint.startsWith("UC")) {
        navController.navigate("leve_channel/$channelIdHint")
        return
    }
    scope.launch {
        val id = withContext(Dispatchers.IO) { LeveYouTubeSearch.video(videoId).getOrNull()?.channelId }
        if (id != null) {
            navController.navigate("leve_channel/$id")
        } else {
            Toast.makeText(context, "Não achei o canal desse vídeo", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun playerHtml(videoId: String) =
    """
    <!DOCTYPE html><html><head>
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
    <style>html,body{margin:0;padding:0;height:100%;background:#000;overflow:hidden}#p{position:absolute;inset:0;width:100%;height:100%}</style>
    </head><body><div id="p"></div>
    <script>
      var player=null, loop=false;
      var tag=document.createElement('script');tag.src='https://www.youtube.com/iframe_api';document.body.appendChild(tag);
      function onYouTubeIframeAPIReady(){
        player=new YT.Player('p',{videoId:'$videoId',
          playerVars:{autoplay:1,playsinline:1,rel:0,modestbranding:1,fs:0,iv_load_policy:3,origin:'https://www.youtube.com'},
          events:{
            onReady:function(e){e.target.playVideo();},
            onError:function(e){LeveBridge.onError(String(e.data));},
            onStateChange:function(e){ if(e.data===0 && loop){player.seekTo(0,true);player.playVideo();} }
          }});
      }
      function leveSpeed(r){ if(player) player.setPlaybackRate(r); }
      function leveSeek(d){ if(player) player.seekTo(Math.max(0,player.getCurrentTime()+d),true); }
      function leveToggle(){ if(!player) return; if(player.getPlayerState()===1) player.pauseVideo(); else player.playVideo(); }
      function leveLoop(v){ loop=v; }
      function leveTime(){ return player ? player.getCurrentTime() : 0; }
      function levePause(){ if(player) player.pauseVideo(); }
    </script></body></html>
    """.trimIndent()

private class LeveBridge(private val onError: (String) -> Unit) {
    @JavascriptInterface
    fun onError(code: String) = onError.invoke(code)
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LeveVideoScreen(videoId: String) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val activity = remember { context.findActivity() }

    var errorCode by remember(videoId) { mutableStateOf<String?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var loop by remember { mutableStateOf(false) }
    var showDescription by remember { mutableStateOf(false) }

    val details by produceState<com.metrolist.music.utils.LeveVideoDetails?>(null, videoId) {
        value = withContext(Dispatchers.IO) { LeveYouTubeSearch.video(videoId).getOrNull() }
    }

    // Pausa a música para não tocar duas coisas ao mesmo tempo
    LaunchedEffect(videoId) { playerConnection?.player?.pause() }

    val webView =
        remember(videoId) {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                setBackgroundColor(android.graphics.Color.BLACK)
                keepScreenOn = true
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                addJavascriptInterface(LeveBridge { code -> post { errorCode = code } }, "LeveBridge")
                loadDataWithBaseURL("https://www.youtube.com", playerHtml(videoId), "text/html", "utf-8", null)
            }
        }

    DisposableEffect(webView) {
        onDispose {
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }
    DisposableEffect(fullscreen) {
        activity?.requestedOrientation =
            if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    BackHandler(enabled = fullscreen) { fullscreen = false }

    fun js(code: String) = webView.evaluateJavascript(code, null)

    fun listenAsAudio() {
        val start = details?.let { d ->
            SongItem(
                id = d.videoId,
                title = d.title,
                artists = listOf(com.metrolist.innertube.models.Artist(name = d.channelName ?: "YouTube", id = d.channelId)),
                musicVideoType = LeveYouTubeSearch.UGC_TYPE,
                thumbnail = "https://i.ytimg.com/vi/${d.videoId}/hqdefault.jpg",
                endpoint = com.metrolist.innertube.models.WatchEndpoint(videoId = d.videoId),
            )
        } ?: return
        webView.evaluateJavascript("leveTime()") { result ->
            val seconds = result?.toDoubleOrNull() ?: 0.0
            js("levePause()")
            playerConnection?.playQueue(LeveVideoQueue(start, details?.related.orEmpty().take(20)))
            scope.launch {
                kotlinx.coroutines.delay(1200)
                if (seconds > 3) playerConnection?.player?.seekTo((seconds * 1000).toLong())
            }
            Toast.makeText(context, "Tocando só o áudio (dá pra apagar a tela)", Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    fun PlayerSurface(modifier: Modifier) {
        AndroidView(
            factory = {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView
            },
            modifier = modifier,
        )
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                PlayerSurface(Modifier.fillMaxSize())
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
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            ) {
                if (!fullscreen) PlayerSurface(Modifier.fillMaxSize())
                errorCode?.let { code ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize().background(Color(0xEE000000)).padding(16.dp),
                    ) {
                        Text("Esse vídeo não deixa ser assistido fora do YouTube (erro $code).", color = Color.White, fontSize = 14.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            VideoChip("Ouvir só o áudio", highlighted = true) { listenAsAudio() }
                            VideoChip("Abrir no YouTube") {
                                context.startActivity(Intent(Intent.ACTION_VIEW, "https://www.youtube.com/watch?v=$videoId".toUri()))
                            }
                        }
                    }
                }
            }
        }

        // Controles rápidos
        item(key = "controls") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                VideoChip("−10s") { js("leveSeek(-10)") }
                VideoChip("⏯") { js("leveToggle()") }
                VideoChip("+10s") { js("leveSeek(10)") }
                VideoChip("Velocidade ${if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()}x", highlighted = speed != 1f) {
                    val options = listOf(1f, 1.25f, 1.5f, 2f, 0.5f, 0.75f)
                    speed = options[(options.indexOf(speed) + 1) % options.size]
                    js("leveSpeed($speed)")
                }
                VideoChip(if (loop) "Repetir: sim" else "Repetir: não", highlighted = loop) {
                    loop = !loop
                    js("leveLoop($loop)")
                }
                VideoChip("Tela cheia") { fullscreen = true }
                VideoChip("Ouvir só o áudio", highlighted = true) { listenAsAudio() }
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
                VideoChip("Abrir no YouTube") {
                    context.startActivity(Intent(Intent.ACTION_VIEW, "https://www.youtube.com/watch?v=$videoId".toUri()))
                }
            }
        }

        item(key = "info") {
            val d = details
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                if (d == null) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = VideoGreen)
                } else {
                    Text(d.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    d.views?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                        Text("Ver canal ›", fontSize = 13.sp, color = VideoGreen, fontWeight = FontWeight.Bold)
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

        val related = details?.related.orEmpty()
        itemsIndexed(related, key = { i, v -> "rel_${i}_${v.id}" }) { index, video ->
            VideoRow(
                video = video,
                onClick = { navController.navigate("leve_video/${video.id}") },
                onLongClick = {
                    js("levePause()")
                    playerConnection?.playQueue(LeveVideoQueue(video, related.drop(index + 1).take(20)))
                    Toast.makeText(context, "Tocando o áudio: ${video.title}", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}

@Composable
private fun VideoChip(label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 13.sp,
        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
        color = if (highlighted) Color.Black else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (highlighted) VideoGreen else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(video: SongItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Box {
            AsyncImage(
                model = video.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(150.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            val duration = formatSeconds(video.duration)
            if (duration.isNotEmpty()) {
                Text(
                    duration,
                    fontSize = 11.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(video.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                video.artists.firstOrNull()?.name.orEmpty(),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LeveChannelScreen(channelId: String) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()

    val page by produceState<com.metrolist.music.utils.LeveChannelPage?>(null, channelId) {
        value = withContext(Dispatchers.IO) { LeveYouTubeSearch.channel(channelId).getOrNull() }
    }
    val extraVideos = remember(channelId) { mutableStateListOf<SongItem>() }
    var continuation by remember(page) { mutableStateOf(page?.continuation) }
    var loadingMore by remember { mutableStateOf(false) }
    val videos = (page?.videos.orEmpty() + extraVideos).distinctBy { it.id }

    LazyColumn(
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            val p = page
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                if (p == null) {
                    CircularProgressIndicator(color = VideoGreen)
                } else {
                    AsyncImage(
                        model = p.avatar,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(p.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!p.description.isNullOrBlank()) {
                        Text(p.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VideoChip("Ouvir tudo", highlighted = true) {
                            if (videos.isNotEmpty()) {
                                playerConnection?.playQueue(ListQueue(title = p.title, items = videos.map { it.toMediaItem() }))
                            }
                        }
                        VideoChip("Aleatório") {
                            if (videos.isNotEmpty()) {
                                playerConnection?.playQueue(ListQueue(title = p.title, items = videos.shuffled().map { it.toMediaItem() }))
                            }
                        }
                        VideoChip("Abrir no YouTube") {
                            context.startActivity(Intent(Intent.ACTION_VIEW, "https://www.youtube.com/channel/$channelId".toUri()))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Toque pra assistir · segure pra ouvir só o áudio", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        itemsIndexed(videos, key = { i, v -> "ch_${i}_${v.id}" }) { index, video ->
            VideoRow(
                video = video,
                onClick = { navController.navigate("leve_video/${video.id}") },
                onLongClick = {
                    playerConnection?.playQueue(ListQueue(title = page?.title, items = videos.drop(index).map { it.toMediaItem() }))
                    Toast.makeText(context, "Tocando o áudio: ${video.title}", Toast.LENGTH_SHORT).show()
                },
            )
        }

        if (continuation != null) {
            item(key = "more") {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (loadingMore) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = VideoGreen)
                    } else {
                        VideoChip("Carregar mais vídeos") {
                            val token = continuation ?: return@VideoChip
                            loadingMore = true
                            scope.launch {
                                val more = withContext(Dispatchers.IO) {
                                    LeveYouTubeSearch.channelContinuation(channelId, page?.title, token).getOrNull()
                                }
                                if (more != null) {
                                    extraVideos.addAll(more.items)
                                    continuation = more.continuation
                                } else {
                                    continuation = null
                                }
                                loadingMore = false
                            }
                        }
                    }
                }
            }
        }
    }
}
