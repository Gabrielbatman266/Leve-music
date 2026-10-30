/**
 * Leve Music → Leve Launcher TV
 *
 * Manda a música que está tocando no celular para a TV (mesmo Wi-Fi) e mantém os dois
 * sincronizados. O celular continua sendo o "controle": a fila, o pular, a pausa e a barra
 * de tempo do celular mandam na TV. O player do celular fica mudo enquanto a TV toca.
 *
 * Protocolo (HTTP na porta 8977 da TV):
 *   POST /play   {id,title,artist,thumb,url,headers,chunk,pos,playing}
 *   POST /pause  /resume  /stop  /seek?ms=N  /volume?d=±1
 *   GET  /state  → {id,playing,pos,dur,error,cmd,cmdSeq,cmdPos,...}
 * Comandos feitos no controle da TV chegam em "cmd" e são aplicados aqui.
 */
package com.metrolist.music.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.music.utils.InnerTubeXPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

class LeveTvLink(
    private val context: Context,
    private val scope: CoroutineScope,
    private val playerProvider: () -> ExoPlayer?,
    private val resolve: suspend (String) -> InnerTubeXPlayer.PlaybackData?,
    private val setTvMuted: (Boolean) -> Unit,
) {
    data class Tv(val name: String, val host: String, val port: Int = PORT)

    data class TvState(
        val id: String = "",
        val title: String = "",
        val playing: Boolean = false,
        val loading: Boolean = false,
        val pos: Long = 0,
        val dur: Long = 0,
        val vol: Int = -1,
        val error: String = "",
    )

    private val _found = MutableStateFlow<List<Tv>>(emptyList())
    val found: StateFlow<List<Tv>> = _found.asStateFlow()

    private val _connected = MutableStateFlow<Tv?>(null)
    val connected: StateFlow<Tv?> = _connected.asStateFlow()

    private val _state = MutableStateFlow<TvState?>(null)
    val state: StateFlow<TvState?> = _state.asStateFlow()

    /** Mensagens curtas para mostrar ao usuário (toast/snackbar). */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val prefs = context.getSharedPreferences("leve_tv", Context.MODE_PRIVATE)
    val lastTv: Tv?
        get() {
            val host = prefs.getString("host", null) ?: return null
            return Tv(prefs.getString("name", host) ?: host, host, prefs.getInt("port", PORT))
        }

    private var attachedPlayer: ExoPlayer? = null
    private var sendJob: Job? = null
    private var pollJob: Job? = null
    private var lastCmdSeq = -1
    private var sentId: String? = null
    private var applyingRemote = false
    private var failures = 0

    // ================= Conectar / desconectar =================

    fun connect(tv: Tv) {
        if (_connected.value != null) disconnect(sendStop = true)
        _connected.value = tv
        prefs.edit().putString("host", tv.host).putString("name", tv.name).putInt("port", tv.port).apply()
        lastCmdSeq = -1
        sentId = null
        failures = 0
        setTvMuted(true)
        attach()
        sendCurrent()
        startPolling()
        messages.tryEmit("Tocando na ${tv.name}")
    }

    fun disconnect(sendStop: Boolean = true) {
        val tv = _connected.value ?: return
        _connected.value = null
        _state.value = null
        pollJob?.cancel()
        sendJob?.cancel()
        detach()
        setTvMuted(false)
        if (sendStop) scope.launch { post(tv, "/stop") }
    }

    fun release() {
        disconnect(sendStop = true)
        stopDiscovery()
    }

    fun volume(delta: Int) {
        val tv = _connected.value ?: return
        scope.launch { post(tv, "/volume?d=$delta") }
    }

    // ================= Espelhar o player do celular =================

    private val listener =
        object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                sendCurrent()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (applyingRemote) return
                val tv = _connected.value ?: return
                scope.launch { post(tv, if (playWhenReady) "/resume" else "/pause") }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (applyingRemote || reason != Player.DISCONTINUITY_REASON_SEEK) return
                if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) return
                val tv = _connected.value ?: return
                scope.launch { post(tv, "/seek?ms=${newPosition.positionMs}") }
            }
        }

    private fun attach() {
        val p = playerProvider() ?: return
        if (p === attachedPlayer) return
        detach()
        p.addListener(listener)
        attachedPlayer = p
    }

    private fun detach() {
        attachedPlayer?.removeListener(listener)
        attachedPlayer = null
    }

    /** Resolve o link do áudio da música atual e manda para a TV. */
    private fun sendCurrent() {
        val tv = _connected.value ?: return
        val player = playerProvider() ?: return
        val item = player.currentMediaItem ?: return
        val id = item.mediaId
        if (id.isBlank()) return
        sendJob?.cancel()
        sendJob =
            scope.launch {
                val meta = item.mediaMetadata
                val data = runCatching { resolve(id) }.getOrNull()
                if (data == null) {
                    messages.tryEmit("Essa música não dá para tocar na TV")
                    return@launch
                }
                val chunk =
                    if (data.requireBoundedRange || data.useRangeChunks) {
                        data.rangeChunkSizeBytes.takeIf { it > 0 } ?: (1L shl 20)
                    } else {
                        0L
                    }
                val body =
                    JSONObject()
                        .put("id", id)
                        .put("title", meta.title?.toString().orEmpty())
                        .put("artist", (meta.artist ?: meta.albumArtist)?.toString().orEmpty())
                        .put("thumb", meta.artworkUri?.toString().orEmpty())
                        .put("url", data.streamUrl)
                        .put("headers", JSONObject(data.streamHeaders))
                        .put("chunk", chunk)
                        .put("pos", playerProvider()?.currentPosition ?: 0L)
                        .put("playing", playerProvider()?.playWhenReady ?: true)
                if (post(tv, "/play", body.toString()) != null) sentId = id
            }
    }

    // ================= Ler a TV (estado + controle remoto) =================

    private fun startPolling() {
        pollJob?.cancel()
        pollJob =
            scope.launch {
                while (isActive && _connected.value != null) {
                    val tv = _connected.value ?: break
                    attach() // o player pode ter sido trocado (crossfade)
                    val json = get(tv, "/state")
                    if (json == null) {
                        failures++
                        if (failures >= 4) {
                            messages.tryEmit("A TV parou de responder")
                            disconnect(sendStop = false)
                            break
                        }
                    } else {
                        failures = 0
                        handleState(json)
                    }
                    delay(1500)
                }
            }
    }

    private fun handleState(j: JSONObject) {
        val s =
            TvState(
                id = j.optString("id"),
                title = j.optString("title"),
                playing = j.optBoolean("playing"),
                loading = j.optBoolean("loading"),
                pos = j.optLong("pos"),
                dur = j.optLong("dur"),
                vol = j.optInt("vol", -1),
                error = j.optString("error"),
            )
        _state.value = s
        val player = playerProvider() ?: return

        // Comando feito no controle da TV
        val seq = j.optInt("cmdSeq", 0)
        if (lastCmdSeq < 0) {
            lastCmdSeq = seq // ignora comandos antigos de antes de conectar
        } else if (seq != lastCmdSeq) {
            lastCmdSeq = seq
            remote {
                when (j.optString("cmd")) {
                    "next" -> player.seekToNext()
                    "prev" -> player.seekToPrevious()
                    "play" -> player.playWhenReady = true
                    "pause" -> player.playWhenReady = false
                    "seek" -> player.seekTo(j.optLong("cmdPos"))
                }
            }
        }

        // A TV manda no relógio: se o celular (mudo) se adiantar ou atrasar, acompanha a TV
        val sameTrack = s.id.isNotEmpty() && s.id == player.currentMediaItem?.mediaId
        if (sameTrack && s.playing && !s.loading && player.isPlaying &&
            abs(player.currentPosition - s.pos) > 2000
        ) {
            remote { player.seekTo(s.pos) }
        }

        // Se a TV não está com a música certa (ex.: TV reiniciou), manda de novo
        if (s.id.isEmpty() && sentId != null && player.currentMediaItem != null) {
            sentId = null
            sendCurrent()
        }
    }

    private inline fun remote(block: () -> Unit) {
        applyingRemote = true
        try {
            block()
        } finally {
            applyingRemote = false
        }
    }

    // ================= Descobrir TVs na rede =================

    private var discovery: NsdManager.DiscoveryListener? = null
    private val nsd by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }

    fun startDiscovery() {
        if (discovery != null) return
        _found.value = emptyList()
        val l =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}

                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    discovery = null
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

                override fun onServiceLost(info: NsdServiceInfo) {
                    _found.value = _found.value.filterNot { it.name == info.serviceName }
                }

                override fun onServiceFound(info: NsdServiceInfo) {
                    resolveService(info)
                }
            }
        discovery = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { discovery = null }
    }

    fun stopDiscovery() {
        val l = discovery ?: return
        discovery = null
        runCatching { nsd.stopServiceDiscovery(l) }
    }

    @Suppress("DEPRECATION")
    private fun resolveService(info: NsdServiceInfo) {
        runCatching {
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress ?: return
                        if (host.contains(':')) return // ignora IPv6
                        val tv = Tv(resolved.serviceName, host, resolved.port)
                        _found.value = (_found.value.filterNot { it.host == host } + tv)
                    }
                },
            )
        }
    }

    /** Conectar digitando o IP mostrado na TV. Confere se é mesmo um Leve TV antes. */
    suspend fun probe(host: String): Tv? {
        val clean = host.trim().removePrefix("http://").substringBefore('/').substringBefore(':')
        if (clean.isEmpty()) return null
        val j = get(Tv(clean, clean), "/state") ?: return null
        return Tv(j.optString("name").ifBlank { "Leve TV" }, clean)
    }

    // ================= HTTP =================

    private suspend fun get(tv: Tv, path: String): JSONObject? = request(tv, "GET", path, null)

    private suspend fun post(tv: Tv, path: String, body: String = "{}"): JSONObject? = request(tv, "POST", path, body)

    private suspend fun request(tv: Tv, method: String, path: String, body: String?): JSONObject? =
        withContext(Dispatchers.IO) {
            runCatching {
                val c = URL("http://${tv.host}:${tv.port}$path").openConnection() as HttpURLConnection
                c.connectTimeout = 3000
                c.readTimeout = 6000
                c.requestMethod = method
                c.setRequestProperty("X-Leve-Phone", PHONE_NAME)
                if (body != null) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.setFixedLengthStreamingMode(bytes.size)
                    c.outputStream.use { it.write(bytes) }
                }
                val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                c.disconnect()
                JSONObject(text)
            }.getOrNull()
        }

    companion object {
        const val PORT = 8977
        const val SERVICE_TYPE = "_levetv._tcp."
        private val PHONE_NAME = (Build.MODEL ?: "Celular").trim()
    }
}
