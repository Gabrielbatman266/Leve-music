package com.metrolist.music.leve

import com.metrolist.music.leve.Placar.budget
import com.metrolist.music.leve.Placar.fail
import com.metrolist.music.leve.Placar.run
import com.metrolist.music.leve.Placar.timed
import com.metrolist.music.utils.LeveYouTubeSearch
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * YouTube de verdade (internet): busca de vídeos, canais, @nomes, páginas seguintes, vídeos
 * relacionados, respostas quebradas e internet ruim. Do fácil (N1) ao impossível (N10).
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class YouTubeStressTest {
    private val Y = LeveYouTubeSearch
    private val idOk = Regex("[\\w-]{11}")

    private fun <T> net(block: suspend () -> T): T = runBlocking { block() }

    // ============================================================ N1 fácil
    @Test fun n01_busca_de_videos() {
        run(1, "YouTube", "buscar \"lofi\"") {
            val (page, ms) = timed { net { Y.search("lofi").getOrThrow() } }
            val bad = page.items.filterNot { idOk.matches(it.id) && it.title.isNotBlank() && it.thumbnail.startsWith("https://") }
            when {
                page.items.size < 10 -> fail("só ${page.items.size} vídeos")
                bad.isNotEmpty() -> fail("${bad.size} itens incompletos")
                else -> "${page.items.size} vídeos em ${ms.toInt()} ms"
            }
        }
        run(1, "YouTube", "vídeos têm duração e canal") {
            val page = net { Y.search("receita de bolo de cenoura").getOrThrow() }
            val withDur = page.items.count { (it.duration ?: 0) > 0 }
            val withChannel = page.items.count { it.artists.firstOrNull()?.id?.startsWith("UC") == true }
            if (withDur >= page.items.size * 0.8 && withChannel >= page.items.size * 0.8) "$withDur/${page.items.size} com duração, $withChannel com canal"
            else fail("duração $withDur, canal $withChannel de ${page.items.size}")
        }
    }

    // ============================================================ N2 canais
    @Test fun n02_canais() {
        var felipe: String? = null
        run(2, "Canais", "buscar canal \"Felipe Neto\"") {
            val (list, _) = net { Y.searchChannels("Felipe Neto").getOrThrow() }
            felipe = list.firstOrNull()?.id
            if (felipe?.startsWith("UC") == true && list.first().title.contains("Felipe", true)) "${list.first().title} ($felipe)"
            else fail("primeiro: ${list.firstOrNull()?.title}")
        }
        val id = felipe ?: "UCV306eHqgo0LvBf3Mh36AHg"
        var token: String? = null
        var firstIds = emptySet<String>()
        run(2, "Canais", "abrir o canal e ver os vídeos") {
            val (p, ms) = timed { net { Y.channel(id).getOrThrow() } }
            token = p.continuation
            firstIds = p.videos.map { it.id }.toSet()
            when {
                p.videos.size < 20 -> fail("só ${p.videos.size} vídeos")
                p.avatar.isNullOrBlank() -> fail("sem foto do canal")
                p.videos.any { it.artists.first().id != id } -> fail("vídeo com canal errado")
                else -> "${p.title}: ${p.videos.size} vídeos em ${ms.toInt()} ms"
            }
        }
        run(2, "Canais", "foto do canal em boa resolução") {
            val p = net { Y.channel(id).getOrThrow() }
            val size = Regex("=s(\\d+)").find(p.avatar.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            if (size == null || size >= 160) "ok (${size ?: "?"} px)" else fail("foto de $size px (fica borrada em 96 dp)")
        }
        run(2, "Canais", "\"Carregar mais vídeos\" traz vídeos novos") {
            val t = token ?: return@run fail("sem botão de carregar mais")
            val more = net { Y.channelContinuation(id, "Felipe Neto", t).getOrThrow() }
            val fresh = more.items.count { it.id !in firstIds }
            if (fresh >= 15) "$fresh vídeos novos" else fail("só $fresh novos")
        }
        var someVideo: String? = null
        run(2, "Canais", "abrir canal a partir de um vídeo") {
            val v = net { Y.search("felipe neto react").getOrThrow() }.items.first()
            someVideo = v.id
            val d = net { Y.video(v.id).getOrThrow() }
            val ch = d.channelId ?: return@run fail("vídeo sem canal")
            val p = net { Y.channel(ch).getOrThrow() }
            if (p.videos.isNotEmpty()) "${d.channelName} → ${p.videos.size} vídeos" else fail("canal vazio")
        }
        run(2, "Vídeos", "vídeos relacionados (assistir em seguida)") {
            val d = net { Y.video(someVideo ?: "jfKfPfyJRdk").getOrThrow() }
            when {
                d.title.isBlank() -> fail("sem título")
                d.related.size < 8 -> fail("só ${d.related.size} relacionados")
                d.related.any { it.id == d.videoId } -> fail("o próprio vídeo nos relacionados")
                else -> "${d.related.size} relacionados"
            }
        }
        for (h in listOf("@MrBeast", "@CazeTV", "https://www.youtube.com/@felipeneto", "youtube.com/channel/UCV306eHqgo0LvBf3Mh36AHg")) {
            run(2, "Canais", "abrir canal por $h") {
                val (list, _) = net { Y.searchChannels(h).getOrThrow() }
                val first = list.firstOrNull() ?: return@run fail("nada")
                val p = net { Y.channel(h).getOrThrow() }
                if (first.id == p.id && p.videos.isNotEmpty()) "${p.title} (${p.videos.size} vídeos)" else fail("busca=${first.title} canal=${p.title}")
            }
        }
        run(2, "Canais", "canal sem vídeos não mostra botão falso") {
            val p = net { Y.channel("UCDh8LnjLHFUSYrq9S6Zp12A").getOrThrow() }
            if (p.videos.isEmpty() && p.continuation == null) "vazio e sem \"carregar mais\"" else fail("${p.videos.size} vídeos, botão=${p.continuation != null}")
        }
    }

    // ============================================================ N5 buscas estranhas
    @Test fun n05_buscas_estranhas() {
        val queries = listOf("🔥🔥🔥", "\"aspas\" <b>tag</b> & %20", "東京 音楽", "xqzjvkwplmn", "a".repeat(1000), "música\n\tcom quebra", "-", "'; DROP TABLE videos;--")
        for (q in queries) {
            run(5, "YouTube", "buscar ${q.take(20).replace("\n", "⏎")}${if (q.length > 20) "…" else ""}") {
                val (r, ms) = timed { net { Y.search(q) } }
                r.exceptionOrNull()?.let { return@run fail("erro: $it") }
                if (ms > 15_000) fail("${ms.toInt()} ms") else "${r.getOrThrow().items.size} vídeos"
            }
        }
        run(5, "YouTube", "busca vazia não gasta internet") {
            val (r, ms) = timed { net { Y.search("   ") } }
            if (r.getOrThrow().items.isEmpty() && ms < 50) "ok" else fail("${ms.toInt()} ms")
        }
        run(5, "Vídeos", "vídeo que não existe") {
            val r = net { Y.video("aaaaaaaaaaa") }
            if (r.isFailure || r.getOrThrow().title.isEmpty()) "tratado sem travar" else fail("inventou: ${r.getOrThrow().title}")
        }
        run(5, "Canais", "canal que não existe") {
            val r = net { Y.channel("UCxxxxxxxxxxxxxxxxxxxxxx") }
            if (r.isFailure) "erro claro (tela mostra \"Tentar de novo\")" else fail("abriu canal falso \"${r.getOrThrow().title}\"")
        }
        run(5, "Canais", "@nome que não existe") {
            val r = net { Y.channel("@essecanalnaoexiste9182736455") }
            if (r.isFailure) "ok" else fail("abriu ${r.getOrThrow().title}")
        }
        run(5, "Vídeos", "vídeo de 10 horas: duração certa") {
            val items = net { Y.search("10 horas som de chuva para dormir").getOrThrow() }.items
            val long = items.maxOfOrNull { it.duration ?: 0 } ?: 0
            if (long >= 3 * 3600) "maior: ${long / 3600}h" else fail("maior duração ${long}s")
        }
        run(5, "Vídeos", "Shorts ficam de fora") {
            val items = net { Y.search("shorts engraçados").getOrThrow() }.items
            val shortish = items.count { (it.duration ?: 999) in 1..60 }
            if (shortish <= items.size / 3) "$shortish curtinhos de ${items.size}" else fail("$shortish de ${items.size} parecem Shorts")
        }
    }

    // ============================================================ N8 pesado
    @Test fun n08_pesado() {
        run(8, "Canais", "rolar 10 páginas de um canal") {
            val id = "UCX6OQ3DkcsbYNE6H8uQQuVA"
            val first = net { Y.channel(id).getOrThrow() }
            val all = first.videos.map { it.id }.toMutableList()
            var token = first.continuation
            var pages = 1
            var worst = 0.0
            while (token != null && pages < 10) {
                val (p, ms) = timed { net { Y.channelContinuation(id, first.title, token!!).getOrThrow() } }
                worst = maxOf(worst, ms)
                all += p.items.map { it.id }
                token = p.continuation
                pages++
            }
            val dup = all.size - all.toSet().size
            when {
                pages < 5 -> fail("parou na página $pages")
                dup > 0 -> fail("$dup vídeos repetidos")
                else -> budget("$pages páginas, ${all.size} vídeos, pior página", worst, 6000.0, "ms")
            }
        }
        run(8, "YouTube", "20 buscas ao mesmo tempo") {
            val qs = listOf("forró", "rock nacional", "asmr", "podcast", "minecraft", "sertanejo", "funk", "rap", "gospel", "lofi",
                "receita", "notícias", "futebol", "piada", "documentário", "aula de violão", "chuva", "pagode", "jazz", "k-pop")
            val (res, ms) = timed { net { kotlinx.coroutines.coroutineScope { qs.map { q -> async { Y.search(q) } }.awaitAll() } } }
            val ok = res.count { it.isSuccess && it.getOrThrow().items.isNotEmpty() }
            if (ok >= 18) budget("$ok/20 ok em", ms, 20000.0, "ms") else fail("só $ok/20 deram certo")
        }
    }

    // ============================================================ N9 respostas quebradas
    @Test fun n09_json_quebrado() {
        val text = runBlocking {
            HttpClient(OkHttp).use { c ->
                c.post("https://www.youtube.com/youtubei/v1/search?prettyPrint=false") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"context":{"client":{"clientName":"WEB","clientVersion":"2.20251020.01.00","hl":"pt","gl":"BR"}},"query":"música"}""")
                }.bodyAsText()
            }
        }
        val root = Json.parseToJsonElement(text)
        run(9, "YouTube", "ler resposta grande (${text.length / 1024} KB)") {
            Y.parseVideos(root)
            val (items, ms) = timed { repeat(5) { Y.parseVideos(root) }; Y.parseVideos(root) }
            budget("${items.size} vídeos, por leitura", ms / 6, 60.0, "ms")
        }
        run(9, "YouTube", "3.000 respostas corrompidas não travam o app") {
            val r = Random(3)
            var crashes = 0
            var first: String? = null
            repeat(3000) {
                val bad = mutate(root, r, 0)
                try {
                    Y.parseVideos(bad); Y.parseChannels(bad); Y.findContinuation(bad)
                } catch (t: Throwable) {
                    crashes++
                    if (first == null) first = t.toString()
                }
            }
            if (crashes == 0) "0 travamentos" else fail("$crashes travamentos; 1º: $first")
        }
    }

    private fun mutate(e: JsonElement, r: Random, depth: Int): JsonElement =
        when {
            depth > 40 -> e
            e is JsonObject && r.nextInt(25) == 0 -> JsonNull
            e is JsonObject -> JsonObject(
                e.filter { r.nextInt(30) != 0 }.mapValues { (_, v) -> mutate(v, r, depth + 1) },
            )
            e is JsonArray && r.nextInt(30) == 0 -> JsonPrimitive("lixo")
            e is JsonArray -> JsonArray(e.map { mutate(it, r, depth + 1) })
            e is JsonPrimitive && r.nextInt(20) == 0 -> listOf(JsonPrimitive(42), JsonNull, JsonObject(emptyMap()), JsonArray(emptyList()), JsonPrimitive("")).random(r)
            else -> e
        }

    // ============================================================ N10 internet ruim
    @Test fun n10_internet_ruim() {
        val original = Y.baseUrl
        try {
            run(10, "YouTube", "sem internet: erro rápido, sem travar") {
                Y.baseUrl = "http://127.0.0.1:9"
                val (r, ms) = timed { net { Y.search("teste") } }
                if (r.isFailure) budget("falhou em", ms, 3000.0, "ms") else fail("devolveu sucesso offline")
            }
            run(10, "YouTube", "internet que trava no meio (servidor lento)") {
                val server = ServerSocket(0)
                thread(isDaemon = true) {
                    runCatching {
                        while (true) {
                            val s = server.accept()
                            thread(isDaemon = true) {
                                runCatching {
                                    s.getInputStream().read(ByteArray(8192))
                                    val o = s.getOutputStream()
                                    o.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100000\r\n\r\n{".toByteArray())
                                    repeat(120) { o.write(' '.code); o.flush(); Thread.sleep(1000) }
                                }
                            }
                        }
                    }
                }
                Y.baseUrl = "http://127.0.0.1:${server.localPort}"
                val (r, ms) = timed { net { Y.channel("UCX6OQ3DkcsbYNE6H8uQQuVA") } }
                server.close()
                if (r.isFailure) budget("desistiu em", ms, 30_000.0, "ms") else fail("sucesso falso")
            }
            run(10, "YouTube", "resposta que não é JSON (página de erro)") {
                val server = ServerSocket(0)
                thread(isDaemon = true) {
                    runCatching {
                        val s = server.accept()
                        s.getInputStream().read(ByteArray(8192))
                        s.getOutputStream().write("HTTP/1.1 429 Too Many Requests\r\nContent-Type: text/html\r\nContent-Length: 20\r\nConnection: close\r\n\r\n<html>erro</html>   ".toByteArray())
                        s.close()
                    }
                }
                Y.baseUrl = "http://127.0.0.1:${server.localPort}"
                val r = net { Y.search("x") }
                server.close()
                if (r.isFailure) "erro tratado" else fail("aceitou HTML como vídeo")
            }
        } finally {
            Y.baseUrl = original
        }
    }
}
