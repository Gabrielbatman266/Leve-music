/**
 * Leve Music — leitura de links do YouTube (sem Android: dá para testar no PC)
 */

package com.metrolist.music.utils

object LeveYouTubeLinks {
    private val idRe = Regex("^[\\w-]{11}$")

    private val blockedHosts =
        listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
            "googletagservices.com", "imasdk.googleapis.com", "ads.youtube.com",
        )
    private val blockedHostPrefixes = listOf("pagead2.", "adservice.google.")
    private val blockedPaths =
        listOf("/pagead/", "/api/stats/ads", "/api/stats/atr", "/get_midroll_", "/ptracking", "/ad_data_204", "/youtubei/v1/log_event")

    private fun hostAndPath(url: String): Pair<String, String>? {
        val noScheme = url.substringAfter("://", "")
        if (noScheme.isEmpty()) return null
        val hostEnd = noScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) noScheme.length else it }
        val host = noScheme.substring(0, hostEnd).substringAfterLast('@').substringBefore(':').lowercase()
        val rest = noScheme.substring(hostEnd)
        val path = rest.substringBefore('#').substringBefore('?').ifEmpty { "/" }
        return host to path
    }

    private fun query(url: String): Map<String, String> {
        val q = url.substringBefore('#').substringAfter('?', "")
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull { part ->
            val k = part.substringBefore('=')
            if (k.isEmpty()) null else k to runCatching { java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
        }.toMap()
    }

    /**
     * Anúncio/rastreio? Olha só o endereço do site e o caminho — nunca o texto da busca
     * (antes pesquisar "doubleclick.net" deixava a página em branco).
     */
    fun isAdUrl(url: String): Boolean {
        val (host, path) = hostAndPath(url) ?: return false
        if (blockedHosts.any { host == it || host.endsWith(".$it") }) return true
        if (blockedHostPrefixes.any { host.startsWith(it) }) return true
        val p = path.lowercase()
        if (blockedPaths.any { p.startsWith(it) || p.contains(it) }) return true
        return p == "/generate_204" && query(url).containsKey("ad")
    }

    /** Id do vídeo em qualquer formato: watch?v=, youtu.be/, /shorts/, /live/, /embed/, /v/. */
    fun videoId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val (host, path) = hostAndPath(url.trim()) ?: return null
        if (!host.endsWith("youtube.com") && host != "youtu.be" && !host.endsWith("youtube-nocookie.com")) return null
        query(url)["v"]?.takeIf { idRe.matches(it) }?.let { return it }
        val segs = path.split('/').filter { it.isNotEmpty() }
        val candidate =
            when {
                host == "youtu.be" -> segs.firstOrNull()
                segs.size >= 2 && segs[0] in setOf("shorts", "live", "embed", "v", "e") -> segs[1]
                else -> null
            }
        return candidate?.takeIf { idRe.matches(it) }
    }

    /** Canal pelo link: "UC…" ou "@nome" (a tela de canal entende os dois). */
    fun channelRef(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val (host, path) = hostAndPath(url.trim()) ?: return null
        if (!host.endsWith("youtube.com")) return null
        val segs = path.split('/').filter { it.isNotEmpty() }
        val first = segs.firstOrNull() ?: return null
        return when {
            first.startsWith("@") && first.length > 1 -> runCatching { java.net.URLDecoder.decode(first, "UTF-8") }.getOrDefault(first)
            first == "channel" && segs.getOrNull(1)?.startsWith("UC") == true -> segs[1]
            else -> null
        }
    }
}
