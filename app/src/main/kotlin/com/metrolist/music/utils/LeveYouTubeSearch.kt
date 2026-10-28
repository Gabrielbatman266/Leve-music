/**
 * Leve Music — busca em todo o YouTube (qualquer vídeo, não só música)
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

data class LeveVideoPage(
    val items: List<SongItem>,
    val continuation: String?,
)

object LeveYouTubeSearch {
    /** Filtro "falso" usado só pela tela de busca para identificar a aba "YouTube". */
    val FILTER_ALL_YOUTUBE = YouTube.SearchFilter("leve_all_youtube")

    private const val CLIENT_VERSION = "2.20251020.01.00"
    private const val VIDEOS_ONLY_PARAMS = "EgIQAQ=="

    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { HttpClient(OkHttp) }

    suspend fun search(query: String): Result<LeveVideoPage> = request(query = query, continuation = null)

    suspend fun continuation(token: String): Result<LeveVideoPage> = request(query = null, continuation = token)

    private suspend fun request(query: String?, continuation: String?): Result<LeveVideoPage> =
        runCatching {
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
                    if (continuation != null) {
                        put("continuation", continuation)
                    } else {
                        put("query", query.orEmpty())
                        put("params", VIDEOS_ONLY_PARAMS)
                    }
                }
            val text =
                client.post("https://www.youtube.com/youtubei/v1/search?prettyPrint=false") {
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
            val root = json.parseToJsonElement(text)
            val renderers = mutableListOf<JsonObject>()
            var token: String? = null
            walk(root) { key, value ->
                if (key == "videoRenderer" && value is JsonObject) renderers += value
                if (key == "continuationCommand" && value is JsonObject && token == null) {
                    token = value.str("token")
                }
            }
            LeveVideoPage(
                items = renderers.mapNotNull { it.toSongItem() }.distinctBy { it.id },
                continuation = token,
            )
        }

    private fun walk(element: JsonElement, visit: (String, JsonElement) -> Unit) {
        when (element) {
            is JsonObject ->
                element.forEach { (key, value) ->
                    visit(key, value)
                    if (key != "videoRenderer") walk(value, visit)
                }
            is JsonArray -> element.forEach { walk(it, visit) }
            else -> Unit
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.firstRunText(key: String): String? =
        obj(key)?.let { container ->
            (container["runs"] as? JsonArray)?.firstOrNull()?.jsonObject?.str("text")
                ?: container.str("simpleText")
        }

    private fun parseDuration(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return text.split(":").mapNotNull { it.trim().toIntOrNull() }
            .takeIf { it.isNotEmpty() }
            ?.fold(0) { acc, part -> acc * 60 + part }
    }

    private fun JsonObject.toSongItem(): SongItem? {
        val videoId = str("videoId") ?: return null
        val title = firstRunText("title") ?: return null
        val channelRun = (obj("ownerText")?.get("runs") as? JsonArray)?.firstOrNull()?.jsonObject
        val channelName = channelRun?.str("text") ?: firstRunText("longBylineText") ?: "YouTube"
        val channelId =
            channelRun?.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("browseId")
        val thumbnail =
            (obj("thumbnail")?.get("thumbnails") as? JsonArray)
                ?.lastOrNull()?.jsonObject?.str("url")
                ?.substringBefore("?")
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        return SongItem(
            id = videoId,
            title = title,
            artists = listOf(Artist(name = channelName, id = channelId)),
            duration = parseDuration(obj("lengthText")?.str("simpleText")),
            musicVideoType = "MUSIC_VIDEO_TYPE_UGC",
            thumbnail = thumbnail,
            endpoint = WatchEndpoint(videoId = videoId),
        )
    }
}
