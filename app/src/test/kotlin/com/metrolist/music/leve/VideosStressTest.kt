package com.metrolist.music.leve

import com.metrolist.music.leve.Placar.budget
import com.metrolist.music.leve.Placar.fail
import com.metrolist.music.leve.Placar.run
import com.metrolist.music.leve.Placar.timed
import com.metrolist.music.ui.screens.documentStartScript
import com.metrolist.music.ui.screens.formatSeconds
import com.metrolist.music.ui.screens.pageScript
import com.metrolist.music.ui.screens.playerHtml
import com.metrolist.music.utils.LeveYouTubeLinks
import org.junit.Test
import java.io.File

/** Vídeos: links, bloqueio de anúncios e os scripts que rodam dentro do YouTube. */
class VideosStressTest {
    @Test fun n01_links_de_video() {
        val cases = listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ" to "dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=42s&list=PL123" to "dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?si=abc" to "dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ" to "dQw4w9WgXcQ",
            "https://www.youtube.com/live/dQw4w9WgXcQ?feature=share" to "dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ" to "dQw4w9WgXcQ",
            "https://m.youtube.com/" to null,
            "https://m.youtube.com/results?search_query=watch?v=dQw4w9WgXcQ" to null,
            "https://www.youtube.com/watch?v=curto" to null,
            "https://evil.com/watch?v=dQw4w9WgXcQ" to null,
            "" to null,
        )
        for ((url, want) in cases) {
            run(1, "Vídeos", "id do vídeo: ${url.ifEmpty { "(vazio)" }.take(60)}") {
                val got = LeveYouTubeLinks.videoId(url)
                if (got == want) "ok" else fail("deu $got (esperado $want)")
            }
        }
        val channels = listOf(
            "https://m.youtube.com/@felipeneto" to "@felipeneto",
            "https://m.youtube.com/@felipeneto/videos" to "@felipeneto",
            "https://www.youtube.com/channel/UCV306eHqgo0LvBf3Mh36AHg" to "UCV306eHqgo0LvBf3Mh36AHg",
            "https://m.youtube.com/@Ca%C3%A7adores" to "@Caçadores",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ" to null,
        )
        for ((url, want) in channels) {
            run(2, "Botões", "botão Canal na página ${url.substringAfter(".com")}") {
                val got = LeveYouTubeLinks.channelRef(url)
                if (got == want) "ok" else fail("deu $got (esperado $want)")
            }
        }
        run(1, "Vídeos", "duração no formato certo") {
            val got = listOf(formatSeconds(59), formatSeconds(61), formatSeconds(3725), formatSeconds(36000), formatSeconds(0), formatSeconds(null))
            val want = listOf("0:59", "1:01", "1:02:05", "10:00:00", "", "")
            if (got == want) "ok" else fail(got.toString())
        }
    }

    @Test fun n03_bloqueio_de_anuncios() {
        val ads = listOf(
            "https://googleads.g.doubleclick.net/pagead/id", "https://www.youtube.com/pagead/adview?x=1",
            "https://pagead2.googlesyndication.com/x.js", "https://www.youtube.com/api/stats/ads?ver=2",
            "https://www.youtube.com/youtubei/v1/log_event?alt=json", "https://imasdk.googleapis.com/js/sdkloader/ima3.js",
            "https://www.youtube.com/generate_204?ad=1",
        )
        val ok = listOf(
            "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&id=o-x", "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            "https://m.youtube.com/youtubei/v1/player?key=x", "https://m.youtube.com/youtubei/v1/next",
            "https://m.youtube.com/results?search_query=doubleclick.net", "https://m.youtube.com/results?search_query=%2Fpagead%2F",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&ptracking=1", "https://www.youtube.com/generate_204?cpn=abc",
            "https://yt3.ggpht.com/abc=s88", "https://m.youtube.com/@adservice",
        )
        run(3, "Vídeos", "anúncios bloqueados (${ads.size} endereços)") {
            val missed = ads.filterNot { LeveYouTubeLinks.isAdUrl(it) }
            if (missed.isEmpty()) "todos bloqueados" else fail("passaram: $missed")
        }
        run(3, "Vídeos", "vídeo, fotos e buscas NÃO são bloqueados") {
            val wrong = ok.filter { LeveYouTubeLinks.isAdUrl(it) }
            if (wrong.isEmpty()) "${ok.size} liberados" else fail("bloqueou: $wrong")
        }
        run(7, "Vídeos", "checar 100 mil pedidos da página") {
            val urls = (ads + ok).let { l -> List(100_000) { l[it % l.size] } }
            val (_, ms) = timed { urls.forEach { LeveYouTubeLinks.isAdUrl(it) } }
            budget("por pedido", ms * 1000 / urls.size, 20.0, "µs")
        }
        run(10, "Vídeos", "links absurdos não travam") {
            val junk = listOf("", "://", "http://", "javascript:alert(1)", "data:text/html,<b>", "https://" + "a".repeat(100_000),
                "https://youtube.com/watch?v=" + "%".repeat(50), "https://user:pass@www.youtube.com:443/watch?v=dQw4w9WgXcQ", "\u0000", "https://youtube.com/@")
            junk.forEach { LeveYouTubeLinks.isAdUrl(it); LeveYouTubeLinks.videoId(it); LeveYouTubeLinks.channelRef(it) }
            val creds = LeveYouTubeLinks.videoId("https://user:pass@www.youtube.com:443/watch?v=dQw4w9WgXcQ")
            if (creds == "dQw4w9WgXcQ") "ok" else fail("porta/senha no link confundiu: $creds")
        }
    }

    private val node: String? = listOf("/opt/node22/bin/node", "/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }

    private fun jsOk(code: String): String? {
        val n = node ?: return null
        val f = File.createTempFile("leve", ".js")
        f.writeText(code)
        val p = ProcessBuilder(n, "--check", f.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        f.delete()
        return if (p.exitValue() == 0) null else out.lines().take(6).joinToString(" | ")
    }

    @Test fun n05_scripts_do_youtube_sem_erro() {
        run(5, "Vídeos", "script de início (tira anúncio, toca com tela apagada)") {
            if (node == null) return@run "node ausente: pulado"
            jsOk(documentStartScript)?.let { fail(it) } ?: "JavaScript válido"
        }
        val combos = listOf(
            listOf(1f, true, true, true, "hd720"), listOf(2f, false, false, false, ""), listOf(0.25f, true, false, true, "tiny"),
            listOf(1.75f, false, true, false, "hd1080"),
        )
        for (c in combos) {
            run(5, "Vídeos", "script da página: vel ${c[0]}, sponsor ${c[1]}, amoled ${c[2]}, sem shorts ${c[3]}, qualidade '${c[4]}'") {
                if (node == null) return@run "node ausente: pulado"
                val js = pageScript(c[0] as Float, c[1] as Boolean, c[2] as Boolean, c[3] as Boolean, c[4] as String)
                jsOk(js)?.let { fail(it) } ?: "JavaScript válido"
            }
        }
        run(5, "Vídeos", "player embutido (iframe) com ids normais e estranhos") {
            if (node == null) return@run "node ausente: pulado"
            val bad = listOf("dQw4w9WgXcQ", "a-b_c-d_e-f", "x';alert(1);'").mapNotNull { id ->
                val html = playerHtml(id)
                val script = html.substringAfter("<script>").substringBefore("</script>")
                jsOk(script)?.let { "$id: $it" }
            }
            if (bad.isEmpty()) "ok" else fail(bad.joinToString())
        }
        run(9, "Vídeos", "velocidade com vírgula (celular em português) não quebra o script") {
            if (node == null) return@run "node ausente: pulado"
            val old = java.util.Locale.getDefault()
            try {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag("pt-BR"))
                jsOk(pageScript(1.25f, true, true, true, "hd720"))?.let { fail(it) } ?: "ok"
            } finally {
                java.util.Locale.setDefault(old)
            }
        }
    }
}
