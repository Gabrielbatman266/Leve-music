package com.metrolist.music.leve

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.music.leve.Placar.fail
import com.metrolist.music.leve.Placar.run
import com.metrolist.music.utils.LeveYouTubeSearch
import com.metrolist.music.viewmodels.OnlineSearchViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * N11 — busca do app (tela de resultados) contra o YouTube de verdade.
 * Garante que nenhuma aba fica carregando para sempre e que conta com cookie ruim ainda busca.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BuscaStressTest {
    private val ctx: Application get() = ApplicationProvider.getApplicationContext()

    private fun vm(q: String) = OnlineSearchViewModel(ctx, SavedStateHandle(mapOf("query" to q)))

    /** Roda o looper principal até [done] ou estourar [maxMs]. */
    private fun waitFor(maxMs: Long = 45_000, done: () -> Boolean): Long {
        val t = System.currentTimeMillis()
        while (!done() && System.currentTimeMillis() - t < maxMs) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return System.currentTimeMillis() - t
    }

    private fun settled(v: OnlineSearchViewModel, f: YouTube.SearchFilter?): Boolean {
        val key = f?.value ?: OnlineSearchViewModel.SUMMARY_KEY
        if (v.errorKeys[key] != null) return true
        return if (f == null) v.summaryPage != null else v.viewStateMap[f.value] != null
    }

    private fun check(q: String, f: YouTube.SearchFilter?, label: String, needItems: Boolean = true): String {
        val v = vm(q)
        v.filter.value = f
        val ms = waitFor { settled(v, f) }
        val key = f?.value ?: OnlineSearchViewModel.SUMMARY_KEY
        v.errorKeys[key]?.let { return fail("$label deu erro: $it (${ms}ms)") }
        if (!settled(v, f)) return fail("$label ficou carregando ${ms}ms")
        val n = if (f == null) v.summaryPage?.summaries?.sumOf { it.items.size } ?: 0 else v.viewStateMap[f.value]?.items?.size ?: 0
        if (needItems && n == 0) return fail("$label sem resultados (${ms}ms)")
        return "$label: $n itens em ${ms}ms"
    }

    @Test fun n11_busca_do_app() {
        YouTube.locale = YouTubeLocale(gl = "BR", hl = "pt-BR")
        runBlocking { YouTube.visitorData = YouTube.visitorData().getOrNull() }

        run(11, "busca", "All") { check("still love you", null, "All") }
        run(11, "busca", "Songs (cai para resumo/vídeos se vazio)") { check("still love you", YouTube.SearchFilter.FILTER_SONG, "Songs") }
        run(11, "busca", "Songs artista BR") { check("anitta", YouTube.SearchFilter.FILTER_SONG, "Songs") }
        run(11, "busca", "Videos") { check("still love you", YouTube.SearchFilter.FILTER_VIDEO, "Videos") }
        run(11, "busca", "Todo o YouTube") { check("still love you", LeveYouTubeSearch.FILTER_ALL_YOUTUBE, "Todo o YouTube") }
        run(11, "busca", "Canais") { check("still love you", LeveYouTubeSearch.FILTER_YT_CHANNELS, "Canais") }
        run(11, "busca", "emoji e acento") { check("ação 🎵 coração", null, "All", needItems = false) }
        run(11, "busca", "texto gigante") { check("a".repeat(300), null, "All", needItems = false) }

        run(11, "busca", "trocar de aba rápido não trava") {
            val v = vm("lofi")
            listOf(YouTube.SearchFilter.FILTER_SONG, null, LeveYouTubeSearch.FILTER_ALL_YOUTUBE, YouTube.SearchFilter.FILTER_SONG)
                .forEach { v.filter.value = it; shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(80) }
            val ms = waitFor { settled(v, YouTube.SearchFilter.FILTER_SONG) }
            v.errorKeys[YouTube.SearchFilter.FILTER_SONG.value]?.let { fail("erro: $it") }
                ?: if (settled(v, YouTube.SearchFilter.FILTER_SONG)) "Songs ok em ${ms}ms" else fail("travou ${ms}ms")
        }

        // Conta com cookie vencido: a busca logada falha e deve cair para sem login.
        run(11, "busca", "cookie vencido ainda busca") {
            val old = YouTube.cookie
            try {
                YouTube.cookie = "SAPISID=invalido; __Secure-3PAPISID=invalido; SID=invalido; HSID=x; SSID=x"
                check("still love you", null, "All com cookie ruim")
            } finally {
                YouTube.cookie = old
            }
        }

        run(11, "busca", "Tentar de novo depois de erro") {
            val v = vm("still love you")
            v.filter.value = null
            waitFor { settled(v, null) }
            v.retry()
            val ms = waitFor { settled(v, null) }
            if (v.summaryPage != null) "ok em ${ms}ms" else fail("retry falhou: ${v.errorKeys}")
        }
    }
}
