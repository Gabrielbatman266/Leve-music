/**
 * Leve Music — YouTube de verdade (site mobile) dentro do app, com recursos estilo ReVanced:
 * bloqueio de anúncios, SponsorBlock, sem Shorts, sem "Abrir app", tocar com a tela apagada,
 * velocidade, repetir, timer pra dormir, tela cheia e "ouvir só o áudio" no player do app.
 */

package com.metrolist.music.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.playback.queues.LeveVideoQueue
import com.metrolist.music.utils.LeveYouTubeSearch
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

private val WebGreen = Color(0xFF1DB954)
private val WebSpeedKey = floatPreferencesKey("leveWebSpeed")
private val WebSponsorKey = booleanPreferencesKey("leveWebSponsorBlock")

/** Hosts/caminhos de anúncio e rastreio bloqueados direto na rede. */
private val blockedHosts =
    listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagservices.com", "adservice.google.", "imasdk.googleapis.com", "pagead2.", "ads.youtube.com",
    )
private val blockedPaths =
    listOf("/pagead/", "/api/stats/ads", "/api/stats/atr", "/get_midroll_", "/ptracking", "/ad_data_204", "/generate_204?ad", "/youtubei/v1/log_event")

private fun isBlocked(url: String): Boolean {
    val u = url.lowercase()
    return blockedHosts.any { u.contains(it) } || blockedPaths.any { u.contains(it) }
}

/** Roda antes do site: tira anúncios das respostas do YouTube e finge que a página está sempre visível. */
private val documentStartScript =
    """
    (function(){
      if (window.__leveStart) return; window.__leveStart = true;
      var strip = function(o){
        try {
          if (o && typeof o === 'object') {
            delete o.adPlacements; delete o.playerAds; delete o.adSlots; delete o.adBreakHeartbeatParams;
            if (o.playerResponse) strip(o.playerResponse);
            if (o.response) strip(o.response);
          }
        } catch(e) {}
        return o;
      };
      var P = JSON.parse; JSON.parse = function(){ return strip(P.apply(this, arguments)); };
      var RJ = Response.prototype.json; Response.prototype.json = function(){ return RJ.apply(this, arguments).then(strip); };
      try {
        var _yipr;
        Object.defineProperty(window, 'ytInitialPlayerResponse', { configurable: true,
          set: function(v){ _yipr = strip(v); }, get: function(){ return _yipr; } });
      } catch(e) {}
      // Tocar com a tela apagada / app em segundo plano
      try {
        Object.defineProperty(document, 'hidden', { get: function(){ return false; } });
        Object.defineProperty(document, 'visibilityState', { get: function(){ return 'visible'; } });
      } catch(e) {}
      var stop = function(e){ e.stopImmediatePropagation(); };
      window.addEventListener('visibilitychange', stop, true);
      document.addEventListener('visibilitychange', stop, true);
      window.addEventListener('blur', stop, true);
    })();
    """.trimIndent()

/** Roda com o site carregado: CSS de limpeza, pular anúncio, SponsorBlock, velocidade, repetir. */
private fun pageScript(speed: Float, sponsor: Boolean) =
    """
    (function(){
      window.__leveSpeed = $speed; window.__leveSB = $sponsor;
      if (window.__levePage) return; window.__levePage = true;
      var css = document.createElement('style');
      css.textContent = [
        'ytm-reel-shelf-renderer','ytm-shorts-lockup-view-model','ytm-shorts-lockup-view-model-v2',
        'ytm-pivot-bar-item-renderer:has(a[href^="/shorts"])','a[href^="/shorts"]',
        'ytm-promoted-sparkles-web-renderer','ytm-promoted-video-renderer','ytm-companion-ad-renderer',
        'ad-slot-renderer','ytm-ad-slot-renderer','.ytp-ad-overlay-container','.ytp-ce-element',
        'ytm-statement-banner-renderer','ytm-mealbar-promo-renderer','.mobile-topbar-header-content ytm-button-renderer:has(a[href*="app"])',
        'ytm-paid-content-overlay-renderer','.ytm-autonav-bar'
      ].join(',') + '{display:none!important}';
      (document.head || document.documentElement).appendChild(css);

      var segs = [], segVid = null, skipped = {};
      function vid(){ try { return new URL(location.href).searchParams.get('v'); } catch(e){ return null; } }
      function loadSegs(id){
        segVid = id; segs = []; skipped = {};
        if (!id) return;
        fetch('https://sponsor.ajay.app/api/skipSegments?videoID=' + id +
          '&categories=["sponsor","selfpromo","interaction","intro","outro","music_offtopic"]')
          .then(function(r){ return r.ok ? r.json() : []; })
          .then(function(j){ if (segVid === id) segs = j || []; })
          .catch(function(){});
      }
      function toast(t){ try { LeveYT.toast(t); } catch(e){} }
      setInterval(function(){
        var v = document.querySelector('video');
        var player = document.querySelector('.html5-video-player');
        var id = vid();
        if (id !== segVid) loadSegs(id);
        if (!v) return;
        // Anúncio: acelera até o fim, muta e clica em "pular"
        if (player && player.classList.contains('ad-showing')) {
          v.muted = true;
          if (isFinite(v.duration) && v.duration > 0) v.currentTime = v.duration;
          var b = document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,button[class*="skip"]');
          if (b) b.click();
          return;
        } else if (v.muted && window.__leveWasAd) { v.muted = false; }
        window.__leveWasAd = player && player.classList.contains('ad-showing');
        if (Math.abs(v.playbackRate - window.__leveSpeed) > 0.01) v.playbackRate = window.__leveSpeed;
        if (window.__leveSB) {
          for (var i = 0; i < segs.length; i++) {
            var s = segs[i].segment;
            if (!skipped[i] && v.currentTime >= s[0] && v.currentTime < s[1] - 0.5) {
              skipped[i] = true; v.currentTime = s[1];
              toast('Pulou ' + (segs[i].category === 'sponsor' ? 'patrocínio' : 'trecho: ' + segs[i].category));
            }
          }
        }
      }, 300);
    })();
    """.trimIndent()

private class LeveYTBridge(private val onToast: (String) -> Unit) {
    @android.webkit.JavascriptInterface
    fun toast(text: String) = onToast(text)
}

private fun videoIdOf(url: String?): String? =
    url?.let { runCatching { it.toUri().getQueryParameter("v") }.getOrNull() }

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LeveYouTubeWebScreen(startVideoId: String?) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val activity = remember { context.findActivity() }

    var speed by rememberPreference(WebSpeedKey, 1f)
    var sponsor by rememberPreference(WebSponsorKey, true)
    var loop by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var sleepJob by remember { mutableStateOf<Job?>(null) }
    var sleepLabel by remember { mutableStateOf("Timer") }
    var votes by remember { mutableStateOf<String?>(null) }

    // A música do app para enquanto o YouTube toca
    LaunchedEffect(Unit) { playerConnection?.player?.pause() }

    val webView =
        remember {
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(android.graphics.Color.BLACK)
                keepScreenOn = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                addJavascriptInterface(
                    LeveYTBridge { text -> post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() } },
                    "LeveYT",
                )
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(
                        this,
                        documentStartScript,
                        setOf("https://m.youtube.com", "https://www.youtube.com", "https://youtube.com"),
                    )
                }
            }
        }

    fun injectPage() = webView.evaluateJavascript(pageScript(speed, sponsor), null)

    fun js(code: String) = webView.evaluateJavascript(code, null)

    DisposableEffect(webView) {
        webView.webViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (isBlocked(request.url.toString())) {
                        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    } else {
                        null
                    }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val host = request.url.host.orEmpty()
                    // Fica dentro do YouTube; links de fora abrem no navegador
                    if (host.contains("youtube.com") || host.contains("youtu.be") || host.contains("google.com")) return false
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        view.evaluateJavascript(documentStartScript, null)
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    injectPage()
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    currentUrl = url
                    injectPage()
                }
            }
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    fullscreenView = view
                    fullscreenCallback = callback
                }

                override fun onHideCustomView() {
                    fullscreenView = null
                    fullscreenCallback = null
                }
            }
        webView.loadUrl(
            if (startVideoId.isNullOrBlank()) "https://m.youtube.com/" else "https://m.youtube.com/watch?v=$startVideoId",
        )
        onDispose {
            sleepJob?.cancel()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    // Reaplica velocidade/SponsorBlock quando mudam
    LaunchedEffect(speed, sponsor) { js("window.__leveSpeed=$speed; window.__leveSB=$sponsor;") }
    LaunchedEffect(loop) { js("var v=document.querySelector('video'); if(v) v.loop=$loop;") }

    // Likes e dislikes (Return YouTube Dislike) do vídeo aberto
    val currentVideo = videoIdOf(currentUrl)
    LaunchedEffect(currentVideo) {
        votes = null
        val id = currentVideo ?: return@LaunchedEffect
        votes =
            withContext(Dispatchers.IO) {
                runCatching {
                    val text = java.net.URL("https://returnyoutubedislikeapi.com/votes?videoId=$id").readText()
                    val likes = Regex("\"likes\":(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()
                    val dislikes = Regex("\"dislikes\":(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()
                    "👍 ${shortCount(likes)}  👎 ${shortCount(dislikes)}"
                }.getOrNull()
            }
    }

    DisposableEffect(fullscreenView) {
        activity?.requestedOrientation =
            if (fullscreenView != null) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    BackHandler(enabled = fullscreenView != null || webView.canGoBack()) {
        if (fullscreenView != null) {
            fullscreenCallback?.onCustomViewHidden()
            fullscreenView = null
        } else {
            webView.goBack()
        }
    }

    fun listenAsAudio() {
        val id = videoIdOf(webView.url)
        if (id == null) {
            Toast.makeText(context, "Abra um vídeo primeiro", Toast.LENGTH_SHORT).show()
            return
        }
        webView.evaluateJavascript("(function(){var v=document.querySelector('video'); if(!v) return 0; v.pause(); return v.currentTime;})()") { result ->
            val seconds = result?.toDoubleOrNull() ?: 0.0
            scope.launch {
                val details = withContext(Dispatchers.IO) { LeveYouTubeSearch.video(id).getOrNull() }
                val start =
                    SongItem(
                        id = id,
                        title = details?.title ?: webView.title.orEmpty().removeSuffix(" - YouTube"),
                        artists = listOf(Artist(name = details?.channelName ?: "YouTube", id = details?.channelId)),
                        musicVideoType = LeveYouTubeSearch.UGC_TYPE,
                        thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                        endpoint = WatchEndpoint(videoId = id),
                    )
                playerConnection?.playQueue(LeveVideoQueue(start, details?.related.orEmpty().take(20)))
                delay(1200)
                if (seconds > 3) playerConnection?.player?.seekTo((seconds * 1000).toLong())
            }
            Toast.makeText(context, "Tocando só o áudio no Leve (dá pra apagar a tela)", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleSleepTimer() {
        val options = listOf(15, 30, 60, 0)
        val currentMinutes = sleepLabel.removePrefix("Timer ").removeSuffix(" min").toIntOrNull() ?: 0
        val next = options[(options.indexOf(currentMinutes).coerceAtLeast(-1) + 1) % options.size]
        sleepJob?.cancel()
        if (next == 0) {
            sleepLabel = "Timer"
            return
        }
        sleepLabel = "Timer $next min"
        sleepJob =
            scope.launch {
                delay(next * 60_000L)
                js("var v=document.querySelector('video'); if(v) v.pause();")
                sleepLabel = "Timer"
                Toast.makeText(context, "Timer: vídeo pausado", Toast.LENGTH_SHORT).show()
            }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Barra Leve (estilo ReVanced)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            WebChip("Ouvir só o áudio", highlighted = true) { listenAsAudio() }
            WebChip("Velocidade ${if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()}x", highlighted = speed != 1f) {
                val options = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.5f, 0.75f)
                speed = options[(options.indexOf(speed).coerceAtLeast(0) + 1) % options.size]
            }
            WebChip(if (sponsor) "SponsorBlock ✓" else "SponsorBlock ✗", highlighted = sponsor) { sponsor = !sponsor }
            WebChip(if (loop) "Repetir ✓" else "Repetir", highlighted = loop) { loop = !loop }
            WebChip(sleepLabel, highlighted = sleepLabel != "Timer") { toggleSleepTimer() }
            votes?.let { WebChip(it) {} }
            WebChip("Canal") {
                val id = videoIdOf(webView.url)
                if (id != null) openLeveChannel(navController, scope, context, id, null)
            }
            WebChip("Compartilhar") {
                val url = webView.url ?: return@WebChip
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url.replace("m.youtube.com", "youtube.com"))
                        },
                        null,
                    ),
                )
            }
            WebChip("Recarregar") { webView.reload() }
            WebChip("Player Leve (beta)") {
                videoIdOf(webView.url)?.let { id ->
                    js("var v=document.querySelector('video'); if(v) v.pause();")
                    navController.navigate("leve_video_native/$id")
                }
            }
        }

        AndroidView(
            factory = {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }

    // Tela cheia do próprio YouTube
    fullscreenView?.let { view ->
        Dialog(
            onDismissRequest = {
                fullscreenCallback?.onCustomViewHidden()
                fullscreenView = null
            },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            (view.parent as? ViewGroup)?.removeView(view)
                            addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun shortCount(n: Long?): String =
    when {
        n == null -> "?"
        n >= 1_000_000 -> String.format("%.1f mi", n / 1_000_000.0)
        n >= 1_000 -> String.format("%.1f mil", n / 1_000.0)
        else -> n.toString()
    }

@Composable
private fun WebChip(label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 13.sp,
        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
        color = if (highlighted) Color.Black else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (highlighted) WebGreen else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
