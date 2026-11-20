package com.metrolist.music.leve

import com.metrolist.music.leve.Placar.fail
import com.metrolist.music.leve.Placar.run
import com.metrolist.music.utils.LeveUpdater
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread

/** Botão "Atualizar" do app: acha a versão nova e baixa o APK sem estragar nada. */
class AtualizacaoStressTest {
    private fun release(tag: String, apk: Boolean = true) =
        """{"tag_name":"$tag","body":"notas","assets":[${if (apk) """{"name":"LeveMusic.apk","browser_download_url":"https://x/$tag.apk"}""" else ""}]}"""

    @Test fun n02_achar_versao_nova() {
        run(2, "Atualização", "releases de verdade do GitHub") {
            val text = runCatching {
                (URL("https://api.github.com/repos/Gabrielbatman266/Leve-music/releases?per_page=30").openConnection())
                    .apply { setRequestProperty("User-Agent", "LeveMusic") }.getInputStream().bufferedReader().readText()
            }.getOrNull() ?: return@run "GitHub fora do ar: pulado"
            val u = LeveUpdater.newestFrom(text, 0) ?: return@run fail("não achou nenhuma versão")
            if (u.apkUrl.endsWith(".apk")) "mais nova: #${u.build}" else fail(u.apkUrl)
        }
        run(3, "Atualização", "ignora releases sem APK, tags estranhas e versão igual") {
            val text = "[" + listOf(
                release("leve-music-50"), release("leve-music-60", apk = false), release("v9999"), release("9999"),
                release("leve-music-abc"), """{"tag_name":null}""", "42",
            ).joinToString() + "]"
            val a = LeveUpdater.newestFrom(text, 10)?.build
            val b = LeveUpdater.newestFrom(text, 50)
            if (a == 50 && b == null) "ok" else fail("achou $a / $b")
        }
        run(10, "Atualização", "resposta do GitHub quebrada") {
            listOf("", "{}", "null", "[1,2,3]", "<html>rate limit</html>", "[{\"assets\":\"x\"}]").forEach { txt ->
                runCatching { LeveUpdater.newestFrom(txt, 0) }.onFailure {
                    if (txt.startsWith("<") || txt.isEmpty()) return@forEach // erro de leitura é tratado pelo check()
                    return@run fail("travou com '$txt': $it")
                }
            }
            "ok"
        }
    }

    /** Servidor de mentira: responde com [status], anuncia [declared] bytes e manda [body]. */
    private fun serve(status: Int, declared: Int, body: ByteArray): String {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    s.getInputStream().read(ByteArray(8192))
                    val o = s.getOutputStream()
                    o.write("HTTP/1.1 $status X\r\nContent-Length: $declared\r\nConnection: close\r\n\r\n".toByteArray())
                    o.write(body)
                    o.flush()
                }
            }
            server.close()
        }
        return "http://127.0.0.1:${server.localPort}/LeveMusic.apk"
    }

    @Test fun n08_download_do_apk() {
        val dir = kotlin.io.path.createTempDirectory("leve").toFile()
        val apk = ByteArray(300_000) { (it % 251).toByte() }.also { it[0] = 'P'.code.toByte(); it[1] = 'K'.code.toByte() }
        run(2, "Atualização", "download normal") {
            val f = File(dir, "ok.apk")
            var last = 0f
            LeveUpdater.downloadTo(serve(200, apk.size, apk), f) { last = it }
            if (f.length() == apk.size.toLong() && last == 1f) "APK completo (${f.length() / 1000} KB)" else fail("tamanho ${f.length()}, progresso $last")
        }
        run(8, "Atualização", "internet caiu no meio do download") {
            val f = File(dir, "cortado.apk")
            val r = runCatching { LeveUpdater.downloadTo(serve(200, apk.size, apk.copyOf(100_000)), f) {} }
            if (r.isFailure && !f.exists() && !File(f.path + ".part").exists()) "erro claro, nada de APK quebrado" else fail("ficou APK de ${f.length()} bytes")
        }
        run(8, "Atualização", "GitHub devolveu página de erro") {
            val f = File(dir, "erro.apk")
            val html = "<html>Not Found</html>".toByteArray()
            val r = runCatching { LeveUpdater.downloadTo(serve(404, html.size, html), f) {} }
            if (r.isFailure && !f.exists()) "ok" else fail("salvou o erro como APK")
        }
        run(9, "Atualização", "arquivo baixado não é APK") {
            val f = File(dir, "lixo.apk")
            val junk = "isto não é um apk".toByteArray()
            val r = runCatching { LeveUpdater.downloadTo(serve(200, junk.size, junk), f) {} }
            if (r.isFailure && !f.exists()) "ok" else fail("aceitou arquivo que não é APK")
        }
        dir.deleteRecursively()
    }
}
