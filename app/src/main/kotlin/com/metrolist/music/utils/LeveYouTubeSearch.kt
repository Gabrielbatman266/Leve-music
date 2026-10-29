/**
 * Leve Music — acesso ao YouTube "normal" (qualquer vídeo, canais e vídeos relacionados)
 */

package com.metrolist.music.utils

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

data class LeveVideoPage(
    val items: List<SongItem>,
    val continuation: String?,
)

data class LeveVideoDetails(
    val videoId: String,
    val title: String,
    val channelName: String?,
    val channelId: String?,
    val channelAvatar: String?,
    val subscribers: String?,
    val views: String?,
    val description: String?,
    val related: List<SongItem>,
)

data class LeveChannelPage(
    val id: String,
    val title: String,
    val avatar: String?,
    val description: String?,
    val videos: List<SongItem>,
    val continuation: String?,
)

object LeveYouTubeSearch {
    /** Filtro "falso" usado só pela tela de busca para identificar a aba "YouTube". */
    val FILTER_ALL_YOUTUBE = YouTube.SearchFilter("leve_all_youtube")

    /** Marca usada nos itens que vêm do YouTube normal (não do YouTube Music). */
    const val UGC_TYPE = "MUSIC_VIDEO_TYPE_UGC"

    private const val CLIENT_VERSION = "2.20251020.01.00"
    private const val VIDEOS_ONLY_PARAMS = "EgIQAQ=="
    private const val CHANNEL_VIDEOS_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"

    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { HttpClient(OkHttp) }

    fun isYouTubeVideo(musicVideoType: String?): Boolean = musicVideoType == UGC_TYPE

    suspend fun search(query: String): Result<LeveVideoPage> =
        runCatching {
            val root = post("search") {
                put("query", query)
                put("params", VIDEOS_ONLY_PARAMS)
            }
            LeveVideoPage(parseVideos(root), findContinuation(root))
        }

    suspend fun continuation(token: String): Result<LeveVideoPage> =
        runCatching {
            val root = post("search") { put("continuation", token) }
            LeveVideoPage(parseVideos(root), findContinuation(root))
        }

    /** Detalhes do vídeo + vídeos relacionados (os mesmos que o YouTube recomenda ao lado). */
    suspend fun video(videoId: String): Result<LeveVideoDetails> =
        runCatching {
            val root = post("next") { put("videoId", videoId) }
            val primary = findFirst(root, "videoPrimaryInfoRenderer") as? JsonObject
            val owner = findFirst(root, "videoOwnerRenderer") as? JsonObject
            val ownerRun = (owner?.obj("title")?.get("runs") as? JsonArray)?.firstOrNull() as? JsonObject
            val description =
                (findFirst(root, "attributedDescription") as? JsonObject)?.str("content")
                    ?: (findFirst(root, "attributedDescriptionBodyText") as? JsonObject)?.str("content")
            val related = parseVideos(root).filter { it.id != videoId }
            LeveVideoDetails(
                videoId = videoId,
                title = primary?.firstRunText("title") ?: "",
                channelName = ownerRun?.str("text"),
                channelId = ownerRun?.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("browseId"),
                channelAvatar = (owner?.obj("thumbnail")?.get("thumbnails") as? JsonArray)?.lastOrNull()?.let { (it as? JsonObject)?.str("url") },
                subscribers = owner?.obj("subscriberCountText")?.str("simpleText"),
                views = (primary?.obj("viewCount")?.obj("videoViewCountRenderer"))?.firstRunText("shortViewCount")
                    ?: (primary?.obj("viewCount")?.obj("videoViewCountRenderer"))?.firstRunText("viewCount"),
                description = description,
                related = related,
            )
        }

    suspend fun channel(channelId: String): Result<LeveChannelPage> =
        runCatching {
            val root = post("browse") {
                put("browseId", channelId)
                put("params", CHANNEL_VIDEOS_PARAMS)
            }
            val meta = findFirst(root, "channelMetadataRenderer") as? JsonObject
            LeveChannelPage(
                id = channelId,
                title = meta?.str("title") ?: "Canal",
                avatar = (meta?.obj("avatar")?.get("thumbnails") as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.str("url") },
                description = meta?.str("description"),
                videos = parseVideos(root, defaultChannel = meta?.str("title"), defaultChannelId = channelId),
                continuation = findContinuation(root),
            )
        }

    suspend fun channelContinuation(channelId: String, channelName: String?, token: String): Result<LeveVideoPage> =
        runCatching {
            val root = post("browse") { put("continuation", token) }
            LeveVideoPage(parseVideos(root, channelName, channelId), findContinuation(root))
        }

    // ---------------- rede ----------------

    private suspend fun post(endpoint: String, extra: JsonObjectBuilder.() -> Unit): JsonElement {
        val locale = Locale.getDefault()
        val body =
            buildJsonObject {
                putJsonObject("context") {
                    putJsonObject("client") {
                        put("clientName", "WEB")
                        put("clientVersion", CLIENT_VERSION)
                        put("hl", locale.language.ifBlank { "pt" })
                        put("gl", locale.country.ifBlank { "BR" })
                    }
                }
                extra()
            }
        val text =
            client.post("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false") {
                contentType(ContentType.Application.Json)
                header("X-YouTube-Client-Name", "1")
                header("X-YouTube-Client-Version", CLIENT_VERSION)
                header("Origin", "https://www.youtube.com")
                header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36",
                )
                setBody(body.toString())
            }.bodyAsText()
        return json.parseToJsonElement(text)
    }

    // ---------------- leitura do JSON ----------------

    private fun walk(element: JsonElement, visit: (String, JsonElement) -> Boolean) {
        when (element) {
            is JsonObject ->
                element.forEach { (key, value) ->
                    // visit devolve true quando já consumiu o objeto (não precisa descer nele)
                    if (!visit(key, value)) walk(value, visit)
                }
            is JsonArray -> element.forEach { walk(it, visit) }
            else -> Unit
        }
    }

    private fun findFirst(root: JsonElement, wanted: String): JsonElement? {
        var found: JsonElement? = null
        walk(root) { key, value ->
            if (found == null && key == wanted) {
                found = value
                true
            } else {
                found != null
            }
        }
        return found
    }

    private fun findContinuation(root: JsonElement): String? =
        (findFirst(root, "continuationCommand") as? JsonObject)?.str("token")

    private fun parseVideos(
        root: JsonElement,
        defaultChannel: String? = null,
        defaultChannelId: String? = null,
    ): List<SongItem> {
        val out = mutableListOf<SongItem>()
        walk(root) { key, value ->
            val obj = value as? JsonObject
            when {
                obj == null -> false
                key == "videoRenderer" || key == "compactVideoRenderer" || key == "gridVideoRenderer" -> {
                    obj.rendererToSongItem(defaultChannel, defaultChannelId)?.let { out += it }
                    true
                }
                key == "lockupViewModel" -> {
                    obj.lockupToSongItem(defaultChannel, defaultChannelId)?.let { out += it }
                    true
                }
                else -> false
            }
        }
        return out.distinctBy { it.id }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.firstRunText(key: String): String? =
        obj(key)?.let { container ->
            ((container["runs"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("text")
                ?: container.str("simpleText")
                ?: container.str("content")
        }

    private fun parseDuration(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val parts = text.split(":").map { it.trim().toIntOrNull() ?: return null }
        return parts.fold(0) { acc, part -> acc * 60 + part }
    }

    private fun thumbFor(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

    private fun buildItem(
        videoId: String,
        title: String,
        channelName: String?,
        channelId: String?,
        duration: Int?,
    ) = SongItem(
        id = videoId,
        title = title,
        artists = listOf(Artist(name = channelName ?: "YouTube", id = channelId)),
        duration = duration,
        musicVideoType = UGC_TYPE,
        thumbnail = thumbFor(videoId),
        endpoint = WatchEndpoint(videoId = videoId),
    )

    private fun JsonObject.rendererToSongItem(defaultChannel: String?, defaultChannelId: String?): SongItem? {
        val videoId = str("videoId") ?: return null
        val title = firstRunText("title") ?: return null
        val channelRun =
            ((obj("ownerText") ?: obj("longBylineText") ?: obj("shortBylineText"))?.get("runs") as? JsonArray)
                ?.firstOrNull() as? JsonObject
        val channelName = channelRun?.str("text") ?: defaultChannel
        val channelId = channelRun?.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("browseId") ?: defaultChannelId
        val duration =
            parseDuration(obj("lengthText")?.str("simpleText"))
                ?: parseDuration((obj("lengthText")?.get("runs") as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.str("text") })
        return buildItem(videoId, title, channelName, channelId, duration)
    }

    private fun JsonObject.lockupToSongItem(defaultChannel: String?, defaultChannelId: String?): SongItem? {
        val type = str("contentType")
        if (type != null && type != "LOCKUP_CONTENT_TYPE_VIDEO") return null
        val videoId = str("contentId") ?: return null
        val meta = obj("metadata")?.obj("lockupMetadataViewModel") ?: return null
        val title = meta.obj("title")?.str("content") ?: return null
        val firstRow =
            ((meta.obj("metadata")?.obj("contentMetadataViewModel")?.get("metadataRows") as? JsonArray)
                ?.firstOrNull() as? JsonObject)
        val channelName =
            ((firstRow?.get("metadataParts") as? JsonArray)?.firstOrNull() as? JsonObject)
                ?.obj("text")?.str("content") ?: defaultChannel
        var durationText: String? = null
        walk(this) { key, value ->
            if (durationText == null && key == "thumbnailBadgeViewModel" && value is JsonObject) {
                durationText = value.str("text")
                true
            } else {
                durationText != null
            }
        }
        return buildItem(videoId, title, channelName, defaultChannelId, parseDuration(durationText))
    }
}
