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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.alpha
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
private fun pageScript(speed: Float, sponsor: Boolean, amoled: Boolean, hideShorts: Boolean) =
    """
    (function(){
      window.__leveSpeed = $speed; window.__leveSB = $sponsor;
      var t = document.getElementById('leve-theme');
      if (!t) { t = document.createElement('style'); t.id = 'leve-theme'; (document.head || document.documentElement).appendChild(t); }
      t.textContent = ($amoled ? 'html,body,ytm-app,#app,ytm-mobile-topbar-renderer,.mobile-topbar-header,ytm-pivot-bar-renderer,ytm-browse,ytm-watch,ytm-single-column-watch-next-results-renderer,ytm-item-section-renderer,.page-container,ytm-engagement-panel{background:#000!important;background-color:#000!important}' : '') +
        ($hideShorts ? 'ytm-reel-shelf-renderer,ytm-shorts-lockup-view-model,ytm-shorts-lockup-view-model-v2,ytm-pivot-bar-item-renderer:has(a[href^="/shorts"]),ytm-rich-section-renderer:has(a[href^="/shorts"]){display:none!important}' : '');
      if (window.__levePage) return; window.__levePage = true;
      var css = document.createElement('style');
      css.textContent = [
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

private val WebThemeKey = androidx.datastore.preferences.core.stringPreferencesKey("leveWebTheme")
private val WebShortsKey = booleanPreferencesKey("leveWebHideShorts")

/** Um WebView só, reaproveitado: o 2º vídeo em diante abre bem mais rápido. */
private object LeveWebHolder {
    var view: WebView? = null
    var wrapper: android.content.MutableContextWrapper? = null
}

/** Tema do site: f6=400 é o modo escuro do próprio YouTube. */
private fun applyYouTubeTheme(theme: String) {
    val cm = CookieManager.getInstance()
    val current = cm.getCookie("https://m.youtube.com").orEmpty()
    val pref =
        current.split(";").map { it.trim() }.firstOrNull { it.startsWith("PREF=") }?.removePrefix("PREF=").orEmpty()
    val parts = pref.split("&").filter { it.isNotBlank() && !it.startsWith("f6=") }.toMutableList()
    if (theme != "claro") parts += "f6=400"
    val value = "PREF=" + parts.joinToString("&") + "; domain=.youtube.com; path=/; max-age=63072000"
    cm.setCookie("https://m.youtube.com", value)
    cm.setCookie("https://www.youtube.com", value)
    cm.flush()
}

private fun setSystemBarsHidden(window: android.view.Window?, hidden: Boolean) {
    window ?: return
    val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
    if (hidden) {
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    } else {
        controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }
}

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
    var theme by rememberPreference(WebThemeKey, "escuro")
    var hideShorts by rememberPreference(WebShortsKey, true)
    var loop by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0) }
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var sleepJob by remember { mutableStateOf<Job?>(null) }
    var sleepMinutes by remember { mutableStateOf(0) }
    var votes by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { playerConnection?.player?.pause() }

    val webView =
        remember {
            applyYouTubeTheme(theme)
            val existing = LeveWebHolder.view
            if (existing != null) {
                LeveWebHolder.wrapper?.baseContext = context
                existing
            } else {
                val wrapper = android.content.MutableContextWrapper(context)
                WebView(wrapper).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    addJavascriptInterface(
                        LeveYTBridge { text -> post { Toast.makeText(this.context, text, Toast.LENGTH_SHORT).show() } },
                        "LeveYT",
                    )
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            documentStartScript,
                            setOf("https://m.youtube.com", "https://www.youtube.com", "https://youtube.com"),
                        )
                    }
                    LeveWebHolder.view = this
                    LeveWebHolder.wrapper = wrapper
                }
            }
        }

    fun injectPage() = webView.evaluateJavascript(pageScript(speed, sponsor, theme == "preto", hideShorts), null)

    fun js(code: String) = webView.evaluateJavascript(code, null)

    DisposableEffect(webView) {
        webView.onResume()
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
                    if (host.contains("youtube.com") || host.contains("youtu.be") || host.contains("google.com")) return false
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        view.evaluateJavascript(documentStartScript, null)
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) = injectPage()

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    currentUrl = url
                    injectPage()
                }
            }
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress
                }

                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    fullscreenView = view
                    fullscreenCallback = callback
                }

                override fun onHideCustomView() {
                    fullscreenView = null
                    fullscreenCallback = null
                }
            }
        val target = if (startVideoId.isNullOrBlank()) null else "https://m.youtube.com/watch?v=$startVideoId"
        when {
            webView.url.isNullOrBlank() || webView.url == "about:blank" -> webView.loadUrl(target ?: "https://m.youtube.com/")
            target != null && videoIdOf(webView.url) != startVideoId -> webView.loadUrl(target)
            else -> {
                currentUrl = webView.url
                injectPage()
            }
        }
        onDispose {
            sleepJob?.cancel()
            // Não destrói: só pausa e solta da tela, pra reabrir rápido
            webView.evaluateJavascript("var v=document.querySelector('video'); if(v) v.pause();", null)
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.onPause()
        }
    }

    LaunchedEffect(speed, sponsor, theme, hideShorts) { injectPage() }
    LaunchedEffect(loop) { js("var v=document.querySelector('video'); if(v) v.loop=$loop;") }

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
                    "👍 ${shortCount(likes)}   👎 ${shortCount(dislikes)}"
                }.getOrNull()
            }
    }

    DisposableEffect(fullscreenView) {
        val on = fullscreenView != null
        activity?.requestedOrientation =
            if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        setSystemBarsHidden(activity?.window, on)
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            setSystemBarsHidden(activity?.window, false)
        }
    }

    BackHandler(enabled = menuOpen || fullscreenView != null || webView.canGoBack()) {
        when {
            menuOpen -> menuOpen = false
            fullscreenView != null -> {
                fullscreenCallback?.onCustomViewHidden()
                fullscreenView = null
            }
            else -> webView.goBack()
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

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepMinutes = minutes
        if (minutes == 0) return
        sleepJob =
            scope.launch {
                delay(minutes * 60_000L)
                js("var v=document.querySelector('video'); if(v) v.pause();")
                sleepMinutes = 0
                Toast.makeText(context, "Timer: vídeo pausado", Toast.LENGTH_SHORT).show()
            }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Barrinha de carregamento fina no topo
        if (progress in 1..99) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress / 100f },
                color = WebGreen,
                trackColor = Color.Transparent,
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
            )
        }

        // Engrenagem discreta (meio transparente)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 150.dp)
                .alpha(if (menuOpen) 1f else 0.45f)
                .size(46.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color(0xCC202020))
                .clickable { menuOpen = !menuOpen },
        ) {
            Text("⚙", fontSize = 22.sp, color = Color.White)
        }

        if (menuOpen) {
            // Fundo escuro: toque fora fecha
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x88000000))
                    .clickable { menuOpen = false },
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(Color(0xFF181818))
                    .clickable(enabled = false) {}
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 120.dp),
            ) {
                Text("Leve · YouTube", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                votes?.let { Text(it, fontSize = 13.sp, color = Color(0xFFB3B3B3)) }

                MenuSection("Reprodução") {
                    WebChip("Ouvir só o áudio", highlighted = true) {
                        menuOpen = false
                        listenAsAudio()
                    }
                    WebChip("Velocidade ${if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()}x", highlighted = speed != 1f) {
                        val options = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.5f, 0.75f)
                        speed = options[(options.indexOf(speed).coerceAtLeast(0) + 1) % options.size]
                    }
                    WebChip(if (loop) "Repetir ✓" else "Repetir", highlighted = loop) { loop = !loop }
                    WebChip(if (sleepMinutes == 0) "Timer" else "Timer $sleepMinutes min", highlighted = sleepMinutes != 0) {
                        val options = listOf(15, 30, 60, 0)
                        setSleepTimer(options[(options.indexOf(sleepMinutes) + 1) % options.size])
                    }
                }
                MenuSection("Limpeza") {
                    WebChip(if (sponsor) "SponsorBlock ✓" else "SponsorBlock", highlighted = sponsor) { sponsor = !sponsor }
                    WebChip(if (hideShorts) "Sem Shorts ✓" else "Mostrar Shorts", highlighted = hideShorts) { hideShorts = !hideShorts }
                    WebChip("Anúncios bloqueados ✓", highlighted = true) {}
                }
                MenuSection("Tema") {
                    listOf("escuro" to "Escuro", "preto" to "Preto (AMOLED)", "claro" to "Claro").forEach { (key, label) ->
                        WebChip(label, highlighted = theme == key) {
                            if (theme != key) {
                                val needsReload = (theme == "claro") != (key == "claro")
                                theme = key
                                applyYouTubeTheme(key)
                                if (needsReload) webView.reload()
                            }
                        }
                    }
                }
                MenuSection("Mais") {
                    WebChip("Canal") {
                        menuOpen = false
                        videoIdOf(webView.url)?.let { openLeveChannel(navController, scope, context, it, null) }
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
                    WebChip("Recarregar") {
                        menuOpen = false
                        webView.reload()
                    }
                    WebChip("Player Leve (beta)") {
                        menuOpen = false
                        videoIdOf(webView.url)?.let { id ->
                            js("var v=document.querySelector('video'); if(v) v.pause();")
                            navController.navigate("leve_video_native/$id")
                        }
                    }
                }
            }
        }
    }

    // Tela cheia de verdade: sem barra de notificação e sem botões do sistema
    fullscreenView?.let { view ->
        Dialog(
            onDismissRequest = {
                fullscreenCallback?.onCustomViewHidden()
                fullscreenView = null
            },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            LaunchedEffect(dialogWindow) {
                dialogWindow?.let { w ->
                    w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        w.attributes = w.attributes.apply {
                            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                    }
                    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
                    setSystemBarsHidden(w, true)
                }
            }
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

@Composable
private fun MenuSection(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        fontSize = 12.sp,
        color = Color(0xFFB3B3B3),
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) { content() }
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
        color = if (highlighted) Color.Black else Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (highlighted) WebGreen else Color(0xFF2A2A2A))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}
